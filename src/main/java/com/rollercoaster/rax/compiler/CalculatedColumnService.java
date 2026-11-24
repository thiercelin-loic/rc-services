package com.rollercoaster.rax.compiler;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.rollercoaster.model.ColumnDefinition;
import com.rollercoaster.model.DuckDbConnectionManager;
import com.rollercoaster.model.SemanticModelManager;
import com.rollercoaster.model.SemanticModelSchema;
import com.rollercoaster.model.TableDefinition;
import com.rollercoaster.rax.ast.SqlDialect;
import com.rollercoaster.rax.compiler.RowExpressionOperators.AggregateFunction;
import com.rollercoaster.rax.compiler.RowExpressionOperators.ComparisonOperator;
import com.rollercoaster.rax.compiler.RowExpressionOperators.InferredDataType;
import com.rollercoaster.rax.context.ContextEvaluator;
import com.rollercoaster.rax.context.ContextEvaluator.ExplicitFilterPredicate;
import com.rollercoaster.rax.context.FilterContext;
import com.rollercoaster.rax.context.RowContext;
import com.rollercoaster.rax.diagnostics.DiagnosticMarker;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Evaluates row-context RAX calculated columns using {@link RaxFormulaCompiler},
 * {@link RowContext}, and {@link ContextEvaluator}, inferring output column types
 * and persisting updated column metadata into {@link SemanticModelManager}.
 */
@Service
public class CalculatedColumnService {

    private static final Pattern CALCULATE_AGG_PATTERN = Pattern.compile(
            "(?is)^CALCULATE\\s*\\(\\s*(SUM|AVERAGE|AVG|MIN|MAX|COUNT)\\s*\\(\\s*"
                    + "(?:'([^']+)'|([\\p{L}_][\\p{L}\\p{Nd}_]*))?\\[([^\\]]+)\\]\\s*\\)"
                    + "(?:\\s*,\\s*(.+))?\\s*\\)$");
    private static final Pattern FILTER_PREDICATE_PATTERN = Pattern.compile(
            "(?i)^(?:'([^']+)'|([\\p{L}_][\\p{L}\\p{Nd}_]*))?\\[([^\\]]+)\\]\\s*(=|<>|!=|>=|<=|>|<)\\s*(.+)$");
    private static final Pattern DATEDIFF_PATTERN = Pattern.compile(
            "(?is)^DATEDIFF\\s*\\(\\s*(.+?)\\s*,\\s*(.+?)\\s*,\\s*(DAY|MONTH|YEAR)\\s*\\)$");
    private static final Pattern RELATED_PATTERN = Pattern.compile(
            "(?i)RELATED\\s*\\(\\s*(?:'([^']+)'|([\\p{L}_][\\p{L}\\p{Nd}_]*))\\[([^\\]]+)\\]\\s*\\)");
    private static final Pattern SINGLE_COL_REF_PATTERN = Pattern.compile(
            "^\\s*(?:'([^']+)'|([\\p{L}_][\\p{L}\\p{Nd}_]*))?\\[([^\\]]+)\\]\\s*$");
    private static final Pattern ANY_COL_REF_PATTERN = Pattern.compile(
            "(?:'([^']+)'|([\\p{L}_][\\p{L}\\p{Nd}_]*))?\\[([^\\]]+)\\]");
    private static final Pattern CONDITION_PATTERN = Pattern.compile("^(.+?)(>=|<=|<>|!=|=|>|<)(.+)$");

    private final RaxFormulaCompiler formulaCompiler;
    private final ContextEvaluator contextEvaluator;
    private final SemanticModelManager semanticModelManager;
    private final DuckDbConnectionManager duckDbConnectionManager;

    public record CalculatedColumnRequest(
            UUID modelId,
            String tableName,
            String columnName,
            @JsonAlias({"formula", "raxExpression"})
            String expression,
            List<Map<String, Object>> rows
    ) {
        public CalculatedColumnRequest {
            RowExpressionParser.requireNonBlank(tableName, "tableName");
            RowExpressionParser.requireNonBlank(columnName, "columnName");
            RowExpressionParser.requireNonBlank(expression, "expression");
            tableName = tableName.trim();
            columnName = columnName.trim();
            expression = expression.trim();
            rows = rows != null ? List.copyOf(rows) : List.of();
        }

        public CalculatedColumnRequest(UUID modelId, String tableName, String columnName, String expression) {
            this(modelId, tableName, columnName, expression, List.of());
        }

        public CalculatedColumnRequest(
                String tableName,
                String columnName,
                String expression,
                List<Map<String, Object>> rows) {
            this(null, tableName, columnName, expression, rows);
        }
    }

    public record CalculatedColumnResult(
            ColumnDefinition column,
            List<Map<String, Object>> rows,
            String inferredDataType,
            List<DiagnosticMarker> diagnostics
    ) {
        public CalculatedColumnResult {
            Objects.requireNonNull(column, "column must not be null");
            RowExpressionParser.requireNonBlank(inferredDataType, "inferredDataType");
            rows = rows != null ? List.copyOf(rows) : List.of();
            diagnostics = diagnostics != null ? List.copyOf(diagnostics) : List.of();
        }

        @JsonProperty("columnName")
        public String columnName() {
            return column.name();
        }

        @JsonProperty("dataType")
        public String dataType() {
            return inferredDataType;
        }

        @JsonProperty("expression")
        public String expression() {
            return column.expression();
        }
    }

    private record RowEvalScope(RowContext rowContext, List<Map<String, Object>> tableRows) {}

    private record PersistColumnCommand(
            SemanticModelSchema schema,
            TableDefinition targetTable,
            ColumnDefinition column) {}

    public CalculatedColumnService() {
        this(
                new RaxFormulaCompiler(new ContextEvaluator(), new TimeIntelligenceCompiler()),
                new ContextEvaluator(),
                new SemanticModelManager(),
                null);
    }

    @Autowired
    public CalculatedColumnService(
            RaxFormulaCompiler formulaCompiler,
            ContextEvaluator contextEvaluator,
            SemanticModelManager semanticModelManager,
            @Autowired(required = false) DuckDbConnectionManager duckDbConnectionManager) {
        this.formulaCompiler = Objects.requireNonNull(formulaCompiler, "formulaCompiler must not be null");
        this.contextEvaluator = Objects.requireNonNull(contextEvaluator, "contextEvaluator must not be null");
        this.semanticModelManager = Objects.requireNonNull(semanticModelManager, "semanticModelManager must not be null");
        this.duckDbConnectionManager = duckDbConnectionManager;
    }

    public CalculatedColumnResult evaluateCalculatedColumn(CalculatedColumnRequest request) {
        Objects.requireNonNull(request, "CalculatedColumnRequest must not be null");
        String executableExpr = formulaCompiler.extractExecutableExpression(request.expression());
        RowExpressionParser.requireNonBlank(executableExpr, "expression");

        SemanticModelSchema schema = semanticModelManager.getModel(request.modelId());
        TableDefinition targetTable = findTable(schema, request.tableName());
        List<Map<String, Object>> sourceRows = resolveSourceRows(request);
        String pkColumn = RowExpressionParser.resolvePrimaryKeyColumn(targetTable, sourceRows);

        List<DiagnosticMarker> diagnostics = collectCompilationDiagnostics(executableExpr, schema, pkColumn);
        List<Map<String, Object>> evaluatedRows = new ArrayList<>(sourceRows.size());
        List<Object> computedValues = new ArrayList<>(sourceRows.size());

        for (Map<String, Object> sourceRow : sourceRows) {
            Map<String, Object> safeRow = sourceRow != null ? sourceRow : Map.of();
            RowContext rowContext = new RowContext(
                    request.tableName(), pkColumn, safeRow, Set.of(request.columnName()));
            Object value = evaluateRowContextExpression(executableExpr, rowContext, sourceRows);
            computedValues.add(value);

            Map<String, Object> nextRow = new LinkedHashMap<>(safeRow);
            nextRow.put(request.columnName(), value);
            evaluatedRows.add(Collections.unmodifiableMap(nextRow));
        }

        InferredDataType inferredType = RowExpressionParser.inferColumnDataType(computedValues, executableExpr);
        ColumnDefinition columnDef = new ColumnDefinition(
                request.columnName(), inferredType.name(), false, false, false, null,
                "", "", inferredType.defaultSummarization(), executableExpr, "", "");

        persistCalculatedColumn(new PersistColumnCommand(schema, targetTable, columnDef), evaluatedRows);
        return new CalculatedColumnResult(columnDef, evaluatedRows, inferredType.name(), diagnostics);
    }

    public Object evaluateRowContextExpression(String expression, Map<String, Object> row) {
        Objects.requireNonNull(expression, "expression must not be null");
        Map<String, Object> safeRow = row != null ? row : Map.of();
        return evaluateRowContextExpression(expression, new RowContext("", null, safeRow), List.of(safeRow));
    }

    public Object evaluateRowContextExpression(
            String expression,
            RowContext rowContext,
            List<Map<String, Object>> tableRows) {
        Objects.requireNonNull(expression, "expression must not be null");
        Objects.requireNonNull(rowContext, "rowContext must not be null");
        String expr = formulaCompiler.extractExecutableExpression(expression).trim();
        RowExpressionParser.requireNonBlank(expr, "expression");
        RowEvalScope scope = new RowEvalScope(rowContext, tableRows != null ? tableRows : List.of());

        Matcher calcMatcher = CALCULATE_AGG_PATTERN.matcher(expr);
        if (calcMatcher.matches()) {
            return evaluateCalculateTransition(calcMatcher, scope);
        }
        Matcher dateDiffMatcher = DATEDIFF_PATTERN.matcher(expr);
        if (dateDiffMatcher.matches()) {
            return RowExpressionParser.evaluateDateDiff(dateDiffMatcher, rowContext.getRowTuple());
        }
        if (RowExpressionParser.isFunctionCall(expr, "IF")) {
            return evaluateIfExpression(expr, scope);
        }
        if (RowExpressionParser.isFunctionCall(expr, "DIVIDE")) {
            return evaluateDivideExpression(expr, scope);
        }
        return evaluateScalarOrArithmetic(expr, scope);
    }

    public boolean evaluateRowCondition(String condition, Map<String, Object> row) {
        Objects.requireNonNull(condition, "condition must not be null");
        Map<String, Object> safeRow = row != null ? row : Map.of();
        return evaluateRowCondition(condition, new RowContext("", null, safeRow), List.of(safeRow));
    }

    public boolean evaluateRowCondition(
            String condition,
            RowContext rowContext,
            List<Map<String, Object>> tableRows) {
        Objects.requireNonNull(condition, "condition must not be null");
        Objects.requireNonNull(rowContext, "rowContext must not be null");
        String trimmed = condition.trim();
        if ("TRUE".equalsIgnoreCase(trimmed) || "TRUE()".equalsIgnoreCase(trimmed)) {
            return true;
        }
        if ("FALSE".equalsIgnoreCase(trimmed) || "FALSE()".equalsIgnoreCase(trimmed)) {
            return false;
        }
        Matcher matcher = CONDITION_PATTERN.matcher(trimmed);
        if (!matcher.matches()) {
            throw new IllegalArgumentException("Invalid row condition syntax: " + condition);
        }
        Object leftVal = evaluateRowContextExpression(matcher.group(1).trim(), rowContext, tableRows);
        ComparisonOperator op = ComparisonOperator.fromSymbol(matcher.group(2));
        Object rightVal = evaluateRowContextExpression(matcher.group(3).trim(), rowContext, tableRows);
        return op.evaluateObjects(leftVal, rightVal);
    }

    public Object resolveRowField(Map<String, Object> row, String columnName) {
        return RowExpressionParser.resolveRowField(row, columnName);
    }

    public Double evaluateSafeArithmetic(String expression) {
        return RowExpressionParser.evaluateSafeArithmetic(expression);
    }

    private List<Map<String, Object>> resolveSourceRows(CalculatedColumnRequest request) {
        if (!request.rows().isEmpty()) {
            return request.rows();
        }
        return duckDbConnectionManager != null
                ? duckDbConnectionManager.getTableRows(request.tableName())
                : List.of();
    }

    private void persistCalculatedColumn(
            PersistColumnCommand command,
            List<Map<String, Object>> evaluatedRows) {
        if (command.targetTable() != null && command.schema() != null) {
            semanticModelManager.upsertColumn(
                    command.schema().modelId(), command.targetTable().name(), command.column());
            if (duckDbConnectionManager != null && !evaluatedRows.isEmpty()) {
                duckDbConnectionManager.ingestTableRows(command.targetTable().name(), evaluatedRows);
            }
        }
    }

    private Object evaluateCalculateTransition(Matcher calcMatcher, RowEvalScope scope) {
        AggregateFunction aggFn = AggregateFunction.fromToken(calcMatcher.group(1));
        String targetTable = calcMatcher.group(2) != null ? calcMatcher.group(2) : calcMatcher.group(3);
        String targetCol = calcMatcher.group(4).trim();
        String filterText = calcMatcher.group(5) != null ? calcMatcher.group(5).trim() : "";

        List<ExplicitFilterPredicate> filters = filterText.isEmpty()
                ? List.of()
                : List.of(new ExplicitFilterPredicate(targetCol, Set.of(), filterText, false));
        FilterContext synthesized = contextEvaluator.synthesizeCalculateContext(
                new FilterContext(), scope.rowContext(), List.of(), filters);

        List<Map<String, Object>> candidateRows = resolveCalculateTargetRows(targetTable, scope);
        String pkCol = scope.rowContext().getPrimaryKeyColumn();
        Object pkVal = (pkCol != null && !pkCol.isBlank()) ? scope.rowContext().getValue(pkCol) : null;

        List<Double> values = new ArrayList<>();
        for (Map<String, Object> candidateRow : candidateRows) {
            if (matchesContextTransition(candidateRow, pkCol, pkVal)
                    && matchesFilterPredicate(candidateRow, filterText, synthesized)) {
                Double numeric = RowExpressionParser.tryParseDouble(resolveRowField(candidateRow, targetCol));
                if (numeric != null) {
                    values.add(numeric);
                }
            }
        }
        return aggFn.compute(values);
    }

    private List<Map<String, Object>> resolveCalculateTargetRows(String targetTable, RowEvalScope scope) {
        if (targetTable != null && !targetTable.isBlank()
                && !targetTable.equalsIgnoreCase(scope.rowContext().getTableName())
                && duckDbConnectionManager != null) {
            List<Map<String, Object>> externalRows = duckDbConnectionManager.getTableRows(targetTable);
            if (!externalRows.isEmpty()) {
                return externalRows;
            }
        }
        return scope.tableRows();
    }

    private boolean matchesContextTransition(Map<String, Object> row, String pkCol, Object pkVal) {
        if (pkCol == null || pkCol.isBlank() || pkVal == null || !RowExpressionParser.hasRowField(row, pkCol)) {
            return true;
        }
        Object candidateVal = resolveRowField(row, pkCol);
        return candidateVal != null && Objects.equals(String.valueOf(candidateVal), String.valueOf(pkVal));
    }

    private boolean matchesFilterPredicate(
            Map<String, Object> row,
            String filterText,
            FilterContext synthesized) {
        if (filterText == null || filterText.isBlank() || synthesized == null) {
            return true;
        }
        Matcher predMatcher = FILTER_PREDICATE_PATTERN.matcher(filterText);
        if (!predMatcher.matches()) {
            return true;
        }
        Object actualVal = resolveRowField(row, predMatcher.group(3).trim());
        if (actualVal == null) {
            return false;
        }
        ComparisonOperator op = ComparisonOperator.fromSymbol(predMatcher.group(4));
        return op.evaluateObjects(actualVal, RowExpressionParser.unquote(predMatcher.group(5).trim()));
    }

    private Object evaluateIfExpression(String expr, RowEvalScope scope) {
        List<String> args = RowExpressionParser.extractFunctionArgs(expr, "IF");
        if (args.size() < 2 || args.size() > 3) {
            throw new IllegalArgumentException("IF requires 2 or 3 arguments: " + expr);
        }
        boolean conditionMet = evaluateRowCondition(args.get(0), scope.rowContext(), scope.tableRows());
        if (conditionMet) {
            return evaluateRowContextExpression(args.get(1), scope.rowContext(), scope.tableRows());
        }
        return args.size() == 3
                ? evaluateRowContextExpression(args.get(2), scope.rowContext(), scope.tableRows())
                : null;
    }

    private Object evaluateDivideExpression(String expr, RowEvalScope scope) {
        List<String> args = RowExpressionParser.extractFunctionArgs(expr, "DIVIDE");
        if (args.size() < 2 || args.size() > 3) {
            throw new IllegalArgumentException("DIVIDE requires 2 or 3 arguments: " + expr);
        }
        Double numerator = RowExpressionParser.tryParseDouble(
                evaluateRowContextExpression(args.get(0), scope.rowContext(), scope.tableRows()));
        Double denominator = RowExpressionParser.tryParseDouble(
                evaluateRowContextExpression(args.get(1), scope.rowContext(), scope.tableRows()));
        double alternate = args.size() == 3
                ? Objects.requireNonNullElse(RowExpressionParser.tryParseDouble(
                        evaluateRowContextExpression(args.get(2), scope.rowContext(), scope.tableRows())), 0.0)
                : 0.0;
        if (numerator == null || denominator == null || Double.compare(denominator, 0.0) == 0) {
            return RowExpressionParser.roundTwoDecimals(alternate);
        }
        return RowExpressionParser.roundTwoDecimals(numerator / denominator);
    }

    private Object evaluateScalarOrArithmetic(String expr, RowEvalScope scope) {
        String trimmed = expr.trim();
        if (RowExpressionParser.isQuotedString(trimmed)) {
            return RowExpressionParser.unquote(trimmed);
        }
        if ("TRUE".equalsIgnoreCase(trimmed) || "TRUE()".equalsIgnoreCase(trimmed)) {
            return Boolean.TRUE;
        }
        if ("FALSE".equalsIgnoreCase(trimmed) || "FALSE()".equalsIgnoreCase(trimmed)) {
            return Boolean.FALSE;
        }
        Map<String, Object> rowTuple = scope.rowContext().getRowTuple();
        Matcher singleRef = SINGLE_COL_REF_PATTERN.matcher(trimmed);
        if (singleRef.matches()) {
            return resolveRowField(rowTuple, singleRef.group(3));
        }
        List<String> concatParts = RowExpressionParser.splitTopLevelByAmpersand(trimmed);
        if (concatParts.size() > 1) {
            StringBuilder sb = new StringBuilder();
            for (String part : concatParts) {
                Object partVal = evaluateRowContextExpression(part, scope.rowContext(), scope.tableRows());
                if (partVal != null) {
                    sb.append(partVal);
                }
            }
            return sb.toString();
        }
        String substituted = substituteColumnReferences(resolveRelatedCalls(trimmed, rowTuple), rowTuple);
        if (RowExpressionParser.isArithmeticOnly(substituted)) {
            return evaluateSafeArithmetic(substituted);
        }
        Double literalNum = RowExpressionParser.tryParseDouble(substituted);
        if (literalNum != null) {
            return RowExpressionParser.roundTwoDecimals(literalNum);
        }
        throw new IllegalArgumentException("Unsupported or unresolvable row-context expression: " + expr);
    }

    private String resolveRelatedCalls(String expr, Map<String, Object> rowTuple) {
        Matcher matcher = RELATED_PATTERN.matcher(expr);
        StringBuilder sb = new StringBuilder();
        while (matcher.find()) {
            String relTable = matcher.group(1) != null ? matcher.group(1) : matcher.group(2);
            String colName = matcher.group(3).trim();
            Object val = lookupRelatedValue(relTable, colName, rowTuple);
            Double num = RowExpressionParser.tryParseDouble(val);
            matcher.appendReplacement(sb, Matcher.quoteReplacement(num != null ? String.valueOf(num) : "0"));
        }
        matcher.appendTail(sb);
        return sb.toString();
    }

    private Object lookupRelatedValue(String relTable, String colName, Map<String, Object> rowTuple) {
        if (duckDbConnectionManager != null && relTable != null && !relTable.isBlank()) {
            List<Map<String, Object>> relRows = duckDbConnectionManager.getTableRows(relTable);
            for (Map<String, Object> candidate : relRows) {
                if (sharesMatchingKey(rowTuple, candidate)) {
                    return resolveRowField(candidate, colName);
                }
            }
            if (!relRows.isEmpty()) {
                return resolveRowField(relRows.get(0), colName);
            }
        }
        return resolveRowField(rowTuple, colName);
    }

    private boolean sharesMatchingKey(Map<String, Object> left, Map<String, Object> right) {
        for (Map.Entry<String, Object> entry : left.entrySet()) {
            if (entry.getValue() == null) {
                continue;
            }
            Object rightVal = resolveRowField(right, entry.getKey());
            if (rightVal != null && Objects.equals(String.valueOf(entry.getValue()), String.valueOf(rightVal))) {
                return true;
            }
        }
        return false;
    }

    private String substituteColumnReferences(String expr, Map<String, Object> rowTuple) {
        Matcher matcher = ANY_COL_REF_PATTERN.matcher(expr);
        StringBuilder sb = new StringBuilder();
        while (matcher.find()) {
            Double num = RowExpressionParser.tryParseDouble(resolveRowField(rowTuple, matcher.group(3).trim()));
            matcher.appendReplacement(sb, Matcher.quoteReplacement(num != null ? String.valueOf(num) : "0"));
        }
        matcher.appendTail(sb);
        return sb.toString();
    }

    private List<DiagnosticMarker> collectCompilationDiagnostics(
            String executableExpr,
            SemanticModelSchema schema,
            String pkColumn) {
        List<DiagnosticMarker> markers = new ArrayList<>();
        try {
            int fyMonth = schema != null ? schema.fiscalYearStartMonth() : 1;
            formulaCompiler.compile(
                    executableExpr, SqlDialect.DUCKDB, fyMonth,
                    new FilterContext(), new RowContext("probe", pkColumn, Map.of()), Map.of());
        } catch (IllegalArgumentException ex) {
            markers.add(DiagnosticMarker.error(
                    1, 0, Math.max(1, executableExpr.length()), "ERR_RAX_SYNTAX", ex.getMessage()));
        }
        return List.copyOf(markers);
    }

    private static TableDefinition findTable(SemanticModelSchema schema, String tableName) {
        if (schema == null || schema.tables() == null) {
            return null;
        }
        return schema.tables().stream()
                .filter(t -> t.name().equalsIgnoreCase(tableName.trim()))
                .findFirst()
                .orElse(null);
    }
}
