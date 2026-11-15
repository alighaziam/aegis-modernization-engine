# Aegis Input Specification

**Status:** Initial baseline (v0.1)
**Product:** Aegis Modernization Engine
**Scope:** Input formats, intake modes, normalization, evidence, incremental updates and validation.

## 1. Purpose

This specification defines how Aegis receives information about an existing software system and converts it into a canonical, traceable project model. Inputs may be supplied independently, in pairs, or as a larger bundle. They may also arrive over multiple sessions. Aegis must never require every input category to be present before it can begin useful analysis.

## 2. Runtime independence constraint

The runtime MUST NOT call a hosted or local LLM, AI model, chat completion API, embedding model, or agent service. It MUST NOT require ChatGPT, OpenAI, Ollama, or another model provider. It MUST NOT send project source, documents, schema metadata, prompts, logs, or derived project data to an external inference service. It MUST NOT silently fall back to a remote service when a local parser cannot understand an input.

The runtime MAY use deterministic parsers, compiler front ends, AST libraries, schema validators, graph algorithms, regular expressions, document extraction libraries, static analysis rules, templates and explicitly authored transformation rules. Aegis can run fully disconnected from the network. Optional database metadata discovery must be user-initiated, least-privilege/read-only where possible, and must never transmit extracted information externally.

## 3. Intake modes

| Mode | Description | Expected behavior |
|---|---|---|
| Single source | One file, artifact or connection | Validate and analyze supported facts; report missing context |
| Pair | Any two compatible source categories | Correlate evidence and surface conflicts |
| Bundle | Multiple sources in one intake operation | Normalize and cross-correlate all supported sources |
| Incremental | Sources added over time | Version inputs, update affected analysis and preserve history |
| Manual | User enters facts through forms | Validate fields and record user-provided evidence |

The intake engine must not assume a preferred order. A user may provide a data model before source code, or source code before requirements documents.

## 4. Supported input families

Support is capability-based: a format is supported only when a parser, validator, tests and documented limitations exist.

### 4.1 Data model and database inputs

- SQL DDL: tables, columns, primary keys, foreign keys, unique/check constraints, indexes, views, sequences, triggers and supported stored procedure definitions.
- Database metadata export / data dictionary: CSV, TSV, XLSX or JSON with documented column mapping.
- ERD: supported interchange formats; image-only diagrams require manual confirmation unless deterministic diagram parsing is implemented.
- ORM metadata: JPA annotations, Hibernate XML mappings and other explicitly supported mapping formats.
- Live metadata discovery: optional read-only connections to supported databases, initially Oracle and PostgreSQL; later MySQL and SQL Server.
- Logical data model: structured JSON/YAML or forms.
- Target data model: target schema and mapping definitions.

Absence of a declared foreign key does not prove absence of a business relationship. Candidate relationships may be discovered from SQL joins, ORM mappings, source-code access patterns, stored procedures and naming/type heuristics. Such relationships MUST be marked inferred or review-needed, never confirmed solely by a heuristic.

### 4.2 Documents and specifications

- PDF (scanned PDFs require OCR capability to be explicitly installed and tested)
- DOCX, Markdown, TXT, HTML, XLSX and CSV
- Requirements, architecture descriptions, process definitions and interface specifications

Document extraction is not human-level semantic understanding. Aegis identifies headings, tables, lists, terms, identifiers and supported patterns. Unsupported or ambiguous prose must be surfaced for review.

### 4.3 Structured configuration

- JSON, YAML, XML, TOML and properties files where a parser is implemented
- Project-specific intake forms

JSON/YAML intake must be validated against versioned schemas. Unknown fields should be rejected in strict mode or retained as explicitly unrecognized extensions in permissive mode; never silently reinterpret them.

### 4.4 Source code and build artifacts

- ZIP source bundle, local project directory, Git snapshot or repository archive
- Maven POM and Gradle build files
- Source code and configuration for explicitly supported languages/frameworks
- Generated source, where distinguishable from maintained source

Initial language focus: Java. Initial front-end focus: JavaScript/TypeScript and AngularJS/React project metadata. Additional language analyzers (C#, Python, PHP, etc.) are separate capabilities added through parser adapters.

### 4.5 API and integration contracts

- OpenAPI / Swagger
- WSDL / SOAP
- Postman collections
- AsyncAPI
- Supported message schema formats
- API gateway and integration configuration exports

### 4.6 Tests and operational artifacts

- Unit/integration/acceptance test sources and reports
- Characterization test fixtures
- Dockerfile, Docker Compose, Kubernetes manifests and Helm values
- CI/CD pipeline definitions (for example Jenkinsfile)
- Scheduler/job definitions
- Sanitized logs, metrics exports and incident reports
- Runtime configuration templates

Production secrets, tokens, private keys and personal data must be detected/redacted or excluded before persistence and reporting.

## 5. Intake envelope

Every intake operation is represented by a versioned project-intake document. The initial JSON contract is `schemas/project-intake.schema.json`.

The envelope contains schema version, project identity, optional source/target technology profiles, one or more input artifacts or connection descriptors, optional constraints and quality attributes, optional user-confirmed facts, and optional links to previous intake versions. An artifact descriptor records its type, format, origin, location/reference, checksum when bytes are available, and optional user-provided metadata. Do not embed secrets in the envelope.

## 6. Input lifecycle

1. Register the input and assign an immutable artifact ID.
2. Determine file type using content inspection as well as extension; do not trust extension alone.
3. Apply size, archive-depth, file-count and decompression-bomb limits.
4. Scan for secrets and sensitive data according to configured policies.
5. Validate format and schema.
6. Parse with the matching adapter.
7. Normalize extracted facts into the Unified Project Knowledge Model (UPKM).
8. Attach evidence and source locations to every extracted fact.
9. Correlate facts with existing project knowledge.
10. Detect conflicts, missing information and unsupported constructs.
11. Recompute only impacted analysis nodes where possible.
12. Produce an intake report with accepted, rejected, skipped and review-needed items.

Parsing failure in one artifact must not discard successful results from other artifacts in the same bundle.

## 7. Unified Project Knowledge Model (UPKM)

UPKM is the canonical, versioned domain representation. Format-specific parser output must be adapted into UPKM rather than allowing each engine to consume arbitrary raw formats directly.

Initial conceptual aggregates:

- Project, IntakeBatch, SourceArtifact, TechnologyProfile
- SystemBoundary, Application, Module, Component, Package, SourceSymbol
- API, Endpoint, MessageContract, DataStore, Schema, Table, Column, View
- DataEntity, Attribute, Relationship, BusinessCapability, BusinessRule, Process
- Dependency, Integration, Job, QualityAttribute, Constraint
- MigrationMapping, Transformation, Evidence, Finding, Conflict, ReviewTask
- AnalysisRun, Decision, ValidationResult

Each fact has a stable identity and lifecycle. The UPKM must distinguish:
- **Observed:** directly present in a source artifact.
- **User-confirmed:** explicitly supplied or confirmed by a user.
- **Inferred:** derived through a documented deterministic rule.
- **Unknown:** not established by current evidence.
- **Conflicted:** multiple sources disagree.

## 8. Evidence and confidence contract

Every extracted or inferred fact must be traceable to one or more evidence records. Required properties: evidence ID; subject/fact reference; source artifact ID or explicit user-input origin; source locator (file path, line/column, JSON pointer, SQL object, database catalog location, page/table/section, etc.); extraction method and parser/rule version; evidence kind (direct, user-confirmed, inferred); confidence classification and rationale; review status; extraction timestamp and analysis-run ID.

Confidence is not a probability unless a calibrated statistical method is explicitly introduced and documented. Use categorical confidence (high/medium/low) with a rule-based rationale. Do not invent numeric certainty.

For relationships: declared FK or explicit model relationship can be observed; SQL JOIN or ORM access patterns can support an inferred relationship; naming similarity alone is weak evidence and must never create a confirmed relationship. Every relationship retains cardinality when known, direction where meaningful, source and confirmation status.

The evidence schema is `schemas/evidence.schema.json`.

## 9. Correlation and conflict handling

When sources disagree, Aegis must preserve both claims and create a conflict record. It must not silently select one source based on a hard-coded preference.

Examples: a document describes a field as mandatory while the database allows NULL; a JPA entity maps a column absent from the DDL snapshot; an API contract exposes a property not found in the current source revision; a user says the target is PostgreSQL while a migration config indicates another target.

A conflict includes competing facts, evidence references, impact and a resolution state. User resolution becomes a new evidence record; historical claims remain available.

## 10. Incremental and versioned intake

- Every intake batch is immutable after acceptance; corrections create a new version.
- Each source artifact is checksummed when possible.
- Re-ingesting an identical artifact should be idempotent.
- Changed artifacts trigger impact analysis and invalidate only dependent derived results.
- UPKM changes are auditable.
- Analysis runs record tool/parser/rule versions and input artifact versions.
- A user can compare two project snapshots and inspect what changed.

## 11. Validation and safety

Validate schema and syntax, file type and size, archive traversal and decompression limits, encoding and malformed content, duplicate artifacts, unsupported syntax/version, secret and sensitive-data exposure, database connection permissions and cross-source consistency.

Never execute untrusted source code, SQL scripts, build plugins, shell scripts or migrations during discovery. Static parsing and metadata reads are the default. Execution-based validation must be a separate, explicitly approved, isolated stage.

## 12. Intake outputs

Each intake produces an intake summary; accepted/rejected/skipped artifact list; parser coverage and unsupported constructs; extracted facts and evidence references; conflicts and review tasks; missing information checklist; UPKM snapshot/version; and analysis impact summary.

## 13. Initial implementation priorities

MVP input path: JSON/YAML project intake; Java/Maven source archive; SQL DDL (Oracle and PostgreSQL subset); OpenAPI; Markdown/TXT/DOCX/PDF text extraction; CSV/XLSX data dictionary; manual forms for business rules, quality attributes and unresolved relationships.

Do not claim full support for a format until fixtures, positive/negative tests, version coverage and limitations are documented.

## 14. Acceptance criteria

- A project can be created with only one supported artifact.
- Two or more input families can be correlated without requiring a fixed order.
- New artifacts can be added later and update the existing project snapshot.
- Every extracted fact can be traced to its source.
- Inferred relationships are distinguishable from declared relationships.
- Conflicts remain visible until resolved.
- Unsupported constructs are reported, not silently ignored.
- Runtime analysis can complete without network access and without any AI/LLM/model service.
- Re-ingestion of unchanged artifacts is idempotent.
- Malformed or hostile inputs do not cause execution of embedded code or scripts.
