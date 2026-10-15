# Current manual template flow, legacy history and planned API cutover

The browser behavior and friction below describe the **old editor**, historically the `/create` default, not the shipped image-only Konva editor. The default editor is merged into `main`; the public release workflow shows successful build-and-test and deploy jobs for the approved `stage` production release, and the user reports completing deployment and successfully testing the live bot. `/create` and `/create/` forward to packaged `/create/editor/index.html`; `/create/editor` and `/create/editor/` remain available. `/create/legacy` and `/create/legacy/` forward to untouched old `/create/index.html`, with absolute `/create/index.js` and `/create/styles.css` still reachable. The new **Criador anterior** link points to `/create/legacy`. No file moves, resource-handler changes or security/server/host changes; the existing bot `/create` URL stays unchanged.

Current workflow contract: **@regatasimulatorbot menu Criar (`https://regsim.boatarde.dev/create`) → packaged Konva editor → prepare/copy data → manual document/caption → chat preview → submitter confirmation → administrator moderation → publication**. The menu launch is confirmed by user-provided BotFather/screenshots, not configured by checked-in code. Current legacy fallback: **Criador anterior → `/create/legacy` → old editor → the same bot workflow**. These remain unchanged until the approved first API-integrated cutover, which retires manual TEMPLATE ingress and the manual creator fallback rather than keeping two production flows. Backend submission/rendering/moderation contracts below describe today's runtime; the user's live-bot success report does not establish that every step was exercised. Core domain and safety rules remain in the [project guide](../project-map.md).

Next work follows the proposed [backend architecture](backend-architecture.md): **contracts/endpoints/payloads/responses/database/workflow plus an isolated ImageMagick/text/resource/security PoC → complete tested backend authentication/direct upload/versioned mixed image+text/repositories/recovery/meme generation → frontend image-only direct POST → frontend text areas/color controls**. This supersedes text/color-first development. Full backend mixed/text-only generation must be ready before frontend API integration; deploy backend phase 2 and frontend phase 3 together at the first API-integrated release, with downtime acceptable. The native preview button is optional and never gates final POST; permanent drafts are not required. Cutouts and bounded local draft restoration follow later. The [opt-in native harness](native-text-poc.md) in `spikes/native-text/` has executed its first bounded macOS generated-fixture slice: file-fed literal tests and native/Java2D→Magick mixed, shared-slot text-only and literal K=0 renders passed, alongside exact native image-only baseline and opaque/transparent planes; fractional-alpha Java-oracle parity was excluded. The 128-small-box sequential cases completed, but the 40MP native solid baseline was CAPACITY_REJECTED before text, not a passed boundary. See [maintenance](../maintenance.md#backend-feasibility-gate--first-local-slice-executed) for tool/font identities and unchanged native cache limits. Sampled RSS is not cgroup/VPS evidence; actual Linux AppRun/NoNewPrivileges proof remains. Keep Magick composition and investigate bounded Java2D text, without final engine selection or changes to hardening and the [host baseline](../deployment-setup.md#vps-layout-and-supervision) of heap 64m/256m, MemoryMax448M / MemorySwapMax64M. Auth, creator APIs, schema, production text/publication and recovery remain proposed; the early harness is not backend readiness.

Future changes retain maintenance-Java/frontend/browser/package/security gates and focused smokechecks; the completed release approval grants no ongoing permission to push/merge/deploy, change bot links or perform live operations. Rollback follows [the deployment runbook](../deployment-setup.md#release-and-rollback) using a data-compatible artifact/captured unit and verified recovery plan; an older JAR may lack `/create/legacy` and restores its original `/create`. Artifact rollback does not reverse storage writes.

## Approved API handoff and cutover — not implemented

Use a minimal authenticated multipart POST carrying the original image and a typed, versioned definition, plus an idempotency key bound to the verified actor/content. The same operation must return a stable receipt after a lost response; reuse with different content must conflict. Persist verified submitter/origin, notification destination and receipt explicitly, without fabricating a Telegram Message. Exact submission payloads, responses and persistence belong to the [proposed backend design](backend-architecture.md).

**Telegram-only authentication is decided:** verify the official raw signed `Telegram.WebApp.initData` server-side using Telegram's HMAC procedure, with integrity/freshness checks, before issuing an opaque short-lived session held in server memory. Keep its bearer in page memory and omit cookies. No OAuth/JWT platform or JWT library is needed for this few-friends hobby project. `initDataUnsafe`, client ID/platform, SDK presence, Referer and CORS are not identity proof. The shipped exact official `https://telegram.org/js/telegram-web-app.js` URL already supplies the current SDK; feature-gate client capabilities. Use a narrowly matched creator chain; any non-cookie CSRF exception stays there, while the existing ADMIN session/XOR-CSRF, CORS, cookie and frame policies remain unchanged.

The planned journey is **edit → optionally preview → submit/confirm in app → receipt for a REVIEW item → administrator moderation → publication**, with no extra chat Confirm. Administrator notification follows persistence; its failure must not erase the item/receipt or invite duplicate submission. The optional preview button calls authenticated `POST /api/creator/template-previews` with the same image/definition plus bounded **client-generated synthetic slot files/descriptions**, returning PNG bytes. It has no permanent drafts, database/history writes, Telegram effects, arbitrary URLs or real source selection. No preview request, successful preview or `previewId` is required for final submission. Fast client overlays may also help editing, but do not prove native rendering.

For mixed text, descriptions are required at normal source ingress, without fallback text. Bad/null legacy descriptions need explicit validation/migration handling rather than a new normal-user decision. Plan pt-BR with assistant-selected plain Liberation Sans/Serif/Mono at weights 400/700, pending production font packaging/licensing and complete rendering tests; the local PoC's pinned temporary fonts are not a packaged catalog. Shrink to fit with a bounded positive font-size floor of 1 px; impossible fits fail with a typed outcome, not truncation or default CLIP. **Text-only templates are fully valid approved scope:** a template background file plus 1–128 areas, all text, zero image areas. Dynamic text references contiguous `1..K` slots independently of image areas; preserve the image/text referenced-slot union and select-once reuse. All-literal `sourceSlot: null` means **K=0**, with no random-source pool requirement/selection or source-weight updates; normal template publication/weight/history uses an empty source list, never null/dummy Source. Original-pixel geometry/bounds and positive-area canvas overlap remain. Phase 2 must test shared-slot text-only and literal K=0 publication, optional client-synthetic preview (empty samples/no slot files for K=0) and both-store recovery. The local PoC covers rendering fixtures for both, not these production/publication/API/recovery requirements; phases 3 image-only and 4 text UI stay unchanged.

**Retirement timing is decided:** at the first API-integrated deployment, retire manual TEMPLATE CSV/document submissions and the manual creator fallback, including `/create/legacy`; do not plan a production dual-flow period or perpetual fallback. Coordinate backend phase 2 and image-only frontend phase 3 in the same release. Plan a maintenance window with stopped writers/service, a verified coherent backup of metadata/storage and media, migration/restore/rollback checks and explicit disposition of pending legacy template confirmations during downtime. Remove the old template-upload adapter/branch and manual creator fallback, updating guidance, routes, links and package guards together. Preserve stored legacy templates and applicable migration/import/export/recovery compatibility. **SOURCE uploads are separate and are not implicitly removed.**

Until that cutover, the manual workflow below remains unchanged, including original-Message ownership and stored-preview binding checks that fail closed. The direct web path must not weaken remaining legacy callback authorization or administrator approval. These are approved future design/cutover decisions, not current API availability, completed tests or authorization for live operations.

## 1. Historical legacy editor: open and prepare geometry

The user-provided BotFather/screenshots confirm the **Telegram menu button Criar** on **@regatasimulatorbot** opens **https://regsim.boatarde.dev/create**; that URL remains unchanged. Historically [WebConfig](../../src/main/java/com/boatarde/regatasimulator/configuration/WebConfig.java) forwarded `/create` to the old static page; the shipped routing contract makes that editor reachable through `/create/legacy` instead. [SecurityConfig](../../src/main/java/com/boatarde/regatasimulator/configuration/SecurityConfig.java) permits the creator assets; existing `/api/**` operations require ADMIN except the explicitly public login/CSRF endpoints. Public static GETs do not imply a public upload API or a general CSRF exemption.

The checked-in code does not create/configure that external menu or handle `/start` as a creator-launch command; configuration is now confirmed rather than unknown or an ordinary URL launch. The separate **Main Mini App** points to **https://regsim.boatarde.dev/** in fullscreen, an SDK-independent administration page the user rarely uses. Do not move it, change bot links or ask the launch questions again. [Official docs](https://core.telegram.org/bots/webapps#launching-mini-apps-from-the-menu-button) equate menu and inline WebApp launches, so signed `initData` is expected and the future backend must verify it. User-provided configuration evidence is not implemented/tested HMAC authentication or inspection of real initData.

The legacy frontend is [HTML](../../src/main/resources/static/create/index.html), [JavaScript](../../src/main/resources/static/create/index.js) and [CSS](../../src/main/resources/static/create/styles.css), with no framework build. `static/create2/` is empty, not an alternate working editor. Dependencies are Bootstrap **5.3.0**, Clipboard.js **2.0.11**, unversioned Toastify CDN assets and Telegram's hosted WebApp SDK. None has an SRI attribute in this page; the existing Spring CSP specifies frame ancestors, not a script-source policy.

`imageLoader.change`, paste and drop handlers read a file into a DataURL and load an `Image`. They **do not upload it**. Loading an image resets the areas to one full-canvas area. `+` clones the selected area's geometry/background and chooses a new source slot; it does not start drawing a fresh area.

Each `Area` stores TL/TR/BR/BL corners in **display-canvas coordinates**, a source slot, background boolean and color. `resizeCanvas()` fits the canvas to the container and scales the stored corners. `updateCoordinates()` divides by `scale`, rounds to integers and emits CSV. The source dropdown restricts choices based on adjacent areas, although the backend accepts non-adjacent reuse such as slots `[1,2,1]`.

`draw()` displays checkerboard transparency, the template and colored polygon overlays. Background polygons redraw the clipped template over the colored fill. This is **not a test meme**: no real source image is distorted, and overlay painting does not reproduce the renderer's grouped layer stack.

Telegram integration uses expansion, closing confirmation, theme parameters, viewport changes and a copy-success popup/close action. It does **not** send template data through `sendData()`, validate `initData`, render through HTTP or use the native main button for submission.

## 2. Copy CSV and manually upload the original image

`Copiar` copies the text in `#coordinates`, then prompts the user to paste it into the template description and send it to the bot. The exported format is:

`Area,Source,TLx,TLy,TRx,TRy,BRx,BRy,BLx,BLy,Background`

“Command” here is geometry CSV, **not an ImageMagick/shell command**. The user must separately attach the matching image as a Telegram **document/file** and paste this CSV as its caption. Sending an ordinary Telegram photo is not accepted by the current upload route; keeping a PNG as a document also avoids losing its transparency through photo processing.

[TelegramRouter.dispatch/upload](../../src/main/java/com/boatarde/regatasimulator/adapter/telegram/TelegramRouter.java) accepts private user messages containing documents declared PNG/JPEG. A caption beginning `source:` enters source submission; otherwise it is parsed as template CSV. Missing captions and malformed CSV are ignored by this routing path, so several mistakes produce no actionable reply. Later service failures receive a generic failure message.

[JsonDBUtils.parseTemplateCsv](../../src/main/java/com/boatarde/regatasimulator/util/JsonDBUtils.java) requires the exact header, 11 nonblank integer fields per row, background 0/1, at most 128 areas and a `64 * 1024`-character input bound. The legacy browser neither checks these limits nor predicts caption transport limits; this is not a description of the new editor's validation. Telegram's [Bot API](https://core.telegram.org/bots/api#senddocument) documents a 1,024-character outgoing document-caption limit; incoming user-client limits may differ. A 64K parser bound is not evidence that a large CSV can travel in a caption.

## 3. Validate, store and render the chat preview

[SubmissionService.submitTemplate/prepare](../../src/main/java/com/boatarde/regatasimulator/service/SubmissionService.java):

1. Generate a template UUID, validate geometry, prepare its owned media directory and download the Telegram file through the gateway.
2. Check filename extension and actual decoded PNG/JPEG bytes/dimensions, then validate geometry against the image.
3. Build a template in **REVIEW**, with initial weight and the **original upload Message**; send “Criando template...”.
4. Record submitter and template in the metadata unit of work, then call `MemeService.previewTemplate` outside that transaction.

[MediaValidation](../../src/main/java/com/boatarde/regatasimulator/util/MediaValidation.java) requires:

- Nonempty media ≤20 MiB, ≤40 million pixels and each dimension ≤10,000.
- 1–128 areas; area indices exactly `1..N` in list order.
- Distinct source slots form `1..K`; slots may repeat in any area order.
- Convex, nondegenerate quadrilaterals; corner coordinates bounded to ±10,000.
- Every area has **positive-area** image overlap. Partial off-canvas geometry is valid; merely touching an edge/corner or being fully outside is not.

The metadata transaction does not cover files or Telegram. Uncommitted, unreferenced media can be cleaned on failure. **After insertion, a failed render/send can leave a REVIEW item and media without a bound preview**; the service does not automatically erase a committed item. Drafts are not a separate state today.

[MemeService.previewTemplate](../../src/main/java/com/boatarde/regatasimulator/service/MemeService.java) selects approved sources using the distinct-slot count, weights, capacity-aware recent-history exclusion and birthday fallback. It renders, removes the progress message best-effort, sends a photo with Confirm/Cancel, and binds the returned chat/message identity while the item is still REVIEW. Preview does **not** decrement weights or record publication history.

## 4. What ImageMagick actually does

[ImageMagickRenderer](../../src/main/java/com/boatarde/regatasimulator/adapter/media/ImageMagickRenderer.java) owns an isolated private job directory. For each area, `buildDistortedSource()`:

1. Identifies template dimensions `W × H`.
2. Resizes the selected source to `W × H` with `!` (forced dimensions, not contain/cover).
3. Enables alpha/transparent virtual pixels and applies `-distort Perspective`, mapping `(0,0)→TL`, `(0,H)→BL`, `(W,0)→TR`, `(W,H)→BR`.
4. Builds a white quadrilateral mask on a black `W × H` canvas and applies it with `CopyOpacity`.

Preserve that pipeline when making previews; a crop library or a rectangular CSS resize is not equivalent. Off-canvas points are passed through unchanged, not clamped by the renderer.

`compositeFinalImage()` partitions the **ordered list**, preserving order within each group:

| Back to front | Rule |
| --- | --- |
| Background sources | All `background=true` areas, in area/list order |
| Template image | The uploaded template, including its alpha channel |
| Foreground sources | All `background=false` areas, in area/list order |

Later sources cover earlier sources **within the same group**. A background area added last still goes behind the template and all foreground areas. For mixed rows `F1,B2,F3,B4`, the effective stack is **B2,B4,template,F1,F3**. There is no arbitrary interleaving across the template plane.

Slot 1 means the first selected source for this render, **not a persisted source UUID**. Two areas referencing slot 1 use the same image; another render may select a different image. Distinct slots use the without-replacement source selection.

“Segundo plano” **does not remove template pixels**. Behind an opaque part of the template, that source remains hidden. PNG holes/transparency are what reveal the background source, explaining the user's external preprocessing step.

ImageMagick has per-command memory/map/disk/thread/time limits and a 30-second process-runner bound. These are not a whole-preview deadline or a global concurrency/admission budget. Scratch intermediates are cleaned; `RenderedImage` retains final output until delivery completes and the caller closes it.

## 5. Submitter confirmation, then administrator moderation

[ReviewCallbackService](../../src/main/java/com/boatarde/regatasimulator/service/ReviewCallbackService.java) checks item REVIEW status, the **original Message's actor/chat**, stored preview chat/message binding and a usable preview photo for confirmation. Same-item callbacks are process-locally locked. Unbound/legacy/null-origin callbacks fail closed.

- **Confirm:** send the preview/file ID and item identity to the administrator, clear the review binding and user keyboard. The template **remains REVIEW**; this is not approval.
- **Cancel:** delete the template/media through compensated deletion, then remove the preview message. Failed/uncertain deletion can retain `.delete-*` bytes for reconciliation.

REVIEW records already exist before confirmation, and the admin gallery/API is not a distinct “confirmed submissions only” state machine. Confirmation primarily routes the review request to the administrator; do not describe it as a database DRAFT→REVIEW transition.

[TemplateController.reviewTemplate](../../src/main/java/com/boatarde/regatasimulator/controller/TemplateController.java) exposes ADMIN-only `POST /api/templates/review`. [ModerationService](../../src/main/java/com/boatarde/regatasimulator/service/ModerationService.java) applies a REVIEW-only APPROVED/REJECTED transition and audit through the metadata unit of work. SQLite makes decision/audit atomic; JsonDB does not. Notification follows the commit, and failure does not undo the decision. Null-message items skip notification.

Once approved, the template becomes a publication candidate. `MemeService.publish()` renders/sends before committing source/template weight changes and history; send-only photo retries reuse that artifact.

## Historical legacy-editor friction and defects

| Observation | Evidence/impact |
| --- | --- |
| Copy → close → reattach → caption → preview → confirm | No creator upload/API integration; editing/testing requires leaving the editor |
| No pinch/pan/editor zoom; page zoom disabled | Meta viewport forbids scaling; touch handlers always use `touches[0]` and prevent default |
| Tiny corner crosshairs, ambiguous hit choice | 10px markers; nearest corner has no hit threshold; center-vs-corner distance chooses whole-area movement |
| Rapid touch endings can unexpectedly straighten geometry | Double-tap detection checks only elapsed time, not position, travel or finger count |
| Background checkbox can show the previous area's value | Area selection handler omits `updateBackgroundCheckbox()` |
| Valid backend capabilities cannot be edited | Browser clamps corners/movement to canvas and restricts non-adjacent source-slot reuse |
| Geometry coupled to viewport, rounded only for export | Resize mutates every corner; precision/drift should be characterized rather than assumed pixel-exact |
| No delete/reorder/undo/numeric precision/real preview | Only add, selectors, reset and copy are exposed; reset/image replacement loses work |
| Browser accepts more formats than server | `accept=image/*`, no file-size/pixel/convexity/overlap checks or decode error guidance |

Touch coordinate conversion **does** account for CSS canvas scaling. Whole-area movement checks **every** proposed corner; it is not a missing-boundary-check bug. The real gap is zoom/gesture arbitration, visual/model separation and usable hit targets.

The historical standalone-page inspection confirmed the legacy layout and disabled-zoom policy, but had no selected file or Telegram identity; it did not exercise real mobile behavior or a new live submission. Separately, the user reports successful testing of the new editor through the temporary HTTPS preview and, after deployment, on the live bot. The public release workflow shows successful build-and-test and deploy jobs. These support the reported shipped baseline, not an exact device/OS/client/keyboard/performance matrix, complete upload/confirmation/moderation coverage or native text/font rendering proof. Do not attribute the legacy defects above to the shipped editor. Existing [renderer](../../src/test/java/com/boatarde/regatasimulator/adapter/media/ImageMagickRendererTest.java), [submission](../../src/test/java/com/boatarde/regatasimulator/service/SubmissionServiceTest.java) and [callback](../../src/test/java/com/boatarde/regatasimulator/service/ReviewCallbackServiceTest.java) tests provide characterization starting points, not substitutes for focused validation of future editor changes.