package com.rollercoaster.model;

import com.rollercoaster.model.RelationshipInferenceService.RelationshipInferenceResult;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RelationshipInferenceServiceTest {

    private final RelationshipInferenceService service = new RelationshipInferenceService();

    @Test
    void inferRelationshipsReturnsEmptyImmutableListForNullOrSingleTable() {
        List<RelationshipDefinition> fromNull = service.inferRelationshipsFromTables(null);
        assertNotNull(fromNull);
        assertTrue(fromNull.isEmpty());
        assertThrows(UnsupportedOperationException.class, () -> fromNull.add(null));

        TableDefinition loneTable = new TableDefinition(
                "telemetry_events", "FACT", null, 100L, false,
                List.of(new ColumnDefinition("event_id", "INT64", true, false)));
        List<RelationshipDefinition> fromSingle = service.inferRelationshipsFromTables(List.of(loneTable));
        assertTrue(fromSingle.isEmpty());
    }

    @Test
    void inferRelationshipsAutoDetectsDomainNeutralForeignKeysAndRolePlayingDimensions() {
        TableDefinition telemetryFact = new TableDefinition(
                "telemetry_readings", "FACT", null, 5000L, false,
                List.of(
                        new ColumnDefinition("reading_id", "INT64", true, false),
                        new ColumnDefinition("sensor_id", "VARCHAR", false, false),
                        new ColumnDefinition("date_id", "INT64", false, false),
                        new ColumnDefinition("calibration_date_id", "INT64", false, false),
                        new ColumnDefinition("facility_id", "INT64", false, false)
                ));
        TableDefinition sensorDim = new TableDefinition(
                "dim_sensors", "DIMENSION", null, 200L, false,
                List.of(
                        new ColumnDefinition("sensor_id", "VARCHAR", true, false),
                        new ColumnDefinition("model_name", "VARCHAR", false, false)
                ));
        TableDefinition calendarDim = new TableDefinition(
                "calendar", "DIMENSION", null, 365L, false,
                List.of(
                        new ColumnDefinition("date_id", "INT64", true, false),
                        new ColumnDefinition("calendar_date", "DATE", false, false)
                ));
        TableDefinition facilityDim = new TableDefinition(
                "facilities", "DIMENSION", null, 50L, false,
                List.of(
                        new ColumnDefinition("id", "INT64", true, false),
                        new ColumnDefinition("facility_name", "VARCHAR", false, false)
                ));

        List<RelationshipDefinition> relationships = service.inferRelationshipsFromTables(
                List.of(telemetryFact, sensorDim, calendarDim, facilityDim));

        assertEquals(4, relationships.size());
        assertThrows(UnsupportedOperationException.class, () -> relationships.add(null));

        RelationshipDefinition sensorRel = relationships.stream()
                .filter(r -> r.toTable().equals("dim_sensors"))
                .findFirst()
                .orElseThrow();
        assertEquals("telemetry_readings", sensorRel.fromTable());
        assertEquals("sensor_id", sensorRel.fromColumn());
        assertEquals("sensor_id", sensorRel.toColumn());
        assertEquals(Cardinality.MANY_TO_ONE, sensorRel.cardinality());
        assertEquals(CrossFilterDirection.SINGLE, sensorRel.crossFilterDirection());
        assertTrue(sensorRel.isActive());

        List<RelationshipDefinition> calendarRels = relationships.stream()
                .filter(r -> r.toTable().equals("calendar"))
                .toList();
        assertEquals(2, calendarRels.size());
        assertEquals(1L, calendarRels.stream().filter(RelationshipDefinition::isActive).count());
        RelationshipDefinition activeCalRel = calendarRels.stream()
                .filter(RelationshipDefinition::isActive)
                .findFirst()
                .orElseThrow();
        assertEquals("date_id", activeCalRel.fromColumn());
        RelationshipDefinition inactiveCalRel = calendarRels.stream()
                .filter(r -> !r.isActive())
                .findFirst()
                .orElseThrow();
        assertEquals("calibration_date_id", inactiveCalRel.fromColumn());

        RelationshipDefinition facilityRel = relationships.stream()
                .filter(r -> r.toTable().equals("facilities"))
                .findFirst()
                .orElseThrow();
        assertEquals("facility_id", facilityRel.fromColumn());
        assertEquals("id", facilityRel.toColumn());
        assertTrue(facilityRel.isActive());
    }

    @Test
    void inferRelationshipsSkipsDimensionToDimensionSnowflakeLinks() {
        TableDefinition dimA = new TableDefinition(
                "dim_department", "DIMENSION", null, 20L, false,
                List.of(
                        new ColumnDefinition("department_id", "INT64", true, false),
                        new ColumnDefinition("division_id", "INT64", false, false)
                ));
        TableDefinition dimB = new TableDefinition(
                "dim_division", "DIMENSION", null, 5L, false,
                List.of(new ColumnDefinition("division_id", "INT64", true, false)));

        List<RelationshipDefinition> relationships = service.inferRelationshipsFromTables(List.of(dimA, dimB));
        assertTrue(relationships.isEmpty());
    }

    @Test
    void inferDateColumnNamePrioritizesTemporalTypesAndFallsBackToNamingConventions() {
        TableDefinition typedDateTable = new TableDefinition(
                "calendar", "DIMENSION", null, 365L, false,
                List.of(
                        new ColumnDefinition("date_key", "INT64", true, false),
                        new ColumnDefinition("fiscal_period", "VARCHAR", false, false),
                        new ColumnDefinition("calendar_date", "ISO_DATE", false, false)
                ));
        assertEquals("calendar_date", service.inferDateColumnName(typedDateTable));

        TableDefinition untypedDateTable = new TableDefinition(
                "shipments", "FACT", null, 1000L, false,
                List.of(
                        new ColumnDefinition("shipment_id", "VARCHAR", true, false),
                        new ColumnDefinition("dispatch_date", "VARCHAR", false, false)
                ));
        assertEquals("dispatch_date", service.inferDateColumnName(untypedDateTable));

        TableDefinition noDateTable = new TableDefinition(
                "categories", "DIMENSION", null, 10L, false,
                List.of(new ColumnDefinition("category_name", "VARCHAR", false, false)));
        assertEquals("", service.inferDateColumnName(noDateTable));
        assertEquals("", service.inferDateColumnName((TableDefinition) null));
        assertEquals("", service.inferDateColumnName((List<ColumnDefinition>) null));

        RelationshipInferenceResult metadata = service.inferModelMetadata(
                List.of(typedDateTable, untypedDateTable, noDateTable));
        assertEquals("calendar_date", metadata.dateColumnsByTable().get("calendar"));
        assertEquals("dispatch_date", metadata.dateColumnsByTable().get("shipments"));
        assertFalse(metadata.dateColumnsByTable().containsKey("categories"));
    }
}
