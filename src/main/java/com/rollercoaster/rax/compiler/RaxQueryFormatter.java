package com.rollercoaster.rax.compiler;

import com.rollercoaster.model.ColumnDefinition;
import com.rollercoaster.model.MeasureDefinition;
import com.rollercoaster.model.RelationshipDefinition;
import com.rollercoaster.model.SemanticModelSchema;
import com.rollercoaster.model.TableDefinition;
import com.rollercoaster.rax.grammar.RaxGrammarLexer;
import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.Token;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Formats RAX query studio scripts and generates TMDL / DEFINE MEASURE model scripts
 * using {@link RaxGrammarLexer} token streams and {@link SemanticModelSchema}.
 */
@Component
public class RaxQueryFormatter {

    private static final String INDENT_UNIT = "    ";
    private static final String DEFAULT_MEASURES_TABLE = "Measures";

    private static final Set<String> TOP_LEVEL_KEYWORDS = Set.of(
            "DEFINE", "EVALUATE", "ORDER", "START"
    );

    private static final Set<String> MULTILINE_FUNCTIONS = Set.of(
            "CALCULATE", "CALCULATETABLE", "FILTER", "SUMMARIZE", "SUMMARIZECOLUMNS",
            "SELECTCOLUMNS", "ADDCOLUMNS", "ROW", "UNION", "IF", "SWITCH", "DIVIDE",
            "SUMX", "AVERAGEX", "MINX", "MAXX", "COUNTX", "RANKX", "TOPN"
    );

    private static final Set<String> UPPERCASE_KEYWORDS = Set.of(
            "DEFINE", "MEASURE", "VAR", "RETURN", "EVALUATE", "ORDER", "BY", "START", "AT",
            "ASC", "DESC", "IN", "NOT", "AND", "OR", "TRUE", "FALSE", "BLANK"
    );

    public String formatQuery(String rawScript) {
        if (rawScript == null || rawScript.isBlank()) {
            return "";
        }
        String trimmed = rawScript.trim();
        if (isTmdlScript(trimmed)) {
            return normalizeTmdlIndentation(trimmed);
        }

        List<Token> tokens = tokenizeWithLexer(trimmed);
        if (tokens.isEmpty()) {
            return trimmed;
        }
        return formatTokenStream(trimmed, tokens).trim();
    }

    public String generateModelTmdl(SemanticModelSchema schema) {
        if (schema == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder("createOrReplace\n");
        String fallbackTable = resolveFallbackTableName(schema);

        Map<String, List<MeasureDefinition>> measuresByTable = groupMeasuresByTable(schema, fallbackTable);
        List<TableDefinition> tables = schema.tables() != null ? schema.tables() : List.of();

        if (tables.isEmpty() && measuresByTable.isEmpty()) {
            sb.append("  table ").append(quoteSingle(fallbackTable)).append("\n");
            return sb.toString().trim();
        }

        for (TableDefinition table : tables) {
            appendTableTmdl(sb, table, measuresByTable.remove(table.name()));
        }
        for (Map.Entry<String, List<MeasureDefinition>> orphanEntry : measuresByTable.entrySet()) {
            appendOrphanMeasuresTableTmdl(sb, orphanEntry.getKey(), orphanEntry.getValue());
        }
        appendRelationshipsTmdl(sb, schema.relationships());

        return sb.toString().trim();
    }

    public String defineAllMeasures(SemanticModelSchema schema) {
        if (schema == null) {
            return "";
        }
        String fallbackTable = resolveFallbackTableName(schema);
        List<MeasureDefinition> measures = schema.measures() != null ? schema.measures() : List.of();

        StringBuilder sb = new StringBuilder("DEFINE\n");
        if (measures.isEmpty()) {
            sb.append(INDENT_UNIT)
                    .append("MEASURE ")
                    .append(quoteSingle(fallbackTable))
                    .append("[Row Count] = COUNTROWS(")
                    .append(quoteSingle(fallbackTable))
                    .append(")\n\n")
                    .append("EVALUATE\n")
                    .append(INDENT_UNIT)
                    .append("ROW(\"Row Count\", [Row Count])");
            return sb.toString();
        }

        List<String> rowProjections = new ArrayList<>(measures.size());
        for (MeasureDefinition measure : measures) {
            String tableName = (measure.tableName() != null && !measure.tableName().isBlank())
                    ? measure.tableName().trim()
                    : fallbackTable;
            String expr = (measure.raxExpression() != null && !measure.raxExpression().isBlank())
                    ? measure.raxExpression().trim()
                    : "0";
            sb.append(INDENT_UNIT)
                    .append("MEASURE ")
                    .append(quoteSingle(tableName))
                    .append("[")
                    .append(measure.name())
                    .append("] = ")
                    .append(expr)
                    .append("\n");
            rowProjections.add("\"" + escapeDoubleQuotes(measure.name()) + "\", [" + measure.name() + "]");
        }

        sb.append("\nEVALUATE\n")
                .append(INDENT_UNIT)
                .append("ROW(\n")
                .append(INDENT_UNIT).append(INDENT_UNIT)
                .append(String.join(",\n" + INDENT_UNIT + INDENT_UNIT, rowProjections))
                .append("\n")
                .append(INDENT_UNIT)
                .append(")");
        return sb.toString();
    }

    private List<Token> tokenizeWithLexer(String script) {
        RaxGrammarLexer lexer = new RaxGrammarLexer(CharStreams.fromString(script));
        lexer.removeErrorListeners();
        List<Token> visibleTokens = new ArrayList<>();
        for (Token token = lexer.nextToken(); token.getType() != Token.EOF; token = lexer.nextToken()) {
            if (token.getChannel() == Token.DEFAULT_CHANNEL) {
                visibleTokens.add(token);
            }
        }
        return visibleTokens;
    }

    private String formatTokenStream(String sourceScript, List<Token> tokens) {
        StringBuilder out = new StringBuilder();
        List<Boolean> parenMultilineStack = new ArrayList<>();
        boolean inDefineSection = false;
        int indentLevel = 0;

        for (int i = 0; i < tokens.size(); i++) {
            Token curr = tokens.get(i);
            Token prev = i > 0 ? tokens.get(i - 1) : null;
            Token next = (i + 1 < tokens.size()) ? tokens.get(i + 1) : null;

            String rawText = curr.getText();
            String upperText = rawText.toUpperCase(Locale.ROOT);

            if (isDottedFunctionPrefix(sourceScript, curr, i, tokens)) {
                int lastIdx = findLastDottedTokenIndex(sourceScript, tokens, i);
                String dottedName = collectDottedFunctionName(tokens, i, lastIdx);
                appendSpacingBefore(out, prev, curr);
                out.append(dottedName.toUpperCase(Locale.ROOT));
                i = lastIdx;
                continue;
            }

            if ("DEFINE".equals(upperText)) {
                inDefineSection = true;
                indentLevel = 0;
                ensureFreshLine(out, 0);
                out.append("DEFINE");
                indentLevel = 1;
                continue;
            }

            if ("EVALUATE".equals(upperText)) {
                inDefineSection = false;
                indentLevel = 0;
                ensureFreshLine(out, 0);
                out.append("EVALUATE");
                indentLevel = 1;
                ensureFreshLine(out, indentLevel);
                continue;
            }

            if ("MEASURE".equals(upperText) && inDefineSection) {
                indentLevel = 1;
                ensureFreshLine(out, indentLevel);
                out.append("MEASURE");
                continue;
            }

            if ("VAR".equals(upperText)) {
                ensureFreshLine(out, Math.max(1, indentLevel));
                out.append("VAR");
                continue;
            }

            if ("RETURN".equals(upperText)) {
                int returnIndent = Math.max(1, indentLevel);
                ensureFreshLine(out, returnIndent);
                out.append("RETURN");
                ensureFreshLine(out, returnIndent + 1);
                continue;
            }

            if ("ORDER".equals(upperText) && next != null && "BY".equalsIgnoreCase(next.getText())) {
                indentLevel = 0;
                ensureFreshLine(out, 0);
                out.append("ORDER BY");
                i++;
                continue;
            }

            if ("(".equals(rawText)) {
                String fnName = prev != null ? prev.getText().toUpperCase(Locale.ROOT) : "";
                boolean multilineCall = MULTILINE_FUNCTIONS.contains(fnName) && hasTopLevelCommaInsideParen(tokens, i);
                parenMultilineStack.add(multilineCall);
                out.append("(");
                if (multilineCall) {
                    indentLevel++;
                    ensureFreshLine(out, indentLevel);
                }
                continue;
            }

            if (")".equals(rawText)) {
                boolean wasMultiline = !parenMultilineStack.isEmpty()
                        && parenMultilineStack.remove(parenMultilineStack.size() - 1);
                if (wasMultiline) {
                    indentLevel = Math.max(0, indentLevel - 1);
                    ensureFreshLine(out, indentLevel);
                }
                out.append(")");
                continue;
            }

            if (",".equals(rawText) || ";".equals(rawText)) {
                out.append(",");
                boolean insideMultilineParen = !parenMultilineStack.isEmpty()
                        && parenMultilineStack.get(parenMultilineStack.size() - 1);
                if (insideMultilineParen) {
                    ensureFreshLine(out, indentLevel);
                } else {
                    out.append(" ");
                }
                continue;
            }

            appendSpacingBefore(out, prev, curr);
            out.append(formatIndividualToken(curr, next));
        }

        return out.toString();
    }

    private boolean isDottedFunctionPrefix(String sourceScript, Token curr, int index, List<Token> tokens) {
        return curr.getType() == RaxGrammarLexer.ID
                && findLastDottedTokenIndex(sourceScript, tokens, index) > index;
    }

    private int findLastDottedTokenIndex(String sourceScript, List<Token> tokens, int startIndex) {
        int cursor = startIndex;
        while (cursor + 1 < tokens.size()) {
            Token left = tokens.get(cursor);
            Token right = tokens.get(cursor + 1);
            if (right.getType() == RaxGrammarLexer.ID && isSeparatedByDot(sourceScript, left, right)) {
                cursor++;
            } else {
                break;
            }
        }
        return cursor;
    }

    private boolean isSeparatedByDot(String sourceScript, Token left, Token right) {
        int endLeft = left.getStopIndex() + 1;
        int startRight = right.getStartIndex();
        if (endLeft >= 0 && startRight > endLeft && startRight <= sourceScript.length()) {
            return ".".equals(sourceScript.substring(endLeft, startRight).trim());
        }
        return false;
    }

    private String collectDottedFunctionName(List<Token> tokens, int startIndex, int endIndex) {
        StringBuilder sb = new StringBuilder();
        for (int idx = startIndex; idx <= endIndex; idx++) {
            if (idx > startIndex) {
                sb.append('.');
            }
            sb.append(tokens.get(idx).getText());
        }
        return sb.toString();
    }

    private boolean hasTopLevelCommaInsideParen(List<Token> tokens, int openParenIndex) {
        int depth = 0;
        for (int i = openParenIndex; i < tokens.size(); i++) {
            String t = tokens.get(i).getText();
            if ("(".equals(t)) {
                depth++;
            } else if (")".equals(t)) {
                depth--;
                if (depth == 0) {
                    return false;
                }
            } else if ((",".equals(t) || ";".equals(t)) && depth == 1) {
                return true;
            }
        }
        return false;
    }

    private void appendSpacingBefore(StringBuilder out, Token prev, Token curr) {
        if (out.isEmpty() || prev == null) {
            return;
        }
        char lastChar = out.charAt(out.length() - 1);
        if (lastChar == '\n' || lastChar == ' ' || lastChar == '(') {
            return;
        }
        String currText = curr.getText();
        String prevText = prev.getText();

        if (curr.getType() == RaxGrammarLexer.COLUMN_REF
                && (prev.getType() == RaxGrammarLexer.QUOTED_TABLE || prev.getType() == RaxGrammarLexer.ID)) {
            return;
        }
        if ("(".equals(currText) && isFunctionNameToken(prev)) {
            return;
        }
        if (")".equals(currText) || ",".equals(currText) || ";".equals(currText)) {
            return;
        }
        if (".".equals(currText) || ".".equals(prevText)) {
            return;
        }
        out.append(' ');
    }

    private boolean isFunctionNameToken(Token token) {
        String upper = token.getText().toUpperCase(Locale.ROOT);
        return (token.getType() == RaxGrammarLexer.ID || token.getType() == RaxGrammarLexer.NOT)
                && !TOP_LEVEL_KEYWORDS.contains(upper)
                && !"RETURN".equals(upper)
                && !"EVALUATE".equals(upper)
                && !"BY".equals(upper)
                && !"= ".equals(upper);
    }

    private String formatIndividualToken(Token curr, Token next) {
        String text = curr.getText();
        String upper = text.toUpperCase(Locale.ROOT);
        if (UPPERCASE_KEYWORDS.contains(upper)) {
            return upper;
        }
        if (next != null && "(".equals(next.getText()) && curr.getType() == RaxGrammarLexer.ID) {
            return upper;
        }
        return text;
    }

    private void ensureFreshLine(StringBuilder out, int indentLevel) {
        while (!out.isEmpty() && out.charAt(out.length() - 1) == ' ') {
            out.setLength(out.length() - 1);
        }
        if (!out.isEmpty() && out.charAt(out.length() - 1) != '\n') {
            out.append('\n');
        }
        out.append(INDENT_UNIT.repeat(Math.max(0, indentLevel)));
    }

    private boolean isTmdlScript(String trimmed) {
        String upper = trimmed.toUpperCase(Locale.ROOT);
        return upper.startsWith("CREATEORREPLACE") || upper.startsWith("TABLE ") || upper.startsWith("MODEL ");
    }

    private String normalizeTmdlIndentation(String tmdl) {
        String[] lines = tmdl.split("\\r?\\n");
        StringBuilder sb = new StringBuilder();
        for (String line : lines) {
            String stripped = line.stripTrailing();
            if (!stripped.isEmpty()) {
                sb.append(stripped).append('\n');
            }
        }
        return sb.toString().trim();
    }

    private String resolveFallbackTableName(SemanticModelSchema schema) {
        if (schema.tables() != null && !schema.tables().isEmpty()) {
            return schema.tables().getFirst().name();
        }
        if (schema.measures() != null && !schema.measures().isEmpty()) {
            String firstMeasureTable = schema.measures().getFirst().tableName();
            if (firstMeasureTable != null && !firstMeasureTable.isBlank()) {
                return firstMeasureTable.trim();
            }
        }
        return DEFAULT_MEASURES_TABLE;
    }

    private Map<String, List<MeasureDefinition>> groupMeasuresByTable(
            SemanticModelSchema schema,
            String fallbackTable) {
        Map<String, List<MeasureDefinition>> byTable = new LinkedHashMap<>();
        if (schema.measures() == null) {
            return byTable;
        }
        for (MeasureDefinition measure : schema.measures()) {
            String tableName = (measure.tableName() != null && !measure.tableName().isBlank())
                    ? measure.tableName().trim()
                    : fallbackTable;
            byTable.computeIfAbsent(tableName, k -> new ArrayList<>()).add(measure);
        }
        return byTable;
    }

    private void appendTableTmdl(
            StringBuilder sb,
            TableDefinition table,
            List<MeasureDefinition> tableMeasures) {
        sb.append("  table ").append(quoteSingle(table.name())).append("\n");
        if (table.description() != null && !table.description().isBlank()) {
            sb.append("    /// ").append(table.description().trim()).append("\n");
        }
        if (tableMeasures != null) {
            for (MeasureDefinition measure : tableMeasures) {
                appendMeasureTmdl(sb, measure);
            }
        }
        if (table.columns() != null) {
            for (ColumnDefinition col : table.columns()) {
                sb.append("    column ").append(quoteSingle(col.name())).append("\n");
                if (col.dataType() != null && !col.dataType().isBlank()) {
                    sb.append("      dataType: ").append(col.dataType().toLowerCase(Locale.ROOT)).append("\n");
                }
            }
        }
    }

    private void appendOrphanMeasuresTableTmdl(
            StringBuilder sb,
            String tableName,
            List<MeasureDefinition> measures) {
        sb.append("  table ").append(quoteSingle(tableName)).append("\n");
        for (MeasureDefinition measure : measures) {
            appendMeasureTmdl(sb, measure);
        }
    }

    private void appendMeasureTmdl(StringBuilder sb, MeasureDefinition measure) {
        if (measure.description() != null && !measure.description().isBlank()) {
            sb.append("    /// ").append(measure.description().trim()).append("\n");
        }
        sb.append("    measure ")
                .append(quoteSingle(measure.name()))
                .append(" = ")
                .append(measure.raxExpression() != null ? measure.raxExpression().trim() : "0")
                .append("\n");
        if (measure.formatString() != null && !measure.formatString().isBlank()) {
            sb.append("      formatString: \"").append(escapeDoubleQuotes(measure.formatString())).append("\"\n");
        }
    }

    private void appendRelationshipsTmdl(StringBuilder sb, List<RelationshipDefinition> relationships) {
        if (relationships == null || relationships.isEmpty()) {
            return;
        }
        for (RelationshipDefinition rel : relationships) {
            sb.append("  relationship ").append(quoteSingle(rel.name())).append("\n");
            sb.append("    fromColumn: ").append(quoteSingle(rel.fromTable())).append("[").append(rel.fromColumn()).append("]\n");
            sb.append("    toColumn: ").append(quoteSingle(rel.toTable())).append("[").append(rel.toColumn()).append("]\n");
            if (!rel.isActive()) {
                sb.append("    isActive: false\n");
            }
        }
    }

    private static String quoteSingle(String identifier) {
        String safe = identifier != null ? identifier.trim() : "";
        return "'" + safe.replace("'", "''") + "'";
    }

    private static String escapeDoubleQuotes(String text) {
        return text != null ? text.replace("\"", "\"\"") : "";
    }
}
