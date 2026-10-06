# Agent guidance

## Scope and documentation

- This is a Java/Spring Boot modular monolith: Telegram bot, administration APIs, static browser pages, ImageMagick rendering, scheduled publishing, and backups.
- Put produced plans, research, reviews, and other Markdown in root `docs/`. Keep this `AGENTS.md` at repository root.
- Read [project map](docs/project-map.md) for current architecture/setup; [backend review](docs/backend-review.md) and [improvement plan](docs/backend-improvement-plan.md) contain proposals, **not implemented decisions**.
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
- Existing tests use JUnit 5/Mockito and need no runtime secrets. Add regression/use-case tests for behavioral changes; current coverage is narrow and the application-context test is commented out.
- Java editor non-project warnings can be workspace-import problems; check Gradle output before changing valid packages to appease the editor.

## Runtime and data safety

- Do not start `bootRun`, register bots, publish memes, send backups, or deploy as routine verification. Startup invokes Telegram and schedules side effects. Use isolated test adapters/storage; explicitly requested live tests need a dedicated development bot/environment.
- Never read/write/delete production data to test a migration or cleanup. Copy data to isolated storage, back it up, validate import/restore, and document rollback first.
- Required runtime variables: `REGATA_SIMULATOR_ENC_PASSWORD`, `REGATA_SIMULATOR_DB_PATH`, `REGATA_SIMULATOR_SOURCES_PATH`, `REGATA_SIMULATOR_TEMPLATES_PATH`, `MAGICK_PATH`; select profile with `SPRING_PROFILES_ACTIVE`.
- `.env` is a placeholder convenience file, not automatically loaded by Spring Boot. Ensure it is Git-ignored before entering real secrets. Do not print decrypted configuration, tokens, passwords, or token-bearing Telegram URLs.
- JsonDB collections are `sources`, `templates`, `memes`, and `users`. Images live in separate UUID directories. Imported sources may have no Telegram Message; preserve that nullable contract.
- Do not assume database transactions cover filesystem or Telegram effects. Explicitly handle partial failure, cleanup, and recovery.

## Change conventions

- Preserve four-space Java formatting, existing package layout, Lombok usage, and constructor injection; avoid unrelated reformatting.
- Keep public API paths/body shapes, Telegram callback format, template CSV format, and Portuguese messages compatible unless changing them is intentional and documented.
- Prefer typed use-case inputs/results, thin adapters, domain services, and reusable rendering/storage/Telegram boundaries for new work. Do not grow the custom workflow framework unnecessarily or rewrite it as a side effect of an unrelated task.
- While workflows remain, steps register with `@WorkflowStepRegistration`; verify transitions and required bag values. A fresh bag is created per invocation, and the runner is synchronous, not a durable/resumable workflow system.
- Separate submitter confirmation from administrator approval. Check actor authorization and allowed status transitions for callback and HTTP mutations.
- Keep per-job scratch files isolated. Bound external process/network work, check exit codes, preserve interruption, and clean up on failure. Retain argument-array ProcessBuilder calls rather than shell interpolation.
- Treat CORS origins/cookie domains as deployable public configuration, not secrets. CORS is not authentication or CSRF protection; test security changes through the full filter chain.
- Introduce SQLite, schema migrations, and dependency upgrades only in explicitly scoped changes with data migration/backup tests. Do not assume review proposals are already present.
- Update `docs/` and this guidance when architecture, setup, or verified commands change. Report what was tested and any real verification limits.