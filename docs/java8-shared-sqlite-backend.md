# Shared SQLite backend on Java 8 / Spigot 1.8.8

Pinned reference: `6390c1cab41bd4d7683c7df88dd36537c8c7861e`.
Upstream introduction: `6de19008339894154fb540d9d46c217475e2d50c`.

The additive `SqliteUserBackend` keeps registered schema declarations and existing
physical columns. Initialization creates a missing table, checks text-compatible
UUID identity and a whole-column uniqueness constraint, and adds only missing
registered columns. It does not replace retained values or rewrite operator
configuration. Existing data with ambiguous identity remains rejected rather
than guessing a migration.

Lifecycle admission uses the pinned fair read/write gate. Close stops new callers
before waiting for admitted reads, writes, transactions and enumeration to drain.
Retained user adapters cannot access a successor generation. Concurrent close,
interruption and a queued reader crossing close are covered by real SQLite tests.
An operation cannot close its own backend and deadlock on its read hold.
Enumeration uses 512-row pages, advances past malformed UUIDs, reports one bounded
malformed-row diagnostic, streams larger tables, and rejects materialized lists
above 100,000 users. No second user cache or executor is introduced.

Java 8 adaptations replace the private cursor record with an immutable pair and
reuse the code-point whitespace helper for String.strip/isBlank behavior. A real
SQLite 3.7.2 reproduction demonstrated two required JDBC adaptations:

- `PRAGMA index_list` has no `partial` field. Missing metadata is accepted only
  after querying the same connection and proving a known SQLite 3.0–3.7 engine.
  SQLite documents partial indexes as introduced in 3.8.0:
  <https://www.sqlite.org/partialindex.html>. Unknown/new/malformed versions and
  null partial metadata do not prove a full index. Engine-query failure propagates.
- For an empty index list, the legacy driver reports no result columns.
  `execute()` distinguishes that successful empty response from an SQL failure;
  it remains insufficient UUID uniqueness evidence. Exceptions are not swallowed.

The default test dependency remains SQLite 3.7.2, matching Spigot 1.8.8.
`-Dsqlite.test.version=3.42.0.0` selects an additional test-only driver. Both
focused suites exercise the same identity, persistence, close, and bounds tests;
with the newer engine an actual partial UUID index is created and rejected. The
legacy engine instead proves that it cannot create one and that an unconstrained
UUID table is rejected. No production dependency changes or simultaneous upgrade
is required.

Focused command (actual Java 8, workspace-local Maven repository and temporary
folder flags as documented in java8-sync.md):

```
mvn -B -f AdvancedCore/pom.xml -Dtest=SqliteUserBackend*Test,LegacySqliteIndexEvidenceTest,LegacySqliteEnumerationTest test
```

Repeat with the test-only version override for the newer engine. Both runs:
24 PASS, zero failures/errors/skips. The initial valid-unique-index failure and
empty-index diagnosis are retained in isolated workspace evidence.

Manual runtime fixture: `AdvancedCore/src/test/runtime/run-shared-sqlite.py` and
`SharedSqliteAcceptance.java`. After building the exact paired VotingPlugin jar:

```
python3 AdvancedCore/src/test/runtime/run-shared-sqlite.py /workspace/votingplugin-1.8-port-workspace
```

The fixture requires the workspace Java 8 JDK, Spigot 1.8.8 jar, Python/PyYAML,
free loopback port 47237 and isolated runtime/evidence directories. It compiles
an acceptance plugin against the exact shaded candidate, starts an isolated server,
and performs database work on a worker. It checks the actual server SQLite engine,
atomic user/receipt commit and rollback, expired scopes, close/reopen identity and
serialized values, independent SQLite integrity, and clean linkage/shutdown.
It never runs against an operator server; generated classes/jars/worlds/databases
remain under the isolated runtime directory and must not be committed.

The native Bukkit cache does not yet select this backend. MySQL/factory adapters,
shared cache ownership, checkpoint notification generations and cached transaction
reconciliation remain required. Backend SQL receipt tests do not prove complete
reward replay or exactly-once external effects.

Candidate full validation: actual Java 8 producer `clean install` 707 unit +
72 artifact = 779 PASS; exact installed producer consumed by VotingPlugin
`clean verify` 45 unit + 1 artifact = 46 PASS. Both packaged base-class maxima
are 52; neither jar bundles a SQLite driver. The final shared backend runtime
fixture passed 10 checks on actual Spigot 1.8.8/SQLite 3.7.2. Logs and hashes:
`evidence/sqlite-backend-build-results.json`,
`evidence/sqlite-backend-shared-spigot-runtime-final.log`. Native cache acceptance
is a separate fixture; neither result proves shared cache/runtime integration.
