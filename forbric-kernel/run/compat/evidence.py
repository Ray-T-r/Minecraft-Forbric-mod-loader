#!/usr/bin/env python3
"""Bind one validation run to its actual sources, tools, game artifacts and mod inventory.

This is provenance, not a test verdict. `verify` fails when ANY recorded input changes. A release
capture additionally requires the complete build input/output set and a clean committed source tree.
No recorded command is executed when a manifest is read.
"""
import argparse
from datetime import datetime, timezone
import hashlib
import io
import json
from pathlib import Path
import subprocess
import sys
import time
import tomllib
import zipfile

RELEASE_ROLES = frozenset({"vanilla", "forge-patched", "neo-patched", "merged", "forge-runtime",
                           "neo-runtime", "forge-interop", "kernel", "kernel-runtime", "merge-tools"})


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
        # Retain unresolved version expressions: guessing would falsify the inventory. The archive hash
        # and manifest remain authoritative, and runtime-resolved discovery can be attached as an artifact.
        manifest = jar.read("META-INF/MANIFEST.MF").decode("utf-8", errors="replace") if "META-INF/MANIFEST.MF" in names else ""
        out.append({"archive": origin, "sha256": hashlib.sha256(data).hexdigest(),
                    "declarations": declarations, "manifest": manifest})
        for name in sorted(nested):
            if name not in names:
                raise ValueError(f"declared nested jar missing: {origin}::{name}")
            if jar.getinfo(name).file_size > 256 * 1024 * 1024:
                raise ValueError(f"nested jar too large to inventory: {origin}::{name}")
            out.extend(archive_metadata(jar.read(name), origin + "::" + name, depth + 1))
    return out


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
    mod_records = [mod_record(path) for path in mods]
    if before != source_record(root, excluded):
        raise ValueError("source changed while collecting evidence; retry on a stable build")
    result = {"schema": 1, "capturedAt": datetime.now(timezone.utc).isoformat(), "release": release,
              "source": before, "artifacts": artifact_records, "mods": mod_records}
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
    if saved.get("release") and (RELEASE_ROLES - saved["artifacts"].keys() or not saved["mods"] or source["dirty"]):
        raise ValueError("incomplete release evidence")
    return saved


def verified_run(root, artifacts, mods, output, command, release=False):
    """Run an acceptance command against fixed inputs, retaining its log and both verdicts separately."""
    root, output = Path(root).resolve(), Path(output).resolve()
    if not command:
        raise ValueError("an acceptance command is required after --")
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
    result = {"schema": 1, "command": command, "exitCode": code, "inputsUnchanged": drift is None,
              "inputFailure": drift, "elapsedSeconds": time.monotonic() - started,
              "commandPassed": code == 0 and drift is None, "log": str(log), "manifest": str(output)}
    output.with_suffix(".result.json").write_text(json.dumps(result, indent=2) + "\n")
    return result


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
    args = parser.parse_args()
    try:
        if args.command == "verify":
            verify(args.manifest)
            print("[evidence] source, artifacts and mod inventory unchanged")
        else:
            artifacts = {}
            for value in args.artifact:
                role, separator, filename = value.partition("=")
                if not separator or not role or role in artifacts:
                    raise ValueError("artifact must have a unique ROLE=PATH: " + value)
                artifacts[role] = Path(filename)
            if args.command == "run":
                command = args.acceptance_command
                if command and command[0] == "--":
                    command = command[1:]
                result = verified_run(args.source, artifacts, args.mods, args.output, command, args.release)
                print(f"[evidence] command exit={result['exitCode']}, inputs unchanged={result['inputsUnchanged']}; {result['log']}")
                if not result["commandPassed"]:
                    parser.exit(1)
            else:
                result = capture(args.source, artifacts, args.mods, args.output, args.release)
                print(f"[evidence] captured {len(result['artifacts'])} artifacts at {result['source']['commit']}")
    except (ValueError, OSError, KeyError, zipfile.BadZipFile, subprocess.CalledProcessError) as error:
        parser.exit(1, f"[evidence] FAIL: {error}\n")


if __name__ == "__main__":
    main()
