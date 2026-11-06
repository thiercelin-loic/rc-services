package com.rollercoaster.model;

import com.rollercoaster.rax.diagnostics.DiagnosticMarker;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Manages the Star Schema Semantic Model, Relationship Directed Acyclic Graph
 * (DAG),
 * SCD Type 2 interval boundary injection, Role-Playing dimension aliasing,
 * RSDL serialization, and TMDL parsing/generation.
 */
@Service
public class SemanticModelManager {

    private static final Pattern RSDL_REL_PATTERN = Pattern.compile(
            "relationship\\s+'([^']+)'\\s+fromColumn:\\s+'([^']+)'\\[([^\\]]+)\\]\\s+toColumn:\\s+'([^']+)'\\[([^\\]]+)\\]\\s+isActive:\\s*(true|false)",
            Pattern.CASE_INSENSITIVE | Pattern.MULTILINE);
    private static final Pattern TMDL_TABLE_LINE = Pattern.compile(
            "(?i)^\\s*table\\s+(?:'([^']+)'|([\\p{L}_][\\p{L}\\p{Nd}_\\s-]*))\\s*$");
    private static final Pattern TMDL_MEASURE_LINE = Pattern.compile(
            "(?i)^\\s*measure\\s+(?:'([^']+)'|([\\p{L}_][\\p{L}\\p{Nd}_\\s-]*))\\s*=\\s*(.+)$");
    private static final Pattern TMDL_FORMAT_LINE = Pattern.compile(
            "(?i)^\\s*formatString:\\s*\"([^\"]*)\"\\s*$");

    private final Map<UUID, SemanticModelSchema> registeredModels = new ConcurrentHashMap<>();
    private final SemanticModelSchema defaultRetailModel;

    public SemanticModelManager() {
        this.defaultRetailModel = buildDefaultRetailStarSchema();
        this.registeredModels.put(this.defaultRetailModel.modelId(), this.defaultRetailModel);
    }

    public SemanticModelSchema getDefaultRetailModel() {
        return defaultRetailModel;
    }

    public SemanticModelSchema getDefaultModel() {
        return defaultRetailModel;
    }

    public SemanticModelSchema getModel(UUID modelId) {
        if (modelId == null) {
            return defaultRetailModel;
        }
        return registeredModels.getOrDefault(modelId, defaultRetailModel);
    }

    public void registerModel(SemanticModelSchema schema) {
        if (schema != null && schema.modelId() != null) {
            registeredModels.put(schema.modelId(), schema);
        }
    }

    /**
     * Validates DAG consistency:
     * 1. Rejects snowflake chains (DIMENSION -> DIMENSION relationships).
     * 2. Detects directed cycles (ERR_CIRCULAR_DEP).
     * 3. Enforces at most one active relationship path between any two entities
     * (ERR_AMBIGUOUS_PATH).
     */
    public DagValidationReport validateDagConsistency(SemanticModelSchema schema) {
        List<DiagnosticMarker> diagnostics = new ArrayList<>();
        boolean hasCycles = false;
        boolean hasAmbiguous = false;
        boolean violatesStar = false;

        Map<String, TableDefinition> tableMap = new HashMap<>();
        for (TableDefinition t : schema.tables()) {
            tableMap.put(t.name().toLowerCase(), t);
        }

        // 1. Check Star Schema constraint (no Dimension -> Dimension snowflaking)
        for (RelationshipDefinition rel : schema.relationships()) {
            TableDefinition from = tableMap.get(rel.fromTable().toLowerCase());
            TableDefinition to = tableMap.get(rel.toTable().toLowerCase());
            if (from != null && to != null
                    && "DIMENSION".equalsIgnoreCase(from.role())
                    && "DIMENSION".equalsIgnoreCase(to.role())) {
                violatesStar = true;
                diagnostics.add(DiagnosticMarker.error(
                        1, 0, rel.name().length(),
                        "ERR_SNOWFLAKE_REJECTED",
                        "Snowflake normalization between dimensions '" + from.name() + "' and '" + to.name()
                                + "' is rejected; flatten dimensions into a Star Schema."));
            }
        }

        // 2. Check unambiguous active paths (at most 1 active relationship between any
        // pair of entities)
        Map<String, List<RelationshipDefinition>> activePairEdges = new HashMap<>();
        Map<String, List<String>> adjacency = new HashMap<>();
        for (RelationshipDefinition rel : schema.relationships()) {
            String f = rel.fromTable().toLowerCase();
            String t = rel.toTable().toLowerCase();
            adjacency.computeIfAbsent(f, k -> new ArrayList<>()).add(t);

            if (rel.isActive()) {
                String pairKey = f.compareTo(t) < 0 ? f + "::" + t : t + "::" + f;
                activePairEdges.computeIfAbsent(pairKey, k -> new ArrayList<>()).add(rel);
            }
        }

        for (Map.Entry<String, List<RelationshipDefinition>> entry : activePairEdges.entrySet()) {
            if (entry.getValue().size() > 1) {
                hasAmbiguous = true;
                String relNames = entry.getValue().stream().map(rel -> rel.name()).toList().toString();
                diagnostics.add(DiagnosticMarker.error(
                        1, 0, entry.getKey().length(),
                        "ERR_AMBIGUOUS_PATH",
                        "Multiple active relationships detected between entities (" + entry.getKey() + "): "
                                + relNames
                                + ". Persist secondary relationships as isActive: false and use USERELATIONSHIP()."));
            }
        }

        // 3. Detect directed cycles via DFS 3-color marking
        Map<String, Integer> visitState = new HashMap<>(); // 0=unvisited, 1=visiting, 2=visited
        for (String node : adjacency.keySet()) {
            if (visitState.getOrDefault(node, 0) == 0) {
                if (detectCycleDfs(node, adjacency, visitState)) {
                    hasCycles = true;
                    diagnostics.add(DiagnosticMarker.error(
                            1, 0, node.length(),
                            "ERR_CIRCULAR_DEP",
                            "Circular relationship dependency cycle detected involving entity '" + node + "'."));
                    break;
                }
            }
        }

        boolean valid = !hasCycles && !hasAmbiguous && !violatesStar;
        return new DagValidationReport(valid, hasCycles, hasAmbiguous, violatesStar, diagnostics);
    }

    /**
     * Injects SCD Type 2 temporal validity boundary filters (WHERE :evalDate
     * BETWEEN valid_from AND valid_to)
     * for any referenced SCD Type 2 dimension (e.g., dim_customer).
     */
    public String injectScdType2BoundaryFilter(
            String baseSql,
            SemanticModelSchema schema,
            LocalDate evalDate) {
        if (baseSql == null || schema == null) {
            return baseSql;
        }
        String effectiveDate = (evalDate != null ? evalDate : LocalDate.of(2026, 10, 12)).toString();
        String resultSql = baseSql;
        for (TableDefinition table : schema.tables()) {
            if (table.scdType2() && resultSql.toLowerCase().contains(table.name().toLowerCase())) {
                String scdFilter = "'" + effectiveDate + "' BETWEEN " + table.name() + ".valid_from AND " + table.name()
                        + ".valid_to";
                if (resultSql.toUpperCase().contains("WHERE ")) {
                    resultSql = resultSql + " AND (" + scdFilter + ")";
                } else {
                    resultSql = resultSql + " WHERE (" + scdFilter + ")";
                }
            }
        }
        return resultSql;
    }

    /**
     * Resolves Role-Playing Dimension join clause (e.g. dim_calendar aliased across
     * active date_id
     * vs inactive delivery_date_id activated via USERELATIONSHIP).
     */
    public String resolveRolePlayingJoinClause(
            SemanticModelSchema schema,
            String factTable,
            String dimensionTable,
            Map<String, String> activeRelationshipOverrides) {

        for (RelationshipDefinition rel : schema.relationships()) {
            if (rel.fromTable().equalsIgnoreCase(factTable) && rel.toTable().equalsIgnoreCase(dimensionTable)) {
                String qualifiedFrom = (rel.fromTable() + "[" + rel.fromColumn() + "]").toLowerCase().replace("'", "");
                boolean dynamicallyActivated = activeRelationshipOverrides != null
                        && activeRelationshipOverrides.containsKey(qualifiedFrom);
                if (dynamicallyActivated) {
                    String alias = dimensionTable + "_by_" + rel.fromColumn();
                    return "JOIN " + dimensionTable + " AS " + alias
                            + " ON " + factTable + "." + rel.fromColumn() + " = " + alias + "." + rel.toColumn();
                }
            }
        }

        // Default to active relationship
        for (RelationshipDefinition rel : schema.relationships()) {
            if (rel.isActive()
                    && rel.fromTable().equalsIgnoreCase(factTable)
                    && rel.toTable().equalsIgnoreCase(dimensionTable)) {
                return "JOIN " + dimensionTable
                        + " ON " + factTable + "." + rel.fromColumn() + " = " + dimensionTable + "." + rel.toColumn();
            }
        }
        return "";
    }

    /**
     * Serializes the SemanticModelSchema into RollerCoaster Semantic Definition
     * Language (model.rsdl).
     */
    public String formatToRsdl(SemanticModelSchema schema) {
        StringBuilder sb = new StringBuilder();
        sb.append("// RollerCoaster Semantic Definition Language (RSDL v1.0)\n");
        sb.append("model '").append(schema.name()).append("' {\n");
        sb.append("  fiscalYearStartMonth: ").append(schema.fiscalYearStartMonth()).append("\n\n");

        for (TableDefinition t : schema.tables()) {
            sb.append("  table '").append(t.name()).append("' (\n");
            sb.append("    role: ").append(t.role()).append("\n");
            for (ColumnDefinition c : t.columns()) {
                sb.append("    column '").append(c.name()).append("': ").append(c.dataType());
                if (c.isPrimaryKey())
                    sb.append(" [PK]");
                if (c.isForeignKey())
                    sb.append(" [FK]");
                sb.append("\n");
            }
            sb.append("  )\n\n");
        }

        for (RelationshipDefinition rel : schema.relationships()) {
            sb.append(rel.toRsdlSnippet().indent(2)).append("\n");
        }

        for (MeasureDefinition m : schema.measures()) {
            if (m.description() != null && !m.description().isBlank()) {
                sb.append("  /// ").append(m.description()).append("\n");
            }
            sb.append("  measure '").append(m.tableName()).append("'[").append(m.name()).append("] = ")
                    .append(m.raxExpression()).append("\n");
            if (m.formatString() != null && !m.formatString().isBlank()) {
                sb.append("    formatString: \"").append(m.formatString()).append("\"\n");
            }
        }
        sb.append("}\n");
        return sb.toString();
    }

    /**
     * Serializes the measures of a SemanticModelSchema into Tabular Model Definition Language (TMDL)
     * with /// description comments, matching Power BI TMDL View.
     */
    public String formatToTmdl(SemanticModelSchema schema) {
        if (schema == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("createOrReplace\n");
        Map<String, List<MeasureDefinition>> byTable = new LinkedHashMap<>();
        String fallbackTable = schema.tables() != null && !schema.tables().isEmpty()
                ? schema.tables().get(0).name()
                : "Measures Table";

        if (schema.measures() != null) {
            for (MeasureDefinition m : schema.measures()) {
                String tName = (m.tableName() != null && !m.tableName().isBlank()) ? m.tableName() : fallbackTable;
                byTable.computeIfAbsent(tName, k -> new ArrayList<>()).add(m);
            }
        }

        if (byTable.isEmpty()) {
            sb.append("  table '").append(fallbackTable).append("'\n");
            return sb.toString();
        }

        for (Map.Entry<String, List<MeasureDefinition>> entry : byTable.entrySet()) {
            sb.append("  table '").append(entry.getKey()).append("'\n");
            for (MeasureDefinition m : entry.getValue()) {
                if (m.description() != null && !m.description().isBlank()) {
                    sb.append("    /// ").append(m.description()).append("\n");
                }
                sb.append("    measure '").append(m.name()).append("' = ").append(m.raxExpression()).append("\n");
                if (m.formatString() != null && !m.formatString().isBlank()) {
                    sb.append("      formatString: \"").append(m.formatString()).append("\"\n");
                }
            }
        }
        return sb.toString();
    }

    /**
     * Applies a TMDL script (containing table, /// description, measure, and formatString declarations)
     * to the target semantic model.
     */
    public SemanticModelSchema applyTmdlScript(UUID modelId, String tmdlScript) {
        SemanticModelSchema current = getModel(modelId);
        if (tmdlScript == null || tmdlScript.isBlank()) {
            return current;
        }

        Map<String, MeasureDefinition> updatedMeasures = new LinkedHashMap<>();
        if (current.measures() != null) {
            for (MeasureDefinition existing : current.measures()) {
                updatedMeasures.put(existing.name().toLowerCase(), existing);
            }
        }

        String currentTable = current.tables() != null && !current.tables().isEmpty()
                ? current.tables().get(0).name()
                : "Measures Table";
        StringBuilder pendingDescription = new StringBuilder();
        String lastMeasureKey = null;

        String[] lines = tmdlScript.split("\\r?\\n");
        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            if (trimmed.startsWith("///")) {
                String descText = trimmed.substring(3).trim();
                if (!descText.isEmpty()) {
                    if (pendingDescription.length() > 0) {
                        pendingDescription.append(" ");
                    }
                    pendingDescription.append(descText);
                }
                continue;
            }

            Matcher tableMatcher = TMDL_TABLE_LINE.matcher(line);
            if (tableMatcher.matches()) {
                String tName = tableMatcher.group(1) != null ? tableMatcher.group(1) : tableMatcher.group(2);
                if (tName != null && !tName.isBlank()) {
                    currentTable = tName.trim();
                }
                pendingDescription.setLength(0);
                continue;
            }

            Matcher measureMatcher = TMDL_MEASURE_LINE.matcher(line);
            if (measureMatcher.matches()) {
                String mName = measureMatcher.group(1) != null ? measureMatcher.group(1) : measureMatcher.group(2);
                String expr = measureMatcher.group(3) != null ? measureMatcher.group(3).trim() : "";
                if (mName != null && !mName.isBlank()) {
                    String cleanName = mName.trim();
                    String key = cleanName.toLowerCase();
                    MeasureDefinition prev = updatedMeasures.get(key);
                    String id = prev != null ? prev.id() : "m_" + cleanName.toLowerCase().replaceAll("[^a-z0-9]+", "_");
                    String desc = pendingDescription.length() > 0
                            ? pendingDescription.toString()
                            : (prev != null && prev.description() != null ? prev.description() : "");
                    String fmt = prev != null && prev.formatString() != null ? prev.formatString() : "";
                    updatedMeasures.put(key, new MeasureDefinition(id, cleanName, currentTable, expr, fmt, desc));
                    lastMeasureKey = key;
                }
                pendingDescription.setLength(0);
                continue;
            }

            Matcher formatMatcher = TMDL_FORMAT_LINE.matcher(line);
            if (formatMatcher.matches() && lastMeasureKey != null) {
                MeasureDefinition prev = updatedMeasures.get(lastMeasureKey);
                if (prev != null) {
                    updatedMeasures.put(
                            lastMeasureKey,
                            new MeasureDefinition(
                                    prev.id(),
                                    prev.name(),
                                    prev.tableName(),
                                    prev.raxExpression(),
                                    formatMatcher.group(1),
                                    prev.description()));
                }
            }
        }

        SemanticModelSchema nextSchema = new SemanticModelSchema(
                current.modelId(),
                current.name(),
                current.fiscalYearStartMonth(),
                current.tables(),
                current.relationships(),
                new ArrayList<>(updatedMeasures.values()),
                current.rlsRolePredicates());
        registerModel(nextSchema);
        return nextSchema;
    }

    /**
     * Parses relationship declarations from an RSDL (model.rsdl) document.
     */
    public List<RelationshipDefinition> parseRelationshipsFromRsdl(String rsdlText) {
        List<RelationshipDefinition> parsed = new ArrayList<>();
        if (rsdlText == null || rsdlText.isBlank()) {
            return parsed;
        }
        Matcher matcher = RSDL_REL_PATTERN.matcher(rsdlText);
        while (matcher.find()) {
            String relName = matcher.group(1);
            String fromTable = matcher.group(2);
            String fromCol = matcher.group(3);
            String toTable = matcher.group(4);
            String toCol = matcher.group(5);
            boolean isActive = Boolean.parseBoolean(matcher.group(6));
            parsed.add(new RelationshipDefinition(
                    relName,
                    fromTable,
                    fromCol,
                    toTable,
                    toCol,
                    Cardinality.MANY_TO_ONE,
                    CrossFilterDirection.SINGLE,
                    isActive));
        }
        return parsed;
    }

    private boolean detectCycleDfs(
            String current,
            Map<String, List<String>> adjacency,
            Map<String, Integer> visitState) {
        visitState.put(current, 1);
        for (String neighbor : adjacency.getOrDefault(current, List.of())) {
            int state = visitState.getOrDefault(neighbor, 0);
            if (state == 1) {
                return true;
            }
            if (state == 0 && detectCycleDfs(neighbor, adjacency, visitState)) {
                return true;
            }
        }
        visitState.put(current, 2);
        return false;
    }

    /**
     * Constructs an empty initial semantic model schema; tables, relationships, and
     * measures
     * are populated by user-provided datasets and definitions.
     */
    private SemanticModelSchema buildDefaultRetailStarSchema() {
        return new SemanticModelSchema(
                UUID.fromString("a98f12b4-539c-4b6e-8210-90c1f6d90124"),
                "Untitled Project",
                1,
                List.of(),
                List.of(),
                List.of(),
                Map.of());
    }
}
