package com.rollercoaster.model;

import com.rollercoaster.config.ErrorHandler;
import com.rollercoaster.config.JacksonConfig;
import com.rollercoaster.rax.compiler.InfoFunctionEvaluator;
import com.rollercoaster.rax.compiler.RaxFormulaCompiler;
import com.rollercoaster.rax.compiler.TimeIntelligenceCompiler;
import com.rollercoaster.rax.context.ContextEvaluator;
import com.rollercoaster.security.DynamicDataMaskingService;
import com.rollercoaster.security.RlsSecurityContextResolver;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.converter.StringHttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class SemanticModelControllerTest {

    private DuckDbConnectionManager duckDbConnectionManager;
    private SemanticModelManager semanticModelManager;
    private MockMvc mockMvc;
    private UUID modelId;

    @BeforeEach
    void setUp() {
        duckDbConnectionManager = new DuckDbConnectionManager("1GB", 2);
        duckDbConnectionManager.initializeEngine();

        RelationshipInferenceService inferenceService = new RelationshipInferenceService();
        RsdlFormatter rsdlFormatter = new RsdlFormatter();
        semanticModelManager = new SemanticModelManager(inferenceService, rsdlFormatter);

        RaxFormulaCompiler compiler = new RaxFormulaCompiler(
                new ContextEvaluator(), new TimeIntelligenceCompiler());
        DualEngineCoordinator coordinator = new DualEngineCoordinator(
                compiler,
                duckDbConnectionManager,
                semanticModelManager,
                new RlsSecurityContextResolver(),
                new DynamicDataMaskingService(),
                null,
                null,
                null,
                1_000_000L);

        SemanticModelController controller = new SemanticModelController(
                semanticModelManager,
                inferenceService,
                rsdlFormatter,
                coordinator,
                new InfoFunctionEvaluator());

        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new ErrorHandler())
                .setMessageConverters(
                        new StringHttpMessageConverter(StandardCharsets.UTF_8),
                        JacksonConfig.createJsonHttpMessageConverter())
                .build();

        modelId = UUID.randomUUID();
        TableDefinition events = new TableDefinition(
                "events", "FACT", null, 500L, false,
                List.of(
                        new ColumnDefinition("event_id", "INT64", true, false),
                        new ColumnDefinition("device_id", "VARCHAR", false, false),
                        new ColumnDefinition("backup_device_id", "VARCHAR", false, false),
                        new ColumnDefinition("event_date", "DATE", false, false)
                ));
        TableDefinition devices = new TableDefinition(
                "devices", "DIMENSION", null, 50L, false,
                List.of(
                        new ColumnDefinition("device_id", "VARCHAR", true, false),
                        new ColumnDefinition("device_name", "VARCHAR", false, false)
                ));
        TableDefinition calendar = new TableDefinition(
                "calendar", "DIMENSION", null, 365L, false,
                List.of(
                        new ColumnDefinition("calendar_date", "DATE", true, false),
                        new ColumnDefinition("year_num", "INT64", false, false)
                ));

        semanticModelManager.registerModel(new SemanticModelSchema(
                modelId, "Telemetry Workspace", 1,
                List.of(events, devices, calendar), List.of(), List.of(), Map.of(), false));
    }

    @AfterEach
    void tearDown() {
        duckDbConnectionManager.shutdownEngine();
    }

    @Test
    void inferRelationshipsEndpointReturnsInferredEdgesAndDateColumns() throws Exception {
        String payload = """
                {
                  "tables": [
                    {
                      "name": "shipments",
                      "role": "FACT",
                      "columns": [
                        { "name": "shipment_id", "dataType": "INT64", "isPrimaryKey": true, "isForeignKey": false },
                        { "name": "carrier_id", "dataType": "VARCHAR", "isPrimaryKey": false, "isForeignKey": false },
                        { "name": "dispatch_date", "dataType": "DATE", "isPrimaryKey": false, "isForeignKey": false }
                      ]
                    },
                    {
                      "name": "carriers",
                      "role": "DIMENSION",
                      "columns": [
                        { "name": "carrier_id", "dataType": "VARCHAR", "isPrimaryKey": true, "isForeignKey": false },
                        { "name": "carrier_name", "dataType": "VARCHAR", "isPrimaryKey": false, "isForeignKey": false }
                      ]
                    }
                  ]
                }
                """;

        mockMvc.perform(post("/api/models/infer-relationships")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.relationships", hasSize(1)))
                .andExpect(jsonPath("$.relationships[0].fromTable").value("shipments"))
                .andExpect(jsonPath("$.relationships[0].fromColumn").value("carrier_id"))
                .andExpect(jsonPath("$.relationships[0].toTable").value("carriers"))
                .andExpect(jsonPath("$.relationships[0].isActive").value(true))
                .andExpect(jsonPath("$.dateColumnsByTable.shipments").value("dispatch_date"))
                .andExpect(jsonPath("$.suggestedDateColumns.shipments").value("dispatch_date"));
    }

    @Test
    void putModelAndRsdlEndpointReturnSchemaRsdlAndDagValidationInOnePayload() throws Exception {
        String schemaJson = """
                {
                  "modelId": "%s",
                  "name": "Saved Telemetry Model",
                  "fiscalYearStartMonth": 4,
                  "tables": [
                    {
                      "name": "events",
                      "role": "FACT",
                      "columns": [
                        { "name": "event_id", "dataType": "INT64", "isPrimaryKey": true, "isForeignKey": false },
                        { "name": "device_id", "dataType": "VARCHAR", "isPrimaryKey": false, "isForeignKey": true }
                      ]
                    },
                    {
                      "name": "devices",
                      "role": "DIMENSION",
                      "columns": [
                        { "name": "device_id", "dataType": "VARCHAR", "isPrimaryKey": true, "isForeignKey": false }
                      ]
                    }
                  ],
                  "relationships": [
                    {
                      "name": "rel_events_devices",
                      "fromTable": "events",
                      "fromColumn": "device_id",
                      "toTable": "devices",
                      "toColumn": "device_id",
                      "cardinality": "MANY_TO_ONE",
                      "crossFilterDirection": "SINGLE",
                      "isActive": true
                    }
                  ],
                  "measures": [],
                  "rlsRolePredicates": {},
                  "discourageImplicitMeasures": true
                }
                """.formatted(modelId);

        mockMvc.perform(post("/api/models/rsdl")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(schemaJson))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.schema.name").value("Saved Telemetry Model"))
                .andExpect(jsonPath("$.dagValidation.valid").value(true))
                .andExpect(jsonPath("$.rsdl", containsString("discourageImplicitMeasures: true")))
                .andExpect(jsonPath("$.rsdl", containsString("relationship 'rel_events_devices'")));

        mockMvc.perform(put("/api/models/{modelId}", modelId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(schemaJson))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.schema.fiscalYearStartMonth").value(4))
                .andExpect(jsonPath("$.dagValidation.valid").value(true))
                .andExpect(jsonPath("$.rsdl", containsString("model 'Saved Telemetry Model'")));
    }

    @Test
    void relationshipAndModelMetadataMutationEndpointsWorkEndToEnd() throws Exception {
        String createRelJson = """
                {
                  "name": "rel_events_devices",
                  "fromTable": "events",
                  "fromColumn": "device_id",
                  "toTable": "devices",
                  "toColumn": "device_id",
                  "cardinality": "MANY_TO_ONE",
                  "crossFilterDirection": "SINGLE",
                  "isActive": true
                }
                """;

        mockMvc.perform(post("/api/models/{modelId}/relationships", modelId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createRelJson))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.schema.relationships", hasSize(1)))
                .andExpect(jsonPath("$.dagValidation.valid").value(true))
                .andExpect(jsonPath("$.rsdl", containsString("column 'device_id': VARCHAR [FK]")));

        mockMvc.perform(patch("/api/models/{modelId}/relationships/{name}/active", modelId, "rel_events_devices"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.schema.relationships[0].isActive").value(false))
                .andExpect(jsonPath("$.rsdl", containsString("isActive: false")));

        String routingJson = """
                {
                  "cardinality": "ONE_TO_ONE",
                  "crossFilterDirection": "BOTH"
                }
                """;
        mockMvc.perform(patch("/api/models/{modelId}/relationships/{name}/routing", modelId, "rel_events_devices")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(routingJson))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.schema.relationships[0].cardinality").value("ONE_TO_ONE"))
                .andExpect(jsonPath("$.schema.relationships[0].crossFilterDirection").value("BOTH"))
                .andExpect(jsonPath("$.rsdl", containsString("cardinality: OneToOne")))
                .andExpect(jsonPath("$.rsdl", containsString("crossFilter: Both")));

        mockMvc.perform(post("/api/models/{modelId}/hide-foreign-keys", modelId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rsdl", containsString("column 'device_id': VARCHAR [FK] [Hidden]")));

        mockMvc.perform(post("/api/models/{modelId}/primary-key", modelId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "tableName": "devices", "columnName": "device_name" }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rsdl", containsString("column 'device_name': VARCHAR [PK]")));

        mockMvc.perform(post("/api/models/{modelId}/date-table", modelId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "tableName": "calendar", "dateColumn": "calendar_date" }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rsdl", containsString("isDateTable: true")))
                .andExpect(jsonPath("$.rsdl", containsString("dateColumn: 'calendar_date'")));

        mockMvc.perform(post("/api/models/{modelId}/discourage-implicit-measures", modelId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.schema.discourageImplicitMeasures").value(true))
                .andExpect(jsonPath("$.rsdl", containsString("discourageImplicitMeasures: true")));

        mockMvc.perform(get("/api/models/{modelId}/rsdl", modelId))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("model 'Telemetry Workspace'")));

        mockMvc.perform(delete("/api/models/{modelId}/relationships/{name}", modelId, "rel_events_devices"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.schema.relationships", hasSize(0)));
    }

    @Test
    void domainValidationErrorsReturnRfc9457ProblemDetail() throws Exception {
        String selfJoinJson = """
                {
                  "name": "rel_self",
                  "fromTable": "events",
                  "fromColumn": "event_id",
                  "toTable": "events",
                  "toColumn": "event_id",
                  "cardinality": "ONE_TO_ONE",
                  "crossFilterDirection": "SINGLE",
                  "isActive": true
                }
                """;

        mockMvc.perform(post("/api/models/{modelId}/relationships", modelId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(selfJoinJson))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Invalid Request Argument"))
                .andExpect(jsonPath("$.errorCode").value("ERR_INVALID_ARGUMENT"))
                .andExpect(jsonPath("$.detail", containsString("Self-referential")));

        UUID mismatchedId = UUID.randomUUID();
        String mismatchedBody = """
                {
                  "modelId": "%s",
                  "name": "Wrong Id",
                  "fiscalYearStartMonth": 1,
                  "tables": [],
                  "relationships": [],
                  "measures": [],
                  "rlsRolePredicates": {}
                }
                """.formatted(mismatchedId);

        mockMvc.perform(put("/api/models/{modelId}", modelId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mismatchedBody))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("ERR_INVALID_ARGUMENT"))
                .andExpect(jsonPath("$.detail", containsString("identifiers must match")));

        mockMvc.perform(post("/api/models/rsdl")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{ not-valid-json"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Malformed Request Payload"))
                .andExpect(jsonPath("$.errorCode").value("ERR_MALFORMED_REQUEST"));
    }
}
