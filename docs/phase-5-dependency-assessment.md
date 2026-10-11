# Phase 5 dependency assessment — 2026-10-10

**Phase 5 dependency maintenance is implemented and verified for the local/offline candidate scope.** The current Maven OSV gate is complete with zero findings; the earlier blocked graphs below are historical and resolved by the separate Boot 4/native-PBE security-maintenance slice. This is not production deployment approval, native/JDK/CDN vulnerability clearance or license/legal clearance. JsonDB remains default and SQLite explicit opt-in. No production data, secrets, bot startup, deployment or global JDK installation was used.

## Current measured runtime and verification

| Component | Current source/resolved version |
| --- | --- |
| Spring Boot / dependency-management plugin | **4.1.1 / 1.1.7** |
| Spring Framework / Security / Session | **7.0.9 / 7.1.1 / 4.1.1** |
| Tomcat / springdoc | **11.0.26 / 3.1.1** |
| Jackson 2 / Jackson 3 | **2.21.7 / 3.1.7** (Jackson 2 annotations **2.21**) |
| SQLite / Xerial / Liquibase | **3.53.4 / 3.53.4.0 / 4.33.0** |
| Tika / OpenCSV | **3.3.2 / 5.12.0** |
| Telegram Bots / JsonDB / native Jasypt | **6.9.7.1 / 1.0.115-j11 / 1.9.3** |
| Log4j / Commons Lang / Guava / JXPath | **2.25.5 / 3.21.0 / 33.7.2-jre / 1.4.0** |
| Gradle / Java maintenance target | **8.14.6 / Temurin 21.0.12.1+1** |

Boot dependency management supplies the compatible Spring family; no isolated Spring 7 override on Boot 3 was used. Jackson 2 remains persistence/Telegram and preferred HTTP through `spring-boot-jackson2` plus `spring.http.converters.preferred-json-mapper=jackson2`; both JSON families are patched coherently. The Boot 4 migration is a dedicated security-maintenance slice, not a service rewrite or database cutover. Runtime upgrade locally committed as `679884e` (`build: migrate supported Spring runtime and preserve legacy encrypted properties`), after `54d9ad0` reconciliation and `4ea9544` quality follow-up; all commits are local, with no push or deployment.

Final full clean build: **828 JUnit cases / 42 suites**, zero failures/errors/skips. Node: **18 tests** across web-security, deployment and dependency-scan fixtures, all passing. Real temporary JsonDB/SQLite/migration/restore and isolated Boot/filter-chain tests retain the existing API/CSRF/login contracts. Synthetic ImageIO/fake renderer and Telegram boundaries do not prove native ImageMagick or live hosting behavior.

The actual current report at `build/dependency-security/osv-report.json` records **`COMPLETE`, 138 coordinates, 0 findings, `2026-10-10T05:30:52.749Z`**. This is a real public-API rerun, not inferred from version numbers or relabeling older evidence. No advisories are suppressed.

### Native legacy ENC compatibility, not new encryption policy

The flagged `jasypt-spring-boot-starter`/`jasypt-spring-boot` **3.0.5** code is actually removed from the runtime graph. Native `org.jasypt:jasypt:1.9.3` backs a narrow PBE adapter using **`PBEWITHHMACSHA512ANDAES_256`**, SunJCE, pool 1, **1,000 iterations**, random salt, random IV and Base64. This preserves synthetic old-format `ENC(...)` compatibility; 1,000 iterations are **not a recommended new KDF**. Production ciphertext was not decrypted, re-encrypted or validated.

`LegacyEncryptedProperties` preserves source order, lazy reads, enumeration/contains/origin/native relaxed lookup and the **`MapPropertySource` type and original backing map**. It rejects unsupported GCM/custom algorithms/settings rather than silently supporting them. `EncryptedPropertyFailure` is a **cause-free `Error`**, intentionally not an `Exception`: Boot 4.1.1's opaque/non-enumerable property mapper catches exceptions and could otherwise fall through to lower-precedence plaintext. Synthetic Binder/bootstrap/concurrency/map tests cover refusal and redaction; ordinary higher-precedence plaintext is normal Spring precedence, not decryption fallback. Unknown-key enumeration cannot be guaranteed for opaque sources.

### Distribution integrity and actual patched-JDK proof

Gradle **8.14.6** uses the verified official distribution SHA-256 **`7988ed071b2a07900e2ec715fca15c6ab72bce6db433af301fa7fa7e9407bd24`**. CI also validates the wrapper. Strict locking is **runtimeClasspath only**, not a test/plugin lock claim.

Temurin **21.0.12.1+1** was downloaded through the official latest-release API into **`/private/tmp/regata-phase5-jdk-yCePxA/jdk-21.0.12.1+1/Contents/Home`**; archive SHA-256 **`44db0f08196daf19a47f90d13388b0c943b67663cb537f998fe29e836fa842ce`** was verified. This is isolated temporary storage, not a global install or `.env` change. `verifyMaintenanceJava clean build --info` used only that toolchain path, with autodetection/autodownload disabled, and proved **both compilation and Gradle Test Executor** used the patched JDK. The previous OpenJDK **21.0.2** build also passed but is not release-gate evidence. Exact command and evidence boundaries: [operations verification](phase-5-operations-results.md).

## Earlier compatible-maintenance attempt (HISTORICAL, superseded)

The following records describe the intermediate Boot 3 graph and publication checks at that time. They are preserved as evidence, **not current versions or unresolved current-graph findings**.

- Boot 3.2.3 → **3.5.16** (published Maven POM and official Java/Gradle requirements retrieved); dependency-management 1.1.7. Spring Session no longer overrides the BOM: resolved **3.5.7**, Spring Framework **6.2.19**, Security **6.5.11**.
- Tika **3.3.2**, springdoc **2.8.17**, OpenCSV **5.12.0**. Retained Telegram Bots **6.9.7.1** and JsonDB **1.0.115-j11**, covered by existing adapter/repository tests. A clean OSV result for Telegram itself does not establish current upstream support or comprehensive vulnerability freedom.
- Cohesive family overrides: Jackson BOM **2.21.7**, Log4j **2.25.5**; Commons Lang **3.21.0**, Guava **33.7.2-jre**, JXPath **1.4.0**. Real JsonDB, SQLite, migration, security and complete clean-build tests passed after updates.
- Tomcat **10.1.57** is resolvable. Attempted **10.1.58** (advisory fix) failed Gradle resolution and direct public POM retrieval with 404, so it was not left configured. Spring **6.2.20** and Boot **3.5.17** POMs likewise returned 404.
- Wrapper 8.6 has the official distribution SHA-256 `9631d53cf3e74bfa726893aee1f8994fee4e060c401335946dba2156f440f24c`. Runtime graph is strictly locked in `gradle.lockfile`; test/plugin graphs are not claimed locked.
- At that intermediate checkpoint `.tool-versions`/CI targeted Temurin **21.0.12.1+1**, but local tests still used installed **21.0.2**. The subsequent isolated patched-JDK verification above resolves that local evidence gap; host provisioning remains separate.

## Actual scanner evidence

`dependencyInventory` resolves runtime JAR coordinates and retains each embedded LICENSE/NOTICE/COPYING text under `build/dependency-security/licenses/`, alongside coordinate/license-review inventory. Missing embedded notices explicitly require upstream POM review; this is not a legal clearance. Upstream license families include Apache-2.0 (Spring, Jackson, Telegram, JsonDB, Tika, OpenCSV, Xerial, Liquibase), LGPL/BSD notices in individual transitives, EPL/LGPL Logback, and public-domain SQLite. Preserve the generated inventory/notices with release artifacts; do not strip upstream notices. CI retains evidence for 30 days; operator archival may need longer retention.

`dependencyScan` calls only the public OSV Maven batch API, no NVD key/.env. Three bounded attempts, 15-second request/stream deadlines, 4 MiB response cap, 50-package sequential batches, strict parsing/pagination refusal and incomplete-network failure. New scans remove stale reports. Local shim environments can supply `-PnodeExecutable=/absolute/node-or-shim`.

- **Historical first scan:** COMPLETE, **118 JAR coordinates / 23 findings**.
- **Historical compatible-patch scan:** COMPLETE, **122 coordinates / 7 findings**, **2026-10-10T03:39:30.854Z**; gate exited nonzero. Jackson/Guava/Commons Lang/Log4j findings disappeared in that actual rerun.
- **Current separate Boot 4/native-PBE rerun:** COMPLETE, **138 coordinates / 0 findings**, **2026-10-10T05:30:52.749Z**. The seven historical findings below are resolved for this graph, not waived.

| Historical blocked artifact | Advisory | Historical assessment → current resolution |
| --- | --- | --- |
| Jasypt starter + spring-boot library 3.0.5 (two coordinates) | GHSA-jgj7-c8vj-w563 / CVE-2026-9370 | Predictable GCM salt; no published fixed version listed then. Flagged starter/library code removed; native 1.9.3 narrow PBE bridge tested with synthetic legacy format, no suppression or production re-encryption. |
| Tomcat core 10.1.57 | GHSA-9xv2-5v5q-p794, GHSA-gcx9-497g-6cp6, GHSA-h3x4-894j-xpx5 | 10.1.58 was not publicly resolvable at that checkpoint. Separate Boot 4/container migration now uses 11.0.26 and passes actual OSV; no authenticator-based waiver. |
| Spring MVC 6.2.19 | GHSA-j9f9-w8pj-32f8, GHSA-pc63-qcmh-9cmg | No compatible public 6.2 patch was retrieved then. Separate Boot 4 migration now supplies Framework 7.0.9 with compatibility tests and actual zero-finding scan; no isolated framework override or exploitability waiver. |

**Historical decision:** the Boot-3.5 attempt was blocked and was not deployment-approved; no enterprise entitlement was assumed. The separately tested Boot 4 migration is now included in completed local maintenance. Boot 4 requires Java 17+ (not Java 22), while this repository requires patched Java 21. Release gates continue to fail closed on future findings/incomplete scans. The current zero-finding Maven report does **not** establish production approval, upstream support for every retained library, JDK/native SQLite/ImageMagick/CDN coverage, reachability, unknown-advisory freedom or legal clearance. Native/JDK advisory and license reviews remain operator responsibilities.

## Retrieved sources

- Current primary references: [Boot 4.1 requirements](https://docs.spring.io/spring-boot/4.1/system-requirements.html), [Boot 4.1.1 BOM](https://repo.maven.apache.org/maven2/org/springframework/boot/spring-boot-dependencies/4.1.1/spring-boot-dependencies-4.1.1.pom), [Tomcat 11 security](https://tomcat.apache.org/security-11.html), [Tomcat 10 security](https://tomcat.apache.org/security-10.html), [Jackson 3.1.7 POM](https://repo.maven.apache.org/maven2/tools/jackson/core/jackson-databind/3.1.7/jackson-databind-3.1.7.pom), [Gradle 8.14.6 checksum](https://downloads.gradle.org/distributions/gradle-8.14.6-bin.zip.sha256). These implementation references supplement the source/locked graph and saved actual scan.
- The Boot 3.5/Gradle 8.6 references below belong to the **historical** compatible-maintenance investigation, not the current graph.
- [Boot requirements](https://docs.spring.io/spring-boot/3.5/system-requirements.html), [published BOM](https://repo.maven.apache.org/maven2/org/springframework/boot/spring-boot-dependencies/3.5.16/spring-boot-dependencies-3.5.16.pom), [support timeline](https://spring.io/projects/spring-boot#support), [support policy](https://spring.io/support-policy).
- [Temurin support](https://adoptium.net/support/), [release API](https://api.adoptium.net/v3/info/release_versions?version=%5B21.0.12.1%2C21.0.13%29), [wrapper checksum](https://downloads.gradle.org/distributions/gradle-8.6-bin.zip.sha256).
- [OSV batch contract](https://google.github.io/osv.dev/post-v1-querybatch/); individual records at `https://api.osv.dev/v1/vulns/<advisory>` were fetched, with exact affected/fixed intervals inspected. No advisories were suppressed.