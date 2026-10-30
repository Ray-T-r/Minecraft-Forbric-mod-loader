"""Every executable copy of the NeoForge and MinecraftForge pins agrees with the installer's Pins.java.

The pins live in one Java file, but scripts that cannot read it (the native-control installers, the evidence
recorder, the carrier assembly script, the merged-base script's userdev defaults, the census table's platform
line) carry their own copy. A bump that misses one of them does not fail anywhere: the native controls quietly
compare against the previous NeoForge, or the release check records the wrong build. This test is where a
half-done bump stops.
"""
import re
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
COMPAT = ROOT / 'forbric-kernel/run/compat'
PINS_JAVA = ROOT / 'forbric-kernel-installer/src/main/java/net/forbric/installer/kernel/Pins.java'


def java_pin(name):
    return re.search(r'static final String ' + name + r' = "([^"]+)"', PINS_JAVA.read_text(encoding='utf-8')).group(1)


def found(path, pattern):
    text = path.read_text(encoding='utf-8')
    values = re.findall(pattern, text)
    if not values:
        raise AssertionError(f'{path.relative_to(ROOT)}: no match for {pattern}')
    return values


class PinAgreementTest(unittest.TestCase):
    def setUp(self):
        self.neo = java_pin('NEOFORGE')
        self.forge = java_pin('FORGE')
        self.mc = java_pin('MINECRAFT')

    def assertAll(self, values, expected, where):
        self.assertEqual({expected}, set(values), where)

    def test_neoforge_pin_everywhere(self):
        neo, compat = self.neo, COMPAT
        self.assertAll(found(compat / 'evidence.py', r'"neoforge": "([^"]+)"'), neo, 'evidence.py PINS')
        self.assertAll(found(compat / 'native-controls.py', r'"neo": "([0-9][^"]*)"'), neo, 'native-controls.py VERSIONS')
        self.assertAll(found(compat / 'native-controls.py', r'net/neoforged/neoforge/([0-9][^/"]*)/'), neo,
                       'native-controls.py installer URL')
        self.assertAll(found(compat / 'native-controls.py', r'minecraft-server-patched/([0-9][^/"]*)/'), neo,
                       'native-controls.py patched server')
        self.assertAll(found(compat / 'fluid-parity-gate.py', r'net/neoforged/neoforge/([0-9][^/\'"]*)'), neo,
                       'fluid-parity-gate.py NATIVE')
        self.assertAll(found(compat / 'breath-drown.py', r"'net/neoforged/neoforge', '([^']+)'"), neo,
                       'breath-drown.py NATIVE_VERSIONS')
        self.assertAll(found(ROOT / 'forbric-loader/run/assemble-neoforge-runtime.sh',
                             r'NF_VERSION="\$\{NF_VERSION:-([^}]+)\}"'), neo, 'assemble-neoforge-runtime.sh')
        self.assertAll(found(ROOT / 'forbric-loader/run/build-merged-base.sh', r'dl/neoforge-([0-9.]+)-userdev\.jar'),
                       neo, 'build-merged-base.sh NEO_USERDEV default')
        table = ROOT / 'forbric-kernel/src/main/resources/net/forbric/kernel/mixin/native-only-methods.txt'
        self.assertAll(found(table, r'^platform neoforge minecraft=\S+ neoforge=(\S+)$'.replace('^', '(?m)^')), neo,
                       'native-only-methods.txt platform line (regenerate with FORBRIC_WRITE_NATIVE_ONLY_METHODS=1)')

    def test_forge_pin_everywhere(self):
        forge, compat = self.forge, COMPAT
        fml = forge.split('-', 1)[1]
        self.assertAll(found(compat / 'evidence.py', r'"forge": "([^"]+)"'), forge, 'evidence.py PINS')
        self.assertAll(found(compat / 'native-controls.py', r'"forge": "([0-9][^"]*)"'), fml, 'native-controls.py VERSIONS')
        self.assertAll(found(compat / 'breath-drown.py', r"'net/minecraftforge/forge', '([^']+)'"), forge,
                       'breath-drown.py NATIVE_VERSIONS')
        self.assertAll(found(ROOT / 'forbric-loader/run/build-merged-base.sh', r'dl/forge-([0-9.-]+)-userdev\.jar'),
                       forge, 'build-merged-base.sh FORGE_USERDEV default')

    def test_minecraft_pin_in_evidence(self):
        self.assertAll(found(COMPAT / 'evidence.py', r'"minecraft": "([^"]+)"'), self.mc, 'evidence.py PINS')


if __name__ == '__main__':
    unittest.main()
