# Production-copy SQLite audit — 2026-10-10

## Outcome

The exact offline importer finished with **exit 2 / AUDIT_FAILED**, reading the coherent verified backup at `/home/ubuntu/.local/share/regata-v1-recovery/pre-upgrade-0tk4ins2`. No SQLite file was created or activated. No original/backup records or image files were modified or discarded.

Safe report retained at `/home/ubuntu/.local/share/regata-production-candidate-20261010/import-report.json`; private operation log and read-only diagnostic helper are retained alongside it. Do not print persisted Telegram Messages or legacy runtime recovery secrets.

## Records loaded

**1,026 sources / 349 templates / 1,000 memes / 7 users / 0 audits.** All collection records loaded; no `MISSING_MEDIA`, invalid weight/status or unreadable collection issue was reported. Absence of those codes is not whole-dataset functional rendering proof.

Nonblocking legacy findings: 900 sources and six templates have null origins. These are supported nullable contracts, not records to delete.

## Blocking compatibility issues

- **Three duplicate normalized source-name pairs**: six records total, producing three UNIQUE conflicts. Preserve UUIDs/media/history and explicitly decide distinct names; never merge/drop one record silently.
  - `5c89dea9-c8f0-483d-9afb-515beb90af16` / `ab8cd2c7-926a-48c6-948c-0947c738d399`
  - `dc90d15a-e1f1-4aaf-9007-ee2f03670027` / `f70cb450-60e8-4726-b8fd-962262937491`
  - `01afd052-8f6c-4789-a723-94b81e9ab9bf` / `e475a170-00ef-4198-b888-9671853001c9`
- **Six approved templates fail corner bounds**. Three also fail geometry validation without dimensions; the other three pass general geometry but exceed decoded image bounds. Read-only diagnostic used the current `MediaValidation` implementation. No coordinate clamping/rescaling was applied.
  - `a3246c25-9559-414f-9436-2c985b7e493a` (531×470), `798a6e5e-e098-4296-be4a-0982927501a8` (619×372), `53f66ab7-d522-45d5-8522-02f63a33b34e` (454×549): bounds fail even without image dimensions.
  - `bcf71270-2716-4d78-9860-76b27b7e9842` (1743×2048), `0f620ae2-aa81-4fdc-9fb9-dfa209eca50c` (447×559), `bc1ebd69-050c-47e4-bd0f-b395128be2ae` (1050×1458): geometry passes, decoded-image bounds fail.
- **Two approved templates have valid WebP bytes under `template.jpg`**, not missing/corrupt files: `568f0013-ae78-4d6a-8333-30cea850e848` (1080×1166) and `4cd119c7-d9f2-43d0-9213-1cd5f6d9a6a0` (1432×1920). Active Java ImageIO validation accepts PNG/JPEG, not this encoding. General geometry passed; image-bounds validation needs rechecking after a same-dimension PNG conversion on a new working copy.

The aggregate report has eight template `INVALID_MEDIA_OR_BOUNDS` findings and three additional `INVALID_GEOMETRY` findings for the same three templates, not eleven distinct templates.

## Preservation-first next steps (not yet performed)

Obtain explicit approval for a new repaired working copy: preserve all UUIDs/records/history and original bytes; record old/new hashes and field changes in a private repair manifest; convert the two WebP assets to same-dimension PNG; choose deterministic distinct descriptions for duplicate sources; inspect/correct six geometries with visual before/after rendering rather than blind clamping. Do not exclude invalid templates or weaken validators to get a green import.

Rerun importer into a fresh SQLite target/report only after approved changes and independent validation. Verify record counts/canonical reconciliation, integrity/foreign keys, real dataset render/backup/restore under Linux constraints and post-write rollback policy before production activation. Original production storage and verified on/off-host v1 recovery archives remain the recovery baseline.

Current state: **v1 intentionally stopped; replacement not deployed; merge remains on hold**. [Linux neutral-fixture capacity checks](linux-capacity-results.md) passed but do not resolve these legacy input blockers. See [preservation checkpoint](production-preservation-checkpoint.md).