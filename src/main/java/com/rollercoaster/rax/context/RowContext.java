package com.rollercoaster.rax.context;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

public class RowContext {

    private final String tableName;
    private final String primaryKeyColumn;
    private final Map<String, Object> rowTuple;
    private final Set<String> activeCalculatedColumnStack;

    public RowContext(String tableName, String primaryKeyColumn, Map<String, Object> rowTuple) {
        this(tableName, primaryKeyColumn, rowTuple, Set.of());
    }

    public RowContext(
            String tableName,
            String primaryKeyColumn,
            Map<String, Object> rowTuple,
            Set<String> activeCalculatedColumnStack) {
        this.tableName = tableName != null ? tableName.replace("'", "").trim().toLowerCase() : "";
        this.primaryKeyColumn = primaryKeyColumn != null ? primaryKeyColumn.trim().toLowerCase() : null;
        this.rowTuple = new LinkedHashMap<>();
        if (rowTuple != null) {
            rowTuple.forEach((key, value) -> this.rowTuple.put(key.trim().toLowerCase(), value));
        }
        this.activeCalculatedColumnStack = activeCalculatedColumnStack != null
                ? Set.copyOf(activeCalculatedColumnStack)
                : Set.of();
    }

    public String getTableName() {
        return tableName;
    }

    public String getPrimaryKeyColumn() {
        return primaryKeyColumn;
    }

    public Map<String, Object> getRowTuple() {
        return Collections.unmodifiableMap(rowTuple);
    }

    public Set<String> getActiveCalculatedColumnStack() {
        return activeCalculatedColumnStack;
    }

    public boolean hasPrimaryKeyIndex() {
        return primaryKeyColumn != null && !primaryKeyColumn.isBlank() && rowTuple.containsKey(primaryKeyColumn);
    }

    public Object getValue(String columnName) {
        String cleanedColumnName = columnName.replace("[", "").replace("]", "").trim().toLowerCase();
        return rowTuple.get(cleanedColumnName);
    }

    public Map<String, Set<Object>> toContextTransitionPredicates() {
        Map<String, Set<Object>> predicates = new LinkedHashMap<>();
        if (rowTuple.isEmpty()) {
            return predicates;
        }
        if (hasPrimaryKeyIndex()) {
            String qualifiedKey = tableName + "[" + primaryKeyColumn + "]";
            predicates.put(qualifiedKey, Set.of(rowTuple.get(primaryKeyColumn)));
            return predicates;
        }
        for (Map.Entry<String, Object> entry : rowTuple.entrySet()) {
            if (entry.getValue() != null) {
                String qualifiedColumnName = tableName + "[" + entry.getKey() + "]";
                predicates.put(qualifiedColumnName, Set.of(entry.getValue()));
            }
        }
        return predicates;
    }
}
