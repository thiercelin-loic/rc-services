package com.rollercoaster.rax.diagnostics;

import com.rollercoaster.model.MeasureDefinition;
import com.rollercoaster.model.SemanticModelSchema;
import com.rollercoaster.model.TableDefinition;
import com.rollercoaster.rax.compiler.AnalyticalArchetypeLibrary;
import com.rollercoaster.rax.grammar.RaxGrammarLexer;
import com.rollercoaster.rax.grammar.RaxGrammarParser;
import org.antlr.v4.runtime.BaseErrorListener;
import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.RecognitionException;
import org.antlr.v4.runtime.Recognizer;
import org.antlr.v4.runtime.Token;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class MonacoDiagnosticsService {

    public record RaxTokenDto(String text, String type) {}

    public record RaxValidationResponse(
            boolean isValid,
            String statusLabel,
            String measureName,
            String formulaExpression,
            List<DiagnosticMarker> diagnostics,
            List<RaxTokenDto> tokens
    ) {
        public RaxValidationResponse {
            diagnostics = diagnostics != null ? List.copyOf(diagnostics) : List.of();
            tokens = tokens != null ? List.copyOf(tokens) : List.of();
        }
    }

    private record ScriptExpressionSegment(String expression, int startOffset) {}

    private static final Pattern QUALIFIED_COL_PATTERN = Pattern.compile(
            "(?:'([^']+)'|([\\p{L}_][\\p{L}\\p{Nd}_]*))\\[([^\\]]+)\\]");
    private static final Pattern FUNCTION_CALL_PATTERN = Pattern.compile(
            "\\b([A-Za-z_][A-Za-z0-9_.]*)\\s*\\(");
    private static final Pattern DEFINE_MEASURE_CLAUSE_PATTERN = Pattern.compile(
            "(?is)\\bMEASURE\\s+(?:(?:'([^']+)'|([\\p{L}_][\\p{L}\\p{Nd}_]*))\\s*)?\\[([^\\]]+)\\]\\s*=\\s*(.+?)(?=\\bMEASURE\\b|\\bEVALUATE\\b|$)");
    private static final Pattern EVALUATE_KEYWORD_PATTERN = Pattern.compile("(?i)\\bEVALUATE\\b");
    private static final Pattern ORDER_BY_SUFFIX_PATTERN = Pattern.compile("(?is)\\s+ORDER\\s+BY\\s+.+$");
    private static final Pattern TEMPLATE_PLACEHOLDER_PATTERN = Pattern.compile("<[^>]+>");
    private static final Pattern VAR_KEYWORD_PATTERN = Pattern.compile("(?i)\\bVAR\\b");
    private static final Pattern RETURN_KEYWORD_PATTERN = Pattern.compile("(?i)\\bRETURN\\b");
    private static final Pattern TRAILING_OPERATOR_PATTERN = Pattern.compile("[+\\-*/^&,=]\\s*$");
    private static final Pattern ASSIGNMENT_PATTERN = Pattern.compile("^\\s*([^=]+?)\\s*=\\s*([\\s\\S]*)$");
    private static final Pattern VAR_DECL_PATTERN = Pattern.compile("(?i)^\\s*VAR\\s+[A-Za-z_][A-Za-z0-9_]*\\s*=");
    private static final Pattern TOKEN_REGEX = Pattern.compile(
            "(//[^\\n]*|--[^\\n]*|/\\*[\\s\\S]*?\\*/)|" +
            "(\"(?:[^\"]|\"\")*\")|" +
            "('[^']+'\\[[^\\]]+\\]|[A-Za-z_][A-Za-z0-9_]*\\[[^\\]]+\\]|\\[[^\\]]+\\])|" +
            "('[^']+')|" +
            "(\\b(?:VAR|RETURN|TRUE|FALSE|BLANK|IN|NOT|AND|OR|ASC|DESC|DEFINE|EVALUATE|MEASURE|ORDER|BY|START|AT)\\b)|" +
            "(\\b[A-Za-z_][A-Za-z0-9_.]*(?=\\s*\\())|" +
            "(\\b\\d+(?:\\.\\d+)?\\b)|" +
            "(<>|<=|>=|&&|\\|\\||[=+\\-*/^&,()<>])|" +
            "(\\b[A-Za-z_][A-Za-z0-9_]*\\b)|" +
            "(\\s+)|" +
            "(.)",
            Pattern.CASE_INSENSITIVE
    );

    private static final Set<String> RESERVED_FUNCTION_TOKENS = Set.of(
            "VAR", "RETURN", "IN", "NOT", "AND", "OR", "TRUE", "FALSE", "DEFINE", "EVALUATE", "MEASURE", "ORDER", "BY"
    );

    private final SimpMessagingTemplate messagingTemplate;
    private final AnalyticalArchetypeLibrary archetypeLibrary;

    public MonacoDiagnosticsService() {
        this(null, new AnalyticalArchetypeLibrary());
    }

    public MonacoDiagnosticsService(SimpMessagingTemplate messagingTemplate) {
        this(messagingTemplate, new AnalyticalArchetypeLibrary());
    }

    @Autowired
    public MonacoDiagnosticsService(
            @Autowired(required = false) SimpMessagingTemplate messagingTemplate,
            AnalyticalArchetypeLibrary archetypeLibrary) {
        this.messagingTemplate = messagingTemplate;
        this.archetypeLibrary = archetypeLibrary != null ? archetypeLibrary : new AnalyticalArchetypeLibrary();
    }

    public RaxValidationResponse inspectFormula(String rawInput, String defaultMeasureName, SemanticModelSchema schema) {
        String safeInput = rawInput != null ? rawInput : "";
        String fallbackName = defaultMeasureName != null && !defaultMeasureName.isBlank()
                ? defaultMeasureName
                : "New Measure";
        String measureName = fallbackName;
        String formulaExpression = safeInput.trim();

        if (!isMultiStatementScript(formulaExpression) && !VAR_DECL_PATTERN.matcher(safeInput).find()) {
            Matcher assignMatcher = ASSIGNMENT_PATTERN.matcher(safeInput);
            if (assignMatcher.matches()) {
                String candidateName = assignMatcher.group(1).trim();
                if (!candidateName.isEmpty() && !candidateName.contains("(") && !candidateName.contains("[")) {
                    measureName = candidateName;
                    formulaExpression = assignMatcher.group(2).trim();
                }
            }
        } else if (isMultiStatementScript(formulaExpression)) {
            Matcher defineMatcher = DEFINE_MEASURE_CLAUSE_PATTERN.matcher(formulaExpression);
            if (defineMatcher.find()) {
                measureName = defineMatcher.group(3).trim();
            }
        }

        List<DiagnosticMarker> markers = validateFormula(formulaExpression, schema);
        List<RaxTokenDto> tokens = tokenizeFormula(safeInput);
        String structuralError = checkStructuralValidity(formulaExpression);
        boolean hasErrorMarker = markers.stream()
                .anyMatch(m -> m.severityEnum() == DiagnosticMarker.Severity.ERROR);
        boolean isValid = structuralError == null && !hasErrorMarker;

        String statusLabel;
        if (isValid) {
            statusLabel = "RAX Syntax Valid";
        } else if (structuralError != null) {
            statusLabel = structuralError;
        } else {
            DiagnosticMarker firstError = markers.stream()
                    .filter(m -> m.severityEnum() == DiagnosticMarker.Severity.ERROR)
                    .findFirst()
                    .orElse(markers.getFirst());
            statusLabel = "Syntax Error: " + firstError.message();
        }
        return new RaxValidationResponse(
                isValid,
                statusLabel,
                measureName,
                formulaExpression,
                List.copyOf(markers),
                List.copyOf(tokens));
    }

    public List<RaxTokenDto> tokenizeFormula(String rawFormula) {
        if (rawFormula == null || rawFormula.isEmpty()) {
            return List.of();
        }
        List<RaxTokenDto> tokens = new ArrayList<>();
        Matcher matcher = TOKEN_REGEX.matcher(rawFormula);
        while (matcher.find()) {
            tokens.add(new RaxTokenDto(matcher.group(0), resolveTokenType(matcher)));
        }
        return List.copyOf(tokens);
    }

    private String resolveTokenType(Matcher matcher) {
        if (matcher.group(1) != null) return "comment";
        if (matcher.group(2) != null) return "string";
        if (matcher.group(3) != null) return "column";
        if (matcher.group(4) != null) return "table";
        if (matcher.group(5) != null) return "keyword";
        if (matcher.group(6) != null) return "function";
        if (matcher.group(7) != null) return "number";
        if (matcher.group(8) != null) return "operator";
        if (matcher.group(9) != null) return "identifier";
        return "plain";
    }

    public List<DiagnosticMarker> validateFormula(String raxFormula, SemanticModelSchema schema) {
        if (raxFormula == null || raxFormula.isBlank()) {
            return List.of(DiagnosticMarker.error(1, 0, 1, "ERR_RAX_EMPTY", "RAX expression cannot be empty."));
        }

        String stripped = stripCommentsPreservingLayout(raxFormula);
        if (stripped.isBlank()) {
            return List.of(DiagnosticMarker.error(1, 0, 1, "ERR_RAX_EMPTY", "RAX expression cannot be empty."));
        }

        String trimmed = raxFormula.trim();
        String upper = trimmed.toUpperCase(Locale.ROOT);
        if (isTmdlDdlScript(upper)) {
            return List.of();
        }

        List<DiagnosticMarker> markers = new ArrayList<>();
        collectStructuralMarkers(stripped, markers);
        validateUnknownFunctions(stripped, markers);
        validateSemanticSchema(stripped, schema, markers);
        validateTableFilterEfficiency(stripped, markers);

        if (markers.stream().noneMatch(m -> m.severityEnum() == DiagnosticMarker.Severity.ERROR)) {
            validateScriptGrammarSegments(stripped, markers);
        }

        return markers.isEmpty() ? List.of() : List.copyOf(markers);
    }

    private boolean isMultiStatementScript(String text) {
        if (text == null) {
            return false;
        }
        String stripped = stripCommentsPreservingLayout(text).trim();
        return stripped.regionMatches(true, 0, "DEFINE", 0, 6)
                || stripped.regionMatches(true, 0, "EVALUATE", 0, 8);
    }

    private boolean isTmdlDdlScript(String upper) {
        return upper.startsWith("CREATEORREPLACE") || upper.startsWith("TABLE ") || upper.startsWith("MODEL ");
    }

    private boolean isInfoQuerySegment(String expr) {
        String upper = expr.toUpperCase(Locale.ROOT);
        return upper.contains("INFO.VIEW.")
                || upper.contains("INFO.TABLES")
                || upper.contains("INFO.COLUMNS")
                || upper.contains("INFO.MEASURES")
                || upper.contains("INFO.RELATIONSHIPS")
                || upper.contains("INFO.PARTITIONS")
                || upper.contains("INFO.CALCDEPENDENCY")
                || upper.contains("INFO.STORAGETABLECOLUMNS");
    }

    private void collectStructuralMarkers(String text, List<DiagnosticMarker> markers) {
        Matcher placeholderMatcher = TEMPLATE_PLACEHOLDER_PATTERN.matcher(text);
        if (placeholderMatcher.find()) {
            int start = placeholderMatcher.start();
            markers.add(DiagnosticMarker.error(
                    countLinesUpTo(text, start),
                    computeColumnOffset(text, start),
                    placeholderMatcher.end() - start,
                    "ERR_TEMPLATE_PLACEHOLDER",
                    "Replace template placeholder '" + placeholderMatcher.group() + "' with a valid RAX expression."));
        }

        int openParen = 0;
        int openBracket = 0;
        int lastOpenParenOffset = 0;
        int lastOpenBracketOffset = 0;
        boolean inSingleQuote = false;
        boolean inDoubleQuote = false;
        int lastQuoteOffset = 0;

        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (ch == '\'' && !inDoubleQuote) {
                if (!inSingleQuote) {
                    lastQuoteOffset = i;
                }
                inSingleQuote = !inSingleQuote;
            } else if (ch == '"' && !inSingleQuote) {
                if (!inDoubleQuote) {
                    lastQuoteOffset = i;
                }
                inDoubleQuote = !inDoubleQuote;
            } else if (!inSingleQuote && !inDoubleQuote) {
                if (ch == '(') {
                    openParen++;
                    lastOpenParenOffset = i;
                } else if (ch == ')') {
                    openParen--;
                    if (openParen < 0) {
                        markers.add(DiagnosticMarker.error(
                                countLinesUpTo(text, i), computeColumnOffset(text, i), 1,
                                "ERR_UNBALANCED_PAREN", "Syntax Error: Unexpected ')'"));
                        return;
                    }
                } else if (ch == '[') {
                    openBracket++;
                    lastOpenBracketOffset = i;
                } else if (ch == ']') {
                    openBracket--;
                    if (openBracket < 0) {
                        markers.add(DiagnosticMarker.error(
                                countLinesUpTo(text, i), computeColumnOffset(text, i), 1,
                                "ERR_UNBALANCED_BRACKET", "Syntax Error: Unexpected ']'"));
                        return;
                    }
                }
            }
        }

        if (inSingleQuote || inDoubleQuote) {
            markers.add(DiagnosticMarker.error(
                    countLinesUpTo(text, lastQuoteOffset), computeColumnOffset(text, lastQuoteOffset), 1,
                    "ERR_UNCLOSED_QUOTE", "Syntax Error: Unclosed Quote"));
            return;
        }
        if (openParen != 0) {
            markers.add(DiagnosticMarker.error(
                    countLinesUpTo(text, lastOpenParenOffset), computeColumnOffset(text, lastOpenParenOffset), 1,
                    "ERR_UNBALANCED_PAREN", "Syntax Error: Unbalanced '('"));
        }
        if (openBracket != 0) {
            markers.add(DiagnosticMarker.error(
                    countLinesUpTo(text, lastOpenBracketOffset), computeColumnOffset(text, lastOpenBracketOffset), 1,
                    "ERR_UNBALANCED_BRACKET", "Syntax Error: Unbalanced '['"));
        }

        boolean hasVar = VAR_KEYWORD_PATTERN.matcher(text).find();
        boolean hasReturn = RETURN_KEYWORD_PATTERN.matcher(text).find();
        if (hasVar && !hasReturn) {
            markers.add(DiagnosticMarker.error(
                    1, 0, Math.min(text.length(), 3),
                    "ERR_VAR_WITHOUT_RETURN", "Syntax Error: VAR requires RETURN"));
        }
        Matcher trailingMatcher = TRAILING_OPERATOR_PATTERN.matcher(text);
        if (trailingMatcher.find()) {
            int start = trailingMatcher.start();
            markers.add(DiagnosticMarker.error(
                    countLinesUpTo(text, start), computeColumnOffset(text, start), 1,
                    "ERR_TRAILING_OPERATOR", "Syntax Error: Trailing Operator"));
        }
    }

    private String checkStructuralValidity(String expr) {
        String stripped = expr != null ? stripCommentsPreservingLayout(expr).trim() : "";
        if (stripped.isEmpty()) {
            return "Syntax Error: Empty Expression";
        }
        if (TEMPLATE_PLACEHOLDER_PATTERN.matcher(stripped).find()) {
            return "Syntax Error: Replace Template Placeholders";
        }
        int openParen = 0;
        int openBracket = 0;
        boolean inSingleQuote = false;
        boolean inDoubleQuote = false;
        for (int i = 0; i < stripped.length(); i++) {
            char ch = stripped.charAt(i);
            if (ch == '\'' && !inDoubleQuote) {
                inSingleQuote = !inSingleQuote;
            } else if (ch == '"' && !inSingleQuote) {
                inDoubleQuote = !inDoubleQuote;
            } else if (!inSingleQuote && !inDoubleQuote) {
                if (ch == '(') openParen++;
                else if (ch == ')') openParen--;
                else if (ch == '[') openBracket++;
                else if (ch == ']') openBracket--;
                if (openParen < 0) return "Syntax Error: Unexpected ')'";
                if (openBracket < 0) return "Syntax Error: Unexpected ']'";
            }
        }
        if (inSingleQuote || inDoubleQuote) return "Syntax Error: Unclosed Quote";
        if (openParen != 0) return "Syntax Error: Unbalanced '('";
        if (openBracket != 0) return "Syntax Error: Unbalanced '['";
        boolean hasVar = VAR_KEYWORD_PATTERN.matcher(stripped).find();
        boolean hasReturn = RETURN_KEYWORD_PATTERN.matcher(stripped).find();
        if (hasVar && !hasReturn) {
            return "Syntax Error: VAR requires RETURN";
        }
        if (TRAILING_OPERATOR_PATTERN.matcher(stripped).find()) {
            return "Syntax Error: Trailing Operator";
        }
        return null;
    }

    private void validateUnknownFunctions(String strippedScript, List<DiagnosticMarker> markers) {
        Matcher matcher = FUNCTION_CALL_PATTERN.matcher(strippedScript);
        while (matcher.find()) {
            int start = matcher.start(1);
            if (isOffsetInsideQuotes(strippedScript, start)) {
                continue;
            }
            String fnName = matcher.group(1);
            String upperFn = fnName.toUpperCase(Locale.ROOT);
            if (RESERVED_FUNCTION_TOKENS.contains(upperFn)) {
                continue;
            }
            if (!archetypeLibrary.isKnownFunction(upperFn)) {
                markers.add(DiagnosticMarker.error(
                        countLinesUpTo(strippedScript, start),
                        computeColumnOffset(strippedScript, start),
                        fnName.length(),
                        "ERR_UNKNOWN_FUNCTION",
                        "Unknown RAX function '" + fnName + "'."));
            }
        }
    }

    private void validateScriptGrammarSegments(String strippedScript, List<DiagnosticMarker> markers) {
        List<ScriptExpressionSegment> segments = extractScriptSegments(strippedScript);
        for (ScriptExpressionSegment segment : segments) {
            String expr = segment.expression().trim();
            if (expr.isEmpty() || isInfoQuerySegment(expr)) {
                continue;
            }
            RaxGrammarLexer lexer = new RaxGrammarLexer(CharStreams.fromString(segment.expression()));
            CommonTokenStream tokens = new CommonTokenStream(lexer);
            RaxGrammarParser parser = new RaxGrammarParser(tokens);

            int baseLineOffset = countLinesUpTo(strippedScript, segment.startOffset()) - 1;
            attachAntlrErrorListener(lexer, parser, markers, baseLineOffset);
            parser.raxExpression();
        }
    }

    private List<ScriptExpressionSegment> extractScriptSegments(String strippedScript) {
        String trimmed = strippedScript.trim();
        List<ScriptExpressionSegment> segments = new ArrayList<>();

        if (trimmed.regionMatches(true, 0, "DEFINE", 0, 6)) {
            Matcher defineMatcher = DEFINE_MEASURE_CLAUSE_PATTERN.matcher(strippedScript);
            boolean foundMeasure = false;
            while (defineMatcher.find()) {
                foundMeasure = true;
                String measureBody = defineMatcher.group(4);
                int bodyOffset = defineMatcher.start(4);
                segments.add(new ScriptExpressionSegment(measureBody, bodyOffset));
            }
            Matcher evalMatcher = EVALUATE_KEYWORD_PATTERN.matcher(strippedScript);
            if (evalMatcher.find()) {
                int evalBodyStart = evalMatcher.end();
                String evalBody = ORDER_BY_SUFFIX_PATTERN.matcher(strippedScript.substring(evalBodyStart)).replaceFirst("");
                segments.add(new ScriptExpressionSegment(evalBody, evalBodyStart));
            } else if (!foundMeasure) {
                segments.add(new ScriptExpressionSegment(strippedScript, 0));
            }
            return segments;
        }

        if (trimmed.regionMatches(true, 0, "EVALUATE", 0, 8)) {
            Matcher evalMatcher = EVALUATE_KEYWORD_PATTERN.matcher(strippedScript);
            if (evalMatcher.find()) {
                int evalBodyStart = evalMatcher.end();
                String evalBody = ORDER_BY_SUFFIX_PATTERN.matcher(strippedScript.substring(evalBodyStart)).replaceFirst("");
                segments.add(new ScriptExpressionSegment(evalBody, evalBodyStart));
                return segments;
            }
        }

        String cleaned = ORDER_BY_SUFFIX_PATTERN.matcher(strippedScript).replaceFirst("");
        segments.add(new ScriptExpressionSegment(cleaned, 0));
        return segments;
    }

    private void attachAntlrErrorListener(
            RaxGrammarLexer lexer,
            RaxGrammarParser parser,
            List<DiagnosticMarker> markers,
            int baseLineOffset) {
        BaseErrorListener listener = new BaseErrorListener() {
            @Override
            public void syntaxError(
                    Recognizer<?, ?> recognizer,
                    Object offendingSymbol,
                    int line,
                    int charPositionInLine,
                    String message,
                    RecognitionException exception) {
                int tokenLength = 1;
                if (offendingSymbol instanceof Token token && token.getText() != null) {
                    tokenLength = Math.max(token.getText().length(), 1);
                }
                markers.add(DiagnosticMarker.error(
                        Math.max(1, baseLineOffset + line),
                        charPositionInLine,
                        tokenLength,
                        "ERR_RAX_SYNTAX",
                        message));
            }
        };

        lexer.removeErrorListeners();
        parser.removeErrorListeners();
        lexer.addErrorListener(listener);
        parser.addErrorListener(listener);
    }

    private void validateSemanticSchema(
            String raxFormula,
            SemanticModelSchema schema,
            List<DiagnosticMarker> markers) {
        if (schema == null || schema.tables() == null || schema.tables().isEmpty()) {
            return;
        }

        Set<String> knownTables = new HashSet<>();
        Set<String> knownColumns = new HashSet<>();
        Set<String> knownMeasures = new HashSet<>();
        for (TableDefinition table : schema.tables()) {
            String tableNameLower = table.name().toLowerCase(Locale.ROOT);
            knownTables.add(tableNameLower);
            if (table.columns() != null) {
                table.columns().forEach(column ->
                        knownColumns.add(tableNameLower + "[" + column.name().toLowerCase(Locale.ROOT) + "]"));
            }
        }
        if (schema.measures() != null) {
            for (MeasureDefinition measure : schema.measures()) {
                knownMeasures.add(measure.name().toLowerCase(Locale.ROOT));
                if (measure.tableName() != null) {
                    knownColumns.add(measure.tableName().toLowerCase(Locale.ROOT)
                            + "[" + measure.name().toLowerCase(Locale.ROOT) + "]");
                }
            }
        }

        Matcher defineMatcher = DEFINE_MEASURE_CLAUSE_PATTERN.matcher(raxFormula);
        while (defineMatcher.find()) {
            String definedTable = defineMatcher.group(1) != null ? defineMatcher.group(1) : defineMatcher.group(2);
            String definedMeasure = defineMatcher.group(3).trim().toLowerCase(Locale.ROOT);
            knownMeasures.add(definedMeasure);
            if (definedTable != null && !definedTable.isBlank()) {
                knownColumns.add(definedTable.trim().toLowerCase(Locale.ROOT) + "[" + definedMeasure + "]");
            }
        }

        Matcher matcher = QUALIFIED_COL_PATTERN.matcher(raxFormula);
        while (matcher.find()) {
            if (isOffsetInsideQuotes(raxFormula, matcher.start()) || isMeasureDefinitionTarget(raxFormula, matcher.start())) {
                continue;
            }
            String table = matcher.group(1) != null ? matcher.group(1) : matcher.group(2);
            String columnName = matcher.group(3);
            String tableNameLower = table.toLowerCase(Locale.ROOT);
            String columnLower = columnName.toLowerCase(Locale.ROOT);
            String qualifiedLower = tableNameLower + "[" + columnLower + "]";
            int charOffset = matcher.start();
            int line = countLinesUpTo(raxFormula, charOffset);
            int columnInLine = computeColumnOffset(raxFormula, charOffset);
            int matchLength = matcher.end() - matcher.start();

            if (!knownTables.contains(tableNameLower)) {
                markers.add(DiagnosticMarker.error(
                        line, columnInLine, matchLength,
                        "ERR_UNKNOWN_TABLE",
                        "Unknown semantic model table '" + table + "'."));
            } else if (!knownColumns.contains(qualifiedLower) && !knownMeasures.contains(columnLower)) {
                markers.add(DiagnosticMarker.error(
                        line, columnInLine, matchLength,
                        "ERR_UNKNOWN_COLUMN",
                        "Column '[" + columnName + "]' does not exist on table '" + table + "'."));
            }
        }
    }

    private boolean isMeasureDefinitionTarget(String text, int matchStart) {
        int prefixStart = Math.max(0, matchStart - 24);
        String prefix = text.substring(prefixStart, matchStart).trim().toUpperCase(Locale.ROOT);
        return prefix.endsWith("MEASURE");
    }

    private boolean isOffsetInsideQuotes(String text, int targetOffset) {
        boolean inDouble = false;
        for (int i = 0; i < targetOffset && i < text.length(); i++) {
            if (text.charAt(i) == '"') {
                inDouble = !inDouble;
            }
        }
        return inDouble;
    }

    private String stripCommentsPreservingLayout(String input) {
        StringBuilder sb = new StringBuilder(input.length());
        int i = 0;
        int len = input.length();
        boolean inSingle = false;
        boolean inDouble = false;

        while (i < len) {
            char ch = input.charAt(i);
            char next = (i + 1 < len) ? input.charAt(i + 1) : '\0';

            if (!inSingle && !inDouble) {
                if ((ch == '/' && next == '/') || (ch == '-' && next == '-')) {
                    while (i < len && input.charAt(i) != '\n') {
                        sb.append(' ');
                        i++;
                    }
                    continue;
                }
                if (ch == '/' && next == '*') {
                    sb.append("  ");
                    i += 2;
                    while (i < len && !(input.charAt(i) == '*' && (i + 1 < len && input.charAt(i + 1) == '/'))) {
                        sb.append(input.charAt(i) == '\n' ? '\n' : ' ');
                        i++;
                    }
                    if (i + 1 < len) {
                        sb.append("  ");
                        i += 2;
                    }
                    continue;
                }
            }
            if (ch == '\'' && !inDouble) {
                inSingle = !inSingle;
            } else if (ch == '"' && !inSingle) {
                inDouble = !inDouble;
            }
            sb.append(ch);
            i++;
        }
        return sb.toString();
    }

    private void validateTableFilterEfficiency(String raxFormula, List<DiagnosticMarker> markers) {
        String upperFormula = raxFormula.toUpperCase(Locale.ROOT);
        if (upperFormula.contains("FILTER(ALL(") && !upperFormula.contains("[")) {
            markers.add(DiagnosticMarker.warning(
                    1, 0, raxFormula.length(),
                    "WARN_INEFFICIENT_TABLE_FILTER",
                    "Filtering an entire table via FILTER(ALL(Table), ...) degrades DuckDB SIMD vectorization; filter only the specific column."));
        }
    }

    public List<DiagnosticMarker> validateAndBroadcast(
            String workspaceId,
            String raxFormula,
            SemanticModelSchema schema) {
        List<DiagnosticMarker> diagnostics = validateFormula(raxFormula, schema);
        if (messagingTemplate != null && workspaceId != null) {
            messagingTemplate.convertAndSend("/topic/diagnostics/" + workspaceId, diagnostics);
        }
        return diagnostics;
    }

    private int countLinesUpTo(String text, int index) {
        int line = 1;
        for (int i = 0; i < index && i < text.length(); i++) {
            if (text.charAt(i) == '\n') {
                line++;
            }
        }
        return line;
    }

    private int computeColumnOffset(String text, int index) {
        int lastNewline = text.lastIndexOf('\n', index - 1);
        return lastNewline < 0 ? index : (index - lastNewline - 1);
    }
}
