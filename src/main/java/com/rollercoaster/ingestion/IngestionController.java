package com.rollercoaster.ingestion;

import com.rollercoaster.ingestion.IngestionDtos.PipelinePreviewRequest;
import com.rollercoaster.ingestion.IngestionDtos.PipelinePreviewResponse;
import com.rollercoaster.ingestion.IngestionDtos.SheetColumnDto;
import com.rollercoaster.ingestion.IngestionDtos.SheetExtractionRequest;
import com.rollercoaster.ingestion.IngestionDtos.SheetExtractionResponse;
import com.rollercoaster.ingestion.refresh.IncrementalRefreshCoordinator;
import com.rollercoaster.ingestion.refresh.IncrementalRefreshCoordinator.FactDeltaRecord;
import com.rollercoaster.ingestion.refresh.IncrementalRefreshCoordinator.IncrementalRefreshResult;
import com.rollercoaster.ingestion.rquery.DataPrepPipelineService;
import com.rollercoaster.ingestion.rquery.RQueryStep;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
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
    private final DataPrepPipelineService dataPrepPipelineService;
    private final IncrementalRefreshCoordinator refreshCoordinator;

    public IngestionController(
            DataIngestionService dataIngestionService,
            SheetExtractionService sheetExtractionService,
            DataPrepPipelineService dataPrepPipelineService,
            IncrementalRefreshCoordinator refreshCoordinator) {
        this.dataIngestionService = Objects.requireNonNull(dataIngestionService, "dataIngestionService must not be null");
        this.sheetExtractionService = Objects.requireNonNull(sheetExtractionService, "sheetExtractionService must not be null");
        this.dataPrepPipelineService = Objects.requireNonNull(dataPrepPipelineService, "dataPrepPipelineService must not be null");
        this.refreshCoordinator = Objects.requireNonNull(refreshCoordinator, "refreshCoordinator must not be null");
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
            List<Map<String, Object>> initialRows,
            String fileName,
            String rangeStart,
            String rangeEnd
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
            this(tenantId, datasetId, format, rawPayload, steps, rawPayloadBase64, sheetName, lookupTables, null, null, null, null, null);
        }

        public RQueryPreviewRequest(
                String tenantId,
                String datasetId,
                SourceFormat format,
                String rawPayload,
                List<RQueryStep> steps,
                String rawPayloadBase64,
                String sheetName,
                Map<String, List<Map<String, Object>>> lookupTables,
                List<SheetColumnDto> initialColumns,
                List<Map<String, Object>> initialRows) {
            this(tenantId, datasetId, format, rawPayload, steps, rawPayloadBase64, sheetName, lookupTables, initialColumns, initialRows, null, null, null);
        }

        public PipelinePreviewRequest toPipelinePreviewRequest() {
            return new PipelinePreviewRequest(
                    tenantId,
                    datasetId,
                    fileName,
                    format,
                    rawPayload,
                    rawPayloadBase64,
                    sheetName,
                    steps,
                    lookupTables,
                    initialColumns,
                    initialRows,
                    rangeStart,
                    rangeEnd
            );
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

    /**
     * Legacy streaming batch preview endpoint preserved for backward compatibility.
     */
    @PostMapping("/preview")
    @PreAuthorize("hasAnyAuthority('PERM_MODEL_WRITE', 'PERM_REPORT_READ')")
    public ResponseEntity<List<RecordBatch>> previewPipeline(@RequestBody RQueryPreviewRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("RQueryPreviewRequest body is required");
        }
        return ResponseEntity.ok(dataPrepPipelineService.evaluateRecordBatches(request.toPipelinePreviewRequest()));
    }

    /**
     * Unified Data Preparation preview endpoint returning normalized columns ({@code name}, {@code type},
     * {@code displayType}, {@code numeric}), transformed rows, quarantined records, full Power Query M script,
     * and active step formula.
     */
    @PostMapping({"/pipeline/preview", "/pipeline/evaluate"})
    @PreAuthorize("hasAnyAuthority('PERM_MODEL_WRITE', 'PERM_REPORT_READ')")
    public ResponseEntity<PipelinePreviewResponse> evaluatePipelinePreview(@RequestBody PipelinePreviewRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("PipelinePreviewRequest body is required");
        }
        return ResponseEntity.ok(dataPrepPipelineService.evaluatePreview(request));
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

