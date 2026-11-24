package com.rollercoaster.ingestion;

import com.rollercoaster.ingestion.IngestionController.QuarantineRecordDto;
import com.rollercoaster.ingestion.rquery.RQueryStep;

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
            String tableName,
            List<WorkbookSheetDto> sheets,
            String error
    ) {
        public SheetExtractionResponse {
            tableName = (tableName != null && !tableName.isBlank()) ? tableName : "Dataset";
            sheets = sheets != null ? List.copyOf(sheets) : List.of();
        }

        public SheetExtractionResponse(List<WorkbookSheetDto> sheets, String error) {
            this("Dataset", sheets, error);
        }
    }

    public record PipelineColumnDto(
            String name,
            String type,
            String displayType,
            boolean numeric
    ) {
        public static PipelineColumnDto from(String name, DataType dataType) {
            DataType resolved = dataType != null ? dataType : DataType.UTF8_STRING;
            return new PipelineColumnDto(
                    name,
                    resolved.name(),
                    resolved.displayLabel(),
                    resolved.isNumeric()
            );
        }
    }

    public record PipelinePreviewRequest(
            String tenantId,
            String datasetId,
            String fileName,
            SourceFormat format,
            String rawPayload,
            String rawPayloadBase64,
            String sheetName,
            List<RQueryStep> steps,
            Map<String, List<Map<String, Object>>> lookupTables,
            List<SheetColumnDto> initialColumns,
            List<Map<String, Object>> initialRows,
            String rangeStart,
            String rangeEnd
    ) {
    }

    public record PipelinePreviewResponse(
            List<PipelineColumnDto> columns,
            List<Map<String, Object>> rows,
            List<QuarantineRecordDto> quarantinedRecords,
            String mQueryScript,
            String activeStepFormula
    ) {
        public PipelinePreviewResponse {
            columns = columns != null ? List.copyOf(columns) : List.of();
            rows = rows != null ? List.copyOf(rows) : List.of();
            quarantinedRecords = quarantinedRecords != null ? List.copyOf(quarantinedRecords) : List.of();
            mQueryScript = mQueryScript != null ? mQueryScript : "";
            activeStepFormula = activeStepFormula != null ? activeStepFormula : "";
        }
    }
}
