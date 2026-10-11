# Rejected duplicate removal — 2026-10-11

The user authorized deleting the rejected members of the three identified duplicate source-name pairs. This was applied **only to a new migration working copy**, not the original v1 storage or verified recovery baseline. It is not a blanket deletion of all rejected sources.

## Verified outcome

- Working copy: `/home/ubuntu/.local/share/regata-migration-working-20261011`.
- Removed REJECTED records: `VALBER` (`f70cb450-60e8-4726-b8fd-962262937491`), `lucas` (`e475a170-00ef-4198-b888-9671853001c9`), and `gabriel cagado` (`5c89dea9-c8f0-483d-9afb-515beb90af16`).
- Corresponding APPROVED records retained unchanged: `valber` (`dc90d15a-e1f1-4aaf-9007-ee2f03670027`), `Lucas` (`01afd052-8f6c-4789-a723-94b81e9ab9bf`), and `gabriel cagado` (`ab8cd2c7-926a-48c6-948c-0947c738d399`).
- Source count **1,026 → 1,023**. Normalized descriptions in the remaining collection have no duplicate groups. Names on retained records were not changed.
- Removed original NDJSON records and the three copied media files retained under `removed-rejected-duplicates/`, outside the active working `sources/` tree. Baseline/original recovery copies still preserve all three records/media as well.
- Full baseline manifest/file hashes verified before copying and again afterward. Working media hashes matched expected retained/removed partitions. Templates and all other database collection files remained byte-for-byte unchanged, including meme history. Historical references were not rewritten or erased; SQLite's nullable-link history contract supports current-item deletion.
- Private `duplicate-removal-manifest.json` records removed/retained UUIDs, source collection before/after hashes and removed-media hashes. A copy was retained and independently checked at `/Users/lucas.xavier/regsim-recovery/2026-10-10/duplicate-removal-manifest-20261011.json`.

V1 remains stopped; no SQLite import, production activation, merge or push was performed by this deletion. The two WebP-under-JPG assets remain unchanged, and the new [off-canvas overlap validation](off-canvas-template-compatibility.md) still requires a fresh production-copy audit with the updated artifact. Use this working copy for subsequent explicitly scoped migration work, never overwrite the [verified v1 recovery baseline](production-preservation-checkpoint.md).