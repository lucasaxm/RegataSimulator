# Project map and development baseline

Verified: 2026-10-06. This describes the **current** application, not the proposed redesign.

## Stack and repository layout

- Java 21; `.tool-versions` pins OpenJDK 21.0.2.
- Gradle wrapper 8.6, Groovy build configuration; Spring Boot 3.2.3.
- Spring MVC, Security, Actuator, in-memory Spring Session; Lombok/Jackson.
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
| `RegataSimulatorApplication` | Boot entry point; registers the bot and creates four JsonDB collections in `@PostConstruct` |
| `bots/RegataSimulatorBot` | Telegram long-polling adapter forwarding updates to `RouterService` |
| `routes/` | Six route predicates: ping, meme, report, backup, source upload/callback, template upload/callback |
| `service/RouterService` | Runs all matching routes; enum-driven synchronous workflow loop |
| `flows/` | Workflow interface/action/registration, registry, and object-valued data bag |
| `flows/ping/`, `flows/common/` | Pong construction and generic text/photo sending |
| `flows/simulator/` | Source/template creation, selection, rendering, sending, confirmation/cancellation, decision notifications |
| `flows/backup/` | JsonDB/media backup sequencing and report generation |
| `service/SourceService`, `TemplateService` | JsonDB-backed gallery, image loading, review status, deletion, weight maintenance |
| `service/SourceImporterService` | CSV import using the separate bilu-tags bot's file identity |
| `service/BackupService` | ZIP packaging via FileUtils and Telegram document delivery |
| `service/ScheduledTaskService` | Scheduled meme generation and backup; also called by administration endpoints |
| `controller/` | SourceController, TemplateController, AdminController |
| `configuration/` | Security, sessions/cookies, CORS, MVC forwarding, JsonDB, scheduling, Telegram health |
| `models/` | Persisted entities, geometry, status, gallery/review bodies |
| `dto/` | CSV row and search request data |
| `util/` | Telegram methods/file transfer, JXPath builders, CSV/weighted selection, filesystem/ZIP helpers |

### Workflow count and contracts

There are 21 concrete steps: one ping, two common sends, four backup/report, and fourteen simulator steps. `@WorkflowStepRegistration` also acts as a Spring component annotation. A new step needs an action/registration plus any bag keys it consumes/produces; there is no compile-time flow contract.

Each invocation gets a fresh bag. `NONE` has no registered step and ends execution. Missing actions also end execution with the current implementation. See [backend review](backend-review.md) for actual flow sequences and defects; do not use these behaviors as recommended new design.

## Persistence and media

| Collection | Entity | Stored domain fields |
| --- | --- | --- |
| `sources` | `Source extends CommonEntity` | UUID, description, weight, status, full Telegram Message |
| `templates` | `Template extends CommonEntity` | UUID, areas, weight, status, full Telegram Message |
| `memes` | `Meme` | UUID, template UUID, ordered source UUID list, Telegram Message |
| `users` | `Author` | Long Telegram user ID, first/last name, optional username |

JsonDB discovers `@Document` entities under `models`. Review status values are exactly `REVIEW`, `APPROVED`, `REJECTED`; initial source/template weight is 10. Publication reduces weights toward 1 and trims meme history when it reaches 1000 in the current sending step.

- Source image layout: configured sources directory / UUID / `source.jpg`, `source.jpeg`, or `source.png`.
- Template image layout: configured templates directory / UUID / `template.jpg`, `template.jpeg`, or `template.png`.
- Template geometry stores 1-based area/source numbers, four integer corners, and background flags. The CSV header is exactly `Area,Source,TLx,TLy,TRx,TRy,BRx,BRy,BLx,BLy,Background`.
- Imported sources have no Telegram origin message. Current entity sorting derives dates from Message or zero.
- Files and JsonDB metadata are separate; neither source deletion nor create operations are application-level atomic across them.

## HTTP and browser integration

| Prefix / mapping | Main operations |
| --- | --- |
| `/api/sources` | Paginated/filter list; `/{id}.png` image and `/{id}.json` entity; delete; review; CSV import; search; reset weights |
| `/api/templates` | List, image/entity, delete, review, reset weights, initialize source IDs |
| `/api/admin` | Current GET `/post_meme` and `/create_backup` trigger side effects |
| `/api/login`, `/api/logout` | Spring Security form-login/logout processing |
| `/`, `/create` | Forward to gallery and template-creator pages |

`SecurityConfig` permits login/create assets and root JS/CSS, then requires authentication for other requests. API docs/Swagger and Actuator health are **not** explicitly permitted anonymously; enabling them in YAML does not override that filter chain. The authenticated admin account currently has role USER.

Cookies/sessions use `MapSessionRepository`, not database-backed sessions. Stage explicitly sets a domain; dev explicitly forces Secure. Prod uses the default cookie serializer. CSRF is currently disabled. Browser API calls are same-origin relative fetches. Keep existing endpoints, body shapes, callback strings, and Portuguese messages compatible unless the task intentionally changes them.

## Scheduling and side effects

- Meme publishing: `0 0,30 * * * *` — at minute 0 and 30 of each hour.
- Backup: `0 15 12 * * SUN` — Sunday at 12:15.
- Neither scheduled annotation sets a zone; the scheduler timezone applies. Birthday selection separately uses America/Sao_Paulo.
- Backups include JsonDB, templates, sources, then a statistics report, delivered to a configured Telegram backup chat. ZIP grouping targets 40 MiB of uncompressed items, not a guaranteed maximum output size.
- Application startup calls the live Telegram API and starts long polling. Registered schedules can publish and send backups. `bootRun` is **not** a harmless startup smoke test.

## Build and test commands

Run from repository root with Java 21:

- `./gradlew test` — existing unit suite.
- `./gradlew clean test` — clean baseline verification.
- `./gradlew clean build` — compile/test/package (the standard PR CI command).
- `./gradlew bootJar` — executable JAR under `build/libs/`.
- `./gradlew test --tests 'com.boatarde.regatasimulator.service.RouterServiceTest'` — targeted test example.

Tests passed without supplying runtime secrets or ImageMagick. Test reports: `build/reports/tests/test/index.html` and `build/test-results/test/`. The fully commented `BilubotApplicationTests.java` contributes no tests. IDE non-project diagnostics should be investigated via Gradle/Java workspace import, not fixed by changing valid package declarations.

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

A placeholder-only root `.env` was added during the review because none existed. Spring Boot does **not** automatically load dotenv files: supply variables through the process environment/IDE or deliberately configure loading in a future task. `.env` was not ignored by Git at review time; add it to the project's ignore rules before entering any real secrets. Placeholders cannot decrypt the existing encrypted configuration and are not a working local runtime setup.

## CI and deployment

- PRs targeting main run `clean build` and then `test` (usually redundant/up-to-date after build).
- Main pushes/manual dispatch build an executable JAR, optionally select environment for deployment, and copy it to one SSH server. Default deployment environment is stage.
- Startup shell code is generated inside `release-and-deploy.yml`; it is not a file under `scripts/`. It expects an external `subprocess` command and a fixed host Java path, runs a restart loop, and writes a wrapper PID.
- A prior JAR is backed up; startup is detected through log text. No automatic rollback or HTTP readiness smoke check is defined.
- Actual host layout, environment secrets, reverse proxy, and the external `subprocess` program were not verified.

See [backend improvement plan](backend-improvement-plan.md) for proposed changes. None of the suggested SQLite/repository/service redesign has been applied yet.