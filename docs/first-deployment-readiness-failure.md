# First deployment readiness failure — 2026-10-11

## Confirmed cause and recovery

Release run `38177347213`, merged commit `8d6b4788ee87facea6e36eeeb02607fef3c59d8e`, failed with **Readiness deadline exceeded**. Actions SSH authentication, checksum-verified artifact transfer and service startup reached the VPS successfully. The SSH-agent post-job cleanup warning was not the deployment failure.

Sanitized service journal recorded successful Tomcat startup on 8080 and `Started RegataSimulatorApplication in 51.887 seconds`. No observed bot-registration failure, encryption failure, OOM, permission failure or failed Spring startup marker. The script allowed only 30 readiness attempts, separated by one-second waits with bounded HTTP calls; on this constrained host it shut down the app just as startup completed. Its first-release recovery confirmed writer stop and removed `current`.

Verified after failure: MainPID 0, no port8080 listener, no selected release, and migrated database SHA-256 unchanged at `190e92ff41cca824ddca3da52041afc558fa2ee56624a466d2e7ca85619e154f`. Incoming and immutable failed-release JARs both hash to `3a762b8391278fb6650070a5353e5137036a3588fa1895731594fe44d05229d3`; schema marker is 3. These artifacts were retained, not deleted or overwritten.

## Fix

The release script now defaults to **120 readiness attempts**, retaining one-second waits and curl connect/total limits of 2/3 seconds. Optional `READINESS_ATTEMPTS` must be a canonical positive integer **1..300**, validated before artifact/service changes. This is a bounded attempt allowance, not an exact wall-clock deadline; HTTP time can make it longer than the nominal two-minute interval. Failed readiness still stops writers and rolls back/removes selection as before.

Tests cover delayed success beyond the old 30-attempt window, unsafe overrides, bounded default, rollback, first-release failure and unchanged preflight contracts. This is operational startup headroom, not a relaxation of authentication, storage or health policy.

## Retry policy

Do not blindly re-run the old Actions job: its incoming/releases commit directories already exist, and its checkout still contains the old script. Prefer merging the reviewed readiness fix and deploying the new commit SHA after CI and explicit `stage` approval. Preserve this failed attempt for diagnosis. Any same-SHA operator retry requires an explicit stopped-state/archive procedure, not deletion of data or overwriting immutable releases.

No production restart, release retry, GitHub deployment approval or database/media modification was performed during this diagnosis. Real Telegram replies, HTTPS login and longer-term resource behavior remain post-start verification tasks. See [morning handoff](morning-deployment-review.md) for preserved recovery copies and memory constraints.