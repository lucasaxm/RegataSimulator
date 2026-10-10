# Phase 3 — Typed application services (in progress)

Baseline: `6275028`, Java 21.0.2 / Gradle 8.6. JsonDB is retained. No SQLite, dependency upgrade, UI redesign, runtime data access or live integrations are part of this phase.

## Slice 1 — Domain repositories

- Added `SourceRepository`, `TemplateRepository`, `AuthorRepository`, and `MemeHistoryRepository` with JsonDB adapters. Operations name submission/import, typed filtering, conditional REVIEW decisions/bindings, preview consumption, weight updates and delivered-history retention rather than generic CRUD.
- Gallery/review source/template services depend on repositories, not JsonDB. Existing HTTP DTOs and callbacks remain unchanged. Other production workflow steps still access JsonDB temporarily; **the runner is still active and Phase 3 is not complete**.
- Description criteria are literal case-insensitive text, never interpolated into JXPath. Single-area template selection has an explicit criterion. Source/template status/binding updates preserve unrelated stored fields.
- Weight reset/decrement updates only the weight field; decrement reads current state and floors at one. Synchronization is local to a shared adapter, not multiple instances/processes or a transaction with other operations.
- Delivered history is inserted before trimming the current store to 1,000; ordered source IDs are retained. The synchronized adapter avoids competing cap calculations within one instance. Insert/trim is **not transactional**; a trim failure may leave extra history and a send is never rolled back.
- Persisted entities retain the complete nullable Telegram `Message` for JsonDB compatibility. No legacy backfill or schema migration occurred. Gallery projections still exclude transport details.
- Existing service, callback and workflow regressions are retained with repository test wiring. New real-JsonDB `@TempDir` tests cover review/binding conditions, metadata preservation, literal text criteria, explicit single-area filtering, concurrent review contention, weights, author updates and history order/retention/reload.

Slice 1 validation: focused repository/service/workflow/callback/context tests passed; `./gradlew clean build` passed with **745 cases / 34 suites**, zero failures/errors/skips; separate Node security tests **5/5**; `git diff --check` passed. Editor findings for duplicate literals and a test method reference were fixed; remaining Java package/non-project warnings are workspace-import diagnostics, not a server quality-gate result. No environment files, secrets, generated files, runtime media or user datasets are staged.

## Remaining Phase 3 work

## Slice 2 — Direct operations and Telegram boundary

- Added typed Telegram destination/text/photo/document/delivery requests behind `TelegramGateway`; bot-method construction is in `BotTelegramGateway`. Bot lookup is lazy to avoid the bot/router/service dependency cycle; isolated context startup performs no external calls.
- Production ping/report/backup commands now invoke application services directly after the existing exclusive/creator-authorized route checks. Scheduled/admin backup calls `BackupService.create()` without fabricating Telegram updates. Meme generation still uses the runner temporarily.
- Backup explicitly sequences JsonDB, templates, sources, then report. Archives retain the 40 MiB bound and batch-owned cleanup. Command report destinations preserve reply identity; scheduled report uses the configured backup chat. A missing synthetic origin is no longer needed. This is not a coherent snapshot/restore implementation.
- `ReportService` uses repositories and handles nullable/incomplete legacy author origins. `PingService` uses the injected Clock.
- Temporary legacy backup overloads and workflow tests remain until all workflows migrate. The runner/actions are still present; **Phase 3 remains incomplete**.
- Validation: focused direct/backup/router/context suites passed after correcting command fixtures; clean build **754 cases / 35 suites**, zero failures/errors/skips; Node **5/5**; whitespace check passed. XML totals were independently checked after rejecting an incorrect zero-count aggregation. Timezone diagnostic fixed; workspace-import warnings remain.

## Remaining Phase 3 work (after slice 2)

Extract shared media/render boundaries; explicit publication/preview/submission/moderation services; thin Telegram parsing/router and HTTP/scheduler adapters; central application failures outside `flows`; remove production runner/actions/bag/registration/steps and adapt tests to direct service contracts. Phases 4–5 and durable recovery/cross-adapter transactions remain pending.