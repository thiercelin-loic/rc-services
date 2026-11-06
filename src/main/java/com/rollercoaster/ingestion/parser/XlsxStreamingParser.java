package com.rollercoaster.ingestion.parser;

import com.rollercoaster.ingestion.DataType;
import com.rollercoaster.ingestion.FileParser;
import com.rollercoaster.ingestion.QuarantineRecord;
import com.rollercoaster.ingestion.RecordBatch;
import com.rollercoaster.ingestion.SchemaHints;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.DateUtil;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;

import java.io.InputStream;
import java.nio.channels.Channels;
import java.nio.channels.SeekableByteChannel;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Multi-sheet spreadsheet (.xlsx) parser parameterized via SchemaHints.sheetName or SchemaHints.sheetIndex,
 * defaulting to the primary visible sheet at index 0.
 * Offloads blocking Apache POI I/O to Schedulers.boundedElastic().
 */
@Component
public class XlsxStreamingParser implements FileParser {

    private final TypeInferenceEngine typeInferenceEngine;

    public XlsxStreamingParser(TypeInferenceEngine typeInferenceEngine) {
        this.typeInferenceEngine = typeInferenceEngine;
    }

    @Override
    public Flux<RecordBatch> parseStream(InputStream stream, SchemaHints hints) {
        return Flux.<RecordBatch>create(sink -> {
            try (Workbook workbook = new XSSFWorkbook(stream)) {
                Sheet sheet = resolveTargetSheet(workbook, hints);
                if (sheet == null) {
                    sink.complete();
                    return;
                }

                List<String> headers = new ArrayList<>();
                List<Map<String, Object>> rawBatch = new ArrayList<>();
                List<Long> rowNumbers = new ArrayList<>();
                Map<String, DataType> schema = null;
                int batchIdx = 0;
                boolean headerParsed = false;

                for (Row row : sheet) {
                    if (!headerParsed) {
                        for (Cell cell : row) {
                            headers.add(extractCellValueAsString(cell));
                        }
                        headerParsed = true;
                        continue;
                    }

                    Map<String, Object> rowMap = new LinkedHashMap<>();
                    for (int c = 0; c < headers.size(); c++) {
                        Cell cell = row.getCell(c, Row.MissingCellPolicy.RETURN_BLANK_AS_NULL);
                        rowMap.put(headers.get(c), extractCellValue(cell));
                    }
                    rawBatch.add(rowMap);
                    rowNumbers.add((long) row.getRowNum() + 1);

                    if (rawBatch.size() == RecordBatch.MAX_BATCH_ROWS) {
                        if (schema == null) {
                            schema = typeInferenceEngine.inferSchemaFromSample(
                                    rawBatch.subList(0, Math.min(100, rawBatch.size())),
                                    hints.expectedColumnTypes());
                        }
                        sink.next(validateBatch(batchIdx++, schema, rawBatch, rowNumbers, hints));
                        rawBatch.clear();
                        rowNumbers.clear();
                    }
                }

                if (!rawBatch.isEmpty()) {
                    if (schema == null) {
                        schema = typeInferenceEngine.inferSchemaFromSample(
                                rawBatch.subList(0, Math.min(100, rawBatch.size())),
                                hints.expectedColumnTypes());
                    }
                    sink.next(validateBatch(batchIdx, schema, rawBatch, rowNumbers, hints));
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

    private Sheet resolveTargetSheet(Workbook workbook, SchemaHints hints) {
        if (workbook.getNumberOfSheets() == 0) {
            return null;
        }
        if (hints.sheetName() != null && !hints.sheetName().isBlank()) {
            Sheet byName = workbook.getSheet(hints.sheetName());
            if (byName != null) {
                return byName;
            }
            throw new IllegalArgumentException("Worksheet '" + hints.sheetName() + "' not found in workbook.");
        }
        int targetIdx = hints.sheetIndex() != null ? hints.sheetIndex() : 0;
        if (targetIdx >= 0 && targetIdx < workbook.getNumberOfSheets()) {
            return workbook.getSheetAt(targetIdx);
        }
        return workbook.getSheetAt(0);
    }

    private Object extractCellValue(Cell cell) {
        if (cell == null) return null;
        return switch (cell.getCellType()) {
            case STRING -> cell.getStringCellValue();
            case NUMERIC -> {
                if (DateUtil.isCellDateFormatted(cell)) {
                    yield cell.getLocalDateTimeCellValue().toLocalDate().toString();
                }
                double d = cell.getNumericCellValue();
                if (d == Math.rint(d)) {
                    yield (long) d;
                }
                yield d;
            }
            case BOOLEAN -> cell.getBooleanCellValue();
            case FORMULA -> cell.getCellFormula();
            default -> null;
        };
    }

    private String extractCellValueAsString(Cell cell) {
        Object val = extractCellValue(cell);
        return val != null ? val.toString().trim() : "Column_" + (cell != null ? cell.getColumnIndex() : 0);
    }

    private RecordBatch validateBatch(
            int batchIdx,
            Map<String, DataType> schema,
            List<Map<String, Object>> rawBatch,
            List<Long> rowNumbers,
            SchemaHints hints) {
        List<Map<String, Object>> valid = new ArrayList<>(rawBatch.size());
        List<QuarantineRecord> quarantined = new ArrayList<>();

        for (int i = 0; i < rawBatch.size(); i++) {
            Map<String, Object> raw = rawBatch.get(i);
            long lineNum = rowNumbers.get(i);
            Map<String, Object> coerced = new LinkedHashMap<>();
            boolean failed = false;
            for (Map.Entry<String, Object> entry : raw.entrySet()) {
                DataType targetType = schema.getOrDefault(entry.getKey(), DataType.UTF8_STRING);
                try {
                    coerced.put(entry.getKey(), typeInferenceEngine.coerceValue(entry.getValue(), targetType));
                } catch (Exception ex) {
                    quarantined.add(new QuarantineRecord(
                            hints.tenantId(),
                            hints.datasetId(),
                            lineNum,
                            entry.getKey(),
                            targetType.name(),
                            raw.toString(),
                            "XLSX datatype coercion failed: " + ex.getMessage()
                    ));
                    failed = true;
                    break;
                }
            }
            if (!failed) {
                valid.add(coerced);
            }
        }
        return new RecordBatch(batchIdx, schema, valid, quarantined);
    }
}
