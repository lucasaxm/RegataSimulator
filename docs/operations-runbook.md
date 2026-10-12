# Data safety, backup and recovery

Preserve metadata **and images together**. Never test migration/cleanup against original production paths. Freeze writers, preserve coherent copies/hash manifests and an independent recovery copy before selecting new paths. Schedules-off alone is not a freeze: HTTP, Telegram callbacks/uploads, manual jobs, other JVMs/filesystem writers still mutate.

## Backup and retention

`RecoverySnapshotService` takes a cooperative **single-JVM** exclusive media lease, captures metadata/copied media and validates staging, then packages/delivers outside that lease. Normal services cooperate; external writers/direct repository maintenance do not.

- SQLite: autocommit **VACUUM INTO** to a new file, including committed WAL. Never replace with a plain copy of live DB or live DB/WAL/SHM trio.
- JsonDB: five schema-1.0 NDJSON collections (`sources`, `templates`, `users`, `memes`, `audits`), not a live lock directory.
- Preserve orphans/uncertain deletion stages; referenced inconsistencies block capture. No auto-repair/purge.
- Backup root: existing/canonical/absolute/non-symlink, mode 700, nonoverlapping with media/JsonDB/SQLite. Count retention defaults to 7, range 1..100; not age/space-based.
- Validate every candidate's completion/manifest/parts **before deleting any**. Corrupt/incomplete holds retention; unrelated directories remain. Validation isn't a functional restore drill.
- Complete local bundles survive remote delivery/report failure. Partial Telegram attachments are not recoverable until all listed parts validate. No durable retry/exactly-once delivery.

### Bundle contract

`backup-<UUID>` contains ZIP parts, format 1 `manifest.json` and **COMPLETE**, written last with manifest SHA-256. Manifest includes engine/schema, relative-file/archive hashes, counts/anomalies. Hashes are integrity, not signatures/encryption/fsync guarantees. POSIX directories/files are private 700/600.

| Limit | Contract |
| --- | --- |
| Indivisible item / encoded archive | 40 MiB; DB file or whole media UUID directory isn't split |
| Payload / restored expansion | 2 GiB |
| Files / extracted entries | 100,000; restore also counts directories |
| Manifest / parts | 8 MiB / 10,000 |

Restore rejects unsupported/tampered/missing/unexpected/duplicate parts, unsafe paths, symlinks/special/encrypted/multidisk/ZIP64 entries and excessive sizes. Existing targets are never overwritten. Failed packaging cleans owned candidates; failed restore retains an unverified target. Retention/capture errors can still leave completed artifacts: inspect actual state.

## Offline command conventions

`offlineStore`, `restoreBundle`, `inspectRecoveryStages` run standalone mains, **not Spring/bots/schedules**, and don't load dotenv. Run from repo root with compatible Java. Set the variables below to actual **canonical absolute isolated paths**, with no spaces for these examples. Acknowledgments do not stop production for you.

Use coherent **stopped copies**, new nonoverlapping targets/reports with existing private parents. Preserve manifests/anomalies/failed candidates; choose new names for retry. Unsupported options/paths/validation fail closed.

### Restore

`./gradlew restoreBundle --args="--ack-all-writers-stopped true restore ${RECOVERY_BUNDLE_ABS} ${RESTORE_TARGET_ABS}"`

1. Freeze all writers; preserve the complete bundle; select a nonexistent fresh target, never a live configured root.
2. Exact five arguments, exit 0 on success / 2 on refusal. Output: `jsondb/` or `db/store.db`, plus `sources/` and `templates/`.
3. Require **RESTORED_VERIFIED** with matching manifest hash. Counts/hashes/decoded media/geometry/schema/integrity/FK checks must pass. No marker means no permission to resume.
4. Review anomalies and rehearse real rendering under intended environment/sandbox. Files don't provision Java/secrets/identity/service/proxy/TLS.
5. Select validated paths only with operator approval; retain baseline/failed candidates.

Writable SQLite schema is 3; read-only offline accepts validated schemas 2/3 without migrating, schema 2 with empty audits. Unknown/tampered schemas fail. Read-only WAL readers can touch sidecars: inspect private stopped clones, not original/live files.

### JsonDB → SQLite

`./gradlew offlineStore --args="import --ack-write-freeze-and-isolated-copy true --source-path ${JSONDB_COPY_ABS} --sources-path ${SOURCES_COPY_ABS} --templates-path ${TEMPLATES_COPY_ABS} --target-file ${NEW_SQLITE_ABS} --report-file ${NEW_REPORT_ABS}"`

- Audit all records/media first. Preserve UUIDs/status/weights/nullable origins/bindings/history/geometry/users/audits; require canonical-field reconciliation, integrity/FK checks and unchanged inputs.
- Require **IMPORTED_VERIFIED**. **AUDIT_FAILED** gives no approved candidate; **IMPORT_FAILED_CANDIDATE_RETAINED** isn't success. Never silently skip records.
- Normalized uniqueness includes REJECTED items. Resolve conflicts only with explicit policy on a new copy; retain changed/removed records/media. Nullable origins and valid orphan history aren't deletion criteria.
- Detect true image formats. Convert mislabeled encodings only on copies, preserve originals and verify decoded pixels/dimensions. Keep intentional positively-overlapping off-canvas geometry, not blind clamping.
- Rehearse candidate startup/render/backup/restore in isolation with registration/schedules disabled and external effects mocked. Cutover selects explicit engine/path; startup does not import JSON.

### SQLite → JsonDB post-write rollback

`./gradlew offlineStore --args="export --ack-write-freeze-and-isolated-copy true --source-path ${SQLITE_COPY_ABS} --sources-path ${SOURCES_COPY_ABS} --templates-path ${TEMPLATES_COPY_ABS} --target-path ${NEW_JSON_BUNDLE_ABS} --report-file ${NEW_REPORT_ABS}"`

Freeze writers; export coherent copied SQLite/media to new `jsondb/`, `sources/`, `templates/`, preserving **new SQLite writes**, history/audits/media. Require **EXPORTED_VERIFIED** and independent reconciliation; **EXPORT_FAILED_CANDIDATE_RETAINED** isn't fallback. Rehearse a known new write and select a compatible binary deliberately.

**Never restart an older binary against stale pre-migration JSON after new SQLite writes.** Artifact/data rollback differ; equal schema markers do not prove old-binary semantic compatibility. Even pre-write recovery needs a complete freeze/validated snapshot, not an implied data-loss waiver.

## Retained deletion stages

Media deletion stages `.delete-<canonical item UUID>-<canonical operation UUID>` beside original, removes metadata then purges. Confirmed metadata failure restores; uncertain DB/restore/purge retains bytes. Compensation isn't crash-proof atomicity.

`./gradlew inspectRecoveryStages --args="--ack-stopped-isolated-copy true ${ENGINE} ${DATABASE_COPY_ABS} ${SOURCES_COPY_ABS} ${TEMPLATES_COPY_ABS} ${NEW_REPORT_ABS}"`

Exactly seven arguments; `ENGINE=jsondb` with directory, `sqlite` with file. Inputs mutually nonoverlapping/report outside them. Exit 0 means **INSPECTED_ONLY**, not repaired. SQLite reader clones stopped DB/sidecars before opening.

| Recommendation | Operator action |
| --- | --- |
| `RETAIN_DESTINATION_CONFLICT` | Original exists: keep both; compare, never overwrite |
| `RESTORE_CANDIDATE_OPERATOR_ACK_REQUIRED` | Metadata exists/original absent: validate before acknowledged restore |
| `RETAIN_COMMITTED_DELETION_OPERATOR_REVIEW` | Neither exists: possible deletion, not permission to purge |

Only missing media tied to a recognized same-item stage is exempted. Unreadable metadata, bad schema/records/geometry, unstaged missing media, bad stage names/symlinks still block; unavailable metadata cannot imply committed deletion. No automatic rename/restore/purge/repair/restart.

## Recovery storage hygiene

Keep complete copies outside the VPS failure domain with restrictive access/capacity for retained archives, scratch and fresh candidates. Bundles/legacy runtime snapshots can contain sensitive metadata or credentials: never commit or publicly upload them.

Legacy archives are under production `~/.local/share/regata-v1-recovery`; off-host copies under the operator's `~/regsim-recovery`. These are **manual snapshots**, not automatically `restoreBundle` inputs. Identify them using private manifests/checksums. Repository documentation cleanup never authorizes deleting them. Managed count retention applies only to the configured backup root, not legacy/off-host copies.

If failure follows a side effect, preserve artifacts/audits/stages and inspect applied state before retry. Notification failure doesn't undo moderation; Telegram/metadata/files aren't atomically committed. Durable retries, exactly-once delivery and whole-host restoration aren't provided.

See [deployment](deployment-setup.md) for writer/service control and [development](project-map.md) for contracts.