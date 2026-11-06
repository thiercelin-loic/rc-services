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
 * Coordinates multi-format streaming ingestion across CSV, TSV, JSON, XML, XLSX spreadsheets,
 * Parquet, and Avro without loading entire files into JVM heap memory.
 * Persists quarantined rows asynchronously so streams complete without interruption.
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
            @Autowired(required = false) QuarantineRepository quarantineRepository) {
        this.delimitedFileParser = delimitedFileParser;
        this.jsonXmlStreamingParser = jsonXmlStreamingParser;
        this.xlsxStreamingParser = xlsxStreamingParser;
        this.columnarChannelParser = columnarChannelParser;
        this.quarantineRepository = quarantineRepository;
    }

    public Flux<RecordBatch> ingestStream(InputStream stream, SchemaHints hints) {
        FileParser parser = resolveParser(hints.format());
        return parser.parseStream(stream, hints)
                .doOnNext(this::persistQuarantinedRecordsIfAny);
    }

    public Flux<RecordBatch> ingestChannel(SeekableByteChannel channel, SchemaHints hints) {
        FileParser parser = resolveParser(hints.format());
        return parser.parseChannel(channel, hints)
                .doOnNext(this::persistQuarantinedRecordsIfAny);
    }

    public List<QuarantineRecord> getQuarantineQueue(String tenantId, String datasetId) {
        if (quarantineRepository == null) {
            return List.of();
        }
        return quarantineRepository.findByTenantIdAndDatasetIdOrderBySourceLineNumberAsc(tenantId, datasetId);
    }

    private FileParser resolveParser(SourceFormat format) {
        return switch (format) {
            case CSV, TSV -> delimitedFileParser;
            case JSON, XML -> jsonXmlStreamingParser;
            case XLSX -> xlsxStreamingParser;
            case PARQUET, AVRO -> columnarChannelParser;
            default -> delimitedFileParser;
        };
    }

    private void persistQuarantinedRecordsIfAny(RecordBatch batch) {
        if (quarantineRepository != null && !batch.quarantinedRecords().isEmpty()) {
            quarantineRepository.saveAll(batch.quarantinedRecords());
        }
    }
}
