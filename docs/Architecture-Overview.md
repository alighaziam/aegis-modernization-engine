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
Workspace Analysis Orchestrator
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

The Oracle adapter persists project metadata, immutable UPKM JSON snapshots, artifact objects and append-only evidence/conflict journal events behind repository interfaces. Oracle is an optional Aegis-internal persistence backend; parsing and static analysis commands run without an Oracle connection. Customer database discovery remains file/DDL based at this milestone; live read-only catalog discovery is not yet implemented.

## 4. Core subsystems

- **Project and Intake Management:** project lifecycle, intake batches, artifact metadata, schema versions, incremental updates and access policy.
- **Artifact and Format Adapter Layer:** format/version validation, parsing, normalized facts and diagnostics. No architecture decisions.
- **Unified Project Knowledge Model:** canonical parser-independent model. Physical, logical and target data views stay distinct.
- **Evidence and Conflict Registry:** provenance, extraction method, rule/parser version, confidence rationale, user confirmations and unresolved conflicts.
- **Analysis Orchestrator:** deterministic workflow scheduling, incremental recomputation and reproducible runs; not an autonomous LLM agent.
- **Legacy Discovery Engine:** languages, frameworks, build systems, modules, APIs, ORM mappings, integrations and configuration.
- **Data Intelligence Engine:** physical FK lineage, explicit JPA entity/table/column/relationship extraction, conservative SQL SELECT/JOIN output-column lineage and JPA-to-DDL reconciliation. Implicit ORM naming, CTEs, subqueries, computed SQL expressions, stored procedures and runtime data lineage are not inferred.
- **Architecture Intelligence Engine:** dependency graphs, cycles and deterministic Java type/field/method-signature references. Java reference matching is syntactic and import-aware, not compiler-backed symbol resolution; ambiguous simple names remain unresolved.
- **Decision and Migration Planning Engine:** compatibility matrices, policies, quality attributes, risk and explicit approval.
- **Transformation Workbench:** versioned deterministic recipes, diffs and reversible changes; original inputs remain immutable.
- **Validation and Reconciliation:** compilation, static checks, tests, API comparisons, behavior characterization, data reconciliation and rollback verification.
- **Reporting:** architecture maps, lineage, decision records, roadmaps, risk registers and validation reports. The current CLI can render a deterministic Markdown snapshot from a serialized UPKM. The workspace orchestrator combines Java/build discovery, optional DDL scanning, recursive OpenAPI discovery, JPA mapping analysis, dependency graphs, physical FK lineage, SQL SELECT/JOIN lineage and JPA-to-DDL reconciliation. It writes UPKM JSON, OpenAPI/ORM/query-lineage/reconciliation inventories, a summary and a Markdown report. Interactive visual maps and richer export formats remain future work.

## 5. Data model layers

1. **Physical model:** vendor-specific objects and constraints as observed.
2. **Logical model:** business entities and relationships independent of database vendor.
3. **Target model:** proposed destination structures, mappings and migration operations.

Mappings are explicit and evidence-backed. Never collapse the three views into one schema.


## 5.1 Current static data-lineage coverage

- **Physical lineage:** declared foreign-key constraints in supported DDL snapshots, retaining source/target columns and source locators.
- **ORM lineage:** explicit `javax.persistence` / `jakarta.persistence` entity/table/column/join annotations and relationship targets from Java ASTs.
- **ORM/schema reconciliation:** explicit table and column names are compared with physical DDL; explicit join columns are checked against declared foreign keys. Implicit naming strategies are not guessed.
- **Query lineage:** a bounded SELECT projection plus FROM/JOIN subset maps output columns to source columns when table and column resolution is unique. Computed expressions, functions, CTEs, subqueries, set operations, SELECT * and dynamic SQL are diagnostics or unsupported cases.

These are static source declarations, not proof of actual runtime SQL, ORM provider behavior, data movement or production schema state.

## 6. Technology strategy

Java 25 and Maven are the current implementation baseline. Oracle JDBC is confined to the internal persistence adapter. Customer database support is implemented through isolated dialect adapters. Verify license, security, compatibility and offline packaging before adopting additional dependencies. Runtime analysis must work offline after dependencies and rule packs are installed.

## 7. Security and offline operation

- No AI/LLM/model client modules or provider abstractions.
- No telemetry or external analytics by default.
- No external upload of source or metadata.
- Database credentials are environment/secret-manager inputs and never CLI arguments or committed config.
- Use least-privilege Oracle schema credentials and TLS/wallet where required.
- Never execute untrusted project code during static analysis.
- Transformation targets and rollback backups must be path-confined, symlink-safe, non-overwriting and integrity-checked before restoration.
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
