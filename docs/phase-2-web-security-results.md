# Phase 2 — Configuration and web security results

Verified locally: 2026-10-09. Branch: `refactor`. Baseline: `98e97c0` (Phase 1 complete, 649 tests / 29 suites).

**Phase 2 is implemented for its scoped configuration/HTTP acceptance items. Phases 3–5 remain pending.** This report supersedes historical statements that CSRF is disabled, the administrator has USER authority, or Phase 2 is unimplemented. Earlier reports remain historical evidence, not current contracts.

## Local slices

| Commit | Subject |
| --- | --- |
| `8b7477c` | fix: validate web origins and configure secure session cookies |
| `64b8448` | fix: require admin authorization and CSRF for web mutations |
| `2366b61` | fix: validate web APIs and expose safe media and import DTOs |

The final slice completes edge-case/profile coverage, corrects misleading names/comments in already-fixed Phase 1 regressions, and records this handoff. Its hash is reported separately rather than embedded in its own commit. Every slice passed focused tests and `./gradlew clean build` before local commit; nothing was pushed.

## Public deployment configuration

Browser API URLs are relative/same-origin. The public template creator works locally with the Telegram SDK and coordinate clipboard; it does not submit HTTP mutations. No need for cross-origin API grants was found in these clients. Actual reverse-proxy/hosting topology was **not** accessed or verified; cross-origin access now defaults to denied rather than retaining unexplained domain grants.

Validated `regata-simulator.web` properties can be overridden in profile YAML or deployment configuration without recompiling:

| Property | Default / contract |
| --- | --- |
| `allowed-origins` | Empty explicit list. If a deployment genuinely needs cross-origin APIs, list exact HTTP(S) origins including required ports. Wildcards, paths, credentials, queries, fragments, invalid schemes/ports are rejected in every profile. |
| `allow-credentials` | `true`, but only for explicitly listed origins; empty origins grant nothing. `false` omits the credential grant. |
| `frame-ancestors` | `'self'`, `https://*.telegram.org`, `https://telegram.org`; existing Telegram framing retained. Entries are validated, and arbitrary CSP directive injection is rejected. The historical Telegram subdomain wildcard is allowed only here, never in CORS. |
| `cookie-domain` | Absent: host-only. Optional validated hostname; no scheme/path/port/leading dot. Do not configure a shared parent domain unless hosting actually requires it. |
| `cookie-secure` | `true`. `application-dev.yml` explicitly uses `false` for local HTTP. Stage/prod refuse insecure cookies even if dev/test is also active. |
| `cookie-same-site` | `Lax`; `Strict` or secure `None` may be explicitly configured. Insecure `None` is rejected. |

One `UrlBasedCorsConfigurationSource` is used by Spring Security; MVC no longer defines an independent grant. Allowed headers are Content-Type, X-CSRF-TOKEN, and X-Requested-With; allowed methods are GET/POST/DELETE/OPTIONS. Notification status is exposed. Successful credentialless preflight never authenticates an actual request. Tests cover exact origins, scheme/port/lookalikes, disallowed and default-denied origins, no-Origin, same-origin, credential grants and their absence, and actual authenticated requests through the full filter chain.

One actual Spring Session `CookieSerializer` applies JSESSIONID, Path=/, HttpOnly, configured Secure/domain/SameSite across profiles. Tests inspect serialized Set-Cookie output and load the actual dev/stage/prod YAML settings. These are not unused servlet cookie properties.

**Iframe limit:** authenticated gallery/login inside a cross-site Telegram iframe has not been proven to require SameSite=None or validated in real clients. Lax is deliberately retained; public `/create/**` framing remains permitted. Do not claim all Telegram browser cookie policies were tested or weaken cookie defaults speculatively. The CSP only controls frame ancestors; no new script/style policy was added that would blindly break existing CDN/Alpine integrations.

## Authentication, authorization, and CSRF

- All `/api/**` routes require ROLE_ADMIN except public `/api/login` and `/api/csrf`. Existing public login/create/JS/CSS assets remain public; protected browser pages redirect to login. APIs return generic application/problem+json 401/403 rather than a followed login-page redirect. API requests are not saved as post-login navigation targets.
- The configured in-memory account now has ADMIN authority. A declared BCryptPasswordEncoder encodes the existing decrypted plaintext configuration at startup, preserving encrypted configuration compatibility without `User.withDefaultPasswordEncoder`. No new password field, runtime secret, or credential disclosure was introduced. Throttling/auditing remain later work.
- GET `/api/admin/post_meme` and GET `/api/admin/create_backup` no longer exist; authenticated GET returns 405 without invoking the task. The corresponding POST paths require admin and CSRF. No mutating GET alias remains, and no existing frontend caller of those task paths was found.
- CSRF remains enabled with Security **6.2's** default session repository and XOR request handler. Public GET `/api/csrf` materializes the deferred, masked token and returns `{headerName, parameterName, token}` with Cache-Control: no-store. The token is session-bound, not a persistent-domain-readable cookie.
- Existing `api.js` requests acquire and send tokens for unsafe methods. Login uses the same helper. X-Requested-With: XMLHttpRequest login/logout success is 204; XHR bad credentials are 401, not a misleading redirected 200. Normal form success/failure/logout still use redirects. Login/logout refresh tokens before browser navigation. Mutation failures are not automatically replayed.
- Tests submit an actual endpoint-provided XOR token, authenticate the real synthetic configured account, verify session rotation and rejection of the old token, refresh and mutate, then verify logout invalidation and rejection of stale tokens. Missing/invalid CSRF and USER-role requests cannot reach mocked administration/media services.
- Telegram callback owner/REVIEW/preview-binding authorization remains a **separate** unchanged policy. No callback trusts HTTP roles or backfills ownership. Existing callback integration tests remain green.

## Intentional HTTP contract changes

| Contract | Current behavior |
| --- | --- |
| Pagination | GET lists and POST search require page >= 1 and perPage 1–100. Query is optional/null-compatible and limited to 200 characters. Invalid numbers/enums/JSON return safe 400 before service calls. |
| Review body | Existing sourceId/templateId, approved, reason keys retained. UUID and Boolean approval are required/non-null; omission no longer silently means rejection. Reason is required/non-null and at most 1,000 characters (empty remains compatible with approval). |
| Errors | Invalid requests/CSV 400; missing entity/media 404; repeat or concurrently lost REVIEW transition 409; unavailable workflow 503; execution/unexpected failure generic 500. Global ProblemDetail advice strips original exception messages, stack traces, filesystem paths, and arbitrary error bodies. No error-message substring classification. |
| Notification recovery | Applied review still returns 204, with X-Notification-Status: failed if notification fails. Null-origin imports skip notification. No durable retry or transaction guarantee was added. |
| Gallery/single JSON/search | Existing `{items,totalItems}`, ID/weight/status/description or areas remain. `message` is now an HTTP DTO with date and `from` limited to ID, first_name, last_name, username; absent origin/author produces null. No full Telegram Message, chat, text, attachments, file IDs, previewChatId or previewMessageId is serialized. Gallery name/geometry access remains compatible. |
| Old import | POST `/api/sources/import` still returns a list, now containing the same reduced source DTOs. Parsing IOException and CsvException both map to 400; persistence/reconciliation failure is not mislabeled as malformed CSV. |
| Import report | New POST `/api/sources/import/report` returns `{created, rows, persistenceFailed}`. Every row keeps its number/name/CREATED–SKIPPED–FAILED outcome and safe reason enum; compensated persistence failure remains explicit in the report. No exception details or full entities are exposed. |
| Images | Existing `/{id}.png` URLs remain. ImageIO detects stored PNG/JPEG **bytes**, even if file suffix is wrong; Content-Type matches actual bytes, not the URL. Unrecognized stored media fails with generic 500 instead of a false PNG header. |

Typed DTOs are adapter projections only. Domain services/JsonDB/workflows were not rewritten as Phase 3. Direct service pagination remains an internal caller contract; HTTP validation does not make arbitrary direct Java calls validated. Filesystem/database deletion atomicity, zero-weight legacy utility behavior, and missing backup-report origin remain honestly characterized where still present.

## Verification and limits

- OpenJDK **21.0.2**, Gradle wrapper **8.6**, Boot **3.2.3** retained. Only BOM-compatible Jakarta validation starter and spring-security-test were added; no major/dependency maintenance upgrade.
- Focused configuration/filter/API/moderation/import/backup/upload/render/selection/callback regressions and repeated `./gradlew clean build` passed. Final XML: **735 tests / 33 suites**, failures **0**, errors **0**, skipped **0**. This is a case count, not a coverage percentage.
- Node **22.23.1**: syntax checks for browser scripts and `node --test src/test/js/web-security.test.cjs` passed **5/5** mocked-fetch browser tests. Node tests are a separate explicit command, not currently run by Gradle/CI. No UI framework/package build was introduced.
- `git diff --check` passed. Editor diagnostics were checked; actual hostname-regex and filter-method complexity findings were fixed. Remaining non-project/package diagnostics are Java workspace-import warnings, not Gradle compile errors. Dedicated Sonar analysis could not be loaded with the available tool interface; no project-wide scan or server quality-gate claim is made.
- Tests use synthetic temporary JsonDB/media and mocked boundaries. MVC slices explicitly avoid application bootstrap; the full test context retains disabled registration/scheduling and mock bot/registrar. No bootRun/live application, Telegram, ImageMagick, dotenv/secrets, user dataset, push, or deployment was used.
- Main-agent review and later phases remain required. Hosting/proxy trust, live iframe compatibility, coherent backup/restore, durable retries, migration, operations/auditing and dependency/security maintenance are unverified/deferred, not established by green HTTP tests.

Next: [Phase 3 typed services with JsonDB retained](backend-improvement-plan.md#phase-3--typed-application-services-with-jsondb-retained), then rehearsed SQLite migration and Phase 5 recovery/deployment work in separately scoped changes.