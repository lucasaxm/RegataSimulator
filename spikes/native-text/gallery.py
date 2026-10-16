#!/usr/bin/env python3
"""Export generated visual PoC evidence to a fresh, private, persistent HTML gallery.

No rendering, decoding, downloads, repository metadata reads, or automatic deletion.
PNG admission checks the signature only; visual/native correctness comes from the
retained manifest checks, not from this exporter. POSIX/macOS/Linux only.
"""
import argparse
from collections import Counter
import hashlib
import html
import json
import os
from pathlib import Path
import re
import stat
import sys
from urllib.parse import quote

REPO = Path(__file__).resolve().parents[2]
MIB = 1024 * 1024
PNG_LIMIT = 20 * MIB
TOTAL_LIMIT = 128 * MIB
FILE_LIMIT = 200
MANIFEST_LIMIT = 4 * MIB
PNG_SIGNATURE = b"\x89PNG\r\n\x1a\n"
# Exact generated metadata names only: never enumerate/copy the input directory.
OPTIONAL = {
    "visual-checks.csv": MIB,
    "metadata.properties": 256 * 1024,
    "external-metrics.json": 256 * 1024,
    "source-metadata.json": 256 * 1024,
    "run-metadata.json": 256 * 1024,
}
STATUSES = ("PASS", "FAIL", "LIMITATION")


class Failure(ValueError):
    """A redacted admission failure; never include untrusted paths/content."""


def clean_path(value, existing=True):
    raw = os.fspath(value)
    path = Path(raw)
    if (not path.is_absolute() or str(path) != raw or ".." in path.parts
            or "\x00" in raw):
        raise Failure("An absolute canonical path is required")
    for ancestor in (path, *path.parents):
        if ancestor.is_symlink():
            raise Failure("Symlink ancestry is forbidden")
    if path.resolve(strict=existing) != path:
        raise Failure("An absolute canonical path is required")
    return path


def overlaps(a, b):
    return a == b or a in b.parents or b in a.parents


def directory_fd(path):
    """Walk from / with no-follow opens, including ancestors (race resistant)."""
    fd = os.open("/", os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW)
    try:
        for component in path.parts[1:]:
            child = os.open(component, os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW,
                            dir_fd=fd)
            os.close(fd)
            fd = child
        return fd
    except BaseException:
        os.close(fd)
        raise


def private_directory(fd):
    info = os.fstat(fd)
    if info.st_uid != os.getuid() or stat.S_IMODE(info.st_mode) != 0o700:
        raise Failure("Directory must be owned by this user with mode 0700")


def filename(value):
    # Flat local names, including quoted/spaced names, are safely URL-encoded.
    if (not isinstance(value, str) or not value or value.startswith(".")
            or len(value) > 240
            or any(c in value for c in ("/", "\\", ":"))
            or any(ord(c) < 32 or ord(c) == 127 or 0xD800 <= ord(c) <= 0xDFFF for c in value)):
        raise Failure("Artifact must have a plain non-hidden filename")
    return value


def read_artifact(root, fd, name, limit, png=False):
    filename(name)
    path = clean_path(root / name)
    if path.parent != root:
        raise Failure("Artifact must resolve inside input")
    child = os.open(name, os.O_RDONLY | os.O_NOFOLLOW | os.O_NONBLOCK, dir_fd=fd)
    with os.fdopen(child, "rb") as stream:
        before = os.fstat(stream.fileno())
        if not stat.S_ISREG(before.st_mode) or not 0 < before.st_size <= limit:
            raise Failure("Artifact must be a bounded nonempty regular file")
        data = stream.read(limit + 1)
        after = os.fstat(stream.fileno())
    if (len(data) != before.st_size or len(data) > limit
            or (before.st_dev, before.st_ino, before.st_size, before.st_mtime_ns,
                before.st_ctime_ns) != (after.st_dev, after.st_ino, after.st_size,
                                        after.st_mtime_ns, after.st_ctime_ns)):
        raise Failure("Artifact changed during bounded read")
    if png and not data.startswith(PNG_SIGNATURE):
        raise Failure("PNG signature mismatch")
    identity = {"path": str(path), "device": before.st_dev, "inode": before.st_ino,
                "mtimeNs": before.st_mtime_ns, "size": len(data),
                "sha256": hashlib.sha256(data).hexdigest()}
    return data, identity


def validate_result(result):
    if not isinstance(result, dict) or result.get("engine") not in ("java2d", "magick", "suite"):
        raise Failure("Invalid engine result")
    checks = result.get("checks")
    if not isinstance(checks, list) or len(checks) > 200:
        raise Failure("Invalid check list")
    for check in checks:
        if (not isinstance(check, dict) or check.get("status") not in STATUSES
                or not isinstance(check.get("name"), str)
                or not isinstance(check.get("detail"), str)):
            raise Failure("Invalid visual check")
    for key in ("image", "guides", "reference"):
        name = result.get(key)
        if name is not None and not filename(name).endswith(".png"):
            raise Failure("Only manifest-named PNG images are allowed")


def validate_fixture(record):
    if (not isinstance(record, dict) or not isinstance(record.get("id"), str)
            or not isinstance(record.get("title"), str)
            or not isinstance(record.get("style"), dict)
            or record.get("text") is not None and not isinstance(record["text"], str)
            or not isinstance(record.get("results"), list)
            or not 1 <= len(record["results"]) <= 3):
        raise Failure("Invalid fixture record")
    engines = set()
    for result in record["results"]:
        validate_result(result)
        engine = result["engine"]
        if engine in engines:
            raise Failure("Duplicate engine")
        engines.add(engine)


def manifest_records(data):
    try:
        records = json.loads(data)
    except (ValueError, RecursionError):
        raise Failure("Invalid visual manifest JSON") from None
    if not isinstance(records, list) or not 1 <= len(records) <= 128:
        raise Failure("Visual manifest must be a nonempty fixture array (at most 128)")
    for record in records:
        validate_fixture(record)
    return records


def escaped(value):
    text = "null" if value is None else str(value)
    # Java intentionally tests malformed UTF-16; preserve its visible identity.
    text = text.encode("utf-8", "backslashreplace").decode("utf-8")
    return html.escape(text, quote=True)


def href(name):
    return escaped(quote(filename(name), safe=""))


def encoded_json(value):
    return json.dumps(value, ensure_ascii=True, indent=2, allow_nan=False).encode() + b"\n"


CSS = """
:root{color-scheme:light;--ink:#24313a;--muted:#53616c;--line:#dce2e5}
*{box-sizing:border-box}body{margin:0;background:#f4f5f2;color:var(--ink);font:16px/1.5 system-ui,sans-serif}
main{max-width:1440px;margin:auto;padding:32px 24px}header{border-bottom:2px solid var(--ink);padding-bottom:24px}
h1{font-size:clamp(28px,4vw,48px);letter-spacing:-.04em;margin:8px 0}h2{margin:0;font-size:24px}h3{margin:0}
.eyebrow{letter-spacing:.16em;font-size:12px;font-weight:700;text-transform:uppercase}.muted{color:var(--muted)}
.banner{background:#fff5df;border-left:4px solid #b37818;padding:16px;margin:20px 0}.counts{display:flex;gap:12px;flex-wrap:wrap}
.badge{font-size:13px;font-weight:700;padding:3px 9px;border:1px solid currentColor;border-radius:4px}
.PASS{color:#246440}.FAIL{color:#a02d34}.LIMITATION{color:#805516}
section{margin:28px 0;padding:24px;background:white;border:1px solid var(--line);border-radius:12px}
.pair{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:24px}.panel{min-width:0;border-top:1px solid var(--line);padding-top:16px}
.viewport{overflow:auto;max-width:100%;background:#faf9f4;border:1px solid #aeb8be;margin:12px 0}
.viewport img{display:block;width:auto;max-width:100%;height:auto;margin:auto}
.viewport.checker{background-color:white;background-image:conic-gradient(#edf0f1 25%,white 0 50%,#edf0f1 0 75%,white 0);background-size:24px 24px}
.controls{display:flex;flex-wrap:wrap;gap:8px;align-items:center}button,a.button{font:inherit;font-size:14px;border:1px solid #86959e;background:white;color:var(--ink);padding:6px 10px;border-radius:5px;cursor:pointer;text-decoration:none}
button:focus-visible,a:focus-visible{outline:3px solid #007b91;outline-offset:3px}button[aria-pressed=true]{background:#e6f1f2}
pre{white-space:pre-wrap;overflow-wrap:anywhere;background:#f5f6f4;padding:12px;border-radius:6px;font-size:13px}
.checks{padding-left:20px}.checks li{margin:10px 0;overflow-wrap:anywhere}.checks p{margin:4px 0;font-size:14px}
.swatches{display:flex;gap:16px;flex-wrap:wrap;margin:12px 0}.swatch{display:inline-block;width:24px;height:24px;border:1px solid #5c6870;vertical-align:middle;margin-right:6px}
details{margin:12px 0}summary{cursor:pointer;font-weight:600}a{color:#00697b}footer{padding:24px 0;font-size:14px}
@media(max-width:760px){main{padding:20px 12px}.pair{grid-template-columns:1fr}section{padding:16px}}
"""

# Static script only: fixture JSON/text is never embedded in executable content.
SCRIPT = """
document.querySelectorAll('.panel').forEach(panel => {
 const img=panel.querySelector('img'); if(!img) return;
 const link=panel.querySelector('.full'); let scale=0;
 const resize=()=>{img.style.width=scale ? (img.naturalWidth*scale)+'px' : '';
   img.style.maxWidth=scale ? 'none' : '100%';};
 img.addEventListener('load',resize);
 panel.querySelectorAll('[data-zoom]').forEach(button=>button.addEventListener('click',()=>{
   scale=Number(button.dataset.zoom); resize();
   panel.querySelectorAll('[data-zoom]').forEach(b=>b.setAttribute('aria-pressed',String(b===button)));
 }));
 const guide=panel.querySelector('[data-guides]');
 if(guide) guide.addEventListener('click',()=>{
   const on=guide.getAttribute('aria-pressed')!=='true';
   guide.setAttribute('aria-pressed',String(on));
   img.src=on ? guide.dataset.guides : guide.dataset.clean; link.href=img.src;
   img.alt=on ? 'Debug guides / '+img.dataset.label : 'Clean render / '+img.dataset.label;
 });
 const paper=panel.querySelector('[data-paper]');
 paper.addEventListener('click',()=>{
   const on=paper.getAttribute('aria-pressed')!=='true';
   paper.setAttribute('aria-pressed',String(on)); panel.querySelector('.viewport').classList.toggle('checker',on);
 });
 const pixels=panel.querySelector('[data-pixels]');
 pixels.addEventListener('click',()=>{
     const on=pixels.getAttribute('aria-pressed')!=='true';
     pixels.setAttribute('aria-pressed',String(on)); img.style.imageRendering=on ? 'pixelated' : 'auto';
 });
});
"""


def panel(result):
    label = {"java2d": "Java2D", "magick": "ImageMagick", "suite": "Suite"}[result["engine"]]
    content = [f'<article class="panel"><h3>{label}</h3>',
               f'<p>Fitted size: <strong>{escaped(result.get("fontSize"))} px</strong> · '
               f'Rejection: {escaped(result.get("rejectionType", "none"))}</p>']
    content.append(f'<p class="muted">Fit policy: {escaped(result.get("fittingPolicy", "not recorded"))}</p>')
    readability = result.get("readability")
    if isinstance(readability, dict):
        content.append(f'<p class="banner"><strong>Readability: {escaped(readability.get("status"))}</strong> — {escaped(readability.get("detail"))}</p>')
    image, guides = result.get("image"), result.get("guides")
    # Failed results with a retained image receive exactly the same visual access.
    initial = image or guides
    if initial:
        content.append('<div class="controls">')
        for zoom, title in ((0, "Fit"), (1, "Actual size / 1×"), (2, "2×")):
            content.append(f'<button type="button" data-zoom="{zoom}" aria-pressed="{str(zoom == 0).lower()}">{title}</button>')
        if image and guides:
            content.append(f'<button type="button" data-guides="{href(guides)}" data-clean="{href(image)}" aria-pressed="false">Debug guides</button>')
        content.extend([
            '<button type="button" data-paper aria-pressed="false">Checkerboard</button>',
            '<button type="button" data-pixels aria-pressed="false">Pixel inspection (no smoothing)</button>',
            f'<a class="button full" href="{href(initial)}">Open full resolution</a></div>',
            f'<div class="viewport"><img src="{href(initial)}" data-label="{label}" alt="{label} {"clean render" if image else "debug guides"}"></div>',
            '<p class="muted">Fit shows the entire canvas, without cropping. Pan horizontally at 1×/2×; viewport edges are not image clipping. Zoom normally uses browser smoothing; pixel inspection exposes source pixels, not improved quality. 1× = one image pixel per CSS pixel; browser zoom and device scaling are not native-pixel proof.</p>',
        ])
        if image:
            content.append(f'<a href="{href(image)}">Clean PNG</a> ')
        if guides:
            content.append(f'<a href="{href(guides)}">Debug PNG (orange box / cyan content / overscan)</a>')
    else:
        content.append('<p class="muted">No raster retained for this result; inspect rejection/execution checks below.</p>')
    if result.get("reference"):
        content.append(f'<p><a href="{href(result["reference"])}">Uncropped native annotation reference PNG</a> — transparent overscan, not a fitted box.</p>')
    content.append('<ul class="checks">')
    for check in result["checks"]:
        status = check["status"]
        content.append(f'<li><span class="badge {status}">{status}</span> <strong>{escaped(check["name"])}</strong><p>{escaped(check["detail"])}</p></li>')
    content.append('</ul></article>')
    return "".join(content)


def gallery_html(records, counts):
    csp = "default-src 'none'; img-src 'self'; style-src 'unsafe-inline'; script-src 'unsafe-inline'; connect-src 'none'; base-uri 'none'; form-action 'none'"
    content = ['<!doctype html><html lang="en"><head><meta charset="utf-8">',
               '<meta name="viewport" content="width=device-width,initial-scale=1">',
               f'<meta http-equiv="Content-Security-Policy" content="{escaped(csp)}">',
               f'<title>Native text · visual review</title><style>{CSS}</style></head><body><main>',
               '<header><div class="eyebrow">Native text / retained visual evidence</div><h1>Look closely. Keep the evidence.</h1>',
               '<p class="muted">Generated fixtures · Java2D versus ImageMagick · offline, persistent review</p><div class="counts">']
    for status in STATUSES:
        content.append(f'<span class="badge {status}">{counts[status]} {status}</span>')
    content.append('</div></header><div class="banner"><strong>Not an overall-green or release-readiness claim.</strong> '
                   'Counts describe individual recorded checks, including expected typed rejections. FAIL is retained, not hidden. '
                   'LIMITATION means unproven, not PASS. Native caption internal clipping / differing glyph raster may be a known candidate failure; '
                   'outer overscan alone does not establish complete glyph coverage. These browser previews do not establish Linux hardening, cgroup ceilings, or device acceptance.</div>')
    content.append('<div class="banner"><strong>Separate rendering from layout.</strong> Controlled samples fix font size; other samples use independent auto-fit and are not equal-size sharpness comparisons. Native caption does not apply the requested line-height multiplier. Outline path widths/paint order are not normalized across engines. A fit PASS is not a readability or visual-quality approval.</div>')
    for record in records:
        style = record["style"]
        content.extend([
            f'<section><div class="eyebrow">{escaped(record["id"])}</div><h2>{escaped(record["title"])}</h2>',
            f'<p>Expected rejection: {escaped(record.get("expectedRejection"))} · Box: {escaped(style.get("width"))} × {escaped(style.get("height"))} original pixels · Requested size: {escaped(style.get("requestedFontSize"))} px</p>',
            f'<p><strong>Comparison:</strong> {escaped(style.get("comparisonMode", "legacy independent candidates"))} · <strong>Outline width:</strong> {escaped(style.get("outlineWidth", "not recorded"))} px (0 = disabled)</p>',
            f'<pre aria-label="Expected literal text">{escaped(record.get("text"))}</pre><div class="swatches">',
        ])
        for key in ("color", "outlineColor", "boxFill"):
            color = style.get(key)
            if isinstance(color, str) and re.fullmatch(r"#[0-9a-fA-F]{6}(?:[0-9a-fA-F]{2})?", color):
                content.append(f'<span><span class="swatch" style="background-color:{color}"></span>{key}: <code>{color}</code></span>')
        content.extend([
            '</div><details><summary>Complete expected style / fitted sizes</summary><pre>',
            escaped(json.dumps(style, ensure_ascii=True, indent=2)),
            '</pre></details><div class="pair">',
        ])
        results = {result["engine"]: result for result in record["results"]}
        for engine in ("java2d", "magick", "suite"):
            if engine in results:
                content.append(panel(results[engine]))
            elif engine != "suite" and "suite" not in results:
                content.append(f'<article class="panel"><h3>{engine}</h3><p class="muted">No recorded result. Not a PASS.</p></article>')
        content.append('</div></section>')
    content.append('<footer><a href="visual-manifest.json">Original fixture manifest</a> · <a href="review.json">Copy identities / SHA-256 index</a><p>Private persistent copy; no automatic deletion. PNG signatures checked, not full image decoding. No fonts, user-media directories, or browser reports are copied.</p></footer>')
    content.append(f'</main><script>{SCRIPT}</script></body></html>')
    return "".join(content).encode("utf-8")


def collect_payloads(source, source_fd):
    payloads, identities = {}, {}

    def collect(name, limit, png=False):
        if name not in payloads:
            payloads[name], identities[name] = read_artifact(source, source_fd, name, limit, png)
            if sum(map(len, payloads.values())) > TOTAL_LIMIT or len(payloads) + 2 > FILE_LIMIT:
                raise Failure("Review total byte/file budget exceeded")

    collect("visual-manifest.json", MANIFEST_LIMIT)
    records = manifest_records(payloads["visual-manifest.json"])
    counts = Counter(dict.fromkeys(STATUSES, 0))
    for result in (result for record in records for result in record["results"]):
        counts.update(check["status"] for check in result["checks"])
        for key in ("image", "guides", "reference"):
            if result.get(key) is not None:
                collect(result[key], PNG_LIMIT, png=True)
    for name, limit in OPTIONAL.items():
        try:
            os.stat(name, dir_fd=source_fd, follow_symlinks=False)
        except FileNotFoundError:
            continue
        collect(name, limit)
    return payloads, identities, records, counts


def publish_payloads(parent_fd, output, payloads):
    # Entire admission/preflight completes before the first write. The retained
    # parent fd prevents ancestor replacement from redirecting file publication.
    os.mkdir(output.name, mode=0o700, dir_fd=parent_fd)
    target_fd = os.open(output.name, os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW,
                        dir_fd=parent_fd)
    try:
        os.fchmod(target_fd, 0o700)
        private_directory(target_fd)
        for name, data in payloads.items():
            child = os.open(filename(name), os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW,
                            0o600, dir_fd=target_fd)
            with os.fdopen(child, "wb") as stream:
                os.fchmod(stream.fileno(), 0o600)
                stream.write(data)
                stream.flush()
                os.fsync(stream.fileno())
        os.fsync(target_fd)
    finally:
        os.close(target_fd)


def export_gallery(input_path, output_path):
    source = clean_path(input_path)
    output = clean_path(output_path, existing=False)
    if overlaps(output, REPO) or overlaps(source, output):
        raise Failure("Output must not overlap repository or input")
    if os.path.lexists(output):
        raise Failure("Output must be a fresh directory; no overwrite")
    parent_fd = directory_fd(output.parent)
    source_fd = None
    try:
        private_directory(parent_fd)
        source_fd = directory_fd(source)
        private_directory(source_fd)
        payloads, identities, records, counts = collect_payloads(source, source_fd)
        payloads["index.html"] = gallery_html(records, counts)
        review = {"schema": 1, "input": str(source), "output": str(output),
                  "checkCounts": dict(counts), "fixtures": records,
                  "validation": "PNG signature and bounded stable regular-file read; no decode or correctness inference",
                  "limits": {"pngBytes": PNG_LIMIT, "totalBytes": TOTAL_LIMIT, "files": FILE_LIMIT},
                  "files": [{"name": name, "size": len(data),
                             "sha256": hashlib.sha256(data).hexdigest(),
                             "source": identities.get(name)} for name, data in payloads.items()],
                  "indexScope": "All copied/generated files except this review.json (no circular self-digest)"}
        payloads["review.json"] = encoded_json(review)
        if sum(map(len, payloads.values())) > TOTAL_LIMIT or len(payloads) > FILE_LIMIT:
            raise Failure("Review total byte/file budget exceeded")
        publish_payloads(parent_fd, output, payloads)
        return output / "index.html"
    finally:
        if source_fd is not None:
            os.close(source_fd)
        os.close(parent_fd)


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--input", required=True, help="Absolute private visual-output directory")
    parser.add_argument("--output", required=True, help="Fresh absolute directory outside Git; existing owner-only 0700 parent")
    args = parser.parse_args(argv)
    try:
        if os.name != "posix":
            raise Failure("POSIX filesystem safety is required")
        index = export_gallery(args.input, args.output)
        print(index)
        return 0
    except (OSError, ValueError, RecursionError):
        print("Gallery export rejected or incomplete; verify canonical paths, private parents, manifest and budgets. No automatic cleanup.", file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())
