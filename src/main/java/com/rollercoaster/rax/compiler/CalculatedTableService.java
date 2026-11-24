package com.rollercoaster.rax.compiler;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.rollercoaster.model.ColumnDefinition;
import com.rollercoaster.model.DuckDbConnectionManager;
import com.rollercoaster.model.SemanticModelManager;
import com.rollercoaster.model.SemanticModelSchema;
import com.rollercoaster.model.TableDefinition;
import com.rollercoaster.rax.compiler.RowExpressionOperators.AggregateFunction;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.TextStyle;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Evaluates RAX calculated table formulas ({@code CALENDAR}, {@code CALENDARAUTO},
 * {@code SUMMARIZECOLUMNS}, and {@code INFO.VIEW.*} data dictionaries) and generates
 * date-table schemas and daily rows on the backend.
 */
@Service
public class CalculatedTableService {

    private static final int MAX_CALENDAR_DAYS = 1461;
    private static final int DEFAULT_FISCAL_END_MONTH = 12;
    private static final String DATE_COL_NAME = "Date";
    private static final DateTimeFormatter YEAR_MONTH_FMT = DateTimeFormatter.ofPattern("yyyy-MM");

    private static final Pattern DATE_CALL_PATTERN = Pattern.compile(
            "(?i)DATE\\s*\\(\\s*(\\d{4})\\s*,\\s*(\\d{1,2})\\s*,\\s*(\\d{1,2})\\s*\\)");
    private static final Pattern CALENDAR_AUTO_PATTERN = Pattern.compile(
            "(?is).*?CALENDARAUTO\\s*\\(\\s*(\\d*)\\s*\\).*");
    private static final Pattern COL_REF_PATTERN = Pattern.compile(
            "(?:'([^']+)'|([\\p{L}_][\\p{L}\\p{Nd}_]*))\\[([^\\]]+)\\]");
    private static final Pattern SUMMARIZE_ALIAS_PATTERN = Pattern.compile(
            "(?i)\"([^\"]+)\"\\s*,\\s*(SUM|AVERAGE|AVG|COUNT|MIN|MAX)\\s*\\(\\s*"
                    + "(?:'([^']+)'|([\\p{L}_][\\p{L}\\p{Nd}_]*))?\\[([^\\]]+)\\]\\s*\\)");

    private final SemanticModelManager semanticModelManager;
    private final InfoFunctionEvaluator infoFunctionEvaluator;
    private final DuckDbConnectionManager duckDbConnectionManager;

    public enum TableFormulaKind {
        DATA_DICTIONARY,
        CALENDAR_AUTO,
        CALENDAR,
        SUMMARIZE_COLUMNS;

        public static TableFormulaKind classify(String expression) {
            RowExpressionParser.requireNonBlank(expression, "expression");
            String upper = expression.toUpperCase(Locale.ROOT);
            if (upper.contains("INFO.VIEW.") || upper.contains("INFO.")) {
                return DATA_DICTIONARY;
            }
            if (upper.contains("CALENDARAUTO")) {
                return CALENDAR_AUTO;
            }
            if (upper.contains("CALENDAR")) {
                return CALENDAR;
            }
            if (upper.contains("SUMMARIZECOLUMNS")) {
                return SUMMARIZE_COLUMNS;
            }
            throw new IllegalArgumentException("Unsupported calculated table expression: " + expression);
        }
    }

    public record CalculatedTableRequest(
            UUID modelId,
            String tableName,
            @JsonAlias({"formula", "raxExpression"})
            String expression,
            List<Map<String, Object>> sourceRows
    ) {
        public CalculatedTableRequest {
            RowExpressionParser.requireNonBlank(tableName, "tableName");
            RowExpressionParser.requireNonBlank(expression, "expression");
            tableName = tableName.trim();
            expression = expression.trim();
            sourceRows = sourceRows != null ? List.copyOf(sourceRows) : List.of();
        }

        public CalculatedTableRequest(UUID modelId, String tableName, String expression) {
            this(modelId, tableName, expression, List.of());
        }

        public CalculatedTableRequest(String tableName, String expression) {
            this(null, tableName, expression, List.of());
        }
    }

    public record CalculatedTableResult(
            String tableName,
            List<ColumnDefinition> columns,
            List<Map<String, Object>> rows,
            boolean isDateTable,
            String dateColumn
    ) {
        public CalculatedTableResult {
            RowExpressionParser.requireNonBlank(tableName, "tableName");
            columns = columns != null ? List.copyOf(columns) : List.of();
            rows = rows != null ? List.copyOf(rows) : List.of();
            dateColumn = dateColumn != null ? dateColumn.trim() : "";
        }
    }

    private record DateRange(LocalDate startDate, LocalDate endDate) {}

    private record GroupColumnRef(String table, String column) {}

    private record AggregateAlias(String alias, AggregateFunction function, String sourceTable, String sourceCol) {}

    public CalculatedTableService() {
        this(new SemanticModelManager(), new InfoFunctionEvaluator(), null);
    }

    @Autowired
    public CalculatedTableService(
            SemanticModelManager semanticModelManager,
            InfoFunctionEvaluator infoFunctionEvaluator,
            @Autowired(required = false) DuckDbConnectionManager duckDbConnectionManager) {
        this.semanticModelManager = Objects.requireNonNull(semanticModelManager, "semanticModelManager must not be null");
        this.infoFunctionEvaluator = Objects.requireNonNull(infoFunctionEvaluator, "infoFunctionEvaluator must not be null");
        this.duckDbConnectionManager = duckDbConnectionManager;
    }

    public CalculatedTableResult buildCalculatedTableFromFormula(CalculatedTableRequest request) {
        Objects.requireNonNull(request, "CalculatedTableRequest must not be null");
        SemanticModelSchema schema = semanticModelManager.getModel(request.modelId());
        TableFormulaKind kind = TableFormulaKind.classify(request.expression());

        CalculatedTableResult result = switch (kind) {
            case DATA_DICTIONARY -> buildDataDictionaryTable(request.tableName(), schema);
            case CALENDAR -> buildExplicitCalendarTable(request.tableName(), request.expression());
            case CALENDAR_AUTO -> buildCalendarAutoTable(request, schema);
            case SUMMARIZE_COLUMNS -> buildSummarizeColumnsTable(request);
        };

        persistCalculatedTable(schema, result);
        return result;
    }

    public CalculatedTableResult buildCalculatedTableFromFormula(String tableName, String formula) {
        return buildCalculatedTableFromFormula(new CalculatedTableRequest(tableName, formula));
    }

    public List<Map<String, Object>> generateCalendarRowsRange(
            LocalDate startDate,
            LocalDate endDate,
            boolean includeOffsets) {
        Objects.requireNonNull(startDate, "startDate must not be null");
        Objects.requireNonNull(endDate, "endDate must not be null");
        if (endDate.isBefore(startDate)) {
            throw new IllegalArgumentException("endDate must not precede startDate: " + startDate + " > " + endDate);
        }

        long rawDays = ChronoUnit.DAYS.between(startDate, endDate) + 1L;
        int totalDays = (int) Math.min(Math.max(rawDays, 1L), MAX_CALENDAR_DAYS);
        LocalDate today = LocalDate.now();
        List<Map<String, Object>> rows = new ArrayList<>(totalDays);

        for (int i = 0; i < totalDays; i++) {
            LocalDate current = startDate.plusDays(i);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put(DATE_COL_NAME, current.toString());
            row.put("Year", current.getYear());
            row.put("Quarter", "Q" + (((current.getMonthValue() - 1) / 3) + 1));
            row.put("Month", current.getMonth().getDisplayName(TextStyle.SHORT, Locale.US));
            row.put("MonthNum", current.getMonthValue());
            row.put("YearMonth", current.format(YEAR_MONTH_FMT));
            row.put("Day", current.getDayOfMonth());
            if (includeOffsets) {
                row.put("Offset Day", (int) ChronoUnit.DAYS.between(today, current));
                row.put("Offset Month", (current.getYear() - today.getYear()) * 12
                        + (current.getMonthValue() - today.getMonthValue()));
                row.put("Offset Year", current.getYear() - today.getYear());
            }
            rows.add(Collections.unmodifiableMap(row));
        }
        return List.copyOf(rows);
    }

    private CalculatedTableResult buildDataDictionaryTable(
            String tableName,
            SemanticModelSchema schema) {
        List<ColumnDefinition> columns = List.of(
                createColumn("Type", "VARCHAR", "None"),
                createColumn("Name", "VARCHAR", "None"),
                createColumn("Table", "VARCHAR", "None"),
                createColumn("Description", "VARCHAR", "None"),
                createColumn("Rax", "VARCHAR", "None"));

        List<Map<String, Object>> rawDict = infoFunctionEvaluator.buildUnifiedDataDictionary(schema);
        List<Map<String, Object>> filtered = rawDict.stream()
                .filter(row -> !tableName.equalsIgnoreCase(String.valueOf(row.get("Table")))
                        && !tableName.equalsIgnoreCase(String.valueOf(row.get("Name"))))
                .toList();
        return new CalculatedTableResult(tableName, columns, filtered, false, "");
    }

    private CalculatedTableResult buildExplicitCalendarTable(String tableName, String expression) {
        boolean includeOffsets = hasOffsetColumns(expression);
        DateRange range = parseCalendarDateRange(expression);
        List<ColumnDefinition> columns = buildCalendarColumns(includeOffsets);
        List<Map<String, Object>> rows = generateCalendarRowsRange(range.startDate(), range.endDate(), includeOffsets);
        return new CalculatedTableResult(tableName, columns, rows, true, DATE_COL_NAME);
    }

    private CalculatedTableResult buildCalendarAutoTable(CalculatedTableRequest request, SemanticModelSchema schema) {
        int fiscalEndMonth = parseFiscalEndMonth(request.expression());
        boolean includeOffsets = hasOffsetColumns(request.expression());
        DateRange range = computeCalendarAutoRange(schema, request.sourceRows(), fiscalEndMonth);
        List<ColumnDefinition> columns = buildCalendarColumns(includeOffsets);
        List<Map<String, Object>> rows = generateCalendarRowsRange(range.startDate(), range.endDate(), includeOffsets);
        return new CalculatedTableResult(request.tableName(), columns, rows, true, DATE_COL_NAME);
    }

    private CalculatedTableResult buildSummarizeColumnsTable(CalculatedTableRequest request) {
        List<AggregateAlias> aliases = parseAggregateAliases(request.expression());
        Set<String> aggSourceCols = new LinkedHashSet<>();
        for (AggregateAlias agg : aliases) {
            aggSourceCols.add(agg.sourceCol().toLowerCase(Locale.ROOT));
        }

        List<GroupColumnRef> groupCols = parseGroupColumnRefs(request.expression(), aggSourceCols);
        List<ColumnDefinition> columns = new ArrayList<>();
        for (GroupColumnRef g : groupCols) {
            columns.add(createColumn(g.column(), "VARCHAR", "None"));
        }
        for (AggregateAlias agg : aliases) {
            columns.add(createColumn(agg.alias(), "DECIMAL", "Sum"));
        }
        if (columns.isEmpty()) {
            columns.add(createColumn("SummaryValue", "DECIMAL", "Sum"));
        }

        List<Map<String, Object>> sourceRows = resolveSummarizeSourceRows(request, groupCols, aliases);
        List<Map<String, Object>> aggRows = aggregateSummarizeRows(sourceRows, groupCols, aliases);
        return new CalculatedTableResult(request.tableName(), columns, aggRows, false, "");
    }

    private List<Map<String, Object>> resolveSummarizeSourceRows(
            CalculatedTableRequest request,
            List<GroupColumnRef> groupCols,
            List<AggregateAlias> aliases) {
        if (!request.sourceRows().isEmpty()) {
            return request.sourceRows();
        }
        if (duckDbConnectionManager == null) {
            return List.of();
        }
        String factTable = !aliases.isEmpty() && !aliases.get(0).sourceTable().isBlank()
                ? aliases.get(0).sourceTable()
                : (!groupCols.isEmpty() ? groupCols.get(0).table() : "");
        return !factTable.isBlank() ? duckDbConnectionManager.getTableRows(factTable) : List.of();
    }

    private List<Map<String, Object>> aggregateSummarizeRows(
            List<Map<String, Object>> sourceRows,
            List<GroupColumnRef> groupCols,
            List<AggregateAlias> aliases) {
        if (sourceRows.isEmpty()) {
            return List.of();
        }
        Map<String, Map<String, List<Double>>> numericBuckets = new LinkedHashMap<>();
        Map<String, Map<String, Object>> groupKeysByBucket = new LinkedHashMap<>();

        for (Map<String, Object> row : sourceRows) {
            StringBuilder keyBuilder = new StringBuilder();
            Map<String, Object> groupSeed = new LinkedHashMap<>();
            for (GroupColumnRef g : groupCols) {
                Object val = readFieldIgnoreCase(row, g.column());
                String valStr = val != null ? String.valueOf(val) : "";
                keyBuilder.append(valStr).append("||");
                groupSeed.put(g.column(), val != null ? val : "");
            }
            String bucketKey = keyBuilder.toString();
            groupKeysByBucket.putIfAbsent(bucketKey, groupSeed);
            Map<String, List<Double>> bucket = numericBuckets.computeIfAbsent(bucketKey, k -> new LinkedHashMap<>());
            for (AggregateAlias agg : aliases) {
                Double num = RowExpressionParser.tryParseDouble(readFieldIgnoreCase(row, agg.sourceCol()));
                if (num != null) {
                    bucket.computeIfAbsent(agg.alias(), k -> new ArrayList<>()).add(num);
                }
            }
        }

        List<Map<String, Object>> resultRows = new ArrayList<>(groupKeysByBucket.size());
        for (Map.Entry<String, Map<String, Object>> entry : groupKeysByBucket.entrySet()) {
            Map<String, Object> outRow = new LinkedHashMap<>(entry.getValue());
            Map<String, List<Double>> bucket = numericBuckets.getOrDefault(entry.getKey(), Map.of());
            for (AggregateAlias agg : aliases) {
                outRow.put(agg.alias(), agg.function().compute(bucket.getOrDefault(agg.alias(), List.of())));
            }
            resultRows.add(Collections.unmodifiableMap(outRow));
        }
        return List.copyOf(resultRows);
    }

    private void persistCalculatedTable(SemanticModelSchema schema, CalculatedTableResult result) {
        if (schema == null) {
            return;
        }
        TableDefinition tableDef = new TableDefinition(
                result.tableName(),
                result.isDateTable() ? "DIMENSION" : "FACT",
                null,
                result.rows().size(),
                false,
                "",
                false,
                result.isDateTable(),
                result.dateColumn(),
                result.columns());
        semanticModelManager.upsertTable(schema.modelId(), tableDef);
        if (duckDbConnectionManager != null && !result.rows().isEmpty()) {
            duckDbConnectionManager.ingestTableRows(result.tableName(), result.rows());
        }
    }

    private DateRange parseCalendarDateRange(String expression) {
        List<LocalDate> dates = new ArrayList<>();
        Matcher matcher = DATE_CALL_PATTERN.matcher(expression);
        while (matcher.find()) {
            int year = Integer.parseInt(matcher.group(1));
            int month = Integer.parseInt(matcher.group(2));
            int day = Integer.parseInt(matcher.group(3));
            dates.add(LocalDate.of(year, month, day));
        }
        if (dates.size() >= 2) {
            return new DateRange(dates.get(0), dates.get(1));
        }
        if (dates.size() == 1) {
            LocalDate start = dates.get(0);
            return new DateRange(start, LocalDate.of(start.getYear(), 12, 31));
        }
        int currentYear = LocalDate.now().getYear();
        return new DateRange(LocalDate.of(currentYear, 1, 1), LocalDate.of(currentYear, 12, 31));
    }

    private DateRange computeCalendarAutoRange(
            SemanticModelSchema schema,
            List<Map<String, Object>> explicitRows,
            int fiscalEndMonth) {
        List<LocalDate> discovered = new ArrayList<>();
        collectDatesFromRows(explicitRows, discovered);
        if (schema != null && schema.tables() != null && duckDbConnectionManager != null) {
            for (TableDefinition table : schema.tables()) {
                if (!table.isDateTable()) {
                    collectDatesFromRows(duckDbConnectionManager.getTableRows(table.name()), discovered);
                }
            }
        }
        int minYear = discovered.stream().mapToInt(LocalDate::getYear).min().orElse(LocalDate.now().getYear());
        int maxYear = discovered.stream().mapToInt(LocalDate::getYear).max().orElse(LocalDate.now().getYear());

        if (fiscalEndMonth == DEFAULT_FISCAL_END_MONTH) {
            return new DateRange(LocalDate.of(minYear, 1, 1), LocalDate.of(maxYear, 12, 31));
        }
        int startMonth = (fiscalEndMonth % 12) + 1;
        LocalDate start = LocalDate.of(minYear - 1, startMonth, 1);
        LocalDate end = LocalDate.of(maxYear, fiscalEndMonth, 1).withDayOfMonth(
                LocalDate.of(maxYear, fiscalEndMonth, 1).lengthOfMonth());
        return new DateRange(start, end);
    }

    private static void collectDatesFromRows(List<Map<String, Object>> rows, List<LocalDate> target) {
        if (rows == null || rows.isEmpty()) {
            return;
        }
        for (Map<String, Object> row : rows) {
            if (row == null) {
                continue;
            }
            for (Object val : row.values()) {
                LocalDate parsed = tryParseLocalDate(val);
                if (parsed != null) {
                    target.add(parsed);
                }
            }
        }
    }

    private static LocalDate tryParseLocalDate(Object val) {
        if (val instanceof LocalDate ld) {
            return ld;
        }
        if (!(val instanceof String text)) {
            return null;
        }
        String trimmed = text.trim();
        if (trimmed.length() < 10) {
            return null;
        }
        try {
            return LocalDate.parse(trimmed.substring(0, 10));
        } catch (DateTimeParseException ex) {
            return null;
        }
    }

    private static int parseFiscalEndMonth(String expression) {
        Matcher m = CALENDAR_AUTO_PATTERN.matcher(expression);
        if (m.matches() && !m.group(1).isBlank()) {
            int month = Integer.parseInt(m.group(1).trim());
            if (month < 1 || month > 12) {
                throw new IllegalArgumentException("CALENDARAUTO fiscalYearEndMonth must be between 1 and 12: " + month);
            }
            return month;
        }
        return DEFAULT_FISCAL_END_MONTH;
    }

    private static boolean hasOffsetColumns(String expression) {
        String upper = expression.toUpperCase(Locale.ROOT);
        return upper.contains("OFFSET") || upper.contains("DATEDIFF");
    }

    private static List<ColumnDefinition> buildCalendarColumns(boolean includeOffsets) {
        List<ColumnDefinition> cols = new ArrayList<>();
        cols.add(new ColumnDefinition(DATE_COL_NAME, "DATE", true, false, false, null, "", "", "None", "", "", "Date"));
        cols.add(new ColumnDefinition("Year", "INT", false, false, false, null, "", "", "None", "", "", "WholeNumber"));
        cols.add(createColumn("Quarter", "VARCHAR", "None"));
        cols.add(new ColumnDefinition("Month", "VARCHAR", false, false, false, null, "", "", "None", "", "MonthNum", ""));
        cols.add(new ColumnDefinition("MonthNum", "INT", false, false, true, null, "", "", "None", "", "", ""));
        cols.add(createColumn("YearMonth", "VARCHAR", "None"));
        cols.add(new ColumnDefinition("Day", "INT", false, false, false, null, "", "", "None", "", "", "WholeNumber"));
        if (includeOffsets) {
            cols.add(createColumn("Offset Day", "INT", "None"));
            cols.add(createColumn("Offset Month", "INT", "None"));
            cols.add(createColumn("Offset Year", "INT", "None"));
        }
        return List.copyOf(cols);
    }

    private static List<AggregateAlias> parseAggregateAliases(String expression) {
        List<AggregateAlias> aliases = new ArrayList<>();
        Matcher matcher = SUMMARIZE_ALIAS_PATTERN.matcher(expression);
        while (matcher.find()) {
            String alias = matcher.group(1).trim();
            AggregateFunction fn = AggregateFunction.fromToken(matcher.group(2));
            String table = matcher.group(3) != null ? matcher.group(3) : (matcher.group(4) != null ? matcher.group(4) : "");
            String col = matcher.group(5).trim();
            aliases.add(new AggregateAlias(alias, fn, table.trim(), col));
        }
        return aliases;
    }

    private static List<GroupColumnRef> parseGroupColumnRefs(String expression, Set<String> aggSourceCols) {
        List<GroupColumnRef> refs = new ArrayList<>();
        Matcher matcher = COL_REF_PATTERN.matcher(expression);
        while (matcher.find()) {
            String table = matcher.group(1) != null ? matcher.group(1) : (matcher.group(2) != null ? matcher.group(2) : "");
            String col = matcher.group(3).trim();
            if (!aggSourceCols.contains(col.toLowerCase(Locale.ROOT))) {
                refs.add(new GroupColumnRef(table.trim(), col));
            }
        }
        return refs;
    }

    private static Object readFieldIgnoreCase(Map<String, Object> row, String colName) {
        if (row.containsKey(colName)) {
            return row.get(colName);
        }
        for (Map.Entry<String, Object> entry : row.entrySet()) {
            if (entry.getKey() != null && entry.getKey().equalsIgnoreCase(colName)) {
                return entry.getValue();
            }
        }
        return null;
    }

    private static ColumnDefinition createColumn(String name, String dataType, String summarizeBy) {
        return new ColumnDefinition(name, dataType, false, false, false, null, "", "", summarizeBy, "", "", "");
    }
}
