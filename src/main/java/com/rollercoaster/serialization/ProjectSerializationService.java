package com.rollercoaster.serialization;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.rollercoaster.serialization.ProjectDtos.MeasureDto;
import com.rollercoaster.serialization.ProjectDtos.ParsedProjectResponse;
import com.rollercoaster.serialization.ProjectDtos.ProjectArchiveReceipt;
import com.rollercoaster.serialization.ProjectDtos.ProjectExportRequest;
import com.rollercoaster.serialization.ProjectDtos.ProjectImportRequest;
import com.rollercoaster.serialization.ProjectDtos.TableDto;
import com.rollercoaster.serialization.ProjectDtos.VisualItemDto;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Orchestrates `.rcp` project archive export and import, preserving Power BI-aligned archive layout.
 */
@Service
public class ProjectSerializationService {

    public static final String PROJECT_EXTENSION = ".rcp";
    public static final String FORMAT_VERSION = "1.0.0";
    public static final String ENGINE_VERSION = "1.0.0";
    public static final int GRID_COLUMNS = 12;
    public static final String DEFAULT_PAGE_TITLE = "Page 1";

    private static final String MANIFEST_SCHEMA_URL = "urn:rollercoaster:schemas:v1:manifest";
    private static final String DEFINITION_SCHEMA_URL = "urn:rollercoaster:schemas:v1:definition";
    private static final String THEME_SCHEMA_URL = "urn:rollercoaster:schemas:v1:theme";
    private static final String THEME_NAME = "RollerCoaster Corporate Theme";

    private static final Map<String, String> THEME_TOKENS = Map.of(
            "--rc-primary", "#FF981A",
            "--rc-primary-dark", "#E65100",
            "--rc-surface-light", "#F2F2F2",
            "--rc-surface-dark", "#333333",
            "--rc-text-pure", "#FFFFFF",
            "--rc-text-dark", "#333333",
            "--rc-neutral-soft", "#B9C1C6");

    private final ProjectArchiveCodec archiveCodec;

    public ProjectSerializationService(ProjectArchiveCodec archiveCodec) {
        this.archiveCodec = archiveCodec;
    }

    public ProjectArchiveReceipt exportProject(ProjectExportRequest request) {
        String filename = normalizeName(request != null ? request.filename() : null);
        String projectTitle = filename.replaceAll("(?i)\\.rcp$", "");
        String themeMode = (request != null && request.themeMode() != null && !request.themeMode().isBlank())
                ? request.themeMode()
                : "dark";

        List<TableDto> tables = (request != null && request.tables() != null) ? request.tables() : List.of();
        Map<String, List<Map<String, Object>>> rows = (request != null && request.rows() != null) ? request.rows() : Map.of();
        List<MeasureDto> measures = (request != null && request.measures() != null) ? request.measures() : List.of();
        List<String> pages = (request != null && request.pages() != null && !request.pages().isEmpty())
                ? request.pages()
                : List.of(DEFAULT_PAGE_TITLE);
        Map<String, List<VisualItemDto>> visualsMap = (request != null && request.visualsMap() != null)
                ? request.visualsMap()
                : Map.of();

        boolean hasEmbeddedData = !tables.isEmpty() || !rows.isEmpty() || !measures.isEmpty();
        String checksum = archiveCodec.computeSha256(projectTitle);

        try {
            Map<String, byte[]> zipEntries = new LinkedHashMap<>();
            zipEntries.put("Version/manifest.json",
                    archiveCodec.writePrettyJson(buildManifest(projectTitle, hasEmbeddedData, checksum)));
            zipEntries.put("Report/Layout.json",
                    archiveCodec.writePrettyJson(buildLayoutDefinition(pages, visualsMap)));
            zipEntries.put("DataModel/model.rsdl",
                    archiveCodec.buildRsdl(filename, tables, measures).getBytes(StandardCharsets.UTF_8));
            zipEntries.put("Settings/queries.rq.json", "[]".getBytes(StandardCharsets.UTF_8));
            zipEntries.put("Report/StaticResources/SharedResources/BaseThemes/theme.json",
                    archiveCodec.writePrettyJson(buildThemeDefinition(themeMode)));
            zipEntries.put("DataModel/data.json",
                    archiveCodec.writeCompactJson(buildDataBundle(tables, rows, measures)));

            byte[] zipBytes = archiveCodec.packZipEntries(zipEntries);
            String base64 = Base64.getEncoder().encodeToString(zipBytes);
            String watermark = LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm"));
            return new ProjectArchiveReceipt(filename, base64, watermark, checksum);
        } catch (IOException ex) {
            throw new CorruptedPackageException("Failed to pack .rcp project archive: " + ex.getMessage(), ex);
        }
    }

    public ParsedProjectResponse importProject(ProjectImportRequest request) {
        if (request == null || request.archiveBase64() == null || request.archiveBase64().isBlank()) {
            throw new CorruptedPackageException("Empty or missing .rcp project archive payload.");
        }
        String filename = normalizeName(request.filename());
        byte[] bytes = decodeArchiveBytes(request.archiveBase64());

        if (archiveCodec.isZipArchive(bytes)) {
            return parseZipArchive(filename, bytes);
        }
        return archiveCodec.parseRawJson(filename, bytes);
    }

    public static String normalizeName(String rawName) {
        String trimmed = (rawName != null && !rawName.isBlank()) ? rawName.trim() : "Untitled Project";
        return trimmed.toLowerCase().endsWith(PROJECT_EXTENSION)
                ? trimmed
                : trimmed + PROJECT_EXTENSION;
    }

    private byte[] decodeArchiveBytes(String archiveBase64) {
        try {
            byte[] bytes = Base64.getDecoder().decode(archiveBase64);
            if (bytes.length < 2) {
                throw new CorruptedPackageException("Corrupt or truncated .rcp project archive.");
            }
            return bytes;
        } catch (IllegalArgumentException ex) {
            throw new CorruptedPackageException("Invalid Base64 encoding for .rcp archive.", ex);
        }
    }

    private ParsedProjectResponse parseZipArchive(String filename, byte[] bytes) {
        Map<String, byte[]> entries = archiveCodec.unpackZipEntries(bytes);
        archiveCodec.validateManifest(entries.getOrDefault("Version/manifest.json", entries.get("manifest.json")));

        DataBundle bundle = parseDataBundle(entries.getOrDefault("DataModel/data.json", entries.get("data.json")));
        List<String> pages = new ArrayList<>();
        Map<String, List<VisualItemDto>> visualsMap = new LinkedHashMap<>();
        parseLayoutPages(entries.getOrDefault("Report/Layout.json", entries.get("definition.json")), pages, visualsMap);

        if (pages.isEmpty()) {
            pages.add(DEFAULT_PAGE_TITLE);
            visualsMap.put(DEFAULT_PAGE_TITLE, List.of());
        }
        return new ParsedProjectResponse(
                filename, pages, visualsMap, bundle.tables(), bundle.rows(), bundle.measures());
    }

    private DataBundle parseDataBundle(byte[] dataBytes) {
        if (dataBytes == null) {
            return new DataBundle(null, null, null);
        }
        JsonNode dataNode = archiveCodec.readTree(dataBytes, "Invalid DataModel/data.json in .rcp archive.");
        List<TableDto> tables = dataNode.has("tables") && dataNode.get("tables").isArray()
                ? archiveCodec.convertValue(dataNode.get("tables"), new TypeReference<List<TableDto>>() {})
                : null;
        Map<String, List<Map<String, Object>>> rows = dataNode.has("rows") && dataNode.get("rows").isObject()
                ? archiveCodec.convertValue(dataNode.get("rows"), new TypeReference<Map<String, List<Map<String, Object>>>>() {})
                : null;
        List<MeasureDto> measures = dataNode.has("measures") && dataNode.get("measures").isArray()
                ? archiveCodec.convertValue(dataNode.get("measures"), new TypeReference<List<MeasureDto>>() {})
                : null;
        return new DataBundle(tables, rows, measures);
    }

    private void parseLayoutPages(
            byte[] layoutBytes,
            List<String> pages,
            Map<String, List<VisualItemDto>> visualsMap) {
        if (layoutBytes == null) {
            return;
        }
        JsonNode layoutNode = archiveCodec.readTree(layoutBytes, "Invalid Report/Layout.json in .rcp archive.");
        JsonNode pagesNode = layoutNode.get("pages");
        if (pagesNode == null || !pagesNode.isArray() || pagesNode.isEmpty()) {
            return;
        }
        for (JsonNode pageNode : pagesNode) {
            String pageTitle = pageNode.path("title").asText(DEFAULT_PAGE_TITLE);
            pages.add(pageTitle);
            visualsMap.put(pageTitle, extractPageVisuals(pageNode.get("visuals")));
        }
    }

    private List<VisualItemDto> extractPageVisuals(JsonNode visualsNode) {
        List<VisualItemDto> visuals = new ArrayList<>();
        if (visualsNode == null || !visualsNode.isArray()) {
            return visuals;
        }
        for (JsonNode vNode : visualsNode) {
            visuals.add(toVisualItemDto(vNode));
        }
        return visuals;
    }

    private VisualItemDto toVisualItemDto(JsonNode vNode) {
        String visId = vNode.path("visualId").asText("vis-" + System.currentTimeMillis());
        String archetype = vNode.path("archetype").asText("Bar");
        String title = vNode.path("title").asText(archetype + " Visual");
        int colSpan = vNode.path("layout").path("colSpan").asInt(6);
        String width = colSpan == 12 ? "full" : "half";
        String measureId = vNode.path("measureId").asText("Metric");
        String metricLabel = vNode.path("metricLabel").asText(measureId);
        String metricValue = vNode.path("metricValue").asText("€ 0.00");
        String metricSubtext = vNode.path("metricSubtext").asText("Imported from .rcp");
        String sortBy = vNode.hasNonNull("sortBy") ? vNode.get("sortBy").asText() : null;
        String sortOrder = vNode.hasNonNull("sortOrder") ? vNode.get("sortOrder").asText() : null;
        Map<String, List<String>> fieldWells = (vNode.hasNonNull("fieldWells") && vNode.get("fieldWells").isObject())
                ? archiveCodec.convertValue(vNode.get("fieldWells"), new TypeReference<Map<String, List<String>>>() {})
                : null;
        return new VisualItemDto(
                visId, title, archetype, width, metricLabel, metricValue, metricSubtext, sortBy, sortOrder, fieldWells);
    }

    private Map<String, Object> buildManifest(String projectTitle, boolean hasEmbeddedData, String checksum) {
        Map<String, Object> manifest = new LinkedHashMap<>();
        manifest.put("$schema", MANIFEST_SCHEMA_URL);
        manifest.put("projectId", UUID.randomUUID().toString());
        manifest.put("formatVersion", FORMAT_VERSION);
        manifest.put("minEngineVersion", ENGINE_VERSION);
        manifest.put("title", projectTitle);
        manifest.put("packageType", "STANDALONE_PROJECT");
        manifest.put("hasEmbeddedData", hasEmbeddedData);
        manifest.put("checksumSha256", checksum);
        manifest.put("parameters", List.of());
        return manifest;
    }

    private Map<String, Object> buildThemeDefinition(String themeMode) {
        Map<String, Object> theme = new LinkedHashMap<>();
        theme.put("$schema", THEME_SCHEMA_URL);
        theme.put("name", THEME_NAME);
        theme.put("mode", themeMode);
        theme.put("tokens", THEME_TOKENS);
        return theme;
    }

    private Map<String, Object> buildDataBundle(
            List<TableDto> tables,
            Map<String, List<Map<String, Object>>> rows,
            List<MeasureDto> measures) {
        Map<String, Object> dataBundle = new LinkedHashMap<>();
        dataBundle.put("tables", tables);
        dataBundle.put("rows", rows);
        dataBundle.put("measures", measures);
        return dataBundle;
    }

    private Map<String, Object> buildLayoutDefinition(
            List<String> pages,
            Map<String, List<VisualItemDto>> visualsMap) {
        List<Map<String, Object>> mappedPages = new ArrayList<>();
        for (String pageTitle : pages) {
            Map<String, Object> pageObj = new LinkedHashMap<>();
            pageObj.put("id", pageTitle.toLowerCase().replaceAll("[^a-z0-9]+", "-"));
            pageObj.put("title", pageTitle);
            pageObj.put("visuals", serializePageVisuals(visualsMap.getOrDefault(pageTitle, List.of())));
            mappedPages.add(pageObj);
        }

        Map<String, Object> definition = new LinkedHashMap<>();
        definition.put("$schema", DEFINITION_SCHEMA_URL);
        definition.put("version", FORMAT_VERSION);
        definition.put("gridColumns", GRID_COLUMNS);
        definition.put("pages", mappedPages);
        return definition;
    }

    private List<Map<String, Object>> serializePageVisuals(List<VisualItemDto> visuals) {
        List<Map<String, Object>> mappedVisuals = new ArrayList<>();
        for (VisualItemDto visual : visuals) {
            Map<String, Object> vObj = new LinkedHashMap<>();
            vObj.put("visualId", visual.id());
            vObj.put("archetype", visual.archetype());
            vObj.put("title", visual.title());
            vObj.put("metricLabel", visual.metricLabel());
            vObj.put("metricValue", visual.metricValue());
            vObj.put("metricSubtext", visual.metricSubtext());
            vObj.put("measureId", (visual.metricLabel() != null && !visual.metricLabel().isBlank())
                    ? visual.metricLabel()
                    : "m_sales");
            if (visual.sortBy() != null) {
                vObj.put("sortBy", visual.sortBy());
            }
            if (visual.sortOrder() != null) {
                vObj.put("sortOrder", visual.sortOrder());
            }
            if (visual.fieldWells() != null) {
                vObj.put("fieldWells", visual.fieldWells());
            }
            vObj.put("layout", Map.of(
                    "colSpan", "full".equalsIgnoreCase(visual.width()) ? 12 : 6,
                    "rowSpan", 1));
            mappedVisuals.add(vObj);
        }
        return mappedVisuals;
    }

    private record DataBundle(
            List<TableDto> tables,
            Map<String, List<Map<String, Object>>> rows,
            List<MeasureDto> measures
    ) {}
}
