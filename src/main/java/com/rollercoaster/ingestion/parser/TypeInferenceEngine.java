package com.rollercoaster.ingestion.parser;

import com.rollercoaster.ingestion.DataType;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Infers column datatypes dynamically across the initial batch using heuristic regex rules
 * for ISO-8601 dates, floating-point decimals, 64-bit integers, booleans, currency, and UTF-8 strings,
 * and coerces values against inferred/expected types.
 */
@Component
public class TypeInferenceEngine {

    private static final Pattern INT64_REGEX = Pattern.compile("^[+-]?\\d+$");
    private static final Pattern FLOAT64_REGEX = Pattern.compile("^[+-]?\\d+\\.\\d+([eE][+-]?\\d+)?$");
    private static final Pattern CURRENCY_REGEX = Pattern.compile("^[€$£]\\s*[+-]?[\\d,]+(\\.\\d+)?$|^[+-]?[\\d,]+(\\.\\d+)?\\s*[€$£]$");
    private static final Pattern BOOLEAN_REGEX = Pattern.compile("^(?i)(true|false)$");
    private static final Pattern ISO_DATE_REGEX = Pattern.compile("^\\d{4}-\\d{2}-\\d{2}$");
    private static final Pattern ISO_DATETIME_REGEX = Pattern.compile("^\\d{4}-\\d{2}-\\d{2}[T ]\\d{2}:\\d{2}:\\d{2}(\\.\\d+)?(Z|[+-]\\d{2}:?\\d{2})?$");

    public DataType inferValueType(Object rawVal) {
        if (rawVal == null) {
            return DataType.UTF8_STRING;
        }
        if (rawVal instanceof Long || rawVal instanceof Integer) {
            return DataType.INT64;
        }
        if (rawVal instanceof Double || rawVal instanceof Float) {
            return DataType.FLOAT64;
        }
        if (rawVal instanceof BigDecimal) {
            return DataType.DECIMAL;
        }
        if (rawVal instanceof Boolean) {
            return DataType.BOOLEAN;
        }
        String str = rawVal.toString().trim();
        if (str.isEmpty()) {
            return DataType.UTF8_STRING;
        }
        if (BOOLEAN_REGEX.matcher(str).matches()) {
            return DataType.BOOLEAN;
        }
        if (INT64_REGEX.matcher(str).matches()) {
            try {
                Long.parseLong(str);
                return DataType.INT64;
            } catch (NumberFormatException ignored) {
                return DataType.UTF8_STRING;
            }
        }
        if (FLOAT64_REGEX.matcher(str).matches()) {
            return DataType.FLOAT64;
        }
        if (CURRENCY_REGEX.matcher(str).matches()) {
            return DataType.CURRENCY;
        }
        if (ISO_DATE_REGEX.matcher(str).matches()) {
            return DataType.ISO_DATE;
        }
        if (ISO_DATETIME_REGEX.matcher(str).matches()) {
            return DataType.ISO_DATETIME;
        }
        return DataType.UTF8_STRING;
    }

    public Map<String, DataType> inferSchemaFromSample(
            List<Map<String, Object>> sampleRows,
            Map<String, DataType> explicitHints) {
        Map<String, DataType> inferred = new LinkedHashMap<>();
        if (sampleRows == null || sampleRows.isEmpty()) {
            if (explicitHints != null) {
                inferred.putAll(explicitHints);
            }
            return inferred;
        }

        for (Map<String, Object> row : sampleRows) {
            for (Map.Entry<String, Object> entry : row.entrySet()) {
                String col = entry.getKey();
                if (explicitHints != null && explicitHints.containsKey(col)) {
                    inferred.put(col, explicitHints.get(col));
                    continue;
                }
                Object val = entry.getValue();
                if (val == null || val.toString().isBlank()) {
                    inferred.putIfAbsent(col, DataType.UTF8_STRING);
                    continue;
                }
                DataType candidate = inferValueType(val);
                DataType existing = inferred.get(col);
                if (existing == null) {
                    inferred.put(col, candidate);
                } else if (existing != candidate) {
                    inferred.put(col, widenType(existing, candidate));
                }
            }
        }
        if (explicitHints != null) {
            inferred.putAll(explicitHints);
        }
        return inferred;
    }

    public Object coerceValue(Object rawVal, DataType targetType) {
        if (rawVal == null) {
            return null;
        }
        String str = rawVal.toString().trim();
        if (str.isEmpty()) {
            return null;
        }
        return switch (targetType) {
            case INT64 -> {
                if (rawVal instanceof Number n) {
                    if (rawVal instanceof Double d && d % 1 != 0) {
                        throw new IllegalArgumentException("Non-integer value '" + str + "' for INT64");
                    }
                    yield n.longValue();
                }
                yield Long.parseLong(str);
            }
            case FLOAT64 -> {
                if (rawVal instanceof Number n) {
                    yield n.doubleValue();
                }
                yield Double.parseDouble(str.replace(",", ""));
            }
            case DECIMAL, CURRENCY -> {
                if (rawVal instanceof BigDecimal bd) {
                    yield bd;
                }
                String clean = str.replaceAll("[€$£,\\s]", "");
                yield new BigDecimal(clean);
            }
            case BOOLEAN -> {
                if (rawVal instanceof Boolean b) {
                    yield b;
                }
                if ("true".equalsIgnoreCase(str) || "1".equals(str)) yield Boolean.TRUE;
                if ("false".equalsIgnoreCase(str) || "0".equals(str)) yield Boolean.FALSE;
                throw new IllegalArgumentException("Invalid boolean literal: " + str);
            }
            case ISO_DATE -> {
                if (rawVal instanceof LocalDate ld) yield ld.toString();
                yield LocalDate.parse(str.substring(0, Math.min(10, str.length()))).toString();
            }
            case ISO_DATETIME -> {
                if (rawVal instanceof OffsetDateTime odt) yield odt.toString();
                if (ISO_DATETIME_REGEX.matcher(str).matches()) yield str;
                throw new IllegalArgumentException("Invalid ISO-8601 datetime literal: " + str);
            }
            case UTF8_STRING -> str;
        };
    }

    private DataType widenType(DataType a, DataType b) {
        if (a == DataType.UTF8_STRING || b == DataType.UTF8_STRING) {
            return DataType.UTF8_STRING;
        }
        if ((a == DataType.INT64 && b == DataType.FLOAT64) || (a == DataType.FLOAT64 && b == DataType.INT64)) {
            return DataType.FLOAT64;
        }
        if ((a == DataType.INT64 || a == DataType.FLOAT64) && (b == DataType.DECIMAL || b == DataType.CURRENCY)) {
            return b;
        }
        if ((b == DataType.INT64 || b == DataType.FLOAT64) && (a == DataType.DECIMAL || a == DataType.CURRENCY)) {
            return a;
        }
        return DataType.UTF8_STRING;
    }
}
