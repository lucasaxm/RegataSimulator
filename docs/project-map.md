# Project and development guide

RegataSimulator is a Java/Spring Boot modular monolith: Telegram submissions and publishing, authenticated administration APIs, static browser pages, ImageMagick rendering, and recoverable metadata/media storage. Prefer typed services and shared boundaries over a workflow framework or distributed infrastructure.

## Where to work

Base package: `com.boatarde.regatasimulator`.

| Location | Responsibility |
| --- | --- |
| `src/main/java/.../adapter/telegram` | Command/upload/callback parsing and Telegram transport |
| `application`, `service` | Typed use cases, `ApplicationFailure`, publication/submission/moderation |
| `repository`, `repository/jsondb`, `repository/sqlite` | Domain persistence adapters and metadata-only transactions |
| `adapter/media`, `util/ProcessRunner` | Media lifecycle, isolated ImageMagick jobs, bounded external work |
| `migration` | Offline import/export, coherent capture, restore and stage inspection |
| `controller`, `dto`, `configuration` | Thin HTTP adapters, safe projections, security/runtime configuration |
| `models` | Persisted records, review status and template geometry |
| `src/main/resources/application*.yml` | Shared/profile configuration |
| `src/main/resources/static` | Browser source; no npm/framework build required |
| `src/test/java`, `src/test/js` | Java regressions and separate Node fixtures |
| `.github/workflows`, `scripts` | CI, dependency scanning and artifact deployment |

Do not edit generated `build/` resources/classes/reports or `.gradle/`. Preserve four-space Java formatting, Lombok, constructor injection, Portuguese messages and public HTTP/Telegram/CSV contracts unless intentionally changing them. The old runner/step/route framework is removed; do not restore it. `ReviewCallbackStepsTest` covers active adapters despite its historical name.

## Build and local verification

Use Java 21 and the checked-in Gradle wrapper. Release/CI requires patched Java; see [maintenance](maintenance.md).

- `./gradlew test` — normal Java verification.
- `./gradlew clean build` — clean compile/test/package; `./gradlew bootJar` — executable JAR.
- `./gradlew test --tests 'fully.qualified.TestClass'` — focused regression.
- `node --test src/test/js/*.test.cjs` — browser/deployment/scanner fixtures, separate from Gradle.
- `node --check path/to/edited.js` — browser JS syntax.
- `./gradlew verifyMaintenanceJava clean build` and `./gradlew dependencyScan` — release gates.

Tests use JUnit Jupiter/Mockito, real temporary JsonDB/SQLite and generated ImageIO fixtures; external bot/render boundaries are mocked. Update known-failure characterizations when fixed; do not preserve missing authorization as valid behavior. Java editor non-project/package warnings can be import problems: check Gradle before changing valid packages. Reports live under `build/reports/tests/test` and `build/test-results/test`, not documentation timelines.

### Runtime setup and side effects

Shared variables: `REGATA_SIMULATOR_ENC_PASSWORD`, `REGATA_SIMULATOR_DB_PATH`, `REGATA_SIMULATOR_SOURCES_PATH`, `REGATA_SIMULATOR_TEMPLATES_PATH`, `MAGICK_PATH`; profile: `SPRING_PROFILES_ACTIVE`. Keep environment files Git-ignored. Spring does **not** auto-load `.env`/`.env.dev`; supply them through the process environment/IDE. Never print or commit keys, decrypted configuration or token-bearing Telegram URLs.

Startup normally registers the bot and enables schedules. `dev` is not a side-effect safety switch. Tests disable **both** `telegram.bots.regata-simulator.registration-enabled` and `regata-simulator.scheduling.enabled`, override every storage path and mock external calls. These flags do not disable manual mutations or protected external health. `bootRun` is not a harmless smoke test. Authorized live testing needs a dedicated dev bot, isolated storage, private destinations and loopback binding; never use production `stage` as a test.

## Data and domain contracts

- Collections/entities: `sources`, `templates`, `memes`, `users`, `audits`. Preserve UUID identities and nullable original Telegram Messages; imported sources may have no origin.
- Status: `REVIEW`, `APPROVED`, `REJECTED`. Initial weights default to 10; publication decreases selected weights toward 1 and keeps up to 1,000 history records. History exclusion preserves selection capacity; insufficient birthday pools fall back to normal approved sources.
- Media layout: configured root / UUID / `source.{jpg,jpeg,png}` or `template.{jpg,jpeg,png}`. Files and metadata are separate resources, not one transaction.
- JsonDB is the compatibility default. SQLite requires explicit `regata-simulator.database.engine=sqlite` and absolute `sqlite-file`; production uses SQLite but startup never imports JSON. Use local disk/single host, WAL/FULL, per-connection FK/busy settings and the small pool; retain the WAL-reset runtime guard.
- Source-name uniqueness uses NFKC/Locale.ROOT across **all statuses**, including REJECTED. Resolve migration conflicts explicitly, not by silently dropping records.
- SQLite history preserves immutable ordered UUIDs plus nullable FK links, including duplicates/orphans/order after current-item deletion. Geometry/full nullable Messages remain validated JSON.
- Keep transactions around submission author/item inserts, publication weights/history and SQLite REVIEW/audit writes—not rendering, network or media. JsonDB cannot atomically update decision and audit across collections.
- Preview callbacks require original submitter, REVIEW state and exact stored preview chat/message binding. Legacy/unbound buttons fail closed. Confirmation is not admin approval. Update binding/status conditionally, not stale full snapshots; callback locks are process-local.
- Moderation is REVIEW-only. Applied decisions survive notification failure; HTTP 204 may carry `X-Notification-Status: failed`. Null-origin items skip notification; repeated/lost transitions are conflicts.
- Creation/import failure compensates metadata before removing referenced media; uncertain failures retain bytes. Deletion stages `.delete-<item UUID>-<operation UUID>` for [reconciliation](operations-runbook.md).

### Media and geometry

Validate actual PNG/JPEG bytes, not extensions: 20 MiB, 40 million pixels, each dimension at most 10,000. Detect legacy encodings mislabeled `.jpg`; convert only on preserved copies, retaining originals and verifying decoded pixels/dimensions.

Template CSV header: `Area,Source,TLx,TLy,TRx,TRy,BRx,BRy,BLx,BLy,Background`. Allow 1–128 areas, ordered contiguous 1-based indices/slots, convex nondegenerate corners within **±10,000**. Intentional off-canvas coordinates remain unchanged when every polygon has **positive-area canvas overlap**; fully outside or edge/corner-only touching is rejected. Submission/import/recovery and renderer share that rule. Import reports retain ordered outcomes for every row.

Each render owns private scratch outside persistent media. `RenderedImage` holds output through sending/persistence, then releases it; never delete it before delivery or move scratch into templates. Keep argument-array ProcessBuilder calls, bounded exit/output/time handling, platform-thread pipe readers and interruption/cleanup behavior. Test native rendering under the actual sandbox; see [deployment](deployment-setup.md#imagemagick-and-sandbox).

## HTTP/security integration

- `/api/sources`, `/api/templates`: gallery/filter/image/DTO/review/delete/import/weight operations. Preserve controller body/path shapes, pagination validation and generic ProblemDetail 400/404/409/503/500 outcomes.
- `/api/admin/post_meme`, `/api/admin/create_backup`: **POST-only side effects**. Login/logout: `/api/login`, `/api/logout`; public token acquisition: `/api/csrf` (no-store).
- APIs require ROLE_ADMIN except login/CSRF; generic API 401/403, browser redirects, protected Swagger. DTOs omit full Messages/bindings; preserve minimal `message.from` names and byte-detected MIME on legacy `.png` URLs.
- CSRF is session-bound/XOR: obtain a token for unsafe methods, refresh after login/logout and never automatically replay mutations. Sessions are in-memory; one active login and bounded process-local throttling (429).
- One CORS source denies cross-origin access by default; exact origins only. CORS is not auth/CSRF protection. Cookies default host-only/Secure/HttpOnly/Lax; dev explicitly permits HTTP, stage/prod must not. Preserve Telegram frame ancestors; test WebView/iframe behavior instead of speculatively weakening SameSite.
- Anonymous liveness/readiness report lifecycle only, not storage/render/Telegram health. Other Actuator paths require ADMIN; external Telegram health is bounded/cached/redacted.
- Low-cardinality operation/outcome telemetry and protected actor/time audits are visibility, not durable retries/outboxes/exactly-once delivery. See [partial-failure recovery](operations-runbook.md).

## Future work boundaries

Frontend redesign is a separate scope. Durable retries, cross-process coordination, stronger credential encryption, distributed throttling and alerts require explicit design; synchronous services/cooperative locks do not already provide them. Keep [maintenance](maintenance.md) and operations guides current when contracts change.