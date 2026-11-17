"""The developer docs describe CI with the command CI actually runs, and CI runs only what the tree still has."""
from pathlib import Path
import re
import shlex
import unittest

ROOT = Path(__file__).resolve().parents[1]


def kernel_command(workflow):
    """The command the kernel job's 'Build and check' step runs inside forbric-kernel/."""
    match = re.search(r'- name: Build and check[^\n]*\n(?:[ \t]+[^\n]*\n)*?[ \t]+run: cd forbric-kernel && ([^\n]+)\n', workflow)
    if not match:
        raise AssertionError("build.yml has no 'Build and check' step running in forbric-kernel")
    return match.group(1).strip()


def ci_bullet(document):
    """The '- **CI**' bullet of the testing section, with its continuation lines joined."""
    match = re.search(r'^- \*\*CI\*\*.*?(?=^\S|^- |\Z)', document, re.M | re.S)
    if not match:
        raise AssertionError('no "- **CI**" bullet')
    return ' '.join(match.group(0).split())


# What the Java plugin gives every project, beyond the tasks a build file registers and those its source sets imply.
JAVA_PLUGIN_TASKS = {'assemble', 'build', 'check', 'classes', 'clean', 'compileJava', 'compileTestJava', 'jar', 'javadoc',
                     'processResources', 'processTestResources', 'test', 'testClasses'}
# Gradle options that take the next word as their value, so that word is neither a task nor a property.
VALUE_OPTIONS = {'-p', '--project-dir', '-x', '--exclude-task', '--tests', '-I', '--init-script', '-g',
                 '--gradle-user-home', '-c', '--settings-file'}


def run_blocks(workflow):
    """Each step's shell text, one string per `run:` key: block scalars keep their lines, folded ones are joined."""
    lines = workflow.split('\n')
    blocks, index = [], 0
    while index < len(lines):
        match = re.match(r'^(\s*)(?:- )?run:\s*(.*)$', lines[index])
        index += 1
        if not match:
            continue
        indent, value = len(match.group(1)), match.group(2).strip()
        if value[:1] not in ('|', '>'):
            blocks.append(value)
            continue
        body = []
        while index < len(lines) and (not lines[index].strip() or len(lines[index]) - len(lines[index].lstrip()) > indent):
            body.append(lines[index].strip())
            index += 1
        blocks.append(' '.join(body) if value[0] == '>' else '\n'.join(body))
    return blocks


def gradle_invocations(block):
    """(project directory relative to the root, tasks, -P properties) for each Gradle wrapper call in one run block.

    The directory follows the block's `cd`s (one inside a `( … )` subshell holds for that line only) and `-p`."""
    calls, cwd = [], Path('.')
    for line in block.split('\n'):
        line_cwd = cwd
        for match in re.finditer(r'(\(\s*)?\bcd\s+([^\s;&|)]+)|(\S*gradlew(?:\.bat)?)([^;&|)"\n]*)', line):
            if match.group(2):
                line_cwd = line_cwd / match.group(2)
                if not match.group(1):
                    cwd = line_cwd
                continue
            words, project, tasks, properties, index = shlex.split(match.group(4)), line_cwd, [], {}, 0
            while index < len(words):
                word = words[index]
                if word in VALUE_OPTIONS and index + 1 < len(words):
                    if word in ('-p', '--project-dir'):
                        project = line_cwd / words[index + 1]
                    elif word in ('-x', '--exclude-task'):
                        tasks.append(words[index + 1])
                    index += 2
                    continue
                if word.startswith('-P'):
                    name, _, value = word[2:].partition('=')
                    properties[name] = value
                elif not word.startswith('-'):
                    tasks.append(word)
                index += 1
            calls.append((Path(*[part for part in project.parts if part != '.']) if project.parts else Path('.'),
                          tasks, properties))
    return calls


def known_tasks(build_file):
    """The task names a build file can run: registered ones, the Java plugin's, its source sets', and clean<Task>."""
    text = build_file.read_text(encoding='utf-8')
    names = set(JAVA_PLUGIN_TASKS)
    names.update(re.findall(r'tasks\.register\(\s*[\'"]([\w-]+)[\'"]', text))
    names.update(re.findall(r'^\s*task\s+(\w+)', text, re.M))
    block = re.search(r'^sourceSets \{\n(.*?)^\}', text, re.M | re.S)
    for source_set in re.findall(r'^\t(\w+)\s*\{', block.group(1), re.M) if block else []:
        if source_set in ('main', 'test'):
            continue
        capital = source_set[0].upper() + source_set[1:]
        names.update({f'{source_set}Classes', f'compile{capital}Java', f'process{capital}Resources'})
    names.update('clean' + name[0].upper() + name[1:] for name in list(names))
    return names


def path_words(text):
    """Repository paths a workflow names: words with a directory and a file extension or a trailing slash."""
    for word in re.findall(r'[\w.${}+-]*(?:/[\w.${}+*-]+)+/?', text):
        word = word.rstrip('.,')  # the end of a sentence in a comment
        if '://' in word or word.startswith('/'):
            continue
        if not (re.search(r'\.(py|sh|tsv|gradle|java|json|txt|properties)$', word) or word.endswith('/')):
            continue
        yield word


def loop_values(block):
    """{variable: [values]} for each `for v in a b c; do` of a run block, so a path built from it can be checked."""
    return {name: values.split() for name, values in re.findall(r'\bfor\s+(\w+)\s+in\s+([^;\n]+);\s*do\b', block)}


def stale_references(workflow, root=ROOT):
    """Everything a workflow names that the tree at root no longer has, one human-readable line each."""
    problems, directories, loops = [], {Path('.')}, {}
    blocks = run_blocks(workflow)
    for block in blocks:
        loops.update(loop_values(block))
        directories.update(Path(target) for target in re.findall(r'\bcd\s+([^\s;&|)]+)', block))
        for project, tasks, properties in gradle_invocations(block):
            build_file = root / project / 'build.gradle'
            if not build_file.is_file():
                problems.append(f'Gradle runs in {project}, which has no build.gradle')
                continue
            build_text = build_file.read_text(encoding='utf-8')
            known = known_tasks(build_file)
            problems += [f'{project}: task {task} does not exist' for task in tasks if task not in known]
            for name, value in properties.items():
                if f"'{name}'" not in build_text and f'"{name}"' not in build_text:
                    problems.append(f'{project}: -P{name} is read by nothing in its build.gradle')
                # skipRatchet compares against src/test/skip-baseline/<profile>.tsv (see forbric-kernel/build.gradle).
                if name == 'forbric.skipBaseline' and not (root / project / 'src/test/skip-baseline' / f'{value}.tsv').is_file():
                    problems.append(f'{project}: skip baseline profile {value} has no src/test/skip-baseline/{value}.tsv')
        for script, command in re.findall(r'\bpython3?\s+(\S+\.py)\s+([a-z][\w-]*)', block):
            source = root / script
            text = source.read_text(encoding='utf-8') if source.is_file() else ''
            if f"'{command}'" not in text and f'"{command}"' not in text:
                problems.append(f'{script} has no command {command}')
    # Paths anywhere in the file, comments and cache keys included: a step, a comment or a hashFiles() naming a file
    # that is gone is stale the same way. Outputs (build/) and prepared inputs (.dev/) are made by the run itself.
    for word in path_words(workflow):
        expanded = [word]
        for name, values in loops.items():
            if f'${name}' in word or '${' + name + '}' in word:
                expanded = [w.replace('${' + name + '}', v).replace('$' + name, v) for w in expanded for v in values]
        for path in expanded:
            if '$' in path or '*' in path or set(Path(path).parts) & {'build', '.dev'}:
                continue
            if not any((root / directory / path).exists() for directory in directories):
                problems.append(f'build.yml names {path}, which the tree does not have')
    return problems


class CiContractTest(unittest.TestCase):
    def test_docs_name_the_command_the_kernel_job_runs(self):
        command = kernel_command((ROOT / '.github/workflows/build.yml').read_text(encoding='utf-8'))
        for name in ('introduction.md', 'introduction.zh-CN.md'):
            with self.subTest(document=name):
                bullet = ci_bullet((ROOT / name).read_text(encoding='utf-8'))
                self.assertIn(f'`{command}`', bullet, f'{name} describes CI with a command build.yml does not run')

    def test_the_contract_can_tell_a_stale_description(self):
        workflow = ('      - name: Build and check (boot only)\n        shell: bash\n'
                    '        run: cd forbric-kernel && ./gradlew build -Pforbric.skipBaseline=ci-unstaged\n')
        command = kernel_command(workflow)
        self.assertEqual(command, './gradlew build -Pforbric.skipBaseline=ci-unstaged')
        stale = '- **CI** (`build.yml`): job `kernel` runs `./gradlew jar test` in\n  `forbric-kernel/`.\n\n## 17.'
        self.assertNotIn(f'`{command}`', ci_bullet(stale))

    def test_ci_runs_only_tasks_files_and_properties_the_tree_has(self):
        self.maxDiff = None
        workflow = (ROOT / '.github/workflows/build.yml').read_text(encoding='utf-8')
        self.assertEqual(stale_references(workflow), [],
                         'build.yml names a task, file, property or skip profile this tree no longer has')

    def test_the_contract_finds_every_kind_of_stale_reference(self):
        # One of each kind, in the shapes build.yml uses: a step that cds in, a -p call, a subshell, a folded summary
        # step, a loop over gate names, and a comment. Real names are kept beside each stale one, so a reader that
        # flags everything fails too.
        workflow = '\n'.join([
            '      # Reads forbric-kernel/src/test/skip-baseline/ci-unstaged.tsv and forbric-kernel/no-such-dir/.',
            '      - name: one',
            '        run: cd forbric-kernel && ./gradlew jar noSuchTask -x alsoNoSuchTask -Pforbric.skipBaseline=no-such-profile'
            ' -Pforbric.noSuchProperty -Pforbric.requireTransfer=true',
            '      - name: two',
            '        run: ./forbric-kernel-installer/gradlew -p forbric-kernel-installer installerTest noSuchInstallerTask',
            '      - name: three',
            '        shell: bash',
            '        run: |',
            '          (cd forbric-kernel-installer && ./gradlew -q jar)',
            '          ./gradlew -q printBootClasspath',
            '          cd forbric-kernel',
            '          for gate in m1 m99-no-such-gate; do bash "run/gate-$gate.sh"; done',
            '          bash ../forbric-loader/run/build-testmods.sh',
            '          bash ../forbric-loader/run/no-such-script.sh',
            '      - name: four',
            '        run: >-',
            '          python tools/junit_report.py summary --baseline forbric-kernel/src/test/skip-baseline/gone.tsv',
            '          && python tools/dev.py no-such-command',
        ])
        self.assertEqual(sorted(stale_references(workflow)), sorted([
            'forbric-kernel: task noSuchTask does not exist',
            'forbric-kernel: task alsoNoSuchTask does not exist',
            'forbric-kernel: -Pforbric.noSuchProperty is read by nothing in its build.gradle',
            'forbric-kernel: skip baseline profile no-such-profile has no src/test/skip-baseline/no-such-profile.tsv',
            'forbric-kernel-installer: task noSuchInstallerTask does not exist',
            # the subshell's cd held for its own line only, so this call runs at the root
            'Gradle runs in ., which has no build.gradle',
            'build.yml names run/gate-m99-no-such-gate.sh, which the tree does not have',
            'build.yml names ../forbric-loader/run/no-such-script.sh, which the tree does not have',
            'build.yml names forbric-kernel/src/test/skip-baseline/gone.tsv, which the tree does not have',
            'tools/dev.py has no command no-such-command',
            'build.yml names forbric-kernel/no-such-dir/, which the tree does not have',
        ]))


if __name__ == '__main__':
    unittest.main()
