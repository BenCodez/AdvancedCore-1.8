# Java 8 Bukkit cache-owner integration

Pinned main comparison: `6390c1cab41bd4d7683c7df88dd36537c8c7861e`.

The production `BukkitUserCacheOwner` now adapts the existing manager, native cache
and worker to `SharedUserDataRuntime`. Java 8 adaptations replace its private
population record, pattern matching and collection factories. Population keeps
its UUID/cache/version fence. Backend replacement preserves stable admission
identities and fences queued predecessor notifications.

Initial binding requires a quiescent native admission boundary and cache-map
transition. Busy binding fails before publishing the initializer or SQL route,
leaving existing queued payload and legacy admission usable. Existing caches bind
to the selected writer before that initial transition ends. Native checked row
and bulk APIs use the selected provider; direct/bulk writes join shared exclusive
admission. The strict queue snapshot uses shared read admission, preferring pending
cache edits without discarding them or querying a retired legacy provider.
Presence and removal use that same selected provider. Successful removal retires
the cache generation and releases its adapter gates. Missing population snapshots
fail instead of publishing a ready empty cache; a valid later snapshot can retry.

Tests use the actual production adapter, manager, cache, runtime and JDBC SQLite
backend. Plugin/user hooks are controlled; they do not prove automatic plugin
startup integration. Eleven adapter tests cover physical reopen, busy binding,
pre-existing queued writes, notification generations, population fences, checked
provider selection, a transaction/direct-write race, pending queue snapshots, presence/removal, missing-snapshot rejection and native replacement generation
fencing.
Three native binding tests cover thread ownership and retirement seals.

Still incomplete: native provider bridges, full startup/reload/shutdown binding,
primary-thread deferred population/readiness, fetch-mode overloads, every scalar and
maintenance entry point, and real-server acceptance with automatic
shared routing enabled. Native storage stripes remain in place. No claim of
independent per-UUID native concurrency is made. Shared ownership is not enabled
automatically. The upstream shared-user dispositions remain partial.

Validation with Temurin 8u504 and the dedicated workspace-local Maven repository:

- Focused owner/binding/cache/notification checks: 28 tests PASS.
- AdvancedCore `clean install`: 820 unit + 78 artifact = 898 PASS.
- VotingPlugin `clean verify`: 45 unit + one artifact = 46 PASS.
- All reported tests have zero failures/errors/skips.
- Installed AdvancedCore dependency is byte-identical to the producer artifact.
- Producer: 1893 base classes; consumer: 2510; maximum class-file major 52.
- Existing actual Java 8/Spigot 1.8.8 checkpoint/publication retry, claim fencing,
  native actions and overflow park/disable/restart fixture: 12 checks PASS. It
  guards existing native recovery behavior, not automatic shared routing or
  process-crash recovery.

Commands select the workspace Java 8 JDK and use `-B -f <module>/pom.xml`,
`-Dmaven.resolver.transport=wagon`,
`-Dmaven.repo.local=/workspace/votingplugin-1.8-port-workspace/.m2/repository`
and `-Djava.io.tmpdir=/workspace/votingplugin-1.8-port-workspace/runtime/tmp`.
Logs and exact artifact identities are in the workspace-only
`evidence/shared-bukkit-final-owner-*` files. Full-scope independent review and final
backport acceptance remain pending.

The first retained-writer experiment attempted to mutate a cache already retired
by replacement and was correctly rejected. It did not prove a product defect.
The corrected regression verifies that rejection and then creates a fresh native
cache generation, whose public add/flush paths commit through the successor SQL
backend. The failed hypothesis log is `shared-bukkit-captured-writer-red.log`;
no implementation was changed to bypass retired-handle protection.
