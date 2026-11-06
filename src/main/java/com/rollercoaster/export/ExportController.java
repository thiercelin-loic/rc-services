package com.rollercoaster.export;

import com.rollercoaster.export.ExportDtos.ExportJobReceipt;
import com.rollercoaster.export.ExportDtos.ExportRequest;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/reports")
public class ExportController {

    private static final Logger LOGGER = LoggerFactory.getLogger(ExportController.class);

    private final ExportService exportService;

    public ExportController(ExportService exportService) {
        this.exportService = exportService;
    }

    @PostMapping(
            value = "/export/pdf",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("hasAnyAuthority('PERM_REPORT_READ', 'ROLE_ADMIN', 'ROLE_USER', 'ROLE_OPERATOR')")
    public ResponseEntity<ExportJobReceipt> exportPdf(@Valid @RequestBody ExportRequest request) {
        LOGGER.info("Received REST request to export PDF for report: {}", request.reportId());
        ExportJobReceipt receipt = exportService.exportPdf(request);
        return ResponseEntity.ok(receipt);
    }

    @PostMapping(
            value = "/{reportId}/visuals/{visualId}/export",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("hasAnyAuthority('PERM_REPORT_READ', 'ROLE_ADMIN', 'ROLE_USER', 'ROLE_OPERATOR')")
    public ResponseEntity<ExportJobReceipt> exportVisual(
            @PathVariable String reportId,
            @PathVariable String visualId,
            @Valid @RequestBody(required = false) ExportRequest request) {
        LOGGER.info("Received REST request to export visual '{}' in report '{}'", visualId, reportId);
        ExportJobReceipt receipt = exportService.exportVisual(reportId, visualId, request);
        return ResponseEntity.ok(receipt);
    }
}
