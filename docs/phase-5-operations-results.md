# Phase 5 — Operations implementation and offline results

Date: **2026-10-10**. Scope: current source, configuration, scripts, tests and local Git evidence in `RegataSimulator`.

**Phase 5 operations are implemented for the isolated local/offline scope:** coordinated recovery capture, fresh-target restore, read-only deletion-stage inspection, bounded operational startup/probes, configurable scheduling/shutdown, telemetry/login throttling, moderation auditing, dependency maintenance and checked-artifact deployment recovery. This is **not** live production cutover, a whole-application production-readiness certification, a crash-proof recovery guarantee, or evidence of a real deployment.

JsonDB remains the default; SQLite requires explicit opt-in. No automatic production path selection/import is introduced. Implementation verification used temporary stores, synthetic native-PBE/ImageIO fixtures and fake Telegram/render/host boundaries. Patched Temurin was downloaded and checksum-verified into isolated `/private/tmp` storage, **not globally installed**. No production data/secrets, application/bot startup, actual ImageMagick or real deployment was used; `.env` and global tool configuration were unchanged.

## Evidence and local commit status

Read first: `AGENTS.md`, [backend improvement plan](backend-improvement-plan.md), [project map](project-map.md), and [dependency assessment](phase-5-dependency-assessment.md). Current guidance records Phase 5 completion; earlier Phase 4/early Phase 5 checkpoints remain explicitly historical. Configured Phase 5 capture supersedes the SQLite backup block, and checked-in scripts replace `subprocess`; historical results are not rewritten as newly verified behavior.

| Local commit | Implemented slice |
| --- | --- |
| `8cbe0ad` | Cooperative media mutation boundary and verified SQLite snapshots |
| `59f86df` | Coherent local recovery bundles, retention and offline restoration |
| `b2a7e98` | Local probes and bounded startup/operational configuration |
| `9c84a2d` | Dependency maintenance, runtime locking and fail-closed OSV evidence |
| `985d81b` | Immutable release supervision and failed-rollout recovery fixtures |
| `e4cdee1` | Atomic SQLite moderation audit and preserved recovery history |
| `4b9b7fb` | Redacted operation measurements and credential-attempt throttling |
| `54d9ad0` | Stopped-copy reconciliation without modifying retained data |
| `4ea9544` | Reconciliation quality/refactoring follow-up preserving safety checks |
| `679884e` | `build: migrate supported Spring runtime and preserve legacy encrypted properties` — separate Boot 4/Gradle/native-PBE compatibility and security-maintenance slice |

The seven original Phase 5 commits and two reconciliation follow-ups are recorded local slices. Runtime upgrade locally committed as `679884e`; all commits are local, with no push or deployment. Local/offline implementation acceptance is complete; Git history does not imply production approval.

## 1. Coherent recovery capture and retained local artifacts

### Implemented snapshot boundary

- `application.MediaMutationGuard` is a fair `ReentrantReadWriteLock`: mutation leases take the shared/read lock; `snapshot()` takes the exclusive/write lock and rejects attempted read-to-write upgrades. It is a **cooperative single-JVM barrier**, not a cross-process/database/filesystem transaction.
- `configuration.MediaMutationBoundary`, ordered before callback locks and metadata transactions, wraps public methods of `SourceService`, `TemplateService`, `SubmissionService`, `SourceImporterService`, `MemeService`, `ModerationService` and `ReviewCallbackService`. HTTP, Telegram and scheduler adapters use these services. Direct repository maintenance, another JVM and external filesystem writers are outside this protection and must be stopped separately.
- `migration.RecoverySnapshotService.capture()` serializes captures and creates owner-only scratch storage. While holding the exclusive lease it captures metadata, copies both media trees, audits the copy and applies private permissions. The copied files—not mutable live media paths—are then the input to packaging **after the lease is released**.
- SQLite capture calls `SqliteStore.snapshot()`: an autocommit connection executes the parameterized **`VACUUM INTO ?`** statement, including committed WAL writes, into a new absolute file. Existing outputs, symlink ancestors and active Spring transactions are refused. The candidate is chmod 600 and reopened for schema/integrity validation. This is verified `VACUUM INTO`, not a plain copy of a live database and not an implementation of SQLite's backup API.
- JsonDB capture exports repository metadata into fresh schema-1.0 NDJSON files: `sources.json`, `templates.json`, `users.json`, `memes.json` and **`audits.json`**. It does not archive the live JsonDB lock directory. Nullable origins, preview bindings and ordered history retain their existing contracts.
- Orphan directories and uncertain `.delete-*` bytes are preserved, not automatically purged. Blocking metadata/media inconsistencies prevent successful packaging. A stage containing bytes for a missing original is not automatically reconciled by capture.

`SqliteSnapshotTest` checks committed WAL inclusion, independence from later writes, new-target/transaction refusal and private permissions. `MediaMutationBoundaryTest` checks writer/snapshot exclusion, nested mutation blocking, upgrade refusal and release on exception. `SqliteApplicationContextTest` checks actual service-proxy pointcuts and real capture/restore; a fake delivery can invoke a writer after capture, confirming delivery is outside the exclusive lease.

### Bundle format, validation and limits

`migration.RecoveryBundle` packages a `backup-<UUID>` directory containing `regata-backup-*.zip` parts, `manifest.json` and `COMPLETE`.

- Manifest format **1** records engine, schema version, database location (`db/store.db` or `jsondb`), collection counts including audits, relative-file SHA-256 hashes, anomaly records and each part's category/name/SHA-256.
- `COMPLETE` is written **last** and contains the 64-character SHA-256 of `manifest.json`. It marks successful application-level packaging, not fsync/power-loss durability or authenticity. Hashes detect mismatches; they are not cryptographic signatures or encryption.
- Each direct child file/directory is **indivisible**. A whole SQLite database file or media UUID directory above **40 MiB** fails rather than being split. Encoded ZIP size is checked too: compression/ZIP overhead cannot exceed **40 MiB per archive**.
- Packaging bounds regular-file payloads to **2 GiB** and **100,000 files**. Restoration independently bounds total expanded bytes to **2 GiB** and entries—including directory entries—to **100,000**. A file count under the packaging limit alone does not waive the restoration entry limit.
- The manifest is at most **8 MiB**, with at most **10,000 parts**. Unsupported formats/engines/schema identities, malformed/null fields, missing/unexpected parts, invalid hashes, duplicate part names and oversized archives are refused.
- Path validation rejects absolute/traversal/dot/empty components, backslashes, colons, percent signs, control characters and names over 2,048 characters. Filesystem symlinks/ancestors are forbidden. Extraction uses new files and checks duplicate entries across parts, expanded byte/entry limits and exact restored hashes.
- ZIP central-directory validation rejects encrypted, multi-disk, ZIP64 and special Unix entries such as symlinks; malformed directories fail closed. Existing destinations are never overwritten.
- Packaging failures clean only the owned candidate/prepared ZIPs; scratch cleanup is exception-safe. Failed restoration retains an **unverified** fresh candidate without `RESTORED_VERIFIED`, for inspection. Cleanup failure is not a claim that every failure leaves no bytes.

### Independent destination and retention

Configure `regata-simulator.backup.local-directory` explicitly to an **existing absolute, canonical, non-symlink directory with mode 700**. It must not overlap either media root, configured JsonDB directory or configured SQLite file. The service does not create/select a production backup root automatically. `regata-simulator.backup.retention-count` defaults to **7** and accepts **1..100**.

Retention is **count-only**, not age/space-based. Only matching `backup-[a-f0-9-]{36}` direct child directories are candidates. Every candidate's completion marker, manifest and part checksums/sizes are validated **before any deletion**; a corrupt/incomplete candidate aborts retention and holds the earlier candidates. Candidates are ordered by `COMPLETE` modification time (stable lexical ordering for ties). Unrelated directories are untouched. Retention does not perform a complete restore drill on every candidate.

An independent directory is not proof of an independent disk/host or off-site durability. Operators must provision and verify the desired failure-domain separation, space and access policy. Bundles contain sensitive metadata/media and are not encrypted by this format.

### Telegram delivery and partial failure

The injected production `BackupService.create()` first captures/retains a complete local bundle, then sends its regular files and finally the statistics report. A document/report failure leaves the **local complete artifact** available; partially sent Telegram files are not a complete remote backup merely because a completion marker was received. Restore requires all manifest-listed parts and successful validation.

There is no automatic resend, durable retry/outbox, exactly-once remote delivery or crash-completion guarantee. A retention failure can report capture failure while leaving a completed new bundle for inspection. The older `archive()` helper still has temporary-ZIP cleanup semantics; it is not the coordinated production capture path. `DirectOperationsTest` verifies capture-before-delivery/report and retention on delivery failure.

## 2. Offline restoration and deletion-stage inspection

### Restore operator procedure

Run from the repository root using the checked-in Gradle wrapper and a compatible Java 21. All examples use **operator-supplied environment-variable placeholders containing absolute isolated paths**, not production defaults. These commands are operator procedures, not evidence of a live restore.

1. Freeze every writer: bot submissions/callbacks, HTTP administration/imports, scheduled/manual publications, other JVMs and filesystem maintenance. Disabling schedules alone is insufficient. Preserve the original bundle and coherent stopped copies.
2. Set `RECOVERY_BUNDLE_ABS` to an isolated complete `backup-<UUID>` directory and `RESTORE_TARGET_ABS` to a **nonexistent**, nonoverlapping canonical absolute candidate path with an existing private parent. Do not use a live configured storage root. Examples assume paths without spaces.
3. Invoke the exact task/CLI contract: `./gradlew restoreBundle --args="--ack-all-writers-stopped true restore ${RECOVERY_BUNDLE_ABS} ${RESTORE_TARGET_ABS}"`.
4. `RestoreCli` requires exactly **five arguments**: acknowledgment flag, `true`, `restore`, bundle path and fresh target path. It does not load Spring, profiles or dotenv files and does not register a bot. Refusal/failure exits **2** with a generic preservation instruction; normal return exits **0**.
5. Successful restore yields `db/store.db` for SQLite or `jsondb/` for JsonDB, plus `sources/`, `templates/` and **`RESTORED_VERIFIED`** containing the bundle manifest hash. Schema, integrity/foreign keys, counts, decoded referenced images/geometry and file hashes must validate before this marker is written. Directories/files are 700/600 on tested POSIX platforms.
6. On failure, keep the bundle and unverified candidate. Do not resume writers, reuse that existing target or infer partial files are valid. Investigate and choose a new fresh path for another attempt.
7. Independently review anomalies and perform an isolated functional smoke/rehearsal before selecting new runtime paths. Restoring files does not provision secrets, bot identity, ImageMagick, systemd, proxy/TLS or host resources.

SQLite read-only inspection accepts known Liquibase schema **2 or 3**, validates identities/checksums and never applies a migration. Current writable startup owns schema **3**. Historical schema 2 has no audit table and is read with empty audits, not invented records. Unknown/tampered schema fails closed.

`RecoveryBundleTest` exercises actual SQLite and JsonDB reopen, new writes, audit/history/media preservation, fresh-target permissions, multipart missing/tampered parts, malformed manifests, traversal/special entries, duplicate entries, reduced real expansion/entry bounds, safe failure cleanup and retention hold behavior. Its sample render decodes restored source/template pixels, validates geometry and uses an **in-process fake `ImageRenderer`** to write/read an image and clean scratch. **No actual ImageMagick render or live Telegram recovery was rehearsed.**

### Inspect uncertain deletion stages — seven-argument contract

Use `inspectRecoveryStages` only on coherent **stopped isolated copies**. Set `INSPECTION_ENGINE` to `sqlite` or `jsondb`; `INSPECTION_DATABASE_ABS` is respectively the copied SQLite file or copied JsonDB directory. Set `INSPECTION_SOURCES_ABS` and `INSPECTION_TEMPLATES_ABS` to copied media roots and `INSPECTION_REPORT_ABS` to a new absolute report file outside all inputs.

Exact command: `./gradlew inspectRecoveryStages --args="--ack-stopped-isolated-copy true ${INSPECTION_ENGINE} ${INSPECTION_DATABASE_ABS} ${INSPECTION_SOURCES_ABS} ${INSPECTION_TEMPLATES_ABS} ${INSPECTION_REPORT_ABS}"`.

`migration.ReconciliationCli` accepts exactly **seven arguments**: flag, `true`, engine, database, sources, templates and report. All three inputs must be canonical absolute, non-symlink and mutually nonoverlapping; the report must be new with an existing parent and must not overlap inputs. Normal return exits **0**; refused/failed inspection exits **2** with a generic message and no automatic reconciliation.

The JSON report has `outcome: INSPECTED_ONLY`, `stages` and a `policy` operator hint. Each stage includes `kind` (`SOURCE`/`TEMPLATE`), relative `directory`, canonical `itemId`, `metadataPresent`, `destinationPresent`, `recommendation` and relative-file SHA-256 `hashes`. Stage names must be exactly `.delete-<canonical item UUID>-<canonical operation UUID>`.

| Metadata/original destination | Recommendation | Operator meaning |
| --- | --- | --- |
| Original destination exists, regardless of metadata | `RETAIN_DESTINATION_CONFLICT` | Keep both; never overwrite the original. Compare coherent copies and resolve explicitly. |
| Metadata exists; original destination absent | `RESTORE_CANDIDATE_OPERATOR_ACK_REQUIRED` | Retained stage may be a restore candidate; independently verify identity, bytes and metadata before acknowledging any manual action. |
| Metadata absent; original destination absent | `RETAIN_COMMITTED_DELETION_OPERATOR_REVIEW` | Possible committed deletion, not permission to purge. Retain pending operator review. |

Only `MISSING_MEDIA` tied to a recognized stage for that same collection/item is exempted during inspection. Unavailable/malformed collections, invalid/duplicate IDs, bad schema/audit/geometry, unstaged missing media and corrupt original media remain blocking. Missing/unreadable metadata **cannot** masquerade as committed deletion. Unrecognized stage names and symlinks also refuse the report.

SQLite inspection uses `RecoveryBundle.readSqliteCopy()`: it copies the main file and existing `-wal`/`-shm` into private scratch before opening a read-only `SqliteStore`, because a read-only WAL connection can still write sidecars. The reader verifies the cloned database; supplied input bytes are not opened for mutation. This assumes a coherent stopped input set, not a live three-file copy algorithm. `ReconciliationCliTest` verifies all recommendation codes for **both engines**, refusals, unchanged input hashes/entries and committed-WAL inspection without modifying supplied database/sidecar bytes.

**No automatic restore, purge, stage rename, metadata repair or writer resumption exists.** Freeze writers, preserve evidence and reconcile only after independent operator verification. Process crashes and DB/filesystem uncertainty still require this manual procedure.

## 3. Startup, health, schedules and graceful shutdown

- `RegataSimulatorApplication.onStartUpInit()` creates/checks JsonDB `users`, `templates`, `sources`, `memes` and `audits` **before** registering the bot. SQLite is initialized/migrated through its injected store/repositories, not through JsonDB collection creation. Registration failure refuses startup with a generic cause-free failure; it is not silently accepted.
- `telegram.bots.regata-simulator.registration-enabled` defaults **true**. `regata-simulator.scheduling.enabled` defaults **true**. Tests explicitly disable both and mock external boundaries; neither switch disables manual mutations or the external health contributor.
- Telegram SDK request options bound connect/pool acquisition to **3,000 ms**, socket reads to **10,000 ms** and long-poll `getUpdates` timeout to **5 seconds**. There is one application registration invocation, **no application-level registration retry loop**. These transport settings are not a hard end-to-end deadline for all SDK behavior. Host restart retries are bounded separately by systemd below.
- Anonymous `/actuator/health/liveness` includes only `livenessState`; `/actuator/health/readiness` includes only `readinessState`. They report local lifecycle state, do not call Telegram and are not full storage/render/external dependency checks. Other Actuator paths require ADMIN; only health is web-exposed by default.
- `/actuator/health/external` contains `telegramBot`. `TelegramBotHealthIndicator` returns status without identity/error details, with `regata-simulator.health.telegram-timeout-millis` default **2,000**, accepted **50..5,000**, and `regata-simulator.health.telegram-cache-millis` default **15,000**, accepted **100..300,000**. One daemon worker and a zero-capacity queue prevent accumulating work when a transport ignores cancellation. Timeout/rejection/exception yields DOWN; interruption is preserved. TTL is not a retry/durable monitoring guarantee.

| Exact property | Default/current value |
| --- | --- |
| `regata-simulator.scheduling.zone` | `America/Sao_Paulo` |
| `regata-simulator.scheduling.publish-cron` | `0 0,30 * * * *` |
| `regata-simulator.scheduling.backup-cron` | `0 15 12 * * SUN` |
| `server.shutdown` | `graceful` |
| `spring.lifecycle.timeout-per-shutdown-phase` | `30s` |
| `server.forward-headers-strategy` | `none` |

`ScheduleProperties` validates zone and six-field cron expressions. `RegataSimulatorApplicationTest`, `OperationalHealthTest`, scheduling tests and full application/filter-chain contexts cover storage-before-registration, fail-closed startup, local minimal probes, role protection, timeouts/options and schedule validation. Network availability, live registration and shutdown of real host workloads remain unverified.

## 4. Operation telemetry, credential throttling and audit history

`OperationTelemetry` supplies a UUID **operation ID** shared by nested synchronous calls, temporarily places it in MDC and restores previous context on success/failure. Micrometer records `regata.operation.duration` and `regata.operation.count` with only **`operation` and `outcome`** tags. Operations derive from the finite service/method set (unknown services become `other`), not user inputs. Outcomes include `completed`, typed application failure kinds, `failed`, `notification_failed` and `partial_import`.

Telemetry logs only ID, operation, outcome and duration—not method arguments, usernames/actors, paths, raw exception causes/messages, tokens or Telegram URLs. IDs never become high-cardinality metric tags. `OperationTelemetryTest` verifies redaction, tags and MDC release. This is instrumentation, not durable job state, end-to-end tracing or a configured metrics export/alert system; metrics endpoints are not exposed by the current YAML.

`LoginThrottleFilter` is inside Spring Security after CSRF and before credential authentication, for POST `/api/login`. `LoginAttemptLimiter` synchronously reserves SHA-256-keyed IP and username buckets and rejects over-capacity/new identities until TTL expiry. Successful authentication resets both buckets. It trusts `getRemoteAddr()`, not client-supplied forwarded headers; a real trusted-proxy deployment needs a separately reviewed policy.

| Property | Default | Bounds |
| --- | --- | --- |
| `regata-simulator.web.login.max-attempts` | 10 | 1..100 |
| `regata-simulator.web.login.window-millis` | 60,000 | 1,000..3,600,000 |
| `regata-simulator.web.login.max-entries` | 10,000 | 10..100,000 |

Throttled responses are generic **429** `application/problem+json`, no-store. Full-chain/concurrency/TTL/capacity tests are in `LoginThrottleTest`. This limiter is process-local and resets on restart; it is not distributed abuse protection and does not replace authentication/CSRF.

`ModerationAudit` records immutable audit UUID, item type/UUID, adapter-authorized actor name, optional **64-bit Telegram actor ID**, decision timestamp and APPROVED/REJECTED decision. Notification outcome starts nullable and can be recorded once as SENT/SKIPPED/FAILED. Actor identity belongs in protected audit storage, not telemetry labels.

- SQLite schema **3** adds `moderation_audit`, item/time index and triggers forbidding immutable-field changes/deletion and repeated notification outcome updates. `MetadataUnitOfWork` commits the REVIEW transition and audit insertion together. Failed audit insertion rolls back the decision and sends no notification.
- JsonDB has a separate **`audits` collection / `audits.json` sidecar** and append/single-outcome repository contract. It cannot atomically commit decision plus audit across collections; an audit-write failure can leave an applied decision requiring reconciliation. Do not extend SQLite's guarantee to JsonDB.
- Notification occurs after metadata commit. Failed notification does not undo moderation; failure to persist its audit outcome is logged generically for reconciliation, with no durable retry.
- Bundles, offline import and SQLite-to-JsonDB post-write rollback export preserve audit records. Historical no-audit schema-2/JSON inputs remain readable with empty audit history. `AuditRepositoryTest`, `OfflineStoreCliTest`, `RecoveryBundleTest` and SQLite HTTP context tests cover these contracts.

## 5. Immutable release deployment and artifact-only rollback

Current checked-in deployment is `scripts/deploy-release.sh` plus `scripts/regatasimulator.service` and `.github/workflows/release-and-deploy.yml`; it replaces the historical generated `subprocess`/log-text restart wrapper. **No remote server validation or real deployment was performed.**

- CI builds/tests/scans once, retains the executable artifact, and deploys that downloaded artifact rather than rebuilding on the host. Release identity is the full 40-character commit SHA; transfer/deployment verify SHA-256. `releases/<SHA>/app.jar` is new, mode 600, with schema metadata; managed `current` selection uses an atomic Linux `mv -Tf` symlink switch.
- The script's exact five arguments are root, release ID, SHA-256, schema version and **`artifact-only-schema-compatible`**. Example: `bash scripts/deploy-release.sh "${DEPLOY_ROOT_ABS}" "${RELEASE_COMMIT_SHA}" "${RELEASE_JAR_SHA256}" "${SQLITE_SCHEMA_VERSION}" artifact-only-schema-compatible`. This is a deployment procedure, not an offline smoke command; do not execute it against a live host casually.
- Root/release/incoming paths must be canonical and non-symlink, root must already contain `releases/` and `incoming/`, and the incoming artifact must be a new regular file. Private operator-owned root permissions are a provisioning requirement; the script does not prove every host permission/ownership policy.
- Before switching, writer stop must be confirmed by systemd MainPID **0** and inactive/failed state. Calls have bounded timeouts: stop 50 seconds, state queries 5 seconds, at most 20 stop checks, start 15 seconds; readiness makes at most 30 attempts with curl connect **2 seconds** and total **3 seconds** per attempt against `http://127.0.0.1:8080/actuator/health/readiness`.
- New-readiness failure stops writers again and selects/rechecks the previous compatible artifact while returning failure. Failed first deployment stops writers and removes `current`. Unconfirmed stop or failed previous readiness requires manual recovery.
- A previous release must declare the **same schema version**. Rollback is **only binary selection**, not database migration undo or restoration of old writes. Schema changes require a separate frozen migration/recovery procedure. Same recorded schema is a necessary check, not proof that arbitrary older binaries are semantically compatible.
- `regatasimulator.service` is a Linux user unit with a preprovisioned `%h/.config/regata/runtime.env`, `%h/.local/share/regata` root and `/usr/bin/java`. Restart-on-failure uses **5-second** delay, **3 starts per 300 seconds**; SIGTERM, `KillMode=control-group`, stop timeout **45 seconds**, UMask 0077 and filesystem/resource restrictions are explicit. Hardening/unit behavior is not verified on a real Linux host by macOS fixtures.
- Workflow variables are `SERVER_HOST`, `SERVER_USERNAME`, `SERVER_ROOT` and **pretrusted `SERVER_KNOWN_HOSTS`**; `SERVER_SSH_KEY` is supplied through the SSH agent. Strict host-key checking/BatchMode and bounded SSH are enabled. No opportunistic `ssh-keyscan`, runtime secret transfer/decryption, application log scraping or automatic environment-file creation is used.

Operators must provision the unit/user-manager lifecycle, patched Java, private root/incoming/releases, runtime environment, allowed writable data/backup paths, trusted host keys and proxy/port policy before deployment. Ensure configurable `SERVER_ROOT` matches the unit's fixed working/JAR paths (or explicitly provision a reviewed unit override); `JAVA_BIN` used by script validation must agree with the unit's actual Java executable. Current readiness URL assumes port 8080.

`src/test/js/deployment.test.cjs` exercises real shell logic with **fake Java/systemctl/curl/timeout/sleep/mv**: success, failed-new rollback, unconfirmed stop, checksum/schema/old-Java refusals, failed-first deployment and unit/workflow contracts. It emulates GNU rename semantics on macOS and proves no real service/network rollout. These fixtures cannot establish the actual server layout, supervision permissions, host-key trust or application readiness.

## 6. Dependency/security maintenance and compatibility

Current `build.gradle` and `gradle.lockfile`, not the earlier assessment's Boot-3.5 table, establish:

| Component | Current source/locked version |
| --- | --- |
| Spring Boot | **4.1.1** |
| Spring Framework | **7.0.9** |
| Tomcat | **11.0.26** |
| springdoc | **3.1.1** |
| Jackson 2 BOM/core/databind | **2.21.7** (annotations resolve as **2.21**) |
| Jackson 3 BOM/core/databind | **3.1.7** |
| Spring Security / Session | **7.1.1 / 4.1.1** |
| Xerial / Liquibase | **3.53.4.0 / 4.33.0** |
| Bundled SQLite engine | **3.53.4** |
| Gradle wrapper | **8.14.6** |
| Java release target | Temurin **21.0.12.1+1** |

Boot dependency management provides the compatible Spring family. Jackson 2 remains the persistence/Telegram and preferred HTTP converter path via `spring-boot-jackson2` and `spring.http.converters.preferred-json-mapper=jackson2`; both JSON families are patched coherently. Existing repository, migration, API/CSRF/login and isolated context tests are retained rather than assuming a BOM upgrade preserves behavior.

### Narrow native legacy ENC adapter

The Jasypt starter is removed; the native dependency is `org.jasypt:jasypt:1.9.3`. `LegacyEncryptedProperties` delegates to `PooledPBEStringEncryptor`, with **`PBEWITHHMACSHA512ANDAES_256`**, SunJCE, pool 1, **1,000 key-obtention iterations**, random salt, random IV and Base64. This deliberately preserves existing **`ENC(...)` compatibility**, not a newly recommended encryption/KDF standard. Production ciphertext was not decrypted, rewritten or validated here.

There is **no GCM, asymmetric/custom encryptor support or plaintext fallback on decryption failure**. Missing/wrong keys, malformed markers, cycles and unsupported/unknown enumerable encryptor settings fail generically without sensitive causes. Known GCM/custom alternatives are also rejected for opaque sources; unenumerable sources cannot provide arbitrary-key enumeration guarantees. Ordinary higher-precedence plaintext configuration retains Spring precedence; that is not fallback from failed ciphertext.

`EncryptedPropertyFailure` deliberately extends **`Error`**, with a generic message and **no nested cause**. Boot 4.1.1's non-enumerable property mapper catches `Exception` and can try lower-precedence sources; an ordinary runtime exception would therefore permit unsafe fallback. The synthetic opaque-source Binder regression proves this failure escapes that fallback; it does not request JVM termination or expose ciphertext/key details. The flagged starter/GCM dependency is removed from the graph, not whitelisted/suppressed.

`LegacyEncryptedEnvironmentPostProcessor` runs after ConfigData; `LegacyEncryptedPropertyInitializer` covers later sources/configuration parsing before binding. `META-INF/spring.factories` discovers both. Wrappers retain source order/enumeration/contains/origin/native relaxed lookup and **the `MapPropertySource` type plus original backing map** required by Boot test customizers. Decryption is lazy, source ciphertext stays untouched and no plaintext cache is introduced.

`LegacyEncryptedPropertiesTest`/`LegacyEncryptedBootstrapTest` use only synthetic native-PBE fixtures and isolated Boot lifecycles: profile/placeholder/typed binding, attached property adapter, late sources, map contract, random salt/IV fixtures, concurrency, unsupported configuration and redacted fail-closed behavior. They do not establish stronger encryption or compatibility with untested custom historical formats.

### Wrapper, JDK and fail-closed scan evidence

The configured Gradle 8.14.6 distribution SHA-256 is **`7988ed071b2a07900e2ec715fca15c6ab72bce6db433af301fa7fa7e9407bd24`**, verified against the official distribution checksum in implementation verification. Distribution validation and CI wrapper validation are configured. Strict dependency locking covers **runtimeClasspath only**, not test/plugin graphs.

`.tool-versions` and CI target Temurin **21.0.12.1+1**; `verifyMaintenanceJava` rejects Java 21 older than **21.0.12.1** for CI/release. The earlier installed OpenJDK **21.0.2** build passed, but final maintenance verification used the isolated patched JDK and proved compiler/test JVM identity. Actual host Java and future CI executions still require validation; they are not implied by a local pass.

Official latest Adoptium API download: **`/private/tmp/regata-phase5-jdk-yCePxA/jdk-21.0.12.1+1/Contents/Home`**. Verified archive SHA-256: **`44db0f08196daf19a47f90d13388b0c943b67663cb537f998fe29e836fa842ce`**. No global installation, `.tool-versions` override outside the repository, dotenv or production-data changes were made.

Isolated verification command (reproducible explicit-toolchain procedure): `JAVA_HOME=/private/tmp/regata-phase5-jdk-yCePxA/jdk-21.0.12.1+1/Contents/Home PATH=/private/tmp/regata-phase5-jdk-yCePxA/jdk-21.0.12.1+1/Contents/Home/bin:$PATH ./gradlew --no-daemon -Porg.gradle.java.installations.paths=/private/tmp/regata-phase5-jdk-yCePxA/jdk-21.0.12.1+1/Contents/Home -Porg.gradle.java.installations.auto-detect=false -Porg.gradle.java.installations.auto-download=false verifyMaintenanceJava clean build --info`. The `--info` verification establishes **compile toolchain and Gradle Test Executor** paths under that Home, not merely the launcher version. A future operator must provide their own isolated absolute JDK path; the temporary download is not a durable host provisioning path.

`dependencyInventory` retains resolved runtime coordinates and embedded LICENSE/NOTICE/COPYING files under `build/dependency-security/`; missing embedded notices explicitly require upstream POM review, not presumed legal clearance. CI retains this evidence for **30 days**.

`dependencyScan` uses the public OSV Maven batch API with sequential **50-package batches**, at most **three attempts**, **15-second** request/stream deadlines, **4 MiB** response limit and strict incomplete/pagination refusal. It removes stale reports and fails for findings or incomplete results. No NVD key/dotenv is required; an absolute Node executable can be supplied with `-PnodeExecutable`. Release/PR workflows gate on maintenance Java, clean build and scan, plus separate Node fixtures.

**Actual current OSV: `COMPLETE`, 138 coordinates, 0 findings, `2026-10-10T05:30:52.749Z`**, saved at `build/dependency-security/osv-report.json`. The earlier 118/23 and 122/7 scans remain historical in the [dependency assessment](phase-5-dependency-assessment.md); their blocks are resolved by the separate Boot 4/native-PBE migration and coherent JSON patches, not suppression. This is local Maven maintenance acceptance, not a security-approved production release. OSV does not cover unknown advisories, reachability, JDK/native SQLite/ImageMagick/CDN risk or guarantee zero vulnerabilities; notices/inventory are not legal clearance.

## 7. Final verification and scoped acceptance

Final verification evidence for runtime commit `679884e` is recorded below. Earlier Phase 4 totals remain historical, not relabeled as Phase 5 totals.

| Final evidence | Result |
| --- | --- |
| Explicit-only patched-JDK `verifyMaintenanceJava clean build --info` | **Passed**, Temurin **21.0.12.1+1** used by compiler **and Gradle Test Executor**; autodetect/autodownload disabled |
| Full clean-build XML | **828 JUnit cases / 42 suites / 0 failures / 0 errors / 0 skips** |
| Separate Node suite | **18 tests passed**: web-security **5**, deployment **8**, dependency-scan **5** |
| Actual public OSV rerun | **COMPLETE / 138 coordinates / 0 findings**, **2026-10-10T05:30:52.749Z** |
| Earlier OpenJDK 21.0.2 build | Passed compatibility check; **not** maintenance-release gate evidence |
| Runtime/data/live boundaries | TempDir JsonDB/SQLite, synthetic PBE/ImageIO, fake Telegram/renderer/host commands; **no production data, app/bot startup, real ImageMagick or server deployment** |

| Phase 5 acceptance | Implemented/rehearsed evidence | Remaining boundary |
| --- | --- | --- |
| Consistent recoverable backup | Cooperative barrier, verified WAL-aware `VACUUM INTO`, JsonDB export, copied media, hashes/limits/retention | Single JVM/cooperating writers; no power-loss guarantee or verified independent failure domain |
| Fresh-directory recovery | SQLite/JsonDB reopen, integrity/foreign keys, counts/hashes, private permissions, decoded media and fake render | No real ImageMagick, bot, whole-host restore or production-data drill |
| Safe development operations | Explicit startup/schedule switches, mocked boundaries, isolated stores/probes and bounded health | Manual operations still mutate; live network/registration/shutdown not verified |
| Maintenance | Separate Boot-4/native-PBE/JSON slice `679884e` and fixtures, wrapper SHA, runtime lock, patched-JDK compile/test proof and complete zero-finding OSV | Future CI/host, native/JDK/CDN support and license/legal reviews remain separate |
| Detectable failed release | Checked artifact, confirmed writer stop, local readiness, schema-compatible artifact rollback fixtures | Linux host/unit/env/SSH trust/runtime paths/real rollout unverified; never DB migration undo |
| Visibility/audit | Redacted operation IDs/metrics, bounded login limiter, atomic SQLite audit and recovery preservation | JsonDB cross-collection atomicity, durable retries, distributed limits and alerts are not implemented |

### Separate operator prerequisites before live adoption (not performed here)

1. Review runtime upgrade commit `679884e`; all commits are local, with no push or deployment. Re-run the maintenance gates for the exact release artifact in CI and verify the host's patched Java.
2. Review native/JDK/ImageMagick/CDN advisories, retained-library support and license obligations independently of the Maven OSV result.
3. Provision independent private backup storage and verify capacity/failure-domain separation, single-host/local disk, Linux user-unit lifecycle, runtime environment, root/Java/writable-path agreement, port/proxy/TLS policy, pretrusted host keys and schema-compatible prior binary. Actual unit provisioning is required; macOS fixtures do not install/test a Linux service.
4. Rehearse fresh isolated restore using the actual intended ImageMagick/runtime before live cutover; approve writer freeze, anomalies, post-write rollback/reconciliation and operator sign-off. Never start the bot or use production paths as routine verification.
5. Preserve [Phase 4 results](phase-4-sqlite-results.md) unchanged as history. Its backup block is superseded **only by configured Phase 5 capture**, not by calling the old archive helper coherent. Crash uncertainty still requires stopped-copy inspection and manual reconciliation, never automatic purge/resumption.

The completed local operations scope is a tested implementation and offline recovery foundation. Live production adoption, whole-application readiness, crash reconciliation, external delivery and deployment guarantees remain explicitly separate.