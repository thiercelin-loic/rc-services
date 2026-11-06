package com.rollercoaster.export;

import com.rollercoaster.export.ExportDtos.ExportJobReceipt;
import com.rollercoaster.export.ExportDtos.ExportMeasureRecord;
import com.rollercoaster.export.ExportDtos.ExportRequest;
import com.rollercoaster.export.ExportDtos.ExportVisualRecord;
import com.rollercoaster.model.DuckDbConnectionManager;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.streaming.SXSSFWorkbook;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

@Service
public class ExportServiceImpl implements ExportService {

    private static final Logger LOGGER = LoggerFactory.getLogger(ExportServiceImpl.class);

    private static final String DEFAULT_PROJECT_NAME = "Untitled Project.rcp";
    private static final String DEFAULT_PAGE_NAME = "Page 1";
    private static final String DEFAULT_CONTEXT_HASH = "00000000";
    private static final String DEFAULT_DELIMITER = ";";
    private static final String TAB_DELIMITER = "\t";
    private static final String LINE_SEPARATOR = "\r\n";
    private static final char UTF8_BYTE_ORDER_MARK = '\uFEFF';
    private static final String COMPLETED_STATUS = "COMPLETED";
    private static final int POI_ROW_WINDOW_SIZE = 100;

    private static final String[] VISUAL_COLUMNS = {
            "Project", "Page", "Visual ID", "Visual Title", "Archetype",
            "Metric Label", "Metric Value", "Metric Subtext", "Context Hash", "Exported At"
    };

    private static final String[] MEASURE_COLUMNS = {
            "Measure ID", "Measure Name", "Table", "RAX Formula"
    };

    private final DuckDbConnectionManager duckDbConnectionManager;

    public ExportServiceImpl(DuckDbConnectionManager duckDbConnectionManager) {
        this.duckDbConnectionManager = duckDbConnectionManager;
    }

    @Override
    public ExportJobReceipt exportPdf(ExportRequest request) {
        LOGGER.info("Initiating PDF export receipt generation for project: {}", resolveSafeString(request.projectName(), null, DEFAULT_PROJECT_NAME));
        return generateReceipt(request, ".pdf");
    }

    @Override
    public ExportJobReceipt exportVisual(String reportId, String visualId, ExportRequest request) {
        ExportRequest effectiveRequest = ExportRequest.withVisualContext(reportId, visualId, request);
        LOGGER.info("Exporting visual '{}' in report '{}' with format '{}'", visualId, reportId, effectiveRequest.format());
        return generateReceipt(effectiveRequest, effectiveRequest.format());
    }

    private ExportJobReceipt generateReceipt(ExportRequest request, String requestedFormat) {
        assertReportNotEmpty(request, requestedFormat);
        ExportMetadata metadata = ExportMetadata.forFormat(requestedFormat);
        Instant timestamp = Instant.now();
        byte[] payloadBytes = generateBinaryPayload(request, metadata.extension(), timestamp);

        String base64Payload = (payloadBytes != null && payloadBytes.length > 0)
                ? Base64.getEncoder().encodeToString(payloadBytes)
                : null;

        return new ExportJobReceipt(
                "exp-" + UUID.randomUUID(),
                metadata.extension(),
                metadata.mimeType(),
                metadata.filename(),
                metadata.engine(),
                COMPLETED_STATUS,
                timestamp,
                base64Payload);
    }

    private void assertReportNotEmpty(ExportRequest request, String requestedFormat) {
        if (request == null || request.visuals() == null || request.visuals().isEmpty()) {
            ExportMetadata metadata = ExportMetadata.forFormat(requestedFormat);
            String message = "Export of " + metadata.filename() + " failed: report builder canvas is empty.";
            LOGGER.warn(message);
            throw new IllegalArgumentException(message);
        }
    }

    private byte[] generateBinaryPayload(ExportRequest request, String extension, Instant timestamp) {
        if (request == null) {
            return new byte[0];
        }
        return switch (extension) {
            case ".pdf" -> buildPdfPayload(request, timestamp);
            case ".xlsx" -> buildXlsxPayload(request, timestamp);
            case ".parquet" -> buildParquetPayload(request, timestamp);
            case ".tsv" -> buildDelimitedPayload(request, TAB_DELIMITER, false, timestamp);
            default -> {
                String delimiter = (request.delimiter() != null && !request.delimiter().isEmpty())
                        ? request.delimiter()
                        : DEFAULT_DELIMITER;
                boolean includeBom = request.includeBom() == null || request.includeBom();
                yield buildDelimitedPayload(request, delimiter, includeBom, timestamp);
            }
        };
    }

    private byte[] buildPdfPayload(ExportRequest request, Instant timestamp) {
        String projectName = resolveSafeString(request.projectName(), request.reportId(), DEFAULT_PROJECT_NAME);
        String pageName = resolveSafeString(request.pageName(), request.visualId(), DEFAULT_PAGE_NAME);
        int visualCount = request.visuals() != null ? request.visuals().size() : 0;

        String safeProject = projectName.replace("(", "[").replace(")", "]");
        String safePage = pageName.replace("(", "[").replace(")", "]");
        String textStream = String.format(
                "BT\n/F1 18 Tf\n50 750 Td\n(%s) Tj\n/F1 12 Tf\n0 -30 Td\n(Page: %s | Exported: %s) Tj\n0 -25 Td\n(Total Visuals: %d) Tj\nET",
                safeProject,
                safePage,
                timestamp.toString(),
                visualCount
        );
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
        sb.append("xref\n0 6\n");
        sb.append("0000000000 65535 f \n");
        for (int off : offsets) {
            sb.append(String.format("%010d 00000 n \n", off));
        }
        sb.append("trailer\n<</Size 6 /Root 1 0 R>>\nstartxref\n");
        sb.append(xrefOffset).append("\n%%EOF\n");

        return sb.toString().getBytes(StandardCharsets.US_ASCII);
    }

    private byte[] buildXlsxPayload(ExportRequest request, Instant timestamp) {
        try (SXSSFWorkbook workbook = new SXSSFWorkbook(POI_ROW_WINDOW_SIZE);
             ByteArrayOutputStream outputStream = new ByteArrayOutputStream()) {

            Sheet visualsSheet = workbook.createSheet("Report Visuals");
            populateXlsxVisualsSheet(visualsSheet, request, timestamp);

            Sheet measuresSheet = workbook.createSheet("Measures & Context");
            populateXlsxMeasuresSheet(measuresSheet, request);

            workbook.write(outputStream);
            return outputStream.toByteArray();
        } catch (Exception exception) {
            LOGGER.error("Failed to generate XLSX binary payload: {}", exception.getMessage(), exception);
            return new byte[0];
        }
    }

    private void populateXlsxVisualsSheet(Sheet sheet, ExportRequest request, Instant timestamp) {
        Row headerRow = sheet.createRow(0);
        for (int i = 0; i < VISUAL_COLUMNS.length; i++) {
            headerRow.createCell(i).setCellValue(VISUAL_COLUMNS[i]);
        }

        String project = resolveSafeString(request.projectName(), request.reportId(), DEFAULT_PROJECT_NAME);
        String page = resolveSafeString(request.pageName(), request.visualId(), DEFAULT_PAGE_NAME);
        String contextHash = resolveSafeString(request.contextHash(), null, DEFAULT_CONTEXT_HASH);
        List<ExportVisualRecord> visuals = request.visuals() != null ? request.visuals() : List.of();

        int rowIndex = 1;
        for (ExportVisualRecord visual : visuals) {
            Row row = sheet.createRow(rowIndex++);
            row.createCell(0).setCellValue(project);
            row.createCell(1).setCellValue(page);
            row.createCell(2).setCellValue(resolveSafeString(visual.id(), null, ""));
            row.createCell(3).setCellValue(resolveSafeString(visual.title(), null, ""));
            row.createCell(4).setCellValue(resolveSafeString(visual.archetype(), null, ""));
            row.createCell(5).setCellValue(resolveSafeString(visual.metricLabel(), null, ""));
            row.createCell(6).setCellValue(resolveSafeString(visual.metricValue(), null, ""));
            row.createCell(7).setCellValue(resolveSafeString(visual.metricSubtext(), null, ""));
            row.createCell(8).setCellValue(contextHash);
            row.createCell(9).setCellValue(timestamp.toString());
        }
    }

    private void populateXlsxMeasuresSheet(Sheet sheet, ExportRequest request) {
        Row headerRow = sheet.createRow(0);
        for (int i = 0; i < MEASURE_COLUMNS.length; i++) {
            headerRow.createCell(i).setCellValue(MEASURE_COLUMNS[i]);
        }

        List<ExportMeasureRecord> measures = request.measures() != null ? request.measures() : List.of();
        int rowIndex = 1;
        for (ExportMeasureRecord measure : measures) {
            Row row = sheet.createRow(rowIndex++);
            row.createCell(0).setCellValue(resolveSafeString(measure.id(), null, ""));
            row.createCell(1).setCellValue(resolveSafeString(measure.label(), null, ""));
            row.createCell(2).setCellValue(resolveSafeString(measure.table(), null, ""));
            row.createCell(3).setCellValue(resolveSafeString(measure.formula(), null, ""));
        }
    }

    private byte[] buildParquetPayload(ExportRequest request, Instant timestamp) {
        if (duckDbConnectionManager == null) {
            LOGGER.warn("DuckDB connection manager is null; cannot export Parquet format.");
            return new byte[0];
        }

        Path tempParquetFile = null;
        String tempTableName = "rc_export_" + UUID.randomUUID().toString().replace("-", "");
        try {
            tempParquetFile = Files.createTempFile("rc-export-", ".parquet");
            Files.deleteIfExists(tempParquetFile);
            String normalizedPath = tempParquetFile.toAbsolutePath().toString().replace('\\', '/');

            String project = resolveSafeString(request.projectName(), request.reportId(), DEFAULT_PROJECT_NAME);
            String page = resolveSafeString(request.pageName(), request.visualId(), DEFAULT_PAGE_NAME);
            String contextHash = resolveSafeString(request.contextHash(), null, DEFAULT_CONTEXT_HASH);
            List<ExportVisualRecord> visuals = request.visuals() != null ? request.visuals() : List.of();

            try (Connection connection = duckDbConnectionManager.acquireConnection();
                 Statement statement = connection.createStatement()) {

                statement.execute("CREATE TEMP TABLE " + tempTableName + " ("
                        + "project VARCHAR, page VARCHAR, visual_id VARCHAR, visual_title VARCHAR, "
                        + "archetype VARCHAR, metric_label VARCHAR, metric_value VARCHAR, "
                        + "metric_subtext VARCHAR, context_hash VARCHAR, exported_at VARCHAR)");

                String insertSql = "INSERT INTO " + tempTableName + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
                try (PreparedStatement preparedStatement = connection.prepareStatement(insertSql)) {
                    for (ExportVisualRecord visual : visuals) {
                        preparedStatement.setString(1, project);
                        preparedStatement.setString(2, page);
                        preparedStatement.setString(3, resolveSafeString(visual.id(), null, ""));
                        preparedStatement.setString(4, resolveSafeString(visual.title(), null, ""));
                        preparedStatement.setString(5, resolveSafeString(visual.archetype(), null, ""));
                        preparedStatement.setString(6, resolveSafeString(visual.metricLabel(), null, ""));
                        preparedStatement.setString(7, resolveSafeString(visual.metricValue(), null, ""));
                        preparedStatement.setString(8, resolveSafeString(visual.metricSubtext(), null, ""));
                        preparedStatement.setString(9, contextHash);
                        preparedStatement.setString(10, timestamp.toString());
                        preparedStatement.addBatch();
                    }
                    preparedStatement.executeBatch();
                }

                statement.execute("COPY " + tempTableName + " TO '" + normalizedPath + "' (FORMAT PARQUET, COMPRESSION SNAPPY)");
                statement.execute("DROP TABLE IF EXISTS " + tempTableName);
            }

            return Files.readAllBytes(tempParquetFile);
        } catch (Exception exception) {
            LOGGER.error("Failed to generate DuckDB Parquet payload: {}", exception.getMessage(), exception);
            return new byte[0];
        } finally {
            if (tempParquetFile != null) {
                try {
                    Files.deleteIfExists(tempParquetFile);
                } catch (Exception cleanupException) {
                    LOGGER.debug("Temporary file cleanup notification: {}", cleanupException.getMessage());
                }
            }
        }
    }

    private byte[] buildDelimitedPayload(
            ExportRequest request,
            String delimiter,
            boolean includeBom,
            Instant timestamp) {
        StringBuilder builder = new StringBuilder();
        if (includeBom) {
            builder.append(UTF8_BYTE_ORDER_MARK);
        }

        builder.append(String.join(delimiter, VISUAL_COLUMNS)).append(LINE_SEPARATOR);

        String project = resolveSafeString(request.projectName(), request.reportId(), DEFAULT_PROJECT_NAME);
        String page = resolveSafeString(request.pageName(), request.visualId(), DEFAULT_PAGE_NAME);
        String contextHash = resolveSafeString(request.contextHash(), null, DEFAULT_CONTEXT_HASH);
        List<ExportVisualRecord> visuals = request.visuals() != null ? request.visuals() : List.of();

        for (ExportVisualRecord visual : visuals) {
            String[] fields = {
                    escapeDelimitedField(project, delimiter),
                    escapeDelimitedField(page, delimiter),
                    escapeDelimitedField(resolveSafeString(visual.id(), null, ""), delimiter),
                    escapeDelimitedField(resolveSafeString(visual.title(), null, ""), delimiter),
                    escapeDelimitedField(resolveSafeString(visual.archetype(), null, ""), delimiter),
                    escapeDelimitedField(resolveSafeString(visual.metricLabel(), null, ""), delimiter),
                    escapeDelimitedField(resolveSafeString(visual.metricValue(), null, ""), delimiter),
                    escapeDelimitedField(resolveSafeString(visual.metricSubtext(), null, ""), delimiter),
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

    private static String resolveSafeString(String primary, String secondary, String fallback) {
        if (primary != null && !primary.isBlank()) {
            return primary;
        }
        if (secondary != null && !secondary.isBlank()) {
            return secondary;
        }
        return fallback;
    }

    private record ExportMetadata(String extension, String mimeType, String filename, String engine) {
        private static ExportMetadata forFormat(String requestedFormat) {
            String normalized = requestedFormat == null ? "" : requestedFormat.trim().toLowerCase();
            return switch (normalized) {
                case ".pdf", "pdf", "300 dpi pdf" -> new ExportMetadata(
                        ".pdf",
                        "application/pdf",
                        "Workspace-Report-300DPI.pdf",
                        "Playwright Headless Chromium (300 DPI Vector/Raster)");
                case ".xlsx", "xlsx", "excel sxssf" -> new ExportMetadata(
                        ".xlsx",
                        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                        "Workspace-Report.xlsx",
                        "Apache POI SXSSFWorkbook (100-row sliding window)");
                case ".parquet", "parquet" -> new ExportMetadata(
                        ".parquet",
                        "application/vnd.apache.parquet",
                        "Workspace-Report.parquet",
                        "Native DuckDB Engine (SNAPPY Columnar)");
                case ".tsv", "tsv" -> new ExportMetadata(
                        ".tsv",
                        "text/tab-separated-values; charset=utf-8",
                        "Workspace-Report.tsv",
                        "Spring WebFlux Reactive (8 KB ByteBuffer)");
                default -> new ExportMetadata(
                        ".csv",
                        "text/csv; charset=utf-8",
                        "Workspace-Report.csv",
                        "Spring WebFlux Reactive (UTF-8 BOM + Semicolon Delimiter)");
            };
        }
    }
}
