"""Pure generated-byte tests; no fonts, native rendering, network or real export."""
import contextlib
import hashlib
import importlib.util
import io
import json
import os
from pathlib import Path
import stat
import tempfile
import unittest
from unittest.mock import patch

SPEC = importlib.util.spec_from_file_location("gallery", Path(__file__).with_name("gallery.py"))
gallery = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(gallery)


class GalleryTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(prefix="gallery-unit-")
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name).resolve()
        self.root.chmod(0o700)
        self.source = self.root / "input"
        self.source.mkdir(mode=0o700)
        self.output = self.root / "review"
        self.records = [{"id": "example", "title": 'Caption <script>alert("x")</script>',
                         "text": '\nJoão <img src=x onerror="alert(1)">\n',
                         "expectedRejection": None,
                         "style": {"width": 640, "height": 180, "requestedFontSize": 48,
                                   "color": "#ff0000ff", "outlineColor": "#000000ff",
                                   "boxFill": "#ebdbb1ff", "fontFace": "Sans-Bold",
                                   "fittedFontSizes": {"java2d": 48, "magick": 47}},
                         "results": [self.result("java2d", "PASS"), self.result("magick", "FAIL")]}]
        for result in self.records[0]["results"]:
            for key in ("image", "guides"):
                (self.source / result[key]).write_bytes(gallery.PNG_SIGNATURE + b"generated test bytes")
        self.manifest()

    @staticmethod
    def result(engine, status):
        return {"engine": engine, "image": engine + '.png', "guides": engine + '-guides.png',
                "fontSize": 48, "checks": [
                    {"name": "coverage", "status": status, "detail": '"known" <script>bad</script>'},
                    {"name": "line-height", "status": "LIMITATION", "detail": "Unproven"}]}

    def manifest(self):
        (self.source / "visual-manifest.json").write_text(json.dumps(self.records), encoding="utf-8")

    def export(self):
        return gallery.export_gallery(str(self.source), str(self.output))

    def reject(self):
        with self.assertRaises((gallery.Failure, OSError)):
            self.export()
        self.assertFalse(self.output.exists(), "Admission must finish before writes")

    def test_export_pairs_failed_artifacts_hashes_and_permissions(self):
        (self.source / "metadata.properties").write_bytes(b"java=21\n")
        (self.source / "external-metrics.json").write_text('{"succeeded":false,"sourceSha256":{"VisualCorrectnessPoc.java":"abc"}}')
        (self.source / "visual-checks.csv").write_text("fixture,engine,check,status,detail\n")
        for name in ("font.ttf", "secret.env", ".env", "browser-report.html", "user.png"):
            (self.source / name).write_bytes(b"DO NOT COPY")
        index = self.export()
        review = json.loads((self.output / "review.json").read_bytes())
        self.assertEqual(review["checkCounts"], {"PASS": 1, "FAIL": 1, "LIMITATION": 2})
        self.assertEqual(review["fixtures"], self.records)
        self.assertEqual(stat.S_IMODE(self.output.stat().st_mode), 0o700)
        expected = {"index.html", "review.json", "visual-manifest.json", "metadata.properties",
                    "external-metrics.json", "visual-checks.csv", "java2d.png", "magick.png",
                    "java2d-guides.png", "magick-guides.png"}
        self.assertEqual({p.name for p in self.output.iterdir()}, expected)
        for entry in review["files"]:
            path = self.output / entry["name"]
            self.assertEqual(stat.S_IMODE(path.stat().st_mode), 0o600)
            self.assertEqual(len(path.read_bytes()), entry["size"])
            self.assertEqual(hashlib.sha256(path.read_bytes()).hexdigest(), entry["sha256"])
            if entry["source"]:
                self.assertEqual(entry["source"]["sha256"], entry["sha256"])
                self.assertEqual(entry["source"]["path"], str(self.source / entry["name"]))
        page = index.read_text()
        for marker in ("Java2D", "ImageMagick", "Debug guides", "Actual size / 1×",
                       "2×", "Checkerboard", "Open full resolution", "Not an overall-green",
                       "#ff0000ff", "internal clipping", "browser zoom"):
            self.assertIn(marker, page)
        self.assertEqual((self.output / "magick.png").read_bytes(), (self.source / "magick.png").read_bytes())

    def test_html_labels_attributes_and_style_escape(self):
        self.records[0]["style"]["color"] = 'red; background:url(https://evil/)'
        self.records[0]["style"]["fontFace"] = '</pre><script>evil()</script>'
        unusual = 'java "quote" #?.png'
        (self.source / unusual).write_bytes(gallery.PNG_SIGNATURE)
        self.records[0]["results"][0]["image"] = unusual
        self.manifest()
        page = self.export().read_text()
        self.assertNotIn('<script>alert(', page)
        self.assertNotIn('<script>bad', page)
        self.assertNotIn('<script>evil', page)
        self.assertIn('&lt;script&gt;', page)
        self.assertIn('&quot;x&quot;', page)
        self.assertIn('java%20%22quote%22%20%23%3F.png', page)
        self.assertNotIn('style="background-color:red;', page)
        self.assertIn("connect-src &#x27;none&#x27;", page)
        self.assertEqual(page.count('<script>'), 1)

    def test_uncropped_reference_is_copied_and_linked(self):
        name = "magick-reference.png"
        data = gallery.PNG_SIGNATURE + b"uncropped reference"
        (self.source / name).write_bytes(data)
        self.records[0]["results"][1]["reference"] = name
        self.manifest()
        page = self.export().read_text()
        self.assertEqual((self.output / name).read_bytes(), data)
        self.assertIn('href="magick-reference.png"', page)
        self.assertIn("Uncropped native annotation reference PNG", page)

    def test_reference_cannot_bypass_artifact_guards(self):
        self.records[0]["results"][1]["reference"] = "../escape.png"
        self.manifest()
        self.reject()

    def test_fit_is_not_readability_and_zoom_is_explicit(self):
        self.records[0]["results"][0].update({"fontSize": 10, "fittingPolicy": "fixed-size <script>bad</script>",
            "readability": {"status": "WARNING", "detail": "Shorten text <script>bad</script>"}})
        self.records[0]["style"].update({"outlineWidth": 0, "comparisonMode": "fixed-size"})
        self.manifest()
        page = self.export().read_text()
        for marker in ("Readability: WARNING", "Pixel inspection (no smoothing)", "Pan horizontally",
                       "not a readability", "0 px (0 = disabled)", "Comparison:</strong> fixed-size"):
            self.assertIn(marker, page)
        self.assertNotIn("<script>bad", page)
        self.assertIn("imageRendering=on ? 'pixelated' : 'auto'", page)

    def test_malformed_utf16_and_suite_error_are_retained(self):
        self.records = [{"id": "suite-error", "title": "Interrupted \ud800", "text": None,
                         "style": {}, "results": [{"engine": "suite", "checks": [
                             {"name": "execution", "status": "FAIL", "detail": "Interrupted"}],
                             "image": None, "guides": None}]}]
        self.manifest()
        page = self.export().read_text()
        self.assertIn("No raster retained", page)
        self.assertIn("\\ud800", page)

    def test_unsafe_filenames(self):
        for name in ("../bad.png", "/bad.png", "../../.env", ".env", "a/b.png",
                     "a\\b.png", "x:bad.png", "index.html", "a\x00.png"):
            with self.subTest(name=name):
                self.records[0]["results"][0]["image"] = name
                self.manifest()
                self.reject()

    def test_symlink_source_artifact_and_optional_metadata(self):
        path = self.source / "java2d.png"
        path.unlink()
        path.symlink_to(self.source / "magick.png")
        self.reject()
        path.unlink()
        path.write_bytes(gallery.PNG_SIGNATURE)
        (self.source / "metadata.properties").symlink_to(self.source / "visual-manifest.json")
        self.reject()

    def test_symlink_manifest_and_ancestor(self):
        path = self.source / "visual-manifest.json"
        data = path.read_bytes()
        path.unlink()
        (self.root / "other.json").write_bytes(data)
        path.symlink_to(self.root / "other.json")
        self.reject()
        path.unlink()
        path.write_bytes(data)
        alias = self.root / "alias"
        alias.symlink_to(self.root, target_is_directory=True)
        with self.assertRaises(gallery.Failure):
            gallery.export_gallery(str(alias / "input"), str(self.output))
        with self.assertRaises(gallery.Failure):
            gallery.export_gallery(str(self.source), str(alias / "review"))

    def test_noncanonical_overlap_and_repository_rejection(self):
        for output in (str(self.source / "child"), str(self.root), str(self.root / "missing" / "review"),
                       str(self.root) + "/../review", str(self.root) + "/./review", "relative"):
            with self.subTest(output=output), self.assertRaises((gallery.Failure, OSError)):
                gallery.export_gallery(str(self.source), output)
        # Exercise the real REPO policy without reading .gitignore or writing there.
        with self.assertRaises(gallery.Failure):
            gallery.export_gallery(str(self.source), str(gallery.REPO / "gallery-unit-blocked"))

    def test_private_parent_source_permissions_and_owner(self):
        self.root.chmod(0o750)
        self.reject()
        self.root.chmod(0o700)
        self.source.chmod(0o755)
        self.reject()
        self.source.chmod(0o700)
        with patch.object(gallery.os, "getuid", return_value=os.getuid() + 1):
            self.reject()

    def test_existing_output_never_overwritten(self):
        self.output.mkdir(mode=0o700)
        sentinel = self.output / "index.html"
        sentinel.write_bytes(b"keep")
        with self.assertRaises(gallery.Failure):
            self.export()
        self.assertEqual(sentinel.read_bytes(), b"keep")

    def test_nonregular_and_missing_png(self):
        path = self.source / "java2d.png"
        path.unlink()
        path.mkdir()
        self.reject()
        path.rmdir()
        os.mkfifo(path, 0o600)
        self.reject()
        path.unlink()
        self.reject()

    def test_png_signature_and_file_budget(self):
        path = self.source / "java2d.png"
        path.write_bytes(b"not a PNG")
        self.reject()
        path.write_bytes(gallery.PNG_SIGNATURE + b"X")
        with patch.object(gallery, "PNG_LIMIT", 8):
            self.reject()
        with patch.object(gallery, "FILE_LIMIT", 4):
            self.reject()

    def test_total_and_metadata_manifest_size_budgets(self):
        with patch.object(gallery, "TOTAL_LIMIT", 16):
            self.reject()
        with patch.object(gallery, "MANIFEST_LIMIT", 8):
            self.reject()
        # Generated HTML/review must also fit before any directory is created.
        with patch.object(gallery, "TOTAL_LIMIT", 9000):
            self.reject()
        (self.source / "metadata.properties").write_bytes(b"X" * (256 * 1024 + 1))
        self.reject()

    def test_manifest_root_and_status_validation(self):
        for records in ({"fixtures": self.records}, [], [None]):
            (self.source / "visual-manifest.json").write_text(json.dumps(records))
            self.reject()
        self.records[0]["results"][0]["checks"][0]["status"] = "OVERALL_GREEN"
        self.manifest()
        self.reject()

    def test_cli_redacts_rejection(self):
        stderr = io.StringIO()
        with contextlib.redirect_stderr(stderr):
            self.assertEqual(gallery.main(["--input", "secret-relative-path", "--output", str(self.output)]), 1)
        self.assertNotIn("secret-relative-path", stderr.getvalue())


if __name__ == "__main__":
    unittest.main()
