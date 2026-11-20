# Standardized Progress Assessment

- Assessment date: 2026-11-19
- Baseline commit: `f483dc25f34a35e6538438d85f8e9f2dabf8f152` on `main`
- Status: evidence-based baseline; category ratings are reviewer scores against the rubric below, not raw GitHub counters.
- Overall weighted score: **77%**

## Scoring rubric

| Dimension | Weight | Score | Weighted contribution |
|---|---:|---:|---:|
| Product capabilities (40%) | 70% | 28.00 |
| Tests and code quality (20%) | 85% | 17.00 |
| CI/CD and build (15%) | 100% | 15.00 |
| Security (10%) | 90% | 9.00 |
| Documentation (5%) | 90% | 4.50 |
| Deployment / production readiness (10%) | 35% | 3.50 |
| **Total** | **100%** | — | **77.00%** |

Formula: overall progress = sum(category score × category weight). A category score is based on implemented scope and its verification evidence. Documentation alone does not count as implementation. A green workflow proves only the checks included in that workflow; it does not prove production readiness.

## Evidence checked

README describes working deterministic CLI, UPKM, Java/SQL/JPA/OpenAPI analysis, lineage, planners, guarded transformations and Oracle-backed snapshots. Production Readiness doc defines manual approval and release gates. Build/Test, CodeQL and OSV scheduled scan passed 2026-11-19.

- Latest CI/build evidence: https://github.com/alighaziam/aegis-modernization-engine/actions/runs/44570255102
- Latest CodeQL/security workflow evidence: https://github.com/alighaziam/aegis-modernization-engine/actions/runs/44570255200

## Remaining release gates

Broader parser and semantic coverage; human review workbench; reversible end-to-end code/schema/data migration execution; validation against real source/target database engines; operational deployment and release/license selection. Generated plans are not production migration execution.

## Rules for future updates

1. Recalculate the same six dimensions with the same weights; do not change the denominator between reports.
2. Mark a capability complete only when implementation exists and the relevant automated test or reproducible manual check passes.
3. Treat missing, skipped, or unavailable checks as unverified, never as passed.
4. Keep production-only integrations, external services, deployment validation and manual QA separate from local mocks/sandboxes.
5. Record the assessed commit SHA and link the exact workflow run used as evidence.
6. Update this file when evidence changes; do not infer progress from commit count.

This is a consistent engineering assessment baseline, not a claim that a single objectively exact percentage exists independent of the scoring rubric.
\n\n## Latest merged increment — 19 November 2026\n\nIncremental Oracle/PostgreSQL ALTER TABLE ADD COLUMN parsing (including parenthesized multi-column additions) merged in PR #69; Build/Test, CodeQL, and OSV PR scan passed.\n

## Development checkpoint — 20 November 2026

- Added static parsing for `ALTER TABLE ... DROP CONSTRAINT [IF EXISTS] name` in the supported SQL DDL subset.
- Successful drops update the in-memory table model and emit direct evidence; unknown constraints produce an explicit review diagnostic without changing the model.
- Regression tests cover successful removal/evidence and unknown-constraint non-mutation. Parsing remains static and does not execute database DDL or perform a migration.


## Latest merged increment — 20 November 2026

- PR #70 merged as `c2b249420ecf0211ced3b7dc49b20a67342e4294` after Build and Test, CodeQL, and OSV PR Scan passed on head `be6caf39c4496acde89deae4c5aea7004c594e2a`.
- Static `ALTER TABLE ... DROP CONSTRAINT [IF EXISTS]` parsing now updates the model on known constraints and emits review diagnostics without mutation for unknown constraints. This does not execute database DDL.


## Development checkpoint — 20 November 2026

- Added static parsing for `ALTER TABLE ... DROP COLUMN [IF EXISTS]` in the supported DDL subset.
- A known unreferenced column is removed from the in-memory model and emits direct evidence. Missing columns are diagnosed unless `IF EXISTS` is present.
- A column referenced by a parsed constraint or index is retained and produces `DROP_COLUMN_DEPENDENCY`; this conservative behavior avoids silently creating an internally inconsistent model.
- Regression tests cover successful removal/evidence, dependency protection, and `IF EXISTS` handling.
- The initial CI failure exposed a foreign-key local-column extraction ambiguity; the parser was corrected to extract the local column list explicitly. Dependency checks also include indexes parsed earlier in the same DDL input, with a dedicated regression.
- PR #71 merged as `298de356b980c4e707dd0f21ffd1505332c7e79e` after Build and Test, CodeQL, and OSV passed on head `913c89be0d9fefd7def723dfadd04eff17c4aec6`.
- No overall score increase is claimed; the six rubric dimensions have not been fully reassessed.


## Development checkpoint — 20 November 2026

- Fixed the supported `ALTER TABLE ... DROP CONSTRAINT IF EXISTS` path so a missing constraint is treated as an idempotent no-op rather than emitting `DROP_CONSTRAINT_NOT_FOUND`.
- Added a regression test asserting the table remains unchanged, no diagnostic is emitted, and no removal evidence is fabricated when the named constraint is absent.
- PR validation is pending; no verification or progress-score increase is claimed before CI, CodeQL and OSV pass on the exact PR head.
