# Phase 1 — Preview callback safety

Implemented: 2026-10-09. Scope: source/template submitter confirmation and cancellation, preview binding persistence, and regression tests. **Only the first Phase 1 slice is complete**; rendering, selection, broader recovery, HTTP security, service redesign, and SQLite remain deferred.

## Implemented behavior

The four existing workflow entry points delegate shared validation to `ReviewCallbackSupport`:

- Require exactly `UUID:type:action`, a canonical UUID (case-insensitive hexadecimal), and the expected case-sensitive type/action. Short UUID forms accepted by `UUID.fromString` alone are rejected.
- Require an accessible Telegram Message, usable callback ID, actor ID, preview chat, and positive preview message ID. Confirmation additionally requires a usable photo file ID; cancellation does not.
- Look up the item and require `REVIEW`, original submission metadata proving the actor is its submitter, the same submission/preview chat, and an exact stored preview chat/message binding.
- Reject invalid, missing, unauthorized, stale, inaccessible, or legacy-unbound callbacks with the same generic Portuguese acknowledgment. They do not disclose item existence, remove media/metadata, clear keyboards, or forward for approval.
- Route every callback to exactly one handler in this bot's current source/template callback protocol, including malformed/unknown data for acknowledgment. An absent/blank callback ID cannot be acknowledged; it still causes no effects. New callback families will need an explicit routing policy rather than relying on the current fallback.

### Valid confirmation

1. Forward the photo for administrator approval.
2. Persistently clear the preview binding.
3. Remove the preview keyboard.
4. Acknowledge with the existing success message.

The record remains `REVIEW`: confirmation never approves it. Successful binding consumption rejects subsequent confirmation or cancellation from that preview, including after reload. Forwarding failure leaves the binding/keyboard available; later persistence or transport failures are reported generically and logged rather than escaping malformed-input failures to the router.

### Valid cancellation

Delete the submitter's bound REVIEW item through the existing source/template service, then delete the preview message and acknowledge. Missing items receive the generic acknowledgment instead of throwing or silently leaving a spinner. The existing file/database deletion implementation is unchanged; its partial-failure behavior is not made transactional here.

### Concurrent clicks

A bounded set of process-local locks serializes same-item callback validation/effects. Tests cover confirm/confirm and confirm/cancel overlap: after one successful confirmation, the next request sees the consumed binding and is rejected.

These locks do not coordinate multiple JVMs or all HTTP/manual mutations. They also do not bound external Telegram latency; network/process work remains a later Phase 1 concern.

## Preview persistence and compatibility

`CommonEntity` adds nullable `Long previewChatId` and `Integer previewMessageId`. These are separate from the original `Message`, so submitter ownership, original upload identity, dates, and notification data remain intact.

- `SendMemeStep` captures the actual keyboard-bearing photo's send response and binds it to the entity UUID, not a filesystem-derived UUID.
- A conditional JsonDB `findAndModify` updates only these two fields while the stored item is REVIEW. A missing/already-reviewed record cannot be recreated or changed back to REVIEW by a stale preview snapshot.
- `SourceService.completePreviewReview` and `TemplateService.completePreviewReview` clear only these fields with JsonDB field updates, preserving newer status, weight, origin, and other metadata. They do not save a stale whole entity.
- Binding creation is conditional on REVIEW; consumption clears by item ID independently of the current status. Callback eligibility is checked before effects, and clearing never reverses a newer moderation decision.
- Publication weights/history behavior is unchanged. Preview delivery now performs a binding write, but no publication weight/history mutation.
- Invalid send responses do not create bindings. A persistence exception still propagates from the sending step after temporary-output cleanup; callback validation remains fail-closed if no binding was committed.

JSON persistence remains JsonDB, with the existing collection/schema versions. Tests reopen temporary databases and verify both new-field round trips and legacy records where the fields are entirely absent. Imported sources with null origin metadata still load and remain available to HTTP administration.

**Intentional legacy policy:** old preview buttons without stored binding/ownership evidence are rejected. No attempt is made to infer a trusted preview from an incoming callback or backfill live data. Existing HTTP paths/request bodies, callback string format, and successful Portuguese messages are retained; source/template JSON responses now include the two additive nullable metadata fields.

Before deploying, back up metadata and media coherently and account for outstanding old previews. Re-submission can meet existing duplicate-name restrictions, so administration may need to resolve a pending legacy record rather than users blindly uploading duplicates. No live data migration, backfill, deployment, or restoration was performed. Compatibility tests prove the new code reads old data; they do not prove older binaries accept newly written fields during rollback.

## Regression coverage

| Suite | Added/updated guarantees |
| --- | --- |
| `ReviewCallbackStepsTest` | Ownership, states, missing origin/binding fields, mismatched chats/message IDs, malformed tokens/UUIDs, null/inaccessible envelopes, photo validation, acknowledgment failures, effect order, sequential/concurrent replay |
| `SubmissionRoutesTest` | Valid routing retained; malformed/null/unknown callbacks dispatch exactly once for safe acknowledgment |
| `SendMemeStepTest` | Exact two-field conditional binding update after send; invalid responses, missing/reviewed items, persistence failures/cleanup, entity UUID rather than path UUID |
| `MemeWorkflowTest` | Complete preview workflows preserve original upload ID and publication/history/weight semantics while capturing the sent preview binding |
| `SourceAndTemplateServiceTest` | Real JsonDB save/reload, consumed binding persistence, old JSON with omitted fields, and stale REVIEW snapshots preserving newer APPROVED metadata |
| `PreviewCallbackIntegrationTest` | Actual routes/runner/steps and real temporary JsonDB/media; owner confirmation/cancellation, generic rejection, reload/replay, and approval during preview delivery |

All external calls are mocked and media bytes synthetic; no `.env.dev`, dev/production data, real bot registration, ImageMagick, or schedules are used.

## Validation

- Focused callback/preview/routing/persistence suites passed after the binding field-update change.
- `./gradlew clean build` passed on OpenJDK 21.0.2: **590 executed cases across 24 suites**, zero failures/errors/skips, including packaging. The previous Phase 0/Sonar baseline was 300 cases across 23 suites; parameterized cases are included, not a line/branch coverage percentage.
- All 17 changed Java files were explicitly submitted for Sonar analysis. The returned rule findings (helper parameter count and duplicated conditional expressions) were corrected and reanalyzed. Remaining diagnostics are Java workspace-import/package warnings; a whole-project/server Sonar quality gate or zero UI counter is not claimed.
- No rule suppression, analysis exclusion, dependency upgrade, or live verification was introduced.

## Remaining limits and next slice

Telegram delivery and JsonDB changes are not one transaction. If forwarding succeeds but binding consumption fails, retry can forward again; a lost send response can also leave an ambiguous outcome. A persisted delivery/recovery policy remains deferred. If metadata/file deletion succeeds and Telegram preview cleanup fails, storage cannot be rolled back by Telegram.

Field updates prevent the newly introduced stale binding writes from reverting moderation. They do not make every existing full-entity HTTP/weight update, callback eligibility check, and filesystem operation mutually atomic. Cross-adapter/multi-process transitions and crash consistency still need the planned recovery/service boundaries.

The next Phase 1 slice is **isolated rendering scratch directories and bounded subprocess execution**, followed by capacity-aware selection and the remaining recovery/input fixes. Do not combine it with SQLite migration. See [implementation plan](backend-improvement-plan.md).