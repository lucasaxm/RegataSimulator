# Template creation: editor and remaining design

**Priority: release the useful image-only editor through the existing pipeline, then iterate the wider journey.** `frontend/template-creator/` is the Konva-only source/build package. User-authorized default-route adoption is implemented on `spike/template-creator-text`, not yet deployed/live. Preserve integrated Telegram SDK UX, manual bot document/caption upload, submitter confirmation and administrator review; text/color/native preview/identity/direct submission do not block this release. The isolated spike stays unchanged. Keep contracts/design here, not credentials, user images or historical result timelines. See the [project guide](../project-map.md#image-only-editor-current-first-slice) and [maintenance](../maintenance.md#editor-build-and-verification) for current contracts and verification boundaries.

| Document | Purpose |
| --- | --- |
| [Current flow](current-flow.md) | Historical legacy editor at `/create/legacy`; preserved Telegram → preview → moderation → publication flow and rendering semantics |
| [Implementation proposal](proposal.md) | Implemented image-only foundation, remaining color/text/backend slices, complete journey and acceptance gates |
| [Mixed-area spike](spike.md) | Local runnable Konva/Moveable comparison, exact commands, retained failing candidate and verified limits |
| [Text areas](text-areas.md) | Source-linked description patterns, typography/overflow and production rendering/font/versioning gates |

## Recommendation in brief

**Implemented source route contract (deployment pending):**

| Entry | Forward target / behavior |
| --- | --- |
| `/create`, `/create/` | Packaged new `/create/editor/index.html` |
| `/create/editor`, `/create/editor/` | Retained new-editor aliases |
| `/create/legacy`, `/create/legacy/` | Untouched old `/create/index.html` |
| `/create/index.js`, `/create/styles.css` | Old absolute assets remain reachable |
| **Criador anterior** | Link to `/create/legacy` |

The existing bot **`/create` URL stays unchanged**. No file moves, resource-handler changes or security/server/host changes. This source contract is not proof of a deployed artifact and grants no push/merge/deploy, bot-link or live-operation authority.

First deliver **open image → define/edit areas → prepare/copy data → manually attach original image as a document and paste its caption in the bot → confirm → administrator review**. Improve the editor, not submission backends, in this release candidate. Later **in-app native preview → direct send for approval** can remove manual steps after its own identity/rendering/persistence gates.

- Use **Konva** in the real tool, with rectangle/perspective modes, anchored rectification, edge-pair resizing, precision controls and undo. Moveable remains only in the unchanged comparison spike. Keep user-reported Telegram acceptance distinct from unrecorded device coverage and artifact smokechecks.
- Give desktop and mobile the same template-pixel color sampler; native screen eyedroppers are optional, not a mobile dependency. Keep the canvas/selected region visible while using compact contextual font/color/geometry panels, including keyboard-open editing.
- Add typed text boxes bound to the same source slots as their images, with safe description placeholders and explicit typography/overflow rules; this currently works only in the spike.
- Keep original-image coordinates independent of viewport size/zoom; retain the existing quadrilateral, source-slot and two-plane rendering semantics.
- Reuse ImageMagick for authoritative previews; lightweight browser overlays are editing aids, not guaranteed pixel-identical renders.
- Add authenticated Mini App upload/preview/final-submit use cases. This needs explicit submitter/origin persistence and resource limits, not anonymous access to administrator APIs or fabricated Telegram Messages.
- Add reversible **cut a transparent opening in this area** tooling after the core flow. Arbitrary AI background removal is optional later work, not a prerequisite or a promise.

## Acceptance status and next delivery

The image-only editor, optional official Telegram SDK UX and restricted HTTPS preview mode are implemented. **The user reports that testing the candidate inside Telegram through the temporary HTTPS preview went well.** This is user-reported Telegram-hosted acceptance, not just an SDK double or browser emulation. Device/OS/client versions and individual scenarios were not recorded, so it does not establish Android/iOS/keyboard/performance coverage, successful manual submission/moderation, authenticated creator APIs or release approval. Temporary tunnels are testing tools, not hosting; see [maintenance](../maintenance.md#editor-build-and-verification) for exposure and shutdown limits.

**Next milestone: existing pipeline release plus focused smokecheck**, not another feature phase or a new decision to adopt `/create`:

1. Verify the candidate and release gates with the **21.0.12.1 maintenance-floor JDK** and applicable clean build, frontend/browser/package and security checks. Earlier local passes and configured CI are not current release parity; do not bypass findings/incomplete scans.
2. Obtain explicit operator approval for the live release through the [existing deployment pipeline/runbook](../deployment-setup.md#release-and-rollback). Source adoption does not establish a deployed artifact, require host/security changes or authorize push/merge/deploy, bot-link changes or live operations.
3. Smokecheck `/create` and `/create/`, both editor aliases, both legacy aliases, new hashed assets, old absolute JS/CSS and **Criador anterior** on the approved artifact. Check image opening/editing and copy/manual-copy handoff through the unchanged bot URL. Keep device/keyboard/performance limits explicit, without requiring the entire future-feature matrix before this useful release.
4. The original-document/caption → submitter confirmation → administrator review journey stays unchanged; any live mutation to verify it needs explicit authorization. Do not infer complete journey proof from the Telegram report. Retain `/create/legacy` as candidate fallback; rollback uses the prior compatible artifact/captured unit per the deployment runbook. An older JAR may lack `/create/legacy` and restores its original `/create`; artifact rollback does not undo data/schema writes.

After this first useful image-editor delivery, proceed with portable color/text UX, native text/font rendering, versioned definitions and verified creator identity, then in-app native preview/direct submission as detailed in the [implementation proposal](proposal.md#incremental-delivery-and-acceptance).

## Current source and release-candidate scope

- Vanilla JS **Konva 10.7.1 only at runtime**, esbuild 0.28.2 and Playwright 1.64.0 build/test tools; tracked package/lockfile, CI Node 22.23.1. Hashed self-hosted assets/manifest/notices are generated at `build/generated-resources/creator/static/create/editor/` and packaged by Gradle `processResources`, not edited in place.
- Canvas-first image editing with mobile internally scrolling contextual panel, original-pixel model, rectangle/perspective corners and paired edges, anchored ghost preview/Apply/Cancel, undo, numeric/nudge, source/background and order controls.
- Current candidate contracts: single `#fit` **Ajustar** frames image and off-canvas areas without a separate **Áreas** fit button; central open/empty-canvas tap/paste/drop; tools hidden before image; automatic editing instead of Select; contextual **Forma / Precisão / Camadas** preserving features; confirmed undoable reset retaining the image with one full-image area. **Preparar envio / Dados do template** avoids “CSV” in user UI while preserving internal 11-column data and manual bot handoff.
- Official async **https://telegram.org/js/telegram-web-app.js** and `telegram.mjs` provide ready/expand, theme/viewport, gated closing confirmation and copy-success feedback with explicit **Voltar ao bot**, never auto-close. SDK absent/failed/old must preserve standalone editing/manual copy. Only this exact remote SDK script is allowed; Konva/application assets stay self-hosted. The app uses the minimum of browser visual height and valid Telegram stable height, cancelling gestures before hosting viewport changes without mutating canonical coordinates. No external APIs or authentication inferred from initData/UI.
- Local PNG/JPEG ≤20 MiB/40MP/10,000 per side; initial header read ≤1 MiB, with JPEG metadata/dimensions confined to that window before SOS. Before object URL creation/decode, the private async `inspectPngMetadata(file, head)` walks PNG chunk headers through IEND, including eXIf after IDAT/beyond the initial window, skipping IDAT payloads and reusing cached header bytes when they fit. The walk is limited to 2,048 chunks and 1 MiB of inspected headers/EXIF bytes; IEND must end at EOF. Duplicate eXIf and non-1 orientation are rejected, with normalize-a-copy guidance for orientation.
- Mandatory full `createImageBitmap` raster decode checks dimensions and closes temporary bitmaps, including late completion after timeout/cancellation; unsupported browsers fail closed. Corrupt PNG pixels can pass image onload/`decode()` but fail bitmap decoding: no additional 1×1 canvas probe is needed for decode verification or required for acceptance. The shared 15-second image-load/full-bitmap deadline excludes header/metadata I/O and guarantees neither browser allocation bounds nor decoder cancellation. Future color sampling uses the separately planned bounded scratch canvas.
- Image-only 11-column CSV import/export: legacy contiguous Area indices starting at 0 or 1 normalize to 1-based export; backend still requires 1-based. Imported repeated sources survive adjacency-picker restrictions; valid positively overlapping off-canvas geometry stays unchanged.
- Standalone `npm start --prefix frontend/template-creator` at **http://127.0.0.1:4174/create/editor/** after the locked install documented in maintenance. No Spring, bot or storage startup; tests use generated fixtures, not the user's untracked `spikes/example/` media, which stays untouched.

**Integrated build and verification contracts:** `build.mjs`/`verify-package.mjs` allow exactly one external `src`, the official SDK URL, and no remote `href`; the manifest records `hostedIntegrationScripts` and package verification checks its URL. Local server CSP permits only that exact remote script URL; Spring's existing frame-ancestor/security policy is unchanged, not globally rewritten. `build.mjs --check` and Gradle `buildCreator` explicitly include `telegram.mjs`; npm test runs model/media/controlled Telegram unit suites, and CI invokes these through `frontendCheck`. Node/npm/dependency versions stay unchanged. Standalone and trusted-Wi-Fi LAN preview rebuild before serving. After every delivered editor slice refresh `npm start --prefix frontend/template-creator -- --lan` and provide newly printed reachable phone URLs; default/CI stays loopback-only. Integration is not overall-green evidence or device/release acceptance. Documentation-only work starts no server.

**Later iterations, not first-release blockers:** text/color eyedropper, backend native preview, direct Mini App submit, identity/draft APIs and durable draft saving. Overlays are not native renders. Preserve the remaining plan without making all its phases prerequisites for the useful image-only tool.

## Investigation limits

The exact menu/inline/keyboard launch configuration is not defined in the repository and must be checked with a dedicated development bot before identity integration. Standalone editor access establishes no Telegram identity; an SDK object or bot-launched appearance is not authentication evidence.

Model/media tests, controlled Telegram SDK unit doubles, Chromium emulation and package checks are distinct from the spike; their presence is not overall-green evidence or real-device proof. The user-reported successful Telegram test does not prove an unspecified Android Brave/iOS/client matrix, keyboard usability or performance coverage. These are verification limits, not a requirement to finish later text/color/native-preview/auth slices before the image-only release. Direct-submission round-trip belongs to the later slice, not this candidate. Older Java 21 is not maintenance-floor parity. No production-bot tests or live upload/render/notification/publishing/deployment are authorized by this status.