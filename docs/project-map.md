# Project map and development baseline

Verified: **2026-10-10**. This describes the **current** source-complete local/offline candidate, not production adoption.

## Phase 5 completed local/offline checkpoint

Phase 5 adds coordinated SQLite/JsonDB/media recovery bundles, retention and validated fresh-target restore; read-only stopped-copy reconciliation; storage-before-bot startup, bounded/protected external health and minimal local probes; configurable schedules/shutdown; redacted telemetry/login throttling and moderation audit. Checked-in artifact deployment and Linux supervision replace the generated restart wrapper. See [operations runbook](phase-5-operations-results.md) for exact operator contracts and failure policies.

Final clean build: **828 JUnit cases / 42 suites**, zero failures/errors/skips, plus **18 Node tests**. Patched Temurin **21.0.12.1+1** passed the maintenance gate and explicit-only-toolchain `clean build --info`, proving compiler and Gradle Test Executor paths; old 21.0.2 also passed but is not release proof. Actual OSV **COMPLETE / 138 coordinates / 0 findings**, **2026-10-10T05:30:52.749Z**. Runtime upgrade locally committed as `679884e` (`build: migrate supported Spring runtime and preserve legacy encrypted properties`), after `54d9ad0` reconciliation and `4ea9544` quality follow-up; all commits are local, with no push or deployment. See [dependency assessment](phase-5-dependency-assessment.md).

These are isolated temporary-store/synthetic-PBE/ImageIO/fake-renderer/Telegram/host-fixture results, not production-data access, real ImageMagick, live bot/server, deployment, native/CDN security coverage or license clearance. Cooperative single-host boundaries and manual crash reconciliation remain; Linux unit/environment and independent backup storage require operator provisioning. Earlier checkpoint numbers below are historical.

## Phase 4 offline SQLite checkpoint (historical)

Phase 4's scoped implementation and synthetic offline rehearsal are complete: JsonDB remains default; explicit `regata-simulator.database.engine=sqlite` plus an absolute `sqlite-file` selects the four JDBC adapters. Xerial 3.53.4.0 loads measured SQLite **3.53.4**; Liquibase **4.33.0** owns versioned migrations under `src/main/resources/db/changelog/sqlite.sql`. WAL/FULL, per-connection foreign keys, bounded busy timeout and a two-connection Hikari pool target a single host/local disk, whose actual deployed suitability remains unverified.

`repository.sqlite` implements conditional field writes, positive weights, normalized source uniqueness, parameterized literal filters, deterministic SQL paging/counts and ordered history. `MetadataUnitOfWork` coordinates source/template weight changes with delivered history, and author/entity submission metadata, without transport/files inside the transaction. `migration.OfflineStoreCli`/Gradle `offlineStore` provide guarded, audited raw-JsonDB import and read-only SQLite→JSON/media rollback export without starting Spring. History keeps immutable IDs with nullable ON DELETE SET NULL links; geometry/full nullable Messages remain validated JSON.

Deletion stages media beside its original directory, restores after confirmed metadata failure, and retains uncertain stages for reconciliation. At Phase 4, SQLite legacy backup was blocked; configured Phase 5 capture now supersedes that block, not the old archive helper. See [Phase 4 results and offline operator commands](phase-4-sqlite-results.md) for historical policies/artifacts. Historical baseline: **725 JUnit cases / 32 suites**, zero failures/errors/skips; Node **5/5**. No live migration/cutover, user data, secrets or deployment was accessed.

## Phase 3 completed checkpoint

Typed repositories with JsonDB adapters, Telegram/media/render boundaries, and direct ping/report/backup/meme/submission/moderation/callback services are implemented in sequential local slices. Bot dispatch now uses `adapter.telegram.TelegramRouter`; scheduler publication/backup and HTTP moderation use typed services. There is no registered production workflow runner/step graph. Persisted nullable Telegram messages and Phase 2 DTO/security contracts remain compatible. See [Phase 3 service results](phase-3-service-results.md).

Media-boundary consumption, central application failures and administrator/scheduled origin separation are implemented. Active replacement regression coverage and full-chain submission→callback→moderation integration are implemented; all obsolete workflow/route sources and duplicate harnesses are physically removed, including the old compatibility exception (52 verified Git deletions). The [removal manifest](phase-3-cleanup-removal-manifest.md) is historical and resolved. Phase 3 is **complete** for its scoped acceptance items; its historical final clean build was **702 cases / 25 suites**, zero failures/errors/skips, plus **5 passing Node cases**. Historical 914/40 totals included 15 obsolete duplicate suites. Phases 4–5 extend persistence/recovery without restoring the old workflow framework.

## Stack and repository layout

- Java 21; `.tool-versions`/CI target Temurin **21.0.12.1+1**.
- Gradle wrapper **8.14.6**, official distribution SHA-256 validation, Groovy build; Spring Boot **4.1.1**.
- Framework **7.0.9**, Security **7.1.1**, Session **4.1.1**, Tomcat **11.0.26**, Jakarta validation, Actuator and in-memory sessions; Lombok.
- Jackson 2 **2.21.7** (persistence/Telegram/preferred HTTP), Jackson 3 **3.1.7**; springdoc **3.1.1**. Boot 4 is a separate tested security-maintenance migration, not a service rewrite.
- Telegram Bots **6.9.7.1**, JsonDB **1.0.115-j11**, OpenCSV **5.12.0**, native Jasypt **1.9.3**, Tika **3.3.2**. The flagged Jasypt starter/GCM dependency is removed, not suppressed.
- Opt-in SQLite: Xerial JDBC 3.53.4.0, Liquibase 4.33.0, Boot-managed Spring JDBC/Hikari.
- External ImageMagick executable selected by `MAGICK_PATH`.

| Path | Purpose |
| --- | --- |
| `src/main/java/com/boatarde/regatasimulator/` | Application sources |
| `src/main/resources/application*.yml` | Shared configuration and dev/stage/prod profiles |
| `src/main/resources/META-INF/` | Additional Spring configuration metadata |
| `src/main/resources/static/` | Browser pages/assets served by Boot |
| `src/test/java/com/boatarde/regatasimulator/` | JUnit Jupiter/Mockito tests |
| `.github/workflows/` | PR validation and release/deployment |
| `docs/` | Review, research, plans, and other produced Markdown |
| `scripts/` | Bounded OSV scanner, checked-in artifact deployment and Linux systemd user unit |
| `build/`, `.gradle/` | Generated outputs/caches, not source |

There is no npm/package-manager frontend build. Node built-in tests and the OSV script run separately from Gradle tests and are CI gates. The gallery uses Alpine.js and Bootstrap; the template creator integrates the Telegram Web App SDK, Toastify, and Clipboard.js. CDN/native dependencies remain outside the Maven OSV inventory. Edit `src/main/resources/static`, never generated resources.

## Backend package map

All package paths below are relative to `src/main/java/com/boatarde/regatasimulator/`.

| Package / class | Responsibility |
| --- | --- |
| `RegataSimulatorApplication` | Boot entry point; initializes five JsonDB collections before conditional bot registration |
| `bots/RegataSimulatorBot` | Telegram long-polling adapter forwarding updates to `adapter.telegram.TelegramRouter` |
| `adapter/telegram/` | Strict command/upload/callback parsing, creator checks and redacted failures; `BotTelegramGateway` builds Telegram methods |
| `application/` | Central `ApplicationFailure`, typed Telegram/media/render boundaries and submission origin |
| `repository/`, `repository/jsondb/`, `repository/sqlite/` | Domain contracts and conditional JsonDB/SQLite implementations; database-only unit of work |
| `migration/` | Offline import/export, coordinated recovery capture, fresh-target restore and inspection-only reconciliation |
| `adapter/media/` | Persisted media lookup/lifecycle and isolated owned ImageMagick rendering |
| `service/PingService`, `ReportService`, `BackupService` | Direct pong/statistics/backup orchestration without synthetic Updates |
| `service/MemeService`, `SubmissionService` | Explicit publication/previews and typed uploads with ordered persistence/cleanup |
| `service/ReviewCallbackService`, `ModerationService` | Submitter confirmation/cancellation distinct from administrator REVIEW decisions and notification outcomes |
| `service/SourceService`, `TemplateService` | Repository-backed gallery, media loading, deletion and weight maintenance |
| `service/SourceImporterService` | Validated bounded CSV import, typed row report, normalized batch deduplication/compensation, using bilu-tags file identity |
| `service/BackupService` | Complete local recovery capture/retention before Telegram document/report delivery |
| `service/ScheduledTaskService` | Scheduled meme generation and backup; also called by administration endpoints |
| `controller/` | SourceController, TemplateController, AdminController, public CsrfController, generic ProblemDetail advice and byte-detected image responses |
| `configuration/` | Security/session/CORS, native legacy-PBE binding, storage, scheduling, health, cooperative media boundary/telemetry |
| `models/` | Persisted entities, geometry, status, gallery/review bodies |
| `dto/` | CSV row, validated search request, minimized gallery/origin/import-report HTTP projections |
| `util/` | Telegram methods/file transfer, JXPath builders, CSV/weighted selection, filesystem/ZIP helpers |

### Direct service contracts

There is no workflow runner, enum/action registry, object-valued bag or annotation-driven step graph in production sources. HTTP, Telegram and scheduler adapters call typed application services directly; new work should extend explicit use cases and shared boundaries rather than restore the removed framework.

Preview versus publication is explicit, destinations/origins are typed, and failures use `application.ApplicationFailure`; Telegram senders receive redacted Portuguese messages. Strict callback parsing/ownership/binding and conditional REVIEW writes remain in place. SQLite coordinates metadata across repositories, but these synchronous services do not provide durable retries or transactions covering filesystem/Telegram. See [Phase 5 results](phase-5-operations-results.md) for current verification, [Phase 4 results](phase-4-sqlite-results.md) for historical persistence evidence and [Phase 1 reliability results](phase-1-reliability-results.md) for historical safety fixes.

## Persistence and media

| Collection | Entity | Stored domain fields |
| --- | --- | --- |
| `sources` | `Source extends CommonEntity` | UUID, description, weight, status, original Telegram Message, nullable preview chat/message IDs |
| `templates` | `Template extends CommonEntity` | UUID, areas, weight, status, original Telegram Message, nullable preview chat/message IDs |
| `memes` | `Meme` | UUID, template UUID, ordered source UUID list, Telegram Message |
| `users` | `Author` | Long Telegram user ID, first/last name, optional username |
| `audits` | `ModerationAudit` | Immutable UUID, item/actor identity, decision time/status, single nullable notification outcome |

JsonDB discovers `@Document` entities under `models`; SQLite maps the same entities through JDBC. Current SQLite schema **3** adds immutable moderation audit; read-only offline readers accept validated historical schema 2 with empty audits and never migrate it. Review status values are exactly `REVIEW`, `APPROVED`, `REJECTED`; initial source/template weight is 10. Publication through `MemeService` reduces weights toward 1 and history inserts before trimming to 1000. SQLite weights/history/retention and REVIEW/audit writes use metadata transactions; JsonDB remains nontransactional across collections.

- Source image layout: configured sources directory / UUID / `source.jpg`, `source.jpeg`, or `source.png`.
- Template image layout: configured templates directory / UUID / `template.jpg`, `template.jpeg`, or `template.png`.
- Template geometry stores 1-based area/source numbers, four integer corners, and background flags. The CSV header is exactly `Area,Source,TLx,TLy,TRx,TRy,BRx,BRy,BLx,BLy,Background`.
- Imported sources have no Telegram origin message. Current entity sorting derives dates from Message or zero.
- Preview callbacks require the original submitter, REVIEW state, and the exact persisted preview chat/message identity. Successful confirmation consumes the binding without approving the record. Legacy/unbound previews fail closed. Binding metadata is updated with JsonDB field operations; process-local callback locks prevent concurrent successful replays, not all HTTP/multi-process races. See [callback-safety results](phase-1-callback-safety-results.md).
- Files and metadata remain separate. Deletion stages and restores/retains media on failure; creation retains existing compensation. Neither is a database/filesystem transaction or automatic crash recovery.
- Rendering scratch is job-local outside persisted media, with owner-only permissions. `ProcessRunner` bounds execution/output and checks exit status; `RenderedImage` transfers final output/job ownership to `MemeService` through delivery/persistence. Upload/import validation uses actual decoded PNG/JPEG bytes and bounded convex geometry/CSV. See [reliability results](phase-1-reliability-results.md) for numeric limits and recovery policies.
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

`SecurityConfig` permits login/create assets, root JS/CSS and `/api/csrf`; other APIs require ROLE_ADMIN, while protected browser pages redirect to login. The existing encrypted plaintext admin configuration is BCrypt-encoded at startup through an explicit encoder. Only `/actuator/health/liveness` and `/actuator/health/readiness` are anonymous local probes; other Actuator paths require ADMIN. API docs/Swagger remain authenticated, not automatically public because YAML enables them.

Cookies/sessions use `MapSessionRepository`, not database-backed sessions. One real CookieSerializer defaults host-only/Secure/HttpOnly/Lax across profiles; dev YAML explicitly permits local HTTP, stage/prod refuse insecure cookies. Validated `regata-simulator.web` properties control origins, credential grants, Telegram frame ancestors, cookie domain/Secure/SameSite. One security CORS source defaults to denying cross-origin API access; relative browser calls need no grant. Hosting/proxy and authenticated iframe cookie requirements remain unverified.

CSRF is enabled using Security **7.1.1** session/XOR tokens, retaining the Phase 2 contracts. Existing fetch helpers acquire tokens for unsafe methods and refresh after XHR login/logout (204); API auth failures are generic 401/403 problems rather than redirects. Bounded process-local login throttling returns generic 429. HTTP validation bounds pagination/query/review fields, and safe ProblemDetail maps invalid/missing/conflict/unavailable/internal outcomes to 400/404/409/503/500. Gallery/import DTOs omit full Telegram messages and preview bindings while preserving `message.from` names and geometry. Legacy `.png` image URLs serve actual PNG/JPEG byte-detected MIME. See [Phase 2 results](phase-2-web-security-results.md) for historical contract changes and [operations results](phase-5-operations-results.md) for current boundaries.

## Scheduling and side effects

- Meme publishing: `0 0,30 * * * *` — at minute 0 and 30 of each hour.
- Backup: `0 15 12 * * SUN` — Sunday at 12:15.
- Both cron expressions and their zone are configurable under `regata-simulator.scheduling`; zone defaults to `America/Sao_Paulo`, also used by birthday selection.
- Both stores use configured Phase 5 recovery capture: metadata and copied media under a cooperative single-JVM barrier, then bounded packaging/retention and delivery outside it. Require an existing private nonoverlapping `regata-simulator.backup.local-directory`; retention count defaults to 7. Indivisible items/encoded ZIPs above 40 MiB fail explicitly. Complete local bundles survive delivery failure; no exactly-once remote delivery or crash-proof guarantee.
- Application startup calls the live Telegram API and starts long polling. Registered schedules can publish and send backups. `bootRun` is **not** a harmless startup smoke test.
- Phase 0 added explicit default-on switches: `telegram.bots.regata-simulator.registration-enabled` and `regata-simulator.scheduling.enabled`. Tests set both false and mock Telegram; disabling these alone does not disable manual operations/remote health calls.
- `TimeConfig` supplies a Clock for birthday rules; JsonDBUtils offers RandomGenerator overloads, and the shared renderer/ProcessRunner retain injectable process seams. History exclusion preserves required capacity, birthday pools fall back when insufficient, and subprocess execution is bounded.
- Storage initializes before registration; transport/external health are bounded. Anonymous `/actuator/health/liveness` and `/actuator/health/readiness` report local lifecycle state only, without Telegram/storage/render checks. Protected external health includes Telegram. Graceful shutdown defaults to 30s per phase. Redacted synchronous operation IDs/metrics are not durable jobs or a configured alert system.

## Build and test commands

Run from repository root with Java 21:

- `./gradlew test` — unit, characterization, and isolated Spring-context suite.
- `./gradlew clean test` — clean baseline verification.
- `./gradlew clean build` — compile/test/package (the standard PR CI command).
- `./gradlew bootJar` — executable JAR under `build/libs/`.
- `./gradlew test --tests 'com.boatarde.regatasimulator.adapter.telegram.TelegramRouterTest'` — targeted test example.
- `node --test src/test/js/*.test.cjs` — **18** web-security/deployment/dependency-scan tests, separate from Gradle and included in CI.
- `./gradlew verifyMaintenanceJava clean build` — patched Java release/CI gate; isolate toolchain paths as documented in the operations runbook when proving compiler and test JVM identity.
- `./gradlew dependencyScan` — actual bounded public OSV Maven gate and retained inventory/notices under `build/dependency-security/`; findings/incomplete results fail.
- `restoreBundle`, `inspectRecoveryStages`, `offlineStore` — no-Spring offline tasks with explicit stopped-copy acknowledgment and fresh nonoverlapping outputs; exact placeholder commands are in the operations/Phase 4 runbooks.

The Phase 5 suite has **828 passing cases across 42 suites**, plus **18 passing Node tests**, zero failures/errors/skips, with no runtime secrets, real Telegram or ImageMagick dependency. Historical baselines: Phase 4 725/32 + 5 Node; Phase 3 702/25 (replacing intermediate 914/40); Phase 2 735/33, Phase 1 649/29, callbacks 590/24, Phase 0 300/23. Process tests launch safe Java children; fixtures use generated ImageIO images and fake rendering. Tests use real file-backed SQLite and temporary JsonDB, mocked external boundaries and temporary paths. Reports: `build/reports/tests/test/index.html`, `build/test-results/test/`. See [Phase 5 verification](phase-5-operations-results.md). IDE non-project diagnostics are workspace-import issues, not reasons to change valid packages.

## Runtime environment and local safety

Required variables referenced by shared YAML:

| Variable | Use |
| --- | --- |
| `REGATA_SIMULATOR_ENC_PASSWORD` | Native legacy-PBE bridge password for ENC configuration |
| `REGATA_SIMULATOR_DB_PATH` | JsonDB data directory |
| `REGATA_SIMULATOR_SOURCES_PATH` | Source image root |
| `REGATA_SIMULATOR_TEMPLATES_PATH` | Template image root |
| `MAGICK_PATH` | ImageMagick executable path |
| `SPRING_PROFILES_ACTIVE` | Select dev, stage, or prod Telegram/profile values |

Web credentials and profile bot tokens/IDs are encrypted in configuration; no decrypted values should enter documentation or logs. Use a dedicated development bot and isolated directories outside source/build outputs. Do not assume profile selection disables scheduling or bot registration: it currently does not.

The bridge preserves legacy native PBE format (1,000 iterations, random salt/IV), not a recommended new KDF. Synthetic tests cover lazy decryption, original backing maps/`MapPropertySource` type and cause-free `Error` refusal to prevent Boot opaque-source fallback. No production ciphertext/key was read or rewritten for Phase 5. Backup storage must be explicitly provisioned; verification did not change `.env`, global Java or runtime data.

A placeholder-only root `.env` was initially added during the review. Development setup subsequently moved to the user-created `.env.dev`, with the supplied development-only decryption key and local storage/ImageMagick settings. The 2026-10-07 readiness check confirms `.env.dev` exists, is Git-ignored, and is not tracked; the earlier live-test warning about its ignore status is historical.

Spring Boot does **not** automatically load dotenv files: supply `.env.dev` variables explicitly through the process environment/IDE. Never print or commit the decryption key. Loading the dev environment alone does not make startup side-effect-free: use registration/scheduling opt-outs, isolated storage, and mocked or dedicated test destinations as appropriate. The live test found a mismatch between dev source metadata and image paths; that dataset issue has not been repaired by configuring the environment or adding Phase 0 tests.

## CI and deployment

- PR/release CI validate wrapper integrity and patched Java, run `clean build`, all Node fixtures and fail-closed `dependencyScan`; retain inventory/licenses/OSV evidence for 30 days. RuntimeClasspath is strictly locked; test/plugin graphs are not claimed locked.
- Main pushes/manual dispatch use the once-built executable artifact, full commit SHA release identity and SHA-256 transfer verification. Default deployment environment is stage; no push/deployment was performed for this local scope.
- `scripts/deploy-release.sh` and `scripts/regatasimulator.service` replace the generated `subprocess`/log-text wrapper. Confirmed systemd writer stop precedes atomic artifact selection; bounded local readiness failure selects/rechecks a previous same-schema artifact while reporting failure. Binary rollback never restores/undoes database writes.
- Linux user unit requires preprovisioned `%h/.config/regata/runtime.env`, `%h/.local/share/regata` and patched `/usr/bin/java`; root/unit/Java/port agreement, writable data/backup paths and user-manager lifecycle need operator verification. SSH uses pretrusted `SERVER_KNOWN_HOSTS`, strict host checking and bounded calls, not opportunistic key discovery or secret transfer.
- macOS tests use fake Java/systemctl/curl/rename commands. Real host/unit permissions, deployment, proxy/TLS, bot, native ImageMagick and production cutover remain unverified. See the operations runbook before any live action.

See [backend improvement plan](backend-improvement-plan.md) for completed scoped acceptance, [Phase 3 service results](phase-3-service-results.md) and [Phase 4 results](phase-4-sqlite-results.md) for history, and [Phase 5 operations/runbook](phase-5-operations-results.md) for current recovery/deployment procedures. Phase 5 is complete locally/offline; production cutover remains unperformed.