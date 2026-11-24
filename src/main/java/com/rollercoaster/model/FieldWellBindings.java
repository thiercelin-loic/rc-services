package com.rollercoaster.model;

import java.util.List;
import java.util.Objects;

/**
 * Immutable field well bindings for a Report Builder visual.
 */
public record FieldWellBindings(
        List<String> values,
        List<String> category,
        List<String> legend,
        List<String> rows,
        List<String> columns
) {

    public static final FieldWellBindings EMPTY = new FieldWellBindings(
            List.of(), List.of(), List.of(), List.of(), List.of());

    public FieldWellBindings {
        values = sanitizeList(values);
        category = sanitizeList(category);
        legend = sanitizeList(legend);
        rows = sanitizeList(rows);
        columns = sanitizeList(columns);
    }

    public static FieldWellBindings forChart(List<String> values, List<String> category, List<String> legend) {
        return new FieldWellBindings(values, category, legend, List.of(), List.of());
    }

    public static FieldWellBindings forMatrix(List<String> rows, List<String> columns, List<String> values) {
        return new FieldWellBindings(values, List.of(), List.of(), rows, columns);
    }

    public static FieldWellBindings forMeasureCard(String valueField) {
        List<String> vals = (valueField != null && !valueField.isBlank()) ? List.of(valueField.trim()) : List.of();
        return new FieldWellBindings(vals, List.of(), List.of(), List.of(), List.of());
    }

    public static FieldWellBindings forSlicer(String dimensionField) {
        List<String> cats = (dimensionField != null && !dimensionField.isBlank())
                ? List.of(dimensionField.trim())
                : List.of();
        return new FieldWellBindings(List.of(), cats, List.of(), List.of(), List.of());
    }

    private static List<String> sanitizeList(List<String> raw) {
        if (raw == null || raw.isEmpty()) {
            return List.of();
        }
        return raw.stream()
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
    }
}
