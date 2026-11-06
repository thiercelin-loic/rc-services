package com.rollercoaster.model;

/**
 * Definition of a column in the semantic model.
 * Includes optional metadata used by the front‑end model‑view.
 */
public record ColumnDefinition(
        String name,
        String dataType,
        boolean isPrimaryKey,
        boolean isForeignKey,
        boolean isHidden,
        String piiClassification,
        String description,
        String displayFolder,
        String summarizeBy,
        String expression,
        String sortByColumn,
        String format
) {
    /**
     * Canonical constructor that sanitises optional fields.
     */
    public ColumnDefinition {
        description = description != null ? description : "";
        displayFolder = displayFolder != null ? displayFolder : "";
        summarizeBy = (summarizeBy != null && !summarizeBy.isBlank()) ? summarizeBy : "default";
        expression = expression != null ? expression : "";
        sortByColumn = sortByColumn != null ? sortByColumn : "";
        format = format != null ? format : "";
    }

    /**
     * Minimal constructor used by legacy callers.
     */
    public ColumnDefinition(String name, String dataType, boolean isPrimaryKey, boolean isForeignKey) {
        this(name, dataType, isPrimaryKey, isForeignKey, false, null, "", "", "default", "", "", "");
    }

    /**
     * Constructor with hidden flag and PII classification.
     */
    public ColumnDefinition(String name, String dataType, boolean isPrimaryKey, boolean isForeignKey,
                            boolean isHidden, String piiClassification) {
        this(name, dataType, isPrimaryKey, isForeignKey, isHidden, piiClassification, "", "", "default", "", "", "");
    }

    /**
     * Constructor with description.
     */
    public ColumnDefinition(String name, String dataType, boolean isPrimaryKey, boolean isForeignKey,
                            boolean isHidden, String piiClassification, String description) {
        this(name, dataType, isPrimaryKey, isForeignKey, isHidden, piiClassification, description, "", "default", "", "", "");
    }
}
