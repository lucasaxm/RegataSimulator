# Creator implementation plan

**Priority: first improve/replace the legacy image editor with Konva, preserving manual bot document/caption upload, submitter confirmation, administrator review and Telegram SDK integration; then iterate the wider journey.** Image-only source exists, but is not an accepted release. A useful image-only release candidate must not wait for text, creator identity or direct submission (slices 4–6). Start from the [verified legacy flow](current-flow.md) and [current editor contracts](../project-map.md#image-only-editor-current-first-slice). Keep this within the existing static-page/Spring architecture. No canonical `/create` change or live deployment now; retain legacy fallback until phone/Telegram acceptance and a separately authorized operator release.

The isolated [mixed-area spike](spike.md) exercises Konva/Moveable with source-linked [text boxes](text-areas.md). **Konva is selected for the real creator**, following the comparison and user feedback on macOS Chrome and Android Brave. Moveable stays in the unchanged historical spike, not in the production dependency graph or UI. This closes library selection; actual native-phone/WebView acceptance remains a delivery gate, not another comparison task. Neither the spike format nor text rendering is implemented in the production generator.

## Current implementation boundary

`frontend/template-creator/` now contains the vanilla JS image-only editor, pure original-pixel geometry model, media loader, package/lockfile, tests and build source. It pins Konva **10.7.1** as the sole browser runtime dependency, esbuild **0.28.2** and Playwright **1.64.0** as build/test tools; CI pins Node **22.23.1**. Gradle packages generated hashed/self-hosted assets at **`/create/editor/`** alongside unchanged legacy **`/create`**. The shell is generated from this package's `index.html`, not handwritten under Spring static source. See [maintenance](../maintenance.md#editor-build-and-verification) for install/check/audit/browser/JAR commands and standalone loopback port **4174**, which starts no Spring/bot.

Implemented source controls include local image opening, drawing/automatic selection and editing, pan/zoom, rectangle/perspective corners and paired edges, anchored ghost/Apply/Cancel, numeric/nudge, duplicate/delete/reorder, source/background and undo/redo. The mobile panel scrolls internally; real hardware/keyboard usability is not yet accepted. Konva view state never defines saved coordinates. Internal image-only 11-column CSV accepts contiguous Area indices beginning at 0 or 1 and exports 1-based rows; backend still accepts only 1-based indices. Preserve imported repeated sources outside adjacency choices and valid off-canvas positive-area overlap.

### Current candidate contracts: image-only release

The source and packaging implement these contracts; applicable automated and real-device acceptance gates remain required before release:

- One `#fit` **Ajustar** always frames image plus all areas, including off-canvas points; no separate **Áreas** fit button. Retain zoom/100% and pan without geometry changes.
- Central **Abrir imagem**, empty-canvas tap/click, local paste and file drop; tools/camera hidden before loading. Never fetch dropped URLs; preserve media validation, racing-load and dirty-replacement protection.
- Automatic area editing/selection rather than a **Selecionar** button. Contextual **Forma / Precisão / Camadas** preserves geometry, numeric/nudge, source/background, duplicate/delete/reorder and undo/redo.
- **Recomeçar áreas** confirms replacement when areas exist, retains the image and creates one full-image rectangle/Source 1 in one undoable action.
- **Preparar envio / Dados do template / Copiar dados**, without “CSV” in user UI/errors. Internal 11-column CSV/import/export stays unchanged. Explain manual original-image attachment as Arquivo/documento, pasted caption, bot confirmation and administrator review; this page sends no files.
- The official async hosted SDK loads at **https://telegram.org/js/telegram-web-app.js**. `telegram.mjs` handles already/late-loaded SDKs, embedded `ready`/`expand`, theme/viewport events and version-gated closing confirmation. Copy success gives explicit feedback and offers **Voltar ao bot** or continued editing; close only on explicit choice, never automatically after copy.
- SDK absent/blocked/old/throwing must leave standalone editing/export/manual copy usable. SDK/platform/initData UI detection is **not authentication**. No external APIs, creator credentials or production-bot tests in this slice.

The SDK is the **only exact remote script exception**: `build.mjs`/`verify-package.mjs` allow exactly one external `src`, that URL, and no remote `href`; package verification also checks `manifest.hostedIntegrationScripts`. Local server CSP allows the exact script URL, not the entire host, wildcard hosts or canvas CDNs. Application JS/CSS and Konva remain hashed/self-hosted. Spring retains its existing frame-ancestor/API/CORS/ADMIN/CSRF policy; there is no global CSP rewrite. `build.mjs --check` and Gradle `buildCreator` explicitly include `telegram.mjs`; npm test includes model/media/controlled Telegram unit suites, and CI runs npm check/test through `frontendCheck`. Node/npm/dependency versions remain unchanged. The app uses the minimum of browser visual height and valid Telegram stable height and cancels gestures before hosting viewport changes without mutating canonical coordinates. Doubles/emulation are not overall-green evidence or real-device proof.

After every delivered editor slice, refresh trusted-Wi-Fi phone preview with `npm start --prefix frontend/template-creator -- --lan` and provide newly printed reachable URLs. Default/CI stays loopback-only; LAN preview, Telegram acceptance and operator release are separate. Documentation-only work launches no server.

Local PNG/JPEG limits are 20 MiB/40MP/10,000 per side. The initial header read is at most 1 MiB; JPEG metadata/dimensions must fit within it before SOS. Before object URL creation/decode, the private async `inspectPngMetadata(file, head)` walks PNG chunk headers through IEND, including eXIf after IDAT and beyond the initial window. It skips IDAT payloads, uses cached header bytes when they fit, and permits at most 2,048 chunks and 1 MiB of inspected headers/EXIF bytes. IEND must end exactly at EOF; duplicate eXIf is rejected. Non-1 EXIF orientation is **refused**, with normalize/save-a-copy guidance before placing points; automatic orientation/reencoding below is future work.

The loader requires full `createImageBitmap` raster decode with matching dimensions, closes temporary bitmaps including late completion after timeout/cancellation, and fails closed without API support. Corrupt PNG pixels can pass image onload/`decode()` yet fail bitmap decoding; full bitmap decoding supplies decode verification. An additional **1×1 canvas probe is neither needed for that proof nor an approved acceptance requirement**. The shared 15-second deadline covers image loading and full bitmap decoding, not header/metadata I/O; it guarantees neither bounded total browser allocation nor decoder cancellation. The future color sampler's bounded scratch canvas below serves pixel sampling, not an outstanding loader decode requirement.

Generated-fixture model/media, Chromium desktop/mobile-emulation and packaged-resource tests plus npm audit are configured, not evidence that every full gate passed. Use generated fixtures, leaving the user's untracked `spikes/example/` media untouched. The user reports successful Telegram-hosted testing through the temporary HTTPS preview; this is actual user feedback, not emulation. Device/OS/client versions and exercised scenarios were not recorded, so the remaining Android Brave/iOS/client matrix, performance, virtual-keyboard and complete manual submission/review checks are not closed by that report. Java 21.0.7 can run local tests but is below the 21.0.12.1 maintenance floor and is not release parity.

**Not implemented:** text/color eyedropper, backend native preview, direct Mini App submit, creator identity/draft APIs or durable local draft saving. Editing overlays are not native renders. The remaining target journey, font/schema/authentication/recovery contracts and phase gates below stay in scope as a plan; the first slice does not complete them.

## Implementation decisions

- Build the real editor in the existing static-page/Spring application with **vanilla JavaScript and Konva**. Reuse tested model concepts, not the laboratory shell or its full dependency tree. No more spike implementation is planned.
- Default drawn image areas to **rectangle editing**; retain explicit **perspective editing** for independent corners. Add anchored rectification and paired-edge resizing, specified below.
- Provide an application-owned **Pegar cor da imagem** control on desktop and mobile. Native browser color dialogs/screen eyedroppers are optional conveniences, not the portable implementation.
- Make the canvas occupy the workspace. Compact tools and one contextual property panel replace the laboratory's heading, demo selector, stacked tool rows and long below-canvas inspector.
- Keep source-linked text as a later iteration, including optional box fill, literal `{{source.description}}`, explicit overflow and shared slots. Real fonts/rendering/versioned persistence are prerequisites for text submission, not for the image-only release.
- Preserve legacy geometry, manual document/caption submission, submitter confirmation and administrator review for the first useful release. Then enable mixed definitions and authenticated preview/final submit through separate backend slices. A better image editor is a valid first user delivery, not a reason to wait for the entire final journey.
- Keep compatibility, migration/recovery, native-device testing and dependency/license checks explicit. This plan authorizes neither production operations nor a schema cutover.

## Later target journey (not the first release gate)

First-release journey: **open image → mark/edit areas → prepare/copy data → manually attach original image and paste caption in the bot → confirm in chat → administrator review**. SDK support improves the handoff without authenticating or sending files. The direct journey below is subsequent work.

**Choose image → mark/edit areas → test in place → send for approval.**

1. Open from Telegram; identity is established invisibly when supported. A standalone browser can edit/export, but must open through Telegram to submit without an additional login design.
2. Select/drop/paste the image once. Draw a rectangle, then adjust four corners for perspective. Add/duplicate areas and assign source slots visibly.
3. Press **Ver prévia**. See the server-rendered result in the app, return to editing without losing anything, and optionally change the example sources.
4. Press **Enviar para aprovação** once for the exact previewed revision. This is submitter confirmation. Show a submission receipt and “Aguardando aprovação”; do not require another Confirm click in chat.
5. Keep administrator approval/rejection and Telegram notifications. Do not promise a faster approval time or automatically approve ordinary submissions.

This later path removes copying, manual document reattachment and caption composition. Compatibility data export/import remains available. The first image-only release deliberately preserves these manual steps; solving the entire submission problem is subsequent iteration, not a blocker for a useful editor.

## Precision editor

### Interaction contract

- **Single-pointer:** drag an explicit corner handle to edit that corner; drag the selected polygon's interior to move it. Empty-space gestures never move an arbitrary nearest corner.
- **Two-pointer:** pinch/pan the viewport. A second pointer suspends/cancels shape editing; lifting a pointer must not resume a stale drag or trigger rectification. Handle pointer capture/cancel and WebView interruptions explicitly.
- **Desktop:** wheel/trackpad zoom around pointer, pan mode/Space-drag, arrow nudges of one original-image pixel, Shift for a larger step; do not intercept typing in coordinate inputs.
- **Visible alternatives:** zoom +/−, one **Ajustar** framing image plus all areas, 100%, selected-corner X/Y fields and a mobile nudge pad. Zoom is not the only precision method.
- Keep corner targets approximately **44 CSS px** at any zoom with smaller visual markers; use an offset magnifier on mobile so the finger does not obscure the pixel being placed. Labels/shape outlines supplement color.
- Add/delete/duplicate, anchored straighten-to-rectangle, undo/redo and reorder controls. Undo groups a gesture as one action; no hidden destructive double-tap. Clear/replace-image prompts protect dirty work.

### Rectangle, perspective and paired-edge controls

The legacy `/create` creator's `handleDoubleClick()` / `straightenAreaFromCorner()` anchors a corner, aligns its two neighbors to its axes and derives the opposite corner. Preserve the useful intent, **not** its time-only mobile double-tap detection or automatic mutation.

**Default: Retângulo.** New image areas start as axis-aligned rectangles; text boxes remain rectangular. Show corner and midpoint edge handles:

- Dragging a corner changes width/height while the opposite corner stays exactly fixed. Regenerate the other two corners from the rectangle; do not treat this as four independent corner drags.
- Dragging an edge moves its two endpoints along the appropriate axis while the opposite edge stays fixed. This provides the requested two-corner adjustment without skew.
- Dragging the interior translates the whole area, preserving width/height. Numeric width/height and corner X/Y provide exact alternatives.
- **Manter proporção** is an optional visible toggle, off by default; Shift is a desktop shortcut, not the only way to enable it. Text-box resizing reflows text, not an affine stretch of glyphs or an implicit font-size change.

**Perspectiva** is an explicit image-only mode. Corners move independently; a midpoint handle translates that edge's two endpoints by the same original-pixel delta, preserving their edge vector and leaving the opposite pair untouched. Validate convexity/overlap; a rejected drag keeps the last valid geometry. This is not multi-area selection. Entering perspective mode does not change coordinates. Nonrectangular CSV imports start in this mode; rectangular imports start in rectangle mode.

**Transformar em retângulo** is a visible geometry action, also offered when switching a skewed image area back to rectangle mode:

1. Choose **Canto fixo** from TL/TR/BR/BL using a labeled corner diagram. Default to the selected corner, or TL when none is selected.
2. Show a ghost preview. By default preserve that anchor **and the current opposite corner**, deriving the other two intersections from their x/y coordinates. This differs deliberately from the legacy neighbor-projection formula and makes the two stationary points predictable.
3. Optional positive integer width/height inputs allow a different size about the fixed anchor; the preview shows that the opposite corner will then move. If the existing diagonal has zero extent or cannot preserve the stored corner correspondence, require these explicit dimensions rather than silently flipping/relabeling corners.
4. **Aplicar** commits one undoable command and selects rectangle mode; **Cancelar** preserves both geometry and editing mode. No automatic bounding-box conversion, clamp-to-image, or reshuffling of TL/TR/BR/BL.

Use original integer coordinates, quantize a moving point/delta once, derive all dependent points from the gesture-start geometry, and then validate. Independent rounding of origin and size must never drift the fixed opposite corner. Reject zero sizes, reflections/crossing and invalid overlap rather than committing a collapsed/flipped area. Rectangle/perspective constraints are editor state, not an incompatible new CSV field; undo/redo restores a coherent geometry/mode pair.

Corner double-tap or long-press can later **open** this action, but must never apply it invisibly. Ship the visible button first. Handle hit regions stay screen-sized; when they overlap at low zoom, offer zoom-to-selection or the corner chooser instead of choosing an arbitrary handle.

### Coordinate and layering model

Maintain a small, library-independent document model: template image identity/dimensions, stable typed area IDs, image quadrilaterals or text rectangles in original-image coordinates, source-slot references, background flag and order. Mixed definitions add validated text pattern/font/style fields through the versioned contract described in [text areas](text-areas.md). Keep viewport transform and selection separate. Never persist Konva/Fabric scene JSON as the domain contract.

Points live in **original-image pixels**, not screen/CSS/device pixels. Pan, zoom, rotation of the device and container resize change only the view. Invert the viewport transform for input; quantize once to the existing integer contract, and validate the quantized geometry before preview/export/submit. Keep TL/TR/BR/BL point correspondence stable rather than re-sorting points by screen position.

Provide early validation for convexity, degeneracy, contiguous indices/slots, bounds and positive canvas overlap; the server remains authoritative. Support intentional off-canvas points up to ±10,000, with a visible canvas boundary and the single **Ajustar** control/panning to reach outside handles. Do not reintroduce clamping as a “safety” feature.

Show a layer list with **Atrás do template → Template → Na frente do template**. Within each editable group, provide move-forward/backward or reorder plus keyboard/buttons, not drag-only controls. A position change across the fixed template plane is an explicit background change. Serialize compatible indices after reorder.

Use **Source 1**, **Source 2**, etc., with a visible “reuse same image” relationship and stable slot colors. Keep the current-style image adjacency choices initially, ignoring interleaved text when finding image neighbors; first image is slot 1. Show permitted reuse/new-slot choices explicitly. A default allocation must be offered by that area's picker, including when text already uses the candidate slot. Text can bind existing referenced slots and inherits the selected area's slot. The sample-description palette is not the image-choice list. Deletion renumbers the remaining used-slot union contiguously in one undoable command, updating every image/text reference and sample association together. List order and slot identity are different concepts; repeated/non-adjacent slots remain supported by the domain/CSV even when the picker offers narrower editing choices.

### Consistent color controls and template eyedropper

The screenshot difference is browser/platform UI, not a reliable feature of HTML color inputs. [EyeDropper](https://developer.mozilla.org/en-US/docs/Web/API/EyeDropper) is limited/experimental, requires a secure context and is not a mobile/WebView baseline. Its screen sampler is also different from an HTML color input's native dialog. Do not assume Android Brave exposes it because desktop Chrome does.

Use the same compact color field for text fill, outline and optional box fill: current swatch, validated `#RRGGBB`, recent/preset colors, and **Pegar cor da imagem**. Provide arbitrary color entry through HEX/RGB controls; no native modal is required for the normal path. Sampled box fill is the primary way to cover lettering on a nonblack chat/template background; it stays distinct from **Atrás do template**. Native color dialogs may remain an optional advanced convenience.

Sampler interaction:

1. Bind the operation to the selected area's specific color property and remember the previous value. Collapse the mobile property panel to a short sampling bar; keep the canvas and selected region visible.
2. Desktop hover or one-finger drag moves a crosshair with an offset, pixelated magnifier and candidate swatch/HEX. A tap/click can choose a candidate; **Usar cor** commits exactly one undoable style change. **Cancelar**/Escape restores the previous value and returns to the prior panel. Sampling never drags an area, inserts a shape or changes its source.
3. Sample the loaded, canonically oriented **template bitmap only**, not colored source placeholders, text rasters, selection outlines, handles or the checkerboard. For later cutout tools, this means the working template copy. External desktop/screen pixels are outside the portable feature's scope.
4. Invert camera/CSS coordinates to original-image coordinates and read the bounded pixel/patch in a small reusable scratch canvas, using [getImageData](https://developer.mozilla.org/en-US/docs/Web/API/CanvasRenderingContext2D/getImageData). Sampling must not allocate a second full 40MP RGBA canvas; the magnifier uses a bounded patch. Display caches/downsampling and device pixel ratio must not determine the sampled pixel.
5. Use decoded sRGB RGB values for solid HEX fields. A fully transparent or off-image location has no selectable color; show guidance rather than converting transparent black/checkerboard to black. For a partially transparent pixel, show its alpha and explain that applying its RGB creates a solid color. Do not promise encoded-byte, cross-device color-profile or native-render pixel identity.
6. Two fingers cancel the pending candidate and enter viewport navigation; releasing one cannot commit it. Pointer cancellation, loss of focus, image replacement, selection/property changes and modal closure also cancel safely. Offer sampling X/Y and nudge/confirm controls for keyboard and precision use.

Operate on local/authorized same-origin blobs, not arbitrary remote image URLs. Catch canvas-read security/privacy failures and preserve the old value; keep manual HEX/RGB entry available with an actionable explanation. [Canvas origin rules](https://developer.mozilla.org/en-US/docs/Web/HTML/How_to/CORS_enabled_image) must not be worked around by weakening CORS. Brave/privacy protection can block or perturb pixel reads; verify normal Android Brave settings on hardware and document limitations rather than demanding users disable protections. An optional separately labeled **Pegar da tela** may use feature-detected `EyeDropper.open()` with user activation/cancellation handling on supported desktops; it is not an MVP dependency or a promised mobile screen-capture feature.

### Layout and visual direction

The image is the hero and the editor is a workbench, not a dashboard. **Do not promote the laboratory layout.** Remove its product-description heading, engine/demo selector, persistent file input/status block, diagnostics and visible CSV/sample sections from the normal workspace. After load, a compact filename/change-image action replaces the chooser; help, notices, compatibility export and sample descriptions live in explicit menus/panels.

**Desktop:** canvas fills available space beside one approximately 280px collapsible inspector. Selection opens only relevant properties; tabs are **Área**, **Texto**, **Camadas**, not a stack of every field. Font/color/box-fill changes remain visible in the canvas. Tools, undo and zoom do not become a second dashboard header.

**Mobile portrait:** one approximately 44px app bar, a compact tool dock, and a contextual bottom panel with a collapsed and bounded editing state. No wrapped toolbar rows and no body-scroll journey from inspector back to canvas. At 360×640 and 390×844, with keyboard closed, target at least **55% of the usable application viewport as unobscured canvas**, even while editing common text properties. Usable height excludes browser/OS/Telegram reserved controls and safe insets; measure actual occlusion, not the CSS height of a canvas behind a sheet. The editing panel is normally capped at the smaller of 224px or 30% of usable height; fit controls to the remaining budget rather than violating the canvas target.

Conceptual layouts (not final pixel art):

```text
Desktop
┌─ Change image / filename ─ Undo / Redo ─ Preview ─┐
│                                               │
│  Canvas + unobtrusive zoom     │ Area / Text    │
│  and selected-region handles  │ contextual     │
│                               │ inspector     │
└─ Image / Text / Layers tools ──┴───────────────┘

Mobile
┌─ Open / filename ─ Undo ─ Redo ─ More ─┐
│                                      │
│  Canvas: selected region stays here   │
│  Zoom/fit is a small floating control │
│                                      │
├─ New area / Shape / Precision / Layers┤
│ Selected area / one property category │
│ Compact font OR color OR geometry     │
└─ Preview / submit, when available ────┘
```

Only one property category opens at a time: font/size, color/background, content, or geometry. Advanced settings scroll **inside** the panel, not the entire page. A list/layer drawer can expand deliberately, then closes on selection; it is not the default editing state. Full-screen dialogs are for tasks such as file choice/help/native preview, not routine color/font adjustment. In short landscape layouts, use a narrow side panel or compact strip rather than covering the canvas with a tall bottom sheet.

Preserve the current view when panels open; minimally pan the selected region into the unobscured canvas if needed. Container/keyboard/device resizing changes only the camera, never saved geometry. With the virtual keyboard open, show the focused field in a compact input panel and keep a selected-region preview in the same screen (target at least 128px when usable height permits). For exceptionally small viewports, offer **Ver área** to dismiss the keyboard without losing the edit, not a scroll-to-top requirement. Color sampling dismisses the keyboard and collapses the panel explicitly.

Use Telegram's dynamic theme variables and a system UI font stack; 14–16px readable controls, small contextual titles and no promotional typography/font download. Canvas text uses the separately pinned production font catalog, not this UI stack. Fallback tokens: workspace `#6f7072`, panel `#242426`, text `#f4f4f5`, secondary `#b6b6bb`, action `#285ad1`, destructive `#e36b6b`; verify contrast in light/dark/custom Telegram themes. A neutral workspace/checkerboard distinguishes template bounds without becoming a sampled color. Selection is conveyed through labeled corners, edge handles and source-slot badges, not ornamental gradients/cards or color alone. The distinctive visual element is the user's template; surrounding chrome stays quiet.

Respect dynamic/stable viewport height, VisualViewport keyboard changes and safe/content-safe insets with older-client fallbacks. Feature-detect/version-check optional fullscreen, swipes, haptics and native buttons. Reserve native main-button space only when actually present; provide a browser action otherwise. Disable Telegram vertical swipes only while editor gestures conflict, not throughout unrelated screens. Allow browser accessibility zoom; constrain touch behavior to the editing surface. 44px touch targets, visible keyboard focus, focus return after panels, reduced motion, numeric alternatives, screen-reader area descriptions and accessible color-field labels are required.

**Design review gate:** compare the same user tasks against the compact legacy creator, not against the laboratory alone. Changing font, text color and sampled background must show the updated selected region without a page scroll. Capture portrait/landscape and keyboard-open layouts; review before building all controls, then repeat against the real implementation. Decorative chrome that reduces canvas area is a regression even if it looks polished.

## Library comparison

The inspected upstream licenses are MIT; retain notices and review the **selected version and its dependencies** before shipping. Documentation examples are capability evidence, not a mobile benchmark or a dependency lock.

| Candidate | What it supplies | Work still required | Recommendation |
| --- | --- | --- | --- |
| **Konva** | Canvas scene graph, polygons/images/layers, hit-testing, drag support, inverse transforms; documented pinch and pointer-centered zoom recipes; vanilla JS support | Custom geometry constraints/handles, gesture arbitration, validation, undo/document model. Its standard Transformer is affine, not four-corner perspective image warping | **Selected foundation** |
| **Fabric.js** | Editable polygon controls, object selection and viewport transforms; demonstrated editing under zoom/translation | Perspective image rendering is not supplied by polygon point editing; custom controls/gestures and broader object-model integration | Good alternative if broader image/object editing becomes central |
| **Moveable** | DOM/SVG draggable/warpable/pinchable controls; four-direction perspective handles and CSS `matrix3d` output | World-coordinate extraction, async target/drag lifecycle and masks/stacking; retained spike has a known unselected-first-drag failure | Historical comparison only; exclude from production |
| Rectangle cropper | Mature rectangular crop/rotation interaction | Cannot represent four independently positioned corners, repeated slots or the template's two planes | Not a replacement for our geometry editor |

Do not install all three or add React only to obtain a canvas library. Pin/self-host the chosen library with license notices, or use a narrowly scoped build with a lockfile if packaging requires it. Existing Maven scanning does not cover new npm/CDN dependencies: add the appropriate inventory/security gate. Do not broaden CSP/frame ancestors or weaken admin/CSRF security to accommodate the editor.

**Comparison closed.** Keep the spike reproducible and unchanged; do not spend another slice polishing or replacing its failing candidate. Konva-only production packaging has its own inventory/notices: Moveable/Croact's experimental notice gaps do not automatically apply to that graph. Before rollout, check native-phone latency, finger occlusion and Telegram gesture conflicts. Konva does not supply the native renderer or whole submission workflow. If using its Transformer for rectangles, normalize scale into canonical dimensions/points, disable rotation/flipping/centered scaling, configure aspect behavior explicitly and never persist scene scale as geometry; custom model-owned handles are preferred for predictable rectangle/perspective switching.

### Production source and asset packaging

The dedicated `frontend/template-creator/` package/lockfile now holds the real editor's modules, tests and build tools, separate from `spikes/`. Konva 10.7.1 is the pinned runtime baseline; recheck advisories/notices on upgrades. No Moveable, Fabric, Croact or React runtime ships. The existing Spring static-page deployment serves the generated `/create/editor/` shell alongside unchanged `/create` legacy behavior.

The scoped esbuild bundler emits hashed self-hosted JS/CSS, shell, manifest/inventory and full Konva notice into `build/generated-resources/creator/static/create/editor/`; Gradle `installCreatorDependencies` → `buildCreator` → `processResources` wires these into the executable JAR. Generated output is built, never hand-edited or committed. Module source, package/lockfile and build configuration are tracked. Locked installation uses `npm ci --ignore-scripts --include=dev --include=optional`, retaining optional platform esbuild binaries without lifecycle scripts. Declared inputs/outputs and owned-output cleanup prevent stale hashed assets; CI pins Node 22.23.1. A clean build must produce complete assets or fail. No Node/npm runtime on the VPS and no canvas-library CDN requirement.

Model/media/controlled Telegram/browser/syntax, npm audit and notice/inventory checks are configured alongside Java/Node/security/deployment gates. `verify:package` inspects JAR entries, asset/notice digests and shell references without Spring startup. Its remote-reference validation allows only one external `src` at the exact official SDK URL above, rejects remote `href` and verifies `hostedIntegrationScripts`; all canvas/application assets stay self-hosted. Telegram syntax/unit checks are included in npm and Gradle/CI `frontendCheck`. Local server CSP permits that exact SDK script URL; Spring's existing frame-ancestor/CORS/ADMIN/CSRF policy is unchanged, without a global rewrite. Configured gates are not overall-green evidence. Standalone and trusted-Wi-Fi LAN preview rebuild before serving; editing/export must survive SDK/network failure without pretending identity exists.

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
- Handle unsupported formats/decode errors, picker cancellation and racing file selections explicitly. The current slice refuses non-1 EXIF and requires users to normalize a copy before marking areas; it does not rewrite images. Future automatic normalization must occur **once before placing points**, producing a canonical oriented bitmap/encoded upload with matching dimensions. Account for browser decoders that already honor EXIF, rather than rotating twice; ImageMagick must not orient it again. If canonicalization changes bytes/format, recheck encoded size and explain any limit failure. Replacement requires a dirty-work prompt and a new coordinate space unless explicit remapping is designed. Test EXIF JPEG/color/alpha behavior; transparent edits export PNG. Do not silently transform existing stored assets/geometry.
- Use object URLs/display-resolution caches rather than base64 plus multiple full-size canvases. A 40MP RGBA buffer alone is about 160MB; browser and VPS constraints matter even when an encoded upload is small.
- Add bounded global render admission shared with existing publication/preview paths, per-actor quotas and a **whole-preview deadline**, not just per-subprocess limits. Begin conservatively with one native render at a time and measure under the real service limits. Do not queue unlimited jobs or increase the small VPS's memory ceiling to hide overload.
- Cache/debounce only editor bookkeeping; avoid background renders for every corner motion. Reject busy/rate-limited work with actionable 429/503 while preserving the draft. Cancellation must stop owned process work and retain leases until it really ends; aborting browser fetch alone is not proof of cancellation.
- Keep private draft/preview responses no-store, owner-authorized and size/time bounded. Retain sandboxing, argument arrays, interruption and cleanup. Never fetch arbitrary client image URLs or feed client shell commands to ImageMagick.

## Reduce image preprocessing

After the core editing/upload flow, add **Abrir espaço nesta área**: an explicit, reversible operation cutting a polygon-shaped transparent opening in a copy of the template. Add manual erase/restore brush if users need curved holes or preservation of foreground objects. The original remains intact, with bounded mask/stroke undo rather than full-image snapshots for every movement.

Background positioning and pixel removal remain separate actions. A background area on an opaque JPEG should show a useful explanation, not silently erase it. Preserve intended occluders; compare before/after and preview the result. Once a cut is applied, its mask is an image edit, not silently linked to later source-placement movement. Export/revalidate a full-resolution PNG with the same coordinate space. Automatic hole detection/suggestions are later convenience, always editable.

Do **not** start with server-side AI background removal: it adds model/download/memory, quality, privacy/licensing or paid-service complexity to a constrained hobby deployment. It also solves foreground segmentation, which is not necessarily the user's desired source opening. Consider it only after manual cutout usage demonstrates the need and a separately reviewed client-side/service approach fits the constraints.

## Incremental delivery and acceptance

Use **develop → focused tests → fix/retest → full applicable gates**; commits/pushes/deployment require authorization. Image-only source spans foundation/editor/geometry portions, not completed acceptance. **Complete acceptance of the integrated image-only UX/SDK candidate through applicable automated and phone/Telegram gates, then seek separate operator release before iterating later features. Do not wait for slices 4–6.**

**Current next phase is release readiness for slices 1–2.** Preserve the user's successful Telegram-hosted report, close only the unrecorded device/keyboard/performance and manual document/caption/confirmation/review checks under authorized test conditions, then verify maintenance-Java and all applicable release gates. Prepare a separately approved legacy-adoption/rollback decision with the old creator still reachable. The report is not permission to push, deploy, change bot links or switch `/create`; it does not implement or authenticate direct submission. See the [acceptance status](README.md#acceptance-status-and-next-delivery) for the focused checklist. Slices 3–6 follow the first useful image-editor delivery rather than blocking it.

| Slice | Deliverable | Gate before next slice |
| --- | --- | --- |
| 1. Production foundation and compatibility | Separate Konva-only package, pure original-pixel document/command model, clean-JAR bundling and source/license inventory; comparison is already settled | Generated fixtures, exact supplied three-row CSV and repeated/off-canvas slots round-trip; standalone assets resolve; no spike changes, live operations or schema changes |
| 2. Canvas-first image release candidate | Current candidate UX contracts, full image geometry/undo features, manual bot handoff and integrated optional hosted SDK alongside legacy `/create` | Final applicable automated gates including Telegram doubles/exact SDK packaging/local CSP; unchanged Spring security; real phone/Telegram/keyboard acceptance; separate operator release, no canonical route change now |
| 3. Subsequent color/geometry polish | Preserve rectangle/perspective, paired edges and anchored conversion; iterate portable color sampler and compact text-property fixtures | Fixed-point/undo compatibility; sampler/canvas-visibility acceptance below; later polish does not hold back the image-only candidate |
| 4. Literal text rendering and font catalog | Bounded literal rasterizer, pinned licensed font IDs/weights, shared layout/overflow/box-fill contract, native mixed render fixtures | One source image/description per slot; no expressions/native text escapes, silent truncation or glyph stretching; native sandbox/font/media limits proven on isolated fixtures |
| 5. Mixed definition, creator identity and origin/receipt contracts | Versioned image/text definition, dual repository/DTO validation, explicit verified web submitter/origin, scoped authentication and media acquisition boundary; design any schema changes before implementing them | Legacy CSV/image-only/nullable origins stay valid; full filter-chain negative tests and JsonDB/SQLite/offline backup/restore/export/migration/rollback coverage; no forged Message |
| 6. In-app native preview and final submit | Bounded owned drafts, revision/sample-bound mixed rendering/cache, serialized finalization and stable receipt, existing moderation handoff | Dedicated dev-bot image + caption workflow completes without copying CSV/reattachment/chat confirmation; stale/lost/concurrent requests cannot duplicate or lose committed items; no publication weights/history changes for previews |
| 7. Reversible cutouts and further polish | Polygon opening, then erase/restore if useful; bounded local recovery and additional accessibility/performance refinement | Alpha/full-resolution round-trip, undo/redo and foreground-occlusion parity; measured phone behavior |

Slices 1–2 first deliver the real image tool, not another laboratory. Source and SDK packaging/unit-gate integration are implemented; applicable automated verification and device/keyboard/performance/release acceptance remain separate gates. Slice 3 sampling/text polish and slices 4–6 are later iterations, not first-release prerequisites. **Do not present mixed text submission/export as supported until its rendering/definition/transport contracts are implemented and accepted**; never silently drop text into legacy CSV. Settle fonts, launch mode and schema/resource decisions in their owning slices.

Slices 5–6 later address submission steps without blocking the first useful image-only release. Keep the legacy creator as fallback and the bot document/caption workflow unchanged. Editor adoption and direct-submission enablement are separate decisions. **No canonical `/create` switch now**; reconsider only after parity/phone/Telegram acceptance and explicit operator authorization. Runtime rollout/schema maintenance need separate authorization and data-aware rollback; reverting a JAR does not make mixed templates safe for an old binary.

### Test strategy

- **Current candidate:** single `#fit` includes image/off-canvas areas; natural open/tap/paste/drop and hidden pre-image tools; automatic editing/contextual toolbar parity; confirmed full-image one-area reset/undo; manual-send UI without “CSV”, unchanged caption format. Cover clipboard failure/success and explicit return without auto-close. Telegram Node checks use controlled SDK/DOM doubles for already/late load, ready/expand, theme/viewport, closing confirmation, old/missing/failed SDK; no external API or production bot. Verify the minimum of browser visual/Telegram stable heights and gesture cancellation before hosting viewport changes without canonical-coordinate mutation. Build/JAR guards permit one exact SDK external `src`, no remote `href`, and verify `hostedIntegrationScripts`; local CSP allows only that script URL while Spring security stays unchanged. Verify final main's applicable gates before overall-green claims; refresh LAN URLs per delivered editor slice; hardware acceptance and operator release stay separate.
- **Local media acceptance:** generated valid/corrupt PNG/JPEG; initial header read ≤1 MiB; JPEG metadata/dimensions within that window before SOS; byte/pixel/dimension boundaries and EXIF refusal. Cover PNG eXIf after IDAT/beyond the initial window, cached-header reuse, skipped IDAT payloads, duplicate eXIf rejection, the 2,048-chunk/1 MiB inspected-header-and-EXIF limits and IEND at exact EOF, all before object URL creation/decode. Require full `createImageBitmap` raster decode with matching dimensions, temporary/late bitmap closure, failure without API support and a shared 15-second image-load/full-bitmap deadline excluding header/metadata I/O. Include corrupt PNG pixels accepted by image onload/`decode()` but rejected by bitmap decoding; no extra 1×1 canvas probe is required. Keep mocked decoder lifecycle coverage distinct from actual-browser raster decoding, and do not infer hard total allocation bounds, decoder cancellation or real-device performance.
- **Geometry/model:** resize/rotate-device/zoom/DPR cycles leave original points unchanged; repeat-slot `[1,2,1]`; reorder both planes; delete middle slots; CSV round-trip; negative/edge/corner-only/off-canvas coordinates; convexity after integer rounding; boundaries 1/128/129 areas and byte/pixel limits.
- **Rectangle acceptance:** all four anchors on skewed fixtures; anchor/opposite preservation or explicit width/height override; cancel/no-op; exact one-action undo/redo; each edge moves exactly its pair; each corner resize fixes its opposite; optional aspect lock; skew → rectangle → perspective round-trips; rejected zero/crossed/no-positive-overlap geometry stays unchanged. Valid partially off-canvas rectangles remain supported. Perspective edge translation preserves its edge vector. Numeric assertions remain pixel-exact; native pointer expectations permit only CSS-pixel quantization, never opposite-edge drift.
- **Color acceptance:** known solid/generated pixel patches at pan/zoom/DPR and oriented JPEG/PNG; template colors under guides/text/placeholders; each target property; transparent/partial-alpha/off-image samples; no full-image extra allocation; commit/cancel/undo; pointer/selection/image changes and two-finger cancellation. Test with no `EyeDropper`, native API rejection, tainted/blocked reads and retained manual entry. Desktop Chrome/macOS and **Android Brave on hardware** must exercise the portable control; iOS Safari and Telegram WebViews are required before rollout, not inferred from emulation.
- **Workspace acceptance:** no engine/demo selector or permanently expanded file/CSV/palette block; measure the unobscured canvas target at 360×640/390×844; common font/color/background changes and sampling show their effect without body scrolling; keyboard-open selected-region preview, landscape panels, browser chrome/safe insets, focus return and 200% accessibility zoom. Panel resizing never rewrites document geometry. Screen captures review information density against the legacy creator.
- **Browser:** mouse/stylus/touch, second-finger interruption, pointer cancel, magnifier/nudge, keyboard focus, reduced motion, 360px/390px layouts, landscape, virtual keyboard/safe areas, Telegram Web iframe and native iOS/Android clients, standalone/no SDK/older SDK/CDN failure, cancelled/invalid image inputs and EXIF orientation. Emulation supplements real-device checks; target responsive frame times but measure rather than promise 60fps.
- **Rendering:** generated valid fixtures for skewed and off-canvas quads, reused slots, overlapping B/F areas, opaque/JPEG and alpha PNG holes; verify **B2,B4,template,F1,F3** and current forced-resize/perspective/mask behavior. Compare server/native output with fixed samples; any browser approximation is labeled.
- **Security:** forged/missing/wrong-bot/stale/future/duplicate-field initData; actor spoofing/IDOR/cross-draft access; creator denied moderation/source admin/publish/backup; unchanged ADMIN CSRF and generic errors; token expiry, origin/CORS and real iframe behavior; no credentials in logs/URLs/storage.
- **Failure/concurrency:** stale preview, edit-vs-submit/discard/expiry, double-click and lost successful response, render failure/interruption, quota/overload, cleanup of owned scratch, notification-after-commit failure, both repositories, legacy callbacks/imported null origins and functional backup/restore.
- **Gates:** Node model/browser tests and JS syntax, focused Java adapter/use-case/security/repository/render tests, then clean Java build and all Node/security/dependency gates as appropriate. A new JS dependency needs its own dependency/license coverage; Maven OSV success is not enough.

### Success criteria and open decisions

First-release success means natural image opening, corner placement to **one original-image pixel using precision controls**, reused sources, reachable off-canvas handles, zoom without drift, undo and unchanged manual document/caption submission with safe Telegram handoff. Submitter confirmation and administrator approval remain mandatory. Later success adds native preview correction and direct confirmed submission without copying/resending, with clear status after notification failure.

Measure started/completed creation and validation/render failure outcomes with opt-in/local usability feedback or low-cardinality aggregated telemetry, not raw initData, user names/images or draft UUID metrics. Establish a baseline before choosing time/abandonment targets; no improvement percentages have been measured yet.

Decided: Konva, vanilla/static delivery, original-pixel model, rectangle/perspective separation, anchored rectification, portable template sampling and a canvas-first contextual workspace. The foundation/image-only editor is implemented; portable sampling and the wider workflow remain future slices, with device/release acceptance still required.

Before enabling the owning backend slices, settle: licensed/pinned font artifacts and glyph/missing-description policy; actual dev-bot launch mode; availability of real approved-source previews; draft retention/quota/deadline values measured against host capacity; and concrete versioned-definition/origin/receipt schema with backward-recovery design. Validate the proposed panel heights on actual devices before freezing visual tokens/spacing. Cross-device drafts, editing approved templates, rotated/perspective text, multi-area transforms, whole-phone eyedropping and AI segmentation are not MVP assumptions.

## Upstream references

- [Telegram launch types, initData verification, native controls and limits](https://core.telegram.org/bots/webapps).
- [Bot API web_app_data](https://core.telegram.org/bots/api#webappdata), [answerWebAppQuery](https://core.telegram.org/bots/api#answerwebappquery), [document captions/transport](https://core.telegram.org/bots/api#senddocument). Inline document URLs support PDF/ZIP, so `answerWebAppQuery` is not a magic PNG-upload replacement; cached documents require a prior Telegram upload.
- [Konva overview](https://konvajs.org/docs/overview.html), [polygons](https://konvajs.org/docs/shapes/Line_-_Polygon.html), [pinch recipe](https://konvajs.org/docs/sandbox/Multi-touch_Scale_Stage.html), [pointer-centered zoom](https://konvajs.org/docs/sandbox/Zooming_Relative_To_Pointer.html), [relative pointer positions](https://konvajs.org/docs/sandbox/Relative_Pointer_Position.html), [license](https://github.com/konvajs/konva/blob/master/LICENSE).
- [Fabric polygon controls under viewport transforms](https://fabricjs.com/demos/poly-controls/), [license](https://github.com/fabricjs/fabric.js/blob/master/LICENSE).
- [Moveable overview](https://daybrush.com/moveable/), [warp controls/matrix API](https://daybrush.com/moveable/release/latest/doc/Moveable.Warpable.html), [license](https://github.com/daybrush/moveable/blob/master/LICENSE).
- [Konva Transformer dimensions/scales](https://konvajs.org/docs/select_and_transform/Basic_demo.html), [explicit aspect-ratio behavior](https://konvajs.org/docs/select_and_transform/Keep_Ratio.html).
- [EyeDropper compatibility](https://developer.mozilla.org/en-US/docs/Web/API/EyeDropper), [activation/cancellation](https://developer.mozilla.org/en-US/docs/Web/API/EyeDropper/open), [canvas pixel sampling](https://developer.mozilla.org/en-US/docs/Web/API/Canvas_API/Tutorial/Pixel_manipulation_with_canvas), [canvas origin restrictions](https://developer.mozilla.org/en-US/docs/Web/HTML/How_to/CORS_enabled_image).