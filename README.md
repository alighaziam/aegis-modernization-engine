# Aegis Modernization Engine

**Aegis** is an offline-first, deterministic software modernization and legacy migration engine. It discovers existing systems, builds an evidence-backed model of their structure and behavior, designs a target architecture, plans migration, and validates the result.

> **Non-negotiable product constraint:** Aegis does not connect to or depend on any online or offline AI/LLM model. No ChatGPT, OpenAI API, Ollama, local model server, hosted agent, or model provider is part of the runtime architecture.

## What Aegis is

A rule-based engineering platform built from format-specific parsers and extractors, language and framework analyzers, AST/static analysis and dependency graphs, database metadata and SQL analysis, deterministic rules/mappings/transformation recipes, explicit human review for ambiguity, and repeatable validation. Aegis is not a general-purpose natural-language reasoning system. Unsupported or ambiguous prose must become an explicit review item, never a fabricated fact.

## Initial modernization scenario

**Legacy:** Java 8, legacy Spring, Oracle, AngularJS  
**Target:** a supported modern Java release, compatible Spring Boot release, a database selected per project (Oracle, PostgreSQL, SQL Server, MySQL, etc.), React.

Exact framework/runtime versions must be selected through a maintained compatibility matrix and verified before implementation. Do not assume every newest version is mutually compatible.

## Core capabilities

1. Multi-format project intake: documents, data models, JSON/YAML, source code, database metadata, API contracts, tests, infrastructure and operational artifacts.
2. Incremental intake: add one source now and more later without losing prior analysis.
3. Unified Project Knowledge Model (UPKM): canonical representation of systems, components, entities, relationships, APIs, rules and dependencies.
4. Evidence and provenance: every extracted or inferred fact records its source, extraction method, confidence rationale and review status.
5. Legacy discovery: language/framework detection, AST analysis, SQL/ORM analysis, dependency and integration mapping.
6. Data intelligence: physical/logical/target data models, lineage, mapping, relationship discovery and data-quality findings.
7. Architecture and migration planning: explainable decisions, risk, sequencing, compatibility and rollback.
8. Controlled transformations and validation: generated changes are reviewable, repeatable and tested.

## Current implementation

The repository now contains an initial executable Java implementation:

- Canonical UPKM domain records in `src/main/java/io/aegis/domain`.
- Strict JSON intake validation and content-addressed artifact registration in `src/main/java/io/aegis/intake`.
- An offline SQL DDL parser for a deliberately limited Oracle/PostgreSQL subset, plus a DDL-to-UPKM mapper, in `src/main/java/io/aegis/parser/sql`.
- A JDK compiler-tree Java source analyzer that extracts packages, imports, type declarations, field types and method signatures without compiling or executing uploaded code; the UPKM mapper resolves same-package, explicit-import and unambiguous project type references for dependency analysis, in `src/main/java/io/aegis/parser/java`.
- A bounded, read-only project scanner that discovers Java files and Maven/Gradle descriptors across nested modules, skips generated/vendor directories and symbolic links, applies file/byte limits, merges internal import edges and emits a project dependency-cycle report, in `src/main/java/io/aegis/discovery/ProjectSourceScanner.java`.
- A Java behavior AST analyzer that records syntactic method-call receiver candidates, guard conditions and thrown exceptions; it deliberately does not claim overload resolution or runtime call-graph certainty, in `src/main/java/io/aegis/analysis`.
- A read-only JPA mapping analyzer for `javax`/`jakarta.persistence` entities, tables, columns, join columns and relationship declarations; its recursive scan skips generated/vendor directories and symbolic links and enforces file/byte limits, in `src/main/java/io/aegis/analysis/JpaMappingAnalyzer.java`.
- A JPA-to-physical-schema reconciliation analyzer that compares explicit entity/table/column/join declarations with discovered DDL tables, columns and foreign keys; it reports mismatches, ambiguous tables and unchecked implicit mappings without claiming ORM runtime equivalence.
- A bounded SQL query-lineage analyzer for a conservative `SELECT` projection and `FROM/JOIN` subset. It maps selected output columns back to source tables/columns, reports ambiguous unqualified columns and unsupported constructs, and never executes SQL.
- A workspace analysis pipeline that combines Java, SQL DDL, SQL SELECT/JOIN query lineage, OpenAPI, JPA, dependency, physical FK lineage and JPA-to-DDL reconciliation. It emits UPKM, ORM/OpenAPI/query-lineage/reconciliation inventories, a summary and Markdown architecture report. Spring controller routes are added to UPKM and reconciled against OpenAPI method/path contracts.
- A deterministic dependency graph analyzer that reports internal strongly connected components (cycles) separately from external imports, in `src/main/java/io/aegis/analysis`.
- A bounded SQL SELECT/JOIN query-lineage analyzer that traces simple projected columns to physical source columns and reports unsupported constructs without executing SQL.
- A bounded Spring MVC/WebFlux route analyzer and OpenAPI consistency checker that compares explicit HTTP method/path pairs.
- A technology detector for Java release, Spring Boot and supported Spring dependency markers from Maven/Gradle descriptors and Java source.
- An OpenAPI 3.x YAML/JSON analyzer that extracts endpoint contracts and component schemas without resolving remote `$ref` values.
- A read-only JDBC metadata discovery adapter for tables, columns, primary keys, foreign keys and indexes; connection credentials are read only from environment variables.
- A bounded Spring MVC/WebFlux analyzer that extracts explicit controller mappings and compares HTTP method/path pairs with OpenAPI contracts.
- A read-only JDBC metadata adapter that discovers tables, columns, primary keys, foreign keys and indexes without executing application SQL; JDBC URL and credentials are supplied through environment variables.
- A conservative logical-model and business-rule inference pass that derives candidate entities from physical DDL and validation/guard markers from source text; all inferred facts are marked `INFERRED`.
- A deterministic target architecture and phased migration planner driven by an explicit target profile.
- A source/target data-reconciliation planner that generates row-count, primary-key, required-column and foreign-key orphan checks, supports explicit table/column mappings, and flags type/length/precision/scale reductions. It emits SQL but never executes it.
- A source/target data-reconciliation planner that generates non-executed row-count, primary-key, required-column and foreign-key orphan validation SQL, with explicit mapping support for renamed tables/columns.
- A guarded text transformation engine with expected-occurrence/hash preconditions, isolated rollback storage, symlink/path protections, non-overwriting SHA-256 backups, atomic writes and all-target rollback preflight.
- An initial Oracle-backed internal persistence adapter for versioned UPKM snapshots, isolated behind a database-neutral repository interface.
- A conservative cross-dialect DDL adapter matrix for the supported `CREATE TABLE` / `CREATE INDEX` subset across Oracle, PostgreSQL, MySQL, MariaDB, SQL Server and DB2. Oracle-to-PostgreSQL retains a specialized planner; other pairs use explicit common-type mappings, dialect-specific identifier quoting, and review diagnostics. Unsupported columns cause the affected table to be omitted rather than partially generated. Defaults and vendor-specific semantics are never guessed.
- A syntax-aware Java import migration preview for a strict Jakarta namespace allowlist (`javax.persistence`, `javax.validation`, `javax.servlet`, `javax.transaction`); it uses JDK compiler parse trees, never broad text replacement, and defaults to preview-only.
- A generated-artifact integrity validator that streams SHA-256 hashes, rejects path/symlink escapes and reports malformed or duplicate manifest entries.
- Append-only evidence and conflict journals in `src/main/java/io/aegis/evidence`.
- A command-line entry point for intake, DDL parsing, Java analysis and UPKM mapping in `src/main/java/io/aegis/cli`.

Unsupported SQL syntax is surfaced as a diagnostic requiring review. The parser is not a complete Oracle or PostgreSQL grammar and must not be treated as one.

## Build and run

Requirements: JDK 25, Apache Maven 3.9.11 or newer. Oracle Database 19c+ is required only when using Aegis persistent storage; analysis/parsing commands can still run without a database.

```bash
mvn clean verify

# Run the packaged CLI
java -jar target/aegis-modernization-engine-0.1.0-SNAPSHOT.jar --help

# Validate and register artifacts described by an intake JSON file
java -jar target/aegis-modernization-engine-0.1.0-SNAPSHOT.jar intake ./project-intake.json ./aegis-store

# Parse the supported SQL DDL subset and print a JSON result
java -jar target/aegis-modernization-engine-0.1.0-SNAPSHOT.jar parse-ddl ./schema.sql postgresql

# Merge parsed DDL into the project's canonical physical data model (UPKM)
java -jar target/aegis-modernization-engine-0.1.0-SNAPSHOT.jar map-ddl ./project-intake.json ./schema.sql postgresql orders-db public 16

# Scan all supported SQL DDL files in a project tree and merge the physical schema
java -jar target/aegis-modernization-engine-0.1.0-SNAPSHOT.jar analyze-database ./legacy-project ./project-intake.json postgresql orders-db public 16

# Extract Java declarations/imports and merge them into the project UPKM
java -jar target/aegis-modernization-engine-0.1.0-SNAPSHOT.jar analyze-java ./src/main/java/com/acme/OrderService.java ./project-intake.json

# Scan a complete Java/Maven/Gradle project tree without executing its build
java -jar target/aegis-modernization-engine-0.1.0-SNAPSHOT.jar analyze-project ./legacy-project ./project-intake.json

# Run the end-to-end workspace pipeline and write UPKM, summary and Markdown report
java -jar target/aegis-modernization-engine-0.1.0-SNAPSHOT.jar analyze-workspace ./legacy-project ./project-intake.json ./aegis-output postgresql legacy-db public 16

# Extract JPA entity/table/column and relationship mappings from a Java project
java -jar target/aegis-modernization-engine-0.1.0-SNAPSHOT.jar analyze-jpa ./legacy-project

# Compare explicit ORM mappings with physical tables, columns and foreign keys
java -jar target/aegis-modernization-engine-0.1.0-SNAPSHOT.jar reconcile-jpa ./aegis-output/upkm.json ./aegis-output/orm-mappings.json

# Trace selected columns through supported SQL SELECT/JOIN statements
java -jar target/aegis-modernization-engine-0.1.0-SNAPSHOT.jar analyze-query-lineage ./legacy-project ./aegis-output/upkm.json

# Render an architecture report from a serialized UPKM snapshot
java -jar target/aegis-modernization-engine-0.1.0-SNAPSHOT.jar render-report ./upkm.json ./reports/architecture.md

# Generate a target migration plan from the discovered UPKM
java -jar target/aegis-modernization-engine-0.1.0-SNAPSHOT.jar plan-workspace ./aegis-output/upkm.json 25 'Spring Boot' 4.0.0 PostgreSQL React Kubernetes ./migration-plan

# Generate read-only source/target data reconciliation SQL from two discovered DataStores
java -jar target/aegis-modernization-engine-0.1.0-SNAPSHOT.jar plan-data-reconciliation ./upkm-with-source-and-target.json legacy-db target-db - ./data-reconciliation

# Verify Aegis' internal Oracle connection (credentials supplied through environment variables)
java -jar target/aegis-modernization-engine-0.1.0-SNAPSHOT.jar db-check

# Discover a customer database schema through JDBC metadata (no SQL statements are executed)
java -jar target/aegis-modernization-engine-0.1.0-SNAPSHOT.jar discover-db-metadata ./aegis-output/upkm.json customer-db AEGIS_DB_URL AEGIS_DB_USER AEGIS_DB_PASSWORD public ./aegis-output/upkm-with-db.json

# Store an initial project UPKM snapshot in Aegis' internal Oracle database
java -jar target/aegis-modernization-engine-0.1.0-SNAPSHOT.jar store-intake ./project-intake.json

# Load the latest stored snapshot
java -jar target/aegis-modernization-engine-0.1.0-SNAPSHOT.jar load-model <project-id>

# Generate a review-required Oracle-to-PostgreSQL DDL plan from the supported common subset
java -jar target/aegis-modernization-engine-0.1.0-SNAPSHOT.jar plan-ddl-migration ./schema-oracle.sql oracle postgresql app

# List registered database dialects and declared parser/planner capabilities
java -jar target/aegis-modernization-engine-0.1.0-SNAPSHOT.jar list-db-dialects

# Preview only the explicitly supported javax-to-jakarta import migrations
java -jar target/aegis-modernization-engine-0.1.0-SNAPSHOT.jar migrate-java-imports ./src/main/java/com/acme/OrderService.java

# Apply the previewed import changes (creates OrderService.java.aegis.bak; refuses to overwrite an existing backup)
java -jar target/aegis-modernization-engine-0.1.0-SNAPSHOT.jar migrate-java-imports ./src/main/java/com/acme/OrderService.java --write
```

Exit codes: `0` means completed without diagnostics/warnings, `1` means execution or validation error, `2` means invalid command usage, and `3` means processing completed with review diagnostics or intake warnings.

A GitHub Actions workflow runs Java 25 compilation, tests, executable-JAR packaging and a CLI smoke test with Maven on pushes and pull requests to `main`. The `map-ddl` command combines the validated project intake, technology profile, SQL parse result, and physical database model into one JSON output; it does not infer logical business relationships. The `analyze-java` command uses the JDK parser only (`-proc:none`), records declared imports as explicit dependencies, and does not perform semantic resolution or claim that every import is used. The `analyze-project` command scans Java sources and Maven/Gradle descriptors recursively with explicit file-count and byte limits; it skips common generated/build directories and symbolic links, resolves import names only against discovered project types, and reports internal cycles. This is deterministic name matching, not compiler symbol resolution; non-Java languages, dependency lockfiles and runtime wiring are not yet analyzed. The dependency graph only detects cycles among components already identified in the UPKM; it does not infer hidden runtime calls. The `detect-tech`, `analyze-behavior`, `analyze-openapi`, `infer-model` and `plan` commands expose the new deterministic discovery and planning capabilities. Technology detection is evidence-based and incomplete when build metadata is absent. OpenAPI external references are not fetched. Logical entities, cardinalities and source-derived business rules are candidates, not verified business truth. The general transformation engine applies only explicitly supplied literal replacements. The Jakarta import migrator is AST/syntax-aware but does not resolve types, inspect dependency versions, or guarantee behavioral compatibility; review the emitted diff and validate the target dependency set before applying. Artifact validation verifies hashes but does not replace compilation, integration, security or performance testing.

## Repository map

- `docs/Aegis-Input-Specification.md` — input formats, ingestion behavior, UPKM and evidence contract.
- `docs/Architecture-Overview.md` — logical architecture and engineering boundaries.
- `schemas/project-intake.schema.json` — initial JSON intake contract.
- `schemas/evidence.schema.json` — evidence/provenance contract.
- `examples/minimal-project.json` — minimal project intake example.
- `PROMPT.md` — master development brief. It is a development artifact, not a runtime AI dependency.

## Design principles

- Offline by default; no model inference or remote AI dependency.
- Deterministic and reproducible analysis.
- Evidence before inference; uncertainty is visible.
- Source files are immutable inputs; analysis results are versioned.
- Partial inputs are useful and must not block all analysis.
- Human approval is required for consequential ambiguous decisions.
- Language/database-specific plugins must not leak into the core domain model.
- Generated code and data migrations must be traceable and reversible where feasible.
- Security, privacy, secret redaction and safe handling of production data are first-class concerns.

## Status

Aegis has a working deterministic CLI foundation and a growing set of verified analysis and planning vertical slices. Implemented capabilities include schema-validated intake and immutable artifact registration; UPKM/evidence/conflict primitives; limited Oracle/PostgreSQL and common-dialect DDL parsing/planning; Java AST and behavior discovery; dependency-cycle analysis; OpenAPI extraction; Spring route discovery and OpenAPI method/path reconciliation; JPA mapping discovery and DDL consistency checks; SQL SELECT/JOIN column lineage; JDBC read-only metadata discovery; Oracle-backed snapshots, artifact digests and evidence/conflict journals; guarded text and Jakarta import transformations; target architecture planning; and source/target data-reconciliation SQL generation.

The project is **not production-ready**. The current parsers intentionally cover bounded syntax subsets and do not establish full semantic equivalence. Generated migration and reconciliation SQL is a review artifact; Aegis does not yet execute full application/database migrations or certify data correctness.

Major remaining milestones:
- Broader language/framework parsing and compiler-backed cross-module semantic analysis.
- Broader SQL grammar, stored procedures, views, triggers, sequences, partitions and vendor-specific metadata/DDL coverage.
- General cross-source conflict detection and human review workflows beyond the implemented JPA and API consistency checks.
- Production-grade, reversible code/schema/data migration execution with isolation, approvals, checkpoints and rollback.
- End-to-end validation and data reconciliation against real source/target database engines, including performance and failure recovery.
- Security hardening, dependency vulnerability scanning, operational observability and production deployment packaging.
- A user-facing review/workbench interface and installation/upgrade experience.

A feature is complete only when its supported scope and limitations are documented and its automated tests pass in CI. Do not treat a generated plan or a passing unit test as proof of production migration safety.

## License

License selection is pending. Do not assume this project is licensed for reuse until a license file is added.
