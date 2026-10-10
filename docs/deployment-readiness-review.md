# Deployment readiness review — 2026-10-10

## Decision

**Local runtime gate now passed; production host/data prerequisites remain.** Following this initial review, the user authorized a local `dev` test with real Telegram MCP tools. The [Boot 4 live dev run](boot-4-live-dev-results.md) passed final-artifact startup, real Telegram commands/render/backup, actual dev credential binding, HTTP security and captured JsonDB restoration/native rerendering. A separate staging server is not required. This is not automatic approval of an unverified Linux production setup or production-data recovery.

The initial review inspected source, the current lockfile, Git history, saved verification reports, editor diagnostics and passive Telegram SDK bytecode/dependencies. Its initial scope did not start an app or use real credentials. The subsequently authorized live run resolved dev credentials only in memory and used isolated neutral fixtures/private test-account destinations. Neither step deployed, pushed, changed runtime code, accessed production data or reran the full build/scan.

## Verified evidence

- Starting HEAD: `0d7fd7a`, with runtime upgrade `679884e`. The worktree was clean. Although the editor reported a lockfile change notification, `git diff -- gradle.lockfile` showed no content difference from the committed candidate.
- Saved clean-build reports: **828 JUnit cases / 42 suites**, zero failures/errors/skips. Previous verification established compilation and test execution on checksum-verified Temurin **21.0.12.1+1**.
- Previous separate client/deployment/scanner fixture run: **18 passing Node tests**.
- Saved actual OSV report: **COMPLETE / 138 Maven coordinates / 0 findings**, timestamp **2026-10-10T05:30:52.749Z**. This does not cover native ImageMagick, JDK/CDN exposure, unknown advisories, reachability or license clearance.
- Recovery/migration tests exercise actual temporary JsonDB/SQLite, hashes, permissions, audit/history preservation and failure paths. Telegram delivery and sample rendering/host supervision remain fake boundaries in those tests.
- The current Telegram registration path uses the one-argument `TelegramBotsApi(DefaultBotSession.class)` long-polling constructor. Passive bytecode inspection does not support treating a Jersey webhook startup failure as a proven defect in this path. It also does not prove live registration, transport or shutdown work with the final artifact.

## Preconditions before production

### 1. Align the deployment script and actual service

`scripts/deploy-release.sh` checks `${JAVA_BIN:-/usr/bin/java}`, but `scripts/regatasimulator.service` runs `/usr/bin/java`. A custom checker path does not change the service executable. Verify patched Java at the unit's actual executable, or provision a reviewed unit override and make both paths agree.

The workflow's `SERVER_ROOT` is configurable, but the checked-in unit uses `%h/.local/share/regata`. These paths must agree, as must incoming/releases/current layout and ownership. The script probes `127.0.0.1:8080`; changing the runtime port requires changing and retesting the probe. An unrelated healthy listener on that port is not proof the new service is healthy.

Validate Linux user-manager lifecycle/lingering, unit hardening, writable paths, memory limits, permissions, pinned SSH host keys, private runtime environment, proxy/TLS and firewall policy. No real Linux host has been validated by the macOS deployment fixtures.

### 2. Local integrations verified; check intended production configuration

The dedicated dev bot, actual encrypted-property binding, cold start, HTTP login/CSRF/default CORS/cookies, long-poll replies, ImageMagick rendering/delivery, backup capture/delivery and graceful shutdown now passed with the final artifact on patched Java. Preserve this local isolated rehearsal model; no separate stage server is needed. Production profile destinations, secrets, proxy/cookies, native executable and existing metadata/media paths still need explicit checking.

The October 6 live report predates Boot 4; use the October 10 report for current live evidence. Anonymous readiness remains a local lifecycle check, not storage/render/Telegram readiness. Live uploads/inline callbacks, browser/WebView behavior and production identities were not verified by this run. Do not let local rehearsals register the production bot or publish into normal channels.

### 3. Provision and rehearse recovery

Configure an existing private, nonoverlapping `regata-simulator.backup.local-directory`; its default is blank and does not provision a usable backup destination. Verify independent failure-domain storage, capacity, indivisible 40 MiB item/archive limits, retention and protected access.

Fresh isolated restoration and actual ImageMagick rerendering now passed for the neutral JsonDB store. This is not proof that existing production metadata/media match or that an older binary can safely read new writes. Preserve coherent metadata/media snapshots and uncertain deletion stages. Artifact rollback is schema-compatible binary selection, not undoing database writes or schema migrations. Establish the previous compatible release and explicit freeze/recovery procedure before rollout.

### 4. Separate database cutover from artifact rollout

JsonDB remains the default. Do not turn on SQLite merely because its implementation is complete. Any live SQLite adoption requires a stopped coherent export, anomaly review, validated import, post-write rollback policy and confirmation of single-host local-disk suitability. Existing preview buttons without trusted binding metadata intentionally fail closed.

## Issues with the process and remaining diagnostics

- Offline tests and a zero-finding Maven scan provide useful bounded evidence, not an end-to-end production certification. Earlier progress messages were incomplete/badly formatted; use current Git history and the final results documents rather than those messages as release evidence.
- Editor diagnostics still include workspace-import/package warnings and some genuine style findings in retained helpers/tests. A project-wide clean Sonar quality gate was not established. These are not interchangeable with Gradle compilation errors; blindly changing valid packages or subprocess-fixture stdout/stderr would damage the tests.
- The deployment Java/root/port constraints are configuration hazards if provisioned inconsistently, not unconditional failure of the documented default setup. They must be explicitly checked rather than assumed.
- No new runtime defect requiring a source change was confirmed by the bounded review or exercised local live flows. Remaining host/production-data checks and explicit live-test coverage limits still matter; a passed local smoke test is not whole-host deployment certification.

See [operations runbook](phase-5-operations-results.md), [dependency assessment](phase-5-dependency-assessment.md), and [implementation plan](backend-improvement-plan.md) for exact contracts and historical evidence.