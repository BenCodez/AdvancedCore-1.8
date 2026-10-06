# Upstream build-only dispositions

Pinned current-main reference: `6390c1cab41bd4d7683c7df88dd36537c8c7861e`.

Each row below was checked against the complete changed-file list and exact patch,
not inferred from its subject. These commits change only the stated build target
or workflow JDK. Their disposition is `OMIT_MODERN_ONLY`; no production fix is
hidden in these rows.

| Upstream commit | Complete semantic change | Java 8 / Bukkit 1.8 decision |
| --- | --- | --- |
| `5ba3be780509041b200a12af89c9f96428865db5` | Compiler source, target and release: 8 → 21 | Omit the Java 21 target; retain all three values at 8. |
| `788a40ec1570cfa10a60c114ca11d56aa4b3e483` | Existing Maven workflow JDK: 17 → 21 | Omit this modern-main CI JDK change; acceptance uses the actual workspace Java 8 JDK. No workflow is added or claimed to have run. |
| `a5baa9ded28d7f60dde5379f2552af01e94a766a` | Existing Javadoc publishing workflow JDK: 17 → 21 | Omit this modern publishing JDK change. Publishing is outside the authorized scope and was not run. |
| `28070de9f9adf52d96a46aa5b772a2a38bcff7c2` | Provided Spigot API: 26.1 → 26.1.1 | Omit the modern API target update; retain the 1.8 API and Spigot 1.8.8 runtime acceptance target. |
| `a6f9010c5a184913141c1087634f1cb1fe7d853f` | Provided Spigot API: 26.1.1 → 26.1.2 | Omit the modern API target update; retain the 1.8 API and Spigot 1.8.8 runtime acceptance target. |
| `6999070cacb8556167e607f8c2eeca355e1483a4` | Provided Spigot API: 26.1.2 → 26.2 | Omit the modern API target update; retain the 1.8 API and Spigot 1.8.8 runtime acceptance target. |

Both fork POMs retain compiler source/target/release 8 and provided
`org.spigotmc:spigot-api:1.8.7-R0.1-SNAPSHOT`; the primary live acceptance target
is Spigot 1.8.8. Existing build, artifact and runtime evidence is recorded in
[java8-sync.md](java8-sync.md). This documentation audit changes no build or
runtime inputs and does not claim a fresh build or a CI/publishing run.

This is a bounded subset of the upstream ledger, not a claim of matching main.
Mixed changes to SimpleAPI, Configurate, annotation processors, dependency
versions and compiler-plugin setup remain subject to their own source and
compatibility audit. A modern release number alone is not evidence that a
library is incompatible with Java 8. Fork release versions remain unchanged.
