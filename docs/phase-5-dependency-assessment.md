# Phase 5 dependency assessment — 2026-10-10

Maintenance is locally tested; **security acceptance is blocked**, not complete. Runtime remains JsonDB by default and SQLite is explicit opt-in. No global JDK, runtime data or secrets were accessed.

## Measured changes

- Boot 3.2.3 → **3.5.16** (published Maven POM and official Java/Gradle requirements retrieved); dependency-management 1.1.7. Spring Session no longer overrides the BOM: resolved **3.5.7**, Spring Framework **6.2.19**, Security **6.5.11**.
- Tika **3.3.2**, springdoc **2.8.17**, OpenCSV **5.12.0**. Retained Telegram Bots **6.9.7.1** and JsonDB **1.0.115-j11**, covered by existing adapter/repository tests. A clean OSV result for Telegram itself does not establish current upstream support or comprehensive vulnerability freedom.
- Cohesive family overrides: Jackson BOM **2.21.7**, Log4j **2.25.5**; Commons Lang **3.21.0**, Guava **33.7.2-jre**, JXPath **1.4.0**. Real JsonDB, SQLite, migration, security and complete clean-build tests passed after updates.
- Tomcat **10.1.57** is resolvable. Attempted **10.1.58** (advisory fix) failed Gradle resolution and direct public POM retrieval with 404, so it was not left configured. Spring **6.2.20** and Boot **3.5.17** POMs likewise returned 404.
- Wrapper 8.6 has the official distribution SHA-256 `9631d53cf3e74bfa726893aee1f8994fee4e060c401335946dba2156f440f24c`. Runtime graph is strictly locked in `gradle.lockfile`; test/plugin graphs are not claimed locked.
- `.tool-versions`/CI target Temurin **21.0.12.1+1**, verified by Adoptium release API/support roadmap. Local verification used the installed **21.0.2**, with `ASDF_JAVA_VERSION=openjdk-21.0.2`; no newer JDK was installed or tested locally. `verifyMaintenanceJava` intentionally rejects this older JDK for release/CI, but is not imposed on isolated local tests.

## Actual scanner evidence

`dependencyInventory` resolves runtime JAR coordinates and retains each embedded LICENSE/NOTICE/COPYING text under `build/dependency-security/licenses/`, alongside coordinate/license-review inventory. Missing embedded notices explicitly require upstream POM review; this is not a legal clearance. Upstream license families include Apache-2.0 (Spring, Jackson, Telegram, JsonDB, Tika, OpenCSV, Xerial, Liquibase), LGPL/BSD notices in individual transitives, EPL/LGPL Logback, and public-domain SQLite. Preserve the generated inventory/notices with release artifacts; do not strip upstream notices. CI retains evidence for 30 days; operator archival may need longer retention.

`dependencyScan` calls only the public OSV Maven batch API, no NVD key/.env. Three bounded attempts, 15-second request/stream deadlines, 4 MiB response cap, 50-package sequential batches, strict parsing/pagination refusal and incomplete-network failure. New scans remove stale reports. Local shim environments can supply `-PnodeExecutable=/absolute/node-or-shim`.

- First measured scan: **COMPLETE, 118 JAR coordinates, 23 findings**.
- After compatible patches: **COMPLETE, 122 JAR coordinates, 7 findings**, timestamp **2026-10-10T03:39:30.854Z**; gate exits nonzero. Jackson/Guava/Commons Lang/Log4j findings disappeared in the actual rerun, not merely assumed fixed.

| Remaining artifact | Advisory | Assessment/action |
| --- | --- | --- |
| Jasypt starter + library 3.0.5 (two coordinates) | GHSA-jgj7-c8vj-w563 / CVE-2026-9370 | LOW; predictable GCM salt, no published fixed version listed by OSV. Existing encrypted configuration must not be silently re-encrypted or discarded. Keep finding visible; evaluate compatible fix/configuration with isolated synthetic encryption tests. |
| Tomcat core 10.1.57 | GHSA-9xv2-5v5q-p794, GHSA-gcx9-497g-6cp6, GHSA-h3x4-894j-xpx5 | CRITICAL; fix 10.1.58 is not publicly resolvable in this verification. Do not whitelist merely because app uses Spring form login rather than container authenticators. Recheck publication or perform separately tested container/Boot migration. |
| Spring MVC 6.2.19 | GHSA-j9f9-w8pj-32f8, GHSA-pc63-qcmh-9cmg | CRITICAL; SSE view fragments/XsltView cases. Source does not establish exploitability, but no compatible public 6.2 patch was retrieved. OSV lists 7.0.9 fixes; moving to Spring 7 requires a separate Boot 4 migration, not an isolated framework override. |

**Do not deploy this maintenance set as security-approved.** Official Boot support table shows 3.5 OSS support ended June 2026 and enterprise support through June 2032; no enterprise entitlement is verified. Boot 4 supports Java 17+ (it does not require Java 22), but is outside this requested 3.5-compatible maintenance slice. Release gates remain fail-closed until findings/support policy are resolved. Public OSV scanning does not cover JDK/native SQLite/ImageMagick/CDN dependencies, reachability, unknown advisories or guarantee zero CVEs.

## Retrieved sources

- [Boot requirements](https://docs.spring.io/spring-boot/3.5/system-requirements.html), [published BOM](https://repo.maven.apache.org/maven2/org/springframework/boot/spring-boot-dependencies/3.5.16/spring-boot-dependencies-3.5.16.pom), [support timeline](https://spring.io/projects/spring-boot#support), [support policy](https://spring.io/support-policy).
- [Temurin support](https://adoptium.net/support/), [release API](https://api.adoptium.net/v3/info/release_versions?version=%5B21.0.12.1%2C21.0.13%29), [wrapper checksum](https://downloads.gradle.org/distributions/gradle-8.6-bin.zip.sha256).
- [OSV batch contract](https://google.github.io/osv.dev/post-v1-querybatch/); individual records at `https://api.osv.dev/v1/vulns/<advisory>` were fetched, with exact affected/fixed intervals inspected. No advisories were suppressed.