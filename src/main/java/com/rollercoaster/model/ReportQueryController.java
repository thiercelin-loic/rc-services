package com.rollercoaster.model;

import com.rollercoaster.model.VisualAggregationService.VisualBatchQueryRequest;
import com.rollercoaster.model.VisualAggregationService.VisualBatchQueryResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Objects;

/**
 * Thin REST controller exposing batch Report Builder visual aggregation queries under {@code /api/reports}.
 */
@RestController
@RequestMapping("/api/reports")
public class ReportQueryController {

    private final VisualAggregationService visualAggregationService;

    public ReportQueryController(VisualAggregationService visualAggregationService) {
        this.visualAggregationService = Objects.requireNonNull(
                visualAggregationService, "visualAggregationService must not be null");
    }

    @PostMapping("/visuals/query")
    @PreAuthorize("hasAuthority('PERM_REPORT_READ')")
    public ResponseEntity<VisualBatchQueryResponse> queryVisualBatch(
            @RequestBody(required = false) VisualBatchQueryRequest request,
            Authentication authentication) {
        return ResponseEntity.ok(visualAggregationService.executeBatch(request, authentication));
    }
}
