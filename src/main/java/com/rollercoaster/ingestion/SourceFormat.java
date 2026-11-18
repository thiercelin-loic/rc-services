package com.rollercoaster.ingestion;

/**
 * Supported multi-format file and stream source types.
 */
public enum SourceFormat {
    CSV,
    TSV,
    JSON,
    XML,
    XLSX,
    AVRO,
    REST_API,
    HTML_SCRAPER
}
