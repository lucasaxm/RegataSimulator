# Image-first mixed image/text editor spike

**Local experiment, not the production creator.** Source lives in [`spikes/template-creator/`](../../spikes/template-creator/), outside Spring's packaged static resources. Load a local template and draw image/text regions; the two synthetic scenes remain optional examples. Nothing is uploaded to a server. No bot, Spring, database, credentials, production assets or native ImageMagick work is started.

## Run and verify

From repository root with Node/npm available:

- Install reproducibly: `npm ci --ignore-scripts --prefix spikes/template-creator`.
- Start only the local preview: `npm start --prefix spikes/template-creator`; open **http://127.0.0.1:4173**. It binds loopback only and exposes a fixed read-only asset map; POST returns 405. Stop with Ctrl+C before rebundling edits.
- Syntax/model checks: `npm run check --prefix spikes/template-creator` and `npm test --prefix spikes/template-creator`.
- Chromium install if needed: `npm exec --prefix spikes/template-creator -- playwright install chromium`.
- Browser regressions: `npm run test:browser --prefix spikes/template-creator`. Playwright starts/stops its isolated server if no preview is already running. Avoid reusing an old bundled server after source edits.
- npm dependency gate: `npm run audit --prefix spikes/template-creator`; findings/incomplete results are not a clean pass. Maven scanning does not cover this separate package.

The package pins Konva 10.7.1, Moveable 0.53.0, esbuild 0.28.2 and Playwright 1.64.0; the lockfile is authoritative. No global install or framework replacement is needed. Bundling is in memory; dependencies/node_modules and per-run Playwright artifacts are not checked in. Browser-library license notices are available through the preview's local license link. This experiment is not yet part of root Gradle/CI gates.

The inventory retains packaged notices and flags any **metadata-only license evidence** instead of fabricating copyright text. In particular, Croact 1.0.4 (Moveable dependency) declares MIT but has no notice file in its package or reviewed upstream tree. These gaps block production redistribution/adoption until upstream notice review is complete; a clean npm vulnerability audit is not license clearance. Konva's packaged notice is retained. Do not turn this local experiment into a publicly deployed bundle without resolving the inventory gaps.

## What to try

1. Start with **Seu template**, load a local PNG/JPEG and choose **+ imagem** or **+ texto**, then drag on the template. No areas are inserted automatically. Replacement asks for confirmation when areas exist; cancel/failed decoding preserves the current image and document. The two demos are secondary choices.
2. Select an image or text by the canvas/list; drag its corner or interior. Text resizes as an axis-aligned box, images keep independent TL/TR/BR/BL points. Use X/Y and the nudge pad for exact one-original-pixel edits. Deleting the last area returns to an empty document; undo restores it.
3. Zoom/pan or pinch with two pointers; verify saved geometry doesn't change. Adding a second pointer cancels an unfinished edit/drawing, and lifting a finger doesn't resume it. Escape cancels drawing without adding an undo entry.
4. Image source choices follow the current creator's image adjacency rules: first image uses 1; a last image can reuse its previous image's slot or use the following slot. Interleaved text does not change those image neighbors. Defaults use the smallest unused reference when the picker permits it, otherwise a permitted adjacent slot. Text can bind any currently referenced slot and inherits the selected area's slot. Required slots stay contiguous across both types. The sample-description palette is not the set of permitted image choices.
5. Draw text over/below an existing caption and edit `Candidato {{source.description}}` or `{{source.description}} entrou no grupo`. Enable its optional **box fill** to cover the existing lettering; this is distinct from the behind-template plane. Change the bound slot's synthetic description, font/color/outline/alignment/line spacing and min/max size. Try a long description and watch the explicit overflow result.
6. Paste an existing image-only CSV, including the original header, to replace the areas at the loaded image's dimensions. Export preserves the current 11-column bot format and original integer coordinates. **Any text area disables CSV export** rather than silently dropping text; mixed documents use experimental JSON.
7. Reuse slots, change background plane, reorder, delete and undo; then switch engines without losing the document. JSON export/import is an experimental model round-trip only—**do not send that JSON to the bot**. It contains no image bytes/path: for a custom document, load the same template locally before importing matching-dimension JSON. Strict import rejects unknown/prototype keys, bad geometry, missing bindings/fonts and oversized documents.

Local files are bounded to 20 MiB, 10,000 pixels per side and 40 million pixels. MIME and PNG/JPEG magic are checked; SVG/PDF/disguised files are refused. PNG header dimensions are checked before decode; JPEG dimensions are checked after browser decode, **not** a predecode memory guarantee. Object URLs are released on replacement; no encoded image is included in JSON. Actual template display rasters are downsampled to at most 2,048 pixels per side while document coordinates retain original dimensions.

Image-area previews are translucent colored/labeled source regions over the loaded template; Konva uses polygon fills and Moveable uses a projected DOM canvas. They are editing aids, not actual selected-source photo-distortion renderers. Background-plane outlines remain visible even when an opaque template hides their fill. Captions use native Konva.Text versus a DOM canvas using shared layout rules.

## Comparison and recommendation

| Concern | Konva adapter | Moveable adapter |
| --- | --- | --- |
| Skewed quad controls | Explicit scene-graph corner handles and hit canvas | Native Warpable controls + world/DOMMatrix conversion |
| Text areas | Native Text nodes in clipped rectangular groups | Resizable DOM targets with canvas text rasters |
| Model/zoom/slot/style round-trip | Validated shared model; viewport stays separate | Same model; CSS camera is separate from Moveable UI zoom |
| Gestures | Successful Chromium desktop/mobile-emulation checks, including first-drag selection and pinch cancellation | Selected-corner/text-resize/pinch checks work, but first-drag unselected target switching is still defective |
| Integration complexity | Custom geometry/handles, but one canvas scene graph for mixed areas | Async target/drag lifecycle plus CSS perspective matrix and raster management |

**Recommend Konva for the next editor slice**, especially with native text and mixed layers. This is an integration-quality recommendation, not a proof that Konva performs faster or that Moveable cannot be made to work. Fabric was researched previously but not implemented in this comparison.

### Retained failing candidate (explicit user choice)

Moveable is retained with a visible warning. Starting a drag directly on an unselected area can lose its Gesto drag state, producing `Cannot set properties of null (setting 'dist')` and a partial/no movement. The adapter includes a target-change barrier but the defect remains. Select through the area list before dragging as a comparison workaround; this is **not** acceptable production UX.

The browser regression **“first drag selects and moves an unselected area … (known Moveable target race)”** is annotated as an expected failure **only for Moveable** at both viewport sizes. A successful runner therefore does not mean every candidate behavior passed. Konva's same case and all other regressions remain ordinary assertions, including uncaught-error/network guards. Unexpected success of the known case fails the runner and requires updating/removing the annotation alongside a real fix. Do not copy that annotation into a production regression suite as a permanent desired contract.

## Verified evidence and limits

Executed pure-model/syntax and npm audit gates, plus actual-library Chromium regressions at desktop 1280×900 and mobile-emulation 390×844/DPR2. Tests use generated PNG fixtures, not user-owned example photos, and exercise local image loading, pointer-drawn areas/cancellation, the supplied three-row CSV at 1080×1114, source/text binding, styles, overflow, exact numeric/nudge precision, off-canvas points, layer order, undo, JSON round-trip and hostile input. Ordinary behavior passed; the named Moveable first-drag characterization still fails as expected. Per-run counts/logs remain test output, not timeless promises in this guide.

At fitted zoom, one CSS-pixel pointer step can span multiple original-image pixels on either desktop or mobile. Gesture assertions allow only that derived quantization, while uniform image translation, fixed opposite text-resize corners, saved CSV, numeric/nudge edits and undo remain exact. This is why zoom and precision controls are both needed.

The UI's update timing measures synchronous adapter work, **not** complete frame latency, a fair rendering benchmark or native-render speed. No performance winner/60fps/low-end-device claim is made. There is no native Telegram launch/identity test, actual Android/iOS WebView test, server preview/upload, bundled font metric/glyph parity, transparency brush/cutout tool or publish flow. The current mobile inspector is a below-canvas prototype, not the final bottom-sheet/magnifier design. Large DOM text rasters are display-downsampled; this is not a full-resolution export pipeline.

The read-only local server denies arbitrary filesystem paths and network/API endpoints, but the prototype is not a new production security design. Keep it out of the packaged creator. See [text-area design](text-areas.md) for font/literal-text/server/schema gates and [broader proposal](proposal.md) for authenticated upload/submission and recovery.