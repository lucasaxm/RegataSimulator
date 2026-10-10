# Project map and development baseline

Verified: 2026-10-09. This describes the **current** application, not the proposed redesign.

## Phase 3 intermediate checkpoint

Typed repositories with JsonDB adapters, Telegram/media/render boundaries, and direct ping/report/backup/meme/submission/moderation/callback services are implemented in sequential local slices. Bot dispatch now uses `adapter.telegram.TelegramRouter`; scheduler publication/backup and HTTP moderation use typed services. There is no registered production workflow runner/step graph. Persisted nullable Telegram messages and Phase 2 DTO/security contracts remain compatible. See [Phase 3 service results](phase-3-service-results.md).

The workflow package map/count below describes retained **obsolete intermediate sources**, not current production dispatch; final source removal/test migration, wider media-boundary consumption, failure-package relocation and administrator-origin cleanup remain pending. Phase 3 is **not complete**, and SQLite/Phases 4–5 remain unimplemented. Latest verified clean build: **829 cases / 38 suites**, zero failures/errors/skips, plus **5 Node cases**.

## Stack and repository layout

- Java 21; `.tool-versions` pins OpenJDK 21.0.2.
- Gradle wrapper 8.6, Groovy build configuration; Spring Boot 3.2.3.
- Spring MVC, Security 6.2, Jakarta validation, Actuator, in-memory Spring Session; Lombok/Jackson.
- Telegram Bots 6.9.7.1, JsonDB 1.0.115-j11, OpenCSV, Jasypt, Tika, springdoc.
- External ImageMagick executable selected by `MAGICK_PATH`.

| Path | Purpose |
| --- | --- |
| `src/main/java/com/boatarde/regatasimulator/` | Application sources |
| `src/main/resources/application*.yml` | Shared configuration and dev/stage/prod profiles |
| `src/main/resources/META-INF/` | Additional Spring configuration metadata |
| `src/main/resources/static/` | Browser pages/assets served by Boot |
| `src/test/java/com/boatarde/regatasimulator/` | JUnit 5/Mockito tests |
| `.github/workflows/` | PR validation and release/deployment |
| `docs/` | Review, research, plans, and other produced Markdown |
| `scripts/` | Empty at review time; no checked-in startup/migration scripts |
| `build/`, `.gradle/` | Generated outputs/caches, not source |

There is no Node/package-manager build configured. The gallery uses Alpine.js and Bootstrap; the template creator integrates the Telegram Web App SDK, Toastify, and Clipboard.js. Empty `create2`/nested static directories and stale `build/resources` contents are not separate active implementations. Edit `src/main/resources/static`, never generated resources.

## Backend package map

All package paths below are relative to `src/main/java/com/boatarde/regatasimulator/`.

| Package / class | Responsibility |
| --- | --- |
| `RegataSimulatorApplication` | Boot entry point; conditionally registers the bot through TelegramBotRegistration and creates four JsonDB collections in `@PostConstruct` |
| `bots/RegataSimulatorBot` | Telegram long-polling adapter forwarding updates to `RouterService` |
| `routes/` | Six route predicates: ping, meme, report, backup, source upload/callback, template upload/callback |
| `service/RouterService` | Requires an exclusive route match; bounded enum-driven synchronous workflow loop and redacted failure reporting |
| `flows/` | Workflow interface/action/registration, registry, and object-valued data bag |
| `flows/ping/`, `flows/common/` | Pong construction and generic text/photo sending |
| `flows/simulator/` | Source/template creation, selection, rendering, sending, confirmation/cancellation, decision notifications |
| `flows/backup/` | JsonDB/media backup sequencing and report generation |
| `service/SourceService`, `TemplateService` | JsonDB-backed gallery, image loading, review status, deletion, weight maintenance |
| `service/SourceImporterService` | Validated bounded CSV import, typed row report, normalized batch deduplication/compensation, using bilu-tags file identity |
| `service/BackupService` | ZIP packaging via FileUtils and Telegram document delivery |
| `service/ScheduledTaskService` | Scheduled meme generation and backup; also called by administration endpoints |
| `controller/` | SourceController, TemplateController, AdminController, public CsrfController, generic ProblemDetail advice and byte-detected image responses |
| `configuration/` | Security, sessions/cookies, CORS, MVC forwarding, JsonDB, scheduling, Telegram health |
| `models/` | Persisted entities, geometry, status, gallery/review bodies |
| `dto/` | CSV row, validated search request, minimized gallery/origin/import-report HTTP projections |
| `util/` | Telegram methods/file transfer, JXPath builders, CSV/weighted selection, filesystem/ZIP helpers |

### Workflow count and contracts

There are 21 concrete steps: one ping, two common sends, four backup/report, and fourteen simulator steps. `@WorkflowStepRegistration` also acts as a Spring component annotation. A new step needs an action/registration plus any bag keys it consumes/produces; there is no compile-time flow contract.

Each invocation gets a fresh bag. `NONE` is the only successful terminal action and cannot be registered. Missing annotations/actions, null transitions, cycles beyond 64 transitions, and ambiguous route matches fail explicitly. Runner callers receive typed application failures; Telegram senders receive a redacted Portuguese message. See [Phase 1 reliability results](phase-1-reliability-results.md) for verified behavior and limits.

## Persistence and media

| Collection | Entity | Stored domain fields |
| --- | --- | --- |
| `sources` | `Source extends CommonEntity` | UUID, description, weight, status, original Telegram Message, nullable preview chat/message IDs |
| `templates` | `Template extends CommonEntity` | UUID, areas, weight, status, original Telegram Message, nullable preview chat/message IDs |
| `memes` | `Meme` | UUID, template UUID, ordered source UUID list, Telegram Message |
| `users` | `Author` | Long Telegram user ID, first/last name, optional username |

JsonDB discovers `@Document` entities under `models`. Review status values are exactly `REVIEW`, `APPROVED`, `REJECTED`; initial source/template weight is 10. Publication reduces weights toward 1 and trims meme history when it reaches 1000 in the current sending step.

- Source image layout: configured sources directory / UUID / `source.jpg`, `source.jpeg`, or `source.png`.
- Template image layout: configured templates directory / UUID / `template.jpg`, `template.jpeg`, or `template.png`.
- Template geometry stores 1-based area/source numbers, four integer corners, and background flags. The CSV header is exactly `Area,Source,TLx,TLy,TRx,TRy,BRx,BRy,BLx,BLy,Background`.
- Imported sources have no Telegram origin message. Current entity sorting derives dates from Message or zero.
- Preview callbacks require the original submitter, REVIEW state, and the exact persisted preview chat/message identity. Successful confirmation consumes the binding without approving the record. Legacy/unbound previews fail closed. Binding metadata is updated with JsonDB field operations; process-local callback locks prevent concurrent successful replays, not all HTTP/multi-process races. See [callback-safety results](phase-1-callback-safety-results.md).
- Files and JsonDB metadata are separate; neither source deletion nor create operations are application-level atomic across them.
- Rendering scratch is job-local outside persisted media, with owner-only permissions. `ProcessRunner` bounds execution/output and checks exit status; final output ownership passes to `SendMemeStep` through delivery. Upload/import validation uses actual decoded PNG/JPEG bytes and bounded convex geometry/CSV. See [reliability results](phase-1-reliability-results.md) for numeric limits and recovery policies.
- Moderation updates status and consumes preview bindings only from stored REVIEW state; unrelated current fields are preserved. Notification failure does not undo an applied decision; the existing HTTP 204 carries `X-Notification-Status: failed` when applicable. Null-origin imports receive no notification.

## HTTP and browser integration

| Prefix / mapping | Main operations |
| --- | --- |
| `/api/sources` | Validated paginated/filter list; `/{id}.png` image and `/{id}.json` DTO; delete; review; legacy list CSV import and typed `/import/report`; search; reset weights |
| `/api/templates` | Validated list, image/DTO, delete, review, reset weights, initialize source IDs |
| `/api/admin` | POST-only `/post_meme` and `/create_backup` trigger side effects; no mutating GET aliases |
| `/api/login`, `/api/logout` | Spring Security form-login/logout processing |
| `/api/csrf` | Public no-store deferred XOR token acquisition for login/API/logout requests |
| `/`, `/create` | Forward to gallery and template-creator pages |

`SecurityConfig` permits login/create assets, root JS/CSS and `/api/csrf`; other APIs require ROLE_ADMIN, while protected browser pages redirect to login. The existing encrypted plaintext admin configuration is BCrypt-encoded at startup through an explicit encoder. API docs/Swagger and Actuator health are **not** explicitly permitted anonymously; enabling them in YAML does not override the filter chain.

Cookies/sessions use `MapSessionRepository`, not database-backed sessions. One real CookieSerializer defaults host-only/Secure/HttpOnly/Lax across profiles; dev YAML explicitly permits local HTTP, stage/prod refuse insecure cookies. Validated `regata-simulator.web` properties control origins, credential grants, Telegram frame ancestors, cookie domain/Secure/SameSite. One security CORS source defaults to denying cross-origin API access; relative browser calls need no grant. Hosting/proxy and authenticated iframe cookie requirements remain unverified.

CSRF is enabled using Security 6.2 session/XOR tokens. Existing fetch helpers acquire tokens for unsafe methods and refresh after XHR login/logout (204); API auth failures are generic 401/403 problems rather than redirects. HTTP validation bounds pagination/query/review fields, and safe ProblemDetail maps invalid/missing/conflict/unavailable/internal outcomes to 400/404/409/503/500. Gallery/import DTOs omit full Telegram messages and preview bindings while preserving `message.from` names and geometry. Legacy `.png` image URLs serve actual PNG/JPEG byte-detected MIME. See [Phase 2 results](phase-2-web-security-results.md) for intentional contract changes and limits.

## Scheduling and side effects

- Meme publishing: `0 0,30 * * * *` — at minute 0 and 30 of each hour.
- Backup: `0 15 12 * * SUN` — Sunday at 12:15.
- Neither scheduled annotation sets a zone; the scheduler timezone applies. Birthday selection separately uses America/Sao_Paulo.
- Backups include JsonDB, templates, sources, then a statistics report, delivered to a configured Telegram backup chat. Indivisible items and encoded ZIPs above 40 MiB fail explicitly; ZIP creation/delivery cleans prepared archives on failure. This is not a coherent live database/media snapshot; that remains Phase 5.
- Application startup calls the live Telegram API and starts long polling. Registered schedules can publish and send backups. `bootRun` is **not** a harmless startup smoke test.
- Phase 0 added explicit default-on switches: `telegram.bots.regata-simulator.registration-enabled` and `regata-simulator.scheduling.enabled`. Tests set both false and mock Telegram; disabling these alone does not disable manual operations/remote health calls.
- `TimeConfig` supplies a Clock for birthday rules; JsonDBUtils offers RandomGenerator overloads, and BuildMemeStep retains a process-start seam. History exclusion now preserves required capacity, birthday pools fall back when insufficient, and subprocess execution is bounded.

## Build and test commands

Run from repository root with Java 21:

- `./gradlew test` — unit, characterization, and isolated Spring-context suite.
- `./gradlew clean test` — clean baseline verification.
- `./gradlew clean build` — compile/test/package (the standard PR CI command).
- `./gradlew bootJar` — executable JAR under `build/libs/`.
- `./gradlew test --tests 'com.boatarde.regatasimulator.service.RouterServiceTest'` — targeted test example.
- `node --test src/test/js/web-security.test.cjs` — five mocked-fetch browser security tests, separate from Gradle/CI.

The suite has **735 passing cases across 33 suites** after the 2026-10-09 Phase 2 work (Phase 1 baseline: 649/29; callback baseline: 590/24; Phase 0: 300/23), plus **5 separate Node cases**, with no runtime secrets, Telegram calls, or ImageMagick dependency. Process tests launch safe Java children; validation fixtures use generated ImageIO images. Test reports: `build/reports/tests/test/index.html` and `build/test-results/test/`. The dormant `BilubotApplicationTests.java` was replaced with safe context/startup tests; test-only profile YAML and dynamic temporary paths isolate them. See [Phase 0 results](phase-0-results.md), [callback-safety results](phase-1-callback-safety-results.md), [reliability results](phase-1-reliability-results.md), and [web security results](phase-2-web-security-results.md). IDE non-project diagnostics should be investigated via Gradle/Java workspace import, not fixed by changing valid package declarations.

## Runtime environment and local safety

Required variables referenced by shared YAML:

| Variable | Use |
| --- | --- |
| `REGATA_SIMULATOR_ENC_PASSWORD` | Jasypt decryption password for ENC configuration |
| `REGATA_SIMULATOR_DB_PATH` | JsonDB data directory |
| `REGATA_SIMULATOR_SOURCES_PATH` | Source image root |
| `REGATA_SIMULATOR_TEMPLATES_PATH` | Template image root |
| `MAGICK_PATH` | ImageMagick executable path |
| `SPRING_PROFILES_ACTIVE` | Select dev, stage, or prod Telegram/profile values |

Web credentials and profile bot tokens/IDs are encrypted in configuration; no decrypted values should enter documentation or logs. Use a dedicated development bot and isolated directories outside source/build outputs. Do not assume profile selection disables scheduling or bot registration: it currently does not.

A placeholder-only root `.env` was initially added during the review. Development setup subsequently moved to the user-created `.env.dev`, with the supplied development-only decryption key and local storage/ImageMagick settings. The 2026-10-07 readiness check confirms `.env.dev` exists, is Git-ignored, and is not tracked; the earlier live-test warning about its ignore status is historical.

Spring Boot does **not** automatically load dotenv files: supply `.env.dev` variables explicitly through the process environment/IDE. Never print or commit the decryption key. Loading the dev environment alone does not make startup side-effect-free: use registration/scheduling opt-outs, isolated storage, and mocked or dedicated test destinations as appropriate. The live test found a mismatch between dev source metadata and image paths; that dataset issue has not been repaired by configuring the environment or adding Phase 0 tests.

## CI and deployment

- PRs targeting main run `clean build` and then `test` (usually redundant/up-to-date after build).
- Main pushes/manual dispatch build an executable JAR, optionally select environment for deployment, and copy it to one SSH server. Default deployment environment is stage.
- Startup shell code is generated inside `release-and-deploy.yml`; it is not a file under `scripts/`. It expects an external `subprocess` command and a fixed host Java path, runs a restart loop, and writes a wrapper PID.
- A prior JAR is backed up; startup is detected through log text. No automatic rollback or HTTP readiness smoke check is defined.
- Actual host layout, environment secrets, reverse proxy, and the external `subprocess` program were not verified.

See [backend improvement plan](backend-improvement-plan.md) for the sequence and [Phase 3 service results](phase-3-service-results.md) for the implemented intermediate repository/service migration. SQLite and final Phase 3 cleanup have not been applied yet.