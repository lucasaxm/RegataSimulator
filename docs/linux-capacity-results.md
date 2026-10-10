# Linux capacity verification — 2026-10-10

## Confirmed results

The user authorized continuing the upgrade on `bots2.ssh.boatarde.dev` after v1 was stopped and all data preserved. Original production storage and both verified recovery copies were left untouched.

- Installed a Regata-specific Temurin **21.0.12.1+1** under `/home/ubuntu/.local/share/regata-tools/jdk-21.0.12.1+1`. Official Adoptium Linux x64 archive SHA-256 **`ce79869e1307ed8ee1e2baa86a412b1eb5b75d10a01006d788a6f968bcfaee94`** matched before extraction. No system Java selection or other application's JDK was changed.
- Candidate JAR SHA-256 **`3d39fc7934864141f8f25ebed46abac05e01d7f2abfc09ccd16e459d66a9692a`** verified on host. Temporary helper was compiled against its extracted classes/libraries, not modified runtime source.
- Private test root `/home/ubuntu/.local/share/regata-linux-check-20261010` contains only neutral fixtures, candidate/helper and test outputs. Synthetic overrides supplied every required credential. Registration/schedules were disabled, server bound to loopback with an ephemeral port; no Telegram requests or real channel delivery occurred.
- Actual Linux transient user unit `regata-capacity-20261010.service` applied **MemoryMax=448M**, **MemorySwapMax=64M**, **TasksMax=128**, with **-Xms64m -Xmx256m**. The JVM reported effective maximum heap 247 MiB (collector/container ergonomics), not an assured 256 MiB usable heap.
- **Passed** Spring startup, five real ImageMagick **7.1.1-38** renders of 400×300 neutral fixtures, image decode/dimensions, scratch cleanup, coordinated local backup and fresh `RESTORED_VERIFIED` restoration. Helper/service exited **0 / success**; runtime about **89.5 seconds**.
- Aggregate cgroup peak **399,458,304 bytes (~381 MiB)**, current near end **384,167,936 bytes**. Includes helper/JVM/native children and cgroup-accounted cache, not only Java heap.
- Service swap usage **0**; memory events `max=0`, `oom=0`, `oom_kill=0`, `oom_group_kill=0`. This is a successful bounded test, not evidence of zero host-wide swapping or indefinite production safety.

## Limits and next gate

Small neutral fixtures do not establish production dataset/image/concurrency peaks. The shared roughly 1 GiB host remains constrained. Limits cap damage to other apps but can terminate Regata if exceeded; 448 MiB is not a memory reservation. Real production-data startup/render/backup should be checked on isolated copies under the intended unit before enabling long polling/schedules.

At this checkpoint the offline importer was invoked against the **verified stopped production backup**, with fresh nonoverlapping target/report paths under `/home/ubuntu/.local/share/regata-production-candidate-20261010`. It uses `--ack-write-freeze-and-isolated-copy true`; no original database/media path is a write target. No SQLite file is selected in runtime configuration. Results must be read from its safe report, not inferred from command submission or quiet logs. Blocking anomalies must be reconciled explicitly on new copies, never silently dropped or "fixed" in original storage.

The production systemd unit/private environment/GitHub variables are not yet provisioned, v1 remains intentionally stopped, and no merge or production replacement startup occurred in these capacity checks. See [preservation checkpoint](production-preservation-checkpoint.md), [deployment setup](deployment-setup.md), and [operations runbook](phase-5-operations-results.md).