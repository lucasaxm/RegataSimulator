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

## Slice 2 — Direct operations and Telegram boundary

- Added typed Telegram destination/text/photo/document/delivery requests behind `TelegramGateway`; bot-method construction is in `BotTelegramGateway`. Bot lookup is lazy to avoid the bot/router/service dependency cycle; isolated context startup performs no external calls.
- Production ping/report/backup commands now invoke application services directly after the existing exclusive/creator-authorized route checks. Scheduled/admin backup calls `BackupService.create()` without fabricating Telegram updates. Meme generation still uses the runner temporarily.
- Backup explicitly sequences JsonDB, templates, sources, then report. Archives retain the 40 MiB bound and batch-owned cleanup. Command report destinations preserve reply identity; scheduled report uses the configured backup chat. A missing synthetic origin is no longer needed. This is not a coherent snapshot/restore implementation.
- `ReportService` uses repositories and handles nullable/incomplete legacy author origins. `PingService` uses the injected Clock.
- Temporary legacy backup overloads and workflow tests remain until all workflows migrate. The runner/actions are still present; **Phase 3 remains incomplete**.
- Validation: focused direct/backup/router/context suites passed after correcting command fixtures; clean build **754 cases / 35 suites**, zero failures/errors/skips; Node **5/5**; whitespace check passed. XML totals were independently checked after rejecting an incorrect zero-count aggregation. Timezone diagnostic fixed; workspace-import warnings remain.

## Slice 3 — Shared rendering and explicit meme service

- `MediaStorage`/`FileMediaStorage` and `ImageRenderer`/`ImageMagickRenderer` provide reusable boundaries. The shared renderer retains isolated owner-only scratch, argument-array process execution, memory/map/disk/thread/time limits, bounded process/output handling, geometry/dimension/output validation, interruption preservation and intermediate cleanup.
- `RenderedImage` transfers whole-job ownership to the caller until delivery finishes. `MemeService` closes that ownership around send and persistence, including failed sends/binding writes. The old rendering step is only a temporary adapter; its duplicate algorithm was removed.
- Explicit `publish`, `previewSource`, and `previewTemplate` methods use typed origin/destination inputs. Preview mode is independent of progress messages and null transport updates. Preview delivery binds the actual returned message only while REVIEW; publication alone updates current weights and delivered history.
- Production Telegram meme commands and scheduled/admin generation now call `MemeService` directly. Submission preview still enters the runner temporarily. Administrator generation currently passes through the scheduled adapter and remains a pending origin-classification cleanup.
- Real temporary repository/media tests cover minimal pools/history, all birthday fallback dates, explicit previews with no progress message, original origin preservation, conditional binding versus concurrent moderation, failure cleanup and publication-only weights/history. Existing process/render/concurrent-job regressions exercise the shared renderer through the temporary step seam.
- Focused suites, clean build **771 cases / 36 suites** (zero failures/errors/skips) and separate Node **5/5** passed after correcting Mockito restubbing in failure fixtures; no actual Telegram/ImageMagick or runtime dataset was used. Counts were independently read from all XML suite headers.

## Slice 4 — Typed submissions and HTTP moderation

- `SubmissionService` accepts typed source/template submissions, upload identity/author and an explicit origin projection. Telegram adapter parsing supplies descriptions/areas rather than passing `Update` into business logic. Download, decoded image/geometry validation, author-before-entity persistence, preview delivery and uncommitted-media cleanup are explicit method calls.
- Original full nullable Telegram Message remains unchanged at the JsonDB persistence boundary; destination fields are projected separately. Successful persistence retains uploaded media if later preview fails. Cleanup avoids deleting known persisted submission media; uncertain storage failures still require reconciliation rather than a claimed transaction.
- Production Telegram uploads now call this service directly. Old upload steps remain temporarily for regression migration; callback dispatch still uses the runner.
- `ModerationService` accepts typed item/status/reason/administrator identity. HTTP controllers only map existing body fields to the decision and result header; no synthetic Update/channel-post is constructed. Review transition occurs before notification, null origins skip notifications, applied status survives transport failure and repeated decisions remain 409.
- `SourceImporterService` now uses `SourceRepository` for names/batch persistence/compensation. Existing ordered reports, byte bounds and metadata-before-media compensation remain covered with real temporary JsonDB.
- Existing direct moderation tests now exercise the real service through a fake gateway; full-chain API tests retain real auth/CSRF and real moderation with mocked persistence/transport. Additional temporary-repository tests cover complete submissions, invalid-image cleanup and committed decision/notification failure. Focused suites and clean build passed: **777 cases / 36 suites**, zero failures/errors/skips; Node **5/5** and whitespace check passed.

## Slice 5 — Direct Telegram router and callbacks

- Bot now calls `adapter.telegram.TelegramRouter`, which parses commands/uploads/callbacks and dispatches typed services directly. Creator-only private-chat administration, exact callback format/canonical UUID/type/action, accessible envelope/actor/photo checks and generic acknowledgment remain enforced.
- `ReviewCallbackService` validates stored original submitter, REVIEW, original chat and exact stored preview binding. Confirmation forwards to existing `telegram.creator.id`, consumes binding and clears keyboard without approving; cancellation deletes only the authorized item. Same-item process-local stripes serialize confirm/confirm and confirm/cancel. Forwarding failure preserves binding; acknowledgment failure stays safe.
- Old `RouterService`, `WorkflowManager` and annotation-driven steps are no longer Spring-registered. Isolated context asserts no runner/step beans and one direct Telegram router. **Obsolete workflow sources still exist for pending test migration/removal; this is not final Phase 3 acceptance.**
- Direct callback tests use real temporary repositories/media and cover invalid ownership/state/origin/chat/binding, missing records/photo, confirmation persistence/replay, cancellation, concurrent races, failed forwarding and acknowledgment. Router tests cover command dispatch/creator checks, malformed/valid callbacks, missing envelopes and redacted failure reporting. Original callback regressions also remain green during migration.
- Focused callback/router/context regressions, clean build **829 cases / 38 suites** (zero failures/errors/skips), Node **5/5** and whitespace check passed. Generic test lookup/fixture issues and reported complexity findings were corrected. No new runtime property, live call or data access was introduced.

## Slice 6 — Complete media consumption and publication origins

- Gallery services and importer now use `MediaStorage` for lookup/preparation/deletion/discard rather than configured paths and filesystem work in orchestration. Existing extension priority, missing-media errors, strict gallery deletion and best-effort uncommitted import cleanup remain covered. Metadata compensation still precedes media cleanup; this is not a transaction.
- Administrator publication and scheduled publication use distinct ADMIN/SCHEDULED origins on the same MemeService. HTTP paths/statuses, ROLE_ADMIN and CSRF are unchanged. Full-chain tests verify the administrator adapter; a direct test verifies both origin requests.
- Focused gallery/import/callback/meme/API/security/context regressions, clean build **830 cases / 38 suites** (zero failures/errors/skips), Node **5/5** and whitespace check passed. The sequential stateful import loop is intentionally retained; no parallelization or rule suppression was introduced.

## Remaining Phase 3 work (after slice 6)

Central application failures outside `flows`; remove obsolete runner/actions/bag/registration/steps/routes and adapt remaining tests to direct service contracts. Phases 4–5 and durable recovery/cross-adapter transactions remain pending. **Phase 3 remains incomplete.**