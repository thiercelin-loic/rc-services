package com.rollercoaster.model;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RsdlFormatterTest {

    private final RsdlFormatter formatter = new RsdlFormatter();

    @Test
    void formatGeneratesCanonicalRsdlWithTablesRelationshipsMeasuresAndRlsPredicates() {
        TableDefinition factTable = new TableDefinition(
                "telemetry", "FACT", null, 1000L, false, "", false, false, "",
                List.of(
                        new ColumnDefinition("reading_id", "INT64", true, false),
                        new ColumnDefinition("sensor_id", "VARCHAR", false, true, true, null),
                        new ColumnDefinition("temperature", "DOUBLE", false, false)
                ));
        TableDefinition dateTable = new TableDefinition(
                "calendar", "DIMENSION", null, 365L, false, "", false, true, "calendar_date",
                List.of(new ColumnDefinition("calendar_date", "DATE", true, false)));

        RelationshipDefinition relationship = new RelationshipDefinition(
                "rel_telemetry_sensor",
                "telemetry",
                "sensor_id",
                "calendar",
                "calendar_date",
                Cardinality.MANY_TO_MANY,
                CrossFilterDirection.BOTH,
                true,
                "bridge_sensor_calendar");

        MeasureDefinition measure = new MeasureDefinition(
                "m_avg_temp",
                "Avg Temperature",
                "telemetry",
                "AVERAGE(telemetry[temperature])",
                "#,##0.0",
                "Average sensor temperature");

        SemanticModelSchema schema = new SemanticModelSchema(
                UUID.randomUUID(),
                "IoT Fleet Model",
                4,
                List.of(factTable, dateTable),
                List.of(relationship),
                List.of(measure),
                Map.of("RegionAnalyst", "telemetry[region] = USERPRINCIPALNAME()"),
                true);

        String rsdl = formatter.format(schema);

        assertTrue(rsdl.contains("model 'IoT Fleet Model' {"));
        assertTrue(rsdl.contains("fiscalYearStartMonth: 4"));
        assertTrue(rsdl.contains("discourageImplicitMeasures: true"));
        assertTrue(rsdl.contains("column 'reading_id': INT64 [PK]"));
        assertTrue(rsdl.contains("column 'sensor_id': VARCHAR [FK] [Hidden]"));
        assertTrue(rsdl.contains("isDateTable: true"));
        assertTrue(rsdl.contains("dateColumn: 'calendar_date'"));
        assertTrue(rsdl.contains("relationship 'rel_telemetry_sensor'"));
        assertTrue(rsdl.contains("cardinality: ManyToMany"));
        assertTrue(rsdl.contains("crossFilter: Both"));
        assertTrue(rsdl.contains("bridgeTable: 'bridge_sensor_calendar'"));
        assertTrue(rsdl.contains("/// Average sensor temperature"));
        assertTrue(rsdl.contains("measure 'telemetry'[Avg Temperature] = AVERAGE(telemetry[temperature])"));
        assertTrue(rsdl.contains("formatString: \"#,##0.0\""));
        assertTrue(rsdl.contains("role 'RegionAnalyst' {"));
        assertTrue(rsdl.contains("filterPredicate: telemetry[region] = USERPRINCIPALNAME()"));
    }

    @Test
    void parseRelationshipsRoundTripsFormattedRsdlRelationships() {
        RelationshipDefinition activeRel = new RelationshipDefinition(
                "rel_a", "fact_a", "dim_id", "dim_b", "dim_id",
                Cardinality.ONE_TO_ONE, CrossFilterDirection.BOTH, true);
        RelationshipDefinition inactiveRel = new RelationshipDefinition(
                "rel_b", "fact_a", "alt_dim_id", "dim_b", "dim_id",
                Cardinality.MANY_TO_ONE, CrossFilterDirection.SINGLE, false);

        SemanticModelSchema schema = new SemanticModelSchema(
                UUID.randomUUID(), "Test", 1, List.of(), List.of(activeRel, inactiveRel), List.of(), Map.of());

        String rsdl = formatter.format(schema);
        List<RelationshipDefinition> parsed = formatter.parseRelationships(rsdl);

        assertEquals(2, parsed.size());
        assertEquals("rel_a", parsed.get(0).name());
        assertEquals(Cardinality.ONE_TO_ONE, parsed.get(0).cardinality());
        assertEquals(CrossFilterDirection.BOTH, parsed.get(0).crossFilterDirection());
        assertTrue(parsed.get(0).isActive());

        assertEquals("rel_b", parsed.get(1).name());
        assertEquals(Cardinality.MANY_TO_ONE, parsed.get(1).cardinality());
        assertEquals(CrossFilterDirection.SINGLE, parsed.get(1).crossFilterDirection());
        assertFalse(parsed.get(1).isActive());
    }

    @Test
    void formatThrowsNullPointerExceptionWhenSchemaIsNull() {
        assertThrows(NullPointerException.class, () -> formatter.format(null));
    }
}
