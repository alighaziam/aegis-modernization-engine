# Production Readiness

Aegis is an engineering tool with deliberately bounded parsers and transformation coverage. A successful build does not establish that a customer migration is safe.

## Before using a customer project

- Record the source and target system owners, scope, maintenance window and rollback owner.
- Use sanitized project artifacts. Never ingest secrets, credentials, live personal data or unredacted production logs unless the organization has explicitly approved that data handling.
- Keep source artifacts immutable and retain their hashes.
- Use a dedicated database account with catalog/metadata read privileges only. Do not rely on JDBC `Connection.setReadOnly` as an authorization boundary.
- Supply JDBC credentials through environment variables or a managed secret store. Do not put secret values in command-line arguments, repository files or logs.
- Verify the exact database vendor/version, schema scope and supported parser subset.

## Before applying a generated transformation or migration

- Review every diagnostic, inferred fact, conflict, unsupported construct and manual-review item.
- Verify framework/runtime compatibility against an approved version matrix.
- Review generated source diffs and SQL with application and database owners.
- Back up source code, schemas and data; rehearse restore and rollback.
- Execute changes only in an isolated non-production environment first.
- Run compilation, unit/integration tests, API contract checks, data reconciliation, security tests and performance tests.
- Compare source/target row counts, keys, required-column nulls and foreign-key integrity for every approved migration batch.
- Obtain explicit approval before production execution and preserve an audit trail of inputs, rules, generated artifacts, approvals and outcomes.

## Release gates

- CI build and tests pass on the supported JDK/Maven toolchain.
- CodeQL and dependency review have no unresolved high-severity findings.
- Supported syntax, dialects and limitations are documented.
- Secrets are absent from artifacts, logs and test fixtures.
- Backup, rollback, observability and incident-response procedures are tested.
- A release version and license have been explicitly selected.

## Current limitation

Aegis generates reviewable plans and bounded transformations. A guarded reconciliation executor can run only planner-generated scalar COUNT SELECT statements; it does not execute DDL or data writes. It does not yet provide a universal, fully automated, production-safe migration executor. Human review and external validation remain mandatory.


## Containerized CLI deployment

A hardened container option is available for deterministic offline CLI workflows. The image uses a Java 25 JRE runtime, runs as UID 10001, has a read-only root filesystem, drops all Linux capabilities, enables `no-new-privileges`, and exposes no network listener. Docker Compose mounts only `./workspace` as writable and applies CPU/memory limits. Keep customer inputs and reports in that workspace; make the host directory writable by UID 10001. Oracle connectivity remains an explicit deployment concern and must use environment-based credentials plus an approved network policy.

The container does not change the product's safety boundary: Aegis still does not execute DDL or data writes. `parse-ddl-sarif` exports SQL DDL parser diagnostics as SARIF 2.1.0; it does not upload results to GitHub and does not convert review diagnostics into approvals.
