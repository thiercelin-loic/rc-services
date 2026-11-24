package com.rollercoaster.model;

import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Domain-neutral service that infers foreign-key relationships across arbitrary semantic model tables
 * and identifies candidate temporal columns for date-table designation.
 */
@Service
public class RelationshipInferenceService {

    private static final List<String> TABLE_PREFIXES = List.of("dim_", "fact_", "fct_", "d_", "f_", "tbl_", "t_");
    private static final List<String> KEY_SUFFIXES = List.of("_id", "_key", "_code", "_sk", "_fk", "_pk");

    public record RelationshipInferenceResult(
            List<RelationshipDefinition> relationships,
            Map<String, String> dateColumnsByTable
    ) {
        public RelationshipInferenceResult {
            relationships = relationships != null ? List.copyOf(relationships) : List.of();
            dateColumnsByTable = dateColumnsByTable != null ? Map.copyOf(dateColumnsByTable) : Map.of();
        }
    }

    private record CandidateEdge(
            TableDefinition fromTable,
            ColumnDefinition fromColumn,
            TableDefinition toTable,
            ColumnDefinition toColumn,
            boolean exactColumnMatch
    ) {}

    public RelationshipInferenceResult inferModelMetadata(List<TableDefinition> tables) {
        List<RelationshipDefinition> relationships = inferRelationshipsFromTables(tables);
        Map<String, String> dateColumns = inferDateColumnsByTable(tables);
        return new RelationshipInferenceResult(relationships, dateColumns);
    }

    /**
     * Infers directed many-to-one relationships across any set of tables without hardcoded domain table names.
     * Secondary role-playing relationships between the same entity pair are emitted as inactive (`isActive: false`).
     */
    public List<RelationshipDefinition> inferRelationshipsFromTables(List<TableDefinition> tables) {
        List<TableDefinition> validTables = sanitizeTables(tables);
        if (validTables.size() < 2) {
            return List.of();
        }

        List<CandidateEdge> candidates = collectCandidateEdges(validTables);
        List<RelationshipDefinition> inferred = new ArrayList<>();
        Set<String> emittedColumnEdges = new HashSet<>();
        Set<String> activeEntityPairs = new HashSet<>();

        for (CandidateEdge edge : candidates) {
            String edgeKey = buildEdgeKey(edge);
            if (emittedColumnEdges.add(edgeKey)) {
                String pairKey = canonicalPairKey(edge.fromTable().name(), edge.toTable().name());
                boolean isActive = activeEntityPairs.add(pairKey);
                inferred.add(toRelationshipDefinition(edge, isActive));
            }
        }
        return List.copyOf(inferred);
    }

    /**
     * Infers the primary date column name for a table, returning an empty string if none is detected.
     */
    public String inferDateColumnName(TableDefinition table) {
        if (table == null) {
            return "";
        }
        if (table.dateColumn() != null && !table.dateColumn().isBlank() && hasColumn(table, table.dateColumn())) {
            return table.dateColumn().trim();
        }
        return inferDateColumnName(table.columns());
    }

    /**
     * Infers the primary date column name from a column list, prioritizing temporal data types
     * before falling back to date naming conventions.
     */
    public String inferDateColumnName(List<ColumnDefinition> columns) {
        if (columns == null || columns.isEmpty()) {
            return "";
        }
        String typedCandidate = findTemporalTypedColumn(columns);
        if (!typedCandidate.isEmpty()) {
            return typedCandidate;
        }
        return findDateNamedColumn(columns);
    }

    public Map<String, String> inferDateColumnsByTable(List<TableDefinition> tables) {
        List<TableDefinition> validTables = sanitizeTables(tables);
        if (validTables.isEmpty()) {
            return Map.of();
        }
        Map<String, String> result = new LinkedHashMap<>();
        for (TableDefinition table : validTables) {
            String dateColumn = inferDateColumnName(table);
            if (!dateColumn.isEmpty()) {
                result.put(table.name(), dateColumn);
            }
        }
        return Map.copyOf(result);
    }

    private record TablePair(TableDefinition fromTable, TableDefinition toTable) {}

    private List<CandidateEdge> collectCandidateEdges(List<TableDefinition> tables) {
        List<CandidateEdge> exactMatches = new ArrayList<>();
        List<CandidateEdge> rolePlayingMatches = new ArrayList<>();

        for (TableDefinition fromTable : tables) {
            for (TableDefinition toTable : tables) {
                if (canLinkFromTo(fromTable, toTable)) {
                    collectEdgesBetween(new TablePair(fromTable, toTable), exactMatches, rolePlayingMatches);
                }
            }
        }

        List<CandidateEdge> ordered = new ArrayList<>(exactMatches.size() + rolePlayingMatches.size());
        ordered.addAll(exactMatches);
        ordered.addAll(rolePlayingMatches);
        return ordered;
    }

    private void collectEdgesBetween(
            TablePair pair,
            List<CandidateEdge> exactMatches,
            List<CandidateEdge> rolePlayingMatches) {
        for (ColumnDefinition fromCol : pair.fromTable().columns()) {
            if (fromCol == null || fromCol.name() == null || fromCol.name().isBlank()) {
                continue;
            }
            CandidateEdge edge = matchForeignKeyColumn(pair.fromTable(), fromCol, pair.toTable());
            if (edge == null) {
                continue;
            }
            if (edge.exactColumnMatch()) {
                exactMatches.add(edge);
            } else {
                rolePlayingMatches.add(edge);
            }
        }
    }

    private CandidateEdge matchForeignKeyColumn(
            TableDefinition fromTable,
            ColumnDefinition fromCol,
            TableDefinition toTable) {
        if (isOwnEntityPrimaryKey(fromTable, fromCol, toTable)) {
            return null;
        }
        for (ColumnDefinition toCol : toTable.columns()) {
            if (toCol == null || toCol.name() == null || toCol.name().isBlank()) {
                continue;
            }
            if (!isTargetLookupKey(toTable, toCol) || !areTypesCompatible(fromCol.dataType(), toCol.dataType())) {
                continue;
            }
            CandidateEdge candidate = new CandidateEdge(fromTable, fromCol, toTable, toCol, true);
            if (isExactKeyMatch(candidate)) {
                return candidate;
            }
            if (isPrefixedRolePlayingMatch(fromCol, toTable, toCol)) {
                return new CandidateEdge(fromTable, fromCol, toTable, toCol, false);
            }
        }
        return null;
    }

    private boolean canLinkFromTo(TableDefinition fromTable, TableDefinition toTable) {
        if (fromTable.name().equalsIgnoreCase(toTable.name())) {
            return false;
        }
        boolean fromIsDim = "DIMENSION".equalsIgnoreCase(fromTable.role());
        boolean toIsDim = "DIMENSION".equalsIgnoreCase(toTable.role());
        boolean toIsFact = "FACT".equalsIgnoreCase(toTable.role());
        return !(fromIsDim && (toIsDim || toIsFact));
    }

    private boolean isOwnEntityPrimaryKey(
            TableDefinition fromTable,
            ColumnDefinition fromCol,
            TableDefinition toTable) {
        if (!fromCol.isPrimaryKey() || fromCol.isForeignKey()) {
            return false;
        }
        String fromEntity = normalizeEntityStem(fromTable.name());
        String toEntity = normalizeEntityStem(toTable.name());
        String colLower = fromCol.name().toLowerCase(Locale.ROOT);
        return colLower.equals("id") || (colLower.startsWith(fromEntity) && !colLower.startsWith(toEntity));
    }

    private boolean isTargetLookupKey(TableDefinition toTable, ColumnDefinition toCol) {
        if (toCol.isPrimaryKey()) {
            return true;
        }
        String colLower = toCol.name().toLowerCase(Locale.ROOT);
        if (colLower.equals("id") || colLower.equals("key") || colLower.equals("code")) {
            return true;
        }
        String toEntity = normalizeEntityStem(toTable.name());
        return hasKeySuffix(colLower) && (colLower.startsWith(toEntity) || "DIMENSION".equalsIgnoreCase(toTable.role()));
    }

    private boolean isExactKeyMatch(CandidateEdge candidate) {
        if (!candidate.fromColumn().name().equalsIgnoreCase(candidate.toColumn().name())) {
            return false;
        }
        String colLower = candidate.fromColumn().name().toLowerCase(Locale.ROOT);
        if (colLower.equals("id") || colLower.equals("key") || colLower.equals("code")) {
            return false;
        }
        return isManyToOneDirection(candidate);
    }

    private boolean isManyToOneDirection(CandidateEdge candidate) {
        TableDefinition fromTable = candidate.fromTable();
        ColumnDefinition fromCol = candidate.fromColumn();
        TableDefinition toTable = candidate.toTable();
        ColumnDefinition toCol = candidate.toColumn();
        if ("FACT".equalsIgnoreCase(fromTable.role()) && "DIMENSION".equalsIgnoreCase(toTable.role())) {
            return true;
        }
        if (toCol.isPrimaryKey() && (!fromCol.isPrimaryKey() || fromCol.isForeignKey())) {
            return true;
        }
        String toEntity = normalizeEntityStem(toTable.name());
        String fromEntity = normalizeEntityStem(fromTable.name());
        String colStem = stripKeySuffix(toCol.name().toLowerCase(Locale.ROOT));
        return colStem.equals(toEntity) && !colStem.equals(fromEntity);
    }

    private boolean isPrefixedRolePlayingMatch(
            ColumnDefinition fromCol,
            TableDefinition toTable,
            ColumnDefinition toCol) {
        String fromLower = fromCol.name().toLowerCase(Locale.ROOT);
        String toLower = toCol.name().toLowerCase(Locale.ROOT);
        String toEntity = normalizeEntityStem(toTable.name());

        if (toLower.equals("id") || toLower.equals("key") || toLower.equals("code")) {
            String expectedFk = toEntity + "_" + toLower;
            return fromLower.equals(expectedFk) || fromLower.endsWith("_" + expectedFk);
        }
        return hasKeySuffix(toLower) && fromLower.endsWith("_" + toLower);
    }

    private String findTemporalTypedColumn(List<ColumnDefinition> columns) {
        String firstTemporal = "";
        for (ColumnDefinition col : columns) {
            if (col == null || col.name() == null || col.name().isBlank()) {
                continue;
            }
            if (isTemporalDataType(col.dataType())) {
                if (isPreferredDateColumnName(col.name()) || col.isPrimaryKey()) {
                    return col.name();
                }
                if (firstTemporal.isEmpty()) {
                    firstTemporal = col.name();
                }
            }
        }
        return firstTemporal;
    }

    private String findDateNamedColumn(List<ColumnDefinition> columns) {
        String fallback = "";
        for (ColumnDefinition col : columns) {
            if (col == null || col.name() == null || col.name().isBlank()) {
                continue;
            }
            if (isPreferredDateColumnName(col.name())) {
                return col.name();
            }
            if (fallback.isEmpty() && col.name().toLowerCase(Locale.ROOT).contains("date")) {
                fallback = col.name();
            }
        }
        return fallback;
    }

    private static boolean isTemporalDataType(String dataType) {
        if (dataType == null || dataType.isBlank()) {
            return false;
        }
        String upper = dataType.trim().toUpperCase(Locale.ROOT);
        return upper.contains("DATE") || upper.contains("TIME");
    }

    private static boolean isPreferredDateColumnName(String colName) {
        String lower = colName.trim().toLowerCase(Locale.ROOT);
        return lower.equals("date")
                || lower.equals("calendar_date")
                || lower.equals("full_date")
                || lower.equals("date_id")
                || lower.equals("date_key")
                || lower.endsWith("_date");
    }

    private static boolean areTypesCompatible(String fromType, String toType) {
        if (fromType == null || fromType.isBlank() || toType == null || toType.isBlank()) {
            return true;
        }
        String normFrom = normalizeTypeCategory(fromType);
        String normTo = normalizeTypeCategory(toType);
        return normFrom.equals(normTo);
    }

    private static String normalizeTypeCategory(String rawType) {
        String upper = rawType.trim().toUpperCase(Locale.ROOT);
        if (upper.contains("INT") || upper.contains("NUM") || upper.contains("DEC")
                || upper.contains("DOUBLE") || upper.contains("FLOAT") || upper.contains("BIGINT")) {
            return "NUMERIC";
        }
        if (upper.contains("BOOL")) {
            return "BOOLEAN";
        }
        if (upper.contains("DATE") || upper.contains("TIME")) {
            return "TEMPORAL";
        }
        return "TEXT";
    }

    private static List<TableDefinition> sanitizeTables(List<TableDefinition> tables) {
        if (tables == null || tables.isEmpty()) {
            return List.of();
        }
        return tables.stream()
                .filter(t -> t != null && t.name() != null && !t.name().isBlank())
                .toList();
    }

    private static boolean hasColumn(TableDefinition table, String columnName) {
        return table.columns().stream()
                .anyMatch(c -> c != null && c.name() != null && c.name().equalsIgnoreCase(columnName.trim()));
    }

    private static boolean hasKeySuffix(String lowerColumnName) {
        for (String suffix : KEY_SUFFIXES) {
            if (lowerColumnName.endsWith(suffix) && lowerColumnName.length() > suffix.length()) {
                return true;
            }
        }
        return false;
    }

    private static String stripKeySuffix(String lowerColumnName) {
        for (String suffix : KEY_SUFFIXES) {
            if (lowerColumnName.endsWith(suffix) && lowerColumnName.length() > suffix.length()) {
                return lowerColumnName.substring(0, lowerColumnName.length() - suffix.length());
            }
        }
        return lowerColumnName;
    }

    static String normalizeEntityStem(String rawTableName) {
        if (rawTableName == null || rawTableName.isBlank()) {
            return "";
        }
        String lower = rawTableName.trim().toLowerCase(Locale.ROOT);
        for (String prefix : TABLE_PREFIXES) {
            if (lower.startsWith(prefix) && lower.length() > prefix.length()) {
                lower = lower.substring(prefix.length());
                break;
            }
        }
        if (lower.endsWith("ies") && lower.length() > 3) {
            return lower.substring(0, lower.length() - 3) + "y";
        }
        if (lower.endsWith("ses") && lower.length() > 3) {
            return lower.substring(0, lower.length() - 2);
        }
        if (lower.endsWith("s") && !lower.endsWith("ss") && lower.length() > 2) {
            return lower.substring(0, lower.length() - 1);
        }
        return lower;
    }

    private static String canonicalPairKey(String tableA, String tableB) {
        String a = tableA.toLowerCase(Locale.ROOT);
        String b = tableB.toLowerCase(Locale.ROOT);
        return a.compareTo(b) < 0 ? a + "::" + b : b + "::" + a;
    }

    private static String buildEdgeKey(CandidateEdge edge) {
        return (edge.fromTable().name() + "." + edge.fromColumn().name()
                + "->" + edge.toTable().name() + "." + edge.toColumn().name()).toLowerCase(Locale.ROOT);
    }

    private static RelationshipDefinition toRelationshipDefinition(CandidateEdge edge, boolean isActive) {
        String name = "rel_"
                + sanitizeToken(edge.fromTable().name()) + "_"
                + sanitizeToken(edge.fromColumn().name()) + "_"
                + sanitizeToken(edge.toTable().name());
        return new RelationshipDefinition(
                name,
                edge.fromTable().name(),
                edge.fromColumn().name(),
                edge.toTable().name(),
                edge.toColumn().name(),
                Cardinality.MANY_TO_ONE,
                CrossFilterDirection.SINGLE,
                isActive
        );
    }

    private static String sanitizeToken(String value) {
        return value.trim().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "_");
    }
}
