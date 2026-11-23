# Standardized Progress Assessment

- Assessment date: 2026-11-22
- Assessed commit: `63a9778961dded8fc582730fbade32fadd872d60` on `main`
- Status: reassessed after parser expansion; category ratings are reviewer scores against the same rubric, not raw GitHub counters.
- Overall weighted score: **80%**

## Scoring rubric

| Dimension | Weight | Score | Weighted contribution |
|---|---:|---:|---:|
| Product capabilities (40%) | 75% | 30.00 |
| Tests and code quality (20%) | 90% | 18.00 |
| CI/CD and build (15%) | 100% | 15.00 |
| Security (10%) | 90% | 9.00 |
| Documentation (5%) | 90% | 4.50 |
| Deployment / production readiness (10%) | 35% | 3.50 |
| **Total** | **100%** | — | **80.00%** |

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
- PR #72 merged as `17f34de1abc8792d304e72486f11d84cb6cdaedc` after Build and Test (run 44685065328), CodeQL (run 44685065336), and OSV (run 44685065650) passed on head `32c3f6ed26f782029e759507ae10de08977d37d9`.
- Missing constraints with `IF EXISTS` now produce an idempotent no-op without a diagnostic or fabricated removal evidence; without `IF EXISTS`, the existing diagnostic remains.
- No overall score increase is claimed; the six rubric dimensions have not been fully reassessed.

## Daily repository audit — 20 November 2026

- Audited default branch main at 46d495f3195108928162ae6440c73e7baa261637.
- Latest Build and Test: [run 44713242162](https://github.com/alighaziam/aegis-modernization-engine/actions/runs/44713242162) — passed.
- Latest CodeQL: [run 44713242154](https://github.com/alighaziam/aegis-modernization-engine/actions/runs/44713242154) — passed.
- Latest OSV scan: [run 44713242828](https://github.com/alighaziam/aegis-modernization-engine/actions/runs/44713242828) — passed.
- No open pull requests were found. PR #72 is merged; its DROP CONSTRAINT IF EXISTS no-op behavior and regression are already on main.
- No progress percentage is changed: the full six-dimension rubric has not been reassessed. Real database execution, semantic validation, reversible migration execution, review workbench and production deployment remain release gates.
- The hourly PR Guardian file is present and now passes an explicit repository to gh; post-change scheduled execution is still being verified across all repositories.


## Latest verified increment — 22 November 2026

- Added static `ALTER TABLE ... RENAME COLUMN` parsing for the supported DDL subset.
- Successful renames preserve column metadata and update parsed constraint/index references; missing source columns and duplicate targets produce diagnostics without mutating the model.
- Added three regression tests for successful rename/reference updates, duplicate targets, and missing source columns.
- Build and Test passed on assessed commit `63a9778961dded8fc582730fbade32fadd872d60` ([run 44996567405](https://github.com/alighaziam/aegis-modernization-engine/actions/runs/44996567405)); CodeQL passed ([run 44996567412](https://github.com/alighaziam/aegis-modernization-engine/actions/runs/44996567412)).
- Parser remains static and does not execute DDL or perform a live database migration. Human review, broader semantic coverage, and real source/target database validation remain open.

## Latest merged increment — 23 November 2026

- PR #82 merged as `a98688c351a1946158c2ca2baf0f7bb6dde19a60` after Build and Test ([run 45152416117](https://github.com/alighaziam/aegis-modernization-engine/actions/runs/45152416117)), CodeQL ([run 45152416279](https://github.com/alighaziam/aegis-modernization-engine/actions/runs/45152416279)), and OSV scan ([run 45152416604](https://github.com/alighaziam/aegis-modernization-engine/actions/runs/45152416604)) passed on exact PR head `17556f43d70ad3d117c338ab0f8e85aef8e581bd`.
- The bounded static DDL parser now recognizes `DROP TABLE` and `DROP INDEX`, records evidence for successful removals, treats missing objects with `IF EXISTS` as idempotent no-ops, and refuses to remove a parsed table when another parsed table has a foreign-key reference to it.
- Added regression tests for successful table/index drops, dependency protection, missing-object diagnostics and `IF EXISTS`.
- This is static analysis only; no DDL is executed and no production migration is claimed. The 80% score above remains the last full six-dimension assessment; this increment does not by itself justify a new overall score.
