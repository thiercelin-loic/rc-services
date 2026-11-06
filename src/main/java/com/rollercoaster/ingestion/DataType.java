package com.rollercoaster.ingestion;

/**
 * Supported analytical column data types inferred and enforced during ingestion and R-Query transformations.
 */
public enum DataType {
    INT64,
    FLOAT64,
    DECIMAL,
    CURRENCY,
    BOOLEAN,
    ISO_DATE,
    ISO_DATETIME,
    UTF8_STRING
}
