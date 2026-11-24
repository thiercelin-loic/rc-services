package com.rollercoaster.ingestion;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rollercoaster.ingestion.IngestionDtos.SheetColumnDto;
import com.rollercoaster.ingestion.IngestionDtos.SheetExtractionRequest;
import com.rollercoaster.ingestion.IngestionDtos.SheetExtractionResponse;
import com.rollercoaster.ingestion.IngestionDtos.WorkbookSheetDto;
import com.rollercoaster.ingestion.parser.DelimitedFileParser;
import com.rollercoaster.ingestion.parser.TypeInferenceEngine;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.DateUtil;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.parser.Parser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Extracts previewable tabular sheets from XLSX, OpenXML ZIP, JSON, XML, CSV, and TSV payloads.
 */
@Service
public class SheetExtractionService {

    private static final Logger LOGGER = LoggerFactory.getLogger(SheetExtractionService.class);
    private static final int MAX_PREVIEW_RECORDS = 500;

    private final DelimitedFileParser delimitedFileParser;
    private final TypeInferenceEngine typeInferenceEngine = new TypeInferenceEngine();
    private final ObjectMapper objectMapper = new ObjectMapper();

    public SheetExtractionService(DelimitedFileParser delimitedFileParser) {
        this.delimitedFileParser = delimitedFileParser;
    }

    public SheetExtractionResponse extractSheets(SheetExtractionRequest request) {
        if (request == null) {
            return new SheetExtractionResponse("Dataset", List.of(), "Ingestion request payload is required.");
        }
        String fileName = (request.fileName() != null && !request.fileName().isBlank())
                ? request.fileName()
                : "Dataset";
        String baseTitle = sanitizeTableName(fileName);
        SourceFormat format = request.format() != null ? request.format() : detectFormat(fileName);
        String text = request.rawPayload() != null ? request.rawPayload() : "";

        try {
            SheetExtractionResponse result = switch (format) {
                case XLSX -> extractXlsxSheets(baseTitle, text, request.rawPayloadBase64());
                case JSON -> extractJsonSheets(baseTitle, text);
                case XML -> extractXmlSheets(baseTitle, text);
                case CSV, TSV -> extractDelimitedSheets(
                        baseTitle, text, format == SourceFormat.TSV ? '\t' : DataIngestionService.detectDelimiter(text));
                default -> extractDelimitedSheets(baseTitle, text, DataIngestionService.detectDelimiter(text));
            };
            return new SheetExtractionResponse(baseTitle, result.sheets(), result.error());
        } catch (IllegalArgumentException ex) {
            LOGGER.warn("Invalid ingestion sheet request for '{}': {}", fileName, ex.getMessage());
            return new SheetExtractionResponse(baseTitle, List.of(), ex.getMessage());
        }
    }

    public static String sanitizeTableName(String fileName) {
        if (fileName == null || fileName.isBlank()) {
            return "Dataset";
        }
        String baseName = fileName.replace('\\', '/');
        int slashIdx = baseName.lastIndexOf('/');
        if (slashIdx >= 0 && slashIdx < baseName.length() - 1) {
            baseName = baseName.substring(slashIdx + 1);
        }
        String stripped = baseName.replaceAll("\\.[^/.]+$", "")
                .replaceAll("[^\\p{L}\\p{N}]+", " ")
                .trim();
        return stripped.isEmpty() ? "Dataset" : stripped;
    }

    private SourceFormat detectFormat(String fileName) {
        return SourceFormat.fromFileName(fileName).orElse(SourceFormat.CSV);
    }

    private SheetExtractionResponse extractXlsxSheets(String baseTitle, String text, String base64) {
        String effectiveText = text;
        if (base64 != null && !base64.isBlank()) {
            byte[] bytes = Base64.getDecoder().decode(base64);
            if (isZipHeader(bytes)) {
                List<WorkbookSheetDto> poiSheets = tryExtractPoiWorkbook(bytes);
                if (!poiSheets.isEmpty()) {
                    return new SheetExtractionResponse(baseTitle, poiSheets, null);
                }
                List<WorkbookSheetDto> xmlZipSheets = tryExtractOpenXmlZip(bytes);
                if (!xmlZipSheets.isEmpty()) {
                    return new SheetExtractionResponse(baseTitle, xmlZipSheets, null);
                }
            }
            if (effectiveText.isBlank()) {
                effectiveText = new String(bytes, StandardCharsets.UTF_8);
            }
        }
        String trimmed = effectiveText.trim();
        if (trimmed.startsWith("<")) {
            return extractXmlSheets(baseTitle, trimmed);
        }
        return extractDelimitedSheets(baseTitle, trimmed, DataIngestionService.detectDelimiter(trimmed));
    }

    private static boolean isZipHeader(byte[] bytes) {
        return bytes.length >= 2 && bytes[0] == 0x50 && bytes[1] == 0x4B;
    }

    private List<WorkbookSheetDto> tryExtractPoiWorkbook(byte[] bytes) {
        List<WorkbookSheetDto> sheets = new ArrayList<>();
        try (Workbook workbook = new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
            for (int s = 0; s < workbook.getNumberOfSheets(); s++) {
                WorkbookSheetDto dto = extractPoiSheet(workbook.getSheetAt(s), s + 1);
                if (dto != null) {
                    sheets.add(dto);
                }
            }
        } catch (IOException | RuntimeException ex) {
            LOGGER.debug("POI workbook extraction skipped, falling back to OpenXML ZIP: {}", ex.getMessage());
        }
        return sheets;
    }

    private WorkbookSheetDto extractPoiSheet(Sheet sheet, int sheetNumber) {
        String sheetName = sheet.getSheetName();
        List<String> headers = new ArrayList<>();
        List<Map<String, Object>> rows = new ArrayList<>();

        for (Row row : sheet) {
            if (headers.isEmpty()) {
                parsePoiHeaderRow(row, headers);
                continue;
            }
            Map<String, Object> rowMap = parsePoiDataRow(row, headers);
            if (rowMap != null) {
                rows.add(rowMap);
            }
        }
        if (headers.isEmpty()) {
            return null;
        }
        return buildUnpromotedSheet("sheet_" + sheetNumber, sheetName, "Sheet", sheetName, headers, rows);
    }

    private void parsePoiHeaderRow(Row row, List<String> headers) {
        int lastCol = Math.max(row.getLastCellNum(), (short) 0);
        for (int c = 0; c < lastCol; c++) {
            Cell cell = row.getCell(c, Row.MissingCellPolicy.RETURN_BLANK_AS_NULL);
            Object val = extractPoiCellValue(cell);
            String header = (val != null && !val.toString().isBlank()) ? val.toString().trim() : "Column" + (c + 1);
            headers.add(header);
        }
    }

    private Map<String, Object> parsePoiDataRow(Row row, List<String> headers) {
        Map<String, Object> rowMap = new LinkedHashMap<>();
        boolean hasValue = false;
        for (int c = 0; c < headers.size(); c++) {
            Cell cell = row.getCell(c, Row.MissingCellPolicy.RETURN_BLANK_AS_NULL);
            Object val = extractPoiCellValue(cell);
            if (val != null && !val.toString().isBlank()) {
                hasValue = true;
            }
            rowMap.put(headers.get(c), val != null ? val : "");
        }
        return hasValue ? rowMap : null;
    }

    private List<WorkbookSheetDto> tryExtractOpenXmlZip(byte[] bytes) {
        List<WorkbookSheetDto> sheets = new ArrayList<>();
        try {
            Map<String, String> xmlFiles = readZipXmlEntries(bytes);
            List<String> sharedStrings = parseSharedStrings(xmlFiles.get("xl/sharedStrings.xml"));
            Map<String, String> relTargets = parseWorkbookRelations(xmlFiles.get("xl/_rels/workbook.xml.rels"));
            String wbXml = xmlFiles.get("xl/workbook.xml");
            if (wbXml == null) {
                return sheets;
            }
            Document wbDoc = Jsoup.parse(wbXml, "", Parser.xmlParser());
            int idx = 1;
            for (Element sheetEl : wbDoc.select("sheet")) {
                String name = sheetEl.attr("name");
                String target = relTargets.getOrDefault(sheetEl.attr("r:id"), "worksheets/sheet" + idx + ".xml");
                String fullPath = target.startsWith("xl/") ? target : "xl/" + target.replaceFirst("^/+", "");
                String sheetXml = xmlFiles.get(fullPath);
                if (sheetXml != null) {
                    WorkbookSheetDto dto = parseOpenXmlWorksheet("sheet_" + idx, name, sheetXml, sharedStrings);
                    if (dto != null) {
                        sheets.add(dto);
                    }
                }
                idx++;
            }
        } catch (IOException | RuntimeException ex) {
            LOGGER.debug("OpenXML ZIP extraction failed: {}", ex.getMessage());
        }
        return sheets;
    }

    private Map<String, String> readZipXmlEntries(byte[] bytes) throws IOException {
        Map<String, String> xmlFiles = new LinkedHashMap<>();
        try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(bytes))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                if (!entry.isDirectory()) {
                    xmlFiles.put(entry.getName(), new String(zis.readAllBytes(), StandardCharsets.UTF_8));
                }
                zis.closeEntry();
            }
        }
        return xmlFiles;
    }

    private List<String> parseSharedStrings(String sstXml) {
        List<String> sharedStrings = new ArrayList<>();
        if (sstXml == null) {
            return sharedStrings;
        }
        Document sstDoc = Jsoup.parse(sstXml, "", Parser.xmlParser());
        for (Element si : sstDoc.select("si")) {
            sharedStrings.add(si.text());
        }
        return sharedStrings;
    }

    private Map<String, String> parseWorkbookRelations(String relsXml) {
        Map<String, String> relTargets = new LinkedHashMap<>();
        if (relsXml == null) {
            return relTargets;
        }
        Document relsDoc = Jsoup.parse(relsXml, "", Parser.xmlParser());
        for (Element rel : relsDoc.select("Relationship")) {
            relTargets.put(rel.attr("Id"), rel.attr("Target"));
        }
        return relTargets;
    }

    private WorkbookSheetDto parseOpenXmlWorksheet(
            String id,
            String sheetName,
            String sheetXml,
            List<String> sharedStrings) {
        Document doc = Jsoup.parse(sheetXml, "", Parser.xmlParser());
        List<List<Object>> grid = new ArrayList<>();
        int maxCols = 0;
        for (Element rowEl : doc.select("row")) {
            List<Object> rowVals = parseOpenXmlRow(rowEl, sharedStrings);
            maxCols = Math.max(maxCols, rowVals.size());
            grid.add(rowVals);
        }
        if (grid.isEmpty() || maxCols == 0) {
            return null;
        }
        List<SheetColumnDto> cols = new ArrayList<>(maxCols);
        for (int c = 1; c <= maxCols; c++) {
            cols.add(new SheetColumnDto("Column" + c, "String", "String"));
        }
        List<Map<String, Object>> rows = new ArrayList<>(grid.size());
        for (List<Object> rowVals : grid) {
            Map<String, Object> rowMap = new LinkedHashMap<>();
            for (int c = 0; c < maxCols; c++) {
                rowMap.put("Column" + (c + 1), c < rowVals.size() ? rowVals.get(c) : null);
            }
            rows.add(rowMap);
        }
        return new WorkbookSheetDto(id, sheetName, "Sheet", sheetName, cols, rows);
    }

    private List<Object> parseOpenXmlRow(Element rowEl, List<String> sharedStrings) {
        List<Object> rowVals = new ArrayList<>();
        for (Element cellEl : rowEl.select("c")) {
            String type = cellEl.attr("t");
            String vText = cellEl.selectFirst("v") != null ? cellEl.selectFirst("v").text() : cellEl.text();
            if ("s".equals(type)) {
                int sIdx = Integer.parseInt(vText.trim());
                rowVals.add(sIdx >= 0 && sIdx < sharedStrings.size() ? sharedStrings.get(sIdx) : vText);
            } else {
                rowVals.add(coerceScalar(vText));
            }
        }
        return rowVals;
    }

    private Object extractPoiCellValue(Cell cell) {
        if (cell == null) {
            return "";
        }
        return switch (cell.getCellType()) {
            case STRING -> cell.getStringCellValue();
            case NUMERIC -> {
                if (DateUtil.isCellDateFormatted(cell)) {
                    yield cell.getLocalDateTimeCellValue().toLocalDate().toString();
                }
                double d = cell.getNumericCellValue();
                yield d == Math.rint(d) ? (long) d : d;
            }
            case BOOLEAN -> cell.getBooleanCellValue();
            case FORMULA -> cell.getCellFormula();
            default -> "";
        };
    }

    private SheetExtractionResponse extractJsonSheets(String baseTitle, String text) {
        String trimmed = text != null ? text.replaceFirst("^\uFEFF", "").trim() : "";
        if (trimmed.isEmpty()) {
            return new SheetExtractionResponse(List.of(), "File is empty.");
        }
        try {
            JsonNode root = objectMapper.readTree(trimmed);
            if (root == null || root.isNull()) {
                return new SheetExtractionResponse(List.of(), "JSON root must be an array or object.");
            }
            List<WorkbookSheetDto> sheets = collectJsonSheets(baseTitle, root);
            if (sheets.isEmpty()) {
                return new SheetExtractionResponse(List.of(), "No tabular records found in JSON file.");
            }
            return new SheetExtractionResponse(sheets, null);
        } catch (JsonProcessingException ex) {
            return new SheetExtractionResponse(List.of(), "Invalid JSON syntax: " + ex.getMessage());
        }
    }

    private List<WorkbookSheetDto> collectJsonSheets(String baseTitle, JsonNode root) {
        List<WorkbookSheetDto> sheets = new ArrayList<>();
        if (root.isArray()) {
            List<Map<String, Object>> rows = extractJsonObjectRows(root);
            if (!rows.isEmpty()) {
                sheets.add(buildUnpromotedSheetFromRows("table_1", baseTitle, "Table", baseTitle, rows));
            }
            return sheets;
        }
        if (!root.isObject()) {
            return sheets;
        }
        int idx = 1;
        Iterator<Map.Entry<String, JsonNode>> fields = root.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> entry = fields.next();
            if (entry.getValue().isArray() && !entry.getValue().isEmpty()) {
                List<Map<String, Object>> rows = extractJsonObjectRows(entry.getValue());
                if (!rows.isEmpty()) {
                    sheets.add(buildUnpromotedSheetFromRows("table_" + idx++, entry.getKey(), "Table", entry.getKey(), rows));
                }
            }
        }
        if (sheets.isEmpty()) {
            List<Map<String, Object>> rows = expandJsonItem(root);
            if (!rows.isEmpty()) {
                sheets.add(buildUnpromotedSheetFromRows("table_1", baseTitle, "Table", baseTitle, rows));
            }
        }
        return sheets;
    }

    private List<Map<String, Object>> extractJsonObjectRows(JsonNode arrayNode) {
        List<Map<String, Object>> rows = new ArrayList<>();
        int limit = Math.min(arrayNode.size(), MAX_PREVIEW_RECORDS);
        for (int i = 0; i < limit; i++) {
            JsonNode item = arrayNode.get(i);
            if (item != null && item.isObject()) {
                rows.addAll(expandJsonItem(item));
            }
        }
        return rows;
    }

    private List<Map<String, Object>> expandJsonItem(JsonNode objNode) {
        Map<String, Object> scalarFields = new LinkedHashMap<>();
        List<JsonNode> childObjectArrays = new ArrayList<>();
        flattenJsonFields("", objNode, scalarFields, childObjectArrays);
        if (childObjectArrays.isEmpty()) {
            return scalarFields.isEmpty() ? List.of() : List.of(scalarFields);
        }
        List<Map<String, Object>> expanded = new ArrayList<>();
        for (JsonNode childArr : childObjectArrays) {
            for (JsonNode childItem : childArr) {
                if (childItem.isObject()) {
                    Map<String, Object> combined = new LinkedHashMap<>(scalarFields);
                    flattenJsonFields("", childItem, combined, new ArrayList<>());
                    expanded.add(combined);
                }
            }
        }
        return expanded.isEmpty() ? List.of(scalarFields) : expanded;
    }

    private void flattenJsonFields(
            String prefix,
            JsonNode objNode,
            Map<String, Object> out,
            List<JsonNode> childObjectArrays) {
        Iterator<Map.Entry<String, JsonNode>> fields = objNode.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> entry = fields.next();
            String key = prefix.isEmpty() ? entry.getKey() : prefix + "." + entry.getKey();
            putJsonValue(key, entry.getValue(), out, childObjectArrays);
        }
    }

    private void putJsonValue(
            String key,
            JsonNode value,
            Map<String, Object> out,
            List<JsonNode> childObjectArrays) {
        if (value == null || value.isNull()) {
            out.put(key, null);
        } else if (value.isIntegralNumber()) {
            out.put(key, value.longValue());
        } else if (value.isFloatingPointNumber()) {
            out.put(key, value.doubleValue());
        } else if (value.isBoolean()) {
            out.put(key, value.booleanValue());
        } else if (value.isObject()) {
            flattenJsonFields(key, value, out, childObjectArrays);
        } else if (value.isArray()) {
            if (!value.isEmpty() && value.get(0).isObject()) {
                childObjectArrays.add(value);
            } else {
                List<String> items = new ArrayList<>();
                value.forEach(elem -> items.add(elem.asText()));
                out.put(key, String.join(", ", items));
            }
        } else {
            out.put(key, value.asText());
        }
    }

    private SheetExtractionResponse extractXmlSheets(String baseTitle, String text) {
        String trimmed = text != null ? text.replaceFirst("^\uFEFF", "").trim() : "";
        if (trimmed.isEmpty()) {
            return new SheetExtractionResponse(List.of(), "XML file is empty.");
        }
        Document doc = Jsoup.parse(trimmed, "", Parser.xmlParser());
        Element root = doc.children().first();
        if (root == null) {
            return new SheetExtractionResponse(List.of(), "Invalid XML document structure.");
        }
        Map<String, List<Element>> groups = new LinkedHashMap<>();
        for (Element child : root.children()) {
            groups.computeIfAbsent(child.tagName(), k -> new ArrayList<>()).add(child);
        }
        List<WorkbookSheetDto> sheets = buildXmlSheets(baseTitle, groups);
        if (sheets.isEmpty()) {
            return new SheetExtractionResponse(List.of(), "No repeating record elements found in XML file.");
        }
        return new SheetExtractionResponse(sheets, null);
    }

    private List<WorkbookSheetDto> buildXmlSheets(String baseTitle, Map<String, List<Element>> groups) {
        List<WorkbookSheetDto> sheets = new ArrayList<>();
        int idx = 1;
        for (Map.Entry<String, List<Element>> group : groups.entrySet()) {
            List<Map<String, Object>> rows = new ArrayList<>();
            int limit = Math.min(group.getValue().size(), MAX_PREVIEW_RECORDS);
            for (int i = 0; i < limit; i++) {
                Map<String, Object> row = new LinkedHashMap<>();
                flattenXmlRecord("", group.getValue().get(i), row, true);
                if (!row.isEmpty()) {
                    rows.add(row);
                }
            }
            if (!rows.isEmpty()) {
                String name = groups.size() > 1 ? group.getKey() : baseTitle;
                sheets.add(buildUnpromotedSheetFromRows("xml_" + idx++, name, "Table", name, rows));
            }
        }
        return sheets;
    }

    private void flattenXmlRecord(String prefix, Element el, Map<String, Object> out, boolean isRecordRoot) {
        el.attributes().forEach(attr -> {
            String key = prefix.isEmpty() ? "@" + attr.getKey() : prefix + ".@" + attr.getKey();
            out.put(key, attr.getValue());
        });
        if (el.children().isEmpty()) {
            if (!isRecordRoot || out.isEmpty()) {
                String key = prefix.isEmpty() ? el.tagName() : prefix;
                out.put(key, el.text().trim());
            }
            return;
        }
        for (Element child : el.children()) {
            String childPrefix = prefix.isEmpty() ? child.tagName() : prefix + "." + child.tagName();
            flattenXmlRecord(childPrefix, child, out, false);
        }
    }

    private SheetExtractionResponse extractDelimitedSheets(String baseTitle, String text, char delimiter) {
        if (text == null || text.isBlank()) {
            return new SheetExtractionResponse(List.of(), "File contains no tabular rows.");
        }
        String clean = text.startsWith("\uFEFF") ? text.substring(1) : text;
        List<String> nonEmptyLines = new ArrayList<>();
        for (String line : clean.split("\\r?\\n")) {
            if (!line.isBlank()) {
                nonEmptyLines.add(line);
            }
        }
        if (nonEmptyLines.isEmpty()) {
            return new SheetExtractionResponse(List.of(), "File contains no tabular rows.");
        }
        List<String> headers = normalizeDelimitedHeaders(
                delimitedFileParser.parseDelimitedLine(nonEmptyLines.get(0), delimiter));
        List<Map<String, Object>> rows = new ArrayList<>();
        int limit = Math.min(nonEmptyLines.size(), MAX_PREVIEW_RECORDS + 1);
        for (int r = 1; r < limit; r++) {
            List<String> cells = delimitedFileParser.parseDelimitedLine(nonEmptyLines.get(r), delimiter);
            Map<String, Object> row = new LinkedHashMap<>();
            for (int c = 0; c < headers.size(); c++) {
                String rawVal = c < cells.size() ? cells.get(c) : "";
                row.put(headers.get(c), coerceScalar(rawVal));
            }
            rows.add(row);
        }
        WorkbookSheetDto sheet = buildUnpromotedSheet("sheet_1", baseTitle, "Sheet", "Sheet1", headers, rows);
        return new SheetExtractionResponse(List.of(sheet), null);
    }

    private List<String> normalizeDelimitedHeaders(List<String> rawHeaders) {
        List<String> headers = new ArrayList<>(rawHeaders.size());
        for (int i = 0; i < rawHeaders.size(); i++) {
            String h = rawHeaders.get(i).trim();
            headers.add(h.isEmpty() ? "Column" + (i + 1) : h);
        }
        return headers;
    }

    private Object coerceScalar(String val) {
        if (val == null) {
            return "";
        }
        String trimmed = val.trim();
        if (trimmed.isEmpty()) {
            return "";
        }
        DataType inferred = typeInferenceEngine.inferValueType(trimmed);
        try {
            if (inferred == DataType.INT64 || inferred == DataType.FLOAT64 || inferred == DataType.BOOLEAN) {
                return typeInferenceEngine.coerceValue(trimmed, inferred);
            }
        } catch (IllegalArgumentException ex) {
            LOGGER.trace("Scalar coercion fallback to string for '{}': {}", trimmed, ex.getMessage());
        }
        return trimmed;
    }

    private WorkbookSheetDto buildUnpromotedSheetFromRows(
            String id,
            String name,
            String kind,
            String sheetName,
            List<Map<String, Object>> rows) {
        Set<String> keySet = new LinkedHashSet<>();
        for (Map<String, Object> row : rows) {
            keySet.addAll(row.keySet());
        }
        return buildUnpromotedSheet(id, name, kind, sheetName, new ArrayList<>(keySet), rows);
    }

    private WorkbookSheetDto buildUnpromotedSheet(
            String id,
            String name,
            String kind,
            String sheetName,
            List<String> headers,
            List<Map<String, Object>> dataRows) {
        List<SheetColumnDto> columns = new ArrayList<>(headers.size());
        Map<String, Object> headerRow = new LinkedHashMap<>();
        for (int i = 0; i < headers.size(); i++) {
            String colKey = "Column" + (i + 1);
            columns.add(new SheetColumnDto(colKey, "String", "String"));
            headerRow.put(colKey, headers.get(i));
        }
        List<Map<String, Object>> allRows = new ArrayList<>(dataRows.size() + 1);
        allRows.add(headerRow);
        for (Map<String, Object> row : dataRows) {
            Map<String, Object> unpromotedRow = new LinkedHashMap<>();
            for (int i = 0; i < headers.size(); i++) {
                unpromotedRow.put("Column" + (i + 1), row.get(headers.get(i)));
            }
            allRows.add(unpromotedRow);
        }
        return new WorkbookSheetDto(id, name, kind, sheetName, columns, allRows);
    }
}
