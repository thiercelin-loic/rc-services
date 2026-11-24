package com.rollercoaster.rax.diagnostics;

import com.rollercoaster.config.ErrorHandler;
import com.rollercoaster.model.ColumnDefinition;
import com.rollercoaster.model.DualEngineCoordinator;
import com.rollercoaster.model.DuckDbConnectionManager;
import com.rollercoaster.model.MeasureDefinition;
import com.rollercoaster.model.QueryExecutionResult;
import com.rollercoaster.model.SemanticModelManager;
import com.rollercoaster.model.SemanticModelSchema;
import com.rollercoaster.model.TableDefinition;
import com.rollercoaster.rax.ast.SqlDialect;
import com.rollercoaster.rax.compiler.AnalyticalArchetypeLibrary;
import com.rollercoaster.rax.compiler.AnalyticalArchetypeLibrary.RaxFunctionCatalogEntry;
import com.rollercoaster.rax.compiler.InfoFunctionEvaluator;
import com.rollercoaster.rax.compiler.RaxFormulaCompiler;
import com.rollercoaster.rax.compiler.RaxQueryFormatter;
import com.rollercoaster.rax.compiler.RaxScriptService;
import com.rollercoaster.rax.compiler.TimeIntelligenceCompiler;
import com.rollercoaster.rax.context.ContextEvaluator;
import com.rollercoaster.rax.diagnostics.MonacoDiagnosticsService.RaxValidationResponse;
import com.rollercoaster.rax.diagnostics.RaxDiagnosticsController.ApplyMeasuresRequest;
import com.rollercoaster.rax.diagnostics.RaxDiagnosticsController.ApplyMeasuresResponse;
import com.rollercoaster.rax.diagnostics.RaxDiagnosticsController.DefineMeasuresResponse;
import com.rollercoaster.rax.diagnostics.RaxDiagnosticsController.ExecuteRaxScriptRequest;
import com.rollercoaster.rax.diagnostics.RaxDiagnosticsController.FormatQueryRequest;
import com.rollercoaster.rax.diagnostics.RaxDiagnosticsController.FormatQueryResponse;
import com.rollercoaster.rax.diagnostics.RaxDiagnosticsController.FormulaInspectRequest;
import com.rollercoaster.rax.diagnostics.RaxDiagnosticsController.TmdlGenerateRequest;
import com.rollercoaster.rax.diagnostics.RaxDiagnosticsController.TmdlGenerateResponse;
import com.rollercoaster.security.DynamicDataMaskingService;
import com.rollercoaster.security.RlsSecurityContextResolver;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class RaxDiagnosticsControllerTest {

    private DuckDbConnectionManager duckDbConnectionManager;
    private RaxDiagnosticsController controller;
    private ErrorHandler errorHandler;
    private MockMvc mockMvc;
    private SemanticModelSchema testSchema;
    private UUID modelId;

    @BeforeEach
    void setUp() {
        duckDbConnectionManager = new DuckDbConnectionManager("1GB", 2);
        duckDbConnectionManager.initializeEngine();

        AnalyticalArchetypeLibrary archetypeLibrary = new AnalyticalArchetypeLibrary();
        MonacoDiagnosticsService diagnosticsService = new MonacoDiagnosticsService(null, archetypeLibrary);
        RaxQueryFormatter queryFormatter = new RaxQueryFormatter();
        SemanticModelManager semanticModelManager = new SemanticModelManager();
        RaxFormulaCompiler formulaCompiler = new RaxFormulaCompiler(
                new ContextEvaluator(), new TimeIntelligenceCompiler());
        InfoFunctionEvaluator infoEvaluator = new InfoFunctionEvaluator();

        DualEngineCoordinator coordinator = new DualEngineCoordinator(
                formulaCompiler,
                duckDbConnectionManager,
                semanticModelManager,
                new RlsSecurityContextResolver(),
                new DynamicDataMaskingService(),
                null,
                null,
                null,
                1_000_000L
        );

        RaxScriptService scriptService = new RaxScriptService(
                semanticModelManager,
                coordinator,
                formulaCompiler,
                infoEvaluator
        );

        controller = new RaxDiagnosticsController(
                diagnosticsService,
                queryFormatter,
                scriptService,
                semanticModelManager,
                archetypeLibrary
        );
        errorHandler = new ErrorHandler();
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(errorHandler)
                .build();

        modelId = UUID.randomUUID();
        testSchema = new SemanticModelSchema(
                modelId,
                "Sensor Grid Model",
                1,
                List.of(
                        new TableDefinition(
                                "readings",
                                "FACT",
                                null,
                                100L,
                                false,
                                "Sensor readings table",
                                List.of(
                                        new ColumnDefinition("sensor_id", "VARCHAR", false, false),
                                        new ColumnDefinition("temperature", "DOUBLE", false, false)
                                )
                        )
                ),
                List.of(),
                List.of(
                        new MeasureDefinition(
                                "m_avg_temp",
                                "Avg Temp",
                                "readings",
                                "AVERAGE(readings[temperature])",
                                "#,##0.0"
                        )
                ),
                Map.of()
        );
        semanticModelManager.registerModel(testSchema);

        coordinator.ingestDatasetRows("default-tenant", "readings", List.of(
                Map.of("sensor_id", "S1", "temperature", 21.5),
                Map.of("sensor_id", "S2", "temperature", 24.5)
        ));
    }

    @AfterEach
    void tearDown() {
        duckDbConnectionManager.shutdownEngine();
    }

    @Test
    @DisplayName("POST /api/rax/diagnostics/validate validates full DEFINE MEASURE + EVALUATE script and detects unknown functions/tables/columns")
    void testValidateFullScriptAndSemanticErrors() {
        String validScript = """
                DEFINE
                    MEASURE 'readings'[Max Temp] = MAX(readings[temperature])
                EVALUATE
                    SUMMARIZECOLUMNS(readings[sensor_id], "Max Temp", [Max Temp])
                """;
        ResponseEntity<RaxValidationResponse> validResp = controller.inspectFormulaRest(
                new FormulaInspectRequest("ws-1", validScript, "Max Temp", testSchema));

        assertThat(validResp.getBody()).isNotNull();
        assertThat(validResp.getBody().isValid()).isTrue();
        assertThat(validResp.getBody().diagnostics()).isEmpty();

        String badFunctionScript = "EVALUATE UNKNOWNFUNC(readings[temperature])";
        ResponseEntity<RaxValidationResponse> badFnResp = controller.inspectFormulaRest(
                new FormulaInspectRequest("ws-1", badFunctionScript, "Test", testSchema));
        assertThat(badFnResp.getBody()).isNotNull();
        assertThat(badFnResp.getBody().isValid()).isFalse();
        assertThat(badFnResp.getBody().diagnostics())
                .extracting(DiagnosticMarker::code)
                .contains("ERR_UNKNOWN_FUNCTION");

        String badTableScript = "SUM(missing_table[temperature])";
        ResponseEntity<RaxValidationResponse> badTblResp = controller.inspectFormulaRest(
                new FormulaInspectRequest("ws-1", badTableScript, "Test", testSchema));
        assertThat(badTblResp.getBody()).isNotNull();
        assertThat(badTblResp.getBody().diagnostics())
                .extracting(DiagnosticMarker::code)
                .contains("ERR_UNKNOWN_TABLE");

        String badColumnScript = "SUM(readings[missing_col])";
        ResponseEntity<RaxValidationResponse> badColResp = controller.inspectFormulaRest(
                new FormulaInspectRequest("ws-1", badColumnScript, "Test", testSchema));
        assertThat(badColResp.getBody()).isNotNull();
        assertThat(badColResp.getBody().diagnostics())
                .extracting(DiagnosticMarker::code)
                .contains("ERR_UNKNOWN_COLUMN");
    }

    @Test
    @DisplayName("POST /api/rax/format, POST /api/rax/tmdl, POST /api/rax/measures/define, POST /api/rax/execute, POST /api/rax/measures/apply, and GET /api/rax/catalog")
    void testStudioEndpointsAndProblemDetailAdvice() {
        ResponseEntity<FormatQueryResponse> formatResp = controller.formatScript(
                new FormatQueryRequest("evaluate info.tables()"));
        assertThat(formatResp.getBody()).isNotNull();
        assertThat(formatResp.getBody().formattedScript()).isEqualTo("EVALUATE\n    INFO.TABLES()");

        ResponseEntity<TmdlGenerateResponse> tmdlResp = controller.generateModelTmdl(
                new TmdlGenerateRequest(modelId));
        assertThat(tmdlResp.getBody()).isNotNull();
        assertThat(tmdlResp.getBody().tmdlScript())
                .contains("createOrReplace")
                .contains("table 'readings'")
                .contains("measure 'Avg Temp' = AVERAGE(readings[temperature])");

        ResponseEntity<DefineMeasuresResponse> defineResp = controller.defineAllMeasures(
                new TmdlGenerateRequest(modelId));
        assertThat(defineResp.getBody()).isNotNull();
        assertThat(defineResp.getBody().script())
                .contains("MEASURE 'readings'[Avg Temp] = AVERAGE(readings[temperature])");

        ResponseEntity<QueryExecutionResult> execResp = controller.executeScript(
                new ExecuteRaxScriptRequest(modelId, "EVALUATE INFO.MEASURES()", SqlDialect.DUCKDB),
                null);
        assertThat(execResp.getBody()).isNotNull();
        assertThat(execResp.getBody().rows()).hasSize(1);

        ResponseEntity<ApplyMeasuresResponse> applyResp = controller.applyMeasuresFromScript(
                new ApplyMeasuresRequest(modelId, "DEFINE MEASURE 'readings'[Min Temp] = MIN(readings[temperature])"));
        assertThat(applyResp.getBody()).isNotNull();
        assertThat(applyResp.getBody().appliedCount()).isEqualTo(1);
        assertThat(applyResp.getBody().measures()).hasSize(2);

        ResponseEntity<List<RaxFunctionCatalogEntry>> catalogResp = controller.getFunctionCatalog();
        assertThat(catalogResp.getBody()).isNotNull().isNotEmpty();
        assertThat(catalogResp.getBody())
                .extracting(RaxFunctionCatalogEntry::label)
                .contains("CALCULATE", "SUMMARIZECOLUMNS", "SELECTCOLUMNS", "INFO.TABLES");

        ProblemDetail problem = errorHandler.handleIllegalArgument(
                new IllegalArgumentException("ERR_RAX_SYNTAX: Line 1:0 - extraneous input"));
        assertThat(problem.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST.value());
        assertThat(problem.getProperties()).containsEntry("errorCode", "ERR_RAX_SYNTAX");
    }

    @Test
    @DisplayName("MockMvc HTTP layer verifies JSON serialization, modelId lookup, and RFC 9457 ProblemDetail error responses")
    void testHttpSerializationAndValidationViaMockMvc() throws Exception {
        mockMvc.perform(get("/api/rax/catalog"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].label").isNotEmpty())
                .andExpect(jsonPath("$[0].signature").isNotEmpty())
                .andExpect(jsonPath("$[0].doc").isNotEmpty());

        mockMvc.perform(post("/api/rax/diagnostics/validate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "workspaceId": "ws-live",
                                  "modelId": "%s",
                                  "formula": "SUM(readings[temperature])",
                                  "defaultMeasureName": "TempSum"
                                }
                                """.formatted(modelId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.isValid").value(true))
                .andExpect(jsonPath("$.diagnostics").isArray())
                .andExpect(jsonPath("$.diagnostics.length()").value(0));

        mockMvc.perform(post("/api/rax/format")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"script": "evaluate info.tables()"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.formattedScript").value("EVALUATE\n    INFO.TABLES()"));

        mockMvc.perform(post("/api/rax/tmdl")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"modelId": "%s"}
                                """.formatted(modelId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tmdlScript").value(org.hamcrest.Matchers.containsString("createOrReplace")));

        mockMvc.perform(post("/api/rax/execute")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "modelId": "%s",
                                  "script": "EVALUATE SUMMARIZE('readings', readings[sensor_id], \\"Avg\\", [Avg Temp]) ORDER BY [Avg] DESC",
                                  "dialect": "DUCKDB"
                                }
                                """.formatted(modelId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.routedEngine").value("DUCKDB"))
                .andExpect(jsonPath("$.rows.length()").value(2))
                .andExpect(jsonPath("$.rows[0].sensor_id").value("S2"));

        mockMvc.perform(post("/api/rax/execute")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"modelId": "%s", "script": "   ", "dialect": "DUCKDB"}
                                """.formatted(modelId)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("ERR_VALIDATION_FAILED"));
    }
}
