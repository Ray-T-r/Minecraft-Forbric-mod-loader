"""Verdict cache for gates-all: a gate that went GREEN on these exact inputs is not run again.

WHY. One development round ran the whole sweep five or six times, and several of those sweeps were of the same
tree: the merger swept the stage tip, then VERIFY block 2 swept the same tip again, then a doc-only record commit
went on top. Each sweep is ~45 minutes of game boots whose verdict was already known. A gate's verdict is a function
of what it runs (the kernel and tools source, its own script, the jars it stages, the JVM and the environment it is
handed); when none of that changed, the earlier GREEN is the answer, and re-deriving it only costs time.

WHAT IS IN THE KEY. Deliberately more than a gate strictly reads, because the failure mode that matters is a stale
GREEN, not a needless re-run:

  * every file git sees in the repository (tracked, and untracked-but-not-ignored, so an uncommitted edit counts),
    by content, EXCEPT prose that no gate executes: *.md, LICENSE/NOTICE, and run/compat/reports/. Other gates'
    gate-m*.sh scripts are left out of the common part and each gate adds its OWN script, plus any gate script it
    names (gate-m24b runs pieces of gate-m24, say). Editing gate-m12 re-runs gate-m12, not the 60 others.
  * for a gate that declares `# GATE-CACHE: tests` (gate-m0 runs the unit suite, gate-m39 the transfer suite): the
    unit-test sources (src/test, src/transferTest, test_*.py), EVERY gate script and the prose outside
    run/compat/reports/. Its tests read all of those -- GatesAllTest and test_gate_locale every gate, the contract tests
    one gate each, CompatProtocolTest PROTOCOL.md -- so its verdict depends on them, and editing gate-m13 must re-run
    gate-m0 as well as gate-m13 (review OPT wave 2d, lens A, B2). The records under reports/ stay out: no test such a
    gate runs reads them (test_gate_cache.py holds that), and a record commit must not re-run the suite. A test-only
    commit does not re-run boots that never load a test class.
  * every *.jar under the run directory -- except run/canary/, which only the canary builders write, from sources this
    key already holds -- and under the staged/game directories the launchers read (FORBRIC_OLD's run/, MC_DIR,
    FORBRIC_FORGE_SWAP, FORBRIC_NEO_BUILDS, the build/ fixture sets), by the zip's central directory -- entry names,
    CRC-32s and sizes, not timestamps, so a re-zipped jar with the same classes still hits -- and the canary sources
    in the staged root (STAGED_CANARY_SOURCES), by content.
  * the environment a gate inherits (FORBRIC_*, MC_*, M<n>_*, JAVA_*, LANG/LC_*, and the launchers' unprefixed knobs in
    _ENV_INPUTS: BASE, NEO_RT, NATIVE_REF, VANILLA_JAR, VANILLA_JVM, ...), minus the port knobs and the scheduler's own
    knobs, which change per slot and per run and do not change what a gate proves; a knob that names a jar (BASE,
    NEO_RT, NATIVE_REF, VANILLA_JAR) adds that jar's content. A sweep handed another FORBRIC_JVM (a locale, a switch)
    or another base therefore never reuses a verdict measured without it. test_gate_cache.py holds every variable a
    run/ script reads from its environment to this list.
  * `java -version` of the JVM the launchers will find, and the platform.

TIMING GATES. A gate declared `alone` (its verdict is a timing: check_kept_up, gate-m12's late ticks) is cached like any
other. Its inputs fix the code that has to keep up; another run of the same inputs adds only the host's load to the
answer, and a red from a loaded run is already read as no evidence about the kernel until its boot times say otherwise.
A RED is never kept, so a loaded run that went red runs again next time. test_gate_cache.py holds this rule.

CONTROL ARMS. Inside a gate, a run whose inputs hold nothing of this tree (gate-m31's pure-vanilla server) is kept by
lib.sh's control_arm_dir beside these verdicts, under the same switches: FORBRIC_GATE_FRESH=1 (an acceptance, or a sweep
without a cache) measures it afresh.

WHAT IS NOT. World saves, configs and logs under run/ -- gates write those on every run, so including them would
make every key unique. A hand-edited fixture WORLD is the one change this cache cannot see: run with --no-cache
after changing one. Only GREEN is cached; a RED or EXPECTED_RED gate always runs again. A release run (--release)
never reads the cache: an acceptance is a fresh measurement by definition.
"""
from __future__ import annotations

import hashlib
import io
import json
import os
import platform
import re
import shutil
import subprocess
import time
import zipfile
from pathlib import Path

CACHE_VERSION = "5"   # 2: nested jars digested by their entries; 3: the launchers' java, build/ fixture roots;
                      # 4: run/canary out, staged canary sources in; 5: a tests gate keys every gate and the prose,
                      # the launchers' unprefixed knobs keyed

# Prose no gate executes. A path matching this is left out of every key.
_PROSE = re.compile(r"(\.md$|(^|/)(LICENSE|NOTICE)$|^forbric-kernel/run/compat/reports/)")
# The records: prose no test a `tests` gate runs reads either (test_gate_cache.py holds that), so even that gate leaves them out.
_RECORDS = re.compile(r"^forbric-kernel/run/compat/reports/")
_GATE_SCRIPT = re.compile(r"^forbric-kernel/run/(gate-m[^/]+\.sh)$")
_TEST_SOURCE = re.compile(r"(^forbric-kernel/src/(test|transferTest)/|(^|/)test_[^/]+\.py$)")
_NAMED_GATE = re.compile(r"gate-m[0-9]+[\w-]*\.sh")

_ENV_KEEP = re.compile(r"^(FORBRIC_|MC_|M[0-9]+[A-Z]?_|JAVA_|LANG$|LC_|NATIVES_)")
# What a launcher, lib.sh, old-kernel.sh, a gate or a canary builder reads from its environment without one of the
# prefixes above (each as `${X:-default}` and never set by the script itself). BASE/NEO_RT/NATIVE_REF swap the game
# base, the carrier and the vanilla reference (launch-kernel-*.sh, build-*.sh, gate-m26/m34/m39); MERGED/FORGE_RT are
# the retired Forge-era names the launchers stop on; VANILLA_JAR/VANILLA_JVM are gate-m31's vanilla arm; STOP_READY_*
# and OLD_BOOT_LIMIT are lib.sh's and old-kernel.sh's time limits. A GREEN measured on another base is no answer for
# this one (review OPT wave 2d, lens A, M1).
_ENV_INPUTS = frozenset({"BASE", "NEO_RT", "NATIVE_REF", "MERGED", "FORGE_RT", "VANILLA_JAR", "VANILLA_JVM",
                         "STOP_READY_CAP", "STOP_READY_STALL", "OLD_BOOT_LIMIT"})
# Of those, the ones that name a jar: its content joins the key too, wherever it lives.
_ENV_JARS = ("BASE", "NEO_RT", "NATIVE_REF", "VANILLA_JAR")
# Read from the environment but not an input: where a gate listens (GATE_PORT, dropped below with the other ports) and
# writes (RUNDIR: every gate sets its own before it launches), and HOME (the directories it locates -- the game
# directory, the forge-swap fixture -- are keyed by path and content in external_roots).
_ENV_NOT_INPUT = frozenset({"GATE_PORT", "RUNDIR", "HOME"})
# Per-slot and per-run scheduler knobs: they move where a gate listens and where it writes its log, not what it proves.
# FORBRIC_PREBUILT_* is the sweep's own build of this same source (its stamp is an mtime, new on every build).
_ENV_DROP = re.compile(r"(_PORT$|^GATE_PORT$|^FORBRIC_GATE_|^FORBRIC_CAUGHT_FILE$|^FORBRIC_PREBUILT_)")

_DECL = re.compile(r"^#\s*GATE-CACHE:\s*(.*)$", re.M)


def gate_cache_flags(source: str) -> set[str]:
    m = _DECL.search(source)
    return set(m.group(1).split("(")[0].split()) if m else set()   # "(why)" is prose


def _sha(*parts: bytes) -> str:
    h = hashlib.sha256()
    for p in parts:
        h.update(len(p).to_bytes(8, "big"))
        h.update(p)
    return h.hexdigest()


def repo_files(repo: Path) -> list[str]:
    out = subprocess.run(["git", "-C", str(repo), "ls-files", "-z", "-co", "--exclude-standard"],
                         capture_output=True, check=True).stdout
    return sorted({p for p in out.decode("utf-8", "surrogateescape").split("\0") if p})


class SourceDigest:
    """Content digests of the repository's files, split into the parts a gate key is built from."""

    def __init__(self, repo: Path):
        self.repo = repo
        common, tests, prose = hashlib.sha256(), hashlib.sha256(), hashlib.sha256()
        self.gate_scripts: dict[str, str] = {}
        self.files: dict[str, str] = {}   # every keyed file's digest, for saying what changed
        for rel in repo_files(repo):
            if _RECORDS.search(rel):
                continue
            is_prose = bool(_PROSE.search(rel))
            path = repo / rel
            try:
                if path.is_symlink():
                    data = b"symlink:" + os.readlink(path).encode()
                elif path.is_file():
                    data = path.read_bytes()
                else:
                    continue   # a deleted-but-tracked file: its absence is what the others' contents already say
            except OSError:
                continue
            digest = _sha(rel.encode(), data)
            self.files[rel] = digest
            m = _GATE_SCRIPT.match(rel)
            if is_prose:
                prose.update(digest.encode())   # only a `tests` gate's key reads it
            elif m:
                self.gate_scripts[m.group(1)] = digest
            elif _TEST_SOURCE.search(rel):
                tests.update(digest.encode())
            else:
                common.update(digest.encode())
        self.common = common.hexdigest()
        self.tests = tests.hexdigest()
        self.prose = prose.hexdigest()

    def suite_surface(self) -> str:
        """What a `tests` gate's tests read beyond the common part: the test sources, every gate script and the prose."""
        return _sha(self.tests.encode(), self.prose.encode(),
                    *(f"{n}={d}".encode() for n, d in sorted(self.gate_scripts.items())))


def _zip_digest(z: zipfile.ZipFile, depth: int = 0) -> str:
    """A zip's entries by name, CRC-32 and size -- except a nested jar (jar-in-jar), which is digested the same way
    recursively: its CRC covers its own raw bytes, timestamps included, so the Fabric canary re-zipped with its
    nested library changed every key on every build while not one class in it had changed."""
    rows = []
    for i in z.infolist():
        if depth < 4 and i.filename.endswith(".jar") and not i.is_dir():
            try:
                with zipfile.ZipFile(io.BytesIO(z.read(i))) as nested:
                    rows.append(f"{i.filename}\tjar:{_zip_digest(nested, depth + 1)}")
                    continue
            except (zipfile.BadZipFile, ValueError):
                pass
        rows.append(f"{i.filename}\t{i.CRC}\t{i.file_size}")
    return _sha("\n".join(sorted(rows)).encode())


class JarDigest:
    """Central-directory digests of every jar under some roots, memoised by (size, mtime) between runs."""

    def __init__(self, memo_path: Path | None):
        self.memo_path = memo_path
        self.memo: dict[str, list] = {}
        if memo_path and memo_path.is_file():
            try:
                self.memo = json.loads(memo_path.read_text())
            except (OSError, ValueError):
                self.memo = {}
        self.fresh: dict[str, list] = {}

    def jar(self, path: Path) -> str:
        st = path.stat()
        real = os.path.realpath(path)
        hit = self.memo.get(real)
        if hit and hit[0] == st.st_size and hit[1] == st.st_mtime_ns:
            self.fresh[real] = hit
            return hit[2]
        try:
            with zipfile.ZipFile(path) as z:
                digest = _zip_digest(z)
        except (zipfile.BadZipFile, OSError, ValueError):
            digest = _sha(b"raw", path.read_bytes())
        self.fresh[real] = [st.st_size, st.st_mtime_ns, digest]
        return digest

    def tree(self, root: Path, skip_dirs: set[str], skip_top: set[str] = frozenset(),
             record: dict | None = None) -> str:
        """skip_dirs are skipped at any depth; skip_top only directly under root. record, when given, receives
        every jar's digest by its path, for saying what changed."""
        h = hashlib.sha256()
        seen: set[str] = set()
        rows: list[str] = []
        for top, dirs, files in os.walk(root, followlinks=True):
            real = os.path.realpath(top)
            if real in seen:
                dirs[:] = []
                continue
            seen.add(real)
            top_level = os.path.normpath(top) == os.path.normpath(root)
            dirs[:] = sorted(d for d in dirs if d not in skip_dirs and not (top_level and d in skip_top))
            for f in files:
                if f.endswith(".jar"):
                    p = Path(top) / f
                    try:
                        rows.append(f"{p.relative_to(root)}\t{self.jar(p)}")
                    except OSError:
                        rows.append(f"{p.relative_to(root)}\tunreadable")
        for r in sorted(rows):
            h.update(r.encode() + b"\n")
            if record is not None:
                path, _, digest = r.partition("\t")
                record[f"{root}/{path}"] = digest
        return h.hexdigest()

    def save(self) -> None:
        if not self.memo_path:
            return
        self.memo_path.parent.mkdir(parents=True, exist_ok=True)
        merged = dict(self.memo)   # another tree's sweep shares this memo: keep its jars too
        merged.update(self.fresh)
        tmp = self.memo_path.with_suffix(f".{os.getpid()}.tmp")
        tmp.write_text(json.dumps(merged))
        tmp.replace(self.memo_path)


def _java_version(env: dict) -> str:
    """`java -version` of the JVM the launchers start: they run a bare `java` from PATH (launch-kernel-server.sh,
    launch-kernel-client.sh, launch-vanilla-server.sh), and on macOS /usr/bin/java is a stub that follows JAVA_HOME. So
    this runs the `java` that the gate's own PATH finds, with the gate's JAVA_HOME, and nothing else: FORBRIC_JAVA is
    read by no launcher, and keying on it once let a PATH that found another JDK reuse the first JDK's GREEN."""
    java = shutil.which("java", path=env.get("PATH")) or "java"
    child = {k: v for k, v in os.environ.items() if k not in ("JAVA_HOME", "PATH")}
    child.update({k: env[k] for k in ("JAVA_HOME", "PATH") if k in env})
    try:
        r = subprocess.run([java, "-version"], capture_output=True, text=True, timeout=30, env=child)
        return f"{java}\n{r.stderr}{r.stdout}"
    except (OSError, subprocess.TimeoutExpired):
        return "no java"


FIXTURE_BUILD_DIRS = ("compat-inputs", "purefabric-2026-10-03")

# What the canary builders (run/build-*.sh) write: run/canary/ is ignored by git and holds nothing but their output, and
# every input of theirs is already in the key -- the canary sources and builders are repository files, the jars they
# compile against sit in hashed roots, and the sources two of them read from the staged root are hashed below. Keying
# on their OUTPUT made the first sweep of a fresh worktree (where no gate had built them yet) and the sweep after it
# different inputs, so that second sweep re-ran every gate (measured, lane OPT 2026-11-16: 55 jars "new").
BUILT_RUN_DIRS = ("canary",)

# Sources in the staged root that canary builders compile (build-neolive-canary.sh: livemod-src-neoforge;
# build-forge-refusal-fixtures.sh: livemod-src; build-testmods.sh builds the staged canaries from these). Not jars, so
# the jar walk does not see them.
STAGED_CANARY_SOURCES = ("livemod-src", "livemod-src-neoforge", "testmod-src", "bridge-src", "bridge-src-neoforge",
                         "build-testmods.sh")


def staged_source_digest(old_run: Path, record: dict) -> str:
    h = hashlib.sha256()
    for name in STAGED_CANARY_SOURCES:
        top = old_run / name
        files = [top] if top.is_file() else sorted(q for q in top.rglob("*") if q.is_file()) if top.is_dir() else []
        for f in files:
            rel = str(f.relative_to(old_run))
            try:
                digest = _sha(rel.encode(), f.read_bytes())
            except OSError:
                digest = "unreadable"
            h.update(f"{rel}\t{digest}\n".encode())
            record[f"{old_run}/{rel}"] = digest
    return h.hexdigest()


def external_roots(kernel: Path, env: dict) -> list[Path]:
    """The directories outside run/ whose jars the launchers and gates read. Mirrors lib.sh's and old-kernel.sh's
    defaults: the staged tree (FORBRIC_OLD), the stage roots a worktree carries (.stage, .stage4), the pinned
    development APIs (.dev), the game directory, and the forge-swap fixture."""
    home = env.get("HOME", "")
    old = Path(env.get("FORBRIC_OLD") or kernel.parent / "forbric-loader") / "run"
    mc = Path(env.get("MC_DIR") or Path(home) / "Library/Application Support/minecraft")
    roots = [old, mc, kernel.parent / ".stage", kernel.parent / ".stage4", kernel / ".dev"]
    # The fixture mod sets under build/ that gates stage by default (gate-m60's BCLib from compat-inputs/sweep90 and
    # WunderLib from purefabric-2026-10-03, gate-m61's Create Fly, gate-m62's Carpet). Everything else a gate reads from
    # build/ it built from this tree's sources. test_gate_cache.py holds every gate's $BUILD/<dir>/*.jar to this list.
    roots += [kernel / "build" / d for d in FIXTURE_BUILD_DIRS]
    swap = env.get("FORBRIC_FORGE_SWAP") or (home and f"{home}/Forbric-backups/forge-swap")
    if swap:
        roots.append(Path(swap))
    if env.get("FORBRIC_NEO_BUILDS"):
        roots.append(Path(env["FORBRIC_NEO_BUILDS"]))
    out, seen = [], set()
    for r in roots:   # FORBRIC_OLD is often one of the stage roots; hash each directory once
        if r.is_dir() and os.path.realpath(r) not in seen:
            seen.add(os.path.realpath(r))
            out.append(r)
    return out


def env_lines(env: dict, repo: Path) -> list[str]:
    """The inherited environment, with this working tree's own path written as <repo>: a lane, the merger and
    VERIFY each run from a different worktree, and FORBRIC_OLD=<their tree>/forbric-loader is the same input."""
    here = str(repo)
    return sorted(f"{k}={v.replace(here, '<repo>')}" for k, v in env.items()
                  if (_ENV_KEEP.search(k) or k in _ENV_INPUTS) and not _ENV_DROP.search(k))


def env_digest(env: dict, repo: Path) -> str:
    return _sha("\n".join(env_lines(env, repo)).encode())


class Cache:
    def __init__(self, cache_dir: Path, run_dir: Path, env: dict, say=lambda _l: None,
                 scratch_dirs: set[str] = frozenset()):
        """scratch_dirs: the run/ directories gates declare as their own (GATE-PARALLEL rundirs=). A gate clears
        and restages those from inputs elsewhere on every run, so their jars are the gate's OUTPUT -- and they exist
        only in a tree where that gate has run before, which would make every worktree's key different."""
        self.dir = cache_dir
        self.run_dir = run_dir
        t0 = time.time()
        kernel = run_dir.parent
        repo = Path(subprocess.run(["git", "-C", str(run_dir), "rev-parse", "--show-toplevel"],
                                   capture_output=True, text=True, check=True).stdout.strip())
        self.sources = SourceDigest(repo)
        jars = JarDigest(cache_dir / f"jar-memo-v{CACHE_VERSION}.json")   # a new digest rule, a new memo
        jar_rows: dict[str, str] = {}
        parts = [CACHE_VERSION.encode(), self.sources.common.encode(),
                 jars.tree(run_dir, {".gate-clones"}, set(scratch_dirs) | set(BUILT_RUN_DIRS), jar_rows).encode()]
        staged_rows: dict[str, str] = {}
        old_run = Path(env.get("FORBRIC_OLD") or kernel.parent / "forbric-loader") / "run"
        parts.append(staged_source_digest(old_run, staged_rows).encode())
        for root in external_roots(kernel, env):
            parts.append(str(root).replace(str(repo), "<repo>").encode())
            parts.append(jars.tree(root, {".gate-clones", "saves", "logs", "crash-reports"}, record=jar_rows).encode())
        env_rows = env_lines(env, repo)
        for name in _ENV_JARS:   # a knob that names a jar: the jar's content, not just its path
            target = Path(env.get(name) or "")
            if env.get(name) and target.is_file():
                digest = jars.jar(target)
                parts.append(f"{name}:{digest}".encode())
                jar_rows[f"${name}={str(target).replace(str(repo), '<repo>')}"] = digest
        java = _java_version(env)
        parts += [_sha("\n".join(env_rows).encode()).encode(), java.encode(), platform.platform().encode()]
        jars.save()
        self.common = _sha(*parts)
        say(f"[gates] cache: inputs fingerprinted in {time.time() - t0:.1f}s ({cache_dir})")
        self._explain({"sources": {**self.sources.files, **{k.replace(str(repo), "<repo>"): v for k, v in staged_rows.items()}},
                       "jars": {k.replace(str(repo), "<repo>"): v for k, v in jar_rows.items()}, "env": {r: "" for r in env_rows},
                       "jvm": {java.strip(): "", platform.platform(): ""}}, say)

    def _explain(self, inputs: dict, say) -> None:
        """Keep this run's inputs, and say what differs from the last run's: a sweep that re-runs gates the user
        expected cached should name the file that did it, not leave them to guess."""
        path = self.dir / "last-inputs.json"
        try:
            before = json.loads(path.read_text())
        except (OSError, ValueError):
            before = None
        if before is not None:
            changes = []
            for kind, now in inputs.items():
                old = before.get(kind, {})
                diff = sorted(k for k in set(now) | set(old) if now.get(k) != old.get(k))
                if diff:
                    shown = ", ".join(diff[:5]) + (f" and {len(diff) - 5} more" if len(diff) > 5 else "")
                    changes.append(f"{kind}: {shown}")
            say("[gates] cache: inputs changed since the last run -- " + ("; ".join(changes) if changes else "none"))
        self.dir.mkdir(parents=True, exist_ok=True)
        tmp = path.with_suffix(f".{os.getpid()}.tmp")
        tmp.write_text(json.dumps(inputs))
        tmp.replace(path)

    def key(self, gate_name: str, source: str) -> str:
        parts = [self.common.encode(), gate_name.encode(),
                 self.sources.gate_scripts.get(gate_name, "untracked").encode()]
        for named in sorted(set(_NAMED_GATE.findall(source)) - {gate_name}):
            parts.append(f"{named}={self.sources.gate_scripts.get(named, '-')}".encode())
        if "tests" in gate_cache_flags(source):
            parts.append(self.sources.suite_surface().encode())
        return _sha(*parts)[:32]

    def _entry(self, gate_name: str, key: str) -> Path:
        return self.dir / "verdicts" / gate_name / f"{key}.json"

    def lookup(self, gate_name: str, key: str) -> dict | None:
        entry = self._entry(gate_name, key)
        try:
            data = json.loads(entry.read_text())
        except (OSError, ValueError):
            return None
        log = entry.with_suffix(".log")
        if data.get("verdict") != "GREEN" or not log.is_file():
            return None
        data["log"] = str(log)
        return data

    def record(self, gate_name: str, key: str, verdict: str, secs: float, log_path: Path) -> None:
        if verdict != "GREEN":
            return
        entry = self._entry(gate_name, key)
        entry.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(log_path, entry.with_suffix(".log"))
        tmp = entry.with_suffix(f".{os.getpid()}.tmp")
        tmp.write_text(json.dumps({"verdict": verdict, "secs": round(secs, 1),
                                     "when": time.strftime("%Y-%m-%dT%H:%M:%S"),
                                     "out": str(log_path)}))
        tmp.replace(entry)   # written last and atomically: a lookup never sees an entry without its log
