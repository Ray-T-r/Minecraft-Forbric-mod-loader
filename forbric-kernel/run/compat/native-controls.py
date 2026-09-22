#!/usr/bin/env python3
"""Pinned genuine-loader controls. Own-ecology behavior only, never cross-ecology emulation.

All mutable files live below kernel/build/native-controls. The source workspace/cache is read only.
prepare downloads official fixed installers, verifies available upstream SHA-1s, then runs those installers.
build uses installed native API jars, never a Forbric carrier. run preserves each attempt, including failure.
"""
import argparse
import hashlib
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
ORIGINAL = Path(os.environ.get("NATIVE_CONTROL_CACHE", "/Users/jerry/Documents/Forbric"))
MC = Path(os.environ.get("MC_DIR", Path.home() / "Library/Application Support/minecraft"))
VERSIONS = {"fabric": "0.19.5", "forge": "65.0.1", "neo": "26.2.0.88"}
INSTALLERS = {
    "forge": "https://maven.minecraftforge.net/net/minecraftforge/forge/26.2-65.0.1/forge-26.2-65.0.1-installer.jar",
    "neo": "https://maven.neoforged.net/releases/net/neoforged/neoforge/26.2.0.88/neoforge-26.2.0.88-installer.jar",
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
        launcher = ORIGINAL / "forbric-loader/run/downloads/fabric-server-26.2" / FABRIC_LAUNCHER
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


def compile_canaries():
    artifacts = BASE / "artifacts"
    modules = BASE / "compile/fabric-modules"
    modules.mkdir(parents=True, exist_ok=True)
    fapi = fapi_source()
    with zipfile.ZipFile(fapi) as archive:
        assert json.loads(archive.read("fabric.mod.json"))["version"] == "0.155.2+26.2"
        for name in archive.namelist():
            if name.startswith("META-INF/jars/") and name.endswith(".jar"):
                (modules / Path(name).name).write_bytes(archive.read(name))
    compiled = {}
    for family in VERSIONS:
        image = BASE / "native" / family
        if not (image / "installation.json").is_file():
            raise RuntimeError(f"prepare {family} before compiling native APIs")
        if family == "fabric":
            game = BASE / "compile/vanilla-server-26.2.jar"
            with zipfile.ZipFile(image / ".fabric/server/26.2-server.jar") as bundle:
                game.write_bytes(bundle.read("META-INF/versions/26.2/server-26.2.jar"))
            classpath = [game, *jars(image), *sorted(modules.glob("*.jar"))]
        elif family == "forge":
            game = image / "libraries/net/minecraftforge/forge/26.2-65.0.1/forge-26.2-65.0.1-server.jar"
            classpath = [game, *jars(image)]
        else:
            game = image / "libraries/net/neoforged/minecraft-server-patched/26.2.0.88/minecraft-server-patched-26.2.0.88.jar"
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
            metadata = "fabric.mod.json" if family == "fabric" else "META-INF/" + ("mods.toml" if family == "forge" else "neoforge.mods.toml")
            entries.append((metadata, SOURCE / family / metadata))
            for name, path in entries:
                info = zipfile.ZipInfo(name, (2026, 1, 1, 0, 0, 0)); info.compress_type = zipfile.ZIP_DEFLATED
                archive.writestr(info, path.read_bytes())
        compiled[family] = {"mod": record(artifact), "gameApi": record(game), "classpath": [record(p) for p in classpath], "javacCommand": command}
    copy_file(fapi, artifacts / fapi.name)
    write_json(artifacts / "build-inputs.json", {"sources": sources(), "javac": subprocess.check_output(["javac", "-version"], text=True), "families": compiled, "fabricApi": record(artifacts / fapi.name)})
    print(f"built all three fixed native API fixtures: {artifacts}")


def native_command(family, image, java_args):
    if family == "fabric":
        return ["java", *java_args, "-jar", FABRIC_LAUNCHER, "--nogui"]
    group, version = ("net/minecraftforge/forge", "26.2-65.0.1") if family == "forge" else ("net/neoforged/neoforge", "26.2.0.88")
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
    index = list(VERSIONS).index(family)
    port = 25651 + index + (10 if engine == "forbric" else 0)
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
        env["FORBRIC_OLD"] = str(ORIGINAL / "forbric-loader")
        env["FORBRIC_COMPAT_POLICY"] = "strict"
        env["FORBRIC_JVM"] = " ".join(java_args) + " " + env.get("FORBRIC_JVM", "")
        old = ORIGINAL / "forbric-loader/run"
        carrier_paths = [Path(env.get("MERGED", old / "merged-base/patched-mc-merged-26.2.jar")),
                         Path(env.get("FORGE_RT", old / "merged-base/forge-runtime-interop.jar")),
                         Path(env.get("NEO_RT", old / "neoforge-runtime/neoforge-runtime.jar")),
                         KERNEL / "build/libs/forbric-kernel-0.1.0-SNAPSHOT.jar"]
        carrier_inputs = [record(p) for p in carrier_paths]
        installation = {"kind": "Forbric sovereign kernel", "launcher": record(command[0])}
    before = {"sources": sources(), "modSet": [record(instance / "mods" / p.name) for p in mod_files],
              "carriers": carrier_inputs, "installation": installation, "buildInputs": build_inputs,
              "java": subprocess.check_output(["java", "-version"], stderr=subprocess.STDOUT, text=True),
              "command": command, "cwd": str(instance), "seed": SEED, "actions": ["await Done and 20 ticks", "nativecontrol", "save-all flush", "stop"],
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


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest="action", required=True)
    prepare = sub.add_parser("prepare"); prepare.add_argument("--family", choices=["all", *VERSIONS], default="all")
    sub.add_parser("build")
    run = sub.add_parser("run"); run.add_argument("--engine", choices=["native", "forbric"], default="native"); run.add_argument("--family", choices=["all", *VERSIONS], default="all"); run.add_argument("--timeout", type=int, default=300)
    diff = sub.add_parser("compare"); diff.add_argument("results", nargs=2)
    args = parser.parse_args(); BASE.mkdir(parents=True, exist_ok=True)
    if args.action == "prepare":
        for family in VERSIONS if args.family == "all" else [args.family]:
            install(family)
    elif args.action == "build":
        compile_canaries()
    elif args.action == "run":
        # Evaluate every declared arm even if the first fails. No scope reduction turns a failed set green.
        outcomes = []
        for family in VERSIONS:
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
    return 0


if __name__ == "__main__":
    sys.exit(main())
