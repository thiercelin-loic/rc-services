package com.rollercoaster.export;

import com.rollercoaster.export.ExportDtos.ExportChartData;
import com.rollercoaster.export.ExportDtos.ExportChartDataset;
import com.rollercoaster.export.ExportDtos.ExportMeasureRecord;
import com.rollercoaster.export.ExportDtos.ExportRequest;
import com.rollercoaster.export.ExportDtos.ExportVisualRecord;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.streaming.SXSSFWorkbook;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.Instant;
import java.util.List;

/**
 * Builds streaming OpenXML (.xlsx) workbooks for report visuals, chart series, and RAX measures.
 */
@Component
public class XlsxExportBuilder {

    private static final Logger LOGGER = LoggerFactory.getLogger(XlsxExportBuilder.class);
    private static final int POI_ROW_WINDOW_SIZE = 100;
    private static final int MAX_EXCEL_SHEET_NAME_LENGTH = 31;

    static final String[] VISUAL_COLUMNS = {
            "Project", "Page", "Visual ID", "Visual Title", "Archetype",
            "Metric Label", "Metric Value", "Metric Subtext", "Context Hash", "Exported At"
    };

    private static final String[] MEASURE_COLUMNS = {
            "Measure ID", "Measure Name", "Table", "RAX Formula"
    };

    public byte[] build(
            ExportRequest request,
            String project,
            String page,
            String contextHash,
            Instant timestamp) {
        try (SXSSFWorkbook workbook = new SXSSFWorkbook(POI_ROW_WINDOW_SIZE);
             ByteArrayOutputStream outputStream = new ByteArrayOutputStream()) {

            populateChartSheets(workbook, request.visuals());
            populateVisualsSheet(workbook.createSheet("Report Visuals"), request, project, page, contextHash, timestamp);
            populateMeasuresSheet(workbook.createSheet("Measures & Context"), request.measures());

            workbook.write(outputStream);
            return outputStream.toByteArray();
        } catch (IOException ex) {
            LOGGER.error("Failed to generate XLSX binary payload: {}", ex.getMessage(), ex);
            return new byte[0];
        }
    }

    private void populateChartSheets(SXSSFWorkbook workbook, List<ExportVisualRecord> visuals) {
        if (visuals == null) {
            return;
        }
        int visualIdx = 1;
        for (ExportVisualRecord visual : visuals) {
            ExportChartData chart = visual.chartData();
            if (chart != null && chart.datasets() != null && !chart.datasets().isEmpty()) {
                Sheet chartSheet = workbook.createSheet(formatSheetTab(visualIdx, visual.title()));
                populateChartSheet(chartSheet, chart);
            }
            visualIdx++;
        }
    }

    private void populateChartSheet(Sheet sheet, ExportChartData chart) {
        Row headerRow = sheet.createRow(0);
        headerRow.createCell(0).setCellValue("Category");
        List<ExportChartDataset> datasets = chart.datasets();
        for (int i = 0; i < datasets.size(); i++) {
            String label = datasets.get(i).label();
            headerRow.createCell(i + 1).setCellValue((label != null && !label.isBlank()) ? label : "Value " + (i + 1));
        }

        List<String> labels = chart.labels() != null ? chart.labels() : List.of();
        for (int r = 0; r < labels.size(); r++) {
            Row row = sheet.createRow(r + 1);
            row.createCell(0).setCellValue(labels.get(r) != null ? labels.get(r) : "");
            populateChartRowValues(row, datasets, r);
        }
    }

    private void populateChartRowValues(Row row, List<ExportChartDataset> datasets, int rowIndex) {
        for (int c = 0; c < datasets.size(); c++) {
            List<Double> values = datasets.get(c).data();
            Double val = (values != null && rowIndex < values.size()) ? values.get(rowIndex) : null;
            if (val != null && Double.isFinite(val)) {
                row.createCell(c + 1).setCellValue(val);
            } else {
                row.createCell(c + 1).setCellValue("");
            }
        }
    }

    private void populateVisualsSheet(
            Sheet sheet,
            ExportRequest request,
            String project,
            String page,
            String contextHash,
            Instant timestamp) {
        writeHeaderRow(sheet, VISUAL_COLUMNS);
        List<ExportVisualRecord> visuals = request.visuals() != null ? request.visuals() : List.of();

        int rowIndex = 1;
        for (ExportVisualRecord visual : visuals) {
            Row row = sheet.createRow(rowIndex++);
            row.createCell(0).setCellValue(project);
            row.createCell(1).setCellValue(page);
            row.createCell(2).setCellValue(safeValue(visual.id()));
            row.createCell(3).setCellValue(safeValue(visual.title()));
            row.createCell(4).setCellValue(safeValue(visual.archetype()));
            row.createCell(5).setCellValue(safeValue(visual.metricLabel()));
            row.createCell(6).setCellValue(safeValue(visual.metricValue()));
            row.createCell(7).setCellValue(safeValue(visual.metricSubtext()));
            row.createCell(8).setCellValue(contextHash);
            row.createCell(9).setCellValue(timestamp.toString());
        }
    }

    private void populateMeasuresSheet(Sheet sheet, List<ExportMeasureRecord> measures) {
        writeHeaderRow(sheet, MEASURE_COLUMNS);
        if (measures == null) {
            return;
        }
        int rowIndex = 1;
        for (ExportMeasureRecord measure : measures) {
            Row row = sheet.createRow(rowIndex++);
            row.createCell(0).setCellValue(safeValue(measure.id()));
            row.createCell(1).setCellValue(safeValue(measure.label()));
            row.createCell(2).setCellValue(safeValue(measure.table()));
            row.createCell(3).setCellValue(safeValue(measure.formula()));
        }
    }

    private static void writeHeaderRow(Sheet sheet, String[] columns) {
        Row headerRow = sheet.createRow(0);
        for (int i = 0; i < columns.length; i++) {
            headerRow.createCell(i).setCellValue(columns[i]);
        }
    }

    private static String formatSheetTab(int index, String title) {
        String cleaned = (title != null && !title.isBlank() ? title : "Visual " + index)
                .replaceAll("[:\\\\/?*\\[\\]]", " ")
                .replaceAll("\\s+", " ")
                .trim();
        String candidate = index + " - " + cleaned;
        return candidate.length() > MAX_EXCEL_SHEET_NAME_LENGTH
                ? candidate.substring(0, MAX_EXCEL_SHEET_NAME_LENGTH).trim()
                : candidate;
    }

    private static String safeValue(String value) {
        return value != null ? value : "";
    }
}
