# Prepared reward APIs removed by pinned upstream

The comparison remains AdvancedCore
`6390c1cab41bd4d7683c7df88dd36537c8c7861e`.
Its ancestor `3a85ec26ea7b9654b6368fbba443884c715b5723` explicitly reverted
AdvancedCore #328/#329 in favor of live YAML reward behavior. It deleted prepared
catalog/definition APIs, keyed durability/action admission, related tests and
native handler/loader hooks. It retained atomic user transactions and earlier
platform-neutral shared reward orchestration.

The following prepared-catalog changes are therefore omitted as removed upstream:

- `2cf7d56c0a7e81deed274d05194e4ce92dbda1d9`: definitions/catalog introduction.
- `6e058d53f3f1489e88b8e7a2ccd80ae83fe7f49d`: registry capture coordination.
- `54708cd1701f01dc9b4372a413734cb4d072a719`: empty named fallback.
- `309f5e2d4437edd61850680246ab9524dc84877f`: bounded catalog capture/lookup.

Their ancestry, changed-path inventory and absence from the pinned reference and
fork are recorded in workspace `evidence/prepared-reward-removal-audit.json`.
No existing 1.8 API is deleted and no production source changes for this audit.
These superseded APIs must not be listed as outstanding native implementation.

The retained shared orchestration decision, fingerprint and absolute-deadline
contracts remain supported by the Java 8 port. They do not imply that the native
Bukkit executor uses this orchestrator. Native offline/timed replay durability,
root completion, metadata ordering, queue capacity, lifecycle integration and
runtime acceptance remain separate obligations. This audit does not close those
obligations or classify the entire upstream range.

The keyed-admission introduction and follow-ups are also omitted under the same
explicit upstream removal:

- `0c6974828f5c6e93bdb955babe7baab2444f9079`: action admission.
- `16173903e21ce54606ed0a581e199a63a7501bc7`: post-claim runtime guards.
- `7407b1c84b64de9d84251115c4993eb523d911dd`: keyed nested rejection.
- `8f230e1913e3f0617ba235fd156653276effe3a8`: keyed execution scope.
- `8c5eb7ba5ded3ad95320847e82b0524e9de18ba5`: owner-dispatch correction.

The removal itself is represented by preserving the fork's native live-YAML API
and the retained post-removal shared contracts. No reverted native capture hook
or keyed API is introduced. Ancestry/path/absence checks are retained in workspace
`evidence/shared-reward-keyed-removal-audit.json`.

Retained stack-safety change `dd469393709788f388540c9772950e74ae64a0c7`
is ported by `e8e907a8bdf7a68c0d8e077897e8f627388441cf`. Its 5000-step test
is byte-identical to the pinned reference and passes in the current complete
Java 8 producer build (898 checks). This closes those specific dispositions,
not the rest of the upstream ledger or native runtime acceptance.
