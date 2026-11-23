package com.rollercoaster.export;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.List;

public final class ExportDtos {

    private ExportDtos() {
    }

    public record ExportChartDataset(
            String label,
            List<Double> data) {
        public ExportChartDataset {
            data = data != null ? List.copyOf(data) : List.of();
        }
    }

    public record ExportChartData(
            List<String> labels,
            List<ExportChartDataset> datasets) {
        public ExportChartData {
            labels = labels != null ? List.copyOf(labels) : List.of();
            datasets = datasets != null ? List.copyOf(datasets) : List.of();
        }
    }

    public record CanvasDimensions(
            Integer width,
            Integer height,
            String preset) {
    }

    public record ExportVisualRecord(
            String id,
            String title,
            String archetype,
            String width,
            String metricLabel,
            String metricValue,
            String metricSubtext,
            ExportChartData chartData) {

        public ExportVisualRecord(
                String id,
                String title,
                String archetype,
                String width,
                String metricLabel,
                String metricValue,
                String metricSubtext) {
            this(id, title, archetype, width, metricLabel, metricValue, metricSubtext, null);
        }
    }

    public record ExportMeasureRecord(
            String id,
            String label,
            String formula,
            String table) {
    }

    public record ExportRequest(
            String reportId,
            String visualId,
            String format,
            Boolean includeBom,
            String delimiter,
            String contextHash,
            String projectName,
            String pageName,
            CanvasDimensions canvasDimensions,
            List<ExportVisualRecord> visuals,
            List<ExportMeasureRecord> measures) {

        public ExportRequest {
            visuals = visuals != null ? List.copyOf(visuals) : List.of();
            measures = measures != null ? List.copyOf(measures) : List.of();
        }

        public ExportRequest(
                String reportId,
                String visualId,
                String format,
                Boolean includeBom,
                String delimiter,
                String contextHash,
                String projectName,
                String pageName,
                List<ExportVisualRecord> visuals,
                List<ExportMeasureRecord> measures) {
            this(
                    reportId,
                    visualId,
                    format,
                    includeBom,
                    delimiter,
                    contextHash,
                    projectName,
                    pageName,
                    null,
                    visuals,
                    measures);
        }

        public static ExportRequest withReportContext(String reportId, ExportRequest source) {
            if (source == null) {
                return new ExportRequest(
                        reportId, null, "CSV", null, null, null, null, null, null, List.of(), List.of());
            }
            return new ExportRequest(
                    reportId,
                    source.visualId(),
                    source.format(),
                    source.includeBom(),
                    source.delimiter(),
                    source.contextHash(),
                    source.projectName(),
                    source.pageName(),
                    source.canvasDimensions(),
                    source.visuals(),
                    source.measures());
        }
    }

    public record ExportJobReceipt(
            @NotBlank String jobId,
            @NotBlank String format,
            @NotBlank String mimeType,
            @NotBlank String filename,
            @NotBlank String engine,
            @NotBlank String status,
            @NotNull Instant timestamp,
            String fileContentBase64) {
    }
}
