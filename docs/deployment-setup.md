# Deployment setup: GitHub `stage` is production

Date: 2026-10-10. The user has local development and one production VPS; the historically named GitHub environment **`stage` is production**, not a separate staging server. This is a provisioning checklist, not evidence that the VPS was inspected or deployed.

## 1. Current GitHub Actions behavior

- `.github/workflows/build-and-test-pull-request.yml`: PRs targeting `main` run wrapper validation, patched Temurin 21.0.12.1+1, clean Gradle build/tests, all Node fixtures and the actual fail-closed OSV scan; retain security/license evidence for 30 days. No app secrets are needed for tests/scanning.
- `.github/workflows/release-and-deploy.yml`: a push to `main` builds/tests/scans, uploads the executable JAR, then deploys through environment **`stage`**. Manual dispatch defaults to `stage`; choose that environment for this production VPS. Concurrency serializes releases for that environment.
- SSH uploads the built artifact once, verifies its checksum and invokes the checked-in release script. The script stops the preinstalled user service, switches a managed release symlink and checks localhost:8080 readiness. Failure can select a previous managed artifact with the same declared schema; it does not undo database writes.
- Runtime secrets, the service unit, Java and ImageMagick are **not installed/transmitted by Actions**. Environment name does not automatically select a Spring profile.
- Changes are currently local on `refactor`; no push/deployment has occurred. Do not merge/push to `main` until the production environment and VPS are provisioned. A main push triggers deployment, not only CI. Optional environment approval protects this production gate where the repository plan supports it.

## 2. GitHub environment setup

In Settings → Environments → **stage**, configure exactly these entries:

| Kind | Name | What to configure |
| --- | --- | --- |
| Secret | `SERVER_SSH_KEY` | Keep the existing private deploy key if valid. Its public key must authorize the intended non-root VPS deployment user. |
| Variable | `SERVER_HOST` | Copy the existing host value into a variable; the workflow reads `vars.SERVER_HOST`, not the current secret. Hostname/IP only, no scheme/port. |
| Variable | `SERVER_USERNAME` | Copy the existing SSH user value into a variable; the workflow reads `vars.SERVER_USERNAME`, not the current secret. |
| Variable | `SERVER_ROOT` | **New.** Canonical absolute deployment root matching the unit: `/home/<deploy-user>/.local/share/regata` for a normal home layout. Resolve the actual home path; do not put `$HOME`, `~` or placeholders in the variable. |
| Variable | `SERVER_KNOWN_HOSTS` | **New.** Complete trusted OpenSSH known_hosts line(s) for this host, not just a SHA256 fingerprint. Obtain/verify against the VPS/provider console or another trusted channel. SSH checks are strict; do not disable them or trust a just-discovered key blindly. |

No newly generated application secret, Telegram token or OSV API key is required. The pictured application secrets (`REGATA_SIMULATOR_ENC_PASSWORD`, database/media paths and `MAGICK_PATH`) are no longer consumed by the workflow. Keep them until the VPS runtime environment is verified; their existing correct values can be reused there. Existing host/user secrets can remain temporarily but do not satisfy the new `vars.*` references. Do not paste secret values into chat or commit them.

The checked-in SSH workflow assumes port 22. A different port requires an explicit reviewed workflow change for both ssh/scp and the trusted-host entry, not putting `host:port` into `SERVER_HOST`.

## 3. One-time VPS provisioning

Use the **same non-root account as `SERVER_USERNAME`** for the deployment root, service and data access. Administrator-only package/user-manager work should be done directly on the server by an operator; no privileged operation was performed in this review.

### Binaries and user manager

1. Install patched **Java 21.0.12.1 or newer Java 21**, and verify that **`/usr/bin/java`** is the actual correct executable. The unit and default release check both use that path. Merely installing a different JAVA_HOME does not update the unit. An explicit unit override and consistent deployment check are needed if using another path.
2. Install **ImageMagick 7** and find its actual `magick` executable. The renderer calls `magick identify` and subcommands; do not substitute an ImageMagick 6 `convert` path. Check the executable with the service user's permissions.
3. Provide Bash, OpenSSH, curl, GNU coreutils (`timeout`, `mv -Tf`), `shasum`, sed/grep and a working systemd user manager. Gradle and Node are not needed on the VPS for normal JAR deployment.
4. Arrange that the user's manager starts at boot and remains available over noninteractive SSH, commonly via administrator-provisioned `loginctl enable-linger <deploy-user>`. Verify `systemctl --user` works for that actual SSH user, not just root.
5. Check host support for the unit's sandboxing/cgroup limits. Its 768 MiB memory limit covers Java **and ImageMagick child processes**, not only the 512 MiB JVM heap; ensure capacity or provision a reviewed override. Do not blindly remove hardening after a namespace/permission failure.

### Directories and unit

Create existing private directories (700, owned by the deployment user):

- `~/.local/share/regata`, including `releases/` and `incoming/`;
- `~/.config/regata`;
- `~/.local/share/regata-data` and the intended database/source/template roots;
- `~/.local/share/regata-backups`, nonoverlapping with application metadata/media.

These must be actual canonical paths, not symlink aliases. **Do not create an empty replacement for an existing production store or move data casually.** Prefer preserving its current coherent paths; if outside these prefixes, provision a unit override for `ReadWritePaths` and ownership. `ProtectSystem=strict` requires declared writable locations. Confirm space for render scratch and retained archives; the backup root itself must already exist and be mode 700.

Install checked-in `scripts/regatasimulator.service` as `~/.config/systemd/user/regatasimulator.service` (create the parent). As the deployment user, use `systemctl --user daemon-reload`, verify the unit, and `systemctl --user enable regatasimulator.service`. **Do not start it yet:** first provision runtime configuration, preserve the old release/data and stop the old writer before initial deployment. The unit's working/JAR paths must agree with `SERVER_ROOT`.

### Private runtime environment

Create **`~/.config/regata/runtime.env`**, owned by the deployment user, mode **600**. It is read by systemd, not sourced by the Actions script; use `NAME=value` entries without shell `export`, command substitution or `$HOME` expansion. Enter actual secrets directly on the server, never through chat. Required/intentional entries:

| Variable | Value/purpose |
| --- | --- |
| `SPRING_PROFILES_ACTIVE` | `stage` to retain the existing intended stage/production configuration, after verifying its bot identity/destinations. Do not select `dev` on the production host. |
| `REGATA_SIMULATOR_ENC_PASSWORD` | Existing stage encryption key, unchanged; it must decrypt the checked-in stage/base ENC values. |
| `REGATA_SIMULATOR_DB_PATH` | Existing canonical production JsonDB directory. Preserve this engine for this rollout. |
| `REGATA_SIMULATOR_SOURCES_PATH` | Matching source UUID-directory root. |
| `REGATA_SIMULATOR_TEMPLATES_PATH` | Matching template UUID-directory root. |
| `MAGICK_PATH` | Absolute path to the verified ImageMagick 7 `magick` binary. |
| `REGATA_SIMULATOR_BACKUP_LOCAL_DIRECTORY` | Existing private absolute backup root, normally the deployed user's `~/.local/share/regata-backups` expanded to an absolute path. **New runtime setting; not a GitHub secret.** |
| `REGATA_SIMULATOR_BACKUP_RETENTION_COUNT` | Optional, default `7`; review capacity. |
| `REGATA_SIMULATOR_SCHEDULING_ENABLED` | `false` for the first deployment smoke; explicitly set `true` only after verifying production destinations and behavior. This does not disable manual mutations/long polling. |
| `SERVER_ADDRESS` / `SERVER_PORT` | Recommended `127.0.0.1` / `8080` behind the existing local HTTPS proxy; workflow readiness assumes port 8080. |

`application-stage.yml` supplies creator/token/username/channel/backup-chat and proxy settings. GitHub `stage` and Spring `stage` are distinct concepts even when intentionally using the same name. **Do not switch to Spring `prod` simply to rename production**: the current `application-prod.yml` does not itself supply creator/channel/backup-chat; it would need additional validated configuration.

Default cookies remain Secure/HttpOnly/Lax/host-only in stage. Use the existing HTTPS proxy; do not expose port 8080 directly to the Internet. Stage trusts forwarded-header handling, so verify proxy/header source trust. Same-origin browser access does not need CORS entries; cross-origin frontends require explicit `REGATA_SIMULATOR_WEB_ALLOWED_ORIGINS_0` etc. containing exact origins. No API key is needed for CORS, and it does not replace CSRF/authentication.

## 4. First cutover and deployment

1. Preserve the current JAR, launcher configuration and coherent database/media backup. Identify and stop the legacy `subprocess`/nohup/other service writer before the new service can start. Disable any old restart/crontab that could resurrect it. **Do not run two bots/JVMs against the same token or storage.** Installing the new unit does not stop an old process managed elsewhere.
2. Verify production metadata/media pairing and backup accessibility without making destructive repairs. The old dev dataset's missing images are a separate historical finding, not evidence that production is coherent or broken.
3. Confirm that no unrelated listener can satisfy the fixed port-8080 readiness probe. Keep schedules disabled for the first smoke.
4. Once GitHub variables/key and VPS provisioning are ready, push the reviewed branch and merge to `main`, or invoke the release workflow on the exact approved ref with environment `stage`. CI failures/findings block deployment; do not suppress the gate to force a rollout.
5. Verify service state, local readiness, protected login/API behavior, Telegram identity and an intentional private smoke using the real production configuration. Changes to `runtime.env` take effect on service restart, not merely `daemon-reload`.
6. Enable schedules only after destinations, storage/rendering and backup capture/restore policy are verified. Plan restarts explicitly because normal startup registers the bot.

The old unmanaged binary is **not automatically a previous release** in the new directory layout. A failed first managed deployment leaves writers stopped and no selected `current`; preserve an explicit manual recovery plan for the old binary and any new writes. On later managed same-schema deployments, automatic fallback selects a previous artifact but still does not revert data. Existing `incoming/<commit>`/`releases/<commit>` targets also mean a failed job is not blindly retryable for the same SHA; inspect state before cleanup/retry.

The successful [local final-artifact dev test](boot-4-live-dev-results.md) remains the live application gate. This checklist does not require a second VPS/staging server or a SQLite cutover. See [operations runbook](phase-5-operations-results.md) and [readiness review](deployment-readiness-review.md) for backup limits, writer freeze and deployment/recovery boundaries.