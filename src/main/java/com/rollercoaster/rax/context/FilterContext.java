package com.rollercoaster.rax.context;

import com.rollercoaster.rax.ast.SqlDialect;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

public class FilterContext {

    private static final String HASH_ALGORITHM = "SHA-256";
    private static final int CONTEXT_HASH_LENGTH = 16;
    private static final String SQL_TRUE_PREDICATE = "1 = 1";
    private static final String SQL_FALSE_PREDICATE = "1 = 0";
    private static final String SQL_AND_JOINER = " AND ";
    private static final String SQL_IN_SEPARATOR = ", ";
    private static final String CROSS_FILTER_SEPARATOR = "->";
    private static final char OPEN_BRACKET = '[';
    private static final char CLOSE_BRACKET = ']';

    private final Map<String, Set<Object>> columnFilters;
    private final Map<String, Set<Object>> externalSlicerFilters;
    private final Map<String, String> activeRelationshipOverrides;
    private final Map<String, String> crossFilterOverrides;
    private final Set<String> keepFilterColumns;
    private final List<String> customSqlPredicates;
    private final Set<String> inScopeColumns;

    public FilterContext() {
        this.columnFilters = new LinkedHashMap<>();
        this.externalSlicerFilters = new LinkedHashMap<>();
        this.activeRelationshipOverrides = new LinkedHashMap<>();
        this.crossFilterOverrides = new LinkedHashMap<>();
        this.keepFilterColumns = new LinkedHashSet<>();
        this.customSqlPredicates = new ArrayList<>();
        this.inScopeColumns = new LinkedHashSet<>();
    }

    private FilterContext(FilterContext source) {
        this.columnFilters = new LinkedHashMap<>();
        source.columnFilters.forEach((key, values) -> this.columnFilters.put(key, new LinkedHashSet<>(values)));
        this.externalSlicerFilters = new LinkedHashMap<>();
        source.externalSlicerFilters.forEach((key, values) -> this.externalSlicerFilters.put(key, new LinkedHashSet<>(values)));
        this.activeRelationshipOverrides = new LinkedHashMap<>(source.activeRelationshipOverrides);
        this.crossFilterOverrides = new LinkedHashMap<>(source.crossFilterOverrides);
        this.keepFilterColumns = new LinkedHashSet<>(source.keepFilterColumns);
        this.customSqlPredicates = new ArrayList<>(source.customSqlPredicates);
        this.inScopeColumns = new LinkedHashSet<>(source.inScopeColumns);
    }

    public FilterContext cloneContext() {
        return new FilterContext(this);
    }

    public FilterContext withFilter(String qualifiedColumn, Collection<?> values) {
        FilterContext copy = cloneContext();
        copy.replaceFilter(qualifiedColumn, new LinkedHashSet<>(values));
        return copy;
    }

    public FilterContext withExternalSlicer(String qualifiedColumn, Collection<?> values) {
        FilterContext copy = cloneContext();
        String key = normalizeColumnName(qualifiedColumn);
        copy.externalSlicerFilters.put(key, new LinkedHashSet<>(values));
        copy.columnFilters.put(key, new LinkedHashSet<>(values));
        return copy;
    }

    public FilterContext withInScopeColumn(String qualifiedColumn) {
        FilterContext copy = cloneContext();
        copy.inScopeColumns.add(normalizeColumnName(qualifiedColumn));
        return copy;
    }

    public boolean isInScope(String qualifiedColumn) {
        return inScopeColumns.contains(normalizeColumnName(qualifiedColumn));
    }

    public void applyAllModifier(String tableOrColumn) {
        if (tableOrColumn == null || tableOrColumn.isBlank()) {
            columnFilters.clear();
            customSqlPredicates.clear();
            return;
        }
        String normalized = normalizeColumnName(tableOrColumn);
        if (normalized.indexOf(OPEN_BRACKET) >= 0) {
            columnFilters.remove(normalized);
            keepFilterColumns.remove(normalized);
        } else {
            String tablePrefix = normalized + OPEN_BRACKET;
            columnFilters.keySet().removeIf(column -> column.startsWith(tablePrefix));
            keepFilterColumns.removeIf(column -> column.startsWith(tablePrefix));
        }
    }

    public void applyAllExceptModifier(String tableName, List<String> preservedColumns) {
        String tablePrefix = normalizeColumnName(tableName) + OPEN_BRACKET;
        Set<String> preservedNormalized = preservedColumns.stream()
                .map(this::normalizeColumnName)
                .collect(Collectors.toSet());
        columnFilters.keySet().removeIf(column -> column.startsWith(tablePrefix) && !preservedNormalized.contains(column));
    }

    public void applyAllSelectedModifier(String tableOrColumn) {
        applyAllModifier(tableOrColumn);
        if (tableOrColumn == null || tableOrColumn.isBlank()) {
            externalSlicerFilters.forEach((key, values) -> columnFilters.put(key, new LinkedHashSet<>(values)));
            return;
        }
        String normalized = normalizeColumnName(tableOrColumn);
        if (normalized.indexOf(OPEN_BRACKET) >= 0) {
            if (externalSlicerFilters.containsKey(normalized)) {
                columnFilters.put(normalized, new LinkedHashSet<>(externalSlicerFilters.get(normalized)));
            }
        } else {
            String tablePrefix = normalized + OPEN_BRACKET;
            externalSlicerFilters.forEach((key, values) -> {
                if (key.startsWith(tablePrefix)) {
                    columnFilters.put(key, new LinkedHashSet<>(values));
                }
            });
        }
    }

    public void activateRelationship(String fromColumn, String toColumn) {
        activeRelationshipOverrides.put(normalizeColumnName(fromColumn), normalizeColumnName(toColumn));
    }

    public void overrideCrossFilter(String leftColumn, String rightColumn, String direction) {
        String key = normalizeColumnName(leftColumn) + CROSS_FILTER_SEPARATOR + normalizeColumnName(rightColumn);
        crossFilterOverrides.put(key, direction.toUpperCase());
    }

    public void markKeepFilters(String qualifiedColumn) {
        keepFilterColumns.add(normalizeColumnName(qualifiedColumn));
    }

    public void replaceFilter(String qualifiedColumn, Set<Object> allowedValues) {
        String key = normalizeColumnName(qualifiedColumn);
        if (keepFilterColumns.contains(key) && columnFilters.containsKey(key)) {
            intersectFilter(qualifiedColumn, allowedValues);
        } else {
            columnFilters.put(key, new LinkedHashSet<>(allowedValues));
        }
    }

    public void intersectFilter(String qualifiedColumn, Set<Object> allowedValues) {
        String key = normalizeColumnName(qualifiedColumn);
        if (columnFilters.containsKey(key)) {
            Set<Object> intersection = new LinkedHashSet<>(columnFilters.get(key));
            intersection.retainAll(allowedValues);
            columnFilters.put(key, intersection);
        } else {
            columnFilters.put(key, new LinkedHashSet<>(allowedValues));
        }
    }

    public void addSqlPredicate(String sqlPredicate) {
        if (sqlPredicate != null && !sqlPredicate.isBlank()) {
            this.customSqlPredicates.add(sqlPredicate);
        }
    }

    public Map<String, Set<Object>> getColumnFilters() {
        return Collections.unmodifiableMap(columnFilters);
    }

    public Map<String, String> getActiveRelationshipOverrides() {
        return Collections.unmodifiableMap(activeRelationshipOverrides);
    }

    public Map<String, String> getCrossFilterOverrides() {
        return Collections.unmodifiableMap(crossFilterOverrides);
    }

    public List<String> getCustomSqlPredicates() {
        return Collections.unmodifiableList(customSqlPredicates);
    }

    public Object getSelectedValue(String qualifiedColumn, Object alternateResult) {
        String key = normalizeColumnName(qualifiedColumn);
        Set<Object> values = columnFilters.get(key);
        if (values != null && values.size() == 1) {
            return values.iterator().next();
        }
        return alternateResult;
    }

    public String toSqlWhereClause(SqlDialect dialect) {
        List<String> clauses = new ArrayList<>();
        for (Map.Entry<String, Set<Object>> entry : columnFilters.entrySet()) {
            clauses.add(formatColumnPredicate(entry, dialect));
        }
        clauses.addAll(customSqlPredicates);
        return clauses.isEmpty() ? SQL_TRUE_PREDICATE : String.join(SQL_AND_JOINER, clauses);
    }

    public String computeContextHash() {
        try {
            MessageDigest digest = MessageDigest.getInstance(HASH_ALGORITHM);
            String canonical = toSqlWhereClause(SqlDialect.DUCKDB)
                    + "|rel:" + activeRelationshipOverrides
                    + "|cf:" + crossFilterOverrides
                    + "|scope:" + inScopeColumns;
            byte[] hash = digest.digest(canonical.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash).substring(0, CONTEXT_HASH_LENGTH);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("Security provider missing required hash algorithm: " + HASH_ALGORITHM, exception);
        }
    }

    public String normalizeColumnName(String rawColumnName) {
        Objects.requireNonNull(rawColumnName, "Column or table reference cannot be null");
        return rawColumnName.trim().replace("'", "").toLowerCase();
    }

    private String formatColumnPredicate(Map.Entry<String, Set<Object>> entry, SqlDialect dialect) {
        String rawKey = entry.getKey();
        String operator = "EQ";
        String baseColumn = rawKey;
        int colonIdx = rawKey.lastIndexOf(':');
        if (colonIdx > 0) {
            String suffix = rawKey.substring(colonIdx + 1).trim().toUpperCase();
            if ("EQ".equals(suffix) || "NEQ".equals(suffix) || "IN".equals(suffix) || "BETWEEN".equals(suffix)) {
                operator = suffix;
                baseColumn = rawKey.substring(0, colonIdx).trim();
            }
        }

        String columnSql = toSqlColumnReference(baseColumn, dialect);
        Set<Object> values = entry.getValue();

        if (values.isEmpty()) {
            return SQL_FALSE_PREDICATE;
        }

        if ("NEQ".equals(operator)) {
            if (values.size() == 1) {
                Object singleValue = values.iterator().next();
                return columnSql + " <> " + formatLiteral(singleValue);
            }
            String notInList = values.stream()
                    .map(this::formatLiteral)
                    .collect(Collectors.joining(SQL_IN_SEPARATOR));
            return columnSql + " NOT IN (" + notInList + ")";
        }

        if ("BETWEEN".equals(operator)) {
            List<Object> bounds = new ArrayList<>(values);
            if (bounds.size() == 1 && bounds.get(0) instanceof String strVal) {
                String[] parts = strVal.contains("..")
                        ? strVal.split("\\.\\.", 2)
                        : strVal.contains(",")
                                ? strVal.split(",", 2)
                                : new String[]{strVal};
                if (parts.length == 2) {
                    return columnSql + " BETWEEN " + formatBoundLiteral(parts[0].trim())
                            + " AND " + formatBoundLiteral(parts[1].trim());
                }
            }
            if (bounds.size() >= 2) {
                return columnSql + " BETWEEN " + formatBoundLiteral(bounds.get(0))
                        + " AND " + formatBoundLiteral(bounds.get(1));
            }
            return columnSql + " >= " + formatBoundLiteral(bounds.get(0));
        }

        if (values.size() == 1) {
            Object singleValue = values.iterator().next();
            return columnSql + " = " + formatLiteral(singleValue);
        }

        String inList = values.stream()
                .map(this::formatLiteral)
                .collect(Collectors.joining(SQL_IN_SEPARATOR));
        return columnSql + " IN (" + inList + ")";
    }

    private String formatBoundLiteral(Object value) {
        if (value instanceof String str) {
            try {
                if (str.contains(".")) {
                    return Double.toString(Double.parseDouble(str));
                }
                return Long.toString(Long.parseLong(str));
            } catch (NumberFormatException ignored) {
                return formatLiteral(str);
            }
        }
        return formatLiteral(value);
    }

    private String toSqlColumnReference(String normalizedColumn, SqlDialect dialect) {
        int bracketIndex = normalizedColumn.indexOf(OPEN_BRACKET);
        if (bracketIndex > 0 && normalizedColumn.endsWith(String.valueOf(CLOSE_BRACKET))) {
            String table = normalizedColumn.substring(0, bracketIndex).trim().replace(' ', '_');
            String column = normalizedColumn.substring(bracketIndex + 1, normalizedColumn.length() - 1).trim().replace(' ', '_');
            return table + "." + column;
        }
        if (normalizedColumn.startsWith(String.valueOf(OPEN_BRACKET)) && normalizedColumn.endsWith(String.valueOf(CLOSE_BRACKET))) {
            return normalizedColumn.substring(1, normalizedColumn.length() - 1).replace(' ', '_');
        }
        return normalizedColumn.replace(' ', '_');
    }

    private String formatLiteral(Object value) {
        if (value == null) {
            return "NULL";
        }
        if (value instanceof Number || value instanceof Boolean) {
            return value.toString();
        }
        return "'" + value.toString().replace("'", "''") + "'";
    }
}
