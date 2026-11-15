"""Every gate's game JVMs carry the caller's FORBRIC_JVM.

FORBRIC_JVM is how a whole sweep is pointed at something every game JVM must have: a locale
(FORBRIC_JVM="-Duser.language=zh -Duser.country=CN" is how gates-all is run in Chinese, because the load report
follows the JVM's language; -Dforbric.dialogLanguage=zh_cn added to it reaches the dependency dialog and a refused
mod's finding, which read that switch before the JVM's language), a debugging property, a switch a red control
turns off. The
launchers (launch-kernel-server.sh, launch-kernel-client.sh, old-kernel.sh) put it on the java line, and
gates-parallel.py copies the caller's environment into every gate. That chain held for 16 of 60 gates' game JVMs
(VERIFY run 1, 2026-11-11): the rest wrote FORBRIC_JVM="-D..." over the caller's value, or ran a Python driver that
built the launcher's environment with its own FORBRIC_JVM. "gates-all in zh" then tested English for 43 gates and
said nothing about it.

So these are rules, read from the gates as text through gates-parallel.py's own Gate class:

  * a gate that sets FORBRIC_JVM for a launch starts it with the caller's value: FORBRIC_JVM="${FORBRIC_JVM:-} -D..."
    (first, so a property the gate sets on purpose -- a locale pin, a phase switch, a control's token -- still wins:
    the JVM keeps the last -D of a name; a gate's -D written before the caller's is one a sweep can override). The same holds for an --env "FORBRIC_JVM=..." handed to a driver, and for
    JAVA_TOOL_OPTIONS, which is how a gate reaches a JVM it starts from a resolved command line instead of a
    launcher (gate-m17's installed profile). Unsetting it, `env -u FORBRIC_JVM` and `env -i` drop it outright.
  * Python that a gate runs -- its heredocs, and every run/compat/*.py it names (and the ones those name) -- sets
    FORBRIC_JVM only from an expression that starts with the caller's: os.environ.get('FORBRIC_JVM', '') or
    env['FORBRIC_JVM'] of an environment copied from it, as the left of a +, the first of a ' '.join, the first field of
    an f-string. Popping or deleting it drops it.
  * a launch that pins the language does it on purpose and is a row of PINS below, with why; a new pin, on any launch
    of any gate, is a new row. The kernel reads two language switches -- the JVM's locale (-Duser.language: the load
    report) and -Dforbric.dialogLanguage (DialogLang: the dependency dialog and a refused mod's finding, ahead of the
    locale) -- so a pin pins both, the second to a table DialogLang has by its exact tag ("zh" names two tables and
    is ignored; "zh_cn" is Simplified Chinese).

Text cannot follow every path to a launcher (an environment built from scratch and handed to subprocess, a value read
into a variable first); the rules hold the assignments, which is where all 43 losses were.
"""
import ast
import importlib.util
import re
import tempfile
import unittest
from pathlib import Path

COMPAT = Path(__file__).resolve().parent
RUN = COMPAT.parent

_spec = importlib.util.spec_from_file_location('gates_parallel', COMPAT / 'gates-parallel.py')
scheduler = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(scheduler)

# Launches that set the JVM's language themselves, after the caller's value, so their pin wins over a sweep's locale:
# per gate, (why, the pins of each pinning launch in file order). Every other launch of these gates takes the caller's.
PINS = {
    'gate-m26-forgeclient.sh': ('its refusal-prompt boot asserts the English finding detail the client reports',
                                [('user.language=en', 'forbric.dialogLanguage=en')]),
    'gate-m29-forgecaps.sh': ('its refusal boot asserts the English load report and finding detail',
                              [('user.language=en', 'forbric.dialogLanguage=en')]),
}
LANGUAGE_SWITCHES = ('user.language', 'forbric.dialogLanguage')
DIALOG_LANG = RUN.parent / 'src/main/java/net/forbric/kernel/ui/DialogLang.java'

COMMENT = re.compile(r'^[ \t]*#.*$', re.M)  # not \s*: that eats the blank lines above, and the line numbers with them
# A Python heredoc: `python3 [-I] - args <<'TAG'` ... TAG. Its body is Python, read by the Python rules.
HEREDOC = re.compile(r'''^[^\n]*\bpython3\b[^\n]*?<<(-?)\s*(['"]?)([A-Za-z_][A-Za-z0-9_]*)\2[^\n]*\n''', re.M)
SH_WRITE = re.compile(r'(?<![\w${])(FORBRIC_JVM|JAVA_TOOL_OPTIONS)=')
SH_DROP = re.compile(r'\bunset\b[^\n;|&]*\bFORBRIC_JVM\b|\benv\b[^\n;|&]*\s-u\s*FORBRIC_JVM\b|\benv\s+(?:-\w+\s+)*-i\b')
READS_CALLER_SH = re.compile(r'\$\{FORBRIC_JVM\b|\$FORBRIC_JVM\b')
LEADS_WITH_CALLER_SH = re.compile(r'''["']*\$(?:\{FORBRIC_JVM\b|FORBRIC_JVM\b)''')
LANGUAGE_PIN = re.compile(r'''-D(user\.language|forbric\.dialogLanguage)=([^\s"'$]*)''')
DRIVER = re.compile(r'\b([A-Za-z0-9_-]+\.py)\b')
NAME = 'FORBRIC_JVM'


def split_heredocs(code):
    """(shell text with the Python heredoc bodies taken out, [(first line number, body)])."""
    shell, bodies, pos = [], [], 0
    while True:
        m = HEREDOC.search(code, pos)
        if not m:
            shell.append(code[pos:])
            return ''.join(shell), bodies
        shell.append(code[pos:m.end()])
        strip, tag = m.group(1) == '-', m.group(3)
        end = re.compile(r'^' + (r'\t*' if strip else '') + re.escape(tag) + r'[ \t]*$', re.M).search(code, m.end())
        stop = end.start() if end else len(code)
        bodies.append((code.count('\n', 0, m.end()) + 1, code[m.end():stop]))
        # Keep the line count, so a shell finding names the line the gate has.
        shell.append('\n' * code.count('\n', m.end(), stop))
        pos = stop


def shell_word(text, start, quote):
    """The shell word that starts at <start> (just after the '='); <quote> is the quote the '=' already sits in."""
    i, sq, dq = start, quote == "'", quote == '"'
    while i < len(text):
        c = text[i]
        if sq:
            if c == "'":
                sq = False
        elif c == '\\':
            i += 1
        elif dq:
            if c == '"':
                dq = False
        elif c == "'":
            sq = True
        elif c == '"':
            dq = True
        elif c.isspace() or c in ';|&()<>':
            break
        i += 1
    return text[start:i]


def shell_findings(code):
    """'<line>: <text>' for each FORBRIC_JVM / JAVA_TOOL_OPTIONS a shell text sets without the caller's FORBRIC_JVM,
    and each place it drops FORBRIC_JVM. <code> has its comments and Python heredocs taken out already."""
    joined = code.replace('\\\n', '  ')  # one logical line per command, at the same offsets as <code>
    found = []
    for m in SH_WRITE.finditer(joined):
        quote = joined[m.start() - 1] if m.start() and joined[m.start() - 1] in '"\'' else ''
        value = shell_word(joined, m.end(), quote)
        if not READS_CALLER_SH.search(value):
            found.append(f'{code.count(chr(10), 0, m.start()) + 1}: {m.group(0)}{value[:120]}')
        elif not LEADS_WITH_CALLER_SH.match(value):
            found.append(f'{code.count(chr(10), 0, m.start()) + 1}: not first: {m.group(0)}{value[:120]}')
    for m in SH_DROP.finditer(joined):
        found.append(f'{code.count(chr(10), 0, m.start()) + 1}: {m.group(0)[:120]}')
    return found


def _names_it(node):
    return isinstance(node, ast.Constant) and node.value == NAME


def reads_caller(node):
    """True when the expression reads the caller's FORBRIC_JVM: x.get('FORBRIC_JVM'...), getenv('FORBRIC_JVM'), x['FORBRIC_JVM']."""
    for n in ast.walk(node):
        if isinstance(n, ast.Call) and n.args and _names_it(n.args[0]):
            f = n.func
            if (isinstance(f, ast.Attribute) and f.attr in ('get', 'getenv')) or (isinstance(f, ast.Name) and f.id == 'getenv'):
                return True
        if isinstance(n, ast.Subscript) and _names_it(n.slice) and isinstance(n.ctx, ast.Load):
            return True
    return False


def leads_with_caller(node):
    """True when the expression's value starts with the caller's FORBRIC_JVM, so what follows it wins."""
    if isinstance(node, ast.Call):
        f = node.func
        if isinstance(f, ast.Attribute) and f.attr in ('strip', 'lstrip', 'rstrip') and not node.args:
            return leads_with_caller(f.value)
        if isinstance(f, ast.Attribute) and f.attr == 'join' and len(node.args) == 1:
            items = node.args[0]
            if isinstance(items, ast.Call) and isinstance(items.func, ast.Name) and items.func.id == 'filter' \
                    and len(items.args) == 2:
                items = items.args[1]
            return isinstance(items, (ast.List, ast.Tuple)) and bool(items.elts) and leads_with_caller(items.elts[0])
        return reads_caller(node) and not isinstance(f, ast.Attribute) or (
            isinstance(f, ast.Attribute) and f.attr in ('get', 'getenv') and node.args and _names_it(node.args[0]))
    if isinstance(node, ast.BinOp) and isinstance(node.op, ast.Add):
        return leads_with_caller(node.left)
    if isinstance(node, ast.JoinedStr):
        head = [v for v in node.values if not (isinstance(v, ast.Constant) and not str(v.value).strip())]
        return bool(head) and isinstance(head[0], ast.FormattedValue) and leads_with_caller(head[0].value)
    if isinstance(node, ast.Subscript):
        return _names_it(node.slice) and isinstance(node.ctx, ast.Load)
    return False


def python_findings(source, first_line=1):
    """'<line>: <what>' for each place Python sets FORBRIC_JVM without the caller's value, or drops it."""
    try:
        tree = ast.parse(source)
    except SyntaxError as e:
        return [f'{first_line + (e.lineno or 1) - 1}: not Python the rules can read ({e.msg})']
    found, parts = [], set()
    for n in ast.walk(tree):
        if isinstance(n, ast.JoinedStr):
            parts.update(id(v) for v in n.values)

    def bad(node, what):
        found.append(f'{first_line + node.lineno - 1}: {what}')

    def held(node, value, what):
        if not reads_caller(value):
            bad(node, f"{what} drops the caller's value")
        elif not leads_with_caller(value):
            bad(node, f"not first: {what} puts its own options before the caller's")

    for n in ast.walk(tree):
        if isinstance(n, ast.Call):
            for kw in n.keywords:
                if kw.arg == NAME:
                    held(kw.value, kw.value, f'{NAME}=...')
            if isinstance(n.func, ast.Attribute) and n.func.attr == 'pop' and n.args and _names_it(n.args[0]):
                bad(n, f'pops {NAME}')
        elif isinstance(n, ast.Dict):
            for k, v in zip(n.keys, n.values):
                if k is not None and _names_it(k):
                    held(v, v, f"'{NAME}': ...")
        elif isinstance(n, ast.Assign):
            for t in n.targets:
                if isinstance(t, ast.Subscript) and _names_it(t.slice):
                    held(n, n.value, f"[{NAME!r}] = ...")
        elif isinstance(n, ast.Delete):
            for t in n.targets:
                if isinstance(t, ast.Subscript) and _names_it(t.slice):
                    bad(n, f'deletes {NAME}')
        elif isinstance(n, ast.JoinedStr):
            head = n.values[0] if n.values else None
            if isinstance(head, ast.Constant) and str(head.value).startswith(NAME + '='):
                rest = ast.JoinedStr(values=n.values[1:]) if head.value == NAME + '=' else ast.Constant(value='')
                held(n, rest if reads_caller(n) else n, f"'{NAME}=...'")
        elif isinstance(n, ast.Constant) and id(n) not in parts and isinstance(n.value, str) and n.value.startswith(NAME + '='):
            bad(n, f"'{NAME}=...' drops the caller's value")
    return found


def gates(run_dir=None):
    return scheduler.discover(run_dir if run_dir is not None else RUN)


def drivers(run_dir=None):
    """Every run/compat/*.py a gate names, closed over the ones those name; test files are not drivers."""
    run = run_dir if run_dir is not None else RUN
    compat = run / 'compat'
    exists = {p.name for p in compat.glob('*.py') if not p.name.startswith('test_')}
    todo = {name for gate in gates(run) for name in DRIVER.findall(COMMENT.sub('', gate.source)) if name in exists}
    seen = set()
    while todo:
        name = todo.pop()
        seen.add(name)
        todo |= {n for n in DRIVER.findall((compat / name).read_text(encoding='utf-8', errors='replace'))
                 if n in exists and n not in seen}
    return sorted(seen)


def violations(run_dir=None):
    run = run_dir if run_dir is not None else RUN
    found = {}
    for gate in gates(run):
        shell, bodies = split_heredocs(gate.source)
        hits = shell_findings(COMMENT.sub('', shell))
        for first, body in bodies:
            hits += python_findings(body, first)
        if hits:
            found[gate.name] = hits
    for name in drivers(run):
        hits = python_findings((run / 'compat' / name).read_text(encoding='utf-8', errors='replace'))
        if hits:
            found['compat/' + name] = hits
    return found


def pins(run_dir=None):
    """{gate: [(name=value, ...) for each logical line -- a launch, or the call that hands a launch its options -- that
    pins a language switch, in file order]}."""
    found = {}
    for gate in gates(run_dir):
        shell, bodies = split_heredocs(COMMENT.sub('', gate.source))
        texts = [shell.replace('\\\n', '  ')] + [body for _, body in bodies]
        launches = [tuple(f'{m.group(1)}={m.group(2)}' for m in LANGUAGE_PIN.finditer(line))
                    for text in texts for line in text.split('\n')]
        launches = [launch for launch in launches if launch]
        if launches:
            found[gate.name] = launches
    return found


def dialog_tags(path=DIALOG_LANG):
    return set(re.findall(r'new DialogLang\("([a-z_]+)"', path.read_text(encoding='utf-8')))


def pin_findings(run_dir=None, tags=None):
    """'<gate>: <pins>: <what>' for each pinning launch that leaves one of the two switches to the caller, or names a
    dialog language DialogLang has no table for (it is then ignored, and the caller's or the host's language wins)."""
    tags = dialog_tags() if tags is None else tags
    found = []
    for gate, launches in pins(run_dir).items():
        for launch in launches:
            names = [pin.split('=', 1)[0] for pin in launch]
            for switch in LANGUAGE_SWITCHES:
                if switch not in names:
                    found.append(f'{gate}: {" ".join(launch)}: does not pin {switch}')
            for pin in launch:
                name, value = pin.split('=', 1)
                if name == 'forbric.dialogLanguage' and value not in tags:
                    found.append(f'{gate}: {pin}: DialogLang has no table "{value}" ({", ".join(sorted(tags))})')
    return found


def writes_seen(run_dir=None):
    """How many FORBRIC_JVM settings the walk looked at: the rules are vacuous if this is small."""
    count = 0
    for gate in gates(run_dir):
        shell, bodies = split_heredocs(gate.source)
        count += len(SH_WRITE.findall(COMMENT.sub('', shell)))
        count += sum(body.count(NAME + '=') + body.count(f"'{NAME}'") + body.count(f'"{NAME}"') for _, body in bodies)
    return count


class GateLocaleTest(unittest.TestCase):
    def test_every_gate_hands_the_caller_its_jvm_options(self):
        self.assertEqual({}, violations(),
                         'this sets FORBRIC_JVM (or JAVA_TOOL_OPTIONS) over the caller\'s value, so a sweep run '
                         'with FORBRIC_JVM="-Duser.language=zh ..." boots this JVM in the host\'s language and says '
                         'nothing: write FORBRIC_JVM="${FORBRIC_JVM:-} -D..." (Python: '
                         "os.environ.get('FORBRIC_JVM', '') + ' -D...')")

    def test_every_language_pin_is_declared(self):
        self.assertEqual({gate: launches for gate, (_, launches) in PINS.items()}, pins(),
                         'a launch pins -Duser.language or -Dforbric.dialogLanguage: if that is on purpose, add it to '
                         'PINS with the reason; if not, leave the language to the caller')

    def test_a_language_pin_holds_both_switches_to_a_real_table(self):
        self.assertEqual([], pin_findings(),
                         'a pinned launch must pin both language switches the kernel reads, the dialog one by a tag '
                         'DialogLang has, or a sweep\'s -Dforbric.dialogLanguage rewrites the text it asserts')

    def test_the_walk_sees_the_checked_in_gates(self):
        # A wrong directory, or a heredoc reader that swallows the gates, finds nothing and passes everything above.
        self.assertGreater(len(gates()), 50)
        found = drivers()
        for name in ('regress-gate.py', 'fluid-head-gate.py', 'carpet-gate.py', 'forge-swap.py', 'native-controls.py'):
            self.assertIn(name, found)
        self.assertGreater(writes_seen(), 60)
        self.assertTrue({'en', 'zh_cn', 'zh_tw'} <= dialog_tags())


class ControlTest(unittest.TestCase):
    """Each rule, shown to fire on a gate that breaks it and to stay quiet on one that does not."""

    def gate(self, run, name, body):
        (run / name).write_text('#!/usr/bin/env bash\n# GATE-PARALLEL: rundirs=probe mem=10\n' + body, encoding='utf-8')

    def scratch(self):
        d = tempfile.TemporaryDirectory(prefix='gate-locale-')
        self.addCleanup(d.cleanup)
        run = Path(d.name)
        (run / 'compat').mkdir()
        return run

    def test_an_assignment_that_drops_the_caller_is_caught_and_one_that_keeps_it_is_not(self):
        run = self.scratch()
        self.gate(run, 'gate-m90-probe.sh', 'FORBRIC_JVM="-Dforbric.debug=true" "$KERNEL/run/launch-kernel-server.sh"\n')
        self.gate(run, 'gate-m91-probe.sh', '( stop ) | RUNDIR="$R" FORBRIC_JVM="${M91_EXTRA_JVM:-}" \\\n  launch.sh\n')
        self.gate(run, 'gate-m92-probe.sh', '  export RUNDIR="$dir" FORBRIC_JVM="$jvm"\n')
        self.gate(run, 'gate-m93-probe.sh', 'FORBRIC_JVM="-Da=1 \\\n  -Db=2 ${M93_EXTRA_JVM:-}" launch.sh\n')
        self.gate(run, 'gate-m94-probe.sh', 'FORBRIC_JVM="${FORBRIC_JVM:-} -Dforbric.debug=true" launch.sh\n'
                                            'FORBRIC_JVM="${FORBRIC_JVM:-} -Da=1 \\\n  -Db=2" launch.sh\n'
                                            '  export RUNDIR="$dir" FORBRIC_JVM="${FORBRIC_JVM:-} $jvm"\n'
                                            'FORBRIC_JVM=$FORBRIC_JVM\' -Dx=1\' launch.sh\n'
                                            'echo "${FORBRIC_JVM:-}"\n# FORBRIC_JVM="-Dprose=only"\n')
        # The caller's value is there but not first: a sweep's -D of the same name wins over the gate's.
        self.gate(run, 'gate-m95-probe.sh', 'FORBRIC_JVM="-Duser.language=en ${FORBRIC_JVM:-}" launch.sh\n')
        self.assertEqual(['gate-m90-probe.sh', 'gate-m91-probe.sh', 'gate-m92-probe.sh', 'gate-m93-probe.sh',
                          'gate-m95-probe.sh'], sorted(violations(run)))
        self.assertEqual(['3: FORBRIC_JVM="${M91_EXTRA_JVM:-}"'], violations(run)['gate-m91-probe.sh'])
        self.assertEqual(['3: not first: FORBRIC_JVM="-Duser.language=en ${FORBRIC_JVM:-}"'],
                         violations(run)['gate-m95-probe.sh'])

    def test_a_driver_env_string_and_java_tool_options_are_held_too(self):
        run = self.scratch()
        self.gate(run, 'gate-m90-probe.sh', '$FS client --env "RUNDIR=$C" --env "FORBRIC_JVM=-Da=1 -Db=2" -- launch.sh\n')
        self.gate(run, 'gate-m91-probe.sh', '$FS client --env "FORBRIC_JVM=${FORBRIC_JVM:-} -Da=1 -Db=2" -- launch.sh\n')
        self.gate(run, 'gate-m95-probe.sh', '$FS client --env "FORBRIC_JVM=-Da=1 ${FORBRIC_JVM:-}" -- launch.sh\n'
                                            'xargs -0 env JAVA_TOOL_OPTIONS="-Da=1 ${FORBRIC_JVM:-}" < cmd\n')
        self.gate(run, 'gate-m92-probe.sh', 'xargs -0 env JAVA_TOOL_OPTIONS="-Dforbric.clientSmoke=true" < cmd\n')
        self.gate(run, 'gate-m93-probe.sh', 'xargs -0 env JAVA_TOOL_OPTIONS="${FORBRIC_JVM:-} -Dforbric.clientSmoke=true" < cmd\n')
        self.gate(run, 'gate-m94-probe.sh', '( unset FORBRIC_JVM; launch.sh )\nenv -u FORBRIC_JVM launch.sh\nenv -i PATH="$PATH" launch.sh\n')
        found = violations(run)
        self.assertEqual(['gate-m90-probe.sh', 'gate-m92-probe.sh', 'gate-m94-probe.sh', 'gate-m95-probe.sh'], sorted(found))
        self.assertEqual(3, len(found['gate-m94-probe.sh']))
        self.assertEqual(2, len(found['gate-m95-probe.sh']))

    def test_python_in_a_heredoc_is_held(self):
        run = self.scratch()
        drop = ("python3 - <<'PY' || exit 1\nimport os\nenv=os.environ.copy()\n"
                "env.update(RUNDIR='r',FORBRIC_JVM=f'-Dforbric.phase={1}' + ' -Dx=off')\nPY\n")
        keep = ("python3 - <<'PY' || exit 1\nimport os\nenv=os.environ.copy()\n"
                "env.update(RUNDIR='r',FORBRIC_JVM=os.environ.get('FORBRIC_JVM','')+f' -Dforbric.phase={1}')\nPY\n")
        self.gate(run, 'gate-m90-probe.sh', 'echo before\n' + drop + 'echo after\n')
        self.gate(run, 'gate-m91-probe.sh', keep)
        self.assertEqual({'gate-m90-probe.sh': ['7: FORBRIC_JVM=... drops the caller\'s value']}, violations(run))

    def test_python_drivers_a_gate_names_are_held_in_every_spelling(self):
        run = self.scratch()
        self.gate(run, 'gate-m90-probe.sh', 'python3 "$KERNEL/run/compat/drive.py" --port "$GATE_PORT"\n')
        (run / 'compat' / 'drive.py').write_text(
            "import importlib.util\nspec = importlib.util.spec_from_file_location('helper', HERE / 'helper.py')\n", encoding='utf-8')
        (run / 'compat' / 'unnamed.py').write_text("env['FORBRIC_JVM'] = '-Xmx2G'\n", encoding='utf-8')
        helper = run / 'compat' / 'helper.py'
        for source, bad in [
                ("import os\nenv = dict(os.environ, FORBRIC_JVM='-Xmx2G')\n", True),
                ("import os\nenv = dict(os.environ, FORBRIC_JVM=(os.environ.get('FORBRIC_JVM', '') + ' -Xmx2G').strip())\n", False),
                ("env = {}\nenv['FORBRIC_JVM'] = ' '.join(['-Djava.awt.headless=true'])\n", True),
                ("import os\nenv = os.environ.copy()\nenv['FORBRIC_JVM'] = ' '.join([env.get('FORBRIC_JVM', ''), '-Dh=1'])\n", False),
                ("import os\nenv = os.environ.copy()\nenv['FORBRIC_JVM'] = ' '.join(['-Dh=1', env.get('FORBRIC_JVM', '')])\n", True),
                ("import os\nenv['FORBRIC_JVM'] = ' '.join(filter(None, [os.environ.get('FORBRIC_JVM', ''), '-Xmx2G']))\n", False),
                ("import os\nenv['FORBRIC_JVM'] = (os.environ.get('FORBRIC_JVM', '') + ' ' + ' '.join(a)).strip()\n", False),
                ("import os\nenv['FORBRIC_JVM'] = ' '.join(a) + ' ' + os.environ.get('FORBRIC_JVM', '')\n", True),
                ("import os\nenv.update({'RUNDIR': 'r', 'FORBRIC_JVM': '-Xmx2G'})\n", True),
                ("import os\nenv.update({'FORBRIC_JVM': os.environ.get('FORBRIC_JVM', '') + ' -Xmx2G'})\n", False),
                ("import os\nenv.update({'FORBRIC_JVM': '-Xmx2G ' + os.environ.get('FORBRIC_JVM', '')})\n", True),
                ("env.pop('FORBRIC_JVM', None)\n", True),
                ("del env['FORBRIC_JVM']\n", True),
                ("cmd = ['--env', 'FORBRIC_JVM=-Xmx2G']\n", True),
                ("cmd = ['--env', f'FORBRIC_JVM={jvm}']\n", True),
                ("import os\ncmd = ['--env', f\"FORBRIC_JVM={os.environ.get('FORBRIC_JVM', '')} {jvm}\"]\n", False),
                ("import os\ncmd = ['--env', f\"FORBRIC_JVM={jvm} {os.environ.get('FORBRIC_JVM', '')}\"]\n", True),
                ("record = {k: env[k] for k in ('RUNDIR', 'FORBRIC_JVM') if k in env}\n", False)]:
            with self.subTest(source=source):
                helper.write_text(source, encoding='utf-8')
                self.assertEqual(['drive.py', 'helper.py'], drivers(run))
                self.assertEqual(['compat/helper.py'] if bad else [], sorted(violations(run)))

    def test_a_language_pin_is_found_per_launch(self):
        run = self.scratch()
        both = '-Duser.language=en -Duser.country=US -Dforbric.dialogLanguage=en'
        self.gate(run, 'gate-m90-probe.sh', f'FORBRIC_JVM="${{FORBRIC_JVM:-}} {both}" launch.sh\n')
        self.gate(run, 'gate-m91-probe.sh', '# a sweep runs it with -Duser.language=zh\nFORBRIC_JVM="${FORBRIC_JVM:-}" launch.sh\n')
        # A second pinned launch in a gate that already has one is a second row, not the same declaration.
        self.gate(run, 'gate-m92-probe.sh', f'FORBRIC_JVM="${{FORBRIC_JVM:-}} {both}" \\\n  launch.sh\n'
                                            'boot "$LOG" strict "-Duser.language=zh -Dforbric.dialogLanguage=zh_cn" a.jar\n'
                                            "python3 - <<'PY'\nenv['FORBRIC_JVM'] = env['FORBRIC_JVM'] + ' -Duser.language=ja'\nPY\n")
        en = ('user.language=en', 'forbric.dialogLanguage=en')
        self.assertEqual({'gate-m90-probe.sh': [en],
                          'gate-m92-probe.sh': [en, ('user.language=zh', 'forbric.dialogLanguage=zh_cn'),
                                                ('user.language=ja',)]}, pins(run))

    def test_a_language_pin_holds_both_switches_to_a_table_dialoglang_has(self):
        run = self.scratch()
        self.gate(run, 'gate-m90-probe.sh', 'FORBRIC_JVM="${FORBRIC_JVM:-} -Duser.language=en -Dforbric.dialogLanguage=en" a\n'
                                            'boot "$L" strict "-Duser.language=zh -Duser.country=CN -Dforbric.dialogLanguage=zh_cn"\n')
        self.gate(run, 'gate-m91-probe.sh', 'FORBRIC_JVM="${FORBRIC_JVM:-} -Duser.language=en -Duser.country=US" a\n')
        self.gate(run, 'gate-m92-probe.sh', 'FORBRIC_JVM="${FORBRIC_JVM:-} -Dforbric.dialogLanguage=en" a\n')
        self.gate(run, 'gate-m93-probe.sh', 'boot "$L" strict "-Duser.language=zh -Dforbric.dialogLanguage=zh"\n')
        found = pin_findings(run, tags=dialog_tags())
        self.assertEqual(['gate-m91-probe.sh: user.language=en: does not pin forbric.dialogLanguage',
                          'gate-m92-probe.sh: forbric.dialogLanguage=en: does not pin user.language'],
                         [f for f in found if 'does not pin' in f])
        self.assertEqual(1, sum('gate-m93-probe.sh: forbric.dialogLanguage=zh: DialogLang has no table "zh"' in f
                                for f in found))
        self.assertEqual(3, len(found))


if __name__ == '__main__':
    unittest.main()
