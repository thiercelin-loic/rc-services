package com.rollercoaster.ingestion;

import com.rollercoaster.ingestion.IngestionDtos.SheetColumnDto;
import com.rollercoaster.ingestion.IngestionDtos.SheetExtractionRequest;
import com.rollercoaster.ingestion.IngestionDtos.SheetExtractionResponse;
import com.rollercoaster.ingestion.refresh.IncrementalRefreshCoordinator;
import com.rollercoaster.ingestion.refresh.IncrementalRefreshCoordinator.FactDeltaRecord;
import com.rollercoaster.ingestion.refresh.IncrementalRefreshCoordinator.IncrementalRefreshResult;
import com.rollercoaster.ingestion.rquery.RQueryStep;
import com.rollercoaster.ingestion.rquery.RQueryTransformationEngine;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * REST endpoints for Data Preparation Studio: streaming ingestion preview, sheet extraction,
 * Quarantine Queue inspection, R-Query step execution, and High-Watermark Incremental Refresh.
 */
@RestController
@RequestMapping("/api/ingestion")
public class IngestionController {

    private final DataIngestionService dataIngestionService;
    private final SheetExtractionService sheetExtractionService;
    private final RQueryTransformationEngine rQueryEngine;
    private final IncrementalRefreshCoordinator refreshCoordinator;

    public IngestionController(
            DataIngestionService dataIngestionService,
            SheetExtractionService sheetExtractionService,
            RQueryTransformationEngine rQueryEngine,
            IncrementalRefreshCoordinator refreshCoordinator) {
        this.dataIngestionService = dataIngestionService;
        this.sheetExtractionService = sheetExtractionService;
        this.rQueryEngine = rQueryEngine;
        this.refreshCoordinator = refreshCoordinator;
    }

    /**
     * Preview request. Text formats (CSV, TSV, JSON, XML) travel in {@code rawPayload}; binary formats
     * (XLSX, AVRO) travel Base64-encoded in {@code rawPayloadBase64}. {@code lookupTables} carries
     * the rows of sibling datasets referenced by MergeQueriesStep (keyed by target table name).
     * {@code initialColumns} and {@code initialRows} allow evaluating steps directly against an in-memory snapshot.
     */
    public record RQueryPreviewRequest(
            String tenantId,
            String datasetId,
            SourceFormat format,
            String rawPayload,
            List<RQueryStep> steps,
            String rawPayloadBase64,
            String sheetName,
            Map<String, List<Map<String, Object>>> lookupTables,
            List<SheetColumnDto> initialColumns,
            List<Map<String, Object>> initialRows
    ) {
        public RQueryPreviewRequest(
                String tenantId,
                String datasetId,
                SourceFormat format,
                String rawPayload,
                List<RQueryStep> steps,
                String rawPayloadBase64,
                String sheetName,
                Map<String, List<Map<String, Object>>> lookupTables) {
            this(tenantId, datasetId, format, rawPayload, steps, rawPayloadBase64, sheetName, lookupTables, null, null);
        }
    }

    public record IncrementalRefreshRequest(
            String tenantId,
            String tableId,
            List<FactDeltaRecord> deltaRecords
    ) {}

    public record QuarantineRecordDto(
            UUID id,
            String tenantId,
            String datasetId,
            long sourceLineNumber,
            String columnName,
            String expectedType,
            String rawPayload,
            String errorReason,
            Instant createdAt
    ) {
        public static QuarantineRecordDto from(QuarantineRecord record) {
            if (record == null) {
                return null;
            }
            return new QuarantineRecordDto(
                    record.getId(),
                    record.getTenantId(),
                    record.getDatasetId(),
                    record.getSourceLineNumber(),
                    record.getColumnName(),
                    record.getExpectedType(),
                    record.getRawPayload(),
                    record.getErrorReason(),
                    record.getCreatedAt()
            );
        }
    }

    @PostMapping("/sheets")
    @PreAuthorize("hasAnyAuthority('PERM_MODEL_WRITE', 'PERM_REPORT_READ')")
    public ResponseEntity<SheetExtractionResponse> extractSheets(@RequestBody SheetExtractionRequest request) {
        return ResponseEntity.ok(sheetExtractionService.extractSheets(request));
    }

    @PostMapping("/preview")
    @PreAuthorize("hasAnyAuthority('PERM_MODEL_WRITE', 'PERM_REPORT_READ')")
    public ResponseEntity<List<RecordBatch>> previewPipeline(@RequestBody RQueryPreviewRequest request) {
        Flux<RecordBatch> sourceStream;
        if (request.initialRows() != null) {
            Map<String, DataType> schema = new LinkedHashMap<>();
            if (request.initialColumns() != null) {
                for (SheetColumnDto col : request.initialColumns()) {
                    schema.put(col.name(), mapUiColumnType(col.type()));
                }
            } else if (!request.initialRows().isEmpty()) {
                for (String key : request.initialRows().get(0).keySet()) {
                    schema.put(key, DataType.UTF8_STRING);
                }
            }
            sourceStream = Flux.just(new RecordBatch(0, schema, request.initialRows(), List.of()));
        } else {
            SourceFormat format = request.format() != null ? request.format() : SourceFormat.CSV;
            char delimiter = format == SourceFormat.TSV
                    ? '\t'
                    : DataIngestionService.detectDelimiter(request.rawPayload());
            SchemaHints hints = new SchemaHints(
                    request.tenantId(),
                    request.datasetId(),
                    format,
                    request.sheetName(),
                    0,
                    delimiter,
                    true,
                    StandardCharsets.UTF_8,
                    Map.of()
            );
            byte[] payloadBytes = request.rawPayloadBase64() != null && !request.rawPayloadBase64().isBlank()
                    ? Base64.getDecoder().decode(request.rawPayloadBase64())
                    : (request.rawPayload() != null ? request.rawPayload() : "").getBytes(StandardCharsets.UTF_8);
            ByteArrayInputStream stream = new ByteArrayInputStream(payloadBytes);
            sourceStream = dataIngestionService.ingestStream(stream, hints);
        }

        List<RecordBatch> batches = rQueryEngine.executePipeline(
                        sourceStream,
                        request.steps() != null ? request.steps() : List.of(),
                        request.lookupTables() != null ? request.lookupTables() : Map.of(),
                        request.tenantId() != null ? request.tenantId() : "default-tenant",
                        request.datasetId() != null ? request.datasetId() : "preview-dataset")
                .collectList()
                .block();
        return ResponseEntity.ok(batches != null ? batches : List.of());
    }

    private DataType mapUiColumnType(String uiType) {
        if (uiType == null) return DataType.UTF8_STRING;
        return switch (uiType.toUpperCase()) {
            case "INT64", "INTEGER", "WHOLE NUMBER" -> DataType.INT64;
            case "FLOAT64", "DOUBLE", "DECIMAL NUMBER" -> DataType.FLOAT64;
            case "DECIMAL", "CURRENCY" -> DataType.DECIMAL;
            case "BOOLEAN", "TRUE/FALSE" -> DataType.BOOLEAN;
            case "DATE", "ISO_DATE" -> DataType.ISO_DATE;
            case "DATETIME", "ISO_DATETIME" -> DataType.ISO_DATETIME;
            default -> DataType.UTF8_STRING;
        };
    }

    @GetMapping("/quarantine/{tenantId}/{datasetId}")
    @PreAuthorize("hasAnyAuthority('PERM_MODEL_WRITE', 'PERM_REPORT_READ')")
    public ResponseEntity<List<QuarantineRecordDto>> getQuarantineQueue(
            @PathVariable String tenantId,
            @PathVariable String datasetId) {
        List<QuarantineRecordDto> records = dataIngestionService.getQuarantineQueue(tenantId, datasetId).stream()
                .map(QuarantineRecordDto::from)
                .toList();
        return ResponseEntity.ok(records);
    }

    @PostMapping("/incremental-refresh")
    @PreAuthorize("hasAuthority('PERM_MODEL_WRITE')")
    public ResponseEntity<IncrementalRefreshResult> triggerIncrementalRefresh(
            @RequestBody IncrementalRefreshRequest request) throws Exception {
        IncrementalRefreshResult result = refreshCoordinator.executeIncrementalRefresh(
                request.tenantId(), request.tableId(), request.deltaRecords());
        return ResponseEntity.ok(result);
    }
}

