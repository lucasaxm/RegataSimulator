# Backend improvement plan

Date: 2026-10-06. Updated: 2026-10-09. Status: **Phases 0 and 1 implemented for their scoped acceptance items**; Phases 2–5 remain proposed. [Phase 0 results](phase-0-results.md), [callback-safety results](phase-1-callback-safety-results.md), and [Phase 1 reliability results](phase-1-reliability-results.md) record implementation, verification, and recovery limits.

Read [backend review](backend-review.md) for evidence/tradeoffs and [project map](project-map.md) for the current structure. The goal is a simpler, reliable modular monolith, not a rewrite or a distributed system.

## Progress checkpoint — 2026-10-09

- **Phase 1 completion:** isolated render ownership, bounded processes, capacity-aware history/birthday fallback, fail-closed bounded/exclusive workflows, actual image/geometry/CSV validation, upload/import/ZIP compensation, bounded imported transfers, and null-origin/moderation notification recovery. See [reliability results](phase-1-reliability-results.md) for acceptance mapping and local commits.
- **Final verification:** targeted suites and `./gradlew clean build`; XML **649 tests / 29 suites**, zero failures/errors/skips. Dedicated Sonar analysis was unavailable in this session; editor findings were addressed, with workspace-import diagnostics remaining. No live effects or runtime data access.
- **Next phase:** configuration/web security (Phase 2), not more proposed Phase 1 work. Cross-adapter transactions, coherent restore, durable retries, and production readiness remain explicitly unclaimed.

### Earlier callback-only checkpoint (historical)

- **Complete:** owner/REVIEW/preview-context validation, strict callback parsing, safe generic rejection, persisted nullable preview binding, successful-confirmation consumption, and process-local concurrent replay protection.
- **Verified:** focused regression suites and `./gradlew clean build`, with **590 cases across 24 suites**, zero failures/errors/skips. Changed Java files were explicitly reanalyzed with Sonar; returned rule findings were fixed, with Java workspace-import warnings remaining rather than a verified server-wide quality-gate result.
- **Compatibility:** original submission messages and publication weights/history are preserved; binding writes update fields rather than stale full entities. Legacy JSON still loads, but legacy preview buttons without trust metadata are intentionally rejected. No live data was changed.
- **Next:** isolated rendering scratch directories and bounded subprocess execution. Capacity-aware selection, wider failure recovery, web security, typed services, and SQLite remain pending. This is not completion of all Phase 1 acceptance criteria.

## Progress checkpoint — 2026-10-07

- **Complete:** backend review/project guidance, isolated live development smoke tests, Phase 0 characterization and test seams, and the documented Sonar-driven cleanup. See [live test results](live-test-results.md) and [Phase 0 results](phase-0-results.md) for evidence and limits.
- **Fresh verification:** `./gradlew clean build` passed on Java 21.0.2; test XML reports **300 executed cases across 23 suites**, zero failures/errors/skips. Current editor diagnostics report no errors. This does not establish a project-wide SonarQube scan or server quality-gate result.
- **Local setup:** `.env.dev` exists, is Git-ignored, and is not tracked. The original live-test report's unignored-file warning is historical, not its current Git status.
- **Still pending:** callback actor/status protection, per-job rendering isolation, process timeouts/exit-code checks, capacity-aware selection, failure recovery, web security, typed services, SQLite, and dependency/deployment work. A green characterization suite does not mean these defects are fixed.
- **Ready to begin Phase 1**, not to claim production readiness or start the SQLite cutover. The next slice is callback safety below; no application was started or external data accessed during this readiness check.

## Phase 0 — Protect existing behavior

Completed: isolated startup/context, submission/preview/publication, callback/moderation, import/backup, and deterministic edge-case tests. Known failures remain characterized, not fixed. See [Phase 0 results](phase-0-results.md).

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

1. Establish actual hosting topology. Remove cross-origin API grants if unnecessary, or introduce validated web/CORS properties with explicit per-environment origins and one security-integrated CORS source.
2. Configure Spring Session cookies through `CookieSerializer`; prefer host-only scope and explicitly test Secure/SameSite requirements for local development and supported Telegram clients.
3. Convert admin publish/backup GET endpoints to POST. Update any clients with the same change; do not retain a mutating GET compatibility alias.
4. Enable CSRF, supply a compatible token acquisition mechanism, and update login/logout/API request code. These small frontend integration changes are necessary for backend security, not a frontend redesign. Check APIs against the chosen Spring Security version; newer documentation shortcuts may not exist in 6.2.
5. Add Jakarta validation and centralized `ProblemDetail` responses; return 401/403 JSON for API auth failures while preserving browser page redirects where appropriate.
6. Minimize API response DTOs and serve correct image MIME types. Record any response-contract changes before changing gallery clients.

Tests: full-chain MockMvc preflight for allowed/disallowed/lookalike origins; no-Origin/same-origin behavior; actual API auth; CSRF missing/invalid/valid tokens; login and token refresh after authentication/logout; invalid pagination/body; missing IDs; already-reviewed items.

Acceptance: configuration varies without recompiling Java; preflight does not bypass actual authentication; cookie-based mutations require CSRF; existing supported login/embed flows still work.

## Phase 3 — Typed application services, with JsonDB retained

1. Introduce domain-specific repository interfaces backed by the current JsonDB implementation. Start with operations needed by the use cases, not a generic CRUD abstraction.
2. Extract `MediaStorage`, `ImageRenderer`, and `TelegramGateway` boundaries. Share these components across previews and publication.
3. Migrate ping/report/backup orchestration first, then meme publish/preview, then source/template submission and moderation. Use explicit typed method calls and request/result records.
4. Have HTTP, Telegram, and scheduler adapters call the same application services. Remove synthetic `Update` construction and null-Update mode selection from business logic.
5. Keep old steps only as temporary delegates where useful. Remove enums, bag keys, annotation registration, and the runner once there are no callers. Do not build a new service class per old step.

Acceptance: repository/transport details stay outside use-case orchestration; preview versus publish is explicit; all characterization tests pass; direct service tests verify required order and error propagation.

Risk control: no database engine change in this phase. Logic extraction and behavior changes should be separate commits where practical.

## Phase 4 — SQLite implementation and rehearsed migration

Proceed only after confirming single-host local-disk deployment and product policies for duplicate names, history deletion, nullable legacy metadata, and review transitions.

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

1. Produce a SQLite-consistent snapshot using the backup API or verified VACUUM INTO; coordinate media snapshot/manifest, archive-size checks, exception-safe cleanup, retention, and an independent backup destination where appropriate.
2. Exercise restoration into fresh directories with production-like permissions; verify database integrity, file availability, and sample rendering.
3. Make bot/scheduler startup configurable, initialize storage before registering the bot, and separate local liveness from bounded external readiness. Make cron zone and relevant scheduling/policy values configurable.
4. Upgrade old dependencies in a dedicated tested change; use Boot dependency management for compatible Spring libraries, patch Java 21, add wrapper integrity verification and dependency vulnerability checks. Do not combine a major Boot upgrade with the database cutover.
5. Verify/document the server's `subprocess` command. Prefer systemd process supervision, graceful shutdown, readiness smoke checks, pinned/trusted host keys, and failed-deployment rollback rather than an undocumented restart script.
6. Add operation IDs, duration/outcome metrics, redacted logs, and moderation actor/time auditing. Keep operational complexity proportional to this small app.

Acceptance: a backup can actually restore the app; failed deployments are detectable and recoverable; all jobs can be run safely in development/tests without real Telegram effects.

## First implementation slice

**Completed on 2026-10-09:** source/template preview callback authorization and status checks. The acceptance criteria below describe that completed slice; see [results](phase-1-callback-safety-results.md) for verification and limits. Rendering changes stay in a separate PR.

Acceptance for that first slice:

1. Validate callback UUID/type/action and safely handle absent or inaccessible callback messages. Invalid, missing-item, unauthorized, and stale-state callbacks are acknowledged without deleting files/records, clearing another user's keyboard, or forwarding a submission for approval.
2. Verify the submitter identity from the original stored submission and require REVIEW status for submitter confirm/cancel. Preserve the distinction: confirmation requests administrator approval; it never sets APPROVED itself. Define fail-closed behavior when legacy origin/author metadata cannot prove ownership.
3. Bind callbacks to the expected preview context using the implemented nullable preview chat/message metadata, separately from the original upload Message. Capture preview identity from the send response in `SendMemeStep`; do not overwrite the original submission or infer legacy binding from incoming callbacks.
4. Add adversarial/replay cases alongside existing valid-owner cases in `ReviewCallbackStepsTest` and route/workflow tests. Update known-failure expectations with their fixes, preserving callback formats and Portuguese messages where compatible.
5. Pass targeted tests and `./gradlew clean build`; explicitly reanalyze changed Java files for Sonar findings. Do not suppress rules, include production data, migrate storage, or redesign the workflow framework in this slice.

The subsequent rendering, selection, failure-reporting, validation, and recovery slices are now implemented; see [reliability results](phase-1-reliability-results.md). Continue with CORS/cookie/CSRF configuration in Phase 2. Repository isolation then makes SQLite migration much less invasive.

Each phase is a sequence of small PRs, not one giant PR. Estimates should follow the data audit and required test work; the existing green suite does not justify calling a whole migration low-risk or a two-day task.