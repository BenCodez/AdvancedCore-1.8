# Java 8 shared JDBC user storage

Pinned comparison: `6390c1cab41bd4d7683c7df88dd36537c8c7861e`.
Initial storage implementation: `1e18b18626cf60a42eb8084242216f1d9c654011`.

The package-private JDBC implementation is adapted from the pinned main source
while preserving caller identity, schema key canonicalization, quoted identifiers,
physical boolean representations, prepared binding, bulk transaction atomicity,
row locking and caller-owned transaction extensions. No automatic retries are
introduced. SQL/runtime failures before commit propagate after rollback; failures
restoring auto-commit or closing after a successful commit are reported as cleanup
warnings rather than inviting replay of the committed operation. A failed rollback
never enables auto-commit on a potentially partial batch.

Java 11 String.strip/isBlank calls use code-point Character.isWhitespace logic,
including Unicode whitespace. The internal fromDbType selector is not copied:
SimpleAPI 0.0.7 has no DbType. Internal constructors receive an explicit dialect;
MySQL, SQLite and PostgreSQL SQL/binding behavior is retained. A later factory
adapter must use validated fork configuration, without pretending that absent
modern SimpleAPI configuration APIs are available. No dependency upgrade is made.

Pinned write-outcome, ambiguous canonical-column, PostgreSQL bit binding and
review regressions are retained with Java 8 test syntax. Additional tests execute
real SQLite 3.7.2 transactions using the existing test dependency, explicitly
loading its driver as required by that legacy version. They prove a receipt and
user write commit together, both roll back on caller failure, existing values
survive rollback, creation seeds do not replace an existing row, escaped scopes
reject use, quoted tables and Unicode boolean representations work, and bulk
identity metadata cannot replace the caller UUID.

This class is not yet wired into the native cache or exposed through a factory.
Cache flush/reconciliation, per-user ownership, backend lifecycle admission,
physical schema initialization and factory integration remain required. These
isolated SQL transactions do not prove reward replay is exactly-once, nor that
native cached writes and caller SQL are coordinated. PostgreSQL/MySQL mocks
establish binding/order contracts only, not live driver or server acceptance.

Validation for this candidate: actual Java 8 focused tests 35 PASS; AdvancedCore
`clean install` 683 unit + 72 artifact = 755 PASS; VotingPlugin `clean verify`
against the exact locally installed producer 45 unit + 1 artifact = 46 PASS.
Workspace-local Maven repository and temporary directory flags are used as
documented in the build guide. Both artifact base-class maxima are 52. Evidence
lives under the isolated workspace in `evidence/jdbc-foundation-*.log` and
`evidence/jdbc-foundation-build-results.json`. Live Spigot evidence is kept
separate: native cache/schema acceptance does not exercise this unwired class.
