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

## Checked direct typed writes

The existing setInt/setString overloads use checked storage writes for uncached
or queue=false writes. Their signatures, explicit-storage selection and stored
representations remain unchanged. Cached direct writes share the queued-batch
owner: older queued work commits first, the direct value publishes only after
acknowledgement, and later queued changes remain visible and pending. Retirement
and recursive direct writes cannot close/reorder an in-flight direct commit.
Callbacks execute after owner release; all committed notifications are attempted
and callback failures remain visible without replaying committed storage work.
The additive setValuesStrict(UserStorage,Map) overload preserves explicit backend
selection; legacy bulk setValues APIs remain unchanged.

Observable correction: failed direct writes throw visibly and do not publish or
notify; async direct writes publish after their scheduled commit, not before
executor admission. FLAT direct writes use the checked atomic file owner rather
than the legacy polling thread. Cached queue=true writes retain existing
optimistic publication/notification semantics. Rejected scheduling leaves the
cache unchanged. Ten deterministic regressions cover failure, ordering, delayed
async publication/rejection, later queue writes, string/uncached routes, explicit
storage selection, callback failures and recursive retirement/write rejection.
This does not yet provide manager-wide uncached identity arbitration or shutdown
admission/drain, and legacy bulk mutation paths still need an ownership audit.

Actual Java8 clean install passes129unit+12artifact tests; exact VotingPlugin
clean verify passes14unit+1artifact, all zero failures/errors/skips. Base bytecode
remains major<=52. Controlled Java8/Spigot1.8.8 acceptance invokes the packaged
relocated direct setter through a fixture-only console plugin and verifies
acknowledged cache state plus persisted points. SQLite DirectWriteSQL and FLAT
DirectWriteFlat both pass vote/reward, direct SetPoints7, AddPoints3=>10,total1,
graceful stop/restart persistence with no observed write/event/linkage errors.
The fixture-only plugin is removed and temporary FLAT config restored byte for
byte. This is not live MySQL, proxy or failed-shutdown acceptance. Full ledger,
remaining ownership/lifecycle work and final independent review remain due.

## Reward name normalization and file fallback guards

Pinned upstream2599db95f058d9cbeb21f81111e63729e3cf8b5a lookup behavior is
adapted into the existing RewardHandler facade: case/spaces/dots/underscores
resolve consistently using Locale.ROOT, direct/sub aliases register once,
and rewardExist normalizes spaces/null. Existing ArrayList-returning APIs,
file lookup precedence and ordinary missing-file fallback remain compatible.
VotingPlugin clears/rebuilds direct handles before registering them.

Upstream5d4d5bee446510f242de6c7523b1648128895353 and its follow-up fix
1da8dd94036afea300e6afea6a1df57409126431 provide the final filename guard.
The invalid intermediate syntax is not copied. Missing reward-file fallbacks
reject absolute paths, either separator and NUL before file construction.
The guard also covers the legacy generated DirectlyDefined fallback before
mkdir. Registered handles still resolve first; a registered path containing
a separator is not confused with file traversal. Rejections remain visible
and their warnings omit raw lookup text. No schema/config/wire change.

Four baseline failures reproduced incorrect resolution/deduplication/existence
and unsafe fallback. Seven new tests plus two prior resolution tests cover
locale independence, duplicate aliases, valid fallback, registered separator
handles, null/space lookup and rejected file names. The broader reward loader/
registry/executor reorganization, generated snapshot quarantine and ownership
audits remain incomplete; this does not claim a complete reward subsystem port.

Exact consumerb795fac2... passes actual Java8/Spigot1.8.8SQLite RewardNamesSQL
vote/reward, packaged alias/file-guard checks,points10,total1,graceful stop
and restart persistence. No checkedwrite/event/linkage errors observed.
Fixture-only plugin removed, no escape probe created, owned server stopped.
Evidence reward-names-build-results.json and RewardNamesSQL runtime JSON/logs.
Live MySQL/proxy/quarantine/shutdown-failure acceptance remains unverified.

## Additive asynchronous reward-injection contract

RewardInject now exposes Java 8 CompletionStage hooks and ordered async
admission adapted from upstream923e741a90ad87d3ae1717301ded125f6749fc43.
Existing injections remain synchronous by default; their abstract callback
and public signatures remain available. The default async bridge observes
only the synchronous callback, not hidden scheduled work. Opted-in requests
can return their actual completion stage. Stage settlement, including failure,
releases ordered admission; cancelling an observer does not release unfinished
physical work. User callbacks run outside the injection monitor. Nested reward
injectors must opt out of shared serialization to avoid waiting on themselves.
The replay-checkpoint hook defaults to a no-op, not a durability claim.

Seven focused regressions cover defaults, failure propagation, stage ordering,
failed/null/rejected requests, concurrent submission and observer cancellation.
Actual Java8 clean install:143unit+12artifact tests; exact consumer clean
verify:19unit+1artifact tests. All pass with zero failures/errors/skips. Both
packaged base class sets remain major<=52. Evidence:async-inject-build-results.json.

This is a prerequisite API, not the complete upstream async dispatcher port.
Integer adapters, scheduler ownership, execution/checkpoint integration and
queue replay remain under implementation. Existing queue formats are unchanged.
Generated DirectlyDefined snapshots retain current lookup behavior: legacy
replay clears pending entries before void asynchronous dispatch, so hiding
snapshots before completion-aware replay could lose pending rewards. No
quarantine or durable replay claim is made by this API-only cohort.

## Typed integer completion adapter

RewardInjectInt now adapts its ConfigurationSection callback to the typed
CompletionStage<String> hook without changing the existing synchronous
callback. Missing integer data remains skipped unless the legacy force flags
apply; configured/default integers and null-result placeholder fallback are
unchanged. The typed stage is awaited rather than treated as an immediate
result. Null stages and callback/storage failures settle exceptionally.
Java9 failedFuture calls from main are replaced with Java8 CompletableFuture
completion. Runtime async dispatch is still a separate integration step.

Five focused regressions pass. ActualJava8 clean install148unit+12artifact
and exactconsumer clean verify19unit+1artifact all pass with no failures,
errors or skips; base classes major<=52. Producer3b3947b8195f4e6a700fb5cc815ff7266849cc6a856462f60e1263f2bf891bdc;
consumer01d76c2fdcfcd58ca8ff8c46a6adc0f3a0c40534c210d4a0d035b83ecc349f60.
Evidence async-integer-build-results.json. No config/data/wire version changes.

Exact consumer01d76c2f... also passes controlled actualJava8/Spigot1.8.8
SQLite AsyncInjectSQL vote/reward, points10,total1, graceful shutdown and
restart persistence. Logs show no exception/linkage/write-failure markers;
owned fixture processes stopped. This proves legacy runtime compatibility
with the candidate API additions, not completion-aware queued replay.
Evidence checked-cache-runtime-results-AsyncInjectSQL.json and paired logs.

## Completion-aware injection dispatch on Bukkit1.8

Reward.giveInjectedRewardsAsync now runs a registration-order snapshot on
the Bukkit owner, waits for each opted-in physical stage, publishes its
placeholder on that owner, and runs post injections last. Async failure or
a null stage stops later effects. Ordinary legacy callback exceptions retain
their logged per-injection isolation; their hidden scheduled work is still
not observed. Existing synchronous reward entry points remain unchanged.

The additive continueOnServerThread API uses the same per-plugin dispatcher.
Bukkit1.8 has one owner thread, so modern region/player scheduler APIs are
unnecessary. Java9 orTimeout is replaced with the existing plugin timer and
a monotonic claim-time deadline check. Scheduler admission, actual execution
and physical settlement are distinct. Timeouts, rejected scheduling and
plugin disable fence queued callbacks before side effects. Claimed work is
not timed out/cancelled as unexecuted. No new executor or dependency is added.
Enable publishes a new dispatcher generation; disable closes old admission
before shutting the timer down. A pipeline captures its generation and cannot
continue through a replacement dispatcher. User callbacks settle outside
dispatch ownership locks. This does not yet drain native storage at shutdown.

Seven dispatch and six production-pipeline regressions pass. Actual Java8
producer clean install161unit+12artifact and exact consumer clean verify
19unit+1artifact pass, all zero failures/errors/skips; base major<=52.
Evidence reward-pipeline-build-results.json, ac-reward-pipeline-clean-install.log
and vp-reward-pipeline-clean-verify.log.

The initial live harness attempt used a17-character Minecraft username and
failed login before exercising the API; cleanup ran. The valid-name rerun
RewardPipeSQL invokes the actual relocated API via a fixture-only console
plugin. An actual Bukkit worker settles its stage; subsequent placeholder,
normal callback, post callback and final receipt assert the main thread.
The fixture snapshots its test hooks then immediately restores the registry.
This fixture plugin is not a production dependency and is removed afterward.

Root giveRewardAsync/event/requirements/deferral integration, native async
storage action receipts, persisted queue provenance/checkpoints/replay and
generated snapshot quarantine remain incomplete. PlayerRewardEvent is
explicitly asynchronous and must retain that contract in the root adapter.
No queue format, schema/configuration/wire or release version change occurs.
The full upstream923e741a90ad87d3ae1717301ded125f6749fc43 remains partial.

Exactconsumer09a7a449... passes the packaged async API assertion, real online
vote/reward, points10,total1, graceful shutdown and restart persistence.
No exception/linkage/checked-write failure markers appear in first/restart
logs. Evidence checked-cache-runtime-results-RewardPipeSQL.json. Fixture-only
acceptance jar removed and all owned fixture processes stopped. This is not
MySQL/FLAT/proxy/failed-shutdown or durable queued-replay acceptance.

## Awaited user delivery and caller-state isolation

The established giveRewardUser now routes applicable opted-in injections
through giveRewardUserAsync. Ordinary synchronous injections keep the legacy
inline path; an async feature requiring absent configuration does not move
unrelated rewards to that path. Player/UUID/display placeholders are prepared
on the captured Bukkit owner. The returned stage waits for injection and
post-reward settlement before success notification and repeat scheduling.
Failures or unavailable players do not schedule repeats. Existing config
ForceOffline handling, RepeatOnStartup and CheckRepeat behavior are retained.

Caller placeholders and all existing RewardOptions fields are copied before
admission, including CheckRepeat and the unset OnlineSet distinction. The
copy does not share mutable maps or insert a new Server placeholder. Existing
public option signatures and serialized/configuration/data/wire formats stay
unchanged. Null user-delivery options use defaults. Ordinary legacy callbacks
still have their logged exception-isolation contract; hidden scheduled work
is not made durable by this bridge.

Seven added user-delivery tests plus a complete legacy option-copy regression
pass. ActualJava8 producer clean install169unit+12artifact and exactconsumer
clean verify19unit+1artifact, all zero failures/errors/skips; base major<=52.
Evidence reward-user-build-results.json and paired clean build logs.

Root giveRewardAsync/event/requirements/deferral and checked queue persistence
still require integration. PlayerRewardEvent declares an asynchronous event;
it must remain off-primary while Bukkit state phases use the owner. Deferred
snapshot/file and queue writes need their own checked acknowledgements, not
completion inferred from existing void addOfflineRewards. Native async action
receipts, persisted provenance/checkpoints/replay, global storage lifecycle
and generated snapshot quarantine remain incomplete. Temp-cache player-name
lookup may read storage; the coordinated root preflight must resolve this
off-owner before live preparation. No complete durability/backport claim.

Exactconsumer8c7f497a... passes actualJava8/Spigot1.8.8 packaged
giveRewardUserAsync with dynamic player/UUID placeholders, worker settlement
and owner completion. RewardUserSQL vote/reward,points10,total1,stop/restart
pass; logs show no exception/linkage/checked-write failure markers. Evidence
checked-cache-runtime-results-RewardUserSQL.json. Fixture-only jar removed,
owned processes stopped. Not MySQL/FLAT/proxy/failed-shutdown/queued-replay
acceptance. The full upstream commit remains partial.

## Shared off-primary admission and identity preflight

ServerThreadRewardDispatch now supports both Bukkit-owner and off-primary
continuations with the same pending-admission set, timer, close generation,
monotonic deadline and physical completion receipt. Off-primary setup runs
inline when already away from the owner, or uses Bukkit's async scheduler
when called on the main thread. Admission rejection/timeout/disable prevents
late effects; claimed physical work is not misreported as unexecuted.
Reward.continueOffServerThread exposes that existing owner for event/storage
setup without introducing another executor or runtime owner.

Awaited user delivery resolves the player name off-owner because temporary
cache lookup can read storage. It snapshots registered injections on the
Bukkit owner before that boundary, then reuses the resolved name for live
preparation and success notification. A registry change while preflight
waits cannot replace this delivery's captured sequence. Identity failure
prevents injections and repeats. Legacy synchronous delivery remains inline.

Three new dispatcher tests and two identity-preflight regressions pass;
existing user/injection/admission coverage remains. ActualJava8 producer
clean install174unit+12artifact and exactconsumer clean verify19unit+1artifact
pass, all zero failures/errors/skips. Base major<=52; evidence
reward-preflight-build-results.json and paired clean Maven logs.

The strengthened fixture asserts the receipt remains pending after the first
callback before allowing actual worker settlement, then checks placeholder/
normal/post/final ownership. No assertion is relaxed to accept early
completion. Root asynchronous event/decision/defer, checked snapshot/file
publication and queue acknowledgement, native async action collection,
occurrences/checkpoints/provenance/replay and global storage lifecycle remain
incomplete. Full upstream923e741a90ad87d3ae1717301ded125f6749fc43 stays partial.
No configuration/schema/data/wire/release version change is introduced.

Exactconsumer78e83c3f... passes actualJava8/Spigot1.8.8 RewardPrepSQL
packaged user-delivery/preflight/pending-receipt/owner checks plus real
vote/reward,points10,total1,gracefulstop/restart. Logs have no exception,
linkage or checked-write failure markers. Fixture-only plugin removed and
owned processes stopped. Evidence checked-cache-runtime-results-RewardPrepSQL.json.
This is not liveMySQL/FLAT/proxy/failed-shutdown or durable replay acceptance.

## Checked reward-configuration publication prerequisite

RewardFileData.saveStrict and FilesManager.editFileStrict now publish through
FileThread.saveConfigurationStrict under the existing file owner. The new
checked path stages a YAML document beside its target and atomically replaces
it only after serialization succeeds. Existing modes/owner/group and canonical
symlink target location are preserved. Malformed or non-file predecessors fail
without replacement; failed staging/publication cleans the temporary file and
propagates the original exception. An unsupported atomic move remains a visible
failure. This is publication acknowledgement, not an fsync/power-loss guarantee.

The audit corrected a prior assumption: FilesManager.editFile calls
FileThread.getThread().run(Runnable), which executes synchronously under the
FileThread lock. It is not a queued asynchronous write; its defect for checked
completion is swallowed IOException, and it may start the deprecated polling
thread. The additive checked path reuses the same serialization owner without
calling getThread or starting that thread. Legacy void methods remain available.

Six regressions cover nested YAML/readback, failed serialization/atomic move,
malformed/non-file predecessors, symlink/POSIX identity preservation and actual
RewardFileData-to-FilesManager delegation with missing backing-document failure.
ActualJava8 producer clean install180unit+12artifact and exactconsumer clean
verify19unit+1artifact pass, all zero failures/errors/skips; base major<=52.
Evidence checked-configuration-build-results.json and paired clean Maven logs.

This API is a prerequisite for completion-aware snapshot/queue deferral.
The legacy setRewardFile path has not been replaced: its in-memory mutation
and public registry publication must be coordinated with checked file success
and queue persistence before being wired into durable replay. Do not mutate
a registered snapshot before a failed publication or acknowledge queue creation
from a void setter. Root async decisions/events/defer, checked queue append,
native action receipts, provenance/occurrences/checkpoints/replay, generated
snapshot quarantine, global storage lifecycle and the full ledger remain due.
No configuration/schema/data/wire/release version change.

Exactconsumerb8bb5a48... passes actualJava8/Spigot1.8.8 CheckedSnapSQL
checked RewardFileData publication/readback, pending user receipt and
owner/worker/owner callbacks, realvote/reward,points10,total1,stop/restart.
Logs contain no exception/linkage/checked-write failure markers. Temporary
snapshot deleted, fixture-only console jar removed, owned processes stopped.
Evidence checked-cache-runtime-results-CheckedSnapSQL.json. This validates
the checked API; generated queue snapshot transaction/replay is not yet wired.
Not liveMySQL/FLAT/proxy/failed-shutdown acceptance.

### Detached generated reward candidates (partial async reward backport)

`RewardFileData.prepareGeneratedSnapshot(ConfigurationSection)` now constructs
an independent Reward/YAML candidate without legacy `setData` per-key save and
reload calls. Existing target metadata, generated header and
`DirectlyDefinedReward` flag are preserved. Supplied parent sections replace
predecessor sections as in legacy `setData`; unrelated keys remain. A serialized
copy isolates the candidate from later source and predecessor edits. Preparation
neither writes nor registers it. The caller must separately acknowledge
`saveStrict()` before publishing the candidate in the reward registry.

Three regressions exercise independent candidate/source/predecessor state,
malformed predecessor publication failure, and successful checked publication
with section replacement/header/flag/readback. Actual Java 8 clean producer
install: 183 unit + 12 artifact tests. Exact shaded consumer clean verify:
19 unit + 1 artifact test. All zero failures/errors/skips; both jars' base
classes have major version <=52. Evidence: `snapshot-candidate-build-results.json`
and paired `*-snapshot-candidate-clean-*.log` in the isolated workspace.

This is a prerequisite, not the complete generated reward transaction. Legacy
root deferral remains unchanged; registry/file ordering under concurrent
publication, checked queue append, occurrence/provenance/checkpoint/replay,
native action completion and global storage shutdown remain to be integrated.
No claim of fsync/power-loss durability, full pinned-main parity or final PR
readiness. No configuration/schema/wire/version changes.

Live acceptance: exact consumer `84391f96c6a1a735ffac15e955c3d15ec926a3fa94483f057d8ea3783ca45849`
on real Java 8 / Spigot 1.8.8 with unique fixture identity `DetachedSnapSQL`
passed detached candidate/source/predecessor isolation, checked save/readback,
legacy parent-section replacement, pending async user delivery, vote/reward,
points 10 / total 1, graceful stop, SQLite integrity and restart persistence.
Evidence: `checked-cache-runtime-results-DetachedSnapSQL.json` and
`snapshot-candidate-live.log`. Fixture-only diagnostic plugin removed; owned
server/client processes exited. This does not prove full queued replay,
MySQL/FLAT/proxy acceptance or failed-shutdown behavior for the candidate API.

### Checked cached read-modify-write foundation

`UserDataCache.mutateDirect(key, transform, storageWrite)` performs the read,
side-effect-free transform and synchronous physical write under the existing
batch owner. It flushes older queued changes first, publishes the candidate only
after the supplied checked write succeeds, and preserves a later optimistic
queued replacement. Null cached values remain missing evidence; transforms must
explicitly handle them, not assume an empty durable queue. Null transformed
values are rejected by the new API, while the legacy public `writeDirect`
replacement retains its prior nullable-value behavior. Extension notifications
remain outside ownership; their failure does not mean the physical write failed.
Recursive storage-phase mutation/retirement is rejected. No new owner/executor.

Six regressions exercise two concurrent append transformations without a lost
update, queued-predecessor ordering and later replacement, write failure and
unknown value preservation, legacy null compatibility and checked null rejection,
recursive mutation/retirement fencing, and a post-commit notification failure
carrying the acknowledged value in `CommittedUserDataMutationException`. Legacy
`writeDirect` retains its original callback exception behavior. This is not yet
a complete persisted reward queue API: root deferral, capacity/codec/provenance/occurrences/checkpoints,
uncached canonical UUID ownership, cache-generation transitions and lifecycle
coordination still require integration. No schema/config/wire/version changes.

Actual Java 8 producer clean install passed 189 unit + 12 artifact tests;
exact consumer clean verify passed 19 unit + 1 artifact test, all zero failures,
errors and skips. Base classes 1816 / 2436 have maximum major 52. Evidence:
`queue-mutation-build-results.json` and paired clean Maven logs.

Final consumer SHA256 `cf7564df70f88ccadef1211dd28ab4130ed2b4277d21309da272be489aaf81f0`
passed real Java 8 / Spigot 1.8.8 acceptance with owned identity `QueueAckSQL`:
worker-thread cached mutation followed by checked SQLite readback and restoration
of the original fixture field; detached snapshot; pending async reward receipt;
real vote/reward; points10,total1; graceful stop; SQLite integrity; restart
persistence; no observed linkage/checked-write errors. Evidence:
`checked-cache-runtime-results-QueueAckSQL.json`, `queue-mutation-final-live.log`.
Fixture plugin removed and all owned server/client processes exited. This does
not claim persisted queue replay, MySQL/FLAT/proxy or failed-shutdown acceptance.

### Plugin-local storage ownership across cache generations

`UserStorageOwnership` supplies 64 fixed lock/revision slots per plugin, keyed
by UUID. Checked UserData reads/writes and cache batch ownership share a slot
across cache generations and uncached wrappers. Checked recursive writes and
reads from an in-flight write are rejected. The mutation-attempt revision
changes before attempting a write, including failed/uncertain outcomes: cleanup
can throw after a physical commit, so success-only fencing would be too weak.
Private candidates read outside registry locks; a stale candidate re-reads under
ownership before publication, and an already published live successor still wins.
Direct typed setters resolve the cache when accepted work executes, preserving
a cache published after admission. Retirement detaches the old generation before
its callbacks; change notifications remain outside ownership. Lock order is
storage owner -> cache monitor / native storage owner; no registry compute holds
I/O or extension notifications. No new executor or unbounded identity map.

Seven ownership regressions cover uncached wrapper serialization, private and
existing-cache snapshot fencing, callbacks awaiting another thread's population,
failed/reentrant writes, bounded ownership and an uncertain post-commit exception.
An additional direct setter test covers cache publication after async admission.
Existing cache-generation, callback, optimistic-overlay and direct-write tests
remain intact. Actual Java 8 clean producer install: 197 unit + 12 artifact tests;
exact consumer clean verify: 19 unit + 1 artifact test, all zero failures/errors/
skips. Base classes 1819 / 2439 have maximum major 52. Evidence:
`canonical-owner-build-results.json` and paired clean Maven logs.

Exact consumer `be395e91d63bae4296a1efa6eff2949a3535ce8d343e2cd02cd224df1faf3fd7`
passed real Java 8 / Spigot 1.8.8 `OwnerFenceSQL` acceptance: checked cached
mutation, retirement, uncached typed write, repopulation/readback and restoration
of the owned fixture field, detached reward snapshot, pending async user receipt,
vote/reward, points10,total1, graceful stop, SQLite integrity and restart
persistence. No observed linkage/checked-write errors. Evidence:
`checked-cache-runtime-results-OwnerFenceSQL.json`, `canonical-owner-live.log`.
Temporary diagnostic plugin removed; all owned server/client processes exited.

This remains a partial native storage backport. Legacy bulk setters/deletes/wipes/
migrations, cross-process atomicity, lifecycle admission/drain and failed-close
recovery are not claimed covered. Strict bounded reward queue append/provenance/
occurrences/checkpoints/replay and root async decisions/defer/native action
receipts remain to be integrated. No schema/configuration/wire/release-version
change; additive ownership getter/helper only. This cohort is not live MySQL,
FLAT, proxy or failed-shutdown acceptance, full main parity, or final PR readiness.

### Checked bulk user-data replacement

All legacy `UserData.setValues` overloads now use a defensive copy and one
synchronous checked native batch under the existing plugin-local UUID owner.
SQL continues to ignore the exact `uuid` key; empty effective batches are no-ops.
The active-store cache flushes its older finite prefix first, then publishes
matching fields only after storage acknowledges the replacement. Newer queued
fields remain visible, including fields queued during that older flush; the
scalar direct setter now guards this timing window too. A failed new batch does
not publish its values. A successfully flushed older prefix remains committed;
a failed older flush retains its pending work and prevents the newer write.
Explicit alternate-storage conversions do not flush or publish active-store
cache state. FLAT writes use the existing checked atomic-file owner, retaining
unrelated fields and rejecting malformed predecessor YAML without replacing it.

Bulk writes retain their legacy absence of their own change notifications.
Older-prefix notifications run after releasing ownership. If such a callback
fails after the bulk replacement committed, `CommittedUserDataBatchException`
exposes an immutable map of acknowledged values; callers must not retry the
committed effect. Callback failures are suppressed onto a primary storage
failure instead of hiding it. No new executor, dependency, schema, configuration,
proxy format or release-version change is introduced.

Deterministic tests cover batch acknowledgement/failure, queued-overlay races,
alternate storage, callbacks awaiting another writer, SQL identity/no-op behavior,
uncached writes, invalid input, and real FLAT file publication/malformed-file
rejection through the public bulk entry point without starting the legacy poller.
The coordinated Java 8 clean builds and exact-consumer SQLite runtime evidence
are recorded in the isolated workspace. This does not cover legacy SQL
delete/wipe/migration bypasses, global lifecycle admission/drain, cross-process
atomicity, strict reward queue append/provenance/checkpoints/replay or the full
pinned upstream ledger. Live MySQL/FLAT/proxy acceptance for this portion and
complete final independent review remain outstanding.

Validation for this portion used Temurin 1.8.0_504 and Maven 3.9.9 with
`-Dmaven.resolver.transport=wagon`, the workspace-local Maven repository and
workspace-local temporary directory. Commands actually completed:
`mvn -B -f AdvancedCore/pom.xml -Dtest=LegacyCheckedBulkUserDataTest test`
(14 tests), producer `mvn -B -f AdvancedCore/pom.xml clean install`
(212 unit + 12 artifact tests), and exact-consumer
`mvn -B -f VotingPlugin/pom.xml clean verify` (19 unit + 1 artifact test).
All had zero failures, errors and skips; 1820 / 2440 base classes have maximum
major 52. Full commands/output and artifact hashes are in
`bulk-write-flat-focused.log`, `bulk-write-flat-clean-install.log`,
`bulk-write-flat-consumer-clean-verify.log`, `bulk-write-flat-build-results.json`.

Final consumer SHA256
`3b2f23ada6954cf6e797f3ab63ae60c10ba143cd8ad6a668b95055bb9f4d7871`
passed real Java 8 / Spigot 1.8.8 `BulkFinalSQL` acceptance: checked two-field
bulk SQLite write/readback/cache publication and restoration, cached mutation,
retirement/uncached write/repopulation, detached reward snapshot, pending async
reward receipt, vote/reward, points10,total1, graceful stop, SQLite integrity and
restart persistence. No observed linkage/checked-write errors. Evidence:
`checked-cache-runtime-results-BulkFinalSQL.json`, `bulk-write-final-live.log`.
The controlled test is SQLite-only; deterministic real-file FLAT tests do not
constitute full live FLAT startup/reward acceptance.

### Checked per-user removal

The removal portion of pinned upstream
`1b3d0fa66fe61f5252b1665bd9d77ab648e87399` is adapted to the legacy fork's
native APIs and existing plugin-local UUID owner. `UserData.remove()` and the
historically SQL-only `UserManager.removeUUID()` now flush the older finite
cache prefix, perform a checked deletion, then retire/remove that cache
generation after acknowledgement. Marking the generation as removing rejects
new queued changes during this sequence; subsequent writers acquire the same
UUID owner and resolve current registry state. A failed older flush prevents
deletion and retains pending work. A failed deletion keeps the generation live;
an already acknowledged older prefix is not replayed. This closes the old
row-resurrection path where deletion preceded cache clearing/flush.

Older-prefix notifications run outside ownership and after registry removal on
success. `CommittedUserDataRemovalException` distinguishes successful removal
from a failed post-commit notification, so callers must not retry that effect.
A primary storage failure retains precedence, with notification failure
suppressed. An uncertain post-delete resource-close exception remains visible;
it is not converted into acknowledged success or silently retried.

MySQL strict removal now uses a bound DELETE with the checked borrowed-connection
path, auto-commit verification and resource closure. It invalidates identity/name
observations after acknowledgement without user resolution or full-query reload
under ownership. SQLite adds `deleteStrict` under its existing table owner,
requires auto-commit and leaves its shared connection open. FLAT adds a checked
UUID-file delete under FileThread without starting its deprecated poller; a
missing file is a no-op, directories are rejected, and a symlink is deleted
rather than its target, preserving legacy deletion semantics. Existing public
legacy lower-level delete methods remain available. No new dependency, schema,
configuration key, proxy format or release version is introduced.

Tests cover older-prefix ordering, failed flush/deletion retention, committed
notification failures, callbacks waiting for another writer, actual concurrent
writer serialization, recursion, SQL-only removeUUID compatibility, real SQLite
selected-row deletion, outer-transaction rejection, real FLAT deletion/symlink
behavior, and packaged MySQL binding/resource/uncertain-cleanup behavior. Two
existing strict-delete assertions moved into the established packaged-artifact
fixture: the compile-time SimpleAPI embeds a Java 11 Hikari class, while the
production shaded artifact substitutes the Java 8-compatible pool. Their
failure propagation and identity-retention/eviction assertions remain; the
legacy logging-delete unit assertion remains separate.

Actual Temurin 1.8.0_504 / Maven 3.9.9 commands, with workspace-local Maven repo,
workspace-local temp directory and wagon transport:
`mvn -B -f AdvancedCore/pom.xml -Dtest=LegacyCheckedUserRemovalTest,LegacySQLiteCheckedWriteTest,LegacyCheckedFileWriteTest test`
passed 34 focused tests; final producer `mvn -B -f AdvancedCore/pom.xml clean install`
passed 225 unit + 16 packaged-artifact tests; exact consumer
`mvn -B -f VotingPlugin/pom.xml clean verify` passed 19 unit + 1 artifact test.
All final tests have zero failures, errors or skips. Base classes 1821 / 2441
have maximum major 52. Initial full test failures and the fixture correction
are retained in evidence; they are not reported as passing runs.

Final consumer SHA256
`9d0cb595c952f7df462816c04a9d65ea32d5e7c1e59f29a33fa08117736e6d74`
passed real Java 8 / Spigot 1.8.8 `RemoveNativeSQL` acceptance. A separate newly
created fixture identity had an older queued value, then public removal and
checked empty readback; processing its retired queue did not recreate the row.
The main fixture also passed bulk/cache mutation, retirement/repopulation,
detached snapshot, pending async receipt, vote/reward, points10,total1, graceful
stop, SQLite integrity and restart persistence. No observed linkage/checked-write
errors. Evidence: `checked-removal-build-results.json`,
`checked-removal-packaged-clean-install.log`,
`checked-removal-consumer-clean-verify.log`, `checked-removal-live.log`,
`checked-cache-runtime-results-RemoveNativeSQL.json` in the isolated workspace.

This remains a partial upstream disposition. Global wipes/migrations/provider
rebind/lifecycle admission and shutdown drain, all direct legacy backend callers,
strict reward queue provenance/checkpoint/replay/root deferral and the full
upstream ledger are incomplete. The current live acceptance is SQLite-only;
full live MySQL/FLAT/proxy and failed-shutdown acceptance remain to be completed.
The entire backport and final independent review are not yet ready for PRs.


### Native storage admission and shutdown drain (partial lifecycle backport)

The existing plugin-local UUID owner now also tracks accepted checked operations
with a counter and nested thread scope. Async typed setters reserve before executor
submission and release only when their physical body settles; rejection and body
failure release exactly once. Retirement seals new root admission, waits with a
monotonic caller deadline, permits only retiring-thread synchronous final writes,
and closes providers after an acknowledged final flush. Failure/interruption stays
visible and leaves the provider open; explicit retry is supported. No monitor is
held across UUID ownership, I/O or callbacks. Final cache retirement suppresses
change notifications, retains failed pending data, and removes only acknowledged
generations. Ordinary cache clear/dump/manager retirement notifications remain
inside admission but outside UUID ownership; a callback cannot retire its own work.

AdvancedCore adds a default no-op onPreUnLoad hook, stops known producers while
keeping their shared storage executor available, drains them, then drains that
executor and flushes caches before closing initialized MySQL/SQLite providers.
Partial startup avoids lazy manager/connection creation. False await results and
interruption no longer become apparent success or shutdownNow on accepted work.
VotingPlugin uses the prehook to stop legacy socket ingress/global producers and
await vote work before its final unload/provider cleanup. Existing reward/global/
vote wait bounds remain 10/5/1 seconds; core producers share a 5-second grace.
These bounds can report failure; they are not permission to close under live work.

Live acceptance exposed an important delayed-task distinction: voteTimer retained
TimeQueueHandler's 120-second startup processor wake-up after shutdown. The initial
DrainNativeSQL process exited 0 but Bukkit logged disable failures on both runs.
Its evidence was corrected to FAIL, and the fixture now rejects disable errors.
The fix cancels only owned delayed queue wake-ups with cancel(false), retains
actual votes for the existing save path, and still awaits executor termination
for any running body. It does not cancel queued immediate vote submissions or
alter ScheduledExecutorService's global delayed-task shutdown policy. Wake-up
handles track the existing pending scheduler tasks, not a second vote queue.

New tests cover actual native disable ordering, failed flush/retry, timeout,
interruption, partial startup, producer-to-storage submission, final synchronous
unload writes, admission accounting and notification ownership. Real Java 8
scheduled-executor tests prove delayed wake-up cancellation, physical running-body
fencing, accepted immediate-work completion, retained vote payloads, and rejected
late date-change wake-ups. The notification test failed before the guard fix.
Final Java 8 builds pass AdvancedCore 250 unit + 16 artifact tests and VotingPlugin
28 unit + 1 artifact test, all zero failures/errors/skips, maximum base major 52.
Commands used the dedicated workspace Maven repository/temp directory, wagon
transport, Temurin 1.8.0_504 and Maven 3.9.9: AdvancedCore/pom.xml clean install,
then VotingPlugin/pom.xml clean verify against that exact producer artifact.
Evidence: native-admission-final-build-results.json and corresponding build logs.

Exact final consumer SHA256
7a833c943b731f40fe275d9e1c0a2aaf995e58513a5fc8c0fce974ba1ba9b0fd
passes real Spigot 1.8.8 / Java 8 DrainNotifySQL SQLite acceptance: packaged async
receipt, checked reward snapshot, mutation, uncached write/repopulation, bulk,
removal, vote/reward, points10/total1, clean disable on both runs, integrity and
restart persistence. Logs were separately inspected for disable, linkage,
rejected-executor, unsettled-work and retired-admission errors; none observed.
This is SQLite-only clean shutdown evidence, not live failed-shutdown/MySQL/FLAT/
proxy acceptance. The earlier DrainFixedSQL candidate also passed but does not
prove the later notification-guard candidate.

Remaining lifecycle work is explicit: raw legacy backend APIs and the mutable
cache map can bypass admission; provider replacement, global wipes/migrations,
same-instance disable/re-enable and hot recovery remain unaudited. Producer drain
failure before storage retirement keeps shared admission/provider available for
already queued raw jobs, rather than claiming a global seal. Long delayed rewards
can still exceed their bound; durable root deferral/checkpoint/replay is incomplete.
BungeeHandler.loadGlobalMysql still ignores its old await result and uses
shutdownNow during reload. Its borrowed GlobalMySQL wrapper also unconditionally
disconnects the main pool: GlobalData.UseMainMySQL needs captured provider ownership
before global cleanup can be claimed safe alongside core final cache flush. Redis/
MQTT transport producer retirement needs inspection. No full lifecycle/main parity,
full upstream ledger, final independent-review result or PR readiness is claimed.
No production dependency, config key/default, schema, wire or version changed.


### Captured global-data pool ownership and physical reload fencing

This consumer backport addresses the pool-ownership portion of pinned upstream
VotingPlugin `4a137fd40f507b86a63f0b54fb9298e69fb49184`; the full commit is still
partial. The main pool borrowed by GlobalData.UseMainMySQL is now retained when
global data closes/reloads. An independently created global pool is closed using
its captured creation ownership, even after UseMainMySQL changes. AdvancedCore's
public GlobalMySQL.close behavior is unchanged; the consumer owns this distinction.

BungeeHandler seals new global polling, drains its existing scheduled executor and
all admitted direct protocol polls/period processing, then retires its provider.
The counter is constant-space; no registry, queue or extra executor is introduced.
Bodies capture their provider, arbitrary callbacks run outside the lifecycle
monitor, and physical completion is required before close/replacement. Nested
accepted continuation can finish after sealing; the original public period-method
override dispatch is retained and regression-tested. Self-retirement and concurrent
replacement are rejected before transport side effects. Existing five-second grace
is monotonic/shared across the timer and direct work. False await/interruption is
visible, preserves provider ownership for explicit retry, and never shutdownNow.
Preparation occurs before polling tasks are scheduled. Failed candidate setup stays
sealed/owned for explicit cleanup; legacy GlobalMySQL SQL acknowledgements remain
forgiving and are not newly claimed to be strict initialization evidence.

Full storage reload first drains global readers before configuration/main-pool
replacement. Existing handlers are stopped/closed at disable regardless of current
proxy-enable flags. Switching global data off retires it and restores local time
processing. Initial handler creation is no longer followed by redundant global
replacement in that same reload. These changes do not establish that AdvancedCore
main-provider replacement itself drains every raw/native user operation: that
separate lifecycle work remains incomplete.

Actual Java8 focused native lifecycle tests pass; the direct-poll retirement test
failed on the prior implementation after correcting two fixture issues (wrong
BungeeSettings import, absent mocked GlobalMySQL). Final full consumer clean verify
passes 45 unit + 1 packaged-artifact test, all zero failures/errors/skips, with 2439
base classes and maximum major52. Producer code is unchanged from AdvancedCore
2f7fc42: its exact prior clean install 250 unit + 16 artifact pass and SHA256
cc3bf3f1c8e536421af73d1899368d78b52b1270f3565c3e79eea344f0f1e7f7
are reused, rather than treating a different installed dependency as acceptance.
The consumer was built with Temurin1.8.0_504/Maven3.9.9 and:
`mvn -B -f VotingPlugin/pom.xml -Dmaven.resolver.transport=wagon -Dmaven.repo.local=/workspace/votingplugin-1.8-port-workspace/.m2/repository -Djava.io.tmpdir=/workspace/votingplugin-1.8-port-workspace/runtime/tmp clean verify`.

Final consumer SHA256
1c29e9e01a9598cb14aefd264ed7404304f21f8c57208658d359152641d8a785
passes real Java8/Spigot1.8.8/MariaDB11.8.6 acceptance with ConnectorJ5.1.14 supplied
on the test server classpath. A controlled helper uses the native provider factory:
borrowed pool retained after configuration changes, independent pool closure with
main pool still usable, borrowed-to-borrowed reload, queued main-user points19
persisted at clean disable and restart. Separate final SQLite GlobalCompatSQL
passes async receipt, snapshot, mutation, bulk, removal, vote/reward, points10total1,
both clean disables, integrity and restart persistence. Four final server logs were
independently inspected for disable/unsettled/linkage/rejection/retired-admission
errors and fixture credentials; none observed. All processes stopped and temporary
helper plugin jars removed. Evidence: global-provider-compatible-build-results.json,
global-provider-final-compatible-clean-verify.log, global-provider-mysql-compatible-live.log,
global-provider-mysql-runtime-results.json, global-provider-sqlite-compatible-live.log,
checked-cache-runtime-results-GlobalCompatSQL.json. Initial live fixture readback
wrongly assumed string-valued Points; actual MySQL returned DataValueInt. It failed
before pool tests, was corrected, and is retained as failed fixture evidence.
A restart preflight also rejected a transient socket bind; listener state was
checked, and SO_REUSEADDR fixed the harness check without stealing a live daemon.

No production dependency, release metadata, public signature, config/default,
schema or wire changes. All original checkouts/references remain read-only. This
pool acceptance is not full proxy transport/routing, failed-shutdown, raw API,
main-provider replacement, wipe/migration, same-instance re-enable, or durable
reward root queue/checkpoint/replay acceptance. Async time-change dispatch and
captured-online VoteReminder/VoteShop changes from 4a137fd still need their own
behavior-aware backports. Full ledger/source parity and final independent review
remain incomplete. The earlier global-reload/pool-ownership TODO is superseded
only for the tested native global provider paths described here.

### Native MySQL setter drain and reusable ownership transition

The public setMysql setter now seals new native checked/cache admission, drains
accepted synchronous/queued bodies, acknowledges pending old-pool cache batches,
then closes the predecessor and publishes the candidate. Same-instance assignment
is a no-op, retaining both the live pool and pending batch. Flush uses the existing
final-flush scope so storage callbacks cannot submit more asynchronous work while
provider ownership changes. Change notifications are suppressed for these retired
cache generations; ordinary cache retirement behavior is unchanged. Candidate
ownership stays with the caller if replacement fails before publication.

UserStorageOwnership.replace extends the existing authoritative owner; the fixed
UUID slots/revisions and accepted scopes are never reset. Only a successful
replacement reopens admission. Flush/close failures and interrupted/expired drains
remain visible and sealed for explicit retry. A final retirement request is
permanent even if its drain fails; replacement cannot undo shutdown. Callback work
runs outside the admission monitor. No new executor, queue or production dependency.

Two recorded native regressions failed before the fix (2 tests, 2 assertions,
0 errors/skips), then passed. Extended focused tests pass 27/27. Coordinated
Temurin1.8.0_504/Maven3.9.9 clean install/verify passes AdvancedCore 262 unit +16
packaged tests, VotingPlugin 45 unit +1 packaged test, all zero failures/errors/skips.
Packaged base classes are1822/2439, maximum major52. Producer SHA256
8744b0faae4c29f76c29051bf1c13780c9d7eb5ea7dd9e826f6623f42780cb98;
consumer SHA256 d245b3294414e523b58519687a73b72c8eff6777c221889ad22be459a45911ee.
Exact commands (with JAVA_HOME/PATH set to workspace JDK8):
`mvn -B -f AdvancedCore/pom.xml -Dmaven.resolver.transport=wagon -Dmaven.repo.local=/workspace/votingplugin-1.8-port-workspace/.m2/repository -Djava.io.tmpdir=/workspace/votingplugin-1.8-port-workspace/runtime/tmp clean install`
and the same flags with `-f VotingPlugin/pom.xml clean verify` in the consumer.
Evidence main-provider-rebind-clean-install.log,
main-provider-rebind-consumer-clean-verify.log,
main-provider-rebind-coordinated-build-results.json.

Real packaged Java8/Spigot1.8.8/MariaDB11.8.6 acceptance at that consumer hash passes
same-instance main assignment retaining pool/pending batch, native replacement
flushing pending points17, global borrowed/independent ownership, clean disable
flushing points19 and restart persistence. ConnectorJ5.1.14 remains an explicit test
server classpath fixture, not a new production dependency. Evidence
main-provider-rebind-mysql-live.log/global-provider-mysql-runtime-results.json.

This is not full configuration-driven reload acceptance. loadConfig currently
mutates Options before selecting a new provider; its full old-config/type cache
ordering still requires fenced integration. Public loadUserAPI SQLite replacement,
FLAT transitions, convertDataStorage, raw low-level provider callers and same-instance
plugin re-enable remain unfinished. Do not infer those guarantees from direct
setMysql tests with unchanged storage configuration. Full upstream ledger,
durable reward root processing and final independent review also remain incomplete.

Exact consumer also passes MainRebindSQL2 SQLite async pipeline/snapshot/mutation/
bulk/removal/vote/reward/points10,total1/clean disable/integrity/restart. The first
MainRebindSQL fixture attempt lacked its temporary command helper and timed out
before acceptance markers; the harness now owns install/cleanup and uses a fresh
identity. This fixture-only failure remains recorded, not treated as product
evidence. Four final MySQL/SQLite server logs have no lifecycle/linkage/failure
markers or fixture credentials. Evidence main-provider-rebind-sqlite-fixed-live.log
and checked-cache-runtime-results-MainRebindSQL2.json.

### Configuration reload: flush before settings and provider publication

Native loadConfig(true) now seals/drains existing storage admission and flushes
pending cache batches under the old type/config before Options.load or provider
creation. The current replacement publisher may compose the MySQL setter without
starting another retirement or reopening admission midway. Permission is
thread-local, unavailable during final flush, unavailable to other threads, and
cleared on failure. The redundant post-publication cache retirement is removed
for this path. Options-only/disabled-user-data paths retain legacy behavior.
No release/config/schema/wire/dependency change.

The native old-options/cache-order regression fails on prior code (1 assertion,
0 errors), then passes. Extended focus19 passes, including nested setter, failed
flush/preparation, retry, shutdown prohibition and thread-local publication.
The initial full build exposed an order-dependent LegacyRewardShutdownTest fixture:
RewardHandler eager singleton initialization requires a plugin/datafolder context.
Isolated baseline3errors reproduced it. Its fixture now supplies that context and
closes singleton test timers; all original production shutdown assertions remain.
No production reward change was needed. Initial failed logs are retained.

Actual Temurin1.8.0_504/Maven3.9.9 full coordinated build: AdvancedCore clean install
269 unit+16artifact, VotingPlugin clean verify45unit+1artifact, all0fail/errors/skips.
Use module POM with -Dmaven.resolver.transport=wagon,
-Dmaven.repo.local=/workspace/votingplugin-1.8-port-workspace/.m2/repository and
-Djava.io.tmpdir=/workspace/votingplugin-1.8-port-workspace/runtime/tmp.
Producer SHA256 fc0c32014290f52b0b7412af5b5912587d54126b737b863ad495be47d0205c74;
consumer98b0323b9f99a7abfe5a9e6f5e4f5b6ab86d29281ba1d743116efbf91c4bec5e.
Base classes1822/2439, maximum major52. Evidence
configuration-storage-reload-final-clean-install.log,
configuration-storage-reload-consumer-clean-verify.log,
configuration-storage-reload-build-results.json.

Real Java8/Spigot1.8.8/MariaDB11.8.6 fixture9166cde10e passes VotingPlugin.reloadAll
on the server thread with pending points18, retaining main pool usability and
persisted data; native setter/global ownership tests also pass. Final points19
flush at clean disable and restart. ConnectorJ5.1.14 is test classpath only.
Exact consumer separately passes SQLite ConfigReloadSQL async pipeline/snapshot/
mutation/bulk/removal/vote/reward/points10total1/clean disable/integrity/restart.
Four final server logs contain no lifecycle/linkage/failure or fixture credentials.
Evidence configuration-storage-reload-live-results.json and mysql-live/sqlite-live logs.

Not established: cross-provider changes, SQLite predecessor connection retirement,
public loadUserAPI outside this fenced config path, lazy initialization and
convertDataStorage migration ownership, raw user/provider APIs, options-only
storage-type changes, same-instance re-enable, blocked server-owner callbacks
across reload, failed-shutdown or full proxy routing. The entire remaining
upstream ledger/features/durable root queue/final independent review remains
unfinished. This supersedes the old Options-before-flush TODO only for tested
native storage-config reload. Do not treat the setter/config tests as a full
provider/migration guarantee.

### Public provider initialization and SQLite predecessor retirement

The native public `loadUserAPI` now uses the existing storage admission/replacement
owner. Initial lazy SQLite bootstrap keeps public override dispatch, coalesces
concurrent readers, and runs inside accepted admission instead of retiring its own
read. Final retirement prevents both public and lazy provider resurrection before
factory side effects. SQLite candidates are prepared before publication; successful
replacement closes the predecessor's stored physical connection. Cleanup must not
use `Database.getConnection`, which can reopen a retired connection in the pinned
SimpleAPI. Unpublished factory-owned candidates are cleaned on preparation/close
failure, retaining the original failure and sealing admission until explicit retry.

Explicit initialization of a second storage type retains the first provider:
`convertDataStorage(from,to)` requires both. This cohort does not fence or validate
the entire conversion loop. Public signatures, configuration, schema, dependency,
release and proxy payload versions remain unchanged.

The corrected unchanged-native baseline produces 2 assertion failures, 0 errors;
the first attempt lacked the fixture's required static plugin context and is not
product evidence. Final focused lifecycle/config/setter/admission tests: 27 pass.
The extended first attempt also exposed a Mockito stubbing-order fixture error,
fixed without weakening assertions. Final lifecycle class contains 10 tests.

Actual Temurin 1.8.0_504 / Maven 3.9.9 coordinated commands (from each fork):
`mvn -B -f AdvancedCore/pom.xml -Dmaven.resolver.transport=wagon
-Dmaven.repo.local=/workspace/votingplugin-1.8-port-workspace/.m2/repository
-Djava.io.tmpdir=/workspace/votingplugin-1.8-port-workspace/runtime/tmp clean install`
and the same flags with `-f VotingPlugin/pom.xml clean verify`. Both run with the
workspace Java 8 JAVA_HOME/PATH. AdvancedCore: 279 unit + 16 packaged-artifact checks;
VotingPlugin: 45 unit + 1 artifact check; all zero failures/errors/skips.
Producer SHA256 `ac61e7565d5969803260fb221f7c50a23d9056b2d8b25c3741c76f328a6dc434`;
consumer `2cd194972ee1cc35235825cd95a3b7b629c25c0b01a514d179a736bbbf35a12b`.
Base class counts 1822/2439; maximum class-file major 52. Evidence:
`sqlite-provider-build-results.json`, `sqlite-provider-clean-install.log`,
`sqlite-provider-consumer-clean-verify.log`, `sqlite-provider-extended-fixed-test.log`.

Exact consumer real Java 8 / Spigot 1.8.8 SQLite acceptance: full configuration
reload closes the captured predecessor, public SQLite reinitialization closes its
predecessor and preserves stored marker data, temporary fixture marker is restored,
async reward/cache mutations, online vote, points 10/total 1, clean disable,
SQLite integrity and restart persistence pass. NuVotifier 2.7.2, PlaceholderAPI
2.11.6 and Vault 1.7.3 are the fixture integrations.
Real MariaDB 11.8.6 acceptance also proves SQLite initialization retains the main
MySQL source, pending points 18 survive configuration reload, final points 19 persist
on clean disable and restart, and the preceding global-pool ownership checks pass.
ConnectorJ 5.1.14 is supplied only on the test-server classpath, not as a new
production dependency. Four final server logs contain no tested lifecycle/linkage/
failure markers or fixture credentials. Evidence: `sqlite-provider-live-results.json`,
`global-provider-mysql-runtime-results.json`,
`checked-cache-runtime-results-SQLiteOwnerSQL.json` and corresponding live logs.

Not established: full storage conversion, active type-switch acceptance, raw provider
handle protection, options-only type reload, direct core MySQL replacement while
borrowed global readers remain active, same-instance plugin re-enable, blocked
server-owner callbacks during reload, failed shutdown, or complete proxy routing.
SimpleAPI constructors/schema helpers may swallow failures before a candidate is
returned; this is not strict schema-initialization acknowledgement. The complete
upstream ledger, remaining features and fresh independent final review remain
unfinished. No source push or PR creation is authorized yet.

### Conversion maintenance admission and provider reuse

Native `convertDataStorage` now drains accepted work and flushes old pending cache
batches once, then keeps the same storage owner sealed through provider setup,
source enumeration and the complete copy loop. Only the current synchronous
maintenance thread may enter nested storage operations. It cannot enqueue async
work or recursively retire the owner. Leaked admissions prevent reopening. Failure
removes thread privileges and leaves admission sealed; reconcile partial committed
work before explicit retry. This is not a transactional rollback of copied rows.
Open source/destination providers are reused, avoiding recreation/closure of the
active MySQL source. Public provider initialization remains independently supported.
No configuration, schema, release version, dependency or proxy format changes.

Unchanged native baseline: 2 tests / 2 assertion failures / 0 errors, proving source
recreation and foreign writer admission during enumeration. Extended focus: 26 pass,
including 5 native conversion and 4 maintenance regressions plus retained replacement
and SQLite lifecycle tests. Source/copy failure retains the original exception;
final retirement, queued-work drain, nested sync writes, rejected async work and
leaked scope recovery are covered. `conversion-maintenance-baseline-test.log` and
`conversion-maintenance-extended-test.log` retain exact results.

Actual Java 8 clean install/consumer clean verify with the same explicit workspace
Maven repository/tmp/wagon flags documented above: AC288unit+16artifact,
VP45unit+1artifact; all0fail/errors/skips. Baseclasses1822/2439, maxmajor52.
AC SHA256 f468ce7994efadbc607c5bd383f96db8a8325f63708d2b19707e9e6cce8c9601;
VP SHA256 10f16ea3c522772df55828a542c35fcccd89308a7b5e1ec3c3d839c547ef3c04.
See `conversion-maintenance-build-results.json` and clean-build logs.

Exact consumer Java8/Spigot1.8.8/MariaDB11.8.6 fixture d435184d5d: native conversion
flushes pending points19, retains the original main MySQL provider, copies points19
to SQLite, then pending points20 persist on clean disable/restart. Existing global
borrowed/owned-pool, setter and config-reload checks also pass. Independent offline
SQLite integrity and target-row points19 verification pass. Both final server logs
contain no tested runtime failure markers or fixture credentials. ConnectorJ5.1.14
remains fixture-classpath-only. Evidence `conversion-maintenance-live-results.json`
and `conversion-maintenance-mysql-live.log`.

Still incomplete: strict full-source SQL enumeration (legacy queries can return
partial maps after failure), explicit FLAT-source enumeration, asynchronous
completion/server-thread API behavior, transactional/partial-copy recovery,
inactive-provider configuration drift, raw APIs that bypass admission, and borrowed
global readers during direct external parent replacement. Do not claim full migration
safety, whole upstream runtime parity or PR readiness from this cohort. The full
remaining ledger/features/runtime matrix and final independent review remain required.

### Complete conversion source reads

Conversion now fully materializes its requested source before creating a missing
destination. Additive `UserManager.getAllKeysStrict`, MySQL/UserTable
`getAllQueryStrict`, and FileThread `getAllValuesStrict` leave legacy query APIs
unchanged. Strict SQL reads propagate preparation/iteration failures, reject missing
or duplicate column/normalized UUID identities, malformed integers/booleans and
unacknowledged outer transactions. SQL null integer/boolean defaults remain 0/false;
boolean true/false and numeric 1/0 are supported. Borrowed MySQL connections are
closed, the shared SQLite connection stays with its owner. FLAT enumeration reads
its own Data directory under the existing FileThread lock, preserves scalar values
and source bytes, rejects malformed YAML/structured values/duplicate identities,
and never resolves through the active SQL store or starts the legacy polling thread.
No schema, configuration, release, dependency or wire changes.

Corrected native SQLite baseline: 2 assertion failures / 0 errors. Earlier attempts
had fixture overload/stubbing errors and an incompatible unshaded dependency pool
on the raw Java8 unit classpath; they are not product evidence. MySQL coverage uses
the actual packaged Java8 relocation instead. Final focused21pass; complete actual
Temurin1.8.0_504/Maven3.9.9 builds with the same explicit workspace .m2/tmp/wagon
flags above: AC300unit+18artifact, VP45unit+1artifact, all0fail/errors/skips.
SHA256 AC8ecb10bb9a4ebc3531a67c3f0a27af1e3287e5a3115d4a844d914692647a0ac2;
VP16531f7ebb9c06a878455a9d9a41e2ebd5a526cc369ee956a7d7930b4e3cb135.
Baseclasses1823/2440, maxmajor52. Packaged MySQL complete-read success/failure and
borrowed connection closure actually ran. Evidence complete-source-build-results.json,
complete-source-final-baseline-test.log, complete-source-extended-test.log and build logs.

Exact consumer realJava8/Spigot1.8.8/MariaDB11.8.6 fixturefd5a392c78: pending19
MYSQL-to-SQLITE copy retains main source pool; explicit FLAT source points21 and
marker copy to SQLite while currentMYSQL stays19 and FLAT bytes unchanged. Final
pendingMYSQL20 survives clean shutdown/restart. Previous global pool/setter/config
reload checks pass. Independent offlineSQLite integrity and points21/FLAT marker
readback pass. Both final logs contain no tested failure markers or fixture secrets.
ConnectorJ5.1.14 remains fixture classpath only. Evidence complete-source-live-results.json
and complete-source-mysql-flat-live.log.

Remaining: async completion/server-owner behavior, partial-copy reconciliation,
inactive provider configuration drift, duplicate YAML keys (the legacy YAML loader
may collapse them), concurrent external SQL/file changes and raw APIs that bypass
admission. SQL auto-commit enumeration is not a transactional network-wide snapshot.
No complete-migration, final-review or full upstream parity claim. Full goal active.

### Conversion completion and server-thread entry points

Additive `convertDataStorageAsync` uses the existing canonical scheduler dispatcher
and waits for the actual synchronous conversion body. The void API still completes
synchronously for worker callers; server callers dispatch off-owner and receive safe
failure diagnostics. Public virtual conversion dispatch is retained. No new executor
or storage owner was added, and maintenance is not run inside an admitted submit
scope that would retire itself. Queued work can be rejected/fenced; accepted physical
work is not cancelled or declared unfinished merely because its dispatcher closes.
The receipt deadline bounds scheduler admission, not execution after admission.

Both ConvertToData and ConvertFromData commands use the completion stage, deliver
starting/final messages through the server owner, and report failure without raw
provider exception details. Failed result-message delivery is logged separately and
never replays a committed conversion. Commands, permissions, argument syntax and
normal messages remain; no config/schema/release/dependency/wire change.

Native unchanged server-thread baseline fails 1 assertion/0 errors. Final focused24
pass (5 completion, 3 command, retained6 conversion and10 dispatch tests). The first
expanded attempt had a test-only ambiguous Logger.severe overload, corrected without
changing production/assertions. Actual Temurin1.8.0_504/Maven3.9.9 coordinated builds
with explicit modulePOM/workspace .m2/tmp/wagon flags: AC308unit+18artifact,
VP45unit+1artifact all0fail/errors/skips, baseclasses1823/2440 maxmajor52.
SHA256 AC030cab38a344e60cceb5f82e44b7a65412681079a19ef957d8dc5f639c45874b;
VP aec32bac9c9d4774376f5bbbe6dc0771a21e54e8c935ab36134eed8aac8391c2.
Evidence conversion-completion-build-results.json, baseline/fixed-extended/buildlogs.

Exactconsumer Java8/Spigot1.8.8/MariaDB11.8.6 fixture052d74e1ab: conversion stages
requested on real server thread complete MYSQL->SQLITE pending19 and explicitFLAT21
with source preservation. Independent FLAT marker/points readback runs before commands.
Actual console `av ConvertToData SQLITE` and `av ConvertFromData SQLITE` each report
completion, followed by MYSQL and SQLite points20 readback. Clean disable/restart20
and previous global/main provider checks pass. Final offlineSQLite integrity/points20
pass; two server logs contain no tested failure markers, lost-message warnings or
fixture credentials. ConnectorJ5.1.14 remains test classpath only. Evidence
conversion-completion-live-results.json and conversion-completion-mysql-flat-live.log.

Not proven: transactional/partial-copy recovery, inactive-provider config drift,
duplicate YAML keys, concurrent external/raw storage changes, failed shutdown,
same-instance re-enable or broad async Bukkit/debug/user API audit. Caller overrides
that internally schedule hidden work cannot acquire physical completion merely from
a void return. Full upstream ledger/features/runtime matrix/fresh independent final
review remain unfinished. Full goal active; no PR opening/source push authorized.


### Explicit flat-source YAML validation and retry acceptance (2026-10-06)

Conversion now captures one UTF-8 source document and validates its SnakeYAML
syntax nodes before passing that same text to Bukkit's legacy loader. This avoids
silently choosing the last duplicate key. Quoted/escaped equivalent keys, nested
duplicates, non-scalar keys and recursive aliases fail before copying. Scalar
aliases and UTF-8 text remain supported. This uses the existing Spigot SnakeYAML
API, without a dependency, version, schema, configuration or wire-format change.
Normal configuration loading is unchanged; validation applies to explicit user
source conversion. The unchanged duplicate-key regression failed before the fix.

A focused fault-injected adapter regression also establishes the existing partial
copy contract: acknowledged absolute assignments remain, source rows are retained,
the original failure reaches the caller and ordinary admission stays sealed.
An explicit successful retry assigns the same values rather than adding them,
then reopens admission. This is not transactional rollback, a durable migration
journal, cross-process recovery, or proof of live database-outage recovery.

Final focused conversion/source/completion/retry tests: 18 passed. Actual Java 8
coordinated `clean install` (AdvancedCore) and `clean verify` (VotingPlugin), using
explicit module POMs, workspace-local Maven repository/tmp and wagon transport:
AdvancedCore 311 unit + 18 artifact tests; VotingPlugin 45 unit + 1 artifact test.
All have zero failures/errors/skips; 1823/2440 base classes, maximum major 52.
Artifact SHA256:
AdvancedCore `9f3af91126ba125f7cf86fc9b96bd7223ab300c60eeb03766e1024b6ec6c2495`;
VotingPlugin `597647f2f5f2925b24f378bdfe436f6688697d6d3b1f5f7b9105d5bb505dcea8`.

The exact consumer ran on Java 8/Spigot 1.8.8/MariaDB 11.8.6 in isolated fixture
fd993131b9. Duplicate source rejection preserved source bytes, prior SQLite21
and active MySQL19. Explicit repair/retry produced SQLite22 with independent
readback and reopened admission. Actual `av ConvertToData SQLITE` and
`av ConvertFromData SQLITE` then completed and produced points20; clean disable
and restart retained20. Offline SQLite integrity passed. Both server logs passed
failure-marker and credential-absence audits. ConnectorJ5.1.14 was supplied only
on the test server classpath. Evidence: conversion-retry-yaml-build-results.json,
conversion-retry-yaml-live-results.json, focused/build/live logs under the isolated
workspace's evidence directory.

Still unverified: physical mid-copy database failure, inactive-provider config
drift, concurrent external/raw storage mutation, failed shutdown and the remaining
full upstream ledger/runtime matrix. The bounded local tooltip draft was empty
and inconclusive, so no claim or change was adopted from it. Tooltip adaptation
and the full independent final review remain pending. No PR or source push.


### Item tooltip option adapted for Spigot 1.8 (2026-10-06)

Upstream `f9da02380781a6ddb6db0b62bbb2717090d1bc50` and
`d03ad2b537869797f0be3ae1330a0db4c55b0069` are ported with Bukkit 1.8
adaptation. Item configuration `HideToolTip: true` adds every available legacy
ItemFlag, including for serialized ItemStack configurations. Missing/false does
nothing and preserves previously configured flags. The public
`setHideTooltipCompat(ItemStack, boolean)` signature is retained; true adds flags,
false removes them, and updated metadata is published to the item. Null items or
metadata are safe. No native modern API call, reflective fallback, stacktrace,
or upstream unconditional chance-linked setter is copied into the Java 8 fork.
Spigot 1.8 cannot hide the entire tooltip: custom names and lore remain visible.
No dependency, release version, stored format or proxy payload change is needed.

The unchanged baseline failed one assertion with zero errors. Four final focused
tests pass. Actual Java 8 clean install/verify: AdvancedCore315 unit+18 artifact,
VotingPlugin45 unit+1 artifact, all zero failures/errors/skips. Base classes
1823/2440, maximum major52. SHA256:
AC `287b56804c141bbedfff5d5457a3b14d62df0e28cd77257c83c5312f4d633b4d`;
VP `9fe6fb740b8e3cff39022d0ed5bc33d423985e7b2ad6b77d505e5a231e09b610`.
Build commands retain explicit modulePOM, workspace-local .m2/tmp, wagon transport,
Temurin1.8.0_504 and Maven3.9.9.

Real Spigot1.8.8 fixture6b263d1fb6 exercises actual ItemMeta on the server owner:
normal and serialized configured items, all supported flags, missing/false
preservation, explicit unhide and unchanged custom names/lore. Existing native
MySQL/SQLite/FLAT lifecycle, duplicate-source failure/repaired retry, both console
conversion commands, clean disable/restart and final points20/integrity pass.
The first helper attempt used Arrays.asList where the pre-existing loader expects
ArrayList; the fixture alone was corrected, and its failed terminal evidence is
retained. Assertions/product behavior were not weakened. Final two logs contain
no tested error markers/fixture credentials. Test driver remains classpath-only.
Evidence: item-tooltip-baseline-test.log, item-tooltip-focused-test.log,
item-tooltip-build-results.json and item-tooltip-live-results.json.

This completes only the two recorded upstream tooltip dispositions. Full upstream
ledger, remaining features/lifecycle issues, runtime matrix and fresh independent
final review remain pending. No source push or PR opening.


### Independent item templates and builder clones (2026-10-06)

Upstream `15c078998727a4888b9468608e6026c9bbd05c3c` is ported: the ItemStack
constructor copies its non-null input, so ItemBuilder.clone() also owns an
independent mutable item. The existing nullable constructor remains accepted.
This prevents GUI/template and placeholder changes from mutating the caller's
item. Consumers inspected include BInventoryButton construction/setItem,
AdvancedCoreUser's placeholder-aware item delivery, VotingPlugin VoteSite display
items, VoteToday icons and VoteShop editors (which already explicitly clone).
These consume the builder/result; no intended template mutation was identified.
No public signature, config, serialization, schema, dependency or wire change.

Unchanged baseline: two failures/zero errors. Final ownership+tooltip focused
suite: six passed. Java8 clean install (AC) and coordinated clean verify (VP):
AC317unit+18artifact, VP45unit+1artifact, zero failures/errors/skips. Baseclasses
1823/2440, maximum major52. SHA256:
AC `35af32a1fa56076dce7bd280d9cab4cfbb2d08ee65912058c5f202cc410bf3eb`;
VP `5362c5cd683825251ee78632d779f7eb468e9fdb230a4b9b61d79ff769da9bc1`.
Exact commands retain Temurin1.8.0_504/Maven3.9.9, explicit modulePOM,
workspace-local Maven repository/tmp and wagon transport.

Actual Spigot1.8.8 fixture7a34d41c0e verifies on the server owner that constructor
and builder clones are distinct, caller amount/name remain unchanged, metadata
flags/lore/durability survive copying, and subsequent caller lore mutation does
not affect its copy. Retained tooltip and native storage conversion/retry/command
checks also pass, with clean shutdown/restart20 and SQLite integrity. Two logs
contain no tested errors or fixture credentials. Evidence:
item-ownership-build-results.json and item-ownership-live-results.json, plus
baseline/focused/build/live logs in the isolated workspace evidence directory.

Exact upstream `36648206e2154314ccc4ca5f1f5592a0b21b7818` only removes the
modern tooltip-reflection stacktrace; the fork's direct1.8 ItemFlag adaptation
already avoids it. `c65c4d71ab0e47f2fabc713a6dc7d218af8ce3a2` silences modern
addGlow reflection; the fork retains its non-reflective legacy enchantment
implementation, without importing the modern call. Both dispositions preserve
the existing1.8 implementation. Documentation-only
`a70ded0072f5e1d24ce486676a68968fc66c4e16` is represented by this ownership
rule: api/item owns item construction and compatibility-sensitive serialization;
api/inventory owns GUI/editor behavior. Preserve those boundaries in later work.

Full upstream ledger/features/lifecycle/runtime matrix and independent final
review remain unfinished. Source commits are local; no push or PR opening.


### Damage and leather configuration: partial upstream port (2026-10-06)

Upstream158dec28c7be447b57882a78c64c7a1aaa0bb826 is PARTIAL, not completed.
Damage takes precedence over Durability, including explicit zero. MissingDamage
preserves the legacy Durability path. Public setDamage(int) uses 1.8 durability
only for materials with positive maximum durability; other material variant data
is untouched. Negative or aboveShort.MAX_VALUE damage is rejected for damageable
items instead of silently truncating into the legacy short field. This adaptation
uses no modernDamageable API. LeatherColor.Red/Green/Blue uses existing legacy
leather metadata; absentcolor retains default. Existing setDurability remains.
Example portable configuration:

```yaml
Material: LEATHER_CHESTPLATE
Amount: 1
Damage: 5
LeatherColor:
  Red: 12
  Green: 34
  Blue: 56
```

Baseline2fail/0errors; final4damage/color +6retained item tests pass. ActualJava8
ACcleaninstall321unit+18artifact and VPcleanverify45unit+1artifact, all0fail/errors/
skips; baseclasses1823/2440 maxmajor52. Exact modulePOM/workspace .m2/tmp/wagon
commands retain Temurin1.8.0_504/Maven3.9.9. SHA256:
ACe1f7088aa4d0c7d0e71683c7b457bcc45295e6f88a394bb4815f17bdc3f24b54;
VPf3126b4de089814058c52637ef4056dd0bcc32fb4792e9c3335a991b0f15d718.
ActualSpigot1.8.8 fixturee0d94f1691 owner-thread checks damage precedence/missing/
zero/publicsetter bounds, preservedwoolvariant, leatherRGB/defaultcolor and retained
itemownership/tooltip. Existing SQLFLAT conversion/retry/commands/disable/restart20
alsoPASS. SQLiteintegrity and two log error/credential auditsPASS. Evidence
item-damage-color-build-results.json/item-damage-color-live-results.json.

Still pending in this upstreamcommit: portable serialization expansion, aliases,
createConfigurationData coordination and originalexample resource changes; modern
metadata omissions need individual evidence. Current serializers are unchanged,
so no storedformat migration is claimed. Damage sampling/breakage upstream
7d9297a5228629a1bbd0d2aa67ad670540702aa4 and dcc9b1ea7de6d39ac777dac5826c6c93abb282b9
remain pending separately. Fullscope ledger/lifecycle/features/runtime/freshfinal
independentreview unfinished. No release/dependency/schema/wire change or sourcepush/PR.


### Portable item serialization completes upstream158 disposition (2026-10-06)

Upstream158dec28c7be447b57882a78c64c7a1aaa0bb826 now has a recorded disposition
for every changed file/hunk. The previous partial record is superseded by this
PORT_WITH_BUKKIT_1_8_ADAPTATION, not a claim of full pinned-main equivalence.

- ItemBuilder serializers share the typed legacy configuration representation;
  getConfigurationData(boolean) alias is added, true retains Bukkit full data.
- Legacy Durability/Data and createConfigurationData's empty Skull default remain.
  Damage, regular/stored book enchants, flags, names/copied lore, leatherRGB,
  custom potion duration/amplifier, fireworkpower and named skull owner are added.
- Spigot1.8 unbreakable metadata reads/writes use its actual extension. A plain
  Bukkit base placeholder is omitted on reads, and requested unsupported writes
  still throw; no broad exception swallowing. Missing config leaves state alone.
- Enchantment/potion identifiers retain legacy getName, and skull getOwner replaces
  unavailable modern getOwningPlayer. Stored/regular enchants merge as upstream.
- Modern custom models, ItemModelHandler.hasItemModel guard, potion bottle colors
  and native full-tooltip flag are omitted: javap of the actual1.8 API shows those
  methods absent; ItemModelHandler is absent from the fork. Existing ItemFlags
  preserve supported tooltip details without inventing a full-tooltip state.
- Upstream example comments are adapted for Damage/leather/unbreakable on1.8.
  Parsed YAML defaults before/after are identical; no operator config replacement.

Baseline2assertfail/0errors; final18focused tests pass (8serialization+10retained).
Intermediate test fixtures initially used unsupported baseSpigot and inline mock
class identity; corrected to model actual implementations and retain the explicit
base-placeholder countertest. Real runtime validates supported metadata separately.
Java8 finalcleaninstall AC329unit+18artifact; cleanverify VP45unit+1artifact,
all0fail/errors/skips; baseclasses1823/2440 maxmajor52. ActualTemurin1.8.0_504/
Maven3.9.9, explicit modulePOM/workspace .m2/tmp/wagon. SHA256:
AC75e41e7116234b7eff71ed84a2476cae6c0b0212c9c9746904a07d8882b2cbbd;
VP8780ce4fd38882e1cefdac0dc1c9095e4da4e7d4ad71761dd63030a4ad55a0cc.

RealSpigot1.8.8 fixturef6c11804c6 compares exact original/restored item equality
following YAML save/load for each of getConfiguration(false),
createConfigurationData(), getConfigurationData(false), and the fulltrue lane.
Cases: damaged/named/lore/flagged/unbreakable/enchanted leather armor, coloredwool,
stored-enchant book, custompotion, fireworkpower. AllPASS on server-owner thread.
Retaineditem/damage/tooltip/storageconversionretry/commands/disable/restart20PASS.
SQLiteintegrity and two log error/credential auditsPASS. Skullowner strings are
unit-tested; remote skull resolution has not been exercised and is not claimed.
Simplified serialization does not promise all arbitraryNBT; exact Bukkit lane is
retained. No dependency/schema/wire/releaseversion or existing serializedformat
replacement. Evidence item-serialization-build-results.json,
item-serialization-live-results.json, item-serialization-api-contract.txt.

Full goal active: pending ledger/features/lifecycle/proxy/upgradematrix and fresh
independentfinalreview remain; no sourcepush or PR opening authorized.


### Legacy held-item damage API and bounded breakage (2026-10-06)

Upstream7d9297a5228629a1bbd0d2aa67ad670540702aa4 and
dcc9b1ea7de6d39ac777dac5826c6c93abb282b9 complete production/test patches are
ported with Java8 adaptation. The original public API introduction
8c26aa69d71310ebe8166d93e01c83391b677928 predates the identified main release-copy
ancestor, but damageItemInHand was absent from the fork's baseline. A presence
regression fails1assertion/0errors before this change; the supported API is added.
No existing public method, config, schema, wire or release metadata is replaced.

Java8 Random replaces RandomGenerator; legacy hand access/durability replaces
modern main-hand/Damageable calls. Callers must use the Bukkit owner thread.
Normal updates explicitly publish the held slot; exact material limit removes the
item and returnsfalse. Missing/non-tool/protected items returnfalse; nonpositive
damage leaves eligible items alone. ImplementedSpigot unbreakable metadata is
required; unsupported base protection throws instead of damaging a possibly
protected item. Long arithmetic avoids enchantment/damage overflow. Geometric
sampling bounds work by remaining durability; original integer-percentage chance
semantics are retained, including zero chance at extreme enchantment levels.
Nonpositive internal sampler caps returnzero rather than negative damage.

Ten focusedtests pass, retaining all six upstream cases/statistical assertions
and adding APIpresence, capwork/invalidcaps, extremelevels and unsupportedflag
coverage. FinalJava8 cleaninstall AC339unit+18artifact; cleanverify VP45unit+
1artifact, all0fail/errors/skips; base1823/2440 maxmajor52. ExplicitmodulePOM,
workspace .m2/tmp/wagon, Temurin1.8.0_504/Maven3.9.9. SHA256:
AC06faaf57f4c5c18f9bb0ead00b2e2af519a53936b1f49bac84a2593c00f30e50;
VP1c90b84dbdd43ca8afe330eea9c03bf3927092204f9d558d175ed579f916b773.

RealSpigot1.8.8 fixture1169d40353 uses an unregistered CraftPlayer/native inventory
on the server owner, checking actual slot readbacks for damage58, exact-limit
removal, Integer.MAX_VALUE damage with Unbreaking, protection/non-tool/no-op.
No connectedclient, world/player-list registration or join event is claimed.
Version-specific NMS exists only in the workspace fixture, not production.
The initial dynamicproxy fixture failed on legacy incompatible health-method
signatures. The native fixture then caught a real callee adaptation error:
legacyMiscUtils.getEnchant compares toString instead of name and returnsnull.
The new API now directly uses the supported DURABILITY constant; shared helper
behavior is not changed. Both failed terminal attempts are retained, assertions
unchanged. Exact finalconsumer also passes retaineditem/YAML/storageconversion/
retry/commands/disable/restart20; SQLiteintegrity and two log error/credential
auditsPASS. Evidence item-damage-api-final-build-results.json and
item-damage-api-final-live-results.json plus focused/build/failed/live logs.

Connectedclient slot acknowledgment remains unverified. Full upstream ledger,
remainingfeatures/lifecycle/runtime matrix and freshindependentfinalreview are
unfinished. No sourcepush or PR opening authorized; full goal remains active.


### Shared inventory pagination boundaries (2026-10-06)

The complete upstream helper and regression-test patches at
`22b51a43f4389a241bf0e6b39df59bd77db4944d` and
`fb9534fb4ee8abdb3fe40806a1307fe5e2135fac` are ported unchanged.
The pagination portion of `fbccf80091d3d680505dbdf1b1ca218ba3cd5084`
is integrated into legacy `BInventory` and its click listener. The remainder
of that upstream commit, including per-viewer timer ownership and close/open
lifecycle changes, is still pending; this is not a complete GUI lifecycle port.

Page counts include the highest zero-based button slot, so a button at slot 90
in a 54-slot paginated GUI remains accessible on page three. Rendering and click
mapping share the same content-size calculation. The nine-slot legacy minimum
has one content slot instead of dividing by zero. Requested pages below one
throw before creating an inventory; oversized requests clamp to the last page.
The published session, current-page/total-page placeholders, and source offsets
use that clamped page. Disabling pagination resets the exposed maximum page to
one. Existing size normalization, public APIs, legacy navigation materials,
configuration defaults, storage, and proxy formats remain unchanged.

Before production changes, five integration tests produced four assertion
failures and zero errors: missing final boundary page, invalid-page acceptance,
missing clamping, and nine-slot division by zero. The final focused run passes
11 tests: three unchanged upstream helper tests, six production integration/
listener tests (including both 54- and nine-slot mappings), and two retained
timer regressions. Actual Java 8 clean install passes AdvancedCore 348 unit plus
18 artifact tests; VotingPlugin clean verify against that exact locally installed
artifact passes 45 unit plus one artifact test. All failures, errors, and skips
are zero. Base class counts are 1824/2441; maximum class-file major is 52.
Both commands use Temurin 1.8.0_504, Maven 3.9.9, their explicit module POMs,
workspace-local Maven repository/temp paths, and the wagon resolver.
Artifact SHA-256:

- AdvancedCore: `617196926b2d11f5f268c2c25c72a05b9eea63ce44dbd69228789a14e2fab05b`
- VotingPlugin: `23f9e74d173758c40fc284c04d98cc20a1bf531480b9267eaf4b03f24470b23c`

Real Java 8 / Spigot 1.8.8 fixture `d1f7784a50` verifies native inventory contents,
GUI/session identity, exact page publication multiplicities, navigation slots,
clamping, invalid-page rejection, and graceful disable. Its unregistered native
CraftPlayer overrides only inventory publication to capture actual native
inventories. This does not prove connected-client rendering, inventory-view
activation, or final-open ordering. Workspace-only NMS/capture helpers are never
included in either production artifact or committed source.

Failed fixture attempts are retained. The first assumed that a separate scheduled
check would run after all five inventory opens; only one open had arrived. The
next attempt observed all five but assumed FIFO completion. Native observations
proved the correct inventory contents arrived in a different order. The corrected
fixture waits for the fifth physical capture and validates every GUI/page identity
and exact count independently of scheduler order. Production code and semantic
assertions did not change during those fixture corrections. A combined storage
fixture was interrupted by the early failed GUI assertion; its cleanup overlap
is not attributed to product storage. The workspace runner now waits for the
owned storage fixture body to settle before failure cleanup.

Evidence: `pagination-baseline-test.log`, `pagination-focused-test.log`,
`pagination-clean-install.log`, `pagination-consumer-clean-verify.log`,
`pagination-build-results.json`, `pagination-runtime-d1f7784a50.json`, and
retained private fixture logs/results. Connected-client GUI acceptance,
viewer-timer lifecycle, the remaining full upstream ledger/implementation/runtime
matrix, and fresh independent final review remain unfinished. No source push or
PR opening has been authorized; the full objective remains active.


The exact final consumer also passed retained native item/serialization/damage,
MySQL/global borrowed/owned lifecycle, conversion/retry/console commands, pending
write shutdown, and restart persistence in fixture `2928e89d6e`. Its owned fixture
body settled before cleanup. SQLite integrity and both runtime log/error/credential
audits passed. Evidence: `pagination-final-live-results.json`,
`global-provider-mysql-runtime-results-2928e89d6e.json`, and `pagination-live.log`.


### Viewer timer ownership and Bukkit-thread item updates (2026-10-06)

The updating-button lifecycle patches at
`82dff7bedb759f8352b5a24aa217afbdbb76cbb1`,
`8021c3168cc655e0caaec95825f2180db0b75622`, and
`04eed7b7612be2244c26392b9b385055442e4c12` are ported with legacy inventory
adaptations. The full viewer-cancellation test patch at
`61323ba4b9198b5b5a653560a9a66fc0ceab033e` is retained. The timer portion of
`fbccf80091d3d680505dbdf1b1ca218ba3cd5084` is now integrated; that commit
remains partial because its other rendering/fill/lifecycle changes are unfinished.

`BInventory` retains the legacy global timer API and adds viewer-scoped periodic
registration/cancellation plus tracked delayed click tasks. Registration and
cancellation share one lock, preventing a cancellation from passing an in-progress
schedule and leaving its newly returned future unowned. Completed futures are
reclaimed on the next registration; opening another page cancels only that
viewer's timers. Parameterless cancellation still cancels every timer, and
force-close still cancels legacy globally registered tasks while preserving other
viewers' scoped tasks. Closing uses the current GUI session identity rather than
one shared last-rendered inventory field.

Periodic item construction, placeholder expansion, readiness checks, and inventory
writes now run on the Bukkit owner thread. Each periodic registration admits at
most one queued owner callback; rejected scheduler admission clears coalescing and
propagates its failure. A closed viewer is checked before readiness so an unloaded
cache cannot retain its timer indefinitely. Updates retain their original native
inventory target: an old page's queued callback cannot update or cancel the new
page. Native inventory equality, rather than Java wrapper identity, is required
on Spigot 1.8.8. Source slots are mapped through the active session page before
writing, keeping the navigation row intact. Delayed clicks retain their original
GUI/target, and null fill slots are ignored without falling through to the ordinary
button slot. Existing public constructors/getters, configurations, materials,
commands, storage formats, proxy payloads, and release metadata remain unchanged.

The missing public viewer-timer API fails one baseline assertion with zero errors.
The readiness-confirmed previous packaged artifact also fails live because its
item construction runs on the timer thread. Focused regressions cover independent
viewer cancellation, owner execution, delayed targeting, stale-page rejection,
coalescing, scheduler failure, fill slots, registration/cancellation overlap, and
completed-future reclamation. The final Java 8 clean install passes AdvancedCore
364 unit plus 18 artifact tests; exact-dependent VotingPlugin clean verify passes
45 unit plus one artifact test. All failures, errors, and skips are zero. Both
artifacts' base classes remain at major version 52 or lower (1818/2435 classes).
Their six-class decrease reflects replacement of anonymous runnable classes with
Java 8 lambdas, not removal of public compatibility classes.
Artifact SHA-256:

- AdvancedCore: `29cc1af34989c84ed446e4221b457e2b12462c9225bc9ebcff727c786ca37a92`
- VotingPlugin: `2b1bf98ad91f3a6ffbe1ee4505422e7656b183c6361cf8014ad5e32c5c30cfd0`

Real Java 8 / Spigot 1.8.8 fixture `32237ba904` uses two connected 1.8.8 protocol
clients and one shared paginated GUI. Both open page two and acknowledge native
slot-zero item revisions. The first acknowledges revisions one/two, then closes;
the second continues through revisions one through five. Server assertions prove
owner-thread construction, correct GUI/page/content/navigation, and no further
updates to the closed viewer. Graceful disable and SQLite integrity pass. Client
acknowledgments precede fixture closes; neither a server-side write alone nor a
configured CI job is counted as client acceptance. This is protocol observation,
not a claim that a graphical Minecraft client was visually inspected.

Failed fixture attempts are retained. Fresh players had no stored user data and
the normal login path did not populate their caches: the fixture now initializes
its caches through the existing API off-owner and confirms readiness. The first
candidate incorrectly compared native wrappers with `==`; live evidence showed
both clients' wrappers were different objects but equal native inventories. The
corrected production guard uses `equals`, preserving different-page rejection.
Another fixture closed windows before the final state had transmitted. It now
waits for bounded client acknowledgments of actual item revisions, preserving the
same update/count assertions. No arbitrary delay or production login changes were
introduced. Fixture cache work is settled before failure cleanup.

The exact final consumer also passes retained native item/pagination/damage,
MySQL/global borrowed/owned pool lifecycle, storage conversions/repaired retry,
console completion, pending-write shutdown, and restart persistence in fixture
`a918c76505`. SQLite integrity, error/credential audits, and both fixture cleanup
fences pass. Evidence: `viewer-timer-final-build-results.json`,
`viewer-timer-final-live-results.json`, final build/live logs, and retained failed
attempts. Workspace-only helpers, clients, generated artifacts, and dependencies
are excluded from source commits.

Other GUI families, actual delayed-click integration, initial zero-delay/view-open
ordering, stale queued opens/closes, complete fill-button copying/loading, and the
remaining full upstream ledger/features/runtime matrix still require work. Fresh
independent final review has not run. The full goal remains active; no source push
or PR opening is authorized.

## Inventory click ownership and callback compatibility

Ported pinned upstream listener changes `7788ea80f721614615bf89fc827325e86989892c`
and `387d3a74adb28c6928f577b1080346703dfe9c99`, together with the additive
callback-mode API from `08efab8b3523091fb866aea21ad623f30559bec1`.
Native click admission, cursor/event protection, inventory close, full-inventory
delivery checks and page navigation run on the Bukkit owner. Button callbacks
remain asynchronous by default. `setClickAsync(boolean)`, `runClicksSync()` and
`runClicksAsync()` provide the same explicit opt-in contract as pinned main.
Existing callback signatures and the integer `SpamClickTime` configuration remain
compatible; unrelated modern duration parsing is not copied. The upstream patch's
accidental removal of a required fill-loop brace is not reproduced.

Top-inventory selection uses native inventory equality, retaining Spigot 1.8.8's
distinct equal wrappers. A backend/player inventory being another chest does not
make it a managed GUI. Default asynchronous callbacks can still call the shared
sound method; native location/sound access now hands off to the Bukkit owner.
Disabled sound remains a no-op without scheduling. Configuration, schemas,
serialized forms, command/permission definitions, wire formats and release
metadata are unchanged.

The unchanged baseline lacks the click-mode API: its focused test fails with one
`NoSuchMethodException` error. The final candidate passes 377 unit and 18 artifact
AdvancedCore tests, and exact-dependent VotingPlugin passes 45 unit and one
artifact test. Failures, errors and skips are zero. Final artifacts have 1816/2433
base classes and maximum major version 52; the two-class decrease is replacement
of anonymous listener tasks by Java 8 lambdas. Commands actually run use the
workspace Temurin 8u504 JDK and dedicated Maven repository:

```shell
mvn -B -f AdvancedCore/pom.xml -Dmaven.resolver.transport=wagon \
  -Dmaven.repo.local=/workspace/votingplugin-1.8-port-workspace/.m2/repository \
  -Djava.io.tmpdir=/workspace/votingplugin-1.8-port-workspace/runtime/tmp clean install
mvn -B -f VotingPlugin/pom.xml -Dmaven.resolver.transport=wagon \
  -Dmaven.repo.local=/workspace/votingplugin-1.8-port-workspace/.m2/repository \
  -Djava.io.tmpdir=/workspace/votingplugin-1.8-port-workspace/runtime/tmp clean verify
```

Artifact SHA-256:

- AdvancedCore: `9ba1a4464fc8849a49466064c835f18b5abb41c6bb5d873ac84c5a42204d1fbf`
- VotingPlugin: `5aed4ec81b0a6748fb79649da9e6ab045ba8ac837ae80dc04b59e2fea13fdc45`

Real Java 8 / Spigot 1.8.8 connected-client fixture `8c173748e9` sends native
next-page, page-two emerald and subsequent diamond clicks. Server assertions
verify the sync opt-in executes on the owner and default callbacks execute
asynchronously. The client observes three native windows and sends all three
clicks. Graceful disable and SQLite integrity pass. This is protocol-client
observation, not graphical visual inspection.

Two failed fixture attempts are retained: the first sent the second click during
the configured spam interval; the next fixture mistakenly clicked the next arrow
again while waiting. The final fixture waits for server-confirmed page-two
admission readiness (elapsed 145 ms, configured threshold 100 ms), then sends its
content click. It restricts navigation to the first window. Production spam
protection is unchanged; no arbitrary delay or weaker callback assertion was used.
Fresh fixture cache initialization runs off-owner and settles before cleanup.
The same candidate also passes the two-connected-viewer timer acceptance again.

Remaining work includes complete public GUI rendering/open/close generations,
retaining callback event snapshots, all GUI families, fill-button copying/loading,
and full FullInventoryHandler queue/persistence/timer lifecycle. Existing async
callback code remains responsible for its own Bukkit handoffs. Neither this
cohort nor green builds prove the full upstream ledger or complete runtime matrix.
The full backport remains active; independent final review and PR readiness are
still pending. No source push or PR opening is authorized.

Exact candidate retained acceptance also passes native item/pagination/damage,
MySQL/global pool lifecycle, conversions/repaired retry, console completion,
pending-write disable and restart persistence in fixture `374e6c657d`. The two-
connected-viewer repeat is `2c820ec022`. Evidence is retained in
`listener-final-live-results.json` and the build/live logs. Test helpers and
generated artifacts remain outside implementation repositories.
