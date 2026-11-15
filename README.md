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

This repository currently establishes the product contract and initial architecture. Implementation milestones and supported format/version matrices will be added as they are verified. No runtime implementation is claimed by these documents.

## License

License selection is pending. Do not assume this project is licensed for reuse until a license file is added.
