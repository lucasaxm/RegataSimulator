# Morning deployment review — 2026-10-11

## Decision: hold approval until remaining checks are verified

Overnight preparation is authorized; **main merge, production registration, scheduled publishing and release deployment are not**. PR **#24** is draft on `refactor`. Do not mark it ready or approve a production run solely because the branch is mergeable. This handoff distinguishes verified preparation from still-running checks.

## Completed preparation

- V1 restart loop and JVM stopped with user authorization. Original `/home/ubuntu/bots/RegataSimulator/{jsondb,sources,templates}` remains unchanged. Verified complete recovery archive is retained on the VPS and locally; exact paths/hash are in [preservation checkpoint](production-preservation-checkpoint.md).
- Exactly three REJECTED duplicate sources removed from new working copies; all APPROVED counterparts and removed records/media retained. Source count is 1,023, templates 349, history 1,000, users 7. No other rejected items were removed.
- Two mislabeled WebP templates converted to PNG only in `/home/ubuntu/.local/share/regata-data/templates`. ImageMagick comparison verified **zero decoded pixel differences** and identical dimensions. Original WebP files remain in the immutable recovery baseline, working baseline and private preparation recovery folder. All six off-canvas template geometries remain unchanged.
- Updated artifact `7027bb8ee541deaa660a2e7b846c10e5e09117503625ccdf11bd2f7eaf821f9d` passed full offline SQLite import: **IMPORTED_VERIFIED**, 1,023 sources / 349 templates / 1,000 memes / 7 users / zero audits, zero blocking issues. Import report is private under `/home/ubuntu/.local/share/regata-prepare-20261011`.
- Candidate SQLite `/home/ubuntu/.local/share/regata-data/store.db` copied off-host to `/Users/lucas.xavier/regsim-recovery/2026-10-10/migrated-candidate-20261011.db`; stopped database hashes matched: **`190e92ff41cca824ddca3da52041afc558fa2ee56624a466d2e7ca85619e154f`**. No runtime writes have been enabled on this candidate.
- Real Linux isolated runtime count/integrity checks passed; **all eight formerly blocked templates rendered successfully** with real ImageMagick and unchanged intentional coordinates. Full backup/restore and final service-memory outcome are not yet claimed here.
- Patched JDK checksum-verified at `/home/ubuntu/.local/share/regata-tools/jdk-21.0.12.1+1`; other apps' Java selection unchanged.
- `/home/ubuntu/.config/regata/runtime.env` privately provisioned (600), using preserved production encryption key in memory. Spring `stage`, explicit SQLite candidate, candidate media paths, backup root, loopback:8080 and schedules **false**. No credentials committed or printed.
- User service installed and systemd validation passed. Host-specific executable uses private JDK, **-Xms64m -Xmx256m / MemoryMax448M / MemorySwapMax64M**; `ubuntu` lingering enabled. Service remains **inactive and disabled**, with **no `current` release selected**. Other bots and nginx were not restarted/changed.
- Existing nginx route for `regsim.boatarde.dev` points to localhost:8080. Certificate covers `regsim.boatarde.dev` and expires November 26, 2026. The observed `.dev.br` alias is **not covered by that certificate**; do not call that alias HTTPS-verified.
- GitHub `stage` variables saved/reloaded: `SERVER_HOST=bots2.ssh.boatarde.dev`, `SERVER_USERNAME=ubuntu`, `SERVER_ROOT=/home/ubuntu/.local/share/regata`, matched pinned `SERVER_KNOWN_HOSTS`, `SERVER_JAVA_BIN` pointing to the private JDK. Existing eight secrets retained. GitHub secret key **presence** is known; actual Actions SSH authentication with that secret remains a deployment gate and was not proven by local SSH credentials.
- GitHub `stage` requires reviewer **lucasaxm**, permits self-review for the solo owner, and disables administrator bypass. A merge starts release CI but deployment waits for explicit approval. No approval was given overnight.
- Local full build: **845 JUnit / 42 suites**, zero failures/errors/skips. Final separate Node suite **19 passed**, zero failures/cancellations/skips. Fresh OSV **COMPLETE / 138 coordinates / zero findings**, `2026-10-11T04:47:46.413Z`; Maven scope only.
- Actual PR CI caught an invalid setup-java four-component SemVer input before compilation. Both workflows now download the exact official Linux JDK, validate SHA256 and use documented `jdkfile` installation with valid cache identifier `21.0.12+101`. Fix pushed as `d2a271f`; a successful new remote CI run is still required, never inferred from local contract tests.

## Pending evidence — do not approve deployment yet

1. Final outcome of `regata-ready-check-20261011`: full real-dataset coordinated SQLite backup, fresh restore, final cgroup peak/swap/OOM metrics and clean process exit. Earlier neutral Linux capacity passed but does not substitute for this dataset.
2. `regata-followup-20261011` waits for that success before running synthetic-new-write SQLite-to-JsonDB rollback export and hardened stage binding/login with real private configuration but **registration disabled/no external requests**. Failures or missing final markers remain blockers.
3. Repaired PR CI must finish green for the latest branch. PR stays draft; no auto-merge enabled.
4. Confirm transient test units have finished; production port8080/service remain inactive, and original/candidate hashes are unchanged. Retain complete migration/recovery evidence off-host.

## Tomorrow's controlled first rollout

After pending checks pass and this document is updated: inspect PR #24/latest green checks, mark ready and merge only when intentionally proceeding. Release CI builds a new artifact and waits for your **stage approval**. Approve only after checking host prepared state and candidate identity. First deploy has no managed previous release: failure stops writers and removes `current`; it does not automatically restart legacy v1.

Keep schedules disabled during first startup. Verify unit/local readiness, real production bot identity/replies, HTTPS configured login/CSRF and one intentional private render/backup before enabling schedules. Anonymous readiness is not render/storage/Telegram readiness. Review any cgroup limit hit before increasing limits on the shared 1 GiB host. Enable service boot persistence and schedules only as explicit post-smoke steps; changing runtime.env requires a deliberate restart.

### Recovery policy

Never overwrite original media/metadata or recovery bundles. Before any new production writes, legacy recovery can restore the preserved pre-upgrade state only under a complete writer freeze and explicit identity/path review. After new SQLite writes, **do not point v1 at stale original JSON**: stop all writers and export a stopped coherent SQLite/media copy into new JsonDB/media output, validate counts/hashes and decide compatible-binary recovery explicitly. The synthetic rollback rehearsal must prove preservation of a new write; it does not certify all v1 semantic compatibility. Artifact rollback is binary selection, not data/schema undo.

See [deployment setup](deployment-setup.md), [production migration audit](production-migration-audit.md), [off-canvas compatibility](off-canvas-template-compatibility.md), and [operations runbook](phase-5-operations-results.md) for exact contracts. Private helper sources/logs under preparation roots are evidence, not committed production launchers.