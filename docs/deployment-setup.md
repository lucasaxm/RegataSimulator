# Deployment and production operations

**GitHub environment `stage` is production**, not a separate staging server. Production is `ubuntu@bots2.ssh.boatarde.dev`, a shared OCI free-tier host, with SQLite and nginx/HTTPS at `regsim.boatarde.dev`. Local dev is the test environment. GitHub environment selection does not automatically select a Spring profile.

## GitHub Actions and settings

PRs to `main` run clean Java tests/build, wrapper validation, Node fixtures and fail-closed scanning. Main pushes or approved manual dispatch build once and deploy that artifact; no VPS rebuild. Releases use full commit SHA and verified checksum; environment concurrency serializes deployment. Review latest-head CI and don't bypass findings/incomplete scans. See [maintenance](maintenance.md) for JDK/scan policy.

Configure in **stage**:

| Kind | Name | Purpose |
| --- | --- | --- |
| Secret | `SERVER_SSH_KEY` | Private key authorized for the non-root deploy account |
| Variable | `SERVER_HOST` | `bots2.ssh.boatarde.dev`; hostname/IP only |
| Variable | `SERVER_USERNAME` | `ubuntu` |
| Variable | `SERVER_ROOT` | `/home/ubuntu/.local/share/regata`; canonical absolute path |
| Variable | `SERVER_KNOWN_HOSTS` | Complete trusted OpenSSH host-key lines; independently verify rotations |
| Variable | `SERVER_JAVA_BIN` | `/home/ubuntu/.local/share/regata-tools/jdk-21.0.12.1+1/bin/java`; matches unit ExecStart |

Unset Java defaults to `/usr/bin/java`. Workflow assumes SSH port 22; another port needs reviewed ssh/scp/trust changes. Host/user secrets alone do not satisfy `vars.*`. Application secrets are private VPS configuration, not transmitted by Actions; OSV needs no API key.

`stage` requires reviewer `lucasaxm`, permits self-review for the solo owner and disables administrator bypass. Merge and deployment approval are separate decisions; don't approve on the user's behalf without authorization.

## VPS layout and supervision

| Path (deployment user's home) | Purpose |
| --- | --- |
| `~/.local/share/regata/{incoming,releases,current}` | Transfer, immutable JARs, selected-release symlink |
| `~/.config/regata/runtime.env` | Private runtime settings, mode 600 |
| `~/.config/systemd/user/regatasimulator.service` | User service |
| `~/.local/share/regata-data/{store.db,sources,templates}` | Explicit SQLite and UUID media trees |
| `~/.local/share/regata-backups` | Existing private/nonoverlapping backup root, mode 700 |
| `~/.local/share/regata-tools` | Regata-specific JDK/native tools, not other bots' installations |

Use canonical non-symlink paths/private directories/deployment-user ownership. Provide Bash, SSH, curl, GNU coreutils (`timeout`, `mv -Tf`), shasum and a working user manager. Enable lingering **and** the unit for reboot persistence; active-but-disabled won't return automatically. Don't replace global Java or restart unrelated bots.

The checked-in unit is a provisioning baseline, not an installer. Installed `bots2` settings use private Java, **-Xms64m -Xmx256m**, **MemoryMax448M / MemorySwapMax64M**, cgroup shutdown/private temp/strict filesystem/no-new-privileges restrictions. Ceilings cover JVM/native children/cache, not reservations. Keep writable data/backups in `ReadWritePaths`. Runtime changes need deliberate restart; unit edits need daemon-reload. Preserve old configuration privately first.

The service template adds **`-Djava.net.preferIPv4Stack=true`** for Regata's JVM, avoiding unsupported IPv6 routes on the current IPv4-only host. This belongs in the actual unit's ExecStart (or private per-service JAVA_TOOL_OPTIONS), **not** the artifact deployment script or host-wide network policy. Actions does not install/update the unit: apply the flag explicitly to existing host-specific units, preserving their private Java/heap/sandbox settings, then daemon-reload/restart with authorization. A committed template change alone does not alter the live JVM. Check DNS/IP-family routes and connectivity before attributing every network failure to IPv6.

### Private runtime environment

Use systemd `NAME=value`; no shell export/substitution or `$HOME` expansion. Never put secrets in chat/Git/logs/action output.

- `SPRING_PROFILES_ACTIVE=stage`: existing production identity/destinations. Don't switch to Spring `prod` just to rename production; it lacks some creator/channel/backup properties.
- `REGATA_SIMULATOR_ENC_PASSWORD`: existing key, unchanged unless explicitly migrating credentials.
- `REGATA_SIMULATOR_DATABASE_ENGINE=sqlite`, `REGATA_SIMULATOR_DATABASE_SQLITE_FILE`: verified absolute store; no startup JSON import.
- `REGATA_SIMULATOR_DB_PATH`: legacy compatibility/overlap-check path; never point an old binary there after new SQLite writes.
- `REGATA_SIMULATOR_SOURCES_PATH`, `REGATA_SIMULATOR_TEMPLATES_PATH`: matching absolute media roots.
- `MAGICK_PATH`: sandbox-compatible ImageMagick 7 entrypoint below.
- `REGATA_SIMULATOR_BACKUP_LOCAL_DIRECTORY`: existing private backup root; optional `REGATA_SIMULATOR_BACKUP_RETENTION_COUNT` defaults to 7.
- `REGATA_SIMULATOR_SCHEDULING_ENABLED`: true for normal production; false for a planned rollout smoke. Neither disables manual writes or registration.
- `SERVER_ADDRESS=127.0.0.1`, `SERVER_PORT=8080`: nginx upstream/fixed release readiness endpoint.

Production reboot startup and schedules are enabled. Default publishing: every 30 minutes; backup: Sunday at 12:15, `America/Sao_Paulo`. Override `regata-simulator.scheduling.{zone,publish-cron,backup-cron}` deliberately; changes enable live side effects.

### ImageMagick and sandbox

An ImageMagick **AppImage can work in SSH but fail under NoNewPrivileges** because FUSE mounting is denied (exit 127). Keep hardening: extract the existing bundle once privately, preserve it, and point `MAGICK_PATH` to **`squashfs-root/AppRun`**. The launcher configures bundled libraries/assets and executes native payload without FUSE. The current `bots2` entrypoint is `/home/ubuntu/.local/share/regata-tools/imagemagick-extracted-20261012/squashfs-root/AppRun`, not the original `/home/ubuntu/tools/bin/magick` bundle.

Verify version **and full render** under actual EnvironmentFile/NoNewPrivileges/PrivateTmp/ProtectSystem/resource limits. Resource-only tests or hardened login tests do not verify rendering. Preserve scratch/image/geometry/process limits; don't bypass policy or expose raw subprocess output to fix packaging.

### HTTPS and browser policy

Keep port 8080 private behind the trusted proxy. Stage handles forwarded headers: verify upstream/header trust. Keep Secure/HttpOnly/host-only/Lax cookies; same-origin needs no CORS grant, cross-origin needs exact origins. The certificate covers `regsim.boatarde.dev`, not necessarily the `.dev.br` alias. Test actual authenticated HTTPS/CSRF when changing proxy/cookies.

## Release and rollback

1. Check latest-head CI, resource capacity, recoverable data and intended destinations. Rehearse data/schema changes separately using [offline procedures](operations-runbook.md).
2. Approve stage intentionally. Script validates hash/Java/paths/schema, confirms writers stopped and atomically selects `current`.
3. Readiness defaults to **120 attempts**, one-second intervals, curl connect/total limits of 2/3 seconds. Script-level `READINESS_ATTEMPTS` accepts canonical integers 1..300. Bounded headroom is not an exact two-minute deadline; don't hide broken startup.
4. Verify commit, service/restarts, local readiness, HTTPS and real Telegram/render behavior. Enable boot/schedules intentionally if paused; observe memory during jobs, not just idle.

Interface: `bash scripts/deploy-release.sh ROOT FULL_COMMIT_SHA JAR_SHA256 SCHEMA_VERSION artifact-only-schema-compatible`. `JAVA_BIN` controls its Java check; workflow/actual unit must agree. Root needs incoming/releases; release output must be new.

Failed readiness stops writers and rechecks a previous managed same-schema artifact, still reporting failure; first release removes selection and remains stopped. Failed/unmanaged binaries aren't known-good fallbacks. Unconfirmed stop or failed fallback needs manual recovery. **Artifact rollback does not undo data/schema writes**; equal markers don't establish arbitrary old-binary compatibility.

Don't blindly rerun a failed same-SHA job: paths already exist and old jobs use old scripts. Preserve failed artifacts; use a reviewed new release or explicit stopped/archive procedure. Never delete data/recovery files to make retry pass.

## Troubleshooting and smoke checks

- Read-only: `systemctl --user status regatasimulator.service`, `systemctl --user show regatasimulator.service`, `readlink ~/.local/share/regata/current`, local probes and HTTPS response codes. Summarize journals without credentials/raw Telegram URLs/private Messages.
- UP means lifecycle, not rendering. Correlate `operation=MemeService.publish outcome=completed`, scheduler failures, cgroup peak/swap/limit/OOM events and restarts.
- Exit 127: reproduce under full sandbox, inspect AppImage/FUSE/loader/delegate requirements before changing policy.
- **POST `/api/admin/post_meme`**, **POST `/api/admin/create_backup`** require admin login/fresh post-login CSRF. They send real messages/mutate state: authorize, invoke once, never replay uncertain responses. Publish HTTP 200 follows render/send/persistence completion.
- Photo sends have bounded connection/429 retries using the same rendered artifact; see [delivery contract](project-map.md#media-and-geometry). Ambiguous read/reset failures and server errors are not blindly retried, and failed delivery is not durably queued for later. The next cron invocation is a new generation, not recovery of the previous one.
- Shared small hosts may reach cgroup ceilings during recovery. Prefer sequential jobs and monitor pressure; don't silently increase limits/stop unrelated apps. Swap occupancy alone doesn't establish active thrashing or spare capacity.

Use the [recovery runbook](operations-runbook.md) for data protection and partial failure.