# Template creation: editor and remaining design

**Status: first image-only implementation slice exists; the full journey is not complete.** `frontend/template-creator/` is the Konva-only editor source/build package, integrated into Gradle/JAR resources at public `/create/editor/`. Legacy `/create` and its document/caption submission remain unchanged. The isolated mixed image/text comparison spike stays unchanged and separate. Keep current contracts, remaining design and acceptance criteria here, not production credentials, user images or result timelines. Runtime/build instructions also live in the [project guide](../project-map.md#image-only-editor-current-first-slice) and [maintenance](../maintenance.md#editor-build-and-verification).

| Document | Purpose |
| --- | --- |
| [Current flow](current-flow.md) | Legacy `/create` → Telegram → preview → moderation → publication flow, rendering semantics and friction |
| [Implementation proposal](proposal.md) | Implemented image-only foundation, remaining color/text/backend slices, complete journey and acceptance gates |
| [Mixed-area spike](spike.md) | Local runnable Konva/Moveable comparison, exact commands, retained failing candidate and verified limits |
| [Text areas](text-areas.md) | Source-linked description patterns, typography/overflow and production rendering/font/versioning gates |

## Recommendation in brief

Replace the CSV-first experience with **choose image → define areas → preview in the app → send for approval**. Keep administrator approval, but remove copying coordinates, manually reattaching the image/caption and the additional chat confirmation from the normal path.

- Use **Konva** in the real tool, with rectangle/perspective modes, anchored rectification, edge-pair resizing, precision controls and undo. Moveable remains only in the unchanged comparison spike. Actual native-phone acceptance is still required.
- Give desktop and mobile the same template-pixel color sampler; native screen eyedroppers are optional, not a mobile dependency. Keep the canvas/selected region visible while using compact contextual font/color/geometry panels, including keyboard-open editing.
- Add typed text boxes bound to the same source slots as their images, with safe description placeholders and explicit typography/overflow rules; this currently works only in the spike.
- Keep original-image coordinates independent of viewport size/zoom; retain the existing quadrilateral, source-slot and two-plane rendering semantics.
- Reuse ImageMagick for authoritative previews; lightweight browser overlays are editing aids, not guaranteed pixel-identical renders.
- Add authenticated Mini App upload/preview/final-submit use cases. This needs explicit submitter/origin persistence and resource limits, not anonymous access to administrator APIs or fabricated Telegram Messages.
- Add reversible **cut a transparent opening in this area** tooling after the core flow. Arbitrary AI background removal is optional later work, not a prerequisite or a promise.

## Available now

- Vanilla JS **Konva 10.7.1 only at runtime**, esbuild 0.28.2 and Playwright 1.64.0 build/test tools; tracked package/lockfile, CI Node 22.23.1. Hashed self-hosted assets/manifest/notices are generated at `build/generated-resources/creator/static/create/editor/` and packaged by Gradle `processResources`, not edited in place.
- Canvas-first image editing with mobile internally scrolling contextual panel, original-pixel model, rectangle/perspective corners and paired edges, anchored ghost preview/Apply/Cancel, undo, numeric/nudge, source/background and order controls.
- Local PNG/JPEG ≤20 MiB/40MP/10,000 per side; initial header read ≤1 MiB, with JPEG metadata/dimensions confined to that window before SOS. Before object URL creation/decode, the private async `inspectPngMetadata(file, head)` walks PNG chunk headers through IEND, including eXIf after IDAT/beyond the initial window, skipping IDAT payloads and reusing cached header bytes when they fit. The walk is limited to 2,048 chunks and 1 MiB of inspected headers/EXIF bytes; IEND must end at EOF. Duplicate eXIf and non-1 orientation are rejected, with normalize-a-copy guidance for orientation.
- Mandatory full `createImageBitmap` raster decode checks dimensions and closes temporary bitmaps, including late completion after timeout/cancellation; unsupported browsers fail closed. Corrupt PNG pixels can pass image onload/`decode()` but fail bitmap decoding: no additional 1×1 canvas probe is needed for decode verification or required for acceptance. The shared 15-second image-load/full-bitmap deadline excludes header/metadata I/O and guarantees neither browser allocation bounds nor decoder cancellation. Future color sampling uses the separately planned bounded scratch canvas.
- Image-only 11-column CSV import/export: legacy contiguous Area indices starting at 0 or 1 normalize to 1-based export; backend still requires 1-based. Imported repeated sources survive adjacency-picker restrictions; valid positively overlapping off-canvas geometry stays unchanged.
- Standalone `npm start --prefix frontend/template-creator` at **http://127.0.0.1:4174/create/editor/** after the locked install documented in maintenance. No Spring, bot or storage startup; tests use generated fixtures, not the user's untracked `spikes/example/` media, which stays untouched.

**Not available yet:** text/color eyedropper, backend native preview, direct Mini App submit, creator identity/draft APIs or durable draft saving. Overlays are not a rendered meme, and the editor still exports CSV for the legacy manual route. The remaining phased plan is preserved, not marked complete.

## Investigation limits

The exact menu/inline/keyboard launch configuration is not defined in the repository and must be checked with a dedicated development bot before identity integration. Standalone editor access establishes no Telegram identity; an SDK object or bot-launched appearance is not authentication evidence.

Automated editor model/media tests, Chromium desktop/mobile-emulation and packaged-resource checks exist independently of the spike's checks; configured gates do not establish a green full run. User feedback from macOS Chrome and Android Brave informed the design, but real Android Brave/iOS/Telegram WebView, keyboard-open usability, performance and a complete direct-submission round-trip remain **pending release gates**, not inferred from emulation. Older local Java 21 can run tests but does not satisfy the patched maintenance floor. This status is not deployment approval or release-parity evidence; no live upload/render/notification/publishing/deployment is required to verify the standalone editor.