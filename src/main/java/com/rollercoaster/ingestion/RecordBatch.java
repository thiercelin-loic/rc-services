package com.rollercoaster.ingestion;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Discrete streaming unit capped at 10,000 rows per batch.
 * Preserves inferred or transformed column schema metadata alongside valid rows and quarantined records.
 */
public record RecordBatch(
        int batchIndex,
        Map<String, DataType> schema,
        List<Map<String, Object>> rows,
        List<QuarantineRecord> quarantinedRecords
) {
    public static final int MAX_BATCH_ROWS = 10_000;

    public RecordBatch {
        if (rows != null && rows.size() > MAX_BATCH_ROWS) {
            throw new IllegalArgumentException(
                    "RecordBatch exceeds maximum allowed size of " + MAX_BATCH_ROWS + " rows: " + rows.size());
        }
        schema = schema != null ? Collections.unmodifiableMap(new LinkedHashMap<>(schema)) : Map.of();
        rows = rows != null ? List.copyOf(rows) : List.of();
        quarantinedRecords = quarantinedRecords != null ? List.copyOf(quarantinedRecords) : List.of();
    }

    public int rowCount() {
        return rows.size();
    }

    public int quarantineCount() {
        return quarantinedRecords.size();
    }
}
