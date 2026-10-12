# Template creation improvement design

**Status: researched proposal, not implemented behavior.** This folder is the workspace for the template-creation stage, separate from the maintained runtime/operations guides. Keep design decisions and acceptance criteria here, not production credentials, user images or incident transcripts.

| Document | Purpose |
| --- | --- |
| [Current flow](current-flow.md) | Source-verified browser → Telegram → preview → moderation → publication flow, rendering semantics and friction |
| [Proposal](proposal.md) | Recommended UX, library comparison, authenticated direct submission, compatibility risks and incremental delivery/testing plan |

## Recommendation in brief

Replace the CSV-first experience with **choose image → define areas → preview in the app → send for approval**. Keep administrator approval, but remove copying coordinates, manually reattaching the image/caption and the additional chat confirmation from the normal path.

- Use a focused canvas editor: zoom/pan, explicit corner handles, precision controls, undo/redo and visible source/layer relationships. **Konva is the preferred starting point**, subject to a small real-device comparison with Moveable's perspective controls.
- Keep original-image coordinates independent of viewport size/zoom; retain the existing quadrilateral, source-slot and two-plane rendering semantics.
- Reuse ImageMagick for authoritative previews; lightweight browser overlays are editing aids, not guaranteed pixel-identical renders.
- Add authenticated Mini App upload/preview/final-submit use cases. This needs explicit submitter/origin persistence and resource limits, not anonymous access to administrator APIs or fabricated Telegram Messages.
- Add reversible **cut a transparent opening in this area** tooling after the core flow. Arbitrary AI background removal is optional later work, not a prerequisite or a promise.

## Investigation limits

Reviewed active source, existing regression tests, public live creator assets, the shared creator DOM and official Telegram/library documentation. The shared standalone creator has no Telegram `initData`; the shared Telegram page had no open creator frame during inspection. The screenshot and user description establish the bot-launched experience, but the exact current menu/inline/keyboard launch configuration is not defined in the repository and remains to be checked with the dedicated development bot before integration.

No production upload, preview render, callback, notification, publishing, deployment or restart was performed for this design. Real iOS/Android interaction, candidate library performance and a complete new submission round-trip are **future acceptance checks**, not claimed results. This change contains documentation only.