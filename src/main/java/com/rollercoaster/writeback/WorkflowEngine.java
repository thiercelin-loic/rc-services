package com.rollercoaster.writeback;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Validates operational write-back adjustments (Capacity Allocation & Forecast Override Grid)
 * and coordinates approval notifications.
 */
@Service
public class WorkflowEngine {

    private final ObjectMapper objectMapper;

    public WorkflowEngine(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public ValidationOutcome validateSubmission(WritebackStagingEntity entity) {
        List<String> errors = new ArrayList<>();
        if (entity.getTargetTable() == null || entity.getTargetTable().isBlank()) {
            errors.add("Target table is required.");
        }
        try {
            Map<String, Object> payload = objectMapper.readValue(
                    entity.getPayloadJson(), new TypeReference<>() {});
            if (payload.isEmpty()) {
                errors.add("Write-back payload cannot be empty.");
            }
            if (payload.containsKey("soldUnits")) {
                int sold = ((Number) payload.get("soldUnits")).intValue();
                int received = payload.containsKey("receivedUnits")
                        ? ((Number) payload.get("receivedUnits")).intValue()
                        : Integer.MAX_VALUE;
                if (sold < 0 || sold > received) {
                    errors.add("Sold units must be between 0 and received units (" + received + ").");
                }
            }
            if (payload.containsKey("adjustmentPct")) {
                double adj = ((Number) payload.get("adjustmentPct")).doubleValue();
                if (adj < -50.0 || adj > 50.0) {
                    errors.add("Markdown / forecast adjustment percentage must stay within [-50%, +50%].");
                }
            }
        } catch (Exception e) {
            errors.add("Invalid JSON payload in writeback submission: " + e.getMessage());
        }
        return new ValidationOutcome(errors.isEmpty(), List.copyOf(errors));
    }

    public record ValidationOutcome(boolean valid, List<String> errors) {
    }
}
