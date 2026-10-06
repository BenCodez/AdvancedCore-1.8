# Java 8 shared reward orchestration

Pinned main comparison: `6390c1cab41bd4d7683c7df88dd36537c8c7861e`.

The `core.reward` API is now available in the 1.8 fork. It sequences prepared
native actions through supplied platform and durability adapters. The pinned
main implementation has no Bukkit adapter or native execution entry-point
wiring for this API; the backport preserves that boundary. It does not create a
second queue, store or executor, nor replace existing reward processing.

The contracts cover immutable prepared plans and versioned fingerprints,
execution-local context, persisted eligibility and cursor-zero placeholders,
absolute delay deadlines, retryable requirements, online deferral, physical
completion followed by checkpoint acknowledgement, terminal results,
collision-free nested paths and stack-safe asynchronous chains. Unsupported
versioned durability adapters fail closed. The caller's existing replay owner
must serialize its own occurrence/path and implement atomic begin and durable
acknowledgements. No production SQL adapter or crash-proof store is implied.

Java 8 adaptations:

- Records become final immutable classes with the same constructor/accessor,
  equality, hash and text shapes. Record reflection is unavailable on Java 8.
- Lists remain defensive, immutable and reject null elements. Progress maps
  retain nullable placeholder values and immutable defensive copies.
- Exceptional futures use an internal Java 8 helper. The interface's private
  fingerprint validator moves to a package-private helper without adding a
  public adapter method.
- Blank validation uses Unicode code-point whitespace; fingerprint bytes retain
  SHA-256 over the original length-delimited UTF-8 encoding with lowercase hex.
- Tests replace newer collection factories and inferred local types. An opaque
  CompletionStage proxy replaces `minimalCompletionStage` and rejects future
  conversion, so the chain tests still exercise arbitrary stage implementations.
  Duration's newer `toSeconds` test call becomes its equivalent `getSeconds`.

All six pinned upstream test classes are included with these Java 8 adaptations;
no assertion was weakened. Their 37 tests pass under the actual Java 8 JDK.
Four further tests verify immutable/null/Unicode contracts, value semantics and
an independently computed fixed fingerprint. Test-only disk restart/lost-ack
fixtures are not production database or process-crash acceptance.

The full producer uses the documented actual Temurin 8u504, workspace-local
Maven repository and temporary directory flags with `clean install`; the exact
consumer uses `clean verify`. Evidence: `shared-reward-java8-focused.log`,
`shared-reward-java8-clean-install.log`,
`shared-reward-java8-consumer-clean-verify.log`,
`shared-reward-java8-build-results.json`. A preliminary consumer invocation from
the producer directory failed because its POM path did not exist; its separate
log is retained. The correctly located consumer build passed.

This does not complete the shared user runtime/cache adapter, generation gates,
owned MySQL backend, native prepared-definition integration, or the full upstream
change ledger. Those remain required work; the presence of these classes must
not be used as evidence that native Bukkit rewards use this orchestrator.

Exact final results: 776 unit + 78 artifact = 854 producer tests PASS;
45 unit + one artifact = 46 consumer tests PASS; no failures/errors/skips.
Producer SHA-256: `80d5a7551a8151b76acbfe986d9e47fbaa64b39b451ca4fbedfefd5ce79329b8`.
Consumer SHA-256: `f36e45a2797d878be0ea402acf15b4c5d81e6e94883eea894717d7f016d25531`.
Both base class sets remain major 52: 1881 producer and 2498 consumer classes.

The exact packaged consumer passes all 12 existing actual Java 8/Spigot 1.8.8
native timed checkpoint/publication retry, active-claim fencing, scoped native
actions, overflow park/disable/restart/recovery and SQLite integrity checks
(`shared-reward-java8-runtime.log`). This is native integration regression
evidence, not a claim that Bukkit uses the newly added shared API or that its
test disk adapter proves production process-crash recovery. All 167 original
checkouts and both immutable references remain unchanged.
