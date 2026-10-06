# Checked legacy MySQL provider adapter

Pinned AdvancedCore comparison:
`6390c1cab41bd4d7683c7df88dd36537c8c7861e`.
This prerequisite does not complete MysqlUserBackend or its factory constructor.

Inspection of SimpleAPI 0.0.7 ConnectionManager bytecode proves that its legacy
getConnection method may reopen a pool, print an SQL exception, reopen again and
return null. The shared JDBC path must receive a checked acquisition failure;
closed platform-owned state must not be revived by this adapter.

`SqlUserBackendFactory.existingMysqlUser` adapts an existing MySQL/MariaDB wrapper.
It borrows directly through javax.sql.DataSource and rejects missing/closed pools,
missing managers and null connections. It neither calls legacy getConnection/open
nor closes/disconnects the pool. Acquisition SQLException reaches the JDBC facade
without replacement or retry. Caller-owned lifecycle admission, schema validity,
transaction-compatible driver configuration and native cache fencing remain
required. PostgreSQL is rejected for this legacy provider; an explicit opener
through existingUser is the distinct supported generic JDBC boundary.

Directly naming the legacy getter's Hikari return class fails compilation with
actual Java 8: that class is major version 55 in SimpleAPI's compile-time jar.
The fork already replaces it with its verified Java 8 pool when shading. The
adapter therefore reflects exactly the fixed public getDataSource getter, then
uses the standard DataSource interface. Reflection failure is a checked visible
error, not a fallback to the unsafe legacy acquisition method. No arbitrary
reflection target, configuration property or production dependency is added.

Six artifact regressions load the actual packaged Java 8 pool and prove successful
borrow/connection cleanup without pool retirement, closed/missing pool rejection
without reopen, exact SQL acquisition failure/no retry, and missing manager/null
borrow failure, visible getter failure and rejected PostgreSQL selection. They do not prove live MySQL transactions, native cache ownership
or complete migration safety. Full MySQL backend/schema and shared runtime work
remain outstanding; the root reward recovery objective is not complete.

The framework investigation source is an immutable Git object from SimpleAPI
`6ffe511055861a22dacbef7da5010f2a54033daa`, retained as workspace evidence. That
external snapshot is investigation material, not the pinned AdvancedCore comparison
or a claim that mutable 1.0.2-SNAPSHOT is reproducibly identified by it. No original
SimpleAPI checkout or main/reference checkout was changed.

## Verified candidate

Actual Temurin 8u504, workspace-local Maven repository and temporary directory:
`mvn -B -f AdvancedCore/pom.xml -Dmaven.resolver.transport=wagon
-Dmaven.repo.local=<workspace>/.m2/repository
-Djava.io.tmpdir=<workspace>/runtime/tmp clean install` passes 711 unit and 78
artifact tests (789 total), with no failures, errors or skips.
The installed artifact matches the producer SHA-256:
`045790d5e991b872dd36fb5f7f2181b29a3aefe2433b77247a7d9bb317d74f6a`.
Dependent VotingPlugin's `clean verify` with the same Java/Maven isolation flags
passes 45 unit and one artifact test (46 total). Its candidate SHA-256 is
`4008f234e16f32cf577b9f0f10b58fe759054e113500ccaa835d61e11cab4a4c`.
Both final jars have maximum base-class version 52 (1867 AdvancedCore and 2484
VotingPlugin classes). No production SQLite driver is added.

Actual Java 8/Spigot 1.8.8 acceptance against that exact VotingPlugin jar passes
10 public shared-SQLite factory/transaction/lifecycle checks and nine native
schema/cache registration checks. These protect the unchanged SQLite and native
paths; they do not establish live MySQL pool or root reward crash acceptance.
Workspace evidence: `mysql-checked-build-results.json`,
`mysql-checked-factory-clean-install-final.log`,
`mysql-checked-consumer-clean-verify.log`,
`mysql-checked-shared-sqlite-runtime.log`, and
`mysql-checked-native-runtime.log` in the isolated evidence directory.
The recorded 167 original checkout statuses/branches/heads are unchanged.
