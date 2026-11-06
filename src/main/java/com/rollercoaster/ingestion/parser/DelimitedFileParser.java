package com.rollercoaster.ingestion.parser;

import com.rollercoaster.ingestion.DataType;
import com.rollercoaster.ingestion.FileParser;
import com.rollercoaster.ingestion.QuarantineRecord;
import com.rollercoaster.ingestion.RecordBatch;
import com.rollercoaster.ingestion.SchemaHints;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.channels.Channels;
import java.nio.channels.SeekableByteChannel;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Streaming parser for linear delimited formats (CSV, TSV).
 * Processes streams in discrete RecordBatch units capped at 10,000 rows,
 * infers datatypes across the initial batch, and diverts invalid rows to quarantine_records.
 */
@Component
public class DelimitedFileParser implements FileParser {

    private final TypeInferenceEngine typeInferenceEngine;

    public DelimitedFileParser(TypeInferenceEngine typeInferenceEngine) {
        this.typeInferenceEngine = typeInferenceEngine;
    }

    @Override
    public Flux<RecordBatch> parseStream(InputStream stream, SchemaHints hints) {
        return Flux.<RecordBatch>create(sink -> {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, hints.encoding()))) {
                String headerLine = reader.readLine();
                if (headerLine == null) {
                    sink.complete();
                    return;
                }
                // Strip UTF-8 BOM if present
                if (headerLine.startsWith("\uFEFF")) {
                    headerLine = headerLine.substring(1);
                }
                List<String> headers = parseDelimitedLine(headerLine, hints.delimiter());

                List<Map<String, Object>> currentRawRows = new ArrayList<>();
                List<Long> lineNumbers = new ArrayList<>();
                Map<String, DataType> activeSchema = null;
                int batchIndex = 0;
                long lineNum = 1;

                String line;
                while ((line = reader.readLine()) != null) {
                    lineNum++;
                    if (line.isBlank()) {
                        continue;
                    }
                    List<String> values = parseDelimitedLine(line, hints.delimiter());
                    Map<String, Object> rawRow = new LinkedHashMap<>();
                    for (int i = 0; i < headers.size(); i++) {
                        String val = i < values.size() ? values.get(i) : null;
                        rawRow.put(headers.get(i), val);
                    }
                    currentRawRows.add(rawRow);
                    lineNumbers.add(lineNum);

                    if (currentRawRows.size() == RecordBatch.MAX_BATCH_ROWS) {
                        if (activeSchema == null) {
                            activeSchema = typeInferenceEngine.inferSchemaFromSample(
                                    currentRawRows.subList(0, Math.min(100, currentRawRows.size())),
                                    hints.expectedColumnTypes());
                        }
                        sink.next(buildValidatedBatch(batchIndex++, activeSchema, currentRawRows, lineNumbers, hints));
                        currentRawRows.clear();
                        lineNumbers.clear();
                    }
                }

                if (!currentRawRows.isEmpty()) {
                    if (activeSchema == null) {
                        activeSchema = typeInferenceEngine.inferSchemaFromSample(
                                currentRawRows.subList(0, Math.min(100, currentRawRows.size())),
                                hints.expectedColumnTypes());
                    }
                    sink.next(buildValidatedBatch(batchIndex, activeSchema, currentRawRows, lineNumbers, hints));
                }
                sink.complete();
            } catch (Exception e) {
                sink.error(e);
            }
        }).subscribeOn(Schedulers.boundedElastic());
    }

    @Override
    public Flux<RecordBatch> parseChannel(SeekableByteChannel channel, SchemaHints hints) {
        return parseStream(Channels.newInputStream(channel), hints);
    }

    private RecordBatch buildValidatedBatch(
            int batchIndex,
            Map<String, DataType> schema,
            List<Map<String, Object>> rawRows,
            List<Long> lineNumbers,
            SchemaHints hints) {
        List<Map<String, Object>> validRows = new ArrayList<>(rawRows.size());
        List<QuarantineRecord> quarantined = new ArrayList<>();

        for (int i = 0; i < rawRows.size(); i++) {
            Map<String, Object> rawRow = rawRows.get(i);
            long lineNum = lineNumbers.get(i);
            Map<String, Object> typedRow = new LinkedHashMap<>();
            boolean failed = false;

            for (Map.Entry<String, Object> entry : rawRow.entrySet()) {
                String col = entry.getKey();
                DataType targetType = schema.getOrDefault(col, DataType.UTF8_STRING);
                try {
                    Object coerced = typeInferenceEngine.coerceValue(entry.getValue(), targetType);
                    typedRow.put(col, coerced);
                } catch (Exception ex) {
                    quarantined.add(new QuarantineRecord(
                            hints.tenantId(),
                            hints.datasetId(),
                            lineNum,
                            col,
                            targetType.name(),
                            rawRow.toString(),
                            "Datatype constraint failure on column '" + col + "': " + ex.getMessage()
                    ));
                    failed = true;
                    break;
                }
            }
            if (!failed) {
                validRows.add(typedRow);
            }
        }
        return new RecordBatch(batchIndex, schema, validRows, quarantined);
    }

    public List<String> parseDelimitedLine(String line, char delimiter) {
        List<String> fields = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inQuotes = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '"') {
                if (inQuotes && i + 1 < line.length() && line.charAt(i + 1) == '"') {
                    current.append('"');
                    i++;
                } else {
                    inQuotes = !inQuotes;
                }
            } else if (c == delimiter && !inQuotes) {
                fields.add(current.toString().trim());
                current.setLength(0);
            } else {
                current.append(c);
            }
        }
        fields.add(current.toString().trim());
        return fields;
    }
}
