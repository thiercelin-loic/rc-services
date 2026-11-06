package com.rollercoaster.model;

import com.rollercoaster.rax.compiler.RaxFormulaCompiler;
import com.rollercoaster.rax.compiler.TimeIntelligenceCompiler;
import com.rollercoaster.rax.context.ContextEvaluator;
import com.rollercoaster.security.DynamicDataMaskingService;
import com.rollercoaster.security.RlsSecurityContextResolver;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class DualEngineVisualQueryTest {

    private DuckDbConnectionManager duckDbConnectionManager;
    private DualEngineCoordinator coordinator;
    private SemanticModelManager semanticModelManager;
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

        modelId = UUID.randomUUID();
        SemanticModelSchema schema = new SemanticModelSchema(
                modelId,
                "Analytics Model",
                1,
                List.of(
                        new TableDefinition(
                                "orders",
                                "FACT",
                                null,
                                500L,
                                false,
                                "Orders fact table",
                                List.of(
                                        new ColumnDefinition("category", "VARCHAR", false, false),
                                        new ColumnDefinition("region", "VARCHAR", false, false),
                                        new ColumnDefinition("amount", "DOUBLE", false, false)
                                )
                        )
                ),
                List.of(),
                List.of(
                        new MeasureDefinition(
                                "m_total_amount",
                                "Total Amount",
                                "orders",
                                "SUM(orders[amount])",
                                "#,##0.00",
                                "Total order amount"
                        )
                ),
                Map.of()
        );
        semanticModelManager.registerModel(schema);

        coordinator.ingestDatasetRows("tenant-1", "orders", List.of(
                Map.of("category", "Compute", "region", "EU", "amount", 1200.0),
                Map.of("category", "Compute", "region", "US", "amount", 800.0),
                Map.of("category", "Storage", "region", "EU", "amount", 500.0),
                Map.of("category", "Transit", "region", "APAC", "amount", 300.0)
        ));
    }

    @AfterEach
    void tearDown() {
        duckDbConnectionManager.shutdownEngine();
    }

    @Test
    @DisplayName("Executes grouped visual query from field wells and aggregates live DuckDB rows")
    void testGroupedFieldWellVisualQuery() {
        VisualQueryDefinition query = new VisualQueryDefinition(
                modelId,
                "tenant-1",
                "report-1",
                "vis-bar-1",
                "orders.amount",
                null,
                "orders",
                500L,
                false,
                List.of("orders.category"),
                Map.of(),
                Map.of(),
                LocalDate.of(2026, 11, 3),
                "en-US"
        );

        QueryExecutionResult result = coordinator.executeVisualQuery(query, null);

        assertThat(result.executedSql())
                .contains("SELECT orders.category AS category, SUM(orders.amount) AS measure_value FROM orders")
                .contains("GROUP BY orders.category");
        assertThat(result.rows()).hasSize(3);
    }

    @Test
    @DisplayName("Applies EQ, NEQ, and BETWEEN page filters to visual query execution")
    void testPageFiltersAppliedToVisualQuery() {
        VisualQueryDefinition eqQuery = new VisualQueryDefinition(
                modelId,
                "tenant-1",
                "report-1",
                "vis-bar-2",
                "m_total_amount",
                null,
                "orders",
                500L,
                false,
                List.of("orders.category"),
                Map.of("orders.region:EQ", Set.of("EU")),
                Map.of(),
                LocalDate.of(2026, 11, 3),
                "en-US"
        );

        QueryExecutionResult eqResult = coordinator.executeVisualQuery(eqQuery, null);
        assertThat(eqResult.executedSql()).contains("orders.region = 'EU'");
        assertThat(eqResult.rows()).hasSize(2);

        VisualQueryDefinition neqQuery = new VisualQueryDefinition(
                modelId,
                "tenant-1",
                "report-1",
                "vis-bar-3",
                "m_total_amount",
                null,
                "orders",
                500L,
                false,
                List.of("orders.category"),
                Map.of("orders.region:NEQ", Set.of("EU")),
                Map.of(),
                LocalDate.of(2026, 11, 3),
                "en-US"
        );

        QueryExecutionResult neqResult = coordinator.executeVisualQuery(neqQuery, null);
        assertThat(neqResult.executedSql()).contains("orders.region <> 'EU'");
        assertThat(neqResult.rows()).hasSize(2);

        VisualQueryDefinition betweenQuery = new VisualQueryDefinition(
                modelId,
                "tenant-1",
                "report-1",
                "vis-bar-4",
                "m_total_amount",
                null,
                "orders",
                500L,
                false,
                List.of("orders.category"),
                Map.of("orders.amount:BETWEEN", Set.of("400..1000")),
                Map.of(),
                LocalDate.of(2026, 11, 3),
                "en-US"
        );

        QueryExecutionResult betweenResult = coordinator.executeVisualQuery(betweenQuery, null);
        assertThat(betweenResult.executedSql()).contains("orders.amount BETWEEN 400 AND 1000");
        assertThat(betweenResult.rows()).hasSize(2);
    }

    @Test
    @DisplayName("Evaluates INFO.CALCDEPENDENCY() and TMDL createOrReplace via DualEngineCoordinator")
    void testInfoQueryAndTmdlScriptExecution() {
        VisualQueryDefinition infoQuery = new VisualQueryDefinition(
                modelId,
                "tenant-1",
                "report-1",
                "vis-info-1",
                null,
                "EVALUATE INFO.CALCDEPENDENCY()",
                "orders",
                500L,
                false,
                List.of(),
                Map.of(),
                Map.of(),
                LocalDate.of(2026, 11, 3),
                "en-US"
        );

        QueryExecutionResult infoResult = coordinator.executeVisualQuery(infoQuery, null);
        assertThat(infoResult.rows()).isNotEmpty();
        assertThat(infoResult.rows().getFirst()).containsEntry("OBJECT", "Total Amount");

        String tmdlScript = """
                createOrReplace
                    table 'orders'
                        /// Updated description from TMDL
                        measure 'Total Amount' = SUM(orders[amount])
                            formatString: "$#,##0.00"
                """;
        VisualQueryDefinition tmdlQuery = new VisualQueryDefinition(
                modelId,
                "tenant-1",
                "report-1",
                "vis-tmdl-1",
                null,
                tmdlScript,
                "orders",
                500L,
                false,
                List.of(),
                Map.of(),
                Map.of(),
                LocalDate.of(2026, 11, 3),
                "en-US"
        );

        QueryExecutionResult tmdlResult = coordinator.executeVisualQuery(tmdlQuery, null);
        assertThat(tmdlResult.rows()).hasSize(1);
        MeasureDefinition updatedMeasure = semanticModelManager.getModel(modelId)
                .measures()
                .stream()
                .filter(m -> m.id().equals("m_total_amount"))
                .findFirst()
                .orElseThrow();
        assertThat(updatedMeasure.description()).isEqualTo("Updated description from TMDL");
        assertThat(updatedMeasure.formatString()).isEqualTo("$#,##0.00");
    }
}
