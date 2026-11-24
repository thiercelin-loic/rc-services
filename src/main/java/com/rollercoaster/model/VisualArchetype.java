package com.rollercoaster.model;

import com.fasterxml.jackson.annotation.JsonCreator;

import java.util.Locale;

/**
 * Strongly typed visual archetypes supported by the Report Builder aggregation engine.
 */
public enum VisualArchetype {
    BAR,
    LINE,
    AREA,
    DONUT,
    GAUGE,
    GEO_MAP,
    MATRIX,
    MEASURE_CARD,
    SLICER;

    public boolean isChartSeries() {
        return switch (this) {
            case BAR, LINE, AREA, DONUT, GAUGE, GEO_MAP -> true;
            case MATRIX, MEASURE_CARD, SLICER -> false;
        };
    }

    public boolean requiresDimensionGrouping() {
        return switch (this) {
            case BAR, LINE, AREA, DONUT, GEO_MAP, MATRIX, SLICER -> true;
            case GAUGE, MEASURE_CARD -> false;
        };
    }

    @JsonCreator
    public static VisualArchetype fromToken(String token) {
        if (token == null || token.isBlank()) {
            throw new IllegalArgumentException("VisualArchetype token must not be blank.");
        }
        String normalized = token.trim().toUpperCase(Locale.ROOT).replace('-', '_').replace(' ', '_');
        return switch (normalized) {
            case "BAR", "BAR_CHART", "COLUMN", "COLUMN_CHART" -> BAR;
            case "LINE", "LINE_CHART" -> LINE;
            case "AREA", "AREA_CHART" -> AREA;
            case "DONUT", "DONUT_CHART", "PIE", "PIE_CHART" -> DONUT;
            case "GAUGE", "GAUGE_CHART" -> GAUGE;
            case "GEO_MAP", "GEOMAP", "MAP" -> GEO_MAP;
            case "MATRIX", "PIVOT", "TABLE" -> MATRIX;
            case "MEASURE_CARD", "MEASURECARD", "CARD", "KPI", "KPI_CARD" -> MEASURE_CARD;
            case "SLICER", "FILTER" -> SLICER;
            default -> throw new IllegalArgumentException("Unsupported visual archetype: " + token);
        };
    }
}
