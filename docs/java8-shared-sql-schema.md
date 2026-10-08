# Shared SQL schema and lifecycle prerequisites

Comparison reference: `6390c1cab41bd4d7683c7df88dd36537c8c7861e`.

The additive `core.user.storage.sql` contracts preserve the pinned main public
API without replacing the fork's native storage or changing any configuration,
physical table, serialized user value, proxy payload, or release version.

- `SqlUserSchema`: upstream introduction
  `0840e973c3534a599c9283148489384d63a1ae4b`. The Java record becomes an
  immutable Java 8 value class with the same accessors, equality, hash and text
  representation. Java 11 `isBlank()` becomes code-point whitespace validation.
  Ordered snapshots, root-locale lookup, declared SQL types, reserved UUID and
  duplicate rejection remain intact. Key membership is copied before extension
  metadata callbacks, preserving the fork's detached-registration boundary:
  keys registered by a callback belong to the next schema generation.
- `SqlBackendLogger`: upstream introduction
  `640b301091bbc006d39a02fcf9bb2d45aaa438af`, unchanged. The optional no-op logger
  is a contract, not a replacement for existing failure reporting.
- `SqlUserBackend`: upstream introduction
  `af8c3bc81929306c85ad2d1837ddd0d3366efd09`, retaining the pinned lifecycle and
  additive streaming API. The materialization limit and streaming override must
  be enforced by future concrete backends; this interface alone enforces neither.

Six schema regressions cover immutable generation membership, detached ordered
views, reserved/case-duplicate identity rejection, locale-independent typing,
Java 8 value semantics, Unicode blank rejection, and metadata callbacks that
register another key. Removing the key-membership copy makes the callback test
fail with ConcurrentModificationException; candidate source was restored before
full validation. The existing six SQL facade regressions also pass.

These are prerequisites, not a completed shared SQL runtime. No new backend is
selected or instantiated. Concrete JDBC backend/factory, cache ownership,
transaction integration, runtime replacement and notification ordering remain
incomplete. Native storage acceptance cannot establish those absent contracts.
No production dependency, Bukkit API requirement or descriptor changed.
