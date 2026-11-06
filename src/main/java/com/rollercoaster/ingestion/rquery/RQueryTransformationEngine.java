package com.rollercoaster.ingestion.rquery;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rollercoaster.ingestion.DataType;
import com.rollercoaster.ingestion.QuarantineRecord;
import com.rollercoaster.ingestion.RecordBatch;
import com.rollercoaster.ingestion.parser.TypeInferenceEngine;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Deterministic R-Query Transformation Engine.
 * Executes a chronological chain of immutable steps (Source, ChangeTypeStep, FilterRowsStep,
 * RenameColumnStep, UnpivotColumnsStep, GroupRowsStep, MergeQueriesStep, AddCustomColumnStep)
 * and inline column cleansing operations across streaming RecordBatch units.
 */
@Service
public class RQueryTransformationEngine {

    private static final Pattern BRACKET_COL_PATTERN = Pattern.compile("\\[([^\\]]+)\\]");

    private final TypeInferenceEngine typeInferenceEngine;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public RQueryTransformationEngine(TypeInferenceEngine typeInferenceEngine) {
        this.typeInferenceEngine = typeInferenceEngine;
    }

    public Flux<RecordBatch> executePipeline(
            Flux<RecordBatch> sourceStream,
            List<RQueryStep> steps,
            Map<String, List<Map<String, Object>>> dimensionLookupTables,
            String tenantId,
            String datasetId) {
        return sourceStream.map(batch -> applyStepsToBatch(batch, steps, dimensionLookupTables, tenantId, datasetId));
    }

    public RecordBatch applyStepsToBatch(
            RecordBatch inputBatch,
            List<RQueryStep> steps,
            Map<String, List<Map<String, Object>>> dimensionLookupTables,
            String tenantId,
            String datasetId) {
        RecordBatch current = inputBatch;
        for (RQueryStep step : steps) {
            if ("Source".equalsIgnoreCase(step.step())) {
                continue;
            }
            current = executeSingleStep(current, step, dimensionLookupTables, tenantId, datasetId);
        }
        return current;
    }

    public RecordBatch executeSingleStep(
            RecordBatch batch,
            RQueryStep step,
            Map<String, List<Map<String, Object>>> dimensionLookupTables,
            String tenantId,
            String datasetId) {
        if (step == null || step.step() == null) {
            return batch;
        }
        return switch (step.step()) {
            case "ChangeTypeStep", "ChangeType" -> applyChangeType(batch, step, tenantId, datasetId);
            case "DetectDataTypeStep", "DetectDataType" -> applyDetectDataTypes(batch, tenantId, datasetId);
            case "FilterRowsStep", "FilterRows" -> applyFilterRows(batch, step);
            case "FilterNullsStep", "FilterNulls" -> applyFilterNulls(batch, step);
            case "RenameColumnStep", "RenameColumn" -> applyRenameColumn(batch, step);
            case "TransformColumnNamesStep", "TransformColumnNames" -> applyTransformColumnNames(batch, step);
            case "RemoveColumnStep", "RemoveColumn" -> applyRemoveColumn(batch, step);
            case "RemoveOtherColumnsStep", "RemoveOtherColumns" -> applyRemoveOtherColumns(batch, step);
            case "UnpivotColumnsStep", "UnpivotColumns", "UnpivotOtherColumnsStep" -> applyUnpivotColumns(batch, step);
            case "GroupRowsStep", "GroupRows" -> applyGroupRows(batch, step);
            case "MergeQueriesStep", "MergeQueries" -> applyMergeQueries(batch, step, dimensionLookupTables);
            case "AddCustomColumnStep", "AddCustomColumn" -> applyAddCustomColumn(batch, step, tenantId, datasetId);
            case "AddIndexColumnStep", "AddIndexColumn" -> applyAddIndexColumn(batch, step);
            case "FillDownStep", "FillDown" -> applyFillDown(batch, step);
            case "TransposeStep", "Transpose" -> applyTranspose(batch);
            case "PromoteHeadersStep", "PromoteHeaders" -> applyPromoteHeaders(batch, tenantId, datasetId);
            case "RemoveTopRowsStep", "RemoveTopRows" -> applyRemoveTopRows(batch, step);
            case "RemoveBottomRowsStep", "RemoveBottomRows" -> applyRemoveBottomRows(batch, step);
            case "SplitColumnStep", "SplitColumn" -> applySplitColumn(batch, step);
            case "MergeColumnsStep", "MergeColumns" -> applyMergeColumns(batch, step);
            case "RemoveDuplicatesStep", "RemoveDuplicates" -> applyRemoveDuplicates(batch, step);
            case "SortRowsStep", "SortRows" -> applySortRows(batch, step);
            case "InlineCleansingStep", "InlineCleansing" -> applyInlineCleansing(
                    batch,
                    step.column(),
                    step.expression() != null ? step.expression() : "ROUND_2_DECIMALS");
            default -> batch;
        };
    }

    /**
     * Inline column cleansing helpers surfaced in the Data Preparation preview grid header menu:
     * - ROUND_2_DECIMALS
     * - REMOVE_ZERO_VALUES
     * - REPLACE_NULLS_WITH_ZERO
     */
    public RecordBatch applyInlineCleansing(RecordBatch batch, String columnName, String cleansingAction) {
        List<Map<String, Object>> transformed = new ArrayList<>();
        for (Map<String, Object> row : batch.rows()) {
            Map<String, Object> copy = new LinkedHashMap<>(row);
            Object val = copy.get(columnName);
            switch (cleansingAction.toUpperCase()) {
                case "ROUND_2_DECIMALS" -> {
                    if (val instanceof Number n) {
                        double rounded = BigDecimal.valueOf(n.doubleValue())
                                .setScale(2, RoundingMode.HALF_UP)
                                .doubleValue();
                        copy.put(columnName, rounded);
                    }
                    transformed.add(copy);
                }
                case "REMOVE_ZERO_VALUES" -> {
                    boolean isZero = val instanceof Number n && Math.abs(n.doubleValue()) < 1e-12;
                    if (!isZero) {
                        transformed.add(copy);
                    }
                }
                case "REPLACE_NULLS_WITH_ZERO" -> {
                    if (val == null || val.toString().isBlank()) {
                        copy.put(columnName, 0.0);
                    }
                    transformed.add(copy);
                }
                default -> transformed.add(copy);
            }
        }
        return new RecordBatch(batch.batchIndex(), batch.schema(), transformed, batch.quarantinedRecords());
    }

    public String serializePipelineToJson(List<RQueryStep> steps) throws Exception {
        return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(steps);
    }

    public List<RQueryStep> deserializePipelineFromJson(String json) throws Exception {
        return objectMapper.readValue(json, new TypeReference<List<RQueryStep>>() {});
    }

    private RecordBatch applyChangeType(RecordBatch batch, RQueryStep step, String tenantId, String datasetId) {
        Map<String, DataType> newSchema = new LinkedHashMap<>(batch.schema());
        newSchema.put(step.column(), step.type());

        List<Map<String, Object>> validRows = new ArrayList<>();
        List<QuarantineRecord> quarantined = new ArrayList<>(batch.quarantinedRecords());

        long rowIdx = 0;
        for (Map<String, Object> row : batch.rows()) {
            rowIdx++;
            Map<String, Object> copy = new LinkedHashMap<>(row);
            try {
                Object coerced = typeInferenceEngine.coerceValue(copy.get(step.column()), step.type());
                copy.put(step.column(), coerced);
                validRows.add(copy);
            } catch (Exception ex) {
                quarantined.add(new QuarantineRecord(
                        tenantId,
                        datasetId,
                        rowIdx,
                        step.column(),
                        step.type().name(),
                        row.toString(),
                        "ChangeTypeStep failed on column '" + step.column() + "': " + ex.getMessage()
                ));
            }
        }
        return new RecordBatch(batch.batchIndex(), newSchema, validRows, quarantined);
    }

    private RecordBatch applyDetectDataTypes(RecordBatch batch, String tenantId, String datasetId) {
        Map<String, DataType> detected = typeInferenceEngine.inferSchemaFromSample(batch.rows(), Map.of());
        RecordBatch current = batch;
        for (Map.Entry<String, DataType> entry : detected.entrySet()) {
            RQueryStep synthetic = RQueryStep.changeType("DetectDataType", entry.getKey(), entry.getValue());
            current = applyChangeType(current, synthetic, tenantId, datasetId);
        }
        return current;
    }

    private RecordBatch applyFilterRows(RecordBatch batch, RQueryStep step) {
        String pred = step.predicate();
        if (pred == null || pred.isBlank()) {
            return batch;
        }
        List<Map<String, Object>> filtered = batch.rows().stream()
                .filter(row -> evaluateRowPredicate(row, pred))
                .toList();
        return new RecordBatch(batch.batchIndex(), batch.schema(), filtered, batch.quarantinedRecords());
    }

    private RecordBatch applyFilterNulls(RecordBatch batch, RQueryStep step) {
        String col = step.column();
        if (col == null || col.isBlank()) {
            return batch;
        }
        List<Map<String, Object>> filtered = batch.rows().stream()
                .filter(row -> {
                    Object val = row.get(col);
                    return val != null && !val.toString().isBlank();
                })
                .toList();
        return new RecordBatch(batch.batchIndex(), batch.schema(), filtered, batch.quarantinedRecords());
    }

    private RecordBatch applyRenameColumn(RecordBatch batch, RQueryStep step) {
        Map<String, DataType> newSchema = new LinkedHashMap<>();
        batch.schema().forEach((col, type) -> {
            if (col.equals(step.oldName())) {
                newSchema.put(step.newName(), type);
            } else {
                newSchema.put(col, type);
            }
        });

        List<Map<String, Object>> renamedRows = new ArrayList<>(batch.rows().size());
        for (Map<String, Object> row : batch.rows()) {
            Map<String, Object> renamed = new LinkedHashMap<>();
            row.forEach((k, v) -> renamed.put(k.equals(step.oldName()) ? step.newName() : k, v));
            renamedRows.add(renamed);
        }
        return new RecordBatch(batch.batchIndex(), newSchema, renamedRows, batch.quarantinedRecords());
    }

    private RecordBatch applyTransformColumnNames(RecordBatch batch, RQueryStep step) {
        String mode = step.expression() != null ? step.expression().toUpperCase() : "REPLACE_TEXT";
        String searchOrAffix = step.oldName() != null ? step.oldName() : "_";
        String replacement = step.newName() != null ? step.newName() : " ";

        Map<String, String> renameMap = new LinkedHashMap<>();
        for (String col : batch.schema().keySet()) {
            String nextName = switch (mode) {
                case "ADD_SUFFIX" -> col + searchOrAffix;
                case "ADD_PREFIX" -> searchOrAffix + col;
                default -> col.replace(searchOrAffix, replacement).trim();
            };
            renameMap.put(col, nextName);
        }

        Map<String, DataType> newSchema = new LinkedHashMap<>();
        batch.schema().forEach((col, type) -> newSchema.put(renameMap.getOrDefault(col, col), type));

        List<Map<String, Object>> transformedRows = new ArrayList<>(batch.rows().size());
        for (Map<String, Object> row : batch.rows()) {
            Map<String, Object> renamed = new LinkedHashMap<>();
            row.forEach((k, v) -> renamed.put(renameMap.getOrDefault(k, k), v));
            transformedRows.add(renamed);
        }
        return new RecordBatch(batch.batchIndex(), newSchema, transformedRows, batch.quarantinedRecords());
    }

    private RecordBatch applyRemoveColumn(RecordBatch batch, RQueryStep step) {
        String colToRemove = step.column();
        if (colToRemove == null) {
            return batch;
        }
        Map<String, DataType> newSchema = new LinkedHashMap<>(batch.schema());
        newSchema.remove(colToRemove);

        List<Map<String, Object>> nextRows = new ArrayList<>(batch.rows().size());
        for (Map<String, Object> row : batch.rows()) {
            Map<String, Object> copy = new LinkedHashMap<>(row);
            copy.remove(colToRemove);
            nextRows.add(copy);
        }
        return new RecordBatch(batch.batchIndex(), newSchema, nextRows, batch.quarantinedRecords());
    }

    private RecordBatch applyRemoveOtherColumns(RecordBatch batch, RQueryStep step) {
        List<String> kept = step.groupColumns() != null ? step.groupColumns()
                : (step.pivotColumns() != null ? step.pivotColumns() : List.of());
        if (kept.isEmpty()) {
            return batch;
        }
        Set<String> keptSet = new LinkedHashSet<>(kept);
        Map<String, DataType> newSchema = new LinkedHashMap<>();
        batch.schema().forEach((col, type) -> {
            if (keptSet.contains(col)) {
                newSchema.put(col, type);
            }
        });

        List<Map<String, Object>> nextRows = new ArrayList<>(batch.rows().size());
        for (Map<String, Object> row : batch.rows()) {
            Map<String, Object> copy = new LinkedHashMap<>();
            for (String k : keptSet) {
                if (row.containsKey(k)) {
                    copy.put(k, row.get(k));
                }
            }
            nextRows.add(copy);
        }
        return new RecordBatch(batch.batchIndex(), newSchema, nextRows, batch.quarantinedRecords());
    }

    private RecordBatch applyUnpivotColumns(RecordBatch batch, RQueryStep step) {
        List<String> pivotCols;
        if ("UnpivotOtherColumnsStep".equalsIgnoreCase(step.step()) && step.groupColumns() != null && !step.groupColumns().isEmpty()) {
            Set<String> anchorCols = new LinkedHashSet<>(step.groupColumns());
            pivotCols = batch.schema().keySet().stream().filter(c -> !anchorCols.contains(c)).toList();
        } else {
            pivotCols = step.pivotColumns() != null ? step.pivotColumns() : List.of();
        }
        String attrNameCol = step.nameCol() != null ? step.nameCol() : "Attribute";
        String attrValCol = step.valCol() != null ? step.valCol() : "Value";

        Map<String, DataType> newSchema = new LinkedHashMap<>();
        batch.schema().forEach((col, type) -> {
            if (!pivotCols.contains(col)) {
                newSchema.put(col, type);
            }
        });
        newSchema.put(attrNameCol, DataType.UTF8_STRING);
        newSchema.put(attrValCol, DataType.FLOAT64);

        List<Map<String, Object>> unpivoted = new ArrayList<>();
        for (Map<String, Object> row : batch.rows()) {
            for (String pCol : pivotCols) {
                if (!row.containsKey(pCol)) continue;
                Map<String, Object> outRow = new LinkedHashMap<>();
                row.forEach((k, v) -> {
                    if (!pivotCols.contains(k)) {
                        outRow.put(k, v);
                    }
                });
                outRow.put(attrNameCol, pCol);
                outRow.put(attrValCol, row.get(pCol));
                unpivoted.add(outRow);
            }
        }
        return new RecordBatch(batch.batchIndex(), newSchema, unpivoted, batch.quarantinedRecords());
    }

    private RecordBatch applyGroupRows(RecordBatch batch, RQueryStep step) {
        List<String> groupCols = step.groupColumns() != null ? step.groupColumns() : List.of();
        List<RQueryStep.AggregateDef> aggs = step.aggregations() != null ? step.aggregations() : List.of();

        Map<List<Object>, List<Map<String, Object>>> groups = new LinkedHashMap<>();
        for (Map<String, Object> row : batch.rows()) {
            List<Object> key = groupCols.stream().map(row::get).toList();
            groups.computeIfAbsent(key, k -> new ArrayList<>()).add(row);
        }

        Map<String, DataType> newSchema = new LinkedHashMap<>();
        for (String gc : groupCols) {
            newSchema.put(gc, batch.schema().getOrDefault(gc, DataType.UTF8_STRING));
        }
        for (RQueryStep.AggregateDef agg : aggs) {
            newSchema.put(agg.outputColumn(), DataType.FLOAT64);
        }

        List<Map<String, Object>> aggregatedRows = new ArrayList<>(groups.size());
        for (Map.Entry<List<Object>, List<Map<String, Object>>> entry : groups.entrySet()) {
            Map<String, Object> outRow = new LinkedHashMap<>();
            List<Object> keyVals = entry.getKey();
            for (int i = 0; i < groupCols.size(); i++) {
                outRow.put(groupCols.get(i), keyVals.get(i));
            }
            List<Map<String, Object>> bucketRows = entry.getValue();
            for (RQueryStep.AggregateDef agg : aggs) {
                double val = switch (agg.function()) {
                    case COUNT -> bucketRows.size();
                    case SUM -> bucketRows.stream().mapToDouble(r -> toDouble(r.get(agg.sourceColumn()))).sum();
                    case AVG -> bucketRows.stream().mapToDouble(r -> toDouble(r.get(agg.sourceColumn()))).average().orElse(0.0);
                    case MIN -> bucketRows.stream().mapToDouble(r -> toDouble(r.get(agg.sourceColumn()))).min().orElse(0.0);
                    case MAX -> bucketRows.stream().mapToDouble(r -> toDouble(r.get(agg.sourceColumn()))).max().orElse(0.0);
                };
                outRow.put(agg.outputColumn(), val);
            }
            aggregatedRows.add(outRow);
        }
        return new RecordBatch(batch.batchIndex(), newSchema, aggregatedRows, batch.quarantinedRecords());
    }

    private RecordBatch applyMergeQueries(
            RecordBatch batch,
            RQueryStep step,
            Map<String, List<Map<String, Object>>> dimensionLookupTables) {
        if (dimensionLookupTables == null || !dimensionLookupTables.containsKey(step.targetTable())) {
            return batch;
        }
        List<Map<String, Object>> rightRows = dimensionLookupTables.get(step.targetTable());
        List<String> joinKeys = step.joinKeys() != null ? step.joinKeys() : List.of();

        // Support both Expand (1-to-1 lookup) and Aggregate (1-to-many detail rollup as in Power Query Merge -> Aggregate)
        if (step.aggregations() != null && !step.aggregations().isEmpty()) {
            Map<List<Object>, List<Map<String, Object>>> rightGroups = new LinkedHashMap<>();
            for (Map<String, Object> rRow : rightRows) {
                List<Object> key = joinKeys.stream().map(rRow::get).toList();
                rightGroups.computeIfAbsent(key, k -> new ArrayList<>()).add(rRow);
            }
            Map<String, DataType> newSchema = new LinkedHashMap<>(batch.schema());
            for (RQueryStep.AggregateDef agg : step.aggregations()) {
                newSchema.put(agg.outputColumn(), DataType.FLOAT64);
            }
            List<Map<String, Object>> joined = new ArrayList<>(batch.rows().size());
            for (Map<String, Object> leftRow : batch.rows()) {
                List<Object> key = joinKeys.stream().map(leftRow::get).toList();
                List<Map<String, Object>> matches = rightGroups.getOrDefault(key, List.of());
                Map<String, Object> merged = new LinkedHashMap<>(leftRow);
                for (RQueryStep.AggregateDef agg : step.aggregations()) {
                    double val = switch (agg.function()) {
                        case COUNT -> matches.size();
                        case SUM -> matches.stream().mapToDouble(r -> toDouble(r.get(agg.sourceColumn()))).sum();
                        case AVG -> matches.stream().mapToDouble(r -> toDouble(r.get(agg.sourceColumn()))).average().orElse(0.0);
                        case MIN -> matches.stream().mapToDouble(r -> toDouble(r.get(agg.sourceColumn()))).min().orElse(0.0);
                        case MAX -> matches.stream().mapToDouble(r -> toDouble(r.get(agg.sourceColumn()))).max().orElse(0.0);
                    };
                    merged.put(agg.outputColumn(), val);
                }
                joined.add(merged);
            }
            return new RecordBatch(batch.batchIndex(), newSchema, joined, batch.quarantinedRecords());
        }

        Map<List<Object>, Map<String, Object>> rightIndex = new LinkedHashMap<>();
        for (Map<String, Object> rRow : rightRows) {
            List<Object> key = joinKeys.stream().map(rRow::get).toList();
            rightIndex.put(key, rRow);
        }

        Map<String, DataType> newSchema = new LinkedHashMap<>(batch.schema());
        if (!rightRows.isEmpty()) {
            for (String rCol : rightRows.getFirst().keySet()) {
                newSchema.putIfAbsent(rCol, DataType.UTF8_STRING);
            }
        }

        List<Map<String, Object>> joined = new ArrayList<>();
        for (Map<String, Object> leftRow : batch.rows()) {
            List<Object> key = joinKeys.stream().map(leftRow::get).toList();
            Map<String, Object> match = rightIndex.get(key);
            if (match != null) {
                Map<String, Object> merged = new LinkedHashMap<>(leftRow);
                match.forEach(merged::putIfAbsent);
                joined.add(merged);
            } else if (step.joinType() == RQueryStep.JoinType.LEFT || step.joinType() == RQueryStep.JoinType.FULL) {
                joined.add(new LinkedHashMap<>(leftRow));
            }
        }
        return new RecordBatch(batch.batchIndex(), newSchema, joined, batch.quarantinedRecords());
    }

    private RecordBatch applyAddCustomColumn(
            RecordBatch batch,
            RQueryStep step,
            String tenantId,
            String datasetId) {
        DataType colType = step.type() != null ? step.type() : DataType.FLOAT64;
        Map<String, DataType> newSchema = new LinkedHashMap<>(batch.schema());
        newSchema.put(step.column(), colType);

        List<Map<String, Object>> validRows = new ArrayList<>(batch.rows().size());
        List<QuarantineRecord> quarantined = new ArrayList<>(batch.quarantinedRecords());
        boolean quarantineOnError = "quarantine".equalsIgnoreCase(step.onError());

        long rowIdx = 0;
        for (Map<String, Object> row : batch.rows()) {
            rowIdx++;
            Map<String, Object> copy = new LinkedHashMap<>(row);
            try {
                Object computed = evaluateCustomColumnExpression(row, step.expression(), colType);
                copy.put(step.column(), computed);
                validRows.add(copy);
            } catch (Exception ex) {
                if (quarantineOnError) {
                    quarantined.add(new QuarantineRecord(
                            tenantId,
                            datasetId,
                            rowIdx,
                            step.column(),
                            newSchema.get(step.column()).name(),
                            row.toString(),
                            "AddCustomColumnStep error on expression '" + step.expression() + "': " + ex.getMessage()
                    ));
                } else {
                    copy.put(step.column(), null);
                    validRows.add(copy);
                }
            }
        }
        return new RecordBatch(batch.batchIndex(), newSchema, validRows, quarantined);
    }

    private Object evaluateCustomColumnExpression(Map<String, Object> row, String expression, DataType targetType) {
        if (expression == null || expression.isBlank()) {
            return targetType == DataType.UTF8_STRING ? "" : 0.0;
        }
        String trimmed = expression.trim();

        // 1. Power Query M Text.Select([col], {"0".."9"}) digit extraction
        if (trimmed.contains("Text.Select")) {
            Matcher m = BRACKET_COL_PATTERN.matcher(trimmed);
            if (m.find()) {
                Object raw = row.get(m.group(1));
                return raw == null ? "" : raw.toString().replaceAll("[^0-9]", "");
            }
        }

        // 2. Conditional Column: if [colA] == "val" then [colB] else null
        if (trimmed.toLowerCase().startsWith("if ")) {
            Pattern condPattern = Pattern.compile(
                    "(?i)^if\\s+(.+?)\\s+then\\s+(.+?)(?:\\s+else\\s+(.+))?$");
            Matcher cm = condPattern.matcher(trimmed);
            if (cm.matches()) {
                String condPart = cm.group(1).trim();
                String thenPart = cm.group(2).trim();
                String elsePart = cm.group(3) != null ? cm.group(3).trim() : "null";
                boolean matched = evaluateRowPredicate(row, condPart);
                String chosen = matched ? thenPart : elsePart;
                return resolveTokenValue(row, chosen);
            }
        }

        // 3. String concatenation with '&'
        if (trimmed.contains("&") || targetType == DataType.UTF8_STRING) {
            if (trimmed.contains("&")) {
                String[] parts = trimmed.split("&");
                StringBuilder sb = new StringBuilder();
                for (String p : parts) {
                    Object v = resolveTokenValue(row, p.trim());
                    if (v != null) {
                        sb.append(v);
                    }
                }
                return sb.toString();
            }
        }

        return evaluateRowArithmeticExpression(row, trimmed);
    }

    private Object resolveTokenValue(Map<String, Object> row, String token) {
        if (token == null || "null".equalsIgnoreCase(token)) {
            return null;
        }
        Matcher m = BRACKET_COL_PATTERN.matcher(token);
        if (m.matches()) {
            return row.get(m.group(1));
        }
        return token.replaceAll("^[\"']|[\"']$", "");
    }

    private RecordBatch applyAddIndexColumn(RecordBatch batch, RQueryStep step) {
        String colName = step.column() != null && !step.column().isBlank() ? step.column() : "Index";
        int initial = 1;
        int increment = 1;
        if (step.parameters() != null) {
            if (step.parameters().get("initialValue") instanceof Number n) initial = n.intValue();
            if (step.parameters().get("increment") instanceof Number n) increment = n.intValue();
        }
        Map<String, DataType> newSchema = new LinkedHashMap<>(batch.schema());
        newSchema.put(colName, DataType.INT64);

        List<Map<String, Object>> nextRows = new ArrayList<>(batch.rows().size());
        long currentIdx = initial;
        for (Map<String, Object> row : batch.rows()) {
            Map<String, Object> copy = new LinkedHashMap<>(row);
            copy.put(colName, currentIdx);
            nextRows.add(copy);
            currentIdx += increment;
        }
        return new RecordBatch(batch.batchIndex(), newSchema, nextRows, batch.quarantinedRecords());
    }

    private RecordBatch applyFillDown(RecordBatch batch, RQueryStep step) {
        List<String> targetCols = step.groupColumns() != null && !step.groupColumns().isEmpty()
                ? step.groupColumns()
                : (step.column() != null ? List.of(step.column()) : List.copyOf(batch.schema().keySet()));

        Map<String, Object> lastNonNull = new LinkedHashMap<>();
        List<Map<String, Object>> filledRows = new ArrayList<>(batch.rows().size());
        for (Map<String, Object> row : batch.rows()) {
            Map<String, Object> copy = new LinkedHashMap<>(row);
            for (String col : targetCols) {
                Object val = copy.get(col);
                if (val != null && !val.toString().isBlank()) {
                    lastNonNull.put(col, val);
                } else if (lastNonNull.containsKey(col)) {
                    copy.put(col, lastNonNull.get(col));
                }
            }
            filledRows.add(copy);
        }
        return new RecordBatch(batch.batchIndex(), batch.schema(), filledRows, batch.quarantinedRecords());
    }

    private RecordBatch applyTranspose(RecordBatch batch) {
        List<String> origCols = List.copyOf(batch.schema().keySet());
        List<Map<String, Object>> origRows = batch.rows();
        if (origCols.isEmpty()) {
            return batch;
        }

        int newColCount = origRows.size() + 1;
        Map<String, DataType> newSchema = new LinkedHashMap<>();
        for (int c = 1; c <= newColCount; c++) {
            newSchema.put("Column" + c, DataType.UTF8_STRING);
        }

        List<Map<String, Object>> transposedRows = new ArrayList<>(origCols.size());
        for (String colName : origCols) {
            Map<String, Object> tRow = new LinkedHashMap<>();
            tRow.put("Column1", colName);
            for (int r = 0; r < origRows.size(); r++) {
                tRow.put("Column" + (r + 2), origRows.get(r).get(colName));
            }
            transposedRows.add(tRow);
        }
        return new RecordBatch(batch.batchIndex(), newSchema, transposedRows, batch.quarantinedRecords());
    }

    private RecordBatch applyPromoteHeaders(RecordBatch batch, String tenantId, String datasetId) {
        if (batch.rows().isEmpty()) {
            return batch;
        }
        Map<String, Object> firstRow = batch.rows().getFirst();
        List<String> oldCols = List.copyOf(batch.schema().keySet());
        Map<String, String> colMap = new LinkedHashMap<>();
        for (int i = 0; i < oldCols.size(); i++) {
            String oldCol = oldCols.get(i);
            Object rawHeader = firstRow.get(oldCol);
            String promoted = (rawHeader != null && !rawHeader.toString().isBlank())
                    ? rawHeader.toString().trim()
                    : "Column" + (i + 1);
            colMap.put(oldCol, promoted);
        }

        List<Map<String, Object>> remainingRows = new ArrayList<>();
        for (int r = 1; r < batch.rows().size(); r++) {
            Map<String, Object> srcRow = batch.rows().get(r);
            Map<String, Object> mappedRow = new LinkedHashMap<>();
            for (Map.Entry<String, String> entry : colMap.entrySet()) {
                mappedRow.put(entry.getValue(), srcRow.get(entry.getKey()));
            }
            remainingRows.add(mappedRow);
        }

        Map<String, DataType> inferredSchema = typeInferenceEngine.inferSchemaFromSample(remainingRows, Map.of());
        for (String newCol : colMap.values()) {
            inferredSchema.putIfAbsent(newCol, DataType.UTF8_STRING);
        }
        return new RecordBatch(batch.batchIndex(), inferredSchema, remainingRows, batch.quarantinedRecords());
    }

    private RecordBatch applyRemoveTopRows(RecordBatch batch, RQueryStep step) {
        int count = 1;
        if (step.parameters() != null && step.parameters().get("count") instanceof Number n) {
            count = Math.max(0, n.intValue());
        }
        List<Map<String, Object>> rows = batch.rows();
        List<Map<String, Object>> sliced = count >= rows.size() ? List.of() : rows.subList(count, rows.size());
        return new RecordBatch(batch.batchIndex(), batch.schema(), sliced, batch.quarantinedRecords());
    }

    private RecordBatch applyRemoveBottomRows(RecordBatch batch, RQueryStep step) {
        int count = 1;
        if (step.parameters() != null && step.parameters().get("count") instanceof Number n) {
            count = Math.max(0, n.intValue());
        }
        List<Map<String, Object>> rows = batch.rows();
        List<Map<String, Object>> sliced = count >= rows.size() ? List.of() : rows.subList(0, rows.size() - count);
        return new RecordBatch(batch.batchIndex(), batch.schema(), sliced, batch.quarantinedRecords());
    }

    private RecordBatch applySplitColumn(RecordBatch batch, RQueryStep step) {
        String targetCol = step.column();
        if (targetCol == null || !batch.schema().containsKey(targetCol)) {
            return batch;
        }
        String delimiter = step.parameters() != null && step.parameters().get("delimiter") != null
                ? step.parameters().get("delimiter").toString()
                : ":";

        int maxParts = 2;
        for (Map<String, Object> row : batch.rows()) {
            Object val = row.get(targetCol);
            if (val != null) {
                int parts = val.toString().split(Pattern.quote(delimiter), -1).length;
                if (parts > maxParts) maxParts = parts;
            }
        }

        Map<String, DataType> newSchema = new LinkedHashMap<>();
        for (Map.Entry<String, DataType> entry : batch.schema().entrySet()) {
            if (entry.getKey().equals(targetCol)) {
                for (int i = 1; i <= maxParts; i++) {
                    newSchema.put(targetCol + "." + i, DataType.UTF8_STRING);
                }
            } else {
                newSchema.put(entry.getKey(), entry.getValue());
            }
        }

        List<Map<String, Object>> splitRows = new ArrayList<>(batch.rows().size());
        for (Map<String, Object> row : batch.rows()) {
            Map<String, Object> outRow = new LinkedHashMap<>();
            for (String col : batch.schema().keySet()) {
                if (col.equals(targetCol)) {
                    Object val = row.get(targetCol);
                    String[] parts = val != null ? val.toString().split(Pattern.quote(delimiter), -1) : new String[0];
                    for (int i = 0; i < maxParts; i++) {
                        outRow.put(targetCol + "." + (i + 1), i < parts.length ? parts[i].trim() : null);
                    }
                } else {
                    outRow.put(col, row.get(col));
                }
            }
            splitRows.add(outRow);
        }
        return new RecordBatch(batch.batchIndex(), newSchema, splitRows, batch.quarantinedRecords());
    }

    private RecordBatch applyMergeColumns(RecordBatch batch, RQueryStep step) {
        List<String> colsToMerge = step.groupColumns() != null ? step.groupColumns() : List.of();
        if (colsToMerge.size() < 2) {
            return batch;
        }
        String separator = step.parameters() != null && step.parameters().get("separator") != null
                ? step.parameters().get("separator").toString()
                : ":";
        String mergedColName = step.newName() != null && !step.newName().isBlank() ? step.newName() : "Merged";
        Set<String> mergeSet = new LinkedHashSet<>(colsToMerge);
        String firstCol = colsToMerge.getFirst();

        Map<String, DataType> newSchema = new LinkedHashMap<>();
        for (Map.Entry<String, DataType> entry : batch.schema().entrySet()) {
            if (entry.getKey().equals(firstCol)) {
                newSchema.put(mergedColName, DataType.UTF8_STRING);
            } else if (!mergeSet.contains(entry.getKey())) {
                newSchema.put(entry.getKey(), entry.getValue());
            }
        }

        List<Map<String, Object>> mergedRows = new ArrayList<>(batch.rows().size());
        for (Map<String, Object> row : batch.rows()) {
            Map<String, Object> outRow = new LinkedHashMap<>();
            String joinedVal = colsToMerge.stream()
                    .map(c -> row.get(c) != null ? row.get(c).toString() : "")
                    .reduce((a, b) -> a + separator + b)
                    .orElse("");
            for (String col : batch.schema().keySet()) {
                if (col.equals(firstCol)) {
                    outRow.put(mergedColName, joinedVal);
                } else if (!mergeSet.contains(col)) {
                    outRow.put(col, row.get(col));
                }
            }
            mergedRows.add(outRow);
        }
        return new RecordBatch(batch.batchIndex(), newSchema, mergedRows, batch.quarantinedRecords());
    }

    private RecordBatch applyRemoveDuplicates(RecordBatch batch, RQueryStep step) {
        List<String> keyCols = step.groupColumns() != null && !step.groupColumns().isEmpty()
                ? step.groupColumns()
                : List.copyOf(batch.schema().keySet());

        Set<List<Object>> seen = new LinkedHashSet<>();
        List<Map<String, Object>> uniqueRows = new ArrayList<>();
        for (Map<String, Object> row : batch.rows()) {
            List<Object> signature = keyCols.stream().map(row::get).toList();
            if (seen.add(signature)) {
                uniqueRows.add(row);
            }
        }
        return new RecordBatch(batch.batchIndex(), batch.schema(), uniqueRows, batch.quarantinedRecords());
    }

    private RecordBatch applySortRows(RecordBatch batch, RQueryStep step) {
        String sortCol = step.column();
        if (sortCol == null || !batch.schema().containsKey(sortCol)) {
            return batch;
        }
        boolean desc = "DESC".equalsIgnoreCase(step.expression());
        List<Map<String, Object>> sorted = new ArrayList<>(batch.rows());
        Comparator<Map<String, Object>> cmp = (a, b) -> {
            Object va = a.get(sortCol);
            Object vb = b.get(sortCol);
            if (va == null && vb == null) return 0;
            if (va == null) return 1;
            if (vb == null) return -1;
            if (va instanceof Number na && vb instanceof Number nb) {
                return Double.compare(na.doubleValue(), nb.doubleValue());
            }
            return va.toString().compareToIgnoreCase(vb.toString());
        };
        sorted.sort(desc ? cmp.reversed() : cmp);
        return new RecordBatch(batch.batchIndex(), batch.schema(), sorted, batch.quarantinedRecords());
    }

    private double evaluateRowArithmeticExpression(Map<String, Object> row, String expression) {
        if (expression == null || expression.isBlank()) {
            return 0.0;
        }
        Matcher matcher = BRACKET_COL_PATTERN.matcher(expression);
        List<Double> operands = new ArrayList<>();
        while (matcher.find()) {
            String colName = matcher.group(1);
            Object val = row.get(colName);
            if (val == null) {
                throw new IllegalArgumentException("Null operand for column [" + colName + "]");
            }
            operands.add(toDouble(val));
        }

        if (operands.size() >= 2) {
            double left = operands.get(0);
            double right = operands.get(1);
            if (expression.contains("/")) {
                if (Math.abs(right) < 1e-12) {
                    throw new ArithmeticException("Division by zero in R-Query custom column expression");
                }
                return left / right;
            }
            if (expression.contains("*")) return left * right;
            if (expression.contains("-")) return left - right;
            if (expression.contains("+")) return left + right;
        }
        return operands.isEmpty() ? 0.0 : operands.getFirst();
    }

    private boolean evaluateRowPredicate(Map<String, Object> row, String predicate) {
        if (predicate == null || predicate.isBlank()) {
            return true;
        }
        // Compound 'and' support (e.g. incremental refresh: [date] >= '2025-01-01' and [date] < '2025-04-01')
        if (predicate.toLowerCase().contains(" and ")) {
            String[] clauses = predicate.split("(?i)\\s+and\\s+");
            for (String clause : clauses) {
                if (!evaluateSingleClause(row, clause.trim())) {
                    return false;
                }
            }
            return true;
        }
        return evaluateSingleClause(row, predicate.trim());
    }

    private boolean evaluateSingleClause(Map<String, Object> row, String clause) {
        if (clause.contains("!=" ) || clause.contains("<>")) {
            String[] parts = clause.split("!=|<>", 2);
            String col = parts[0].replace("[", "").replace("]", "").trim();
            String expected = parts[1].replace("\"", "").replace("'", "").trim();
            Object actual = row.get(col);
            if ("null".equalsIgnoreCase(expected) || expected.isEmpty()) {
                return actual != null && !actual.toString().isBlank();
            }
            return actual != null && !actual.toString().equalsIgnoreCase(expected);
        }
        if (clause.contains(">=")) {
            String[] parts = clause.split(">=", 2);
            String col = parts[0].replace("[", "").replace("]", "").trim();
            String rhs = parts[1].replace("\"", "").replace("'", "").trim();
            return compareValues(row.get(col), rhs) >= 0;
        }
        if (clause.contains("<=")) {
            String[] parts = clause.split("<=", 2);
            String col = parts[0].replace("[", "").replace("]", "").trim();
            String rhs = parts[1].replace("\"", "").replace("'", "").trim();
            return compareValues(row.get(col), rhs) <= 0;
        }
        if (clause.contains("==")) {
            String[] parts = clause.split("==", 2);
            String col = parts[0].replace("[", "").replace("]", "").trim();
            String expected = parts[1].replace("\"", "").replace("'", "").trim();
            Object actual = row.get(col);
            return actual != null && Objects.equals(actual.toString(), expected);
        }
        if (clause.contains(">")) {
            String[] parts = clause.split(">", 2);
            String col = parts[0].replace("[", "").replace("]", "").trim();
            String rhs = parts[1].replace("\"", "").replace("'", "").trim();
            return compareValues(row.get(col), rhs) > 0;
        }
        if (clause.contains("<")) {
            String[] parts = clause.split("<", 2);
            String col = parts[0].replace("[", "").replace("]", "").trim();
            String rhs = parts[1].replace("\"", "").replace("'", "").trim();
            return compareValues(row.get(col), rhs) < 0;
        }
        return true;
    }

    private int compareValues(Object actual, String rhs) {
        if (actual == null) return -1;
        try {
            double leftNum = toDouble(actual);
            double rightNum = Double.parseDouble(rhs);
            return Double.compare(leftNum, rightNum);
        } catch (NumberFormatException ignored) {
            return actual.toString().compareTo(rhs);
        }
    }

    private double toDouble(Object val) {
        if (val == null) return 0.0;
        if (val instanceof Number n) return n.doubleValue();
        return Double.parseDouble(val.toString().replaceAll("[€$£,\\s]", ""));
    }
}
