# Aegis persistence and database portability

## Two independent database concerns

Aegis has its own operational metadata database, and it analyzes/migrates databases belonging to customer systems. These must never be conflated.

- **Aegis internal persistence:** Oracle Database 19c+ is the required operational database for Aegis deployments.
- **Customer source/target databases:** vendor-neutral project metadata. The source and target database vendors are selected per modernization project and are not required to be Oracle.
- **Core domain:** physical database objects retain their source/target vendor identity, while logical entities and relationships remain database-independent.

The internal Oracle implementation is behind the `ProjectRepository` port. Analysis engines, parsers and transformation planners depend on the port/domain model, never on Oracle JDBC classes. The current Oracle adapter stores append-only UPKM snapshots as JSON CLOBs and maintains relational project/run metadata. It does not replace the immutable content-addressed artifact store or evidence/conflict journals yet.

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
```

`store-intake` persists the initial project model created from the intake envelope. Later analysis runs create additional immutable snapshots for the same project. `load-model` returns the latest snapshot. Database failures never print credentials. Configure Oracle TLS/wallet and network policy at deployment level.

## Customer database portability

The internal Oracle JDBC connection is not reused for customer databases. Customer database discovery and migration use explicit source/target dialect adapters. A project can target Oracle, PostgreSQL, SQL Server, MySQL, or another dialect once the corresponding tested adapter and transformation rules are installed. Unsupported dialects and syntax must be reported as unsupported; the engine must not silently map them to Oracle or PostgreSQL.

Current SQL parser/planner coverage remains limited. Oracle and PostgreSQL have initial DDL parsing/planning support, not full grammar or data migration support. Adding a target dialect requires fixtures, mappings, diagnostics, round-trip/validation tests and documented unsupported syntax.
