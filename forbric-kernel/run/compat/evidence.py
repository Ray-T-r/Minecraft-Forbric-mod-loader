#!/usr/bin/env python3
"""Bind one validation run to its actual sources, tools, game artifacts and mod inventory.

This is provenance, not a test verdict. `verify` fails when ANY recorded input changes. A release
capture additionally requires the complete build input/output set, a clean committed source tree, the
pinned platform versions read out of the artifacts themselves, the jars the kernel build, its bytecode tests
and the gates' launchers actually read being the attested ones, the staged base and carrier stamped as built from the
pinned platform, and the vanilla-reference sidecar having been measured against the attested base and vanilla jar.
`release-check` compares the manifests of one acceptance with each other and with the files to publish.
No recorded command is executed when a manifest is read.
"""
import argparse
from datetime import datetime, timezone
import hashlib
import io
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import time
import tomllib
import zipfile

# What a release ships and runs on (Stage 4 of dropping MinecraftForge): vanilla, NeoForge's patched game (the base),
# its one carrier, the kernel and its runtime jar, and the vanilla-reference sidecar measured against the base. No
# merged base, no MinecraftForge carrier or interop jar, no merge tools: a role outside this set is not a release role,
# and release-check refuses to compare a published file against one.
RELEASE_ROLES = frozenset({"vanilla", "neo-patched", "neo-runtime", "kernel", "kernel-runtime", "sidecar"})

# The fixed platform (PLAN P0). A release reads each value out of the artifact it describes; a name, a path or a
# stamp beside the file is not the version of the bytes being accepted.
PINS = {"minecraft": "26.2", "neoforge": "26.2.0.93"}
# Which roles carry which reading: the game jars their own version.json, the carrier its manifest. The sidecar holds no
# version; what it records is the base and the vanilla jar it was measured against (sidecar_provenance).
MINECRAFT_ROLES = ("vanilla", "neo-patched")
NEOFORGE_ROLES = ("neo-runtime",)
# The installer's VanillaReference writes this beside the classes it measured; run/vanilla-reference-jar.sh reads it.
SIDECAR_PROPERTIES = "META-INF/forbric/vanilla-reference.properties"

# What forbric-kernel/build.gradle compiles kernel-runtime against and what its bytecode tests read (the base and the
# carrier), plus the sidecar the gates' launchers hand the kernel (run/vanilla-reference-jar.sh takes
# <stage>/vanilla-reference/ first while it records the base). The staged root resolves as build.gradle's stagedRoot
# does when no -Pforbric.stagedRoot is given (staged_root), and the build hands that root to its tests as FORBRIC_OLD.
STAGED_BUILD_INPUTS = {"neo-patched": "neoforge-patched/patched-mc-neoforge-26.2.jar",
                       "neo-runtime": "neoforge-runtime/neoforge-runtime.jar",
                       "sidecar": "vanilla-reference/vanilla-reference-26.2.jar"}

# The staged inputs DevPrepare builds and stamps (<jar>.pins: the installer's Pins.stamp() then its tools digest). The
# base declares no NeoForge version of its own (its version.json says only 26.2, and the patched game of another NeoForge
# can be byte-identical), so the stamp is the one record of which NeoForge built it; a release requires both stamps to
# name the pinned platform (check_staged_build_stamps).
STAMPED_ROLES = ("neo-patched", "neo-runtime")
# (pin, the stamp's key for it). Not a dict literal: tools/test_pins.py reads every '"<pin>": "<value>"' in this file
# as a pin.
STAMP_KEYS = (("minecraft", "mc"), ("neoforge", "neoforge"))

# The gate aggregator's verdict lines. A release acceptance must not contain a gate that was not run, or that is
# still red by declaration, however the aggregator's own exit code read.
UNPASSED_GATE = re.compile(r"^RESULT (\S+) (SKIP|EXPECTED_RED)\b.*$", re.M)


def digest(path, algorithm="sha256"):
    with Path(path).open("rb") as stream:
        return hashlib.file_digest(stream, algorithm).hexdigest()


def file_record(path):
    path = Path(path).resolve(strict=True)
    if not path.is_file() or path.stat().st_size == 0:
        raise ValueError(f"missing or empty input: {path}")
    before = path.stat()
    checksum = digest(path)
    after = path.stat()
    if (before.st_size, before.st_mtime_ns) != (after.st_size, after.st_mtime_ns):
        raise ValueError(f"input changed while hashing: {path}")
    return {"path": str(path), "size": after.st_size, "sha256": checksum}


def git(root, *args):
    return subprocess.run(["git", "-C", str(root), *args], check=True, capture_output=True).stdout


def source_record(root, excluded):
    root = Path(root).resolve(strict=True)
    revision = git(root, "rev-parse", "HEAD").decode().strip()
    tracked = set(git(root, "ls-files", "-z", "--cached").split(b"\0"))
    names = git(root, "ls-files", "-z", "--cached", "--others", "--exclude-standard").split(b"\0")
    files = {}
    for raw in sorted(set(names)):
        if not raw:
            continue
        name = raw.decode("utf-8")
        path = root / name
        if path.resolve() in excluded or path.is_dir():
            continue
        # Preserve deletion and symlink identity instead of reading arbitrary linked trees as source.
        files[name] = ("symlink:" + str(path.readlink()) if path.is_symlink() else
                       digest(path) if path.is_file() else "deleted")
    encoded = json.dumps(files, sort_keys=True, separators=(",", ":")).encode()
    changed = git(root, "diff", "--name-only", "-z", "HEAD").split(b"\0")
    dirty = any(raw and (root / raw.decode()).resolve() not in excluded for raw in changed)
    dirty = dirty or any(name.encode() not in tracked for name in files)
    return {"root": str(root), "commit": revision, "sha256": hashlib.sha256(encoded).hexdigest(),
            "files": files, "dirty": dirty}


def archive_metadata(data, origin, depth=0):
    if depth > 16:
        raise ValueError(f"nested mod archive exceeds 16 levels: {origin}")
    out = []
    with zipfile.ZipFile(io.BytesIO(data)) as jar:
        names = set(jar.namelist())
        declarations = []
        nested = set()
        if "fabric.mod.json" in names:
            # The real metadata reader accepts literal newlines/tabs in strings (the installed EMF/ETF
            # manifests use them in descriptions). Retain the original bytes/hash; do not rewrite jars.
            try:
                meta = json.loads(jar.read("fabric.mod.json"), strict=False)
            except (ValueError, UnicodeError) as invalid:
                raise ValueError(f"cannot inventory {origin}::fabric.mod.json: {invalid}") from invalid
            declarations.append({"ecosystem": "FABRIC", "id": meta["id"], "version": str(meta["version"])})
            nested.update(entry["file"] for entry in meta.get("jars", []))
        for name, family in [("META-INF/mods.toml", "FORGE"), ("META-INF/neoforge.mods.toml", "NEOFORGE")]:
            if name in names:
                meta = tomllib.loads(jar.read(name).decode("utf-8"))
                declarations.extend({"ecosystem": family, "id": m["modId"], "version": str(m["version"])}
                                    for m in meta.get("mods", []))
        if "META-INF/jarjar/metadata.json" in names:
            try:
                metadata = json.loads(jar.read("META-INF/jarjar/metadata.json"))
                for entry in metadata.get("jars", []):
                    path = entry["path"]
                    if not isinstance(path, str) or not path:
                        raise ValueError("nested path must be a nonempty string")
                    nested.add(path)
            except (KeyError, TypeError, ValueError, UnicodeError) as invalid:
                raise ValueError(f"cannot inventory {origin}::META-INF/jarjar/metadata.json: {invalid}") from invalid
        nested.update(name for name in names if name.endswith(".jar") and
                      name.startswith(("META-INF/jars/", "META-INF/jarjar/")))
        # Retain unresolved version expressions: guessing would falsify the inventory. The one expression with a
        # defined meaning is FML's ${file.jarVersion}: the archive's own Implementation-Version, which is read
        # from the same bytes and recorded beside the raw declaration, never in place of it.
        manifest = jar.read("META-INF/MANIFEST.MF").decode("utf-8", errors="replace") if "META-INF/MANIFEST.MF" in names else ""
        jar_version = manifest_attributes(manifest).get("Implementation-Version")
        for declaration in declarations:
            if declaration["version"] == "${file.jarVersion}":
                declaration["resolvedVersion"] = jar_version
        out.append({"archive": origin, "sha256": hashlib.sha256(data).hexdigest(),
                    "declarations": declarations, "manifest": manifest})
        for name in sorted(nested):
            if name not in names:
                raise ValueError(f"declared nested jar missing: {origin}::{name}")
            if jar.getinfo(name).file_size > 256 * 1024 * 1024:
                raise ValueError(f"nested jar too large to inventory: {origin}::{name}")
            out.extend(archive_metadata(jar.read(name), origin + "::" + name, depth + 1))
    return out


def manifest_attributes(text):
    """The MAIN section of a jar manifest: attributes up to the first blank line, continuation lines joined."""
    attributes, last = {}, None
    for line in text.replace("\r\n", "\n").split("\n"):
        if not line:
            break
        if line.startswith(" ") and last:
            attributes[last] += line[1:]
            continue
        name, separator, value = line.partition(": ")
        if separator:
            attributes[name], last = value, name
    return attributes


def platform_reading(role, path):
    """The platform version the artifact itself declares, or None when it declares none."""
    try:
        with zipfile.ZipFile(path) as jar:
            names = set(jar.namelist())
            if role in MINECRAFT_ROLES:
                return json.loads(jar.read("version.json"))["id"] if "version.json" in names else None
            main = manifest_attributes(jar.read("META-INF/MANIFEST.MF").decode("utf-8", errors="replace")
                                       if "META-INF/MANIFEST.MF" in names else "")
    except (zipfile.BadZipFile, KeyError, ValueError, OSError):
        return None
    version, title = main.get("Implementation-Version"), main.get("Implementation-Title")
    return version if title == "NeoForge" else None


def platform_record(artifacts, release):
    readings = {"minecraft": {}, "neoforge": {}}
    for family, roles in (("minecraft", MINECRAFT_ROLES), ("neoforge", NEOFORGE_ROLES)):
        for role in roles:
            if role in artifacts:
                readings[family][role] = platform_reading(role, artifacts[role])
    if release:
        wrong = [f"{role}={value or 'unreadable'} (pinned {PINS[family]})"
                 for family, roles in readings.items() for role, value in roles.items() if value != PINS[family]]
        if wrong:
            raise ValueError("release artifacts are not the pinned platform: " + ", ".join(wrong))
    return {"pins": dict(PINS), "readings": readings}


def staged_root(root, environment=None):
    """The staged root the kernel build reads, resolved in build.gradle's order (its stagedRoot, lines 31-34, without
    -Pforbric.stagedRoot): $FORBRIC_OLD/run; else forbric-kernel/.dev/staged/run when it holds the base (what
    `tools/dev.py prepare` stages); else forbric-loader/run, the build's last fallback, which Stage 5 removes there and
    here together. A release run sets FORBRIC_OLD or prepares .dev/staged; nothing prepares a sidecar into the old
    fallback (on this machine it also holds an unstamped 26.2.0.88 carrier), so a release that reaches it fails in
    check_staged_build_inputs."""
    environment = os.environ if environment is None else environment
    old = environment.get("FORBRIC_OLD")
    if old:
        return Path(old) / "run"
    dev = Path(root) / "forbric-kernel" / ".dev" / "staged" / "run"
    if (dev / STAGED_BUILD_INPUTS["neo-patched"]).is_file():
        return dev
    return Path(root) / "forbric-loader" / "run"


def staged_build_inputs(root, environment=None):
    run = staged_root(root, environment)
    out = {}
    for role, relative in STAGED_BUILD_INPUTS.items():
        path = (run / relative).absolute()
        out[role] = file_record(path) if path.is_file() else {"path": str(path), "missing": True}
    return out


def check_staged_build_inputs(staged, artifacts):
    wrong = []
    for role, record in staged.items():
        attested = artifacts.get(role)
        if record.get("missing") or attested is None or record["sha256"] != attested["sha256"]:
            wrong.append(f"{role}: the kernel build, its bytecode tests and the gates read {record['path']}"
                         f" ({'missing' if record.get('missing') else record['sha256'][:12]}), attested"
                         f" {attested['sha256'][:12] if attested else 'nothing'}")
    if wrong:
        raise ValueError("release evidence attests other jars than the build tests: " + "; ".join(wrong)
                         + ". Point FORBRIC_OLD at a staged root holding the attested candidate.")


def build_stamp(path):
    """A build stamp file as it reads: its path and its stripped text, or None for the text when there is no file."""
    try:
        stamp = Path(path).read_text(encoding="utf-8").strip()
    except OSError:
        stamp = None
    return {"path": str(path), "stamp": stamp}


def staged_build_stamps(staged):
    """The build stamp beside each stamped staged input (STAMPED_ROLES): <jar>.pins next to the recorded jar."""
    return {role: build_stamp(staged[role]["path"] + ".pins") for role in STAMPED_ROLES}


def check_staged_build_stamps(stamps):
    """A release's staged base and carrier were built from the pinned platform: each build stamp names
    mc=<minecraft pin> and neoforge=<neoforge pin>. A stage nobody re-prepared (unstamped) or one built from another
    NeoForge is not the candidate the release says it is, whatever its bytes hash to."""
    wrong = []
    for role in STAMPED_ROLES:
        record = stamps.get(role) or {}
        stamp = record.get("stamp")
        values = dict(item.partition("=")[::2] for item in (stamp or "").split())
        problems = [f"{key}={values.get(key, 'nothing')} (pinned {PINS[family]})" for family, key in STAMP_KEYS
                    if values.get(key) != PINS[family]]
        if stamp is None:
            wrong.append(f"{role}: {record.get('path', 'its build stamp')} is missing")
        elif problems:
            wrong.append(f"{role}: {record['path']} says " + ", ".join(problems))
    if wrong:
        raise ValueError("the staged base and carrier were not built from the pinned platform: " + "; ".join(wrong)
                         + ". Run tools/dev.py prepare, or point FORBRIC_OLD at a stage it prepared.")


def sidecar_provenance(artifacts, release):
    """What the vanilla-reference sidecar records it was measured against (its base-sha1 and vanilla-sha1), and, for a
    release, that those are the attested neo-patched and vanilla jars. A sidecar measured against another base costs
    every class the two bases disagree on its FABRIC reference without a word (run/vanilla-reference-jar.sh), so a
    release must not pair one with a base it was not measured against."""
    if "sidecar" not in artifacts:
        return None
    recorded = {}
    try:
        with zipfile.ZipFile(artifacts["sidecar"]) as jar:
            text = jar.read(SIDECAR_PROPERTIES).decode("utf-8", errors="replace")
        for line in text.splitlines():
            key, separator, value = line.strip().partition("=")
            if separator:
                recorded[key] = value
    except (zipfile.BadZipFile, KeyError, OSError):
        recorded = {}
    result = {"baseSha1": recorded.get("base-sha1"), "vanillaSha1": recorded.get("vanilla-sha1")}
    if release:
        wrong = []
        for key, role in (("baseSha1", "neo-patched"), ("vanillaSha1", "vanilla")):
            actual = digest(artifacts[role], "sha1") if role in artifacts else None
            if result[key] is None or result[key] != actual:
                wrong.append(f"it records {role} {result[key] or 'nothing'}, the attested {role} is {actual or 'absent'}")
        if wrong:
            raise ValueError("the sidecar was not measured against the attested jars: " + "; ".join(wrong)
                             + f" ({SIDECAR_PROPERTIES})")
    return result


def mod_record(directory):
    directory = Path(directory).resolve(strict=True)
    if not directory.is_dir():
        raise ValueError(f"mods input is not a directory: {directory}")
    jars = []
    for path in sorted(directory.glob("*.jar")):
        record = file_record(path)
        record["inventory"] = archive_metadata(path.read_bytes(), path.name)
        jars.append(record)
    return {"directory": str(directory), "jars": jars}


def capture(root, artifacts, mods, output, release=False):
    if release and (missing := RELEASE_ROLES - artifacts.keys()):
        raise ValueError("release evidence missing artifacts: " + ", ".join(sorted(missing)))
    if not artifacts:
        raise ValueError("at least one artifact is required")
    if release and not mods:
        raise ValueError("release evidence requires the tested mods directory, even for an empty-mod test")
    excluded = {Path(output).resolve()}
    before = source_record(root, excluded)
    if release and before["dirty"]:
        raise ValueError("release evidence requires committed source changes")
    artifact_records = {name: file_record(path) for name, path in sorted(artifacts.items())}
    platform = platform_record(artifacts, release)
    staged = staged_build_inputs(root)
    stamps = staged_build_stamps(staged)
    if release:
        check_staged_build_inputs(staged, artifact_records)
        check_staged_build_stamps(stamps)
    sidecar = sidecar_provenance(artifacts, release)
    mod_records = [mod_record(path) for path in mods]
    if before != source_record(root, excluded):
        raise ValueError("source changed while collecting evidence; retry on a stable build")
    result = {"schema": 1, "capturedAt": datetime.now(timezone.utc).isoformat(), "release": release,
              "source": before, "artifacts": artifact_records, "platform": platform,
              "stagedBuildInputs": staged, "stagedBuildStamps": stamps, "sidecarProvenance": sidecar,
              "mods": mod_records}
    output = Path(output)
    output.parent.mkdir(parents=True, exist_ok=True)
    temporary = output.with_name(output.name + ".tmp")
    temporary.write_text(json.dumps(result, indent=2, ensure_ascii=False) + "\n")
    temporary.replace(output)
    return result


def verify(path):
    path = Path(path).resolve(strict=True)
    saved = json.loads(path.read_text())
    if saved.get("schema") != 1:
        raise ValueError("unsupported evidence schema")
    source = saved["source"]
    if source_record(source["root"], {path}) != source:
        raise ValueError("source revision or contents changed since capture")
    for role, expected in saved["artifacts"].items():
        if file_record(expected["path"]) != expected:
            raise ValueError(f"artifact changed since capture: {role}")
    for expected in saved["mods"]:
        if mod_record(expected["directory"]) != expected:
            raise ValueError("mod inventory changed since capture: " + expected["directory"])
    for role, expected in saved.get("stagedBuildInputs", {}).items():
        if not expected.get("missing") and file_record(expected["path"]) != expected:
            raise ValueError(f"staged build input changed since capture: {role}")
    for role, expected in saved.get("stagedBuildStamps", {}).items():
        if build_stamp(expected["path"]) != expected:
            raise ValueError(f"staged build stamp changed since capture: {role}")
    if saved.get("release") and (RELEASE_ROLES - saved["artifacts"].keys() or not saved["mods"] or source["dirty"]):
        raise ValueError("incomplete release evidence")
    if saved.get("release"):
        platform_record({role: record["path"] for role, record in saved["artifacts"].items()}, True)
        if "stagedBuildInputs" not in saved:
            raise ValueError("incomplete release evidence: the jars the build and its tests read are not recorded")
        check_staged_build_inputs(saved["stagedBuildInputs"], saved["artifacts"])
        if "stagedBuildStamps" not in saved:
            raise ValueError("incomplete release evidence: the build stamps of the staged base and carrier are not recorded")
        check_staged_build_stamps(saved["stagedBuildStamps"])
        if not saved.get("sidecarProvenance"):
            raise ValueError("incomplete release evidence: the sidecar's base and vanilla jar are not recorded")
        sidecar_provenance({role: record["path"] for role, record in saved["artifacts"].items()}, True)
    return saved


def verified_run(root, artifacts, mods, output, command, release=False):
    """Run an acceptance command against fixed inputs, retaining its log and both verdicts separately."""
    root, output = Path(root).resolve(), Path(output).resolve()
    if not command:
        raise ValueError("an acceptance command is required after --")
    if release and "--skip" in command:
        raise ValueError("a release acceptance runs every gate; --skip makes it a sweep, not an acceptance")
    if release and any(Path(part).name == "gates-all.sh" for part in command) and "--release" not in command:
        raise ValueError("a release acceptance runs gates-all.sh --release, so an unpassed gate fails it")
    if output.is_relative_to(root):
        ignored = subprocess.run(["git", "-C", str(root), "check-ignore", "-q", str(output)]).returncode == 0
        if not ignored:
            raise ValueError("run evidence must be outside the source tree or in an ignored build directory")
    capture(root, artifacts, mods, output, release)
    started = time.monotonic()
    log = output.with_suffix(".log")
    with log.open("w") as stream:
        try:
            code = subprocess.run(command, cwd=root, stdout=stream, stderr=subprocess.STDOUT).returncode
        except OSError as error:
            stream.write(str(error) + "\n")
            code = 127
    drift = None
    try:
        verify(output)
    except (ValueError, OSError, KeyError, zipfile.BadZipFile, subprocess.CalledProcessError) as error:
        drift = str(error)
    # The exit code is the command's own verdict; for a release it is not the only one. A gate the aggregator
    # reported as SKIP or EXPECTED_RED did not pass, whatever the aggregator's exit status said.
    unpassed = [f"{name} {verdict}" for name, verdict in UNPASSED_GATE.findall(log.read_text(errors="replace"))]
    result = {"schema": 1, "command": command, "exitCode": code, "inputsUnchanged": drift is None,
              "inputFailure": drift, "elapsedSeconds": time.monotonic() - started, "unpassedGates": unpassed,
              "commandPassed": code == 0 and drift is None and not (release and unpassed),
              "log": str(log), "manifest": str(output)}
    output.with_suffix(".result.json").write_text(json.dumps(result, indent=2) + "\n")
    return result


def release_check(manifests, publish):
    """One acceptance, one candidate: every manifest passed, all agree, and the published files are those bytes."""
    if not manifests:
        raise ValueError("release-check needs at least one --manifest")
    commits, roles, problems = set(), {}, []
    for path in manifests:
        path = Path(path).resolve(strict=True)
        saved = verify(path)
        if not saved.get("release"):
            problems.append(f"{path.name}: not captured with --release")
        result_path = path.with_suffix(".result.json")
        result = json.loads(result_path.read_text()) if result_path.is_file() else None
        if result is None or Path(result.get("manifest", "")).resolve() != path:
            problems.append(f"{path.name}: no run result (a capture is provenance, not a passed acceptance)")
        elif not result.get("commandPassed"):
            problems.append(f"{path.name}: its acceptance command did not pass")
        commits.add((saved["source"]["commit"], saved["source"]["sha256"]))
        for role, record in saved["artifacts"].items():
            roles.setdefault(role, set()).add(record["sha256"])
    if len(commits) != 1:
        problems.append(f"manifests bind {len(commits)} different sources")
    problems += [f"role {role} has {len(hashes)} different hashes" for role, hashes in sorted(roles.items())
                 if len(hashes) != 1]
    for role, path in sorted(publish.items()):
        if role not in RELEASE_ROLES:
            problems.append(f"{role}: not a release role (the release roles are {', '.join(sorted(RELEASE_ROLES))})")
        elif role not in roles:
            problems.append(f"{role}: no accepted artifact to compare {path} with")
        elif digest(path) not in roles[role]:
            problems.append(f"{role}: {path} is not the accepted {role}")
    if problems:
        raise ValueError("release check failed: " + "; ".join(problems))
    return {role: next(iter(hashes)) for role, hashes in roles.items()}


def role_paths(values):
    out = {}
    for value in values:
        role, separator, filename = value.partition("=")
        if not separator or not role or role in out:
            raise ValueError("artifact must have a unique ROLE=PATH: " + value)
        out[role] = Path(filename)
    return out


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)
    for name in ("capture", "run"):
        create = commands.add_parser(name)
        create.add_argument("--source", required=True, type=Path)
        create.add_argument("--artifact", action="append", default=[], metavar="ROLE=PATH")
        create.add_argument("--mods", action="append", default=[], type=Path)
        create.add_argument("--output", required=True, type=Path)
        create.add_argument("--release", action="store_true")
        if name == "run":
            create.add_argument("acceptance_command", nargs=argparse.REMAINDER)
    check = commands.add_parser("verify")
    check.add_argument("manifest", type=Path)
    released = commands.add_parser("release-check")
    released.add_argument("--manifest", action="append", default=[], type=Path)
    released.add_argument("--publish", action="append", default=[], metavar="ROLE=PATH")
    args = parser.parse_args()
    try:
        if args.command == "verify":
            verify(args.manifest)
            print("[evidence] source, artifacts and mod inventory unchanged")
        elif args.command == "release-check":
            accepted = release_check(args.manifest, role_paths(args.publish))
            print(f"[evidence] release check passed: {len(args.manifest)} manifest(s), {len(accepted)} role(s),"
                  f" {len(args.publish)} published file(s) match")
        else:
            artifacts = role_paths(args.artifact)
            if args.command == "run":
                command = args.acceptance_command
                if command and command[0] == "--":
                    command = command[1:]
                result = verified_run(args.source, artifacts, args.mods, args.output, command, args.release)
                print(f"[evidence] command exit={result['exitCode']}, inputs unchanged={result['inputsUnchanged']}"
                      + (f", unpassed gates={result['unpassedGates']}" if result["unpassedGates"] else "")
                      + f"; {result['log']}")
                if not result["commandPassed"]:
                    parser.exit(1)
            else:
                result = capture(args.source, artifacts, args.mods, args.output, args.release)
                print(f"[evidence] captured {len(result['artifacts'])} artifacts at {result['source']['commit']}")
    except (ValueError, OSError, KeyError, zipfile.BadZipFile, subprocess.CalledProcessError) as error:
        parser.exit(1, f"[evidence] FAIL: {error}\n")


if __name__ == "__main__":
    main()
