# Build, dependency and security maintenance

[build.gradle](../build.gradle), [gradle.lockfile](../gradle.lockfile), [wrapper configuration](../gradle/wrapper/gradle-wrapper.properties) and [CI](../.github/workflows/) are version authorities. Keep upgrade policy and compatibility requirements here, not historical vulnerability/test timelines.

The image-only editor also has a tracked [package](../frontend/template-creator/package.json)/[npm lockfile](../frontend/template-creator/package-lock.json) and build source in `frontend/template-creator/`, separate from the unchanged spike.

## Runtime baseline

| Family | Configured baseline |
| --- | --- |
| Java / Gradle | Java 21, release floor 21.0.12.1; Temurin 21.0.12.1+1 / Gradle 8.14.6 |
| Boot / Framework / Security / Session | 4.1.1 / 7.0.9 / 7.1.1 / 4.1.1 |
| Tomcat / springdoc | 11.0.26 / 3.1.1 |
| Jackson 2 / Jackson 3 | 2.21.7 / 3.1.7 |
| Xerial / SQLite / Liquibase | 3.53.4.0 / 3.53.4 / 4.33.0 |
| Telegram Bots / JsonDB / native Jasypt | 6.9.7.1 / 1.0.115-j11 / 1.9.3 |
| Editor CI Node / bundled browser runtime | 22.23.1 / Konva 10.7.1 (only bundled runtime dependency; optional hosted Telegram SDK UX) |
| Editor build / browser test tools | esbuild 0.28.2 / Playwright 1.64.0 |

Boot provides compatible Spring families. Jackson 2 is persistence/Telegram/preferred HTTP via `spring-boot-jackson2`; patch both JSON families coherently. Strict runtimeClasspath locking doesn't lock test/plugin graphs. Retain SQLite's runtime patch guard/per-connection durability.

The editor package allows Node `>=22.23.1 <23`; CI pins 22.23.1. Older Java 21, including 21.0.7, can run local tests/builds but is below the **21.0.12.1 maintenance floor**. Such a pass does not satisfy `verifyMaintenanceJava` or prove release parity; keep patched launcher/compiler/test-executor proof separate from ordinary local verification.

## Editor build and verification

From repository root, use the tracked package/lockfile, with no global dependency installation:

- `npm ci --ignore-scripts --include=dev --include=optional --prefix frontend/template-creator` — locked runtime/build/test graph. Preserve optional `@esbuild/<platform>-<architecture>` binaries with lifecycle scripts disabled; do not use `--omit=optional` or enable install scripts to repair a missing binary.
- `npm run check --prefix frontend/template-creator` — syntax for editor/build/test sources.
- `npm test --prefix frontend/template-creator` — model/media/Telegram suites and isolated static-server HTTP checks, including controlled SDK/DOM doubles without hosted SDK/external API/production-bot requests. Stop a manual preview first: server checks bind loopback port 4174.
- `node --check frontend/template-creator/telegram.mjs` and `node --test frontend/template-creator/test/telegram.test.mjs` — focused Telegram syntax/unit checks, also included in the npm gates and Gradle/CI `frontendCheck`. Doubles are not real-device proof; Node/npm/dependency versions remain unchanged.
- `npm exec --prefix frontend/template-creator -- playwright install chromium` — explicit browser provisioning when needed; npm lifecycle scripts remain disabled.
- `npm run test:browser --prefix frontend/template-creator` — Chromium desktop/mobile emulation with its own loopback server. Stop a manual server first: reuse is disabled.
- `npm audit --audit-level=low --prefix frontend/template-creator` (or `npm run audit --prefix frontend/template-creator`) — npm graph, including build/test tools; findings/incomplete audits are not a pass.
- `./gradlew frontendCheck` — installs locked dependencies and runs syntax plus npm model/media/Telegram unit checks; browser/audit/package gates remain separate.
- `./gradlew clean build` — clean Java tests/package plus generated editor assets; `./gradlew bootJar` — executable JAR without implying the other gates passed.
- `npm run verify:package --prefix frontend/template-creator` — requires an existing `bootJar`; inspects exact editor entries, self-hosted hashed JS/CSS, manifest digests and complete Konva notice without starting Spring.

`installCreatorDependencies` → `buildCreator` → `processResources` emits assets at `build/generated-resources/creator/static/create/editor/`, then includes them under `BOOT-INF/classes/static/create/editor/`. Source is `frontend/template-creator/`, not generated output. Declared inputs/outputs and owned-output cleanup prevent accumulating stale hashed assets; never hand-edit output or rely on a stale bundle. Runtime bundling permits only Konva; `licenses.txt` retains its packaged MIT notice and `manifest.json` includes runtime and locked security inventory. Tool-license metadata is not legal clearance; development tools are not shipped in the runtime bundle.

**Current SDK integration contract:** HTML includes official async **https://telegram.org/js/telegram-web-app.js**. `build.mjs` and `verify-package.mjs` permit exactly one external `src`, that exact URL, and no remote `href`; package verification also checks `manifest.hostedIntegrationScripts`. `server.mjs` scopes local CSP to that exact remote script URL, not the host or wildcards. Konva/application JS/CSS stay hashed/self-hosted. Spring retains its existing frame-ancestor/CORS/ADMIN/CSRF policy; this is not a global CSP rewrite. `build.mjs --check` and Gradle `buildCreator` explicitly include `telegram.mjs`; npm test includes its controlled unit suite, and CI `frontendCheck` runs npm check/test. Preserve already/late/missing/failed/older SDK handling, ready/expand, theme/viewport, gated closing confirmation, clipboard failure/manual fallback and copy-success feedback with explicit return-only closure. The app uses the minimum of browser visual height and valid Telegram stable height and cancels gestures before hosting viewport changes without changing canonical geometry. SDK/initData/UI does not authenticate or authorize external APIs; the hosted unversioned SDK is outside npm/Maven audit coverage. Configured checks, stale JARs and doubles are not overall-green or release evidence.

`npm start --prefix frontend/template-creator` rebuilds/serves only **http://127.0.0.1:4174/create/editor/**, without Spring/bot/storage/schedules. First deliver the useful Konva image-only candidate with unchanged manual document/caption upload, confirmation/review and optional safe SDK UX. Its single **Ajustar**, natural image opening, hidden pre-image tools, automatic editing, **Forma / Precisão / Camadas**, confirmed undoable full-image reset and **Preparar envio** UI preserve internal CSV compatibility. Later text/eyedropper/native preview/direct submission (slices 4–6) do not block it. No canonical `/create` switch or deployment now; retain legacy fallback until phone/Telegram acceptance and separate operator release. This is not the spike's port 4173; no Node/npm VPS runtime is needed.

For phone testing, use `npm start --prefix frontend/template-creator -- --lan` on trusted Wi-Fi. It rebuilds before binding all IPv4 interfaces, accepts only loopback/current private-interface Host addresses and prints phone URLs on port 4174. Phone and Mac need a reachable shared network; do not forward the LAN listener to the internet. Only generated assets are served, not selected images/backend APIs. Plain HTTP may lack clipboard access; retain manual-copy fallback. Default/CI stays loopback-only. After **every delivered editor slice**, restart with fresh assets and provide newly printed reachable URLs, not stale private IPs. LAN preview is separate from Telegram-client acceptance/authentication/operator release; documentation-only work launches no server.

**Explicitly authorized temporary Telegram HTTPS testing:** stop the LAN preview and run `npm start --prefix frontend/template-creator -- --telegram-preview`. This mutually exclusive mode remains bound to `127.0.0.1:4174` and changes only local CSP `frame-ancestors` to `'self' https://*.telegram.org https://telegram.org`, allowing Telegram Web embedding; default/LAN previews retain `'none'`. Use a trusted HTTPS tunnel client pointing only to `http://127.0.0.1:4174`, with origin Host rewritten to `127.0.0.1:4174` (for example `cloudflared tunnel --url http://127.0.0.1:4174 --http-host-header 127.0.0.1:4174 --no-autoupdate`). Append `/create/editor/` to its newly printed HTTPS origin. The tunnel is public/unauthenticated and terminated by the provider: share only with testers, do not use sensitive images, keep it short-lived, and stop both processes afterward. No account credential or bot token is needed for Cloudflare Quick Tunnels; download a platform-specific official binary into temporary storage and verify its release-asset SHA-256 rather than adding it to npm/runtime dependencies. Never tunnel Spring, repository directories, storage, APIs or other ports. The operator changes/reverts the bot link; the assistant does not mutate bot settings as part of serving the preview. Restore the original link before stopping the tunnel. URLs expire/change on restart; no uptime, production deployment, authentication or real-client acceptance is implied. Production Spring policy and canonical `/create` remain unchanged.

PR/release workflows configure Node 22.23.1, clean Gradle packaging, `frontendCheck`, npm audit, explicit Chromium provisioning, browser and package checks alongside existing Java/Node/Maven gates. Their presence is not evidence of a green run. Tests use generated fixtures, never `spikes/example/` or private media. Real Android Brave/iOS/Telegram WebView and keyboard-open acceptance remain rollout gates; emulation is not release/device parity. See [current editor contracts](project-map.md#image-only-editor-current-first-slice) and [remaining plan](template-creation/proposal.md).

## Release validation

- `./gradlew verifyMaintenanceJava clean build` — patched Java release gate/tests/package.
- `node --test src/test/js/*.test.cjs` — separate browser/deployment/scanner gates.
- `./gradlew dependencyScan` — actual OSV Maven scan/inventory/notices.
- Node shim trouble: supply `-PnodeExecutable=/absolute/path/to/node`.
- Editor npm shim trouble: `-PnpmExecutable=/absolute/path/to/npm`; keep its Node on PATH consistent with the selected version.
- Isolated JDK proof: set JAVA_HOME/PATH plus `-Porg.gradle.java.installations.paths=/absolute/jdk`, disable auto-detect/auto-download and use `--info` to verify **compiler and test executor**, not merely launcher. Don't install globally just for testing.

CI verifies the exact official Linux Temurin archive's SHA256 before setup-java `jdkfile`. Runtime `21.0.12.1+1` isn't valid setup-java SemVer; current archive uses supported cache identifier `21.0.12+101`. That identifier does not replace checksum/runtime verification. Update URL/hash/identifier coherently. Gradle distribution checksum/wrapper validation remain mandatory.

## Scanner and license boundaries

Inventory retains resolved coordinates and embedded LICENSE/NOTICE/COPYING under `build/dependency-security/`; absent notices require upstream POM review. CI retains evidence 30 days; archive longer if required and preserve notices when redistributing.

Public OSV scanner needs no key: 50-package batches, three attempts, 15-second request/stream bounds, 4 MiB response cap. Findings/malformed/paginated/incomplete/network-failed results fail the gate; stale reports removed. **Don't suppress the gate to force deployment.** Zero findings is time-bound Maven-graph evidence, not security certification.

Review JDK/native ImageMagick/SQLite/OS/CDN and npm advisories, upstream support/licenses separately; Maven scanning doesn't cover those, unknown advisories or every exploit path. The editor has its own npm audit/inventory/packaged-notice gates; the spike's Moveable/Croact graph and notice gaps remain separate. Version numbers/fake scanner tests/local passes don't establish real CI/production behavior.

## Legacy encrypted configuration

The native bridge preserves existing ENC: PBEWITHHMACSHA512ANDAES_256, SunJCE, pool size 1, 1,000 iterations, random salt/IV, Base64. **Compatibility only, not a new KDF recommendation.** Don't rewrite production ciphertext or encryption policy incidentally.

- Preserve lazy reads/source order/enumeration/contains/origin/relaxed lookup, `MapPropertySource` type and original backing map.
- Unsupported GCM/custom/asymmetric settings, malformed ciphertext, missing/wrong keys and cycles fail closed with no secret-bearing causes/plaintext fallback. Higher-precedence plaintext still follows Spring precedence.
- `EncryptedPropertyFailure` intentionally is a **cause-free Error**: Boot opaque-source mapping catches exceptions and could fall through to lower-precedence plaintext. Don't "tidy" it away without regression coverage.
- Keep environment-postprocessor/late-initializer spring.factories discovery. Automated tests use synthetic ciphertext; authorized real credential checks stay in memory, never fixtures/docs/logs.

## Upgrade workflow

Scope runtime/dependency/schema changes explicitly; select published compatible releases, not speculative versions or isolated incompatible Spring overrides. Refresh lock/inventory/scan; run clean Java/Node gates and full filter-chain/property/repository/migration/recovery regressions. Review binary/native compatibility; separate schema cutover from artifact rollout.

Native changes need actual renders under full service environment/sandbox; readiness and shell version checks aren't sufficient. Preserve trust, backups/rollback and [approval controls](deployment-setup.md). Per-run evidence belongs in CI/private operational storage, not new report docs.