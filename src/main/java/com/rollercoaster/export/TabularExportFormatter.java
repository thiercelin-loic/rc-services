package com.rollercoaster.export;

import com.rollercoaster.export.ExportDtos.ExportRequest;
import com.rollercoaster.export.ExportDtos.ExportVisualRecord;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;

/**
 * Formats report export payloads into PDF, CSV, and TSV formats.
 */
@Component
public class TabularExportFormatter {

    private static final String LINE_SEPARATOR = "\r\n";
    private static final char UTF8_BYTE_ORDER_MARK = '\uFEFF';

    public byte[] buildPdf(ExportRequest request, String projectName, String pageName, Instant timestamp) {
        int visualCount = request.visuals() != null ? request.visuals().size() : 0;
        String safeProject = projectName.replace("(", "[").replace(")", "]");
        String safePage = pageName.replace("(", "[").replace(")", "]");
        String textStream = String.format(
                "BT\n/F1 18 Tf\n50 750 Td\n(%s) Tj\n/F1 12 Tf\n0 -30 Td\n(Page: %s | Exported: %s) Tj\n0 -25 Td\n(Total Visuals: %d) Tj\nET",
                safeProject,
                safePage,
                timestamp.toString(),
                visualCount);
        byte[] streamBytes = textStream.getBytes(StandardCharsets.US_ASCII);

        StringBuilder sb = new StringBuilder();
        sb.append("%PDF-1.4\n%\\xE2\\xE3\\xCF\\xD3\n");
        int[] offsets = new int[5];

        offsets[0] = sb.length();
        sb.append("1 0 obj\n<</Type /Catalog /Pages 2 0 R>>\nendobj\n");
        offsets[1] = sb.length();
        sb.append("2 0 obj\n<</Type /Pages /Kids [3 0 R] /Count 1>>\nendobj\n");
        offsets[2] = sb.length();
        sb.append("3 0 obj\n<</Type /Page /Parent 2 0 R /MediaBox [0 0 595 842] /Resources << /Font << /F1 4 0 R >> >> /Contents 5 0 R>>\nendobj\n");
        offsets[3] = sb.length();
        sb.append("4 0 obj\n<</Type /Font /Subtype /Type1 /BaseFont /Helvetica>>\nendobj\n");
        offsets[4] = sb.length();
        sb.append("5 0 obj\n<</Length ").append(streamBytes.length).append(">>\nstream\n");
        sb.append(textStream).append("\nendstream\nendobj\n");

        int xrefOffset = sb.length();
        sb.append("xref\n0 6\n0000000000 65535 f \n");
        for (int off : offsets) {
            sb.append(String.format("%010d 00000 n \n", off));
        }
        sb.append("trailer\n<</Size 6 /Root 1 0 R>>\nstartxref\n").append(xrefOffset).append("\n%%EOF\n");
        return sb.toString().getBytes(StandardCharsets.US_ASCII);
    }

    public byte[] buildDelimited(
            ExportRequest request,
            String delimiter,
            boolean includeBom,
            String project,
            String page,
            String contextHash,
            Instant timestamp) {
        StringBuilder builder = new StringBuilder();
        if (includeBom) {
            builder.append(UTF8_BYTE_ORDER_MARK);
        }
        builder.append(String.join(delimiter, XlsxExportBuilder.VISUAL_COLUMNS)).append(LINE_SEPARATOR);

        List<ExportVisualRecord> visuals = request.visuals() != null ? request.visuals() : List.of();
        for (ExportVisualRecord visual : visuals) {
            String[] fields = {
                    escapeDelimitedField(project, delimiter),
                    escapeDelimitedField(page, delimiter),
                    escapeDelimitedField(safeValue(visual.id()), delimiter),
                    escapeDelimitedField(safeValue(visual.title()), delimiter),
                    escapeDelimitedField(safeValue(visual.archetype()), delimiter),
                    escapeDelimitedField(safeValue(visual.metricLabel()), delimiter),
                    escapeDelimitedField(safeValue(visual.metricValue()), delimiter),
                    escapeDelimitedField(safeValue(visual.metricSubtext()), delimiter),
                    escapeDelimitedField(contextHash, delimiter),
                    escapeDelimitedField(timestamp.toString(), delimiter)
            };
            builder.append(String.join(delimiter, fields)).append(LINE_SEPARATOR);
        }
        return builder.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static String escapeDelimitedField(String value, String delimiter) {
        String normalized = value == null ? "" : value;
        if (normalized.contains("\"") || normalized.contains("\n") || normalized.contains("\r") || normalized.contains(delimiter)) {
            return "\"" + normalized.replace("\"", "\"\"") + "\"";
        }
        return "\"" + normalized + "\"";
    }

    private static String safeValue(String value) {
        return value != null ? value : "";
    }
}
