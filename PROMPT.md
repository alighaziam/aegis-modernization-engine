# Aegis — Master Development Brief

This file is the authoritative project brief for human contributors and any coding assistant used during development. It describes how to build Aegis. It does **not** authorize or require an AI/LLM integration in the Aegis product.

## Mission

Build **Aegis Modernization Engine**, an offline-first, deterministic software engineering platform that discovers legacy systems, reconstructs evidence-backed knowledge about their architecture and data, designs target states, plans migrations, performs controlled transformations, and validates outcomes.

First end-to-end modernization scenario:
- Source: Java 8, legacy Spring, Oracle, AngularJS
- Target: a supported modern Java release, compatible Spring Boot release, PostgreSQL, React

Do not hard-code target version assumptions. Establish and test a compatibility matrix before selecting exact versions.

## Absolute non-negotiable constraint: no AI/LLM in the product

Aegis runtime MUST NOT connect to OpenAI/ChatGPT APIs, Ollama or any local model server, any hosted or locally installed LLM, embedding model, AI model, autonomous agent or inference provider. Do not add AI provider interfaces, model clients, prompt execution, embeddings, vector databases for semantic model retrieval, or network fallbacks. Do not describe Aegis as an LLM agent.

Build intelligence from parsers and compiler front ends; AST and symbol analysis; SQL and ORM analysis; document text/structure extraction; deterministic rules and pattern matching; graph algorithms; explicit compatibility matrices; templates and transformation recipes; test and validation engines; human review for ambiguity.

Aegis can orchestrate workflows, but orchestration is deterministic. Never claim human-like understanding of arbitrary free-form prose. When information is missing or ambiguous, create a review task or ask a precise question.

## Product capabilities

1. Accept one or many inputs: data models, DDL, database metadata, ERDs, documents, JSON/YAML/XML, source repositories, build files, API contracts, tests, infrastructure, sanitized logs and manual forms.
2. Support single-input, pairwise, bundle and incremental intake in any order.
3. Maintain an immutable artifact registry and versioned project snapshots.
4. Normalize facts into a Unified Project Knowledge Model (UPKM).
5. Preserve evidence/provenance for every extracted or inferred fact.
6. Detect conflicts and missing information across sources.
7. Discover languages, frameworks, modules, dependencies, APIs, integrations and data access.
8. Build physical, logical and target data models; infer candidate relationships only with traceable evidence.
9. Produce explainable architecture decisions and migration plans.
10. Apply controlled, reviewable, deterministic code/data transformations.
11. Validate compilation, tests, API compatibility, behavior and data reconciliation.
12. Produce reports and migration artifacts.

## Required engineering behavior

- Read the relevant documentation and schemas before implementation.
- Keep the domain model independent from parser libraries, storage vendors and UI.
- Use ports/interfaces at stable boundaries; avoid premature micro-modules.
- Prefer cohesive modules and a working vertical slice over empty scaffolding.
- Never overwrite original input artifacts.
- Every generated artifact must identify the transformation/rule/template version and its inputs.
- Every inferred fact must be marked as inferred; never promote it to confirmed without user confirmation or direct evidence.
- Preserve conflicting evidence and create a conflict/review item.
- Make ingestion idempotent and incremental.
- Report unsupported constructs and parser coverage.
- Never execute untrusted source code, SQL, scripts or build plugins during discovery.
- Any execution-based validation must be isolated and explicitly approved.
- Treat secrets and personal data as sensitive; redact from logs and reports.
- Do not transmit source code or project metadata externally.
- Tests are mandatory for parsers, normalization, evidence, conflict resolution and transformations.

## Input contract

Authoritative initial contract:
- `docs/Aegis-Input-Specification.md`
- `schemas/project-intake.schema.json`
- `schemas/evidence.schema.json`
- `examples/minimal-project.json`

Do not break schema compatibility casually. Version schema changes, provide migration notes and add fixtures for valid and invalid payloads.

## Core conceptual model

The UPKM should represent at least:
- Project, intake batch, source artifact and technology profile
- Application, system boundary, module, component and source symbol
- API, endpoint, message contract and integration
- Data store, schema, table, column, key, constraint and index
- Logical entity, attribute and relationship
- Business capability, business rule and process
- Dependency, job and quality attribute
- Migration mapping and transformation
- Evidence, finding, conflict, review task, decision and validation result

Keep physical database facts distinct from logical business concepts and target database design.

## Evidence requirements

For each fact retain stable fact reference; source artifact or manual-input origin; file/line/column, JSON pointer, database object, page/section or other locator; parser/extractor and version; rule ID/version where applicable; evidence kind (direct, user-confirmed or inferred); confidence category (high/medium/low/not applicable) and rationale; review status; analysis run and timestamp.

Confidence is categorical unless a statistically calibrated score is implemented and documented. Do not invent probabilities.

## Architecture boundaries

Suggested logical areas: Project and Intake; Artifact Storage; Parser SPI and format adapters; UPKM/Domain; Evidence and Conflict Registry; Analysis Orchestrator; Legacy Discovery; Data Intelligence; Architecture Analysis; Decision and Migration Planning; Transformation Workbench; Validation and Reconciliation; Reporting; CLI/UI (later).

Potential technologies are options, not mandates. Java, Maven, JavaParser/Eclipse JDT, JSqlParser, Apache Tika, JGraphT, JSON Schema validation, JavaPoet, JUnit 5 and Testcontainers may be evaluated. Verify license, security, compatibility and offline packaging before adoption.

## Development workflow

For each feature:
1. State the requirement and acceptance criteria.
2. Identify affected domain concepts and schemas.
3. Design the smallest coherent implementation.
4. Implement parser/adapter and domain mapping separately.
5. Add positive, negative, malformed and edge-case fixtures.
6. Add unit and integration tests.
7. Run the relevant test suite and static checks.
8. Document supported syntax, limitations and version coverage.
9. Provide a concise implementation report with actual test results.

Do not claim a feature is complete merely because code was generated. Completion requires successful verification.

## MVP priorities

1. Project intake, schema validation and artifact registry
2. UPKM core and evidence model
3. SQL DDL parser (initial Oracle/PostgreSQL subset)
4. Java/Maven discovery
5. Data relationship and lineage analysis
6. Cross-source conflict detection
7. Architecture and data model reports
8. Target model and migration mapping
9. Deterministic transformation recipes
10. Validation and data reconciliation

## Definition of done

A feature is done only when requirements and limitations are documented; code is implemented with clear boundaries; automated tests cover expected and failure cases; tests have actually been run and pass; no AI/LLM/model dependency has been introduced; unsupported behavior is not represented as certain; evidence and source traceability are preserved; and the change is reproducible and reviewable.

When uncertain, stop and document the uncertainty or ask for a decision. Do not silently invent requirements.
