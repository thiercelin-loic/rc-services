package com.rollercoaster.model;

import com.rollercoaster.config.ErrorHandler;
import com.rollercoaster.model.VisualAggregationService.ChartSeriesPayload;
import com.rollercoaster.model.VisualAggregationService.MatrixRollupPayload;
import com.rollercoaster.model.VisualAggregationService.MatrixRow;
import com.rollercoaster.model.VisualAggregationService.MeasureCardPayload;
import com.rollercoaster.model.VisualAggregationService.SlicerPayload;
import com.rollercoaster.model.VisualAggregationService.VisualAggregationPayload;
import com.rollercoaster.model.VisualAggregationService.VisualBatchQueryRequest;
import com.rollercoaster.model.VisualAggregationService.VisualBatchQueryResponse;
import com.rollercoaster.rax.compiler.RaxFormulaCompiler;
import com.rollercoaster.rax.compiler.TimeIntelligenceCompiler;
import com.rollercoaster.rax.context.ContextEvaluator;
import com.rollercoaster.security.DynamicDataMaskingService;
import com.rollercoaster.security.RlsSecurityContextResolver;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class VisualAggregationServiceTest {

    private DuckDbConnectionManager duckDbConnectionManager;
    private SemanticModelManager semanticModelManager;
    private DualEngineCoordinator coordinator;
    private VisualAggregationService aggregationService;
    private ReportQueryController reportQueryController;
    private UUID modelId;

    @BeforeEach
    void setUp() {
        duckDbConnectionManager = new DuckDbConnectionManager("1GB", 2);
        duckDbConnectionManager.initializeEngine();

        ContextEvaluator contextEvaluator = new ContextEvaluator();
        TimeIntelligenceCompiler timeIntelligenceCompiler = new TimeIntelligenceCompiler();
        RaxFormulaCompiler compiler = new RaxFormulaCompiler(contextEvaluator, timeIntelligenceCompiler);
        semanticModelManager = new SemanticModelManager();
        RlsSecurityContextResolver rlsResolver = new RlsSecurityContextResolver();
        DynamicDataMaskingService maskingService = new DynamicDataMaskingService();

        coordinator = new DualEngineCoordinator(
                compiler,
                duckDbConnectionManager,
                semanticModelManager,
                rlsResolver,
                maskingService,
                null,
                null,
                null,
                1_000_000L
        );

        aggregationService = new VisualAggregationService(semanticModelManager, coordinator, compiler);
        reportQueryController = new ReportQueryController(aggregationService);

        modelId = UUID.randomUUID();
        SemanticModelSchema schema = new SemanticModelSchema(
                modelId,
                "Cloud Workload Telemetry",
                1,
                List.of(
                        new TableDefinition(
                                "workloads",
                                "FACT",
                                "recorded_date",
                                500L,
                                false,
                                "Cloud workload telemetry",
                                false,
                                true,
                                "recorded_date",
                                List.of(
                                        new ColumnDefinition("workload_id", "VARCHAR", true, false),
                                        new ColumnDefinition("service_tier", "VARCHAR", false, false),
                                        new ColumnDefinition("region", "VARCHAR", false, false),
                                        new ColumnDefinition("recorded_date", "DATE", false, false),
                                        new ColumnDefinition("cost_usd", "DOUBLE", false, false),
                                        new ColumnDefinition("cpu_hours", "DOUBLE", false, false)
                                )
                        )
                ),
                List.of(),
                List.of(
                        new MeasureDefinition(
                                "m_total_cost",
                                "Total Cost",
                                "workloads",
                                "SUM(workloads[cost_usd])",
                                "$#,##0.00",
                                "Total workload cost in USD"
                        ),
                        new MeasureDefinition(
                                "m_total_cpu",
                                "Total CPU Hours",
                                "workloads",
                                "SUM(workloads[cpu_hours])",
                                "#,##0",
                                "Total CPU hours consumed"
                        ),
                        new MeasureDefinition(
                                "m_cost_per_cpu",
                                "Cost Per CPU Hour",
                                "workloads",
                                "DIVIDE([Total Cost], [Total CPU Hours], 0)",
                                "$#,##0.00",
                                "Blended cost per CPU hour"
                        )
                ),
                Map.of()
        );
        semanticModelManager.registerModel(schema);

        coordinator.ingestDatasetRows("tenant-1", "workloads", List.of(
                Map.of("workload_id", "w1", "service_tier", "Compute", "region", "EU", "recorded_date", "2026-01-10", "cost_usd", 1200.0, "cpu_hours", 300.0),
                Map.of("workload_id", "w2", "service_tier", "Compute", "region", "US", "recorded_date", "2026-01-15", "cost_usd", 800.0, "cpu_hours", 200.0),
                Map.of("workload_id", "w3", "service_tier", "Storage", "region", "EU", "recorded_date", "2026-02-01", "cost_usd", 500.0, "cpu_hours", 100.0),
                Map.of("workload_id", "w4", "service_tier", "Transit", "region", "APAC", "recorded_date", "2026-02-20", "cost_usd", 300.0, "cpu_hours", 50.0)
        ));
    }

    @AfterEach
    void tearDown() {
        duckDbConnectionManager.shutdownEngine();
    }

    @Test
    @DisplayName("Aggregates BAR chart series with default dimension inference and server-side DESC sorting")
    void testBarChartSeriesAndDefaultDimensionInference() {
        VisualQueryDefinition barQuery = new VisualQueryDefinition(
                modelId,
                "vis-bar-cost",
                VisualArchetype.BAR,
                FieldWellBindings.forChart(List.of("m_total_cost"), List.of(), List.of()),
                new SortSpec("measure_value", SortDirection.DESC),
                Map.of(),
                Map.of()
        );

        VisualAggregationPayload payload = aggregationService.aggregateVisual(barQuery);
        ChartSeriesPayload chart = payload.chartSeries();

        assertThat(payload.archetype()).isEqualTo(VisualArchetype.BAR);
        assertThat(chart.labels()).containsExactly("Compute", "Storage", "Transit");
        assertThat(chart.datasets()).hasSize(1);
        assertThat(chart.datasets().getFirst().label()).isEqualTo("Total Cost");
        assertThat(chart.datasets().getFirst().data()).containsExactly(2000.0, 500.0, 300.0);
        assertThat(chart.seriesMeta().categoryField()).isEqualTo("service_tier");
        assertThat(chart.seriesMeta().minValue()).isEqualTo(300.0);
        assertThat(chart.seriesMeta().maxValue()).isEqualTo(2000.0);
        assertThat(chart.seriesMeta().totalValue()).isEqualTo(2800.0);
    }

    @Test
    @DisplayName("Infers date dimension for LINE chart and geo dimension for GEO_MAP chart")
    void testLineAndGeoMapDimensionInference() {
        VisualQueryDefinition lineQuery = new VisualQueryDefinition(
                modelId,
                "vis-line-date",
                VisualArchetype.LINE,
                FieldWellBindings.forChart(List.of("m_total_cost"), List.of(), List.of()),
                new SortSpec("recorded_date", SortDirection.ASC),
                Map.of(),
                Map.of()
        );
        VisualAggregationPayload linePayload = aggregationService.aggregateVisual(lineQuery);
        assertThat(linePayload.chartSeries().seriesMeta().categoryField()).isEqualTo("recorded_date");
        assertThat(linePayload.chartSeries().labels())
                .containsExactly("2026-01-10", "2026-01-15", "2026-02-01", "2026-02-20");

        VisualQueryDefinition geoQuery = new VisualQueryDefinition(
                modelId,
                "vis-geo-region",
                VisualArchetype.GEO_MAP,
                FieldWellBindings.forChart(List.of("m_total_cost"), List.of(), List.of()),
                new SortSpec("measure_value", SortDirection.DESC),
                Map.of(),
                Map.of()
        );
        VisualAggregationPayload geoPayload = aggregationService.aggregateVisual(geoQuery);
        assertThat(geoPayload.chartSeries().seriesMeta().categoryField()).isEqualTo("region");
        assertThat(geoPayload.chartSeries().labels()).containsExactly("EU", "US", "APAC");
        assertThat(geoPayload.chartSeries().datasets().getFirst().data()).containsExactly(1700.0, 800.0, 300.0);
    }

    @Test
    @DisplayName("Computes MATRIX rollups with pivoted column headers, row totals, column totals, and grand total")
    void testMatrixPivotRollupAndTotals() {
        VisualQueryDefinition matrixQuery = new VisualQueryDefinition(
                modelId,
                "vis-matrix-1",
                VisualArchetype.MATRIX,
                FieldWellBindings.forMatrix(
                        List.of("workloads.service_tier"),
                        List.of("workloads.region"),
                        List.of("m_total_cost")),
                new SortSpec("rowTotal", SortDirection.DESC),
                Map.of(),
                Map.of()
        );

        VisualAggregationPayload payload = aggregationService.aggregateVisual(matrixQuery);
        MatrixRollupPayload matrix = payload.matrix();

        assertThat(matrix.columnHeaders()).containsExactlyInAnyOrder("APAC", "EU", "US");
        assertThat(matrix.rows()).hasSize(3);
        assertThat(matrix.rows().stream().map(MatrixRow::rowKey).toList())
                .containsExactly("Compute", "Storage", "Transit");

        MatrixRow computeRow = matrix.rows().getFirst();
        assertThat(computeRow.rowTotal()).isEqualTo(2000.0);
        assertThat(computeRow.values()).containsEntry("EU", 1200.0).containsEntry("US", 800.0).containsEntry("APAC", 0.0);

        assertThat(matrix.totals().get("EU")).isEqualTo(1700.0);
        assertThat(matrix.totals().get("US")).isEqualTo(800.0);
        assertThat(matrix.totals().get("APAC")).isEqualTo(300.0);
        assertThat(matrix.totals().get("Total Cost")).isEqualTo(2800.0);
        assertThat(matrix.grandTotal()).isEqualTo(2800.0);
    }

    @Test
    @DisplayName("Computes MEASURE_CARD formatted KPIs including composite measure expansion")
    void testMeasureCardKpiFormattingAndCompositeMeasures() {
        VisualQueryDefinition cardQuery = new VisualQueryDefinition(
                modelId,
                "vis-card-blended",
                VisualArchetype.MEASURE_CARD,
                FieldWellBindings.forMeasureCard("m_cost_per_cpu"),
                null,
                Map.of("region", Set.of("EU")),
                Map.of()
        );

        VisualAggregationPayload payload = aggregationService.aggregateVisual(cardQuery);
        MeasureCardPayload card = payload.measureCard();

        assertThat(card.label()).isEqualTo("Cost Per CPU Hour");
        assertThat(card.rawValue()).isEqualTo(4.25);
        assertThat(card.formattedValue()).isEqualTo("$4.25");
        assertThat(card.status()).isEqualTo("OK");
    }

    @Test
    @DisplayName("Applies applySortToPayload for ASC and DESC directions on numeric and string fields")
    void testApplySortToPayload() {
        List<Map<String, Object>> sampleRows = List.of(
                Map.of("service_tier", "Storage", "measure_value", 500.0),
                Map.of("service_tier", "Compute", "measure_value", 2000.0),
                Map.of("service_tier", "Transit", "measure_value", 300.0)
        );

        List<Map<String, Object>> byValueAsc = aggregationService.applySortToPayload(
                sampleRows, new SortSpec("measure_value", SortDirection.ASC));
        assertThat(byValueAsc.stream().map(r -> r.get("service_tier")).toList())
                .containsExactly("Transit", "Storage", "Compute");

        List<Map<String, Object>> byValueDesc = aggregationService.applySortToPayload(
                sampleRows, new SortSpec("measure_value", SortDirection.DESC));
        assertThat(byValueDesc.stream().map(r -> r.get("service_tier")).toList())
                .containsExactly("Compute", "Storage", "Transit");

        List<Map<String, Object>> byNameDesc = aggregationService.applySortToPayload(
                sampleRows, new SortSpec("service_tier", SortDirection.DESC));
        assertThat(byNameDesc.stream().map(r -> r.get("service_tier")).toList())
                .containsExactly("Transit", "Storage", "Compute");
    }

    @Test
    @DisplayName("Applies cross-filters, page filters, and slicer selections across a batch visual query")
    void testBatchQueryCrossFilteringAndSlicers() {
        VisualQueryDefinition barVisual = new VisualQueryDefinition(
                modelId,
                "vis-bar",
                VisualArchetype.BAR,
                FieldWellBindings.forChart(List.of("m_total_cost"), List.of("workloads.service_tier"), List.of()),
                new SortSpec("measure_value", SortDirection.DESC),
                Map.of(),
                Map.of()
        );
        VisualQueryDefinition kpiVisual = new VisualQueryDefinition(
                modelId,
                "vis-kpi",
                VisualArchetype.MEASURE_CARD,
                FieldWellBindings.forMeasureCard("m_total_cost"),
                null,
                Map.of(),
                Map.of()
        );
        VisualQueryDefinition slicerVisual = new VisualQueryDefinition(
                modelId,
                "vis-slicer-region",
                VisualArchetype.SLICER,
                FieldWellBindings.forSlicer("workloads.region"),
                new SortSpec("region", SortDirection.ASC),
                Map.of(),
                Map.of()
        );

        VisualBatchQueryRequest batchRequest = new VisualBatchQueryRequest(
                modelId,
                "report-telemetry",
                List.of(barVisual, kpiVisual, slicerVisual),
                Map.of(),
                Map.of("workloads.service_tier", Set.of("Compute", "Storage")),
                Map.of("region", Set.of("EU"))
        );

        ResponseEntity<VisualBatchQueryResponse> httpResponse =
                reportQueryController.queryVisualBatch(batchRequest, null);
        assertThat(httpResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        VisualBatchQueryResponse batchResponse = httpResponse.getBody();
        assertThat(batchResponse).isNotNull();
        assertThat(batchResponse.payloads()).containsKeys("vis-bar", "vis-kpi", "vis-slicer-region");

        VisualAggregationPayload barPayload = batchResponse.getPayload("vis-bar");
        assertThat(barPayload.chartSeries().labels()).containsExactly("Compute", "Storage");
        assertThat(barPayload.chartSeries().datasets().getFirst().data()).containsExactly(1200.0, 500.0);

        VisualAggregationPayload kpiPayload = batchResponse.getPayload("vis-kpi");
        assertThat(kpiPayload.measureCard().rawValue()).isEqualTo(1700.0);
        assertThat(kpiPayload.measureCard().formattedValue()).isEqualTo("$1,700.00");

        SlicerPayload slicerPayload = batchResponse.getPayload("vis-slicer-region").slicer();
        assertThat(slicerPayload.selectedValues()).contains("EU");
        assertThat(slicerPayload.options()).hasSize(2);
    }

    @Test
    @DisplayName("Rejects invalid batch requests and maps IllegalArgumentException to RFC 9457 ProblemDetail")
    void testValidationErrorsMappedToProblemDetail() {
        ErrorHandler errorHandler = new ErrorHandler();

        assertThatThrownBy(() -> reportQueryController.queryVisualBatch(null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .satisfies(ex -> {
                    ProblemDetail problem = errorHandler.handleIllegalArgument((IllegalArgumentException) ex);
                    assertThat(problem.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST.value());
                    assertThat(problem.getProperties()).containsEntry("errorCode", "ERR_INVALID_ARGUMENT");
                });

        VisualBatchQueryRequest emptyBatch = new VisualBatchQueryRequest(List.of(), Map.of());
        assertThatThrownBy(() -> reportQueryController.queryVisualBatch(emptyBatch, null))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
