package com.rollercoaster.ingestion.rquery;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.rollercoaster.ingestion.DataType;
import com.rollercoaster.ingestion.SourceFormat;

import java.util.List;
import java.util.Map;

/**
 * Represents a serialized step in an R-Query transformation pipeline (queries.rq.json).
 * Supports all 8 canonical step types:
 * Source, ChangeTypeStep, FilterRowsStep, RenameColumnStep, UnpivotColumnsStep,
 * GroupRowsStep, MergeQueriesStep, and AddCustomColumnStep.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record RQueryStep(
        String step,
        String input,
        String uri,
        SourceFormat format,
        String column,
        DataType type,
        String oldName,
        String newName,
        String predicate,
        List<String> pivotColumns,
        String nameCol,
        String valCol,
        List<String> groupColumns,
        List<AggregateDef> aggregations,
        List<String> joinKeys,
        String targetTable,
        JoinType joinType,
        String expression,
        Map<String, Object> parameters,
        String onError
) {
    public record AggregateDef(String outputColumn, String sourceColumn, AggregationFunction function) {}

    public enum AggregationFunction {
        SUM, AVG, MIN, MAX, COUNT
    }

    public enum JoinType {
        INNER, LEFT, RIGHT, FULL
    }

    public static RQueryStep changeType(String input, String column, DataType targetType) {
        return new RQueryStep(
                "ChangeTypeStep", input, null, null, column, targetType,
                null, null, null, null, null, null, null, null, null, null, null, null, null, "quarantine");
    }

    public static RQueryStep filterRows(String input, String predicate) {
        return new RQueryStep(
                "FilterRowsStep", input, null, null, null, null,
                null, null, predicate, null, null, null, null, null, null, null, null, null, null, null);
    }

    public static RQueryStep renameColumn(String input, String oldName, String newName) {
        return new RQueryStep(
                "RenameColumnStep", input, null, null, null, null,
                oldName, newName, null, null, null, null, null, null, null, null, null, null, null, null);
    }

    public static RQueryStep unpivotColumns(String input, List<String> pivotColumns, String nameCol, String valCol) {
        return new RQueryStep(
                "UnpivotColumnsStep", input, null, null, null, null,
                null, null, null, pivotColumns, nameCol, valCol, null, null, null, null, null, null, null, null);
    }

    public static RQueryStep groupRows(String input, List<String> groupColumns, List<AggregateDef> aggregations) {
        return new RQueryStep(
                "GroupRowsStep", input, null, null, null, null,
                null, null, null, null, null, null, groupColumns, aggregations, null, null, null, null, null, null);
    }

    public static RQueryStep mergeQueries(String input, List<String> joinKeys, String targetTable, JoinType joinType) {
        return new RQueryStep(
                "MergeQueriesStep", input, null, null, null, null,
                null, null, null, null, null, null, null, null, joinKeys, targetTable, joinType, null, null, null);
    }

    public static RQueryStep addCustomColumn(
            String input,
            String column,
            DataType type,
            String expression,
            Map<String, Object> parameters,
            String onError) {
        return new RQueryStep(
                "AddCustomColumnStep", input, null, null, column, type,
                null, null, null, null, null, null, null, null, null, null, null, expression, parameters, onError);
    }

    public static RQueryStep removeColumn(String input, String column) {
        return new RQueryStep(
                "RemoveColumnStep", input, null, null, column, null,
                null, null, null, null, null, null, null, null, null, null, null, null, null, null);
    }

    public static RQueryStep removeOtherColumns(String input, List<String> keptColumns) {
        return new RQueryStep(
                "RemoveOtherColumnsStep", input, null, null, null, null,
                null, null, null, null, null, null, keptColumns, null, null, null, null, null, null, null);
    }

    public static RQueryStep fillDown(String input, List<String> columns) {
        return new RQueryStep(
                "FillDownStep", input, null, null, columns != null && !columns.isEmpty() ? columns.getFirst() : null, null,
                null, null, null, null, null, null, columns, null, null, null, null, null, null, null);
    }

    public static RQueryStep transpose(String input) {
        return new RQueryStep(
                "TransposeStep", input, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null, null, null, null, null);
    }

    public static RQueryStep promoteHeaders(String input) {
        return new RQueryStep(
                "PromoteHeadersStep", input, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null, null, null, null, null);
    }

    public static RQueryStep removeTopRows(String input, int count) {
        return new RQueryStep(
                "RemoveTopRowsStep", input, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null, null, null, Map.of("count", count), null);
    }

    public static RQueryStep removeBottomRows(String input, int count) {
        return new RQueryStep(
                "RemoveBottomRowsStep", input, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null, null, null, Map.of("count", count), null);
    }

    public static RQueryStep splitColumnByDelimiter(String input, String column, String delimiter) {
        return new RQueryStep(
                "SplitColumnStep", input, null, null, column, null,
                null, null, null, null, null, null, null, null, null, null, null, null,
                Map.of("delimiter", delimiter != null ? delimiter : ":"), null);
    }

    public static RQueryStep mergeColumns(String input, List<String> columns, String separator, String newColumnName) {
        return new RQueryStep(
                "MergeColumnsStep", input, null, null, null, null,
                null, newColumnName, null, null, null, null, columns, null, null, null, null, null,
                Map.of("separator", separator != null ? separator : ":"), null);
    }

    public static RQueryStep removeDuplicates(String input, List<String> keyColumns) {
        return new RQueryStep(
                "RemoveDuplicatesStep", input, null, null, null, null,
                null, null, null, null, null, null, keyColumns, null, null, null, null, null, null, null);
    }

    public static RQueryStep addIndexColumn(String input, String columnName, int initialValue, int increment) {
        return new RQueryStep(
                "AddIndexColumnStep", input, null, null, columnName != null ? columnName : "Index", DataType.INT64,
                null, null, null, null, null, null, null, null, null, null, null, null,
                Map.of("initialValue", initialValue, "increment", increment), null);
    }

    public static RQueryStep transformColumnNames(String input, String mode, String searchOrAffix, String replacement) {
        return new RQueryStep(
                "TransformColumnNamesStep", input, null, null, null, null,
                searchOrAffix, replacement, null, null, null, null, null, null, null, null, null, mode, null, null);
    }
}
