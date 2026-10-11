# Phase 4 — SQLite implementation and offline rehearsal

Baseline: clean `refactor` at `aab05e2`; Java **21.0.2**, Gradle **8.6**, Boot **3.2.3**. This is an opt-in candidate implementation and synthetic offline rehearsal, **not a production cutover**. Single-host local-disk operation is an explicit prerequisite, not a verified description of the deployed host. No user dataset, environment file, live application, Telegram, ImageMagick, push or deployment was used.

## Local sequential slices

| Commit | Scope |
| --- | --- |
| `fb9c262` | Patched JDBC, versioned schema, guarded pooled connections |
| `8e47666` | Four JDBC repositories, shared store contracts and contention |
| `e90d674` | Explicit engine selection, metadata transactions and service paging |
| `29be9f9` | Offline audited import and post-write JSON/media rollback export |
| `4737a79` | Reversible media deletion staging and uncertain-artifact retention |

Each slice passed focused tests, clean build, the five Node security tests and whitespace checks before local commit. The final acceptance/documentation slice adds checked-failure rollback, SQLite submission→preview→confirmation→HTTP moderation, property validation and a stronger typed foreign-key failure assertion. Its hash is reported after committing rather than embedded in its own contents.

## Dependencies and actual engine

- Xerial `org.xerial:sqlite-jdbc:3.53.4.0`, resolved from Maven Central. Tests execute `SELECT sqlite_version()` and assert **3.53.4**, not a version inferred solely from the artifact name.
- Liquibase **4.33.0** core contains `SQLiteDatabase`; no speculative Flyway SQLite module or ORM dialect is used. Tests apply both formatted-SQL change sets, reopen without rerunning them, and upgrade a file containing only change set 1 while preserving rows. Its changelog/checksums are managed by Liquibase, not a custom migration framework.
- Xerial is Apache-2.0 with retained upstream BSD notices; Liquibase 4.33.0 is Apache-2.0. SQLite is public domain. JDBC starter uses Boot-managed Spring/Hikari versions. No major Boot or general maintenance upgrade occurred.
- Official SQLite documentation identifies the WAL-reset corruption fix in **3.51.3 and later** (backports also exist). This implementation fails closed below 3.51.3 and rejects withdrawn 3.52.0; no arbitrary 2024 driver is accepted. A custom overridden native library must pass the runtime version check too.
- Connection factory uses Xerial properties on **every physical connection**: foreign keys ON, bounded busy timeout (default 2,000 ms; 1–10,000), synchronous FULL, WAL, IMMEDIATE write transactions. Hikari has at most two connections, minimum idle zero and a five-second borrow bound. Tests simultaneously borrow both and verify pragmas; a held writer yields typed UNAVAILABLE/HTTP 503 and recovers after rollback. Offline export instead opens driver and pool read-only, with DEFERRED transactions and no migrations.

Sources consulted and verified through retrieval:

- [SQLite release history](https://sqlite.org/changes.html), [WAL-reset explanation and same-host restriction](https://sqlite.org/wal.html#walresetbug).
- [Maven Central Xerial metadata](https://repo.maven.apache.org/maven2/org/xerial/sqlite-jdbc/maven-metadata.xml), [maintained Xerial driver](https://github.com/xerial/sqlite-jdbc), [connection usage](https://github.com/xerial/sqlite-jdbc/blob/master/USAGE.md).
- [Pinned Liquibase SQLite implementation](https://raw.githubusercontent.com/liquibase/liquibase/v4.33.0/liquibase-standard/src/main/java/liquibase/database/core/SQLiteDatabase.java), [published 4.33.0 POM/license](https://repo.maven.apache.org/maven2/org/liquibase/liquibase-core/4.33.0/liquibase-core-4.33.0.pom). Attempted moved Liquibase integration-guide URLs returned 404; support is established by the pinned implementation and actual file-backed migration tests, not those failed URLs.

## Explicit configuration and compatibility

`regata-simulator.database.engine` defaults to **jsondb**. Existing installations and test profiles remain JsonDB; nothing converts `REGATA_SIMULATOR_DB_PATH`, whose value still means a **directory**. For a deliberately isolated SQLite candidate set:

```properties
regata-simulator.database.engine=sqlite
regata-simulator.database.sqlite-file=/absolute/isolated/candidate.db
regata-simulator.database.busy-timeout-millis=2000
```

The parent must already exist. Runtime opt-in initializes/version-migrates the explicitly selected SQLite file; it never imports JSON or media on startup. Stage/prod settings were not switched. Existing application environment placeholders still apply; this task did not read/create/change dotenv files. The standalone offline CLI does not load Spring, profiles, dotenv, bot registration or schedules.

JsonDB beans/adapters are conditional; SQLite mode has no JsonDB bean or constructor. Boot's generic DataSource/Liquibase auto-configuration is excluded: only the explicit SQLite configuration creates its pool. Existing repository interfaces remain, with compatible default paging; SQLite implements SQL LIMIT/OFFSET and count in one database transaction. Dates use zero when origin date is absent, with UUID descending tie-breaks. `instr` on a Java-normalized key treats quotes, `%`, `_` and SQL-looking text literally without LIKE escaping hazards. HTTP paths, DTOs, Portuguese messages and callback format stay compatible. Direct service pagination now rejects zero sizes, matching existing HTTP bounds; the old characterization expectation was updated intentionally.

## Schema and product policies

- `sources`, `templates`, `users`, `memes`, `meme_sources`; UUIDs are preserved, weights must be positive and statuses exactly REVIEW/APPROVED/REJECTED. Authors and chat identities are 64-bit integers. Full nullable Telegram Message JSON is preserved with explicit nullable author/chat/date query columns; no trusted ownership is backfilled. Nullable preview bindings are a paired positive-message identity.
- Source description keys use **strip + NFKC + Locale.ROOT lowercase** shared by upload/import/filtering. The SQLite UNIQUE constraint, not just a precheck, arbitrates concurrent duplicates as ApplicationFailure.CONFLICT. Null legacy descriptions remain nullable and may occur more than once; empty or duplicate normalized names block migration. No record is dropped or renamed to resolve a conflict. This is NFKC/ROOT normalization, not full linguistic Unicode case folding.
- Geometry remains ordered JSON rather than an unnecessary corner-table ORM. Every repository insertion and offline import validates 1–128 ordered contiguous area indices, contiguous positive slots with reuse allowed, convex corners, numeric limits and decoded-media bounds. Invalid legacy geometry is reported/rejected, never normalized into guessed coordinates.
- History has immutable `template_uuid` and positional `source_uuid` values plus nullable foreign-key links with ON DELETE SET NULL. Deleting a current source/template removes it from selection/findById while retaining original history identities. Legacy orphans and nullable/duplicate ordered source IDs are preserved and reported. The position primary key allows repeated source UUIDs. Deleting a history row cascades only its positions. No strict import FK silently discards an orphan or rejects all legacy history.
- New delivery inserts history before trimming to 1,000 **in one transaction**. Offline import retains every snapshot history row, even if above the runtime cap; the next delivered publication applies normal retention. Source/template decrements and delivery history are one database-only transaction after send. Failure rolls back all metadata but cannot unsend the photo. Author/entity insertion is similarly atomic in SQLite; JsonDB's transitional unit of work remains nontransactional. Checked failures are wrapped before leaving the transactional boundary and tested to roll back too.
- REVIEW decisions/bindings are conditional SQL field updates; affected-row results drive existing typed conflicts and preserve current owner/origin/weight. Callback replay/confirmation and notification-failure behavior are tested against SQLite. Confirmation is not approval. Notifications remain outside transactions; there is no durable retry/outbox guarantee.
- Deletion first atomically moves the item directory to `.delete-<item UUID>-<operation UUID>` beside its original path, removes metadata, then purges the stage. Confirmed metadata failure restores the original directory. An unavailable existence check, restoration failure or post-commit purge failure retains recoverable staged bytes. Tests replace the old deletion-loss characterization with restore/retain assertions. Staging is not a filesystem/database transaction or crash-proof automatic recovery.

## Offline import procedure (copies only)

1. Verify single-host/local-disk suitability and deliberately approve the policies above. Stop **all writers** for a coherent snapshot: bot submissions/callbacks, HTTP imports/admin mutations and schedules. A scheduler switch alone is insufficient. This session did not stop or access any actual deployment.
2. Copy all four JSON collections **and both media roots** together into new isolated directories under the write freeze. Preserve the originals and their permissions. Canonicalize paths first (macOS `/var` aliases `/private/var`); symlinked path components/entries are rejected. Do not use a live data directory as the rehearsal input.
3. Run from the repository using Java 21 and explicit, new paths. Example placeholders must be replaced with isolated copies, never the user `regsim` workspace:

```sh
./gradlew offlineStore --args='import --ack-write-freeze-and-isolated-copy true --source-path /isolated/snapshot/jsondb --sources-path /isolated/snapshot/sources --templates-path /isolated/snapshot/templates --target-file /isolated/candidate.db --report-file /isolated/import-report.json'
```

4. Require exit zero and `IMPORTED_VERIFIED`. The reader consumes raw schema-header/NDJSON without constructing JsonDB or modifying lock/source files. It audits every parseable record and reports malformed rows, duplicate/invalid IDs, normalized-name conflicts, weights/statuses/bindings, geometry/media bounds, missing media, incomplete/null origins and orphan history. Blocking anomalies leave **no target database**. Warnings preserve data. Reports contain safe codes/row/ID, counts and SHA-256 manifests, not Message payloads or exception dumps.
5. Import reserves a new file atomically, applies Liquibase schema, then imports all metadata in one transaction. It compares canonical complete fields/order/counts, runs integrity_check/foreign_key_check and rechecks input hashes before commit. Failed candidates are retained with a failure outcome for inspection; **do not reuse them**. A crash/report-write failure is not success: independently verify, keep the input snapshot and use a new target for another attempt. Existing outputs and overlapping input/output paths are refused. CLI acknowledgments document operator responsibility; they cannot prove every writer was actually stopped.
6. Use the original isolated media copy with the candidate database. Preserve/review the manifest and test selection/preview/moderation with fake boundaries. The isolated Spring test validates real SQLite wiring with bot/registrar/render/Telegram mocked. There was no live cutover or rendered ImageMagick smoke test.

## Rollback and restore rehearsal

**Before any SQLite writes:** retain the coherent JSON/media snapshot and restore the JsonDB engine/configuration only during another full write freeze, after verifying its manifest. Do not overwrite the retained snapshot.

**After SQLite writes:** never point an old JAR at stale JSON and call that rollback. Freeze every writer again, obtain an isolated SQLite/media candidate containing those new writes, and export it into a **new** bundle:

```sh
./gradlew offlineStore --args='export --ack-write-freeze-and-isolated-copy true --source-path /isolated/candidate.db --sources-path /isolated/candidate-media/sources --templates-path /isolated/candidate-media/templates --target-path /isolated/rollback-bundle --report-file /isolated/export-report.json'
```

Require exit zero and `EXPORTED_VERIFIED`. Export opens SQLite read-only (no schema changes), reads a consistent database transaction, copies both media trees, writes schema-1.0 JSON files, compares full fields and media hashes, and reports anomalies. The write freeze is essential for consistency between metadata and media. Use a coherent stopped candidate, including committed WAL state—not a bare live `.db` copied without its WAL. Automatic live SQLite snapshots are Phase 5, not implemented here.

Tests import synthetic JsonDB with nullable origins, omitted old bindings, Unicode names, 64-bit origins, valid ImageIO geometry/media and orphan/repeated history; make new SQLite records and moderation/weight writes; export/reopen with **actual JsonDB 1.0.115-j11** and compare fields and all media bytes. Input JSON/media hashes remain unchanged, and read-only export leaves the closed SQLite file hash unchanged. This establishes compatibility with the retained current JsonDB code/schema, **not every older released binary**. Independently reopen/test the rollback bundle with isolated adapters before changing any deployment config; retain both stores until acceptance. Partial export directories are retained, never selected as a successful rollback.

## Verification and boundaries

Final clean build on Java 21.0.2 / Gradle 8.6: independently summed XML **725 tests / 32 suites**, **0 failures / errors / skips**. Separate Node tests **5/5**, whitespace check clean. All new database tests use real file-backed SQLite, not H2. Existing JsonDB/service/security regressions remain active. Editor diagnostics were checked and actionable offline-audit findings corrected. Dedicated Sonar invocation could not be loaded because this session has no tool-discovery interface for its deferred tool; no project/server quality-gate result is claimed.

Runtime JsonDB remains the compatibility default; it is not made read-only because that would break existing installations before an explicit cutover. Offline import reads JSON without JsonDB. Both implementations remain covered by shared contracts during transition.

Database transactions do not cover filesystem/Telegram. Existing upload/import compensation and uncertain-failure reconciliation remain. For a retained `.delete-*` stage, freeze writers and work from coherent copies: inspect whether the corresponding source/template metadata still exists, verify bytes/checksums, restore to its original UUID directory only when metadata remains and the destination is absent; otherwise retain/archive or deliberately purge only after confirming committed deletion. Never overwrite a concurrently recreated directory or infer committed deletion from an unavailable database. A process crash between staging and metadata change still requires this operator reconciliation.

Callback locks remain process-local and cannot make forwarding/binding consumption atomic across failures. There is no durable notification/job replay, live snapshot, automatic restore, multi-host support or production readiness claim. Legacy Telegram backup is explicitly **blocked in SQLite mode** (UNAVAILABLE) rather than archiving stale JSON or an inconsistent live WAL file. Phase 5 consistent snapshots/recovery/dependency/deployment maintenance is next; no Phase 5 implementation was performed.