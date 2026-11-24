package com.rollercoaster.ingestion.rquery;

import com.rollercoaster.ingestion.DataIngestionService;
import com.rollercoaster.ingestion.DataType;
import com.rollercoaster.ingestion.IngestionDtos.PipelineColumnDto;
import com.rollercoaster.ingestion.IngestionDtos.PipelinePreviewRequest;
import com.rollercoaster.ingestion.IngestionDtos.PipelinePreviewResponse;
import com.rollercoaster.ingestion.IngestionDtos.SheetColumnDto;
import com.rollercoaster.ingestion.SheetExtractionService;
import com.rollercoaster.ingestion.SourceFormat;
import com.rollercoaster.ingestion.parser.ColumnarChannelParser;
import com.rollercoaster.ingestion.parser.DelimitedFileParser;
import com.rollercoaster.ingestion.parser.JsonXmlStreamingParser;
import com.rollercoaster.ingestion.parser.TypeInferenceEngine;
import com.rollercoaster.ingestion.parser.XlsxStreamingParser;
import com.rollercoaster.ingestion.rquery.DataPrepPipelineService.InitialPipelineSpec;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DataPrepPipelineServiceTest {

    private DataPrepPipelineService pipelineService;

    @BeforeEach
    void setUp() {
        TypeInferenceEngine typeInferenceEngine = new TypeInferenceEngine();
        DelimitedFileParser delimitedFileParser = new DelimitedFileParser(typeInferenceEngine);
        JsonXmlStreamingParser jsonXmlStreamingParser = new JsonXmlStreamingParser(typeInferenceEngine);
        XlsxStreamingParser xlsxStreamingParser = new XlsxStreamingParser(typeInferenceEngine);
        ColumnarChannelParser columnarChannelParser = new ColumnarChannelParser(null, typeInferenceEngine);
        DataIngestionService dataIngestionService = new DataIngestionService(
                delimitedFileParser, jsonXmlStreamingParser, xlsxStreamingParser, columnarChannelParser, null);
        RQueryTransformationEngine transformationEngine = new RQueryTransformationEngine(typeInferenceEngine);
        MQueryFormatter mQueryFormatter = new MQueryFormatter();
        pipelineService = new DataPrepPipelineService(dataIngestionService, transformationEngine, mQueryFormatter);
    }

    @Test
    void dataTypeAndSourceFormatHelpersProvideSingleSourceOfTruth() {
        assertEquals("Int64", DataType.INT64.displayLabel());
        assertEquals("Float64", DataType.FLOAT64.displayLabel());
        assertEquals("Decimal", DataType.DECIMAL.displayLabel());
        assertEquals("Currency", DataType.CURRENCY.displayLabel());
        assertEquals("Boolean", DataType.BOOLEAN.displayLabel());
        assertEquals("Date", DataType.ISO_DATE.displayLabel());
        assertEquals("DateTime", DataType.ISO_DATETIME.displayLabel());
        assertEquals("String", DataType.UTF8_STRING.displayLabel());

        assertTrue(DataType.INT64.isNumeric());
        assertTrue(DataType.CURRENCY.isNumeric());
        assertFalse(DataType.ISO_DATE.isNumeric());
        assertFalse(DataType.UTF8_STRING.isNumeric());

        assertEquals(DataType.INT64, DataType.fromLabel("Whole Number"));
        assertEquals(DataType.FLOAT64, DataType.fromLabel("Float64"));
        assertEquals(DataType.ISO_DATE, DataType.fromLabel("Date"));
        assertEquals(DataType.UTF8_STRING, DataType.fromLabel(null));
        assertEquals(DataType.UTF8_STRING, DataType.fromLabel("unknown_type"));

        assertEquals(Optional.of(SourceFormat.XLSX), SourceFormat.fromFileName("report_2025.xlsx"));
        assertEquals(Optional.of(SourceFormat.CSV), SourceFormat.fromFileName("metrics.csv"));
        assertEquals(Optional.empty(), SourceFormat.fromFileName("   "));
        assertTrue(SourceFormat.XLSX.isBinary());
        assertTrue(SourceFormat.AVRO.isBinary());
        assertFalse(SourceFormat.CSV.isBinary());

        assertEquals("quarterly metrics 2025", SheetExtractionService.sanitizeTableName("path/to/quarterly_metrics-2025.csv"));
    }

    @Test
    void buildInitialPipelineConstructsFourCanonicalStepsAndNormalizeStripsMetadata() {
        Map<String, DataType> colTypes = new LinkedHashMap<>();
        colTypes.put("id", DataType.INT64);
        colTypes.put("label", DataType.UTF8_STRING);

        List<RQueryStep> initial = pipelineService.buildInitialPipeline(
                new InitialPipelineSpec("sensors_2025.csv", SourceFormat.CSV, "sensors", colTypes));

        assertEquals(4, initial.size());
        assertEquals("FileSource", initial.get(0).step());
        assertEquals("Navigation", initial.get(1).step());
        assertEquals("PromoteHeadersStep", initial.get(2).step());
        assertEquals("ChangeTypeStep", initial.get(3).step());

        List<RQueryStep> normalized = pipelineService.normalizeSteps(initial);
        assertEquals(2, normalized.size());
        assertEquals("PromoteHeadersStep", normalized.get(0).step());
        assertEquals("ChangeTypeStep", normalized.get(1).step());
    }

    @Test
    void evaluatePreviewExecutesStepsAndGeneratesMQueryAndQuarantineRecords() {
        List<SheetColumnDto> initialCols = List.of(
                new SheetColumnDto("Column1", "String", "Abc"),
                new SheetColumnDto("Column2", "String", "Abc")
        );
        List<Map<String, Object>> initialRows = List.of(
                Map.of("Column1", "id", "Column2", "score"),
                Map.of("Column1", "1", "Column2", "42.5"),
                Map.of("Column1", "2", "Column2", "invalid_number"),
                Map.of("Column1", "3", "Column2", "88.0")
        );

        List<RQueryStep> steps = List.of(
                new RQueryStep("FileSource", null, "scores.csv", SourceFormat.CSV, null, null,
                        null, null, null, null, null, null, null, null, null, null, null, null, null, null),
                new RQueryStep("Navigation", "FileSource", null, null, "scores", null,
                        null, null, null, null, null, null, null, null, null, "scores", null, null, null, null),
                RQueryStep.promoteHeaders("Navigation"),
                RQueryStep.changeType("PromoteHeadersStep", "score", DataType.FLOAT64),
                RQueryStep.filterRows("ChangeTypeStep", "[score] > 50")
        );

        PipelinePreviewRequest request = new PipelinePreviewRequest(
                "t1",
                "d1",
                "scores.csv",
                SourceFormat.CSV,
                null,
                null,
                "scores",
                steps,
                Map.of(),
                initialCols,
                initialRows,
                "2025-01-01",
                "2025-12-31"
        );

        PipelinePreviewResponse response = pipelineService.evaluatePreview(request);

        assertNotNull(response.quarantinedRecords());
        assertEquals(1, response.quarantinedRecords().size());
        assertEquals("score", response.quarantinedRecords().getFirst().columnName());

        assertEquals(1, response.rows().size());
        assertEquals(88.0, response.rows().getFirst().get("score"));

        assertEquals(2, response.columns().size());
        PipelineColumnDto scoreCol = response.columns().get(1);
        assertEquals("score", scoreCol.name());
        assertEquals("FLOAT64", scoreCol.type());
        assertEquals("Float64", scoreCol.displayType());
        assertTrue(scoreCol.numeric());

        assertTrue(response.mQueryScript().contains("RangeStart = \"2025-01-01\""));
        assertTrue(response.mQueryScript().contains("Table.SelectRows"));
        assertTrue(response.activeStepFormula().startsWith("= Table.SelectRows"));
    }
}
