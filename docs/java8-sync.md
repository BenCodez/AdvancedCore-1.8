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

## Full-inventory queue, scheduling and snapshot backport

Backported the full-inventory series through
`d5c804f280bd8985ad6459660d185c9a680c0b21`, including the required brace fix
`7d5ac81b2a26f6aac5f0219df78a9ea15f6a667a`. It incorporates the UUID merge,
owner scheduling, isolated timer and single-save snapshot changes from
`4133360d10188dd71f5dc604430b8d9849640305`,
`335be4b1f948aa321ca1b2b13b898c591842034e`,
`360ba12b7faf65ded5c7adba7337806fa8f0c367`,
`740f6c843ed4adba7b246ee3f141610bf3051700`,
`162518b8bace1182eaa27ebff5631b7ccf281332`, and
`786ec78e4a211299b6ee9c244964cd0f8e54d610`.
The ordinary-check shutdown guards from
`cc4f71d54e09a9c5075f3a346dc167acb4976675` are ported; its replay-specific
flush work is still partial and is not represented as complete in the ledger.

Appending a list for an existing UUID no longer puts a null key into the
ConcurrentHashMap. Accepted lists are copied and merged per UUID. Offline
sweeps no longer dereference a missing player to expire message state. Native
player inventory checks and delivery use the existing Bukkit player scheduler;
sweeps resolve players on the Bukkit scheduler. The handler keeps its own
executor, so external callers stopping its timer do not stop GUI updates.
Repeated timer loads are idempotent; an externally stopped private executor can
be recreated. A handler that has undergone final shutdown keeps ordinary checks
fenced; recreating an executor is not a claim that final shutdown reopens delivery.

Saving builds a complete FullInventory replacement under the delivery/snapshot
lock and performs one ServerData save. A failed save restores the previous
in-memory section, retains accepted pending items and propagates failure for an
explicit retry. Inspection of the actual SimpleAPI 0.0.7 bytecode confirmed that
its inherited void save method swallowed IOException after printing it.
ServerData preserves that void API but now propagates the I/O failure as an
IllegalStateException. AdvancedCore disable retires this handler and saves its
pending snapshot. Existing FullInventory UUID/Items/Time keys, item serialization
and one-day retention remain readable. Release metadata, commands, permissions,
configuration defaults, proxy payloads and public void item APIs are unchanged.

Two merge assertions fail on the baseline with zero test errors. Eight retained
upstream tests use Arrays.asList instead of Java 9 List.of. Ten additional merge,
queue/snapshot overlap, shutdown-fence, input ownership, retention and actual
filesystem failure/readback tests cover the changes. A first recovery fixture
assertion incorrectly assumed a mocked setData applied its mutation; the fixture
now models that established method's side effect without changing assertions or
production behavior. Final Java 8 clean install passes 395 unit plus 18 artifact
AdvancedCore tests. Exact-dependent VotingPlugin clean verify passes 45 unit plus
one artifact test. All failures, errors and skips are zero.

Commands actually run with workspace Temurin 8u504 and the dedicated Maven cache:

```shell
mvn -B -f AdvancedCore/pom.xml -Dmaven.resolver.transport=wagon \
  -Dmaven.repo.local=/workspace/votingplugin-1.8-port-workspace/.m2/repository \
  -Djava.io.tmpdir=/workspace/votingplugin-1.8-port-workspace/runtime/tmp clean install
mvn -B -f VotingPlugin/pom.xml -Dmaven.resolver.transport=wagon \
  -Dmaven.repo.local=/workspace/votingplugin-1.8-port-workspace/.m2/repository \
  -Djava.io.tmpdir=/workspace/votingplugin-1.8-port-workspace/runtime/tmp clean verify
```

Artifact SHA-256:

- AdvancedCore: `583de453f8d3bdb2dc0e81c989799fdca503db095e5204ba878f2008b33f1a05`
- VotingPlugin: `8ef58b541377afa5afc70eacca05285b671017163432439d459728c3b00b7808`

There are 1815/2432 base classes, maximum major version 52. The one-class decrease
is the replaced anonymous timer callback, not removal of a compatibility API.
Real Java 8/Spigot 1.8.8 fixture `190b528a2e` fills a connected player's native
inventory, parks exactly three overflow diamonds, saves and disables, restarts
with that legacy snapshot, frees one native slot, and delivers the recovered
three diamonds. The connected protocol client observes the amount; final disable
removes the delivered snapshot. SQLite integrity and both startup/shutdowns pass.
Repeated packets showing the same slot are observations, not additional rewards.
This is protocol-client acceptance, not graphical visual inspection or a proof
of exactly-once delivery through arbitrary crashes.

The same artifact passes real native item/pagination, MySQL/global pool,
conversion/repaired retry, console completion and restart acceptance in
`09285ff502`, plus connected listener navigation and sync/async callback acceptance
in `c1edab0eb3`. Evidence is `full-inventory-final-results.json` and retained
build/live logs. Helpers, clients, dependencies and generated artifacts remain
outside source commits.

Remaining work includes replay-aware item completion/reservations and root reward
checkpoints, queued legacy delivery retirement, full queue bounds and mutable
getItems bypasses. The current snapshot write lock includes disk I/O and needs
further owner-latency work. Atomic filesystem publication, arbitrary native
partial failures, crash recovery, failed shutdown and the full remaining upstream
ledger/runtime matrix are not proven. The full goal remains active and incomplete;
fresh independent final review and PR readiness have not been reached. No source
push or PR opening is authorized.

## Full-inventory snapshot publication latency

Disk publication no longer holds the native delivery lock. Capturing and replacing
the complete FullInventory section is fenced against remove/requeue delivery;
each captured ItemStack is cloned. A separate per-handler save monitor serializes
captures and disk writes, preventing a second save from replacing an in-flight
snapshot. The existing void save API remains synchronous and propagates failure;
a failure restores the prior in-memory section and leaves accepted pending items
available for explicit retry. A completed save represents a point-in-time capture,
not a fence preventing later native delivery before publication finishes.

A gated disk-write regression fails on the previous implementation with one
assertion failure and no test errors. Three added regressions prove delivery can
finish while disk publication remains physically pending, native quantity changes
do not alter captured items, and competing saves publish in order. Worker cleanup
waits for actual termination. Existing remove/requeue and failed-write tests remain
in place. This addresses disk latency on the delivery lock; it does not make an
owner-thread caller of the synchronous save API asynchronous.

Workspace Java 8 clean install: AdvancedCore 398 unit plus 18 artifact tests.
Exact-dependent Java 8 clean verify: VotingPlugin 45 unit plus one artifact test.
All failures/errors/skips are zero. Base class counts remain 1815/2432 and maximum
major version 52. Artifact SHA-256:

- AdvancedCore: `e9d21eb88bb8fe2e9e0b8798fcd17405dfc399d6b1d264d0cec1c30068ecbeeb`
- VotingPlugin: `aca5cface171382609224927177df439056951e445cca2cadfec1310d822f2ad`

The exact consumer artifact passes connected native overflow park/save/disable/
restart/recovery (`4e469c8c9f`), connected navigation and sync/async callbacks
(`c3e283706e`), and native item/pagination/MySQL/global pool/conversion/repaired
retry/console completion/pending-write restart acceptance (`5545a6f773`). These
are real Java 8/Spigot 1.8.8 protocol-client and storage tests, not graphical UI
inspection or live fault-injected disk-latency acceptance. Full evidence is in
the workspace overflow-latency build and runtime logs.

Unrelated ServerData writers, reload races, atomic file publication, replay-aware
item completion and reward checkpoints, queued give retirement, arbitrary partial
native failure and crash exactly-once delivery remain incomplete. This cohort
does not complete the full upstream ledger or independent final review. All 167
original checkouts and both pinned references remain unchanged. No source push or
PR opening is authorized.

## Replay-aware item delivery boundary

Added the pinned-main `giveItemAsync(Player, ItemStack...)` and `saveDurably()`
APIs while preserving established void APIs and legacy FullInventory data. A
queued receipt is not completed at scheduling. It validates the captured player
identity immediately before mutation; scheduler rejection, pre-admission timeout,
and shutdown produce the main-compatible not-started marker. Java 8 uses the
existing private handler executor instead of Java 9 delayedExecutor. Observer
cancellation cannot turn physically accepted work into a replay receipt. Queued
receipts settle before shutdown cancels their deadlines; external executor stop
rejects queued admission without throwing away its receipt.

Started overflow stays in a private reservation, excluded from ordinary sweeps.
It is promoted and acknowledged only after serialization succeeds. Failed writes
use an owner-thread drop fallback; only undropped items are retained. If neither
persistence nor fallback succeeds, the receipt remains pending and a retry is
scheduled. Shutdown flushes accepted reservations before executor retirement and
propagates its save failure while still stopping the executor. Missing ServerData
cannot produce a positive durable result. The existing separate disk-publication
monitor and cloned snapshot items remain; a fallback defers while reservations
are being published. Completion callbacks run after releasing publication locks.

Nineteen added tests comprise fourteen pinned-main cases, three queue admission/
cancellation regressions, missing-persistence evidence and a callback-lock
regression. Eight duplicate upstream cases remain in the existing suite rather
than being added again. The three queue defects fail before their corrections;
the callback-lock test reproduces a TimeoutException before releasing the monitor.
Fault-injection tests end their injected write failure before fixture retirement
and await physical executor termination; their original behavioral assertions
remain unchanged. Existing capture, native delivery latency, clone ownership,
competing saves, remove/requeue and failed-save tests continue passing.

Exact workspace Java 8 clean install: 417 unit plus 18 artifact AdvancedCore
tests. Exact-dependent clean verify: 45 unit plus one artifact VotingPlugin test.
Zero failures, errors or skips. Base bytecode maximum 52, class counts 1818/2435.
Artifact SHA-256:

- AdvancedCore: `8f3ef9decacd73c612e59650dccdc1afac35c5d16cdf472f2697d8e4380176b4`
- VotingPlugin: `45cd7c469568076ba3d6310e044d74c70902cb2f1b451767e2933b8429586f37`

Real Java 8/Spigot 1.8.8 fixture `aa04caf4d3` uses the new async API, waits for its
receipt without an additional explicit save, validates persisted three-diamond
overflow, disables/restarts and delivers it to a connected protocol client.
Retained connected listener acceptance passes in `645ec5a5c6`; native items,
MySQL/global pools, conversion/repaired retry/console completion and pending-write
restart acceptance pass in `b771fd5ed2`. Evidence is workspace
`replay-item-final-results.json` and its build/runtime logs. This is not graphical
UI inspection, arbitrary crash exactly-once acceptance or a complete reward replay
proof. All 167 originals remain unchanged and reference commits stay pinned.

The item-handler portions of the pinned main reservation/lifecycle series are
ported. AdvancedCoreUser legacy action collection/context, Reward root checkpoints
and identity/fingerprint/random replay integration are still incomplete; existing
legacy user methods have not yet switched to the new receipt. Queued ordinary void
give retirement, queue capacity/mutable getter bypasses, arbitrary partial native
failure, shared ServerData writers and atomic publication remain outstanding.
The full backport/ledger/runtime matrix and fresh independent final review remain
incomplete. No source push, PR opening, merge, release or deployment is authorized.

## Explicit user action collection and item receipt integration

The awaited injection pipeline now opens an explicit per-user action collection,
restores the invoking thread's prior scope immediately after the callback, and
awaits the collected actions before advancing. Async injectors can capture an
AsyncActionContext and explicitly wrap Runnable, Function or Supplier callbacks.
Unrelated ordinary work does not join another pending scope. Legacy giveItem and
giveItems use the new handler receipt within a scope and retain void delivery
outside it without adding a second scheduler boundary. Existing synchronous
injection isolation and default async opt-in routing remain unchanged.

The pinned-main action fingerprint/occurrence identities, sorted Base64 snapshot
and completed-action formats, reserved replay metadata helpers, ReplayState and
ReplayCheckpoint structures are present. Java8 failed-stage and recovery adapters
replace failedFuture/exceptionallyCompose. Checkpoint consumers use the existing
server-thread dispatch owner's off-primary admission, rather than a new timer or
Java9 delayed executor. RewardOptions copies its new progress/fingerprint maps
at dispatch. The collection releases its monitor before invoking actions or
checkpoint callbacks. Closing it twice returns one cancellation-protected receipt
and cannot dispatch accepted actions twice.

Five new behavioral cases cover pipeline wait, explicitly wrapped continuation,
unrelated ordinary work, exact completed-payload skip and duplicate close. The
pipeline wait test fails before integration; duplicate close reproduces two
invocations before its fix. Mockito's attempt to describe a real ItemStack in the
first duplicate diagnostic required an unavailable test ItemFactory; checking
invocation count directly exposes the intended assertion without changing its
behavioral requirement. All original tests are retained.

Exact Java8 AdvancedCore clean install: 422 unit plus 18 artifact tests. Exact
VotingPlugin clean verify: 45 unit plus one artifact test. All failures, errors
and skips are zero. Maximum base class major remains 52, counts 1823/2440.
Artifact SHA-256:

- AdvancedCore: `f8bbf4710192fc8627bb2a5a50f0d1d53471c9015cd29b55eb820ad32bfce085`
- VotingPlugin: `350850669e130de7403fcd3128ecb6634314abb3e9eaebbf9bf4aee8ca30b9d7`

Actual Java8/Spigot1.8.8 fixture b2b0535331 constructs a real VotingPlugin user off
the Bukkit owner, dispatches scoped giveItem on the owner, awaits its receipt
without an extra save, validates persisted three-diamond overflow, and disables/
restarts/delivers it to the connected protocol client. Retained connected listener
fixture a80b52d308 and MySQL/global-pool/conversion/repaired-retry/console-completion/
pending-write restart fixture ac80359efc also pass. This is native user item-scope
acceptance, not complete persisted root reward replay or graphical UI inspection.
All 167 original checkouts remain unchanged. Helpers and evidence remain outside
source repositories. No production dependency or release version changed.

Remaining work: other legacy user action families; root injection registry and
occurrence propagation; checkpoint consumer binding to the admitted runtime
owner and checked storage; offline/timed reward recovery and choice/random
reservation integration; malformed/changed/partial checkpoint tests; failed
shutdown/crash acceptance; and the entire remaining ledger/platform/runtime
matrix. This phase does not classify the whole AdvancedCoreUser or Reward class,
or their mixed upstream commits, as complete. The full goal and independent final
review remain outstanding. No source push or PR opening is authorized.

## Legacy scheduled user action receipt backport

Scoped experience, experience levels, money deposits/withdrawals, potion effects
and temporary-permission admission now join their originating action collection.
Native execution uses the existing Java8 ServerThreadRewardDispatch; each scope
captures that owner so a replacement runtime cannot admit old queued actions.
Player-bound effects validate current identity/online state immediately before
execution. Pre-admission rejection or timeout is distinguished from an exception
after the native body starts. No new executor, production dependency or Java9
scheduled-future API is introduced. Ordinary experience retains its immediate
legacy behavior; unscoped money/potion calls retain their existing fire-and-forget
scheduler. Temporary permission completion covers its initial mutation, not the
entire permission lifetime. Both ItemBuilder and item-with-placeholder overloads
use the same missing-player guard. Public void signatures and defaults remain.

Twelve regressions cover experience and owner-side level reads, disconnected
players, captured-owner retirement/replacement, late timeout callbacks, observer
cancellation, started native failure classification, ordinary immediate behavior,
Vault provider deposit/withdraw invocation, potion construction/application,
initial permission mutation and both missing-player item overloads. The initial
experience regression fails on the old body with one assertion failure and no
errors. Native action tests and the existing ordered-injection tests pass.

Exact Java8 AdvancedCore clean install: 434 unit plus 18 artifact tests; exact
VotingPlugin clean verify: 45 unit plus one artifact test. Zero failures, errors
or skips. Base bytecode maximum 52, class counts 1820/2437. Three old anonymous
Runnable classes were replaced with lambdas; no public API was deleted. Hashes:

- AdvancedCore: `55aa0763f84f4ef8166de25e0be14b6bb19125955ca9771be9289183074c7c95`
- VotingPlugin: `d41114e4d8a16cc382e6176225808c81de021db6a8da3ad86bd25f92fdd66cf1`

Real Java8/Spigot1.8.8 fixture 9ccc4a4185 uses a real VotingPlugin user and validates
scoped native experience and a SPEED potion after the aggregate receipt settles.
Its three overflow diamonds are persisted, survive disable/restart and reach the
connected protocol client. Retained connected listener fixture a0b8706fa0 and native
item/pagination/MySQL/global-pool/conversion/repaired-retry/console completion/
pending-write restart fixture 89a903d00c pass on that same artifact. This is not a
live economy-provider or permission-expiry acceptance test, graphical UI inspection,
or a complete durable root replay proof. Evidence is workspace
user-native-action-final-results.json and its build/runtime logs.

Remaining root work includes admitted-generation binding for item/checkpoint paths,
checked root checkpoint writes, stable injection/occurrence identities, offline/
timed recovery, nested/random/choice integration and failure/crash acceptance.
Vault return-value acceptance semantics remain inherited and require audit; actual
provider invocation is the covered boundary. Permission-map repeated admission and
expiry require focused reproduction: installed Java8 ConcurrentHashMap bytecode
confirms its legacy contains(Object) is a containsValue alias, and the old handler
uses it with a UUID. That discovery has not been patched or classified complete.
All 167 originals remain unchanged. Full source ledger, broader runtime matrix and
fresh independent final review remain incomplete. No source push or PR authorized.

## Root injection checkpoint pipeline (in progress toward full recovery)

The awaited root pipeline now uses the pinned main registry fingerprint
(class/path/priority/post order), explicit replay path and logical occurrence,
and the existing user action snapshots. Normal and post injections run in order;
each physical receipt is followed by the off-owner checkpoint consumer and its
completion hook before another injection starts. Checkpoint submission retains
one admitted ServerThreadRewardDispatch generation. A count-only or changed
registry checkpoint is refused before effects. Fresh reused RewardOptions do not
inherit a previous independent recipient's progress. Public synchronous APIs and
ordinary callback exception isolation remain; durable callbacks propagate failure
with completed-prefix/registry/action metadata for recovery.

Five new production-entry regressions cover unsafe legacy checkpoint refusal,
effect/write/next ordering, failed-write prefix recovery and changed-registry
refusal, runtime retirement, and explicit context/fresh occurrence independence.
The baseline refusal test fails with one assertion failure before the port.
Focused native-action plus ordered-root tests: 37 passed. Exact Java8 AdvancedCore
clean install: 439 unit plus 18 artifact tests (457); exact VotingPlugin clean
verify against it: 45 unit plus one artifact test (46). No failures/errors/skips.
Base classes 1822/2439, maximum major 52. Candidate SHA256:

- AdvancedCore: `30f501533116b4410d61f228156ed45b6d51368d99ef9c0d558b3ef0b6300e74`
- VotingPlugin: `15e07e3aefc2f557d7af63dc074e131fbbbffe15f8a750ba9ae413aa680e16cb`

Real Java8/Spigot1.8.8 fixture `7f4f8207e7` runs a real VotingPlugin user's root
reward through the production registry, verifies native experience/potion effects,
checks the off-owner fixture checkpoint file exists before root completion, then
proves three overflow diamonds survive disable/restart and reach the connected
protocol client. SQLite integrity and clean enable/disable pass. This checkpoint
consumer is a fixture file writer, not the unfinished production offline/timed
queue adapter. It does not prove crash exactly-once or complete nested recovery.

Production offline/timed queue claims, protected-entry migration, checked cache/
store writes, recovery deletion, nested/random/choice integration and crash/failure
acceptance remain unfinished. Capture the item handler and action dispatcher from
the admitted root generation as well: the current action scope still obtains its
owner from the plugin getter at collection construction; deferred item suppliers
still obtain FullInventoryHandler when run. Those sibling paths are not covered
by the checkpoint generation fix. Full upstream ledger and fresh independent
final review remain incomplete. No source push or PR opening is authorized.

## Shared root/action runtime capture

ReplayState now captures the root's ServerThreadRewardDispatch and
FullInventoryHandler before its scheduler boundary. Each action collection uses
that same captured pair; independent collections capture their pair at creation.
Deferred item suppliers use the captured handler. A runtime replacement cannot
redirect an older root or explicit action context into the new handler/dispatcher.
Ordinary unscoped void delivery still uses the current runtime. An absent admitted
handler fails the scoped item receipt before delivery instead of borrowing a
newly initialized replacement. Physical retirement remains the existing handler's
responsibility; this change adds no timers or cancellation shortcuts.

Three new regressions cover root admission followed by getter replacement and an
explicit asynchronous item continuation, independent scope deferred item delivery,
and native experience against the root's admitted dispatcher after the plugin
getter changes. The corrected baseline has three assertion failures and zero
errors. An earlier Mockito wanted-invocation error while formatting ItemStack was
replaced with numeric invocation assertions. Forty focused native/root tests pass.
Exact Java8 clean install: 442 unit +18 artifact tests (460); exact VotingPlugin
clean verify: 45 unit +1 artifact test (46). No failures/errors/skips. Base bytecode
maximum52,1822/2439 classes. SHA256:

- AdvancedCore: `fd9fdb6de6a1755350460e3dd2c37f2cfb38a9c283a5197151a093bfc8d835df`
- VotingPlugin: `0194e5e3efa3df70acc77c530582ffeffd6ec57ed27ab642b7ad9545763ef1b9`

Real Java8/Spigot1.8.8 root fixture `07af69d1ce` passes effects/checkpoint settlement,
three-diamond overflow persistence/disable/restart/client recovery and SQLite
integrity. Connected listener fixture `4158484096` passes native navigation,
owner opt-in/default async callbacks, page-source mapping and graceful shutdown
on the same exact artifact. Neither fixture proves production offline/timed queue
recovery or crash exactly-once. Original/reference repositories remain unchanged.

Next is the production queued recovery adapter: stable queued references and
occurrences, shared per-user claims, checked progress publication and completion
removal, pause/vanish/delay retention, bounded serial replay and shutdown/failure
recovery. UserData's existing nonqueued typed write routes through checked storage
and UserDataManager.writeDirect; its active cache flushes queued predecessors
under batch ownership before publishing the acknowledged direct value. Reuse that
contract rather than adding a new queue/cache owner or treating a queued write as
completed. Full upstream classification, remaining features/runtime matrix and
fresh independent final review are unfinished. No source push or PR authorized.

## Complete awaited reward preparation entry point

The Java8 giveRewardAsync API now retains its captured root runtime through
asynchronous PlayerRewardEvent, delayed/timed admission, server-owner live-player
and requirement evaluation, off-owner decision/deferral work, native effect
completion and root checkpoints. Existing void giveReward routes explicitly
asynchronous or durable work through that full entry point. Ordinary synchronous
injections retain their legacy void path. Options are copied before handoff.
LiveRewardDecision uses a Java8 class rather than main's record; admission uses
the existing lifecycle-bound dispatcher instead of Java9 orTimeout/delayedExecutor.
Disabled reward processing remains an ordinary no-op but fails retained replay;
requirement exceptions likewise fail retained replay. Cancellation and terminal
requirement denial remain intentional completion. Paused/vanished durable work
returns a recognizable deferral signal, retaining its original queue occurrence
rather than adding another copy. The forthcoming queue adapter must consume that
signal by releasing its claim without removing the entry. Ordinary deferral still
uses the existing configured queue API off the Bukkit owner.

Seven regressions cover asynchronous event/owner requirements/effect ordering and
caller option isolation, cancellation, disabled ordinary/durable behavior, paused
retained replay, requirement exceptions, retired async-event admission and the
existing void wrapper's awaited path. The first focused run had one Mockito nested
stubbing error; fetching PluginManager before constructing its stub fixed the
fixture. Six new plus existing tests then pass (46 focused), and the seventh void
wrapper regression passes in the full Java8 build. Final clean install:449 unit
+18 artifact tests(467); exact VotingPlugin clean verify:45 unit+1 artifact(46),
zero failures/errors/skips. Base classes1824/2441,maxmajor52. SHA256:

- AdvancedCore: `867feaea302b85954643c8fcd25fe547672bdfc6bea3bb601afde097ed938796`
- VotingPlugin: `eee571f4b861c2556a38201cb774297c3174e2b42718ae89d00e72cad2aee46b`

Real Java8/Spigot1.8.8 fixture `2b06e1614f` runs the complete production entry point
for a real VotingPlugin user, awaits native experience/potion/item actions and the
off-owner fixture checkpoint, and proves overflow diamonds survive disable/restart
and reach the connected protocol client. SQLite integrity/clean shutdown pass.
An earlier runtime fixture `e87327ef0f` is honestly recorded FAIL: the copied
harness expected a renamed marker that its helper still emitted under the old
name, after native work had settled. The corrected helper rerun passes without
changing product code or weakening the assertion.

This is not the completed offline/timed queue recovery adapter. Queue provenance,
protected-entry occurrence migration, bounded per-user claims, checked mutation,
completion deletion, retries, nested snapshots/choices, and crash acceptance
remain unfinished. No queue metadata/storage/wire format was changed here.
Complete upstream classification, other features/platform matrix and independent
final review remain required. No source push or PR opening is authorized.

## Persisted reward resolution and checked generated snapshots

Added the package-private-origin PersistedQueueReference capability and awaited
RewardHandler queue resolver. Normal references resolve registered live rewards;
explicit snapshot references resolve generated snapshots restricted to the
requesting user UUID. Legacy references prefer registered normal rewards and may
fall back to marked generated snapshots. Missing/malformed references fail without
creating an empty reward or interpreting the reference as a command. Generated
files already in the legacy global registry do not become normal queue rewards.

The generated loader performs a checked YAML read, requires DirectlyDefinedReward,
and constructs from that captured document. It never reopens the file through the
legacy auto-creating loader, and checkRewardFile cannot republish a loaded snapshot.
The file identity remains available through getFile. Snapshot creation now uses the
existing saveStrict publication API before reporting created provenance or updating
the registry. No public legacy void reward API was removed.

Eleven focused regressions pass, including a file deletion between validated read
and construction, malformed YAML, user binding, explicit normal/snapshot precedence,
unsafe/missing references, disabled runtime and failed checked snapshot publication.
The publication regression first failed against the unchecked save path. The
current full Java8 build passes 460 unit +18 artifact tests (478) in AdvancedCore,
and 45 unit +1 artifact test (46) in VotingPlugin; zero failures/errors/skips.
Actual commands (Temurin8u504, workspace-local Maven repository):

- mvn -B -f AdvancedCore/pom.xml -Dmaven.resolver.transport=wagon
  -Dmaven.repo.local=/workspace/votingplugin-1.8-port-workspace/.m2/repository
  -Djava.io.tmpdir=/workspace/votingplugin-1.8-port-workspace/runtime/tmp clean install
- mvn -B -f VotingPlugin/pom.xml with the same properties clean verify
- focused producer test: same properties, -Dtest=LegacyPersistedRewardResolutionTest test

Final artifact SHA256: AdvancedCore
`310bd4b45d24fdf39e9e6b8c20a4a1e776623f798e65ed8b6aa78e32bd31e839`;
VotingPlugin `2e501f92f319d656bb8c3827c6fb50db5c284c045a791c028d493945bf68dac3`.
Base classes1826/2443, maximum class-file major52. Native Java8/Spigot1.8.8
fixture3962760ba4 passes all9 checks on the exact VotingPlugin candidate:
generated snapshot refuses unrelated UUID; experience/potion/root actions and
fixture checkpoint settle; three overflow diamonds survive clean disable/restart
and reach the connected protocol client; SQLite integrity is healthy.
The helper initially failed to compile because its command pointed at a nonexistent
Spigot jar; correcting the fixture classpath compiled successfully before execution.

This remains a resolver/publication foundation. Production offline/timed dispatch
still needs occurrence migration, shared per-user claims, serial replay, persisted
checkpoint rewrite, completion-only removal and failure/restart recovery. The
runtime checkpoint is a fixture file, not production queue persistence. Nested
replay choices and the remaining upstream ledger/platform matrix are incomplete.
No queue storage format/config/proxy payload changed in this cohort. No push or PR
opening; the independent final review remains pending the full scope.

## Checked queue mutation bridge

Added UserDataManager.mutateDirect to resolve the actual current per-UUID cache
at execution and reuse its existing checked mutation owner. Uncached users perform
checked read/transform/write under that same UserStorageOwnership slot. Cache
retirement in the selection gap rejects instead of applying a read to a successor.
Notifications run after ownership is released, and an uncached post-commit failure
uses the existing CommittedUserDataMutationException with the acknowledged value.

UserData.mutateStringListStrict provides the production queue integration boundary:
unknown cached keys require a checked storage snapshot, only a checked absent key
becomes empty, invalid types/null edits fail, writes are physically acknowledged,
and the existing %line% serialization is retained. An edit larger than65535 UTF8
bytes fails without trimming retained entries. This synchronous API belongs off
the Bukkit owner, including replay checkpoint callbacks. Existing setters and
ordinary reward behavior are unchanged; queue writers are not migrated yet.

Eleven new regressions cover current-generation selection, checked cold reads,
read/write failures, legitimate absent records, cached and uncached post-commit
notification failures, malformed predecessors/null edits, concurrent uncached
updates and the exact capacity boundary. The first focused compile failed because
legacy MySQL.getExactStrict returns ArrayList rather than List; fixture values
were corrected. The corrected initial7 plus existing18 tests pass (25 focused),
and all11 final regressions pass in the full build.

Actual Temurin8u504 commands retain the dedicated Maven repository and tmpdir:

- mvn -B -f AdvancedCore/pom.xml -Dmaven.resolver.transport=wagon
  -Dmaven.repo.local=/workspace/votingplugin-1.8-port-workspace/.m2/repository
  -Djava.io.tmpdir=/workspace/votingplugin-1.8-port-workspace/runtime/tmp clean install
- mvn -B -f VotingPlugin/pom.xml with the same properties clean verify
- same producer properties, -Dtest=LegacyQueueMutationBridgeTest,LegacyCheckedQueueMutationTest,LegacyDirectUserDataTest test

Final full build:471 unit+18 artifact(489) AdvancedCore tests;45 unit+1 artifact(46)
VotingPlugin tests, zero failures/errors/skips. Base classes1826/2443,maxmajor52.
SHA256: AdvancedCore `7678d33a32be8a4cc245f2171188627b10743407bf35bd25a4e792518b908f08`;
VotingPlugin `e4c99be1b06719d1454c5f35394800d5d7de04d002c0b384ec1d3da6f7a77630`.

The native fixture uses the actual UserData mutation bridge for a dedicated test
column during root admission/checkpoint and independently reads SQLite after clean
shutdown. It also exercises generated user restrictions, native effects, overflow
restart and client-visible recovery. This proves the bridge's real SQL path;
it does not prove the production offline/timed queue dispatcher, claims, occurrence
migration or crash recovery. Those remain the next integration work, along with
all queue writers sharing this owner so stale replacements cannot erase progress.
The full upstream ledger/platform matrix and independent review remain incomplete.
No push or PR opening is authorized. Original CRLF sources remain CRLF; whitespace
validation uses git -c core.whitespace=cr-at-eol diff --check without changing Git
configuration.

## Production offline occurrence recovery

Offline dispatch now keeps its persisted entry throughout awaited reward execution.
It reserves a per-plugin/per-UUID occurrence shared by user wrappers, migrates a
legacy entry to a durable UUID before effects, and serializes distinct queued
occurrences. Identical legacy entries are still distinct legitimate rewards.
Ordinary/forced entry points retain the fork's force-offline options; the new
checkOfflineRewardsAsync receipt covers admission, effects/checkpoints and removal.
The void entry points observe/report failure. Queued work retains the dispatcher
and inventory runtime captured before storage work instead of rebinding on reload.

Checkpoints atomically rewrite only the current occurrence using the existing
checked mutation owner. Completion removes only that current entry after all
promised effects settle. Failed effects retain the occurrence/progress for retry;
failed storage publication leaves the last acknowledged predecessor. Physically
committed edits with notification failure advance their local reference and report
the notification failure without replaying the edit. New additions carry the
pinned-main normal/snapshot provenance and occurrence envelopes. Legacy oldest-first
capacity trimming remains, protecting claimed entries and refusing a new admission
if active work is the only possible victim. Owner-thread void admission schedules
the write off-owner and logs rejection; off-owner admission propagates failures.

The codec is adapted from pinned main AdvancedCoreUser: v3 progress/fingerprints,
v2 and count-only legacy checkpoint classification, placeholders and occurrence
markers. Malformed/negative/duplicate progress and duplicate occurrence identities
fail instead of becoming fresh work. Occurrence parsing excludes placeholder values.
Existing storage keys, %line% serialization, schema and proxy wire payloads remain.
Old plain reward entries migrate on admission. Pre-port binaries cannot interpret
new protected queue envelopes; downgrade requires preserving/restoring compatible
queue data rather than assuming they can replay new entries.

Eighteen focused production-boundary tests cover pending retention, repeated polls
and separate wrappers, forced dispatch, serial identical entries, admission/effect/
checkpoint/completion failures, appends and replay metadata, committed notification
failure, malformed/duplicate identities/progress, runtime replacement, disabled
processing and capacity preservation. Initial integration compilation caught a
package-private isRetired call (use the existing public UUID retirement identity).
The first test run lacked the static plugin fixture; later second-wrapper stubbing
nested another mock invocation. Both fixture errors were corrected; no assertion
was weakened. The runtime helper initially had a Java8 shadowed-variable compile
error and was corrected before execution.

Actual Temurin8u504 commands retain the dedicated repository and temporary directory:

- mvn -B -f AdvancedCore/pom.xml -Dmaven.resolver.transport=wagon
  -Dmaven.repo.local=/workspace/votingplugin-1.8-port-workspace/.m2/repository
  -Djava.io.tmpdir=/workspace/votingplugin-1.8-port-workspace/runtime/tmp clean install
- mvn -B -f VotingPlugin/pom.xml with the same properties clean verify
- same producer properties, -Dtest=LegacyOfflineQueueReplayTest test; related focused
  resolver/mutation run:33 tests before the additional wrapper/capacity regressions

Final builds:489 unit+18 artifact(507) AdvancedCore tests;45 unit+1 artifact(46)
VotingPlugin tests, zero failures/errors/skips. Base classes1829/2446,maxmajor52.
SHA256: AdvancedCore `2d93b3fd84fc654e5dbbbf7eb148b682266825a593f9900f618b8049e318fbea`;
VotingPlugin `47f795acd204e36e1f16caff53287bc84f2626ce937d607ab06de009ccd13c8c`.

Native Java8/Spigot1.8.8 fixture e7b798affa uses addOfflineRewards and
checkOfflineRewardsAsync on an actual VotingPlugin user. An injection checkpoint
notification performs an independent checked SQL read before queue completion and
proves the occurrence/v3 progress is physically present; completion checks its
physical removal. Generated UUID restriction, experience/potion/native items,
overflow persistence/restart and connected-client recovery all pass(10 checks),
with clean enable/disable and SQLite integrity. Earlier fixture79f81b25fe also passed
on its earlier candidate; the final result above belongs to the final candidate.

Not complete: timed queue adaptation, offline crash/restart recovery, nested choices,
rolling storage generations/shutdown acceptance and public explicit queue-clear/
bulk-wipe concurrency. VotingPlugin.clearOfflineVotes intentionally clears the
queue and has not been redesigned; disappearance now rejects a checkpoint instead
of silently permitting the next effect. No claim of end-to-end exactly-once effects.
The complete upstream ledger, other platform matrix and independent final review
remain unfinished. No source push or PR opening is authorized.

## Production timed replay and actual delayed-timer retry

Timed producer and dispatcher now use the same per-plugin/per-UUID serial owner as
offline replay. A due entry receives a durable occurrence before effects, retains
its existing %ExecutionTime/% encoding and execution date, and checkpoints only
its own current key. Completion removes that occurrence after awaited effects;
new appends survive checkpoints/removal. New admissions retain generated snapshot
provenance and use UUIDs so equal reward/due-time admissions remain distinct.
Owner-thread void admissions schedule checked writes off-owner; the async receipt
checkDelayedTimedRewardsAsync covers the work admitted by that poll.

Failure retains progress/occurrence, physically publishes a retry date/count and
requests the existing delayed timer only after publication. The exponential policy
is adapted from pinned main (count capped8, delay bounded5minutes); the existing
500ms timer margin is retained. Deferred durable work stays in the timed queue
rather than creating a second offline entry. Future and zero-date entries do not
run. Invalid dates/retry metadata and duplicate timed keys/occurrences fail before
effects. Reference marker handling excludes placeholders; the ordinary public
getTimedRewards reader also uses the final execution-date delimiter so marker text
inside placeholder values cannot confuse startup scheduling.

Fourteen new focused regressions cover pending retention, active polling, shared
offline/timed serialization, future/zero dates, retry publication/scheduling,
checkpoint/removal with a new append, malformed/cold reads, admission/checkpoint
write failures, notification-after-commit failures, equal admission dates, literal
markers in placeholders and duplicate legacy keys. The first8 plus existing18
focused replay tests pass(26); the final14 pass in the complete suite. There were
no compilation/test/runtime failures in this cohort.

Actual Java8 build commands (Temurin8u504; workspace-local dependencies/tmpdir):

- mvn -B -f AdvancedCore/pom.xml -Dmaven.resolver.transport=wagon
  -Dmaven.repo.local=/workspace/votingplugin-1.8-port-workspace/.m2/repository
  -Djava.io.tmpdir=/workspace/votingplugin-1.8-port-workspace/runtime/tmp clean install
- mvn -B -f VotingPlugin/pom.xml with the same properties clean verify
- same producer properties, -Dtest=LegacyTimedQueueReplayTest,LegacyOfflineQueueReplayTest test

Final full builds:503 unit+18 artifact(521) AdvancedCore tests;45 unit+1 artifact(46)
VotingPlugin tests, zero failures/errors/skips. Base classes1831/2448,maxmajor52.
SHA256: AdvancedCore `588aab8d550daf5a22877bbf76f3ce8b7feb4a7d8d67f6569d51ff2533c8c659`;
VotingPlugin `9f99dea335cd258ff2f849e869a0c0f5ec0b577173b1ad09e1efea7d457208b6`.

Native Java8/Spigot1.8.8 fixture429dee82a2 uses the actual timed producer and delayed
timer. Its first injection deliberately fails before any native effect. The timer
retries the same occurrence; the successful attempt gives7 experience, a potion
and3 diamonds once. An independent checked SQL read observes the timed occurrence
and v3 checkpoint before completion; a later checked read verifies removal. The
connected client receives exactly3 overflow diamonds after clean restart. All11
checks pass, including generated user restriction, SQLite integrity and clean
shutdown. Earlier fixturee9d65060bd passed on the earlier candidate; the final
fixture above belongs to the final artifact.

Recovery remains incomplete. If retry-state publication itself fails, the original
entry is retained, but a later same-process timer wakeup is not guaranteed. Add a
bounded/coalesced storage-outage retry under the existing timer/owner after proving
that path. Offline/timed crash recovery, partial native-effect restart, queue clear/
bulk-wipe concurrency, reload/shutdown ownership and the wider upstream ledger/
platform matrix still require implementation or acceptance. No end-to-end exactly-
once guarantee, complete-main claim or independent final review is implied.
No source push or PR opening is authorized.


### Timed admission storage-outage recovery (2026-10-06)

A focused regression first failed against the preceding timed-replay candidate:
a temporary checked SQL admission failure retained the entry but left no timer
wakeup. The regression exercises the production delayed-timer callback with a
controlled scheduler; the recorded failure is one test, one assertion failure,
zero errors/skips. It now passes without weakening the assertion.

Storage read/admission failures whose cause is SQLException/IOException now keep
one coalesced wakeup per plugin/user under the existing persisted replay owner.
The wakeup uses the existing delayed timer and captured dispatcher/runtime; bounded
exponential backoff matches the existing retry policy. Repeated polls share that
wakeup. Malformed metadata is not treated as a transient storage outage. Retirement
cancels pending recovery futures, including a racing future publication; old
runtime callbacks cannot use a replacement dispatcher. A successfully published
normal retry does not also create a storage-recovery wakeup.

Only failures before reward effects are admitted use this polling recovery.
Failures after effects start still need retained-progress publication recovery;
blindly polling that case could replay committed effects. This cohort does not
claim that remaining post-effect failure path is repaired.

Focused Java8 tests:20 timed+18 offline=38, zero failures/errors/skips.
Full Java8 producer clean install:509 unit+18 artifact=527 tests.
Exact workspace-local dependency consumer clean verify:45 unit+1 artifact=46 tests.
Both full builds have zero failures/errors/skips. Existing Maven commands and
workspace-local repository/temp-directory flags above were used unchanged.
Base classes1832/2449,maxmajor52.
SHA256: AdvancedCore `6470a918f51141f64a97a60759c2d8696b1906ee0ab0cbbcc155bdfb2cf8726a`;
VotingPlugin `8237b555e08206e4a607963e73fa430bee806dfaff86b525083868460c1369b6`.

Native Java8/Spigot1.8.8 fixture42c153c29b passed12 checks. A fixture-only SQLite
trigger rejects updates to the non-empty timed queue. The producer successfully
appends the entry, then actual timed admission fails. Before removing that trigger,
the fixture verifies no native injection ran and the physical entry remains. After
repair, the actual coalesced timer resumes automatically; the existing deliberate
first injection failure is retried normally. Native effects, physical v3 checkpoint,
completion removal, exactly3 overflow diamonds after clean restart, SQLite integrity
and clean shutdown all pass. No production fault-injection hook or dependency was
added. This proves pre-effect same-process SQL admission recovery, not post-effect
storage recovery or offline/timed crash recovery.

Remaining full scope includes retained-progress publication after effects, queued
crash recovery, explicit clear/wipe concurrency, startup cold-read repair, broader
lifecycle/platform acceptance and the complete pinned upstream ledger. Independent
final review remains pending. No push or PR opening is authorized.


### Post-effect queue publication recovery (2026-10-06)

Two regressions first failed against AdvancedCore
`df8b65219e1166c31b98c1f483e2ac4e5a0756d9`: a completed offline/timed effect followed
by a failed checked removal settled its receipt and released the occurrence. A
subsequent poll could admit the reward again. Both desired-behavior assertions now
pass unchanged.

Offline/timed removal and post-effect recovery metadata use one captured publication
under the existing per-user persisted replay owner. It retains the serial tail and
occurrence until that exact storage edit is acknowledged. SQL/IO failures retry only
the captured edit with bounded backoff on the existing delayed timer; they never
call the reward handler. Appended entries are preserved. Missing entries and other
permanent errors terminate explicitly rather than spinning. Retirement cancels
queued retries and a racing future publication, but keeps a running physical commit
owned until its result is known. Acknowledged writes remain acknowledged even when
retirement races their completion. No new executor, store, dependency or wire format
was introduced. Pre-effect admission retains the previous coalesced polling behavior.

Ten new regression tests cover offline/timed removals, recovery-prefix publication,
repeated outage/repair, append preservation, shared serial ownership, queued and
running retirement, late future publication, and permanent missing-entry failure.
Focused48 tests pass. Full Java8 producer clean install:519 unit+18 artifact=537;
consumer clean verify against the exact local dependency:45 unit+1 artifact=46.
Zero failures/errors/skips. The new tests were subsequently rerun with bounded
2-second completion waits:10 tests pass. Base classes1834/2451,maxmajor52.
SHA256: AdvancedCore `d97f7c30013c471e4d92e639c966b3cfbfd5067d7b058fd0e1fdc0895c8a1bb5`;
VotingPlugin `ff7187cb6208f65d66b4106eb30477be91f107f77d6d8108970df1462efc0502`.

Native Java8/Spigot1.8.8 fixtures e418ac06c0(timed removal),f765b79f43(offline removal)
and ab87833bd9(completed-root checkpoint recovery) each pass12 checks on that exact
consumer. Fixture-only SQLite triggers reject the selected physical update. The
completion cases retain a fully checkpointed entry until repair; a repeated poll is
fenced and only removal is retried. The completed-root case permits every native
action checkpoint, then rejects the completed root-prefix write and its recovery
publication. After repair the retained prefix is published and normal timed replay
skips the completed root: its callback runs once. Each case verifies7 experience,
a potion, exactly3 overflow diamonds recovered after clean restart, checked physical
queue state, completion removal, SQLite integrity and clean shutdown.

Fixture corrections are retained as evidence, not counted as passing acceptance:
an offline helper was first run from the wrong directory and then had a replacement-
order method-name typo. The initial checkpoint fault targeted action checkpoints too
early; its narrowed expression then used instr(), which the real SQLite3.7.2 driver
rejects (confirmed by a Java8 probe). Compatible LIKE matching of the actual encoded
completed-root prefix corrected the fixture without weakening once-only assertions
or changing production dependencies. An intermediate focused cancellation assertion
also found duplicate cancel calls; the implementation was corrected, not the test.

Same-process post-effect publication recovery is now tested. This does not prove
crash recovery or exactly-once behavior across a crash between a non-idempotent
effect and durable acknowledgement. Explicit clear/wipe concurrency, startup cold-
read repair, same-instance reenable/failed shutdown, partial native-action restart,
the broader runtime/platform matrix and complete upstream ledger still require work.
Independent final review remains pending. No push or PR opening is authorized.


### Acknowledged root/action checkpoint crash acceptance (2026-10-06)

Four native Java 8/Spigot 1.8.8 cases now pass 10 checks each (40 total) against the
unchanged consumer SHA256
`ff7187cb6208f65d66b4106eb30477be91f107f77d6d8108970df1462efc0502`:

- Completed root, offline case626fb307e9: automatic login replay.
- Completed root, timed case3976f6bfb7: automatic timed startup replay.
- Partial first item action, offline case8919a0187e: automatic login replay.
- Partial first item action, timed case4ca02aace4: automatic timed startup replay.

These are actual process crashes: the fixture verifies an independently readable
SQLite checkpoint, keeps the original receipt pending, kills its owned Java process
with SIGKILL and observes exit -9 without VotingPlugin disable. The physical queue is
checked again before restart. The restarted process receives no queue-check/resend
command: normal login or timed startup drives replay. An inspection-only command
waits for the resulting checkpoint hook and physical completion removal.

The completed-root cases verify a durable fixture root-call counter stays 1. The
completed root is skipped after restart and native inventory/experience remain
exactly 3 diamonds/7 experience. The partial cases pause after the first item action
checkpoint, with exactly 3 diamonds and 0 experience. On restart the unfinished root
is reconstructed once (root-call counter 2), its persisted item receipt skips that
item, and the pending experience action executes once. Final native state is again
exactly 3 diamonds/7 experience. Both boundaries preserve the same occurrence ID,
remove the completed queue entry, pass SQLite integrity and clean second-process
shutdown, and show no Java8 linkage errors.

The partial fixture wraps the existing PlayerRewardEvent checkpoint consumer, calls
the original checked publication first, and pauses only after independently reading
its acknowledged one-action receipt. It never substitutes for the acknowledgement
or directly edits the stored queue. Native player data is explicitly saved through
Bukkit before each kill. This establishes recovery at these acknowledged boundaries;
it does not prove atomicity between SQLite and unsaved Minecraft player data, power-
loss recovery, or a crash between a non-idempotent effect and its acknowledgement.
Arbitrary injector side effects without replay-aware receipts are not covered.

Commands actually run in workspace-local runtime fixture directories:

- Temurin 8u504 javac -source 8 -target 8 against the exact VotingPlugin candidate and
  the actual Spigot 1.8.8 jar, output to the fixture classes directory.
- Java 8 jar packaging of the small fixture helper and descriptor.
- python3 run.py offline and python3 run.py timed, with the working directory
  runtime/queue-crash-acceptance.
- python3 run.py offline and python3 run.py timed, with the working directory
  runtime/partial-queue-crash-acceptance.

The scripts launch the Java 8 runtime with workspace-local java.io.tmpdir and a loopback
Minecraft protocol client. Servers, jars, helper sources, data and logs stay under the
isolated workspace and are not committed. Independent artifact hash checks confirm
both production candidates are unchanged. The previous full build evidence remains
AdvancedCore 537/VotingPlugin 46 tests, zero failures/errors/skips, base max major 52;
there was no production/test-source change requiring another full build this cohort.

These cases supersede the earlier lack of evidence for acknowledged root and first-
item partial-action crash recovery. Random/nested reward restart, unacknowledged-effect
boundaries, explicit clear/wipe concurrency, cold-read startup repair, same-instance
reenable/failed shutdown and the wider upstream/platform matrix remain outstanding.
No complete-main or globally exactly-once claim is made. Independent final review
remains pending; no push or PR opening is authorized.

## Named RandomReward child completion and restart recovery

A live Java 8/Spigot 1.8.8 regression proved that the old `RandomReward` callback
could remove its offline parent while the selected child was still pending. The
callback used the legacy void scheduler API, so the parent never awaited that
child. This is a confirmed backport gap, not a change to the legacy void API's
admission contract.

The asynchronous `RandomReward` path now persists its selected branch before
execution, carries the admitted runtime and explicit child path/occurrence, awaits
the child, and persists a one-child completion marker before settling its parent.
Checkpoint snapshots merge reserved metadata from the shared replay state so a
parent snapshot cannot discard a child's replay records. Configured named children
and the existing slash-command form have an awaited dispatch overload; durable
missing children fail without creating an empty reward file. Fresh named lookup,
empty optional names, the synchronous callback, and existing overloads remain.
Commands retain leading-slash removal and JavaScript/placeholder substitution;
completion means their actual Bukkit-owner invocation returned. It does not mean
an arbitrary command's external effects are transactional.

The helpers adapt pinned-main selection and child-marker behavior from
`a6eede5b26a81e9516f40cb9d1dcb6f4c2ac6714`,
`12bc1f5245c9d63cbc200d38259538ed51cf1015`,
`3465fd4c611e872d9afb1d7834342b46f36ac2b3`,
`a83cae5baaa4a14131c229a580f72ba28c1427cd`, and
`31ef89f8e838a88a8f74f322fe476b16f876cd67`, with Java 8 exceptional-future
construction and the existing Spigot 1.8 owner. These whole upstream commits are
not declared fully ported by this named-child cohort. No production dependency,
release version, configuration default, schema, or proxy payload changes.

Validation used Temurin 8u504, Maven 3.9.9 and the isolated `.m2/repository`:

```sh
mvn -B -f AdvancedCore/pom.xml -Dmaven.resolver.transport=wagon   -Dmaven.repo.local=/workspace/votingplugin-1.8-port-workspace/.m2/repository   -Djava.io.tmpdir=/workspace/votingplugin-1.8-port-workspace/runtime/tmp clean install
mvn -B -f VotingPlugin/pom.xml -Dmaven.resolver.transport=wagon   -Dmaven.repo.local=/workspace/votingplugin-1.8-port-workspace/.m2/repository   -Djava.io.tmpdir=/workspace/votingplugin-1.8-port-workspace/runtime/tmp clean verify
```

Run from their respective writable fork roots with the actual Java 8 `JAVA_HOME`
and its `bin` first on PATH. AdvancedCore: 528 unit + 18 packaged-artifact tests
(546 total). VotingPlugin: 45 unit + 1 packaged-artifact test (46 total). All pass;
zero failures, errors, or skips. Nine new deterministic tests cover selection
publication before dispatch, awaiting completion, failed children, durable child
skip, distinct paths, retained runtime, malformed completion metadata, missing
named lookup, empty/fresh lookup, and owner-dispatched slash commands.

Exact producer SHA-256:
`daf8da6c1afb5ec48e08e81bfa18154c1d68576db2ec3e1d0056e61aa2213159`.
Exact consumer SHA-256:
`ccab3c707e85c7d7c974632159ea60b7fd7fb70f865efb4f5095ecc54fb09069`.
All 1,834 producer and 2,451 consumer base classes have major version <=52.

The original live parent-completion acceptance changed from RED to PASS on that
exact consumer (case `b2c33dc193`): SQLite retains the parent while a controlled
child is pending, then removes it only after release. Two real SIGKILL/restart
cases passed 12 checks each: offline `2eafc3b0d8` and timed `556b69130a`. Both
persist a selected child's completed checkpoint while its parent awaits it,
explicitly save native player data, and kill the owned Java process (exit -9).
The fixture changes its candidate list to only the opposite branch before
restart. Automatic login/timed replay still selects the persisted original child,
skips its completed effects, preserves occurrence identity and exactly the
observed 3 diamonds/7 experience, removes the parent, and shuts down cleanly.
SQLite integrity and Java 8 linkage checks pass. The initial timed fixture
preflight hit a TIME_WAIT bind conflict after the prior stopped process; no Java
listener remained. The fixture preflight now uses SO_REUSEADDR, matching the
server's bind behavior; no production transport or delay was changed.

This does not prove unacknowledged external effects, power loss, cross-store
atomicity, or every nested reward form. `Random` fallback/inline paths,
`AdvancedRandomReward`, general `Rewards` lists, Javascript/Lucky branches,
RewardBuilder integration and their restart/failure cases still need audit and
backport work. Existing v3 queue decoding remains; the inactive legacy source
tree is retained. No push, PR, release, deployment, or independent final approval
is claimed by this cohort.

## Awaited Rewards lists and RewardBuilder configuration dispatch

A real Java 8/Spigot 1.8.8 regression on the previous exact consumer proved
that `Rewards: [child]` could remove its offline parent while the child was still
pending. The legacy builder/void dispatch detached the child. The same unchanged
parent-retention acceptance is now PASS (`aa693b5e37`) on the new consumer.

`RewardBuilder.sendAsync(user)` and the configuration-path async overload now
propagate completion for list, scalar, and inline section forms. Existing `send`
and void overloads retain their admission behavior. The async `Rewards` injector
uses that lane and explicitly captures its parent replay state, path and occurrence.
List admission persists an ordered snapshot before effects; each child has an
index-specific path and completion cursor. Repeated names remain distinct valid
rewards. A retry skips completed children before lookup, recovers the frozen list
even if configuration was removed/changed, and shares only reserved replay metadata
between otherwise copied child placeholders. Missing durable configuration and
invalid cursor bounds fail rather than acknowledging undispatched work. Inline
prefix/suffix and direct/sub-definition lookup follow the existing 1.8 rules;
materializing a required generated snapshot runs off the captured Bukkit owner.

This adapts the list/child behavior in pinned-main reward code, including parts
of `b2a7e976138b00218156e24f09f04294e15aa1f6`,
`3465fd4c611e872d9afb1d7834342b46f36ac2b3`, and
`6da6565c87b0bdb11122d69a87668033db1e88f5`. Java 8 uses explicit empty-list and
exceptional-future construction. There is no modern prepared-catalog transplant,
new production dependency, schema change, release/version change, or proxy wire
change. Reserved list markers use the existing bounded queue/checkpoint envelope;
existing queue decoders and the inactive compatibility source tree remain.
These mixed upstream commits are still partial dispositions, not wholly complete.

Five new production-entry tests cover sequential duplicate names/awaiting both,
failed-last-child retry with the completed first child missing and original
configuration removed, cursor bounds before dispatch, inline builder naming and
off-owner snapshot preparation, and valid empty versus missing durable config.
Their final waits are bounded at two seconds; unchanged assertions pass.

Actual Java 8 builds used these commands from the corresponding isolated forks:

```sh
mvn -B -f AdvancedCore/pom.xml -Dmaven.resolver.transport=wagon \
  -Dmaven.repo.local=/workspace/votingplugin-1.8-port-workspace/.m2/repository \
  -Djava.io.tmpdir=/workspace/votingplugin-1.8-port-workspace/runtime/tmp clean install
mvn -B -f VotingPlugin/pom.xml -Dmaven.resolver.transport=wagon \
  -Dmaven.repo.local=/workspace/votingplugin-1.8-port-workspace/.m2/repository \
  -Djava.io.tmpdir=/workspace/votingplugin-1.8-port-workspace/runtime/tmp clean verify
```

Temurin 8u504 `JAVA_HOME` and its `bin` were first on PATH; Maven is 3.9.9.
AdvancedCore: 533 unit + 18 artifact checks = 551 passed. VotingPlugin:
45 unit + 1 artifact check = 46 passed. Zero failures, errors or skips. The focused
run passed 60 tests; after bounding the five new tests they passed again without
production changes. Exact producer SHA-256:
`c45ffec245a3453e0182a7546e5afc148289c45138bbe9b788af72fca8e8424c`;
consumer SHA-256:
`18b69d21a50191910fe6737ff52260d7e02f0597af0efadffd1ed0fabb5f8a1b`.
All 1,834 producer and 2,451 consumer base classes remain major <=52.

Real SIGKILL cases offline `ac0a2b3e4f` and timed `204413c29f` passed 12 checks
each on that consumer. The first child performs/saves 3 diamonds and 7 experience,
then holds after its checked physical child checkpoint, before the list cursor can
acknowledge completion. The owned Java process exits -9 without plugin disable.
The fixture replaces the configured list with an unavailable reward before
restart. Automatic login/timed replay still recovers the original two-child list,
skips the first completed child's effect and finishes the second once. Final
native totals are 6 diamonds and 14 experience, durable fixture count is exactly
2, occurrence identity survives, parent queue removal and SQLite integrity pass,
and shutdown/linkage checks are clean. The first offline fixture attempt retained
its earlier single-child three-diamond assertion; client evidence already showed
six. The fixture was corrected to require the proper aggregate six/14 for two
children and rerun; production code and per-child once assertions were unchanged.

Remaining scope includes random inline/fallback selection, other nested injectors,
prepared definition freezing for inline configuration changes, clear/wipe races,
lifecycle cases, the full upstream ledger and broader platform acceptance. This
cohort does not prove unacknowledged effects, power loss, cross-store atomicity,
or arbitrary command/plugin effects. No independent final approval, PR readiness,
push, PR, release or deployment is claimed.


## Random and AdvancedRandomReward awaited completion (2026-10-06)

The existing anonymous `Random` and `AdvancedRandomReward` injections now opt into
completion-aware dispatch without changing their legacy synchronous callbacks,
registration identity, priorities, placeholders, defaults or YAML shapes. A
selected branch is checkpointed before dispatch, shares its parent's replay
state/occurrence, and the parent awaits the child's physical completion followed
by its durable single-child completion marker. Named selections, inline rewards,
fallbacks and advanced named/inline definitions use the existing admitted runtime.
No new dependency, release version, proxy format or schema is introduced.

Preserve the fork's unusual established chance semantics: `Chance: 0` and
`Chance: 100` both mean unconditional success. The deterministic fallback fixture
uses `Chance: -1`; it does not reinterpret zero or introduce probabilistic waits.
Absent, blank and empty-list optional fallback/inline definitions remain no-ops
on first admission. Missing unfinished durable definitions fail explicitly.

The strengthened live regression observed the actual registered branch callback:
old consumer case `7af7382e44` settled while its child was unresolved. An earlier
parent-timing-only attempt passed prematurely and is not defect proof. The new
fixture forwards the original callback/stage unchanged and requires that its
settlement occurs only after child release. The first inline run `809462721f`
failed because the fixture inserted a fileless reward into the file-backed reward
registry. Replacing that helper entry with a normal named reward file fixed the
fixture; production code was unchanged. Five corrected cases passed four checks
each: pick, inline, fallback, advanced-name and advanced-inline. Each checks actual
callback settlement, physical offline queue retention/removal, Java 8 linkage
and shutdown. This is bounded controlled-child acceptance, not a probabilistic
selection test or proof of arbitrary inline definition freezing across restart.

Validation used Temurin 8u504 and Maven 3.9.9:

```shell
mvn -B -f AdvancedCore/pom.xml -Dmaven.resolver.transport=wagon \
  -Dmaven.repo.local=/workspace/votingplugin-1.8-port-workspace/.m2/repository \
  -Djava.io.tmpdir=/workspace/votingplugin-1.8-port-workspace/runtime/tmp clean install
mvn -B -f VotingPlugin/pom.xml -Dmaven.resolver.transport=wagon \
  -Dmaven.repo.local=/workspace/votingplugin-1.8-port-workspace/.m2/repository \
  -Djava.io.tmpdir=/workspace/votingplugin-1.8-port-workspace/runtime/tmp clean verify
```

AdvancedCore: 538 unit + 18 artifact checks = 556 passed. VotingPlugin:
45 unit + 1 artifact check = 46 passed, using the exact locally installed producer.
All tests have zero failures, errors or skips. Ten focused nested-reward tests
passed. The initial full run reported three fixture errors because the legacy
MiscUtils singleton had captured a null plugin before mock initialization; the
fixture now binds/restores that singleton's plugin during each test, without
changing production chance logic or weakening assertions.

Producer SHA-256: `868b47287b057956c72b67e24a57acbb36a8be474684cced4c9e18c6fbcd08aa`; consumer: `f2ea3dbd2bd99246fb67e8f57c29738a526ff6ce4fa8e2d398b17d24be6714a3`.
All 1,834 producer and 2,451 consumer base classes have major <=52.

Prepared inline definition freezing, other nested injectors, clear/wipe/lifecycle
races, the remaining upstream ledger and broader platform acceptance remain in
scope. No unacknowledged-effect, power-loss, cross-store atomicity, arbitrary
command/plugin-effect guarantee, final independent approval or PR readiness is
claimed. No push, PR, release or deployment was performed.


Random pick SIGKILL acceptance passed 12 checks in each lane: offline
`ef5f04af4e`, timed `e4d5305248`, on the exact consumer above. The real owned Java
process exits -9 after the selected child's checked physical checkpoint and
explicit player save, before its parent finishes. The fixture changes the
candidate list to only the opposite named reward. Automatic login/timed startup
replay retains the original selection and occurrence, skips its completed native
effect, finishes/removes the parent queue and preserves exactly 3 diamonds and
7 experience with durable fixture effect count 1. SQLite integrity, restart,
shutdown and Java 8 linkage checks pass. This proves acknowledged named-selection
recovery for these lanes, not arbitrary changed inline/advanced definitions or
unacknowledged external effects.


## Detached generated snapshot publication (2026-10-06)

Connected the previously tested `RewardFileData.prepareGeneratedSnapshot` helper
to `Reward.setRewardFile`. Before this integration, legacy `setData` wrote and
reloaded individual keys on the registered predecessor, then changed its marker
before the final checked save. A forced failure of that final save reproduced a
changed predecessor; it was not an atomic acknowledged snapshot operation.
The new path prepares an independent candidate, uses one checked publication,
marks the candidate and updates the registry only after publication succeeds,
and acknowledges the source after registry replacement. IOException remains an
explicit failed snapshot operation. No API, configuration key/default, release
version, database schema or proxy payload was changed.

Three integration regressions cover failure with no legacy per-key writes,
acknowledged detached replacement with existing merge/provenance/header behavior,
and successful retry from an unchanged predecessor. The original mocked failed
publication test now injects failure at the candidate's no-argument `saveStrict`
call; its no-registration/no-created assertions are unchanged. The new integration
test was red on the previous source and passed after the production integration.
The focused run passed 29 tests with zero failures/errors/skips.

Actual Java 8/Spigot 1.8.8 case `e3504e8408` passed four checks. The fixture loads
and registers a real file-backed predecessor, replaces only its test target with
malformed YAML, and invokes the actual snapshot path. Checked publication fails;
malformed bytes and registered predecessor remain unchanged and the source is
not acknowledged. After restoring a valid test target, retry publishes/registers
a distinct candidate with expected header, generated provenance, values and
retained unrelated configuration. SQLite integrity, Java 8 linkage and clean
shutdown also pass. Fixture state stays under the isolated runtime directory.

Exact commands with Temurin 8u504 first on PATH and Maven 3.9.9:

```shell
mvn -B -f AdvancedCore/pom.xml -Dmaven.resolver.transport=wagon \
  -Dmaven.repo.local=/workspace/votingplugin-1.8-port-workspace/.m2/repository \
  -Djava.io.tmpdir=/workspace/votingplugin-1.8-port-workspace/runtime/tmp clean install
mvn -B -f VotingPlugin/pom.xml -Dmaven.resolver.transport=wagon \
  -Dmaven.repo.local=/workspace/votingplugin-1.8-port-workspace/.m2/repository \
  -Djava.io.tmpdir=/workspace/votingplugin-1.8-port-workspace/runtime/tmp clean verify
```

AdvancedCore: 541 unit + 18 artifact checks = 559 passed. Exact producer:
`09c93542775f1f1e2c76b0b14cf1b9f91311aacb84fdaea465bf3e778cf3bd53`. VotingPlugin: 45 unit + 1 artifact check = 46 passed against that
exact installed workspace-local producer; consumer: `ffec6f2ee73a71af5d3f22140dd8e8cc206d9d4b5f8cb81fb98d18c91dd5fd61`. Zero failures,
errors or skips. All 1,834 producer and 2,451 consumer base classes remain <=52.

This integration protects registered predecessor state on failed snapshot
publication. It does not by itself freeze every inline definition per occurrence
across configuration changes or prove power-loss/cross-store atomicity. Public
registry exclusion of generated snapshots, other nested injectors, lifecycle and
clear/wipe races, full ledger/platform acceptance and independent final review
remain in scope. No PR readiness, push, PR, release or deployment is claimed.


On this exact consumer, all five actual Random/AdvancedRandomReward pending-child
forms passed again. Both named-Random and frozen nested-list SIGKILL recoveries
were rerun in offline and timed lanes: four cases, 12 checks each, all passed.
Cases: 2649e77444, 92d5c20802, 2cd50318f8, 7cf7637de7. The Random tests change the candidate list to only the
opposite named child; list tests replace the configured list with an unavailable
reward. Acknowledged native effects remain once, unfinished list work completes,
occurrence identity and physical queue cleanup survive, and SQLite/linkage/clean
shutdown checks pass. These are acknowledged-checkpoint tests with explicitly
saved player state, not unacknowledged-effect or arbitrary inline-mutation proof.


## Generated queued snapshots stay outside the public registry (2026-10-06)

Ported the complete bounded behavior of pinned upstream commits
`6dfb241004d67babc12b9667b1016bad8e9aed07` (registry exclusion) and
`db1673135f7de00ebe6f4e2e4d324219ca465818` (null-safe guard), in the existing
legacy RewardHandler rather than copying the modern RewardRegistry refactor.
updateReward excludes a reward only when both its generated marker is true and
its backing folder is DirectlyDefined, case-insensitively. Ordinary/default-file
registration and file-identity replacement remain unchanged. Unmarked files in
that folder and marked files elsewhere preserve their established validation
path. Null configuration is safe for the new guard; a null reward still follows
the established invalid-input behavior after the guard.

Generated queue files remain available through getQueuedGeneratedReward, which
captures validated YAML and constructs a reward restricted to the
requested user. Persisted snapshot provenance keeps using that explicit loader;
strict normal provenance cannot borrow a generated snapshot. Existing direct/sub
handles, legacy persisted fallback and normal named/default-file resolution are
unchanged. The active VotingPlugin VoteTester uses public named lookup and does
not call the snapshot publisher or explicit loader. The inactive src.main.java
tree remains untouched. No API signature, release/configuration default, database
schema or serialized proxy/queue format changed.

Six new regression tests cover exclusion and public lookup fallback, same-named
ordinary reward retention, ordinary file replacement, exact folder/marker
boundaries, requested-user snapshot loading, and the null-config guard. Three
exclusion assertions failed against the preceding source; all six pass after the
port. The focused registry/resolution/publication suite passed 34 tests with zero
failures, errors or skips.

Actual Spigot 1.8.8 case `2077bf8783` passed five checks using the exact consumer
below. It retains the malformed-target failure/no-mutation checks, then verifies
successful detached publication is retrieved through the explicit loader while
the public predecessor remains unchanged. The loaded snapshot is restricted to
exactly the requested fixture user. Header/merge/provenance values, SQLite
integrity, Java 8 linkage and clean shutdown pass. The new fixture preserves the
previous publication fixture/evidence and updates the lookup assertion specifically
for the proven upstream exclusion; it does not weaken failure or persistence
assertions.

Validation with Temurin 8u504 first on PATH and Maven 3.9.9:

```shell
mvn -B -f AdvancedCore/pom.xml -Dmaven.resolver.transport=wagon \
  -Dmaven.repo.local=/workspace/votingplugin-1.8-port-workspace/.m2/repository \
  -Djava.io.tmpdir=/workspace/votingplugin-1.8-port-workspace/runtime/tmp clean install
mvn -B -f VotingPlugin/pom.xml -Dmaven.resolver.transport=wagon \
  -Dmaven.repo.local=/workspace/votingplugin-1.8-port-workspace/.m2/repository \
  -Djava.io.tmpdir=/workspace/votingplugin-1.8-port-workspace/runtime/tmp clean verify
```

AdvancedCore: 547 unit + 18 artifact checks = 565 passed; producer SHA-256
`47cb1e433a7458a0318241d849bb248fdca848b6a6d0af34b9836af3ae1a94bb`. VotingPlugin: 45 unit + 1 artifact check = 46 passed against that
exact installed workspace-local producer; consumer SHA-256 `4617acfd7b77237f1cf71be4be61c1721e6164a594035ff17b96e202983b6249`.
Zero failures, errors or skips. All 1,834 producer and 2,451 consumer base classes
remain <=52.

Only these two bounded upstream changes are fully dispositioned by this cohort.
Other nested injectors, prepared inline-definition freezing, clear/wipe/lifecycle
races, the remaining ledger/platform matrix and final independent review remain
in scope. No power-loss, arbitrary-effect guarantee or full-main equivalence is
claimed. No PR readiness, push, PR, release or deployment is claimed.


On the exact consumer above, all five Random/AdvancedRandomReward completion
forms passed again. Four acknowledged-child SIGKILL cases also passed 12 checks
each: Random offline `35cebe0499`, Random timed `072878ce9a`, nested-list offline
`ec6647b7eb`, nested-list timed `1fb576451b`. Opposite random candidate lists and
removed nested lists do not change the acknowledged replay selection/cursor;
completed native effects remain once and unfinished list work completes. Queue
removal, occurrence retention, SQLite integrity, native saved-player state,
Java 8 linkage and clean shutdown pass. These acknowledged-checkpoint tests do
not prove arbitrary mutated inline definitions or unacknowledged external effects.


## AdvancedRewards sequential awaited replay (2026-10-06)

Adapted the pinned AdvancedRewards frozen-key sequence behavior into the existing
anonymous legacy injector. It checkpoints the configured branch-key snapshot,
runs children sequentially, awaits each child's real completion, and persists its
cursor before advancing. Child replay paths include the branch key and index,
share the parent's state/occurrence and dispatch through the admitted runtime.
Missing unfinished definitions fail explicitly. Async injector serialization is
disabled so recursive children using the same shared injector cannot deadlock;
legacy synchronous callbacks, registration identity, priority/post ordering and
synchronous synchronization configuration remain unchanged.

Preserved the fork's existing prefix `parent_AdvancedRewards_branch`, including
the resulting inline generated name `parent_AdvancedRewards_branch_branch`.
Pinned modern code uses a different shorter prefix; copying it would change
existing generated names. The regression asserts the actual inline constructor
name and propagates child failure. Another test drives two actual registered
builtin named children with incomplete futures and proves ordered admission and
parent retention through both. Both new tests failed before the adaptation;
the focused nested suite now passes 12 tests with zero failures/errors/skips.

Old exact consumer `4617acfd7b77237f1cf71be4be61c1721e6164a594035ff17b96e202983b6249`
also failed real Spigot case `db1fc3dc35`: direct instrumentation observed the
registered AdvancedRewards callback settle while its child was unresolved. On
the new exact consumer, named, inline and recursive forms pass four checks each:
physical parent queue retained while child is pending, queue removed only after
completion, actual callback settles after child release, and Java 8 linkage/clean
shutdown. Recursive acceptance checks the real nested injector rather than an
implementation-shaped synchronization-flag assertion.

Temurin 8u504 was first on PATH; Maven 3.9.9 commands:

```shell
mvn -B -f AdvancedCore/pom.xml -Dmaven.resolver.transport=wagon \
  -Dmaven.repo.local=/workspace/votingplugin-1.8-port-workspace/.m2/repository \
  -Djava.io.tmpdir=/workspace/votingplugin-1.8-port-workspace/runtime/tmp clean install
mvn -B -f VotingPlugin/pom.xml -Dmaven.resolver.transport=wagon \
  -Dmaven.repo.local=/workspace/votingplugin-1.8-port-workspace/.m2/repository \
  -Djava.io.tmpdir=/workspace/votingplugin-1.8-port-workspace/runtime/tmp clean verify
```

AdvancedCore: 549 unit + 18 artifact checks = 567 passed; SHA-256 `dd27d203224df480fa91fc2135640967f90a54a1b5ab07bdad65eb4c47010557`.
VotingPlugin: 45 unit + 1 artifact check = 46 passed against that exact installed
workspace-local producer; SHA-256 `27fb65e2c40b751a186652ca9d397503cca5f48e5c9b5d6da72df1b6e2a79bcd`. Zero failures/errors/skips.
All 1,834 producer and 2,451 consumer base classes remain major <=52.
No dependency, API signature, config key/default, release version, database schema
or proxy/queue format changed.

Other nested injectors, arbitrary prepared inline-definition freezing,
clear/wipe/lifecycle races, the remaining ledger/platform matrix and final
independent review remain in scope. A frozen branch-key list does not prove that
all mutable branch values or external side effects are frozen. No full-main
alignment, power-loss/cross-store guarantee, final approval or PR readiness is
claimed. No push, PR, release or deployment was performed.


AdvancedRewards SIGKILL acceptance passed 12 checks in each lane on that exact
consumer: c052506815, f8c1e0e7b8. The first branch cursor is acknowledged before the
second child's physical checkpoint is held. Both native effects are saved before
SIGKILL (-9 without plugin disable). The fixture removes the completed first
branch and adds an unavailable new branch before restart. Automatic offline-login
and timed-startup replay skip the missing completed branch, retain the original
frozen key list, finish the second child's checkpoint without repeating either
effect and remove the parent queue. The durable effect count remains exactly 2;
saved native totals remain 6 diamonds and 14 experience. Occurrence identity,
SQLite integrity, Java 8 linkage and clean shutdown pass.

The initial offline fixture attempt `6f3652455c` recovered correctly but retained
an assertion from the earlier one-child crash point expecting effect two in the
restart log. That assertion was corrected for this two-completed-effects crash
point: require effect two in the initial log, no effect execution in the restart
log, and count 2 before/after restart. Both lanes were rerun; production code and
native once assertions were unchanged. This is acknowledged named-branch/cursor
recovery, not arbitrary pending inline mutation, unacknowledged effects or power
loss.


## Javascript conditional branch awaited replay (2026-10-06)

Adapted the pinned conditional Javascript async selection behavior into the
existing anonymous legacy injector. The selected TrueRewards/FalseRewards branch
is checkpointed before child dispatch, shares the parent's state and occurrence,
and is awaited through the existing builder/configuration dispatch and durable
single-child marker. Replay uses the stored decision rather than evaluating the
expression again, even when Javascript.Enabled was disabled after admission.
Missing unfinished definitions and unknown stored branch values fail explicitly;
the durable single-child marker skips its child before definition resolution.

Preserved the existing external-player placeholder then local-placeholder
substitution order and Java 8 JavascriptEngine call. No modern addPlaceholders
API or Java 9+ method was copied. The legacy synchronous callback, standalone
Javascripts list handler, expression/engine failure semantics and configuration
keys are unchanged. The awaited path carries parent placeholders to its child.
Missing, blank or empty-list optional selected branches are valid initial no-ops;
disabled fresh Javascript does not construct an engine or dispatch a child.
Inline generated names retain the `parent.Javascript_TrueRewards` prefix shape.

Four new actual-builtin regressions cover true/false child retention and expression
substitution, disabled execution, optional empty branches, and actual inline
constructor name/failure propagation. The first selected-child regression failed
against the preceding source; the focused nested suite now passes 16 tests with
zero failures/errors/skips. Old exact consumer case `6d3eea0fad` also fails the
real Java 8/Spigot callback-settlement assertion, proving the gap beyond mocks.

Actual true, false, inline and recursive Java 8 Nashorn forms pass four checks
each on the exact consumer below: physical parent queue remains until selected
child completion, callback settlement follows child release, queue removal occurs
only afterward, and linkage/shutdown stay clean. This uses the real builtin
expression engine; no mocked boolean decision is used by live acceptance.

Temurin 8u504 was first on PATH; Maven 3.9.9 commands:

```shell
mvn -B -f AdvancedCore/pom.xml -Dmaven.resolver.transport=wagon \
  -Dmaven.repo.local=/workspace/votingplugin-1.8-port-workspace/.m2/repository \
  -Djava.io.tmpdir=/workspace/votingplugin-1.8-port-workspace/runtime/tmp clean install
mvn -B -f VotingPlugin/pom.xml -Dmaven.resolver.transport=wagon \
  -Dmaven.repo.local=/workspace/votingplugin-1.8-port-workspace/.m2/repository \
  -Djava.io.tmpdir=/workspace/votingplugin-1.8-port-workspace/runtime/tmp clean verify
```

AdvancedCore: 553 unit + 18 artifact checks = 571 passed; producer SHA-256
`9f62c265f429461eb908c613128b00e48c80689c367380ea7e36d58019de96c1`. VotingPlugin: 45 unit + 1 artifact check = 46 passed against that
exact installed workspace-local producer; consumer SHA-256 `2d6aabf7cc684bfb6df4ee28389ae55105e245a53991079342ef98ec163ba6d6`.
Zero failures/errors/skips. All 1,834 producer and 2,451 consumer base classes
remain major <=52. No dependency, API signature, release metadata, database schema
or proxy/queue format changed.

Lucky, AdvancedWorld, arbitrary prepared inline-definition freezing,
clear/wipe/lifecycle races, the full remaining upstream ledger/platform matrix
and independent final review remain in scope. This selection backport does not
freeze arbitrary mutable inline values or prove external-effect/power-loss
atomicity. No full-main alignment, final approval or PR readiness is claimed.
No push, PR, release or deployment was performed.


Javascript SIGKILL acceptance passed 12 checks in each lane on that exact
consumer: 474e521c16, ab75755fe2. The true branch child's physical checkpoint is
acknowledged and native player data saved before SIGKILL (-9 without plugin
disable), while its parent remains pending. The fixture changes the expression
to false and sets Javascript.Enabled false before restart, retaining both branch
definitions. Automatic offline-login/timed-startup replay uses the persisted true
branch, does not run its completed native effect again, acknowledges the remaining
child/parent state and removes the physical queue. Durable effect count remains
exactly 1; native totals remain 3 diamonds and 7 experience. Occurrence identity,
SQLite integrity, linkage and shutdown pass. These are acknowledged selected-child
checks with retained definitions and explicitly saved player state, not arbitrary
mutated pending inline values, unacknowledged effects or power loss.


## Lucky selected-child completion and replay

The existing Lucky builtin now has an additive async callback that freezes the
winning paths before child dispatch and awaits the existing durable sequential
child cursor. Positive integer denominator parsing, normalized Lucky.<number>
paths, descending denominator order, parent-level OnlyOneLucky, legacy prefixes,
registration priority and the original synchronous callback remain unchanged.
The async path does not hold the shared injection monitor across nested futures.
The guaranteed denominator 1 regression fails on the preceding implementation
because its result settles while the child is pending. Four new tests exercise
the actual registered builtin, including child failure, invalid keys, ordering,
OnlyOneLucky scope and retry without reevaluating chance. Focused suite: 20 pass.

With Temurin 8u504 and Maven 3.9.9, the same explicit workspace-local repository
and temporary directory commands recorded above were run: AdvancedCore clean
install passes 557 unit + 18 artifact tests (575); VotingPlugin clean verify
passes 45 unit + 1 artifact test (46) against the exact installed producer.
Zero failures/errors/skips. Producer SHA-256:
6433d0baeed1afd7b12756dfc75fc6ece9df7948a12e6808c93719b6b6f8caf0.
Consumer SHA-256:
1231b7ff31d3f7eeae7a00f3ead804ff73161892e58b60e45c3a00b9abc5ef47.
All 1834/2451 base classes remain major <=52.

Actual Java 8 / Spigot 1.8.8 named, inline and recursive forms pass six checks:
physical parent retention while the child is pending and queue removal only
following child completion, on the exact consumer above. Runtime fixtures use
the guaranteed denominator 1 rather than probabilistic success assertions.

This freezes the selected path sequence, not arbitrary mutable pending inline
values. AdvancedWorld, prepared inline definitions, lifecycle/admin races, the
remaining upstream dispositions and full platform matrix remain unfinished.
No schema, release metadata, public API or proxy payload change is made here.
No final review, main alignment or PR readiness is claimed; no PR or push.

Offline bb258d9d2c and timed bd7407cf76 SIGKILL acceptance pass 12 checks each.
After an acknowledged selected-child checkpoint and explicitly saved native
player data, kill -9 leaves the parent pending. Changing OnlyOneLucky to true
and adding Lucky.2 before restart does not change the persisted selection.
Automatic login/timed startup recovery retains occurrence identity, native
3 diamonds/7 experience and effect count 1, then removes the physical queue.
SQLite integrity, clean shutdown and Java 8 linkage pass. This does not prove
unacknowledged effects, power loss or arbitrary changed pending definitions.


## AdvancedWorld awaited children and recovery

The existing builtin now freezes its world-key sequence and awaits each child's
completion and durable cursor. It captures the admitted runtime and uses its
owner dispatcher to inject the legacy Worlds requirement before child creation.
Parent-name AdvancedWorld prefixes, exact world names, registration priority,
post-reward placement and the synchronous callback remain unchanged. Empty or
absent fresh configuration remains a no-op. An unfinished missing definition
fails explicitly; a completed cursor skips lookup. Async shared-injector locking
is disabled to permit recursive AdvancedWorld rewards.

Two actual-builtin tests exercise sequential pending child futures, failure,
legacy inline names, owner-thread creation, Worlds injection and optional empty
configuration. The focused suite passes 22 tests in the full build. A preceding
source test fails; actual old-consumer case 49486d1ce6 also fails because its
AdvancedWorld callback settles while the child remains pending. The exact new
consumer passes inline and recursive Spigot 1.8.8 pending-queue/completion cases
279b4d1eb5 and c7d0310724 (four checks total).

With Temurin 8u504/Maven 3.9.9 and the explicit local Maven/temp paths already
recorded above, AdvancedCore clean install passes 559 unit + 18 artifact = 577;
VotingPlugin clean verify passes 45 unit + 1 artifact = 46 against the exact
locally installed producer. No failures/errors/skips. Producer SHA-256:
2d4f2ad0890217befe8c8bf71dde8e786d40f5580809e2e71e5426a5810f75e2.
Consumer SHA-256:
f3c14f26bc244b5a8d9c7db989d883c8d3acb15724fb2f534cbd6d651d00df10.
All 1834/2451 base classes remain major <=52.

Offline fd6fedf5d1 and timed 86dca25e34 actual SIGKILL cases pass 12 checks each.
After the selected world's named child's physical checkpoint is acknowledged
and player data saved, process kill -9 occurs while the parent remains pending.
A new unmatched world/candidate is added before restart. Automatic login/timed
recovery retains the frozen world list and occurrence, does not repeat native
3 diamonds/7 experience (effect count remains 1), and completes queue removal.
SQLite integrity, clean restart shutdown and Java 8 linkage pass. The selected
world and its pending definition remain available; these cases do not prove
arbitrary changed inline values, unacknowledged effects or power-loss atomicity.

This is a partial adaptation of mixed upstream 167952a0feec97017a550477e54694a944648fac;
remaining changes in that commit and the full upstream ledger still need audit.
No public signature, schema, release metadata or proxy format was changed.
No full-main alignment, final review or PR readiness is claimed. No push or PR.


## Command replay sequence foundation (integration pending)

The additive replayCommandSequence overloads freeze concrete command expansions
in the existing reserved command-metadata namespace and v1 snapshot codec, then
await each physical dispatch and durable cursor. Snapshot/pending-work queries
mirror the pinned API. Java 9 collection factories and failedFuture are adapted
to Java 8. Existing void command callbacks and their timing remain unchanged in
this commit; builtin command wiring is the next implementation phase.

Four helper regressions prove ordered awaiting, restored cursor skipping and
frozen expansions despite changed input, initial publication failure with no
physical command, malformed cursors/expansion mismatch, and same-state retry of
an unacknowledged snapshot. The last reproduces an issue in the initially copied
pinned helper: createdSnapshot=false bypasses pre-dispatch persistence after a
failed initial write. The candidate reacknowledges retained metadata before
physical dispatch or completed return. It does not claim transactional or
exactly-once external command effects across an unacknowledged crash.

Temurin 8u504/Maven 3.9.9, explicit workspace-local Maven repository and temp
paths, producer clean install and exact consumer clean verify: 563 unit + 18
artifact = 581 AdvancedCore checks; 45 unit + 1 artifact = 46 VotingPlugin checks.
All pass, zero failures/errors/skips; focused nested/helper suite has 26 tests.
Producer SHA-256: 4683b53430943656484c7f56938853a2bb1450bf7b2f7198936afd29666088e5.
Consumer SHA-256: 0184c393ede9af1faa83a3e6908d9bb0df31c2aabebf445b301a4d53e4ff9997.
All producer/consumer base classes remain major <=52. No release metadata,
existing public signature, database schema or proxy payload change is made.

This helper has not yet been exercised through actual builtin commands or live
command SIGKILL acceptance. Captured owner admission with legacy tick staggering,
console/player/numeric/random builtin integration, sibling snapshot retry audit
and live storage/recovery proofs remain required before claiming command replay
complete. Arbitrary pending inline-definition immutability is not a separate
pinned-main contract; retain root snapshot provenance and audit actual prepared
effect behavior rather than inventing an additional nested-YAML queue format.
The whole backport remains incomplete; no final review, PR or push.


## Captured Bukkit tick admission and awaited console API

ServerThreadRewardDispatch now exposes additive dispatchAfterTicks, using Bukkit
runTaskLater for positive ticks and runTask for zero. It retains the same owner,
pending admissions, monotonic deadline and close/claim fencing. A zero-tick task
remains queued even on the primary thread; admitted physical stages cannot time
out as unexecuted. No wall-clock replacement for Bukkit ticks is introduced.

MiscUtils adds awaited single/list console APIs. Preparation stays on the
captured owner, legacy single online-player and list offline-player substitution
order stays intact, and leading slash removal is retained. Lists keep an initial
next-tick dispatch and one tick between staggered commands; nonstaggered later
commands use immediate owner admission. Replay state/key are captured before
scheduler hops and passed explicitly, rather than relying on callback thread
locals. The explicit replayCommandSequence context overload is now public for
this cross-package use. Existing void overloads and builtin wiring are unchanged.

Three dispatcher regressions prove zero-tick handoff, exact positive Bukkit
ticks, deadline/retirement fencing and awaited physical completion; the real
MiscUtils list regression proves next-tick/one-tick progression, slash removal,
player placeholder substitution and primary-thread dispatch. Focused suites:
40 passed. Java8 clean install/consumer clean verify, same explicit local Maven
and temp paths: 567 unit + 18 artifact = 585 AdvancedCore; 45 unit + 1 artifact =
46 VotingPlugin. Zero failures/errors/skips, exact installed producer verified.
Producer SHA-256: 9ef5efc0c05f860474b918fafa1cea27f35b5635e27a8425157ba975cfc4d4e0.
Consumer SHA-256: 026d00d77c56de1da2d2cb068c5e2bab9544d6ad26d384623e58ef6f0415bd26.
All base classes remain major <=52.

Actual builtin numeric/random/console/player integration and live command
completion/SIGKILL acceptance still remain. Do not infer complete command replay
or exactly-once external effects from these API tests. Sibling failed-publication
retry and lifecycle/admin queue ownership still need audit. No schema, existing
signature, release metadata or proxy payload is changed. No push, PR or final
review; the complete backport remains in progress.

## Awaited builtin command replay

The actual Command, Commands list/section, NumberCommand and RandomCommand
registrations now use completion-aware command APIs for asynchronous reward
replay. Existing synchronous callbacks, public void methods, configuration
keys/defaults, priority and placeholder registration stay unchanged. Numeric
selection and random selection are frozen with existing replay metadata;
concrete command expansions and per-lane cursors use the existing command
snapshot format. Console/player mixed sections capture replay context before
the availability continuation, preserve console-before-player ordering and
check required player availability before console side effects. Player chat
commands retain their slash prefix and validate the captured live player on
Bukkit owner admission. No wire/schema or release metadata changes.

Six regression methods exercise actual registrations, awaited failures,
player availability/disconnect fencing, frozen numeric result/random choice
and expanded substitution on retry, empty lists and malformed list elements.
Focused nested suite:33 PASS. Java8 clean install:573 unit +18 artifact =591
AdvancedCore PASS. Exact workspace-local producer identity verified before
consumer clean verify:45 unit +1 artifact =46 VotingPlugin PASS. All tests
have zero failures/errors/skips. Both artifacts have base class major <=52.

AdvancedCore SHA256:
5cc34dff475fd8ea4acaaa46959745fc20303cd7aca59b3ce0ff7a1c0c01d245.
VotingPlugin SHA256:
0517d3039e8f53c73343acd02c5247d4ef1e8f1a5c4960fff12e9f2d161fa0e2.
Build commands use actual JDK8u504, Maven clean install/clean verify,
-Dmaven.resolver.transport=wagon, the explicit isolated .m2/repository and
runtime/tmp paths, as established earlier. Exact evidence commands/logs:
command-final-clean-install.log and command-final-consumer-clean-verify.log.

Real Java8/Spigot1.8.8 acceptance covers seven command forms (28 checks),
completed command checkpoint SIGKILL recovery offline/timed (22 checks), and
partial-list SIGKILL recovery offline/timed (22 checks). Partial-list tests
physically confirm the first command cursor in SQLite, deliberately reject
the second command before any effect, kill without plugin disable, clear the
configured list and automatically resume. Only the unacknowledged second
command executes; native totals are6 diamonds/14 experience, the original
occurrence survives and the queue clears. Completed-sequence tests retain
3 diamonds/7 experience without redispatch. Native player data is explicitly
saved by the acceptance fixture. The rebuilt consumer's complete base-class
contents match the live-tested consumer byte-for-byte; ZIP hashes differ.

These tests do not prove exactly-once effects when an external command acts
before its acknowledgement fails, power-loss durability, every transport or
storage backend, or the complete upstream backport. Mixed upstream commits
remain PARTIAL. Original167 checkouts remain unchanged. Runtime fixtures,
logs, artifacts and Maven caches stay outside tracked sources. No push/PR.

## Permission and delayed-login backport

Permanent and timed grants reuse the existing UUID handler. Consecutive offline grants
merge instead of replacing pending state. Existing public seconds-based APIs and
HashMap getter signatures remain unchanged. Fresh offline durations start on login;
already timed/restored grants preserve absolute epoch-millisecond deadlines, including
across real disconnect/reconnect. The legacy `permission%line%epochMillis` persistence
format remains readable and release versions are unchanged. No modern ParsedDuration
dependency or Folia dispatch contract is introduced into this Bukkit1.8 artifact.

Expiry timers submit Bukkit-owner work; expected-deadline and handler/session identity
checks fence stale callbacks after renewal, permanent conversion, logout, and shutdown.
The quitting Player's attachment is detached while tracked state moves offline. Stale
quit events cannot detach a replacement session. Repeated login for the same Player
reuses its attachment; successor attachment creation precedes predecessor retirement.
Failed detachment/handoff preserves ownership for retry. Expiry admission precedes
renewal metadata replacement, and successful offline grant prefixes are consumed
individually so retry cannot reset their deadlines.

Delayed joins capture the existing owner dispatcher and pending Player identity.
Permission attachment and vanish metadata checks run on the Bukkit owner, while
AdvancedCoreLoginEvent retains its declared asynchronous contract. The optional
SuperVanish show callback also validates live Player identity and admits permission
work on the owner. Authentication/vanish suppression and existing login delays remain.
No optional integration is made mandatory. Failures are reported rather than silently
continuing past failed preparation.

Shutdown fences callbacks, saves active and pending timed state, and checks actual
expiry executor termination. Failed publication remains retryable. Malformed legacy
UUID/timestamp restoration fails visibly, retires its owned executor, and preserves
source evidence. Retained PlayerPermissionHandler grant/login/attachment methods
share manager-before-player locking and reject after close; bounded concurrency tests
prove shutdown cannot overtake admitted direct grants. Explicit removal remains
available for revocation after close. Legacy raw mutable getters do not gain a claim
of arbitrary external mutation safety. Fresh offline relative grants are not claimed
durable across restart; persistence retains already timed/restored absolute grants.

Verification used the actual Java8 JDK and workspace-local Maven repository:
`mvn -B -f AdvancedCore/pom.xml clean install` with explicit Java8/local-repo flags
passed604 unit +18 artifact tests (**622**); `mvn -B -f VotingPlugin/pom.xml clean verify`
with the same flags passed45 unit +1 artifact tests (**46**) against the byte-identical
installed producer. Focused permission/login tests passed31. Failures/errors/skips
are zero. Both shaded artifacts have base class major<=52. Producer SHA256:
`505ec249da440ec13fe39168950961a60611032d8255662f066a30ab58ad3ef4`;
consumer SHA256:
`36174fc3e462b93b14a59fe6fdb23305c00b844ae6f24201658c1eb8dfa8b407`.
Exact full commands and terminal logs are retained in isolated evidence.

Ten real Java8/Spigot1.8.8 native permission checks and five representative legacy
TimedPermissions upgrade checks pass on that exact consumer. Native acceptance covers
physical grants, expiry/renewal, repeated login, real logout/reconnect, a3-second fresh
grant queued during a4-second disconnect, original active deadline preservation,
SQLite integrity and clean shutdown/linkage. Upgrade acceptance preserves the original
stored epoch deadline through automatic login and physical expiry. Native SuperVanish
integration and the full compatibility matrix remain unverified. Cache cleanup
lifecycle and complete upstream patch/caller classification remain separate work;
this cohort does not establish whole-main parity or PR readiness.


## Cache cleanup owner and session-generation backport

Periodic cache cleanup captures Bukkit Player identities on the existing captured owner
dispatcher, then reconciles session markers and flushes/retires caches on the manager's
storage worker. Native joins mark online even when delayed user loading is disabled;
quits mark offline and capture UUID/name before their worker callback. Generation-stamped
offline tombstones prevent an older queued snapshot from erasing a newer quit or
resurrecting it. Java8 immutable state classes and copied collections replace modern
records/Set.copyOf. Offline storage identity resolution preserves the existing fork's
PlayerManager contract.

Cleanup reuses canonical UserStorageOwnership. It rechecks the selected handler and
online state under storage ownership; accepted physical flushes retire their generation
even if a join arrives during the flush. Failed retirement retains mapped pending work
for retry, and notification-created successor generations remain protected by existing
conditional removal. Owner retirement fences queued capture; admission/storage failures
are reported and retained as the last diagnostic failure without stopping later attempts.
Explicit removeCache remains unconditional invalidation, matching pinned main.

Focused cache/registry/login/permission tests49 PASS. ActualJava8 producer clean install
614 unit +18 artifact =632 PASS; exact locally installed producer consumer clean verify
45 unit +1 artifact =46 PASS, failures/errors/skips zero. Base classes remain major<=52.
Producer SHA256 `c81bcf8b69f74d46b6f9d1ac0f736ae68bd395935434b3b965d90d1e52dbf0d3`;
consumer SHA256 `6bf2c652dcdfa08ee4da7ee745989599546f9534ffc8b10f1a072e82e439481f`.

Six real Java8/Spigot1.8.8 cache checks pass on that exact consumer: automatic existing-
user cache loading, online protection after a storage-worker barrier, queued offline
flush/retirement, distinct PlayerName value persisted in SQLite, integrity and clean
shutdown/linkage. Ten native permission and five legacy timestamp upgrade checks also
pass on the rebuilt consumer after join/quit integration. Initial cache fixture failure
used an unstored new player, whose normal login correctly does not populate a stored-
user cache; the corrected fixture seeds a representative legacy SQLite user and retains
all physical assertions. A headless test mock-restubbing error was corrected before
final passing validation; production assertions were not weakened.

Remaining full-patch audit includes the pinned cleanup snapshot-version fence against
in-place cache mutation while canonical ownership is awaited, wider cache population/
refresh callers, and runtime reload/failure matrix. This cohort does not establish
whole-main parity or full-backport readiness. Configuration, schemas, public existing
APIs, proxy wire formats and release metadata are unchanged.


## Periodic cleanup snapshot-version fence

Periodic cleanup now selects the cache snapshot version before waiting for canonical
storage ownership, then rechecks version, mapped handler identity and online state
under that owner. A newer in-place mutation makes that cleanup skip without physical
writes; a later cleanup can flush and retire normally. The package-private version
getter does not change existing public APIs. Explicit cache invalidation remains
unconditional. A cache invalidated after a concurrent join is retired safely but is
not logged as an offline eviction, matching the final pinned upstream behavior.

Both defects have genuine failing regressions followed by19 passing cache/registry
checks. The ownership-wait regression uses the actual ReentrantLock and a newer queued
mutation, rather than a mocked successful storage path. The join-during-flush test
checks retirement and the absence of misleading offline-removal logging.

Final actualJava8 clean install615 unit +18 artifact =633 PASS; exact local producer
consumer clean verify45 unit +1 artifact =46 PASS, failures/errors/skips zero. Both
artifacts retain base class major<=52. Producer SHA256:
`6f154a039f1f25a90cf1636ba05f3ae41527ae7a3218ca51efd17012178289a0`;
consumer SHA256:
`1c1f27220ba78bc78d07b2dbdc3bace103a319ee81b4b1c1e8ad912da3d4e51b`.
Six native cache, ten native permission/reconnect and five legacy timestamp upgrade
checks pass on this exact final consumer in real Java8/Spigot1.8.8 fixtures.

The pinned session/snapshot commits bf11544e,fe461c6e,c092125a,ca9af6c7,5fd805d2
are disposed as Java8/Bukkit1.8 adaptations of their final behavior. Immutable Java8
state and copied collections replace records/Set.copyOf; existing canonical storage
ownership replaces modern shared-runtime routing. Obsolete intermediate retention of
a cleared cache after join is superseded by the final invalidation fix. Configuration,
schemas, serialized data, wire formats, release metadata and public existing signatures
are unchanged. See isolated cache-cleanup-upstream-audit.md for immutable IDs/callers.
Mixed merge patches and wider schema/cache population/lifecycle backports remain
unfinished; this is not whole-main parity or PR readiness.


## Registered SQL schema membership synchronization

Pinned AdvancedCore main commit `6390c1cab41bd4d7683c7df88dd36537c8c7861e` includes
`5eac8bd739ad3cf7ff119efc41f18b08297863df`, whose registration/snapshot synchronization
is adapted to the fork's native SQL implementation. `addKey` and the additive
`getRegisteredKeysSnapshot` use the same manager monitor. SQLite initial table queries,
SQLite custom-column migration, MySQL initial table creation and the existing manual
MySQL column-size command each capture detached membership before processing keys or
performing SQL. No SQL work is added inside the registration monitor.

Existing public `getKeys` still returns its mutable ArrayList and UserDataKey objects
retain their existing setters. The snapshot freezes list membership only; arbitrary
external raw-list edits and concurrent key-object mutations do not gain a thread-safety
contract. No modern backend hierarchy, Java9+ API or production dependency is imported.
Existing schemas, configuration, data, wire formats and release versions are unchanged.

Actual Java8 regression result: original two tests failed with a lock assertion and
ConcurrentModificationException; all four final tests pass. They cover registration
exclusion, actual SQLite migration with late registration and preserved row/idempotence,
query projection with registration during type resolution, and legacy collection/key
compatibility. ActualJava8 clean install619 unit +18 artifact =637 PASS; exact locally
installed producer consumer clean verify45 unit +1 artifact =46 PASS, zero failures,
errors or skips. Both artifacts have only base bytecode major<=52.
Producer SHA256 `a4711202d3aaf2efd4825a5be063c0d753c513ef586992c225f1a5f1e9f94dfb`;
consumer SHA256 `bb37b131e79f1c20ff8f3894c285b5402bc7023899d364d4a9e6eb3212af00f4`.

Six native checks pass on that consumer under realJava8/Spigot1.8.8: first-pass detached
membership, next-pass inclusion/idempotence, existing user preservation, database
integrity, physical single late column, and clean shutdown/linkage. The runtime fixture
runs DDL on the existing storage worker. MySQL runtime registration races and broader
schema reconciliation are not established by that SQLite acceptance. Cache-default
population and wider registration/type-reader ownership remain separate pending audits.
All167 original checkout states and both pinned reference states are verified unchanged.
This cohort does not establish whole-main parity or final PR readiness.


## Cache population and registered-type publication

The native cache's additional-key/default population now uses the registered membership
snapshot, including the reread path after an intervening canonical storage write.
Default callbacks execute after releasing the registration monitor. A key registered
by a default callback is available on the next population pass, without invalidating
the current iterator. `isInt` and `isBoolean` share the registration monitor so supported
addKey registration publishes the typed indexes coherently to these readers.

Two failing regressions reproduced the live-list ConcurrentModificationException and
unowned type lookup. All25 registration/cache snapshot/canonical ownership checks pass
on Java8. Existing writer-versus-refresh, failed physical write and uncertain commit
assertions remain; their fixture pause point now targets detached membership capture.
The raw public ArrayList/key setter contracts remain unchanged; external raw collection
mutation has no newly promised thread-safety. No SQL or extension callback is performed
inside the registration monitor. No configuration/schema/data/wire/version changes.

ActualJava8 clean install621 unit +18 artifact =639 PASS; exact locally installed
producer consumer clean verify45 unit +1 artifact =46 PASS, zero failures/errors/skips.
Both packaged artifacts retain base class major<=52. Producer SHA256
`a166d66b28c4e5ec9d8e9206ccefe7be6f177790f71f79b27e13f1d9706f7186`;
consumer SHA256 `dac99c13a1bad430aa204083eb83efb282c5b210847591eb802ed77fa82a1c4b`.
Eight native Java8/Spigot1.8.8 checks pass on that exact consumer: the six SQL membership,
existing-user/integrity/linkage checks plus registration during default population and
late-default availability on the next cache pass. This is not MySQL live migration
acceptance or whole-main parity. Full schema reconciliation and remaining ledger work
are still pending; the final independent review and PR readiness gate have not run.


## Native MySQL registered-column expansion

Missing registered MySQL columns now use their declared UserDataKey SQL type, length,
nullability and default, matching initial table creation. Unregistered dynamic columns
retain legacy TEXT storage. Synchronous JDBC DDL finishes and borrowed resources close
before column membership is published. SQL failures propagate as IllegalStateException
with the original cause. Missing connections and non-auto-commit borrowed transactions
are rejected; this path never commits an existing caller transaction or closes its pool.
Public method signatures, existing values/schemas, configuration and releases are kept.

The bundled SimpleAPI Query.executeUpdate catches SQLExceptions internally; simply
catching its declared exception cannot prove DDL completion. Packaged-artifact regressions
exercise the JDBC boundary instead. Their child loader uses the final Java8-compatible
pool replacement, avoiding the compile dependency's embedded Java11 pool classes. The
existing fixture is shared at test-package scope only; no production dependency changed.
Five packaged checks cover integer/default expansion, string length/attributes, rejected
DDL without membership publication, unknown dynamic TEXT, and transaction rejection.
The prototype helper-boundary tests were superseded after bytecode inspection exposed
swallowed JDBC failures; the corrected packaged RED had three failures before checked DDL.

ActualJava8 clean install621 unit +23 artifact =644 PASS; exact locally installed producer
consumer clean verify45 unit +1 artifact =46 PASS, zero failures/errors/skips. Base bytecode
remains major<=52. Producer SHA256
`d78ed032dce11b268881fbbd640216df88c3ed748180381d553a52420049541e`;
consumer SHA256 `a2aca893e85e198b08e19575b19e22cb265a1bd08a3bbafe75d9b453383c6ed6`.
RealJava8/MariaDB11.8.6 validates physical integer/default expansion, preserved existing
row, synchronous DDL, deliberately invalid-type SQL rejection, and unchanged membership
on failure. The unique fixture table is removed and owned daemon terminated. Eight native
Java8/Spigot1.8.8 registration/cache/SQLite/linkage checks pass on the exact rebuilt consumer.
A fixture preflight retry corrected TIME_WAIT handling after confirming no active listener;
all product assertions were preserved.

This partially adapts801fb0a50524996ef7a1d060e0cdc9f33245c190. Full borrowed-schema
reconciliation, fail-closed metadata, retained-type migration, identity/unique constraints,
peer migration idempotence and modern headless construction/cleanup remain unfinished.
No PostgreSQL support or whole-main parity is claimed. Original checkouts/references
remain unchanged; no push/PR/final independent readiness gate has occurred.


## Checked schema evidence and peer column additions

Native MySQL column addition first reads live, zero-row table metadata. Missing or failed
metadata is an error, not evidence that a column is absent. Existing case-folded columns
are remembered once without duplicate DDL. Cached checkColumn supports the existing
public List contract without assuming an ArrayList; uncached checks use the same checked
addition path rather than the forgiving SHOW helper.

A competing ADD succeeds only when error1060/SQLState42S21 is followed by successful live
reinspection proving the column exists. Unrelated errors, absent peer columns, failed
reinspection and cleanup errors remain failures with their original/suppressed evidence.
Membership publishes only after resources close. A retry after physical DDL plus failed
connection cleanup can discover the completed column without repeating the DDL.
No generic retries, sleeps, user enumeration or alternate pool ownership are introduced.

The initial five new regressions failed (three assertions/two errors); final16 packaged
schema checks pass. The first implementation double-read metadata count; the peer-race
regression detected that and the count is now captured once. Additional counterchecks
cover wrong SQL state, absent metadata, statement/inspection cleanup failures, failed
reinspection and uncertain completion retry. ActualJava8 clean install621 unit +34
artifact =655 PASS; exact locally installed producer consumer clean verify45 unit +1
artifact =46 PASS, zero failures/errors/skips; base bytecode major<=52.
Producer SHA256 `6205189a725821e447a4fc8e9a1a2a2ac5de6bb5f41c152f47780ab0ec4b2655`;
consumer SHA256 `be392478be876523b560f27565ae6da15c93099ed72000cd88c79c0632bdf84f`.

RealJava8/MariaDB11.8.6 acceptance forces two independent wrappers/connections to capture
absent-column metadata before either can add it. Both physically attempt ADD; both settle
successfully, with one physical column and one remembered entry each. Repeated registration
does not repeat DDL; the declared integer/default, existing row and invalid-DDL rejection
remain correct. Owned test table/daemon/workers are removed or terminated. Eight real
Java8/Spigot1.8.8 registration/cache/SQLite/linkage checks pass on the exact final consumer.

This establishes column membership evidence/idempotence, not full retained-schema
compatibility. Physical retained column types, defaults, UUID uniqueness and canonical
cached values still require reconciliation. In particular, physical case-folded labels
currently reach exact-key cache/default handling; preserving nonzero stored data under
such names needs a separate reproduction and fix. The full801fb0a/5ef372/5198b961
headless/borrowed/retained migration patches remain partial or pending. Existing APIs,
configuration/data/wire/release contracts and original/reference checkouts are preserved.
No whole-main parity, final independent review, push or PR readiness is claimed.

## Canonical retained SQL column reads

Native MySQL and SQLite individual/legacy/bulk readers now capture registered SQL
names once per read and project physical case aliases onto the registered name.
Complete-source conversion uses the same projection before validating duplicate labels
and UUID identity. A retained `points=17` for registered `Points` consequently reaches
UserData/cache under `Points`, rather than an unknown key beside a default `Points=0`.
Unknown dynamic columns keep their physical names. FLAT key handling and the public
integer/boolean type-index APIs are unchanged. Identical repeated legacy registrations
remain supported; distinct case aliases for one registered physical identity fail visibly.

The initial two packaged reproductions failed on physical names instead of canonical
names. Final actual Java8 producer clean install passes625 unit +37 artifact =662 tests;
exact locally installed producer consumer clean verify passes45 unit +1 artifact =46.
Zero failures/errors/skips. Producer SHA256
`71994ccb9e754203107428a2db23daf5a083a769aa38c417da0e8b877f28df34`;
consumer SHA256 `1b54f644851d4460bb73e39f0ed4e5f322695f9b024cbd2201c6c70e73e56518`.
All1834/2451 base classes respectively have major<=52.

RealJava8/MariaDB11.8.6 verifies physical lowercase `points=17` through strict/legacy
single-user and complete/bulk reads plus actual UserData conversion, preserving unknown
stored data. The existing forced concurrent schema-add acceptance also remains green.
Nine realJava8/Spigot1.8.8 acceptance checks pass against the exact final consumer,
including preserved nonzero canonical cache data, detached registration/default passes,
SQLite integrity and clean shutdown/linkage. Owned tables/servers/workers are cleaned up.

This adapts the pinned main JdbcSqlUserStorage canonical projection to native legacy
stores. It does not complete retained physical type/default migration, UUID uniqueness,
full borrowed-schema reconciliation or the broader storage ledger. No new dependency,
release change, configuration/wire change, final independent review, push or PR readiness
is claimed. Remaining upstream entries keep their partial/pending dispositions.

## Checked retained numeric-to-string SQL migration

Registered string columns with text/character declarations now reconcile retained
numeric/boolean physical storage before the first checked write. Cached membership
alone cannot bypass that inspection; a successfully reconciled declaration is remembered
only after connection cleanup, and a changed declaration triggers another check.
Unknown dynamic columns, deliberately numeric declarations and legacy boolean-string
keys retain their existing storage contract. Extension callbacks run outside registration
and schema monitors. Existing public signatures, configuration/data/wire/release values
and pool ownership are preserved.

Migration preserves observed nullability, default and comment rather than replacing them
with new-key defaults. Generated/automatic attributes, unsafe defaults/comments and missing
metadata fail visibly. A peer completing between inspections or after DDL failure is accepted
only with matching target type and retained attributes. Statement/connection cleanup failures
cannot publish successful schema membership or the reconciled declaration.

A live permissive-mode counterexample found VARCHAR(1) truncating an existing17. Migration
now temporarily adds STRICT_ALL_TABLES on the borrowed connection and restores its original
session mode before return. Unavailable mode evidence prevents DDL. Restoration failure is
reported and invokes the installed pool's public eviction API for that connection, without
closing the pool. The narrow reflective bridge is required because the compile dependency's
embedded datasource is Java11 while the packaged replacement is Java8; it accesses only
getDataSource/evictConnection and never imports or loads the incompatible class in Java8.
An initial raw-connection-close candidate allowed an immediate closed Hikari handle to be
borrowed again; the live countercheck caught it and supported eviction replaced it.

ActualJava8 final producer clean install625 unit +50 artifact =675 PASS; exact locally
installed producer consumer clean verify45 unit +1 artifact =46 PASS, zero failures/errors/skips.
Producer SHA256 `da6deba2a3623ef12de2b4c0ddfa226cbbf7251dc2390f93d011b88d9239f1a7`;
consumer SHA256 `a16036fa2e1c5c640aeb76888a7445700e717b347b73d6b65c8fbc84c8f16ef7`.
All1838/2455 base classes respectively have major<=52. RealJava8/MariaDB validates successful
TEXT conversion with17/default7/not-null/comment preserved, rejected narrowed conversion
(SQL1406/22001) preserving originalINT/17/attributes and restored session mode, and injected
restoration failure evicting the affected physical connection while the live Hikari pool
continues with a fresh original-mode connection. The fault wrapper's fixture adapter forwards
eviction to the underlying real Hikari handle. Nine exact-consumer Java8/Spigot1.8.8 checks pass.

One earlier full build failed an existing inventory queued-lock assertion; its unchanged
focused11-test rerun and subsequent full builds passed. Its intermittent cause remains
unproven and the failure evidence is retained; this SQL change does not claim to fix it.

This is a partial native-store adaptation of5ef372ddea971d00aa92ec9f6c61803b2ce9bd65,
5198b9615dae66ff57021a66c7d933f3cf57f4d9 and related final pinned main reconciliation.
Declared numeric precision/unsigned changes, nonstandard declarations, all-registry schema
reconciliation, UUID constraints, headless lifecycle/paging and remaining upstream changes
still require work. PostgreSQL and a separate real MySQL server are not runtime-verified.
No whole-main parity, independent final review, push or PR readiness is claimed.


## Retained numeric declarations and exact decimal migration checkpoint

Pinned comparison remains AdvancedCore main `6390c1cab41bd4d7683c7df88dd36537c8c7861e`.
This continues the partial native-store adaptation of `5ef372ddea971d00aa92ec9f6c61803b2ce9bd65`
and `5198b9615dae66ff57021a66c7d933f3cf57f4d9`; it does not complete their whole scope.
Registered string APIs can intentionally use numeric SQL types. Physical numeric types now
compare aliases, integer display widths, unsigned/zerofill, BOOLEAN/TINYINT(1), decimal
precision/scale and BIT length rather than always retaining a narrower numeric column.
INT-to-BIGINT reconciles without converting to TEXT and retains values, nullability, default
and comment. Equivalent numeric defaults such as 7.000 and 7.0000 count as the same value.
Extension declarations are resolved once outside both schema monitors for check and direct ADD.

Strict SQL mode alone did not reject DECIMAL scale rounding in the live fixture. Decimal
scale reduction therefore validates rows and the retained default under an owned WRITE lock
before its one ALTER. The query returns at most one loss witness and uses JDBC query timeouts;
metadata, session-mode, lock, default and DDL statements also set five-second query timeouts.
Source attributes are inspected again after acquiring ownership. A missing result or rounding
risk rejects migration before DDL. The scope owns a fresh auto-commit connection, switches
it to the InnoDB lock pattern, releases locks/restores auto-commit, and always evicts its
modified physical connection through the existing pool API. Cleanup failures stay visible,
including unchecked rollback faults, and cannot publish acknowledged schema membership.
A fenced DDL failure cannot be reinterpreted as a successful peer migration.

The current narrow driver bridge supports Connector/J5's public connection contract. Its
setAutoReconnect(false) does not clear the driver's cached initialization flag: a live kill/
reconnect counterexample changed the physical session despite that setter. Connections
created with automatic reconnect enabled are rejected before taking the lock. Only fixed
boolean option names are read; connection properties and credentials are never logged.
The scope requires LOCK TABLES privileges and InnoDB table-lock evidence; Galera/wsrep nodes
are rejected. Additional driver contracts and floating/BIT/cross-family exact conversion
remain unfinished, with visible guards rather than an unsafe conversion. These are current
implementation limits, not a claim that the remaining upstream requirements are complete.

Actual Java8 commands used the workspace-local Maven repository and JDK8u504 with
`mvn -B -f AdvancedCore/pom.xml -Dmaven.resolver.transport=wagon
-Dmaven.repo.local=/workspace/votingplugin-1.8-port-workspace/.m2/repository
-Djava.io.tmpdir=/workspace/votingplugin-1.8-port-workspace/runtime/tmp clean install`.
Final producer: 630 unit +63 artifact =693 PASS, zero failures/errors/skips.
Producer SHA256 `e98bb86cdb45a45b482f867e260c63b0755b0709956e7d58152fcb4c74ddc2b2`;
all1841 base classes have major<=52. Connector/J5.1.14 is a test-only dependency and is absent
from the production jar. Exact installed producer consumer clean verify:45 unit +1 artifact
=46 PASS; consumer SHA256 `3292145dda2552d62117fa42ee2316f56c7f58e4e664d315ee0b653228566de7`,
all2458 base classes major<=52. The consumer used the same Maven flags with
`-f VotingPlugin/pom.xml clean verify`.

RealJava8/MariaDB11.8.6 native packaged-code fixtures passed exact DECIMAL12,3-to12,2 with
1.230/default7 preserved, rejected source1.234 and default7.001 rounding without changing
source schema/values/attributes, blocked a competing writer through validation/DDL until
release, and failed visibly after connection loss before ALTER while retaining source data.
These fixtures use the existing pool eviction boundary with a fixture adapter that closes
the owned connection; they do not independently prove every Hikari/driver combination.
Nine exact-consumer Java8/Spigot1.8.8 SQLite/schema/cache/startup/shutdown acceptance checks
pass; this is not a claim of MySQL user-cache runtime or full vote/proxy acceptance.
Public explicit alterColumnType compatibility, all-registry schema reconciliation, UUID
constraints, headless lifecycle/paging, additional numeric families/driver/pool acceptance,
remaining ledger entries and the final independent review are still required.


## Explicit native SQL type API compatibility checkpoint

The starting fork `d4a8f667d91e7121a5e0929a0e16cd615556f00d` checks presence before
asynchronously applying alterColumnType's requested declaration. A packaged-code regression
proved the new automatic registered-type reconciliation first performed an unwanted TEXT
conversion before queuing VARCHAR(30). The explicit API now performs presence-only admission,
creating a missing column with its registered definition, then runs the requested ALTER on
the existing SQL executor. Public signatures and asynchronous void/admission semantics remain.
Explicit declarations remain caller-owned; this API does not implicitly preserve omitted
SQL nullability/default/comment clauses or acquire the automatic decimal migration fence.

The existing per-column resolution map distinguishes pending explicit ownership from settled
reconciliation. Normal checks do not apply a competing registered conversion while the explicit
request is pending. Checked SQL plus statement/connection cleanup must succeed before settlement;
SQL/cleanup/callback failures release only that request's ownership and stay visibly logged.
Rejected submission restores prior ownership, and an older failure cannot cancel a newer
request. An existing borrowed transaction is rejected without implicit commit. Completed
explicit ownership is process-local and tied to the captured registered declaration; changed
registration invalidates that match. Cross-restart external-addon override expectations and
SQL-executor shutdown/cancellation still need the broader API/lifecycle audit. No completion
future, ordering guarantee or durable override format is added.

ActualJDK8u504 producer clean install:630 unit +72 artifact =702 PASS; exact installed producer
consumer clean verify:45 unit +1 artifact =46 PASS, zero failures/errors/skips. Commands use
the same explicit workspace-local Maven repository/resolver/tmp flags recorded above, with
AdvancedCore/pom.xml clean install followed by VotingPlugin/pom.xml clean verify.
Producer SHA256 `2a9c7ecc90b0981b5b1a284c69c8a8781f1ee1c8a467e4c439dc557d115fbbbc`;
consumer SHA256 `08c4ebaa0d9052886fdb1792d64b3f4be20c916adb50a944fad117bce3be8284`.
All1842/2459 base classes respectively have major<=52. Nine new explicit API artifact tests
cover queued/completed ownership, rejected execution, older/newer requests, SQL/cleanup/
callback failures, missing-column creation and transaction protection.
A real executor/native packaged API/MariaDB fixture proves INT remains unchanged while queued,
VARCHAR(30) applies on the worker, a later check retains it, and stored17 is preserved.
Nine exact-consumer Java8/Spigot1.8.8 SQLite/schema/cache/startup/shutdown checks pass.
The remaining upstream ledger, numeric/driver/pool and full runtime/API/lifecycle scope,
and fresh independent final review are still incomplete. No push or PR readiness is claimed.


### Root checkpoint notification ledger audit

Compared the complete production patches of pinned-main commits
`e75605add366aa23b9bed6452aee6681c410506c` (skip empty legacy action checkpoints)
and `2533230d62fa84550614debd32b0c40eabdcbbf5` (durable replay checkpoint callbacks)
against the isolated fork. Both behaviors already exist; this audit adds regression
coverage rather than another production implementation.

The empty-action guard is unchanged: an empty persisted snapshot becomes dirty only
when the current action snapshot is nonempty. The public additive
`RewardInject.onReplayCheckpointPersisted` hook retains its completed no-op default.
`Reward` awaits a checkpoint before notification, awaits the hook before the next
injection, and invokes the hook again for a persisted completed prefix on recovery.
The fork uses its Java 8 exceptional-stage helper and admitted server-thread
owner instead of Java 9+ `CompletableFuture.failedFuture`. Occurrence and injection
identities remain stable. Hook failure does not erase the persisted completed effect.
The hook must be idempotent: a later recovery may notify it again.

Three regression tests exercise the production collection/pipeline entry points:
empty collections preserve operator placeholders without creating replay metadata;
notifications start after the durable write and block the next effect until settlement;
and failed notification recovery from the saved checkpoint retries the hook without
repeating the first effect. The empty-collection regression also fails when the
upstream guard is temporarily removed, with source restored afterward.
Actual Java 8 focused command uses the recorded workspace-local Maven flags and
`-Dtest=LegacyOrderedRewardPipelineTest test`: 45 tests pass, zero failures/errors/skips.
Evidence is in `evidence/root-checkpoint-notification-focused.log` and
`evidence/empty-legacy-checkpoint-mutation.log` under the isolated workspace.
The complete actual-Java-8 `mvn ... test` unit suite also passes: 633 tests, zero
failures/errors/skips (`evidence/root-checkpoint-notification-full-unit.log`).
Production sources and packaged dependencies are unchanged from the previous
702-test producer / 46-test consumer artifact cohort; no fresh package or server
acceptance is claimed for this test/documentation-only audit.
This is deterministic pipeline evidence, not new end-to-end database/crash acceptance.
Remaining replay/storage-generation commits and the full upstream ledger still need
individual source review. No final PR readiness is claimed.


### Nested replay and checkpoint admission ledger audit

Complete production patches reviewed against the pinned main:

- `c439becf55905891d7939973e37c70afc964ce9c`: frozen nested reward lists,
  sequential children, persisted child cursor, and skipping completed children
  before registry resolution. Present in `Reward.replayNestedRewardSequence` and
  the retained `RewardHandler.giveRewardAsync` configuration/list dispatcher;
  the fork does not require main's reorganized `RewardExecutor` source layout.
- `3a7964616986d2c4609a009771487f874dcc24fa`: AdvancedRewards, AdvancedWorld
  and Lucky delegate to that child-checkpoint sequence. Present in the retained
  `RewardHandler` builtins. Java 8 uses `Arrays.asList` / empty collections and
  exceptional-stage helpers; Bukkit 1.8 retains its supported configuration API
  and the fork's established branch prefixes. AdvancedWorld still sets its
  world constraint on the admitted server owner before child dispatch.
- `1167618dd3519cb224f0bde809c7267a83173181`: mixed commands validate player
  availability before console effects, preserve console-before-player order,
  and prevent a queued checkpoint from publishing after admission timeout.
  Present in the builtin/user paths and `ReplayState.persistCheckpointAsync`.
  Java 8 uses the existing owner dispatcher's deadline/claim mechanism instead
  of modern `delayedExecutor`/timeout helpers. Once a write is actually admitted,
  its physical completion remains authoritative; the queue timeout does not
  falsely report that an in-flight write was cancelled.

The existing 33 `LegacyNestedRewardSequenceTest` tests exercise duplicate child
occurrences, frozen-list recovery after a completed child is removed, malformed
cursors, actual AdvancedRewards/AdvancedWorld/Lucky builtins, and mixed command
availability/order. A new production checkpoint test verifies both a fired
admission deadline and a delayed deadline callback with an expired monotonic
clock: neither path can later invoke the queued checkpoint consumer.
Actual Java 8 focused suite: 79 PASS (46 pipeline + 33 nested), zero failures,
errors or skips. Full Java 8 unit suite: 634 PASS, zero failures/errors/skips.
Commands use the documented workspace-local Maven flags with
`-Dtest=LegacyOrderedRewardPipelineTest,LegacyNestedRewardSequenceTest test`
and then `test`; logs are `evidence/replay-nested-timeout-focused.log` and
`evidence/replay-nested-timeout-full-unit.log` under the isolated workspace.
No production or packaging input changed in this audit. Prior artifact/runtime
results remain separate evidence; these tests do not establish fresh process-crash,
MySQL replay or complete upstream compatibility acceptance.

The bounded modern build-target omissions are recorded separately in
[upstream-build-dispositions.md](upstream-build-dispositions.md). Mixed dependency
or compiler-processing patches are not classified as modern-only on that basis.
The remaining ledger and final independent review remain incomplete.


### Overflow retry rejection diagnostic and exact-artifact validation

A new deterministic production-path regression proves an operator-visible gap:
stop the private persistence executor after native delivery admission, reject the
owner fallback, and the retry is also rejected while the partial insertion stays
pending with no diagnostic. The unchanged baseline fails the logger assertion
(`evidence/overflow-retry-rejection-red.log`). The fix reports that retry rejection
at WARNING with its cause. It preserves the reservation and pending completion;
it does not acknowledge volatile overflow or label a partial insertion safely
replayable. An explicit successful save settles the same delivery without another
inventory insertion. The fixture checks both that ownership and actual saved data.

The rejected retry catch is also present in pinned main, so this is a narrow
additional diagnostic rather than a missing fork API. The normal 30-second retry
cadence is inherited unchanged; it is not used as proof of persistence or completion.
World-drop and persistence fallback behavior remain unchanged by this fix. Broader
partial-insertion/crash, drop failure and shutdown recovery need their own evidence;
this warning does not establish exactly-once delivery after a process crash.

Actual Java 8 `-Dtest=LegacyReplayItemDeliveryTest,LegacyFullInventoryLifecycleTest test`:
31 PASS. Actual Java 8 producer `AdvancedCore/pom.xml clean install`: 635 unit +
72 artifact = 707 PASS. Exact installed producer verified by hash before consumer
`VotingPlugin/pom.xml clean verify`: 45 unit + 1 artifact = 46 PASS. All runs use
the same explicit workspace-local Maven/resolver/tmp flags documented above;
zero failures/errors/skips. Producer SHA256:
`6fd9615af6da56a657eb4176e1ad2ab7bbd6b0f86ecf0012c1ac1ce0cee60c30`.
Consumer SHA256:
`721f8369723c90ab99754c863cfd3b7e6296b5e4b0323dfde8a40a3ff239a898`.
All 1842 producer and 2459 consumer base classes have major version <= 52;
Java 8 ignores multi-release entries. Build manifests and logs are under workspace
`evidence/overflow-retry-rejection-*` (build-results.json, clean-install.log,
consumer-clean-verify.log and focused.log).

Actual Java 8 / Spigot 1.8.8 acceptance with that exact consumer: six PASS checks
for native asynchronous overflow park/persist, disable preserving the legacy
FullInventory schema, restart recovery, a connected protocol client receiving
exactly three diamonds, final snapshot removal, and clean lifecycle/SQLite integrity.
The runtime helper awaits `giveItemAsync` settlement before inspecting the stored
snapshot. Command: `python3 runtime/replay-overflow-acceptance/run.py` from the
isolated workspace; evidence: `evidence/overflow-retry-rejection-spigot-runtime.log`
and `evidence/replay-overflow-runtime-3491bc8be8.json`.
This does not prove outer reward checkpoint crash recovery, rejected-retry logging
on a live server, arbitrary queued retirement, concurrent persistence failure,
all-full world drops or global queue bounds. The failure/recovery diagnostic is
verified by the deterministic real-entry-point regression, not that live smoke test.
The remaining upstream ledger and fresh independent final review are incomplete.
No push, PR readiness, release or deployment is claimed.


### Shared SQL facade foundation

Reviewed the pinned main `SqlUserStorage`, `SqlUserDataAccess` and
`BukkitSqlUserStorage`, plus the initial extraction commit
`b789d7b1b95252f05083f717a039fc9ff0841975`, against native checked storage callers.
Ported the core storage contract and row-access facade from pinned main
`6390c1cab41bd4d7683c7df88dd36537c8c7861e`. Java 8 replaces the default
transaction overload's `Map.of()` with an empty `Collections` map. The interface's
unsupported transaction default remains an explicit exception without invoking the
callback; it does not pretend to provide a JDBC transaction. The documentation
states that cached transaction integration is not yet supplied by this fork.

The facade preserves lazy construction, a caller-supplied live row reader,
exact key matching, legacy invalid-integer/default and null-string semantics,
first matching typed value for scalar reads, last-wins duplicate conversion,
mutable detached maps retaining the original value objects, and backing provider
exceptions. Native `UserData.convert` delegates to its equivalent conversion helper.
The native strict snapshot path still rejects omitted snapshots before conversion;
checked writes, lifecycle/per-user ownership, caches, public row overrides,
configuration, schemas and wire formats are not replaced by this foundation.
No production dependency, executor, connection, schema or listener is created.

Six new focused tests cover lazy live reads, duplicate/default behavior, boolean
and null sentinel strings, detached conversion, provider failure propagation,
write delegation and explicitly unsupported transactions. Actual Java 8 focused
command with existing workspace-local Maven flags:
`-Dtest=LegacySqlUserDataAccessTest,LegacyCacheSnapshotTest,LegacyQueueMutationBridgeTest test`
passes 30 tests, zero failures/errors/skips. Producer `AdvancedCore/pom.xml clean install`:
642 unit +72 artifact =714 PASS; exact installed producer verified before consumer
`VotingPlugin/pom.xml clean verify`:45 unit +1 artifact =46 PASS.
Producer SHA256 `f882e03107223bd91e1e4a35edc1e9ca80a8e5479b3e7565b4e64d130f08071f`;
consumer SHA256 `0a5e6a57e8670e0c8dd74b50561fb58b74403cd9690976c5027454eebb84418c`.
All1846 producer/2463 consumer base classes have major<=52; both packaged artifacts
contain the core row-access class. Full logs/manifest: workspace
`evidence/sql-facade-foundation-{focused,clean-install,consumer-clean-verify}.log`
and `evidence/sql-facade-foundation-build-results.json`.

Fresh exact-consumer Java8/Spigot1.8.8 cache/schema acceptance: nine PASS checks
for stored nonzero canonical value preservation, detached schema/cache registration,
idempotent next pass, retained existing row, physical late column, SQLite integrity,
Java8 linkage and clean shutdown. Command:
`python3 runtime/canonical-read-acceptance/run.py work/VotingPlugin-1.8/VotingPlugin/target/VotingPlugin.jar`
from the isolated workspace. Evidence: `evidence/sql-facade-foundation-spigot-runtime.log`
and `evidence/schema-registration-acceptance-5654391214.json`.
This proves packaged conversion/native SQLite use, not a shared SQL runtime,
MySQL facade integration, atomic user transactions or outer reward crash recovery.

The extraction remains a partial port: the pinned Bukkit adapter depends on shared
route/maintenance APIs, and its methods cannot replace native checked writes blindly.
Backend adapters, schema/backend lifecycle, shared cache owner, shared runtime,
transaction implementation and remaining facade delegation still require integration.
See `docs/upstream-shared-user-dispositions.md`. No main-match, final independent
review or PR readiness is claimed. No push, release or deployment was performed.

### Server-affine offline recovery and explicit force

Reviewed complete upstream commit
`009ca9521044435c013de6c81e5f2c0b7822c853` against the pinned comparison
`6390c1cab41bd4d7683c7df88dd36537c8c7861e` and its current callers.
Normal recovery no longer sets `forceOffline` or disables `GiveOffline`.
Previously this fork's asynchronous recovery accidentally applied the force
settings to every poll, bypassing retryable Server and online requirements.
The public `forceRunOfflineRewards()` retains its explicit override through a
separate private admission flag. Normal/forced paths still use the same captured
dispatcher, durable occurrence/checkpoint mutations, completion removal and
claim cleanup. Public signatures, queue formats and configuration keys are
unchanged. A deferral releases the active claim while retaining the same stored
occurrence; it neither creates another queued copy nor runs effects.

A new regression first failed against the previous implementation because normal
recovery set `forceOffline=true`. Three added deterministic tests check normal
queue options, explicit-force options and actual asynchronous reward decisions
for retryable requirement failure (no effect/checkpoint vs awaited force effect).
Actual Java 8 focused command, using the documented isolated flags:
`mvn -B -f AdvancedCore/pom.xml -Dtest=LegacyOfflineQueueReplayTest,LegacyTimedQueueReplayTest,LegacyOrderedRewardPipelineTest test`:
87 PASS, zero failures/errors/skips.
Full producer `clean install`: 714 unit + 78 artifact tests = 792 PASS.
Exact locally installed producer SHA-256:
`3688cf464a885c7b63bc93aa7272b91e8a06bf99e8e4bda7ccc08b88e1ba47c8`.
Dependent VotingPlugin `clean verify`: 45 unit + one artifact test = 46 PASS.
Consumer SHA-256:
`8868c74eddf6de95e6a9ff2c461008a31eee58ccfc7030a419e748aeed112a68`.
Both artifacts retain maximum base-class major version 52.

Manual reproducible acceptance:
`python3 AdvancedCore/src/test/runtime/run-offline-server-affinity.py <workspace>`.
Against that exact consumer on actual Java 8/Spigot 1.8.8 with a connected client,
all four checks PASS: the real builtin Server requirement retains a wrong-backend
occurrence unchanged over two polls without effects, the matching target runs
one effect and durably removes it, explicit force overrides the mismatched target
and durably removes its distinct occurrence, and Java 8 linkage/shutdown succeed.
The fixture queries checked SQLite state and performs no vote processing. It is
a manual test helper outside the packaged source roots, not a production plugin.
This is not process-crash, live MySQL or timed-recovery acceptance.

Evidence under the isolated workspace: `server-affine-replay-red.log`,
`server-affine-replay-focused-final.log`, `server-affine-replay-clean-install.log`,
`server-affine-consumer-clean-verify.log`, `server-affine-build-results.json`,
`server-affine-runtime.log`, and `offline-affinity-runtime-f211c8cb28.json`.
The complete upstream ledger and full shared-runtime/root-recovery work remain
incomplete. No final PR readiness is claimed.

### Deferred public reward options and queued recovery

Complete upstream production/test patch reviewed:
`400bf780208b7b3bef913ff4107202905372368c`, against pinned main
`6390c1cab41bd4d7683c7df88dd36537c8c7861e`.
The fork already had replay-state copying and option-aware serialization, but
its public queue API and deferral callers never used them. Added the compatible
`addOfflineRewards(Reward, HashMap<String,String>, RewardOptions)` overload and
wired paused, vanished and retryable-offline deferral in both public synchronous
and asynchronous reward paths. The original two-argument API delegates with null
options, retaining fresh occurrence behavior. The internal cross-package
`snapshotReplayOptionsForQueue` bridge freezes options and merges the completed
state/registry fingerprints/nested selection metadata before serialization;
caller-owned options and placeholder maps remain unchanged. Existing queue
markers and payload formats are reused. No configuration or schema migration is
needed. Java 8 uses the existing captured dispatcher and checked native storage
mutation instead of main's modern continuation helpers.

Already admitted durable queue replays still release their claim and retain the
original occurrence on deferral, rather than inserting another queued copy.
Existing claim cleanup rechecks occurrences, serial tail, retained publication
and storage wakeup under the shared claim lock before removing the current
per-user claim object. The public null-options reward-user guard is retained.
The fork does not replace this proven native queue implementation with main's
different storage manager layout.

Two new deterministic regressions cover option serialization/recovery and both
public deferral entry points. They preserve the exact occurrence, completed
prefix, registry identity, frozen child metadata and administrator placeholders.
Existing negative deferral tests now prohibit both queue overloads.
Reverting only the four deferral calls to the old overload makes the new
entry-point regression fail with the expected missing option-aware admission;
candidate source bytes were restored before positive validation.
Actual Java 8 focused reward/offline/timed suite: 89 PASS, no failures/errors/skips.
Full producer `clean install`: 716 unit + 78 artifact tests = 794 PASS.
Producer and dedicated installed dependency match SHA-256
`f16d6169caf200c7270c28d74e41723ce110547f768ba33bcc8ba3b8498a7420`.
Exact dependent consumer `clean verify`: 45 unit + one artifact test = 46 PASS;
SHA-256 `cc5f85f8406b1ac45b96690e8f81f0f88a5feb4d9330d379b656e6d34178fb60`.
Both jars' base classes remain major version 52 or lower.

The manual server-affinity fixture now also snapshots the actual completed
runtime replay state, admits it through the new public queue overload, observes
its occurrence/progress/metadata in checked SQLite, and verifies normal recovery
skips the completed effect and removes the occurrence. All five fixture checks
PASS on actual Java 8/Spigot 1.8.8 against that exact consumer artifact.
The existing offline root/native-effects/physical-checkpoint fixture also passes
all 10 checks, including normal disable/restart and recovery of exactly three
parked diamonds. The timed physical-checkpoint/recovery-publication fixture passes
12 checks, including failed completion removal, an actual timer retry that does
not repeat effects, and another poll fenced while the original claim remains
active. This is not a claim of SIGKILL recovery or exactly-once arbitrary
external effects, live MySQL recovery, global capacity or full ledger completion.

Workspace evidence: `deferred-options-focused-entrypoints-final.log`,
`deferred-options-entrypoints-mutation.log`, `deferred-options-clean-install.log`,
`deferred-options-consumer-clean-verify.log`, `deferred-options-build-results.json`,
`deferred-options-runtime.log`, `offline-affinity-runtime-a883f548fa.json`,
`deferred-options-offline-integration.log`, and
`offline-replay-integration-runtime-3a64b74edf.json`,
`deferred-options-timed-publication.log`, and
`queue-publication-recovery-runtime-f3df9b2974.json`.
All 167 recorded original checkouts and both pinned references remain unchanged
(`deferred-options-isolation-after.json`). Full shared-runtime/root-recovery
coverage, upstream classifications and final independent review remain pending.

### Timed deferral and inherited durable child audit

The focused disposition record is
[upstream-replay-dispositions.md](upstream-replay-dispositions.md).
It records the complete audit of durable missing-child commit
`9d35eaf326442b1368c63f20f1b38779a0b6c8ef` and the partial timed/lifecycle port of
`091a339479f48648aa185e134e88b843ad5827d0`. Timed provenance is now preserved
through public options and nested dispatch, without a queue format change.
Final actual Java 8 producer: 799 tests PASS; exact consumer: 46 tests PASS.
Actual Spigot 1.8.8 timed checkpoint/timer retry/restart fixture: 12 checks PASS.
The modern shared runtime cleanup/extraction obligations remain pending; this
checkpoint does not establish full backport or PR readiness.

The optional shared reward API and its Java 8 boundary are documented in
[java8-shared-reward-orchestration.md](java8-shared-reward-orchestration.md).
Native shared-runtime integration remains incomplete.

Shared-user runtime foundation and remaining native integration:
[java8-shared-user-runtime.md](java8-shared-user-runtime.md).
