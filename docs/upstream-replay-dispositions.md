# Java 8 replay disposition audit

Pinned main comparison: `6390c1cab41bd4d7683c7df88dd36537c8c7861e`.
This audit complements the workspace-wide change ledger; it does not claim that
all upstream replay or lifecycle changes have been reviewed or implemented.

## Durable nested failures

`9d35eaf326442b1368c63f20f1b38779a0b6c8ef`:
`PORT_WITH_JAVA8_ADAPTATION`.
The complete production and test patch was reviewed. The existing fork's
`Reward.isDurableReplay` recognizes both an explicit checkpoint consumer and a
consumer inherited through shared replay state. Its retained `RewardHandler`
implements main's relevant `RewardExecutor` behavior: absent optional fresh
configuration is a no-op, absent durable configuration fails, and a selected
child inherited from a durable parent cannot fall back to creating an empty
reward file. Java 8 uses the existing exceptional-stage helper and captured
owner dispatcher, without adding main's source-layout refactor.

Two added tests exercise the actual handler with an inherited state and no
child-owned checkpoint consumer. They verify failure for missing selected and
configured children, prohibit the auto-creating resolver, and preserve fresh
optional no-op behavior. Existing nested sequence tests continue to cover
completed-child skipping and frozen-list recovery.

## Timed queue ownership and shutdown

`091a339479f48648aa185e134e88b843ad5827d0`:
`PARTIAL_PORT_WITH_JAVA8_ADAPTATION`.
The complete production and test patch was reviewed. The public
`RewardOptions.timedQueueReplay` flag is now present, set by actual timed queue
admission, and retained by dispatch, nested child and queue snapshots. Explicit
timed deferral returns failure before any offline queue insertion, with or
without a consumer inherited from a parent. Existing admitted durable offline
replays retain the offline deferral signal. No serialized field, queue marker,
configuration default or database schema changes.

A deterministic regression proves failure rather than false success and
prohibits both offline admission APIs. Removing just the explicit timed guard
makes that regression fail because deferral returns successful completion;
source bytes were restored before positive builds. The real timed loader test
also checks the new flag at the reward handler boundary.

The fork retains its native `AdvancedCorePlugin.onDisable` ordering: producers
settle while shared storage is still available, then the shared executor settles
before cache/provider retirement. Added tests prove a live producer cannot stop
shared storage and a live shared checkpoint executor cannot close the provider;
neither case force-cancels accepted work. Existing tests cover interruptions,
final flush failure and flush-before-close. These are native lifecycle proofs,
not completion of the still-unported `AdvancedCoreRuntime`/`BukkitRuntimePlatform`
extraction or its full cleanup-phase contract. The lifecycle part remains a
required technical obligation alongside its other upstream commits; it is not
an accepted omission or a product deferral.

## Validation

Actual Temurin Java 8u504, workspace-local Maven repository and temporary folder:

- Focused `-Dtest=LegacyOrderedRewardPipelineTest,LegacyNestedRewardSequenceTest,LegacyTimedQueueReplayTest,LegacyNativeShutdownTest,LegacyRewardShutdownTest test`:
  114 PASS before the two additional shutdown regressions.
- Final AdvancedCore `clean install`: 721 unit + 78 artifact tests = 799 PASS.
- Exact locally installed producer SHA-256:
  `9331e75892f2d361b00f278c892789d6c486246e7191c06bb14d1a22088c18b7`.
- Dependent VotingPlugin `clean verify`: 45 unit + one artifact test = 46 PASS.
- Consumer SHA-256:
  `997450ac7e566065531e609d033ba7ea6424e4ed40ee49c86cffb63434021e6c`.
- Both jars' base classes remain major version 52 or lower (1867 AdvancedCore,
  2484 VotingPlugin classes).

Commands retain the documented `JAVA_HOME`, `PATH`,
`-Dmaven.resolver.transport=wagon`, `-Dmaven.repo.local=<workspace>/.m2/repository`
and `-Djava.io.tmpdir=<workspace>/runtime/tmp` isolation flags.
Workspace evidence: `timed-deferral-focused.log`, `timed-deferral-mutation.log`,
`timed-deferral-clean-install-final.log`,
`timed-deferral-consumer-clean-verify.log` and `timed-deferral-build-results.json`.

Actual Java 8/Spigot 1.8.8 acceptance against that exact consumer passes all 12
existing timed physical-checkpoint/recovery-publication checks, including the
actual timer retry of failed completion removal without repeating native effects,
a repeated poll fenced while the original claim remains active, normal
shutdown/restart of parked items, and checked SQLite integrity. Evidence:
`timed-deferral-runtime.log` and
`queue-publication-recovery-runtime-a04cd218e6.json`.
This fixture does not prove paused timed SIGKILL recovery or the modern shared
runtime cleanup contract. Explicit paused deferral and inherited-state behavior
are proved by the deterministic tests; they are not relabeled as live crash tests.
All 167 original checkouts and both pinned references remain unchanged.

## Fresh sends and action reservations

`4f5d16ce23027ce2d7a79155882e1a0c40bfa15b` and
`57170f2de59aa8201cc6fa10af66de7370bc08d5`:
`PORT_WITH_JAVA8_ADAPTATION`.
The complete production and test patches were compared with the retained fork
implementation. Fresh root options remain reusable: each independent send has a
new replay state and occurrence ID, without setting caller-owned state, key or
occurrence fields. Inherited nested state is retained explicitly. Fresh command,
selection and nested-list metadata is held in that shared state; restored or
checkpointed execution may carry its durable metadata in placeholders.

The list adapter still copied internal markers into fresh child placeholders.
It now merges those markers only for durable execution. A regression failed
before this guard and passes afterward. Tests exercise both fresh and durable
lists, preserve ordinary parent placeholders, share frozen child metadata through
state, and execute an actual console command twice when fresh options are reused.
No configuration, serialized queue format or public signature changes.

`fe3e41998dda269c4a89ab517c6eaf4f244d9b8d`:
`PORT_WITH_JAVA8_ADAPTATION`.
The existing native action scope already releases and checkpoints only an action
conclusively rejected before entry. Its captured owner dispatcher fences late
callbacks and distinguishes failures before action entry from uncertain failures
after entry. Java 8 uses `recoverCompletion` instead of `exceptionallyCompose`.
Added tests call the actual scoped `AdvancedCoreUser.giveExp`: an unavailable
player prevents execution, checkpoints both admission and release, and allows a
changed payload on retry. A native action that throws after entry keeps its
reservation and rejects a changed retry without executing it. This is bounded
owner-dispatch evidence, not a claim of exactly-once external side effects under
process crash.

Candidate validation uses actual Temurin Java 8u504 and the same isolated Maven
flags documented above. Focused reward/list/native-action tests: 100 PASS.
AdvancedCore `clean install`: 725 unit + 78 artifact = 803 PASS.
VotingPlugin `clean verify` against that exact installed artifact:
45 unit + one artifact = 46 PASS. Zero failures, errors or skips.
Both artifacts retain base class-file major version 52 (1867 and 2484 classes).
Producer SHA-256: `25507fb625dc32ccfb31a32b8edde24e80af773709ad476ef6fd535ab4403a29`.
Consumer SHA-256: `797678be55d5b8dc4785436cbcbcac864a404bc3de1a9471cd0e4a105f478417`.
Workspace evidence: `fresh-child-metadata-red.log`,
`fresh-child-reservation-focused.log`, `fresh-child-clean-install.log`,
`fresh-child-consumer-clean-verify.log`, `fresh-child-build-results.json`.
These dispositions do not complete the remaining root replay/shared-runtime
integration, lifecycle or crash-recovery obligations in the overall ledger.

Actual Java 8/Spigot 1.8.8 acceptance against this exact consumer: five PASS
checks for wrong-backend retention without effects, matching-backend one-time
removal, completed-prefix round-trip through the public deferred queue API,
explicit force override, and clean linkage/shutdown. Evidence:
`fresh-child-runtime.log` and `offline-affinity-runtime-b398f37f34.json`.
This run is not a process-crash, MySQL or timed recovery test.

## Claimed queue entries under capacity pressure

`e6b1a80e6ce8c3a35c0c9f133dc409ff415e44e6`:
`PORT_WITH_JAVA8_ADAPTATION`.
The complete production and test patch was reviewed. The retained public
`setOfflineRewards` still trimmed the oldest entry even if it was running or
admitted to the serial backlog. Its new shared trimming helper also serves the
checked new-reward admission path. It snapshots claims without creating registry
entries, protects versioned occurrence IDs, counts removable copies of identical
legacy entries, and measures the existing 65535-byte limit with explicit UTF-8.
Unclaimed entries retain oldest-first removal. The appended entry is protected.
If only admitted entries remain above capacity, admission fails visibly instead
of deleting the durable record; the checked mutation does not publish that edit.
The public setter retains its existing queued-write API and intentional full-list
replacement semantics; this fix does not make stale caller snapshots atomic.
No format, storage column, public signature or configuration change.

A red-before-fix regression starts actual asynchronous queue replay, holds the
first effect pending, and verifies both active and serial backlog occurrences
survive a public setter's trimming. After publication both still complete and
are removed normally. Further tests verify two claimed identical legacy copies
survive while an excess third copy is removed, and multibyte queue size is bounded.
Focused queue tests: 40 PASS before the added legacy-count regression. Full
Java 8 producer/consumer and runtime evidence is recorded separately below.

Final Java 8 `clean install`: 728 unit + 78 artifact = 806 PASS.
Exact dependent `clean verify`: 45 unit + one artifact = 46 PASS.
Zero failures, errors or skips. Producer SHA-256:
`db38ffddb7640db965fc842ea60f270450bfe389bc49a66b4ce682d8693ce1c3`.
Consumer SHA-256:
`bdccb6a4e3fa91d5dc748322f078b7177caa74128db4f9a6a20f036050401235`.
Base classes remain major 52 (1867 producer, 2484 consumer). Evidence:
`claimed-trimming-red.log`, `claimed-trimming-focused.log`,
`claimed-trimming-clean-install.log`,
`claimed-trimming-consumer-clean-verify.log`,
`claimed-trimming-build-results.json`.

Actual Java 8/Spigot 1.8.8 acceptance passes five existing offline recovery,
completed-prefix queue round-trip, explicit-force and linkage/shutdown checks
against this exact consumer. Evidence: `claimed-trimming-runtime.log`.
The new capacity-pressure behavior is exercised deterministically, not presented
as a live-server capacity or crash test.

## Registered child setup admission

`fdfc48a9f4d4e2f8942bbc182f0cb5386417d014`:
`PORT_WITH_JAVA8_ADAPTATION`.
The complete production, build and test patch was reviewed. The retained facade
now hands a registered child's setup to its captured off-primary dispatcher,
including custom reward implementations. A queued callback claims the admission
receipt before invoking child code; expiry, closure or rejection fences late
setup. Once admitted, the receipt awaits the child's physical completion rather
than timing out that accepted effect. Null stages and thrown setup errors remain
exceptional receipts. Dispatch options are copied and explicit inherited replay
state is retained. The fork uses its existing Java 8 deadline dispatcher instead
of Java 9 `orTimeout`; it does not add a second executor or arbitrary delay.

The shade minimizer was already disabled in the fork. Scalar EXP and EXPLevels
now skip zero-value player callbacks, matching the upstream safeguard without
changing nonzero payloads, configuration keys, placeholders or public signatures.
Three added tests exercise the actual facade's expired queued setup, an accepted
child with pending physical completion beyond the admission deadline, and both
zero scalar built-ins. They fail before the adaptation. Focused nested/pipeline/
dispatch tests: 102 PASS.

`9a29a43b17d2d9ffd26edbfaea7fad5aad256bd4`:
`PARTIAL_PORT_WITH_JAVA8_ADAPTATION`.
The complete patch was reviewed. Root setup already uses the same bounded native
owner dispatcher; existing tests prove deadline callbacks and clock expiry fence
late work. The timed duplicate-occurrence reconciliation from this patch is not
implemented: the fork currently rejects duplicate timed occurrences before any
reward dispatch. This is a remaining compatibility/recovery obligation, not an
accepted omission or a claim that strict rejection implements reconciliation.

Java 8 `clean install`: 731 unit + 78 artifact = 809 PASS; exact consumer
`clean verify`: 45 unit + one artifact = 46 PASS, zero failures/errors/skips.
Producer SHA-256: `b586e2e569f372b070856eb177243888fc2cc8254e71f117e2a4d57591a4a760`.
Consumer SHA-256: `a9f387c3c5a5e9e3cb43aa099d8ca529b0845f8f816bab77abe322f13098d94e`.
Base classes remain major 52 (1867 producer, 2484 consumer). Evidence:
`async-child-handoff-red.log`, `async-child-handoff-focused.log`,
`async-child-handoff-clean-install.log`,
`async-child-handoff-consumer-clean-verify.log`,
`async-child-handoff-build-results.json`.

The exact consumer also passes all five existing actual Java 8/Spigot 1.8.8
offline recovery, completed-prefix round-trip, explicit-force and clean linkage/
shutdown checks (`async-child-handoff-runtime.log`). These are live integration
regressions; deadline expiry and zero callbacks are covered by deterministic
tests, not mislabeled as live process-crash tests. All 167 original checkouts
and both immutable references remain unchanged.

## Timed failure recovery publication

Additional adaptation for `9a29a43b17d2d9ffd26edbfaea7fad5aad256bd4`;
its disposition remains `PARTIAL_PORT_WITH_JAVA8_ADAPTATION`.
Timed failure recovery now reconciles the current admitted record with one
unambiguous cached record for the same occurrence inside the checked atomic queue
mutation. It validates reward identity before choosing that cached record,
retains its placeholders and progress, increments its existing retry count, and
publishes only one retry record. Different reward identities or multiple
conflicting alternatives fail before publication. Legacy pre-admission storage
failures still match their exact entry and preserve bounded storage wakeups.
The admitted claim and producer publication fence are retained until publication
settles; no vote/reward effect is repeated by the recovery edit itself.

Two new deterministic tests prove the original failure leaves duplicate records,
and then verify reconciliation of cached progress/retry/placeholders versus an
identity conflict retaining the original queue without timer publication. The
38 focused timed/checked-mutation/publication tests pass, including storage outage
wakeups, retired owners and physical checkpoint publication failure behavior.
No existing assertion was weakened. Initial-admission duplicate reconciliation
remains a required compatibility obligation; strict initial duplicate rejection
is not claimed to implement the complete upstream patch. Configurations, public
APIs, wire formats and serialized markers are unchanged.

Java 8 `clean install`: 733 unit + 78 artifact = 811 PASS; exact consumer
`clean verify`: 45 unit + one artifact = 46 PASS. Zero failures/errors/skips.
Producer SHA-256: `16ac8e5ec1c27cbd4b430c30726465921053e9c214b09ddd073677569841e356`.
Consumer SHA-256: `7ee34f87044f09bf7e58b8d4c4d79d03a4485d5b3edf21f258fb2c04fe9aab52`.
Base classes remain major 52 (1867 producer, 2484 consumer). Evidence:
`timed-reconciliation-red.log`, `timed-reconciliation-focused.log`,
`timed-reconciliation-clean-install.log`,
`timed-reconciliation-consumer-clean-verify.log`,
`timed-reconciliation-build-results.json`.

Actual Java 8/Spigot 1.8.8 acceptance passes all 12 existing timed physical
checkpoint, completion-removal outage/timer retry, competing-poll fencing,
native actions, overflow park/disable/restart, item recovery and checked SQLite
integrity checks against this exact consumer (`timed-reconciliation-runtime.log`,
`queue-publication-recovery-runtime-680e2d4728.json`). The cached-record conflict
reproduction is deterministic; this fixture is not a process-crash or initial
duplicate-admission proof. All 167 originals and both references remain unchanged.

## Monotonic failure checkpoint publication

The cached timed reconciliation audit exposed another failure mode: applying an
older `RewardReplayFailure` replaced a more advanced persisted v3 checkpoint and
its placeholders. The shared offline/timed failure serializer now compares
per-path completed counts and registry fingerprints before replacement. A
strictly dominating persisted checkpoint remains unchanged; a failure checkpoint
that covers the persisted paths may advance it. Incomparable progress or a
fingerprint mismatch fails before queue publication. Equal completed maps retain
the existing failure-metadata behavior, since child/command cursor metadata can
advance before the enclosing injection count does. This does not claim a complete
ordering or merge for all nested metadata, legacy checkpoints or shared storage
generations; those remain separate technical obligations.

A deterministic red-before-fix test publishes a cached v3 count of two and then
settles an older failure at count one. It now retains count two, cached
placeholders and the cached retry count. A second regression changes registry
fingerprints and verifies the queue remains unchanged with no retry timer.
The first focused timed/offline/publication run passes 57 tests before adding the
fingerprint regression. No configuration, API, schema or serialized format change.

Java 8 `clean install`: 735 unit + 78 artifact = 813 PASS; exact consumer
`clean verify`: 45 unit + one artifact = 46 PASS. Zero failures/errors/skips.
Producer SHA-256: `f46fe72349cc2a0d4e777bb3a39805a1c2d3ebdfbb7ff81e3c2a07e2a5fbba35`.
Consumer SHA-256: `1be82c1f569eb8c1dcd70ae591b5e556b76efb54981ce32bb44242b9db79355b`.
Base classes remain major 52 (1867 producer, 2484 consumer). Evidence:
`timed-checkpoint-order-red.log`, `timed-checkpoint-order-focused.log`,
`timed-checkpoint-order-clean-install.log`,
`timed-checkpoint-order-consumer-clean-verify.log`,
`timed-checkpoint-order-build-results.json`.

Actual Java 8/Spigot 1.8.8 acceptance passes all 12 existing timed physical
checkpoint, completion-removal/timer retry, competing-poll fencing, native
actions, overflow park/disable/restart/item recovery and checked SQLite integrity
checks against the exact consumer (`timed-checkpoint-order-runtime.log`).
Checkpoint comparison/conflict coverage is deterministic; no process-crash or
shared-generation proof is inferred from this run. All 167 original checkouts
and both references remain unchanged.

## Monotonic successful checkpoint acknowledgement

The successful checkpoint serializer lacked the non-empty v3 progress/fingerprint
ordering already applied to failure restoration. A late acknowledgement could
replace cursor two and its placeholder snapshot with cursor one, and a changed
registry could replace the acknowledged record. New regressions invoke each
production offline/timed queue dispatcher, publish through its captured checkpoint
consumer and inspect the actual native cache/checked persistence path. Before the
fix, two of three test methods fail: stale progress replaces the newer record and
registry conflicts are accepted. The forward-progress countertest passes.

The serializer now retains a strictly dominating persisted checkpoint and rejects
incomparable non-empty v3 maps or registry conflicts before queue mutation. Valid
forward progress continues to publish. Equal maps retain the existing metadata
behavior. This is an additional fork correctness fix discovered during the audit;
it does not establish complete ordering for nested command/child metadata,
empty-progress snapshots, legacy checkpoints or cross-process replay ownership.
Those remain explicit outstanding obligations. No schema, proxy format, public API,
configuration default or release version changes.

Actual Java 8 validation of the combined current worktree:

- Offline/timed/order focused run: 51 tests PASS.
- AdvancedCore clean install: 817 unit + 78 artifact = 895 PASS.
- Exact-dependency VotingPlugin clean verify: 45 unit + one artifact = 46 PASS.
- All passing runs have zero failures/errors/skips. Installed producer is
  byte-identical to its target jar; base classes remain maximum major 52.
- Existing actual Java 8/Spigot 1.8.8 checkpoint/publication retry, claim fencing,
  native actions and overflow park/disable/restart acceptance: 12 checks PASS.
  This is not a process-crash or shared-runtime automatic-startup acceptance claim.

Workspace evidence is `evidence/checkpoint-acknowledgement-order-*`, including the
red-before-fix run, commands/build logs, exact hashes and unchanged-original audit.
All 167 originals and both pinned references remain unchanged. Full scope and the
independent final review remain incomplete.

## Empty acknowledgement and lifecycle-test observation

A further red-before-fix regression showed that an empty successful
acknowledgement erased a non-empty persisted v3 checkpoint. The ordering guard
now also treats empty proposed progress as an older checkpoint. Initial empty
metadata publication and later cursor-zero/forward publication remain valid;
regressions exercise both production offline and timed consumers. This extends
non-empty v3 protection; equal-count nested metadata and legacy ordinal state
remain separate unresolved cases.

The full build also exposed a lifecycle-test observation race: the test read
`hasQueuedThreads()` twice and required both transient observations to be true.
It now captures the actual saving worker and awaits `hasQueuedThread(worker)`.
It still requires save to remain incomplete until delivery is released and then
checks the overflow item in the saved configuration. No inventory production
code or existing behavioral assertion was removed.

The nested-metadata audit identified an important valid exception for future
ordering work: `AsyncActionCollection` removes an unstarted action reservation
from its snapshot after `LegacyActionNotStartedException` and checkpoints that
release. Snapshot shrinkage alone therefore cannot establish stale publication.
A comparator must retain that proven-not-started release behavior. No general
nested-metadata comparator or process-crash guarantee is claimed here.

Validation of the combined current Java 8 worktree: 53 focused offline/timed/order
tests PASS; 16 focused checkpoint/inventory lifecycle tests PASS; corrected clean
install 819 unit + 78 artifact = 897 PASS; exact-dependency consumer clean verify
46 PASS. Zero failures/errors/skips in those passing runs. Both jars remain
maximum major 52, and the installed producer matches its target byte-for-byte.
Actual Spigot 1.8.8 recovery fixture: 12 existing checks PASS. The initial red run
and the initial full-build lifecycle-test failure are retained alongside the
corrected logs in workspace `evidence/checkpoint-empty-acknowledgement-*`.
All 167 originals and both pinned references remain unchanged. Full backport
acceptance and independent final review are still incomplete.
