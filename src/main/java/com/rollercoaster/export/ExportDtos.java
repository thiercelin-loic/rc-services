package com.rollercoaster.export;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.List;

public final class ExportDtos {

    private ExportDtos() {
    }

    public record ExportVisualRecord(
            String id,
            String title,
            String archetype,
            String width,
            String metricLabel,
            String metricValue,
            String metricSubtext) {
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
            Integer dpi,
            Boolean includeBom,
            String delimiter,
            String contextHash,
            String projectName,
            String pageName,
            List<ExportVisualRecord> visuals,
            List<ExportMeasureRecord> measures) {

        public ExportRequest {
            visuals = visuals != null ? List.copyOf(visuals) : List.of();
            measures = measures != null ? List.copyOf(measures) : List.of();
        }

        public static ExportRequest withVisualContext(String reportId, String visualId, ExportRequest source) {
            if (source == null) {
                return new ExportRequest(
                        reportId, visualId, "CSV", null, null, null, null, null, null, List.of(), List.of());
            }
            return new ExportRequest(
                    reportId,
                    visualId,
                    source.format(),
                    source.dpi(),
                    source.includeBom(),
                    source.delimiter(),
                    source.contextHash(),
                    source.projectName(),
                    source.pageName(),
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
