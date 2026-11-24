package com.rollercoaster.rax.diagnostics;

import com.rollercoaster.model.MeasureDefinition;
import com.rollercoaster.model.QueryExecutionResult;
import com.rollercoaster.model.SemanticModelManager;
import com.rollercoaster.model.SemanticModelSchema;
import com.rollercoaster.rax.ast.SqlDialect;
import com.rollercoaster.rax.compiler.AnalyticalArchetypeLibrary;
import com.rollercoaster.rax.compiler.AnalyticalArchetypeLibrary.RaxFunctionCatalogEntry;
import com.rollercoaster.rax.compiler.RaxQueryFormatter;
import com.rollercoaster.rax.compiler.RaxScriptService;
import com.rollercoaster.rax.compiler.RaxScriptService.ApplyMeasuresResult;
import com.rollercoaster.rax.compiler.RaxScriptService.ScriptExecutionRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.messaging.handler.annotation.DestinationVariable;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

@RestController
@RequestMapping("/api/rax")
public class RaxDiagnosticsController {

    private static final Logger LOGGER = LoggerFactory.getLogger(RaxDiagnosticsController.class);

    private final MonacoDiagnosticsService diagnosticsService;
    private final RaxQueryFormatter queryFormatter;
    private final RaxScriptService scriptService;
    private final SemanticModelManager semanticModelManager;
    private final AnalyticalArchetypeLibrary archetypeLibrary;

    public RaxDiagnosticsController(MonacoDiagnosticsService diagnosticsService) {
        this(
                diagnosticsService,
                new RaxQueryFormatter(),
                null,
                new SemanticModelManager(),
                new AnalyticalArchetypeLibrary());
    }

    @Autowired
    public RaxDiagnosticsController(
            MonacoDiagnosticsService diagnosticsService,
            RaxQueryFormatter queryFormatter,
            RaxScriptService scriptService,
            SemanticModelManager semanticModelManager,
            AnalyticalArchetypeLibrary archetypeLibrary) {
        this.diagnosticsService = Objects.requireNonNull(diagnosticsService, "MonacoDiagnosticsService must not be null");
        this.queryFormatter = Objects.requireNonNull(queryFormatter, "RaxQueryFormatter must not be null");
        this.scriptService = scriptService;
        this.semanticModelManager = Objects.requireNonNull(semanticModelManager, "SemanticModelManager must not be null");
        this.archetypeLibrary = Objects.requireNonNull(archetypeLibrary, "AnalyticalArchetypeLibrary must not be null");
    }

    public record DiagnosticsRequest(
            @NotBlank(message = "Workspace identifier is required")
            String workspaceId,

            @NotNull(message = "Formula string must not be null")
            String formula,

            SemanticModelSchema schema) {
    }

    public record FormulaInspectRequest(
            String workspaceId,
            UUID modelId,
            String formula,
            String defaultMeasureName,
            SemanticModelSchema schema
    ) {
        public FormulaInspectRequest(
                String workspaceId,
                String formula,
                String defaultMeasureName,
                SemanticModelSchema schema) {
            this(workspaceId, null, formula, defaultMeasureName, schema);
        }
    }

    public record FormatQueryRequest(
            @NotBlank(message = "RAX script is required")
            String script
    ) {}

    public record FormatQueryResponse(String formattedScript) {}

    public record TmdlGenerateRequest(UUID modelId) {}

    public record TmdlGenerateResponse(String tmdlScript) {}

    public record DefineMeasuresResponse(String script) {}

    public record ExecuteRaxScriptRequest(
            UUID modelId,
            @NotBlank(message = "RAX script is required")
            String script,
            SqlDialect dialect
    ) {}

    public record ApplyMeasuresRequest(
            UUID modelId,
            @NotBlank(message = "RAX script is required")
            String script
    ) {}

    public record ApplyMeasuresResponse(
            int appliedCount,
            List<MeasureDefinition> measures
    ) {
        public ApplyMeasuresResponse {
            measures = measures != null ? List.copyOf(measures) : List.of();
        }
    }

    @PostMapping("/diagnostics/validate")
    public ResponseEntity<MonacoDiagnosticsService.RaxValidationResponse> inspectFormulaRest(
            @RequestBody FormulaInspectRequest request) {
        LOGGER.debug("Inspecting and tokenizing RAX formula via REST for workspace: {}", request.workspaceId());
        SemanticModelSchema resolvedSchema = request.schema() != null
                ? request.schema()
                : semanticModelManager.getModel(request.modelId());
        return ResponseEntity.ok(diagnosticsService.inspectFormula(
                request.formula(),
                request.defaultMeasureName(),
                resolvedSchema));
    }

    @PostMapping("/diagnostics")
    public ResponseEntity<List<DiagnosticMarker>> validateFormulaRest(@Valid @RequestBody DiagnosticsRequest request) {
        LOGGER.debug("Validating RAX formula via REST for workspace: {}", request.workspaceId());
        SemanticModelSchema resolvedSchema = request.schema() != null
                ? request.schema()
                : semanticModelManager.getDefaultModel();
        List<DiagnosticMarker> markers = diagnosticsService.validateAndBroadcast(
                request.workspaceId(),
                request.formula(),
                resolvedSchema);
        return ResponseEntity.ok(markers);
    }

    @PostMapping("/format")
    public ResponseEntity<FormatQueryResponse> formatScript(@Valid @RequestBody FormatQueryRequest request) {
        String formatted = queryFormatter.formatQuery(request.script());
        return ResponseEntity.ok(new FormatQueryResponse(formatted));
    }

    @PostMapping("/tmdl")
    public ResponseEntity<TmdlGenerateResponse> generateModelTmdl(
            @RequestBody(required = false) TmdlGenerateRequest request) {
        SemanticModelSchema schema = semanticModelManager.getModel(request != null ? request.modelId() : null);
        String tmdl = queryFormatter.generateModelTmdl(schema);
        return ResponseEntity.ok(new TmdlGenerateResponse(tmdl));
    }

    @PostMapping("/measures/define")
    public ResponseEntity<DefineMeasuresResponse> defineAllMeasures(
            @RequestBody(required = false) TmdlGenerateRequest request) {
        SemanticModelSchema schema = semanticModelManager.getModel(request != null ? request.modelId() : null);
        return ResponseEntity.ok(new DefineMeasuresResponse(queryFormatter.defineAllMeasures(schema)));
    }

    @PostMapping("/execute")
    public ResponseEntity<QueryExecutionResult> executeScript(
            @Valid @RequestBody ExecuteRaxScriptRequest request,
            Authentication authentication) {
        if (scriptService == null) {
            throw new IllegalStateException("RaxScriptService is not initialized.");
        }
        ScriptExecutionRequest execRequest = new ScriptExecutionRequest(
                request.modelId(),
                request.script(),
                request.dialect());
        return ResponseEntity.ok(scriptService.executeScript(execRequest, authentication));
    }

    @PostMapping("/measures/apply")
    public ResponseEntity<ApplyMeasuresResponse> applyMeasuresFromScript(
            @Valid @RequestBody ApplyMeasuresRequest request) {
        if (scriptService == null) {
            throw new IllegalStateException("RaxScriptService is not initialized.");
        }
        ApplyMeasuresResult result = scriptService.extractAndSaveMeasuresFromScript(
                request.modelId(),
                request.script());
        return ResponseEntity.ok(new ApplyMeasuresResponse(result.appliedCount(), result.measures()));
    }

    @GetMapping("/catalog")
    public ResponseEntity<List<RaxFunctionCatalogEntry>> getFunctionCatalog() {
        return ResponseEntity.ok(archetypeLibrary.getFunctionCatalog());
    }

    @MessageMapping("/diagnostics/{workspaceId}")
    public void validateFormulaStomp(
            @DestinationVariable String workspaceId,
            @Valid DiagnosticsRequest request) {
        LOGGER.debug("Validating RAX formula via STOMP for workspace: {}", workspaceId);
        String targetWorkspace = (workspaceId != null && !workspaceId.isBlank())
                ? workspaceId
                : request.workspaceId();
        diagnosticsService.validateAndBroadcast(targetWorkspace, request.formula(), request.schema());
    }
}
