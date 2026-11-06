package com.rollercoaster.model;

public record MeasureDefinition(
        String id,
        String name,
        String tableName,
        String raxExpression,
        String formatString,
        String description
) {
    public MeasureDefinition {
        description = description != null ? description : "";
    }

    public MeasureDefinition(
            String id,
            String name,
            String tableName,
            String raxExpression,
            String formatString) {
        this(id, name, tableName, raxExpression, formatString, "");
    }
}
