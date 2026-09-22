#!/usr/bin/env python3
"""Exercise the installer's actual subprocess and the baseline in the packaged merge tool."""
from pathlib import Path
import shutil
import subprocess
import tempfile
import zipfile

project = Path(__file__).resolve().parents[1]
loader = project.parent / "forbric-loader"
tools = loader / "build/libs/forbric-merge-tools-0.1.0.jar"
baseline = loader / "src/test/resources/merge/link-check-baseline.txt"
with zipfile.ZipFile(tools) as jar:
    assert jar.read("net/forbric/tools/link-check-baseline.txt") == baseline.read_bytes(), "packaged baseline drifted"
with tempfile.TemporaryDirectory(prefix="forbric-installer-links-") as temporary:
    work = Path(temporary)
    classes = work / "test-classes"
    classes.mkdir()
    sources = sorted((project / "src/main/java").rglob("*.java"))
    sources += sorted((project / "src/test/java").rglob("*LinkGateTest.java"))
    subprocess.run(["javac", "--release", "17", "-d", str(classes), *map(str, sources)], check=True)
    resource = classes / "forbric/tools/forbric-merge-tools.jar"
    resource.parent.mkdir(parents=True)
    shutil.copyfile(tools, resource)
    subprocess.run(["java", "-cp", str(classes), "net.forbric.installer.kernel.MergedBaseLinkGateTest", str(work)], check=True)
