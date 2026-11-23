package com.rollercoaster.serialization;

import java.util.List;
import java.util.Map;

public final class ProjectDtos {

    private ProjectDtos() {
    }

    public record VisualItemDto(
            String id,
            String title,
            String archetype,
            String width,
            String metricLabel,
            String metricValue,
            String metricSubtext,
            String sortBy,
            String sortOrder,
            Map<String, List<String>> fieldWells) {
    }

    public record ColumnDto(
            String name,
            String dataType,
            String keyType,
            String description,
            Boolean isHidden,
            String displayFolder,
            String sortByColumn,
            String summarizeBy,
            String displayFormat,
            String expression) {
    }

    public record TableDto(
            String id,
            String name,
            String role,
            List<ColumnDto> columns,
            Integer rowCount,
            String description,
            String expression,
            Boolean isHidden,
            Boolean isDateTable,
            String dateColumn) {
    }

    public record MeasureDto(
            String id,
            String label,
            String formula,
            String table,
            String description,
            String formatString,
            Boolean isHidden) {
    }

    public record ProjectExportRequest(
            String filename,
            String themeMode,
            List<String> pages,
            Map<String, List<VisualItemDto>> visualsMap,
            List<TableDto> tables,
            Map<String, List<Map<String, Object>>> rows,
            List<MeasureDto> measures) {
    }

    public record ProjectArchiveReceipt(
            String filename,
            String archiveBase64,
            String watermark,
            String checksumSha256) {
    }

    public record ProjectImportRequest(
            String filename,
            String archiveBase64) {
    }

    public record ParsedProjectResponse(
            String filename,
            List<String> pages,
            Map<String, List<VisualItemDto>> visualsMap,
            List<TableDto> tables,
            Map<String, List<Map<String, Object>>> rows,
            List<MeasureDto> measures) {
    }
}
