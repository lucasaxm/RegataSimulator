#!/usr/bin/env python3
"""Opt-in, stdlib-only launcher for the separately supplied NativeTextPoc.java."""
import argparse
import gzip
import hashlib
import io
import json
import os
from pathlib import Path, PurePosixPath
import re
import shutil
import signal
import stat
import subprocess
import sys
import tarfile
import tempfile
import threading
import time
import urllib.request

HERE = Path(__file__).resolve().parent
REPO = HERE.parent.parent
ARCHIVE_URL = "https://github.com/liberationfonts/liberation-fonts/files/7261482/liberation-fonts-ttf-2.1.5.tar.gz"
ARCHIVE_SHA256 = "7191c669bf38899f73a2094ed00f7b800553364f90e2637010a69c0e268f25d0"
FONT_HASHES = {
    "LiberationSans-Regular.ttf": "76d04c18ea243f426b7de1f3ad208e927008f961dc5945e5aad352d0dfde8ee8",
    "LiberationSans-Bold.ttf": "788abee4c806d660e8aee46689dd8540cd4bb98da03dcc9d171ce3efd99a9173",
    "LiberationSerif-Regular.ttf": "058ea80864aef09a23f45cbec2bb5400bc3dfbdea01c3f10538a21fcb497fb74",
    "LiberationSerif-Bold.ttf": "d754ba427cfe0bca54ae052384baa8f842da5bd6550ad4da024ac441e7a7d5ce",
    "LiberationMono-Regular.ttf": "f2b83c763e8afd21709333370bed4774337fae82267937e2b5aea7e2fbd922c1",
    "LiberationMono-Bold.ttf": "bd62a0672d0b9b6710b01df434c80ad54fa5f0835207eb7b17b7a761463067bb",
    "LICENSE": "93fed46019c38bbe566b479d22148e2e8a1e85ada614accb0211c37b2c61c19b",
}
MIB = 1024 * 1024
RSS_WARNING = ("Sampled descendant RSS may double-count shared pages and miss transient processes; "
               "not a cgroup peak or an enforced ceiling. Compilation is excluded.")


class Failure(Exception):
    def __init__(self, message, diagnostic=""):
        super().__init__(message)
        self.diagnostic = diagnostic


def clean_path(value, existing=True):
    path = Path(value)
    if not path.is_absolute() or ".." in path.parts:
        raise Failure("An absolute, canonical path is required")
    for part in (path, *path.parents):
        if part.is_symlink():
            raise Failure("Symlink ancestry is not allowed")
    if existing and not path.exists():
        raise Failure("Required path does not exist")
    return path


def overlaps(left, right):
    return left == right or left in right.parents or right in left.parents


def new_output(value, fonts=None):
    path = clean_path(value, existing=False)
    # Roots are admission boundaries, not writable targets: exclusive 0700 creation,
    # rejected symlink ancestry and fd-relative O_EXCL/0600 publication protect writes.
    roots = {Path("/tmp").resolve(), Path(tempfile.gettempdir()).resolve()}
    if not any(root in path.parents for root in roots):
        raise Failure("Output must be strictly beneath a resolved temporary root")
    if overlaps(path, REPO) or (fonts is not None and overlaps(path, fonts)):
        raise Failure("Output overlaps repository or fonts")
    if os.path.lexists(path) or not path.parent.is_dir():
        raise Failure("Output must be new with an existing directory parent")
    parent = path.parent.stat()
    if parent.st_uid != os.getuid() or parent.st_mode & 0o077:
        raise Failure("Output parent must already be owner-only and owned by this user")
    return path


def verify_bytes(name, data):
    limit = 64 * 1024 if name == "LICENSE" else 2 * MIB
    if not 0 < len(data) <= limit:
        raise Failure("Font artifact size is out of bounds")
    if hashlib.sha256(data).hexdigest() != FONT_HASHES[name]:
        raise Failure("Pinned font artifact digest mismatch")


def validate_fonts(value):
    fonts = clean_path(value)
    if not fonts.is_dir():
        raise Failure("Fonts must be a directory")
    for name in FONT_HASHES:
        path = clean_path(fonts / name)
        if not stat.S_ISREG(path.lstat().st_mode):
            raise Failure("Font must be a regular file")
        with os.fdopen(os.open(path, os.O_RDONLY | os.O_NOFOLLOW | os.O_NONBLOCK), "rb") as stream:
            info = os.fstat(stream.fileno())
            limit = 64 * 1024 if name == "LICENSE" else 2 * MIB
            if not stat.S_ISREG(info.st_mode) or not 0 < info.st_size <= limit:
                raise Failure("Font must be a bounded regular file")
            verify_bytes(name, stream.read(limit + 1))
    return fonts


def private_write(directory, name, data):
    """Publish a complete 0600 file atomically, without replacing any target."""
    if not name or name in (".", "..") or Path(name).name != name:
        raise Failure("Artifact name must be a plain filename")
    directory = clean_path(directory)
    fd = os.open(directory, os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW)
    temporary = ".write-" + os.urandom(16).hex()
    created = False
    try:
        info = os.fstat(fd)
        if info.st_uid != os.getuid() or info.st_mode & 0o077:
            raise Failure("Artifact directory must be owner-only and owned by this user")
        child = os.open(temporary, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600, dir_fd=fd)
        created = True
        with os.fdopen(child, "wb") as stream:
            stream.write(data)
            stream.flush()
            os.fsync(stream.fileno())
        os.link(temporary, name, src_dir_fd=fd, dst_dir_fd=fd, follow_symlinks=False)
    finally:
        if created:
            os.unlink(temporary, dir_fd=fd)
        os.close(fd)


def download_archive(deadline):
    # A daemon reader bounds wall time as well as urllib's per-socket timeout.
    result = []
    stop = threading.Event()
    end = min(deadline, time.monotonic() + 60)

    def read():
        try:
            # No environment proxy credentials and no global opener mutation.
            opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
            with opener.open(ARCHIVE_URL, timeout=60) as response:
                data = bytearray()
                while not stop.is_set() and time.monotonic() < end:
                    block = response.read(min(65536, 10 * MIB + 1 - len(data)))
                    if not block:
                        result.append(bytes(data))
                        return
                    data.extend(block)
                    if len(data) > 10 * MIB:
                        break
        except Exception:
            pass  # Never expose response URLs, headers or arbitrary network errors.

    worker = threading.Thread(target=read, daemon=True)
    worker.start()
    try:
        worker.join(max(0, end - time.monotonic()))
    finally:
        stop.set()
    if not result:
        raise Failure("Font download failed, exceeded 10 MiB, or exceeded 60 seconds")
    return result[0]


def tar_members(unpacked):
    # Inspect raw headers too: tarfile iteration hides PAX/GNU indirection entries.
    offset, total, count = 0, 0, 0
    while offset + 512 <= len(unpacked):
        header = unpacked[offset:offset + 512]
        if not any(header):
            break
        entry = tarfile.TarInfo.frombuf(header, "utf-8", "strict")
        path = PurePosixPath(entry.name)
        if (not entry.name or path.is_absolute() or ".." in path.parts
                or "\\" in entry.name or ":" in entry.name
                or not (entry.isfile() or entry.isdir()) or entry.size < 0):
            raise Failure("Unsafe archive entry")
        count, total = count + 1, total + entry.size
        if count > 256 or total > 20 * MIB:
            raise Failure("Archive entry/content budget exceeded")
        start = offset + 512
        if start + entry.size > len(unpacked):
            raise Failure("Truncated tar entry")
        offset = start + ((entry.size + 511) // 512) * 512
        yield entry, unpacked[start:start + entry.size]
    if len(unpacked) - offset < 1024 or any(unpacked[offset:]):
        raise Failure("Missing or invalid tar terminator")


def archive_fonts(data):
    if len(data) > 10 * MIB or hashlib.sha256(data).hexdigest() != ARCHIVE_SHA256:
        raise Failure("Pinned archive size or digest mismatch (not signature authentication)")
    with gzip.GzipFile(fileobj=io.BytesIO(data)) as compressed:
        unpacked = compressed.read(21 * MIB + 1)
    if len(unpacked) > 21 * MIB:
        raise Failure("Uncompressed archive exceeds its budget")
    selected = {}
    for entry, contents in tar_members(unpacked):
        name = PurePosixPath(entry.name).name
        if name in FONT_HASHES:
            if name in selected or not entry.isfile():
                raise Failure("Duplicate or non-file font artifact")
            verify_bytes(name, contents)
            selected[name] = contents
    if selected.keys() != FONT_HASHES.keys():
        raise Failure("Archive is missing pinned font artifacts")
    return selected


def child_env(jdk, work, magick=None):
    paths = [str(jdk / "bin")]
    if magick is not None:
        paths.append(str(magick.parent))
    paths.extend(("/usr/bin", "/bin", "/usr/sbin", "/sbin"))
    return {"PATH": os.pathsep.join(paths), "LANG": "en_US.UTF-8",
            "HOME": str(work), "TMPDIR": str(work)}


def kill_group(process):
    try:
        os.killpg(process.pid, signal.SIGKILL)
    except ProcessLookupError:
        pass


def run_child(argv, env, cwd, deadline, limit=65536, sampler=None):
    remaining = deadline - time.monotonic()
    if remaining <= 0:
        raise Failure("Whole-job deadline exceeded")
    retained = [bytearray(), bytearray()]
    oversized = [False, False]
    readers = []

    def drain(stream, index):
        with stream:
            while block := stream.read(8192):
                available = (limit if index == 0 else 65536) - len(retained[index])
                oversized[index] |= len(block) > available
                retained[index].extend(block[:available])

    process = subprocess.Popen(argv, cwd=cwd, env=env, stdin=subprocess.DEVNULL,
                               stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                               start_new_session=True)
    try:
        for index, stream in enumerate((process.stdout, process.stderr)):
            reader = threading.Thread(target=drain, args=(stream, index), daemon=True)
            reader.start()
            readers.append(reader)
        if sampler is not None:
            sampler.start(process.pid)
        try:
            process.wait(timeout=max(0.001, deadline - time.monotonic()))
        except subprocess.TimeoutExpired:
            raise Failure("Child exceeded whole-job deadline") from None
    finally:
        kill_group(process)  # Also removes lingering descendants after a normal exit.
        try:
            process.wait(timeout=0.5)
        except subprocess.TimeoutExpired:
            pass
        if sampler is not None:
            sampler.stop()
        for reader in readers:
            reader.join(0.25)
    if process.returncode != 0:
        raise Failure("Child failed with exit code %s" % process.returncode,
                      bytes(retained[1]).decode("utf-8", "replace"))
    if any(reader.is_alive() for reader in readers):
        raise Failure("Child output drain did not finish")
    return tuple(bytes(value).decode("utf-8", "replace") for value in retained), oversized


def parse_java_version(text):
    match = re.search(r'(?m)^(?:openjdk|java) version "(\d+)(?:[.\-+][^"\n]*)?"', text)
    if not match or int(match[1]) != 21:
        raise Failure("Explicit JDK must report Java 21")
    return 21


def tree_rss(text, root):
    table = {}
    for line in text.splitlines():
        fields = line.split()
        if len(fields) != 3 or not all(field.isdigit() for field in fields):
            return None
        try:
            pid, parent, rss = map(int, fields)
        except ValueError:
            return None
        if pid in table:
            return None
        table[pid] = (parent, rss)
    if root not in table:
        return None
    included = {root}
    while children := {pid for pid, (parent, _) in table.items() if parent in included} - included:
        included.update(children)
    return sum(table[pid][1] for pid in included)


class Sampler:
    def __init__(self, env, work, deadline, enabled):
        self.env, self.work, self.deadline = env, work, deadline
        self.ps = shutil.which("ps", path=env["PATH"]) if enabled else None
        self.event, self.thread, self.peak = threading.Event(), None, None
        self.reason = "disabled" if not enabled else "ps unavailable or no valid samples"

    def start(self, pid):
        if not self.ps:
            return

        def sample():
            while not self.event.is_set():
                try:
                    (stdout, _), overflow = run_child(
                        [self.ps, "-axo", "pid=,ppid=,rss="], self.env, self.work,
                        min(self.deadline, time.monotonic() + 0.5), limit=2 * MIB)
                    value = None if any(overflow) else tree_rss(stdout, pid)
                    if value is not None:
                        self.peak = max(self.peak or 0, value)
                        self.reason = "sampled"
                except (Failure, OSError):
                    self.peak, self.reason = None, "ps failed; metrics unavailable"
                    return
                self.event.wait(0.05)

        self.thread = threading.Thread(target=sample, daemon=True)
        self.thread.start()

    def stop(self):
        self.event.set()
        if self.thread is not None:
            self.thread.join(1.5)
            if self.thread.is_alive():
                self.peak, self.reason = None, "sampler did not finish"


def executable(value):
    path = Path(value)
    if not path.is_absolute():
        raise Failure("Executable/JDK paths must be absolute")
    path = path.resolve(strict=True)
    if not path.is_file() or not os.access(path, os.X_OK):
        raise Failure("Required executable is unavailable")
    return path


def launch(args, deadline):
    fonts = validate_fonts(args.fonts)  # Before version probing, compilation or native work.
    output = new_output(args.output, fonts) if args.command == "run" else None
    home = Path(args.java_home)
    if not home.is_absolute():
        raise Failure("JDK home must be absolute")
    home = home.resolve(strict=True)
    java, javac = executable(home / "bin/java"), executable(home / "bin/javac")
    magick = executable(args.magick) if output is not None else None
    sources = [REPO / "src/main/java/com/boatarde/regatasimulator/util/ProcessRunner.java",
               HERE / "NativeTextPoc.java", HERE / "VisualCorrectnessPoc.java",
               HERE / "MagickCaptionFitter.java", HERE / "MagickCaptionFitterTest.java"]
    if any(not clean_path(source).is_file() for source in sources):
        raise Failure("Standalone Java sources must be regular files")
    with tempfile.TemporaryDirectory(prefix="native-text-", dir=Path(tempfile.gettempdir()).resolve()) as temporary:
        work = clean_path(temporary)
        env = child_env(home, work, magick)
        (stdout, stderr), _ = run_child([str(java), "-version"], env, work, deadline)
        parse_java_version(stdout + "\n" + stderr)
        classes = work / "classes"
        classes.mkdir(mode=0o700)
        run_child([str(javac), "--release", "21", "-d", str(classes),
                   *map(str, sources)], env, work, deadline)
        argv = [str(java), "-Djava.awt.headless=true", "-Xms64m", "-Xmx256m",
            "-cp", str(classes), "com.boatarde.regatasimulator.spike.NativeTextPoc"]
        argv += (["self-test", str(fonts)] if output is None else
                 ["run", str(magick), str(fonts), str(output), args.profile, str(args.rounds)])
        sampler = Sampler(env, work, deadline, output is not None and args.sample_rss)
        started = time.monotonic()
        succeeded = False
        try:
            (stdout, _), truncated = run_child(argv, env, work, deadline, sampler=sampler)
            succeeded = True
            print(stdout, end="" if stdout.endswith("\n") else "\n")
            if truncated[0]:
                print("[native stdout truncated at 64 KiB]")
        except Failure as error:
            # The child receives only isolated environment/generated fixtures. Never
            # publish diagnostics to chat or a production API; retain <=64 KiB privately.
            if output is not None and output.is_dir() and error.diagnostic:
                private_write(output, "child-stderr.txt", error.diagnostic.encode("utf-8")[:65536])
            raise
        finally:
            elapsed = time.monotonic() - started
            if output is not None and os.path.lexists(output):
                metrics = {"elapsedSeconds": elapsed, "peakSampledTreeRssKiB": sampler.peak,
                           "samplesIntervalMs": 50, "samplingStatus": sampler.reason,
                           "succeeded": succeeded, "warning": RSS_WARNING,
                           "successMeaning": "Child completed; inspect checks.csv for CAPACITY_REJECTED and visual-checks.csv for FAIL/LIMITATION. Not release readiness.",
                           "sourceSha256": {path.name: hashlib.sha256(path.read_bytes()).hexdigest()
                                            for path in [*sources, Path(__file__)]}}
                private_write(output, "external-metrics.json", json.dumps(metrics, indent=2).encode() + b"\n")
        if output is not None and not output.is_dir():
            raise Failure("Java did not create its output directory")


def interrupt(*_):
    raise KeyboardInterrupt()


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)
    prepare = commands.add_parser("prepare-fonts", help="Opt-in pinned download; no system font installation")
    prepare.add_argument("--output", required=True)
    for command in ("self-test", "run"):
        child = commands.add_parser(command)
        child.add_argument("--java-home", required=True)
        child.add_argument("--fonts", required=True)
        if command == "run":
            child.add_argument("--magick", required=True)
            child.add_argument("--output", required=True)
            child.add_argument("--profile", required=True, choices=("smoke", "boundary", "visual"))
            child.add_argument("--rounds", required=True, type=int, choices=range(1, 6))
            child.add_argument("--sample-rss", action="store_true")
    args = parser.parse_args(argv)
    if args.command == "run" and args.profile == "visual" and args.rounds != 1:
        parser.error("Visual evidence requires exactly one round")
    if os.name != "posix":
        parser.error("Only POSIX process-group isolation is supported")
    deadline = time.monotonic() + 295  # Reserve five seconds for bounded termination/drain.
    previous = signal.signal(signal.SIGTERM, interrupt)
    try:
        if args.command == "prepare-fonts":
            output = new_output(args.output)
            files = archive_fonts(download_archive(deadline))
            new_output(output).mkdir(mode=0o700)
            for name, contents in files.items():
                private_write(output, name, contents)
            print(output)
        else:
            launch(args, deadline)
        return 0
    except KeyboardInterrupt:
        print("Interrupted; owned child process groups terminated", file=sys.stderr)
        return 130
    except (Failure, OSError, ValueError, tarfile.TarError, EOFError) as error:
        print(str(error) if isinstance(error, Failure) else "Isolated job failed (details redacted)", file=sys.stderr)
        return 1
    finally:
        signal.signal(signal.SIGTERM, previous)


if __name__ == "__main__":
    sys.exit(main())
