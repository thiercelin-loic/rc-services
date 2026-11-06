package com.rollercoaster.rax.diagnostics;

import com.rollercoaster.model.SemanticModelSchema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.messaging.handler.annotation.DestinationVariable;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Objects;

@RestController
@RequestMapping("/api/rax/diagnostics")
public class RaxDiagnosticsController {

    private static final Logger LOGGER = LoggerFactory.getLogger(RaxDiagnosticsController.class);

    private final MonacoDiagnosticsService diagnosticsService;

    public RaxDiagnosticsController(MonacoDiagnosticsService diagnosticsService) {
        this.diagnosticsService = Objects.requireNonNull(diagnosticsService, "MonacoDiagnosticsService must not be null");
    }

    public record DiagnosticsRequest(
            @NotBlank(message = "Workspace identifier is required")
            String workspaceId,

            @NotNull(message = "Formula string must not be null")
            String formula,

            SemanticModelSchema schema) {
    }

    @PostMapping
    public ResponseEntity<List<DiagnosticMarker>> validateFormulaRest(@Valid @RequestBody DiagnosticsRequest request) {
        LOGGER.debug("Validating RAX formula via REST for workspace: {}", request.workspaceId());
        List<DiagnosticMarker> markers = diagnosticsService.validateAndBroadcast(
                request.workspaceId(),
                request.formula(),
                request.schema());
        return ResponseEntity.ok(markers);
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
