package com.rollercoaster.ingestion;

import reactor.core.publisher.Flux;

import java.io.InputStream;
import java.nio.channels.SeekableByteChannel;

/**
 * Streaming Ingestion Contract for processing linear streams (CSV, TSV, JSON, XML, XLSX)
 * and columnar formats (Parquet, Avro) in discrete RecordBatch units capped at 10,000 rows
 * without loading entire files into JVM heap memory.
 */
public interface FileParser {
    Flux<RecordBatch> parseStream(InputStream stream, SchemaHints hints);

    Flux<RecordBatch> parseChannel(SeekableByteChannel channel, SchemaHints hints);
}
