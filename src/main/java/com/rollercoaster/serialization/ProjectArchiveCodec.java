package com.rollercoaster.serialization;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rollercoaster.serialization.ProjectDtos.ColumnDto;
import com.rollercoaster.serialization.ProjectDtos.MeasureDto;
import com.rollercoaster.serialization.ProjectDtos.ParsedProjectResponse;
import com.rollercoaster.serialization.ProjectDtos.TableDto;
import com.rollercoaster.serialization.ProjectDtos.VisualItemDto;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * Low-level codec for packing and unpacking `.rcp` ZIP archives, RSDL definitions, and SHA-256 digests.
 */
@Component
public class ProjectArchiveCodec {

    private final ObjectMapper objectMapper = new ObjectMapper();

    public byte[] packZipEntries(Map<String, byte[]> entries) throws IOException {
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream();
             ZipOutputStream zos = new ZipOutputStream(baos, StandardCharsets.UTF_8)) {
            for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
                ZipEntry zipEntry = new ZipEntry(entry.getKey());
                zos.putNextEntry(zipEntry);
                zos.write(entry.getValue());
                zos.closeEntry();
            }
            zos.finish();
            return baos.toByteArray();
        }
    }

    public Map<String, byte[]> unpackZipEntries(byte[] bytes) {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(bytes), StandardCharsets.UTF_8)) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                if (!entry.isDirectory()) {
                    entries.put(entry.getName(), zis.readAllBytes());
                }
                zis.closeEntry();
            }
        } catch (IOException ex) {
            throw new CorruptedPackageException("Failed to unpack .rcp ZIP archive: " + ex.getMessage(), ex);
        }
        if (entries.isEmpty()) {
            throw new CorruptedPackageException("Corrupt or empty .rcp ZIP container.");
        }
        return entries;
    }

    public void validateManifest(byte[] manifestBytes) {
        if (manifestBytes == null) {
            return;
        }
        try {
            JsonNode manifestNode = objectMapper.readTree(manifestBytes);
            String minEngine = manifestNode.path("minEngineVersion").asText(ProjectSerializationService.ENGINE_VERSION);
            if (minEngine.startsWith("2.") || minEngine.startsWith("3.")) {
                throw new IncompatibleSchemaException(
                        "Unsupported .rcp engine version: " + minEngine,
                        manifestNode.path("formatVersion").asText(ProjectSerializationService.FORMAT_VERSION),
                        ProjectSerializationService.ENGINE_VERSION);
            }
        } catch (IncompatibleSchemaException ex) {
            throw ex;
        } catch (IOException ex) {
            throw new CorruptedPackageException("Invalid manifest.json in .rcp archive.", ex);
        }
    }

    public JsonNode readTree(byte[] bytes, String errorMessage) {
        try {
            return objectMapper.readTree(bytes);
        } catch (IOException ex) {
            throw new CorruptedPackageException(errorMessage, ex);
        }
    }

    public <T> T convertValue(JsonNode node, TypeReference<T> typeReference) {
        return objectMapper.convertValue(node, typeReference);
    }

    public byte[] writePrettyJson(Object value) throws JsonProcessingException {
        return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(value);
    }

    public byte[] writeCompactJson(Object value) throws JsonProcessingException {
        return objectMapper.writeValueAsBytes(value);
    }

    public ParsedProjectResponse parseRawJson(String filename, byte[] bytes) {
        try {
            JsonNode root = objectMapper.readTree(bytes);
            if (root == null || !root.isObject()) {
                throw new CorruptedPackageException("Project JSON payload must be a JSON object.");
            }
            List<String> pages = extractPages(root);
            Map<String, List<VisualItemDto>> visualsMap = extractVisualsMap(root);
            return new ParsedProjectResponse(filename, pages, visualsMap, null, null, null);
        } catch (CorruptedPackageException ex) {
            throw ex;
        } catch (IOException | IllegalArgumentException ex) {
            throw new CorruptedPackageException("Corrupt or invalid .rcp project archive: " + ex.getMessage(), ex);
        }
    }

    public String buildRsdl(String filename, List<TableDto> tables, List<MeasureDto> measures) {
        List<String> lines = new ArrayList<>();
        lines.add("// RollerCoaster Semantic Definition Language (RSDL)");
        lines.add("// Project: " + filename);
        lines.add("");

        for (TableDto table : tables) {
            lines.add("table '" + table.name() + "' {");
            lines.add("  role: '" + table.role() + "'");
            if (table.columns() != null) {
                for (ColumnDto col : table.columns()) {
                    lines.add("  column '" + col.name() + "' : " + col.dataType());
                }
            }
            lines.add("}");
            lines.add("");
        }

        for (MeasureDto measure : measures) {
            lines.add("measure '" + measure.label() + "' = " + measure.formula());
        }
        return String.join("\n", lines);
    }

    public boolean isZipArchive(byte[] bytes) {
        return bytes.length >= 4
                && bytes[0] == 0x50
                && bytes[1] == 0x4b
                && bytes[2] == 0x03
                && bytes[3] == 0x04;
    }

    public String computeSha256(String seed) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(seed.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 MessageDigest algorithm is unavailable", ex);
        }
    }

    private List<String> extractPages(JsonNode root) {
        List<String> pages = new ArrayList<>();
        if (root.has("pages") && root.get("pages").isArray() && !root.get("pages").isEmpty()) {
            for (JsonNode p : root.get("pages")) {
                pages.add(p.asText());
            }
            return pages;
        }
        pages.add(ProjectSerializationService.DEFAULT_PAGE_TITLE);
        return pages;
    }

    private Map<String, List<VisualItemDto>> extractVisualsMap(JsonNode root) {
        if (root.has("visuals") && root.get("visuals").isObject()) {
            return objectMapper.convertValue(
                    root.get("visuals"),
                    new TypeReference<Map<String, List<VisualItemDto>>>() {});
        }
        Map<String, List<VisualItemDto>> fallback = new LinkedHashMap<>();
        fallback.put(ProjectSerializationService.DEFAULT_PAGE_TITLE, List.of());
        return fallback;
    }
}
