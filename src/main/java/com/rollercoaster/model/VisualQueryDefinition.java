package com.rollercoaster.model;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Represents an analytical query dispatched from a Report Builder visual card or data export request.
 */
public record VisualQueryDefinition(
        UUID modelId,
        String tenantId,
        String reportId,
        String visualId,
        VisualArchetype archetype,
        List<String> values,
        List<String> category,
        List<String> legend,
        List<String> rows,
        List<String> columns,
        SortSpec sortSpec,
        String measureId,
        String raxExpression,
        String factTableName,
        long estimatedRows,
        boolean operationalLookup,
        List<String> groupByColumns,
        Map<String, Set<Object>> activeFilters,
        Map<String, Set<Object>> crossFilters,
        Map<String, Set<Object>> externalSlicerFilters,
        LocalDate evaluationDate,
        String acceptLanguage
) {

    private static final String DEFAULT_TENANT = "default-tenant";
    private static final String DEFAULT_REPORT_ID = "default-report";
    private static final String DEFAULT_VISUAL_ID = "default-visual";
    private static final String DEFAULT_LANGUAGE = "en-US";

    public VisualQueryDefinition {
        tenantId = (tenantId != null && !tenantId.isBlank()) ? tenantId : DEFAULT_TENANT;
        reportId = (reportId != null && !reportId.isBlank()) ? reportId : DEFAULT_REPORT_ID;
        visualId = (visualId != null && !visualId.isBlank()) ? visualId : DEFAULT_VISUAL_ID;
        archetype = archetype != null ? archetype : VisualArchetype.BAR;
        values = sanitizeStrings(values);
        category = sanitizeStrings(category);
        legend = sanitizeStrings(legend);
        rows = sanitizeStrings(rows);
        columns = sanitizeStrings(columns);
        factTableName = factTableName != null ? factTableName : "";
        groupByColumns = sanitizeStrings(groupByColumns);
        activeFilters = copyFilterMap(activeFilters);
        crossFilters = copyFilterMap(crossFilters);
        externalSlicerFilters = copyFilterMap(externalSlicerFilters);
        evaluationDate = evaluationDate != null ? evaluationDate : LocalDate.now();
        acceptLanguage = (acceptLanguage != null && !acceptLanguage.isBlank()) ? acceptLanguage : DEFAULT_LANGUAGE;
    }

    /**
     * Legacy constructor preserving backward compatibility for direct engine queries.
     */
    public VisualQueryDefinition(
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
            String acceptLanguage) {
        this(
                modelId,
                tenantId,
                reportId,
                visualId,
                VisualArchetype.BAR,
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                null,
                measureId,
                raxExpression,
                factTableName,
                estimatedRows,
                operationalLookup,
                groupByColumns,
                activeFilters,
                Map.of(),
                externalSlicerFilters,
                evaluationDate,
                acceptLanguage
        );
    }

    /**
     * Cohesive constructor for visual archetype aggregation queries.
     */
    public VisualQueryDefinition(
            UUID modelId,
            String visualId,
            VisualArchetype archetype,
            FieldWellBindings fieldWells,
            SortSpec sortSpec,
            Map<String, Set<Object>> crossFilters,
            Map<String, Set<Object>> externalSlicerFilters) {
        this(
                modelId,
                DEFAULT_TENANT,
                DEFAULT_REPORT_ID,
                visualId,
                archetype,
                fieldWells != null ? fieldWells.values() : List.of(),
                fieldWells != null ? fieldWells.category() : List.of(),
                fieldWells != null ? fieldWells.legend() : List.of(),
                fieldWells != null ? fieldWells.rows() : List.of(),
                fieldWells != null ? fieldWells.columns() : List.of(),
                sortSpec,
                null,
                null,
                "",
                0L,
                false,
                List.of(),
                Map.of(),
                crossFilters,
                externalSlicerFilters,
                LocalDate.now(),
                DEFAULT_LANGUAGE
        );
    }

    public FieldWellBindings fieldWells() {
        return new FieldWellBindings(values, category, legend, rows, columns);
    }

    private static List<String> sanitizeStrings(List<String> items) {
        if (items == null || items.isEmpty()) {
            return List.of();
        }
        return items.stream()
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
    }

    private static Map<String, Set<Object>> copyFilterMap(Map<String, Set<Object>> source) {
        if (source == null || source.isEmpty()) {
            return Map.of();
        }
        Map<String, Set<Object>> copy = new LinkedHashMap<>();
        for (Map.Entry<String, Set<Object>> entry : source.entrySet()) {
            String key = entry.getKey();
            Set<Object> valSet = entry.getValue();
            if (key != null && !key.isBlank() && valSet != null) {
                Set<Object> nonNullValues = new LinkedHashSet<>();
                for (Object item : valSet) {
                    if (item != null) {
                        nonNullValues.add(item);
                    }
                }
                copy.put(key.trim(), Set.copyOf(nonNullValues));
            }
        }
        return Map.copyOf(copy);
    }
}
