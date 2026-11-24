package com.rollercoaster.model;

import com.rollercoaster.rax.ast.SqlDialect;
import com.rollercoaster.rax.compiler.RaxFormulaCompiler;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;

import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * HTTP-agnostic, domain-neutral service for Report Builder visual query resolution,
 * cross-filter and slicer merging, server-side sorting, chart series construction,
 * matrix rollups, and measure card KPI formatting.
 */
@Service
public class VisualAggregationService {

    private static final String DEFAULT_TENANT = "default-tenant";
    private static final String DEFAULT_REPORT = "default-report";
    private static final String MEASURE_VALUE_KEY = "measure_value";
    private static final String ROW_TOTAL_KEY = "rowTotal";
    private static final String DEFAULT_COUNT_LABEL = "Row Count";
    private static final String STATUS_OK = "OK";
    private static final String STATUS_EMPTY = "EMPTY";
    private static final int MAX_MEASURE_EXPANSION_DEPTH = 8;

    private final SemanticModelManager semanticModelManager;
    private final DualEngineCoordinator dualEngineCoordinator;
    private final RaxFormulaCompiler raxFormulaCompiler;

    public VisualAggregationService(
            SemanticModelManager semanticModelManager,
            DualEngineCoordinator dualEngineCoordinator,
            RaxFormulaCompiler raxFormulaCompiler) {
        this.semanticModelManager = Objects.requireNonNull(
                semanticModelManager, "semanticModelManager must not be null");
        this.dualEngineCoordinator = Objects.requireNonNull(
                dualEngineCoordinator, "dualEngineCoordinator must not be null");
        this.raxFormulaCompiler = Objects.requireNonNull(
                raxFormulaCompiler, "raxFormulaCompiler must not be null");
    }

    public sealed interface ArchetypePayload
            permits ChartSeriesPayload, MatrixRollupPayload, MeasureCardPayload, SlicerPayload {
    }

    public record ChartDataset(
            String label,
            List<Double> data,
            String measureField
    ) {
        public ChartDataset {
            label = label != null ? label : "";
            data = data != null ? List.copyOf(data) : List.of();
            measureField = measureField != null ? measureField : "";
        }
    }

    public record ChartSeriesMeta(
            String categoryField,
            String legendField,
            List<String> valueFields,
            double minValue,
            double maxValue,
            double totalValue
    ) {
        public static final ChartSeriesMeta EMPTY = new ChartSeriesMeta("", "", List.of(), 0.0, 0.0, 0.0);

        public ChartSeriesMeta {
            categoryField = categoryField != null ? categoryField : "";
            legendField = legendField != null ? legendField : "";
            valueFields = valueFields != null ? List.copyOf(valueFields) : List.of();
        }
    }

    public record ChartSeriesPayload(
            List<String> labels,
            List<ChartDataset> datasets,
            ChartSeriesMeta seriesMeta
    ) implements ArchetypePayload {
        public static final ChartSeriesPayload EMPTY = new ChartSeriesPayload(
                List.of(), List.of(), ChartSeriesMeta.EMPTY);

        public ChartSeriesPayload {
            labels = labels != null ? List.copyOf(labels) : List.of();
            datasets = datasets != null ? List.copyOf(datasets) : List.of();
            seriesMeta = seriesMeta != null ? seriesMeta : ChartSeriesMeta.EMPTY;
        }
    }

    public record MatrixRow(
            String rowKey,
            Map<String, Object> cells,
            double rowTotal
    ) {
        public MatrixRow {
            rowKey = rowKey != null ? rowKey : "";
            cells = cells != null ? Map.copyOf(cells) : Map.of();
        }

        public Map<String, Object> values() {
            return cells;
        }
    }

    public record MatrixRollupPayload(
            List<MatrixRow> rows,
            List<String> columnHeaders,
            Map<String, Double> totals,
            double grandTotal
    ) implements ArchetypePayload {
        public static final MatrixRollupPayload EMPTY = new MatrixRollupPayload(
                List.of(), List.of(), Map.of(), 0.0);

        public MatrixRollupPayload {
            rows = rows != null ? List.copyOf(rows) : List.of();
            columnHeaders = columnHeaders != null ? List.copyOf(columnHeaders) : List.of();
            totals = totals != null ? Map.copyOf(totals) : Map.of();
        }
    }

    public record MeasureCardPayload(
            String formattedValue,
            double rawValue,
            String label,
            String status
    ) implements ArchetypePayload {
        public static final MeasureCardPayload EMPTY = new MeasureCardPayload("0", 0.0, "", STATUS_EMPTY);

        public MeasureCardPayload {
            formattedValue = formattedValue != null ? formattedValue : "0";
            label = label != null ? label : "";
            status = (status != null && !status.isBlank()) ? status : STATUS_EMPTY;
        }
    }

    public record SlicerOption(
            String value,
            long count,
            boolean selected
    ) {
        public SlicerOption {
            value = value != null ? value : "";
        }
    }

    public record SlicerPayload(
            String field,
            List<SlicerOption> options,
            Set<Object> selectedValues
    ) implements ArchetypePayload {
        public static final SlicerPayload EMPTY = new SlicerPayload("", List.of(), Set.of());

        public SlicerPayload {
            field = field != null ? field : "";
            options = options != null ? List.copyOf(options) : List.of();
            selectedValues = selectedValues != null ? Set.copyOf(selectedValues) : Set.of();
        }
    }

    public record VisualAggregationPayload(
            String visualId,
            VisualArchetype archetype,
            String executedSql,
            SqlDialect routedEngine,
            boolean cacheHit,
            long executionTimeMs,
            List<Map<String, Object>> rows,
            ChartSeriesPayload chartSeries,
            MatrixRollupPayload matrix,
            MeasureCardPayload measureCard,
            SlicerPayload slicer
    ) {
        public VisualAggregationPayload {
            visualId = visualId != null ? visualId : "";
            archetype = archetype != null ? archetype : VisualArchetype.BAR;
            executedSql = executedSql != null ? executedSql : "";
            routedEngine = routedEngine != null ? routedEngine : SqlDialect.DUCKDB;
            rows = rows != null ? List.copyOf(rows) : List.of();
            chartSeries = chartSeries != null ? chartSeries : ChartSeriesPayload.EMPTY;
            matrix = matrix != null ? matrix : MatrixRollupPayload.EMPTY;
            measureCard = measureCard != null ? measureCard : MeasureCardPayload.EMPTY;
            slicer = slicer != null ? slicer : SlicerPayload.EMPTY;
        }

        public ArchetypePayload activePayload() {
            return switch (archetype) {
                case BAR, LINE, AREA, DONUT, GAUGE, GEO_MAP -> chartSeries;
                case MATRIX -> matrix;
                case MEASURE_CARD -> measureCard;
                case SLICER -> slicer;
            };
        }
    }

    public record VisualBatchQueryRequest(
            UUID modelId,
            String tenantId,
            String reportId,
            List<VisualQueryDefinition> visuals,
            Map<String, Set<Object>> pageFilters,
            Map<String, Set<Object>> crossFilters,
            Map<String, Set<Object>> slicerFilters
    ) {
        public VisualBatchQueryRequest {
            tenantId = (tenantId != null && !tenantId.isBlank()) ? tenantId : DEFAULT_TENANT;
            reportId = (reportId != null && !reportId.isBlank()) ? reportId : DEFAULT_REPORT;
            visuals = visuals != null ? List.copyOf(visuals) : List.of();
            pageFilters = pageFilters != null ? Map.copyOf(pageFilters) : Map.of();
            crossFilters = crossFilters != null ? Map.copyOf(crossFilters) : Map.of();
            slicerFilters = slicerFilters != null ? Map.copyOf(slicerFilters) : Map.of();
        }

        public VisualBatchQueryRequest(
                List<VisualQueryDefinition> visuals,
                Map<String, Set<Object>> pageFilters) {
            this(null, DEFAULT_TENANT, DEFAULT_REPORT, visuals, pageFilters, Map.of(), Map.of());
        }

        public VisualBatchQueryRequest(
                UUID modelId,
                String reportId,
                List<VisualQueryDefinition> visuals,
                Map<String, Set<Object>> pageFilters,
                Map<String, Set<Object>> crossFilters,
                Map<String, Set<Object>> slicerFilters) {
            this(modelId, DEFAULT_TENANT, reportId, visuals, pageFilters, crossFilters, slicerFilters);
        }
    }

    public record VisualBatchQueryResponse(
            String reportId,
            Map<String, VisualAggregationPayload> payloads,
            long totalExecutionTimeMs
    ) {
        public VisualBatchQueryResponse {
            reportId = reportId != null ? reportId : DEFAULT_REPORT;
            payloads = payloads != null ? Map.copyOf(payloads) : Map.of();
        }

        public VisualAggregationPayload getPayload(String visualId) {
            return payloads.get(visualId);
        }

        public Map<String, VisualAggregationPayload> visuals() {
            return payloads;
        }

        public Map<String, VisualAggregationPayload> results() {
            return payloads;
        }
    }

    private record ResolvedMeasureBinding(
            String rawBinding,
            String label,
            String raxExpression,
            String formatString,
            String tableName,
            String aliasKey
    ) {
    }

    private record ResolvedVisualContext(
            VisualQueryDefinition query,
            SemanticModelSchema model,
            String primaryTable,
            List<ResolvedMeasureBinding> measures,
            List<String> rowDimensions,
            List<String> columnDimensions,
            String categoryDimension,
            String legendDimension,
            List<String> groupByColumns
    ) {
    }

    private record MultiMeasureResult(
            QueryExecutionResult primaryExecution,
            List<Map<String, Object>> mergedRows
    ) {
    }

    public VisualBatchQueryResponse executeBatch(VisualBatchQueryRequest request) {
        return executeBatch(request, null);
    }

    public VisualBatchQueryResponse executeBatch(
            VisualBatchQueryRequest request,
            Authentication authentication) {
        if (request == null) {
            throw new IllegalArgumentException("VisualBatchQueryRequest must not be null.");
        }
        if (request.visuals().isEmpty()) {
            throw new IllegalArgumentException("At least one visual query definition is required in a batch request.");
        }

        long startNanos = System.nanoTime();
        Map<String, VisualAggregationPayload> results = new LinkedHashMap<>();

        for (VisualQueryDefinition item : request.visuals()) {
            if (item == null) {
                throw new IllegalArgumentException("Visual query item in batch must not be null.");
            }
            VisualQueryDefinition mergedItem = mergeBatchContext(item, request);
            VisualAggregationPayload payload = aggregateVisual(mergedItem, authentication);
            results.put(payload.visualId(), payload);
        }

        long elapsedMs = Math.max(1L, (System.nanoTime() - startNanos) / 1_000_000L);
        return new VisualBatchQueryResponse(request.reportId(), results, elapsedMs);
    }

    public VisualAggregationPayload aggregateVisual(VisualQueryDefinition query) {
        return aggregateVisual(query, null);
    }

    public VisualAggregationPayload aggregateVisual(
            VisualQueryDefinition query,
            Authentication authentication) {
        if (query == null) {
            throw new IllegalArgumentException("VisualQueryDefinition must not be null.");
        }
        SemanticModelSchema model = semanticModelManager.getModel(query.modelId());
        ResolvedVisualContext context = resolveVisualContext(query, model);
        MultiMeasureResult execution = executeMeasures(context, authentication);

        SortSpec effectiveSort = resolveEffectiveSortSpec(context);
        List<Map<String, Object>> sortedRows = applySortToPayload(execution.mergedRows(), effectiveSort);
        ArchetypePayload archetypePayload = computeArchetypePayload(context, sortedRows);

        QueryExecutionResult primaryResult = execution.primaryExecution();
        return switch (archetypePayload) {
            case ChartSeriesPayload chart -> new VisualAggregationPayload(
                    query.visualId(),
                    query.archetype(),
                    primaryResult.executedSql(),
                    primaryResult.routedEngine(),
                    primaryResult.cacheHit(),
                    primaryResult.executionTimeMs(),
                    sortedRows,
                    chart,
                    MatrixRollupPayload.EMPTY,
                    MeasureCardPayload.EMPTY,
                    SlicerPayload.EMPTY);
            case MatrixRollupPayload matrix -> new VisualAggregationPayload(
                    query.visualId(),
                    query.archetype(),
                    primaryResult.executedSql(),
                    primaryResult.routedEngine(),
                    primaryResult.cacheHit(),
                    primaryResult.executionTimeMs(),
                    sortedRows,
                    ChartSeriesPayload.EMPTY,
                    matrix,
                    MeasureCardPayload.EMPTY,
                    SlicerPayload.EMPTY);
            case MeasureCardPayload card -> new VisualAggregationPayload(
                    query.visualId(),
                    query.archetype(),
                    primaryResult.executedSql(),
                    primaryResult.routedEngine(),
                    primaryResult.cacheHit(),
                    primaryResult.executionTimeMs(),
                    sortedRows,
                    ChartSeriesPayload.EMPTY,
                    MatrixRollupPayload.EMPTY,
                    card,
                    SlicerPayload.EMPTY);
            case SlicerPayload slicer -> new VisualAggregationPayload(
                    query.visualId(),
                    query.archetype(),
                    primaryResult.executedSql(),
                    primaryResult.routedEngine(),
                    primaryResult.cacheHit(),
                    primaryResult.executionTimeMs(),
                    sortedRows,
                    buildSlicerChartFallback(slicer),
                    MatrixRollupPayload.EMPTY,
                    MeasureCardPayload.EMPTY,
                    slicer);
        };
    }

    /**
     * Applies server-side sorting to a list of aggregated result rows based on {@link SortSpec}.
     */
    public List<Map<String, Object>> applySortToPayload(
            List<Map<String, Object>> rows,
            SortSpec sortSpec) {
        if (rows == null || rows.isEmpty()) {
            return List.of();
        }
        if (sortSpec == null) {
            return List.copyOf(rows);
        }
        String targetField = sortSpec.field();
        SortDirection direction = sortSpec.direction() != null ? sortSpec.direction() : SortDirection.DESC;
        int multiplier = switch (direction) {
            case ASC -> 1;
            case DESC -> -1;
        };

        List<Map<String, Object>> mutable = new ArrayList<>(rows);
        mutable.sort((rowA, rowB) -> {
            Object valA = extractSortValue(rowA, targetField);
            Object valB = extractSortValue(rowB, targetField);
            return compareCellValues(valA, valB) * multiplier;
        });
        return List.copyOf(mutable);
    }

    /**
     * Re-sorts an already computed {@link VisualAggregationPayload} using the given {@link SortSpec}.
     */
    public VisualAggregationPayload applySortToPayload(
            VisualAggregationPayload payload,
            SortSpec sortSpec) {
        Objects.requireNonNull(payload, "payload must not be null");
        if (sortSpec == null) {
            return payload;
        }
        List<Map<String, Object>> sortedRows = applySortToPayload(payload.rows(), sortSpec);
        return switch (payload.archetype()) {
            case BAR, LINE, AREA, DONUT, GAUGE, GEO_MAP -> {
                ChartSeriesPayload resortedChart = resortChartSeries(payload.chartSeries(), sortedRows);
                yield new VisualAggregationPayload(
                        payload.visualId(), payload.archetype(), payload.executedSql(),
                        payload.routedEngine(), payload.cacheHit(), payload.executionTimeMs(),
                        sortedRows, resortedChart, payload.matrix(), payload.measureCard(), payload.slicer());
            }
            case MATRIX -> {
                MatrixRollupPayload resortedMatrix = resortMatrixRows(payload.matrix(), sortSpec);
                yield new VisualAggregationPayload(
                        payload.visualId(), payload.archetype(), payload.executedSql(),
                        payload.routedEngine(), payload.cacheHit(), payload.executionTimeMs(),
                        sortedRows, payload.chartSeries(), resortedMatrix, payload.measureCard(), payload.slicer());
            }
            case MEASURE_CARD -> payload;
            case SLICER -> {
                SlicerPayload resortedSlicer = resortSlicerOptions(payload.slicer(), sortSpec);
                yield new VisualAggregationPayload(
                        payload.visualId(), payload.archetype(), payload.executedSql(),
                        payload.routedEngine(), payload.cacheHit(), payload.executionTimeMs(),
                        sortedRows, buildSlicerChartFallback(resortedSlicer), payload.matrix(),
                        payload.measureCard(), resortedSlicer);
            }
        };
    }

    private ArchetypePayload computeArchetypePayload(
            ResolvedVisualContext context,
            List<Map<String, Object>> sortedRows) {
        return switch (context.query().archetype()) {
            case BAR, LINE, AREA, DONUT, GAUGE, GEO_MAP -> buildChartSeriesPayload(context, sortedRows);
            case MATRIX -> buildMatrixRollupPayload(context, sortedRows);
            case MEASURE_CARD -> buildMeasureCardPayload(context, sortedRows);
            case SLICER -> buildSlicerPayload(context, sortedRows);
        };
    }

    private ChartSeriesPayload buildChartSeriesPayload(
            ResolvedVisualContext context,
            List<Map<String, Object>> sortedRows) {
        String categoryAlias = extractAliasName(context.categoryDimension());
        String legendAlias = extractAliasName(context.legendDimension());
        List<String> valueFields = context.measures().stream()
                .map(ResolvedMeasureBinding::label)
                .toList();

        if (sortedRows.isEmpty()) {
            List<ChartDataset> emptyDatasets = context.measures().stream()
                    .map(m -> new ChartDataset(m.label(), List.of(), m.rawBinding()))
                    .toList();
            return new ChartSeriesPayload(
                    List.of(),
                    emptyDatasets,
                    new ChartSeriesMeta(categoryAlias, legendAlias, valueFields, 0.0, 0.0, 0.0));
        }

        if (!legendAlias.isEmpty()) {
            return buildLegendPivotedChartSeries(context, sortedRows);
        }

        List<String> labels = new ArrayList<>();
        if (categoryAlias.isEmpty()) {
            String fallbackLabel = context.measures().isEmpty()
                    ? DEFAULT_COUNT_LABEL
                    : context.measures().getFirst().label();
            labels.add(fallbackLabel);
        } else {
            for (Map<String, Object> row : sortedRows) {
                Object rawCat = findRowValue(row, categoryAlias);
                labels.add(rawCat != null ? rawCat.toString() : "Unknown");
            }
        }

        List<ChartDataset> datasets = new ArrayList<>();
        double minVal = Double.POSITIVE_INFINITY;
        double maxVal = Double.NEGATIVE_INFINITY;
        double totalVal = 0.0;

        for (int i = 0; i < context.measures().size(); i++) {
            ResolvedMeasureBinding measure = context.measures().get(i);
            String valueKey = i == 0 ? MEASURE_VALUE_KEY : measure.aliasKey();
            List<Double> points = new ArrayList<>(labels.size());

            if (categoryAlias.isEmpty()) {
                double sum = 0.0;
                for (Map<String, Object> row : sortedRows) {
                    sum += toDoubleValue(findMeasureValue(row, valueKey, measure));
                }
                points.add(sum);
                minVal = Math.min(minVal, sum);
                maxVal = Math.max(maxVal, sum);
                totalVal += sum;
            } else {
                for (Map<String, Object> row : sortedRows) {
                    double val = toDoubleValue(findMeasureValue(row, valueKey, measure));
                    points.add(val);
                    minVal = Math.min(minVal, val);
                    maxVal = Math.max(maxVal, val);
                    totalVal += val;
                }
            }
            datasets.add(new ChartDataset(measure.label(), points, measure.rawBinding()));
        }

        if (minVal == Double.POSITIVE_INFINITY) {
            minVal = 0.0;
            maxVal = 0.0;
        }
        return new ChartSeriesPayload(
                labels,
                datasets,
                new ChartSeriesMeta(categoryAlias, legendAlias, valueFields, minVal, maxVal, totalVal));
    }

    private ChartSeriesPayload buildLegendPivotedChartSeries(
            ResolvedVisualContext context,
            List<Map<String, Object>> sortedRows) {
        String categoryAlias = extractAliasName(context.categoryDimension());
        String legendAlias = extractAliasName(context.legendDimension());
        ResolvedMeasureBinding primaryMeasure = context.measures().getFirst();

        Set<String> distinctLabels = new LinkedHashSet<>();
        Set<String> distinctLegends = new LinkedHashSet<>();
        Map<String, Map<String, Double>> pivot = new LinkedHashMap<>();

        for (Map<String, Object> row : sortedRows) {
            Object rawCat = findRowValue(row, categoryAlias);
            Object rawLeg = findRowValue(row, legendAlias);
            String catKey = rawCat != null ? rawCat.toString() : "Unknown";
            String legKey = rawLeg != null ? rawLeg.toString() : "Default";
            double val = toDoubleValue(findMeasureValue(row, MEASURE_VALUE_KEY, primaryMeasure));

            distinctLabels.add(catKey);
            distinctLegends.add(legKey);
            pivot.computeIfAbsent(legKey, k -> new LinkedHashMap<>()).merge(catKey, val, Double::sum);
        }

        List<String> labels = List.copyOf(distinctLabels);
        List<ChartDataset> datasets = new ArrayList<>(distinctLegends.size());
        double minVal = Double.POSITIVE_INFINITY;
        double maxVal = Double.NEGATIVE_INFINITY;
        double totalVal = 0.0;

        for (String legendKey : distinctLegends) {
            Map<String, Double> byCategory = pivot.getOrDefault(legendKey, Map.of());
            List<Double> points = new ArrayList<>(labels.size());
            for (String label : labels) {
                double val = byCategory.getOrDefault(label, 0.0);
                points.add(val);
                minVal = Math.min(minVal, val);
                maxVal = Math.max(maxVal, val);
                totalVal += val;
            }
            datasets.add(new ChartDataset(legendKey, points, primaryMeasure.rawBinding()));
        }

        if (minVal == Double.POSITIVE_INFINITY) {
            minVal = 0.0;
            maxVal = 0.0;
        }
        List<String> valueFields = context.measures().stream().map(ResolvedMeasureBinding::label).toList();
        return new ChartSeriesPayload(
                labels,
                datasets,
                new ChartSeriesMeta(categoryAlias, legendAlias, valueFields, minVal, maxVal, totalVal));
    }

    private MatrixRollupPayload buildMatrixRollupPayload(
            ResolvedVisualContext context,
            List<Map<String, Object>> sortedRows) {
        List<String> rowAliases = context.rowDimensions().stream()
                .map(VisualAggregationService::extractAliasName)
                .filter(s -> !s.isEmpty())
                .toList();
        List<String> colAliases = context.columnDimensions().stream()
                .map(VisualAggregationService::extractAliasName)
                .filter(s -> !s.isEmpty())
                .toList();

        if (!colAliases.isEmpty()) {
            return buildPivotedMatrixRollup(context, sortedRows, rowAliases);
        }
        return buildTabularMatrixRollup(context, sortedRows, rowAliases);
    }

    private MatrixRollupPayload buildPivotedMatrixRollup(
            ResolvedVisualContext context,
            List<Map<String, Object>> sortedRows,
            List<String> rowAliases) {
        String colAlias = extractAliasName(context.columnDimensions().getFirst());
        ResolvedMeasureBinding primaryMeasure = context.measures().getFirst();

        Set<String> distinctColHeaders = new LinkedHashSet<>();
        Map<String, Map<String, Object>> rowDimensionValues = new LinkedHashMap<>();
        Map<String, Map<String, Double>> rowPivotCells = new LinkedHashMap<>();

        for (Map<String, Object> row : sortedRows) {
            String rowKey = buildCompositeRowKey(row, rowAliases);
            Object rawCol = findRowValue(row, colAlias);
            String colHeader = rawCol != null ? rawCol.toString() : "Total";
            double val = toDoubleValue(findMeasureValue(row, MEASURE_VALUE_KEY, primaryMeasure));

            distinctColHeaders.add(colHeader);
            Map<String, Object> dimVals = rowDimensionValues.computeIfAbsent(rowKey, k -> new LinkedHashMap<>());
            for (String rAlias : rowAliases) {
                Object rVal = findRowValue(row, rAlias);
                if (rVal != null) {
                    dimVals.put(rAlias, rVal);
                }
            }
            rowPivotCells.computeIfAbsent(rowKey, k -> new LinkedHashMap<>())
                    .merge(colHeader, val, Double::sum);
        }

        List<String> columnHeaders = List.copyOf(distinctColHeaders);
        Map<String, Double> totals = new LinkedHashMap<>();
        for (String header : columnHeaders) {
            totals.put(header, 0.0);
        }

        List<MatrixRow> matrixRows = new ArrayList<>(rowPivotCells.size());
        double grandTotal = 0.0;

        for (Map.Entry<String, Map<String, Double>> entry : rowPivotCells.entrySet()) {
            String rowKey = entry.getKey();
            Map<String, Double> pivotVals = entry.getValue();
            Map<String, Object> cells = new LinkedHashMap<>(rowDimensionValues.getOrDefault(rowKey, Map.of()));
            double rowTotal = 0.0;

            for (String header : columnHeaders) {
                double cellVal = pivotVals.getOrDefault(header, 0.0);
                cells.put(header, cellVal);
                rowTotal += cellVal;
                totals.merge(header, cellVal, Double::sum);
            }
            cells.put(ROW_TOTAL_KEY, rowTotal);
            grandTotal += rowTotal;
            matrixRows.add(new MatrixRow(rowKey, cells, rowTotal));
        }

        totals.put(primaryMeasure.label(), grandTotal);
        totals.put(primaryMeasure.aliasKey(), grandTotal);
        totals.put(MEASURE_VALUE_KEY, grandTotal);

        MatrixRollupPayload unsorted = new MatrixRollupPayload(matrixRows, columnHeaders, totals, grandTotal);
        return context.query().sortSpec() != null ? resortMatrixRows(unsorted, context.query().sortSpec()) : unsorted;
    }

    private MatrixRollupPayload buildTabularMatrixRollup(
            ResolvedVisualContext context,
            List<Map<String, Object>> sortedRows,
            List<String> rowAliases) {
        List<String> columnHeaders = context.measures().stream()
                .map(ResolvedMeasureBinding::label)
                .toList();
        Map<String, Double> totals = new LinkedHashMap<>();
        for (ResolvedMeasureBinding measure : context.measures()) {
            totals.put(measure.label(), 0.0);
        }

        List<MatrixRow> matrixRows = new ArrayList<>(sortedRows.size());
        double grandTotal = 0.0;

        for (Map<String, Object> row : sortedRows) {
            String rowKey = buildCompositeRowKey(row, rowAliases);
            Map<String, Object> cells = new LinkedHashMap<>();
            for (String rAlias : rowAliases) {
                Object rVal = findRowValue(row, rAlias);
                if (rVal != null) {
                    cells.put(rAlias, rVal);
                }
            }

            double rowTotal = 0.0;
            for (int i = 0; i < context.measures().size(); i++) {
                ResolvedMeasureBinding measure = context.measures().get(i);
                String lookupKey = i == 0 ? MEASURE_VALUE_KEY : measure.aliasKey();
                double val = toDoubleValue(findMeasureValue(row, lookupKey, measure));
                cells.put(measure.label(), val);
                cells.put(measure.aliasKey(), val);
                if (i == 0) {
                    cells.put(MEASURE_VALUE_KEY, val);
                }
                rowTotal += val;
                totals.merge(measure.label(), val, Double::sum);
                totals.put(measure.aliasKey(), totals.get(measure.label()));
                if (i == 0) {
                    totals.put(MEASURE_VALUE_KEY, totals.get(measure.label()));
                }
            }
            cells.put(ROW_TOTAL_KEY, rowTotal);
            grandTotal += rowTotal;
            matrixRows.add(new MatrixRow(rowKey, cells, rowTotal));
        }

        return new MatrixRollupPayload(matrixRows, columnHeaders, totals, grandTotal);
    }

    private MeasureCardPayload buildMeasureCardPayload(
            ResolvedVisualContext context,
            List<Map<String, Object>> sortedRows) {
        ResolvedMeasureBinding primaryMeasure = context.measures().getFirst();
        if (sortedRows.isEmpty()) {
            return new MeasureCardPayload(
                    formatMeasureValue(0.0, primaryMeasure.formatString()),
                    0.0,
                    primaryMeasure.label(),
                    STATUS_EMPTY);
        }

        double rawValue = 0.0;
        for (Map<String, Object> row : sortedRows) {
            rawValue += toDoubleValue(findMeasureValue(row, MEASURE_VALUE_KEY, primaryMeasure));
        }
        String formatted = formatMeasureValue(rawValue, primaryMeasure.formatString());
        return new MeasureCardPayload(formatted, rawValue, primaryMeasure.label(), STATUS_OK);
    }

    private SlicerPayload buildSlicerPayload(
            ResolvedVisualContext context,
            List<Map<String, Object>> sortedRows) {
        String slicerField = context.categoryDimension();
        String slicerAlias = extractAliasName(slicerField);
        Set<Object> selected = resolveSelectedSlicerValues(context.query(), slicerField, slicerAlias);

        List<SlicerOption> options = new ArrayList<>(sortedRows.size());
        ResolvedMeasureBinding countMeasure = context.measures().getFirst();
        for (Map<String, Object> row : sortedRows) {
            Object rawVal = findRowValue(row, slicerAlias);
            if (rawVal == null) {
                continue;
            }
            String strVal = rawVal.toString();
            long count = Math.round(toDoubleValue(findMeasureValue(row, MEASURE_VALUE_KEY, countMeasure)));
            boolean isSelected = selected.contains(rawVal) || selected.contains(strVal);
            options.add(new SlicerOption(strVal, count, isSelected));
        }
        return new SlicerPayload(slicerField, options, selected);
    }

    private static ChartSeriesPayload buildSlicerChartFallback(SlicerPayload slicer) {
        List<String> labels = slicer.options().stream().map(SlicerOption::value).toList();
        List<Double> counts = slicer.options().stream().map(o -> (double) o.count()).toList();
        double min = counts.stream().mapToDouble(Double::doubleValue).min().orElse(0.0);
        double max = counts.stream().mapToDouble(Double::doubleValue).max().orElse(0.0);
        double total = counts.stream().mapToDouble(Double::doubleValue).sum();
        String alias = extractAliasName(slicer.field());
        return new ChartSeriesPayload(
                labels,
                List.of(new ChartDataset(DEFAULT_COUNT_LABEL, counts, slicer.field())),
                new ChartSeriesMeta(alias, "", List.of(DEFAULT_COUNT_LABEL), min, max, total));
    }

    private MultiMeasureResult executeMeasures(
            ResolvedVisualContext context,
            Authentication authentication) {
        List<ResolvedMeasureBinding> measures = context.measures();
        VisualQueryDefinition primaryQuery = buildEngineQuery(context, measures.getFirst());
        QueryExecutionResult primaryResult = dualEngineCoordinator.executeVisualQuery(primaryQuery, authentication);

        if (measures.size() <= 1 || context.groupByColumns().isEmpty()) {
            return new MultiMeasureResult(primaryResult, primaryResult.rows());
        }

        List<String> groupAliases = context.groupByColumns().stream()
                .map(VisualAggregationService::extractAliasName)
                .filter(s -> !s.isEmpty())
                .toList();

        Map<String, Map<String, Object>> mergedByKey = new LinkedHashMap<>();
        ResolvedMeasureBinding firstMeasure = measures.getFirst();
        for (Map<String, Object> row : primaryResult.rows()) {
            String key = buildCompositeRowKey(row, groupAliases);
            Map<String, Object> copy = new LinkedHashMap<>(row);
            Object val = findRowValue(row, MEASURE_VALUE_KEY);
            copy.put(firstMeasure.aliasKey(), val);
            copy.put(firstMeasure.label(), val);
            mergedByKey.put(key, copy);
        }

        for (int i = 1; i < measures.size(); i++) {
            ResolvedMeasureBinding extraMeasure = measures.get(i);
            VisualQueryDefinition extraQuery = buildEngineQuery(context, extraMeasure);
            QueryExecutionResult extraResult = dualEngineCoordinator.executeVisualQuery(extraQuery, authentication);
            for (Map<String, Object> row : extraResult.rows()) {
                String key = buildCompositeRowKey(row, groupAliases);
                Map<String, Object> target = mergedByKey.computeIfAbsent(key, k -> {
                    Map<String, Object> base = new LinkedHashMap<>();
                    for (String gAlias : groupAliases) {
                        base.put(gAlias, findRowValue(row, gAlias));
                    }
                    base.put(MEASURE_VALUE_KEY, 0.0);
                    return base;
                });
                Object val = findRowValue(row, MEASURE_VALUE_KEY);
                target.put(extraMeasure.aliasKey(), val);
                target.put(extraMeasure.label(), val);
            }
        }

        return new MultiMeasureResult(primaryResult, List.copyOf(mergedByKey.values()));
    }

    private VisualQueryDefinition buildEngineQuery(
            ResolvedVisualContext context,
            ResolvedMeasureBinding measure) {
        VisualQueryDefinition original = context.query();
        Map<String, Set<Object>> mergedActive = mergeAndNormalizeFilters(
                original.activeFilters(), original.crossFilters(), context);
        Map<String, Set<Object>> normalizedSlicers = normalizeSlicerFiltersForVisual(
                original.externalSlicerFilters(), context);

        return new VisualQueryDefinition(
                context.model().modelId(),
                original.tenantId(),
                original.reportId(),
                original.visualId(),
                original.archetype(),
                original.values(),
                original.category(),
                original.legend(),
                original.rows(),
                original.columns(),
                original.sortSpec(),
                null,
                measure.raxExpression(),
                context.primaryTable(),
                original.estimatedRows(),
                original.operationalLookup(),
                context.groupByColumns(),
                mergedActive,
                original.crossFilters(),
                normalizedSlicers,
                original.evaluationDate(),
                original.acceptLanguage()
        );
    }

    private ResolvedVisualContext resolveVisualContext(
            VisualQueryDefinition query,
            SemanticModelSchema model) {
        String initialPrimaryTable = resolvePrimaryTable(query, model);
        List<ResolvedMeasureBinding> measures = resolveMeasureBindings(query, model, initialPrimaryTable);
        String primaryTable = (!measures.isEmpty() && !measures.getFirst().tableName().isBlank())
                ? measures.getFirst().tableName()
                : initialPrimaryTable;

        String categoryDim = resolveCategoryDimension(query, model, primaryTable);
        String legendDim = !query.legend().isEmpty()
                ? qualifyColumnReference(query.legend().getFirst(), model, primaryTable)
                : "";
        List<String> rowDims = resolveMatrixRowDimensions(query, model, primaryTable);
        List<String> colDims = query.columns().stream()
                .map(c -> qualifyColumnReference(c, model, primaryTable))
                .filter(s -> !s.isEmpty())
                .toList();

        List<String> groupByCols = resolveGroupByColumns(
                query,
                new DimensionResolution(categoryDim, legendDim, rowDims, colDims),
                new TableContext(model, primaryTable));

        return new ResolvedVisualContext(
                query,
                model,
                primaryTable,
                measures,
                rowDims,
                colDims,
                categoryDim,
                legendDim,
                groupByCols);
    }

    private record DimensionResolution(
            String categoryDim,
            String legendDim,
            List<String> rowDims,
            List<String> colDims
    ) {
    }

    private record TableContext(
            SemanticModelSchema model,
            String primaryTable
    ) {
    }

    private List<String> resolveGroupByColumns(
            VisualQueryDefinition query,
            DimensionResolution dims,
            TableContext tableCtx) {
        Set<String> groupCols = new LinkedHashSet<>();
        for (String explicit : query.groupByColumns()) {
            String qualified = qualifyColumnReference(explicit, tableCtx.model(), tableCtx.primaryTable());
            if (!qualified.isEmpty()) {
                groupCols.add(qualified);
            }
        }

        switch (query.archetype()) {
            case BAR, LINE, AREA, DONUT, GEO_MAP -> {
                if (!dims.categoryDim().isEmpty()) {
                    groupCols.add(dims.categoryDim());
                }
                if (!dims.legendDim().isEmpty()) {
                    groupCols.add(dims.legendDim());
                }
            }
            case MATRIX -> {
                groupCols.addAll(dims.rowDims());
                groupCols.addAll(dims.colDims());
            }
            case SLICER -> {
                if (!dims.categoryDim().isEmpty()) {
                    groupCols.add(dims.categoryDim());
                }
            }
            case GAUGE, MEASURE_CARD -> {
                // Scalar KPI archetypes only group if groupByColumns was explicitly supplied
            }
        }
        return List.copyOf(groupCols);
    }

    private String resolveCategoryDimension(
            VisualQueryDefinition query,
            SemanticModelSchema model,
            String primaryTable) {
        if (!query.category().isEmpty()) {
            return qualifyColumnReference(query.category().getFirst(), model, primaryTable);
        }
        if (!query.groupByColumns().isEmpty()) {
            return qualifyColumnReference(query.groupByColumns().getFirst(), model, primaryTable);
        }
        if (query.archetype() == VisualArchetype.SLICER) {
            if (!query.values().isEmpty()) {
                return qualifyColumnReference(query.values().getFirst(), model, primaryTable);
            }
            if (!query.rows().isEmpty()) {
                return qualifyColumnReference(query.rows().getFirst(), model, primaryTable);
            }
        }
        if (!query.archetype().requiresDimensionGrouping() || query.archetype() == VisualArchetype.MATRIX) {
            return "";
        }
        return inferDefaultDimension(query, model, primaryTable);
    }

    private List<String> resolveMatrixRowDimensions(
            VisualQueryDefinition query,
            SemanticModelSchema model,
            String primaryTable) {
        if (query.archetype() != VisualArchetype.MATRIX) {
            return List.of();
        }
        List<String> source = !query.rows().isEmpty()
                ? query.rows()
                : (!query.category().isEmpty() ? query.category() : query.groupByColumns());
        if (!source.isEmpty()) {
            return source.stream()
                    .map(r -> qualifyColumnReference(r, model, primaryTable))
                    .filter(s -> !s.isEmpty())
                    .toList();
        }
        String inferred = inferDefaultDimension(query, model, primaryTable);
        return inferred.isEmpty() ? List.of() : List.of(inferred);
    }

    private String inferDefaultDimension(
            VisualQueryDefinition query,
            SemanticModelSchema model,
            String primaryTable) {
        if (model == null || model.tables().isEmpty()) {
            return "";
        }
        Set<String> boundValueCols = collectBoundValueColumnNames(query);
        TableDefinition targetTable = findTable(model, primaryTable).orElse(model.tables().getFirst());

        if (query.archetype() == VisualArchetype.LINE || query.archetype() == VisualArchetype.AREA) {
            String dateDim = findDateDimension(model, targetTable);
            if (!dateDim.isEmpty()) {
                return dateDim;
            }
        }
        if (query.archetype() == VisualArchetype.GEO_MAP) {
            String geoDim = findGeoDimension(targetTable);
            if (!geoDim.isEmpty()) {
                return geoDim;
            }
        }

        for (ColumnDefinition col : targetTable.columns()) {
            if (!col.isHidden()
                    && !col.isPrimaryKey()
                    && !col.isForeignKey()
                    && !isNumericDataType(col.dataType())
                    && !boundValueCols.contains(col.name().toLowerCase(Locale.ROOT))) {
                return sanitizeTableAndCol(targetTable.name(), col.name());
            }
        }
        for (ColumnDefinition col : targetTable.columns()) {
            if (!col.isHidden() && !boundValueCols.contains(col.name().toLowerCase(Locale.ROOT))) {
                return sanitizeTableAndCol(targetTable.name(), col.name());
            }
        }
        if (!targetTable.columns().isEmpty()) {
            return sanitizeTableAndCol(targetTable.name(), targetTable.columns().getFirst().name());
        }
        return "";
    }

    private static String findDateDimension(SemanticModelSchema model, TableDefinition primaryTable) {
        if (primaryTable.isDateTable() && !primaryTable.dateColumn().isBlank()) {
            return sanitizeTableAndCol(primaryTable.name(), primaryTable.dateColumn());
        }
        if (primaryTable.partitionColumn() != null && !primaryTable.partitionColumn().isBlank()) {
            return sanitizeTableAndCol(primaryTable.name(), primaryTable.partitionColumn());
        }
        for (ColumnDefinition col : primaryTable.columns()) {
            String dt = col.dataType() != null ? col.dataType().toUpperCase(Locale.ROOT) : "";
            if (dt.contains("DATE") || dt.contains("TIME")) {
                return sanitizeTableAndCol(primaryTable.name(), col.name());
            }
        }
        for (TableDefinition table : model.tables()) {
            if (table.isDateTable() && !table.dateColumn().isBlank()) {
                return sanitizeTableAndCol(table.name(), table.dateColumn());
            }
        }
        return "";
    }

    private static String findGeoDimension(TableDefinition table) {
        Set<String> geoTokens = Set.of("region", "country", "state", "city", "iso", "location", "zone", "territory");
        for (ColumnDefinition col : table.columns()) {
            String lower = col.name().toLowerCase(Locale.ROOT);
            for (String token : geoTokens) {
                if (lower.contains(token)) {
                    return sanitizeTableAndCol(table.name(), col.name());
                }
            }
        }
        return "";
    }

    private List<ResolvedMeasureBinding> resolveMeasureBindings(
            VisualQueryDefinition query,
            SemanticModelSchema model,
            String primaryTable) {
        if (query.archetype() == VisualArchetype.SLICER) {
            String countExpr = "COUNTROWS(" + primaryTable + ")";
            return List.of(new ResolvedMeasureBinding(
                    countExpr, DEFAULT_COUNT_LABEL, countExpr, "#,##0", primaryTable, MEASURE_VALUE_KEY));
        }

        if (query.raxExpression() != null && !query.raxExpression().isBlank()) {
            String expanded = expandModelMeasuresInFormula(query.raxExpression(), model, primaryTable);
            String label = (query.measureId() != null && !query.measureId().isBlank())
                    ? resolveMeasureDisplayName(query.measureId(), model)
                    : "Custom Measure";
            String fmt = resolveMeasureFormatString(query.measureId(), model);
            return List.of(new ResolvedMeasureBinding(
                    query.raxExpression().trim(), label, expanded, fmt, primaryTable, MEASURE_VALUE_KEY));
        }

        List<String> rawBindings = new ArrayList<>();
        if (query.measureId() != null && !query.measureId().isBlank()) {
            rawBindings.add(query.measureId().trim());
        }
        for (String val : query.values()) {
            if (!rawBindings.contains(val)) {
                rawBindings.add(val);
            }
        }

        if (rawBindings.isEmpty()) {
            if (model != null && !model.measures().isEmpty() && query.archetype() == VisualArchetype.MEASURE_CARD) {
                MeasureDefinition firstMeasure = model.measures().getFirst();
                String expanded = expandModelMeasuresInFormula(firstMeasure.raxExpression(), model, primaryTable);
                String tbl = !firstMeasure.tableName().isBlank()
                        ? DuckDbConnectionManager.sanitizeIdentifier(firstMeasure.tableName())
                        : primaryTable;
                return List.of(new ResolvedMeasureBinding(
                        firstMeasure.id(), firstMeasure.name(), expanded,
                        firstMeasure.formatString(), tbl, DuckDbConnectionManager.sanitizeIdentifier(firstMeasure.id())));
            }
            String countExpr = "COUNTROWS(" + primaryTable + ")";
            return List.of(new ResolvedMeasureBinding(
                    countExpr, DEFAULT_COUNT_LABEL, countExpr, "#,##0", primaryTable, MEASURE_VALUE_KEY));
        }

        List<ResolvedMeasureBinding> resolved = new ArrayList<>(rawBindings.size());
        for (String binding : rawBindings) {
            resolved.add(resolveSingleMeasureBinding(binding, model, primaryTable));
        }
        return List.copyOf(resolved);
    }

    private ResolvedMeasureBinding resolveSingleMeasureBinding(
            String rawBinding,
            SemanticModelSchema model,
            String fallbackTable) {
        String trimmed = stripSurroundingBrackets(rawBinding.trim());

        MeasureDefinition matchedMeasure = findMeasure(model, trimmed);
        if (matchedMeasure != null) {
            String tbl = (matchedMeasure.tableName() != null && !matchedMeasure.tableName().isBlank())
                    ? DuckDbConnectionManager.sanitizeIdentifier(matchedMeasure.tableName())
                    : fallbackTable;
            String expandedExpr = expandModelMeasuresInFormula(matchedMeasure.raxExpression(), model, tbl);
            String aliasKey = DuckDbConnectionManager.sanitizeIdentifier(matchedMeasure.id());
            return new ResolvedMeasureBinding(
                    rawBinding,
                    matchedMeasure.name(),
                    expandedExpr,
                    matchedMeasure.formatString() != null ? matchedMeasure.formatString() : "",
                    tbl,
                    !aliasKey.isEmpty() ? aliasKey : MEASURE_VALUE_KEY);
        }

        if (looksLikeRaxFormula(trimmed)) {
            String expanded = expandModelMeasuresInFormula(trimmed, model, fallbackTable);
            return new ResolvedMeasureBinding(
                    rawBinding,
                    trimmed,
                    expanded,
                    "",
                    fallbackTable,
                    DuckDbConnectionManager.sanitizeIdentifier(trimmed));
        }

        String[] tableAndCol = parseTableAndColumn(trimmed, model, fallbackTable);
        String table = tableAndCol[0];
        String col = tableAndCol[1];
        ColumnDefinition colDef = findColumn(model, table, col);
        String aggFunc = resolveDefaultAggregationFunction(colDef);
        String raxExpr = aggFunc + "(" + table + "[" + col + "])";
        String format = colDef != null && !colDef.format().isBlank() ? colDef.format() : "";
        String label = colDef != null ? colDef.name() : col;

        return new ResolvedMeasureBinding(
                rawBinding,
                label,
                raxExpr,
                format,
                table,
                col);
    }

    private String expandModelMeasuresInFormula(
            String rawFormula,
            SemanticModelSchema model,
            String primaryTable) {
        String executable = raxFormulaCompiler.extractExecutableExpression(rawFormula);
        if (executable.equalsIgnoreCase("COUNTROWS()")) {
            return "COUNTROWS(" + primaryTable + ")";
        }
        if (model == null || model.measures().isEmpty()) {
            return executable;
        }
        String current = executable;
        for (int depth = 0; depth < MAX_MEASURE_EXPANSION_DEPTH; depth++) {
            boolean replaced = false;
            for (MeasureDefinition m : model.measures()) {
                String nameToken = "[" + m.name() + "]";
                String idToken = "[" + m.id() + "]";
                String innerExpr = "(" + raxFormulaCompiler.extractExecutableExpression(m.raxExpression()) + ")";
                if (containsIgnoreCase(current, nameToken)) {
                    current = replaceIgnoreCase(current, nameToken, innerExpr);
                    replaced = true;
                } else if (containsIgnoreCase(current, idToken)) {
                    current = replaceIgnoreCase(current, idToken, innerExpr);
                    replaced = true;
                }
            }
            if (!replaced) {
                break;
            }
        }
        return current;
    }

    private static String resolveDefaultAggregationFunction(ColumnDefinition colDef) {
        if (colDef == null) {
            return "SUM";
        }
        String summarizeBy = colDef.summarizeBy() != null
                ? colDef.summarizeBy().trim().toUpperCase(Locale.ROOT)
                : "DEFAULT";
        return switch (summarizeBy) {
            case "AVERAGE", "AVG" -> "AVERAGE";
            case "MIN" -> "MIN";
            case "MAX" -> "MAX";
            case "COUNT" -> "COUNT";
            case "DISTINCTCOUNT" -> "DISTINCTCOUNT";
            case "NONE" -> isNumericDataType(colDef.dataType()) ? "SUM" : "COUNT";
            default -> isNumericDataType(colDef.dataType()) ? "SUM" : "COUNT";
        };
    }

    private String resolvePrimaryTable(VisualQueryDefinition query, SemanticModelSchema model) {
        if (query.factTableName() != null && !query.factTableName().isBlank()) {
            String safe = DuckDbConnectionManager.sanitizeIdentifier(query.factTableName());
            if (!safe.isEmpty()) {
                return safe;
            }
        }
        if (query.measureId() != null && !query.measureId().isBlank()) {
            MeasureDefinition m = findMeasure(model, stripSurroundingBrackets(query.measureId()));
            if (m != null && m.tableName() != null && !m.tableName().isBlank()) {
                return DuckDbConnectionManager.sanitizeIdentifier(m.tableName());
            }
            String extracted = extractTablePrefix(query.measureId());
            if (!extracted.isEmpty()) {
                return extracted;
            }
        }
        List<List<String>> allWells = List.of(
                query.values(), query.category(), query.rows(),
                query.columns(), query.legend(), query.groupByColumns());
        for (List<String> well : allWells) {
            for (String item : well) {
                MeasureDefinition m = findMeasure(model, stripSurroundingBrackets(item));
                if (m != null && m.tableName() != null && !m.tableName().isBlank()) {
                    return DuckDbConnectionManager.sanitizeIdentifier(m.tableName());
                }
                String extracted = extractTablePrefix(item);
                if (!extracted.isEmpty()) {
                    return extracted;
                }
                String owner = findOwningTableForColumn(model, item);
                if (!owner.isEmpty()) {
                    return owner;
                }
            }
        }
        if (model != null && !model.tables().isEmpty()) {
            for (TableDefinition t : model.tables()) {
                if ("FACT".equalsIgnoreCase(t.role())) {
                    return DuckDbConnectionManager.sanitizeIdentifier(t.name());
                }
            }
            return DuckDbConnectionManager.sanitizeIdentifier(model.tables().getFirst().name());
        }
        return "dataset";
    }

    private SortSpec resolveEffectiveSortSpec(ResolvedVisualContext context) {
        if (context.query().sortSpec() != null) {
            return context.query().sortSpec();
        }
        if (!context.groupByColumns().isEmpty()) {
            String primaryDimAlias = extractAliasName(context.groupByColumns().getFirst());
            return new SortSpec(primaryDimAlias, SortDirection.ASC);
        }
        return null;
    }

    private Map<String, Set<Object>> mergeAndNormalizeFilters(
            Map<String, Set<Object>> activeFilters,
            Map<String, Set<Object>> crossFilters,
            ResolvedVisualContext context) {
        Map<String, Set<Object>> merged = new LinkedHashMap<>();
        appendNormalizedFilters(merged, activeFilters, context);
        appendNormalizedFilters(merged, crossFilters, context);
        return Map.copyOf(merged);
    }

    private Map<String, Set<Object>> normalizeSlicerFiltersForVisual(
            Map<String, Set<Object>> slicerFilters,
            ResolvedVisualContext context) {
        if (slicerFilters == null || slicerFilters.isEmpty()) {
            return Map.of();
        }
        String ownSlicerAlias = context.query().archetype() == VisualArchetype.SLICER
                ? extractAliasName(context.categoryDimension())
                : "";
        Map<String, Set<Object>> normalized = new LinkedHashMap<>();
        for (Map.Entry<String, Set<Object>> entry : slicerFilters.entrySet()) {
            String normalizedKey = normalizeFilterKey(entry.getKey(), context.model(), context.primaryTable());
            String keyAlias = extractAliasName(stripOperatorSuffix(normalizedKey));
            if (!ownSlicerAlias.isEmpty() && ownSlicerAlias.equalsIgnoreCase(keyAlias)) {
                continue;
            }
            if (!normalizedKey.isEmpty() && entry.getValue() != null && !entry.getValue().isEmpty()) {
                normalized.put(normalizedKey, entry.getValue());
            }
        }
        return Map.copyOf(normalized);
    }

    private void appendNormalizedFilters(
            Map<String, Set<Object>> target,
            Map<String, Set<Object>> source,
            ResolvedVisualContext context) {
        if (source == null || source.isEmpty()) {
            return;
        }
        for (Map.Entry<String, Set<Object>> entry : source.entrySet()) {
            if (entry.getValue() == null || entry.getValue().isEmpty()) {
                continue;
            }
            String normalizedKey = normalizeFilterKey(entry.getKey(), context.model(), context.primaryTable());
            if (!normalizedKey.isEmpty()) {
                Set<Object> combined = new LinkedHashSet<>(target.getOrDefault(normalizedKey, Set.of()));
                combined.addAll(entry.getValue());
                target.put(normalizedKey, Set.copyOf(combined));
            }
        }
    }

    private static String normalizeFilterKey(
            String rawKey,
            SemanticModelSchema model,
            String primaryTable) {
        if (rawKey == null || rawKey.isBlank()) {
            return "";
        }
        String trimmed = rawKey.trim();
        String operatorSuffix = "";
        int colonIdx = trimmed.indexOf(':');
        String fieldPart = trimmed;
        if (colonIdx > 0) {
            fieldPart = trimmed.substring(0, colonIdx).trim();
            operatorSuffix = trimmed.substring(colonIdx).trim();
        }
        String qualifiedField = qualifyColumnReference(fieldPart, model, primaryTable);
        return qualifiedField + operatorSuffix;
    }

    private static VisualQueryDefinition mergeBatchContext(
            VisualQueryDefinition item,
            VisualBatchQueryRequest batch) {
        UUID effectiveModelId = item.modelId() != null ? item.modelId() : batch.modelId();
        String effectiveTenant = !DEFAULT_TENANT.equals(item.tenantId()) ? item.tenantId() : batch.tenantId();
        String effectiveReport = !DEFAULT_REPORT.equals(item.reportId()) ? item.reportId() : batch.reportId();

        Map<String, Set<Object>> combinedActive = mergeRawFilterMaps(batch.pageFilters(), item.activeFilters());
        Map<String, Set<Object>> combinedCross = mergeRawFilterMaps(batch.crossFilters(), item.crossFilters());
        Map<String, Set<Object>> combinedSlicers = mergeRawFilterMaps(
                batch.slicerFilters(), item.externalSlicerFilters());

        return new VisualQueryDefinition(
                effectiveModelId,
                effectiveTenant,
                effectiveReport,
                item.visualId(),
                item.archetype(),
                item.values(),
                item.category(),
                item.legend(),
                item.rows(),
                item.columns(),
                item.sortSpec(),
                item.measureId(),
                item.raxExpression(),
                item.factTableName(),
                item.estimatedRows(),
                item.operationalLookup(),
                item.groupByColumns(),
                combinedActive,
                combinedCross,
                combinedSlicers,
                item.evaluationDate(),
                item.acceptLanguage()
        );
    }

    private static Map<String, Set<Object>> mergeRawFilterMaps(
            Map<String, Set<Object>> base,
            Map<String, Set<Object>> override) {
        Map<String, Set<Object>> merged = new LinkedHashMap<>();
        if (base != null) {
            merged.putAll(base);
        }
        if (override != null) {
            for (Map.Entry<String, Set<Object>> entry : override.entrySet()) {
                Set<Object> union = new LinkedHashSet<>(merged.getOrDefault(entry.getKey(), Set.of()));
                if (entry.getValue() != null) {
                    union.addAll(entry.getValue());
                }
                merged.put(entry.getKey(), Set.copyOf(union));
            }
        }
        return Map.copyOf(merged);
    }

    private static Set<Object> resolveSelectedSlicerValues(
            VisualQueryDefinition query,
            String qualifiedField,
            String alias) {
        Set<Object> selected = new LinkedHashSet<>();
        collectMatchingFilterValues(selected, query.externalSlicerFilters(), qualifiedField, alias);
        collectMatchingFilterValues(selected, query.activeFilters(), qualifiedField, alias);
        return Set.copyOf(selected);
    }

    private static void collectMatchingFilterValues(
            Set<Object> target,
            Map<String, Set<Object>> filterMap,
            String qualifiedField,
            String alias) {
        if (filterMap == null) {
            return;
        }
        for (Map.Entry<String, Set<Object>> entry : filterMap.entrySet()) {
            String rawField = stripOperatorSuffix(entry.getKey());
            String entryAlias = extractAliasName(rawField);
            if (rawField.equalsIgnoreCase(qualifiedField) || entryAlias.equalsIgnoreCase(alias)) {
                target.addAll(entry.getValue());
            }
        }
    }

    private static ChartSeriesPayload resortChartSeries(
            ChartSeriesPayload existing,
            List<Map<String, Object>> sortedRows) {
        if (existing == null || sortedRows.isEmpty()) {
            return existing != null ? existing : ChartSeriesPayload.EMPTY;
        }
        String categoryField = existing.seriesMeta().categoryField();
        if (categoryField.isEmpty()) {
            return existing;
        }
        List<String> newLabels = new ArrayList<>();
        for (Map<String, Object> row : sortedRows) {
            Object rawCat = findRowValue(row, categoryField);
            String label = rawCat != null ? rawCat.toString() : "Unknown";
            if (!newLabels.contains(label)) {
                newLabels.add(label);
            }
        }
        List<ChartDataset> reorderedDatasets = new ArrayList<>(existing.datasets().size());
        for (ChartDataset ds : existing.datasets()) {
            Map<String, Double> valueByLabel = new LinkedHashMap<>();
            for (int i = 0; i < existing.labels().size() && i < ds.data().size(); i++) {
                valueByLabel.put(existing.labels().get(i), ds.data().get(i));
            }
            List<Double> newData = newLabels.stream()
                    .map(lbl -> valueByLabel.getOrDefault(lbl, 0.0))
                    .toList();
            reorderedDatasets.add(new ChartDataset(ds.label(), newData, ds.measureField()));
        }
        return new ChartSeriesPayload(newLabels, reorderedDatasets, existing.seriesMeta());
    }

    private static MatrixRollupPayload resortMatrixRows(MatrixRollupPayload matrix, SortSpec sortSpec) {
        if (matrix == null || matrix.rows().isEmpty() || sortSpec == null) {
            return matrix != null ? matrix : MatrixRollupPayload.EMPTY;
        }
        int multiplier = switch (sortSpec.direction()) {
            case ASC -> 1;
            case DESC -> -1;
        };
        String field = sortSpec.field();
        String alias = extractAliasName(field);
        List<MatrixRow> sorted = new ArrayList<>(matrix.rows());
        sorted.sort((a, b) -> {
            Object valA = resolveMatrixSortValue(a, field, alias);
            Object valB = resolveMatrixSortValue(b, field, alias);
            return compareCellValues(valA, valB) * multiplier;
        });
        return new MatrixRollupPayload(sorted, matrix.columnHeaders(), matrix.totals(), matrix.grandTotal());
    }

    private static Object resolveMatrixSortValue(MatrixRow row, String field, String alias) {
        if (field.isBlank()
                || MEASURE_VALUE_KEY.equalsIgnoreCase(field)
                || ROW_TOTAL_KEY.equalsIgnoreCase(field)
                || "total".equalsIgnoreCase(field)) {
            return row.rowTotal();
        }
        Object direct = findRowValue(row.cells(), field);
        if (direct != null) {
            return direct;
        }
        Object byAlias = findRowValue(row.cells(), alias);
        if (byAlias != null) {
            return byAlias;
        }
        return row.rowTotal();
    }

    private static SlicerPayload resortSlicerOptions(SlicerPayload slicer, SortSpec sortSpec) {
        if (slicer == null || slicer.options().isEmpty() || sortSpec == null) {
            return slicer != null ? slicer : SlicerPayload.EMPTY;
        }
        int multiplier = switch (sortSpec.direction()) {
            case ASC -> 1;
            case DESC -> -1;
        };
        boolean sortByCount = MEASURE_VALUE_KEY.equalsIgnoreCase(sortSpec.field())
                || "count".equalsIgnoreCase(sortSpec.field());
        List<SlicerOption> sorted = new ArrayList<>(slicer.options());
        Comparator<SlicerOption> cmp = sortByCount
                ? Comparator.comparingLong(SlicerOption::count)
                : Comparator.comparing(SlicerOption::value, String.CASE_INSENSITIVE_ORDER);
        sorted.sort((a, b) -> cmp.compare(a, b) * multiplier);
        return new SlicerPayload(slicer.field(), sorted, slicer.selectedValues());
    }

    private static Object extractSortValue(Map<String, Object> row, String sortField) {
        if (row == null || row.isEmpty()) {
            return null;
        }
        if (sortField == null || sortField.isBlank()) {
            return findRowValue(row, MEASURE_VALUE_KEY);
        }
        Object direct = findRowValue(row, sortField);
        if (direct != null) {
            return direct;
        }
        String alias = extractAliasName(sortField);
        Object byAlias = findRowValue(row, alias);
        if (byAlias != null) {
            return byAlias;
        }
        return findRowValue(row, MEASURE_VALUE_KEY);
    }

    private static int compareCellValues(Object valA, Object valB) {
        if (valA == valB) {
            return 0;
        }
        if (valA == null) {
            return 1;
        }
        if (valB == null) {
            return -1;
        }
        Double numA = tryParseDouble(valA);
        Double numB = tryParseDouble(valB);
        if (numA != null && numB != null) {
            return Double.compare(numA, numB);
        }
        return valA.toString().compareToIgnoreCase(valB.toString());
    }

    private static Double tryParseDouble(Object value) {
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        if (value instanceof CharSequence seq) {
            String trimmed = seq.toString().trim();
            if (trimmed.isEmpty()) {
                return null;
            }
            try {
                return Double.parseDouble(trimmed);
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    private static double toDoubleValue(Object value) {
        Double parsed = tryParseDouble(value);
        return parsed != null ? parsed : 0.0;
    }

    static String formatMeasureValue(double value, String formatString) {
        DecimalFormatSymbols symbols = DecimalFormatSymbols.getInstance(Locale.US);
        String fmt = formatString != null ? formatString.trim() : "";
        if (!fmt.isEmpty()) {
            boolean isCurrency = fmt.contains("$");
            boolean isPercent = fmt.contains("%");
            String cleanPattern = fmt.replace("$", "").replace("%", "").trim();
            if (cleanPattern.isEmpty()) {
                cleanPattern = "#,##0.00";
            }
            try {
                DecimalFormat df = new DecimalFormat(cleanPattern, symbols);
                double displayVal = isPercent ? value * 100.0 : value;
                String formatted = df.format(displayVal);
                if (isCurrency) {
                    return "$" + formatted;
                }
                if (isPercent) {
                    return formatted + "%";
                }
                return formatted;
            } catch (IllegalArgumentException ignored) {
                // Fall back to default formatting if pattern is non-standard
            }
        }
        if (value == Math.rint(value) && Math.abs(value) < 1_000_000_000_000L) {
            return new DecimalFormat("#,##0", symbols).format(value);
        }
        return new DecimalFormat("#,##0.00", symbols).format(value);
    }

    private static Object findMeasureValue(
            Map<String, Object> row,
            String primaryKey,
            ResolvedMeasureBinding measure) {
        Object val = findRowValue(row, primaryKey);
        if (val != null) {
            return val;
        }
        val = findRowValue(row, measure.aliasKey());
        if (val != null) {
            return val;
        }
        val = findRowValue(row, measure.label());
        if (val != null) {
            return val;
        }
        return findRowValue(row, MEASURE_VALUE_KEY);
    }

    private static Object findRowValue(Map<String, Object> row, String key) {
        if (row == null || key == null || key.isBlank()) {
            return null;
        }
        if (row.containsKey(key)) {
            return row.get(key);
        }
        for (Map.Entry<String, Object> entry : row.entrySet()) {
            if (entry.getKey() != null && entry.getKey().equalsIgnoreCase(key)) {
                return entry.getValue();
            }
        }
        String sanitized = DuckDbConnectionManager.sanitizeIdentifier(key);
        if (!sanitized.isEmpty()) {
            for (Map.Entry<String, Object> entry : row.entrySet()) {
                if (entry.getKey() != null
                        && DuckDbConnectionManager.sanitizeIdentifier(entry.getKey()).equalsIgnoreCase(sanitized)) {
                    return entry.getValue();
                }
            }
        }
        return null;
    }

    private static String buildCompositeRowKey(Map<String, Object> row, List<String> aliases) {
        if (aliases == null || aliases.isEmpty()) {
            return "All";
        }
        List<String> parts = new ArrayList<>(aliases.size());
        for (String alias : aliases) {
            Object val = findRowValue(row, alias);
            parts.add(val != null ? val.toString() : "Unknown");
        }
        return String.join(" | ", parts);
    }

    private static String qualifyColumnReference(
            String rawRef,
            SemanticModelSchema model,
            String fallbackTable) {
        if (rawRef == null || rawRef.isBlank()) {
            return "";
        }
        String[] parsed = parseTableAndColumn(rawRef, model, fallbackTable);
        if (parsed[0].isEmpty() || parsed[1].isEmpty()) {
            return "";
        }
        return parsed[0] + "." + parsed[1];
    }

    private static String[] parseTableAndColumn(
            String rawRef,
            SemanticModelSchema model,
            String fallbackTable) {
        String trimmed = rawRef.trim();
        int bracketIdx = trimmed.indexOf('[');
        if (bracketIdx > 0 && trimmed.endsWith("]")) {
            String tbl = DuckDbConnectionManager.sanitizeIdentifier(
                    trimmed.substring(0, bracketIdx).replace("'", ""));
            String col = DuckDbConnectionManager.sanitizeIdentifier(
                    trimmed.substring(bracketIdx + 1, trimmed.length() - 1));
            return new String[]{!tbl.isEmpty() ? tbl : fallbackTable, col};
        }
        int dotIdx = trimmed.indexOf('.');
        if (dotIdx > 0 && dotIdx < trimmed.length() - 1) {
            String tbl = DuckDbConnectionManager.sanitizeIdentifier(trimmed.substring(0, dotIdx));
            String col = DuckDbConnectionManager.sanitizeIdentifier(trimmed.substring(dotIdx + 1));
            return new String[]{!tbl.isEmpty() ? tbl : fallbackTable, col};
        }
        String cleanCol = DuckDbConnectionManager.sanitizeIdentifier(stripSurroundingBrackets(trimmed));
        String ownerTable = findOwningTableForColumn(model, cleanCol);
        String resolvedTable = !ownerTable.isEmpty() ? ownerTable : fallbackTable;
        return new String[]{resolvedTable, cleanCol};
    }

    private static String extractAliasName(String rawRef) {
        if (rawRef == null || rawRef.isBlank()) {
            return "";
        }
        String trimmed = rawRef.trim();
        int bracketIdx = trimmed.indexOf('[');
        if (bracketIdx >= 0 && trimmed.endsWith("]")) {
            return DuckDbConnectionManager.sanitizeIdentifier(
                    trimmed.substring(bracketIdx + 1, trimmed.length() - 1));
        }
        int dotIdx = trimmed.lastIndexOf('.');
        if (dotIdx >= 0 && dotIdx < trimmed.length() - 1) {
            return DuckDbConnectionManager.sanitizeIdentifier(trimmed.substring(dotIdx + 1));
        }
        return DuckDbConnectionManager.sanitizeIdentifier(trimmed);
    }

    private static String extractTablePrefix(String rawRef) {
        if (rawRef == null || rawRef.isBlank()) {
            return "";
        }
        String trimmed = rawRef.trim();
        int bracketIdx = trimmed.indexOf('[');
        if (bracketIdx > 0 && trimmed.endsWith("]")) {
            return DuckDbConnectionManager.sanitizeIdentifier(
                    trimmed.substring(0, bracketIdx).replace("'", ""));
        }
        int dotIdx = trimmed.indexOf('.');
        if (dotIdx > 0 && dotIdx < trimmed.length() - 1) {
            return DuckDbConnectionManager.sanitizeIdentifier(trimmed.substring(0, dotIdx));
        }
        return "";
    }

    private static String stripOperatorSuffix(String key) {
        if (key == null) {
            return "";
        }
        int colonIdx = key.indexOf(':');
        return colonIdx > 0 ? key.substring(0, colonIdx).trim() : key.trim();
    }

    private static String stripSurroundingBrackets(String token) {
        if (token == null) {
            return "";
        }
        String trimmed = token.trim();
        if (trimmed.startsWith("[") && trimmed.endsWith("]") && trimmed.length() > 2) {
            return trimmed.substring(1, trimmed.length() - 1).trim();
        }
        return trimmed;
    }

    private static boolean looksLikeRaxFormula(String token) {
        return token.contains("(") && token.contains(")");
    }

    private static MeasureDefinition findMeasure(SemanticModelSchema model, String idOrName) {
        if (model == null || idOrName == null || idOrName.isBlank()) {
            return null;
        }
        String target = stripSurroundingBrackets(idOrName);
        for (MeasureDefinition m : model.measures()) {
            if (m.id().equalsIgnoreCase(target) || m.name().equalsIgnoreCase(target)) {
                return m;
            }
        }
        return null;
    }

    private static String resolveMeasureDisplayName(String idOrName, SemanticModelSchema model) {
        MeasureDefinition m = findMeasure(model, idOrName);
        return m != null ? m.name() : idOrName;
    }

    private static String resolveMeasureFormatString(String idOrName, SemanticModelSchema model) {
        MeasureDefinition m = findMeasure(model, idOrName);
        return (m != null && m.formatString() != null) ? m.formatString() : "";
    }

    private static java.util.Optional<TableDefinition> findTable(SemanticModelSchema model, String tableName) {
        if (model == null || tableName == null || tableName.isBlank()) {
            return java.util.Optional.empty();
        }
        String sanitized = DuckDbConnectionManager.sanitizeIdentifier(tableName);
        return model.tables().stream()
                .filter(t -> DuckDbConnectionManager.sanitizeIdentifier(t.name()).equalsIgnoreCase(sanitized))
                .findFirst();
    }

    private static ColumnDefinition findColumn(SemanticModelSchema model, String tableName, String colName) {
        return findTable(model, tableName)
                .flatMap(t -> t.columns().stream()
                        .filter(c -> DuckDbConnectionManager.sanitizeIdentifier(c.name()).equalsIgnoreCase(colName))
                        .findFirst())
                .orElse(null);
    }

    private static String findOwningTableForColumn(SemanticModelSchema model, String colToken) {
        if (model == null || colToken == null || colToken.isBlank()) {
            return "";
        }
        String cleanCol = DuckDbConnectionManager.sanitizeIdentifier(stripSurroundingBrackets(colToken));
        for (TableDefinition table : model.tables()) {
            for (ColumnDefinition col : table.columns()) {
                if (DuckDbConnectionManager.sanitizeIdentifier(col.name()).equalsIgnoreCase(cleanCol)) {
                    return DuckDbConnectionManager.sanitizeIdentifier(table.name());
                }
            }
        }
        return "";
    }

    private static Set<String> collectBoundValueColumnNames(VisualQueryDefinition query) {
        Set<String> names = new LinkedHashSet<>();
        if (query.measureId() != null && !query.measureId().isBlank()) {
            names.add(extractAliasName(query.measureId()).toLowerCase(Locale.ROOT));
        }
        for (String val : query.values()) {
            names.add(extractAliasName(val).toLowerCase(Locale.ROOT));
        }
        return names;
    }

    private static boolean isNumericDataType(String dataType) {
        if (dataType == null || dataType.isBlank()) {
            return false;
        }
        String upper = dataType.trim().toUpperCase(Locale.ROOT);
        return upper.contains("INT")
                || upper.contains("DEC")
                || upper.contains("NUM")
                || upper.contains("FLOAT")
                || upper.contains("DOUBLE")
                || upper.contains("CURR")
                || upper.equals("LONG");
    }

    private static String sanitizeTableAndCol(String table, String col) {
        return DuckDbConnectionManager.sanitizeIdentifier(table)
                + "."
                + DuckDbConnectionManager.sanitizeIdentifier(col);
    }

    private static boolean containsIgnoreCase(String text, String token) {
        return text.toLowerCase(Locale.ROOT).contains(token.toLowerCase(Locale.ROOT));
    }

    private static String replaceIgnoreCase(String text, String target, String replacement) {
        String lowerText = text.toLowerCase(Locale.ROOT);
        String lowerTarget = target.toLowerCase(Locale.ROOT);
        int idx = lowerText.indexOf(lowerTarget);
        if (idx < 0) {
            return text;
        }
        StringBuilder sb = new StringBuilder();
        int pos = 0;
        while (idx >= 0) {
            sb.append(text, pos, idx).append(replacement);
            pos = idx + target.length();
            idx = lowerText.indexOf(lowerTarget, pos);
        }
        sb.append(text, pos, text.length());
        return sb.toString();
    }
}
