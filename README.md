![icon](./icon.svg)

# RollerCoaster Services (`rc-services`)

`rc-services` provides the core server-side platform for RollerCoaster. Built on Spring Boot 4.1.1 and Java 21 LTS with Virtual Threads enabled, it powers a dual-engine analytical architecture combining relational operational storage (MySQL 8.0) and in-process vectorized columnar query execution (embedded DuckDB via native JNI).

The platform handles analytical formula compilation (RAX/DAX expressions via ANTLR 4), Power Query-style ETL pipelines (R-Query), multi-level Row-Level Security (RLS), Dynamic Data Masking, real-time STOMP messaging over WebSockets, and optimistic staged write-backs.

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
Cache hits return immediately within 5ms. Cached entries expire after 300 seconds.

### 2. Cost-Based Routing Decision

- **MySQL 8.0 Relational Pushdown**: Operational record lookups, dimensional metadata queries, and transactional tables with fewer than 1,000,000 rows (`rollercoaster.duckdb.row-threshold-for-columnar: 1000000`) compile to standard SQL with indexed joins and `CASE WHEN` conditional aggregations.
- **Embedded DuckDB JNI Columnar Engine**: Analytical queries exceeding the row threshold, heavy multi-table fact scans, and iterative expressions (`SUMX`, `RANKX`, `AVERAGEX`) run inside in-process DuckDB instances accessing Parquet partitions. Queries leverage native `FILTER (WHERE ...)` clauses and SIMD vectorization.

### 3. Real-Time STOMP Streaming

Query execution results stream across STOMP WebSocket topics (`/topic/visuals/{tenantId}/{reportId}`) to update connected web client canvas widgets within sub-50ms render windows.

---

## Subsystems & Core Modules

### 1. RAX Formula Compiler (`com.rollercoaster.rax`)

- **ANTLR 4 Grammar**: Compiles RollerCoaster Analytical Expressions using formal grammar rules in [`RaxGrammar.g4`](./src/main/antlr4/com/rollercoaster/rax/grammar/RaxGrammar.g4).
- **Dual Dialect Generation**: [`SqlPlanCompilingVisitor`](./src/main/java/com/rollercoaster/rax/compiler/SqlPlanCompilingVisitor.java) compiles the AST into specialized SQL dialects:
  - _DuckDB dialect_: `SUM(sales.amount) FILTER (WHERE sales.region = 'Europe')`
  - _MySQL dialect_: `SUM(CASE WHEN sales.region = 'Europe' THEN sales.amount ELSE NULL END)`
- **Evaluation Context**: [`ContextEvaluator`](./src/main/java/com/rollercoaster/rax/context/ContextEvaluator.java) manages Context Transition from iteration Row Context to Filter Context.
- **Time Intelligence**: [`TimeIntelligenceCompiler`](./src/main/java/com/rollercoaster/rax/compiler/TimeIntelligenceCompiler.java) evaluates temporal functions such as `TOTALYTD`, `SAMEPERIODLASTYEAR`, `DATEADD`, and `DATESYTD`.
- **Language Diagnostics**: [`MonacoDiagnosticsService`](./src/main/java/com/rollercoaster/rax/diagnostics/MonacoDiagnosticsService.java) generates Monaco-compatible syntax and semantic markers over STOMP (`/topic/diagnostics/{workspaceId}`).

### 2. Semantic Data Modeling (`com.rollercoaster.model`)

- **Schema Management**: [`SemanticModelManager`](./src/main/java/com/rollercoaster/model/SemanticModelManager.java) stores table contracts, column types, relationship definitions, and measure inventories.
- **Relationship Topologies**: Supports cardinalities (`1:1`, `1:N`, `N:1`, `N:M`) and cross-filtering configurations (`SINGLE`, `BOTH`).
- **DAG Validation**: Validates model topologies to prevent circular relationship paths and invalid cross-filtering loops.
- **Schema Introspection**: [`InfoFunctionEvaluator`](./src/main/java/com/rollercoaster/rax/compiler/InfoFunctionEvaluator.java) handles `INFO.VIEW()` metadata queries for runtime data dictionaries.

### 3. Data Ingestion & R-Query ETL (`com.rollercoaster.ingestion`)

- **Streaming Parsers**:
  - [`DelimitedFileParser`](./src/main/java/com/rollercoaster/ingestion/parser/DelimitedFileParser.java): Memory-efficient chunked CSV/TSV reader.
  - [`XlsxStreamingParser`](./src/main/java/com/rollercoaster/ingestion/parser/XlsxStreamingParser.java): SAX-based Excel streaming via Apache POI SXSSF to prevent out-of-memory errors on massive workbooks.
  - [`JsonXmlStreamingParser`](./src/main/java/com/rollercoaster/ingestion/parser/JsonXmlStreamingParser.java): StAX and Jackson event-stream reader.
- **R-Query Transformation Engine**: [`RQueryTransformationEngine`](./src/main/java/com/rollercoaster/ingestion/rquery/RQueryTransformationEngine.java) executes step-based data transformations: column drops, renames, type casting, text splits, row filtering, and sibling table lookups.
- **Quarantine Management**: [`QuarantineRepository`](./src/main/java/com/rollercoaster/ingestion/QuarantineRepository.java) captures malformed rows failing validation checks.
- **High-Watermark Incremental Refresh**: [`IncrementalRefreshCoordinator`](./src/main/java/com/rollercoaster/ingestion/refresh/IncrementalRefreshCoordinator.java) coordinates delta ingestion without dropping historical fact partitions.
- **SSRF Protection**: [`SsrfEgressFilter`](./src/main/java/com/rollercoaster/ingestion/web/SsrfEgressFilter.java) prevents Server-Side Request Forgery by blocking loopback, cloud metadata IPs, and private subnets during external web imports.

### 4. Security, RLS & Data Masking (`com.rollercoaster.security`)

- **Row-Level Security (RLS)**: [`RlsSecurityContextResolver`](./src/main/java/com/rollercoaster/security/RlsSecurityContextResolver.java) resolves active user claims and injects tenant-specific predicate filters into compiled AST plans before execution.
- **Dynamic Data Masking**: [`DynamicDataMaskingService`](./src/main/java/com/rollercoaster/security/DynamicDataMaskingService.java) dynamically masks sensitive PII attributes (emails, credit card numbers, national identification numbers) for unauthorized user roles.
- **Argon2id v1.3 Cryptography**: Password hashing via BouncyCastle utilizing 64 MB memory cost, 3 iterations, and 4 parallel lanes.
- **Token Blacklisting**: Redis-backed revocation blacklist ([`TokenBlacklistService`](./src/main/java/com/rollercoaster/auth/TokenBlacklistService.java)) ensuring immediate invalidation upon logout.

### 5. Staged Write-Back & Approvals (`com.rollercoaster.writeback`)

- **Staging Pipeline**: [`WritebackService`](./src/main/java/com/rollercoaster/writeback/WritebackService.java) routes user adjustments into a dedicated staging repository rather than mutating source facts directly.
- **Optimistic Concurrency**: Uses version fields to prevent dirty writes ([`OptimisticLockingConflictException`](./src/main/java/com/rollercoaster/writeback/OptimisticLockingConflictException.java)).
- **Approval Engine**: [`WorkflowEngine`](./src/main/java/com/rollercoaster/writeback/WorkflowEngine.java) tracks workflow states (`PENDING`, `APPROVED`, `REJECTED`, `MERGED`) before applying data back to analytical partitions.

### 6. Streaming Export Engine (`com.rollercoaster.export`)

- **Streaming Excel (SXSSF)**: Generates `.xlsx` workbooks up to 1,048,576 rows with a memory window of 100 rows, flushing data continuously to prevent memory exhaustion.
- **Headless PDF Generation**: REST client dispatches layout models to an isolated Playwright rendering worker for vector PDF exports.

---

## Directory Layout

```
rc-services/
├── src/
│   ├── main/
│   │   ├── antlr4/
│   │   │   └── com/rollercoaster/rax/grammar/
│   │   │       └── RaxGrammar.g4           # ANTLR 4 grammar specification for RAX
│   │   ├── java/com/rollercoaster/
│   │   │   ├── RollerCoasterApplication.java# Spring Boot application entrypoint
│   │   │   ├── auth/                       # JWT auth, user persistence, Argon2id hashing
│   │   │   ├── config/                     # Security, Redis cache, WebSocket STOMP setup
│   │   │   ├── export/                     # SXSSF Excel & headless PDF export controllers
│   │   │   ├── ingestion/                  # Streaming parsers, R-Query, incremental refresh
│   │   │   │   ├── parser/                 # Delimited, XLSX, JSON/XML, Parquet parsers
│   │   │   │   ├── refresh/                # Incremental high-watermark refresh coordinator
│   │   │   │   ├── rquery/                 # Power Query-style transformation engine
│   │   │   │   └── web/                    # Jsoup web table extractor & SSRF filter
│   │   │   ├── model/                      # Dual-engine coordinator, DuckDB JNI, schema DAG
│   │   │   ├── rax/                        # AST, compiler visitors, time intelligence
│   │   │   │   ├── ast/                    # Compilation plan & SQL dialects
│   │   │   │   ├── compiler/               # SQL plan compiling visitor, archetype library
│   │   │   │   ├── context/                # ContextEvaluator, Row/Filter context, symbol tables
│   │   │   │   ├── diagnostics/            # Monaco LSP diagnostics & STOMP controllers
│   │   │   │   └── grammar/                # ANTLR generated parsers & lexers
│   │   │   ├── security/                   # Dynamic data masking, RLS predicate context
│   │   │   ├── serialization/              # Model serialization & exception definitions
│   │   │   └── writeback/                  # Staging entity, approval workflow, concurrency
│   │   └── resources/
│   │       ├── application.yml             # Base production configuration & DuckDB caps
│   │       └── application-local.yml       # Local profile settings (Hibernate ddl update)
│   └── test/java/com/rollercoaster/
│       ├── ingestion/rquery/
│       │   └── RQueryTransformationEngineTest.java # ETL step pipeline verification
│       ├── model/
│       │   └── DualEngineVisualQueryTest.java      # Dual-engine execution & threshold routing
│       └── rax/compiler/
│           └── RaxFormulaCompilerTest.java         # ANTLR AST compilation & SQL dialect testing
├── Dockerfile                              # Multi-stage glibc-based JRE container build
├── docker-compose.yml                      # Full-stack orchestrator (MySQL, Redis, Spring Boot)
├── pom.xml                                 # Maven dependencies, plugins, and JaCoCo gates
└── AGENTS.md                               # Clean Code engineering guidelines
```

---

## Configuration & Environment Variables

Key parameters defined in [`application.yml`](./src/main/resources/application.yml) and configured through environment variables:

| Variable                 | Default Value                              | Description                                       |
| :----------------------- | :----------------------------------------- | :------------------------------------------------ |
| `SPRING_PROFILES_ACTIVE` | `local`                                    | Active Spring profile (`local`, `production`)     |
| `MYSQL_HOST`             | `mysql`                                    | MySQL 8.0 server hostname                         |
| `MYSQL_PORT`             | `3306`                                     | MySQL port (exposed as `33060` on host)           |
| `MYSQL_DATABASE`         | `rollercoaster_analytics`                  | Relational database schema name                   |
| `MYSQL_USER`             | `rc_app_user`                              | MySQL application user                            |
| `APP_DB_PASSWORD`        | `rc_secret`                                | MySQL application password                        |
| `REDIS_HOST`             | `redis`                                    | Redis server hostname                             |
| `REDIS_PORT`             | `6379`                                     | Redis port                                        |
| `DUCKDB_EXTENSIONS_DIR`  | `/opt/duckdb/extensions`                   | Directory for native DuckDB binary extensions     |
| `PARQUET_BASE_DIR`       | `/data/parquet`                            | Base storage path for columnar Parquet partitions |
| `DUCKDB_STAGING_DIR`     | `/staging`                                 | Temporary scratch directory for DuckDB execution  |
| `HEADLESS_RENDERER_URL`  | `http://headless-renderer:3000/render-pdf` | Endpoint for Playwright PDF export worker         |
| `AES_GCM_MASTER_KEY`     | _(32-byte hex)_                            | Master key for encrypting connection credentials  |

---

## Local Development & Operations

### 1. Running the Full Stack with Docker Compose

The recommended approach for local development is launching the bundled services via Docker Compose:

```bash
docker compose -f docker-compose.yml up -d
```

This starts:

- `rc-database` (MySQL 8.0) mapped to host port `33060`.
- `rc-cache` (Redis 7.2) mapped to host port `6379`.
- `rc-api` (Spring Boot 4.1.1) mapped to host port `8080`.

Inspect container statuses:

```bash
docker compose ps
```

View live container logs:

```bash
docker compose logs -f spring-boot
```

### 2. Building the Production Container Image

The production image utilizes a multi-stage build.

> [!IMPORTANT]
> **glibc Runtime Requirement**: The runtime stage uses `eclipse-temurin:21-jre` (glibc-based). The DuckDB native JNI binary is incompatible with musl-based Alpine distributions.

Build the container image:

```bash
docker build -t rc-services:latest -f Dockerfile .
```

### 3. Actuator Health & Probes

The service exposes health and metrics endpoints for container orchestration:

- **Aggregated Health**: `GET http://localhost:8080/actuator/health`
- **Kubernetes Liveness Probe**: `GET http://localhost:8080/actuator/health/liveness`
- **Kubernetes Readiness Probe**: `GET http://localhost:8080/actuator/health/readiness`
- **Prometheus Metrics**: `GET http://localhost:8080/actuator/prometheus`

---

## Engineering Standards

All code within `rc-services` adheres to the Clean Code guidelines specified in [`AGENTS.md`](./AGENTS.md):

- **Single Responsibility & Thin Controllers**: Controllers handle routing, request validation (`@Valid`), status codes, and JSON serialization. Business logic and database interactions belong in services.
- **Constructor Injection**: Inject dependencies through `private final` fields. Field injection (`@Autowired` on attributes) is prohibited.
- **Immutable DTO Contracts**: Request and response payloads must use Java `record` types with Jakarta Bean Validation constraints (`@NotBlank`, `@Size`, `@Email`).
- **Centralized Exception Handling**: Controllers must not contain repetitive try-catch blocks. Exceptions are captured and translated into RFC 7807 `ProblemDetail` structures by [`GlobalExceptionHandler`](./src/main/java/com/rollercoaster/config/GlobalExceptionHandler.java).
- **Quality Gates**: The build enforces an 85% line coverage threshold via the JaCoCo Maven plugin.
