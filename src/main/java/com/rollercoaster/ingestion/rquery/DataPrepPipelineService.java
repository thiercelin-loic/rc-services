package com.rollercoaster.ingestion.rquery;

import com.rollercoaster.ingestion.DataIngestionService;
import com.rollercoaster.ingestion.DataType;
import com.rollercoaster.ingestion.IngestionController.QuarantineRecordDto;
import com.rollercoaster.ingestion.IngestionDtos.PipelineColumnDto;
import com.rollercoaster.ingestion.IngestionDtos.PipelinePreviewRequest;
import com.rollercoaster.ingestion.IngestionDtos.PipelinePreviewResponse;
import com.rollercoaster.ingestion.IngestionDtos.SheetColumnDto;
import com.rollercoaster.ingestion.RecordBatch;
import com.rollercoaster.ingestion.SchemaHints;
import com.rollercoaster.ingestion.SheetExtractionService;
import com.rollercoaster.ingestion.SourceFormat;
import com.rollercoaster.ingestion.rquery.MQueryFormatter.MQueryFormatOptions;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Domain service for Data Preparation pipeline construction, step normalization,
 * and unified preview evaluation.
 */
@Service
public class DataPrepPipelineService {

    private static final String DEFAULT_TENANT = "default-tenant";
    private static final String DEFAULT_DATASET = "preview-dataset";
    private static final String STEP_FILE_SOURCE = "FileSource";
    private static final String STEP_NAVIGATION = "Navigation";
    private static final String STEP_PROMOTE_HEADERS = "PromoteHeadersStep";
    private static final String STEP_CHANGE_TYPE = "ChangeTypeStep";

    private final DataIngestionService dataIngestionService;
    private final RQueryTransformationEngine transformationEngine;
    private final MQueryFormatter mQueryFormatter;

    public DataPrepPipelineService(
            DataIngestionService dataIngestionService,
            RQueryTransformationEngine transformationEngine,
            MQueryFormatter mQueryFormatter) {
        this.dataIngestionService = Objects.requireNonNull(dataIngestionService, "dataIngestionService must not be null");
        this.transformationEngine = Objects.requireNonNull(transformationEngine, "transformationEngine must not be null");
        this.mQueryFormatter = Objects.requireNonNull(mQueryFormatter, "mQueryFormatter must not be null");
    }

    public record InitialPipelineSpec(
            String fileName,
            SourceFormat format,
            String sheetName,
            Map<String, DataType> columnTypes
    ) {
        public InitialPipelineSpec {
            fileName = (fileName != null && !fileName.isBlank()) ? fileName.trim() : "Dataset.csv";
            format = format != null ? format : SourceFormat.fromFileName(fileName).orElse(SourceFormat.CSV);
            sheetName = (sheetName != null && !sheetName.isBlank())
                    ? sheetName.trim()
                    : SheetExtractionService.sanitizeTableName(fileName);
            columnTypes = columnTypes != null ? Map.copyOf(columnTypes) : Map.of();
        }
    }

    /**
     * Builds the canonical initial 4-step Data Preparation pipeline:
     * {@code FileSource -> Navigation -> PromoteHeaders -> ChangeType}.
     */
    public List<RQueryStep> buildInitialPipeline(InitialPipelineSpec spec) {
        Objects.requireNonNull(spec, "spec must not be null");

        RQueryStep fileSource = new RQueryStep(
                STEP_FILE_SOURCE, null, spec.fileName(), spec.format(), null, null,
                null, null, null, null, null, null, null, null, null, null, null, null, null, null);

        RQueryStep navigation = new RQueryStep(
                STEP_NAVIGATION, STEP_FILE_SOURCE, null, null, spec.sheetName(), null,
                null, null, null, null, null, null, null, null, null, spec.sheetName(), null, null, null, null);

        RQueryStep promoteHeaders = RQueryStep.promoteHeaders(STEP_NAVIGATION);

        String firstCol = spec.columnTypes().isEmpty() ? null : spec.columnTypes().keySet().iterator().next();
        DataType firstType = firstCol != null ? spec.columnTypes().get(firstCol) : DataType.UTF8_STRING;
        Map<String, Object> changeTypeParams = spec.columnTypes().isEmpty()
                ? Map.of()
                : Map.of("columnTypes", toColumnTypeLabelMap(spec.columnTypes()));

        RQueryStep changeType = new RQueryStep(
                STEP_CHANGE_TYPE, STEP_PROMOTE_HEADERS, null, null, firstCol, firstType,
                null, null, null, null, null, null, null, null, null, null, null, null, changeTypeParams, "quarantine");

        return List.of(fileSource, navigation, promoteHeaders, changeType);
    }

    /**
     * Strips non-executable metadata steps ({@code FileSource}, {@code Source}, {@code Navigation})
     * and normalizes step identifiers and column types for execution by {@link RQueryTransformationEngine}.
     */
    public List<RQueryStep> normalizeSteps(List<RQueryStep> rawSteps) {
        if (rawSteps == null || rawSteps.isEmpty()) {
            return List.of();
        }
        List<RQueryStep> normalized = new ArrayList<>(rawSteps.size());
        for (RQueryStep step : rawSteps) {
            if (step == null || step.step() == null || step.step().isBlank()) {
                continue;
            }
            if (isMetadataStep(step.step())) {
                continue;
            }
            normalized.add(normalizeSingleStep(step));
        }
        return List.copyOf(normalized);
    }

    public List<RecordBatch> evaluateRecordBatches(PipelinePreviewRequest request) {
        Objects.requireNonNull(request, "request must not be null");
        Flux<RecordBatch> sourceStream = buildSourceStream(request);
        List<RQueryStep> executableSteps = normalizeSteps(request.steps());
        String tenantId = (request.tenantId() != null && !request.tenantId().isBlank()) ? request.tenantId() : DEFAULT_TENANT;
        String datasetId = (request.datasetId() != null && !request.datasetId().isBlank()) ? request.datasetId() : DEFAULT_DATASET;
        Map<String, List<Map<String, Object>>> lookups = request.lookupTables() != null ? request.lookupTables() : Map.of();

        List<RecordBatch> batches = transformationEngine.executePipeline(
                        sourceStream, executableSteps, lookups, tenantId, datasetId)
                .collectList()
                .block();
        return batches != null ? batches : List.of();
    }

    public PipelinePreviewResponse evaluatePreview(PipelinePreviewRequest request) {
        Objects.requireNonNull(request, "request must not be null");
        List<RecordBatch> batches = evaluateRecordBatches(request);

        Map<String, DataType> finalSchema = new LinkedHashMap<>();
        List<Map<String, Object>> allRows = new ArrayList<>();
        List<QuarantineRecordDto> allQuarantined = new ArrayList<>();

        for (RecordBatch batch : batches) {
            finalSchema.putAll(batch.schema());
            allRows.addAll(batch.rows());
            batch.quarantinedRecords().stream()
                    .map(QuarantineRecordDto::from)
                    .filter(Objects::nonNull)
                    .forEach(allQuarantined::add);
        }

        List<PipelineColumnDto> columns = finalSchema.entrySet().stream()
                .map(entry -> PipelineColumnDto.from(entry.getKey(), entry.getValue()))
                .toList();

        List<RQueryStep> scriptSteps = resolveStepsForFormatting(request, finalSchema);
        String tableName = SheetExtractionService.sanitizeTableName(request.fileName());
        MQueryFormatOptions formatOptions = new MQueryFormatOptions(tableName, request.rangeStart(), request.rangeEnd());
        String mQueryScript = mQueryFormatter.formatScript(scriptSteps, formatOptions);
        String activeStepFormula = resolveActiveStepFormula(scriptSteps);

        return new PipelinePreviewResponse(
                columns,
                allRows,
                allQuarantined,
                mQueryScript,
                activeStepFormula
        );
    }

    private List<RQueryStep> resolveStepsForFormatting(
            PipelinePreviewRequest request,
            Map<String, DataType> inferredSchema) {
        if (request.steps() != null && !request.steps().isEmpty()) {
            return request.steps();
        }
        InitialPipelineSpec spec = new InitialPipelineSpec(
                request.fileName(),
                request.format(),
                request.sheetName(),
                inferredSchema
        );
        return buildInitialPipeline(spec);
    }

    private String resolveActiveStepFormula(List<RQueryStep> steps) {
        return mQueryFormatter.formatLastStepFormula(steps);
    }

    private Flux<RecordBatch> buildSourceStream(PipelinePreviewRequest request) {
        if (request.initialRows() != null) {
            Map<String, DataType> schema = new LinkedHashMap<>();
            if (request.initialColumns() != null && !request.initialColumns().isEmpty()) {
                for (SheetColumnDto col : request.initialColumns()) {
                    if (col != null && col.name() != null) {
                        schema.put(col.name(), DataType.fromLabel(col.type()));
                    }
                }
            } else if (!request.initialRows().isEmpty()) {
                for (String key : request.initialRows().getFirst().keySet()) {
                    schema.put(key, DataType.UTF8_STRING);
                }
            }
            return Flux.just(new RecordBatch(0, schema, request.initialRows(), List.of()));
        }

        SourceFormat format = request.format() != null
                ? request.format()
                : SourceFormat.fromFileName(request.fileName()).orElse(SourceFormat.CSV);
        char delimiter = format == SourceFormat.TSV
                ? '\t'
                : DataIngestionService.detectDelimiter(request.rawPayload());
        SchemaHints hints = new SchemaHints(
                request.tenantId(),
                request.datasetId(),
                format,
                request.sheetName(),
                0,
                delimiter,
                true,
                StandardCharsets.UTF_8,
                Map.of()
        );
        byte[] payloadBytes = (request.rawPayloadBase64() != null && !request.rawPayloadBase64().isBlank())
                ? Base64.getDecoder().decode(request.rawPayloadBase64())
                : (request.rawPayload() != null ? request.rawPayload() : "").getBytes(StandardCharsets.UTF_8);
        return dataIngestionService.ingestStream(new ByteArrayInputStream(payloadBytes), hints);
    }

    private static boolean isMetadataStep(String stepType) {
        String normalized = normalizeStepToken(stepType);
        return "FILESOURCE".equals(normalized) || "SOURCE".equals(normalized) || "NAVIGATION".equals(normalized);
    }

    private static RQueryStep normalizeSingleStep(RQueryStep step) {
        String canonicalName = toCanonicalStepName(step.step());
        DataType normalizedType = step.type();
        if (normalizedType == null && step.parameters() != null && step.parameters().get("type") != null) {
            normalizedType = DataType.fromLabel(step.parameters().get("type").toString());
        }
        if ("ChangeTypeStep".equals(canonicalName) && normalizedType == null) {
            normalizedType = DataType.UTF8_STRING;
        }
        return new RQueryStep(
                canonicalName,
                step.input(),
                step.uri(),
                step.format(),
                step.column(),
                normalizedType,
                step.oldName(),
                step.newName(),
                step.predicate(),
                step.pivotColumns(),
                step.nameCol(),
                step.valCol(),
                step.groupColumns(),
                step.aggregations(),
                step.joinKeys(),
                step.targetTable(),
                step.joinType(),
                step.expression(),
                step.parameters(),
                step.onError()
        );
    }

    static String toCanonicalStepName(String rawStepName) {
        return switch (normalizeStepToken(rawStepName)) {
            case "CHANGETYPE", "CHANGEDTYPE" -> STEP_CHANGE_TYPE;
            case "DETECTDATATYPE", "DETECTEDDATATYPES" -> "DetectDataTypeStep";
            case "FILTERROWS", "FILTEREDROWS" -> "FilterRowsStep";
            case "FILTERNULLS", "REMOVEDBLANKROWS" -> "FilterNullsStep";
            case "RENAMECOLUMN", "RENAMEDCOLUMNS" -> "RenameColumnStep";
            case "TRANSFORMCOLUMNNAMES", "TRANSFORMEDCOLUMNNAMES" -> "TransformColumnNamesStep";
            case "REMOVECOLUMN", "REMOVEDCOLUMNS" -> "RemoveColumnStep";
            case "REMOVEOTHERCOLUMNS", "REMOVEDOTHERCOLUMNS" -> "RemoveOtherColumnsStep";
            case "UNPIVOTCOLUMNS", "UNPIVOTEDCOLUMNS" -> "UnpivotColumnsStep";
            case "UNPIVOTOTHERCOLUMNS", "UNPIVOTEDOTHERCOLUMNS" -> "UnpivotOtherColumnsStep";
            case "GROUPROWS", "GROUPEDROWS" -> "GroupRowsStep";
            case "MERGEQUERIES", "MERGEDQUERIES" -> "MergeQueriesStep";
            case "ADDCUSTOMCOLUMN", "ADDEDCUSTOM" -> "AddCustomColumnStep";
            case "ADDINDEXCOLUMN", "ADDEDINDEX" -> "AddIndexColumnStep";
            case "FILLDOWN", "FILLEDDOWN" -> "FillDownStep";
            case "TRANSPOSE", "TRANSPOSEDTABLE" -> "TransposeStep";
            case "PROMOTEHEADERS", "PROMOTEDHEADERS" -> STEP_PROMOTE_HEADERS;
            case "REMOVETOPROWS", "REMOVEDTOPROWS" -> "RemoveTopRowsStep";
            case "REMOVEBOTTOMROWS", "REMOVEDBOTTOMROWS" -> "RemoveBottomRowsStep";
            case "SPLITCOLUMN", "SPLITCOLUMNBYDELIMITER" -> "SplitColumnStep";
            case "MERGECOLUMNS", "MERGEDCOLUMNS" -> "MergeColumnsStep";
            case "REMOVEDUPLICATES", "REMOVEDDUPLICATES" -> "RemoveDuplicatesStep";
            case "SORTROWS", "SORTEDROWS" -> "SortRowsStep";
            case "INLINECLEANSING", "CLEANEDCOLUMN" -> "InlineCleansingStep";
            default -> rawStepName.trim();
        };
    }

    private static String normalizeStepToken(String raw) {
        if (raw == null) {
            return "";
        }
        String token = raw.trim().replaceAll("[\\s_-]+", "").toUpperCase(Locale.ROOT);
        if (token.endsWith("STEP") && token.length() > 4) {
            token = token.substring(0, token.length() - 4);
        }
        return token;
    }

    private static Map<String, String> toColumnTypeLabelMap(Map<String, DataType> columnTypes) {
        Map<String, String> out = new LinkedHashMap<>();
        columnTypes.forEach((col, dt) -> out.put(col, (dt != null ? dt : DataType.UTF8_STRING).displayLabel()));
        return out;
    }
}
