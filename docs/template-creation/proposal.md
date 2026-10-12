# Proposed creator experience and delivery plan

**Design recommendation, not an implemented API/schema or deployment approval.** Start from the [verified current flow](current-flow.md). Keep this a focused template tool inside the existing static-page/Spring architecture, not a site-wide React replacement or general Photoshop clone.

## Target journey

**Choose image → mark/edit areas → test in place → send for approval.**

1. Open from Telegram; identity is established invisibly when supported. A standalone browser can edit/export, but must open through Telegram to submit without an additional login design.
2. Select/drop/paste the image once. Draw a rectangle, then adjust four corners for perspective. Add/duplicate areas and assign source slots visibly.
3. Press **Ver prévia**. See the server-rendered result in the app, return to editing without losing anything, and optionally change the example sources.
4. Press **Enviar para aprovação** once for the exact previewed revision. This is submitter confirmation. Show a submission receipt and “Aguardando aprovação”; do not require another Confirm click in chat.
5. Keep administrator approval/rejection and Telegram notifications. Do not promise a faster approval time or automatically approve ordinary submissions.

The normal path has no CSV copying, manual document reattachment or caption composition. CSV export/import remains an advanced compatibility/fallback action. Editor-only delivery is useful, but **does not by itself solve the multi-step submission problem**.

## Precision editor

### Interaction contract

- **Single-pointer:** drag an explicit corner handle to edit that corner; drag the selected polygon's interior to move it. Empty-space gestures never move an arbitrary nearest corner.
- **Two-pointer:** pinch/pan the viewport. A second pointer suspends/cancels shape editing; lifting a pointer must not resume a stale drag or trigger rectification. Handle pointer capture/cancel and WebView interruptions explicitly.
- **Desktop:** wheel/trackpad zoom around pointer, pan mode/Space-drag, arrow nudges of one original-image pixel, Shift for a larger step; do not intercept typing in coordinate inputs.
- **Visible alternatives:** zoom +/−, Fit image, 100%, selected-corner X/Y fields and a mobile nudge pad. Zoom is not the only precision method.
- Keep corner targets approximately **44 CSS px** at any zoom with smaller visual markers; use an offset magnifier on mobile so the finger does not obscure the pixel being placed. Labels/shape outlines supplement color.
- Add/delete/duplicate, explicit straighten-to-rectangle, undo/redo and reorder controls. Undo groups a gesture as one action; no hidden destructive double-tap. Clear/replace-image prompts protect dirty work.

### Coordinate and layering model

Maintain a small, library-independent document model: image identity/dimensions, stable area IDs, four original-image points, source-slot references, background flag and order. Keep viewport transform and selection separate. Never persist Konva/Fabric scene JSON as the domain contract.

Points live in **original-image pixels**, not screen/CSS/device pixels. Pan, zoom, rotation of the device and container resize change only the view. Invert the viewport transform for input; quantize once to the existing integer contract, and validate the quantized geometry before preview/export/submit. Keep TL/TR/BR/BL point correspondence stable rather than re-sorting points by screen position.

Provide early validation for convexity, degeneracy, contiguous indices/slots, bounds and positive canvas overlap; the server remains authoritative. Support intentional off-canvas points up to ±10,000, with a visible canvas boundary and Fit areas/panning to reach outside handles. Do not reintroduce clamping as a “safety” feature.

Show a layer list with **Atrás do template → Template → Na frente do template**. Within each editable group, provide move-forward/backward or reorder plus keyboard/buttons, not drag-only controls. A position change across the fixed template plane is an explicit background change. Serialize compatible indices after reorder.

Use **Source 1**, **Source 2**, etc., with a visible “reuse same image” relationship and stable slot colors. Adding an area offers **reuse a source / new source** instead of silently assigning another random image. Deletion renumbers used slots contiguously without breaking references; list order and slot identity are different concepts. The same slot remains visibly linked even in non-adjacent areas.

### Layout and visual direction

The canvas is the product, not a decorative hero. On mobile: compact image/tools row, maximum remaining viewport for canvas, collapsible selected-area sheet, and one native Telegram main action. On desktop: canvas plus a narrow area/layer inspector. Hide CSV by default and keep a fallback browser action when the native button is unavailable.

Use Telegram's dynamic theme variables, system font stack (matching the host rather than another font download), 14–16px controls and clear Portuguese labels. Default fallback tokens: surface `#1e1e1e`, panel `#242326`, text `#ffffff`, secondary text `#a6a6a6`, action `#738fed`, invalid/destructive `#ee686f`; verify contrast for actual Telegram themes. Make selection distinctive with labeled handles and linked slot badges, not ornamental gradients/cards.

Respect stable viewport height and safe/content-safe insets with older-client fallbacks. Feature-detect/version-check optional fullscreen, swipes, haptics and native buttons. Disable Telegram vertical swipes only while editor gestures conflict, not throughout unrelated screens. Allow browser accessibility zoom; constrain touch behavior to the editing surface. Numeric fields, focus order, screen-reader area descriptions and non-gesture alternatives are required.

## Library comparison

The inspected upstream licenses are MIT; retain notices and review the **selected version and its dependencies** before shipping. Documentation examples are capability evidence, not a mobile benchmark or a dependency lock.

| Candidate | What it supplies | Work still required | Recommendation |
| --- | --- | --- | --- |
| **Konva** | Canvas scene graph, polygons/images/layers, hit-testing, drag support, inverse transforms; documented pinch and pointer-centered zoom recipes; vanilla JS support | Four independent point controls, gesture arbitration, validation, undo/document model. Its standard Transformer is affine, not four-corner perspective image warping | Preferred foundation for this focused polygon editor |
| **Fabric.js** | Editable polygon controls, object selection and viewport transforms; demonstrated editing under zoom/translation | Perspective image rendering is not supplied by polygon point editing; custom controls/gestures and broader object-model integration | Good alternative if broader image/object editing becomes central |
| **Moveable** | DOM/SVG draggable/warpable/pinchable controls; four-direction perspective handles and CSS `matrix3d` output | World-coordinate extraction, pan/zoom coordination, multiple-area selection, masks/stacking and backend parity; generated API docs are older, so verify the selected package API | Compare against Konva in the spike when ready-made perspective handles minimize effort |
| Rectangle cropper | Mature rectangular crop/rotation interaction | Cannot represent four independently positioned corners, repeated slots or the template's two planes | Not a replacement for our geometry editor |

Do not install all three or add React only to obtain a canvas library. Pin/self-host the chosen library with license notices, or use a narrowly scoped build with a lockfile if packaging requires it. Existing Maven scanning does not cover new npm/CDN dependencies: add the appropriate inventory/security gate. Do not broaden CSP/frame ancestors or weaken admin/CSRF security to accommodate the editor.

**Spike decision gate:** implement the same skewed quad with four handles, zoom/pinch/pan, one reused source and an off-canvas corner in Konva and Moveable. Compare code/adapter complexity, exported points, low-end phone latency, finger occlusion and Telegram gesture conflicts. Promote one candidate; discard the other prototype. Do not claim either library solves native pixel rendering or the whole submission workflow.

## Direct upload and preview architecture

### Telegram authentication, not admin login

[Telegram Mini Apps](https://core.telegram.org/bots/webapps) distinguish launch types:

- `sendData()` sends at most **4,096 bytes**, closes the app, and is available only for **reply-keyboard launches**. It cannot carry a PNG/JPEG. Keyboard launches also lack the same signed `initData` context used by menu/inline launches.
- Menu launches behave like inline-button launches and provide identity/query data. `answerWebAppQuery` can send an inline result on the user's behalf; it is a server Bot API method, **not a file upload from the browser**.
- A standalone `/create` visit does not establish a Telegram identity. Calling methods on an available SDK object is not proof of authentication.

Confirm the actual configured launch button using the dedicated dev bot. Prefer a private-chat menu/inline launch supporting signed identity; any launch-mode change must be explicit. Do not make authentication depend on `initDataUnsafe`, a submitted user/chat ID, arbitrary `web_app_data` text or possession of a draft UUID.

Exchange **raw initData in a POST body/header**, never a logged URL, for a short-lived creator-scoped credential. Verify Telegram's documented signature/HMAC for the configured bot, constant-time comparison, bounded parsing, duplicate fields, required user identity and `auth_date` age/future skew. Bootstrap freshness does not prevent every replay: bound session lifetime and implement operation-specific deduplication/ownership. Never expose the bot token or persist creator credentials with drafts.

Use the existing server-configured bot credential for HMAC verification, not a browser-supplied key or another bot's token. Set explicit validation/session TTLs in the authentication slice; expired sessions require a safe reauthentication/reopen path that preserves the local draft. Token rotation must invalidate associated creator credentials, but rotating production bot credentials is not part of this stage.

Preferred HTTP mode: a separate, narrowly matched **stateless bearer-authenticated creator chain**, with the bearer retained only in page memory and cookies omitted. Every draft/image/preview/submission request checks the verified actor. Any CSRF exception must be confined to that non-cookie chain; retain the existing session/XOR-CSRF ADMIN chain untouched. If implementation instead chooses cookie sessions, keep CSRF and prove authenticated Telegram Web/iframe cookie behavior without broadly changing SameSite/CORS. Do not mix the two authentication modes accidentally.

### Proposed use cases and routes

These are provisional new routes, **not existing endpoints**:

| Route | Responsibility |
| --- | --- |
| `POST /api/creator/session` | Validate Telegram launch data; issue creator-only identity/credential |
| `POST /api/creator/template-drafts` | Bounded multipart image + typed JSON geometry; return owned draft/revision |
| `PUT /api/creator/template-drafts/{id}` | Update owned geometry/edited image with expected revision |
| `POST /api/creator/template-drafts/{id}/preview` | Render that revision with explicit sample mode; return preview identity |
| `GET /api/creator/template-drafts/{id}/preview/{previewId}` | Read owner-only cached output; no rendering on GET |
| `POST /api/creator/template-drafts/{id}/submit` | Confirm the matching previewed revision; return stable submission receipt |
| `DELETE /api/creator/template-drafts/{id}` | Discard owned unsubmitted draft and scratch safely |

Keep `/api/templates/**`, source gallery, publish/backup and moderation ADMIN-only. Thin adapters call typed draft/preview/submission use cases sharing `MediaValidation`, `MediaStorage`, `ImageRenderer`, repositories and metadata boundaries. Refactor `SubmissionService.Upload`'s Telegram-only file-ID requirement behind a media acquisition boundary; do not make HTTP clients invent a Telegram file ID.

Editing stays local; upload when first requesting a server preview, not on every pointer move. Preserve the local draft on network/auth/render failures. Preview uses a fixed example set for that revision; “Trocar exemplos” is explicit. Offer numbered synthetic sample images by default, with real approved-source examples only under an explicit content-access policy matching the bot's intended behavior. Never reveal REVIEW/rejected sources, raw Messages or filesystem paths through preview APIs.

Server preview runs the **same perspective/mask/layer pipeline**, without publication weights/history writes or Telegram messages. The editor overlay can remain instant. Native preview is explicit, cached and revision-bound; do not promise live native rendering at 60fps. Reduced-resolution preview copies may use uniformly scaled points and must be labeled; submitted geometry/image remain full-resolution and revalidated.

Fetch private preview bytes with the creator Authorization header and display an owned blob/object URL, revoking it on replacement. An ordinary image `src` does not attach a bearer header; do not solve that by placing credentials in image URLs or making previews public.

Bind previews to server-owned image hash, canonical integer geometry/order/background/slots, draft revision and sample identity. Changes invalidate the previous preview. Final submit confirms those bytes/geometry, not a stale preview or arbitrary client status. Serialize same-draft finalization against edits, discard/expiry and concurrent submit; a server-assigned stable submission identity/dedup binding returns the same receipt after a lost response rather than inserting twice. A reused key with different content conflicts. The exact persistence design must be covered by the origin/schema slice below.

Drafts are **not templates in REVIEW**. Keep a separately bounded, expiring private draft store, outside configured approved media and backup roots; no automatic promotion on preview. Define owned cleanup, active-job leases, quotas and restart behavior. Local restoration is best-effort (bounded IndexedDB image/geometry storage with clear/discard controls and expiry), not cross-device or crash-proof saving. Telegram CloudStorage/DeviceStorage size limits make them unsuitable as the sole store for allowed template image sizes.

Unsubmitted scratch is not part of permanent template backup/restore; after restart/expiry the UI must recover from its local copy or explain that it needs reupload. Submitted media/identity/receipts are permanent recovery data, even when notifications failed. Mark the local draft submitted only after obtaining/reconciling its receipt, and retain the dedup result long enough for lost-response reconciliation. Cleanup must never remove promoted media or an active renderer's files.

### Ownership/origin is a real compatibility change

Current `CommonEntity.message`, `SubmissionOrigin`, callback authorization and moderation notifications assume a real Telegram upload Message. Simply storing `message=null` loses notifications/current user attribution; setting a fabricated `message.from` to bypass callbacks is not acceptable.

Introduce explicit verified submitter identity, submitted time, origin type and permitted notification destination for direct web items; retain untouched nullable original Messages for legacy/imported/Telegram items. Resolve attribution through a shared accessor/projection, not a forged transport envelope. Web-origin final submission does not need legacy submitter preview buttons; old Telegram callbacks retain their original-message/binding requirements.

Update both repository adapters, author filtering/order and safe gallery DTO attribution, submission receipts, moderation notification routing, audits and offline snapshot/export/import/restore validation. If new SQLite columns/schema markers are needed, deliver a separately tested migration/recovery slice; do not assume the artifact-only deployment acknowledgment permits a schema change. JsonDB and old/null-origin round-trips must remain valid. Reuse existing moderation status values: avoid adding DRAFT to permanent template status solely to host editor scratch.

Persist the REVIEW item/author/confirmation metadata before attempting administrator notification. If notification fails, return **submitted, notification failed**, keep the receipt/item visible in the admin queue and never invite a duplicate upload. Optional bot/user receipts must not turn persistence success into apparent failure. No exactly-once Telegram delivery or durable notification retry is implied; those would need another design. Submitted/approved templates become immutable through the creator API; later editing starts a new proposal or an explicitly designed revision flow, not live media replacement.

### Resource and media safety

- Mirror byte/pixel/area/geometry limits in the client; validate actual bytes again on server. Ignore supplied paths and storage names; generate owned UUID paths. Bound multipart parts/metadata at proxy, servlet and application levels.
- Handle unsupported formats/decode errors, picker cancellation and racing file selections explicitly. For a newly selected image, normalize orientation **once before placing points**, producing a canonical oriented bitmap/encoded upload with matching dimensions. Account for browser decoders that already honor EXIF, rather than rotating twice; ImageMagick must not orient it again. If canonicalization changes bytes/format, recheck encoded size and explain any limit failure. Replacement requires a dirty-work prompt and a new coordinate space unless explicit remapping is designed. Test EXIF JPEG/color/alpha behavior; transparent edits export PNG. Do not silently transform existing stored assets/geometry.
- Use object URLs/display-resolution caches rather than base64 plus multiple full-size canvases. A 40MP RGBA buffer alone is about 160MB; browser and VPS constraints matter even when an encoded upload is small.
- Add bounded global render admission shared with existing publication/preview paths, per-actor quotas and a **whole-preview deadline**, not just per-subprocess limits. Begin conservatively with one native render at a time and measure under the real service limits. Do not queue unlimited jobs or increase the small VPS's memory ceiling to hide overload.
- Cache/debounce only editor bookkeeping; avoid background renders for every corner motion. Reject busy/rate-limited work with actionable 429/503 while preserving the draft. Cancellation must stop owned process work and retain leases until it really ends; aborting browser fetch alone is not proof of cancellation.
- Keep private draft/preview responses no-store, owner-authorized and size/time bounded. Retain sandboxing, argument arrays, interruption and cleanup. Never fetch arbitrary client image URLs or feed client shell commands to ImageMagick.

## Reduce image preprocessing

After the core editing/upload flow, add **Abrir espaço nesta área**: an explicit, reversible operation cutting a polygon-shaped transparent opening in a copy of the template. Add manual erase/restore brush if users need curved holes or preservation of foreground objects. The original remains intact, with bounded mask/stroke undo rather than full-image snapshots for every movement.

Background positioning and pixel removal remain separate actions. A background area on an opaque JPEG should show a useful explanation, not silently erase it. Preserve intended occluders; compare before/after and preview the result. Once a cut is applied, its mask is an image edit, not silently linked to later source-placement movement. Export/revalidate a full-resolution PNG with the same coordinate space. Automatic hole detection/suggestions are later convenience, always editable.

Do **not** start with server-side AI background removal: it adds model/download/memory, quality, privacy/licensing or paid-service complexity to a constrained hobby deployment. It also solves foreground segmentation, which is not necessarily the user's desired source opening. Consider it only after manual cutout usage demonstrates the need and a separately reviewed client-side/service approach fits the constraints.

## Incremental delivery and acceptance

Use the established cycle: **develop → focused tests → fix/retest → full applicable gates → local commit**. No push/deployment authority carries over from the previous one-change production rollout.

| Slice | Deliverable | Gate before next slice |
| --- | --- | --- |
| 1. Compatibility fixtures + library spike | Canonical geometry/serialization tests and isolated Konva/Moveable interaction comparison; verify dev-bot launch context | Same four-point contract under zoom/pan; select one library after real iOS/Android check; no production tests |
| 2. Precision editor | Original-pixel model, handles/gestures, numeric/nudge, undo, delete/duplicate/reorder, linked slots, validation and advanced CSV fallback | Old CSV parses unchanged; zoom/resize never alter saved geometry; touch manipulation does not rectify/move accidentally |
| 3. Creator identity + origin contract | Scoped signed-data authentication, media acquisition boundary, legacy/web attribution/notification contract and any necessary schema/recovery updates | Full filter-chain negative tests, both storage adapters and compatible restore/migration/rollback tests; no fabricated Message |
| 4. In-app native preview + final submit | Bounded owned drafts, revision/sample-bound rendering/cache, stable finalization/receipt and existing moderation handoff | Complete dev-bot upload/edit/preview/submit/admin decision round-trip with no manual file/caption/chat confirmation; failures cannot duplicate or lose committed items |
| 5. Reversible cutouts + polish | Polygon opening, then erase/restore if useful; local recovery and clear status/help | Alpha/full-resolution round-trip, undo/redo and foreground-occlusion parity; stable phone performance |

Slices 3–4 are essential to fixing the steps problem; do not stop at a prettier CSV generator. Keep the legacy creator/caption route available during adoption, clearly named as fallback. Feature-flag the new submission path independently; runtime restart/rollout and any schema maintenance require separate operator authorization.

### Test strategy

- **Geometry/model:** resize/rotate-device/zoom/DPR cycles leave original points unchanged; repeat-slot `[1,2,1]`; reorder both planes; delete middle slots; CSV round-trip; negative/edge/corner-only/off-canvas coordinates; convexity after integer rounding; boundaries 1/128/129 areas and byte/pixel limits.
- **Browser:** mouse/stylus/touch, second-finger interruption, pointer cancel, magnifier/nudge, keyboard focus, reduced motion, 360px/390px layouts, landscape, virtual keyboard/safe areas, Telegram Web iframe and native iOS/Android clients, standalone/no SDK/older SDK/CDN failure, cancelled/invalid image inputs and EXIF orientation. Emulation supplements real-device checks; target responsive frame times but measure rather than promise 60fps.
- **Rendering:** generated valid fixtures for skewed and off-canvas quads, reused slots, overlapping B/F areas, opaque/JPEG and alpha PNG holes; verify **B2,B4,template,F1,F3** and current forced-resize/perspective/mask behavior. Compare server/native output with fixed samples; any browser approximation is labeled.
- **Security:** forged/missing/wrong-bot/stale/future/duplicate-field initData; actor spoofing/IDOR/cross-draft access; creator denied moderation/source admin/publish/backup; unchanged ADMIN CSRF and generic errors; token expiry, origin/CORS and real iframe behavior; no credentials in logs/URLs/storage.
- **Failure/concurrency:** stale preview, edit-vs-submit/discard/expiry, double-click and lost successful response, render failure/interruption, quota/overload, cleanup of owned scratch, notification-after-commit failure, both repositories, legacy callbacks/imported null origins and functional backup/restore.
- **Gates:** Node model/browser tests and JS syntax, focused Java adapter/use-case/security/repository/render tests, then clean Java build and all Node/security/dependency gates as appropriate. A new JS dependency needs its own dependency/license coverage; Maven OSV success is not enough.

### Success criteria and open decisions

Success means a user can place a corner to **one original-image pixel using precision controls**, reuse a source in separate areas, zoom without geometry drift, correct a preview without leaving the app, and send one confirmed submission without copying text or resending a file. Clear status survives notification failure; administrator approval is still mandatory.

Measure started/completed creation and validation/render failure outcomes with opt-in/local usability feedback or low-cardinality aggregated telemetry, not raw initData, user names/images or draft UUID metrics. Establish a baseline before choosing time/abandonment targets; no improvement percentages have been measured yet.

Before implementation, settle: actual bot launch mode; Konva versus Moveable spike outcome; desired availability of real approved-source previews; draft retention/quota/deadline values measured against host capacity; and concrete origin/receipt schema/backward-recovery design. Cross-device drafts, editing approved templates and AI segmentation are not MVP assumptions.

## Upstream references

- [Telegram launch types, initData verification, native controls and limits](https://core.telegram.org/bots/webapps).
- [Bot API web_app_data](https://core.telegram.org/bots/api#webappdata), [answerWebAppQuery](https://core.telegram.org/bots/api#answerwebappquery), [document captions/transport](https://core.telegram.org/bots/api#senddocument). Inline document URLs support PDF/ZIP, so `answerWebAppQuery` is not a magic PNG-upload replacement; cached documents require a prior Telegram upload.
- [Konva overview](https://konvajs.org/docs/overview.html), [polygons](https://konvajs.org/docs/shapes/Line_-_Polygon.html), [pinch recipe](https://konvajs.org/docs/sandbox/Multi-touch_Scale_Stage.html), [pointer-centered zoom](https://konvajs.org/docs/sandbox/Zooming_Relative_To_Pointer.html), [relative pointer positions](https://konvajs.org/docs/sandbox/Relative_Pointer_Position.html), [license](https://github.com/konvajs/konva/blob/master/LICENSE).
- [Fabric polygon controls under viewport transforms](https://fabricjs.com/demos/poly-controls/), [license](https://github.com/fabricjs/fabric.js/blob/master/LICENSE).
- [Moveable overview](https://daybrush.com/moveable/), [warp controls/matrix API](https://daybrush.com/moveable/release/latest/doc/Moveable.Warpable.html), [license](https://github.com/daybrush/moveable/blob/master/LICENSE).