package com.rollercoaster.rax.diagnostics;

import com.rollercoaster.config.ErrorHandler;
import com.rollercoaster.config.JacksonConfig;
import com.rollercoaster.model.ColumnDefinition;
import com.rollercoaster.model.DualEngineCoordinator;
import com.rollercoaster.model.DuckDbConnectionManager;
import com.rollercoaster.model.RelationshipInferenceService;
import com.rollercoaster.model.RsdlFormatter;
import com.rollercoaster.model.SemanticModelController;
import com.rollercoaster.model.SemanticModelManager;
import com.rollercoaster.model.SemanticModelManager.ColumnMetadataUpdate;
import com.rollercoaster.model.SemanticModelManager.ModelMutationResult;
import com.rollercoaster.model.SemanticModelSchema;
import com.rollercoaster.model.TableDefinition;
import com.rollercoaster.rax.compiler.CalculatedColumnService;
import com.rollercoaster.rax.compiler.CalculatedColumnService.CalculatedColumnRequest;
import com.rollercoaster.rax.compiler.CalculatedColumnService.CalculatedColumnResult;
import com.rollercoaster.rax.compiler.CalculatedTableService;
import com.rollercoaster.rax.compiler.CalculatedTableService.CalculatedTableRequest;
import com.rollercoaster.rax.compiler.CalculatedTableService.CalculatedTableResult;
import com.rollercoaster.rax.compiler.InfoFunctionEvaluator;
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
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class RaxStudioControllerTest {

    private DuckDbConnectionManager duckDbConnectionManager;
    private SemanticModelManager semanticModelManager;
    private RaxStudioController studioController;
    private SemanticModelController modelController;
    private MockMvc mockMvc;
    private UUID modelId;

    @BeforeEach
    void setUp() {
        duckDbConnectionManager = new DuckDbConnectionManager("1GB", 2);
        duckDbConnectionManager.initializeEngine();
        semanticModelManager = new SemanticModelManager(new RelationshipInferenceService(), new RsdlFormatter());
        ContextEvaluator contextEvaluator = new ContextEvaluator();
        RaxFormulaCompiler formulaCompiler = new RaxFormulaCompiler(contextEvaluator, new TimeIntelligenceCompiler());
        InfoFunctionEvaluator infoEvaluator = new InfoFunctionEvaluator();

        CalculatedColumnService columnService = new CalculatedColumnService(
                formulaCompiler, contextEvaluator, semanticModelManager, duckDbConnectionManager);
        CalculatedTableService tableService = new CalculatedTableService(
                semanticModelManager, infoEvaluator, duckDbConnectionManager);
        DualEngineCoordinator coordinator = new DualEngineCoordinator(
                formulaCompiler, duckDbConnectionManager, semanticModelManager,
                new RlsSecurityContextResolver(), new DynamicDataMaskingService(),
                null, null, null, 1_000_000L);

        studioController = new RaxStudioController(columnService, tableService);
        modelController = new SemanticModelController(semanticModelManager, coordinator, infoEvaluator);
        mockMvc = MockMvcBuilders.standaloneSetup(studioController, modelController)
                .setControllerAdvice(new ErrorHandler())
                .setMessageConverters(JacksonConfig.createJsonHttpMessageConverter())
                .build();

        modelId = UUID.randomUUID();
        TableDefinition measurements = new TableDefinition(
                "measurements",
                "FACT",
                null,
                2L,
                false,
                List.of(
                        new ColumnDefinition("sample_id", "INT", true, false),
                        new ColumnDefinition("raw_value", "DECIMAL", false, false),
                        new ColumnDefinition("label", "VARCHAR", false, false),
                        new ColumnDefinition("sort_order", "INT", false, false)));
        semanticModelManager.registerModel(new SemanticModelSchema(
                modelId, "Lab Metrics", 1,
                List.of(measurements), List.of(), List.of(), Map.of(), false));

        duckDbConnectionManager.ingestTableRows("measurements", List.of(
                Map.of("sample_id", 1, "raw_value", 10.5, "label", "Low", "sort_order", 1),
                Map.of("sample_id", 2, "raw_value", 25.0, "label", "High", "sort_order", 2)));
    }

    @AfterEach
    void tearDown() {
        duckDbConnectionManager.shutdownEngine();
    }

    @Test
    @DisplayName("POST /api/rax/calculated-column evaluates and persists column metadata")
    void testCalculatedColumnEndpoint() throws Exception {
        String payload = """
                {
                  "modelId": "%s",
                  "tableName": "measurements",
                  "columnName": "doubled_value",
                  "formula": "[raw_value] * 2"
                }
                """.formatted(modelId);

        mockMvc.perform(post("/api/rax/calculated-column")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.columnName").value("doubled_value"))
                .andExpect(jsonPath("$.inferredDataType").value("DECIMAL"))
                .andExpect(jsonPath("$.rows.length()").value(2))
                .andExpect(jsonPath("$.rows[0].doubled_value").value(21.0))
                .andExpect(jsonPath("$.diagnostics").isArray());

        ResponseEntity<CalculatedColumnResult> direct = studioController.evaluateCalculatedColumn(
                new CalculatedColumnRequest(modelId, "measurements", "tripled", "[raw_value] * 3"));
        assertThat(direct.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(direct.getBody()).isNotNull();
        assertThat(direct.getBody().diagnostics()).isEmpty();
    }

    @Test
    @DisplayName("POST /api/rax/calculated-table generates CALENDAR table and columns")
    void testCalculatedTableEndpoint() throws Exception {
        String payload = """
                {
                  "modelId": "%s",
                  "tableName": "Calendar",
                  "formula": "CALENDAR(DATE(2026, 1, 1), DATE(2026, 1, 10))"
                }
                """.formatted(modelId);

        mockMvc.perform(post("/api/rax/calculated-table")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tableName").value("Calendar"))
                .andExpect(jsonPath("$.isDateTable").value(true))
                .andExpect(jsonPath("$.dateColumn").value("Date"))
                .andExpect(jsonPath("$.rows.length()").value(10))
                .andExpect(jsonPath("$.columns.length()").value(7));

        ResponseEntity<CalculatedTableResult> direct = studioController.evaluateCalculatedTable(
                new CalculatedTableRequest(modelId, "Summary", "INFO.VIEW.TABLES()"));
        assertThat(direct.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(direct.getBody()).isNotNull();
        assertThat(direct.getBody().columns()).isNotEmpty();
    }

    @Test
    @DisplayName("PATCH /api/models/{modelId}/tables/{tableName}/columns/{columnName} updates metadata and maps errors to ProblemDetail")
    void testUpdateColumnMetadataEndpointAndProblemDetail() throws Exception {
        String validPatch = """
                {
                  "dataType": "VARCHAR",
                  "summarizeBy": "None",
                  "sortByColumn": "sort_order",
                  "isHidden": true,
                  "format": "Currency"
                }
                """;

        mockMvc.perform(patch("/api/models/{modelId}/tables/{tableName}/columns/{columnName}",
                        modelId, "measurements", "label")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validPatch))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.schema.modelId").value(modelId.toString()));

        ResponseEntity<ModelMutationResult> fetched = modelController.updateColumnMetadata(
                modelId, "measurements", "label", new ColumnMetadataUpdate(null, "Count", null, false));
        assertThat(fetched.getBody()).isNotNull();
        ColumnDefinition updatedCol = fetched.getBody().schema().tables().stream()
                .filter(t -> t.name().equals("measurements"))
                .findFirst().orElseThrow()
                .columns().stream()
                .filter(c -> c.name().equals("label"))
                .findFirst().orElseThrow();
        assertThat(updatedCol.summarizeBy()).isEqualTo("Count");
        assertThat(updatedCol.sortByColumn()).isEqualTo("sort_order");
        assertThat(updatedCol.isHidden()).isFalse();
        assertThat(updatedCol.format()).isEqualTo("Currency");

        String selfSortPatch = """
                {
                  "sortByColumn": "label"
                }
                """;
        mockMvc.perform(patch("/api/models/{modelId}/tables/{tableName}/columns/{columnName}",
                        modelId, "measurements", "label")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(selfSortPatch))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("ERR_INVALID_ARGUMENT"))
                .andExpect(jsonPath("$.detail").exists());
    }
}
