package com.rollercoaster.rax.compiler;

import com.rollercoaster.rax.ast.SqlDialect;
import com.rollercoaster.rax.context.ContextEvaluator;
import com.rollercoaster.rax.context.ContextEvaluator.CalculateModifier;
import com.rollercoaster.rax.context.ContextEvaluator.CalculateModifier.ModifierType;
import com.rollercoaster.rax.context.ContextEvaluator.ExplicitFilterPredicate;
import com.rollercoaster.rax.context.FilterContext;
import com.rollercoaster.rax.context.LexicalSymbolTable;
import com.rollercoaster.rax.context.RowContext;
import com.rollercoaster.rax.grammar.RaxGrammarBaseVisitor;
import com.rollercoaster.rax.grammar.RaxGrammarParser;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

public class SqlPlanCompilingVisitor extends RaxGrammarBaseVisitor<String> {

    private final SqlDialect dialect;
    private final int fiscalYearStartMonth;
    private final ContextEvaluator contextEvaluator;
    private final TimeIntelligenceCompiler timeIntelligenceCompiler;
    private final Map<String, Object> userClaims;
    private FilterContext activeFilterContext;
    private RowContext activeRowContext;
    private LexicalSymbolTable symbolTable;

    private final Set<String> referencedTables = new LinkedHashSet<>();
    private final Set<String> referencedColumns = new LinkedHashSet<>();
    private final Set<String> referencedMeasures = new LinkedHashSet<>();
    private final Set<String> activeRelationshipsUsed = new LinkedHashSet<>();
    private final Map<String, Object> bindParameters = new LinkedHashMap<>();
    private boolean usesIteratorScan = false;
    private boolean usesTimeIntelligence = false;
    private FilterContext rootSynthesizedContext;
    private int calculateNestingDepth = 0;

    public SqlPlanCompilingVisitor(
            SqlDialect dialect,
            int fiscalYearStartMonth,
            FilterContext initialFilterContext,
            RowContext initialRowContext,
            ContextEvaluator contextEvaluator,
            TimeIntelligenceCompiler timeIntelligenceCompiler,
            Map<String, Object> userClaims) {
        this.dialect = dialect != null ? dialect : SqlDialect.DUCKDB;
        this.fiscalYearStartMonth = fiscalYearStartMonth;
        this.activeFilterContext = initialFilterContext != null ? initialFilterContext : new FilterContext();
        this.activeRowContext = initialRowContext;
        this.contextEvaluator = contextEvaluator != null ? contextEvaluator : new ContextEvaluator();
        this.timeIntelligenceCompiler = timeIntelligenceCompiler != null ? timeIntelligenceCompiler : new TimeIntelligenceCompiler();
        this.userClaims = userClaims != null ? userClaims : Map.of();
        this.symbolTable = new LexicalSymbolTable();
    }

    @Override
    public String visitRaxExpression(RaxGrammarParser.RaxExpressionContext context) {
        return visit(context.expression());
    }

    @Override
    public String visitLetExpr(RaxGrammarParser.LetExprContext context) {
        LexicalSymbolTable previousScope = this.symbolTable;
        this.symbolTable = new LexicalSymbolTable(previousScope);
        try {
            for (RaxGrammarParser.VarDeclarationContext varDeclaration : context.varDeclaration()) {
                String variableName = varDeclaration.ID().getText();
                String compiledVariableSql = visit(varDeclaration.expression());
                this.symbolTable.bindVariable(variableName, compiledVariableSql, compiledVariableSql);
            }
            return visit(context.expression());
        } finally {
            this.symbolTable = previousScope;
        }
    }

    @Override
    public String visitPrimaryExpr(RaxGrammarParser.PrimaryExprContext context) {
        return visit(context.primary());
    }

    @Override
    public String visitUnaryExpr(RaxGrammarParser.UnaryExprContext context) {
        String sign = context.MINUS() != null ? "-" : "+";
        return sign + "(" + visit(context.expression()) + ")";
    }

    @Override
    public String visitPowerExpr(RaxGrammarParser.PowerExprContext context) {
        return "POWER(" + visit(context.expression(0)) + ", " + visit(context.expression(1)) + ")";
    }

    @Override
    public String visitMultDivExpr(RaxGrammarParser.MultDivExprContext context) {
        String leftExpression = visit(context.expression(0));
        String rightExpression = visit(context.expression(1));
        if (context.SLASH() != null) {
            return "(" + leftExpression + " / NULLIF(" + rightExpression + ", 0))";
        }
        return "(" + leftExpression + " * " + rightExpression + ")";
    }

    @Override
    public String visitAddSubExpr(RaxGrammarParser.AddSubExprContext context) {
        String operator = context.MINUS() != null ? " - " : " + ";
        return "(" + visit(context.expression(0)) + operator + visit(context.expression(1)) + ")";
    }

    @Override
    public String visitConcatExpr(RaxGrammarParser.ConcatExprContext context) {
        String leftExpression = visit(context.expression(0));
        String rightExpression = visit(context.expression(1));
        if (dialect == SqlDialect.MYSQL) {
            return "CONCAT(" + leftExpression + ", " + rightExpression + ")";
        }
        return "(" + leftExpression + " || " + rightExpression + ")";
    }

    @Override
    public String visitComparisonExpr(RaxGrammarParser.ComparisonExprContext context) {
        String leftExpression = visit(context.expression(0));
        String rightExpression = visit(context.expression(1));
        String operator = resolveComparisonOperator(context);
        return leftExpression + " " + operator + " " + rightExpression;
    }

    private String resolveComparisonOperator(RaxGrammarParser.ComparisonExprContext context) {
        if (context.NEQ() != null) {
            return "<>";
        }
        if (context.LT() != null) {
            return "<";
        }
        if (context.LTE() != null) {
            return "<=";
        }
        if (context.GT() != null) {
            return ">";
        }
        if (context.GTE() != null) {
            return ">=";
        }
        return "=";
    }

    @Override
    public String visitInExpr(RaxGrammarParser.InExprContext context) {
        String leftExpression = visit(context.expression(0));
        if (context.tableConstructor() != null) {
            return leftExpression + " IN " + visit(context.tableConstructor());
        }
        return leftExpression + " IN (" + visit(context.expression(1)) + ")";
    }

    @Override
    public String visitLogicalExpr(RaxGrammarParser.LogicalExprContext context) {
        String leftExpression = visit(context.expression(0));
        String rightExpression = visit(context.expression(1));
        String operator = context.AND() != null ? " AND " : " OR ";
        return "(" + leftExpression + operator + rightExpression + ")";
    }

    @Override
    public String visitLiteralNumber(RaxGrammarParser.LiteralNumberContext context) {
        return context.NUMBER().getText();
    }

    @Override
    public String visitLiteralString(RaxGrammarParser.LiteralStringContext context) {
        String rawText = context.STRING_LIT().getText();
        String unquoted = rawText.substring(1, rawText.length() - 1).replace("\"\"", "''").replace("'", "''");
        return "'" + unquoted + "'";
    }

    @Override
    public String visitColumnOrMeasure(RaxGrammarParser.ColumnOrMeasureContext context) {
        String rawColumnReference = context.COLUMN_REF().getText();
        String columnName = rawColumnReference.substring(1, rawColumnReference.length() - 1).replace("]]", "]").trim();

        if (context.tableRef() != null) {
            String rawTableName = visit(context.tableRef());
            referencedTables.add(rawTableName);
            referencedColumns.add(rawTableName + "[" + columnName + "]");
            return normalizeSqlIdentifier(rawTableName) + "." + normalizeSqlIdentifier(columnName);
        }

        referencedMeasures.add(columnName);
        if (activeRowContext != null) {
            Object rowValue = activeRowContext.getValue(columnName);
            if (rowValue != null) {
                return rowValue.toString();
            }
        }
        return "SUM(" + normalizeSqlIdentifier(columnName) + ")";
    }

    @Override
    public String visitVariableReference(RaxGrammarParser.VariableReferenceContext context) {
        String identifier = context.ID().getText();
        return symbolTable.resolveSqlExpression(identifier)
                .orElseGet(() -> {
                    if ("TRUE".equalsIgnoreCase(identifier)) {
                        return "TRUE";
                    }
                    if ("FALSE".equalsIgnoreCase(identifier)) {
                        return "FALSE";
                    }
                    if ("BLANK".equalsIgnoreCase(identifier)) {
                        return "NULL";
                    }
                    referencedTables.add(identifier);
                    return normalizeSqlIdentifier(identifier);
                });
    }

    @Override
    public String visitQuotedTableReference(RaxGrammarParser.QuotedTableReferenceContext context) {
        String rawText = context.QUOTED_TABLE().getText();
        String unquoted = rawText.substring(1, rawText.length() - 1).replace("''", "'");
        referencedTables.add(unquoted);
        return normalizeSqlIdentifier(unquoted);
    }

    @Override
    public String visitTableRef(RaxGrammarParser.TableRefContext context) {
        if (context.QUOTED_TABLE() != null) {
            String rawText = context.QUOTED_TABLE().getText();
            return rawText.substring(1, rawText.length() - 1).replace("''", "'");
        }
        return context.ID().getText();
    }

    @Override
    public String visitGroupedExpr(RaxGrammarParser.GroupedExprContext context) {
        return "(" + visit(context.expression()) + ")";
    }

    @Override
    public String visitTableLiteral(RaxGrammarParser.TableLiteralContext context) {
        return visit(context.tableConstructor());
    }

    @Override
    public String visitTableConstructor(RaxGrammarParser.TableConstructorContext context) {
        List<String> rows = context.tableRow().stream()
                .map(this::visit)
                .toList();
        return "(" + String.join(", ", rows) + ")";
    }

    @Override
    public String visitTableRow(RaxGrammarParser.TableRowContext context) {
        if (context.expression().size() == 1 && context.LPAREN() == null) {
            return visit(context.expression(0));
        }
        String tuple = context.expression().stream()
                .map(this::visit)
                .collect(Collectors.joining(", "));
        return "(" + tuple + ")";
    }

    @Override
    public String visitFunctionCall(RaxGrammarParser.FunctionCallContext context) {
        String functionName = (context.NOT() != null ? "NOT" : context.ID().getText()).toUpperCase();
        List<RaxGrammarParser.ExpressionContext> arguments = context.expression();

        return switch (functionName) {
            case "NOT" -> "NOT (" + (arguments.isEmpty() ? "FALSE" : visit(arguments.getFirst())) + ")";
            case "SUM", "AVG", "AVERAGE", "MIN", "MAX", "COUNT", "DISTINCTCOUNT" ->
                    compileAggregateFunction(functionName, arguments);
            case "SUMX", "AVERAGEX", "MINX", "MAXX", "COUNTX" ->
                    compileIteratorAggregationFunction(functionName, arguments);
            case "DIVIDE" -> compileDivideFunction(arguments);
            case "IF" -> compileIfFunction(arguments);
            case "CALCULATE", "CALCULATETABLE" -> compileCalculate(arguments);
            case "TOTALYTD", "TOTALMTD", "TOTALQTD", "SAMEPERIODLASTYEAR", "DATEADD" ->
                    compileTimeIntelligenceFunction(functionName, arguments);
            case "RANKX" -> {
                usesIteratorScan = true;
                String orderExpression = arguments.size() > 1 ? visit(arguments.get(1)) : "1";
                yield "DENSE_RANK() OVER (ORDER BY " + orderExpression + " DESC)";
            }
            case "USERELATIONSHIP" -> compileUseRelationshipFunction(arguments);
            case "CROSSFILTER" -> compileCrossFilterFunction(arguments);
            case "ALL", "REMOVEFILTERS", "ALLSELECTED", "ALLEXCEPT", "VALUES", "DISTINCT" ->
                    !arguments.isEmpty() ? visit(arguments.getFirst()) : "*";
            case "FILTER" -> arguments.size() > 1 ? visit(arguments.get(1)) : "1 = 1";
            case "KEEPFILTERS" -> !arguments.isEmpty() ? visit(arguments.getFirst()) : "1 = 1";
            case "ISINSCOPE" -> {
                String columnName = !arguments.isEmpty() ? arguments.getFirst().getText() : "";
                yield activeFilterContext.isInScope(columnName) ? "TRUE" : "TRUE";
            }
            case "SELECTEDVALUE" -> compileSelectedValueFunction(arguments);
            case "MONTH" -> dialect == SqlDialect.MYSQL
                    ? "MONTH(" + visit(arguments.getFirst()) + ")"
                    : "EXTRACT(MONTH FROM " + visit(arguments.getFirst()) + ")";
            case "YEAR" -> dialect == SqlDialect.MYSQL
                    ? "YEAR(" + visit(arguments.getFirst()) + ")"
                    : "EXTRACT(YEAR FROM " + visit(arguments.getFirst()) + ")";
            case "USEROBJECTID", "USERPRINCIPALNAME", "USERNAME", "CUSTOMDATA" ->
                    compileSecurityClaimFunction(functionName);
            default -> {
                String joinedArguments = arguments.stream().map(this::visit).collect(Collectors.joining(", "));
                yield functionName + "(" + joinedArguments + ")";
            }
        };
    }

    private String compileAggregateFunction(String functionName, List<RaxGrammarParser.ExpressionContext> arguments) {
        String sqlFunction = switch (functionName) {
            case "AVERAGE" -> "AVG";
            case "DISTINCTCOUNT" -> "COUNT(DISTINCT ";
            default -> functionName;
        };
        String argumentSql = arguments.isEmpty() ? "1" : visit(arguments.getFirst());
        if ("DISTINCTCOUNT".equals(functionName)) {
            return sqlFunction + argumentSql + ")";
        }
        return sqlFunction + "(" + argumentSql + ")";
    }

    private String compileIteratorAggregationFunction(String functionName, List<RaxGrammarParser.ExpressionContext> arguments) {
        usesIteratorScan = true;
        String aggregateFunction = switch (functionName) {
            case "SUMX" -> "SUM";
            case "AVERAGEX" -> "AVG";
            case "MINX" -> "MIN";
            case "MAXX" -> "MAX";
            default -> "COUNT";
        };
        String expressionSql = arguments.size() > 1 ? visit(arguments.get(1)) : "1";
        return aggregateFunction + "(" + expressionSql + ")";
    }

    private String compileDivideFunction(List<RaxGrammarParser.ExpressionContext> arguments) {
        String numerator = !arguments.isEmpty() ? visit(arguments.get(0)) : "0";
        String denominator = arguments.size() > 1 ? visit(arguments.get(1)) : "1";
        String alternate = arguments.size() > 2 ? visit(arguments.get(2)) : "0";
        return "COALESCE((" + numerator + ") / NULLIF(" + denominator + ", 0), " + alternate + ")";
    }

    private String compileIfFunction(List<RaxGrammarParser.ExpressionContext> arguments) {
        String condition = !arguments.isEmpty() ? visit(arguments.get(0)) : "1=1";
        String thenBranch = arguments.size() > 1 ? visit(arguments.get(1)) : "NULL";
        String elseBranch = arguments.size() > 2 ? visit(arguments.get(2)) : "NULL";
        return "CASE WHEN " + condition + " THEN " + thenBranch + " ELSE " + elseBranch + " END";
    }

    private String compileTimeIntelligenceFunction(String functionName, List<RaxGrammarParser.ExpressionContext> arguments) {
        usesTimeIntelligence = true;
        String dateColumnSql = !arguments.isEmpty() && arguments.size() > 1
                ? visit(arguments.get(1))
                : (!arguments.isEmpty() ? visit(arguments.getFirst()) : "dim_calendar.transaction_date");

        return switch (functionName) {
            case "TOTALYTD" -> {
                String baseExpression = !arguments.isEmpty() ? visit(arguments.get(0)) : "0";
                String ytdPredicate = timeIntelligenceCompiler.compileTotalYtdBoundaryPredicate(dateColumnSql, dialect, fiscalYearStartMonth);
                activeFilterContext.addSqlPredicate(ytdPredicate);
                yield baseExpression;
            }
            case "TOTALMTD" -> {
                String baseExpression = !arguments.isEmpty() ? visit(arguments.get(0)) : "0";
                activeFilterContext.addSqlPredicate(timeIntelligenceCompiler.compileTotalMtdBoundaryPredicate(dateColumnSql, dialect));
                yield baseExpression;
            }
            case "TOTALQTD" -> {
                String baseExpression = !arguments.isEmpty() ? visit(arguments.get(0)) : "0";
                activeFilterContext.addSqlPredicate(timeIntelligenceCompiler.compileTotalQtdBoundaryPredicate(dateColumnSql, dialect));
                yield baseExpression;
            }
            case "SAMEPERIODLASTYEAR" -> timeIntelligenceCompiler.compileSamePeriodLastYearPredicate(dateColumnSql, dialect);
            case "DATEADD" -> {
                int offset = arguments.size() > 1 ? parseIntegerSafe(arguments.get(1).getText(), -1) : -1;
                String unit = arguments.size() > 2 ? arguments.get(2).getText() : "MONTH";
                yield timeIntelligenceCompiler.compileDateAddPredicate(dateColumnSql, offset, unit, dialect);
            }
            default -> "1 = 1";
        };
    }

    private String compileUseRelationshipFunction(List<RaxGrammarParser.ExpressionContext> arguments) {
        String fromColumn = !arguments.isEmpty() ? arguments.get(0).getText() : "";
        String toColumn = arguments.size() > 1 ? arguments.get(1).getText() : "";
        activeRelationshipsUsed.add(fromColumn + "->" + toColumn);
        return "1 = 1 /* USERELATIONSHIP(" + fromColumn + ", " + toColumn + ") */";
    }

    private String compileCrossFilterFunction(List<RaxGrammarParser.ExpressionContext> arguments) {
        String leftColumn = !arguments.isEmpty() ? arguments.get(0).getText() : "";
        String rightColumn = arguments.size() > 1 ? arguments.get(1).getText() : "";
        String direction = arguments.size() > 2 ? arguments.get(2).getText() : "BOTH";
        return "1 = 1 /* CROSSFILTER(" + leftColumn + ", " + rightColumn + ", " + direction + ") */";
    }

    private String compileSelectedValueFunction(List<RaxGrammarParser.ExpressionContext> arguments) {
        String columnName = !arguments.isEmpty() ? arguments.get(0).getText() : "";
        Object selectedValue = activeFilterContext.getSelectedValue(columnName, null);
        return selectedValue != null ? selectedValue.toString() : "MAX(" + visit(arguments.getFirst()) + ")";
    }

    private String compileSecurityClaimFunction(String functionName) {
        return switch (functionName) {
            case "USEROBJECTID" -> {
                Object subjectClaim = userClaims.getOrDefault("sub", "00000000-0000-0000-0000-000000000000");
                bindParameters.put("userObjectId", subjectClaim);
                yield ":userObjectId";
            }
            case "USERPRINCIPALNAME" -> {
                Object emailClaim = userClaims.getOrDefault("email", "anonymous@rollercoaster.com");
                bindParameters.put("userPrincipalName", emailClaim);
                yield ":userPrincipalName";
            }
            case "USERNAME" -> {
                Object usernameClaim = userClaims.getOrDefault("preferred_username", "anonymous");
                bindParameters.put("userName", usernameClaim);
                yield ":userName";
            }
            case "CUSTOMDATA" -> {
                Object customDataClaim = userClaims.getOrDefault("custom_data", "");
                bindParameters.put("customData", customDataClaim);
                yield ":customData";
            }
            default -> "NULL";
        };
    }

    private String compileCalculate(List<RaxGrammarParser.ExpressionContext> arguments) {
        if (arguments.isEmpty()) {
            return "NULL";
        }
        calculateNestingDepth++;
        RaxGrammarParser.ExpressionContext targetExpression = arguments.getFirst();
        List<CalculateModifier> modifiers = new ArrayList<>();
        List<ExplicitFilterPredicate> explicitFilters = new ArrayList<>();

        for (int index = 1; index < arguments.size(); index++) {
            parseCalculateArgument(arguments.get(index), modifiers, explicitFilters);
        }

        FilterContext parentFilterContext = this.activeFilterContext;
        RowContext parentRowContext = this.activeRowContext;
        try {
            return contextEvaluator.evaluateInSynthesizedContext(
                    parentFilterContext,
                    parentRowContext,
                    modifiers,
                    explicitFilters,
                    synthesizedFilterContext -> {
                        this.activeFilterContext = synthesizedFilterContext;
                        this.activeRowContext = null;
                        if (calculateNestingDepth == 1) {
                            this.rootSynthesizedContext = synthesizedFilterContext;
                        }
                        String expressionSql = visit(targetExpression);
                        String whereClause = synthesizedFilterContext.toSqlWhereClause(dialect);
                        return applyFilterToExpression(expressionSql, whereClause, dialect);
                    });
        } finally {
            this.activeFilterContext = parentFilterContext;
            this.activeRowContext = parentRowContext;
            calculateNestingDepth--;
        }
    }

    private void parseCalculateArgument(
            RaxGrammarParser.ExpressionContext filterArgument,
            List<CalculateModifier> modifiers,
            List<ExplicitFilterPredicate> explicitFilters) {
        String rawText = filterArgument.getText().trim();
        String upperText = rawText.toUpperCase();

        if (upperText.startsWith("ALL(") || upperText.startsWith("REMOVEFILTERS(")) {
            String innerArgument = extractFunctionInnerArg(rawText);
            registerTableAndColumnRef(innerArgument);
            modifiers.add(new CalculateModifier(ModifierType.ALL, innerArgument, null, null, List.of()));
        } else if (upperText.startsWith("ALLEXCEPT(")) {
            List<String> argumentParts = splitFunctionArgs(extractFunctionInnerArg(rawText));
            String tableName = argumentParts.isEmpty() ? "" : argumentParts.getFirst();
            registerTableAndColumnRef(tableName);
            List<String> preservedColumns = argumentParts.size() > 1 ? argumentParts.subList(1, argumentParts.size()) : List.of();
            for (String preservedColumn : preservedColumns) {
                registerTableAndColumnRef(preservedColumn);
            }
            modifiers.add(new CalculateModifier(ModifierType.ALLEXCEPT, tableName, null, null, preservedColumns));
        } else if (upperText.startsWith("ALLSELECTED(")) {
            String innerArgument = extractFunctionInnerArg(rawText);
            registerTableAndColumnRef(innerArgument);
            modifiers.add(new CalculateModifier(ModifierType.ALLSELECTED, innerArgument, null, null, List.of()));
        } else if (upperText.startsWith("USERELATIONSHIP(")) {
            List<String> argumentParts = splitFunctionArgs(extractFunctionInnerArg(rawText));
            String fromColumn = !argumentParts.isEmpty() ? argumentParts.get(0) : "";
            String toColumn = argumentParts.size() > 1 ? argumentParts.get(1) : "";
            registerTableAndColumnRef(fromColumn);
            registerTableAndColumnRef(toColumn);
            activeRelationshipsUsed.add(fromColumn + "->" + toColumn);
            modifiers.add(new CalculateModifier(ModifierType.USERELATIONSHIP, fromColumn, toColumn, null, List.of()));
        } else if (upperText.startsWith("CROSSFILTER(")) {
            List<String> argumentParts = splitFunctionArgs(extractFunctionInnerArg(rawText));
            String leftColumn = !argumentParts.isEmpty() ? argumentParts.get(0) : "";
            String rightColumn = argumentParts.size() > 1 ? argumentParts.get(1) : "";
            String direction = argumentParts.size() > 2 ? argumentParts.get(2) : "BOTH";
            registerTableAndColumnRef(leftColumn);
            registerTableAndColumnRef(rightColumn);
            modifiers.add(new CalculateModifier(ModifierType.CROSSFILTER, leftColumn, rightColumn, direction, List.of()));
        } else if (upperText.startsWith("KEEPFILTERS(")) {
            String innerSqlExpression = visit(filterArgument);
            String targetColumnReference = extractLeadingColumnRef(rawText);
            registerTableAndColumnRef(targetColumnReference);
            modifiers.add(new CalculateModifier(ModifierType.KEEPFILTERS, targetColumnReference, null, null, List.of()));
            explicitFilters.add(new ExplicitFilterPredicate(targetColumnReference, Set.of(), innerSqlExpression, true));
        } else {
            String predicateSql = visit(filterArgument);
            String targetColumnReference = extractLeadingColumnRef(rawText);
            registerTableAndColumnRef(targetColumnReference);
            explicitFilters.add(new ExplicitFilterPredicate(targetColumnReference, Set.of(), predicateSql, false));
        }
    }

    private String applyFilterToExpression(String expressionSql, String whereClause, SqlDialect dialect) {
        if (whereClause == null || whereClause.isBlank() || "1 = 1".equals(whereClause.trim())) {
            return expressionSql;
        }

        AggregateCall aggregateCall = parseTopLevelAggregate(expressionSql);
        if (aggregateCall != null) {
            if (dialect == SqlDialect.DUCKDB) {
                return expressionSql + " FILTER (WHERE " + whereClause + ")";
            }
            if (aggregateCall.isDistinct()) {
                return "COUNT(DISTINCT CASE WHEN " + whereClause + " THEN " + aggregateCall.argumentSql() + " ELSE NULL END)";
            }
            return aggregateCall.functionName() + "(CASE WHEN " + whereClause + " THEN " + aggregateCall.argumentSql() + " ELSE NULL END)";
        }

        return "(CASE WHEN " + whereClause + " THEN (" + expressionSql + ") ELSE NULL END)";
    }

    private record AggregateCall(String functionName, String argumentSql, boolean isDistinct) {}

    private AggregateCall parseTopLevelAggregate(String sql) {
        if (sql == null) {
            return null;
        }
        String trimmedSql = sql.trim();
        int openParenthesisIndex = trimmedSql.indexOf('(');
        if (openParenthesisIndex <= 0 || !trimmedSql.endsWith(")")) {
            return null;
        }
        String functionName = trimmedSql.substring(0, openParenthesisIndex).trim().toUpperCase();
        if (!Set.of("SUM", "AVG", "MIN", "MAX", "COUNT").contains(functionName)) {
            return null;
        }

        int parenthesisDepth = 0;
        for (int index = openParenthesisIndex; index < trimmedSql.length(); index++) {
            char currentChar = trimmedSql.charAt(index);
            if (currentChar == '(') {
                parenthesisDepth++;
            } else if (currentChar == ')') {
                parenthesisDepth--;
                if (parenthesisDepth == 0 && index < trimmedSql.length() - 1) {
                    return null;
                }
            }
        }
        if (parenthesisDepth != 0) {
            return null;
        }

        String innerArgument = trimmedSql.substring(openParenthesisIndex + 1, trimmedSql.length() - 1).trim();
        boolean isDistinct = false;
        if ("COUNT".equals(functionName) && innerArgument.toUpperCase().startsWith("DISTINCT ")) {
            isDistinct = true;
            innerArgument = innerArgument.substring(9).trim();
        }
        return new AggregateCall(functionName, innerArgument, isDistinct);
    }

    private void registerTableAndColumnRef(String reference) {
        if (reference == null || reference.isBlank()) {
            return;
        }
        int openBracketIndex = reference.indexOf('[');
        int closeBracketIndex = reference.indexOf(']');
        if (openBracketIndex >= 0 && closeBracketIndex > openBracketIndex) {
            String tableName = reference.substring(0, openBracketIndex).trim().replace("'", "");
            String columnName = reference.substring(openBracketIndex + 1, closeBracketIndex).trim();
            if (!tableName.isBlank()) {
                referencedTables.add(tableName);
                referencedColumns.add(tableName + "[" + columnName + "]");
            } else {
                referencedColumns.add("[" + columnName + "]");
            }
        } else if (!reference.contains("(") && !reference.contains(")")) {
            referencedTables.add(reference.trim().replace("'", ""));
        }
    }

    public FilterContext getActiveFilterContext() {
        return rootSynthesizedContext != null ? rootSynthesizedContext : activeFilterContext;
    }

    public Set<String> getReferencedTables() {
        return referencedTables;
    }

    public Set<String> getReferencedColumns() {
        return referencedColumns;
    }

    public Set<String> getReferencedMeasures() {
        return referencedMeasures;
    }

    public Set<String> getActiveRelationshipsUsed() {
        return activeRelationshipsUsed;
    }

    public Map<String, Object> getBindParameters() {
        return bindParameters;
    }

    public boolean isUsesIteratorScan() {
        return usesIteratorScan;
    }

    public boolean isUsesTimeIntelligence() {
        return usesTimeIntelligence;
    }

    private String normalizeSqlIdentifier(String rawIdentifier) {
        return rawIdentifier.replace("'", "").trim().toLowerCase().replace(' ', '_').replace('-', '_');
    }

    private String extractFunctionInnerArg(String rawCall) {
        int openParenthesisIndex = rawCall.indexOf('(');
        int closeParenthesisIndex = rawCall.lastIndexOf(')');
        if (openParenthesisIndex >= 0 && closeParenthesisIndex > openParenthesisIndex) {
            return rawCall.substring(openParenthesisIndex + 1, closeParenthesisIndex).trim();
        }
        return "";
    }

    private List<String> splitFunctionArgs(String innerArgument) {
        if (innerArgument.isBlank()) {
            return List.of();
        }
        String[] tokens = innerArgument.split("[,;]");
        List<String> argumentList = new ArrayList<>(tokens.length);
        for (String token : tokens) {
            argumentList.add(token.trim());
        }
        return argumentList;
    }

    private String extractLeadingColumnRef(String rawPredicate) {
        int closeBracketIndex = rawPredicate.indexOf(']');
        if (closeBracketIndex > 0) {
            String prefix = rawPredicate.substring(0, closeBracketIndex + 1).trim();
            int openParenthesisIndex = prefix.lastIndexOf('(');
            if (openParenthesisIndex >= 0) {
                prefix = prefix.substring(openParenthesisIndex + 1).trim();
            }
            return prefix;
        }
        return "";
    }

    private int parseIntegerSafe(String rawInteger, int fallbackValue) {
        try {
            return Integer.parseInt(rawInteger.replace("(", "").replace(")", "").trim());
        } catch (NumberFormatException exception) {
            return fallbackValue;
        }
    }
}
