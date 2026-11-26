![icon](./icon.svg)

# RollerCoaster Services (`rc-services`)

`rc-services` provides the core server-side platform for RollerCoaster. Built on Spring Boot 4.1.1 and Java 21 LTS with Virtual Threads enabled, it powers a dual-engine analytical architecture combining relational operational storage (MySQL 8.0) and in-process vectorized columnar query execution (embedded DuckDB 1.1.3 via native JNI).

The platform handles RAX/DAX analytical formula and script compilation (ANTLR 4.13.2), Data Preparation pipelines (R-Query transformations and Power Query M generation), semantic model management (RSDL/TMDL, relationship inference, DAG validation), batch visual aggregation, European geospatial projection, `.rcp` project archive serialization, multi-level Row-Level Security (RLS), Dynamic Data Masking, real-time STOMP messaging over WebSockets, multi-format exports, and optimistic staged write-backs.

---

## Dual-Engine Cost-Based Architecture

The heart of query execution is the Cost-Based Query Coordinator ([`DualEngineCoordinator`](./src/main/java/com/rollercoaster/model/DualEngineCoordinator.java)):

```
                        +--------------------------+
                        |  Incoming Visual Query   |
                        +------------+-------------+
                                     |
                                     v
                        +--------------------------+
                        |   Redis L1 Query Cache   | ---- Hit ---> Return Result
                        | (TTL: 300s, by RLS Hash) |
                        +------------+-------------+
                                     | Miss
                                     v
                        +--------------------------+
                        |   ANTLR4 RAX Compiler    |
                        |   AST + Context Resolver |
                        +------------+-------------+
                                     |
                         Cost-Based Decision Gate
                      (Threshold: 1,000,000 rows)
                                     |
                  +------------------+------------------+
                  |                                     |
                  v (< 1M Rows)                         v (>= 1M Rows / Iterators)
       +----------------------+             +----------------------+
       |   MySQL 8.0 Engine   |             |  Embedded DuckDB JNI |
       | Relational Pushdown  |             |  Vectorized Parquet  |
       |  CASE WHEN Predicate |             |  FILTER (WHERE ...)  |
       +----------+-----------+             +----------+-----------+
                  |                                     |
                  +------------------+------------------+
                                     |
                                     v
                        +--------------------------+
                        |   Dynamic Data Masking   |
                        |    PII Role Obfuscation  |
                        +------------+-------------+
                                     |
                        +------------+-------------+
                        | Write to Redis L1 Cache  |
                        | STOMP WebSocket Dispatch |
                        +--------------------------+
```

### 1. Redis L1 Query Cache

Before compiling or querying engines, the coordinator probes Redis under a deterministic composite key:
`cache:query:{tenant_id}:{rls_hash}:{context_hash}:{measure_id}`.
Cache hits return immediately, and cached entries expire after 300 seconds.

### 2. Cost-Based Routing Decision

- **MySQL 8.0 Relational Pushdown**: Operational record lookups, dimensional metadata queries, and transactional tables with fewer than 1,000,000 rows (`rollercoaster.duckdb.row-threshold-for-columnar: 1000000`) compile to standard SQL with indexed joins and `CASE WHEN` conditional aggregations.
- **Embedded DuckDB JNI Columnar Engine**: Analytical queries exceeding the row threshold, heavy multi-table fact scans, and iterative expressions (`SUMX`, `RANKX`, `AVERAGEX`) run inside in-process DuckDB instances accessing Parquet partitions. Queries leverage native `FILTER (WHERE ...)` clauses and SIMD vectorization.

### 3. Real-Time STOMP Streaming

STOMP over WebSocket (`/ws`) broadcasts visual query results (`/topic/visuals/{tenantId}/{reportId}`), workspace telemetry (`/topic/workspace/{workspaceId}`), and live Monaco RAX syntax/semantic diagnostics (`/topic/diagnostics/{workspaceId}`).

---

## Subsystems & Core Modules

### 1. RAX Formula, Script & Studio Engine (`com.rollercoaster.rax`)

- **ANTLR 4 Grammar**: Compiles RollerCoaster Analytical Expressions using formal grammar rules in [`RaxGrammar.g4`](./src/main/antlr4/com/rollercoaster/rax/grammar/RaxGrammar.g4).
- **Dual Dialect Generation**: [`SqlPlanCompilingVisitor`](./src/main/java/com/rollercoaster/rax/compiler/SqlPlanCompilingVisitor.java) compiles ASTs into specialized SQL dialects:
  - _DuckDB dialect_: `SUM(sales.amount) FILTER (WHERE sales.region = 'Europe')`
  - _MySQL dialect_: `SUM(CASE WHEN sales.region = 'Europe' THEN sales.amount ELSE NULL END)`
- **Evaluation Context & Time Intelligence**: [`ContextEvaluator`](./src/main/java/com/rollercoaster/rax/context/ContextEvaluator.java) manages context transition from Row Context to Filter Context; [`TimeIntelligenceCompiler`](./src/main/java/com/rollercoaster/rax/compiler/TimeIntelligenceCompiler.java) evaluates temporal functions (`TOTALYTD`, `SAMEPERIODLASTYEAR`, `DATEADD`, `DATESYTD`).
- **Multi-Statement Script Execution**: [`RaxScriptService`](./src/main/java/com/rollercoaster/rax/compiler/RaxScriptService.java) executes `DEFINE MEASURE ... EVALUATE ... ORDER BY ...` scripts (including `SELECTCOLUMNS`, `SUMMARIZE`, `SUMMARIZECOLUMNS`, `ROW`, and `INFO.*` introspection) and persists extracted measures into the semantic model.
- **Calculated Columns & Calculated Tables**: [`CalculatedColumnService`](./src/main/java/com/rollercoaster/rax/compiler/CalculatedColumnService.java) evaluates row-context column formulas with type inference, while [`CalculatedTableService`](./src/main/java/com/rollercoaster/rax/compiler/CalculatedTableService.java) builds tables from `CALENDAR`, `CALENDARAUTO`, `SUMMARIZECOLUMNS`, and `INFO.VIEW.*` formulas via [`RaxStudioController`](./src/main/java/com/rollercoaster/rax/diagnostics/RaxStudioController.java).
- **Formatting, Function Catalog & Monaco LSP**: [`RaxQueryFormatter`](./src/main/java/com/rollercoaster/rax/compiler/RaxQueryFormatter.java) formats RAX/TMDL scripts, [`AnalyticalArchetypeLibrary`](./src/main/java/com/rollercoaster/rax/compiler/AnalyticalArchetypeLibrary.java) serves the RAX function catalog, and [`MonacoDiagnosticsService`](./src/main/java/com/rollercoaster/rax/diagnostics/MonacoDiagnosticsService.java) powers token inspection and diagnostics over REST and STOMP in [`RaxDiagnosticsController`](./src/main/java/com/rollercoaster/rax/diagnostics/RaxDiagnosticsController.java).

### 2. Semantic Data Modeling & Visual Aggregation (`com.rollercoaster.model`)

- **Schema & Relationship Management**: [`SemanticModelManager`](./src/main/java/com/rollercoaster/model/SemanticModelManager.java) and [`SemanticModelController`](./src/main/java/com/rollercoaster/model/SemanticModelController.java) manage tables, columns, primary keys, foreign-key visibility, date-table designations, measure inventories, relationship cardinalities (`1:1`, `1:N`, `N:1`, `N:M`), and cross-filtering (`SINGLE`, `BOTH`) with DAG cycle validation.
- **Relationship & Date Inference**: [`RelationshipInferenceService`](./src/main/java/com/rollercoaster/model/RelationshipInferenceService.java) infers candidate many-to-one relationships (marking secondary role-playing paths inactive) and temporal columns across domain-neutral schemas.
- **RSDL, TMDL & Data Dictionary**: [`RsdlFormatter`](./src/main/java/com/rollercoaster/model/RsdlFormatter.java) serializes and parses RollerCoaster Semantic Definition Language (RSDL) and Tabular Model Definition Language (TMDL); [`InfoFunctionEvaluator`](./src/main/java/com/rollercoaster/rax/compiler/InfoFunctionEvaluator.java) serves `INFO.VIEW()` data dictionaries.
- **Batch Visual Aggregation**: [`VisualAggregationService`](./src/main/java/com/rollercoaster/model/VisualAggregationService.java) and [`ReportQueryController`](./src/main/java/com/rollercoaster/model/ReportQueryController.java) resolve cross-filters, slicers, server-side sorting, chart series, matrix rollups, and KPI measure cards in batch.

### 3. Data Ingestion & Data Preparation Studio (`com.rollercoaster.ingestion`)

- **Streaming Parsers**:
  - [`DelimitedFileParser`](./src/main/java/com/rollercoaster/ingestion/parser/DelimitedFileParser.java): Chunked CSV/TSV reader with automatic delimiter detection.
  - [`XlsxStreamingParser`](./src/main/java/com/rollercoaster/ingestion/parser/XlsxStreamingParser.java): SAX-based Excel streaming via Apache POI to avoid full-workbook heap allocation, paired with [`SheetExtractionService`](./src/main/java/com/rollercoaster/ingestion/SheetExtractionService.java) for sheet discovery.
  - [`JsonXmlStreamingParser`](./src/main/java/com/rollercoaster/ingestion/parser/JsonXmlStreamingParser.java): StAX and Jackson event-stream reader.
  - [`ColumnarChannelParser`](./src/main/java/com/rollercoaster/ingestion/parser/ColumnarChannelParser.java): `SeekableByteChannel`-backed parser for Avro streams (Parquet import support is disabled in ingestion).
- **Data Prep Pipeline & Power Query M**: [`DataPrepPipelineService`](./src/main/java/com/rollercoaster/ingestion/rquery/DataPrepPipelineService.java), [`RQueryTransformationEngine`](./src/main/java/com/rollercoaster/ingestion/rquery/RQueryTransformationEngine.java), and [`RQueryPredicateBuilder`](./src/main/java/com/rollercoaster/ingestion/rquery/RQueryPredicateBuilder.java) execute step-based transformations (source navigation, header promotion, type casting, column drops/renames, text splits, filtering, sibling lookups) and emit Power Query M (`let ... in ...`) scripts via [`MQueryFormatter`](./src/main/java/com/rollercoaster/ingestion/rquery/MQueryFormatter.java).
- **Quarantine, Incremental Refresh & SSRF Protection**: [`QuarantineRepository`](./src/main/java/com/rollercoaster/ingestion/QuarantineRepository.java) persists malformed rows without halting streams, [`IncrementalRefreshCoordinator`](./src/main/java/com/rollercoaster/ingestion/refresh/IncrementalRefreshCoordinator.java) handles high-watermark delta ingestion, and [`SsrfEgressFilter`](./src/main/java/com/rollercoaster/ingestion/web/SsrfEgressFilter.java) blocks loopback, cloud metadata, and private IP ranges on web imports.

### 4. Authentication, RLS & Data Masking (`com.rollercoaster.auth`, `com.rollercoaster.security`)

- **Stateless JWT Auth**: [`AuthController`](./src/main/java/com/rollercoaster/auth/AuthController.java), [`JwtTokenProvider`](./src/main/java/com/rollercoaster/auth/JwtTokenProvider.java) (HMAC-SHA256 signed JWTs), [`SecurityConfig`](./src/main/java/com/rollercoaster/config/SecurityConfig.java) (`BCryptPasswordEncoder`), and Redis-backed token revocation ([`TokenBlacklistService`](./src/main/java/com/rollercoaster/auth/TokenBlacklistService.java)).
- **Row-Level Security (RLS)**: [`RlsSecurityContextResolver`](./src/main/java/com/rollercoaster/security/RlsSecurityContextResolver.java) resolves active user claims and injects tenant predicate filters into compiled AST plans before execution.
- **Dynamic Data Masking**: [`DynamicDataMaskingService`](./src/main/java/com/rollercoaster/security/DynamicDataMaskingService.java) obfuscates sensitive PII attributes (emails, credit card numbers, national IDs) for unauthorized roles.

### 5. Project Archive Serialization (`.rcp`) (`com.rollercoaster.serialization`)

- **Portable Project Bundles**: [`ProjectSerializationService`](./src/main/java/com/rollercoaster/serialization/ProjectSerializationService.java), [`ProjectArchiveCodec`](./src/main/java/com/rollercoaster/serialization/ProjectArchiveCodec.java), and [`ProjectController`](./src/main/java/com/rollercoaster/serialization/ProjectController.java) export and import `.rcp` ZIP archives containing `manifest.json`, embedded RSDL model definitions, report layouts, and SHA-256 checksums, rejecting tampered or incompatible archives.

### 6. Geospatial Projection & Hit-Testing (`com.rollercoaster.geo`)

- **European Territory Projection**: [`GeoService`](./src/main/java/com/rollercoaster/geo/GeoService.java) and [`GeoController`](./src/main/java/com/rollercoaster/geo/GeoController.java) provide Albers conic coordinate projection and cursor hit-testing for territory map visuals.

### 7. Staged Write-Back & Approvals (`com.rollercoaster.writeback`)

- **Staging & Concurrency**: [`WritebackService`](./src/main/java/com/rollercoaster/writeback/WritebackService.java) and [`WritebackController`](./src/main/java/com/rollercoaster/writeback/WritebackController.java) stage user edits separately from source fact tables, enforcing optimistic version checks ([`OptimisticLockingConflictException`](./src/main/java/com/rollercoaster/writeback/OptimisticLockingConflictException.java)).
- **Approval Engine**: [`WorkflowEngine`](./src/main/java/com/rollercoaster/writeback/WorkflowEngine.java) tracks workflow states (`PENDING`, `APPROVED`, `REJECTED`, `MERGED`) prior to merging into analytical partitions.

### 8. Multi-Format Export Engine (`com.rollercoaster.export`)

- **Streaming Excel (SXSSF)**: [`XlsxExportBuilder`](./src/main/java/com/rollercoaster/export/XlsxExportBuilder.java) generates `.xlsx` workbooks up to 1,048,576 rows with a 100-row sliding memory window.
- **PDF & Delimited Exports**: [`TabularExportFormatter`](./src/main/java/com/rollercoaster/export/TabularExportFormatter.java) generates PDF 1.4 documents and UTF-8 CSV/TSV files (with optional BOM and configurable delimiter) via [`ExportController`](./src/main/java/com/rollercoaster/export/ExportController.java).

---

## API Overview

| Prefix | Controller | Key Endpoints |
| :--- | :--- | :--- |
| `/api/auth` | [`AuthController`](./src/main/java/com/rollercoaster/auth/AuthController.java) | `POST /signup`, `POST /login`, `GET /me`, `POST /logout` |
| `/api/models` | [`SemanticModelController`](./src/main/java/com/rollercoaster/model/SemanticModelController.java) | CRUD models, `/infer-relationships`, `/rsdl`, `/tmdl`, `/validate-dag`, `/dictionary`, `/tables/{table}/rows`, relationship & column metadata mutations |
| `/api/reports` | [`ReportQueryController`](./src/main/java/com/rollercoaster/model/ReportQueryController.java), [`SemanticModelController`](./src/main/java/com/rollercoaster/model/SemanticModelController.java), [`ExportController`](./src/main/java/com/rollercoaster/export/ExportController.java) | `POST /visuals/query`, `POST /{reportId}/query`, `POST /{reportId}/export` |
| `/api/rax` | [`RaxDiagnosticsController`](./src/main/java/com/rollercoaster/rax/diagnostics/RaxDiagnosticsController.java), [`RaxStudioController`](./src/main/java/com/rollercoaster/rax/diagnostics/RaxStudioController.java) | `POST /diagnostics`, `POST /diagnostics/validate`, `POST /format`, `POST /tmdl`, `POST /execute`, `POST /measures/define`, `POST /measures/apply`, `GET /catalog`, `POST /calculated-column`, `POST /calculated-table` |
| `/api/ingestion` | [`IngestionController`](./src/main/java/com/rollercoaster/ingestion/IngestionController.java) | `POST /sheets`, `POST /preview`, `POST /pipeline/preview`, `POST /pipeline/evaluate`, `GET /quarantine/{tenantId}/{datasetId}`, `POST /incremental-refresh` |
| `/api/projects` | [`ProjectController`](./src/main/java/com/rollercoaster/serialization/ProjectController.java) | `POST /export`, `POST /import` |
| `/api/geo` | [`GeoController`](./src/main/java/com/rollercoaster/geo/GeoController.java) | `POST /project-point`, `POST /project`, `POST /hit-test` |
| `/api/writeback` | [`WritebackController`](./src/main/java/com/rollercoaster/writeback/WritebackController.java) | `POST /submit`, `POST /{id}/approve`, `GET /pending` |
| `/ws` (STOMP) | [`SemanticModelController`](./src/main/java/com/rollercoaster/model/SemanticModelController.java), [`RaxDiagnosticsController`](./src/main/java/com/rollercoaster/rax/diagnostics/RaxDiagnosticsController.java) | `/app/calculate-measure`, `/app/diagnostics/{workspaceId}` |

---

## Directory Layout

```
rc-services/
├── .github/
│   ├── CODEOWNERS
│   ├── PULL_REQUEST_TEMPLATE.md
│   └── workflows/
│       └── ci.yml                          # PR branch lint, Maven test & Checkstyle CI
├── src/
│   ├── main/
│   │   ├── antlr4/com/rollercoaster/rax/grammar/
│   │   │   └── RaxGrammar.g4               # ANTLR 4 grammar specification for RAX
│   │   ├── java/com/rollercoaster/
│   │   │   ├── RollerCoasterApplication.java
│   │   │   ├── auth/                       # JWT auth, user entities, token blacklist
│   │   │   ├── config/                     # Security, Jackson 3, ErrorHandler, Redis, STOMP
│   │   │   ├── export/                     # SXSSF Excel, PDF, and CSV/TSV exporters
│   │   │   ├── geo/                        # European conic projection & territory hit-testing
│   │   │   ├── ingestion/                  # Streaming parsers, R-Query, incremental refresh
│   │   │   │   ├── parser/                 # Delimited, XLSX, JSON/XML, Avro channel parsers
│   │   │   │   ├── refresh/                # Incremental high-watermark refresh coordinator
│   │   │   │   ├── rquery/                 # Data prep pipeline, predicates, M-Query formatter
│   │   │   │   └── web/                    # Jsoup web table extractor & SSRF egress filter
│   │   │   ├── model/                      # Dual-engine coordinator, DuckDB JNI, RSDL, visual agg
│   │   │   ├── rax/                        # AST, compilers, context, studio & diagnostics
│   │   │   │   ├── ast/                    # Compilation plan & SQL dialects
│   │   │   │   ├── compiler/               # SQL compiler, RAX script, calc columns/tables, formatter
│   │   │   │   ├── context/                # ContextEvaluator, Row/Filter context, symbol tables
│   │   │   │   ├── diagnostics/            # Monaco LSP diagnostics, studio & STOMP controllers
│   │   │   │   └── grammar/                # ANTLR generated parsers & lexers
│   │   │   ├── security/                   # Dynamic data masking, RLS predicate context
│   │   │   ├── serialization/              # .rcp ZIP archive codec, SHA-256 validation & REST API
│   │   │   └── writeback/                  # Staging entity, approval workflow, concurrency
│   │   └── resources/
│   │       ├── application.yml             # Base configuration & DuckDB/ingestion/security caps
│   │       └── application-local.yml       # Local profile settings (Hibernate ddl update)
│   └── test/java/com/rollercoaster/
│       ├── export/                         # ExportControllerTest
│       ├── ingestion/rquery/               # DataPrepPipelineService, MQueryFormatter, PredicateBuilder, TransformationEngine tests
│       ├── model/                          # DualEngineVisualQuery, RelationshipInference, RsdlFormatter, SemanticModel, VisualAggregation tests
│       └── rax/
│           ├── compiler/                   # CalculatedColumn, CalculatedTable, RaxFormulaCompiler, RaxQueryFormatter, RaxScriptService tests
│           └── diagnostics/                # RaxDiagnosticsController, RaxStudioController tests
├── AGENTS.md                               # Backend engineering standards
├── checkstyle.xml                          # Checkstyle clean-code rules
├── Dockerfile                              # Multi-stage glibc-based JRE container build
├── docker-compose.yml                      # Full-stack orchestrator (MySQL, Redis, Spring Boot)
└── pom.xml                                 # Maven dependencies, Checkstyle & JaCoCo gates
```

---

## Configuration & Environment Variables

Key parameters defined in [`application.yml`](./src/main/resources/application.yml) and [`docker-compose.yml`](./docker-compose.yml):

| Variable                 | Default Value                              | Description                                       |
| :----------------------- | :----------------------------------------- | :------------------------------------------------ |
| `SPRING_PROFILES_ACTIVE` | `local`                                    | Active Spring profile (`local`, `production`)     |
| `MYSQL_HOST`             | `mysql`                                    | MySQL 8.0 server hostname                         |
| `MYSQL_PORT`             | `3306`                                     | MySQL container port                              |
| `MYSQL_HOST_PORT`        | `33060`                                    | MySQL host-mapped port in Docker Compose          |
| `MYSQL_DATABASE`         | `rollercoaster_analytics`                  | Relational database schema name                   |
| `MYSQL_USER`             | `rc_app_user`                              | MySQL application user                            |
| `APP_DB_PASSWORD`        | `rc_secret`                                | MySQL application password                        |
| `REDIS_HOST`             | `redis`                                    | Redis server hostname                             |
| `REDIS_PORT`             | `6379`                                     | Redis port                                        |
| `REDIS_PASSWORD`         | _(empty)_                                  | Optional Redis authentication password            |
| `DUCKDB_EXTENSIONS_DIR`  | `/opt/duckdb/extensions`                   | Directory for native DuckDB binary extensions     |
| `PARQUET_BASE_DIR`       | `/data/parquet`                            | Base storage path for columnar Parquet partitions |
| `DUCKDB_STAGING_DIR`     | `/staging`                                 | Temporary scratch directory for DuckDB execution  |
| `HEADLESS_RENDERER_URL`  | `http://headless-renderer:3000/render-pdf` | Optional endpoint for external PDF render worker  |
| `AES_GCM_MASTER_KEY`     | _(32-byte hex)_                            | Master key for encrypting connection credentials  |
| `KEYCLOAK_ISSUER_URI`    | `https://auth.rollercoaster.com/realms/rollercoaster` | OAuth2/OIDC JWT issuer URI             |

---

## Local Development, CI & Operations

### 1. Running the Full Stack with Docker Compose

```bash
docker compose -f docker-compose.yml up -d
```

This starts:

- `rc-database` (MySQL 8.0) mapped to host port `33060`.
- `rc-cache` (Redis 7.2) mapped to host port `6379`.
- `rc-api` (Spring Boot 4.1.1) mapped to host port `8080`.

Inspect container statuses and logs:

```bash
docker compose ps
docker compose logs -f spring-boot
```

### 2. Running Tests & Quality Checks

GitHub Actions ([`ci.yml`](./.github/workflows/ci.yml)) validates PR branch naming and executes the test suite and Checkstyle on every push/PR to `main`:

```bash
mvn -B test checkstyle:check
```

Run full verification including the JaCoCo 85% line coverage gate:

```bash
mvn -B verify
```

### 3. Building the Production Container Image

> [!IMPORTANT]
> **glibc Runtime Requirement**: The runtime stage uses `eclipse-temurin:21-jre` (glibc-based). The DuckDB native JNI binary is incompatible with musl-based Alpine distributions.

```bash
docker build -t rc-services:latest -f Dockerfile .
```

### 4. Actuator Health & Probes

- **Aggregated Health**: `GET http://localhost:8080/actuator/health`
- **Kubernetes Liveness Probe**: `GET http://localhost:8080/actuator/health/liveness`
- **Kubernetes Readiness Probe**: `GET http://localhost:8080/actuator/health/readiness`
- **Prometheus Metrics**: `GET http://localhost:8080/actuator/prometheus`

---

## Engineering Standards

All code within `rc-services` adheres to [`AGENTS.md`](./AGENTS.md) and [`checkstyle.xml`](./checkstyle.xml):

- **Single Responsibility & Thin Controllers**: Controllers handle routing, validation (`@Valid`), status codes, and serialization; domain logic resides in services.
- **Constructor Injection**: Inject dependencies via `private final` fields (no field injection).
- **Immutable DTO Contracts & Jackson 3**: Request/response payloads use Java `record` types; [`JacksonConfig`](./src/main/java/com/rollercoaster/config/JacksonConfig.java) disables `FAIL_ON_NULL_FOR_PRIMITIVES` for partial record deserialization.
- **Centralized Exception Handling**: Exceptions are mapped to RFC 9457 `ProblemDetail` payloads with structured `errorCode` properties by [`ErrorHandler`](./src/main/java/com/rollercoaster/config/ErrorHandler.java).
- **Quality Gates**: Checkstyle enforces clean-code limits (max 500 lines/file, max 80 lines/method, zero unused/star imports), and JaCoCo enforces an 85% line coverage threshold during `mvn verify`.
