# Java 8 shared-user runtime foundation

Pinned main comparison: `6390c1cab41bd4d7683c7df88dd36537c8c7861e`.

The fork includes the platform-neutral `SharedUserDataRuntime`, `UserCacheOwner`
and `UserDataFetchMode` contracts from the pinned comparison. The runtime
coordinates an injected existing cache/queue and SQL backend; it does not allocate
another cache, queue, storage provider or executor. Fair lifecycle and per-user
admission gates fence backend replacement, exclusive transactions and shutdown.
Transactions flush pending cache writes before committing SQL, then retire the
cache generation. Post-commit cache failures are reported separately rather than
inviting duplicate transaction retries. Replaced-backend cleanup can be retried
without undoing the published successor. Final shutdown discards notifications,
including when its final flush fails.

Java 8 adaptations replace collection factories with defensive immutable copies,
pattern matching with ordinary casts, and Java 9 minimal close stages with an
observation-only CompletionStage adapter. Each future conversion receives a new
observer; cancelling or manually completing it cannot settle the owned close
attempt. Chained stages retain this observation boundary. No production dependency
was added. Public method signatures, storage types and physical schemas remain
unchanged.

The pinned ten runtime tests are retained with only Java 8 collection substitutions.
An additional regression queues shutdown, manipulates two observation futures,
and proves the actual close remains pending until its owned task executes.
These tests use controlled cache/backend adapters. They do not prove native Bukkit
cache integration or real database behavior.

The subsequent native cache and Bukkit owner milestones are documented in
`java8-native-shared-cache.md` and `java8-bukkit-cache-owner.md`. The production
Bukkit adapter now exists, with explicit worker-side manager binding and selected
native routing. Automatic startup/reload/shutdown integration, provider bridges,
primary-thread deferred population and the remaining native overloads are still
required. Adding the fetch-mode enum does not port those overloads. The four
shared-user checkpoint commits remain partial; these milestones do not establish
full backport readiness.

Validation for this foundation milestone (actual Temurin 8u504):

- Focused `-Dtest=SharedUserDataRuntimeTest test`: 11 PASS.
- Producer `-f AdvancedCore/pom.xml clean install`: 787 unit + 78 artifact =
  865 PASS; zero failures, errors or skips.
- Consumer `-f VotingPlugin/pom.xml clean verify`: 45 unit + one artifact =
  46 PASS; zero failures, errors or skips. Its workspace-local dependency matches
  the producer jar byte-for-byte.
- Producer: 1888 base classes, maximum major 52; SHA-256
  `2194cc51fb8f251716a6e0633da80606c2bb311954f0fd8b96d9ffe59fbb9b44`.
- Consumer: 2505 base classes, maximum major 52; SHA-256
  `c7ab01b38502e929e75e79f9a68097779e213d75172d8fe27427d5f1fbf7c0b7`.
- The exact consumer passes all 12 existing real Java 8/Spigot 1.8.8 timed
  checkpoint/publication retry, native action and overflow park/restart checks.
  This guards existing native behavior; it does not activate the shared adapter.

All Maven commands use `-B -Dmaven.resolver.transport=wagon`,
`-Dmaven.repo.local=/workspace/votingplugin-1.8-port-workspace/.m2/repository`
and `-Djava.io.tmpdir=/workspace/votingplugin-1.8-port-workspace/runtime/tmp`,
with JAVA_HOME/PATH selecting the workspace Java 8 JDK. Logs and artifact/test
counts are retained in the workspace `evidence/shared-runtime-java8-*` files.
The initial focused compile failed on a test fixture name typo; it was corrected
before the focused and complete successful runs. All 167 original checkouts and
both pinned references remain unchanged. Full-scope independent review remains
pending until implementation is complete.
