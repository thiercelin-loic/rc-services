package com.rollercoaster.ingestion;

import java.util.Locale;
import java.util.Optional;

/**
 * Supported multi-format file and stream source types.
 */
public enum SourceFormat {
    CSV(false),
    TSV(false),
    JSON(false),
    XML(false),
    XLSX(true),
    AVRO(true),
    REST_API(false),
    HTML_SCRAPER(false);

    private final boolean binary;

    SourceFormat(boolean binary) {
        this.binary = binary;
    }

    public boolean isBinary() {
        return binary;
    }

    /**
     * Detects the {@link SourceFormat} from a file name extension.
     */
    public static Optional<SourceFormat> fromFileName(String fileName) {
        if (fileName == null || fileName.isBlank()) {
            return Optional.empty();
        }
        String lower = fileName.trim().toLowerCase(Locale.ROOT);
        int dotIndex = lower.lastIndexOf('.');
        if (dotIndex < 0 || dotIndex == lower.length() - 1) {
            return Optional.empty();
        }
        String ext = lower.substring(dotIndex + 1);
        return switch (ext) {
            case "csv", "txt" -> Optional.of(CSV);
            case "tsv", "tab" -> Optional.of(TSV);
            case "json" -> Optional.of(JSON);
            case "xml" -> Optional.of(XML);
            case "xlsx", "xls" -> Optional.of(XLSX);
            case "avro" -> Optional.of(AVRO);
            case "html", "htm" -> Optional.of(HTML_SCRAPER);
            default -> Optional.empty();
        };
    }
}
