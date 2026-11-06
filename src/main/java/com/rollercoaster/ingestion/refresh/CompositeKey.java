package com.rollercoaster.ingestion.refresh;

/**
 * In-memory composite hash key used by PipelineAggregationEngine for sliding window grouping
 * before disk persistence.
 */
public record CompositeKey(
        String dim1,
        String dim2,
        int dateBucket
) {}
