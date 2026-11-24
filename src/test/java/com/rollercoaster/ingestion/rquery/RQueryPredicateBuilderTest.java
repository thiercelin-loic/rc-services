package com.rollercoaster.ingestion.rquery;

import com.rollercoaster.ingestion.DataType;
import com.rollercoaster.ingestion.rquery.RQueryPredicateBuilder.FilterCriterion;
import com.rollercoaster.ingestion.rquery.RQueryPredicateBuilder.FilterOperator;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RQueryPredicateBuilderTest {

    @Test
    void buildsUnquotedNumericPredicates() {
        assertEquals(
                "[quantity] >= 10",
                RQueryPredicateBuilder.buildPredicate("quantity", FilterOperator.GREATER_OR_EQUAL, "10", DataType.INT64));
        assertEquals(
                "[price] < 99.95",
                RQueryPredicateBuilder.buildPredicate("price", FilterOperator.LESS_THAN, "99.95", DataType.FLOAT64));
        assertEquals(
                "[discount] != 0",
                RQueryPredicateBuilder.buildPredicate("discount", FilterOperator.NOT_EQUALS, "0"));
    }

    @Test
    void buildsQuotedTextAndDatePredicates() {
        assertEquals(
                "[region] == 'EMEA'",
                RQueryPredicateBuilder.buildPredicate("region", FilterOperator.EQUALS, "EMEA", DataType.UTF8_STRING));
        assertEquals(
                "[code] == '00123'",
                RQueryPredicateBuilder.buildPredicate("code", FilterOperator.EQUALS, "00123", DataType.UTF8_STRING));
        assertEquals(
                "[order_date] > '2025-01-01'",
                RQueryPredicateBuilder.buildPredicate("order_date", FilterOperator.GREATER_THAN, "2025-01-01", DataType.ISO_DATE));
    }

    @Test
    void buildsBetweenPredicatesForNumericAndDateRanges() {
        assertEquals(
                "[amount] >= 100 and [amount] <= 500",
                RQueryPredicateBuilder.buildBetweenPredicate("amount", "100", "500", DataType.DECIMAL));
        assertEquals(
                "[created_at] >= '2025-01-01' and [created_at] <= '2025-03-31'",
                RQueryPredicateBuilder.buildBetweenPredicate("created_at", "2025-01-01", "2025-03-31", DataType.ISO_DATE));
    }

    @Test
    void resolvesFilterOperatorAliasesAndValidatesPreconditions() {
        assertEquals(FilterOperator.EQUALS, FilterOperator.fromString("=="));
        assertEquals(FilterOperator.NOT_EQUALS, FilterOperator.fromString("<>"));
        assertEquals(FilterOperator.GREATER_OR_EQUAL, FilterOperator.fromString("GTE"));
        assertEquals(FilterOperator.LESS_OR_EQUAL, FilterOperator.fromString("<="));
        assertEquals(FilterOperator.BETWEEN, FilterOperator.fromString("between"));

        assertThrows(IllegalArgumentException.class, () -> FilterOperator.fromString("LIKE"));
        assertThrows(IllegalArgumentException.class,
                () -> new FilterCriterion("", FilterOperator.EQUALS, "x", null, false));
        assertThrows(IllegalArgumentException.class,
                () -> new FilterCriterion("col", FilterOperator.BETWEEN, "1", null, true));
    }
}
