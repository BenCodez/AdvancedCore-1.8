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
