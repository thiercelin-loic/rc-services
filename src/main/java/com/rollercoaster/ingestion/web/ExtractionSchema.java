package com.rollercoaster.ingestion.web;

import com.rollercoaster.ingestion.DataType;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Declares CSS selector paths for Jsoup HTML table scraping and column datatype normalization.
 */
public record ExtractionSchema(
        String tableSelector,
        String rowSelector,
        Map<String, String> columnCssSelectors,
        Map<String, DataType> columnTypes
) {
    public ExtractionSchema {
        tableSelector = tableSelector != null ? tableSelector : "table";
        rowSelector = rowSelector != null ? rowSelector : "tbody tr";
        columnCssSelectors = columnCssSelectors != null
                ? Collections.unmodifiableMap(new LinkedHashMap<>(columnCssSelectors))
                : Map.of();
        columnTypes = columnTypes != null
                ? Collections.unmodifiableMap(new LinkedHashMap<>(columnTypes))
                : Map.of();
    }
}
