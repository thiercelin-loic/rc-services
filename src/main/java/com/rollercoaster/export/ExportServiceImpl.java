package com.rollercoaster.export;

import com.rollercoaster.export.ExportDtos.ExportJobReceipt;
import com.rollercoaster.export.ExportDtos.ExportRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Base64;
import java.util.UUID;

/**
 * Coordinates report export receipt generation across PDF, XLSX, CSV,
 * and TSV formats.
 */
@Service
public class ExportServiceImpl implements ExportService {

    private static final Logger LOGGER = LoggerFactory.getLogger(ExportServiceImpl.class);

    private static final String DEFAULT_PROJECT_NAME = "Untitled Project.rcp";
    private static final String DEFAULT_PAGE_NAME = "Page 1";
    private static final String DEFAULT_CONTEXT_HASH = "00000000";
    private static final String DEFAULT_DELIMITER = ";";
    private static final String TAB_DELIMITER = "\t";
    private static final String COMPLETED_STATUS = "COMPLETED";

    private final XlsxExportBuilder xlsxExportBuilder;
    private final TabularExportFormatter tabularExportFormatter;

    public ExportServiceImpl(
            XlsxExportBuilder xlsxExportBuilder,
            TabularExportFormatter tabularExportFormatter) {
        this.xlsxExportBuilder = xlsxExportBuilder;
        this.tabularExportFormatter = tabularExportFormatter;
    }

    @Override
    public ExportJobReceipt exportReport(String reportId, ExportRequest request) {
        ExportRequest effectiveRequest = ExportRequest.withReportContext(reportId, request);
        LOGGER.info("Exporting report '{}' with format '{}'",
                reportId, effectiveRequest.format());
        return generateReceipt(effectiveRequest, effectiveRequest.format());
    }

    private ExportJobReceipt generateReceipt(ExportRequest request, String requestedFormat) {
        assertReportNotEmpty(request, requestedFormat);
        ExportMetadata metadata = ExportMetadata.forFormat(requestedFormat, request.projectName());
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
            ExportMetadata metadata = ExportMetadata.forFormat(
                    requestedFormat, request != null ? request.projectName() : null);
            String message = "Export of " + metadata.filename() + " failed: report builder canvas is empty.";
            LOGGER.warn(message);
            throw new IllegalArgumentException(message);
        }
    }

    private byte[] generateBinaryPayload(ExportRequest request, String extension, Instant timestamp) {
        String project = resolveSafeString(request.projectName(), request.reportId(), DEFAULT_PROJECT_NAME);
        String page = resolveSafeString(request.pageName(), request.visualId(), DEFAULT_PAGE_NAME);
        String contextHash = resolveSafeString(request.contextHash(), null, DEFAULT_CONTEXT_HASH);

        return switch (extension) {
            case ".pdf" -> tabularExportFormatter.buildPdf(request, project, page, timestamp);
            case ".xlsx" -> xlsxExportBuilder.build(request, project, page, contextHash, timestamp);
            case ".tsv" -> tabularExportFormatter.buildDelimited(
                    request, TAB_DELIMITER, false, project, page, contextHash, timestamp);
            default -> {
                String delimiter = (request.delimiter() != null && !request.delimiter().isEmpty())
                        ? request.delimiter()
                        : DEFAULT_DELIMITER;
                boolean includeBom = request.includeBom() == null || request.includeBom();
                yield tabularExportFormatter.buildDelimited(
                        request, delimiter, includeBom, project, page, contextHash, timestamp);
            }
        };
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
        private static ExportMetadata forFormat(String requestedFormat, String rawProjectName) {
            String normalized = requestedFormat == null ? "" : requestedFormat.trim().toLowerCase();
            String baseProject = (rawProjectName != null && !rawProjectName.isBlank())
                    ? rawProjectName.trim().replaceAll("(?i)\\.rcp$", "").trim()
                    : "";
            return switch (normalized) {
                case ".pdf", "pdf", "300 dpi pdf" -> new ExportMetadata(
                        ".pdf",
                        "application/pdf",
                        baseProject.isEmpty() ? "Workspace-Report-300DPI.pdf" : baseProject + ".pdf",
                        "Playwright Headless Chromium (300 DPI Vector/Raster)");
                case ".xlsx", "xlsx", "excel sxssf" -> new ExportMetadata(
                        ".xlsx",
                        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                        baseProject.isEmpty() ? "Workspace-Report.xlsx" : baseProject + ".xlsx",
                        "Apache POI SXSSFWorkbook (100-row sliding window)");
                case ".tsv", "tsv" -> new ExportMetadata(
                        ".tsv",
                        "text/tab-separated-values; charset=utf-8",
                        baseProject.isEmpty() ? "Workspace-Report.tsv" : baseProject + ".tsv",
                        "Spring WebFlux Reactive (8 KB ByteBuffer)");
                default -> new ExportMetadata(
                        ".csv",
                        "text/csv; charset=utf-8",
                        baseProject.isEmpty() ? "Workspace-Report.csv" : baseProject + ".csv",
                        "Spring WebFlux Reactive (UTF-8 BOM + Semicolon Delimiter)");
            };
        }
    }
}
