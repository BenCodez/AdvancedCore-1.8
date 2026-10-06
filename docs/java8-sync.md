# Java 8 / Spigot 1.8 synchronization

This is a behavior-aware compatibility backport, not a replacement with current main.
The legacy public packages, configuration defaults, user data formats and proxy payloads
remain in use. It does not claim feature parity with main.

## Immutable comparison inputs

| Repository | Clean 1.8 branch start | Main comparison |
| --- | --- | --- |
| AdvancedCore | `d4a8f667d91e7121a5e0929a0e16cd615556f00d` | `6390c1cab41bd4d7683c7df88dd36537c8c7861e` |
| VotingPlugin | `1b1e6d7d47a3d0af2ad5ede79ae3436c7aaddf73` | `834bcb84a59b6da40640eac83c20fd97ad1d64c9` |

The forks contain independently copied/adapted releases, rather than a continuous main
ancestry. The last represented releases are AdvancedCore 3.7.17
(`9e89ad0bfe4a0472f2fb01737546b024f4be87f0`) and VotingPlugin 6.18.7
(`caa70583638dcfebce67c0a0019daf1cd16402b5`). The retained
`VotingPlugin/src.main.java` tree is not an active Maven source root; it was not deleted.

## Dependency and platform decisions

Build with an actual Java 8 JDK. Test and production dependency base classes must be
compatible with Java 8. Artifact tests inspect the actual shaded jar and independently
load its Hikari pool and reflective scheduler classes; a compiler target alone is insufficient.
Java 8 ignores multi-release entries under `META-INF/versions/`.

The unavailable SimpleAPI `0.0.7-SNAPSHOT` is replaced by the fixed `0.0.7` release.
Its modern pool/Folia classes are excluded, and the existing Java 8 HikariCP 3.4.1 is
used. SLF4J API and binding are both 1.7.36. Minimized shading is disabled because it
removed reflectively selected scheduler implementations. A narrow source compatibility
bridge retains the old `GlobalMessageProxyHandler` ABI and existing ArrayList payload
behavior. SimpleAPI changed that ABI in `9cbde768823d09542f6909de86169a10d6360ea0`;
the bridge uses the old contract at `088b751bdc3ed50b74315aa64c4308a83288ca6a`.
It does not introduce the modern global-message wire format.

VotingPlugin shades the exact locally installed AdvancedCore jar, rather than adding its
raw transitive libraries again. This prevents Java 11/17 classes from reappearing downstream.

Bukkit and Bungee entry points are retained. Velocity sources are preserved but excluded
from the Java 8 artifact, including its descriptor and platform-specific adapters.
Although the cached Velocity API itself targeted Java 8, its 3.1.2 runtime targeted Java 11
at `ffa4c95435d1348d094d8a740ecae581c166f95b`. Current Velocity support is therefore
not claimed. The Java 8 Bungee acceptance runtime is archived Jenkins build 1485
(which reports git runtime build 1484); current build 2102 contains incompatible base classes.
This does not imply support for a current Bungee runtime on Java 8.

## Reproducible local build

Use a workspace-local Maven repository, separate from normal operator development caches:

```sh
export JAVA_HOME=/path/to/jdk8
export PATH="$JAVA_HOME/bin:$PATH"
mvn -B -f work/AdvancedCore-1.8/AdvancedCore/pom.xml \
  -Dmaven.resolver.transport=wagon -Dmaven.repo.local="$PWD/.m2/repository" clean install
mvn -B -f work/VotingPlugin-1.8/VotingPlugin/pom.xml \
  -Dmaven.resolver.transport=wagon -Dmaven.repo.local="$PWD/.m2/repository" clean verify
```

The coordinated dependency is `com.bencodez:advancedcore-1.8:3.7.17_1.8`, built from
this exact checkout and installed into that repository immediately before VotingPlugin.
No `LATEST`, deployment, release metadata change, or publication is required.
Final artifacts are `AdvancedCore/target/AdvancedCore.jar` and
`VotingPlugin/target/VotingPlugin.jar` respectively.

## Configuration, data and acceptance limits

No replacement of operator configuration, schema rewrite, or new proxy payload format
is introduced. Added behavior supplies defaults through existing getters and preserves
legacy signatures. The SQLite migration regression uses JDBC 3.7.2, bundled with Spigot
1.8.8, and checks repeat application with existing rows.

Unmodified baseline builds failed on dependency retrieval: the removed SimpleAPI snapshot
for AdvancedCore and a dead Velocity repository/missing AdvancedCore artifact for VotingPlugin.
These are recorded baseline failures, not passing baseline builds. The isolated candidate was
subsequently tested with Temurin 8u504 and an actual BuildTools-built Spigot 1.8.8 server.
NuVotifier 2.7.2 initialized; provider test votes exercised offline persistence, delivery on login,
online processing and rewards. Disabled sites were neither recreated nor counted. `/vote`
opened its inventory; reload and graceful shutdown completed; SQLite integrity was `ok`.

The archived Bungee runtime enabled VotingPlugin on Java 8 against an isolated loopback
MariaDB 11.8.6 database using its bundled legacy MySQL JDBC driver. NuVotifier ingress,
PLUGINMESSAGING delivery and backend rewards succeeded. One offline vote survived a
graceful proxy shutdown/restart, delivered once on login, and cleared from the JSON cache.
A backend configured with the documented shared MySQL database read the same totals;
the fixture recorded three votes and three points. This tests MariaDB interoperability,
not a separate Oracle MySQL installation or every proxy transport.
PlaceholderAPI 2.11.6 registered the VotingPlugin expansion and resolved persisted totals
and points; Vault 1.7.3 registered its permissions hook. An economy provider is absent, so
economy rewards remain unverified. A broad historical-data upgrade matrix remains unverified. These limitations must not be represented as passed runtime tests.

Modern storage/backend abstractions, current authenticated proxy transports, Control management,
Folia/Paper/Adventure features and new duration/milestone configuration systems have not been
copied wholesale. Their callers, data migrations and dependencies require separate review.
The full comparison ledger remains incomplete; these changes must not be described as matching main.

## AdvancedCore behavior backports

- Bulk commands require both base and `.All` permission, with existing admin overrides and
  narrowly scoped legacy SetAllData aliases. Malformed metadata fails closed; bulk commands
  cannot fall through to a single-player write. Legacy constructor arguments are forwarded.
- Inventory repeat timers use the configured interval, and `isSlotTaken(int)` is additive.
  Upstream: `92cac8ecef36d428e68b3a60108727e26545eb95` and `ebd2d15971462d0086bf38b4863c896a49be7fb2`.
- Month recovery is restricted to the first twelve hours, preserving bypass behavior.
  Upstream: `0174e3c11c6d0f216e472b50f86e95abfabedabe`.
- SQLite added-column names are quoted; the misspelled public `addColoumn` API is retained.
  Upstream: `bd8bb2cb81ba5a1c73d0ca7fc84fdb4ed8a148cf` and
  `531590f93e42d734c87c6abacf93a8361435a182`.
- Directly defined rewards take precedence over same-name reward files. Mixed underscore/dot
  serialized subreward names resolve across all three lookup paths. Upstream: `1ded132a925b41a42bf36797ab704a90b8897325`
  and `69311a9c311ac8984ae3a52b7f007b61efcb5287` (full identities are in the change ledger).
- Failed MySQL deletes retain UUID/name caches; additive `deletePlayerStrict` reports the
  original failure. Legacy `deletePlayer` still reports SQL errors without throwing.
  Upstream: `2304bff3fa976b373ba5636aec3d6e4a02df1ae0`, adapted to the existing Query API.

Existing failure contracts elsewhere in the legacy storage layer have not been globally rewritten.
A strict delete API is not a claim that every legacy caller now uses strict storage operations.

Latest coordinated validation: AdvancedCore Java 8 `clean install`: **54 unit tests + 3 artifact
integration test**, all pass. VotingPlugin Java 8 `clean verify`: **11 unit tests + 1 artifact
integration test**, all pass, using that exact isolated AdvancedCore installation.

Legacy SQLite statements and result sets now close on success, early returns and errors,
without closing the shared connection or changing legacy failure returns. SQLite player-name
lookup and update values are now bound parameters, so apostrophes remain data and SQL-looking
names cannot select another player (upstream `0f5a0477a2d38fb70732a76e46e15be6d725fe31`).
The regression tests execute against SQLite 3.7.2 and failed against the previous implementation.
This is a local old-layout adaptation, rather than adoption of the modern storage abstraction.
AdvancedCore is a library artifact with no `plugin.yml`; runtime acceptance occurs inside
VotingPlugin, which shades it, rather than independent Bukkit installation.

## Additional Java 8 runtime adaptations

SimpleAPI 0.0.7's Bungee JSON cache uses a newer static Gson parser API that is absent
from the Java 8 Bungee acceptance runtime's Gson 2.8.0. A narrow source bridge preserves
its public methods, nested cache layout and existing parsing behavior while using the
compatible parser instance methods. The bridge source is pinned to SimpleAPI
`1617356c3f026f741b5562bf62c8de59345637e1`; Gson 2.8.0 is a provided compile/test API,
not a new shaded runtime library. Nested existing-cache reload/save/reconstruction tests
run against that exact Gson API. Non-object/empty existing caches retain the old loud
initialization failure and remain byte-for-byte untouched; they are not replaced by empty
state. Malformed JSON recovery remains the inherited behavior and is not a guarantee of
recovering votes from damaged files. No cache-format migration is required.

VotingPlugin proxy shutdown now tolerates incomplete initialization while still closing
the initialized database and stopping the proxy. AdvancedCore time-checker shutdown is safe
before its timer has loaded and still stops an initialized timer. Three proxy tests cover missing proxy,
missing method/cache initialization, and database closure before the time checker exists.
This does not suppress startup failures or change normal vote routing.

Hardcoded admin/reward-editor icons use Spigot 1.8 materials. The dragon-head decorative
icon uses a skull item because 1.8 has no dragon-head material. Player-head items retain
legacy SKULL_ITEM data value 3 and the existing owner metadata. Bundled GUI defaults use
SIGN/WATCH rather than OAK_SIGN/CLOCK. Configuration keys and item purposes remain the
same; existing operator files are not replaced or automatically rewritten. Operators with
modern material names in old custom GUI files must select their 1.8 equivalents.

## MySQL bound-value backport

Upstream `f468b485e8ba17729f8288d3464df6aa0bff5097` is adapted to the fork's
existing MySQL-only table and Query APIs. Player-name/exact-row lookup, delete,
insert, and both synchronous/asynchronous update overloads bind values before
execution. Empty updates no longer execute an invalid SET statement. Table/column
identities and existing cache eviction/error-return behavior are unchanged.

Unlike newer database abstractions, this fork stores boolean values in TEXT columns
and reads them through Boolean.valueOf(String). Bound booleans therefore retain
`"true"`/`"false"` text rather than JDBC numeric boolean representation. This avoids
a data-format regression during the backport. Five focused write/dispatch tests
and two packaged read regressions cover apostrophes, SQL-looking names, boolean
format and empty updates. The read regressions execute JDBC queries through the
actual shaded Java8 dependency layout, with SQLite used for SELECT-compatible
fixtures; that is not a claim of exercising MySQL-specific write SQL on SQLite.

## Continued cache scheduler backport

The scheduling portion of upstream `fd1676d24aafbaaccc6fdfe4a04bba3bd5a32462`
now resets its ownership flag in `finally`, schedules changes queued during a
write, and resets the flag after rejected scheduling. The legacy three-second
coalescing interval is unchanged. Scheduling rejection still propagates to the
caller rather than becoming a silent success.

Three regressions failed against the unchanged scheduler, then passed after the
fix. Two valid-input checks cover coalescing and an already scheduled task
finishing after cache dump. Actual Java8 `clean install` passes 59 unit tests and
three packaged-artifact tests, with no failures, errors, or skips.

This is a partial port of that upstream commit. It does not claim serialized
concurrent flushes, retirement fencing, or durable failure acknowledgement from
legacy void storage adapters. SQL/file write failure propagation and queue
retention remain a separate coordinated storage/cache backport under audit;
scheduling completion alone is not proof of a committed vote or point write.
