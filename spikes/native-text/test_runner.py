"""Pure unittest/mocks: no real fonts, downloads, JDK, ps or native renderer."""
import argparse
import hashlib
import io
import json
import os
from pathlib import Path
import signal
import subprocess
import tarfile
import tempfile
import time
import unittest
from unittest.mock import Mock, patch

import runner


class RunnerTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(dir=Path(tempfile.gettempdir()).resolve())
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.fonts = self.root / "fonts"
        self.fonts.mkdir(mode=0o700)
        self.payloads = {name: (name + " fixture").encode() for name in runner.FONT_HASHES}
        self.hashes = {name: hashlib.sha256(data).hexdigest() for name, data in self.payloads.items()}
        for name, data in self.payloads.items():
            (self.fonts / name).write_bytes(data)

    def archive(self, extra=None):
        buffer = io.BytesIO()
        with tarfile.open(fileobj=buffer, mode="w:gz") as archive:
            for name, data in self.payloads.items():
                member = tarfile.TarInfo("release/" + name)
                member.size = len(data)
                archive.addfile(member, io.BytesIO(data))
            if extra is not None:
                archive.addfile(extra, io.BytesIO(b"\0" * extra.size) if extra.size else None)
        return buffer.getvalue()

    def test_output_guards(self):
        self.assertEqual(runner.new_output(self.root / "new", self.fonts), self.root / "new")
        for path in (self.fonts, self.fonts / "nested", self.root, Path("relative"), Path("/outside/new")):
            with self.subTest(path=path), self.assertRaises(runner.Failure):
                runner.new_output(path, self.fonts)
        with patch.object(runner, "REPO", self.root / "repo"):
            with self.assertRaises(runner.Failure):
                runner.new_output(self.root / "repo")
        (self.root / "link").symlink_to(self.fonts, target_is_directory=True)
        (self.root / "broken").symlink_to(self.root / "absent")
        for path in (self.root / "link/new", self.root / "broken"):
            with self.assertRaises(runner.Failure):
                runner.new_output(path)
        shared = self.root / "shared"
        shared.mkdir(mode=0o755)
        with self.assertRaises(runner.Failure):
            runner.new_output(shared / "new")

    def test_fonts_hash_size_links_and_missing(self):
        with patch.object(runner, "FONT_HASHES", self.hashes):
            self.assertEqual(runner.validate_fonts(self.fonts), self.fonts)
            font = self.fonts / next(iter(self.hashes))
            font.write_bytes(b"wrong")
            with self.assertRaises(runner.Failure):
                runner.validate_fonts(self.fonts)
            with self.assertRaises(runner.Failure):
                runner.verify_bytes(font.name, b"x" * (2 * runner.MIB + 1))
            font.unlink()
            font.symlink_to(self.fonts / "LICENSE")
            with self.assertRaises(runner.Failure):
                runner.validate_fonts(self.fonts)
            font.unlink()
            with self.assertRaises(runner.Failure):
                runner.validate_fonts(self.fonts)

    def test_archive_validation_and_exact_license(self):
        safe = self.archive()
        with patch.object(runner, "FONT_HASHES", self.hashes), patch.object(
                runner, "ARCHIVE_SHA256", hashlib.sha256(safe).hexdigest()):
            self.assertEqual(runner.archive_fonts(safe), self.payloads)
            for data in (safe + b"changed", b"x" * (10 * runner.MIB + 1)):
                with self.assertRaises(runner.Failure):
                    runner.archive_fonts(data)
        for name, kind, size in (("/absolute", tarfile.DIRTYPE, 0),
                                 ("a/../escape", tarfile.DIRTYPE, 0),
                                 ("link", tarfile.SYMTYPE, 0), ("hard", tarfile.LNKTYPE, 0),
                                 ("pax", tarfile.XHDTYPE, 0),
                                 ("pipe", tarfile.FIFOTYPE, 0),
                                 ("large", tarfile.REGTYPE, 20 * runner.MIB + 1)):
            entry = tarfile.TarInfo(name)
            entry.type, entry.size = kind, size
            data = self.archive(entry)
            with self.subTest(name=name), patch.object(runner, "FONT_HASHES", self.hashes), patch.object(
                    runner, "ARCHIVE_SHA256", hashlib.sha256(data).hexdigest()), self.assertRaises(runner.Failure):
                runner.archive_fonts(data)

    def test_download_and_prepare_mock_only(self):
        archive = self.archive()
        response = Mock()
        response.__enter__ = Mock(return_value=response)
        response.__exit__ = Mock(return_value=False)
        response.read.side_effect = [archive, b""]
        opener = Mock()
        opener.open.return_value = response
        with patch.object(runner.urllib.request, "build_opener", return_value=opener):
            self.assertEqual(runner.download_archive(time.monotonic() + 5), archive)
            opener.open.assert_called_once_with(runner.ARCHIVE_URL, timeout=60)
        output = self.root / "prepared"
        with patch.object(runner, "download_archive", return_value=archive), patch.object(
                runner, "FONT_HASHES", self.hashes), patch.object(
                runner, "ARCHIVE_SHA256", hashlib.sha256(archive).hexdigest()), patch("builtins.print"):
            self.assertEqual(runner.main(["prepare-fonts", "--output", str(output)]), 0)
        self.assertEqual((output / "LICENSE").read_bytes(), self.payloads["LICENSE"])
        self.assertEqual(output.stat().st_mode & 0o777, 0o700)

    def test_version_and_environment(self):
        for version in ('openjdk version "21.0.9"', 'java version "21"', 'openjdk version "21-ea"'):
            self.assertEqual(runner.parse_java_version(version), 21)
        for version in ('openjdk version "22.0.1"', 'java version "1.8.0"', 'junk 21'):
            with self.assertRaises(runner.Failure):
                runner.parse_java_version(version)
        with patch.dict(os.environ, {"JAVA_TOOL_OPTIONS": "secret", "_JAVA_OPTIONS": "secret",
                                     "REGATA_SIMULATOR_ENC_PASSWORD": "secret", "CLASSPATH": "secret"}):
            env = runner.child_env(Path("/jdk"), self.root, Path("/native/magick"))
        self.assertEqual(set(env), {"PATH", "LANG", "HOME", "TMPDIR"})
        self.assertNotIn("secret", str(env))
        self.assertEqual(env["PATH"], "/jdk/bin:/native:/usr/bin:/bin:/usr/sbin:/sbin")

    def test_rss_descendants_and_missing(self):
        self.assertEqual(runner.tree_rss("0 0 5\n1 0 90\n4 1 100\n5 4 30\n6 5 40\n9 1 999", 4), 170)
        for text in ("", "1 0 90", "4 1 invalid", "4 1 5\n4 1 6", "4 1 -5"):
            self.assertIsNone(runner.tree_rss(text, 4))
        with patch.object(runner.shutil, "which", return_value=None):
            sampler = runner.Sampler({"PATH": "/bin"}, self.root, 10, True)
            sampler.start(4)
            sampler.stop()
            self.assertIsNone(sampler.peak)

    def test_sampler_failed_ps_mock_only(self):
        with patch.object(runner.shutil, "which", return_value="/mock/ps"), patch.object(
                runner, "run_child", side_effect=runner.Failure("mock ps failure")):
            sampler = runner.Sampler({"PATH": "/bin"}, self.root, 10, True)
            sampler.start(4)
            sampler.thread.join(1)
            sampler.stop()
            self.assertIsNone(sampler.peak)
            self.assertIn("unavailable", sampler.reason)

    def test_cli_rejects_missing_output_and_invalid_rounds(self):
        invalid = ["run", "--java-home", "/jdk", "--fonts", "/fonts", "--magick", "/magick",
                   "--output", str(self.root / "new"), "--profile", "smoke", "--rounds", "6"]
        with patch.object(runner, "launch") as launch, patch("sys.stderr", new=io.StringIO()):
            for argv in (["prepare-fonts"], invalid):
                with self.assertRaises(SystemExit):
                    runner.main(argv)
            launch.assert_not_called()

    def test_process_management_mock_only(self):
        for outcome in (None, subprocess.TimeoutExpired("redacted", 1), KeyboardInterrupt()):
            process = Mock(pid=123, returncode=0, stdout=io.BytesIO(b"ok"), stderr=io.BytesIO(b"err"))
            process.wait.side_effect = [outcome, None] if outcome else None
            deadline = time.monotonic() + 2
            error_type = KeyboardInterrupt if isinstance(outcome, KeyboardInterrupt) else runner.Failure
            with patch.object(runner.subprocess, "Popen", return_value=process) as popen, patch.object(
                    runner.os, "killpg") as kill:
                if outcome:
                    with self.assertRaises(error_type):
                        runner.run_child(["mock-java"], {"PATH": "/bin"}, self.root, deadline)
                else:
                    self.assertEqual(runner.run_child(["mock-java"], {}, self.root, time.monotonic() + 2)[0], ("ok", "err"))
                self.assertTrue(popen.call_args.kwargs["start_new_session"])
                self.assertEqual(popen.call_args.kwargs["stdin"], subprocess.DEVNULL)
                kill.assert_called_once_with(123, signal.SIGKILL)

    def test_launch_mock_compile_and_metrics(self):
        output = self.root / "results"
        args = argparse.Namespace(command="run", fonts=str(self.fonts), java_home=str(self.root),
                                  magick="/native/magick", output=str(output), profile="smoke", rounds=1, sample_rss=False)
        calls = []

        def child(argv, *unused, **kwargs):
            calls.append(argv)
            if "com.boatarde.regatasimulator.spike.NativeTextPoc" in argv:
                output.mkdir(mode=0o700)
            return (("", 'openjdk version "21.0.9"'), [False, False])

        real_clean_path = runner.clean_path
        def clean(path, **kwargs):
            return Mock(is_file=lambda: True) if str(path).endswith(".java") else real_clean_path(path, **kwargs)
        with patch.object(runner, "FONT_HASHES", self.hashes), patch.object(
            runner, "executable", lambda path: Path(path)), patch.object(runner, "clean_path", side_effect=clean), patch.object(
            runner, "run_child", side_effect=child), patch("builtins.print"):
            runner.launch(args, time.monotonic() + 30)
        self.assertIn("--release", calls[1])
        self.assertEqual(len([value for value in calls[1] if value.endswith(".java")]), 2)
        self.assertEqual(calls[2][-6:], ["run", "/native/magick", str(self.fonts), str(output), "smoke", "1"])
        self.assertIn("-Xmx256m", calls[2])
        metrics = json.loads((output / "external-metrics.json").read_text())
        self.assertIsNone(metrics["peakSampledTreeRssKiB"])
        self.assertIn("not a cgroup", metrics["warning"])
        with self.assertRaises(FileExistsError):
            runner.private_write(output, "external-metrics.json", b"replacement")


if __name__ == "__main__":
    unittest.main()