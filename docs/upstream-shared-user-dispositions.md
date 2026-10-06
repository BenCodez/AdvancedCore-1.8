# Shared-user checkpoint audit: incomplete dependency port

Pinned main: `6390c1cab41bd4d7683c7df88dd36537c8c7861e`.

The complete production patches of these four commits were inspected. They
operate on main's shared SQL runtime, exclusive flush gates, staged change and
notification queues, and captured notification generations. The fork has none
of `SharedUserDataRuntime`, `BukkitUserCacheOwner`, `sharedExclusiveFlushGate`,
`changesAfterExclusiveFlush`, or `dispatchSharedUserDataNotification` in its
active production sources. Native per-user storage ownership is not evidence
that this optional shared-runtime feature is already ported.

| Upstream commit | Native fork evidence | Remaining work |
| --- | --- | --- |
| `9a740db145f993c1c318821b911f7aa783b41099` | Per-user ownership and lifecycle admission fence native mutations; native final cache retirement suppresses callbacks. | Port/audit shared lifecycle replacement and producing-generation capture; prove old callbacks cannot escape into the successor runtime. |
| `0532058f2f220e5e90290f9f0d5e1db3e97abfb5` | Checked native writes and queue mutations preserve admission and committed-vs-notification-failure distinctions. | Shared exclusive checkpoint admission, staged mutation boundary and captured deferred notification dispatch remain absent. |
| `a5ebd1917c33e98c6183516bdd21e0132f320bcc` | `UserDataCache.updateCache` preserves pending values; a new native checkpoint race regression proves newer queued data survives replacement and is later stored. | Shared staged-change republishing path remains absent; do not infer it from native snapshot preservation. |
| `65e6095d381f03780e638d89024848af9345f893` | Native callbacks run outside the storage monitor/lock, with committed failure distinctions retained. | Shared notifications-before-immediate-flush order remains unimplemented/unverified; native callback completion tests do not prove this different queue contract. |

All four are `PARTIAL_PORT_PENDING_SOURCE_REVIEW`, not complete ports, omitted
modern-only changes, or accepted product deferrals. Their shared-runtime
prerequisites require a Java 8/Bukkit 1.8 compatibility audit and integration
that preserves the native public APIs, physical schemas, payloads and lifecycle
ownership. Do not copy the shared runtime wholesale and fix compiler errors
without tracing its adapters and callers.

The dependency inventory starts with these inspected foundational commits:

- `b789d7b1b95252f05083f717a039fc9ff0841975`: SQL facade and existing Bukkit
  backend adapters (`SqlUserStorage`, `SqlUserDataAccess`, `BukkitSqlUserStorage`).
- `4417d55c016ed81102a72cc53ad3a4a6a3284889`: `UserCacheOwner` interface.
- `216acbf9639865befb0b1eccf4175fb93b0132a3`: shared lifecycle runtime.

The workspace evidence file `evidence/shared-user-runtime-pending-inventory.json`
contains 25 subject-selected candidates with immutable hashes and file stats.
This is an investigation index, not a complete dependency graph or a claim that
those patches have been fully reviewed or are Java 8 compatible.

The new native regression queues a newer value during a blocked direct checkpoint,
then replaces the visible snapshot with that checkpoint. It asserts immediate
read-after-write visibility, preserved pending ownership, storage callback
ordering, and a later stored value matching the newer mutation. Removing only
`preservePendingValues(replacement)` makes it fail; production source was restored
before the final test run. This test uses actual native cache entry points with
controlled storage callbacks, not a real SQL/runtime shared adapter.

Actual Java 8 focused suite:
`-Dtest=LegacyCacheSnapshotTest,LegacyQueueMutationBridgeTest,LegacyNativeAdmissionTest test`
with the documented workspace-local Maven flags: 29 PASS, zero failures/errors/skips.
Full actual Java 8 `test`: 636 PASS, zero failures/errors/skips. Evidence:
`evidence/checkpoint-replacement-native-focused.log`,
`evidence/checkpoint-replacement-native-mutation.log`, and
`evidence/checkpoint-replacement-native-full-unit.log` under the isolated workspace.
Only tests and documentation changed. No fresh artifact or live server validation
is claimed for this audit; prior exact artifact evidence remains separate.
The full backport and final independent review remain incomplete.
