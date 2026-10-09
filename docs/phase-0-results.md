# Phase 0 — Characterization and isolated testing

Implemented: 2026-10-06. Scope: protect existing behavior and make tests safe; this is **not** the Phase 1 bug/security fix or the service/SQLite migration.

This report preserves the Phase 0/Sonar baseline. The 2026-10-09 [callback-safety slice](phase-1-callback-safety-results.md) supersedes its callback failure characterizations and increases the suite to 590 cases across 24 suites. Other characterized defects remain pending.

## Outcome

The original 12 tests remain green. Phase 0 initially delivered 293 cases. After subsequent Sonar-driven cleanup and focused regression/test splits, the suite contains **300 executed cases across 23 suites**: 288 additional cases in 18 new test classes. Parameterized cases are included in those totals; this is not a line/branch coverage percentage.

The dormant, fully commented `BilubotApplicationTests.java` was replaced by a real isolated Spring application-context test and focused startup tests. No tests use `.env.dev`, existing dev/production data, actual Telegram sends/downloads, or a real ImageMagick subprocess.

## Small production seams

| Seam | Default behavior preserved | How tests isolate it |
| --- | --- | --- |
| `TelegramBotRegistration` | Still creates/registers the same long-polling bot; existing registration error handling/order remains | Mock the registrar; opt out with `telegram.bots.regata-simulator.registration-enabled=false` |
| `SchedulingConfig` property condition | Automatic publishing/backup schedules remain enabled unless explicitly disabled | `regata-simulator.scheduling.enabled=false` prevents the scheduled annotation processor from registering |
| `TimeConfig` / injected Clock | Birthday rules still use the America/Sao_Paulo date and existing aliases | Fixed clocks cover all special dates and UTC/local-day boundaries |
| `JsonDBUtils` RandomGenerator overloads | Existing public methods still use a new Random and the same selection algorithm | Explicit random draws/seeds test weighted intervals and repeatability |
| `BuildMemeStep.startProcess(ProcessBuilder)` | Production still calls ProcessBuilder.start with argument arrays | Test subclasses return fake processes and write only temporary placeholder outputs |

Existing two-argument application/source-step constructors are retained. The settings are described in Spring configuration metadata. Test-only YAML is under `src/test/resources/application-test.yml`, not the shipped application profiles.

Disabling registration/scheduling does **not** disable every manual Telegram operation or remote health call. Tests also replace the bot/network boundaries and use temporary paths. Do not treat those switches alone as a complete offline mode.

## Coverage matrix

All new paths below are relative to `src/test/java/com/boatarde/regatasimulator/`.

| Area | Tests and protected/characterized behavior |
| --- | --- |
| Startup | `RegataSimulatorApplicationTest`: disabled/enabled registration, collection preservation/creation, current registration-failure continuation |
| Real context | `RegataSimulatorApplicationContextTest`: actual Spring wiring, temporary JsonDB collections, no registration/scheduled processor, public pages/auth protection, synthetic form login, allowed/disallowed preflight, real source metadata/media lifecycle with null origin |
| Schedule opt-out | `configuration/SchedulingConfigTest`: default-on and explicit-off settings |
| Source submission | `flows/simulator/CreateSourceStepTest`: REVIEW/author/path/bag creation, Portuguese validation message, duplicate case handling, download/progress failures, current partial-file cleanup gaps |
| Template submission | `flows/simulator/CreateTemplateStepTest`: CSV geometry/REVIEW/author/path contracts and current failure boundaries |
| Real workflows | `flows/simulator/MemeWorkflowTest`: complete source preview, template preview, and publication through actual runner/steps with fake external boundaries; previews do not alter publication history/weights |
| Selection | `flows/simulator/GetRandomSourceStepTest`, `GetRandomTemplateStepTest`: approved pools, supported media paths, ordered source slots, history sorting/reuse/exclusion, preview branching, one-item/insufficient-pool failures, missing files and single-area templates |
| Rendering orchestration | `flows/simulator/BuildMemeStepTest`: perspective/mask/composite command arrays, paths with spaces, cleanup, process-start failure, sparse source/area indices, duplicate scratch indices, ignored nonzero exits, interruption preservation, and dimension-reader closure/empty output |
| Sending | `flows/simulator/SendMemeStepTest`: publication destinations, weight/history changes, history cap, preview keyboards, sending failures and output cleanup |
| Callbacks | `flows/simulator/ReviewCallbackStepsTest`: submitter confirmation forwards for approval without approving, own REVIEW-item cancellation/deletion, missing/malformed IDs and Telegram failure points |
| Transport predicates | `routes/SubmissionRoutesTest`: supported document MIME/captions, callbacks, photo rejection, malformed CSV-number propagation |
| Moderation | `controller/ModerationControllerTest`: both decisions, commit-before-notification ordering, already-approved/missing records, null origins, failure after commit, real null-origin template-notification failure |
| Real persistence/files | `service/SourceAndTemplateServiceTest`: temporary JsonDB CRUD/status, nullable origins, extensions, nested deletion/sibling preservation, pagination/search, weight resets, source-slot initialization and partial deletion failures |
| Import | `service/SourceImporterServiceTest`: actual CSV parsing, existing/wrong-type filtering, REVIEW/null-origin records, partial download failure, malformed/empty input, duplicates inside one batch and final insert failure |
| Backup service | `service/BackupServiceTest`: real temporary ZIP fixtures, delivery order/captions, cleanup after success, current leftover-archive behavior on failure |
| Backup workflow | `flows/backup/BackupWorkflowTest`: real runner/registry/steps, database→templates→sources→report order, failure termination and report transport failures |
| Utility contracts | `util/JsonDBUtilsTest`: exact CSV format/corners/background, malformed numeric/header/field counts, current sparse/duplicate-index acceptance, null-origin comparators, deterministic weighted/single-area selection |

Some orchestration tests use a controlled repository double; the service/context tests separately exercise real JsonDB. They do not provide SQL transaction or migration guarantees.

## Known failures are intentionally visible

Test names containing `currently`, `characterizes`, or `Phase1...Gap/Bug` describe existing defects rather than endorse them:

- Recent-history filtering can remove the only candidate or leave insufficient source capacity.
- Sparse/duplicate geometry is not rejected before rendering; subprocess exit codes are ignored.
- Partial download/import/backup failures can leave files behind.
- At Phase 0, missing callback items/malformed UUIDs could fail without acknowledgment; these expectations were replaced with safe rejection tests in the 2026-10-09 callback slice.
- Template rejection with null origin metadata can persist the decision and then fail in notification.
- Invalid pagination/missing media and metadata/file deletion failures lack useful domain error/recovery handling.

When fixing these in Phase 1/2, update the relevant test to assert the corrected outcome in the same change. A green characterization suite does **not** mean these defects are solved. Tests do not freeze missing actor authorization as an acceptable security contract; adversarial authorization/status-transition tests should accompany the actual security fix.

## Validation

- `./gradlew test`: 300 cases, zero failures/errors/skips after the 2026-10-07 cleanup.
- `./gradlew clean build`: passed after the 2026-10-07 cleanup, including all 300 cases and executable-JAR packaging. The original Phase 0 baseline was 293 cases.
- Configuration metadata JSON parsed successfully, relative documentation links resolve, and `git diff --check` passed. Existing user changes were preserved.
- Original five active suites remain included. No application was started live or production/dev data read to verify this change.
- Existing deprecation warnings and Java editor project-import warnings are separate from Gradle success; dependencies/toolchain were not upgraded here.

## Subsequent Sonar cleanup

The remaining eleven expanded findings were addressed on 2026-10-07: platform-aware progress-message formatting, NIO temporary-file deletion with exception diagnostics, explicit move-from-pool selection, chained AssertJ checks, focused submission/publication tests, unnecessary throws declarations, and redundant Mockito matchers. All assertions and delivery/persistence ordering checks remain covered; no rules were suppressed or files excluded.

Earlier cleanup also narrowed exceptions, preserved rendering interruption, closed the dimension reader, handled missing dimension output, and verified mutable selection results. These small fixes do not implement the deferred timeout/exit-code/concurrency redesign. Eight affected files were explicitly sent for Sonar analysis; the diagnostics interface reports project-import warnings but does not expose a reliable remaining Sonar rule count, so zero findings is not claimed solely from the green build.

## Limits and next step

ImageMagick pixels, real Telegram transport, backup restoration/crash consistency, concurrency, timeouts, and production startup/deployment are not proven by these fake-boundary tests. [Live test results](live-test-results.md) remain a separate historical dev run. Security fixes, frontend fixes, typed services, SQLite, and dependency updates are still deferred.

Phase 0 is complete for the planned behavior/testability coverage. Actor/status/preview authorization is now implemented in the [first Phase 1 slice](phase-1-callback-safety-results.md). Continue with isolated, bounded rendering using these tests as a safety net; do not combine that work with a database cutover.