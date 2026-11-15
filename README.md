# Aegis Modernization Engine

**Aegis** is an offline-first, deterministic software modernization and legacy migration engine. It discovers existing systems, builds an evidence-backed model of their structure and behavior, designs a target architecture, plans migration, and validates the result.

> **Non-negotiable product constraint:** Aegis does not connect to or depend on any online or offline AI/LLM model. No ChatGPT, OpenAI API, Ollama, local model server, hosted agent, or model provider is part of the runtime architecture.

## What Aegis is

A rule-based engineering platform built from format-specific parsers and extractors, language and framework analyzers, AST/static analysis and dependency graphs, database metadata and SQL analysis, deterministic rules/mappings/transformation recipes, explicit human review for ambiguity, and repeatable validation. Aegis is not a general-purpose natural-language reasoning system. Unsupported or ambiguous prose must become an explicit review item, never a fabricated fact.

## Initial modernization scenario

**Legacy:** Java 8, legacy Spring, Oracle, AngularJS  
**Target:** a supported modern Java release, compatible Spring Boot release, PostgreSQL, React.

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
- A Java behavior AST analyzer that records syntactic method-call receiver candidates, guard conditions and thrown exceptions; it deliberately does not claim overload resolution or runtime call-graph certainty, in `src/main/java/io/aegis/analysis`.
- A deterministic dependency graph analyzer that reports internal strongly connected components (cycles) separately from external imports, in `src/main/java/io/aegis/analysis`.
- A technology detector for Java release, Spring Boot and supported Spring dependency markers from Maven/Gradle descriptors and Java source.
- An OpenAPI 3.x YAML/JSON analyzer that extracts endpoint contracts and component schemas without resolving remote `$ref` values.
- A conservative logical-model and business-rule inference pass that derives candidate entities from physical DDL and validation/guard markers from source text; all inferred facts are marked `INFERRED`.
- A deterministic target architecture and phased migration planner driven by an explicit target profile.
- A guarded text transformation engine with expected-occurrence/hash preconditions, SHA-256 backups and rollback conflict checks.
- A generated-artifact integrity validator that checks expected hashes and rejects paths escaping the project root.
- Append-only evidence and conflict journals in `src/main/java/io/aegis/evidence`.
- A command-line entry point for intake, DDL parsing, Java analysis and UPKM mapping in `src/main/java/io/aegis/cli`.

Unsupported SQL syntax is surfaced as a diagnostic requiring review. The parser is not a complete Oracle or PostgreSQL grammar and must not be treated as one.

## Build and run

Requirements: JDK 25 and Gradle 9.2.1 or newer compatible with Java 25.

```bash
gradle clean test
gradle installDist

# Validate and register artifacts described by an intake JSON file
build/install/aegis-modernization-engine/bin/aegis-modernization-engine intake ./project-intake.json ./aegis-store

# Parse the supported SQL DDL subset and print a JSON result
build/install/aegis-modernization-engine/bin/aegis-modernization-engine parse-ddl ./schema.sql postgresql

# Merge parsed DDL into the project's canonical physical data model (UPKM)
build/install/aegis-modernization-engine/bin/aegis-modernization-engine map-ddl ./project-intake.json ./schema.sql postgresql orders-db public 16

# Extract Java declarations/imports and merge them into the project UPKM
build/install/aegis-modernization-engine/bin/aegis-modernization-engine analyze-java ./src/main/java/com/acme/OrderService.java ./project-intake.json
```

Exit codes: `0` means completed without diagnostics/warnings, `1` means execution or validation error, `2` means invalid command usage, and `3` means processing completed with review diagnostics or intake warnings.

A GitHub Actions workflow runs Java 25 compilation, tests, and distribution assembly on pushes and pull requests to `main`. The `map-ddl` command combines the validated project intake, technology profile, SQL parse result, and physical database model into one JSON output; it does not infer logical business relationships. The `analyze-java` command uses the JDK parser only (`-proc:none`), records declared imports as explicit dependencies, and does not perform semantic resolution or claim that every import is used. The dependency graph only detects cycles among components already identified in the UPKM; it does not infer hidden runtime calls. The `detect-tech`, `analyze-behavior`, `analyze-openapi`, `infer-model` and `plan` commands expose the new deterministic discovery and planning capabilities. Technology detection is evidence-based and incomplete when build metadata is absent. OpenAPI external references are not fetched. Logical entities, cardinalities and source-derived business rules are candidates, not verified business truth. The transformation engine applies only explicitly supplied literal replacements; it is not yet a semantic Java refactoring engine. Artifact validation verifies hashes but does not replace compilation, integration, security or performance testing.

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

The project has an initial runtime foundation, not a production-ready modernization engine. Intake, evidence journaling, a canonical model, a limited SQL parser and CLI are implemented. Framework/version discovery, semantic and cross-module source analysis, additional language parsers, OpenAPI ingestion, logical data-model inference, architecture decision automation, migration planning, transformation engines, rollback generation and broad integration tests remain future milestones. Supported syntax and technology/version matrices must be expanded only with executable tests.

## License

License selection is pending. Do not assume this project is licensed for reuse until a license file is added.
