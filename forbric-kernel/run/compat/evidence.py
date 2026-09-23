#!/usr/bin/env python3
"""Bind one validation run to its actual sources, tools, game artifacts and mod inventory.

This is provenance, not a test verdict. `verify` fails when ANY recorded input changes. A release
capture additionally requires the complete build input/output set, a clean committed source tree, the
pinned platform versions read out of the artifacts themselves, the jars the kernel build and its
bytecode tests actually read being the attested ones, and the merged base's own build provenance.
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

RELEASE_ROLES = frozenset({"vanilla", "forge-patched", "neo-patched", "merged", "forge-runtime",
                           "neo-runtime", "forge-interop", "kernel", "kernel-runtime", "merge-tools"})

# The fixed platform (PLAN P0). A release reads each value out of the artifact it describes; a name, a path or a
# stamp beside the file is not the version of the bytes being accepted.
PINS = {"minecraft": "26.2", "forge": "26.2-65.0.1", "neoforge": "26.2.0.88"}
# Which roles carry which reading: the game jars their own version.json, the carriers their manifest.
MINECRAFT_ROLES = ("vanilla", "forge-patched", "neo-patched", "merged")
FORGE_ROLES = ("forge-runtime", "forge-interop")
NEOFORGE_ROLES = ("neo-runtime",)

# What forbric-kernel/build.gradle compiles kernel-runtime against and what its ~130 bytecode tests read: the
# staged root is $FORBRIC_OLD/run, else <source>/forbric-loader/run -- the same resolution the build uses when no
# -Pforbric.stagedRoot is given, and the build hands that root to its tests as FORBRIC_OLD.
STAGED_BUILD_INPUTS = {"merged": "merged-base/patched-mc-merged-26.2.jar",
                       "forge-runtime": "forge-runtime/forge-runtime.jar",
                       "neo-runtime": "neoforge-runtime/neoforge-runtime.jar"}

# The gate aggregator's verdict lines. A release acceptance must not contain a gate that was not run, or that is
# still red by declaration, however the aggregator's own exit code read.
UNPASSED_GATE = re.compile(r"^RESULT (\S+) (SKIP|EXPECTED_RED)\b.*$", re.M)


def digest(path):
    with Path(path).open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


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
    if role in FORGE_ROLES:
        # MinecraftForge numbers itself without the game version; its coordinate is <mc>-<forge>.
        return f"{PINS['minecraft']}-{version}" if title == "MinecraftForge" and version else None
    return version if title == "NeoForge" else None


def platform_record(artifacts, release):
    readings = {"minecraft": {}, "forge": {}, "neoforge": {}}
    for family, roles in (("minecraft", MINECRAFT_ROLES), ("forge", FORGE_ROLES), ("neoforge", NEOFORGE_ROLES)):
        for role in roles:
            if role in artifacts:
                readings[family][role] = platform_reading(role, artifacts[role])
    if release:
        wrong = [f"{role}={value or 'unreadable'} (pinned {PINS[family]})"
                 for family, roles in readings.items() for role, value in roles.items() if value != PINS[family]]
        if wrong:
            raise ValueError("release artifacts are not the pinned platform: " + ", ".join(wrong))
    return {"pins": dict(PINS), "readings": readings}


def staged_build_inputs(root, environment=None):
    environment = os.environ if environment is None else environment
    old = environment.get("FORBRIC_OLD")
    run = Path(old) / "run" if old else Path(root) / "forbric-loader" / "run"
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
            wrong.append(f"{role}: the kernel build and its bytecode tests read {record['path']}"
                         f" ({'missing' if record.get('missing') else record['sha256'][:12]}), attested"
                         f" {attested['sha256'][:12] if attested else 'nothing'}")
    if wrong:
        raise ValueError("release evidence attests other jars than the build tests: " + "; ".join(wrong)
                         + ". Point FORBRIC_OLD at a staged root holding the attested candidate.")


MERGE_TOOL_SOURCES = "forbric-loader/src/tools/java/net/forbric/tools/"
PROVENANCE_INPUTS = {"vanilla": "vanilla", "forge-patched": "forge-patched", "neo-patched": "neo-patched",
                     "forge-runtime": "forge-runtime", "neo-runtime": "neo-runtime"}


def write_merge_provenance(root, output, inputs, produced, tools, link_check):
    """What build-merged-base.sh records beside the merged base: inputs, tool sources, outputs, link-check mode.

    `dirty` covers the tool sources only -- the merge is a function of them and of its inputs, and the release
    capture compares each recorded tool source with the committed tree it attests.
    """
    root = Path(root).resolve(strict=True)
    tools = [Path(tool).resolve(strict=True) for tool in tools]
    status = git(root, "status", "--porcelain", "--", *(str(tool) for tool in tools)).decode().strip()
    record = {"schema": 1, "tool": "forbric-loader/run/build-merged-base.sh", "linkCheck": link_check,
              "source": {"commit": git(root, "rev-parse", "HEAD").decode().strip(), "dirty": bool(status)},
              "toolSources": {tool.name: digest(tool) for tool in tools},
              "inputs": {role: file_record(path) for role, path in sorted(inputs.items())},
              "outputs": {role: file_record(path) for role, path in sorted(produced.items())}}
    output = Path(output)
    temporary = output.with_name(output.name + ".tmp")
    temporary.write_text(json.dumps(record, indent=2) + "\n")
    temporary.replace(output)
    return record


def merged_provenance(artifacts, records, source, release):
    """build-merged-base.sh's record of how the merged base was made, checked against this capture."""
    if "merged" not in artifacts:
        return None
    path = Path(str(artifacts["merged"]) + ".provenance.json")
    if not path.is_file():
        if release:
            raise ValueError(f"release evidence requires the merged base's build provenance: {path}"
                             " (rebuild it with forbric-loader/run/build-merged-base.sh)")
        return {"path": str(path), "missing": True}
    record = file_record(path)
    saved = json.loads(path.read_text())
    if release:
        wrong = []
        outputs, inputs = saved.get("outputs", {}), saved.get("inputs", {})
        for role, produced in (("merged", "merged"), ("forge-interop", "forge-interop")):
            if outputs.get(produced, {}).get("sha256") != records[role]["sha256"]:
                wrong.append(f"{role} is not the jar this provenance's build wrote")
        for role, used in PROVENANCE_INPUTS.items():
            if inputs.get(used, {}).get("sha256") != records[role]["sha256"]:
                wrong.append(f"{role} is not the {used} input the merge read")
        if saved.get("linkCheck") != "enforce":
            wrong.append(f"the build's link check was {saved.get('linkCheck')!r}, not enforced")
        if saved.get("source", {}).get("dirty") is not False:
            wrong.append("the merge tools were built from an uncommitted tree")
        tools = saved.get("toolSources") or {}
        if not tools:
            wrong.append("no merge-tool sources recorded")
        for name, sha in tools.items():
            if source["files"].get(MERGE_TOOL_SOURCES + name) != sha:
                wrong.append(f"{name} differs from the attested source")
        if wrong:
            raise ValueError("merged base provenance does not match this release: " + "; ".join(wrong))
    record["summary"] = {"commit": saved.get("source", {}).get("commit"), "linkCheck": saved.get("linkCheck")}
    return record


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
    if release:
        check_staged_build_inputs(staged, artifact_records)
    provenance = merged_provenance(artifacts, artifact_records, before, release)
    mod_records = [mod_record(path) for path in mods]
    if before != source_record(root, excluded):
        raise ValueError("source changed while collecting evidence; retry on a stable build")
    result = {"schema": 1, "capturedAt": datetime.now(timezone.utc).isoformat(), "release": release,
              "source": before, "artifacts": artifact_records, "platform": platform,
              "stagedBuildInputs": staged, "mergedProvenance": provenance, "mods": mod_records}
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
    provenance = saved.get("mergedProvenance")
    if provenance and not provenance.get("missing"):
        current = file_record(provenance["path"])
        if (current["size"], current["sha256"]) != (provenance["size"], provenance["sha256"]):
            raise ValueError("merged base provenance changed since capture")
    if saved.get("release") and (RELEASE_ROLES - saved["artifacts"].keys() or not saved["mods"] or source["dirty"]):
        raise ValueError("incomplete release evidence")
    if saved.get("release"):
        platform_record({role: record["path"] for role, record in saved["artifacts"].items()}, True)
        if "stagedBuildInputs" not in saved:
            raise ValueError("incomplete release evidence: the jars the build and its tests read are not recorded")
        check_staged_build_inputs(saved["stagedBuildInputs"], saved["artifacts"])
        if not provenance or provenance.get("missing"):
            raise ValueError("incomplete release evidence: no merged base provenance")
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
        if role not in roles:
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
    provenance = commands.add_parser("merge-provenance")
    provenance.add_argument("--source", required=True, type=Path)
    provenance.add_argument("--output", required=True, type=Path)
    provenance.add_argument("--input", action="append", default=[], metavar="ROLE=PATH")
    provenance.add_argument("--produced", action="append", default=[], metavar="ROLE=PATH")
    provenance.add_argument("--tool", action="append", default=[], type=Path)
    provenance.add_argument("--link-check", required=True, choices=("enforce", "warn"))
    released = commands.add_parser("release-check")
    released.add_argument("--manifest", action="append", default=[], type=Path)
    released.add_argument("--publish", action="append", default=[], metavar="ROLE=PATH")
    args = parser.parse_args()
    try:
        if args.command == "verify":
            verify(args.manifest)
            print("[evidence] source, artifacts and mod inventory unchanged")
        elif args.command == "merge-provenance":
            record = write_merge_provenance(args.source, args.output, role_paths(args.input),
                                            role_paths(args.produced), args.tool, args.link_check)
            print(f"[evidence] merge provenance -> {args.output} (tools {'DIRTY' if record['source']['dirty'] else 'clean'}"
                  f" at {record['source']['commit'][:12]}, link check {args.link_check})")
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
