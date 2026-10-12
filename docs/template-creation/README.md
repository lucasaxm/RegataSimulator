# Template creation improvement design

**Status: production redesign is proposed; an isolated mixed image/text browser spike is implemented.** This folder is the workspace for the template-creation stage, separate from the maintained runtime/operations guides. Keep design decisions and acceptance criteria here, not production credentials, user images or incident transcripts.

| Document | Purpose |
| --- | --- |
| [Current flow](current-flow.md) | Source-verified browser → Telegram → preview → moderation → publication flow, rendering semantics and friction |
| [Proposal](proposal.md) | Recommended UX, library comparison, authenticated direct submission, compatibility risks and incremental delivery/testing plan |
| [Mixed-area spike](spike.md) | Local runnable Konva/Moveable comparison, exact commands, retained failing candidate and verified limits |
| [Text areas](text-areas.md) | Source-linked description patterns, typography/overflow and production rendering/font/versioning gates |

## Recommendation in brief

Replace the CSV-first experience with **choose image → define areas → preview in the app → send for approval**. Keep administrator approval, but remove copying coordinates, manually reattaching the image/caption and the additional chat confirmation from the normal path.

- Use a focused canvas editor: zoom/pan, explicit corner handles, precision controls, undo/redo and visible source/layer relationships. **Konva is recommended after the local mixed-area comparison**; Moveable remains experimental with a documented first-drag failure. Actual native-phone acceptance is still required.
- Add typed text boxes bound to the same source slots as their images, with safe description placeholders and explicit typography/overflow rules; this currently works only in the spike.
- Keep original-image coordinates independent of viewport size/zoom; retain the existing quadrilateral, source-slot and two-plane rendering semantics.
- Reuse ImageMagick for authoritative previews; lightweight browser overlays are editing aids, not guaranteed pixel-identical renders.
- Add authenticated Mini App upload/preview/final-submit use cases. This needs explicit submitter/origin persistence and resource limits, not anonymous access to administrator APIs or fabricated Telegram Messages.
- Add reversible **cut a transparent opening in this area** tooling after the core flow. Arbitrary AI background removal is optional later work, not a prerequisite or a promise.

## Investigation limits

Reviewed active source, existing regression tests, public live creator assets, the shared creator DOM and official Telegram/library documentation. The shared standalone creator has no Telegram `initData`; the shared Telegram page had no open creator frame during inspection. The screenshot and user description establish the bot-launched experience, but the exact current menu/inline/keyboard launch configuration is not defined in the repository and remains to be checked with the dedicated development bot before integration.

No production upload, preview render, callback, notification, publishing, deployment or restart was performed for this stage. Local Chromium desktop/mobile-emulation and pure-model checks were executed for the spike; real iOS/Android interaction, candidate performance benchmarking and a complete new submission round-trip are **future acceptance checks**, not claimed results. Application code/storage/rendering remain unchanged.