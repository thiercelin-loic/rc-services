package com.rollercoaster.model;

import java.util.List;

/**
 * Defines a Star Schema table (FACT or DIMENSION) with optional SCD Type 2 interval metadata,
 * Date Table designation, visibility, and estimated row count for cost-based routing.
 */
public record TableDefinition(
        String name,
        String role,
        String partitionColumn,
        long estimatedRows,
        boolean scdType2,
        String description,
        boolean isHidden,
        boolean isDateTable,
        String dateColumn,
        List<ColumnDefinition> columns
) {
    public TableDefinition {
        description = description != null ? description : "";
        dateColumn = dateColumn != null ? dateColumn : "";
        columns = columns != null ? List.copyOf(columns) : List.of();
    }

    public TableDefinition(
            String name,
            String role,
            String partitionColumn,
            long estimatedRows,
            boolean scdType2,
            List<ColumnDefinition> columns) {
        this(name, role, partitionColumn, estimatedRows, scdType2, "", false, false, "", columns);
    }

    public TableDefinition(
            String name,
            String role,
            String partitionColumn,
            long estimatedRows,
            boolean scdType2,
            String description,
            List<ColumnDefinition> columns) {
        this(name, role, partitionColumn, estimatedRows, scdType2, description, false, false, "", columns);
    }
}
