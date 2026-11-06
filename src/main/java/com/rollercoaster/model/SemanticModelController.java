package com.rollercoaster.model;

import com.rollercoaster.rax.compiler.InfoFunctionEvaluator;
import org.springframework.http.ResponseEntity;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * REST and STOMP WebSocket endpoints for the Semantic Model workspace, DAG
 * validation,
 * RSDL/TMDL preview, INFO.* dictionary functions, and visual measure
 * calculation.
 */
@RestController
@RequestMapping("/api")
public class SemanticModelController {

    private final SemanticModelManager semanticModelManager;
    private final DualEngineCoordinator dualEngineCoordinator;
    private final InfoFunctionEvaluator infoFunctionEvaluator;

    public SemanticModelController(
            SemanticModelManager semanticModelManager,
            DualEngineCoordinator dualEngineCoordinator,
            InfoFunctionEvaluator infoFunctionEvaluator) {
        this.semanticModelManager = semanticModelManager;
        this.dualEngineCoordinator = dualEngineCoordinator;
        this.infoFunctionEvaluator = infoFunctionEvaluator;
    }

    @GetMapping("/models/{modelId}")
    @PreAuthorize("hasAuthority('PERM_REPORT_READ')")
    public ResponseEntity<SemanticModelSchema> getModel(@PathVariable UUID modelId) {
        return ResponseEntity.ok(semanticModelManager.getModel(modelId));
    }

    @GetMapping("/models/default")
    @PreAuthorize("hasAuthority('PERM_REPORT_READ')")
    public ResponseEntity<SemanticModelSchema> getDefaultModel() {
        return ResponseEntity.ok(semanticModelManager.getDefaultModel());
    }

    @PostMapping("/models")
    @PreAuthorize("hasAuthority('PERM_MODEL_WRITE')")
    public ResponseEntity<SemanticModelSchema> registerModel(@RequestBody SemanticModelSchema schema) {
        return ResponseEntity.ok(saveModel(schema));
    }

    @PostMapping("/models/{modelId}")
    @PreAuthorize("hasAuthority('PERM_MODEL_WRITE')")
    public ResponseEntity<SemanticModelSchema> updateModel(
            @PathVariable UUID modelId,
            @RequestBody SemanticModelSchema schema) {
        if (schema == null || schema.modelId() == null || !modelId.equals(schema.modelId())) {
            throw new IllegalArgumentException("The model path and payload identifiers must match.");
        }
        return ResponseEntity.ok(saveModel(schema));
    }

    @PostMapping("/models/{modelId}/tables/{tableName}/rows")
    @PreAuthorize("hasAuthority('PERM_MODEL_WRITE')")
    public ResponseEntity<TableRowsIngestionResult> ingestTableRows(
            @PathVariable UUID modelId,
            @PathVariable String tableName,
            @RequestBody List<Map<String, Object>> rows) {
        if (rows == null) {
            return ResponseEntity.badRequest().build();
        }
        semanticModelManager.getModel(modelId);
        int rowsIngested = dualEngineCoordinator.ingestDatasetRows("default-tenant", tableName, rows);
        return ResponseEntity.ok(new TableRowsIngestionResult(modelId, tableName, rowsIngested));
    }

    @PostMapping("/models/validate-dag")
    @PreAuthorize("hasAuthority('PERM_MODEL_WRITE')")
    public ResponseEntity<DagValidationReport> validateDag(@RequestBody SemanticModelSchema schema) {
        if (schema == null) {
            return ResponseEntity.badRequest().build();
        }
        SemanticModelSchema normalized = normalizeSchema(schema);
        return ResponseEntity.ok(semanticModelManager.validateDagConsistency(normalized));
    }

    private SemanticModelSchema saveModel(SemanticModelSchema schema) {
        if (schema == null) {
            throw new IllegalArgumentException("A model payload is required.");
        }
        SemanticModelSchema normalized = normalizeSchema(schema);
        DagValidationReport validation = semanticModelManager.validateDagConsistency(normalized);
        if (!validation.valid()) {
            throw new IllegalArgumentException(
                    "Semantic model validation failed: " + validation.diagnostics());
        }
        semanticModelManager.registerModel(normalized);
        return normalized;
    }

    private SemanticModelSchema normalizeSchema(SemanticModelSchema schema) {
        UUID id = schema.modelId() != null ? schema.modelId() : UUID.randomUUID();
        String name = schema.name() != null && !schema.name().isBlank() ? schema.name() : "Normalized Model";
        int fy = schema.fiscalYearStartMonth() >= 1 && schema.fiscalYearStartMonth() <= 12
                ? schema.fiscalYearStartMonth()
                : 1;
        return new SemanticModelSchema(
                id,
                name,
                fy,
                schema.tables(),
                schema.relationships(),
                schema.measures(),
                schema.rlsRolePredicates());
    }

    @GetMapping(value = "/models/{modelId}/rsdl", produces = "text/plain;charset=UTF-8")
    @PreAuthorize("hasAuthority('PERM_REPORT_READ')")
    public ResponseEntity<String> exportModelRsdl(@PathVariable UUID modelId) {
        return ResponseEntity.ok(semanticModelManager.formatToRsdl(semanticModelManager.getModel(modelId)));
    }

    @GetMapping(value = "/models/{modelId}/tmdl", produces = "text/plain;charset=UTF-8")
    @PreAuthorize("hasAuthority('PERM_REPORT_READ')")
    public ResponseEntity<String> exportModelTmdl(@PathVariable UUID modelId) {
        return ResponseEntity.ok(semanticModelManager.formatToTmdl(semanticModelManager.getModel(modelId)));
    }

    @PostMapping(value = "/models/{modelId}/tmdl", consumes = "text/plain")
    @PreAuthorize("hasAuthority('PERM_MODEL_WRITE')")
    public ResponseEntity<SemanticModelSchema> applyModelTmdl(
            @PathVariable UUID modelId,
            @RequestBody String tmdlScript) {
        return ResponseEntity.ok(semanticModelManager.applyTmdlScript(modelId, tmdlScript));
    }

    @GetMapping("/models/{modelId}/dictionary")
    @PreAuthorize("hasAuthority('PERM_REPORT_READ')")
    public ResponseEntity<List<Map<String, Object>>> getDataDictionary(@PathVariable UUID modelId) {
        return ResponseEntity
                .ok(infoFunctionEvaluator.buildUnifiedDataDictionary(semanticModelManager.getModel(modelId)));
    }

    @GetMapping("/reports/{reportId}/measures/{measureId}")
    @PreAuthorize("hasAuthority('PERM_REPORT_READ')")
    public ResponseEntity<QueryExecutionResult> evaluateReportMeasure(
            @PathVariable String reportId,
            @PathVariable String measureId,
            Authentication authentication) {
        throw new IllegalArgumentException(
                "No report measure is configured for this workspace: " + measureId);
    }

    @PostMapping("/reports/{reportId}/query")
    @PreAuthorize("hasAnyAuthority('PERM_REPORT_READ', 'ROLE_ADMIN', 'ROLE_USER', 'ROLE_OPERATOR')")
    public ResponseEntity<QueryExecutionResult> executeVisualQuery(
            @PathVariable String reportId,
            @RequestBody VisualQueryDefinition query,
            Authentication authentication) {
        return ResponseEntity
                .ok(dualEngineCoordinator.executeVisualQuery(withReportId(query, reportId), authentication));
    }

    private VisualQueryDefinition withReportId(VisualQueryDefinition query, String reportId) {
        if (query == null) {
            throw new IllegalArgumentException("A visual query body is required.");
        }
        return new VisualQueryDefinition(
                query.modelId(),
                query.tenantId(),
                reportId,
                query.visualId(),
                query.measureId(),
                query.raxExpression(),
                query.factTableName(),
                query.estimatedRows(),
                query.operationalLookup(),
                query.groupByColumns(),
                query.activeFilters(),
                query.externalSlicerFilters(),
                query.evaluationDate(),
                query.acceptLanguage());
    }

    public record TableRowsIngestionResult(UUID modelId, String tableName, int rowsIngested) {
    }

    @MessageMapping("/calculate-measure")
    public void calculateMeasureViaStomp(VisualQueryDefinition query, Authentication authentication) {
        dualEngineCoordinator.executeVisualQuery(query, authentication);
    }
}
