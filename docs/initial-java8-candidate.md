# Initial Java 8 / Spigot 1.8.8 candidate

The maintainer narrowed this iteration to a usable first compatibility candidate,
not exhaustive current-main feature parity. The unfinished upstream change ledger
is retained as future-work evidence; it is not a release-completion requirement
for this initial candidate. Unsupported and unverified features remain explicit.

## Comparison and compatibility boundary

| Repository | Clean 1.8 starting commit | Pinned main reference |
| --- | --- | --- |
| AdvancedCore | `d4a8f667d91e7121a5e0929a0e16cd615556f00d` | `6390c1cab41bd4d7683c7df88dd36537c8c7861e` |
| VotingPlugin | `1b1e6d7d47a3d0af2ad5ede79ae3436c7aaddf73` | `834bcb84a59b6da40640eac83c20fd97ad1d64c9` |

The primary target is an actual Java 8 runtime and Spigot 1.8.8. Existing legacy
APIs, configuration keys/defaults, saved user/reward formats and proxy payloads
remain the compatibility boundary. Existing operator configuration is not replaced.
`VotingPlugin/src.main.java` is retained. No release version is changed.

The candidate restores reproducible Java 8 builds and ports selected portable
configuration, commands, storage/cache, GUI, and reward-completion/recovery fixes.
VotingPlugin consumes the exact isolated AdvancedCore artifact through a
workspace-local Maven repository. No normal developer Maven cache, original
checkout, or read-only reference is modified.

## Deliberately outside this first candidate

- Exhaustive disposition and implementation of every intervening main commit.
- Automatic integration of the new platform-neutral SQL backend lifecycle. The
  shared core contracts/checked adapters are present and tested, but the existing
  native plugin storage lifecycle remains authoritative. No operator-facing claim
  of a fully integrated replacement is made.
- Current Control management, authenticated modern proxy transports, and complete
  current-main network/storage semantics. Existing legacy transport contracts stay.
- Current Velocity, Paper/Folia, Adventure and modern NMS/platform features.
  Velocity sources are retained but excluded from the Java 8 artifact. Legacy
  Bungee support does not imply current Bungee runtime Java 8 compatibility.
- Automatic teardown recovery after a producer misses the five-second shutdown
  grace period. Disable reports failure and retains its provider/accepted work for
  explicit retry; it does not force cancellation or close beneath active writes.
  Automatic deferred cleanup after that timeout is not implemented.
- A complete historical-data upgrade matrix or exactly-once effects across every
  process-crash boundary. Recovery evidence covers the scenarios actually tested.
- Economy-provider reward acceptance, Oracle MySQL-specific acceptance, and every
  proxy transport. Earlier runtime evidence covers MariaDB interoperability,
  NuVotifier, PlaceholderAPI, Vault permissions and legacy Bungee plugin messaging;
  it must not be confused with current-artifact reruns unless explicitly reported.

Incomplete foundations must not be enabled automatically or advertised as working
operator features. New discoveries that break startup, storage, basic vote/reward
processing, legacy API compatibility or teardown still require a fix before review
readiness; optional unported modern features can remain deferred.

## Current candidate validation

Actual Temurin 8u504, explicit workspace-local Maven repository and temporary path:

- AdvancedCore `clean install`: 839 unit + 78 artifact = 917 passing tests.
- VotingPlugin `clean verify`: 45 unit + 1 artifact = 46 passing tests against the
  exact locally installed AdvancedCore jar. Target and installed dependency match.
- Both jars: all base classes have class-file major 52 or lower. Multi-release
  entries are evaluated according to Java 8 loader behavior.
- Real Java 8/Spigot 1.8.8 queue recovery fixture: 12 passing checks for root/native
  rewards, physical timed checkpoint, completion-removal retry and active claim
  fencing, overflow persistence/restart delivery and SQLite integrity.

Additional current-artifact Java 8/Spigot 1.8.8 acceptance: 12 checks passed for
basic vote/reward processing, checked native storage, point commands, SQLite
reload/reinitialization and restart persistence (final points/total: 10/1). Ten
offline reward-checkpoint/item-recovery checks also passed. These fixtures do not
prove every Votifier socket/provider path or current-artifact proxy transport.

These results do not establish complete main parity. The final independent review
and candidate-wide readiness assessment remain pending. Exactly one eventual PR
per fork is intended; no push or PR creation is authorized yet. Detailed historical
commands/results and feature-specific limits remain in `java8-sync.md` and the
other compatibility documents.
