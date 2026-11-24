package com.rollercoaster.rax.compiler;

import com.rollercoaster.model.ColumnDefinition;
import com.rollercoaster.model.TableDefinition;
import com.rollercoaster.rax.compiler.RowExpressionOperators.DateDiffUnit;
import com.rollercoaster.rax.compiler.RowExpressionOperators.InferredDataType;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Package-private expression tokenization, date-diff, type-inference, and safe
 * arithmetic parser shared by {@link CalculatedColumnService} and {@link CalculatedTableService}.
 */
final class RowExpressionParser {

    private static final int DECIMAL_SCALE = 2;
    private static final Pattern ARITHMETIC_ONLY_PATTERN = Pattern.compile("^[0-9+\\-*/().\\s]+$");
    private static final Pattern ARITHMETIC_TOKEN_PATTERN = Pattern.compile("\\d+(?:\\.\\d+)?|[+\\-*/()]");
    private static final Pattern SINGLE_COL_REF_PATTERN = Pattern.compile(
            "^\\s*(?:'([^']+)'|([\\p{L}_][\\p{L}\\p{Nd}_]*))?\\[([^\\]]+)\\]\\s*$");

    private RowExpressionParser() {
    }

    static double evaluateSafeArithmetic(String expression) {
        requireNonBlank(expression, "expression");
        String trimmed = expression.trim();
        if (!ARITHMETIC_ONLY_PATTERN.matcher(trimmed).matches()) {
            throw new IllegalArgumentException("Expression contains non-arithmetic tokens: " + expression);
        }
        List<String> tokens = tokenizeArithmetic(trimmed);
        if (tokens.isEmpty()) {
            throw new IllegalArgumentException("Arithmetic expression contains no operands: " + expression);
        }
        ArithmeticCursor cursor = new ArithmeticCursor(tokens);
        double result = cursor.parseExpression();
        if (cursor.hasMore()) {
            throw new IllegalArgumentException("Unexpected token in arithmetic expression: " + cursor.peek());
        }
        return roundTwoDecimals(result);
    }

    static boolean isArithmeticOnly(String expression) {
        return expression != null && !expression.isBlank() && ARITHMETIC_ONLY_PATTERN.matcher(expression).matches();
    }

    static boolean isFunctionCall(String expr, String functionName) {
        String trimmed = expr.trim();
        int openIdx = trimmed.indexOf('(');
        if (!trimmed.endsWith(")") || openIdx <= 0) {
            return false;
        }
        if (!trimmed.substring(0, openIdx).trim().equalsIgnoreCase(functionName)) {
            return false;
        }
        int depth = 0;
        boolean inQuotes = false;
        for (int i = openIdx; i < trimmed.length(); i++) {
            char ch = trimmed.charAt(i);
            if (ch == '"') {
                inQuotes = !inQuotes;
            } else if (!inQuotes) {
                depth += (ch == '(') ? 1 : ((ch == ')') ? -1 : 0);
                if (depth == 0 && i < trimmed.length() - 1) {
                    return false;
                }
            }
        }
        return depth == 0;
    }

    static List<String> extractFunctionArgs(String expr, String functionName) {
        String trimmed = expr.trim();
        int openIdx = trimmed.indexOf('(');
        if (openIdx < 0 || !trimmed.endsWith(")")) {
            throw new IllegalArgumentException("Malformed " + functionName + " call: " + expr);
        }
        return splitTopLevel(trimmed.substring(openIdx + 1, trimmed.length() - 1), ',', ';');
    }

    static List<String> splitTopLevelByAmpersand(String expr) {
        return splitTopLevel(expr, '&', '\0');
    }

    static Object resolveRowField(Map<String, Object> row, String columnName) {
        Objects.requireNonNull(row, "row must not be null");
        requireNonBlank(columnName, "columnName");
        String cleaned = columnName.replace("[", "").replace("]", "").trim();
        if (row.containsKey(cleaned)) {
            return row.get(cleaned);
        }
        String lower = cleaned.toLowerCase(Locale.ROOT);
        for (Map.Entry<String, Object> entry : row.entrySet()) {
            if (entry.getKey() != null && entry.getKey().trim().toLowerCase(Locale.ROOT).equals(lower)) {
                return entry.getValue();
            }
        }
        return null;
    }

    static boolean hasRowField(Map<String, Object> row, String columnName) {
        if (row == null || columnName == null || columnName.isBlank()) {
            return false;
        }
        String cleaned = columnName.replace("[", "").replace("]", "").trim().toLowerCase(Locale.ROOT);
        for (String key : row.keySet()) {
            if (key != null && key.trim().toLowerCase(Locale.ROOT).equals(cleaned)) {
                return true;
            }
        }
        return false;
    }

    static long evaluateDateDiff(Matcher matcher, Map<String, Object> rowTuple) {
        LocalDate startDate = resolveDateOperand(matcher.group(1).trim(), rowTuple);
        LocalDate endDate = resolveDateOperand(matcher.group(2).trim(), rowTuple);
        return DateDiffUnit.fromToken(matcher.group(3)).between(startDate, endDate);
    }

    private static LocalDate resolveDateOperand(String operand, Map<String, Object> rowTuple) {
        if (operand.toUpperCase(Locale.ROOT).startsWith("TODAY")) {
            return LocalDate.now();
        }
        Matcher colMatcher = SINGLE_COL_REF_PATTERN.matcher(operand);
        String rawDateStr = operand;
        if (colMatcher.matches()) {
            Object val = resolveRowField(rowTuple, colMatcher.group(3));
            if (val instanceof LocalDate localDate) {
                return localDate;
            }
            rawDateStr = val != null ? String.valueOf(val) : "";
        }
        String cleaned = unquote(rawDateStr.trim());
        if (cleaned.length() >= 10) {
            cleaned = cleaned.substring(0, 10);
        }
        try {
            return LocalDate.parse(cleaned);
        } catch (DateTimeParseException ex) {
            throw new IllegalArgumentException("Invalid date value in DATEDIFF: " + operand, ex);
        }
    }

    static InferredDataType inferColumnDataType(List<Object> values, String expression) {
        for (Object val : values) {
            if (val == null) {
                continue;
            }
            if (val instanceof Boolean) {
                return InferredDataType.BOOLEAN;
            }
            if (val instanceof Integer || val instanceof Long) {
                return InferredDataType.INT;
            }
            if (val instanceof Number) {
                return InferredDataType.DECIMAL;
            }
            if (val instanceof LocalDate) {
                return InferredDataType.DATE;
            }
            if (val instanceof String strVal) {
                return isIsoDate(strVal) ? InferredDataType.DATE : InferredDataType.VARCHAR;
            }
        }
        String upper = expression.toUpperCase(Locale.ROOT);
        if (upper.startsWith("DATEDIFF")) {
            return InferredDataType.INT;
        }
        return upper.contains("\"") ? InferredDataType.VARCHAR : InferredDataType.DECIMAL;
    }

    static String resolvePrimaryKeyColumn(TableDefinition table, List<Map<String, Object>> rows) {
        if (table != null && table.columns() != null && !table.columns().isEmpty()) {
            for (ColumnDefinition col : table.columns()) {
                if (col.isPrimaryKey()) {
                    return col.name();
                }
            }
            return table.columns().get(0).name();
        }
        if (rows != null && !rows.isEmpty() && rows.get(0) != null && !rows.get(0).isEmpty()) {
            return rows.get(0).keySet().iterator().next();
        }
        return null;
    }

    private static boolean isIsoDate(String value) {
        if (value == null || value.length() != 10) {
            return false;
        }
        try {
            LocalDate.parse(value);
            return true;
        } catch (DateTimeParseException ex) {
            return false;
        }
    }

    private static List<String> splitTopLevel(String text, char sepA, char sepB) {
        List<String> parts = new ArrayList<>();
        int depthParen = 0;
        int depthBracket = 0;
        boolean inSingle = false;
        boolean inDouble = false;
        int start = 0;

        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (ch == '\'' && !inDouble) {
                inSingle = !inSingle;
            } else if (ch == '"' && !inSingle) {
                inDouble = !inDouble;
            } else if (!inSingle && !inDouble) {
                depthParen += (ch == '(') ? 1 : ((ch == ')') ? -1 : 0);
                depthBracket += (ch == '[') ? 1 : ((ch == ']') ? -1 : 0);
                boolean isSep = ch == sepA || (sepB != '\0' && ch == sepB);
                if (isSep && depthParen == 0 && depthBracket == 0) {
                    parts.add(text.substring(start, i).trim());
                    start = i + 1;
                }
            }
        }
        if (start <= text.length()) {
            String tail = text.substring(start).trim();
            if (!tail.isEmpty()) {
                parts.add(tail);
            }
        }
        return parts;
    }

    static Double tryParseDouble(Object val) {
        if (val == null) {
            return null;
        }
        if (val instanceof Number number) {
            return number.doubleValue();
        }
        try {
            return Double.parseDouble(String.valueOf(val).trim());
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    static boolean isQuotedString(String text) {
        return text != null && text.length() >= 2 && text.startsWith("\"") && text.endsWith("\"");
    }

    static String unquote(String text) {
        if (text == null) {
            return "";
        }
        String trimmed = text.trim();
        if (trimmed.length() >= 2
                && ((trimmed.startsWith("\"") && trimmed.endsWith("\""))
                || (trimmed.startsWith("'") && trimmed.endsWith("'")))) {
            return trimmed.substring(1, trimmed.length() - 1);
        }
        return trimmed;
    }

    static double roundTwoDecimals(double value) {
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            return 0.0;
        }
        return BigDecimal.valueOf(value).setScale(DECIMAL_SCALE, RoundingMode.HALF_UP).doubleValue();
    }

    static void requireNonBlank(String value, String fieldName) {
        Objects.requireNonNull(value, fieldName + " must not be null");
        if (value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
    }

    private static List<String> tokenizeArithmetic(String expr) {
        List<String> tokens = new ArrayList<>();
        Matcher matcher = ARITHMETIC_TOKEN_PATTERN.matcher(expr);
        while (matcher.find()) {
            tokens.add(matcher.group());
        }
        return tokens;
    }

    private static final class ArithmeticCursor {
        private final List<String> tokens;
        private int pos;

        private ArithmeticCursor(List<String> tokens) {
            this.tokens = tokens;
        }

        private boolean hasMore() {
            return pos < tokens.size();
        }

        private String peek() {
            return tokens.get(pos);
        }

        private double parseExpression() {
            double value = parseTerm();
            while (hasMore() && ("+".equals(peek()) || "-".equals(peek()))) {
                String op = tokens.get(pos++);
                double right = parseTerm();
                value = "+".equals(op) ? value + right : value - right;
            }
            return value;
        }

        private double parseTerm() {
            double value = parseFactor();
            while (hasMore() && ("*".equals(peek()) || "/".equals(peek()))) {
                String op = tokens.get(pos++);
                double right = parseFactor();
                value = "*".equals(op)
                        ? value * right
                        : (Double.compare(right, 0.0) != 0 ? value / right : 0.0);
            }
            return value;
        }

        private double parseFactor() {
            if (!hasMore()) {
                throw new IllegalArgumentException("Unexpected end of arithmetic expression");
            }
            String token = tokens.get(pos);
            if ("(".equals(token)) {
                pos++;
                double inner = parseExpression();
                if (!hasMore() || !")".equals(tokens.get(pos))) {
                    throw new IllegalArgumentException("Unbalanced parenthesis in arithmetic expression");
                }
                pos++;
                return inner;
            }
            if ("-".equals(token)) {
                pos++;
                return -parseFactor();
            }
            if ("+".equals(token)) {
                pos++;
                return parseFactor();
            }
            pos++;
            return Double.parseDouble(token);
        }
    }
}
