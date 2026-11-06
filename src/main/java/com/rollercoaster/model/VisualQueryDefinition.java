package com.rollercoaster.model;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Represents an analytical query dispatched from a visual card or data export request.
 */
public record VisualQueryDefinition(
        UUID modelId,
        String tenantId,
        String reportId,
        String visualId,
        String measureId,
        String raxExpression,
        String factTableName,
        long estimatedRows,
        boolean operationalLookup,
        List<String> groupByColumns,
        Map<String, Set<Object>> activeFilters,
        Map<String, Set<Object>> externalSlicerFilters,
        LocalDate evaluationDate,
        String acceptLanguage
) {
    public VisualQueryDefinition {
        tenantId = tenantId != null ? tenantId : "default-tenant";
        factTableName = factTableName != null ? factTableName : "";
        groupByColumns = groupByColumns != null ? List.copyOf(groupByColumns) : List.of();
        activeFilters = activeFilters != null ? Map.copyOf(activeFilters) : Map.of();
        externalSlicerFilters = externalSlicerFilters != null ? Map.copyOf(externalSlicerFilters) : Map.of();
        evaluationDate = evaluationDate != null ? evaluationDate : LocalDate.now();
        acceptLanguage = acceptLanguage != null ? acceptLanguage : "en-US";
    }
}
