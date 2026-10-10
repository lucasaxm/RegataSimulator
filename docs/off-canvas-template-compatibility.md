# Intentional off-canvas template areas — 2026-10-10

The user confirmed that existing negative/out-of-image coordinates are intentional. The earlier strict corner-containment rule was incompatible with that feature. This source change preserves existing coordinates; it is not a legacy-data repair or permission to clamp/rescale them.

## Contract

- Each area remains an ordered, nondegenerate convex quadrilateral with contiguous indices/source slots.
- Signed coordinates from −10,000 through +10,000 remain bounded for resource safety. Image byte/pixel bounds, external process limits and scratch ownership are unchanged.
- With decoded image dimensions available, **every area must intersect the canvas with positive area**. Partial clipping, crossing the canvas with all vertices outside, and surrounding the canvas are allowed. Completely outside polygons and edge/corner-only contact are rejected.
- Convex polygon separating-axis checks handle both winding directions and reject diagonal separation even if bounding boxes overlap. Long arithmetic and signed coordinate bounds avoid overflow.
- CSV parsing/SQLite metadata checks without dimensions enforce general geometry; upload/import/recovery use decoded dimensions for overlap. The renderer uses the same overlap check after obtaining template dimensions and before resize/distortion.

Regression coverage includes negative corners, surrounding/crossing polygons, winding directions, small positive overlap, complete separation, edge/corner contact, diagonal false-positive bounding boxes, coordinate overflow/limits, multi-area rejection, submission persistence/cleanup and renderer perspective argument preservation/cleanup.

## Verification

- Focused geometry/submission/renderer/offline-store/recovery/SQLite tests passed on checksum-verified patched Temurin 21.0.12.1+1.
- Full `verifyMaintenanceJava clean build`: **845 JUnit cases / 42 suites**, zero failures/errors/skips. Separate Node checks: **18 passing**, zero failures/cancellations/skips. No dependency upgrade or OSV rerun occurred in this slice.
- Real local ImageMagick smoke without Spring/Telegram: partially off-canvas convex area `(-50,-40), (200,-40), (200,200), (-50,200)` rendered to a 400×300 canvas. Decoded output matched blue pixels in the visible region and white pixels outside; owned scratch was removed. This validates one native clipping case, not every production template or peak production memory.
- Production storage, backup copies and source descriptions were not altered. New executable artifact must replace the earlier tested artifact for re-audit; earlier artifact checksums/capacity evidence are historical, not evidence for these changed classes.

## Duplicate sources found in the preserved production copy

| Name pair | APPROVED UUID | REJECTED UUID |
| --- | --- | --- |
| `valber` / `VALBER` | `dc90d15a-e1f1-4aaf-9007-ee2f03670027` | `f70cb450-60e8-4726-b8fd-962262937491` |
| `Lucas` / `lucas` | `01afd052-8f6c-4789-a723-94b81e9ab9bf` | `e475a170-00ef-4198-b888-9671853001c9` |
| `gabriel cagado` / `gabriel cagado` | `ab8cd2c7-926a-48c6-948c-0947c738d399` | `5c89dea9-c8f0-483d-9afb-515beb90af16` |

NFKC/ROOT normalized uniqueness currently applies across statuses, so rejected records also conflict. No source was renamed/deleted/merged. The [historical audit](production-migration-audit.md) records the earlier containment failures, not desired geometry behavior. The six affected templates require re-audit with this new artifact; do not declare them resolved before that check. WebP encoding and duplicate-name resolution remain separate decisions. Original production storage and verified recovery copies remain unchanged; v1 is stopped and no replacement was activated by this source change.