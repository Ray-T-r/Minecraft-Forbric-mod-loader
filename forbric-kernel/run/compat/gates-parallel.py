#!/usr/bin/env python3
"""Scheduler behind gates-all.sh: run the checked-in gates with as much overlap as the machine allows.

WHY A SCHEDULER AND NOT `xargs -P`. The gates are not independent processes that happen to be slow. Each one
boots a real Minecraft server or client, and three things make a naive fan-out produce GREEN runs that prove
nothing:

  * THE PORT. Eighteen gates call seed_server_properties, which writes GATE_PORT into server.properties. Two of
    them at once and the loser prints "FAILED TO BIND TO PORT" and then still prints "Stopping server" -- so the
    clean-shutdown assertion passes and the gate reads green while its server never existed. lib.sh documents
    this exact failure. Every concurrent gate therefore gets its own port block, not a shared default.
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

-j 1 is exactly the old sequential behaviour: same order, same summary.txt, same exit code.

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
import os
import re
import subprocess
import sys
import time
from pathlib import Path

# Port blocks are handed out one per CONCURRENT SLOT, not one per gate: slot i gets PORT_BASE + i*PORT_STRIDE.
# A GATE_PORT already in the environment becomes the base, so `GATE_PORT=25599 gates-all.sh` still puts 25599 in
# front of the first gate -- the caller keeps the knob, and the slots keep their isolation.
#
# The default moved off 25599 because that is also gate-m12's own default (M12_PORT). The two could never
# collide while the gates ran one at a time; they collide on the first overlapping run.
PORT_BASE = 25700
PORT_STRIDE = 10

# The per-gate knobs that select a port. A gate uses at most one of these; handing it the whole block costs
# nothing and means a gate that grows a second server does not need the scheduler changed.
PORT_VARS = ("GATE_PORT", "M12_PORT", "M13_PORT", "M14_PORT", "M15_PORT", "M16_PORT", "M28_PORT")

DECL = re.compile(r"^#\s*GATE-PARALLEL:\s*(.*)$", re.M)


class Gate:
    def __init__(self, name: str, path: Path):
        self.name = name
        self.path = path
        self.source = path.read_text(encoding="utf-8", errors="replace")
        self.rundirs: set[str] = set()
        self.clone: tuple[str, str] | None = None
        self.mem = 0
        self.declared = False
        self._parse()

    def _parse(self) -> None:
        name = self.name
        m = DECL.search(self.source)
        if not m:
            return
        for field in m.group(1).split():
            key, _, value = field.partition("=")
            if key == "rundirs":
                self.rundirs = {r for r in value.replace(",", " ").split() if r}
            elif key == "clone":
                source, _, variable = value.partition(":")
                if not source or not variable:
                    raise SystemExit(f"{name}: clone= wants <dir>:<ENV_VAR>, got {value!r}")
                self.clone = (source, variable)
            elif key == "mem":
                self.mem = int(value)
        self.declared = True

    @property
    def exclusive(self) -> bool:
        return not self.declared

    def expects_red(self) -> bool:
        return re.search(r"^# EXPECTED: RED until ", self.source, re.M) is not None


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
    """
    if destination.exists():
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

    undeclared = [g.name for g in gates if g.exclusive and g.name not in args.skip]

    out_dir.mkdir(parents=True, exist_ok=True)
    progress_file = (out_dir / "progress.log").open("w", encoding="utf-8", buffering=1)

    def say(line: str) -> None:
        progress_file.write(line + "\n")
        if args.progress:
            print(line, file=sys.stderr, flush=True)

    pending = [g for g in gates if g.name not in args.skip]
    skipped = [g for g in gates if g.name in args.skip]
    results: dict[str, tuple[str, int, float]] = {n: ("SKIP", 0, 0.0) for n in args.skip}

    running: list[Running] = []
    free_slots = list(range(jobs))
    held: set[str] = set()          # rundir keys currently claimed
    used_mb = 0
    exclusive_running = False
    t0 = time.time()

    say(f"[gates] {len(pending)} gates, -j {jobs}, budget {budget} MB, ports {port_base}+")
    if undeclared and jobs > 1:
        say("[gates] no '# GATE-PARALLEL:' line, so each of these runs ALONE: " + " ".join(undeclared))

    def start(gate: Gate) -> None:
        nonlocal used_mb, exclusive_running
        slot = free_slots.pop(0)
        env = dict(os.environ)
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

    while pending or running:
        progressed = False
        for gate in list(pending):
            if can_start(gate):
                pending.remove(gate)
                start(gate)
                progressed = True
        # Nothing runnable and nothing running: the head of the queue does not fit the budget on its own.
        # Run it anyway rather than hang -- the budget is a throttle, not a promise.
        if not running and pending:
            gate = pending.pop(0)
            say(f"[gates] {gate.name} wants {gate.mem} MB, over the {budget} MB budget — running it alone")
            start(gate)
            progressed = True
        if not running:
            break
        if progressed and pending:
            continue
        # Wait for whichever gate finishes first.
        while True:
            done = [r for r in running if r.proc.poll() is not None]
            if done:
                break
            time.sleep(0.25)
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

    wall = time.time() - t0
    failed = 0
    lines = []
    for g in gates:
        v, rc, secs = results.get(g.name, ("RED", -1, 0.0))
        if v == "SKIP":
            if args.release:
                failed = 1
                lines.append(f"RESULT {g.name} SKIP (explicit --skip; not run, so the release run fails)")
            else:
                lines.append(f"RESULT {g.name} SKIP (explicit --skip)")
            continue
        if v == "RED" or (v == "EXPECTED_RED" and args.release):
            failed = 1
        if v == "EXPECTED_RED" and args.release:
            lines.append(f"RESULT {g.name} {v} (exit={rc}; still red, so the release run fails)")
        else:
            lines.append(f"RESULT {g.name} {v} (exit={rc})")
    (out_dir / "summary.txt").write_text("\n".join(lines) + "\n", encoding="utf-8")
    print("\n".join(lines))

    serial = sum(r[2] for r in results.values())
    speedup = f" ({serial / wall:.1f}x)" if wall > 0 else ""
    say(f"[gates] wall {wall / 60:.1f} min; the same gates back-to-back "
        f"would be {serial / 60:.1f} min{speedup}")
    progress_file.close()
    return failed


if __name__ == "__main__":
    sys.exit(main())
