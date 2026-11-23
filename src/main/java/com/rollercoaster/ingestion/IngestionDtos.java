package com.rollercoaster.ingestion;

import java.util.List;
import java.util.Map;

/**
 * Data transfer records for sheet extraction and ingestion preview requests.
 */
public final class IngestionDtos {

    private IngestionDtos() {
    }

    public record SheetColumnDto(String name, String type, String icon) {
    }

    public record WorkbookSheetDto(
            String id,
            String name,
            String kind,
            String sheetName,
            List<SheetColumnDto> columns,
            List<Map<String, Object>> rows
    ) {
    }

    public record SheetExtractionRequest(
            String fileName,
            SourceFormat format,
            String rawPayload,
            String rawPayloadBase64
    ) {
    }

    public record SheetExtractionResponse(
            List<WorkbookSheetDto> sheets,
            String error
    ) {
    }
}
