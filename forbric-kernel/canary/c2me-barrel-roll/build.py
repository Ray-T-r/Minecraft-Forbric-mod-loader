"""Build the read-only runtime probe against the staged game base, its NeoForge carrier and an isolated installed profile."""
from pathlib import Path
import json
import os
import subprocess
import shutil
import zipfile

SOURCE = Path(__file__).resolve().parent
KERNEL = SOURCE.parents[1]
BUILD = KERNEL / 'build'
STAGE = Path(os.environ['FORBRIC_OLD']) / 'run'
MC = Path(os.environ['FORBRIC_MC'])
DBR = Path(os.environ['DBR_JAR'])
OUT = BUILD / 'c2me-barrel-probe'
CLASSES = OUT / 'classes'
if CLASSES.exists():
    shutil.rmtree(CLASSES)
CLASSES.mkdir(parents=True, exist_ok=True)
# The game base and the NeoForge carrier the launchers run (BASE, NEO_RT; their defaults under the stage).
BASE = Path(os.environ.get('BASE', STAGE / 'neoforge-patched/patched-mc-neoforge-26.2.jar'))
NEO_RT = Path(os.environ.get('NEO_RT', STAGE / 'neoforge-runtime/neoforge-runtime.jar'))
classpath = [BASE, NEO_RT, DBR, *sorted((MC / 'libraries').rglob('*.jar'))]
java = Path(os.environ['FORBRIC_JAVA'])
command = [str(java.with_name('javac')), '-proc:none', '-cp', os.pathsep.join(map(str, classpath)),
           '-d', str(CLASSES), *map(str, sorted((SOURCE / 'src').rglob('*.java')))]
with (OUT / 'compile.log').open('w') as log:
    subprocess.run(command, stdout=log, stderr=subprocess.STDOUT, check=True)
jar = OUT / 'c2merollprobe.jar'
with zipfile.ZipFile(jar, 'w') as archive:
    for path in CLASSES.rglob('*.class'):
        archive.write(path, str(path.relative_to(CLASSES)))
    archive.writestr('META-INF/neoforge.mods.toml', 'modLoader="javafml"\nloaderVersion="[4,)"\nlicense="Apache-2.0"\n[[mods]]\nmodId="c2merollprobe"\nversion="1.0"\n[[mixins]]\nconfig="c2merollprobe.mixins.json"\n')
    archive.writestr('c2merollprobe.mixins.json', json.dumps({'required': True, 'package': 'probe.mixin',
                         'compatibilityLevel': 'JAVA_25', 'client': ['MinecraftProbeMixin', 'CameraProbeMixin'],
                         'injectors': {'defaultRequire': 1}}))
print(jar)
