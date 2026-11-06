package com.rollercoaster.ingestion;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Parameterizes streaming file parsers (including multi-sheet workbook selection via sheetName or sheetIndex
 * defaulting to primary visible sheet at index 0, custom delimiters, and expected column type hints).
 */
public record SchemaHints(
        String tenantId,
        String datasetId,
        SourceFormat format,
        String sheetName,
        Integer sheetIndex,
        char delimiter,
        boolean hasHeader,
        Charset encoding,
        Map<String, DataType> expectedColumnTypes
) {
    public SchemaHints {
        tenantId = tenantId != null ? tenantId : "default-tenant";
        datasetId = datasetId != null ? datasetId : "default-dataset";
        format = format != null ? format : SourceFormat.CSV;
        sheetIndex = sheetIndex != null ? sheetIndex : 0;
        delimiter = delimiter != '\0' ? delimiter : (format == SourceFormat.TSV ? '\t' : ',');
        encoding = encoding != null ? encoding : StandardCharsets.UTF_8;
        expectedColumnTypes = expectedColumnTypes != null
                ? Collections.unmodifiableMap(new LinkedHashMap<>(expectedColumnTypes))
                : Map.of();
    }

    public static SchemaHints defaults(SourceFormat format) {
        return new SchemaHints(
                "default-tenant",
                "default-dataset",
                format,
                null,
                0,
                format == SourceFormat.TSV ? '\t' : ',',
                true,
                StandardCharsets.UTF_8,
                Map.of()
        );
    }

    public static SchemaHints forSheet(String sheetName, Integer sheetIndex) {
        return new SchemaHints(
                "default-tenant",
                "default-dataset",
                SourceFormat.XLSX,
                sheetName,
                sheetIndex != null ? sheetIndex : 0,
                ',',
                true,
                StandardCharsets.UTF_8,
                Map.of()
        );
    }
}
