# Native shared-cache dependency milestone

Pinned main comparison: `6390c1cab41bd4d7683c7df88dd36537c8c7861e`.

The fork's existing `UserDataCache` retains its canonical native ownership slot,
checked writes, retry queue, snapshot fencing and existing public APIs. Additive
shared/exclusive admission gates let its batch writer use the supplied SQL backend
and return notifications after releasing storage ownership. Binding refuses an
already-active legacy batch or a different runtime gate. Retirement refuses
unflushed or active checkpoint work; retired caches cannot be repopulated.

`flushChangesAndRun` flushes the admitted prefix before its checkpoint. Mutations
accepted through the deferred shared-flush lane during that checkpoint remain
visible but are staged separately. They are republished into the normal queue
when the checkpoint exits, including failure. Their notifications precede a
requested immediate follow-up flush. Failed batches preserve their original
payload for retry. Snapshot population merges newer and pending native mutations.
Existing unbound cache processing continues to use the checked legacy provider.

The manager captures producing notification generations and lifecycle admission,
queues callbacks on its existing worker, drops retired-generation callbacks and
retains asynchronous failure evidence. An already-started callback keeps its
producing runtime alive. The notification-only lifecycle binding is an additive
fork integration port: callers must publish it inside the owning runtime's
replacement boundary. Changing backend identity fences queued predecessor
notifications. It is not a substitute for complete per-user native SQL routing.

Tests exercise the actual fork cache, shared coordinator and SQLite transactions,
including physical persistence/reopen, pre-transaction flush, rollback, retry,
stale snapshots, staged checkpoint mutation, notification/flush order and failed
checkpoint recovery. Manager tests use its actual worker and verify queued
retirement, started-callback lifetime, backend rebinding and retained failure.
The tests in this milestone use a controlled adapter. Subsequent production
Bukkit adapter evidence and remaining integration gaps are recorded separately
in `java8-bukkit-cache-owner.md`.

During diff inspection, a refactored public flush was found to release native
admission before its synchronous callback. The new shutdown-race regression
failed before correction and passes with the original admission boundary restored.
No existing assertion was removed or weakened. This is a regression introduced
and corrected within this local implementation milestone, not an upstream claim.

The later Bukkit adapter milestone supplies the production `BukkitUserCacheOwner`
and selected worker-side UserData/manager routing. Full provider bridges,
startup/reload/shutdown binding, primary-thread population and real-server
acceptance of the new routes remain required before automatic shared ownership.
The four upstream checkpoint dispositions remain partial. No feature flag,
configuration default, physical schema, proxy payload, release version or
production dependency was changed here.

Validation (actual Temurin 8u504, workspace-local Maven repository/temp directory):

- Focused cache/notification and legacy countertests: 41 PASS.
- `mvn -B -f AdvancedCore/pom.xml clean install`: 801 unit + 78 artifact =
  879 PASS, zero failures/errors/skips.
- `mvn -B -f VotingPlugin/pom.xml clean verify`: 45 unit + one artifact =
  46 PASS, zero failures/errors/skips, using the byte-identical installed producer.
- Producer: 1889 base classes, maximum major 52, SHA-256
  `8b88ceac96c6332b5f7be9be7d30555173961c59ca5e59a5e4c128edd7cdddff`.
- Consumer: 2506 base classes, maximum major 52, SHA-256
  `f81d3865c240ebda5e46ca17d9e513a98a7ab9edf0693a03459c85aad7611db7`.

Commands additionally use `-Dmaven.resolver.transport=wagon`,
`-Dmaven.repo.local=/workspace/votingplugin-1.8-port-workspace/.m2/repository`
and `-Djava.io.tmpdir=/workspace/votingplugin-1.8-port-workspace/runtime/tmp`,
with JAVA_HOME/PATH selecting the workspace JDK. The initial focused test compile
used a nonexistent transaction-scope write overload; it was corrected to the
actual `writeValues` API before successful validation. The intentional admission
regression failure is retained in `shared-native-cache-admission-red.log`.
Final logs/counts/hashes are the workspace `evidence/shared-native-cache-*` files.
Staged conversion and subsequent storage failure tests prove both accepted
payload and notification survive until their explicit flush retry succeeds.

The exact packaged consumer passes all 12 existing real Java 8/Spigot 1.8.8
root/timed checkpoint, publication retry, claim fencing and overflow
park/disable/restart checks (`shared-native-cache-runtime.log`). This protects
existing native behavior. Automatic startup of the new shared manager/adapter
route remains unimplemented. All 167 original checkouts and both
pinned references remain unchanged. Full-scope independent review remains pending.
