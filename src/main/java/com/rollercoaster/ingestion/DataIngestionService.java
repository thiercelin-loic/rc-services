package com.rollercoaster.ingestion;

import com.rollercoaster.ingestion.parser.ColumnarChannelParser;
import com.rollercoaster.ingestion.parser.DelimitedFileParser;
import com.rollercoaster.ingestion.parser.JsonXmlStreamingParser;
import com.rollercoaster.ingestion.parser.XlsxStreamingParser;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.io.InputStream;
import java.nio.channels.SeekableByteChannel;
import java.util.List;

/**
 * Coordinates multi-format streaming ingestion across CSV, TSV, JSON, XML, XLSX
 * spreadsheets, and Avro without loading entire files into JVM heap memory.
 * Persists quarantined rows asynchronously so streams complete without
 * interruption.
 */
@Service
public class DataIngestionService {

    private final DelimitedFileParser delimitedFileParser;
    private final JsonXmlStreamingParser jsonXmlStreamingParser;
    private final XlsxStreamingParser xlsxStreamingParser;
    private final ColumnarChannelParser columnarChannelParser;
    private final QuarantineRepository quarantineRepository;

    public DataIngestionService(
            DelimitedFileParser delimitedFileParser,
            JsonXmlStreamingParser jsonXmlStreamingParser,
            XlsxStreamingParser xlsxStreamingParser,
            ColumnarChannelParser columnarChannelParser,
            // Optional so ingestion still runs in stateless/test contexts without persistence.
            @Autowired(required = false) QuarantineRepository quarantineRepository) {
        this.delimitedFileParser = delimitedFileParser;
        this.jsonXmlStreamingParser = jsonXmlStreamingParser;
        this.xlsxStreamingParser = xlsxStreamingParser;
        this.columnarChannelParser = columnarChannelParser;
        this.quarantineRepository = quarantineRepository;
    }

    public Flux<RecordBatch> ingestStream(InputStream stream, SchemaHints hints) {
        // Persist quarantined rows per batch as a side effect so the stream never halts.
        return resolveParser(hints.format()).parseStream(stream, hints)
                .doOnNext(this::persistQuarantinedRecordsIfAny);
    }

    public Flux<RecordBatch> ingestChannel(SeekableByteChannel channel, SchemaHints hints) {
        // SeekableByteChannel supports random-access formats (e.g. Avro) that read footers/blocks.
        return resolveParser(hints.format()).parseChannel(channel, hints)
                .doOnNext(this::persistQuarantinedRecordsIfAny);
    }

    public List<QuarantineRecord> getQuarantineQueue(String tenantId, String datasetId) {
        if (quarantineRepository == null) {
            return List.of();
        }
        return quarantineRepository.findByTenantIdAndDatasetIdOrderBySourceLineNumberAsc(tenantId, datasetId);
    }

    public static char detectDelimiter(String rawPayload) {
        if (rawPayload == null || rawPayload.isEmpty()) {
            return ',';
        }
        // Inspect only the header line to avoid scanning the full payload.
        int newlineIdx = rawPayload.indexOf('\n');
        String firstLine = newlineIdx >= 0 ? rawPayload.substring(0, newlineIdx) : rawPayload;
        int commas = countChar(firstLine, ',');
        int semicolons = countChar(firstLine, ';');
        int tabs = countChar(firstLine, '\t');
        if (tabs > commas && tabs > semicolons) {
            return '\t';
        }
        return semicolons > commas ? ';' : ',';
    }

    private static int countChar(String s, char c) {
        int count = 0;
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) == c) {
                count++;
            }
        }
        return count;
    }

    public static String toUiColumnType(String colName, DataType dt) {
        if (dt == null) {
            return "STRING";
        }
        return switch (dt) {
            case INT64 -> "INT64";
            case FLOAT64 -> "DOUBLE";
            case DECIMAL, CURRENCY -> "DECIMAL";
            case BOOLEAN -> "BOOLEAN";
            case ISO_DATE, ISO_DATETIME -> "DATE";
            // Treat string columns with "date" in the name as DATE for UI icon/type hints.
            case UTF8_STRING -> colName != null && colName.toLowerCase().contains("date") ? "DATE" : "STRING";
        };
    }

    public static String toUiColumnIcon(String uiType) {
        if (uiType == null) {
            return "Abc";
        }
        return switch (uiType) {
            case "INT64", "DOUBLE" -> "123";
            case "DECIMAL" -> "$";
            case "DATE" -> "Cal";
            case "BOOLEAN" -> "T/F";
            default -> "Abc";
        };
    }

    private FileParser resolveParser(SourceFormat format) {
        return switch (format) {
            case CSV, TSV -> delimitedFileParser;
            case JSON, XML -> jsonXmlStreamingParser;
            case XLSX -> xlsxStreamingParser;
            case AVRO -> columnarChannelParser;
            // Fallback to delimited text when format is unspecified or unrecognized.
            default -> delimitedFileParser;
        };
    }

    private void persistQuarantinedRecordsIfAny(RecordBatch batch) {
        if (quarantineRepository != null && !batch.quarantinedRecords().isEmpty()) {
            quarantineRepository.saveAll(batch.quarantinedRecords());
        }
    }
}
