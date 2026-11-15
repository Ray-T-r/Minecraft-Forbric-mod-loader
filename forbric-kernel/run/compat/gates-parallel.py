#!/usr/bin/env python3
"""Scheduler behind gates-all.sh: run the checked-in gates with as much overlap as the machine allows.

WHY A SCHEDULER AND NOT `xargs -P`. The gates are not independent processes that happen to be slow. Each one
boots a real Minecraft server or client, and three things make a naive fan-out produce verdicts that prove
nothing:

  * THE PORT. Every gate that starts a server writes a port into server.properties, most of them GATE_PORT via
    seed_server_properties. Two of them on one port and the loser prints "FAILED TO BIND TO PORT", writes a crash
    report, and then still prints "Stopping server" -- so the clean-shutdown assertions pass, and the gate fails
    on whatever needed a running server, which reads as a kernel regression. lib.sh's port_was_free now names the
    port instead. Every concurrent gate therefore gets its own port block, not a shared default.
  * THE RUNDIR. gate-m1, gate-m2 and gate-m3 all use run/server-kernel. Two gates in one rundir stage each
    other's mods, truncate each other's server.properties and read each other's logs. Gates that share a
    rundir are never run together.
  * THE ONE RUNDIR WORTH COPYING. Four gates want run/client-merged-pack, a 97-jar install with a world in it,
    and serialising them left the last two minutes of a run with a single gate in it. They get a COPY each
    instead: on APFS `cp -Rc` clones that 434 MB directory in 0.17s and shares its blocks until something is
    written, so four private copies cost no disk and no wait. A gate asks for one by declaring
    `clone=<dir>:<VAR>`, and the scheduler points <VAR> at the copy.
  * THE MEMORY. Every kernel JVM is launched without -Xmx, so each one inherits an ergonomic 4 GB ceiling. Four
    clients on a 16 GB machine is not a test result, it is a swap storm. Each gate declares what it costs and
    the scheduler keeps the running set inside a budget.

A gate that does not declare itself runs ALONE. That is the safe direction to be wrong in: a new gate added by
someone who never read this file is slow, not silently broken. It says so on stderr when it happens.

A gate whose verdict is a TIMING says `alone` on its line, and runs alone too. gate-m12 counts the server ticks that
ran at twice their budget from the client's dial to the leave, against a ceiling; that count is a measure of what
else the host was doing as much as of the server. VERIFY run 1 of C5 had it red at -j 4 (11 of 220 late, ceiling 7)
next to gate-m0's unit suite and four server boots, green alone 5 of 5, and red alone in one of two runs under ten
`yes` CPU hogs: the ceiling was measuring the scheduler's neighbours. Raising the ceiling would blind it to a server that really
falls behind, so the gate keeps it and the scheduler gives it a quiet machine instead. What it cannot quiet is load
from outside this sweep. The same holds for lib.sh's check_kept_up (vanilla's "Can't keep up!" from the client's dial on),
which gate-m12 and gate-m16 call: side-by-side -j 5 batches had it red in two rounds of three on gate-m15 (whose checks
gate-m16 now makes) and on m16 beside the full test suites, so those gates say `alone` too. GatesAllTest finds the gates that use either check and
fails on one that does not say `alone`.

-j 1 is exactly the old sequential behaviour: same order, same summary.txt, same exit code.

LONGEST FIRST. With more than one slot the gates start in order of how long they took last time, longest first
(gate-durations.tsv next door is the checked-in prior; each run's own timings, kept in the cache directory, win over
it). In numerical order gate-m58 -- the longest gate -- was the last one started and then ran alone for twelve minutes
while three slots sat idle; started first it overlaps everything else. summary.txt keeps numerical order whatever
order the gates ran in.

THE VERDICT CACHE (gate_cache.py). With --cache-dir, a gate whose inputs are byte-identical to a run that went GREEN
is not run again: its RESULT says GREEN with `cached:` and the time of that run, and its log is the cached run's
log, so a count of its [kernel] PASS lines still reads the real thing. --release never reads the cache.

SELECTION. --only <gate> (repeatable) runs a subset; the rest are SKIP with the reason, which a --release run treats as
any other skip.

--release IS AN ACCEPTANCE, NOT A SWEEP. An ordinary sweep reports an explicit --skip as SKIP and a declared
EXPECTED_RED as itself, and exits 0 when nothing else is red -- the right answer while developing. For a release
both are the same fact: a gate whose assertions did not pass. So in release mode either one makes the exit code 1,
and its RESULT line says why, because a gate that was never run cannot be counted as passing.

STDOUT IS STILL ONLY THE RESULT LINES. A run now takes minutes with several gates interleaved, so there is a
lot worth saying while it happens -- but gates-all.sh's output is asserted to equal summary.txt byte for byte
(GatesAllTest), and the probe harness merges stderr into it, so neither stream is free. The running commentary
goes to <results>/progress.log instead, which `tail -f` reads live, and `--progress` also mirrors it to stderr
for an interactive run.
"""
from __future__ import annotations

import argparse
import json
import os
import re
import shutil
import signal
import subprocess
import sys
import time
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import gate_cache  # noqa: E402

DURATIONS_PRIOR = Path(__file__).resolve().parent / "gate-durations.tsv"

# Port blocks are handed out one per CONCURRENT SLOT, not one per gate: slot i gets PORT_BASE + i*PORT_STRIDE.
# A GATE_PORT already in the environment becomes the base, so `GATE_PORT=25599 gates-all.sh` still puts 25599 in
# front of the first gate -- the caller keeps the knob, and the slots keep their isolation.
#
# The default moved off 25599 because that is also gate-m12's own default (M12_PORT). The two could never
# collide while the gates ran one at a time; they collide on the first overlapping run.
PORT_BASE = 25700
# Seconds a gate has to stop after the sweep forwards it a TERM (lib.sh's _gate_exit gives what it started 2 s).
GATE_TERM_GRACE = 20
PORT_STRIDE = 10

# The per-gate knobs that select a port, drawn from the slot's block in this order. A gate uses at most one of
# these; handing it the whole block costs nothing and means a gate that grows a second server does not need the
# scheduler changed. A gate reading a knob that is NOT here gets nothing and keeps its own literal, and the slot
# where that literal meets an exported one is two servers on one port -- the race this module exists to prevent.
# test_gate_ports.py holds every gate to this tuple, so a gate that adds an M<n>_PORT fails there instead of in a
# run whose two occupants nobody can reproduce.
PORT_VARS = ("GATE_PORT", "M12_PORT", "M13_PORT", "M14_PORT", "M16_PORT", "M28_PORT", "M32_PORT")

# [ \t], not \s: with re.M a \s* runs over the end of an empty line, and the NEXT line was read as the declaration
# (HARN2 review A-4: "# GATE-PARALLEL:" over "touch x" was the unknown word 'touch').
DECL = re.compile(r"^#[ \t]*GATE-PARALLEL:[ \t]*(.*)$", re.M)
# A comment on the declaration line: a `#` at its start or after whitespace, to the end of the line.
COMMENT = re.compile(r"(?:^|\s)#")
DECL_WORDS = ("rundirs", "clone", "mem", "alone")


class Gate:
    def __init__(self, name: str, path: Path):
        self.name = name
        self.path = path
        self.source = path.read_text(encoding="utf-8", errors="replace")
        self.rundirs: set[str] = set()
        self.clone: tuple[str, str] | None = None
        self.mem = 0
        self.declared = False
        self.alone = False
        self._parse()

    def _parse(self) -> None:
        # Every word on the line is one of the four below, or the sweep refuses to start. Until C5 wave 2d an unknown
        # word was skipped in silence, so a misspelt `alon` or `rundir=` ran the gate beside others with nothing said,
        # and gate-m0's "(builds and runs the unit suite)" was read as five ignored words. Prose goes after ` # `:
        #     # GATE-PARALLEL: rundirs=server-m0-definitions mem=3500  # builds and runs the unit suite
        name = self.name
        found = list(DECL.finditer(self.source))
        if not found:
            return
        if len(found) > 1:
            raise SystemExit(f"{name}: {len(found)} '# GATE-PARALLEL:' lines; one gate has one declaration")
        body = COMMENT.split(found[0].group(1), maxsplit=1)[0]
        if not body.split():
            # HARN2 review A-4: an empty line, or one with only prose, was taken as a declaration of nothing -- no rundirs,
            # 0 MB -- and the gate ran beside any other with nothing said. A gate that claims nothing says so: mem=0.
            raise SystemExit(f"{name}: its GATE-PARALLEL line names nothing (it takes rundirs=<dir>[,<dir>...] "
                             f"clone=<dir>:<ENV_VAR> mem=<MB> alone; prose goes after ' # ')")
        seen: set[str] = set()
        for field in body.split():
            key, eq, value = field.partition("=")
            if key not in DECL_WORDS:
                raise SystemExit(f"{name}: unknown word {field!r} on its GATE-PARALLEL line (it takes "
                                 f"rundirs=<dir>[,<dir>...] clone=<dir>:<ENV_VAR> mem=<MB> alone; prose goes after ' # ')")
            if key in seen:
                raise SystemExit(f"{name}: {key} given twice on its GATE-PARALLEL line")
            seen.add(key)
            if key == "rundirs":
                self.rundirs = {r for r in value.replace(",", " ").split() if r}
                if not self.rundirs:
                    raise SystemExit(f"{name}: rundirs= wants one or more <dir>, got {field!r}")
            elif key == "clone":
                source, _, variable = value.partition(":")
                if not source or not variable:
                    raise SystemExit(f"{name}: clone= wants <dir>:<ENV_VAR>, got {value!r}")
                self.clone = (source, variable)
            elif key == "mem":
                if not value.isdigit():
                    raise SystemExit(f"{name}: mem= wants a whole number of MB, got {field!r}")
                self.mem = int(value)
            elif key == "alone":
                if eq:
                    raise SystemExit(f"{name}: alone takes no value, got {field!r}")
                self.alone = True
        self.declared = True

    @property
    def exclusive(self) -> bool:
        return not self.declared or self.alone

    def expects_red(self) -> bool:
        return re.search(r"^# EXPECTED: RED until ", self.source, re.M) is not None


def kill_tree(root: int) -> None:
    """SIGKILL <root> and everything under it, each stopped before its children are listed (lib.sh's kill_tree)."""
    try:
        os.kill(root, signal.SIGSTOP)
    except OSError:
        return
    listed = subprocess.run(["pgrep", "-P", str(root)], capture_output=True, text=True).stdout.split()
    for child in listed:
        kill_tree(int(child))
    try:
        os.kill(root, signal.SIGKILL)
    except OSError:
        pass


def discover(run_dir: Path) -> list[Gate]:
    def key(p: Path):
        m = re.match(r"gate-m(\d+)(.*)", p.name)
        return int(m[1]), m[2].removesuffix(".sh")

    return [Gate(p.name, p) for p in sorted(run_dir.glob("gate-m*.sh"), key=key)]


def default_budget_mb() -> int:
    """Roughly 55% of physical RAM. The rest is the OS, the editor, the browser and whatever the developer is
    doing while this runs -- a budget that assumes an idle machine is a budget that swaps."""
    try:
        if sys.platform == "darwin":
            out = subprocess.run(["sysctl", "-n", "hw.memsize"], capture_output=True, text=True, check=True)
            total = int(out.stdout.strip())
        else:
            total = 0
            for line in Path("/proc/meminfo").read_text().splitlines():
                if line.startswith("MemTotal:"):
                    total = int(line.split()[1]) * 1024
                    break
    except Exception:
        return 4096
    return max(2048, int(total / 1024 / 1024 * 0.55))


def clone_rundir(source: Path, destination: Path) -> None:
    """Give a gate its own copy of a shared fixture directory.

    `cp -Rc` asks APFS for a clone: the copy shares the original's blocks until one of them is written, so a
    434 MB game install copies in under a fifth of a second and costs no disk. The flag is macOS-only and fails
    on any filesystem that cannot do it, so fall back to `cp --reflink=auto` (btrfs, xfs, modern ext4) and then
    to a real recursive copy, which is slow but correct.

    The source is resolved first. A second working tree (the nightly's) links its fixtures to the main checkout's,
    and cp -R copies a symlink named on its command line AS a symlink: the "copy" was then a link to the main
    checkout's fixture, and the gate saved its world, options and mods straight into it.
    """
    source = Path(os.path.realpath(source))
    if destination.exists() or destination.is_symlink():
        subprocess.run(["rm", "-rf", str(destination)], check=True)
    destination.parent.mkdir(parents=True, exist_ok=True)
    for command in (["cp", "-Rc", str(source), str(destination)],
                    ["cp", "-R", "--reflink=auto", str(source), str(destination)],
                    ["cp", "-R", str(source), str(destination)]):
        if subprocess.run(command, capture_output=True).returncode == 0:
            return
        subprocess.run(["rm", "-rf", str(destination)], capture_output=True)
    raise OSError(f"could not copy {source} to {destination}")


class Running:
    def __init__(self, gate: Gate, proc: subprocess.Popen, log, slot: int, started: float):
        self.gate = gate
        self.proc = proc
        self.log = log
        self.slot = slot
        self.started = started


def verdict_for(gate: Gate, rc: int, log_path: Path) -> str:
    if rc == 0:
        return "GREEN"
    if rc == 2 and gate.expects_red():
        try:
            body = log_path.read_bytes()
        except OSError:
            body = b""
        # Line-anchored, exactly as the bash runner grepped for it: the marker has to be the start of a line
        # the gate printed, not a phrase quoted inside one.
        if re.search(rb"^\[kernel\] EXPECTED-RED ", body, re.M):
            return "EXPECTED_RED"
    return "RED"


def load_durations(history: Path | None) -> dict[str, float]:
    """Seconds per gate: the checked-in prior, overlaid by this machine's own last measurements."""
    durations: dict[str, float] = {}
    try:
        for line in DURATIONS_PRIOR.read_text(encoding="utf-8").splitlines():
            if line and not line.startswith("#"):
                name, secs = line.split("\t")[:2]
                durations[name] = float(secs)
    except (OSError, ValueError):
        pass
    if history and history.is_file():
        try:
            durations.update({k: float(v) for k, v in json.loads(history.read_text()).items()})
        except (OSError, ValueError):
            pass
    return durations


def save_durations(history: Path | None, durations: dict[str, float]) -> None:
    if not history:
        return
    history.parent.mkdir(parents=True, exist_ok=True)
    history.write_text(json.dumps({k: round(v, 1) for k, v in sorted(durations.items())}, indent=0))


def main() -> int:
    ap = argparse.ArgumentParser(add_help=False)
    ap.add_argument("--jobs", "-j", default="auto")
    ap.add_argument("--mem-budget", type=int, default=None, help="MB of RAM the running gates may claim")
    ap.add_argument("--run-dir", required=True)
    ap.add_argument("--out-dir", required=True)
    ap.add_argument("--skip", action="append", default=[])
    ap.add_argument("--list", action="store_true")
    ap.add_argument("--progress", action="store_true",
                    help="mirror <results>/progress.log to stderr as the run goes")
    ap.add_argument("--only", action="append", default=[], help="run only this gate (repeatable)")
    ap.add_argument("--cache-dir", default=None,
                    help="verdict cache and duration history; without it nothing is cached")
    ap.add_argument("--release", action="store_true",
                    help="acceptance run: a skipped or expected-red gate fails the run")
    args = ap.parse_args()

    run_dir, out_dir = Path(args.run_dir), Path(args.out_dir)
    gates = discover(run_dir)
    if not gates:
        print(f"No gates found: {run_dir}", file=sys.stderr)
        return 2
    if args.list:
        for g in gates:
            print(g.name)
        return 0

    known = {g.name for g in gates}
    for s in args.skip:
        if s not in known:
            print(f"Unknown --skip gate: {s}", file=sys.stderr)
            return 2
    for s in args.only:
        if s not in known:
            print(f"Unknown --only gate: {s}", file=sys.stderr)
            return 2
    # Why each gate that will not run is not running, for its RESULT line.
    skip_reason = {s: "explicit --skip" for s in args.skip}
    if args.only:
        wanted = " ".join(f"--only {n}" for n in args.only)
        for g in gates:
            if g.name not in args.only and g.name not in skip_reason:
                skip_reason[g.name] = f"not selected by {wanted}"

    port_base = int(os.environ.get("GATE_PORT") or PORT_BASE)
    budget = args.mem_budget if args.mem_budget is not None else default_budget_mb()
    if args.jobs == "auto":
        # CORES, not memory, is what actually bounds this. Measured on a 10-core, 16 GB machine: the whole
        # sweep peaks at 5.5 GB of game JVMs however wide it runs, so memory never binds — but at -j 7 the
        # gates get starved and the TIME-BASED assertions start failing. gate-m19 went red there on
        # await_server's "still alive 20s after announcing its stop", which is a real check for a leaked
        # non-daemon thread and must not be relaxed to suit a scheduler. -j 5 and -j 4 were green.
        #
        # So: one slot per two cores, with the memory budget only as a ceiling.
        jobs = max(1, min(len(gates), (os.cpu_count() or 4) // 2, budget // 1500))
    else:
        jobs = max(1, int(args.jobs))

    undeclared = [g.name for g in gates if not g.declared and g.name not in skip_reason]
    alone = [g.name for g in gates if g.declared and g.alone and g.name not in skip_reason]

    out_dir.mkdir(parents=True, exist_ok=True)
    # A summary is written only when the sweep finishes, so one left by an earlier sweep in the same directory would sit
    # beside this one's progress.log as if it were its verdict (HARN2 review B-7; an interrupted sweep writes none).
    (out_dir / "summary.txt").unlink(missing_ok=True)
    progress_file = (out_dir / "progress.log").open("w", encoding="utf-8", buffering=1)

    def say(line: str) -> None:
        progress_file.write(line + "\n")
        if args.progress:
            print(line, file=sys.stderr, flush=True)

    pending = [g for g in gates if g.name not in skip_reason]
    results: dict[str, tuple[str, int, float]] = {n: ("SKIP", 0, 0.0) for n in skip_reason}

    cache = None
    keys: dict[str, str] = {}
    cached: dict[str, dict] = {}
    if args.cache_dir:
        try:
            cache = gate_cache.Cache(Path(args.cache_dir), run_dir, dict(os.environ), say,
                                     scratch_dirs=set().union(*(g.rundirs for g in gates)))
        except (OSError, subprocess.CalledProcessError) as e:
            say(f"[gates] cache: could not fingerprint the inputs ({e}); every gate runs")
    if cache:
        for g in list(pending):
            keys[g.name] = cache.key(g.name, g.source)
            hit = None if args.release else cache.lookup(g.name, keys[g.name])
            if hit:
                pending.remove(g)
                cached[g.name] = hit
                shutil.copyfile(hit["log"], out_dir / f"{g.name}.log")
                results[g.name] = ("GREEN", 0, 0.0)
                say(f"[gates]    0s CACHED GREEN {g.name} (same inputs went GREEN at {hit['when']}, {hit['secs']:.0f}s)")
        if cached:
            say(f"[gates] cache: {len(cached)} of {len(cached) + len(pending)} gates already GREEN on these inputs")

    # Longest first, from the last measured durations (see LONGEST FIRST above). -j 1 keeps numerical order.
    history_path = Path(args.cache_dir) / "durations.json" if args.cache_dir else None
    durations = load_durations(history_path)
    if jobs > 1:
        order = {g.name: i for i, g in enumerate(gates)}
        pending.sort(key=lambda g: (-durations.get(g.name, 60.0), order[g.name]))

    running: list[Running] = []
    free_slots = list(range(jobs))
    held: set[str] = set()          # rundir keys currently claimed
    used_mb = 0
    exclusive_running = False
    t0 = time.time()

    say(f"[gates] {len(pending)} gates, -j {jobs}, budget {budget} MB, ports {port_base}+")
    if undeclared and jobs > 1:
        say("[gates] no '# GATE-PARALLEL:' line, so each of these runs ALONE: " + " ".join(undeclared))
    if alone and jobs > 1:
        say("[gates] declared 'alone' (a timing verdict), so each of these runs with no other gate: " + " ".join(alone))

    def start(gate: Gate) -> None:
        nonlocal used_mb, exclusive_running
        slot = free_slots.pop(0)
        env = dict(os.environ)
        # A gate's control arms (lib.sh control_arm_dir) are measured afresh exactly when its verdict is: in an
        # acceptance, and in a sweep that keeps no cache.
        if args.release or not args.cache_dir:
            env["FORBRIC_GATE_FRESH"] = "1"
        base = port_base + slot * PORT_STRIDE
        for i, var in enumerate(PORT_VARS):
            env[var] = str(base + i)
        cloned = ""
        if gate.clone:
            source_name, variable = gate.clone
            source = run_dir / source_name
            # A fixture that is not staged on this machine is not this scheduler's problem: leave the variable
            # unset so the gate reaches its own "no world at ..." SKIP-FATAL and says so in its own words.
            if source.is_dir():
                destination = run_dir / ".gate-clones" / gate.name.removesuffix(".sh") / source_name
                clone_rundir(source, destination)
                env[variable] = str(destination)
                cloned = f", own copy of {source_name}"
        log_path = out_dir / f"{gate.name}.log"
        log = log_path.open("wb")
        proc = subprocess.Popen(
            ["bash", str(run_dir / gate.name)],
            stdout=log, stderr=subprocess.STDOUT, stdin=subprocess.DEVNULL, env=env,
        )
        running.append(Running(gate, proc, log, slot, time.time()))
        held.update(gate.rundirs)
        used_mb += gate.mem
        if gate.exclusive:
            exclusive_running = True
        say(f"[gates] +{int(time.time() - t0):4d}s START {gate.name}"
            f" (slot {slot}, port {base}, {gate.mem or '?'} MB{cloned})")

    def can_start(gate: Gate) -> bool:
        if not free_slots:
            return False
        if exclusive_running:
            return False
        if gate.exclusive:
            return not running
        if gate.rundirs & held:
            return False
        if running and used_mb + gate.mem > budget:
            return False
        return True

    stopping: list[int] = []   # the signal that asked the sweep to stop, once one has

    def schedule() -> None:
        nonlocal used_mb, exclusive_running
        while (pending or running) and not stopping:
            progressed = False
            for gate in list(pending):
                if stopping:
                    return
                if can_start(gate):
                    pending.remove(gate)
                    start(gate)
                    progressed = True
            # Nothing runnable and nothing running: the head of the queue does not fit the budget on its own.
            # Run it anyway rather than hang -- the budget is a throttle, not a promise.
            if not running and pending and not stopping:
                gate = pending.pop(0)
                say(f"[gates] {gate.name} wants {gate.mem} MB, over the {budget} MB budget — running it alone")
                start(gate)
                progressed = True
            if not running:
                break
            if progressed and pending:
                continue
            # Wait for whichever gate finishes first.
            while not stopping:
                done = [r for r in running if r.proc.poll() is not None]
                if done:
                    break
                time.sleep(0.25)
            if stopping:
                return
            for r in done:
                rc = r.proc.returncode
                r.log.close()
                running.remove(r)
                free_slots.append(r.slot)
                free_slots.sort()
                held.difference_update(r.gate.rundirs)
                used_mb -= r.gate.mem
                if r.gate.exclusive:
                    exclusive_running = False
                elapsed = time.time() - r.started
                v = verdict_for(r.gate, rc, out_dir / f"{r.gate.name}.log")
                results[r.gate.name] = (v, rc, elapsed)
                say(f"[gates] +{int(time.time() - t0):4d}s {v:12s} {r.gate.name} ({elapsed:.0f}s)")
                durations[r.gate.name] = elapsed
                if cache:
                    cache.record(r.gate.name, keys[r.gate.name], v, elapsed, out_dir / f"{r.gate.name}.log")

    # A TERM, INT or HUP to the sweep reaches its running gates (C5 wave 2d). gates-all.sh execs this script, so when
    # heavy.sh forwards a TERM it lands here, and until now Python died of it on the spot: every running gate went on
    # to the end on its own, re-parented to launchd, booting servers on the slot's ports with nobody to read its verdict.
    # Each gate is sent TERM, which runs lib.sh's _gate_exit (it stops what the gate started), and given GATE_TERM_GRACE
    # seconds to go; a gate shell still there after that is killed. No verdict is recorded for an interrupted sweep.
    # The handler only records the signal; the loop stops at its next step, so a gate is never half started.
    def interrupted(signum, _frame):
        stopping.append(signum)

    for signum in (signal.SIGTERM, signal.SIGINT, signal.SIGHUP):
        signal.signal(signum, interrupted)
    schedule()
    if stopping:
        signum = stopping[0]
        say(f"[gates] +{int(time.time() - t0):4d}s {signal.Signals(signum).name}: stopping "
            + (" ".join(r.gate.name for r in running) or "nothing (no gate was running)"))
        for r in running:
            if r.proc.poll() is None:
                r.proc.send_signal(signal.SIGTERM)
        deadline = time.time() + GATE_TERM_GRACE
        for r in running:
            try:
                r.proc.wait(timeout=max(0.0, deadline - time.time()))
            except subprocess.TimeoutExpired:
                # A gate that traps TERM itself runs the trap only once its foreground command returns.
                say(f"[gates] {r.gate.name} was still there {GATE_TERM_GRACE}s after its TERM; killed with its tree")
                kill_tree(r.proc.pid)
                r.proc.wait()
            r.log.close()
        progress_file.close()
        return 128 + signum

    wall = time.time() - t0
    failed = 0
    lines = []
    for g in gates:
        v, rc, secs = results.get(g.name, ("RED", -1, 0.0))
        if v == "SKIP":
            why = skip_reason.get(g.name, "explicit --skip")
            if args.release:
                failed = 1
                lines.append(f"RESULT {g.name} SKIP ({why}; not run, so the release run fails)")
            else:
                lines.append(f"RESULT {g.name} SKIP ({why})")
            continue
        if v == "RED" or (v == "EXPECTED_RED" and args.release):
            failed = 1
        if v == "EXPECTED_RED" and args.release:
            lines.append(f"RESULT {g.name} {v} (exit={rc}; still red, so the release run fails)")
        elif g.name in cached:
            lines.append(f"RESULT {g.name} {v} (exit={rc}; cached: same inputs went GREEN at {cached[g.name]['when']})")
        else:
            lines.append(f"RESULT {g.name} {v} (exit={rc})")
    (out_dir / "summary.txt").write_text("\n".join(lines) + "\n", encoding="utf-8")
    print("\n".join(lines))
    save_durations(history_path, durations)

    serial = sum(r[2] for r in results.values())
    speedup = f" ({serial / wall:.1f}x)" if wall > 0 else ""
    say(f"[gates] wall {wall / 60:.1f} min; the same gates back-to-back "
        f"would be {serial / 60:.1f} min{speedup}")
    progress_file.close()
    return failed


if __name__ == "__main__":
    sys.exit(main())
