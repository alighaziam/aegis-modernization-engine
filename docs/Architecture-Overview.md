# Aegis Logical Architecture

**Status:** Initial baseline (v0.1)

## 1. Architectural position

Aegis is a deterministic software engineering workbench for legacy discovery, modernization planning, controlled transformation and validation. It is not an LLM-powered agent framework. The system may orchestrate multi-step workflows, but every decision must come from explicit rules, parsers, compatibility data, graph algorithms, deterministic scoring or human approval.

## 2. High-level flow

```text
User / CLI / UI
      |
Project & Intake API
      |
Input Registry ---- Artifact Storage (immutable, checksummed)
      |
Format Adapters: Documents | JSON/YAML/XML | SQL/DB Metadata | Source Code/Build | API Contracts | Tests/Infrastructure
      |
Normalization & Extraction
      |
Unified Project Knowledge Model (UPKM)
      |
Evidence / Provenance / Conflict Registry
      |
Analysis Orchestrator
  |-- Legacy Discovery
  |-- Data Intelligence
  |-- Dependency & Integration Analysis
  |-- Architecture Analysis
  |-- Quality / Risk Analysis
      |
Decision & Migration Planning
      |
Transformation Workbench
      |
Validation & Reconciliation
      |
Reports / Architecture Maps / Migration Artifacts
```

## 3. Core subsystems

### Project and Intake Management
Owns project lifecycle, intake batches, artifact metadata, schema versions, incremental updates and access policy.

### Artifact and Format Adapter Layer
Each adapter detects supported format/version, validates and parses input, emits normalized facts and source locators, and reports diagnostics and unsupported constructs. It never makes architecture decisions. Adapters are isolated by format and version. Lossy fallback parsing must be recorded.

### Unified Project Knowledge Model
The canonical domain model shared by all analysis engines. It must not contain vendor-specific parser classes or raw syntax-tree nodes as its public domain contract. Raw parse artifacts may be retained separately for traceability.

### Evidence and Conflict Registry
Stores provenance, extraction method, rule/parser version, confidence rationale, user confirmations and unresolved conflicts. Analysis engines must query this registry rather than treating inferred facts as ground truth.

### Analysis Orchestrator
Schedules deterministic analysis tasks, tracks dependencies, supports incremental recomputation and records reproducible analysis runs. It is workflow orchestration, not an autonomous LLM agent.

### Legacy Discovery Engine
Detects languages, frameworks, build systems, modules, APIs, ORM mappings, integration points, configuration and code dependencies. Initial focus is Java/Maven/Spring and JavaScript/AngularJS.

### Data Intelligence Engine
Maintains physical, logical and target data views. Extracts tables, columns, keys, constraints, indexes, views, procedures, triggers, ORM mappings, SQL usage and lineage. Relationship discovery combines declared metadata and evidence-based inference. Candidate relationships require review when evidence is incomplete.

### Architecture Intelligence Engine
Builds dependency graphs, evaluates coupling/cohesion, detects supported architectural patterns, identifies cycles and hotspots, and emits explainable findings. Findings link to source evidence.

### Decision and Migration Planning Engine
Uses explicit compatibility matrices, policy rules, target constraints, quality attributes and risk models. Every decision includes alternatives considered, evidence, rationale, assumptions, unresolved questions and approval status.

### Transformation Workbench
Applies deterministic recipes and templates to source or schema artifacts. Transformations are versioned, repeatable, reviewable and produce diffs. Never overwrite original source artifacts.

### Validation and Reconciliation
Supports compilation, static checks, tests, API contract comparison, behavior characterization, data reconciliation, performance/security checks and rollback verification. Executing builds or migrations requires an isolated workspace and explicit approval.

### Reporting
Produces legacy architecture maps, data lineage, target architecture, decision records, migration roadmaps, risk registers, review tasks and validation reports.

## 4. Data model layers

Aegis must keep three distinct data views:
1. **Physical model:** database-specific objects and constraints as observed.
2. **Logical model:** business entities and relationships independent of database vendor.
3. **Target model:** proposed destination structures, mappings and migration operations.

Mapping between these views must be explicit and evidence-backed. Do not collapse them into one schema.

## 5. Decision traceability

Every non-trivial recommendation includes decision ID/status, context, candidate options, selected option (if approved), evidence references, rule or compatibility-matrix version, rationale and trade-offs, assumptions and unresolved risks, and human approver where required. A generated recommendation is not an approved decision.

## 6. Technology strategy

Technology selection is provisional until a spike and compatibility review are completed. Potential choices: Java implementation language; Maven multi-module; JavaParser or Eclipse JDT for Java AST; JSqlParser for supported SQL; Apache Tika for document extraction; JGraphT for graph algorithms; JSON Schema validation; PostgreSQL or embedded storage for metadata; JavaPoet and versioned templates for code generation; JUnit 5 and Testcontainers for isolated tests.

Verify license, security, compatibility and offline packaging before adoption. Do not hard-code a Java/Spring/Hibernate combination until official compatibility and support matrices are checked for the selected release line.

## 7. Modularity boundaries

Suggested logical areas: aegis-domain, aegis-intake-api, aegis-artifact-store, aegis-parser-spi, aegis-parser-json, aegis-parser-sql, aegis-parser-java, aegis-parser-documents, aegis-parser-openapi, aegis-knowledge-model, aegis-evidence, aegis-analysis-core, aegis-data-intelligence, aegis-architecture-analysis, aegis-migration-planner, aegis-transformation, aegis-validation, aegis-reporting, aegis-cli (later), aegis-ui (later).

These are candidate boundaries, not a mandate to create empty modules. Start with cohesive modules and introduce a module only when it has a stable responsibility and tests.

## 8. Security and offline operation

- No AI/LLM/model client modules or provider abstractions.
- No telemetry or external analytics by default.
- No external upload of source or metadata.
- Dependency resolution/build downloads may be needed during development, but runtime analysis must work offline after dependencies and rule packs are installed.
- Make network access an explicit capability, disabled by default.
- Encrypt sensitive artifacts at rest where applicable; support configurable retention and deletion.
- Redact secrets in logs and reports.
- Use read-only DB credentials for discovery.
- Never execute untrusted project code during static analysis.

## 9. Migration strategy

Prefer incremental, verifiable migration where context allows. Evaluate Strangler Fig, modular extraction, API façade, event/data replication and phased cutover. Big-bang replacement is not the default recommendation.

Every migration plan addresses scope and dependency order; data ownership and synchronization; backward compatibility; dual-run or shadow validation where appropriate; cutover criteria; rollback plan; data reconciliation; operational monitoring.

## 10. Quality attributes

Target determinism and reproducibility, explainability and traceability, extensibility through parser/rule adapters, safety with untrusted inputs, offline operability, scalability for large repositories and schemas, incremental analysis, testability, maintainability, privacy and least privilege.

## 11. Initial delivery sequence

1. Domain vocabulary and UPKM model
2. Intake API, schema validation and artifact registry
3. Evidence and provenance model
4. SQL DDL parser and data model extraction
5. Java/Maven discovery
6. Cross-source correlation and conflict handling
7. Architecture/data lineage reports
8. Target model and migration mapping
9. Transformation recipes
10. Validation and reconciliation
11. UI and broader language/database adapters

Each milestone requires fixtures, tests, documented limitations and reproducible examples.
