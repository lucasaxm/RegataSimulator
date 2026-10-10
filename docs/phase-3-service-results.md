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

Extract shared media/render/Telegram boundaries; direct ping/report/backup orchestration; explicit publication/preview/submission/moderation services; thin Telegram parsing/router and HTTP/scheduler adapters; central application failures outside `flows`; remove production runner/actions/bag/registration/steps and adapt tests to direct service contracts. Phases 4–5 and durable recovery/cross-adapter transactions remain pending.