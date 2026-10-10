# Boot 4 live development verification — 2026-10-10

## Outcome

**Passed for the exercised flows.** Local `dev` verification with real Telegram and ImageMagick satisfies the controlled live-smoke gate; a separate staging server is not required. No application defect was observed in this run. Production host/configuration and production-data recovery remain separate operator checks, not an automatic deployment approval.

## Environment and safety

- Starting repository HEAD `3d78ee8`; runtime source upgrade `679884e`. Tested the existing `build/libs/RegataSimulator-0.0.1.jar`, SHA-256 `3d39fc7934864141f8f25ebed46abac05e01d7f2abfc09ccd16e459d66a9692a`. Its hash was unchanged after testing. No runtime source, dependency or configuration file was edited.
- Boot **4.1.1**, isolated Temurin **21.0.12.1+1**, ImageMagick **7.1.1-47**, JsonDB default persistence, macOS x86_64. ImageMagick's successful behavior is not a native vulnerability assessment.
- Explicitly sourced the existing Git-ignored `.env.dev`. Actual dev encrypted properties were resolved in memory through the current native PBE bridge. Tokens/passwords/decrypted configuration were not printed or recorded in helpers/report.
- Verified the actual identity is `@regatasimulatordevbot` before registration. The connected Telegram MCP user account was the sole creator/channel/backup destination. This is intentional test authorization, not validation of normal production identities/destinations.
- Every startup explicitly set `server.address=127.0.0.1`, port 8080, `regata-simulator.scheduling.enabled=false`, JsonDB engine, isolated database/media and an existing private backup root. No scheduled publishing, production bot or normal channel delivery was enabled.
- Fresh neutral ImageIO fixtures: seven approved colored sources, one null-origin REVIEW source and three approved single-area white templates; valid PNGs and convex geometry, 400×300. Original dev/production data was not used or modified. The historical missing-source dataset was not silently repaired.
- Private evidence root: `/private/tmp/regata-boot4-live.KQcPs1` (mode 700). It contains isolated metadata/media, a complete recovery bundle, restored store, downloaded delivered photo, restored render, private logs and temporary Java helpers. These are not checked-in application/test code or a new supported launcher.

## Observed checks

| Check | Result |
| --- | --- |
| Registration-disabled preflight using final artifact classes | Real dev credential binding, bot identity and protected external Telegram health passed |
| Unmodified executable JAR, registration enabled | Cold start succeeded; anonymous readiness 200/UP; listener bound only to `127.0.0.1:8080` |
| MCP `/ping` | Message 55 → reply 56, `pong! (0s)` |
| MCP `/report` | Message 57 → reply 58, 8 sources / 3 templates |
| MCP `/meme` | Message 59 → reply 60, actual 400×300 Telegram photo |
| Publication persistence | One meme-history record; source weight sum 80→79, template sum 30→29 |
| Actual delivered media | Downloaded through real Telegram gateway after restore, decoded by ImageIO, dimensions/color/frame checked and visually inspected: blue rectangle in white frame |
| MCP `/backup` | Message 61 → five attachments 62–66 and report 67; ZIP attachment independently confirmed as `application/zip`, size matching local artifact |
| Retained recovery bundle | COMPLETE marker, manifest and three ZIP parts under `backup-e97b74a9-60c9-4801-8100-12bd0df1d6f6`; local bundle survived successful delivery |
| Writer freeze / offline restore | SIGTERM stopped the test JVM and closed the listener before the exact artifact's `RestoreCli` ran with explicit acknowledgment and a fresh target |
| Fresh restore | `RESTORED_VERIFIED` matched the manifest hash; 8 sources, 3 templates, 1 meme, 1 audit, 0 users and 11 image files preserved |
| Restored real runtime | Booted restored JsonDB/media with registration/schedules disabled; read original history/Telegram Message and rerendered its composition with actual ImageMagick |
| Restored render ownership | 400×300 PNG with exact expected center color and white frame; visually checked; `RenderedImage.close()` removed its owned scratch directory |
| Shutdown / cleanup | SIGTERM exit 143 was intentional; HTTP graceful shutdown completed. Restored helper closed its context and exited 0. Port 8080 was closed at final verification |

### Real HTTP security and moderation

These checks used the real loopback HTTP server/filter chain and configured dev credentials, with an in-process Java HTTP client (not a browser credential bridge):

- Anonymous source API **401**; default cross-origin preflight **403**.
- `/api/csrf` **200 / no-store**; dev session cookie **HttpOnly / SameSite=Lax / not Secure** as explicitly configured for local HTTP.
- CSRF-bearing configured login **204**, authenticated gallery **200**.
- Invalid page zero **400**, missing UUID **404**, GET on POST-only publication endpoint **405**.
- Missing CSRF and stale pre-login CSRF on a review mutation each **403**.
- Refreshed-token rejection of the null-origin source **204**, persisted REJECTED; repeated review **409**, one audit preserved through backup/restore.
- Gallery DTO hid preview bindings and full chat metadata. Protected external health **200**.
- CSRF-bearing logout **204**; subsequent protected API **401**.

## Verification boundaries

- No separate stage server, production SSH/systemd/proxy/TLS validation, production deployment, push, SQLite cutover or artifact rollback to an older binary was performed.
- Restore used the retained local bundle, not a bundle reconstructed from downloaded Telegram attachments. Delivery and archive type were checked separately; off-host recovery remains an operator responsibility.
- No live JPEG/PNG document submission, preview confirm/cancel button click, second-actor callback test, browser/WebView cookie check, cron execution, concurrency/failure-injection test or large-media backup was attempted. Automated regressions remain their existing evidence; this report does not relabel synthetic callbacks as live Telegram tests.
- The exposed MCP file-send schema has no force-document flag and no callback-click operation was exposed. This run used MCP for identity/commands/replies/media inspection, and temporary artifact-backed helpers for credential-safe HTTP checks and restored native/media verification.
- This did not rerun the Gradle build or OSV scan. Their previous **828 cases / 42 suites** and **COMPLETE / 138 coordinates / zero findings** remain the baseline, not new results. `node --test src/test/js/*.test.cjs` was rerun: **18 passed / zero failed/cancelled/skipped**.
- Private fixtures/evidence were retained; no live test JVM or credential bridge was left running. Runtime environment variables were cleared from the working shell. Documentation links/diffs were validated before local commit.

See [readiness review](deployment-readiness-review.md) and [operations runbook](phase-5-operations-results.md) for production Java/unit/root/port/backup configuration and recovery contracts. The [October 6 live report](live-test-results.md) is historical and does not describe this final Boot 4 run.