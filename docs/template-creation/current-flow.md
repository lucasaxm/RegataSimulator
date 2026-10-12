# Current template creation: end to end

This describes the active implementation, not the intended redesign. Core domain and safety rules remain in the [project guide](../project-map.md).

## 1. Open the creator and prepare geometry

The user opens `https://regsim.boatarde.dev/create` through the bot's Create Template button. [WebConfig](../../src/main/java/com/boatarde/regatasimulator/configuration/WebConfig.java) forwards `/create` to the static page. [SecurityConfig](../../src/main/java/com/boatarde/regatasimulator/configuration/SecurityConfig.java) permits the creator assets; existing `/api/**` operations require ADMIN except the explicitly public login/CSRF endpoints. Public static GETs do not imply a public upload API or a general CSRF exemption.

The checked-in code does not create/configure the launch button or handle `/start` as a creator-launch command. Do not infer the current launch type from the word “button”; menu configuration may be external.

The frontend is [HTML](../../src/main/resources/static/create/index.html), [JavaScript](../../src/main/resources/static/create/index.js) and [CSS](../../src/main/resources/static/create/styles.css), with no framework build. `static/create2/` is empty, not an alternate working editor. Dependencies are Bootstrap **5.3.0**, Clipboard.js **2.0.11**, unversioned Toastify CDN assets and Telegram's hosted WebApp SDK. None has an SRI attribute in this page; the current CSP specifies frame ancestors, not a script-source policy.

`imageLoader.change`, paste and drop handlers read a file into a DataURL and load an `Image`. They **do not upload it**. Loading an image resets the areas to one full-canvas area. `+` clones the selected area's geometry/background and chooses a new source slot; it does not start drawing a fresh area.

Each `Area` stores TL/TR/BR/BL corners in **display-canvas coordinates**, a source slot, background boolean and color. `resizeCanvas()` fits the canvas to the container and scales the stored corners. `updateCoordinates()` divides by `scale`, rounds to integers and emits CSV. The source dropdown restricts choices based on adjacent areas, although the backend accepts non-adjacent reuse such as slots `[1,2,1]`.

`draw()` displays checkerboard transparency, the template and colored polygon overlays. Background polygons redraw the clipped template over the colored fill. This is **not a test meme**: no real source image is distorted, and overlay painting does not reproduce the renderer's grouped layer stack.

Telegram integration uses expansion, closing confirmation, theme parameters, viewport changes and a copy-success popup/close action. It does **not** send template data through `sendData()`, validate `initData`, render through HTTP or use the native main button for submission.

## 2. Copy CSV and manually upload the original image

`Copiar` copies the text in `#coordinates`, then prompts the user to paste it into the template description and send it to the bot. The exported format is:

`Area,Source,TLx,TLy,TRx,TRy,BRx,BRy,BLx,BLy,Background`

“Command” here is geometry CSV, **not an ImageMagick/shell command**. The user must separately attach the matching image as a Telegram **document/file** and paste this CSV as its caption. Sending an ordinary Telegram photo is not accepted by the current upload route; keeping a PNG as a document also avoids losing its transparency through photo processing.

[TelegramRouter.dispatch/upload](../../src/main/java/com/boatarde/regatasimulator/adapter/telegram/TelegramRouter.java) accepts private user messages containing documents declared PNG/JPEG. A caption beginning `source:` enters source submission; otherwise it is parsed as template CSV. Missing captions and malformed CSV are ignored by this routing path, so several mistakes produce no actionable reply. Later service failures receive a generic failure message.

[JsonDBUtils.parseTemplateCsv](../../src/main/java/com/boatarde/regatasimulator/util/JsonDBUtils.java) requires the exact header, 11 nonblank integer fields per row, background 0/1, at most 128 areas and a `64 * 1024`-character input bound. The browser neither checks these limits nor predicts caption transport limits. Telegram's [Bot API](https://core.telegram.org/bots/api#senddocument) documents a 1,024-character outgoing document-caption limit; incoming user-client limits may differ. A 64K parser bound is not evidence that a large CSV can travel in a caption.

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

## Verified friction and defects

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

The shared standalone page confirmed the public layout and disabled-zoom policy, but had no selected file or Telegram identity. Real mobile behavior and a new live submission were not exercised. Existing [renderer](../../src/test/java/com/boatarde/regatasimulator/adapter/media/ImageMagickRendererTest.java), [submission](../../src/test/java/com/boatarde/regatasimulator/service/SubmissionServiceTest.java) and [callback](../../src/test/java/com/boatarde/regatasimulator/service/ReviewCallbackServiceTest.java) tests provide characterization starting points, not a proof that a new editor works.