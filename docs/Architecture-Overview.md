# Aegis Logical Architecture

**Status:** Initial baseline (v0.2)

## 1. Architectural position

Aegis is a deterministic software engineering workbench for legacy discovery, modernization planning, controlled transformation and validation. It is not an LLM-powered agent framework. Every decision comes from explicit rules, parsers, compatibility data, graph algorithms, deterministic scoring or human approval.

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

## 3. Persistence boundary and database independence

Two database roles are deliberately separated:

1. **Aegis internal operational database:** Oracle Database 19c+ is the required production persistence engine for project metadata and versioned analysis snapshots.
2. **Customer project databases:** each analyzed project's source and target database vendor is independent and selected per project. Oracle is one supported dialect, not the only allowed target.

All application services depend on the database-neutral `ProjectRepository` interface. The Oracle adapter owns JDBC, connection pooling and Oracle SQL. No Oracle-specific classes or SQL types may enter the UPKM/domain layer. Customer database parsers and transformation planners are separate dialect adapters and must not use the internal persistence connection.

The initial Oracle adapter persists project metadata and immutable UPKM JSON snapshots in CLOBs. The artifact object store and append-only evidence/conflict journals remain separate stores at this milestone; moving those to Oracle is a later repository-adapter milestone.

## 4. Core subsystems

- **Project and Intake Management:** project lifecycle, intake batches, artifact metadata, schema versions, incremental updates and access policy.
- **Artifact and Format Adapter Layer:** format/version validation, parsing, normalized facts and diagnostics. No architecture decisions.
- **Unified Project Knowledge Model:** canonical parser-independent model. Physical, logical and target data views stay distinct.
- **Evidence and Conflict Registry:** provenance, extraction method, rule/parser version, confidence rationale, user confirmations and unresolved conflicts.
- **Analysis Orchestrator:** deterministic workflow scheduling, incremental recomputation and reproducible runs; not an autonomous LLM agent.
- **Legacy Discovery Engine:** languages, frameworks, build systems, modules, APIs, ORM mappings, integrations and configuration.
- **Data Intelligence Engine:** physical, logical and target data views, lineage, mapping and data-quality findings.
- **Architecture Intelligence Engine:** dependency graphs, coupling/cohesion, supported patterns, cycles and hotspots. Java references are matched deterministically from imports, inheritance, fields and method signatures; ambiguous simple names remain unresolved rather than guessed.
- **Decision and Migration Planning Engine:** compatibility matrices, policies, quality attributes, risk and explicit approval.
- **Transformation Workbench:** versioned deterministic recipes, diffs and reversible changes; original inputs remain immutable.
- **Validation and Reconciliation:** compilation, static checks, tests, API comparisons, behavior characterization, data reconciliation and rollback verification.
- **Reporting:** architecture maps, lineage, decision records, roadmaps, risk registers and validation reports. The current CLI can render a deterministic Markdown snapshot from a serialized UPKM, including declared physical foreign-key lineage; interactive visual maps and richer export formats remain future work.

## 5. Data model layers

1. **Physical model:** vendor-specific objects and constraints as observed.
2. **Logical model:** business entities and relationships independent of database vendor.
3. **Target model:** proposed destination structures, mappings and migration operations.

Mappings are explicit and evidence-backed. Never collapse the three views into one schema.

## 6. Technology strategy

Java 25 and Maven are the current implementation baseline. Oracle JDBC is confined to the internal persistence adapter. Customer database support is implemented through isolated dialect adapters. Verify license, security, compatibility and offline packaging before adopting additional dependencies. Runtime analysis must work offline after dependencies and rule packs are installed.

## 7. Security and offline operation

- No AI/LLM/model client modules or provider abstractions.
- No telemetry or external analytics by default.
- No external upload of source or metadata.
- Database credentials are environment/secret-manager inputs and never CLI arguments or committed config.
- Use least-privilege Oracle schema credentials and TLS/wallet where required.
- Never execute untrusted project code during static analysis.
- Database schema changes are explicit operator actions; the application does not auto-migrate production schemas.

## 8. Migration strategy

Prefer incremental, verifiable migration. Every plan addresses dependency order, data ownership, synchronization, compatibility, cutover criteria, rollback, reconciliation and monitoring.

## 9. Delivery sequence

1. Domain vocabulary and UPKM
2. Intake, schema validation and artifact registry
3. Evidence/provenance
4. SQL DDL and data extraction
5. Java/build discovery
6. Cross-source correlation
7. Architecture/data lineage
8. Target model and migration mapping
9. Transformation recipes
10. Validation and reconciliation
11. Oracle-backed internal persistence and broader database adapters
12. UI and additional language/database adapters

Each milestone requires fixtures, tests, documented limitations and reproducible examples.
