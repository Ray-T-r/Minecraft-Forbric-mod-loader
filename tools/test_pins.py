"""Every executable copy of the NeoForge pin agrees with the installer's Pins.java, and the MinecraftForge copies agree
with each other.

The pins live in one Java file, but scripts that cannot read it (the native-control installers, the evidence
recorder, the carrier assembly script, the merged-base script's userdev defaults, the census table's platform
line) carry their own copy. A bump that misses one of them does not fail anywhere: the native controls quietly
compare against the previous NeoForge, or the release check records the wrong build. This test is where a
half-done bump stops.

The installer builds nothing of MinecraftForge any more, so Pins.java has no MinecraftForge pin. The copies that remain
serve native MinecraftForge as an oracle (native controls, evidence, breath-drown); they are held to one value.
"""
import re
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
COMPAT = ROOT / 'forbric-kernel/run/compat'
PINS_JAVA = ROOT / 'forbric-kernel-installer/src/main/java/net/forbric/installer/kernel/Pins.java'
COORDINATE_PIN_TEST = ROOT / 'forbric-kernel-installer/src/test/java/net/forbric/installer/kernel/BaseCoordinatePinTest.java'
# BaseCoordinatePinTest.RECORDED's rows, as they were published: NFRT build, NFRT result, the base coordinate's version
# (net.forbric:patched-mc-neoforge:<version>), the base's SHA-1. Append-only, like the Java table; the copy here is what
# makes editing a published row in place fail (D11).
PUBLISHED_BASES = (
    ('2.0.18', 'gameJarNoRecomp', '26.2-26.2.0.93', '67bbf24f850965e5943a1bfe0295ecb701d4465b'),
)


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
        self.mc = java_pin('MINECRAFT')

    def test_the_installer_has_no_minecraftforge_pin(self):
        self.assertIsNone(re.search(r'static final String FORGE = ', PINS_JAVA.read_text(encoding='utf-8')))

    def assertAll(self, values, expected, where):
        self.assertEqual({expected}, set(values), where)

    def test_published_base_rows_are_never_edited(self):
        text = COORDINATE_PIN_TEST.read_text(encoding='utf-8')
        table = re.search(r'RECORDED = List\.of\((.*?)\);', text, re.S).group(1)
        rows = [tuple(re.findall(r'"([^"]*)"', row)) for row in re.findall(r'List\.of\(([^()]*)\)', table)]
        self.assertEqual(list(PUBLISHED_BASES), rows[:len(PUBLISHED_BASES)],
                         'BaseCoordinatePinTest.RECORDED must begin with every published row, unchanged: add a row '
                         'for a new NFRT build, never edit one')
        self.assertIn((java_pin('NFRT'), java_pin('NFRT_RESULT')), [row[:2] for row in rows])

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

    def test_minecraftforge_oracle_pin_everywhere(self):
        compat = COMPAT
        forge = found(compat / 'evidence.py', r'"forge": "([^"]+)"')[0]
        self.assertRegex(forge, r'^' + re.escape(self.mc) + r'-[0-9.]+$', 'evidence.py PINS forge is <mc>-<fml>')
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
