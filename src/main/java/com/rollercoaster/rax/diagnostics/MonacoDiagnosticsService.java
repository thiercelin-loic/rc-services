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

    private static final Pattern QUALIFIED_COL_PATTERN = Pattern.compile("(?:'([^']+)'|([\\p{L}_][\\p{L}\\p{Nd}_]*))\\[([^\\]]+)\\]");

    private final SimpMessagingTemplate messagingTemplate;

    public MonacoDiagnosticsService() {
        this(null);
    }

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public MonacoDiagnosticsService(SimpMessagingTemplate messagingTemplate) {
        this.messagingTemplate = messagingTemplate;
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
