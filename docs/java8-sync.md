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

## Continued command and date correctness backport

Upstream `5eca2b0539864e581237cb7dcfd02517bb805c6b` is adapted to the legacy
placeholder APIs: date arithmetic uses the supplied Date; all three command-list
overloads honor their existing stagger option; both single-command overloads and
list dispatch strip one leading slash after existing placeholder processing.
No configuration keys or public signatures change, and command execution stays
on the existing Bukkit scheduler. Four focused tests cover all five overloads,
positive/negative/zero date arithmetic, empty input, and non-staggered dispatch.
Three assertions fail on the unchanged fork, then all four tests pass. Actual
Java8 `clean install` passes 63 unit and three packaged-artifact tests, with zero
failures, errors, or skips. These tests isolate placeholder processing; they do
not claim a new JavaScript/PlaceholderAPI implementation or live runtime proof
of every command overload.

## Checked SQL write foundation for cache integration

The legacy MySQL and SQLite void update APIs remain unchanged. New additive
`updateStrict` methods provide synchronous checked writes for the forthcoming
cache adapter: SQL failure propagates, external transactions cannot produce a
false acknowledgement, primary identities cannot be mutated, and unrelated row
columns remain intact. MySQL uses a bound `INSERT ... ON DUPLICATE KEY UPDATE`;
SQLite uses UPDATE and INSERT OR IGNORE with a bounded race retry, compatible
with the retained SQLite3.7.2 driver. Both preserve textual true/false for legacy
UserData boolean readers. SQLite retains its owner-managed connection; MySQL
closes each borrowed connection and statement. Existing automatic column checks
are retained; the tests below cover writes against explicitly prepared schemas.

The actual Java8 full build passes69 unit+7 packaged-artifact tests, zero
failures/errors/skips (`ac-checked-write-autocommit-valid-fixture.log`). Six new
SQLite regressions use JDBC, and four MySQL regressions load the actual shaded
Java8 artifact to verify bindings, SQL errors, resource closure, transaction
acknowledgement and identity-cache publication. The initial artifact fixture
omitted the existing column-check mutex; fixing that fixture initialization
allowed the unchanged assertions to exercise the intended SQL paths.

The packaged MySQL method was additionally run against the isolated MariaDB11.8.6
fixture with Java8u504 and an already cached, Java8-compatible Connector/J5.1.14
provided only to the test runner. Actual insert, update, idempotent retry,
apostrophe/boolean values, preservation of unrelated columns, and a rejected
batch with unchanged earlier data all pass. The unique owned test table was
removed and the fixture daemon stopped. No production dependency was added.

These APIs are a prerequisite, not completed cache integration. Legacy
UserData/cache callers still use the legacy methods. The checked FLAT adapter,
cache failure retention, serialized flushes, retirement fencing, caller threading
and shutdown behavior remain under implementation and audit. No claim of
completed vote/point durability follows from adding these methods alone.

## Checked FLAT publication and common batch adapter

`UserData.setValuesStrict` is an additive synchronous adapter for FLAT, MySQL,
and SQLite batches. Existing void setters remain unchanged. The FLAT method uses
the existing FileThread owner lock without starting its deprecated polling
thread. It strictly reads existing YAML, preserves unrelated values and legacy
boolean text, stages beside the target, and atomically replaces the target only
after saving successfully. Malformed input and rejected publication preserve the
previous file. Existing Linux POSIX permissions/ownership and resolved symlink
targets are retained. A filesystem without atomic replacement support or without
permission to preserve existing attributes reports failure; there is no unsafe
truncate fallback. Cross-owner/group installations and other filesystems remain
runtime acceptance limitations. This does not claim fsync/power-loss durability.

Missing JDBC connections now produce checked SQLExceptions before preparing
writes or publishing identity caches. Two additional regressions cover this
failure, including the actual shaded MySQL artifact. Eight real temporary-file
tests cover typed batches, partial updates, malformed input preservation,
publication failure cleanup, invalid identities/values, directory rejection,
Linux permissions and symlinks. Four adapter tests cover backend selection,
legacy UUID filtering, unchanged caller maps, and propagated storage failures.

Actual Java8 clean install: 82 unit + 8 packaged-artifact tests, all passing with
zero failures/errors/skips. The exact workspace-local AdvancedCore artifact is
consumed by VotingPlugin's Java8 clean verify: 14 unit + 1 packaged-artifact test,
all passing. Both final artifacts have maximum base class-file major52 (1814
AdvancedCore and2434 VotingPlugin classes). Evidence is recorded in
`ac-checked-flat-adapter-clean-install.log`,
`vp-checked-flat-adapter-clean-verify.log` and
`checked-flat-adapter-build-results.json` outside Git in the isolated workspace.

These tests do not prove cache integration. Queue restoration, concurrent flush
ownership, cache retirement, callback ordering and shutdown behavior are still
under implementation. The full upstream ledger and final independent review
remain incomplete; this cohort is not full-backport or PR readiness.

## Checked cache batches and same-instance retirement

The cache now serializes finite batch claims through a Java8-compatible owner
lock acquired outside its monitor. It calls the checked UserData adapter and
restores failed batches ahead of newer queued changes. Payloads are not dumped
before acknowledgement. Successful notification/cleanup runs after releasing
the storage owner, and listener failure cannot requeue a committed write.
Clear/dump wait for the active owner, reject new admission visibly during their
final flush, and preserve identity/pending data when that flush fails. Recursive
storage callbacks cannot flush or retire their own active batch; a rejected
recursive retirement cannot clear the outer retirement marker. Delayed tasks
can safely finish after dump. Background failures produce a warning once until
a successful background attempt, retaining the existing three-second retry
interval; no user values or SQL are included in that warning.

Nine focused regressions cover checked failure/retry ordering, notification
failure, failed retirement/retry, in-flight retirement and later accepted work,
callback retirement on another thread, retirement admission, preparation
failure, bounded outage warnings/recovery and recursive storage callbacks.
The five existing scheduler regressions retain their assertions and now observe
the checked adapter. Actual Java8 clean install passes91 unit+8 artifact tests,
zero failures/errors/skips. The exact dependency is validated by the paired
VotingPlugin build, documented in its coordinated acceptance section.

The full run exposed a pre-existing mixed Mockito4 inline/subclass handler
lookup assertion in artifact fixtures (verified in installed MockUtil bytecode).
Failsafe now excludes mockito-inline and those fixtures use the one core mock
maker. Unit tests retain inline support for their static mock boundaries.
Existing artifact assertions are unchanged. The first attempted default-answer
fixture adjustment did not by itself solve the mixed-maker problem; failed logs
remain evidence rather than being reported as successful builds.

A controlled real Java8/Spigot1.8.8 SQLite fixture verifies online vote receipt,
reward, cached SetPoints/AddPoints, persisted points10/total1, graceful stop and
restart persistence. Artifact hashes and exact candidate scope are in
`checked-cache-runtime-results-*.json` in the isolated evidence directory.
This is not MySQL/FLAT/proxy or failed-shutdown acceptance.

Remaining: manager cache-generation replacement/removal, population and snapshot
reconciliation with optimistic mutations, legacy non-queued setter interaction,
shutdown draining/owner closure, broader upstream feature ledger, full final
runtime acceptance and fresh independent review. Same-instance fencing alone is
not a complete manager lifecycle backport or PR readiness claim.

## Versioned cache population and safe owned reads

The portable snapshot behavior from pinned main6390c1cab41bd4d7683c7df88dd36537c8c7861e
is adapted to the legacy cache. Storage reads stay outside its monitor. Queued
and claimed values remain visible while a snapshot loads or is replaced; writes
completing during a read also fence its stale result. A completed later snapshot,
explicit replacement or eviction invalidates older reads. Replacement copies
its input, supports null as an empty explicit snapshot while preserving pending
values, and never resurrects a retired instance. Registered defaults and dynamic
stored columns are retained. No storage format, schema or public signature is
removed; the old mutable getCache() API remains available. Owned UserData integer
and string reads now take one monitor-protected value lookup, preserving numeric
string parsing and normal storage fallback when the cache is absent/retired.

The first eight snapshot regressions all fail on the previous implementation
(six failures, two errors). Ten final tests cover those cases, a mutation queued
before a read but committed during it, and actual typed getter behavior without
raw map access. Together with existing cache tests,24 focused tests pass. Actual
Java8 clean install passes101 unit+8 artifact tests; the exact paired consumer
clean verify passes14 unit+1 artifact test, zero failures/errors/skips. Logs are
`ac-cache-snapshot-clean-install.log` and `vp-cache-snapshot-clean-verify.log`.
Base class versions remain<=52 in both jars. Live SQLite Spigot1.8.8 acceptance
for CacheSnapshot proves vote/reward, points10/total1, graceful stop and restart
against the artifact hash in `checked-cache-runtime-results-CacheSnapshot.json`.

This is not the entire modern shared runtime port. Legacy SQL/file read helpers
still need checked error propagation: an unavailable store must not look like a
new user with defaults. Manager generation fencing, non-queued setter/storage
admission and shutdown draining also remain unresolved. External mutation through
the legacy raw map is not advertised as thread-safe. The full ledger, final
runtime acceptance and independent review remain due; no PR readiness is claimed.

## Checked storage snapshot reads

Cache population now uses one additive UserData.getValuesStrict read, replacing
the two legacy keys/values queries. MySQL and SQLite expose getExactStrict for
one primary identity with bound values; statement/result-set failures propagate,
missing connections and outer transactions are rejected, and empty results mean
an actual missing row. MySQL closes its borrowed connection; SQLite retains the
owner-managed connection. Registered integer/boolean and dynamic string fields
retain the existing DTO representation. Legacy getExact/getValues APIs are
unchanged. FileThread's checked read strictly loads existing YAML under its
existing owner lock, is read-only, does not start the legacy polling thread, and
returns empty only for an absent file. Malformed, unreadable/non-file inputs fail.
Cache publication wraps checked errors visibly without replacing existing values
or pending payloads with defaults. These checks affect cache population, not all
remaining direct legacy read callers.

Ten new unit regressions cover JDBC types/missing identities, closed/missing
connections, transaction/filter guards, file read-only/type behavior and malformed
input, cache failure preservation/defaults, and all three adapter routes. Four
new actual-shaded-artifact regressions cover successful/missing MySQL query paths
through real SQLite JDBC and checked query failures/resource closure. They do not
claim live MySQL driver acceptance for this new reader. Java8 clean install
passes111 unit+12 artifact tests; exact paired consumer clean verify passes14
unit+1 artifact test, all zero failures/errors/skips. Final base bytecode stays
major<=52. Logs: ac-checked-read-clean-install.log and
vp-checked-read-clean-verify.log; hashes/counts: checked-read-build-results.json.

The exact candidate additionally passes real Java8/Spigot1.8.8 acceptance in both
SQLite and FLAT modes: online vote/reward, SetPoints/AddPoints to10,total1,
graceful stop and restart persistence. No cache snapshot/write, event-dispatch,
or linkage errors are observed in either startup/restart log. The controlled
fixture's temporary FLAT configuration is restored byte-for-byte to its prior
SQLite configuration. Evidence CacheReadSQL/CacheReadFlat JSON pins the same
consumer artifact; all fixture processes are stopped. This is not live MySQL,
proxy, or failed-shutdown acceptance. Registry generation fencing, non-queued
setter admission, shutdown draining, the broader ledger and final independent
review remain incomplete. No PR readiness is claimed.

## Cache registry generation fencing

Manager removal retires the captured cache and conditionally detaches that exact
instance before delivering its post-commit notification. Failed checked writes
leave the canonical cache and pending work intact. Removing an absent identity
does not populate it. Bulk clear captures existing generations and never clears
a newer generation created by another caller or notification. Concurrent initial
population performs storage reads outside registry locks and atomically selects
an already published live generation instead of overwriting it. The public
ConcurrentHashMap getter and existing cache/refresh overloads remain available.

Eight deterministic regressions cover absent removal, callback replacement, bulk
replacement, slow/concurrent publication, retired-entry recovery, failure
preservation, and a callback awaiting population from another thread. This is
registry fencing, not global storage admission: direct non-queued writers and
shutdown/reload admission, draining and native owner closure still require
further implementation. Returning a cache does not grant a lifetime lease;
concurrent retirement rejects subsequent stale writes visibly. No full upstream
ledger or final PR readiness is claimed by this cohort.

Validation: 37 focused cache tests pass; Java8 clean install passes119 unit
plus12 artifact tests, exact VotingPlugin clean verify passes14 unit plus1
artifact test, zero failures/errors/skips. Base bytecode remains major<=52.
The exact consumer passes actual Java8/Spigot1.8.8 SQLite online vote/reward,
SetPoints7/AddPoints3=>10,total1, graceful stop and restart persistence
(CacheRegistry evidence). This does not verify failed shutdown or live MySQL.
