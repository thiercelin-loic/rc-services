package com.rollercoaster.ingestion.web;

import com.rollercoaster.ingestion.DataType;
import com.rollercoaster.ingestion.QuarantineRecord;
import com.rollercoaster.ingestion.RecordBatch;
import com.rollercoaster.ingestion.SchemaHints;
import com.rollercoaster.ingestion.SourceFormat;
import com.rollercoaster.ingestion.parser.JsonXmlStreamingParser;
import com.rollercoaster.ingestion.parser.TypeInferenceEngine;
import io.netty.channel.ChannelOption;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;
import reactor.netty.http.client.HttpClient;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Web & REST Integration Engine:
 * - Enforces strict timeouts (5-second connection timeout, 30-second read timeout)
 * - Enforces SSRF egress filtering (RFC 1918, RFC 4193, loopback, Docker bridge networks)
 * - Enforces token-bucket rate limiting managed in Redis
 * - Supports Jsoup CSS selector HTML scraping via ExtractionSchema normalized into RecordBatch streams
 */
@Service
public class WebRestIntegrationService {

    private static final int CONNECT_TIMEOUT_MS = 5_000;
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(30);

    private final SsrfEgressFilter ssrfEgressFilter;
    private final JsonXmlStreamingParser jsonXmlStreamingParser;
    private final TypeInferenceEngine typeInferenceEngine;
    private final StringRedisTemplate redisTemplate;
    private final WebClient webClient;
    private final int bucketCapacity;
    private final Map<String, Integer> localFallbackBucket = new ConcurrentHashMap<>();

    public WebRestIntegrationService(
            SsrfEgressFilter ssrfEgressFilter,
            JsonXmlStreamingParser jsonXmlStreamingParser,
            TypeInferenceEngine typeInferenceEngine,
            @Autowired(required = false) StringRedisTemplate redisTemplate,
            @Value("${rollercoaster.ingestion.rate-limit-capacity:60}") int bucketCapacity) {
        this.ssrfEgressFilter = ssrfEgressFilter;
        this.jsonXmlStreamingParser = jsonXmlStreamingParser;
        this.typeInferenceEngine = typeInferenceEngine;
        this.redisTemplate = redisTemplate;
        this.bucketCapacity = bucketCapacity;

        HttpClient httpClient = HttpClient.create()
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, CONNECT_TIMEOUT_MS)
                .responseTimeout(READ_TIMEOUT);

        this.webClient = WebClient.builder()
                .clientConnector(new ReactorClientHttpConnector(httpClient))
                .build();
    }

    public Flux<RecordBatch> fetchRestEndpoint(URI endpointUri, String tenantId, SchemaHints hints) {
        ssrfEgressFilter.validateUri(endpointUri);
        enforceTokenBucketRateLimit(tenantId, endpointUri.getHost());

        return webClient.get()
                .uri(endpointUri)
                .retrieve()
                .bodyToMono(byte[].class)
                .timeout(READ_TIMEOUT)
                .flatMapMany(bytes -> jsonXmlStreamingParser.parseStream(
                        new ByteArrayInputStream(bytes),
                        hints != null ? hints : SchemaHints.defaults(SourceFormat.JSON)));
    }

    public Flux<RecordBatch> scrapeHtmlTable(URI pageUri, String tenantId, ExtractionSchema extractionSchema) {
        ssrfEgressFilter.validateUri(pageUri);
        enforceTokenBucketRateLimit(tenantId, pageUri.getHost());

        return Flux.<RecordBatch>create(sink -> {
            try {
                Document doc = Jsoup.connect(pageUri.toString())
                        .timeout(CONNECT_TIMEOUT_MS)
                        .followRedirects(false)
                        .get();
                RecordBatch batch = extractTableFromHtmlDocument(doc, tenantId, pageUri.toString(), extractionSchema);
                sink.next(batch);
                sink.complete();
            } catch (Exception e) {
                sink.error(e);
            }
        }).subscribeOn(Schedulers.boundedElastic());
    }

    /**
     * Parses HTML markup directly using Jsoup CSS selectors declared in ExtractionSchema
     * and normalizes extracted records into a standard RecordBatch.
     */
    public RecordBatch extractTableFromHtml(String htmlContent, String tenantId, String datasetId, ExtractionSchema schema) {
        Document doc = Jsoup.parse(htmlContent);
        return extractTableFromHtmlDocument(doc, tenantId, datasetId, schema);
    }

    private RecordBatch extractTableFromHtmlDocument(
            Document doc,
            String tenantId,
            String datasetId,
            ExtractionSchema schema) {
        List<Map<String, Object>> validRows = new ArrayList<>();
        List<QuarantineRecord> quarantined = new ArrayList<>();

        Elements tables = doc.select(schema.tableSelector());
        Element table = tables.first();
        if (table == null) {
            return new RecordBatch(0, schema.columnTypes(), List.of(), List.of());
        }

        Elements rowElements = table.select(schema.rowSelector());
        long lineNum = 0;
        for (Element rowEl : rowElements) {
            lineNum++;
            Map<String, Object> rowMap = new LinkedHashMap<>();
            boolean failed = false;

            for (Map.Entry<String, String> colSel : schema.columnCssSelectors().entrySet()) {
                String colName = colSel.getKey();
                String cssSelector = colSel.getValue();
                Element cell = rowEl.selectFirst(cssSelector);
                String rawText = cell != null ? cell.text().trim() : null;
                DataType targetType = schema.columnTypes().getOrDefault(colName, DataType.UTF8_STRING);
                try {
                    rowMap.put(colName, typeInferenceEngine.coerceValue(rawText, targetType));
                } catch (Exception ex) {
                    quarantined.add(new QuarantineRecord(
                            tenantId,
                            datasetId,
                            lineNum,
                            colName,
                            targetType.name(),
                            rowEl.outerHtml(),
                            "HTML CSS selector extraction type failure: " + ex.getMessage()
                    ));
                    failed = true;
                    break;
                }
            }
            if (!failed && !rowMap.isEmpty()) {
                validRows.add(rowMap);
            }
        }

        Map<String, DataType> resolvedSchema = schema.columnTypes().isEmpty()
                ? typeInferenceEngine.inferSchemaFromSample(validRows, Map.of())
                : schema.columnTypes();
        return new RecordBatch(0, resolvedSchema, validRows, quarantined);
    }

    public void enforceTokenBucketRateLimit(String tenantId, String host) {
        String windowMinute = String.valueOf(Instant.now().getEpochSecond() / 60);
        String key = "ratelimit:web:" + tenantId + ":" + host + ":" + windowMinute;
        if (redisTemplate != null) {
            try {
                Long current = redisTemplate.opsForValue().increment(key);
                if (current != null && current == 1L) {
                    redisTemplate.expire(key, Duration.ofSeconds(65));
                }
                if (current != null && current > bucketCapacity) {
                    throw new IllegalStateException("ERR_RATE_LIMIT: Outbound web rate limit exceeded for host: " + host);
                }
                return;
            } catch (IllegalStateException rateEx) {
                throw rateEx;
            } catch (Exception ignored) {
                // Fall through to in-memory token bucket if Redis is offline in unit test
            }
        }
        int count = localFallbackBucket.merge(key, 1, (a, b) -> a + b);
        if (count > bucketCapacity) {
            throw new IllegalStateException("ERR_RATE_LIMIT: Outbound web rate limit exceeded for host: " + host);
        }
    }
}
