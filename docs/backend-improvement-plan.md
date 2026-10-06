# Backend improvement plan

Date: 2026-10-06. Status: proposed; runtime changes have **not** been implemented.

Read [backend review](backend-review.md) for evidence/tradeoffs and [project map](project-map.md) for the current structure. The goal is a simpler, reliable modular monolith, not a rewrite or a distributed system.

## Phase 0 — Protect existing behavior

Work against temporary storage and mocked Telegram, never the production paths/tokens. Preserve the Portuguese user-facing messages unless a product change is intentional.

1. Create characterization tests for source/template submission, previews, publish selection, callbacks, moderation, CSV import, and backup sequencing.
2. Add explicit edge tests for one-item pools with history, insufficient sources, missing single-area templates, sparse/duplicate geometry indices, malformed CSV numbers, null message metadata, and notification failure.
3. Isolate external bot registration/scheduling in tests before adding a real application-context test; the current `@PostConstruct` would otherwise call Telegram.
4. Add controllable Clock/randomness and fake image-rendering/Telegram boundaries as needed; do not test by posting live memes.

Acceptance: baseline 12 tests remain green; new tests describe known failures and intended contracts. Desired security behavior should be tested alongside its fix rather than enshrined as existing behavior.

## Phase 1 — Safety and reliability fixes

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

Start with callback authorization tests/fix and isolated, bounded rendering, then small-pool selection. Follow with CORS/cookie/CSRF configuration. These address safety before replacing infrastructure; repository isolation then makes SQLite migration much less invasive.

Each phase is a sequence of small PRs, not one giant PR. Estimates should follow the data audit and required test work; the existing green suite does not justify calling a whole migration low-risk or a two-day task.