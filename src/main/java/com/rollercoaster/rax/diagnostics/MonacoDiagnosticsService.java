package com.rollercoaster.rax.diagnostics;

import com.rollercoaster.model.SemanticModelSchema;
import com.rollercoaster.model.TableDefinition;
import com.rollercoaster.rax.grammar.RaxGrammarLexer;
import com.rollercoaster.rax.grammar.RaxGrammarParser;
import org.antlr.v4.runtime.BaseErrorListener;
import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.RecognitionException;
import org.antlr.v4.runtime.Recognizer;
import org.antlr.v4.runtime.Token;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
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
    ) {}

    private static final Pattern QUALIFIED_COL_PATTERN = Pattern.compile("(?:'([^']+)'|([\\p{L}_][\\p{L}\\p{Nd}_]*))\\[([^\\]]+)\\]");
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

    private final SimpMessagingTemplate messagingTemplate;

    public MonacoDiagnosticsService() {
        this(null);
    }

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public MonacoDiagnosticsService(SimpMessagingTemplate messagingTemplate) {
        this.messagingTemplate = messagingTemplate;
    }

    public RaxValidationResponse inspectFormula(String rawInput, String defaultMeasureName, SemanticModelSchema schema) {
        String safeInput = rawInput != null ? rawInput : "";
        String fallbackName = defaultMeasureName != null && !defaultMeasureName.isBlank() ? defaultMeasureName : "New Measure";
        String measureName = fallbackName;
        String formulaExpression = safeInput.trim();

        if (!VAR_DECL_PATTERN.matcher(safeInput).find()) {
            Matcher assignMatcher = ASSIGNMENT_PATTERN.matcher(safeInput);
            if (assignMatcher.matches()) {
                String candidateName = assignMatcher.group(1).trim();
                if (!candidateName.isEmpty() && !candidateName.contains("(") && !candidateName.contains("[")) {
                    measureName = candidateName;
                    formulaExpression = assignMatcher.group(2).trim();
                }
            }
        }

        List<DiagnosticMarker> markers = validateFormula(formulaExpression, schema);
        List<RaxTokenDto> tokens = tokenizeFormula(safeInput);
        String structuralError = checkStructuralValidity(formulaExpression);
        boolean isValid = structuralError == null && markers.stream().noneMatch(m -> "ERROR".equalsIgnoreCase(m.severity()));
        String statusLabel;
        if (isValid) {
            statusLabel = "RAX Syntax Valid";
        } else if (structuralError != null) {
            statusLabel = structuralError;
        } else {
            statusLabel = "Syntax Error: " + markers.get(0).message();
        }
        return new RaxValidationResponse(isValid, statusLabel, measureName, formulaExpression, markers, tokens);
    }

    public List<RaxTokenDto> tokenizeFormula(String rawFormula) {
        if (rawFormula == null || rawFormula.isEmpty()) {
            return List.of();
        }
        List<RaxTokenDto> tokens = new ArrayList<>();
        Matcher matcher = TOKEN_REGEX.matcher(rawFormula);
        while (matcher.find()) {
            String text = matcher.group(0);
            String type;
            if (matcher.group(1) != null) {
                type = "comment";
            } else if (matcher.group(2) != null) {
                type = "string";
            } else if (matcher.group(3) != null) {
                type = "column";
            } else if (matcher.group(4) != null) {
                type = "table";
            } else if (matcher.group(5) != null) {
                type = "keyword";
            } else if (matcher.group(6) != null) {
                type = "function";
            } else if (matcher.group(7) != null) {
                type = "number";
            } else if (matcher.group(8) != null) {
                type = "operator";
            } else if (matcher.group(9) != null) {
                type = "identifier";
            } else {
                type = "plain";
            }
            tokens.add(new RaxTokenDto(text, type));
        }
        return tokens;
    }

    private String checkStructuralValidity(String expr) {
        String trimmed = expr != null ? expr.trim() : "";
        if (trimmed.isEmpty()) {
            return "Syntax Error: Empty Expression";
        }
        if (Pattern.compile("<[^>]+>").matcher(trimmed).find()) {
            return "Syntax Error: Replace Template Placeholders";
        }
        int openParen = 0;
        int openBracket = 0;
        boolean inSingleQuote = false;
        boolean inDoubleQuote = false;
        for (int i = 0; i < trimmed.length(); i++) {
            char ch = trimmed.charAt(i);
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
        boolean hasVar = Pattern.compile("(?i)\\bVAR\\b").matcher(trimmed).find();
        boolean hasReturn = Pattern.compile("(?i)\\bRETURN\\b").matcher(trimmed).find();
        if (hasVar && !hasReturn) {
            return "Syntax Error: VAR requires RETURN";
        }
        if (Pattern.compile("[+\\-*/^&,=]\\s*$").matcher(trimmed).find()) {
            return "Syntax Error: Trailing Operator";
        }
        return null;
    }

    public List<DiagnosticMarker> validateFormula(String raxFormula, SemanticModelSchema schema) {
        List<DiagnosticMarker> markers = new ArrayList<>();
        if (raxFormula == null || raxFormula.isBlank()) {
            markers.add(DiagnosticMarker.error(1, 0, 1, "ERR_RAX_EMPTY", "RAX expression cannot be empty."));
            return markers;
        }

        String trimmed = raxFormula.trim();
        String upper = trimmed.toUpperCase();
        if (upper.contains("INFO.VIEW.") || upper.contains("INFO.PARTITIONS")
                || upper.contains("INFO.CALCDEPENDENCY") || upper.contains("INFO.STORAGETABLECOLUMNS")
                || upper.startsWith("CREATEORREPLACE") || upper.startsWith("TABLE ") || upper.startsWith("MODEL ")) {
            return markers;
        }

        RaxGrammarLexer lexer = new RaxGrammarLexer(CharStreams.fromString(raxFormula));
        CommonTokenStream tokens = new CommonTokenStream(lexer);
        RaxGrammarParser parser = new RaxGrammarParser(tokens);

        attachAntlrErrorListener(lexer, parser, markers);
        parser.raxExpression();

        validateSemanticSchema(raxFormula, schema, markers);
        validateTableFilterEfficiency(raxFormula, markers);

        return markers;
    }

    private void attachAntlrErrorListener(
            RaxGrammarLexer lexer,
            RaxGrammarParser parser,
            List<DiagnosticMarker> markers) {
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
                markers.add(DiagnosticMarker.error(line, charPositionInLine, tokenLength, "ERR_RAX_SYNTAX", message));
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
        for (TableDefinition table : schema.tables()) {
            String tableNameLower = table.name().toLowerCase();
            knownTables.add(tableNameLower);
            if (table.columns() != null) {
                table.columns().forEach(column ->
                        knownColumns.add(tableNameLower + "[" + column.name().toLowerCase() + "]"));
            }
        }

        Matcher matcher = QUALIFIED_COL_PATTERN.matcher(raxFormula);
        while (matcher.find()) {
            String table = matcher.group(1) != null ? matcher.group(1) : matcher.group(2);
            String columnName = matcher.group(3);
            String tableNameLower = table.toLowerCase();
            String qualifiedLower = tableNameLower + "[" + columnName.toLowerCase() + "]";
            int charOffset = matcher.start();
            int line = countLinesUpTo(raxFormula, charOffset);
            int columnInLine = computeColumnOffset(raxFormula, charOffset);
            int matchLength = matcher.end() - matcher.start();

            if (!knownTables.contains(tableNameLower)) {
                markers.add(DiagnosticMarker.error(
                        line, columnInLine, matchLength,
                        "ERR_UNKNOWN_TABLE",
                        "Unknown semantic model table '" + table + "'."));
            } else if (!knownColumns.contains(qualifiedLower)) {
                markers.add(DiagnosticMarker.error(
                        line, columnInLine, matchLength,
                        "ERR_UNKNOWN_COLUMN",
                        "Column '[" + columnName + "]' does not exist on table '" + table + "'."));
            }
        }
    }

    private void validateTableFilterEfficiency(String raxFormula, List<DiagnosticMarker> markers) {
        String upperFormula = raxFormula.toUpperCase();
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
