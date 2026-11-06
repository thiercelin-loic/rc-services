package com.rollercoaster.rax.compiler;

import com.rollercoaster.rax.ast.CompilationPlan;
import com.rollercoaster.rax.ast.SqlDialect;
import com.rollercoaster.rax.context.ContextEvaluator;
import com.rollercoaster.rax.context.FilterContext;
import com.rollercoaster.rax.context.RowContext;
import com.rollercoaster.rax.grammar.RaxGrammarLexer;
import com.rollercoaster.rax.grammar.RaxGrammarParser;
import org.antlr.v4.runtime.BaseErrorListener;
import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.RecognitionException;
import org.antlr.v4.runtime.Recognizer;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class RaxFormulaCompiler {

    private static final Pattern DEFINE_MEASURE_PATTERN = Pattern.compile(
            "(?i)\\bMEASURE\\s+(?:'[^']+'|[\\p{L}_][\\p{L}\\p{Nd}_]*)?\\[([^\\]]+)\\]\\s*=\\s*(.+?)(?=\\bMEASURE\\b|\\bEVALUATE\\b|$)",
            Pattern.DOTALL);
    private static final Pattern ORDER_BY_PATTERN = Pattern.compile(
            "(?is)\\s+ORDER\\s+BY\\s+.+$");

    private final ContextEvaluator contextEvaluator;
    private final TimeIntelligenceCompiler timeIntelligenceCompiler;

    public RaxFormulaCompiler(
            ContextEvaluator contextEvaluator,
            TimeIntelligenceCompiler timeIntelligenceCompiler) {
        this.contextEvaluator = contextEvaluator;
        this.timeIntelligenceCompiler = timeIntelligenceCompiler;
    }

    public CompilationPlan compile(
            String raxFormula,
            SqlDialect dialect,
            int fiscalYearStartMonth,
            FilterContext filterContext,
            RowContext rowContext,
            Map<String, Object> userClaims) {

        String executableFormula = extractExecutableExpression(raxFormula);
        validateSyntax(executableFormula);

        SqlPlanCompilingVisitor visitor = new SqlPlanCompilingVisitor(
                dialect,
                fiscalYearStartMonth,
                filterContext,
                rowContext,
                contextEvaluator,
                timeIntelligenceCompiler,
                userClaims
        );

        RaxGrammarLexer lexer = new RaxGrammarLexer(CharStreams.fromString(executableFormula));
        CommonTokenStream tokens = new CommonTokenStream(lexer);
        RaxGrammarParser parser = new RaxGrammarParser(tokens);
        RaxGrammarParser.RaxExpressionContext tree = parser.raxExpression();

        String compiledExpression = visitor.visit(tree);
        String primaryTable = visitor.getReferencedTables().isEmpty()
                ? "fact_store_sales"
                : visitor.getReferencedTables().iterator().next().toLowerCase().replace(' ', '_');
        String whereClause = visitor.getActiveFilterContext().toSqlWhereClause(dialect);
        String fullSelectQuery = "SELECT " + compiledExpression + " AS measure_value FROM " + primaryTable
                + " WHERE " + whereClause;

        return new CompilationPlan(
                raxFormula,
                dialect,
                compiledExpression,
                fullSelectQuery,
                visitor.getBindParameters(),
                new ArrayList<>(visitor.getReferencedTables()),
                new ArrayList<>(visitor.getReferencedColumns()),
                new ArrayList<>(visitor.getReferencedMeasures()),
                new ArrayList<>(visitor.getActiveRelationshipsUsed()),
                visitor.isUsesIteratorScan(),
                visitor.isUsesTimeIntelligence()
        );
    }

    private void validateSyntax(String executableFormula) {
        List<String> syntaxErrors = new ArrayList<>();
        RaxGrammarLexer lexer = new RaxGrammarLexer(CharStreams.fromString(executableFormula));
        CommonTokenStream tokens = new CommonTokenStream(lexer);
        RaxGrammarParser parser = new RaxGrammarParser(tokens);

        BaseErrorListener errorCollector = new BaseErrorListener() {
            @Override
            public void syntaxError(
                    Recognizer<?, ?> recognizer,
                    Object offendingSymbol,
                    int line,
                    int charPositionInLine,
                    String message,
                    RecognitionException exception) {
                syntaxErrors.add("Line " + line + ":" + charPositionInLine + " - " + message);
            }
        };

        lexer.removeErrorListeners();
        parser.removeErrorListeners();
        lexer.addErrorListener(errorCollector);
        parser.addErrorListener(errorCollector);

        parser.raxExpression();
        if (!syntaxErrors.isEmpty()) {
            throw new IllegalArgumentException("ERR_RAX_SYNTAX: " + String.join("; ", syntaxErrors));
        }
    }

    public String extractExecutableExpression(String rawFormula) {
        if (rawFormula == null || rawFormula.isBlank()) {
            return "";
        }
        String trimmed = rawFormula.trim();

        Map<String, String> definedMeasures = new LinkedHashMap<>();
        if (trimmed.regionMatches(true, 0, "DEFINE", 0, 6)) {
            Matcher matcher = DEFINE_MEASURE_PATTERN.matcher(trimmed);
            String lastDefinedExpr = null;
            while (matcher.find()) {
                String measureName = matcher.group(1).trim();
                String measureExpr = matcher.group(2).trim();
                definedMeasures.put(measureName, measureExpr);
                lastDefinedExpr = measureExpr;
            }
            int evaluateIdx = indexOfKeywordOutsideQuotes(trimmed, "EVALUATE");
            if (evaluateIdx != -1) {
                trimmed = trimmed.substring(evaluateIdx + "EVALUATE".length()).trim();
            } else if (lastDefinedExpr != null) {
                trimmed = lastDefinedExpr;
            }
        } else if (trimmed.regionMatches(true, 0, "EVALUATE", 0, 8)) {
            trimmed = trimmed.substring(8).trim();
        }

        trimmed = ORDER_BY_PATTERN.matcher(trimmed).replaceFirst("").trim();
        trimmed = unwrapRowOrSummarizeColumns(trimmed);

        for (Map.Entry<String, String> entry : definedMeasures.entrySet()) {
            String refToken = "[" + entry.getKey() + "]";
            if (trimmed.contains(refToken)) {
                trimmed = trimmed.replace(refToken, "(" + entry.getValue() + ")");
            }
        }

        if (isScopedVariableFormula(trimmed)) {
            return trimmed;
        }

        int assignmentEqualsIndex = findTopLevelAssignmentEqualsIndex(trimmed);
        if (assignmentEqualsIndex != -1) {
            return trimmed.substring(assignmentEqualsIndex + 1).trim();
        }

        return trimmed;
    }

    private String unwrapRowOrSummarizeColumns(String expression) {
        String upper = expression.toUpperCase();
        if ((upper.startsWith("ROW(") || upper.startsWith("SUMMARIZECOLUMNS(")) && expression.endsWith(")")) {
            int openParen = expression.indexOf('(');
            String inner = expression.substring(openParen + 1, expression.length() - 1).trim();
            List<String> args = splitTopLevelArgs(inner);
            if (args.size() >= 2) {
                return args.get(args.size() - 1).trim();
            }
        }
        return expression;
    }

    private List<String> splitTopLevelArgs(String inner) {
        List<String> args = new ArrayList<>();
        int depthParen = 0;
        int depthBracket = 0;
        boolean inSingle = false;
        boolean inDouble = false;
        int start = 0;

        for (int i = 0; i < inner.length(); i++) {
            char ch = inner.charAt(i);
            if (ch == '\'' && !inDouble) {
                inSingle = !inSingle;
            } else if (ch == '"' && !inSingle) {
                inDouble = !inDouble;
            } else if (!inSingle && !inDouble) {
                if (ch == '(') {
                    depthParen++;
                } else if (ch == ')') {
                    depthParen = Math.max(0, depthParen - 1);
                } else if (ch == '[') {
                    depthBracket++;
                } else if (ch == ']') {
                    depthBracket = Math.max(0, depthBracket - 1);
                } else if ((ch == ',' || ch == ';') && depthParen == 0 && depthBracket == 0) {
                    args.add(inner.substring(start, i).trim());
                    start = i + 1;
                }
            }
        }
        if (start <= inner.length()) {
            args.add(inner.substring(start).trim());
        }
        return args;
    }

    private int indexOfKeywordOutsideQuotes(String text, String keyword) {
        Pattern p = Pattern.compile("(?i)\\b" + Pattern.quote(keyword) + "\\b");
        Matcher m = p.matcher(text);
        if (m.find()) {
            return m.start();
        }
        return -1;
    }

    private boolean isScopedVariableFormula(String formula) {
        return formula.regionMatches(true, 0, "VAR ", 0, 4)
                || formula.regionMatches(true, 0, "VAR\t", 0, 4)
                || formula.regionMatches(true, 0, "VAR\n", 0, 4)
                || formula.regionMatches(true, 0, "VAR\r", 0, 4);
    }

    private int findTopLevelAssignmentEqualsIndex(String formula) {
        int depthParentheses = 0;
        int depthBrackets = 0;
        boolean inSingleQuote = false;
        boolean inDoubleQuote = false;

        for (int i = 0; i < formula.length(); i++) {
            char character = formula.charAt(i);
            if (character == '\'' && !inDoubleQuote) {
                inSingleQuote = !inSingleQuote;
            } else if (character == '"' && !inSingleQuote) {
                inDoubleQuote = !inDoubleQuote;
            } else if (!inSingleQuote && !inDoubleQuote) {
                if (character == '(') {
                    depthParentheses++;
                } else if (character == ')') {
                    depthParentheses = Math.max(0, depthParentheses - 1);
                } else if (character == '[') {
                    depthBrackets++;
                } else if (character == ']') {
                    depthBrackets = Math.max(0, depthBrackets - 1);
                } else if (character == '=' && depthParentheses == 0 && depthBrackets == 0) {
                    char previousChar = i > 0 ? formula.charAt(i - 1) : ' ';
                    char nextChar = (i + 1 < formula.length()) ? formula.charAt(i + 1) : ' ';
                    boolean isComparisonOperator = previousChar == '!' || previousChar == '<'
                            || previousChar == '>' || previousChar == '=' || nextChar == '=';
                    if (!isComparisonOperator) {
                        String candidate = formula.substring(0, i).trim();
                        if (!candidate.regionMatches(true, 0, "VAR", 0, 3) && candidate.matches("^[\\w\\s\\-'\\[\\]]+$")) {
                            return i;
                        }
                    }
                }
            }
        }

        return -1;
    }
}
