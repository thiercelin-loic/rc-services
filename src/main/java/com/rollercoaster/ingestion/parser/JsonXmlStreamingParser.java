package com.rollercoaster.ingestion.parser;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rollercoaster.ingestion.DataType;
import com.rollercoaster.ingestion.FileParser;
import com.rollercoaster.ingestion.QuarantineRecord;
import com.rollercoaster.ingestion.RecordBatch;
import com.rollercoaster.ingestion.SchemaHints;
import com.rollercoaster.ingestion.SourceFormat;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.parser.Parser;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;

import java.io.InputStream;
import java.nio.channels.Channels;
import java.nio.channels.SeekableByteChannel;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Streaming parser for JSON and XML documents.
 * Unnests deeply nested JSON/XML structures through recursive path expansion
 * into flattened relational tuples.
 */
@Component
public class JsonXmlStreamingParser implements FileParser {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final TypeInferenceEngine typeInferenceEngine;

    public JsonXmlStreamingParser(TypeInferenceEngine typeInferenceEngine) {
        this.typeInferenceEngine = typeInferenceEngine;
    }

    @Override
    public Flux<RecordBatch> parseStream(InputStream stream, SchemaHints hints) {
        return Flux.<RecordBatch>create(sink -> {
            try {
                List<Map<String, Object>> flattenedRows = new ArrayList<>();
                if (hints.format() == SourceFormat.XML) {
                    Document xmlDoc = Jsoup.parse(stream, hints.encoding().name(), "", Parser.xmlParser());
                    Element root = xmlDoc.children().first();
                    if (root != null) {
                        for (Element recordEl : root.children()) {
                            Map<String, Object> flatRow = new LinkedHashMap<>();
                            flattenXmlElement("", recordEl, flatRow);
                            flattenedRows.add(flatRow);
                        }
                    }
                } else {
                    JsonNode rootNode = objectMapper.readTree(stream);
                    if (rootNode != null) {
                        if (rootNode.isArray()) {
                            for (JsonNode item : rootNode) {
                                Map<String, Object> flatRow = new LinkedHashMap<>();
                                flattenJsonNode("", item, flatRow);
                                flattenedRows.add(flatRow);
                            }
                        } else if (rootNode.isObject()) {
                            Map<String, Object> flatRow = new LinkedHashMap<>();
                            flattenJsonNode("", rootNode, flatRow);
                            flattenedRows.add(flatRow);
                        }
                    }
                }

                Map<String, DataType> schema = typeInferenceEngine.inferSchemaFromSample(
                        flattenedRows.subList(0, Math.min(100, flattenedRows.size())),
                        hints.expectedColumnTypes());

                int batchIdx = 0;
                for (int start = 0; start < flattenedRows.size(); start += RecordBatch.MAX_BATCH_ROWS) {
                    int end = Math.min(start + RecordBatch.MAX_BATCH_ROWS, flattenedRows.size());
                    List<Map<String, Object>> slice = flattenedRows.subList(start, end);
                    List<Map<String, Object>> valid = new ArrayList<>(slice.size());
                    List<QuarantineRecord> quarantined = new ArrayList<>();

                    for (int i = 0; i < slice.size(); i++) {
                        Map<String, Object> raw = slice.get(i);
                        long recordNum = start + i + 1L;
                        Map<String, Object> coercedRow = new LinkedHashMap<>();
                        boolean failed = false;
                        for (Map.Entry<String, DataType> colEntry : schema.entrySet()) {
                            String col = colEntry.getKey();
                            DataType targetType = colEntry.getValue();
                            try {
                                coercedRow.put(col, typeInferenceEngine.coerceValue(raw.get(col), targetType));
                            } catch (Exception ex) {
                                quarantined.add(new QuarantineRecord(
                                        hints.tenantId(),
                                        hints.datasetId(),
                                        recordNum,
                                        col,
                                        targetType.name(),
                                        raw.toString(),
                                        "Datatype failure on unnested path '" + col + "': " + ex.getMessage()));
                                failed = true;
                                break;
                            }
                        }
                        if (!failed) {
                            valid.add(coercedRow);
                        }
                    }
                    sink.next(new RecordBatch(batchIdx++, schema, valid, quarantined));
                }
                sink.complete();
            } catch (Exception e) {
                sink.error(e);
            }
        }).subscribeOn(Schedulers.boundedElastic());
    }

    @Override
    public Flux<RecordBatch> parseChannel(SeekableByteChannel channel, SchemaHints hints) {
        return parseStream(Channels.newInputStream(channel), hints);
    }

    public void flattenJsonNode(String prefix, JsonNode node, Map<String, Object> out) {
        if (node == null || node.isNull()) {
            if (!prefix.isEmpty())
                out.put(prefix, null);
            return;
        }
        if (node.isObject()) {
            Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                String childKey = prefix.isEmpty() ? field.getKey() : prefix + "." + field.getKey();
                flattenJsonNode(childKey, field.getValue(), out);
            }
        } else if (node.isArray()) {
            for (int i = 0; i < node.size(); i++) {
                String childKey = prefix + "[" + i + "]";
                flattenJsonNode(childKey, node.get(i), out);
            }
        } else if (node.isIntegralNumber()) {
            out.put(prefix, node.longValue());
        } else if (node.isFloatingPointNumber()) {
            out.put(prefix, node.doubleValue());
        } else if (node.isBoolean()) {
            out.put(prefix, node.booleanValue());
        } else {
            out.put(prefix, node.asText());
        }
    }

    private void flattenXmlElement(String prefix, Element element, Map<String, Object> out) {
        element.attributes().forEach(attr -> {
            String attrKey = (prefix.isEmpty() ? "" : prefix + ".") + "@" + attr.getKey();
            out.put(attrKey, attr.getValue());
        });
        if (element.children().isEmpty()) {
            String key = prefix.isEmpty() ? element.tagName() : prefix;
            out.put(key, element.text().trim());
        } else {
            for (Element child : element.children()) {
                String childPrefix = prefix.isEmpty() ? child.tagName() : prefix + "." + child.tagName();
                flattenXmlElement(childPrefix, child, out);
            }
        }
    }
}
