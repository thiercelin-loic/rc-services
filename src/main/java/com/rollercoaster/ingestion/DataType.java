package com.rollercoaster.ingestion;

/**
 * Supported analytical column data types inferred and enforced during ingestion and R-Query transformations.
 */
public enum DataType {
    INT64("Int64", true),
    FLOAT64("Float64", true),
    DECIMAL("Decimal", true),
    CURRENCY("Currency", true),
    BOOLEAN("Boolean", false),
    ISO_DATE("Date", false),
    ISO_DATETIME("DateTime", false),
    UTF8_STRING("String", false);

    private final String displayLabel;
    private final boolean numeric;

    DataType(String displayLabel, boolean numeric) {
        this.displayLabel = displayLabel;
        this.numeric = numeric;
    }

    public String displayLabel() {
        return displayLabel;
    }

    public boolean isNumeric() {
        return numeric;
    }

    /**
     * Resolves a {@link DataType} from a UI display label, enum name, or human-readable alias,
     * defaulting to {@link #UTF8_STRING} when the label is null, blank, or unrecognized.
     */
    public static DataType fromLabel(String label) {
        if (label == null || label.isBlank()) {
            return UTF8_STRING;
        }
        String normalized = label.trim().toUpperCase();
        return switch (normalized) {
            case "INT64", "INTEGER", "INT", "LONG", "WHOLE NUMBER", "NUMBER" -> INT64;
            case "FLOAT64", "FLOAT", "DOUBLE", "DECIMAL NUMBER" -> FLOAT64;
            case "DECIMAL", "FIXED DECIMAL NUMBER" -> DECIMAL;
            case "CURRENCY", "MONEY" -> CURRENCY;
            case "BOOLEAN", "BOOL", "TRUE/FALSE" -> BOOLEAN;
            case "DATE", "ISO_DATE" -> ISO_DATE;
            case "DATETIME", "ISO_DATETIME", "TIMESTAMP", "DATE/TIME" -> ISO_DATETIME;
            case "STRING", "UTF8_STRING", "TEXT", "VARCHAR" -> UTF8_STRING;
            default -> UTF8_STRING;
        };
    }
}
