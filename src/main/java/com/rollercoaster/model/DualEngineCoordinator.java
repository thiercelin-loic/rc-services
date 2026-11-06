package com.rollercoaster.model;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rollercoaster.config.RedisCacheAndPubSubConfig;
import com.rollercoaster.rax.ast.CompilationPlan;
import com.rollercoaster.rax.ast.SqlDialect;
import com.rollercoaster.rax.compiler.InfoFunctionEvaluator;
import com.rollercoaster.rax.compiler.RaxFormulaCompiler;
import com.rollercoaster.rax.context.FilterContext;
import com.rollercoaster.security.DynamicDataMaskingService;
import com.rollercoaster.security.RlsSecurityContextResolver;
import com.rollercoaster.security.SecurityPredicateContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Dual-Engine Cost-Based Query Coordinator:
 * 1. Checks Redis L1 Cache under key: cache:query:{tenant_id}:{rls_hash}:{context_hash}:{measure_id} (TTL 300s).
 * 2. On cache miss, compiles RAX AST, resolves Row-Level Security (RLS) predicates, applies Dynamic Data Masking,
 *    and routes execution based on cost:
 *    - Operational row lookups or tables < 1,000,000 rows -> MySQL relational pushdown.
 *    - Multidimensional aggregations across >= 1,000,000 rows or iterative scans (SUMX, RANKX) -> Embedded DuckDB JNI.
 * 3. Stores result in Redis L1 cache (TTL 300s) and broadcasts STOMP visual data batch.
 */
@Service
public class DualEngineCoordinator {

    private final RaxFormulaCompiler raxFormulaCompiler;
    private final DuckDbConnectionManager duckDbConnectionManager;
    private final SemanticModelManager semanticModelManager;
    private final RlsSecurityContextResolver rlsResolver;
    private final DynamicDataMaskingService dataMaskingService;
    private final InfoFunctionEvaluator infoFunctionEvaluator;
    private final JdbcTemplate jdbcTemplate;
    private final StringRedisTemplate redisTemplate;
    private final SimpMessagingTemplate messagingTemplate;
    private final long columnarRowThreshold;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final Map<String, List<Map<String, Object>>> localL1CacheFallback = new ConcurrentHashMap<>();

    public DualEngineCoordinator(
            RaxFormulaCompiler raxFormulaCompiler,
            DuckDbConnectionManager duckDbConnectionManager,
            SemanticModelManager semanticModelManager,
            RlsSecurityContextResolver rlsResolver,
            DynamicDataMaskingService dataMaskingService,
            @Autowired(required = false) JdbcTemplate jdbcTemplate,
            @Autowired(required = false) StringRedisTemplate redisTemplate,
            @Autowired(required = false) SimpMessagingTemplate messagingTemplate,
            @Value("${rollercoaster.duckdb.row-threshold-for-columnar:1000000}") long columnarRowThreshold) {
        this.raxFormulaCompiler = raxFormulaCompiler;
        this.duckDbConnectionManager = duckDbConnectionManager;
        this.semanticModelManager = semanticModelManager;
        this.rlsResolver = rlsResolver;
        this.dataMaskingService = dataMaskingService;
        this.infoFunctionEvaluator = new InfoFunctionEvaluator();
        this.jdbcTemplate = jdbcTemplate;
        this.redisTemplate = redisTemplate;
        this.messagingTemplate = messagingTemplate;
        this.columnarRowThreshold = columnarRowThreshold;
    }

    public int ingestDatasetRows(String tenantId, String tableName, List<Map<String, Object>> rows) {
        int count = duckDbConnectionManager.ingestTableRows(tableName, rows);
        if (tenantId != null && !tenantId.isBlank()) {
            invalidateTenantCache(tenantId);
        }
        return count;
    }

    public QueryExecutionResult executeVisualQuery(
            VisualQueryDefinition query,
            Authentication authentication) {
        long startNanos = System.nanoTime();
        SemanticModelSchema model = semanticModelManager.getModel(query.modelId());

        if (query.raxExpression() != null && !query.raxExpression().isBlank()) {
            String rawTrimmed = query.raxExpression().trim();
            String rawUpper = rawTrimmed.toUpperCase();
            if (infoFunctionEvaluator.isInfoQuery(rawTrimmed)) {
                List<Map<String, Object>> infoRows = infoFunctionEvaluator.evaluateInfoQuery(rawTrimmed, model);
                long elapsedMs = Math.max(1L, (System.nanoTime() - startNanos) / 1_000_000L);
                return new QueryExecutionResult(
                        "meta-info-query",
                        false,
                        SqlDialect.DUCKDB,
                        elapsedMs,
                        "-- Semantic Catalog Introspection (INFO.*)\nSELECT * FROM information_schema.rax_catalog;",
                        infoRows);
            }
            if (rawUpper.startsWith("CREATEORREPLACE") || rawUpper.startsWith("TABLE ") || rawUpper.startsWith("MODEL ")) {
                SemanticModelSchema updatedModel = semanticModelManager.applyTmdlScript(model.modelId(), rawTrimmed);
                long elapsedMs = Math.max(1L, (System.nanoTime() - startNanos) / 1_000_000L);
                return new QueryExecutionResult(
                        "tmdl-ddl-commit",
                        false,
                        SqlDialect.DUCKDB,
                        elapsedMs,
                        "-- TMDL DDL Metadata Transaction\nCOMMIT SEMANTIC CATALOG OBJECTS;",
                        List.of(Map.of(
                                "Operation", "createOrReplace",
                                "Target", updatedModel.name(),
                                "MeasuresCount", updatedModel.measures() != null ? updatedModel.measures().size() : 0,
                                "Status", "APPLIED")));
            }
        }

        // Resolve RLS context & build FilterContext
        SecurityPredicateContext rlsContext = rlsResolver.resolveUserPredicates(model.modelId(), authentication);
        FilterContext filterContext = buildFilterContext(query);
        String raxExpr = resolveRaxExpression(query, model);
        String cacheKey = buildL1CacheKey(
                query.tenantId(),
                rlsContext.rlsHash(),
                filterContext.computeContextHash(),
                query.measureId() != null ? query.measureId() : "adhoc",
                raxExpr,
                query.groupByColumns());

        // 1. Sub-millisecond Redis L1 Cache Lookup
        List<Map<String, Object>> cachedRows = lookupL1Cache(cacheKey);
        if (cachedRows != null) {
            long elapsedMs = Math.max(0L, (System.nanoTime() - startNanos) / 1_000_000L);
            QueryExecutionResult hitResult = new QueryExecutionResult(
                    cacheKey, true, SqlDialect.DUCKDB, elapsedMs, "/* L1 REDIS CACHE HIT */", cachedRows);
            broadcastVisualBatch(query, hitResult);
            return hitResult;
        }

        // 2. Empty RLS selection set returns structured zero-value matrix without throwing exceptions
        if (rlsContext.emptySelectionSet()) {
            List<Map<String, Object>> zeroMatrix = List.of(Map.of("measure_value", 0.0));
            long elapsedMs = Math.max(1L, (System.nanoTime() - startNanos) / 1_000_000L);
            return new QueryExecutionResult(
                    cacheKey, false, SqlDialect.DUCKDB, elapsedMs, "/* RLS EMPTY SELECTION ZERO MATRIX */", zeroMatrix);
        }

        // 3. Cost-based routing decision (MySQL Pushdown vs Embedded DuckDB Columnar)
        CompilationPlan probePlan = raxFormulaCompiler.compile(
                raxExpr,
                SqlDialect.DUCKDB,
                model.fiscalYearStartMonth(),
                filterContext,
                null,
                rlsContext.userClaims());

        SqlDialect targetEngine = selectTargetEngine(query, model, probePlan);
        CompilationPlan finalPlan = targetEngine == SqlDialect.DUCKDB
                ? probePlan
                : raxFormulaCompiler.compile(
                        raxExpr,
                        SqlDialect.MYSQL,
                        model.fiscalYearStartMonth(),
                        filterContext,
                        null,
                        rlsContext.userClaims());

        String executableSql = applyTableOverride(finalPlan.fullSelectQuery(), query, finalPlan);
        executableSql = rlsResolver.injectPredicates(executableSql, rlsContext);
        executableSql = semanticModelManager.injectScdType2BoundaryFilter(
                executableSql, model, query.evaluationDate());
        executableSql = applyGroupByProjection(executableSql, query);

        // 4. Synchronize schema tables in DuckDB & execute against selected engine
        duckDbConnectionManager.synchronizeModelTables(model);
        List<Map<String, Object>> resultRows = dispatchQuery(targetEngine, executableSql);
        boolean isPrivileged = rlsContext.authorities().contains("PERM_SYSTEM_ADMIN");
        List<Map<String, Object>> maskedRows = dataMaskingService.maskQueryResultRows(resultRows, model, isPrivileged);

        // 5. Populate Redis L1 Cache with 300s TTL
        storeInL1Cache(cacheKey, maskedRows);

        long elapsedMs = Math.max(1L, (System.nanoTime() - startNanos) / 1_000_000L);
        QueryExecutionResult missResult = new QueryExecutionResult(
                cacheKey, false, targetEngine, elapsedMs, executableSql, maskedRows);
        broadcastVisualBatch(query, missResult);
        return missResult;
    }

    /**
     * Cost-based routing algorithm:
     * Routes to Embedded DuckDB when:
     * - The query performs iterative scans (SUMX, AVERAGEX, RANKX), OR
     * - The target fact table has >= 1,000,000 rows and the query is not an operational lookup.
     * Routes to MySQL relational pushdown when:
     * - The query is an operational lookup or targets < 1,000,000 rows.
     */
    public SqlDialect selectTargetEngine(
            VisualQueryDefinition query,
            SemanticModelSchema model,
            CompilationPlan compiledPlan) {
        if (query.operationalLookup()) {
            return SqlDialect.MYSQL;
        }
        if (compiledPlan != null && compiledPlan.usesIteratorScan()) {
            return SqlDialect.DUCKDB;
        }
        long rows = query.estimatedRows();
        if (rows <= 0 && model != null) {
            rows = model.tables().stream()
                    .filter(t -> t.name().equalsIgnoreCase(query.factTableName()))
                    .mapToLong(t -> t.estimatedRows())
                    .findFirst()
                    .orElse(12_800_000L);
        }
        return rows >= columnarRowThreshold ? SqlDialect.DUCKDB : SqlDialect.MYSQL;
    }

    public static String buildL1CacheKey(
            String tenantId,
            String rlsHash,
            String contextHash,
            String measureId) {
        return buildL1CacheKey(tenantId, rlsHash, contextHash, measureId, null, java.util.Collections.emptyList());
    }

    public static String buildL1CacheKey(
            String tenantId,
            String rlsHash,
            String contextHash,
            String measureId,
            String raxExpression,
            List<String> groupByColumns) {
        String exprToken = (raxExpression != null && !raxExpression.isBlank())
                ? Integer.toHexString(raxExpression.trim().hashCode())
                : (measureId != null ? measureId : "adhoc");
        String groupToken = (groupByColumns != null && !groupByColumns.isEmpty())
                ? Integer.toHexString(String.join(",", groupByColumns).hashCode())
                : "nogroup";
        return "cache:query:" + tenantId + ":" + rlsHash + ":" + contextHash + ":" + exprToken + ":" + groupToken;
    }

    public void invalidateTenantCache(String tenantId) {
        localL1CacheFallback.keySet().removeIf(k -> k.startsWith("cache:query:" + tenantId + ":"));
        if (redisTemplate != null) {
            try {
                Set<String> keys = redisTemplate.keys("cache:query:" + tenantId + ":*");
                if (keys != null && !keys.isEmpty()) {
                    redisTemplate.delete(keys);
                }
            } catch (Exception ignored) {
            }
        }
    }

    private FilterContext buildFilterContext(VisualQueryDefinition query) {
        FilterContext ctx = new FilterContext();
        for (String gCol : query.groupByColumns()) {
            ctx = ctx.withInScopeColumn(gCol);
        }
        FilterContext mutable = ctx.cloneContext();
        for (Map.Entry<String, Set<Object>> entry : query.externalSlicerFilters().entrySet()) {
            mutable = mutable.withExternalSlicer(entry.getKey(), entry.getValue());
        }
        for (Map.Entry<String, Set<Object>> entry : query.activeFilters().entrySet()) {
            mutable.replaceFilter(entry.getKey(), entry.getValue());
        }
        return mutable;
    }

    private String resolveRaxExpression(VisualQueryDefinition query, SemanticModelSchema model) {
        if (query.raxExpression() != null && !query.raxExpression().isBlank()) {
            String expression = query.raxExpression().trim();
            if (expression.equalsIgnoreCase("COUNTROWS()")) {
                return "COUNTROWS(" + resolvePrimaryTableName(query, model) + ")";
            }
            return expression;
        }
        if (query.measureId() != null && !query.measureId().isBlank()) {
            if (model != null) {
                for (MeasureDefinition m : model.measures()) {
                    if (m.id().equalsIgnoreCase(query.measureId()) || m.name().equalsIgnoreCase(query.measureId())) {
                        return m.raxExpression();
                    }
                }
            }
            String[] tableAndCol = extractTableAndColumn(query.measureId(), query, model);
            if (tableAndCol != null) {
                return "SUM(" + tableAndCol[0] + "[" + tableAndCol[1] + "])";
            }
        }
        if (query.groupByColumns() != null && !query.groupByColumns().isEmpty()) {
            String fallbackTable = resolvePrimaryTableName(query, model);
            return "COUNTROWS(" + fallbackTable + ")";
        }
        throw new IllegalArgumentException("No measure or RAX expression is configured for the visual query.");
    }

    private String[] extractTableAndColumn(String rawField, VisualQueryDefinition query, SemanticModelSchema model) {
        String trimmed = rawField.trim();
        int bracketIdx = trimmed.indexOf('[');
        if (bracketIdx > 0 && trimmed.endsWith("]")) {
            String tbl = DuckDbConnectionManager.sanitizeIdentifier(trimmed.substring(0, bracketIdx));
            String col = DuckDbConnectionManager.sanitizeIdentifier(trimmed.substring(bracketIdx + 1, trimmed.length() - 1));
            if (!tbl.isEmpty() && !col.isEmpty()) {
                return new String[]{tbl, col};
            }
        }
        int dotIdx = trimmed.indexOf('.');
        if (dotIdx > 0 && dotIdx < trimmed.length() - 1) {
            String tbl = DuckDbConnectionManager.sanitizeIdentifier(trimmed.substring(0, dotIdx));
            String col = DuckDbConnectionManager.sanitizeIdentifier(trimmed.substring(dotIdx + 1));
            if (!tbl.isEmpty() && !col.isEmpty()) {
                return new String[]{tbl, col};
            }
        }
        String col = DuckDbConnectionManager.sanitizeIdentifier(trimmed);
        if (!col.isEmpty()) {
            return new String[]{resolvePrimaryTableName(query, model), col};
        }
        return null;
    }

    private String resolvePrimaryTableName(VisualQueryDefinition query, SemanticModelSchema model) {
        if (query.factTableName() != null && !query.factTableName().isBlank()) {
            String sanitized = DuckDbConnectionManager.sanitizeIdentifier(query.factTableName());
            if (!sanitized.isEmpty()) {
                return sanitized;
            }
        }
        if (query.groupByColumns() != null) {
            for (String gCol : query.groupByColumns()) {
                int dotIdx = gCol.indexOf('.');
                if (dotIdx > 0) {
                    String sanitized = DuckDbConnectionManager.sanitizeIdentifier(gCol.substring(0, dotIdx));
                    if (!sanitized.isEmpty()) {
                        return sanitized;
                    }
                }
                int bracketIdx = gCol.indexOf('[');
                if (bracketIdx > 0) {
                    String sanitized = DuckDbConnectionManager.sanitizeIdentifier(gCol.substring(0, bracketIdx));
                    if (!sanitized.isEmpty()) {
                        return sanitized;
                    }
                }
            }
        }
        if (model != null && model.tables() != null && !model.tables().isEmpty()) {
            String sanitized = DuckDbConnectionManager.sanitizeIdentifier(model.tables().get(0).name());
            if (!sanitized.isEmpty()) {
                return sanitized;
            }
        }
        return "fact_store_sales";
    }

    private String applyTableOverride(String baseSql, VisualQueryDefinition query, CompilationPlan plan) {
        if (baseSql == null) {
            return null;
        }
        if ((plan == null || plan.referencedTables().isEmpty())
                && query.factTableName() != null
                && !query.factTableName().isBlank()) {
            String safeTable = DuckDbConnectionManager.sanitizeIdentifier(query.factTableName());
            if (!safeTable.isEmpty()) {
                return baseSql.replace(" FROM fact_store_sales ", " FROM " + safeTable + " ");
            }
        }
        return baseSql;
    }

    private String applyGroupByProjection(String baseSql, VisualQueryDefinition query) {
        if (baseSql == null || query.groupByColumns() == null || query.groupByColumns().isEmpty()) {
            return baseSql;
        }
        List<String> selectProjections = new ArrayList<>();
        List<String> groupExpressions = new ArrayList<>();

        for (String rawCol : query.groupByColumns()) {
            if (rawCol == null || rawCol.isBlank()) {
                continue;
            }
            String trimmed = rawCol.trim();
            String sqlRef;
            String alias;
            int bracketIdx = trimmed.indexOf('[');
            int dotIdx = trimmed.indexOf('.');
            if (bracketIdx > 0 && trimmed.endsWith("]")) {
                String tbl = DuckDbConnectionManager.sanitizeIdentifier(trimmed.substring(0, bracketIdx));
                String col = DuckDbConnectionManager.sanitizeIdentifier(trimmed.substring(bracketIdx + 1, trimmed.length() - 1));
                sqlRef = tbl + "." + col;
                alias = col;
            } else if (dotIdx > 0 && dotIdx < trimmed.length() - 1) {
                String tbl = DuckDbConnectionManager.sanitizeIdentifier(trimmed.substring(0, dotIdx));
                String col = DuckDbConnectionManager.sanitizeIdentifier(trimmed.substring(dotIdx + 1));
                sqlRef = tbl + "." + col;
                alias = col;
            } else {
                String col = DuckDbConnectionManager.sanitizeIdentifier(trimmed);
                sqlRef = col;
                alias = col;
            }
            if (!alias.isEmpty()) {
                selectProjections.add(sqlRef + " AS " + alias);
                groupExpressions.add(sqlRef);
            }
        }

        if (selectProjections.isEmpty()) {
            return baseSql;
        }

        String updatedSql = baseSql;
        if (updatedSql.regionMatches(true, 0, "SELECT ", 0, 7)) {
            updatedSql = "SELECT " + String.join(", ", selectProjections) + ", " + updatedSql.substring(7);
        }
        return updatedSql + " GROUP BY " + String.join(", ", groupExpressions);
    }

    private List<Map<String, Object>> dispatchQuery(SqlDialect targetEngine, String sql) {
        if (targetEngine == SqlDialect.MYSQL && jdbcTemplate != null) {
            try {
                return jdbcTemplate.queryForList(sql);
            } catch (Exception ignored) {
                // Fall back to in-process DuckDB when running without live MySQL container
            }
        }
        try {
            return duckDbConnectionManager.executeColumnarQuery(sql);
        } catch (Exception e) {
            throw new IllegalStateException("Query execution failed on " + targetEngine + ": " + e.getMessage(), e);
        }
    }

    private List<Map<String, Object>> lookupL1Cache(String cacheKey) {
        if (redisTemplate != null) {
            try {
                String json = redisTemplate.opsForValue().get(cacheKey);
                if (json != null && !json.isBlank()) {
                    return objectMapper.readValue(json, new TypeReference<List<Map<String, Object>>>() {});
                }
            } catch (Exception ignored) {
            }
        }
        return localL1CacheFallback.get(cacheKey);
    }

    private void storeInL1Cache(String cacheKey, List<Map<String, Object>> rows) {
        localL1CacheFallback.put(cacheKey, rows);
        if (redisTemplate != null) {
            try {
                String payload = objectMapper.writeValueAsString(rows);
                redisTemplate.opsForValue().set(cacheKey, payload, RedisCacheAndPubSubConfig.QUERY_CACHE_TTL);
            } catch (Exception ignored) {
            }
        }
    }

    private void broadcastVisualBatch(VisualQueryDefinition query, QueryExecutionResult result) {
        if (messagingTemplate != null && query.reportId() != null) {
            messagingTemplate.convertAndSend(
                    "/topic/visuals/" + query.tenantId() + "/" + query.reportId(),
                    result);
        }
    }
}
