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

    @PostMapping(value = "/{reportId}/export", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("hasAuthority('PERM_REPORT_READ')")
    public ResponseEntity<ExportJobReceipt> exportReport(
            @PathVariable String reportId,
            @Valid @RequestBody(required = false) ExportRequest request) {
        LOGGER.info("Received REST request to export report '{}'", reportId);
        ExportJobReceipt receipt = exportService.exportReport(reportId, request);
        return ResponseEntity.ok(receipt);
    }
}
