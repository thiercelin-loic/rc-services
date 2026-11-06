package com.rollercoaster.ingestion.parser;

import com.rollercoaster.ingestion.DataType;
import com.rollercoaster.ingestion.FileParser;
import com.rollercoaster.ingestion.QuarantineRecord;
import com.rollercoaster.ingestion.RecordBatch;
import com.rollercoaster.ingestion.SchemaHints;
import com.rollercoaster.model.DuckDbConnectionManager;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;

import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.channels.ReadableByteChannel;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Columnar format parser (Parquet, Avro) binding SeekableByteChannel over local temporary NVMe
 * scratch buffers or off-heap channels to access footer metadata and dictionary pages without
 * full-file JVM heap buffering.
 */
@Component
public class ColumnarChannelParser implements FileParser {

    private final DuckDbConnectionManager duckDbConnectionManager;
    private final TypeInferenceEngine typeInferenceEngine;

    public ColumnarChannelParser(
            DuckDbConnectionManager duckDbConnectionManager,
            TypeInferenceEngine typeInferenceEngine) {
        this.duckDbConnectionManager = duckDbConnectionManager;
        this.typeInferenceEngine = typeInferenceEngine;
    }

    @Override
    public Flux<RecordBatch> parseStream(InputStream stream, SchemaHints hints) {
        return Flux.<RecordBatch>create(sink -> {
            Path tempScratch = null;
            try {
                tempScratch = Files.createTempFile("rc-nvme-scratch-", ".parquet");
                try (ReadableByteChannel src = Channels.newChannel(stream);
                     FileChannel dst = FileChannel.open(tempScratch, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
                    ByteBuffer offHeapBuf = ByteBuffer.allocateDirect(64 * 1024);
                    while (src.read(offHeapBuf) != -1) {
                        offHeapBuf.flip();
                        dst.write(offHeapBuf);
                        offHeapBuf.clear();
                    }
                }
                if (Files.size(tempScratch) == 0) {
                    sink.complete();
                    return;
                }
                try (SeekableByteChannel channel = Files.newByteChannel(tempScratch, StandardOpenOption.READ)) {
                    if (Files.size(tempScratch) == 0) {
                    sink.complete();
                    return;
                }
                readFromSeekableChannel(channel, tempScratch, hints).forEach(sink::next);
                }
                sink.complete();
            } catch (Exception e) {
                sink.error(e);
            } finally {
                if (tempScratch != null) {
                    try {
                        Files.deleteIfExists(tempScratch);
                    } catch (Exception ignored) {
                    }
                }
            }
        }).subscribeOn(Schedulers.boundedElastic());
    }

    @Override
    public Flux<RecordBatch> parseChannel(SeekableByteChannel channel, SchemaHints hints) {
        return Flux.<RecordBatch>create(sink -> {
            Path tempScratch = null;
            try {
                tempScratch = Files.createTempFile("rc-nvme-channel-", ".parquet");
                channel.position(0);
                try (FileChannel dst = FileChannel.open(tempScratch, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
                    ByteBuffer offHeapBuf = ByteBuffer.allocateDirect(64 * 1024);
                    while (channel.read(offHeapBuf) != -1) {
                        offHeapBuf.flip();
                        dst.write(offHeapBuf);
                        offHeapBuf.clear();
                    }
                }
                if (Files.size(tempScratch) == 0) {
                    sink.complete();
                    return;
                }
                readFromSeekableChannel(channel, tempScratch, hints).forEach(sink::next);
                sink.complete();
            } catch (Exception e) {
                sink.error(e);
            } finally {
                if (tempScratch != null) {
                    try {
                        Files.deleteIfExists(tempScratch);
                    } catch (Exception ignored) {
                    }
                }
            }
        }).subscribeOn(Schedulers.boundedElastic());
    }

    private List<RecordBatch> readFromSeekableChannel(
            SeekableByteChannel channel,
            Path scratchFile,
            SchemaHints hints) throws Exception {
        if (Files.exists(scratchFile) && Files.size(scratchFile) == 0) {
            return List.of();
        }
        List<RecordBatch> batches = new ArrayList<>();
        String normalizedPath = scratchFile.toAbsolutePath().toString().replace('\\', '/');

        try (Connection conn = duckDbConnectionManager.acquireConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT * FROM read_parquet('" + normalizedPath + "')")) {

            ResultSetMetaData meta = rs.getMetaData();
            int colCount = meta.getColumnCount();
            Map<String, DataType> schema = new LinkedHashMap<>();
            for (int c = 1; c <= colCount; c++) {
                String colName = meta.getColumnLabel(c);
                DataType type = hints.expectedColumnTypes().getOrDefault(
                        colName, mapSqlType(meta.getColumnTypeName(c)));
                schema.put(colName, type);
            }

            List<Map<String, Object>> currentRows = new ArrayList<>();
            List<QuarantineRecord> currentQuarantine = new ArrayList<>();
            int batchIdx = 0;
            long rowNum = 0;

            while (rs.next()) {
                rowNum++;
                Map<String, Object> row = new LinkedHashMap<>();
                boolean failed = false;
                for (int c = 1; c <= colCount; c++) {
                    String colName = meta.getColumnLabel(c);
                    Object rawVal = rs.getObject(c);
                    DataType targetType = schema.get(colName);
                    try {
                        row.put(colName, typeInferenceEngine.coerceValue(rawVal, targetType));
                    } catch (Exception ex) {
                        currentQuarantine.add(new QuarantineRecord(
                                hints.tenantId(),
                                hints.datasetId(),
                                rowNum,
                                colName,
                                targetType.name(),
                                String.valueOf(rawVal),
                                "Columnar type validation failed: " + ex.getMessage()
                        ));
                        failed = true;
                        break;
                    }
                }
                if (!failed) {
                    currentRows.add(row);
                }
                if (currentRows.size() == RecordBatch.MAX_BATCH_ROWS) {
                    batches.add(new RecordBatch(batchIdx++, schema, currentRows, currentQuarantine));
                    currentRows = new ArrayList<>();
                    currentQuarantine = new ArrayList<>();
                }
            }
            if (!currentRows.isEmpty() || !currentQuarantine.isEmpty()) {
                batches.add(new RecordBatch(batchIdx, schema, currentRows, currentQuarantine));
            }
        }
        return batches;
    }

    private DataType mapSqlType(String duckType) {
        if (duckType == null) return DataType.UTF8_STRING;
        String upper = duckType.toUpperCase();
        if (upper.contains("INT") || upper.contains("BIGINT")) return DataType.INT64;
        if (upper.contains("DOUBLE") || upper.contains("FLOAT") || upper.contains("REAL")) return DataType.FLOAT64;
        if (upper.contains("DECIMAL") || upper.contains("NUMERIC")) return DataType.DECIMAL;
        if (upper.contains("BOOL")) return DataType.BOOLEAN;
        if (upper.equals("DATE")) return DataType.ISO_DATE;
        if (upper.contains("TIMESTAMP")) return DataType.ISO_DATETIME;
        return DataType.UTF8_STRING;
    }
}
