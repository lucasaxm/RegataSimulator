# Native text experiment: running and interpreting it

## Scope

`spikes/native-text/` is an **opt-in local native experiment**, separate from the unchanged Konva/Moveable browser spike. It does not start Spring/Telegram, load application configuration, read user images or change templates/schema/production rendering. It compiles only its standalone Java class and the existing plain `ProcessRunner`, outside Gradle/JAR source sets. Python uses the standard library; no package, global font or JDK installation is needed.

This is the first executed slice of the [native feasibility gate](backend-architecture.md#4-rendering-proof-of-concept-before-backend-implementation), not a production text renderer or complete backend acceptance. Authentication, image/text API integration, moderation/publication and dual-store migration remain separate planned work.

## Reproduce without application startup

Requires Python **3.12+**, an explicit **Java 21 JDK**, and an existing ImageMagick **7** native executable. Java 21.0.7 can run the local experiment but is not the maintenance-floor release JDK. macOS and POSIX/Linux process groups are supported; Windows is not tested/supported by this launcher.

Create a new owner-only parent under the **resolved** system temporary directory. Pass new child output paths: existing paths, symlink ancestry, shared output parents, repository paths and overlap with fonts are rejected. The launcher never sources `.env`/Spring profiles or trusts inherited Java/Magick configuration variables.

```sh
WORK=$(python3 -c 'from pathlib import Path; import tempfile; print(tempfile.mkdtemp(prefix="regata-native-text-", dir=Path(tempfile.gettempdir()).resolve()))')
python3 spikes/native-text/runner.py prepare-fonts --output "$WORK/fonts"
python3 spikes/native-text/runner.py self-test --java-home /absolute/path/to/jdk21 --fonts "$WORK/fonts"
python3 spikes/native-text/runner.py run --java-home /absolute/path/to/jdk21 --fonts "$WORK/fonts" --magick /absolute/path/to/magick --output "$WORK/smoke" --profile smoke --rounds 2 --sample-rss
python3 spikes/native-text/runner.py run --java-home /absolute/path/to/jdk21 --fonts "$WORK/fonts" --magick /absolute/path/to/magick --output "$WORK/boundary" --profile boundary --rounds 1 --sample-rss
```

Commands are run from repository root; paths are operator-selected **local tools**, never production credentials. Use a new result path for every rerun; retain failed results for comparison. Reports/images stay in private temporary storage, not tracked `build/`, user media or template directories. Delete only owned experiment storage when evidence is no longer needed.

`prepare-fonts` is the only network operation: bounded download of the official Liberation **2.1.5** TTF archive into private storage, pinned archive/individual face/LICENSE SHA-256 values, bounded extraction rejecting links/unsafe headers and unmodified OFL notice retention. These digests identify reviewed downloaded artifacts; they are not an independent upstream signature/legal-clearance claim. Rendering is offline with those explicit font paths. Sans/Serif/Mono regular/bold are used; no font fallback/upload/URL support.

Pure launcher regression tests (no native process, download or real font dependency):

```sh
PYTHONDONTWRITEBYTECODE=1 python3 -m unittest discover -s spikes/native-text -p 'test_*.py' -v
```

`self-test` adds actual JDK/font/model/layout checks but no ImageMagick execution. Ordinary Gradle tests remain independent of native tooling. IDE non-project import warnings for this standalone Java file do not imply `javac` failure; do not add it to production packaging merely to satisfy the editor.

## What the profiles test

| Case | Proof / limitation |
| --- | --- |
| Owned UTF-8 file-fed `-annotate` / `caption:` | Decoded pixels for percent-expression, literal backslash versus newline, and leading `@` sentinel text. Compare an escaped literal oracle. No host secret files are used; this is a finite metacharacter matrix, not a full sandbox audit. |
| Fonts/layout | Six pinned faces, pt-BR accents, combining marks, multiple lines, long text, oversized words, outline, box fill and shrinking. Java uses grapheme-safe breaking and bounded positive size search. Unsupported glyphs, invalid surrogates/controls, excessive strings/boxes and impossible geometry fail explicitly in pure tests. |
| Native fitting | Magick built-in bounded maximum/start size, then free-height verification at its suggested integer size. Fixed-height output alone is not evidence against clipping. Native caption's internal edge/grapheme/outline and line-height parity still need further characterization; final outer margin checks are not proof of every glyph's completeness. |
| Full small scenes | Generated template alpha, skew/off-canvas image distortion and masks, shared image/description slot, both text planes, text-only shared slot and all-literal `K=0`. Prototype empty-source resolution is tested; real repository/history/publication services are not invoked. |
| Image-only parity | Exact decoded pixels against the current native full-canvas source → template composition fixture. Independent Java ordering/placement checks compare exact opaque/transparent pixels; **fractional-alpha cross-engine quantization is excluded**, not passed off as pixel-identical output. |
| Boundary profile | 128 sequential small boxes per candidate; native-only 40MP canvas + small text attempt, without a full 40MP JVM decode. Cache/time failures are reported as capacity outcomes, not silently counted as completed renders. |

Text candidates are **Magick caption from a server-owned text file** and **Java2D/TextLayout box raster → existing Magick compositor**. They do not yet implement the entire proposed text DTO/renderer contract: alignment/style catalogue, all outline bearings, browser metric parity, complete adversarial/native limits and production integration still need their owning tests. No engine winner is automatically selected by a successful profile.

## Bounds and evidence

- Java child uses headless mode and heap **64m/256m**; compilation is isolated and excluded from render measurements. Child environment is reduced to owned HOME/TMPDIR, local tool PATH and locale; inherited application secrets, Java options and user Magick configuration are not loaded.
- Native conversion/identify calls retain memory **128MiB**, map **256MiB**, disk **512MiB**, thread **2**, time **25 seconds**, with a **30-second** process runner. The standalone version query is separately time-bounded and does not accept conversion-limit prefixes.
- Each prototype job has a **90-second** deadline; the launcher bounds the overall child/compilation execution below **300 seconds**, kills owned process groups on interruption/timeout and retains bounded private Java diagnostics. Per-job native/text intermediates are cleaned, while generated fixture/result PNGs and reports are retained.
- Initial prototype text limits are 4,096 UTF-16 units, requested size ≤128 and box ≤2MP/2,048 per side with fixed inset. These are **experiment limits**, not selected production API guarantees. Whole-job text/pixel/scratch/admission budgets still require Linux evidence.
- `checks.csv` records PASS/FAIL/**CAPACITY_REJECTED**. A zero exit or `external-metrics.json.succeeded=true` means the experiment child completed; **it does not turn rejected capacity cases into passes** or establish release readiness.
- `measurements.csv` records case/engine wall time, chosen size, native process count and result bytes; `commands.csv` records individual command time. `metadata.properties` records OS/JDK/Magick/font identities. External metrics include source digests for reproducibility and optional **sampled process-tree RSS**, not a cgroup peak. Shared pages can be double-counted, brief peaks missed, and unavailable metrics stay null.

Do not commit per-run CSV/JSON/image evidence or freeze measured values into evergreen capacity promises. Archive evidence privately/CI if it must outlive temporary storage.

## Verified local conclusions and remaining gate

Local generated-fixture execution on **macOS x86_64 / ImageMagick 7.1.1-47 / Corretto 21.0.7 / Liberation 2.1.5** established:

- File-fed annotation/caption preserved the tested metacharacters literally. This supports continuing with an owned-file boundary; it does not justify passing untrusted text/paths directly to interpreted operands or weakening file-indirection/security policy.
- Both candidates rendered the sampled pt-BR/text-only/mixed cases. Browser text metrics are not implied, and the production generator still has no text implementation.
- Descending one-native-render-per-size fitting is a poor candidate: long text caused many process launches. Native built-in fit plus free-height verification reduced that work, but Java2D remained substantially cheaper on the long-text/128-box fixtures. Keep work/command-count bounds explicit rather than claiming a universal speedup.
- The 40MP **native solid-color baseline failed with pixel-cache exhaustion before text work** under existing limits on this local Q16-HDRI build. The failure is not attributed to text or papered over by larger limits. Admission at 40MP does not guarantee every native operation fits its cache budget; characterize the deployed build and representative media before selecting production limits.

**Working direction: retain Magick for distortion/composition; investigate bounded Java2D for text measurement/rasterization.** Native caption remains a measured alternative, not a forced replacement or a finalized engine decision.

Still required before production selection/enablement: checksum-pinned maintenance JDK/native build, aggregate JVM + Spring + native memory/CPU/scratch and overload/interruption tests, matching Linux **MemoryMax448M / MemorySwapMax64M**, extracted AppRun and actual `NoNewPrivileges`/private-temp/filesystem hardening. This macOS run enforces neither the total service cgroup ceiling nor the VPS sandbox. No live bots2 benchmark, service setting, dependency upgrade, stored data or bot operation was authorized/performed by this slice.
