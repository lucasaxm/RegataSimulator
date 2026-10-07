# Live development test results

Date: 2026-10-06. This is an observed smoke-test report, not a claim that all application behavior is correct or production-ready.

## Environment and safeguards

- Used the user's `.env.dev`, confirmed by the user to contain development-only settings. It was sourced explicitly; Spring Boot does not automatically load it.
- OpenJDK 21.0.2, Gradle 8.6, ImageMagick 7.1.1-47, and the actual dev bot `@regatasimulatordevbot`.
- Java authenticated with Telegram successfully after the user stopped Zscaler. No TLS verification bypass or truststore modification was needed for the successful run.
- Built with `./gradlew bootJar test`; the existing unit baseline contains 12 passing tests. A fresh forced test run was also performed during final validation.
- Ran the built application classes with a temporary Spring launcher, bound to loopback port 8080. This was **not an unmodified `bootRun` invocation**: the launcher disabled automatic scheduled jobs and mapped creator/channel/backup destinations to the private Telegram test account.
- First used a copy of the configured dev database/media, then a separate small neutral fixture store. Original dev files were not modified. Credentials were decrypted only in memory, not recorded in this report or helper source.
- Browser login used a short-lived, loopback-only credential bridge. It was stopped after login. The application was stopped after testing; no test instance or credential bridge was intentionally left running.
- Copied data, logs, and the downloaded neutral image remain under `/private/tmp/regatasimulator-live.wuvx0P`. The disposable Java/Python helper sources were removed after testing to clear their editor/SonarQube findings; they were never checked-in application/test source. Do not promote their test overrides to normal runtime configuration.

## Results

| Check | Observed result |
| --- | --- |
| Dev credential decryption and Telegram `getMe` | Passed; configured bot identity matched the real development bot |
| Startup and authenticated health | Passed; HTTP 200, status `UP`; long polling independently verified by bot replies |
| Public login/template-creator pages | HTTP 200 |
| Anonymous source API and health requests | HTTP 302 to login; access was not granted anonymously |
| Authentication with configured dev web credentials | Passed via both HTTP and the real browser login form |
| Browser session cookie on localhost | Login worked; JSESSIONID was Secure, HttpOnly, SameSite=Lax |
| Authenticated gallery/source/template APIs and API docs | HTTP 200; copied dataset returned 26 sources and 125 templates |
| Source search | HTTP 200; fixture query returned the expected six remaining sources |
| Allowed-origin preflight without authentication/cookies | HTTP 200 with an allow-origin header |
| Disallowed-origin preflight | HTTP 403 without an allow-origin header |
| Telegram `/ping` | Received `pong! (0s)` |
| Telegram `/report` | Received report with 125 templates and 26 sources in copied-data run |
| Telegram `/meme`, copied dev data | Failed to produce a reply because the selected source image was missing; failure was only logged |
| Telegram `/meme`, neutral fixtures | Passed: rendered and delivered a photo replying to the command |
| HTTP GET `/api/admin/post_meme`, neutral fixtures | HTTP 200; rendered and delivered a second photo to the private test account |
| Actual delivered photo | Downloaded via Telegram, decoded as 400×300, and visually checked: correct colored-source composition inside the white template frame |
| Publication persistence | Two meme-history records; selection weights decreased. After deleting the unused review-source fixture, source weight total was 58 for six records; template total was 48 for five records |
| Source rejection with no origin message | HTTP 204; subsequent read confirmed `REJECTED` |
| Source deletion | HTTP 204 on the rejected fixture |
| Template rejection with no origin message | HTTP 500 **after** persisting `REJECTED`; notification path failed |
| Mutations without CSRF token | Authenticated source rejection/deletion were accepted without one; consistent with disabled CSRF, not a security success criterion |
| Telegram `/backup`, small fixture store | Received database, template, and source backup messages followed by a report; database attachment confirmed as `application/zip` |
| Invalid pagination (`page=0`) | HTTP 500, not a useful validation error |
| Missing source UUID | HTTP 500, not HTTP 404 |
| Gallery browser console | Two errors from evaluating `selectedItem.status` while `selectedItem` is null; missing-source images also returned 500 |
| MCP photo resend with source caption | Arrived as `MessageMediaPhoto`; correctly did not create a source because this route requires a JPEG/PNG **document** |

The HTTP helper explicitly replayed its session cookie for local HTTP requests. The separate browser test verified that login actually works on localhost with the configured cookie policy; the helper's behavior alone would not prove that.

## Main findings

### 1. Development metadata and source storage do not match

In the copied data:

- Sources: 26 records, **26 missing expected image paths**.
- Templates: 125 records, zero missing expected template image paths.
- Source status: 25 approved, one rejected. Templates: 121 approved, three in review, one rejected.

The configured source directory does contain images, but none of these 26 IDs has `source.jpg`, `source.jpeg`, or `source.png` where the application expects it. This does not prove data was deleted or corrupted: the database and media directory could belong to different snapshots, or files could use an unsupported layout/name.

Consequences reproduced: gallery image requests return 500; `/meme` logs `Source file not found` and silently terminates. Check the dev path/dataset pairing before attributing this to ImageMagick or Telegram. No original files were renamed, recreated, or removed to hide the failure.

### 2. Template rejection can succeed in storage and fail at the HTTP boundary

With a valid fixture template whose origin message is null, `TemplateController` saves the rejection, then starts `SendTemplateRejectedMessageStep` without the notification data it requires. The client receives 500, but a subsequent read shows `REJECTED`.

This confirms the partial-success risk described in [backend review](backend-review.md): clients cannot infer whether the domain decision was applied from the error response. Eligibility and retry behavior for notifications should be explicit.

### 3. Error handling and browser initialization need regression tests

Invalid pagination and absent entities return 500. Gallery modal review expressions dereference a null `selectedItem` during initial rendering. These are separate from the missing-media dataset problem.

### 4. CORS is working for the tested runtime

The allowed and disallowed preflight tests passed through the running application's actual security/filter setup. The absence of an explicit `.cors(...)` call in `SecurityConfig` is **not sufficient evidence that integration is broken**. Externalizing origins/cookie settings remains useful, but do not introduce a security fix based solely on that source-level assumption. This observation has been incorporated into the review.

## Coverage limits

- New JPEG/PNG document submissions, preview confirm/cancel callbacks, and actor-authorization failure cases were not exercised end-to-end. The Telegram upload tool could not access the local fixture path; a download/resend within that tool's environment worked, but uploaded it as a compressed photo. No synthetic incoming callback was mislabeled as a real Telegram test.
- Creator-only command tests used the temporary test-account mapping; they do not validate the configured creator's identity/authorization in a normal dev deployment.
- Automatic cron execution was deliberately disabled. Manual publishing and the bot backup workflow were tested instead.
- Backup delivery was verified; archive restoration into a fresh application and crash-consistent backup behavior were not tested.
- No production environment, reverse proxy, Telegram WebView/iframe cookie behavior, concurrent renders, timeouts, or interrupted-job recovery was tested.
- Runtime code was not changed to fix the observed failures, and this smoke harness is not a committed regression suite. See [implementation plan](backend-improvement-plan.md) for the proposed permanent tests/fixes.

## Suggested next work

1. Match the dev database to its source-image storage, then add missing-media diagnostics and user-visible generation failure feedback.
2. Add regression tests/fixes for null-origin template rejection and HTTP 400/404/409 error mapping.
3. Address callback authorization, isolated rendering scratch files/timeouts, and CSRF/state-changing GET endpoints as prioritized in the backend plan.
4. Guard null gallery modal state during the later frontend pass.

`.env.dev` was still untracked and not Git-ignored during testing. Keep it out of commits and add an appropriate ignore rule before tracking unrelated work; this report contains no credential values.

## Final verification

- `./gradlew test --rerun-tasks`: 12 tests, zero failures/errors/skips. The fresh build reports deprecated Java APIs and Gradle features incompatible with Gradle 9; these warnings did not fail the build.
- SHA-256 comparisons against the initial snapshot: five dev JSON files, 968 source-storage files, and 488 template-storage files; zero content differences.
- Loopback ports 8080 and 8081 were closed after cleanup. Logs confirm all three fixture backup archives were sent successfully.
- `git diff --check` passed. Tracked changes are documentation only; `.env.dev` was not changed or staged, and generated browser logs were removed from the repository.