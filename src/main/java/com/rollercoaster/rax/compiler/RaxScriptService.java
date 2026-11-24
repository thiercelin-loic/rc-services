package com.rollercoaster.rax.compiler;

import com.rollercoaster.model.DualEngineCoordinator;
import com.rollercoaster.model.MeasureDefinition;
import com.rollercoaster.model.QueryExecutionResult;
import com.rollercoaster.model.SemanticModelManager;
import com.rollercoaster.model.SemanticModelSchema;
import com.rollercoaster.model.TableDefinition;
import com.rollercoaster.model.VisualQueryDefinition;
import com.rollercoaster.rax.ast.CompilationPlan;
import com.rollercoaster.rax.ast.SqlDialect;
import com.rollercoaster.rax.context.FilterContext;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * HTTP-agnostic domain service executing multi-statement RAX scripts ({@code DEFINE MEASURE ... EVALUATE ...}),
 * routing {@code INFO.*} introspection functions through {@link InfoFunctionEvaluator},
 * compiling and executing {@code EVALUATE} queries (including {@code SELECTCOLUMNS}, {@code SUMMARIZE},
 * {@code SUMMARIZECOLUMNS}, {@code ROW}, and {@code ORDER BY}) through {@link RaxFormulaCompiler}
 * and {@link DualEngineCoordinator}, and extracting/persisting measure definitions into {@link SemanticModelManager}.
 */
@Service
public class RaxScriptService {

    private static final Pattern DEFINE_MEASURE_PATTERN = Pattern.compile(
            "(?is)\\bMEASURE\\s+(?:(?:'([^']+)'|([\\p{L}_][\\p{L}\\p{Nd}_]*))\\s*)?\\[([^\\]]+)\\]\\s*=\\s*(.+?)(?=\\bMEASURE\\b|\\bEVALUATE\\b|$)");
    private static final Pattern EVALUATE_KEYWORD_PATTERN = Pattern.compile("(?i)\\bEVALUATE\\b");
    private static final Pattern ORDER_BY_CLAUSE_PATTERN = Pattern.compile("(?is)^(.*?)\\bORDER\\s+BY\\s+(.+)$");
    private static final Pattern ORDER_SPEC_ITEM_PATTERN = Pattern.compile(
            "(?is)^(?:(?:'([^']+)'|([\\p{L}_][\\p{L}\\p{Nd}_]*))\\s*)?(?:\\[([^\\]]+)\\]|\"([^\"]+)\"|([\\p{L}_][\\p{L}\\p{Nd}_.]*))(?:\\s+(ASC|DESC))?$");
    private static final Pattern QUALIFIED_COL_ONLY_PATTERN = Pattern.compile(
            "^\\s*(?:'([^']+)'|([\\p{L}_][\\p{L}\\p{Nd}_]*))\\[([^\\]]+)\\]\\s*$");
    private static final Pattern UNQUALIFIED_BRACKET_ONLY_PATTERN = Pattern.compile(
            "^\\s*\\[([^\\]]+)\\]\\s*$");

    private final SemanticModelManager semanticModelManager;
    private final DualEngineCoordinator dualEngineCoordinator;
    private final RaxFormulaCompiler raxFormulaCompiler;
    private final InfoFunctionEvaluator infoFunctionEvaluator;

    public record ScriptExecutionRequest(
            UUID modelId,
            String script,
            SqlDialect dialect
    ) {
        public ScriptExecutionRequest {
            if (script == null || script.isBlank()) {
                throw new IllegalArgumentException("RAX script must not be null or blank.");
            }
            dialect = dialect != null ? dialect : SqlDialect.DUCKDB;
        }
    }

    public record ApplyMeasuresResult(
            int appliedCount,
            List<MeasureDefinition> measures
    ) {
        public ApplyMeasuresResult {
            measures = measures != null ? List.copyOf(measures) : List.of();
        }
    }

    private record ParsedOrderSpec(String columnOrAlias, boolean descending) {}

    private record ProjectionColumn(String alias, String expression) {}

    private record ParsedEvaluateStatement(
            String rawEvaluateBody,
            String coreExpression,
            List<ParsedOrderSpec> orderSpecs
    ) {}

    public RaxScriptService(
            SemanticModelManager semanticModelManager,
            DualEngineCoordinator dualEngineCoordinator,
            RaxFormulaCompiler raxFormulaCompiler,
            InfoFunctionEvaluator infoFunctionEvaluator) {
        this.semanticModelManager = Objects.requireNonNull(
                semanticModelManager, "semanticModelManager must not be null");
        this.dualEngineCoordinator = Objects.requireNonNull(
                dualEngineCoordinator, "dualEngineCoordinator must not be null");
        this.raxFormulaCompiler = Objects.requireNonNull(
                raxFormulaCompiler, "raxFormulaCompiler must not be null");
        this.infoFunctionEvaluator = Objects.requireNonNull(
                infoFunctionEvaluator, "infoFunctionEvaluator must not be null");
    }

    public QueryExecutionResult executeScript(ScriptExecutionRequest request) {
        return executeScript(request, null);
    }

    public QueryExecutionResult executeScript(ScriptExecutionRequest request, Authentication authentication) {
        Objects.requireNonNull(request, "request must not be null");
        long startNanos = System.nanoTime();
        SemanticModelSchema schema = semanticModelManager.getModel(request.modelId());
        String strippedScript = stripComments(request.script()).trim();
        if (strippedScript.isEmpty()) {
            throw new IllegalArgumentException("RAX script cannot be empty.");
        }

        if (isTmdlScript(strippedScript)) {
            return dualEngineCoordinator.executeVisualQuery(
                    buildAdhocVisualQuery(schema, strippedScript, List.of(), request.dialect()),
                    authentication);
        }

        Map<String, MeasureDefinition> scriptMeasures = parseDefinedMeasures(strippedScript, schema);
        SemanticModelSchema effectiveSchema = mergeTemporaryMeasures(schema, scriptMeasures);
        ParsedEvaluateStatement evaluateStmt = extractEvaluateStatement(strippedScript, scriptMeasures);

        if (infoFunctionEvaluator.isInfoQuery(evaluateStmt.coreExpression())) {
            return evaluateInfoScript(evaluateStmt, effectiveSchema, request.dialect(), startNanos);
        }

        String inlinedCore = inlineMeasures(evaluateStmt.coreExpression(), effectiveSchema);
        QueryExecutionResult baseResult = evaluateCoreExpression(
                inlinedCore,
                effectiveSchema,
                request.dialect(),
                authentication);

        List<Map<String, Object>> orderedRows = applyOrderSpecs(baseResult.rows(), evaluateStmt.orderSpecs());
        long elapsedMs = Math.max(1L, (System.nanoTime() - startNanos) / 1_000_000L);
        return new QueryExecutionResult(
                baseResult.cacheKey(),
                baseResult.cacheHit(),
                baseResult.routedEngine(),
                elapsedMs,
                baseResult.executedSql(),
                orderedRows);
    }

    public ApplyMeasuresResult extractAndSaveMeasuresFromScript(UUID modelId, String script) {
        if (script == null || script.isBlank()) {
            throw new IllegalArgumentException("RAX script must not be null or blank.");
        }
        SemanticModelSchema current = semanticModelManager.getModel(modelId);
        String stripped = stripComments(script).trim();

        if (isTmdlScript(stripped)) {
            int beforeCount = current.measures().size();
            SemanticModelSchema updated = semanticModelManager.applyTmdlScript(current.modelId(), stripped);
            int applied = Math.max(1, updated.measures().size() - beforeCount);
            return new ApplyMeasuresResult(applied, updated.measures());
        }

        Map<String, MeasureDefinition> extracted = parseDefinedMeasures(stripped, current);
        if (extracted.isEmpty()) {
            throw new IllegalArgumentException("No DEFINE MEASURE definitions or measure assignments found in script.");
        }

        Map<String, MeasureDefinition> merged = new LinkedHashMap<>();
        for (MeasureDefinition existing : current.measures()) {
            merged.put(existing.name().toLowerCase(Locale.ROOT), existing);
        }
        for (MeasureDefinition defined : extracted.values()) {
            merged.put(defined.name().toLowerCase(Locale.ROOT), defined);
        }

        SemanticModelSchema updatedSchema = new SemanticModelSchema(
                current.modelId(),
                current.name(),
                current.fiscalYearStartMonth(),
                current.tables(),
                current.relationships(),
                new ArrayList<>(merged.values()),
                current.rlsRolePredicates(),
                current.discourageImplicitMeasures());
        semanticModelManager.registerModel(updatedSchema);

        return new ApplyMeasuresResult(extracted.size(), updatedSchema.measures());
    }

    private QueryExecutionResult evaluateInfoScript(
            ParsedEvaluateStatement evaluateStmt,
            SemanticModelSchema schema,
            SqlDialect dialect,
            long startNanos) {
        String core = evaluateStmt.coreExpression();
        List<Map<String, Object>> infoRows = infoFunctionEvaluator.evaluateInfoQuery(core, schema);
        List<Map<String, Object>> projected = applySelectColumnsProjectionIfPresent(core, infoRows, schema);
        List<Map<String, Object>> ordered = applyOrderSpecs(projected, evaluateStmt.orderSpecs());
        long elapsedMs = Math.max(1L, (System.nanoTime() - startNanos) / 1_000_000L);
        return new QueryExecutionResult(
                "meta-info-query",
                false,
                dialect != null ? dialect : SqlDialect.DUCKDB,
                elapsedMs,
                "-- Semantic Catalog Introspection (INFO.*)\nSELECT * FROM information_schema.rax_catalog;",
                ordered);
    }

    private QueryExecutionResult evaluateCoreExpression(
            String coreExpression,
            SemanticModelSchema schema,
            SqlDialect dialect,
            Authentication authentication) {
        String trimmed = coreExpression.trim();
        String upper = trimmed.toUpperCase(Locale.ROOT);

        if (upper.startsWith("SELECTCOLUMNS(") && trimmed.endsWith(")")) {
            return evaluateSelectColumnsExpression(trimmed, schema, dialect, authentication);
        }
        if (upper.startsWith("SUMMARIZE(") && trimmed.endsWith(")")) {
            return evaluateSummarizeExpression(trimmed, false, schema, dialect, authentication);
        }
        if (upper.startsWith("SUMMARIZECOLUMNS(") && trimmed.endsWith(")")) {
            return evaluateSummarizeExpression(trimmed, true, schema, dialect, authentication);
        }
        if (upper.startsWith("ROW(") && trimmed.endsWith(")")) {
            return evaluateRowExpression(trimmed, schema, dialect, authentication);
        }

        TableDefinition directTable = findTableByName(schema, unquoteSingle(trimmed));
        if (directTable != null) {
            List<Map<String, Object>> tableRows = dualEngineCoordinator.getTableRows(directTable.name());
            return new QueryExecutionResult(
                    "table-scan-" + directTable.name().toLowerCase(Locale.ROOT),
                    false,
                    dialect != null ? dialect : SqlDialect.DUCKDB,
                    1L,
                    "SELECT * FROM " + directTable.name() + " LIMIT 10000",
                    tableRows);
        }

        CompilationPlan compiledPlan = raxFormulaCompiler.compile(
                trimmed,
                dialect,
                schema.fiscalYearStartMonth(),
                new FilterContext(),
                null,
                Map.of());
        String primaryTable = !compiledPlan.referencedTables().isEmpty()
                ? compiledPlan.referencedTables().getFirst()
                : resolveFallbackTableName(schema);

        VisualQueryDefinition visualQuery = buildAdhocVisualQuery(schema, trimmed, List.of(), dialect, primaryTable);
        return dualEngineCoordinator.executeVisualQuery(visualQuery, authentication);
    }

    private QueryExecutionResult evaluateSelectColumnsExpression(
            String selectColumnsExpr,
            SemanticModelSchema schema,
            SqlDialect dialect,
            Authentication authentication) {
        List<String> args = splitTopLevelArguments(extractFunctionInner(selectColumnsExpr));
        if (args.isEmpty()) {
            throw new IllegalArgumentException("SELECTCOLUMNS requires a source table expression.");
        }
        String sourceExpr = args.getFirst();
        List<ProjectionColumn> projections = parseProjectionPairs(args, 1);

        QueryExecutionResult sourceResult;
        if (infoFunctionEvaluator.isInfoQuery(sourceExpr)) {
            List<Map<String, Object>> infoRows = infoFunctionEvaluator.evaluateInfoQuery(sourceExpr, schema);
            sourceResult = new QueryExecutionResult(
                    "meta-info-query",
                    false,
                    dialect != null ? dialect : SqlDialect.DUCKDB,
                    1L,
                    "-- Semantic Catalog Introspection (INFO.*)\nSELECT * FROM information_schema.rax_catalog;",
                    infoRows);
        } else {
            sourceResult = evaluateCoreExpression(sourceExpr, schema, dialect, authentication);
        }

        List<Map<String, Object>> projectedRows = projectRows(sourceResult.rows(), projections);
        return new QueryExecutionResult(
                sourceResult.cacheKey(),
                sourceResult.cacheHit(),
                sourceResult.routedEngine(),
                sourceResult.executionTimeMs(),
                sourceResult.executedSql(),
                projectedRows);
    }

    private QueryExecutionResult evaluateSummarizeExpression(
            String summarizeExpr,
            boolean isSummarizeColumns,
            SemanticModelSchema schema,
            SqlDialect dialect,
            Authentication authentication) {
        List<String> args = splitTopLevelArguments(extractFunctionInner(summarizeExpr));
        if (args.isEmpty()) {
            throw new IllegalArgumentException("SUMMARIZE requires at least one argument.");
        }

        int startIdx = 0;
        String explicitTable = null;
        if (!isSummarizeColumns) {
            explicitTable = unquoteSingle(args.getFirst());
            startIdx = 1;
        }

        List<String> groupByCols = new ArrayList<>();
        List<ProjectionColumn> measurePairs = new ArrayList<>();

        int i = startIdx;
        while (i < args.size()) {
            String arg = args.get(i).trim();
            if (isQuotedStringLiteral(arg) && i + 1 < args.size()) {
                measurePairs.add(new ProjectionColumn(unquoteDouble(arg), args.get(i + 1).trim()));
                i += 2;
            } else {
                if (QUALIFIED_COL_ONLY_PATTERN.matcher(arg).matches()) {
                    groupByCols.add(arg.replace("'", ""));
                }
                i++;
            }
        }

        String primaryTable = explicitTable != null && !explicitTable.isBlank()
                ? explicitTable
                : resolvePrimaryTableFromColumns(groupByCols, schema);
        String measureRax = !measurePairs.isEmpty()
                ? inlineMeasures(measurePairs.getLast().expression(), schema)
                : "COUNTROWS(" + primaryTable + ")";

        VisualQueryDefinition query = buildAdhocVisualQuery(schema, measureRax, groupByCols, dialect, primaryTable);
        QueryExecutionResult result = dualEngineCoordinator.executeVisualQuery(query, authentication);

        if (!measurePairs.isEmpty()) {
            String customAlias = measurePairs.getLast().alias();
            List<Map<String, Object>> aliasedRows = renameMeasureValueColumn(result.rows(), customAlias);
            return new QueryExecutionResult(
                    result.cacheKey(),
                    result.cacheHit(),
                    result.routedEngine(),
                    result.executionTimeMs(),
                    result.executedSql(),
                    aliasedRows);
        }
        return result;
    }

    private QueryExecutionResult evaluateRowExpression(
            String rowExpr,
            SemanticModelSchema schema,
            SqlDialect dialect,
            Authentication authentication) {
        List<String> args = splitTopLevelArguments(extractFunctionInner(rowExpr));
        List<ProjectionColumn> pairs = parseProjectionPairs(args, 0);
        if (pairs.isEmpty()) {
            throw new IllegalArgumentException("ROW requires at least one name/expression pair.");
        }

        Map<String, Object> combinedRow = new LinkedHashMap<>();
        String lastSql = "";
        String lastCacheKey = "row-eval";
        SqlDialect routedEngine = dialect != null ? dialect : SqlDialect.DUCKDB;

        for (ProjectionColumn pair : pairs) {
            String expr = inlineMeasures(pair.expression(), schema);
            CompilationPlan plan = raxFormulaCompiler.compile(
                    expr,
                    routedEngine,
                    schema.fiscalYearStartMonth(),
                    new FilterContext(),
                    null,
                    Map.of());
            String primaryTable = !plan.referencedTables().isEmpty()
                    ? plan.referencedTables().getFirst()
                    : resolveFallbackTableName(schema);
            VisualQueryDefinition query = buildAdhocVisualQuery(schema, expr, List.of(), dialect, primaryTable);
            QueryExecutionResult subResult = dualEngineCoordinator.executeVisualQuery(query, authentication);
            lastSql = subResult.executedSql();
            lastCacheKey = subResult.cacheKey();
            routedEngine = subResult.routedEngine();
            Object value = extractFirstScalarValue(subResult.rows());
            combinedRow.put(pair.alias(), value);
        }

        return new QueryExecutionResult(
                lastCacheKey,
                false,
                routedEngine,
                1L,
                lastSql,
                List.of(combinedRow));
    }

    private List<Map<String, Object>> applySelectColumnsProjectionIfPresent(
            String coreExpression,
            List<Map<String, Object>> rows,
            SemanticModelSchema schema) {
        String trimmed = coreExpression.trim();
        if (!trimmed.toUpperCase(Locale.ROOT).startsWith("SELECTCOLUMNS(") || !trimmed.endsWith(")")) {
            return rows;
        }
        List<String> args = splitTopLevelArguments(extractFunctionInner(trimmed));
        List<ProjectionColumn> projections = parseProjectionPairs(args, 1);
        return projectRows(rows, projections);
    }

    private List<ProjectionColumn> parseProjectionPairs(List<String> args, int startIndex) {
        List<ProjectionColumn> projections = new ArrayList<>();
        for (int i = startIndex; i + 1 < args.size(); i += 2) {
            String alias = unquoteDouble(args.get(i).trim());
            String expr = args.get(i + 1).trim();
            projections.add(new ProjectionColumn(alias, expr));
        }
        return projections;
    }

    private List<Map<String, Object>> projectRows(
            List<Map<String, Object>> sourceRows,
            List<ProjectionColumn> projections) {
        if (sourceRows == null || sourceRows.isEmpty() || projections.isEmpty()) {
            return sourceRows != null ? sourceRows : List.of();
        }
        List<Map<String, Object>> projected = new ArrayList<>(sourceRows.size());
        for (Map<String, Object> row : sourceRows) {
            Map<String, Object> outRow = new LinkedHashMap<>();
            for (ProjectionColumn proj : projections) {
                outRow.put(proj.alias(), resolveRowProjectionValue(row, proj.expression()));
            }
            projected.add(outRow);
        }
        return List.copyOf(projected);
    }

    private Object resolveRowProjectionValue(Map<String, Object> row, String expression) {
        if (row == null || expression == null) {
            return null;
        }
        String trimmed = expression.trim();
        if (isQuotedStringLiteral(trimmed)) {
            return unquoteDouble(trimmed);
        }
        String candidateColumn = extractColumnNameFromRef(trimmed);
        if (row.containsKey(candidateColumn)) {
            return row.get(candidateColumn);
        }
        for (Map.Entry<String, Object> entry : row.entrySet()) {
            if (entry.getKey().equalsIgnoreCase(candidateColumn)) {
                return entry.getValue();
            }
        }
        return row.get(trimmed);
    }

    private List<Map<String, Object>> applyOrderSpecs(
            List<Map<String, Object>> rows,
            List<ParsedOrderSpec> orderSpecs) {
        if (rows == null || rows.size() <= 1 || orderSpecs == null || orderSpecs.isEmpty()) {
            return rows != null ? List.copyOf(rows) : List.of();
        }
        List<Map<String, Object>> sorted = new ArrayList<>(rows);
        Comparator<Map<String, Object>> comparator = null;
        for (ParsedOrderSpec spec : orderSpecs) {
            Comparator<Map<String, Object>> specComparator = (left, right) ->
                    compareRowValues(findRowValue(left, spec.columnOrAlias()), findRowValue(right, spec.columnOrAlias()));
            if (spec.descending()) {
                specComparator = specComparator.reversed();
            }
            comparator = (comparator == null) ? specComparator : comparator.thenComparing(specComparator);
        }
        if (comparator != null) {
            sorted.sort(comparator);
        }
        return List.copyOf(sorted);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private int compareRowValues(Object a, Object b) {
        if (a == b) {
            return 0;
        }
        if (a == null) {
            return 1;
        }
        if (b == null) {
            return -1;
        }
        if (a instanceof Number numA && b instanceof Number numB) {
            return Double.compare(numA.doubleValue(), numB.doubleValue());
        }
        if (a instanceof Comparable compA && a.getClass().isInstance(b)) {
            return compA.compareTo(b);
        }
        return String.valueOf(a).compareToIgnoreCase(String.valueOf(b));
    }

    private Object findRowValue(Map<String, Object> row, String key) {
        if (row == null || key == null) {
            return null;
        }
        if (row.containsKey(key)) {
            return row.get(key);
        }
        String shortCol = extractColumnNameFromRef(key);
        for (Map.Entry<String, Object> entry : row.entrySet()) {
            if (entry.getKey().equalsIgnoreCase(key) || entry.getKey().equalsIgnoreCase(shortCol)) {
                return entry.getValue();
            }
        }
        return null;
    }

    private ParsedEvaluateStatement extractEvaluateStatement(
            String strippedScript,
            Map<String, MeasureDefinition> scriptMeasures) {
        String evalBody = strippedScript;
        Matcher evalMatcher = EVALUATE_KEYWORD_PATTERN.matcher(strippedScript);
        if (evalMatcher.find()) {
            evalBody = strippedScript.substring(evalMatcher.end()).trim();
        } else if (strippedScript.regionMatches(true, 0, "DEFINE", 0, 6)) {
            if (!scriptMeasures.isEmpty()) {
                MeasureDefinition last = new ArrayList<>(scriptMeasures.values()).getLast();
                evalBody = last.raxExpression();
            } else {
                throw new IllegalArgumentException("DEFINE block must contain at least one MEASURE or an EVALUATE clause.");
            }
        }

        List<ParsedOrderSpec> orderSpecs = new ArrayList<>();
        String coreExpr = evalBody;
        Matcher orderMatcher = ORDER_BY_CLAUSE_PATTERN.matcher(evalBody);
        if (orderMatcher.matches()) {
            coreExpr = orderMatcher.group(1).trim();
            String orderClause = orderMatcher.group(2).trim();
            orderSpecs.addAll(parseOrderByClause(orderClause));
        }

        return new ParsedEvaluateStatement(evalBody, coreExpr, List.copyOf(orderSpecs));
    }

    private List<ParsedOrderSpec> parseOrderByClause(String orderClause) {
        List<String> items = splitTopLevelArguments(orderClause);
        List<ParsedOrderSpec> specs = new ArrayList<>(items.size());
        for (String item : items) {
            String trimmed = item.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            Matcher m = ORDER_SPEC_ITEM_PATTERN.matcher(trimmed);
            if (m.matches()) {
                String bracketCol = m.group(3);
                String quotedAlias = m.group(4);
                String bareCol = m.group(5);
                String dir = m.group(6);
                String target = bracketCol != null ? bracketCol : (quotedAlias != null ? quotedAlias : bareCol);
                boolean desc = "DESC".equalsIgnoreCase(dir);
                if (target != null && !target.isBlank()) {
                    specs.add(new ParsedOrderSpec(target.trim(), desc));
                }
            } else {
                boolean desc = trimmed.toUpperCase(Locale.ROOT).endsWith(" DESC");
                String cleaned = trimmed.replaceAll("(?i)\\s+(ASC|DESC)$", "").trim();
                specs.add(new ParsedOrderSpec(extractColumnNameFromRef(cleaned), desc));
            }
        }
        return specs;
    }

    private Map<String, MeasureDefinition> parseDefinedMeasures(String strippedScript, SemanticModelSchema schema) {
        Map<String, MeasureDefinition> defined = new LinkedHashMap<>();
        String fallbackTable = resolveFallbackTableName(schema);

        Matcher matcher = DEFINE_MEASURE_PATTERN.matcher(strippedScript);
        while (matcher.find()) {
            String quotedTable = matcher.group(1);
            String bareTable = matcher.group(2);
            String measureName = matcher.group(3).trim();
            String measureExpr = matcher.group(4).trim();
            String tableName = (quotedTable != null && !quotedTable.isBlank())
                    ? quotedTable.trim()
                    : ((bareTable != null && !bareTable.isBlank()) ? bareTable.trim() : fallbackTable);
            String id = "m_" + measureName.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "_");
            defined.put(
                    measureName.toLowerCase(Locale.ROOT),
                    new MeasureDefinition(id, measureName, tableName, measureExpr, "", ""));
        }

        if (defined.isEmpty() && !strippedScript.regionMatches(true, 0, "EVALUATE", 0, 8)
                && !strippedScript.regionMatches(true, 0, "DEFINE", 0, 6)) {
            int eqIdx = findTopLevelAssignmentEquals(strippedScript);
            if (eqIdx > 0) {
                String lhs = strippedScript.substring(0, eqIdx).trim();
                String rhs = strippedScript.substring(eqIdx + 1).trim();
                if (!lhs.isEmpty() && !rhs.isEmpty()) {
                    String measureName = extractColumnNameFromRef(lhs);
                    String tableName = extractTableNameFromRef(lhs, fallbackTable);
                    String id = "m_" + measureName.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "_");
                    defined.put(
                            measureName.toLowerCase(Locale.ROOT),
                            new MeasureDefinition(id, measureName, tableName, rhs, "", ""));
                }
            }
        }

        return defined;
    }

    private SemanticModelSchema mergeTemporaryMeasures(
            SemanticModelSchema base,
            Map<String, MeasureDefinition> scriptMeasures) {
        if (scriptMeasures == null || scriptMeasures.isEmpty()) {
            return base;
        }
        Map<String, MeasureDefinition> merged = new LinkedHashMap<>();
        for (MeasureDefinition m : base.measures()) {
            merged.put(m.name().toLowerCase(Locale.ROOT), m);
        }
        for (MeasureDefinition m : scriptMeasures.values()) {
            merged.put(m.name().toLowerCase(Locale.ROOT), m);
        }
        return new SemanticModelSchema(
                base.modelId(),
                base.name(),
                base.fiscalYearStartMonth(),
                base.tables(),
                base.relationships(),
                new ArrayList<>(merged.values()),
                base.rlsRolePredicates(),
                base.discourageImplicitMeasures());
    }

    private String inlineMeasures(String expression, SemanticModelSchema schema) {
        if (expression == null || schema == null || schema.measures() == null || schema.measures().isEmpty()) {
            return expression;
        }
        String result = expression;
        for (int pass = 0; pass < 3; pass++) {
            boolean replaced = false;
            for (MeasureDefinition m : schema.measures()) {
                String bareRef = "[" + m.name() + "]";
                String quotedTableRef = "'" + m.tableName() + "'[" + m.name() + "]";
                String unquotedTableRef = m.tableName() + "[" + m.name() + "]";
                String replacement = "(" + m.raxExpression() + ")";

                if (result.contains(quotedTableRef)) {
                    result = result.replace(quotedTableRef, replacement);
                    replaced = true;
                }
                if (result.contains(unquotedTableRef)) {
                    result = result.replace(unquotedTableRef, replacement);
                    replaced = true;
                }
                if (result.contains(bareRef) && !isPrecededByTableIdentifier(result, bareRef)) {
                    result = result.replace(bareRef, replacement);
                    replaced = true;
                }
            }
            if (!replaced) {
                break;
            }
        }
        return result;
    }

    private boolean isPrecededByTableIdentifier(String text, String bareRef) {
        int idx = text.indexOf(bareRef);
        if (idx <= 0) {
            return false;
        }
        char prev = text.charAt(idx - 1);
        return prev == '\'' || Character.isLetterOrDigit(prev) || prev == '_';
    }

    private VisualQueryDefinition buildAdhocVisualQuery(
            SemanticModelSchema schema,
            String raxExpression,
            List<String> groupByColumns,
            SqlDialect dialect) {
        return buildAdhocVisualQuery(schema, raxExpression, groupByColumns, dialect, resolveFallbackTableName(schema));
    }

    private VisualQueryDefinition buildAdhocVisualQuery(
            SemanticModelSchema schema,
            String raxExpression,
            List<String> groupByColumns,
            SqlDialect dialect,
            String primaryTable) {
        boolean operationalLookup = (dialect == SqlDialect.MYSQL);
        long estimatedRows = (dialect == SqlDialect.DUCKDB) ? 2_000_000L : 500L;
        return new VisualQueryDefinition(
                schema.modelId(),
                "default-tenant",
                "rax-studio",
                "rax-studio-editor",
                null,
                raxExpression,
                primaryTable,
                estimatedRows,
                operationalLookup,
                groupByColumns,
                Map.of(),
                Map.of(),
                LocalDate.now(),
                "en-US");
    }

    private List<Map<String, Object>> renameMeasureValueColumn(List<Map<String, Object>> rows, String customAlias) {
        if (rows == null || rows.isEmpty() || customAlias == null || customAlias.isBlank()) {
            return rows != null ? rows : List.of();
        }
        List<Map<String, Object>> renamed = new ArrayList<>(rows.size());
        for (Map<String, Object> row : rows) {
            Map<String, Object> copy = new LinkedHashMap<>();
            for (Map.Entry<String, Object> entry : row.entrySet()) {
                if ("measure_value".equalsIgnoreCase(entry.getKey())) {
                    copy.put(customAlias, entry.getValue());
                } else {
                    copy.put(entry.getKey(), entry.getValue());
                }
            }
            renamed.add(copy);
        }
        return List.copyOf(renamed);
    }

    private Object extractFirstScalarValue(List<Map<String, Object>> rows) {
        if (rows == null || rows.isEmpty()) {
            return 0.0;
        }
        Map<String, Object> first = rows.getFirst();
        if (first == null || first.isEmpty()) {
            return 0.0;
        }
        if (first.containsKey("measure_value")) {
            return first.get("measure_value");
        }
        return first.values().iterator().next();
    }

    private TableDefinition findTableByName(SemanticModelSchema schema, String candidateName) {
        if (schema == null || schema.tables() == null || candidateName == null || candidateName.isBlank()) {
            return null;
        }
        for (TableDefinition table : schema.tables()) {
            if (table.name().equalsIgnoreCase(candidateName.trim())) {
                return table;
            }
        }
        return null;
    }

    private String resolveFallbackTableName(SemanticModelSchema schema) {
        if (schema != null && schema.tables() != null && !schema.tables().isEmpty()) {
            for (TableDefinition table : schema.tables()) {
                if ("FACT".equalsIgnoreCase(table.role())) {
                    return table.name();
                }
            }
            return schema.tables().getFirst().name();
        }
        return "dataset";
    }

    private String resolvePrimaryTableFromColumns(List<String> groupByCols, SemanticModelSchema schema) {
        for (String col : groupByCols) {
            int bracketIdx = col.indexOf('[');
            if (bracketIdx > 0) {
                return col.substring(0, bracketIdx).replace("'", "").trim();
            }
        }
        return resolveFallbackTableName(schema);
    }

    private String extractColumnNameFromRef(String ref) {
        Matcher qualMatcher = QUALIFIED_COL_ONLY_PATTERN.matcher(ref);
        if (qualMatcher.matches()) {
            return qualMatcher.group(3).trim();
        }
        Matcher unqualMatcher = UNQUALIFIED_BRACKET_ONLY_PATTERN.matcher(ref);
        if (unqualMatcher.matches()) {
            return unqualMatcher.group(1).trim();
        }
        int openBracket = ref.indexOf('[');
        int closeBracket = ref.lastIndexOf(']');
        if (openBracket >= 0 && closeBracket > openBracket) {
            return ref.substring(openBracket + 1, closeBracket).trim();
        }
        return unquoteDouble(unquoteSingle(ref));
    }

    private String extractTableNameFromRef(String ref, String fallbackTable) {
        Matcher qualMatcher = QUALIFIED_COL_ONLY_PATTERN.matcher(ref);
        if (qualMatcher.matches()) {
            String t = qualMatcher.group(1) != null ? qualMatcher.group(1) : qualMatcher.group(2);
            if (t != null && !t.isBlank()) {
                return t.trim();
            }
        }
        return fallbackTable;
    }

    private int findTopLevelAssignmentEquals(String formula) {
        if (formula.regionMatches(true, 0, "VAR ", 0, 4)) {
            return -1;
        }
        int depthParen = 0;
        int depthBracket = 0;
        boolean inSingle = false;
        boolean inDouble = false;
        for (int i = 0; i < formula.length(); i++) {
            char ch = formula.charAt(i);
            if (ch == '\'' && !inDouble) {
                inSingle = !inSingle;
            } else if (ch == '"' && !inSingle) {
                inDouble = !inDouble;
            } else if (!inSingle && !inDouble) {
                if (ch == '(') depthParen++;
                else if (ch == ')') depthParen = Math.max(0, depthParen - 1);
                else if (ch == '[') depthBracket++;
                else if (ch == ']') depthBracket = Math.max(0, depthBracket - 1);
                else if (ch == '=' && depthParen == 0 && depthBracket == 0) {
                    char prev = i > 0 ? formula.charAt(i - 1) : ' ';
                    char next = (i + 1 < formula.length()) ? formula.charAt(i + 1) : ' ';
                    if (prev != '<' && prev != '>' && prev != '!' && prev != '=' && next != '=') {
                        return i;
                    }
                }
            }
        }
        return -1;
    }

    private String extractFunctionInner(String call) {
        int open = call.indexOf('(');
        int close = call.lastIndexOf(')');
        if (open >= 0 && close > open) {
            return call.substring(open + 1, close).trim();
        }
        return "";
    }

    private List<String> splitTopLevelArguments(String inner) {
        if (inner == null || inner.isBlank()) {
            return List.of();
        }
        List<String> args = new ArrayList<>();
        int depthParen = 0;
        int depthBracket = 0;
        boolean inSingle = false;
        boolean inDouble = false;
        int start = 0;

        for (int i = 0; i < inner.length(); i++) {
            char ch = inner.charAt(i);
            if (ch == '\'' && !inDouble) {
                inSingle = !inSingle;
            } else if (ch == '"' && !inSingle) {
                inDouble = !inDouble;
            } else if (!inSingle && !inDouble) {
                if (ch == '(') depthParen++;
                else if (ch == ')') depthParen = Math.max(0, depthParen - 1);
                else if (ch == '[') depthBracket++;
                else if (ch == ']') depthBracket = Math.max(0, depthBracket - 1);
                else if ((ch == ',' || ch == ';') && depthParen == 0 && depthBracket == 0) {
                    args.add(inner.substring(start, i).trim());
                    start = i + 1;
                }
            }
        }
        if (start <= inner.length()) {
            args.add(inner.substring(start).trim());
        }
        return args;
    }

    private boolean isTmdlScript(String trimmed) {
        String upper = trimmed.toUpperCase(Locale.ROOT);
        return upper.startsWith("CREATEORREPLACE") || upper.startsWith("TABLE ") || upper.startsWith("MODEL ");
    }

    private static boolean isQuotedStringLiteral(String text) {
        return text != null && text.length() >= 2 && text.startsWith("\"") && text.endsWith("\"");
    }

    private static String unquoteDouble(String text) {
        if (isQuotedStringLiteral(text)) {
            return text.substring(1, text.length() - 1).replace("\"\"", "\"");
        }
        return text != null ? text : "";
    }

    private static String unquoteSingle(String text) {
        if (text != null && text.length() >= 2 && text.startsWith("'") && text.endsWith("'")) {
            return text.substring(1, text.length() - 1).replace("''", "'");
        }
        return text != null ? text.trim() : "";
    }

    private static String stripComments(String input) {
        if (input == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(input.length());
        int i = 0;
        int len = input.length();
        boolean inSingle = false;
        boolean inDouble = false;

        while (i < len) {
            char ch = input.charAt(i);
            char next = (i + 1 < len) ? input.charAt(i + 1) : '\0';

            if (!inSingle && !inDouble) {
                if ((ch == '/' && next == '/') || (ch == '-' && next == '-')) {
                    while (i < len && input.charAt(i) != '\n') {
                        i++;
                    }
                    continue;
                }
                if (ch == '/' && next == '*') {
                    i += 2;
                    while (i < len && !(input.charAt(i) == '*' && (i + 1 < len && input.charAt(i + 1) == '/'))) {
                        i++;
                    }
                    i += 2;
                    continue;
                }
            }
            if (ch == '\'' && !inDouble) {
                inSingle = !inSingle;
            } else if (ch == '"' && !inSingle) {
                inDouble = !inDouble;
            }
            sb.append(ch);
            i++;
        }
        return sb.toString();
    }
}
