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
| `src/main/resources/static` | Legacy browser source, including unchanged `/create` |
| `frontend/template-creator` | New image-only Konva editor, package/lockfile, esbuild build and model/media/Telegram/browser tests |
| `spikes/template-creator` | Separate unchanged mixed-area comparison, never packaged |
| `src/test/java`, `src/test/js` | Java regressions and separate Node fixtures |
| `.github/workflows`, `scripts` | CI, dependency scanning and artifact deployment |

Do not edit generated `build/` resources/classes/reports or `.gradle/`. Preserve four-space Java formatting, Lombok, constructor injection, Portuguese messages and public HTTP/Telegram/CSV contracts unless intentionally changing them. The old runner/step/route framework is removed; do not restore it. `ReviewCallbackStepsTest` covers active adapters despite its historical name.

## Build and local verification

Use Java 21 and the checked-in Gradle wrapper. Release/CI requires patched Java; see [maintenance](maintenance.md).

- `./gradlew test` — normal Java verification.
- `./gradlew clean build` — clean compile/test/package; `./gradlew bootJar` — executable JAR.
- `./gradlew frontendCheck` — editor syntax and model/media/controlled Telegram unit tests via npm check/test; browser/audit/JAR checks remain separate.
- `./gradlew test --tests 'fully.qualified.TestClass'` — focused regression.
- `node --test src/test/js/*.test.cjs` — browser/deployment/scanner fixtures, separate from Gradle.
- `node --check path/to/edited.js` — browser JS syntax.
- `./gradlew verifyMaintenanceJava clean build` and `./gradlew dependencyScan` — release gates.

Tests use JUnit Jupiter/Mockito, real temporary JsonDB/SQLite and generated ImageIO fixtures; external bot/render boundaries are mocked. Update known-failure characterizations when fixed; do not preserve missing authorization as valid behavior. Java editor non-project/package warnings can be import problems: check Gradle before changing valid packages. Reports live under `build/reports/tests/test` and `build/test-results/test`, not documentation timelines.

### Image-only editor: current first slice

`frontend/template-creator/` is implemented source, not just a proposal or the spike. It uses vanilla JavaScript and **Konva 10.7.1 only at browser runtime**, with esbuild 0.28.2 and Playwright 1.64.0 as build/test tools. Package/lockfile and CI Node **22.23.1** are authoritative. Gradle `installCreatorDependencies` uses `npm ci --ignore-scripts --include=dev --include=optional`; retain optional platform binaries rather than enabling lifecycle scripts. `buildCreator` emits hashed self-hosted JS/CSS, shell, manifest/inventory and Konva notice into `build/generated-resources/creator/static/create/editor/`. `processResources` packages them in the executable JAR and removes stale editor hashes. There is no handwritten editor shell under `src/main/resources/static`, canvas-library CDN or Node/npm VPS runtime requirement.

Public **`/create/editor/`** (also forwarded without the trailing slash) is the separate image editor/manual-send candidate. First improve/replace the legacy editor experience while preserving bot document/caption upload, submitter confirmation and administrator review; later iterate text/eyedropper/native preview/direct submission. Do not wait for all slices 4–6 to deliver the useful accepted image-only tool. No canonical **`/create`** change or live deployment now: retain legacy fallback until phone/Telegram acceptance and separate operator release. Public static access/SDK UX introduces no upload/identity APIs or relaxation of ADMIN/CSRF/CORS/frame policy.

For standalone development, install with `npm ci --ignore-scripts --include=dev --include=optional --prefix frontend/template-creator`, then `npm start --prefix frontend/template-creator`. Open **http://127.0.0.1:4174/create/editor/**; this fixed loopback server rebuilds the editor and serves only its assets, without starting Spring, registering the bot or enabling schedules. See [maintenance](maintenance.md#editor-build-and-verification) for the independent checks.

Current candidate contracts: images open centrally, by empty-canvas tap/click, local paste or file drop; tools/camera stay hidden before loading. Selection/editing is automatic (no Select button), with contextual **Forma / Precisão / Camadas**, draw/pan, numeric/nudge, duplicate/delete/reorder, source/background and undo/redo. One `#fit` **Ajustar** fits image plus off-canvas areas; no separate **Áreas** fit button. Confirmed **Recomeçar áreas** keeps the image and creates one full-image rectangle in one undoable action. **Preparar envio / Dados do template / Copiar dados** avoids user-facing “CSV” and guides manual original-document/caption submission. Geometry remains pure original-image pixels, independent of viewport/DPR/Konva JSON. Rectangle corners fix opposites, edges move pairs, perspective permits independent corners/paired-edge translation. Anchored conversion uses ghost/Apply/Cancel and one undoable commit. Overlays are guides, not native previews.

`index.html` includes official async **https://telegram.org/js/telegram-web-app.js**; `telegram.mjs` handles already/late load, ready/expand, theme/viewport, gated closing confirmation and copy-success feedback with explicit **Voltar ao bot**, never auto-close. Missing/failed/old/throwing SDK must preserve standalone editing/export/manual copy. Platform/initData UI detection is not authentication; no external APIs or production-bot tests. Only this exact remote script exception is allowed; application JS/CSS and Konva stay self-hosted.

SDK packaging is integrated: `build.mjs`/`verify-package.mjs` allow exactly one external `src` at that official URL and no remote `href`; the JAR guard also checks `manifest.hostedIntegrationScripts`. Local server CSP permits only that exact remote script URL. Spring's existing frame-ancestor/security policy is unchanged, with no global CSP rewrite. `build.mjs --check` and Gradle `buildCreator` explicitly include `telegram.mjs`; `npm test` includes model/media/controlled Telegram suites, run by CI through `frontendCheck`. Node/npm/dependency versions remain unchanged. The app uses the minimum of browser visual height and valid Telegram stable height, cancelling gestures before hosting viewport changes without mutating canonical coordinates. Doubles/emulation are not overall-green evidence or phone/Telegram acceptance.

CSV remains image-only with the existing 11-column header. The editor accepts ordered contiguous **Area** indices starting at **0 or 1**, exporting **1-based** rows; backend validation still requires **1..N**. Source slots remain contiguous from 1 with repeats supported. The adjacency picker retains imported original values outside its narrower choices. Keep convex geometry within ±10,000 and positive-area image overlap; off-canvas points are not clamped.

Local PNG/JPEG input is limited to 20 MiB, 40MP and 10,000 pixels per dimension. The initial header read is at most 1 MiB; JPEG metadata/dimensions must fit within that window before SOS. Before creating an object URL or decoding, the private async `inspectPngMetadata(file, head)` walks PNG chunk headers through IEND, including eXIf after IDAT and beyond the initial window. It skips IDAT payloads, reuses cached header bytes when they fit, and limits the walk to 2,048 chunks and 1 MiB of inspected headers/EXIF bytes. IEND must end exactly at EOF; duplicate eXIf and non-1 EXIF orientation are rejected. Normalize/save a copy before placing points; the editor does not reorient/reencode originals.

Full `createImageBitmap` raster decode is mandatory, dimensions must match, and temporary bitmaps are closed, including late completion after timeout/cancellation. Corrupt PNG pixels can pass image onload/`decode()` yet fail bitmap decoding; the full bitmap decode provides decode verification without an additional 1×1 canvas probe. Unsupported browsers fail closed. The shared 15-second deadline covers image loading and full bitmap decoding, not header/metadata I/O; it provides neither a hard browser allocation bound nor guaranteed decoder cancellation. Future color sampling uses the separately planned bounded scratch canvas.

Use generated fixtures, never private/user images or `spikes/example/`; leave them untouched. Text/color eyedropper, native preview, direct submission, identity/drafts and durable recovery are later work, not image-only release prerequisites. Real Android Brave/iOS/Telegram WebView, keyboard/performance acceptance and separately authorized operator release remain required. After every delivered editor slice refresh `npm start --prefix frontend/template-creator -- --lan` and provide newly printed reachable phone URLs on trusted Wi-Fi; default/CI stays loopback-only. Documentation-only work launches no server. The [proposal](template-creation/proposal.md) retains later design. Moveable stays confined to the unchanged spike.

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

`BotTelegramGateway.sendPhoto` uses a small `TelegramPhotoSender`: at most three SDK send calls, connection-failure backoff of 1/2 seconds, and at most 10 seconds of total waiting. An explicit 429 honors positive `retry_after` only within that budget; missing/excessive delays fail instead of retrying early. Only definite DNS/TCP-connect failures retry (generic socket failures require a known connect-stage frame). Read/reset/SSL failures, 5xx/permanent responses and unclassified errors are not replayed. Interruption stops delivery and preserves the flag.

Retries retain the **same photo/file**, not source selection or rendering. Publication metadata runs once after successful send; exhausted sends clean scratch without history/weight changes, and metadata failure after sending never triggers resend. Previews using `sendPhoto` share this policy; other gateway operations remain single-call. This is bounded synchronous recovery, not a durable queue, an SDK-independent end-to-end deadline or an exactly-once guarantee.

## HTTP/security integration

- `/api/sources`, `/api/templates`: gallery/filter/image/DTO/review/delete/import/weight operations. Preserve controller body/path shapes, pagination validation and generic ProblemDetail 400/404/409/503/500 outcomes.
- `/api/admin/post_meme`, `/api/admin/create_backup`: **POST-only side effects**. Login/logout: `/api/login`, `/api/logout`; public token acquisition: `/api/csrf` (no-store).
- APIs require ROLE_ADMIN except login/CSRF; generic API 401/403, browser redirects, protected Swagger. DTOs omit full Messages/bindings; preserve minimal `message.from` names and byte-detected MIME on legacy `.png` URLs.
- CSRF is session-bound/XOR: obtain a token for unsafe methods, refresh after login/logout and never automatically replay mutations. Sessions are in-memory; one active login and bounded process-local throttling (429).
- One CORS source denies cross-origin access by default; exact origins only. CORS is not auth/CSRF protection. Cookies default host-only/Secure/HttpOnly/Lax; dev explicitly permits HTTP, stage/prod must not. Preserve Telegram frame ancestors; test WebView/iframe behavior instead of speculatively weakening SameSite.
- Anonymous liveness/readiness report lifecycle only, not storage/render/Telegram health. Other Actuator paths require ADMIN; external Telegram health is bounded/cached/redacted.
- Low-cardinality operation/outcome telemetry and protected actor/time audits are visibility, not durable retries/outboxes/exactly-once delivery. See [partial-failure recovery](operations-runbook.md).

## Future work boundaries

The template editor's first image-only slice is implemented; broader frontend redesign and the remaining [creator slices](template-creation/proposal.md#incremental-delivery-and-acceptance) are separate scope. Durable retries, cross-process coordination, stronger credential encryption, distributed throttling and alerts require explicit design; synchronous services/cooperative locks do not already provide them. Keep [maintenance](maintenance.md) and operations guides current when contracts change.