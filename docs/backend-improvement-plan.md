# Backend improvement plan

Date: 2026-10-06. Updated: **2026-10-10**. Status: **Phases 0–5 are implemented for their explicitly scoped local/offline acceptance**, including the SQLite implementation/migration rehearsal and Phase 5 recovery/security-maintenance candidate. Production cutover, live deployment and whole-host recovery approval are not performed. [Phase 0 results](phase-0-results.md), [callback-safety results](phase-1-callback-safety-results.md), [Phase 1 reliability results](phase-1-reliability-results.md), [Phase 2 web security results](phase-2-web-security-results.md), [Phase 3 service results](phase-3-service-results.md), [Phase 4 SQLite results](phase-4-sqlite-results.md), and [Phase 5 operations/runbook](phase-5-operations-results.md) record implementation, verification, and limits.

Read [backend review](backend-review.md) for evidence/tradeoffs and [project map](project-map.md) for the current structure. The goal is a simpler, reliable modular monolith, not a rewrite or a distributed system.

## Highest progress checkpoint — Phase 5 complete locally/offline, 2026-10-10

- **Recovery:** cooperative single-JVM media boundary, committed-WAL-aware `VACUUM INTO`, five-collection JsonDB export, copied media, bounded multipart manifests/checksums, complete local retention and validated fresh-target restore. Stopped-copy reconciliation reports recommendations without restoring/purging. JsonDB remains default; SQLite is opt-in, with schema-3 moderation audit and no automatic cutover.
- **Operations:** storage-before-registration, configurable bot/schedules/zone/shutdown, local lifecycle probes and protected bounded external health; redacted operation IDs/duration/outcome metrics, bounded process-local login throttling and audit preservation. Checked-in Linux supervision/deployment scripts validate artifacts, stop writers and test readiness/schema-compatible artifact-only rollback.
- **Separate security-maintenance slice:** Boot **4.1.1**, Framework **7.0.9**, Security **7.1.1**, Session **4.1.1**, Tomcat **11.0.26**, Jackson 2 **2.21.7** / 3 **3.1.7**, springdoc **3.1.1**; SQLite **3.53.4** / Xerial **3.53.4.0**, Liquibase **4.33.0** retained. Gradle **8.14.6** has official distribution checksum validation. The native PBE bridge removes the flagged starter, retains synthetic legacy ENC compatibility and fails closed; this is not a service rewrite or new KDF recommendation.
- **Final verification:** full clean build **828 JUnit cases / 42 suites**, zero failures/errors/skips; **18 Node tests** across web-security/deployment/dependency-scan. Patched Temurin **21.0.12.1+1** passed `verifyMaintenanceJava clean build --info` with explicit-only toolchain paths and autodetect/autodownload disabled; compiler **and Gradle Test Executor** used the isolated JDK. The earlier OpenJDK 21.0.2 build also passed, but is not release-gate evidence.
- **Actual OSV rerun:** **COMPLETE / 138 coordinates / 0 findings**, timestamp **2026-10-10T05:30:52.749Z**, saved in `build/dependency-security/osv-report.json`. Prior 23- then 7-finding scans are historical and resolved for this Maven graph; no advisory suppression. See [dependency assessment](phase-5-dependency-assessment.md) for hashes, limits and licenses.
- **Local history:** seven original Phase 5 slices plus `54d9ad0` reconciliation and `4ea9544` quality follow-up. Runtime upgrade locally committed as `679884e` (`build: migrate supported Spring runtime and preserve legacy encrypted properties`); all commits are local, with no push or deployment.
- **Scope boundaries:** temporary stores, synthetic ciphertext/ImageIO and fake Telegram/renderer/host commands only; no production data, app/bot startup, actual ImageMagick render, deployment or global JDK/env change. Single-host/cooperative boundaries, manual crash reconciliation, non-atomic JsonDB, native/CDN review, legal clearance and real Linux unit/runtime provisioning remain separate operator checks, not incomplete local acceptance.

## Phase 4 progress checkpoint — 2026-10-09 (historical)

- **Phase 4 offline completion:** patched Xerial JDBC with measured SQLite 3.53.4, Liquibase 4.33.0 versioned schema, four JDBC adapters, literal normalized filters/deterministic SQL paging, per-connection WAL/FK/FULL/busy bounds, coordinated publication/submission metadata transactions, conditional REVIEW writes, immutable ordered history identities and reversible media deletion staging. JsonDB remains the explicit compatibility default; SQLite is opt-in.
- **Migration/rollback:** standalone no-Spring raw-JSON audit/import into a new file, all-record anomaly reports and hashes, full-field/integrity reconciliation, then read-only SQLite export of post-migration writes into new JsonDB/media bundles. Synthetic ImageIO/TempDir rehearsal reopens actual JsonDB; no user data or deployment settings were touched. See [operator procedure and policies](phase-4-sqlite-results.md).
- **Historical Phase 4 verification:** focused tests, clean build and independently counted XML **725 cases / 32 suites**, zero failures/errors/skips, plus **5 Node cases**. At that checkpoint SQLite legacy backup failed closed. Configured Phase 5 capture now supersedes that block; automatic crash recovery is not claimed. Single-host/local-disk deployment suitability and live cutover remain operator prerequisites.

### Phase 3 checkpoint (historical)

- **Phase 3 completion:** typed JsonDB repositories, shared Telegram/media/render boundaries, central application failures, explicit preview/publication/origins and direct HTTP/Telegram/scheduler services. All 52 obsolete production/test paths are physically removed; active regression replacements and temporary-storage submission→callback→HTTP moderation integration remain. The [removal manifest](phase-3-cleanup-removal-manifest.md) is historical and resolved.
- **Current verification:** focused services/rendering/process/repository/context tests and `./gradlew clean build` passed on Java 21.0.2 / Gradle 8.6; XML **702 tests / 25 suites**, zero failures/errors/skips; separate Node tests **5/5**. Intermediate 914/40 included 15 removed duplicate suites. Local cleanup only; no environment/data/live effects, push or deployment.
- **Next phase:** Phase 4 SQLite implementation and rehearsed migration, subject to its prerequisites below. Phases 4–5, coherent restore, cross-adapter transactions, durable retries and production readiness remain unclaimed.

### Earlier Phase 2 checkpoint (historical)

- **Phase 2 completion:** validated explicit CORS/frame/session-cookie configuration, POST-only admin tasks, ROLE_ADMIN/BCrypt, Security 6.2 XOR CSRF and existing-client integration, full-filter-chain tests, validated APIs/generic ProblemDetail, minimized DTOs/byte-detected image MIME, typed import HTTP report. See [web security results](phase-2-web-security-results.md).
- **Current verification:** `./gradlew clean build`, XML **735 tests / 33 suites**, zero failures/errors/skips; separate Node browser tests **5/5**. Local commits only; no runtime effects/data access. Actual hosting and authenticated Telegram iframe cookie compatibility remain unverified.
- **Next phase:** typed application services with JsonDB retained (Phase 3). Phases 3–5, coherent restore, cross-adapter transactions, durable retries and production readiness remain unclaimed.

### Earlier Phase 1 checkpoint (historical)

- **Phase 1 completion:** isolated render ownership, bounded processes, capacity-aware history/birthday fallback, fail-closed bounded/exclusive workflows, actual image/geometry/CSV validation, upload/import/ZIP compensation, bounded imported transfers, and null-origin/moderation notification recovery. See [reliability results](phase-1-reliability-results.md) for acceptance mapping and local commits.
- **Final verification:** targeted suites and `./gradlew clean build`; XML **649 tests / 29 suites**, zero failures/errors/skips. Dedicated Sonar analysis was unavailable in this session; editor findings were addressed, with workspace-import diagnostics remaining. No live effects or runtime data access.
- **Next phase:** configuration/web security (Phase 2), not more proposed Phase 1 work. Cross-adapter transactions, coherent restore, durable retries, and production readiness remain explicitly unclaimed.

### Earlier callback-only checkpoint (historical)

- **Complete:** owner/REVIEW/preview-context validation, strict callback parsing, safe generic rejection, persisted nullable preview binding, successful-confirmation consumption, and process-local concurrent replay protection.
- **Verified:** focused regression suites and `./gradlew clean build`, with **590 cases across 24 suites**, zero failures/errors/skips. Changed Java files were explicitly reanalyzed with Sonar; returned rule findings were fixed, with Java workspace-import warnings remaining rather than a verified server-wide quality-gate result.
- **Compatibility:** original submission messages and publication weights/history are preserved; binding writes update fields rather than stale full entities. Legacy JSON still loads, but legacy preview buttons without trust metadata are intentionally rejected. No live data was changed.
- **Next:** isolated rendering scratch directories and bounded subprocess execution. Capacity-aware selection, wider failure recovery, web security, typed services, and SQLite remain pending. This is not completion of all Phase 1 acceptance criteria.

## Earlier progress checkpoint — 2026-10-07 (historical)

- **Complete:** backend review/project guidance, isolated live development smoke tests, Phase 0 characterization and test seams, and the documented Sonar-driven cleanup. See [live test results](live-test-results.md) and [Phase 0 results](phase-0-results.md) for evidence and limits.
- **Fresh verification:** `./gradlew clean build` passed on Java 21.0.2; test XML reports **300 executed cases across 23 suites**, zero failures/errors/skips. Current editor diagnostics report no errors. This does not establish a project-wide SonarQube scan or server quality-gate result.
- **Local setup:** `.env.dev` exists, is Git-ignored, and is not tracked. The original live-test report's unignored-file warning is historical, not its current Git status.
- **Still pending:** callback actor/status protection, per-job rendering isolation, process timeouts/exit-code checks, capacity-aware selection, failure recovery, web security, typed services, SQLite, and dependency/deployment work. A green characterization suite does not mean these defects are fixed.
- **Ready to begin Phase 1**, not to claim production readiness or start the SQLite cutover. The next slice is callback safety below; no application was started or external data accessed during this readiness check.

## Phase 0 — Protect existing behavior

Completed: isolated startup/context, submission/preview/publication, callback/moderation, import/backup, and deterministic edge-case tests. Known failures were characterized at Phase 0, not fixed by that phase; later phases update expectations with fixes. See [Phase 0 results](phase-0-results.md) for historical evidence.

Work against temporary storage and mocked Telegram, never the production paths/tokens. Preserve the Portuguese user-facing messages unless a product change is intentional.

1. Create characterization tests for source/template submission, previews, publish selection, callbacks, moderation, CSV import, and backup sequencing.
2. Add explicit edge tests for one-item pools with history, insufficient sources, missing single-area templates, sparse/duplicate geometry indices, malformed CSV numbers, null message metadata, and notification failure.
3. Isolate external bot registration/scheduling in tests before adding a real application-context test; the current `@PostConstruct` would otherwise call Telegram.
4. Add controllable Clock/randomness and fake image-rendering/Telegram boundaries as needed; do not test by posting live memes.

Acceptance: baseline 12 tests remain green; new tests describe known failures and intended contracts. Desired security behavior should be tested alongside its fix rather than enshrined as existing behavior.

## Phase 1 — Safety and reliability fixes

Items 1–5 are implemented as scoped in [callback-safety results](phase-1-callback-safety-results.md) and [reliability results](phase-1-reliability-results.md); cross-adapter transaction/recovery guarantees are still deferred. The list below remains the acceptance scope, not a pending implementation queue.

Small independent changes, each with a regression test:

1. Enforce actor ownership, review status, expected callback message association, valid callback data, and acknowledgment. Keep submitter confirmation distinct from administrator approval.
2. Give rendering jobs isolated scratch directories; extract a tested subprocess runner with timeout, exit-code checks, bounded stdout/stderr handling, process termination, and cleanup on every path.
3. Make recent-history exclusion capacity-aware; define fallback for birthday filters and explicit results when no media can be selected.
4. Centralize application failure reporting without marking errors as successful workflow completion. While the runner exists, fix missing-annotation handling, fail on missing nonterminal registration, bound transition count, and define route matching policy.
5. Correct partial-file cleanup and collect failed import-row results. Validate actual image bytes, size/pixel bounds, required CSV fields, and geometry semantics.

Acceptance: failed jobs leave no shared render artifacts; two concurrent jobs using the same template cannot overwrite each other; timeouts and error paths are visible and bounded. Selection works with minimal viable datasets.

## Phase 2 — Configuration and web security

Items 1–6 are implemented as scoped in [web security results](phase-2-web-security-results.md). The list below is retained as acceptance scope, not pending work. Hosting was inspected through client/source contracts only, not a live server; cross-origin grants default to denied. Authenticated Telegram iframe behavior remains explicitly unverified rather than weakening SameSite speculatively.

1. Establish actual hosting topology. Remove cross-origin API grants if unnecessary, or introduce validated web/CORS properties with explicit per-environment origins and one security-integrated CORS source.
2. Configure Spring Session cookies through `CookieSerializer`; prefer host-only scope and explicitly test Secure/SameSite requirements for local development and supported Telegram clients.
3. Convert admin publish/backup GET endpoints to POST. Update any clients with the same change; do not retain a mutating GET compatibility alias.
4. Enable CSRF, supply a compatible token acquisition mechanism, and update login/logout/API request code. These small frontend integration changes are necessary for backend security, not a frontend redesign. Phase 2 used Security 6.2; Phase 5 retests these contracts on 7.1.1.
5. Add Jakarta validation and centralized `ProblemDetail` responses; return 401/403 JSON for API auth failures while preserving browser page redirects where appropriate.
6. Minimize API response DTOs and serve correct image MIME types. Record any response-contract changes before changing gallery clients.

Tests: full-chain MockMvc preflight for allowed/disallowed/lookalike origins; no-Origin/same-origin behavior; actual API auth; CSRF missing/invalid/valid tokens; login and token refresh after authentication/logout; invalid pagination/body; missing IDs; already-reviewed items.

Acceptance: configuration varies without recompiling Java; preflight does not bypass actual authentication; cookie-based mutations require CSRF; existing supported login/embed flows still work.

## Phase 3 — Typed application services, with JsonDB retained

Completed for the scoped acceptance items below: repository isolation, shared media/render/Telegram boundaries, central application failures and direct service orchestration/production adapters. Active regression replacements and full-chain submission→preview→callback→HTTP moderation coverage were implemented in local commit `b30a42c`; all obsolete workflows/routes/duplicate harnesses and the compatibility exception are now physically removed (52 verified deletions). BackupService retains only its typed path with final injected fields. Final clean build: **702 cases / 25 suites**, zero failures/errors/skips, plus **5 passing Node cases**. The [removal manifest](phase-3-cleanup-removal-manifest.md) is historical and resolved. See [final service results](phase-3-service-results.md); acceptance is based on active contracts and integration, not just disabled beans. The numbered list is retained acceptance scope, not pending work.

1. Introduce domain-specific repository interfaces backed by the current JsonDB implementation. Start with operations needed by the use cases, not a generic CRUD abstraction.
2. Extract `MediaStorage`, `ImageRenderer`, and `TelegramGateway` boundaries. Share these components across previews and publication.
3. Migrate ping/report/backup orchestration first, then meme publish/preview, then source/template submission and moderation. Use explicit typed method calls and request/result records.
4. Have HTTP, Telegram, and scheduler adapters call the same application services. Remove synthetic `Update` construction and null-Update mode selection from business logic.
5. Keep old steps only as temporary delegates where useful. Remove enums, bag keys, annotation registration, and the runner once there are no callers. Do not build a new service class per old step.

Acceptance: repository/transport details stay outside use-case orchestration; preview versus publish is explicit; all characterization tests pass; direct service tests verify required order and error propagation.

Risk control: no database engine change in this phase. Logic extraction and behavior changes should be separate commits where practical.

## Phase 4 — SQLite implementation and rehearsed migration

Implemented for the requested **offline candidate/rehearsal scope**, not production cutover. [Phase 4 results](phase-4-sqlite-results.md) record schema/configuration/product policies, local commits, synthetic import and post-write rollback verification. JsonDB remains default; no production path is automatically imported. The lists below retain acceptance/cutover guidance, not a claim that live steps 1/2/6/7 were executed. Confirm actual single-host local-disk deployment suitability and approve the documented candidate policies before any live adoption.

### Implement and test

1. Select patched Xerial JDBC and a SQLite-compatible versioned migration tool. Verify bundled engine version, migration support, Java/runtime compatibility, and package licensing.
2. Create the schema with IDs preserved, nullable legacy origin fields, positive weights, valid review statuses, normalized uniqueness, ordered area/source relationships, and an explicit deletion/history policy.
3. Implement parameterized Spring JDBC repositories and deterministic paginated/count queries. Avoid guessing an ORM dialect is necessary.
4. Set WAL if chosen, foreign keys per connection, bounded busy timeout, deliberate synchronous durability, and a small tested connection pool.
5. Keep transactions limited to database mutations: coordinated metadata writes, moderation/audit, and weight/history updates. Do not include network/image work in them.
6. Address file/Telegram consistency with staged storage, cleanup/reconciliation, and explicit send/notification outcomes. Add a small persisted outbox/job record only if required for the selected retry guarantees.

Tests: shared repository contracts on both stores during transition; file-backed SQLite migration from empty and earlier schema; two-connection contention; unique-name concurrency; Unicode normalization; rollback/commit behavior; foreign keys on every connection; ordered geometry; deterministic paging; imported null-origin metadata; history cap and deletion rules.

### Rehearse import and cutover

1. Stop all mutations for the final snapshot: bot submissions/callbacks, HTTP administration/imports, and scheduled publication. A scheduler flag alone is insufficient.
2. Back up JsonDB and media coherently, with a manifest/checksums. Validate restore before changing stores.
3. Run an explicit offline export/import command against copied data, not normal startup. Audit duplicates, nulls, broken references, and missing files; report them rather than silently dropping records.
4. Import into a new SQLite file in controlled transactions, preserving IDs, weights, status, timestamps/origin information where known, area order, and meme source order.
5. Compare collection/row counts, normalized field values, media hashes, and representative preview/selection/moderation behavior with fake Telegram. Run SQLite integrity and foreign-key checks.
6. Point a non-production instance at the new store and exercise the full application with bot/scheduler effects isolated.
7. Cut over during a write freeze, retaining the original snapshot. Release writes only after validation. Never let stage and prod use the same storage paths or bot identity.

Acceptance: repeatable migration report, successful restore drill, no silent data loss, preserved functional behavior, tested contention, and an operator-readable rollback procedure.

Rollback caveat: before new writes, restore the old configuration/store. After new writes, reconcile/export those writes before rollback or explicitly accept their loss. Do not simply revert the JAR and point it at stale JSON data.

## Phase 5 — Recovery, dependency, and deployment maintenance

**Completed for the local/offline candidate scope.** The six steps below are retained acceptance scope, not a pending queue. See [operations results/runbook](phase-5-operations-results.md) for exact commands, tests, failure policies and operator boundaries.

1. Produce a SQLite-consistent snapshot using the backup API or verified VACUUM INTO; coordinate media snapshot/manifest, archive-size checks, exception-safe cleanup, retention, and an independent backup destination where appropriate.
2. Exercise restoration into fresh directories with production-like permissions; verify database integrity, file availability, and sample rendering.
3. Make bot/scheduler startup configurable, initialize storage before registering the bot, and separate local liveness from bounded external readiness. Make cron zone and relevant scheduling/policy values configurable.
4. Upgrade old dependencies in a dedicated tested change; use Boot dependency management for compatible Spring libraries, patch Java 21, add wrapper integrity verification and dependency vulnerability checks. Do not combine a major Boot upgrade with the database cutover.
5. Replace the historical undocumented `subprocess` wrapper with checked-in systemd supervision/artifact deployment, graceful shutdown, readiness smoke checks, pinned/trusted host keys and failed-deployment rollback fixtures. Actual server provisioning/verification remains an operator prerequisite.
6. Add operation IDs, duration/outcome metrics, redacted logs, and moderation actor/time auditing. Keep operational complexity proportional to this small app.

Acceptance mapping (all six local implementation steps complete):

| Step | Completed local/offline evidence | Separate live/operator check |
| --- | --- | --- |
| 1 — Consistent backup | WAL-aware snapshot plus copied media under cooperative barrier; hashes, size bounds, cleanup, count retention | Provision private independent destination/failure domain; stop noncooperating writers |
| 2 — Restore | Fresh SQLite/JsonDB reopen, integrity/FK/count/hash checks, 700/600 permissions, decoded images/geometry and fake rendering | Actual intended ImageMagick/runtime and whole-host restore drill |
| 3 — Safe startup | Storage first; explicit switches, minimal local probes, bounded protected external health, configurable cron/zone/graceful shutdown | Real Telegram/network/host shutdown; switches do not disable manual writes |
| 4 — Maintenance | Separate Boot 4/native-PBE slice; patched JDK compile/test proof, wrapper integrity, runtime lock, complete zero-finding OSV | Native/JDK/CDN advisory review, upstream support and legal obligations; future CI/host gate |
| 5 — Failed deployment | Checked artifact/checksum, confirmed stop, bounded readiness, schema-compatible artifact rollback fixtures; `subprocess` removed | Provision/test actual Linux unit/env/root/Java/SSH trust; never undo database writes via binary rollback |
| 6 — Visibility/audit | Redacted IDs/duration/outcomes, low-cardinality metrics, limiter, atomic SQLite REVIEW/audit and recovery preservation | JsonDB non-atomic audit reconciliation, monitoring/alerts and manual crash recovery |

Acceptance is a source-complete, tested recovery/maintenance candidate, **not production approval**. No exactly-once delivery, crash-proof distributed transaction, real server rollout or native ImageMagick verification is claimed.

## First implementation slice

**Completed on 2026-10-09:** source/template preview callback authorization and status checks. The acceptance criteria below describe that historical slice; see [results](phase-1-callback-safety-results.md) for verification and limits. Its `SendMemeStep`/route/workflow names describe the original implementation, physically removed in Phase 3; current typed services/adapters preserve the tested contracts. Rendering was a separate slice.

Acceptance for that first slice:

1. Validate callback UUID/type/action and safely handle absent or inaccessible callback messages. Invalid, missing-item, unauthorized, and stale-state callbacks are acknowledged without deleting files/records, clearing another user's keyboard, or forwarding a submission for approval.
2. Verify the submitter identity from the original stored submission and require REVIEW status for submitter confirm/cancel. Preserve the distinction: confirmation requests administrator approval; it never sets APPROVED itself. Define fail-closed behavior when legacy origin/author metadata cannot prove ownership.
3. Bind callbacks to the expected preview context using the implemented nullable preview chat/message metadata, separately from the original upload Message. Capture preview identity from the send response in `SendMemeStep`; do not overwrite the original submission or infer legacy binding from incoming callbacks.
4. Add adversarial/replay cases alongside existing valid-owner cases in `ReviewCallbackStepsTest` and route/workflow tests. Update known-failure expectations with their fixes, preserving callback formats and Portuguese messages where compatible.
5. Pass targeted tests and `./gradlew clean build`; explicitly reanalyze changed Java files for Sonar findings. Do not suppress rules, include production data, migrate storage, or redesign the workflow framework in this slice.

The subsequent rendering, selection, failure-reporting, validation, and recovery slices are implemented; see [reliability results](phase-1-reliability-results.md). Phase 2 CORS/cookie/CSRF/API boundaries, Phase 3 typed services/repository isolation/obsolete-workflow removal, Phase 4 SQLite/offline rehearsal and **Phase 5 local/offline recovery/maintenance** are complete; see [web security results](phase-2-web-security-results.md), [service results](phase-3-service-results.md), [SQLite results](phase-4-sqlite-results.md) and [operations results](phase-5-operations-results.md). Confirm live prerequisites separately; never infer an automatic production cutover.

Each phase is a sequence of small PRs, not one giant PR. Estimates should follow the data audit and required test work; the existing green suite does not justify calling a whole migration low-risk or a two-day task.