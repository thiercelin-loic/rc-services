package com.rollercoaster.ingestion;

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

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * REST endpoints for Data Preparation Studio: streaming ingestion preview, Quarantine Queue inspection,
 * R-Query step execution, and High-Watermark Incremental Refresh.
 */
@RestController
@RequestMapping("/api/ingestion")
public class IngestionController {

    private final DataIngestionService dataIngestionService;
    private final RQueryTransformationEngine rQueryEngine;
    private final IncrementalRefreshCoordinator refreshCoordinator;

    public IngestionController(
            DataIngestionService dataIngestionService,
            RQueryTransformationEngine rQueryEngine,
            IncrementalRefreshCoordinator refreshCoordinator) {
        this.dataIngestionService = dataIngestionService;
        this.rQueryEngine = rQueryEngine;
        this.refreshCoordinator = refreshCoordinator;
    }

    /**
     * Preview request. Text formats (CSV, TSV, JSON, XML) travel in {@code rawPayload}; binary formats
     * (XLSX, PARQUET, AVRO) travel Base64-encoded in {@code rawPayloadBase64}. {@code lookupTables} carries
     * the rows of sibling datasets referenced by MergeQueriesStep (keyed by target table name).
     */
    public record RQueryPreviewRequest(
            String tenantId,
            String datasetId,
            SourceFormat format,
            String rawPayload,
            List<RQueryStep> steps,
            String rawPayloadBase64,
            String sheetName,
            Map<String, List<Map<String, Object>>> lookupTables
    ) {}

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

    @PostMapping("/preview")
    @PreAuthorize("hasAuthority('PERM_MODEL_WRITE')")
    public ResponseEntity<List<RecordBatch>> previewPipeline(@RequestBody RQueryPreviewRequest request) {
        SourceFormat format = request.format() != null ? request.format() : SourceFormat.CSV;
        SchemaHints hints = new SchemaHints(
                request.tenantId(),
                request.datasetId(),
                format,
                request.sheetName(),
                0,
                format == SourceFormat.TSV ? '\t' : ',',
                true,
                StandardCharsets.UTF_8,
                Map.of()
        );
        byte[] payloadBytes = request.rawPayloadBase64() != null && !request.rawPayloadBase64().isBlank()
                ? Base64.getDecoder().decode(request.rawPayloadBase64())
                : (request.rawPayload() != null ? request.rawPayload() : "").getBytes(StandardCharsets.UTF_8);
        ByteArrayInputStream stream = new ByteArrayInputStream(payloadBytes);
        List<RecordBatch> batches = rQueryEngine.executePipeline(
                        dataIngestionService.ingestStream(stream, hints),
                        request.steps() != null ? request.steps() : List.of(),
                        request.lookupTables() != null ? request.lookupTables() : Map.of(),
                        request.tenantId(),
                        request.datasetId())
                .collectList()
                .block();
        return ResponseEntity.ok(batches != null ? batches : List.of());
    }

    @GetMapping("/quarantine/{tenantId}/{datasetId}")
    @PreAuthorize("hasAuthority('PERM_MODEL_WRITE')")
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
