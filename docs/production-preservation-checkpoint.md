# Production preservation checkpoint — 2026-10-10

The user explicitly authorized stopping Regata v1 on `bots2.ssh.boatarde.dev`; downtime is acceptable, preservation of sources/templates is mandatory. This checkpoint is **not a completed deployment or SQLite migration**.

## Preserved before replacement

- Identified exact v1 JVM `3750345` and restart-loop launcher `3750343`. The launcher was terminated before sending SIGTERM to the JVM; Linux pidfd readiness confirmed both exits. No Regata cron references or installed user services were found in the inspected user configuration. Unrelated services were not stopped.
- Original database and media remain untouched under `/home/ubuntu/bots/RegataSimulator/{jsondb,sources,templates}`. The old JAR and launcher remain in their original locations as well as the backup.
- Private coherent copied snapshot: `/home/ubuntu/.local/share/regata-v1-recovery/pre-upgrade-0tk4ins2`.
- Copied and SHA-256-compared **all files**, including unreferenced media and JsonDB lock-related files: **8 database files, 1,026 source files, 1,005 template files**. Three legacy recovery files preserve the old JAR, launcher and runtime settings. Private runtime recovery contains secrets; never print, commit or publicly upload it.
- Recovery archive: `pre-upgrade-0tk4ins2.tar.gz`, **196,905,635 bytes**, SHA-256 **`b0ab67a076b9a190f5cd0371ff7887009d311a4551c27e9eec291729035ac8d6`**.
- Independent off-host copy: `/Users/lucas.xavier/regsim-recovery/2026-10-10/pre-upgrade-0tk4ins2.tar.gz`. Private directories are mode 700 and archive mode 600.
- Off-host verification checked archive SHA-256, unique/regular archive members, completion-marker/manifest hash and **every archived metadata/media/legacy file hash**. All passed. This preserves bytes, not proof that every legacy metadata/media relationship is valid; audit coherent copies before migration. No candidate ran against original production storage.

## Capacity observations

- Before stopping v1: approximately 206 MiB MemAvailable; v1 approximately 143 MiB resident plus 161 MiB swapped. Full swap did not show active swap-in/out in the short sample.
- After stopping: **359 MiB MemAvailable**, **167 MiB swap free**. These are snapshots, not reserved resources or peak-load guarantees.
- Host ImageMagick **7.1.1-38** is executable at `/home/ubuntu/tools/bin/magick`; absence on a noninteractive PATH was not absence of the tool. Systemd user manager/cgroup v2 are available; `/usr/bin/java` is missing, so the checked-in service cannot yet start as provisioned.
- Corrected local synthetic-credential probe used the unchanged final JAR with patched Temurin 21.0.12.1+1, `-Xms64m -Xmx256m`, registration/schedules disabled and copied neutral JsonDB fixtures. It passed startup, five real native renders, scratch cleanup, backup capture and fresh verified restore. Used heap during the five renders: 39–43 MiB.
- Local macOS `/usr/bin/time -l` reported peak JVM RSS **301,047,808 bytes (~287 MiB)**. This is **not** a measured aggregate Linux cgroup limit for JVM/native children/page cache, nor a representative production dataset benchmark. The 256 MiB heap does not imply a 256 MiB total process footprint.
- An earlier synthetic probe failed with `EncryptedPropertyFailure` because it omitted the `telegram.bots.bilu-tags.token` override; it was a test configuration failure, not observed OOM. The corrected probe used synthetic overrides for all required credentials and made no Telegram requests.

## Current state and next gate

V1 remains intentionally stopped. No merge, new production JVM, migration, deletion of originals, proxy change or unrelated-service change occurred in this checkpoint. Backup/helper copies are retained privately.

Before production startup: provision patched Linux Java without breaking other apps' Java selection; validate reduced-memory startup/render/backup on isolated storage under the intended Linux service constraints; audit the coherent production copy and explicitly plan any SQLite import/rollback; provision private runtime environment/unit/GitHub deployment settings. Only then select the candidate's intended storage and enable the production bot, with schedules initially disabled. Do not infer host fit from the local RSS alone or point an older binary at stale JSON after new SQLite writes.

See [deployment setup](deployment-setup.md), [operations runbook](phase-5-operations-results.md), and [local live verification](boot-4-live-dev-results.md).