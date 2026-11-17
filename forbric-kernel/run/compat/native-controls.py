#!/usr/bin/env python3
"""Pinned genuine-loader controls. Own-ecology behavior only, never cross-ecology emulation.

All mutable files live below kernel/build/native-controls. The source workspace/cache is read only.
prepare downloads official fixed installers, verifies available upstream SHA-1s, then runs those installers.
build uses installed native API jars, never a Forbric carrier. run preserves each attempt, including failure.

The behaviour controls (build, run, compare) have two arms, Fabric and NeoForge: Forbric loads no MinecraftForge mod, so
there is nothing of it to compare. MinecraftForge stays only as an install (`prepare --family forge`, never part of
`prepare`'s default) for the native MinecraftForge arms that remain: the C5c swap oracle (`swap --engine native`) creates
its world on the native MinecraftForge server (D32), and `breath-drown.py reference` runs the MinecraftForge canary on it
to regenerate gate-m57's native reference.
"""
import argparse
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import re
import shutil
import signal
import subprocess
import sys
import time
import uuid
import zipfile

KERNEL = Path(__file__).resolve().parents[2]
BASE = KERNEL / "build/native-controls"
SOURCE = KERNEL / "canary/native-controls"
ORIGINAL = Path(os.environ.get("NATIVE_CONTROL_CACHE", Path(os.environ["FORBRIC_OLD"]).resolve().parent
                              if os.environ.get("FORBRIC_OLD") else KERNEL.parent))


# What the Forbric arm launches from a stage (launch-kernel-server.sh's BASE and NEO_RT defaults, below <stage>/run).
STAGED_BASE = "neoforge-patched/patched-mc-neoforge-26.2.jar"
STAGED_CARRIER = "neoforge-runtime/neoforge-runtime.jar"


def forbric_stage():
    """The staged tree the Forbric arm boots: FORBRIC_OLD as the caller set it, like every gate and launcher reads it;
    without it, the stage `tools/dev.py prepare` writes (forbric-kernel/.dev/staged), which build.gradle also takes when
    FORBRIC_OLD is unset. With neither, the arm refuses, naming both: there is no other stage it may boot.

    Either way the stage must be one this tree's pins built: the build stamps beside its base and carrier
    (<jar>.pins) must begin with the installer's Pins.stamp(), dev.py's own readiness rule (stale_stamps), which
    push-and-run applies to the same files (stamp_problems). Otherwise it refuses, naming the stale stamps. A path is no
    provenance: in a worktree .dev links to the main checkout's, whose stage is an unstamped NeoForge 26.2.0.88 carrier
    with a base byte-identical to a .93 one (C6 REL review A F1), so taking it by presence booted another NeoForge than
    the one the arm reports.

    It used to fall back to the reference checkout's forbric-loader/, whose carrier is NeoForge 26.2.0.88 and unstamped
    (Stage 4 C6, critic 7). Before that it replaced FORBRIC_OLD with ORIGINAL/forbric-loader whatever the caller had set
    (map-misc 4.1: carrier dd54e73f instead of .stage4's 0e3ce8d9). NATIVE_CONTROL_CACHE still names the read-only
    reference workspace for the native caches.
    """
    given = os.environ.get("FORBRIC_OLD")
    prepared = KERNEL / ".dev/staged"
    if given:
        stage, origin = Path(given).resolve(), "FORBRIC_OLD"
    elif (prepared / "run" / STAGED_BASE).is_file():
        stage, origin = prepared.resolve(), "the prepared stage"
    else:
        raise RuntimeError("no stage for the Forbric arm: set FORBRIC_OLD to a prepared stage (its run/ holds "
                           f"{STAGED_BASE} and {STAGED_CARRIER}), or run `python3 tools/dev.py prepare` to write "
                           f"{prepared}. forbric-loader/ is not a stage for it: its carrier is NeoForge 26.2.0.88 and "
                           "unstamped.")
    dev = dev_tools()
    stale = dev.stale_stamps(stage / "run")
    if stale:
        raise RuntimeError(f"{origin} {stage} is not a stage this tree's pins built: "
                           + ", ".join(f"run/{name}" for name in stale)
                           + f" missing or not beginning with \"{dev.pins_stamp()}\". Run `python3 tools/dev.py prepare`, "
                           "or point FORBRIC_OLD at a stage it prepared.")
    return stage


def dev_tools():
    """tools/dev.py of this checkout (standard library only, nothing runs on import): its pins and stamp rule."""
    spec = importlib.util.spec_from_file_location("forbric_dev", Path(__file__).resolve().parents[3] / "tools/dev.py")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def control_port():
    """The port a control server binds: the caller's GATE_PORT (a lane's own block, as every gate takes it), else an OS
    ephemeral port. One server runs at a time in an invocation, so every arm and family of it can share the one port.
    It used to be 25651 + family index (+10 for Forbric), fixed for every lane on the machine."""
    given = os.environ.get("GATE_PORT")
    return int(given) if given else free_port()


MC = Path(os.environ.get("MC_DIR", Path.home() / "Library/Application Support/minecraft"))
VERSIONS = {"fabric": "0.19.5", "forge": "65.0.1", "neo": "26.2.0.93"}
# The families with a behaviour control (build, run): Fabric and NeoForge. "forge" is an install only (the swap oracle).
CONTROL_FAMILIES = ("fabric", "neo")
INSTALLERS = {
    "forge": "https://maven.minecraftforge.net/net/minecraftforge/forge/26.2-65.0.1/forge-26.2-65.0.1-installer.jar",
    "neo": "https://maven.neoforged.net/releases/net/neoforged/neoforge/26.2.0.93/neoforge-26.2.0.93-installer.jar",
}
FABRIC_LAUNCHER = "fabric-server-mc26.2-loader0.19.5-launcher1.1.2.jar"
SEED = 8035262


def sha(path, algorithm="sha256"):
    digest = hashlib.new(algorithm)
    with Path(path).open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def record(path):
    path = Path(path).resolve()
    return {"path": str(path), "bytes": path.stat().st_size, "sha256": sha(path)}


def write_json(path, value):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value, indent=2, ensure_ascii=False) + "\n")


def sources():
    return [record(p) for p in sorted(SOURCE.rglob("*")) if p.is_file()] + [record(__file__)]


def run_logged(command, cwd, log, timeout=900):
    with log.open("w") as output:
        result = subprocess.run(command, cwd=cwd, stdout=output, stderr=subprocess.STDOUT, timeout=timeout)
    if result.returncode:
        raise RuntimeError(f"command failed ({result.returncode}), retained {log}")


def copy_file(source, destination):
    destination.parent.mkdir(parents=True, exist_ok=True)
    shutil.copyfile(source, destination)


def fapi_source():
    return ORIGINAL / "forbric-kernel/run/client-merged-pack/mods/fabric-api-0.155.2+26.2.jar"


def install(family):
    image = BASE / "native" / family
    image.mkdir(parents=True, exist_ok=True)
    if family == "fabric":
        cache = ORIGINAL / "forbric-kernel/run/fab-server"
        # The official launcher jar has one copy on disk, in the reference checkout's forbric-loader download cache (a
        # download, not a stage). Stage 5 moves the fixtures the kernel still reads out of forbric-loader/ before it
        # deletes it; until then a checkout without it is named here rather than failing inside zipfile.
        launcher = ORIGINAL / "forbric-loader/run/downloads/fabric-server-26.2" / FABRIC_LAUNCHER
        missing = [str(path) for path in (launcher, cache / "libraries", cache / ".fabric/server") if not path.exists()]
        if missing:
            raise RuntimeError("prepare --family fabric copies the official Fabric server launcher and its native cache "
                               "from the read-only reference checkout (NATIVE_CONTROL_CACHE, else the parent of "
                               "FORBRIC_OLD, else this checkout), and these are not there: " + ", ".join(missing))
        with zipfile.ZipFile(launcher) as jar:
            properties = jar.read("install.properties").decode()
            assert "fabric-loader-version=0.19.5" in properties and "game-version=26.2" in properties
        copy_file(launcher, image / FABRIC_LAUNCHER)
        # Only immutable native distribution caches, never mods/processedMods/config/world from that instance.
        for rel in ("libraries", ".fabric/server"):
            shutil.copytree(cache / rel, image / rel, dirs_exist_ok=True)
        write_json(image / "installation.json", {"family": family, "loader": VERSIONS[family], "minecraft": "26.2",
                   "launcher": record(image / FABRIC_LAUNCHER), "cacheSource": str(cache), "kind": "official Fabric server launcher cache"})
        return
    downloads = BASE / "downloads"
    downloads.mkdir(exist_ok=True)
    url = INSTALLERS[family]
    installer = downloads / url.rsplit("/", 1)[1]
    checksum = installer.with_suffix(installer.suffix + ".sha1")
    for remote, target in ((url, installer), (url + ".sha1", checksum)):
        if not target.is_file():
            subprocess.run(["curl", "--fail", "--location", "--connect-timeout", "20", "--max-time", "180", "--output", str(target), remote], check=True)
    expected = checksum.read_text().strip().split()[0]
    if not re.fullmatch("[0-9a-fA-F]{40}", expected) or sha(installer, "sha1") != expected.lower():
        raise RuntimeError(f"official installer checksum mismatch: {installer}")
    with zipfile.ZipFile(installer) as archive:
        profile = json.loads(archive.read("install_profile.json"))
        assert profile["minecraft"] == "26.2"
        for name in ("install_profile.json", "version.json"):
            data = json.loads(archive.read(name))
            write_json(image / (name + ".input"), data)
            for library in data.get("libraries", []):
                artifact = library.get("downloads", {}).get("artifact", {})
                relative, expected_hash = artifact.get("path"), artifact.get("sha1")
                if not relative or not expected_hash:
                    continue
                source = MC / "libraries" / relative
                if source.is_file() and sha(source, "sha1") == expected_hash:
                    copy_file(source, image / "libraries" / relative)
    # Vanilla bundle copied from the native Fabric cache; native installers verify it and produce their own patches.
    vanilla = ORIGINAL / "forbric-kernel/run/.gate-downloads/fab-server/.fabric/server/26.2-server.jar"
    metadata = json.loads((MC / "versions/26.2/26.2.json").read_text())
    assert sha(vanilla, "sha1") == metadata["downloads"]["server"]["sha1"]
    suffix = "-bundled" if family == "forge" else ""
    copy_file(vanilla, image / f"libraries/net/minecraft/server/26.2/server-26.2{suffix}.jar")
    # The installer's existing-output verification is deliberately retained on repeated prepare calls.
    command = ["java", "-jar", str(installer), "--installServer", "."]
    run_logged(command, image, image / "installer-console.log")
    if "server installed successfully" not in (image / "installer-console.log").read_text():
        raise RuntimeError(f"no successful native installer result: {family}")
    write_json(image / "installation.json", {"family": family, "minecraft": "26.2", "loader": VERSIONS[family],
               "url": url, "upstreamSha1": expected, "installer": record(installer), "command": command,
               "log": record(image / "installer-console.log"), "kind": "official native --installServer"})


def jars(image):
    return sorted((image / "libraries").rglob("*.jar"))


def compile_canaries(families=CONTROL_FAMILIES):
    """Compile the canary of each family asked for, against that family's prepared image only: a NeoForge control
    needs no Fabric or MinecraftForge image. build-inputs.json keeps another family's earlier entry only while the canary
    sources it was built from are the current ones."""
    unknown = [family for family in families if family not in CONTROL_FAMILIES]
    if unknown:
        raise RuntimeError(f"no behaviour control for {', '.join(unknown)} (controls: {', '.join(CONTROL_FAMILIES)})")
    artifacts = BASE / "artifacts"
    current = sources()
    compiled = {}
    inputs_file = artifacts / "build-inputs.json"
    if inputs_file.is_file():
        earlier = json.loads(inputs_file.read_text())
        if earlier.get("sources") == current:
            compiled = {family: entry for family, entry in earlier.get("families", {}).items()
                        if family in CONTROL_FAMILIES and family not in families}
    for family in families:
        image = BASE / "native" / family
        if not (image / "installation.json").is_file():
            raise RuntimeError(f"prepare {family} before compiling native APIs")
    fapi = fapi_source()
    if "fabric" in families:
        modules = BASE / "compile/fabric-modules"
        modules.mkdir(parents=True, exist_ok=True)
        with zipfile.ZipFile(fapi) as archive:
            assert json.loads(archive.read("fabric.mod.json"))["version"] == "0.155.2+26.2"
            for name in archive.namelist():
                if name.startswith("META-INF/jars/") and name.endswith(".jar"):
                    (modules / Path(name).name).write_bytes(archive.read(name))
    for family in families:
        image = BASE / "native" / family
        if family == "fabric":
            game = BASE / "compile/vanilla-server-26.2.jar"
            with zipfile.ZipFile(image / ".fabric/server/26.2-server.jar") as bundle:
                game.write_bytes(bundle.read("META-INF/versions/26.2/server-26.2.jar"))
            classpath = [game, *jars(image), *sorted((BASE / "compile/fabric-modules").glob("*.jar"))]
        else:
            game = image / "libraries/net/neoforged/minecraft-server-patched/26.2.0.93/minecraft-server-patched-26.2.0.93.jar"
            classpath = [game, *jars(image)]
        destination = BASE / "compile" / family
        if destination.exists():
            shutil.rmtree(destination)
        destination.mkdir(parents=True)
        files = sorted((SOURCE / "common/src").rglob("*.java")) + sorted((SOURCE / family / "src").rglob("*.java"))
        command = ["javac", "-proc:none", "--release", "21", "-cp", os.pathsep.join(map(str, classpath)), "-d", str(destination), *map(str, files)]
        run_logged(command, BASE, BASE / "compile" / f"{family}.log")
        artifact = artifacts / f"nativecontrol{family}-1.0.0.jar"
        artifact.parent.mkdir(parents=True, exist_ok=True)
        # Deterministic jar bytes allow exact jar identity between arms and across an unchanged rebuild.
        with zipfile.ZipFile(artifact, "w", zipfile.ZIP_DEFLATED) as archive:
            entries = [(p.relative_to(destination).as_posix(), p) for p in sorted(destination.rglob("*.class"))]
            metadata = "fabric.mod.json" if family == "fabric" else "META-INF/neoforge.mods.toml"
            entries.append((metadata, SOURCE / family / metadata))
            for name, path in entries:
                info = zipfile.ZipInfo(name, (2026, 1, 1, 0, 0, 0)); info.compress_type = zipfile.ZIP_DEFLATED
                archive.writestr(info, path.read_bytes())
        compiled[family] = {"mod": record(artifact), "gameApi": record(game), "classpath": [record(p) for p in classpath], "javacCommand": command}
    copy_file(fapi, artifacts / fapi.name)
    write_json(inputs_file, {"sources": current, "javac": subprocess.check_output(["javac", "-version"], text=True), "families": compiled, "fabricApi": record(artifacts / fapi.name)})
    print(f"built the native API fixtures of {', '.join(families)}: {artifacts}")


def native_command(family, image, java_args):
    if family == "fabric":
        return ["java", *java_args, "-jar", FABRIC_LAUNCHER, "--nogui"]
    group, version = ("net/minecraftforge/forge", "26.2-65.0.1") if family == "forge" else ("net/neoforged/neoforge", "26.2.0.93")
    return ["java", *java_args, f"@libraries/{group}/{version}/unix_args.txt", "nogui"]


def terminate_owned(process):
    if process.poll() is not None:
        return
    os.killpg(process.pid, signal.SIGTERM)
    try:
        process.wait(15)
    except subprocess.TimeoutExpired:
        os.killpg(process.pid, signal.SIGKILL); process.wait(10)


def run_control(family, engine, timeout):
    run_id = f"{time.strftime('%Y%m%d-%H%M%S')}-{engine}-{family}-{uuid.uuid4().hex[:8]}"
    instance = BASE / "instances" / run_id
    result_dir = BASE / "results" / run_id
    instance.mkdir(parents=True); result_dir.mkdir(parents=True)
    token = uuid.uuid4().hex
    (instance / ".native-control-owned").write_text(token + "\n")
    artifacts = BASE / "artifacts"
    build_inputs = json.loads((artifacts / "build-inputs.json").read_text())
    canary = artifacts / f"nativecontrol{family}-1.0.0.jar"
    if sha(canary) != build_inputs["families"][family]["mod"]["sha256"]:
        raise RuntimeError("compiled fixture drift: rebuild and rerun both arms")
    mod_files = [canary] + ([artifacts / "fabric-api-0.155.2+26.2.jar"] if family == "fabric" else [])
    for mod in mod_files:
        copy_file(mod, instance / "mods" / mod.name)
    (instance / "eula.txt").write_text("eula=true\n")
    port = control_port()
    (instance / "server.properties").write_text(f"server-ip=127.0.0.1\nserver-port={port}\nonline-mode=false\nlevel-name=world\nlevel-seed={SEED}\nlevel-type=minecraft\\:flat\nmax-tick-time=-1\nview-distance=3\nsimulation-distance=3\npause-when-empty-seconds=0\nspawn-protection=0\n")
    java_args = ["-Xms256M", "-Xmx2G", "-Djava.awt.headless=true", f"-Dnativecontrol.root={instance}", f"-Dnativecontrol.token={token}"]
    env = os.environ.copy()
    if engine == "native":
        image = BASE / "native" / family
        for rel in ("libraries", ".fabric/server"):
            if (image / rel).exists():
                shutil.copytree(image / rel, instance / rel)
        for path in image.glob("*.jar"):
            copy_file(path, instance / path.name)
        command = native_command(family, image, java_args)
        carrier_inputs = [record(p) for p in jars(instance)] + [record(p) for p in sorted(instance.glob("*.jar"))]
        if family == "fabric":
            carrier_inputs += [record(p) for p in sorted((instance / ".fabric/server").glob("*.jar"))]
        else:
            for path in sorted((image / "libraries").rglob("unix_args.txt")):
                copy_file(path, instance / path.relative_to(image))
                carrier_inputs.append(record(instance / path.relative_to(image)))
        installation = json.loads((image / "installation.json").read_text())
    else:
        # This arm is explicitly Forbric. Caller owns Gradle scheduling; never run it as a "native" control.
        command = [str(KERNEL / "run/launch-kernel-server.sh")]
        env["RUNDIR"] = str(instance)
        stage = forbric_stage()
        env["FORBRIC_OLD"] = str(stage)
        env["FORBRIC_COMPAT_POLICY"] = "strict"
        env["FORBRIC_JVM"] = (env.get("FORBRIC_JVM", "") + " " + " ".join(java_args)).strip()
        old = stage / "run"
        # What launch-kernel-server.sh runs: NeoForge's patched game (BASE) and its one carrier (NEO_RT), the same
        # defaults. MERGED/FORGE_RT are retired with MinecraftForge; the launcher refuses a caller that still sets them.
        carrier_paths = [Path(env.get("BASE", old / "neoforge-patched/patched-mc-neoforge-26.2.jar")),
                         Path(env.get("NEO_RT", old / "neoforge-runtime/neoforge-runtime.jar")),
                         KERNEL / "build/libs/forbric-kernel-0.1.0-SNAPSHOT.jar"]
        carrier_inputs = [record(p) for p in carrier_paths]
        installation = {"kind": "Forbric sovereign kernel", "launcher": record(command[0])}
    before = {"sources": sources(), "modSet": [record(instance / "mods" / p.name) for p in mod_files],
              "carriers": carrier_inputs, "installation": installation, "buildInputs": build_inputs,
              "java": subprocess.check_output(["java", "-version"], stderr=subprocess.STDOUT, text=True),
              "command": command, "cwd": str(instance), "port": port, "seed": SEED, "actions": ["await Done and 20 ticks", "nativecontrol", "save-all flush", "stop"],
              "environment": {k: env[k] for k in ("FORBRIC_OLD", "FORBRIC_COMPAT_POLICY", "FORBRIC_JVM", "RUNDIR") if k in env}}
    write_json(result_dir / "inputs.json", before)
    for item in before["sources"]:
        path = Path(item["path"])
        copy_file(path, result_dir / "source-snapshot" / path.relative_to(KERNEL))
    copy_file(artifacts / "build-inputs.json", result_dir / "build-inputs.json")
    log = result_dir / "server.log"
    command_sent = False; stop_sent = False; timed_out = False
    started_at = time.monotonic(); error = ""
    with log.open("w") as output:
        process = subprocess.Popen(command, cwd=instance, env=env, stdin=subprocess.PIPE, stdout=output, stderr=subprocess.STDOUT, text=True, start_new_session=True)
        try:
            while process.poll() is None:
                text = log.read_text(errors="replace")
                if not command_sent and re.search(r"Done \(", text) and f"[NativeControl] READY family={family} ticks=20" in text:
                    process.stdin.write("nativecontrol\n"); process.stdin.flush(); command_sent = True
                if command_sent and not stop_sent and (instance / "probe.json").exists():
                    process.stdin.write("save-all flush\nstop\n"); process.stdin.flush(); stop_sent = True
                if time.monotonic() - started_at > timeout:
                    timed_out = True
                    if process.stdin and not stop_sent:
                        try:
                            process.stdin.write("stop\n"); process.stdin.flush()
                        except BrokenPipeError:
                            pass
                    try:
                        process.wait(30)
                    except subprocess.TimeoutExpired:
                        terminate_owned(process)
                    break
                time.sleep(0.25)
        except BaseException as failure:
            error = repr(failure); terminate_owned(process)
        finally:
            if process.stdin:
                try:
                    process.stdin.close()
                except BrokenPipeError:
                    pass  # A failed startup can close stdin early; still retain its failure evidence.
    text = log.read_text(errors="replace")
    proof = None
    if (instance / "probe.json").exists():
        copy_file(instance / "probe.json", result_dir / "probe.json")
        try:
            proof = json.loads((result_dir / "probe.json").read_text())
        except ValueError as failure:
            error += str(failure)
    inputs = before["sources"] + before["modSet"] + before["carriers"]
    drift = [item["path"] for item in inputs if not Path(item["path"]).is_file() or sha(item["path"]) != item["sha256"]]
    checks = {
        "startup": bool(re.search(r"Done \(", text)), "commandSent": command_sent, "stopSent": stop_sent,
        "cleanExit": process.returncode == 0, "noTimeout": not timed_out, "noInputDrift": not drift,
        "freshProof": bool(proof and proof.get("token") == token and proof.get("family") == family),
        "lifecycle": bool(proof and proof.get("initialized") == proof.get("started") == proof.get("commandRegistrations") == 1 and proof.get("ticks", 0) >= 20),
        "worldActions": bool(proof and proof.get("pass") is True and proof.get("actions") == proof.get("expectedActions") == 3 and proof.get("seed") == SEED and proof.get("observedBlock") == f"nativecontrol{family}:probe"),
        "passMarker": f"[NativeControl] PASS family={family}" in text,
        "noFailMarker": "[NativeControl] FAIL" not in text,
    }
    result = {"schemaVersion": 1, "engine": engine, "family": family, "loaderVersion": VERSIONS[family], "minecraft": "26.2",
              "pass": all(checks.values()) and not error, "checks": checks, "exitCode": process.returncode,
              "timedOut": timed_out, "error": error, "inputDrift": drift, "seconds": round(time.monotonic() - started_at, 3),
              "proof": proof, "inputs": record(result_dir / "inputs.json"), "log": record(log), "instance": str(instance)}
    write_json(result_dir / "result.json", result)
    print(json.dumps({"family": family, "engine": engine, "pass": result["pass"], "checks": checks, "result": str(result_dir / "result.json")}, indent=2))
    return result["pass"]


def compare(paths):
    native, forbric = [json.loads(Path(p).read_text()) for p in paths]
    if native["engine"] != "native" or forbric["engine"] != "forbric" or native["family"] != forbric["family"]:
        raise RuntimeError("compare requires a native and Forbric result from the same ecology")
    inputs = [json.loads(Path(result["inputs"]["path"]).read_text()) for result in (native, forbric)]
    mods = [{Path(item["path"]).name: item["sha256"] for item in arm["modSet"]} for arm in inputs]
    same = mods[0] == mods[1] and inputs[0]["seed"] == inputs[1]["seed"] and inputs[0]["actions"] == inputs[1]["actions"]
    verdict = "INPUT_MISMATCH" if not same else ("MATCHED_PASS" if native["pass"] and forbric["pass"] else "FORBRIC_FAILURE" if native["pass"] else "NATIVE_FAILURE_INCONCLUSIVE")
    print(json.dumps({"family": native["family"], "verdict": verdict, "native": native["checks"], "forbric": forbric["checks"], "sameModHashesSeedActions": same}, indent=2))
    return verdict == "MATCHED_PASS"


# --- mod sets: the same jars on a native loader and on Forbric, server side -----------------------------------------

DONE, CRASH, FAILED_TO_START, STALL = "DONE", "CRASH", "FAILED_TO_START", "STALL"
SERVER_OUTCOMES = (DONE, CRASH, FAILED_TO_START, STALL)
# control-diff.sh's Done-detection and its two start failures, so the two tools cannot disagree about a log.
_DONE = re.compile(r"Done \(")
_START_FAILED = re.compile(r'Failed to start the minecraft server|Exception in thread "main"|\[Forbric/Compatibility\] launch stopped')
# What ends a running server: its crash report, the run loop's catch, an exception escaping the server or main thread,
# a failed stop, the JVM's own fatal error. A mod that logs a caught exception and keeps ticking is not a crash, and
# neither is a worker thread dying (counted as evidence, not as an outcome).
_CRASHED = re.compile(r'---- Minecraft Crash Report ----|Encountered an unexpected exception|Exception in thread "(?:Server thread|main)"'
                      r'|Exception stopping the server|A fatal error has been detected by the Java Runtime Environment')
_OTHER_THREAD = re.compile(r'Exception in thread "(?!Server thread"|main")[^"]*"')
# 26.2 answers "The game time is 2 tick(s)"; earlier versions "The time is 2".
_GAMETIME = re.compile(r"The (?:game )?time is (\d+)")
_STOP_ACK = "Stopping the server"


def server_outcome(log, ticks=0, exit_code=None):
    """DONE, CRASH, FAILED_TO_START or STALL for one server session's console.

    exit_code is the status of a process that ended on its own, None when the runner had to kill it (a timeout, or
    a JVM a non-daemon thread kept alive after the server had stopped). ticks > 0 also requires that many game
    ticks between the first and last `time query gametime` answers after Done.
    """
    done = _DONE.search(log)
    if not done:
        ended = exit_code is not None or _START_FAILED.search(log) or _CRASHED.search(log)
        return FAILED_TO_START if ended else STALL
    after = log[done.end():]
    if _CRASHED.search(after):
        return CRASH
    times = [int(value) for value in _GAMETIME.findall(after)]
    reached = ticks <= 0 or (times and max(times) - times[0] >= ticks)
    if exit_code is not None and (_STOP_ACK not in after or not reached):
        # The JVM went away by itself before this runner's stop finished the session.
        return CRASH if exit_code != 0 or not reached else DONE
    return DONE if reached and _STOP_ACK in after else STALL


def other_thread_failures(log):
    """Uncaught exceptions of threads other than the server and main threads: evidence only."""
    return sorted(set(_OTHER_THREAD.findall(log)))


def mod_set(paths):
    """Name, size and SHA-256 of each jar, and one SHA-256 over the sorted jar digests: the set's identity by bytes."""
    rows = sorted(({"name": Path(p).name, "bytes": Path(p).stat().st_size, "sha256": sha(p)} for p in paths), key=lambda r: r["name"])
    digest = hashlib.sha256("\n".join(sorted(r["sha256"] for r in rows)).encode()).hexdigest()
    return rows, digest


def _raise_interrupt(signum, frame):
    raise KeyboardInterrupt(f"signal {signum}")


def free_port():
    import socket
    with socket.socket() as probe:
        probe.bind(("127.0.0.1", 0))
        return probe.getsockname()[1]


def kernel_jar():
    """Build this checkout's kernel jar the way launch-kernel-server.sh does, and return it."""
    build = subprocess.run([str(KERNEL / "gradlew"), "--offline", "-q", "-p", str(KERNEL), "jar"], capture_output=True, text=True)
    if build.returncode:
        raise RuntimeError("kernel jar build failed: " + build.stdout[-2000:] + build.stderr[-2000:])
    found = sorted((KERNEL / "build/libs").glob("forbric-kernel-*.jar"))
    if not found:
        raise RuntimeError("kernel jar build produced no jar")
    return found[0]


SET_EVIDENCE = ["crash-reports/*.txt", "hs_err_pid*.log", ".forbric-kernel/compatibility-report.json", ".forbric-kernel/load-report.txt",
                ".forbric-kernel/crash-analysis.txt", ".forbric-kernel/merge-report.txt", "forbric-mods.txt"]


def run_set(engine, family, mods, ticks=200, timeout=900, xmx="3G", level_type="minecraft:normal", policy="strict", keep=False):
    """One fresh server with exactly these jars; returns the result dict also written to results/<run>/result.json."""
    mods = [Path(m).resolve() for m in mods]
    missing = [str(m) for m in mods if not m.is_file()]
    if missing:
        raise RuntimeError("missing mod jars: " + ", ".join(missing))
    if len({m.name for m in mods}) != len(mods):
        raise RuntimeError("two mod jars share a file name")
    run_id = f"{time.strftime('%Y%m%d-%H%M%S')}-set-{engine}-{family}-{uuid.uuid4().hex[:8]}"
    instance, result_dir = BASE / "instances" / run_id, BASE / "results" / run_id
    instance.mkdir(parents=True); result_dir.mkdir(parents=True)
    (instance / ".native-control-owned").write_text(run_id + "\n")
    for mod in mods:
        copy_file(mod, instance / "mods" / mod.name)
    rows, digest = mod_set(instance / "mods" / m.name for m in mods)
    if digest != mod_set(mods)[1]:
        raise RuntimeError("copied mod set differs from its source")
    (instance / "eula.txt").write_text("eula=true\n")
    port = free_port()
    (instance / "server.properties").write_text(f"server-ip=127.0.0.1\nserver-port={port}\nonline-mode=false\nlevel-name=world\nlevel-seed={SEED}\n"
                                                f"level-type={level_type.replace(':', chr(92) + ':')}\nmax-tick-time=-1\nview-distance=3\n"
                                                "simulation-distance=3\npause-when-empty-seconds=0\nspawn-protection=0\nsync-chunk-writes=false\n")
    java_args = ["-Xms256M", f"-Xmx{xmx}", "-Djava.awt.headless=true"]
    env = os.environ.copy()
    if engine == "native":
        image = BASE / "native" / family
        if not (image / "installation.json").is_file():
            raise RuntimeError(f"prepare --family {family} before running a native set")
        for rel in ("libraries", ".fabric/server"):
            if (image / rel).exists():
                shutil.copytree(image / rel, instance / rel)
        for path in image.glob("*.jar"):
            copy_file(path, instance / path.name)
        if family != "fabric":
            for path in sorted((image / "libraries").rglob("unix_args.txt")):
                copy_file(path, instance / path.relative_to(image))
        command = native_command(family, image, java_args)
        identity = {"loader": VERSIONS[family], "launcher": {k: v for k, v in record(instance / FABRIC_LAUNCHER).items() if k != "path"}
                    if family == "fabric" else json.loads((image / "installation.json").read_text()).get("installer")}
        watched = []
    else:
        jar = kernel_jar()
        command = [str(KERNEL / "run/launch-kernel-server.sh")]
        env["RUNDIR"] = str(instance)
        stage = forbric_stage()
        env["FORBRIC_OLD"] = str(stage)
        # A dedicated server has no window to ask on, so the product default (ask) refuses exactly like strict.
        env["FORBRIC_COMPAT_POLICY"] = policy
        env["FORBRIC_JVM"] = (env.get("FORBRIC_JVM", "") + " " + " ".join(java_args)).strip()
        old = stage / "run"
        # The launcher's two inputs, with launch-kernel-server.sh's BASE and NEO_RT defaults (as run_control records them).
        carriers = {"base": Path(env.get("BASE", old / "neoforge-patched/patched-mc-neoforge-26.2.jar")),
                    "neoRuntime": Path(env.get("NEO_RT", old / "neoforge-runtime/neoforge-runtime.jar"))}
        identity = {"kernel": {"name": jar.name, "bytes": jar.stat().st_size, "sha256": sha(jar)},
                    "carriers": {k: {"name": v.name, "sha256": sha(v)} for k, v in carriers.items()}, "policy": policy}
        watched = [jar]
    inputs = {"engine": engine, "family": family, "modSet": rows, "modSetSha256": digest, "identity": identity, "ticks": ticks,
              "seed": SEED, "levelType": level_type, "xmx": xmx, "command": command, "instance": str(instance),
              "java": subprocess.check_output(["java", "-version"], stderr=subprocess.STDOUT, text=True).strip()}
    write_json(result_dir / "inputs.json", inputs)
    log = result_dir / "server.log"
    # The server runs in its own session so it can be killed as a group; a runner stopped by SIGTERM (a timeout
    # wrapper, a driver's cancel) must take it along instead of orphaning a JVM that keeps ticking.
    import threading
    previous = signal.signal(signal.SIGTERM, _raise_interrupt) if threading.current_thread() is threading.main_thread() else None
    started = time.monotonic(); stop_sent = timed_out = killed = lingered = False
    done_at = failed_at = crash_at = None; last_query = 0.0
    with log.open("w") as output:
        process = subprocess.Popen(command, cwd=instance, env=env, stdin=subprocess.PIPE, stdout=output, stderr=subprocess.STDOUT,
                                   text=True, start_new_session=True)

        def send(line):
            try:
                process.stdin.write(line + "\n"); process.stdin.flush()
            except (BrokenPipeError, ValueError, OSError):
                pass
        try:
            while process.poll() is None:
                now = time.monotonic()
                text = log.read_text(errors="replace")
                done = _DONE.search(text)
                if done and done_at is None:
                    done_at = now
                if not done and _START_FAILED.search(text) and failed_at is None:
                    failed_at = now
                if done and _CRASHED.search(text[done.end():]) and crash_at is None:
                    crash_at = now
                if (failed_at and now - failed_at > 20) or (crash_at and now - crash_at > 30):
                    terminate_owned(process); killed = True; break
                if done and not stop_sent and not crash_at:
                    times = [int(v) for v in _GAMETIME.findall(text[done.end():])]
                    if times and max(times) - times[0] >= ticks:
                        send("save-all flush"); send("stop"); stop_sent = now
                    elif now - last_query >= 2:
                        send("time query gametime"); last_query = now
                if stop_sent and now - stop_sent > 90:
                    # The server stopped (or not) but the JVM stays: a non-daemon thread outlived it.
                    lingered = _STOP_ACK in text[done.end():] if done else False
                    terminate_owned(process); killed = True; break
                if now - started > timeout:
                    timed_out = True
                    send("stop")
                    try:
                        process.wait(30)
                    except subprocess.TimeoutExpired:
                        terminate_owned(process); killed = True
                    break
                time.sleep(0.5)
        except BaseException:
            terminate_owned(process); killed = True
            raise
        finally:
            try:
                process.stdin.close()
            except (BrokenPipeError, OSError):
                pass
            process.wait()
            if previous is not None:
                signal.signal(signal.SIGTERM, previous)
    text = log.read_text(errors="replace")
    exit_code = None if killed or timed_out else process.returncode
    outcome = server_outcome(text, ticks, exit_code)
    for pattern in SET_EVIDENCE:
        for path in sorted(instance.glob(pattern)):
            copy_file(path, result_dir / path.relative_to(instance))
    drift = [m.name for m in mods if sha(instance / "mods" / m.name) != next(r["sha256"] for r in rows if r["name"] == m.name)]
    drift += [p.name for p in watched if sha(p) != identity["kernel"]["sha256"]]
    sys.path.insert(0, str(Path(__file__).resolve().parent / "mac"))
    import ddmin_core
    crash_texts = [p.read_text(errors="replace") for p in sorted((result_dir / "crash-reports").glob("*.txt"))]
    report = result_dir / ".forbric-kernel/compatibility-report.json"
    signature = None if outcome == DONE else ddmin_core.signature(text, crash_texts[0] if crash_texts else "",
                                                                    report.read_text() if report.is_file() else "", process.returncode)
    done = _DONE.search(text)
    times = [int(v) for v in _GAMETIME.findall(text[done.end():])] if done else []
    result = {"schemaVersion": 1, "engine": engine, "family": family, "outcome": outcome, "signature": signature,
              "exitCode": process.returncode, "exitedOnItsOwn": exit_code is not None, "timedOut": timed_out,
              "lingeredAfterStop": lingered, "seconds": round(time.monotonic() - started, 1),
              "secondsToDone": round(done_at - started, 1) if done_at else None,
              "ticksRequested": ticks, "gametime": [times[0], max(times)] if times else None,
              "otherThreadFailures": other_thread_failures(text), "crashReports": len(crash_texts), "inputDrift": drift,
              "modSetSha256": digest, "jars": len(rows), "identity": identity, "inputs": str(result_dir / "inputs.json"),
              "log": str(log), "result": str(result_dir / "result.json")}
    write_json(result_dir / "result.json", result)
    if not keep and outcome == DONE:
        shutil.rmtree(instance, ignore_errors=True)
    return result


# --- swap: a world written with a mod's MinecraftForge build, opened with its NeoForge build (C5c) -------------------
#
# The native oracle for "does the swap lose it natively too?" (D32: the swap oracle keeps native-controls' MinecraftForge
# install). Native: create a world on the MinecraftForge 26.2-65.0.1 image with the Forge builds and a console script,
# reopen it once there (old A/A), then open it on the NeoForge 26.2.0.93 image with the NeoForge builds and reopen.
# Forbric: open a world the old kernel wrote (forge-swap-make-old.sh) with the NeoForge builds on this checkout's kernel,
# then reopen. Every phase is one server session (Done, the phase's console lines, save-all flush, stop) and every
# phase's world is exported with world-snapshot.py; the swap's diffs are written beside them. Instances are APFS clones
# of the prepared images (`cp -c -R`), never a byte copy of their libraries and never `prepare` (which downloads).

SWAP_PHASES = {"native": ("forge-create", "forge-reopen", "neo-open", "neo-reopen"), "forbric": ("neo-open", "neo-reopen")}
# The answer to `save-all flush`, and only that: a server prints "All dimensions are saved" on its way down too, so with
# that line a save-all nobody answered still came out saved once `stop` ran (review A-4). `stop` never prints "Saved the
# game" (measured: one per session log in every swap run, old, native and Forbric arms), so any one after the save-all
# is its answer, even one that comes after the stop was sent.
SAVED = re.compile(r"Saved the game")


def clone_tree(source, destination):
    """An APFS clone of a directory (cp -c -R) where the filesystem has one; a plain copy otherwise."""
    destination.parent.mkdir(parents=True, exist_ok=True)
    if subprocess.run(["cp", "-c", "-R", str(source), str(destination)], capture_output=True).returncode == 0 \
            and destination.is_dir() and not destination.is_symlink():
        return destination
    if destination.exists():
        shutil.rmtree(destination)
    shutil.copytree(source, destination, symlinks=True)
    return destination


def console_lines(path):
    """The console script: one server command per line; blank lines and #-comments skipped; `wait <seconds>` pauses."""
    if not path:
        return []
    lines = []
    for raw in Path(path).read_text().splitlines():
        line = raw.strip()
        if line and not line.startswith("#"):
            lines.append(line)
    return lines


def _snapshot_module():
    spec = importlib.util.spec_from_file_location("world_snapshot", Path(__file__).resolve().with_name("world-snapshot.py"))
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def server_properties(port, level_type="minecraft:flat"):
    return (f"server-ip=127.0.0.1\nserver-port={port}\nonline-mode=false\nlevel-name=world\nlevel-seed={SEED}\n"
            f"level-type={level_type.replace(':', chr(92) + ':')}\ngenerate-structures=false\nmax-tick-time=-1\n"
            "view-distance=4\nsimulation-distance=4\npause-when-empty-seconds=0\nspawn-protection=0\nsync-chunk-writes=true\n")


def swap_session(command, cwd, env, log, lines, timeout=900, settle=3.0, save_wait=60):
    """One server session: wait for Done, send `lines` (a `wait N` line sleeps N seconds), save-all flush, stop.

    Returns {"outcome", "saved", "exitCode", "timedOut", "seconds"}: outcome is server_outcome(log, 0, exit_code), and
    saved says the server answered the final save-all flush (a later "Saved the game" than the one it was sent after);
    a save not answered within save_wait seconds is not waited for further, the stop is sent anyway."""
    started = time.monotonic(); killed = timed_out = False
    done_at = failed_at = save_sent = stop_sent = None
    save_from = 0
    queue = list(lines)
    pause_until = 0.0
    with Path(log).open("w") as output:
        process = subprocess.Popen(command, cwd=cwd, env=env, stdin=subprocess.PIPE, stdout=output, stderr=subprocess.STDOUT,
                                   text=True, start_new_session=True)

        def send(line):
            try:
                process.stdin.write(line + "\n"); process.stdin.flush()
            except (BrokenPipeError, ValueError, OSError):
                pass
        try:
            while process.poll() is None:
                now = time.monotonic()
                text = Path(log).read_text(errors="replace")
                done = _DONE.search(text)
                if not done and _START_FAILED.search(text) and failed_at is None:
                    failed_at = now
                if failed_at and now - failed_at > 20:
                    terminate_owned(process); killed = True; break
                if done and done_at is None:
                    done_at = now; pause_until = now + settle
                if done_at and now >= pause_until and queue:
                    line = queue.pop(0)
                    wait = re.fullmatch(r"wait\s+(\d+(?:\.\d+)?)", line)
                    if wait:
                        pause_until = now + float(wait.group(1))
                    else:
                        send(line); pause_until = now + 0.25
                elif done_at and not queue and now >= pause_until and save_sent is None:
                    save_from = len(text); send("save-all flush"); save_sent = now
                elif save_sent and stop_sent is None and (SAVED.search(text, save_from) or now - save_sent > save_wait):
                    send("stop"); stop_sent = now
                if stop_sent and now - stop_sent > 120:
                    terminate_owned(process); killed = True; break
                if now - started > timeout:
                    timed_out = True; send("stop")
                    try:
                        process.wait(30)
                    except subprocess.TimeoutExpired:
                        terminate_owned(process); killed = True
                    break
                time.sleep(0.25)
        except BaseException:
            terminate_owned(process); killed = True
            raise
        finally:
            try:
                process.stdin.close()
            except (BrokenPipeError, OSError):
                pass
            process.wait()
    text = Path(log).read_text(errors="replace")
    exit_code = None if killed or timed_out else process.returncode
    return {"outcome": server_outcome(text, 0, exit_code), "saved": bool(save_sent and SAVED.search(text, save_from)),
            "exitCode": process.returncode, "timedOut": timed_out, "seconds": round(time.monotonic() - started, 1)}


def _native_instance(family, run_dir, mods, port):
    image = BASE / "native" / family
    if not (image / "installation.json").is_file():
        raise RuntimeError(f"no prepared native {family} image at {image} (clone one there with cp -c -R; prepare downloads)")
    instance = clone_tree(image, run_dir / f"{family}-instance")
    for mod in mods:
        copy_file(mod, instance / "mods" / Path(mod).name)
    (instance / "eula.txt").write_text("eula=true\n")
    (instance / "server.properties").write_text(server_properties(port))
    return instance


def swap(engine, forge_mods, neo_mods, out, console=None, checks=None, old_world=None, chunks=None, xmx="3G", timeout=900,
         after_create=None, old_config=None, create_jvm=None, port=None):
    """Run the swap's phases for one engine; returns the result written to <out>/result.json.

    The NeoForge instance starts from the old instance's config/ (a player swaps jars, not the instance): native, the
    MinecraftForge instance's; Forbric, old_config. after_create (native) is a command run with the MinecraftForge
    instance's path appended once the world is created, before the A/A reopen (forge-swap.py mark-configs).
    create_jvm (native) is a list of extra JVM arguments for the MinecraftForge create only (forge-swap's bag agent,
    which fills a packedup backpack the way the old side's create does); the A/A reopen and the NeoForge phases run
    without them. port: the server port every phase binds (a gate passes its GATE_PORT, so the port it checks is free
    at the end is the one the run used); without it an OS ephemeral port."""
    ws = _snapshot_module()
    out = Path(out).resolve(); out.mkdir(parents=True, exist_ok=True)
    forge_mods = [Path(m).resolve() for m in forge_mods]; neo_mods = [Path(m).resolve() for m in neo_mods]
    for m in forge_mods + neo_mods:
        if not m.is_file():
            raise RuntimeError(f"missing mod jar: {m}")
    box = tuple(int(v) for v in chunks.split(",")) if chunks else None
    java_args = ["-Xms256M", f"-Xmx{xmx}", "-Djava.awt.headless=true"]
    port = port or free_port()
    phases, snapshots = [], {}
    inputs = {"engine": engine, "seed": SEED, "chunks": box, "port": port,
              "forgeMods": mod_set(forge_mods)[0] if forge_mods else [], "neoMods": mod_set(neo_mods)[0],
              "console": record(console) if console else None, "checks": record(checks) if checks else None,
              "java": subprocess.check_output(["java", "-version"], stderr=subprocess.STDOUT, text=True).strip(),
              "createJvm": list(create_jvm or []),
              # The bytes of each -javaagent jar the create ran (review A-3: a path alone, in a scratch directory, names
              # nothing once that directory is gone).
              "createJvmAgents": [record(Path(arg[len("-javaagent:"):].split("=", 1)[0])) for arg in create_jvm or []
                                  if arg.startswith("-javaagent:") and Path(arg[len("-javaagent:"):].split("=", 1)[0]).is_file()]}

    def export(instance, name):
        snap = ws.export(instance / "world", box, label=f"{engine} {name}")
        (out / f"{name}.snapshot.json").write_text(json.dumps(snap, sort_keys=True))
        snapshots[name] = snap

    def run(phase, instance, command, env, lines):
        result = swap_session(command, instance, env, out / f"{phase}.log", lines, timeout)
        result["phase"] = phase
        phases.append(result)
        if result["outcome"] != DONE or not result["saved"]:
            raise RuntimeError(f"{engine} {phase}: {result['outcome']}, saved={result['saved']} (log {out / (phase + '.log')})")

    try:
        if engine == "native":
            if not forge_mods:
                raise RuntimeError("the native arm needs the MinecraftForge builds (--forge-mods)")
            forge = _native_instance("forge", out, forge_mods, port)
            inputs["forgeImage"] = json.loads((BASE / "native/forge/installation.json").read_text())
            command = native_command("forge", forge, java_args)
            run("forge-create", forge, native_command("forge", forge, java_args + list(create_jvm or [])), os.environ.copy(),
                console_lines(console))
            export(forge, "forge-create")
            if after_create:
                subprocess.run([*after_create, str(forge)], check=True)
            run("forge-reopen", forge, command, os.environ.copy(), [])
            export(forge, "forge-reopen")
            neo = _native_instance("neo", out, neo_mods, port)
            inputs["neoImage"] = json.loads((BASE / "native/neo/installation.json").read_text())
            clone_tree(forge / "world", neo / "world")
            if (forge / "config").is_dir():
                shutil.rmtree(neo / "config", ignore_errors=True)
                clone_tree(forge / "config", neo / "config")
            neo_command, neo_env = native_command("neo", neo, java_args), os.environ.copy()
            before = "forge-reopen"
        else:
            if not old_world or not (Path(old_world) / "level.dat").is_file():
                raise RuntimeError("the Forbric arm opens a world the old kernel wrote: --old-world <dir with level.dat>")
            neo = out / "neo-instance"
            neo.mkdir(parents=True, exist_ok=True)
            for mod in neo_mods:
                copy_file(mod, neo / "mods" / mod.name)
            (neo / "eula.txt").write_text("eula=true\n")
            (neo / "server.properties").write_text(server_properties(port))
            clone_tree(Path(old_world).resolve(), neo / "world")
            if old_config:
                clone_tree(Path(old_config).resolve(), neo / "config")
                inputs["oldConfig"] = str(Path(old_config).resolve())
            snapshots["old"] = ws.export(neo / "world", box, label="forbric old (as given)")
            (out / "old.snapshot.json").write_text(json.dumps(snapshots["old"], sort_keys=True))
            stage = forbric_stage()
            neo_env = os.environ.copy()
            neo_env.update({"RUNDIR": str(neo), "FORBRIC_OLD": str(stage), "FORBRIC_COMPAT_POLICY": "strict",
                            "FORBRIC_JVM": (os.environ.get("FORBRIC_JVM", "") + " " + " ".join(java_args)).strip()})
            neo_command = [str(KERNEL / "run/launch-kernel-server.sh")]
            inputs["stage"] = {"FORBRIC_OLD": str(stage),
                               "base": record(Path(neo_env.get("BASE", stage / "run/neoforge-patched/patched-mc-neoforge-26.2.jar"))),
                               "neoRuntime": record(Path(neo_env.get("NEO_RT", stage / "run/neoforge-runtime/neoforge-runtime.jar")))}
            inputs["oldWorld"] = str(Path(old_world).resolve())
            before = "old"
        run("neo-open", neo, neo_command, neo_env, console_lines(checks))
        export(neo, "neo-open")
        run("neo-reopen", neo, neo_command, neo_env, [])
        export(neo, "neo-reopen")
    except RuntimeError as failure:
        # Every phase that ran keeps its log, instance and snapshot; nothing is compared past the failure.
        result = {"schemaVersion": 1, "engine": engine, "phases": phases, "diffs": {}, "error": str(failure), "out": str(out),
                  "instances": sorted(str(p) for p in out.glob("*-instance"))}
        write_json(out / "result.json", result)
        return result
    finally:
        write_json(out / "inputs.json", inputs)
    diffs = {}
    pairs = [("swap", before, "neo-open", "upgrade"), ("reopen", "neo-open", "neo-reopen", "reopen")]
    if engine == "native":
        pairs.insert(0, ("forge-aa", "forge-create", "forge-reopen", "reopen"))
    for name, a, b, mode in pairs:
        found, rules = ws.diff(snapshots[a], snapshots[b], mode)
        report = ws.diff_report(found, rules)
        write_json(out / f"diff-{name}.json", report)
        diffs[name] = {"a": a, "b": b, "rules": mode, "count": report["count"], "byFacet": report["by_facet"],
                       "rulesUsed": report["rules_used"]}
    result = {"schemaVersion": 1, "engine": engine, "phases": phases, "diffs": diffs, "out": str(out),
              "instances": sorted(str(p) for p in out.glob("*-instance"))}
    write_json(out / "result.json", result)
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest="action", required=True)
    # "all" is the two behaviour controls; the MinecraftForge image (the swap oracle's) is installed only when named.
    prepare = sub.add_parser("prepare"); prepare.add_argument("--family", choices=["all", *VERSIONS], default="all")
    build = sub.add_parser("build"); build.add_argument("--family", choices=["all", *CONTROL_FAMILIES], default="all")
    run = sub.add_parser("run"); run.add_argument("--engine", choices=["native", "forbric"], default="native"); run.add_argument("--family", choices=["all", *CONTROL_FAMILIES], default="all"); run.add_argument("--timeout", type=int, default=300)
    diff = sub.add_parser("compare"); diff.add_argument("results", nargs=2)
    run_set_parser = sub.add_parser("run-set", help="one fresh server with exactly the given jars")
    run_set_parser.add_argument("--engine", choices=["native", "forbric"], required=True)
    run_set_parser.add_argument("--family", choices=["fabric"], default="fabric")
    run_set_parser.add_argument("--mods", nargs="+", required=True)
    run_set_parser.add_argument("--ticks", type=int, default=200)
    run_set_parser.add_argument("--timeout", type=int, default=900)
    run_set_parser.add_argument("--xmx", default="3G")
    run_set_parser.add_argument("--level-type", default="minecraft:normal")
    run_set_parser.add_argument("--keep", action="store_true", help="keep the instance directory")
    run_set_parser.add_argument("--policy", choices=["strict", "continue", "ask"], default="strict",
                                help="Forbric arm only: -Dforbric.compatibilityPolicy (continue shows what a refused launch would do)")
    swap_parser = sub.add_parser("swap", help="a world written with mods' MinecraftForge builds, opened with their NeoForge builds")
    swap_parser.add_argument("--engine", choices=["native", "forbric"], required=True)
    swap_parser.add_argument("--forge-mods", nargs="*", default=[], help="native: the MinecraftForge builds the world is created with")
    swap_parser.add_argument("--neo-mods", nargs="+", required=True, help="the NeoForge builds the world is opened with")
    swap_parser.add_argument("--console", help="native: console lines run once after Done on the MinecraftForge create")
    swap_parser.add_argument("--checks", help="console lines run once after Done on the NeoForge open (in-game checks)")
    swap_parser.add_argument("--old-world", help="forbric: the world the old kernel wrote (it is cloned, never written)")
    swap_parser.add_argument("--old-config", help="forbric: the old instance's config/ (cloned into the NeoForge instance)")
    swap_parser.add_argument("--after-create", help="native: a command run with the MinecraftForge instance appended after the create")
    swap_parser.add_argument("--create-jvm", action="append", default=[],
                             help="native: an extra JVM argument for the MinecraftForge create only (repeatable)")
    swap_parser.add_argument("--port", type=int, help="the server port every phase binds (default: an OS ephemeral port)")
    swap_parser.add_argument("--chunks", help="export box X0,Z0,X1,Z1 in chunk coordinates (world-snapshot.py --chunks)")
    swap_parser.add_argument("--out", required=True, help="result directory (instances, logs, snapshots, diffs)")
    swap_parser.add_argument("--xmx", default="3G")
    swap_parser.add_argument("--timeout", type=int, default=900, help="seconds per phase")
    args = parser.parse_args(); BASE.mkdir(parents=True, exist_ok=True)
    if args.action == "prepare":
        for family in CONTROL_FAMILIES if args.family == "all" else [args.family]:
            install(family)
    elif args.action == "build":
        compile_canaries(CONTROL_FAMILIES if args.family == "all" else (args.family,))
    elif args.action == "run":
        # Evaluate every declared arm even if the first fails. No scope reduction turns a failed set green.
        outcomes = []
        for family in CONTROL_FAMILIES:
            if args.family != "all" and family != args.family:
                continue
            try:
                outcomes.append(run_control(family, args.engine, args.timeout))
            except Exception as failure:
                failed = BASE / "results" / f"{time.strftime('%Y%m%d-%H%M%S')}-{args.engine}-{family}-infrastructure-{uuid.uuid4().hex[:8]}.json"
                write_json(failed, {"schemaVersion": 1, "engine": args.engine, "family": family, "pass": False,
                                   "error": repr(failure), "startup": False, "actions": 0,
                                   "expectedActions": 3, "sources": sources()})
                print(f"{family} infrastructure failure, retained {failed}: {failure}", file=sys.stderr)
                outcomes.append(False)
        return 0 if all(outcomes) else 1
    elif args.action == "compare":
        return 0 if compare(args.results) else 1
    elif args.action == "run-set":
        result = run_set(args.engine, args.family, args.mods, args.ticks, args.timeout, args.xmx, args.level_type, args.policy, args.keep)
        print(json.dumps({k: result[k] for k in ("engine", "outcome", "signature", "modSetSha256", "jars", "seconds", "result")}))
        return 0 if result["outcome"] == DONE else 1
    elif args.action == "swap":
        result = swap(args.engine, args.forge_mods, args.neo_mods, args.out, args.console, args.checks, args.old_world,
                      args.chunks, args.xmx, args.timeout, args.after_create.split() if args.after_create else None, args.old_config,
                      args.create_jvm, args.port)
        print(json.dumps({k: result.get(k) for k in ("engine", "error", "diffs", "out")}, indent=2))
        return 1 if result.get("error") else 0
    return 0


if __name__ == "__main__":
    sys.exit(main())
