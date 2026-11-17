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
- A JDK compiler-tree Java source analyzer that extracts packages, imports, type declarations and method signatures without compiling or executing uploaded code, in `src/main/java/io/aegis/parser/java`.
- A bounded, read-only project scanner that discovers Java files and Maven/Gradle descriptors across nested modules, skips generated/vendor directories and symbolic links, applies file/byte limits, merges internal import edges and emits a project dependency-cycle report, in `src/main/java/io/aegis/discovery/ProjectSourceScanner.java`.
- A Java behavior AST analyzer that records syntactic method-call receiver candidates, guard conditions and thrown exceptions; it deliberately does not claim overload resolution or runtime call-graph certainty, in `src/main/java/io/aegis/analysis`.
- A deterministic dependency graph analyzer that reports internal strongly connected components (cycles) separately from external imports, in `src/main/java/io/aegis/analysis`.
- A technology detector for Java release, Spring Boot and supported Spring dependency markers from Maven/Gradle descriptors and Java source.
- An OpenAPI 3.x YAML/JSON analyzer that extracts endpoint contracts and component schemas without resolving remote `$ref` values.
- A conservative logical-model and business-rule inference pass that derives candidate entities from physical DDL and validation/guard markers from source text; all inferred facts are marked `INFERRED`.
- A deterministic target architecture and phased migration planner driven by an explicit target profile.
- A guarded text transformation engine with expected-occurrence/hash preconditions, SHA-256 backups and rollback conflict checks.
- An initial Oracle-backed internal persistence adapter for versioned UPKM snapshots, isolated behind a database-neutral repository interface.
- A conservative cross-dialect DDL adapter matrix for the supported `CREATE TABLE` / `CREATE INDEX` subset across Oracle, PostgreSQL, MySQL, MariaDB, SQL Server and DB2. Oracle-to-PostgreSQL retains a specialized planner; other pairs use explicit common-type mappings, dialect-specific identifier quoting, and review diagnostics. Unsupported columns cause the affected table to be omitted rather than partially generated. Defaults and vendor-specific semantics are never guessed.
- A syntax-aware Java import migration preview for a strict Jakarta namespace allowlist (`javax.persistence`, `javax.validation`, `javax.servlet`, `javax.transaction`); it uses JDK compiler parse trees, never broad text replacement, and defaults to preview-only.
- A generated-artifact integrity validator that checks expected hashes and rejects paths escaping the project root.
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

# Extract Java declarations/imports and merge them into the project UPKM
java -jar target/aegis-modernization-engine-0.1.0-SNAPSHOT.jar analyze-java ./src/main/java/com/acme/OrderService.java ./project-intake.json

# Scan a complete Java/Maven/Gradle project tree without executing its build
java -jar target/aegis-modernization-engine-0.1.0-SNAPSHOT.jar analyze-project ./legacy-project ./project-intake.json

# Verify Aegis' internal Oracle connection (credentials supplied through environment variables)
java -jar target/aegis-modernization-engine-0.1.0-SNAPSHOT.jar db-check

# Store an initial project UPKM snapshot in Aegis' internal Oracle database
java -jar target/aegis-modernization-engine-0.1.0-SNAPSHOT.jar store-intake ./project-intake.json

# Load the latest stored snapshot
java -jar target/aegis-modernization-engine-0.1.0-SNAPSHOT.jar load-model <project-id>

# Generate a review-required PostgreSQL DDL plan from supported Oracle CREATE TABLE statements
java -jar target/aegis-modernization-engine-0.1.0-SNAPSHOT.jar plan-ddl-migration ./schema-oracle.sql oracle app

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

The project has an initial runtime foundation, not a production-ready modernization engine. Intake, evidence journaling, a canonical model, limited SQL/Java/OpenAPI analysis, technology discovery, migration planning, guarded transformations, rollback primitives and CLI are implemented at an early stage. Semantic and cross-module source analysis, additional language parsers, richer logical data-model inference, production-grade code/schema transformations and broad integration tests remain future milestones. Aegis internal persistence is Oracle Database 19c+; see `docs/Persistence-and-Database-Portability.md` and `db/oracle/V001__aegis_core.sql`. Set `AEGIS_ORACLE_JDBC_URL`, `AEGIS_ORACLE_USER`, and `AEGIS_ORACLE_PASSWORD`; optional pool settings are documented. The internal Oracle adapter stores project metadata and append-only UPKM snapshots. It does not yet move artifact objects or evidence/conflict journals into Oracle. Customer source/target databases remain independent and selectable per project; Oracle is not a forced target. The Oracle/PostgreSQL planner does not execute SQL, migrate data, convert PL/SQL, sequences, triggers, indexes or every Oracle datatype; generated DDL is a review artifact, not an executable migration guarantee. The current Java import migrator is intentionally limited to four package roots and must not be interpreted as a complete Java EE/Jakarta migration. Supported syntax and technology/version matrices must be expanded only with executable tests.

## License

License selection is pending. Do not assume this project is licensed for reuse until a license file is added.
