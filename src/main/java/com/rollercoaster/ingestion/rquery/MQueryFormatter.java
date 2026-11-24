package com.rollercoaster.ingestion.rquery;

import com.rollercoaster.ingestion.DataType;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * Generates Power Query M ({@code let ... in ...}) scripts and single-step formulas
 * from R-Query transformation pipelines.
 */
@Component
public class MQueryFormatter {

    private static final String DEFAULT_SOURCE_NAME = "Source";
    private static final String DEFAULT_TABLE_NAME = "Dataset";

    public record MQueryFormatOptions(String tableName, String rangeStart, String rangeEnd) {
        public MQueryFormatOptions {
            tableName = (tableName != null && !tableName.isBlank()) ? tableName.trim() : DEFAULT_TABLE_NAME;
        }

        public static MQueryFormatOptions defaults() {
            return new MQueryFormatOptions(DEFAULT_TABLE_NAME, null, null);
        }

        public static MQueryFormatOptions ofTable(String tableName) {
            return new MQueryFormatOptions(tableName, null, null);
        }
    }

    public String formatScript(List<RQueryStep> steps) {
        return formatScript(steps, MQueryFormatOptions.defaults());
    }

    public String formatScript(List<RQueryStep> steps, String rangeStart, String rangeEnd) {
        return formatScript(steps, new MQueryFormatOptions(DEFAULT_TABLE_NAME, rangeStart, rangeEnd));
    }

    public String formatScript(List<RQueryStep> steps, MQueryFormatOptions options) {
        Objects.requireNonNull(options, "options must not be null");
        List<RQueryStep> safeSteps = steps != null ? steps : List.of();
        List<String> lines = new ArrayList<>();

        appendRangeParameters(lines, options.rangeStart(), options.rangeEnd());

        if (safeSteps.isEmpty()) {
            String sourceIdentifier = formatStepIdentifier(DEFAULT_SOURCE_NAME);
            lines.add("    " + sourceIdentifier + " = Table.FromRecords({})");
            return "let\n" + String.join(",\n", lines) + "\nin\n    " + sourceIdentifier;
        }

        List<String> stepIds = resolveUniqueStepIdentifiers(safeSteps);
        String previousStepRef = DEFAULT_SOURCE_NAME;
        for (int i = 0; i < safeSteps.size(); i++) {
            RQueryStep step = safeSteps.get(i);
            String stepId = stepIds.get(i);
            String rhs = formatStepExpression(step, previousStepRef);
            lines.add("    " + stepId + " = " + rhs);
            previousStepRef = stepId;
        }

        return "let\n" + String.join(",\n", lines) + "\nin\n    " + previousStepRef;
    }

    public String formatStepFormula(RQueryStep step) {
        return formatStepFormula(step, DEFAULT_SOURCE_NAME);
    }

    public String formatStepFormula(RQueryStep step, String previousStepName) {
        if (step == null) {
            return "= Table.FromRecords({})";
        }
        String prevId = formatStepIdentifier(
                (previousStepName != null && !previousStepName.isBlank()) ? previousStepName : DEFAULT_SOURCE_NAME);
        return "= " + formatStepExpression(step, prevId);
    }

    /**
     * Formats the single-line Power Query M formula for the final step of {@code steps},
     * referencing the exact disambiguated identifier of the preceding step in the {@code let} block.
     */
    public String formatLastStepFormula(List<RQueryStep> steps) {
        if (steps == null || steps.isEmpty()) {
            return "= Table.FromRecords({})";
        }
        List<String> stepIds = resolveUniqueStepIdentifiers(steps);
        String prevId = steps.size() >= 2 ? stepIds.get(steps.size() - 2) : DEFAULT_SOURCE_NAME;
        return "= " + formatStepExpression(steps.getLast(), prevId);
    }

    private List<String> resolveUniqueStepIdentifiers(List<RQueryStep> steps) {
        Map<String, Integer> counts = new HashMap<>();
        List<String> identifiers = new ArrayList<>(steps.size());
        for (int i = 0; i < steps.size(); i++) {
            String baseLabel = resolveStepLabel(steps.get(i), i);
            int seen = counts.getOrDefault(baseLabel, 0);
            counts.put(baseLabel, seen + 1);
            String uniqueLabel = seen == 0 ? baseLabel : baseLabel + seen;
            identifiers.add(formatStepIdentifier(uniqueLabel));
        }
        return identifiers;
    }

    private void appendRangeParameters(List<String> lines, String rangeStart, String rangeEnd) {
        if (rangeStart != null && !rangeStart.isBlank()) {
            lines.add("    RangeStart = \"" + escapeString(rangeStart.trim()) + "\"");
        }
        if (rangeEnd != null && !rangeEnd.isBlank()) {
            lines.add("    RangeEnd = \"" + escapeString(rangeEnd.trim()) + "\"");
        }
    }

    private String resolveStepLabel(RQueryStep step, int index) {
        if (step == null) {
            return "Step" + (index + 1);
        }
        if (step.input() != null && !step.input().isBlank() && isMetadataStep(step.step())) {
            return step.step();
        }
        String raw = step.step() != null && !step.step().isBlank() ? step.step().trim() : "Step" + (index + 1);
        return switch (normalizeStepKind(raw)) {
            case "FILESOURCE", "SOURCE" -> "Source";
            case "NAVIGATION" -> "Navigation";
            case "PROMOTEHEADERS" -> "Promoted Headers";
            case "CHANGETYPE" -> "Changed Type";
            case "DETECTDATATYPE" -> "Detected Data Types";
            case "FILTERROWS" -> "Filtered Rows";
            case "FILTERNULLS" -> "Removed Blank Rows";
            case "RENAMECOLUMN" -> "Renamed Columns";
            case "TRANSFORMCOLUMNNAMES" -> "Transformed Column Names";
            case "REMOVECOLUMN" -> "Removed Columns";
            case "REMOVEOTHERCOLUMNS" -> "Removed Other Columns";
            case "UNPIVOTCOLUMNS" -> "Unpivoted Columns";
            case "UNPIVOTOTHERCOLUMNS" -> "Unpivoted Other Columns";
            case "GROUPROWS" -> "Grouped Rows";
            case "MERGEQUERIES" -> "Merged Queries";
            case "ADDCUSTOMCOLUMN" -> "Added Custom";
            case "ADDINDEXCOLUMN" -> "Added Index";
            case "FILLDOWN" -> "Filled Down";
            case "TRANSPOSE" -> "Transposed Table";
            case "REMOVETOPROWS" -> "Removed Top Rows";
            case "REMOVEBOTTOMROWS" -> "Removed Bottom Rows";
            case "SPLITCOLUMN" -> "Split Column by Delimiter";
            case "MERGECOLUMNS" -> "Merged Columns";
            case "REMOVEDUPLICATES" -> "Removed Duplicates";
            case "SORTROWS" -> "Sorted Rows";
            case "INLINECLEANSING" -> "Cleaned Column";
            default -> raw;
        };
    }

    private boolean isMetadataStep(String stepType) {
        if (stepType == null) {
            return false;
        }
        String norm = normalizeStepKind(stepType);
        return "FILESOURCE".equals(norm) || "SOURCE".equals(norm) || "NAVIGATION".equals(norm);
    }

    private String formatStepExpression(RQueryStep step, String prevStepId) {
        if (step == null || step.step() == null) {
            return prevStepId;
        }
        return switch (normalizeStepKind(step.step())) {
            case "FILESOURCE", "SOURCE" -> formatFileSource(step);
            case "NAVIGATION" -> formatNavigation(step, prevStepId);
            case "PROMOTEHEADERS" -> "Table.PromoteHeaders(" + prevStepId + ", [PromoteAllScalars=true])";
            case "CHANGETYPE" -> formatChangeType(step, prevStepId);
            case "DETECTDATATYPE" -> "Table.TransformColumnTypes(" + prevStepId + ", {})";
            case "FILTERROWS" -> formatFilterRows(step, prevStepId);
            case "FILTERNULLS" -> formatFilterNulls(step, prevStepId);
            case "RENAMECOLUMN" -> formatRenameColumn(step, prevStepId);
            case "TRANSFORMCOLUMNNAMES" -> formatTransformColumnNames(step, prevStepId);
            case "REMOVECOLUMN" -> formatRemoveColumn(step, prevStepId);
            case "REMOVEOTHERCOLUMNS" -> formatRemoveOtherColumns(step, prevStepId);
            case "UNPIVOTCOLUMNS" -> formatUnpivotColumns(step, prevStepId, false);
            case "UNPIVOTOTHERCOLUMNS" -> formatUnpivotColumns(step, prevStepId, true);
            case "GROUPROWS" -> formatGroupRows(step, prevStepId);
            case "MERGEQUERIES" -> formatMergeQueries(step, prevStepId);
            case "ADDCUSTOMCOLUMN" -> formatAddCustomColumn(step, prevStepId);
            case "ADDINDEXCOLUMN" -> formatAddIndexColumn(step, prevStepId);
            case "FILLDOWN" -> formatFillDown(step, prevStepId);
            case "TRANSPOSE" -> "Table.Transpose(" + prevStepId + ")";
            case "REMOVETOPROWS" -> "Table.Skip(" + prevStepId + ", " + extractCountParam(step) + ")";
            case "REMOVEBOTTOMROWS" -> "Table.RemoveLastN(" + prevStepId + ", " + extractCountParam(step) + ")";
            case "SPLITCOLUMN" -> formatSplitColumn(step, prevStepId);
            case "MERGECOLUMNS" -> formatMergeColumns(step, prevStepId);
            case "REMOVEDUPLICATES" -> formatRemoveDuplicates(step, prevStepId);
            case "SORTROWS" -> formatSortRows(step, prevStepId);
            case "INLINECLEANSING" -> formatInlineCleansing(step, prevStepId);
            default -> prevStepId;
        };
    }

    private String formatFileSource(RQueryStep step) {
        String uri = step.uri() != null && !step.uri().isBlank() ? step.uri().trim() : DEFAULT_TABLE_NAME;
        if (step.format() != null) {
            return switch (step.format()) {
                case XLSX -> "Excel.Workbook(File.Contents(\"" + escapeString(uri) + "\"), null, true)";
                case JSON -> "Json.Document(File.Contents(\"" + escapeString(uri) + "\"))";
                case XML -> "Xml.Tables(File.Contents(\"" + escapeString(uri) + "\"))";
                case TSV -> "Csv.Document(File.Contents(\"" + escapeString(uri) + "\"), [Delimiter=\"#(tab)\", Encoding=65001, QuoteStyle=QuoteStyle.None])";
                case CSV -> "Csv.Document(File.Contents(\"" + escapeString(uri) + "\"), [Delimiter=\",\", Encoding=65001, QuoteStyle=QuoteStyle.None])";
                case AVRO -> "Avro.Document(File.Contents(\"" + escapeString(uri) + "\"))";
                case REST_API, HTML_SCRAPER -> "Web.Contents(\"" + escapeString(uri) + "\")";
            };
        }
        return "Csv.Document(File.Contents(\"" + escapeString(uri) + "\"), [Delimiter=\",\", Encoding=65001, QuoteStyle=QuoteStyle.None])";
    }

    private String formatNavigation(RQueryStep step, String prevStepId) {
        String sheet = step.column() != null && !step.column().isBlank()
                ? step.column()
                : (step.targetTable() != null && !step.targetTable().isBlank() ? step.targetTable() : "Sheet1");
        return prevStepId + "{[Item=\"" + escapeString(sheet) + "\",Kind=\"Sheet\"]}[Data]";
    }

    private String formatChangeType(RQueryStep step, String prevStepId) {
        if (step.parameters() != null && step.parameters().get("columnTypes") instanceof Map<?, ?> typeMap && !typeMap.isEmpty()) {
            String pairs = typeMap.entrySet().stream()
                    .map(e -> "{\"" + escapeString(String.valueOf(e.getKey())) + "\", "
                            + toMType(DataType.fromLabel(String.valueOf(e.getValue()))) + "}")
                    .collect(Collectors.joining(", "));
            return "Table.TransformColumnTypes(" + prevStepId + ", {" + pairs + "})";
        }
        String col = step.column() != null ? step.column() : "Column1";
        DataType dt = step.type() != null ? step.type() : DataType.UTF8_STRING;
        return "Table.TransformColumnTypes(" + prevStepId + ", {{\"" + escapeString(col) + "\", " + toMType(dt) + "}})";
    }

    private String formatFilterRows(RQueryStep step, String prevStepId) {
        String pred = step.predicate() != null && !step.predicate().isBlank() ? step.predicate().trim() : "true";
        String mPred = pred.replace("==", "=").replace("!=", "<>");
        return "Table.SelectRows(" + prevStepId + ", each " + mPred + ")";
    }

    private String formatFilterNulls(RQueryStep step, String prevStepId) {
        String col = step.column() != null ? step.column() : "Column1";
        return "Table.SelectRows(" + prevStepId + ", each [" + col + "] <> null and [" + col + "] <> \"\")";
    }

    private String formatRenameColumn(RQueryStep step, String prevStepId) {
        String oldName = step.oldName() != null ? step.oldName() : (step.column() != null ? step.column() : "Column1");
        String newName = step.newName() != null ? step.newName() : oldName;
        return "Table.RenameColumns(" + prevStepId + ", {{\"" + escapeString(oldName) + "\", \"" + escapeString(newName) + "\"}})";
    }

    private String formatTransformColumnNames(RQueryStep step, String prevStepId) {
        String mode = step.expression() != null ? step.expression().toUpperCase(Locale.ROOT) : "REPLACE_TEXT";
        String transformFn = switch (mode) {
            case "UPPERCASE" -> "Text.Upper";
            case "LOWERCASE" -> "Text.Lower";
            case "TRIM" -> "Text.Trim";
            case "CLEAN" -> "Text.Clean";
            case "ADD_PREFIX" -> "each \"" + escapeString(nullToEmpty(step.oldName())) + "\" & _";
            case "ADD_SUFFIX" -> "each _ & \"" + escapeString(nullToEmpty(step.oldName())) + "\"";
            default -> "each Text.Replace(_, \"" + escapeString(nullToEmpty(step.oldName())) + "\", \""
                    + escapeString(nullToEmpty(step.newName())) + "\")";
        };
        return "Table.TransformColumnNames(" + prevStepId + ", " + transformFn + ")";
    }

    private String formatRemoveColumn(RQueryStep step, String prevStepId) {
        String col = step.column() != null ? step.column() : "Column1";
        return "Table.RemoveColumns(" + prevStepId + ", {\"" + escapeString(col) + "\"})";
    }

    private String formatRemoveOtherColumns(RQueryStep step, String prevStepId) {
        List<String> kept = step.groupColumns() != null ? step.groupColumns()
                : (step.pivotColumns() != null ? step.pivotColumns() : List.of());
        return "Table.SelectColumns(" + prevStepId + ", " + formatColumnList(kept) + ")";
    }

    private String formatUnpivotColumns(RQueryStep step, String prevStepId, boolean unpivotOther) {
        String attr = step.nameCol() != null && !step.nameCol().isBlank() ? step.nameCol() : "Attribute";
        String val = step.valCol() != null && !step.valCol().isBlank() ? step.valCol() : "Value";
        if (unpivotOther) {
            List<String> anchorCols = step.groupColumns() != null && !step.groupColumns().isEmpty()
                    ? step.groupColumns()
                    : (step.pivotColumns() != null ? step.pivotColumns() : List.of());
            return "Table.UnpivotOtherColumns(" + prevStepId + ", " + formatColumnList(anchorCols)
                    + ", \"" + escapeString(attr) + "\", \"" + escapeString(val) + "\")";
        }
        List<String> pivotCols = step.pivotColumns() != null ? step.pivotColumns() : List.of();
        return "Table.Unpivot(" + prevStepId + ", " + formatColumnList(pivotCols)
                + ", \"" + escapeString(attr) + "\", \"" + escapeString(val) + "\")";
    }

    private String formatGroupRows(RQueryStep step, String prevStepId) {
        List<String> groupCols = step.groupColumns() != null ? step.groupColumns() : List.of();
        List<RQueryStep.AggregateDef> aggs = step.aggregations() != null ? step.aggregations() : List.of();
        String aggSpecs = aggs.stream()
                .map(this::formatAggregateSpec)
                .collect(Collectors.joining(", "));
        return "Table.Group(" + prevStepId + ", " + formatColumnList(groupCols) + ", {" + aggSpecs + "})";
    }

    private String formatAggregateSpec(RQueryStep.AggregateDef agg) {
        String outCol = escapeString(agg.outputColumn() != null ? agg.outputColumn() : "Count");
        String srcCol = agg.sourceColumn() != null ? agg.sourceColumn() : "Column1";
        RQueryStep.AggregationFunction fn = agg.function() != null ? agg.function() : RQueryStep.AggregationFunction.COUNT;
        String expr = switch (fn) {
            case COUNT -> "Table.RowCount(_)";
            case SUM -> "List.Sum([" + srcCol + "])";
            case AVG -> "List.Average([" + srcCol + "])";
            case MIN -> "List.Min([" + srcCol + "])";
            case MAX -> "List.Max([" + srcCol + "])";
        };
        return "{\"" + outCol + "\", each " + expr + ", type number}";
    }

    private String formatMergeQueries(RQueryStep step, String prevStepId) {
        List<String> keys = step.joinKeys() != null ? step.joinKeys() : List.of();
        String target = step.targetTable() != null ? step.targetTable() : "LookupTable";
        RQueryStep.JoinType joinType = step.joinType() != null ? step.joinType() : RQueryStep.JoinType.LEFT;
        String joinKind = switch (joinType) {
            case INNER -> "JoinKind.Inner";
            case LEFT -> "JoinKind.LeftOuter";
            case RIGHT -> "JoinKind.RightOuter";
            case FULL -> "JoinKind.FullOuter";
        };
        String keyList = formatColumnList(keys);
        return "Table.NestedJoin(" + prevStepId + ", " + keyList + ", "
                + formatStepIdentifier(target) + ", " + keyList + ", \"" + escapeString(target) + "\", " + joinKind + ")";
    }

    private String formatAddCustomColumn(RQueryStep step, String prevStepId) {
        String col = (step.column() != null && !step.column().isBlank())
                ? step.column()
                : (step.newName() != null && !step.newName().isBlank() ? step.newName() : "Custom");
        String expr = step.expression() != null && !step.expression().isBlank() ? step.expression().trim() : "null";
        DataType dt = step.type() != null ? step.type() : DataType.UTF8_STRING;
        return "Table.AddColumn(" + prevStepId + ", \"" + escapeString(col) + "\", each " + expr + ", " + toMType(dt) + ")";
    }

    private String formatAddIndexColumn(RQueryStep step, String prevStepId) {
        String col = (step.column() != null && !step.column().isBlank())
                ? step.column()
                : (step.newName() != null && !step.newName().isBlank() ? step.newName() : "Index");
        int initial = 1;
        int increment = 1;
        if (step.parameters() != null) {
            if (step.parameters().get("initialValue") instanceof Number n) {
                initial = n.intValue();
            }
            if (step.parameters().get("increment") instanceof Number n) {
                increment = n.intValue();
            }
        }
        return "Table.AddIndexColumn(" + prevStepId + ", \"" + escapeString(col) + "\", " + initial + ", " + increment + ", Int64.Type)";
    }

    private String formatFillDown(RQueryStep step, String prevStepId) {
        List<String> cols = step.groupColumns() != null && !step.groupColumns().isEmpty()
                ? step.groupColumns()
                : (step.column() != null ? List.of(step.column()) : List.of());
        return "Table.FillDown(" + prevStepId + ", " + formatColumnList(cols) + ")";
    }

    private String formatSplitColumn(RQueryStep step, String prevStepId) {
        String col = step.column() != null ? step.column() : "Column1";
        String delimiter = step.parameters() != null && step.parameters().get("delimiter") != null
                ? step.parameters().get("delimiter").toString()
                : ":";
        return "Table.SplitColumn(" + prevStepId + ", \"" + escapeString(col)
                + "\", Splitter.SplitTextByDelimiter(\"" + escapeString(delimiter) + "\", QuoteStyle.Csv))";
    }

    private String formatMergeColumns(RQueryStep step, String prevStepId) {
        List<String> cols = step.groupColumns() != null ? step.groupColumns() : List.of();
        String separator = step.parameters() != null && step.parameters().get("separator") != null
                ? step.parameters().get("separator").toString()
                : ":";
        String newName = step.newName() != null && !step.newName().isBlank() ? step.newName() : "Merged";
        return "Table.CombineColumns(" + prevStepId + ", " + formatColumnList(cols)
                + ", Combiner.CombineTextByDelimiter(\"" + escapeString(separator) + "\", QuoteStyle.None), \""
                + escapeString(newName) + "\")";
    }

    private String formatRemoveDuplicates(RQueryStep step, String prevStepId) {
        List<String> cols = step.groupColumns() != null ? step.groupColumns() : List.of();
        if (cols.isEmpty()) {
            return "Table.Distinct(" + prevStepId + ")";
        }
        return "Table.Distinct(" + prevStepId + ", " + formatColumnList(cols) + ")";
    }

    private String formatSortRows(RQueryStep step, String prevStepId) {
        String col = step.column() != null ? step.column() : "Column1";
        boolean desc = "DESC".equalsIgnoreCase(step.expression())
                || (step.parameters() != null && Boolean.FALSE.equals(step.parameters().get("ascending")));
        String order = desc ? "Order.Descending" : "Order.Ascending";
        return "Table.Sort(" + prevStepId + ", {{\"" + escapeString(col) + "\", " + order + "}})";
    }

    private String formatInlineCleansing(RQueryStep step, String prevStepId) {
        String col = step.column() != null ? step.column() : "Column1";
        String action = step.expression() != null ? step.expression().toUpperCase(Locale.ROOT) : "TRIM";
        return "Table.TransformColumns(" + prevStepId + ", {{\"" + escapeString(col) + "\", each " + action + "(_)}})";
    }

    private int extractCountParam(RQueryStep step) {
        if (step.parameters() != null && step.parameters().get("count") instanceof Number n) {
            return Math.max(0, n.intValue());
        }
        return 1;
    }

    static String normalizeStepKind(String rawStep) {
        if (rawStep == null || rawStep.isBlank()) {
            return "UNKNOWN";
        }
        String stripped = rawStep.trim().toUpperCase(Locale.ROOT);
        if (stripped.endsWith("STEP") && stripped.length() > 4) {
            stripped = stripped.substring(0, stripped.length() - 4);
        }
        return stripped;
    }

    private static String toMType(DataType type) {
        if (type == null) {
            return "type text";
        }
        return switch (type) {
            case INT64 -> "Int64.Type";
            case FLOAT64 -> "type number";
            case DECIMAL -> "Decimal.Type";
            case CURRENCY -> "Currency.Type";
            case BOOLEAN -> "type logical";
            case ISO_DATE -> "type date";
            case ISO_DATETIME -> "type datetime";
            case UTF8_STRING -> "type text";
        };
    }

    private static String formatStepIdentifier(String name) {
        if (name == null || name.isBlank()) {
            return DEFAULT_SOURCE_NAME;
        }
        String trimmed = name.trim();
        if (trimmed.startsWith("#\"") && trimmed.endsWith("\"")) {
            return trimmed;
        }
        if (trimmed.matches("^[A-Za-z_][A-Za-z0-9_]*$")) {
            return trimmed;
        }
        return "#\"" + escapeString(trimmed) + "\"";
    }

    private static String formatColumnList(List<String> columns) {
        if (columns == null || columns.isEmpty()) {
            return "{}";
        }
        return "{" + columns.stream()
                .filter(Objects::nonNull)
                .map(c -> "\"" + escapeString(c) + "\"")
                .collect(Collectors.joining(", ")) + "}";
    }

    private static String escapeString(String value) {
        return value == null ? "" : value.replace("\"", "\"\"");
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
