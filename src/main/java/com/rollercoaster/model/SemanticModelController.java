package com.rollercoaster.model;

import com.rollercoaster.model.RelationshipInferenceService.RelationshipInferenceResult;
import com.rollercoaster.model.SemanticModelManager.ColumnMetadataUpdate;
import com.rollercoaster.model.SemanticModelManager.ColumnTarget;
import com.rollercoaster.model.SemanticModelManager.DateTableSpec;
import com.rollercoaster.model.SemanticModelManager.ModelMutationResult;
import com.rollercoaster.model.SemanticModelManager.RelationshipRoutingUpdate;
import com.rollercoaster.rax.compiler.InfoFunctionEvaluator;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Thin REST and STOMP WebSocket controller for the Semantic Model workspace,
 * relationship inference, DAG validation, RSDL/TMDL preview, and visual queries.
 */
@RestController
@RequestMapping("/api")
public class SemanticModelController {

    private final SemanticModelManager semanticModelManager;
    private final RelationshipInferenceService relationshipInferenceService;
    private final RsdlFormatter rsdlFormatter;
    private final DualEngineCoordinator dualEngineCoordinator;
    private final InfoFunctionEvaluator infoFunctionEvaluator;

    public record RelationshipInferenceRequest(List<TableDefinition> tables) {
        public RelationshipInferenceRequest {
            tables = tables != null ? List.copyOf(tables) : List.of();
        }
    }

    public record RelationshipInferenceResponse(
            List<RelationshipDefinition> relationships,
            Map<String, String> dateColumnsByTable,
            Map<String, String> suggestedDateColumns
    ) {
        public RelationshipInferenceResponse {
            relationships = relationships != null ? List.copyOf(relationships) : List.of();
            dateColumnsByTable = dateColumnsByTable != null ? Map.copyOf(dateColumnsByTable) : Map.of();
            suggestedDateColumns = suggestedDateColumns != null ? Map.copyOf(suggestedDateColumns) : dateColumnsByTable;
        }

        public RelationshipInferenceResponse(
                List<RelationshipDefinition> relationships,
                Map<String, String> dateColumnsByTable) {
            this(relationships, dateColumnsByTable, dateColumnsByTable);
        }
    }

    public record RsdlEvaluationResponse(
            SemanticModelSchema schema,
            String rsdl,
            DagValidationReport dagValidation
    ) {
        public static RsdlEvaluationResponse from(ModelMutationResult result) {
            return new RsdlEvaluationResponse(result.schema(), result.rsdl(), result.dagValidation());
        }
    }

    public record RelationshipRoutingRequest(
            Cardinality cardinality,
            CrossFilterDirection crossFilterDirection
    ) {}

    public record DateTableRequest(
            String tableName,
            Boolean dateTable,
            String dateColumn
    ) {
        public DateTableSpec toSpec() {
            return new DateTableSpec(tableName, dateTable == null || dateTable, dateColumn);
        }
    }

    public record TableRowsIngestionResult(UUID modelId, String tableName, int rowsIngested) {}

    public SemanticModelController(
            SemanticModelManager semanticModelManager,
            DualEngineCoordinator dualEngineCoordinator,
            InfoFunctionEvaluator infoFunctionEvaluator) {
        this(
                semanticModelManager,
                new RelationshipInferenceService(),
                new RsdlFormatter(),
                dualEngineCoordinator,
                infoFunctionEvaluator);
    }

    @Autowired
    public SemanticModelController(
            SemanticModelManager semanticModelManager,
            RelationshipInferenceService relationshipInferenceService,
            RsdlFormatter rsdlFormatter,
            DualEngineCoordinator dualEngineCoordinator,
            InfoFunctionEvaluator infoFunctionEvaluator) {
        this.semanticModelManager = Objects.requireNonNull(semanticModelManager, "semanticModelManager must not be null");
        this.relationshipInferenceService = Objects.requireNonNull(
                relationshipInferenceService, "relationshipInferenceService must not be null");
        this.rsdlFormatter = Objects.requireNonNull(rsdlFormatter, "rsdlFormatter must not be null");
        this.dualEngineCoordinator = Objects.requireNonNull(dualEngineCoordinator, "dualEngineCoordinator must not be null");
        this.infoFunctionEvaluator = Objects.requireNonNull(infoFunctionEvaluator, "infoFunctionEvaluator must not be null");
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
        return ResponseEntity.ok(semanticModelManager.saveModel(schema));
    }

    @PutMapping("/models")
    @PreAuthorize("hasAuthority('PERM_MODEL_WRITE')")
    public ResponseEntity<RsdlEvaluationResponse> putModel(@RequestBody SemanticModelSchema schema) {
        return ResponseEntity.ok(RsdlEvaluationResponse.from(semanticModelManager.saveAndValidateModel(schema)));
    }

    @PostMapping("/models/{modelId}")
    @PreAuthorize("hasAuthority('PERM_MODEL_WRITE')")
    public ResponseEntity<SemanticModelSchema> updateModel(
            @PathVariable UUID modelId,
            @RequestBody SemanticModelSchema schema) {
        return ResponseEntity.ok(semanticModelManager.saveModel(modelId, schema));
    }

    @PutMapping("/models/{modelId}")
    @PreAuthorize("hasAuthority('PERM_MODEL_WRITE')")
    public ResponseEntity<RsdlEvaluationResponse> putModelById(
            @PathVariable UUID modelId,
            @RequestBody SemanticModelSchema schema) {
        return ResponseEntity.ok(RsdlEvaluationResponse.from(semanticModelManager.saveAndValidateModel(modelId, schema)));
    }

    @PostMapping("/models/infer-relationships")
    @PreAuthorize("hasAnyAuthority('PERM_MODEL_WRITE', 'PERM_REPORT_READ')")
    public ResponseEntity<RelationshipInferenceResponse> inferRelationships(
            @RequestBody RelationshipInferenceRequest request) {
        Objects.requireNonNull(request, "RelationshipInferenceRequest body is required.");
        RelationshipInferenceResult result = relationshipInferenceService.inferModelMetadata(request.tables());
        return ResponseEntity.ok(new RelationshipInferenceResponse(result.relationships(), result.dateColumnsByTable()));
    }

    @PostMapping("/models/rsdl")
    @PreAuthorize("hasAnyAuthority('PERM_MODEL_WRITE', 'PERM_REPORT_READ')")
    public ResponseEntity<RsdlEvaluationResponse> generateRsdlAndValidate(@RequestBody SemanticModelSchema schema) {
        Objects.requireNonNull(schema, "A model payload is required.");
        return ResponseEntity.ok(RsdlEvaluationResponse.from(semanticModelManager.evaluateModel(schema)));
    }

    @PostMapping("/models/validate-dag")
    @PreAuthorize("hasAuthority('PERM_MODEL_WRITE')")
    public ResponseEntity<DagValidationReport> validateDag(@RequestBody SemanticModelSchema schema) {
        Objects.requireNonNull(schema, "A model payload is required.");
        return ResponseEntity.ok(semanticModelManager.evaluateModel(schema).dagValidation());
    }

    @PostMapping("/models/{modelId}/relationships")
    @PreAuthorize("hasAuthority('PERM_MODEL_WRITE')")
    public ResponseEntity<ModelMutationResult> createRelationship(
            @PathVariable UUID modelId,
            @RequestBody RelationshipDefinition relationship) {
        return ResponseEntity.ok(semanticModelManager.createRelationship(modelId, relationship));
    }

    @PutMapping("/models/{modelId}/relationships/{relationshipName}")
    @PreAuthorize("hasAuthority('PERM_MODEL_WRITE')")
    public ResponseEntity<ModelMutationResult> updateRelationship(
            @PathVariable UUID modelId,
            @PathVariable String relationshipName,
            @RequestBody RelationshipDefinition relationship) {
        return ResponseEntity.ok(semanticModelManager.updateRelationship(modelId, relationshipName, relationship));
    }

    @DeleteMapping("/models/{modelId}/relationships/{relationshipName}")
    @PreAuthorize("hasAuthority('PERM_MODEL_WRITE')")
    public ResponseEntity<ModelMutationResult> deleteRelationship(
            @PathVariable UUID modelId,
            @PathVariable String relationshipName) {
        return ResponseEntity.ok(semanticModelManager.deleteRelationship(modelId, relationshipName));
    }

    @PatchMapping("/models/{modelId}/relationships/{relationshipName}/active")
    @PreAuthorize("hasAuthority('PERM_MODEL_WRITE')")
    public ResponseEntity<ModelMutationResult> toggleActiveRelationship(
            @PathVariable UUID modelId,
            @PathVariable String relationshipName) {
        return ResponseEntity.ok(semanticModelManager.toggleActiveRelationship(modelId, relationshipName));
    }

    @PatchMapping("/models/{modelId}/relationships/{relationshipName}/routing")
    @PreAuthorize("hasAuthority('PERM_MODEL_WRITE')")
    public ResponseEntity<ModelMutationResult> updateRelationshipRouting(
            @PathVariable UUID modelId,
            @PathVariable String relationshipName,
            @RequestBody RelationshipRoutingRequest request) {
        Objects.requireNonNull(request, "RelationshipRoutingRequest body is required.");
        RelationshipRoutingUpdate bound = new RelationshipRoutingUpdate(
                relationshipName, request.cardinality(), request.crossFilterDirection());
        return ResponseEntity.ok(semanticModelManager.updateCardinalityAndCrossFilter(modelId, bound));
    }

    @PostMapping("/models/{modelId}/primary-key")
    @PreAuthorize("hasAuthority('PERM_MODEL_WRITE')")
    public ResponseEntity<ModelMutationResult> togglePrimaryKey(
            @PathVariable UUID modelId,
            @RequestBody ColumnTarget target) {
        Objects.requireNonNull(target, "ColumnTarget body is required.");
        return ResponseEntity.ok(semanticModelManager.togglePrimaryKey(modelId, target.tableName(), target.columnName()));
    }

    @PostMapping("/models/{modelId}/hide-foreign-keys")
    @PreAuthorize("hasAuthority('PERM_MODEL_WRITE')")
    public ResponseEntity<ModelMutationResult> hideAllForeignKeys(@PathVariable UUID modelId) {
        return ResponseEntity.ok(semanticModelManager.hideAllForeignKeys(modelId));
    }

    @PostMapping("/models/{modelId}/date-table")
    @PreAuthorize("hasAuthority('PERM_MODEL_WRITE')")
    public ResponseEntity<ModelMutationResult> markDateTable(
            @PathVariable UUID modelId,
            @RequestBody DateTableRequest request) {
        Objects.requireNonNull(request, "DateTableRequest body is required.");
        return ResponseEntity.ok(semanticModelManager.markDateTable(modelId, request.toSpec()));
    }

    @PostMapping("/models/{modelId}/discourage-implicit-measures")
    @PreAuthorize("hasAuthority('PERM_MODEL_WRITE')")
    public ResponseEntity<ModelMutationResult> toggleDiscourageImplicitMeasures(@PathVariable UUID modelId) {
        return ResponseEntity.ok(semanticModelManager.toggleDiscourageImplicitMeasures(modelId));
    }

    @PatchMapping("/models/{modelId}/tables/{tableName}/columns/{columnName}")
    @PreAuthorize("hasAuthority('PERM_MODEL_WRITE')")
    public ResponseEntity<ModelMutationResult> updateColumnMetadata(
            @PathVariable UUID modelId,
            @PathVariable String tableName,
            @PathVariable String columnName,
            @RequestBody ColumnMetadataUpdate update) {
        Objects.requireNonNull(update, "ColumnMetadataUpdate body is required.");
        return ResponseEntity.ok(
                semanticModelManager.updateColumnMetadata(modelId, new ColumnTarget(tableName, columnName), update));
    }

    @PostMapping("/models/{modelId}/tables/{tableName}/rows")
    @PreAuthorize("hasAnyAuthority('PERM_MODEL_WRITE', 'PERM_REPORT_READ')")
    public ResponseEntity<TableRowsIngestionResult> ingestTableRows(
            @PathVariable UUID modelId,
            @PathVariable String tableName,
            @RequestBody List<Map<String, Object>> rows) {
        Objects.requireNonNull(rows, "Table rows payload is required.");
        semanticModelManager.getModel(modelId);
        int rowsIngested = dualEngineCoordinator.ingestDatasetRows("default-tenant", tableName, rows);
        return ResponseEntity.ok(new TableRowsIngestionResult(modelId, tableName, rowsIngested));
    }

    @GetMapping("/models/{modelId}/tables/{tableName}/rows")
    @PreAuthorize("hasAuthority('PERM_REPORT_READ')")
    public ResponseEntity<List<Map<String, Object>>> getTableRows(
            @PathVariable UUID modelId,
            @PathVariable String tableName) {
        semanticModelManager.getModel(modelId);
        return ResponseEntity.ok(dualEngineCoordinator.getTableRows(tableName));
    }

    @GetMapping(value = "/models/{modelId}/rsdl", produces = "text/plain;charset=UTF-8")
    @PreAuthorize("hasAuthority('PERM_REPORT_READ')")
    public ResponseEntity<String> exportModelRsdl(@PathVariable UUID modelId) {
        return ResponseEntity.ok(rsdlFormatter.format(semanticModelManager.getModel(modelId)));
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
        return ResponseEntity.ok(
                infoFunctionEvaluator.buildUnifiedDataDictionary(semanticModelManager.getModel(modelId)));
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
    @PreAuthorize("hasAuthority('PERM_REPORT_READ')")
    public ResponseEntity<QueryExecutionResult> executeVisualQuery(
            @PathVariable String reportId,
            @RequestBody VisualQueryDefinition query,
            Authentication authentication) {
        return ResponseEntity.ok(
                dualEngineCoordinator.executeVisualQuery(withReportId(query, reportId), authentication));
    }

    private VisualQueryDefinition withReportId(VisualQueryDefinition query, String reportId) {
        Objects.requireNonNull(query, "A visual query body is required.");
        return new VisualQueryDefinition(
                query.modelId(), query.tenantId(), reportId, query.visualId(),
                query.archetype(), query.values(), query.category(), query.legend(),
                query.rows(), query.columns(), query.sortSpec(),
                query.measureId(), query.raxExpression(), query.factTableName(),
                query.estimatedRows(), query.operationalLookup(), query.groupByColumns(),
                query.activeFilters(), query.crossFilters(), query.externalSlicerFilters(),
                query.evaluationDate(), query.acceptLanguage());
    }

    @MessageMapping("/calculate-measure")
    public void calculateMeasureViaStomp(VisualQueryDefinition query, Authentication authentication) {
        dualEngineCoordinator.executeVisualQuery(query, authentication);
    }
}
