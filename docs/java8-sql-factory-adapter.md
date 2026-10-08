# Explicit SQL factory and legacy provider boundary

Pinned AdvancedCore reference:
`6390c1cab41bd4d7683c7df88dd36537c8c7861e`.
Factory introduction: `fe71042d8c128b4b0231019a8a2ba065ea7e4f03`.
Caller transaction extension: `b0328c32e0c92270b23ab5a4e95a821d031209c0`.
Both ledger entries remain partial pending their other implementation obligations.

`SqlUserBackendFactory.sqlite` preserves the explicit typed key/schema creation
path. `existingUser` adapts a caller-owned connection opener to the shared JDBC
storage implementation without selecting a pool, changing configuration,
reconciling a table, creating an executor or replacing the native cache.
Constructing existingUser does not connect. The provider must supply a fresh usable connection
for each operation and preserve platform lifecycle admission. Native cache
ownership, flush/reconciliation and generation fencing remain the caller's work;
this API alone does not implement them.

Legacy SimpleAPI 0.0.7 has no DbType. A new core-only DatabaseType enum explicitly
selects MYSQL/MARIADB (MySQL SQL/binding) or POSTGRESQL. SQLite does not require a
database type. Non-SQL UserStorage values are rejected before borrowing. This
Java 8 adaptation does not introduce fake SimpleAPI classes or alter existing
configuration keys, defaults, schemas, serialized data or transport payloads.
The modern-main DbType method signature is not offered by this fork; callers must
use the typed core boundary. Existing 1.8 APIs remain intact.

Inspection of the exact legacy dependency binary also confirms that it lacks
AbstractSqlTable, MysqlConfig.isDebug, ConnectionManager.getDbType and
getConnectionChecked. The modern headless MysqlUserBackend therefore cannot be
copied and assumed compatible. Its owned-pool constructor, schema reconciliation,
retained-type migration and factory mysql constructor remain incomplete. A
compatible adapter must preserve checked acquisition, pool ownership, physical
schema safety and native per-user synchronization, without a broad SimpleAPI
upgrade or silent fallback.

Four focused factory regressions prove lazy construction, real SQLite commit and
rollback through a borrowed provider without retiring its owner, explicit dialect
quoting/binding, rejected unsupported inputs, and acquisition failure without a
retry or replaced SQL cause. Together with the existing real transaction suite:
8 PASS on actual Java 8. MySQL/PostgreSQL dialect checks use mocked JDBC; no live
MySQL/PostgreSQL pool acceptance is claimed for this factory.

The manual Spigot shared SQLite fixture now constructs both generations through
the factory. Run `AdvancedCore/src/test/runtime/run-shared-sqlite.py` with the
isolated workspace argument after the exact producer/consumer builds, as documented
in java8-shared-sqlite-backend.md. This is not proof of native cache integration
or outer reward recovery. Full goal and fresh final independent review remain
incomplete.

Exact candidate validation: actual Java 8 producer `clean install` 711 unit +
72 artifact = 783 PASS; VotingPlugin `clean verify` against the exact locally
installed producer 45 unit + 1 artifact = 46 PASS. All packaged base classes
have major version at most 52. Logs and hashes are retained under the isolated
workspace in `evidence/sql-factory-*.log` and `sql-factory-build-results.json`.
