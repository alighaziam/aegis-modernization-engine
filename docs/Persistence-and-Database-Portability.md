# Aegis persistence and database portability

## Two independent database concerns

Aegis has its own operational metadata database, and it analyzes/migrates databases belonging to customer systems. These must never be conflated.

- **Aegis internal persistence:** Oracle Database 19c+ is the required operational database for Aegis deployments.
- **Customer source/target databases:** vendor-neutral project metadata. The source and target database vendors are selected per modernization project and are not required to be Oracle.
- **Core domain:** physical database objects retain their source/target vendor identity, while logical entities and relationships remain database-independent.

The internal Oracle implementation is behind the `ProjectRepository` port. Analysis engines, parsers and transformation planners depend on the port/domain model, never on Oracle JDBC classes. The Oracle adapter stores append-only UPKM snapshots as JSON CLOBs and maintains relational project/run metadata. Immutable artifact BLOB storage is added by `db/oracle/V002__aegis_artifacts.sql`. Append-only evidence and conflict event journals are added by `db/oracle/V003__aegis_knowledge_journals.sql`, through the vendor-neutral `EvidenceRepository` and `ConflictEventRepository` ports. Apply migrations in order: V001, V002, V003. The file-backed stores remain available for offline/local workflows; Oracle journal adapters are explicit persistence implementations and are not silently activated. The CLI exposes `store-evidence`, `list-evidence`, `store-conflict-event`, and `list-conflicts` so provenance and conflict lifecycle events can be written to and read from the Oracle journals. These commands require the project to exist in `AEGIS_PROJECTS`; duplicate IDs are rejected by Oracle primary keys.

## Oracle support

Supported internal database baseline: Oracle Database 19c or newer, using the Oracle Thin JDBC driver. Create a dedicated schema/user and grant only the required table/index privileges. Execute `db/oracle/V001__aegis_core.sql` as that schema owner before connecting Aegis. The application intentionally does not auto-create or alter production database objects.

Set environment variables (never commit credentials):

- `AEGIS_ORACLE_JDBC_URL`, e.g. `jdbc:oracle:thin:@//dbhost:1521/AegisPdb`
- `AEGIS_ORACLE_USER`
- `AEGIS_ORACLE_PASSWORD`
- Optional `AEGIS_ORACLE_POOL_SIZE` (default 5; range 1..50)
- Optional `AEGIS_ORACLE_CONNECTION_TIMEOUT_MS` (default 10000)

Commands:

```bash
java -jar target/aegis-modernization-engine-0.1.0-SNAPSHOT.jar db-check
java -jar target/aegis-modernization-engine-0.1.0-SNAPSHOT.jar store-intake ./project-intake.json
java -jar target/aegis-modernization-engine-0.1.0-SNAPSHOT.jar load-model <project-id>
java -jar target/aegis-modernization-engine-0.1.0-SNAPSHOT.jar store-artifact <project-id> <artifact-id> <category> <format> <origin> <file>
```

`store-intake` now persists the project metadata, initial UPKM snapshot, and every currently supplied artifact in one Oracle transaction. Artifact paths are resolved relative to the intake JSON file, constrained to that directory (including symlink resolution), checked against declared size/SHA-256, and streamed into Oracle BLOBs with a 100 MiB per-file limit. A duplicate artifact ID or any invalid supplied file rolls back the entire intake transaction. Artifacts with no location or not yet present are reported as pending (exit code 3); they are not treated as successfully stored. Later analysis runs create additional immutable snapshots for the same project. `load-model` returns the latest snapshot. `store-artifact` remains available for later immutable artifact additions. Artifact identifiers are unique within a project; duplicate inserts are rejected rather than overwriting historical input. Database failures never print credentials. Configure Oracle TLS/wallet and network policy at deployment level.

## Customer database portability

The internal Oracle JDBC connection is not reused for customer databases. Customer database discovery and migration use explicit source/target dialect adapters. A project can target Oracle, PostgreSQL, SQL Server, MySQL, or another dialect once the corresponding tested adapter and transformation rules are installed. Unsupported dialects and syntax must be reported as unsupported; the engine must not silently map them to Oracle or PostgreSQL.

The dialect registry currently recognizes Oracle, PostgreSQL, MySQL, MariaDB, SQL Server and DB2. Recognition and common-subset parsing do **not** mean full vendor grammar or migration support. The current parser handles a conservative CREATE TABLE/INDEX subset plus ordered ALTER TABLE ADD/DROP/RENAME operations, PostgreSQL ALTER COLUMN type/nullability/default changes, and Oracle MODIFY column type changes with optional nullability/default clauses. PostgreSQL USING expressions are recorded as review-required and are never evaluated. Vendor-specific statements (for example PL/SQL, T-SQL batches, MySQL engine clauses, partitioning, procedural objects and vendor-specific index options) remain unsupported diagnostics unless a dedicated parser rule is added and tested.

The DDL migration CLI accepts an explicit source and target dialect (`plan-ddl-migration <schema.sql> <source-dialect> <target-dialect> <target-schema>`). A conservative common-subset planner is routed for every pair among the six registered dialects, while Oracle-to-PostgreSQL retains a specialized planner. This is a capability to emit review-required DDL for supported constructs, not a claim of complete vendor grammar, lossless conversion or semantic equivalence. The adapters deliberately omit incomplete tables and unsupported types, and surface defaults, identity/sequence behavior, vendor extensions and other ambiguous semantics for human review. No generated SQL is executed. The legacy Oracle-to-PostgreSQL CLI form remains accepted. There is no live customer-database connector, data-copy engine or automated data reconciliation yet. Use `list-db-dialects` to inspect the declared parser/planner capability registry. Expanding coverage requires dedicated positive, negative and edge-case fixtures, type/constraint mappings, diagnostics and validation tests.
