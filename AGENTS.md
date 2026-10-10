# Agent guidance

## Scope and documentation

- This is a Java/Spring Boot modular monolith: Telegram bot, administration APIs, static browser pages, ImageMagick rendering, scheduled publishing, and backups.
- Put produced plans, research, reviews, and other Markdown in root `docs/`. Keep this `AGENTS.md` at repository root.
- Read [project map](docs/project-map.md) for current architecture/setup; [backend review](docs/backend-review.md) and [improvement plan](docs/backend-improvement-plan.md) contain proposals, **not implemented decisions**.
- [Live test results](docs/live-test-results.md) describe verified dev behavior and failures; that run used isolated storage, disabled schedules, and test-account destinations, not normal production configuration.
- [Phase 0 results](docs/phase-0-results.md) describe implemented characterization coverage/test seams. [Phase 1 callback results](docs/phase-1-callback-safety-results.md) and [Phase 1 reliability results](docs/phase-1-reliability-results.md) describe implemented safety/reliability. [Phase 2 web security results](docs/phase-2-web-security-results.md) record implemented configuration/HTTP security. Phases 3–5 and the service/SQLite migration remain proposed. Historical pending-Phase-1/2 statements are superseded by these results.
- Frontend redesign was deferred in the backend review. Do not expand a backend task into UI/framework replacement.

## Source boundaries

- Base package: `com.boatarde.regatasimulator`; main/test code lives under `src/main/java` and `src/test/java`.
- Configuration: `src/main/resources/application*.yml`; frontend source: `src/main/resources/static/`.
- Never edit generated `build/` resources/classes/reports or `.gradle/` caches. Stale build contents are not authoritative source.
- CI/deployment lives in `.github/workflows/`; the current startup script is generated in workflow YAML, not `scripts/`.

## Build and validation

- Use Java 21 and checked-in Gradle wrapper (currently 8.6). The repository pins OpenJDK 21.0.2 in `.tool-versions`; check actual toolchain before changing it.
- `./gradlew test` for normal verification; `./gradlew clean test` for a clean baseline; `./gradlew clean build` for full packaging/CI parity; `./gradlew bootJar` for the executable JAR.
- Target tests with `./gradlew test --tests 'fully.qualified.TestClass'`.
- Web client checks: `node --test src/test/js/web-security.test.cjs` (separate from Gradle); use `node --check` for edited browser JS. Final Phase 2 baseline: 735 JUnit cases / 33 suites and 5 Node cases.
- Tests use JUnit 5/Mockito, `@TempDir`, and a secret-free test profile; the isolated Spring context mocks bot/registration and dynamically overrides all storage paths. No real Telegram/ImageMagick is needed for `test`.
- Known-failure characterization cases are explicitly named/commented. Update their expectations alongside fixes; do not interpret them as desired future behavior or freeze missing authorization as a valid security contract.
- Java editor non-project warnings can be workspace-import problems; check Gradle output before changing valid packages to appease the editor.

## Runtime and data safety

- Do not start `bootRun`, register bots, publish memes, send backups, or deploy as routine verification. Startup invokes Telegram and schedules side effects. Use isolated test adapters/storage; explicitly requested live tests need a dedicated development bot/environment.
- Bot registration and schedules default on. Tests must disable `telegram.bots.regata-simulator.registration-enabled` and `regata-simulator.scheduling.enabled` **and** mock external calls; these switches do not disable manual mutations or Telegram health checks.
- Never read/write/delete production data to test a migration or cleanup. Copy data to isolated storage, back it up, validate import/restore, and document rollback first.
- Required runtime variables: `REGATA_SIMULATOR_ENC_PASSWORD`, `REGATA_SIMULATOR_DB_PATH`, `REGATA_SIMULATOR_SOURCES_PATH`, `REGATA_SIMULATOR_TEMPLATES_PATH`, `MAGICK_PATH`; select profile with `SPRING_PROFILES_ACTIVE`.
- `.env`/`.env.dev` are not automatically loaded by Spring Boot. Ensure local environment files are Git-ignored before entering real secrets. Do not print decrypted configuration, tokens, passwords, or token-bearing Telegram URLs.
- JsonDB collections are `sources`, `templates`, `memes`, and `users`. Images live in separate UUID directories. Imported sources may have no Telegram Message; preserve that nullable contract.
- Do not assume database transactions cover filesystem or Telegram effects. Explicitly handle partial failure, cleanup, and recovery.

## Change conventions

- Preserve four-space Java formatting, existing package layout, Lombok usage, and constructor injection; avoid unrelated reformatting.
- Keep public API paths/body shapes, Telegram callback format, template CSV format, and Portuguese messages compatible unless changing them is intentional and documented.
- Prefer typed use-case inputs/results, thin adapters, domain services, and reusable rendering/storage/Telegram boundaries for new work. Do not grow the custom workflow framework unnecessarily or rewrite it as a side effect of an unrelated task.
- While workflows remain, steps register with `@WorkflowStepRegistration`; verify transitions and required bag values. A fresh bag is created per invocation, and the runner is synchronous, not a durable/resumable workflow system.
- Separate submitter confirmation from administrator approval. Check actor authorization and allowed status transitions for callback and HTTP mutations.
- Preview callbacks require the original submitter, REVIEW status, and stored preview chat/message binding. Legacy/unbound items fail closed; never trust callback metadata to backfill ownership. Preserve the original upload Message, and update binding fields without saving stale full-entity snapshots. Same-item callback locks are process-local, not a cross-adapter/database transaction.
- Keep per-job scratch files isolated. Bound external process/network work, check exit codes, preserve interruption, and clean up on failure. Retain argument-array ProcessBuilder calls rather than shell interpolation.
- Rendering owns an owner-only scratch directory until `SendMemeStep` finishes delivery; never move scratch back into template directories or delete the final artifact before sending. Process pipe readers use platform threads to avoid Java 21 pinning. Current permissions target macOS/Linux; do not claim untested Windows portability.
- Preserve capacity-aware history exclusion, birthday fallback, and explicit `ApplicationFailure` outcomes. Only `NONE` successfully terminates a workflow; missing registrations, ambiguous routes, null transitions, and more than 64 transitions fail.
- Use valid generated ImageIO fixtures for successful upload/import tests. Validate byte/pixel bounds, CSV headers/limits, ordered contiguous indices/slots, convex corners and decoded image bounds. Import reports include every row outcome; normalization uses NFKC/Locale.ROOT. Compensate metadata before deleting referenced import media and retain uncertain failures for reconciliation.
- REVIEW-only moderation writes status/binding fields, not stale entity snapshots. Applied decisions survive notification failure; HTTP 204 may carry `X-Notification-Status: failed`. Null-origin items skip notifications. Repeated/lost REVIEW transitions are typed conflicts (HTTP 409); this does not add durable retries.
- Backup items remain indivisible; items/encoded archives above 40 MiB fail explicitly and all prepared ZIPs are cleaned. Exception-safe ZIP delivery is not a coherent live snapshot or whole-app restore guarantee.
- Treat CORS origins/cookie domains as deployable public configuration, not secrets. CORS is not authentication or CSRF protection; test security changes through the full filter chain.
- Phase 2 uses validated `regata-simulator.web` properties and one security CORS source: deny cross-origin by default, exact origin lists only. One Spring Session CookieSerializer defaults host-only/Secure/HttpOnly/Lax; local dev explicitly opts out of Secure, stage/prod cannot. Preserve Telegram frame ancestors; live authenticated iframe requirements remain unverified.
- HTTP APIs require ROLE_ADMIN except public login/CSRF token acquisition. CSRF is enabled with Security 6.2 XOR/session tokens; `/api/csrf` is no-store. Unsafe clients submit tokens and refresh after login/logout; never auto-replay mutations. Admin publish/backup are POST-only. API auth failures are generic 401/403 problems while pages redirect.
- Validate HTTP pagination/body inputs and use generic ProblemDetail errors (400/404/409/503/500). Return gallery/import DTOs, not persisted Telegram Message/preview bindings. Preserve minimal `message.from` name compatibility and byte-detected PNG/JPEG MIME on legacy `.png` URLs; `/import/report` exposes safe ordered row outcomes.
- Introduce SQLite, schema migrations, and dependency upgrades only in explicitly scoped changes with data migration/backup tests. Do not assume review proposals are already present.
- Update `docs/` and this guidance when architecture, setup, or verified commands change. Report what was tested and any real verification limits.