#!/usr/bin/env python3
"""Run nightly, on the developer's Mac, what CI cannot run: the client gates (they open a macOS window), the
gates that need third-party mod packs, and the soak.

launchd starts it (com.forbric.nightly.plist; README.md beside this file says how to install it). Python
standard library only, like tools/dev.py. One night:

  1. fetch, and put a dedicated worktree (--work) detached at origin/main. The main checkout is only read:
     its HEAD, branch, index and files are never changed.
  2. link into it, from the main checkout, the fixtures the gates and the staged tests read (FIXTURES).
  3. run `tools/dev.py integration` (strict: a skipped test fails it), then `gates-all.sh`. gate-m34-soak takes
     two hours or more, so it runs only on Sundays, and a Sunday run is a --release acceptance run.
  4. write results/<YYYY-MM-DD>/summary.md and latest.md on the orphan branch ci-results, in a worktree of its
     own (<work>-results), and push that branch and nothing else.
  5. set the commit status nightly/dev-mac on the tested commit.

--dry-run prints every command and runs only the ones that change nothing: no worktree is made or cleaned, no
test runs, nothing is committed, pushed or posted.
"""
import argparse
from collections import Counter
import datetime
import os
from pathlib import Path
import shlex
import signal
import subprocess
import sys
import time

REPO_SLUG = 'Ray-T-r/Minecraft-Forbric-mod-loader'
STATUS_CONTEXT = 'nightly/dev-mac'
RESULTS_BRANCH = 'ci-results'
SOAK_GATE = 'gate-m34-soak.sh'
SUNDAY = 6  # datetime.date.weekday()
# Everything the gates and the staged tests read from a checkout that is not in git, found by reading
# forbric-kernel/run/gate-m*.sh, gates-all.sh, gates-parallel.py and the tests' fixture paths. The rest of what
# they read comes from FORBRIC_OLD (the staged game jars, the downloaded mods and canaries in
# forbric-loader/run) and MC_DIR, which stay in the main checkout and are passed by environment instead.
FIXTURES = (
    # Pack installs. The client gates get their own copy of client-merged-pack from gates-parallel.py, so no
    # gate writes through these links; the others are only read (m8 reads client-kernel/mods, the staged tests
    # read client-popular and client-kernel).
    'forbric-kernel/run/client-merged-pack',
    'forbric-kernel/run/client-popular',
    'forbric-kernel/run/client-neo-pack',
    'forbric-kernel/run/client-kernel',
    # The third-party mod sets the bytecode tests read (sweep90, carpet, create-fly, ...).
    'forbric-kernel/build/compat-inputs',
    # gate-m56's phase 3: a Mod Menu jar for 26.2 (and the fabric-api beside it). The gate requires it -- SKIP-FATAL,
    # exit 3, RED -- rather than passing with Mod Menu's list untested, so a main checkout without it shows here.
    'forbric-kernel/run/modmenu',
    # Two more the tests read by path (GuiItemCaptureMixinAdapterTest, KernelClientHookMixinAnchorsTest,
    # CompatPluginPlatformInjectorTest). Without them the first nightly's strict run failed those tests as skipped.
    'forbric-kernel/build/sweep80-mac',
    'forbric-kernel/build/sweep100-mac-network',
    # The fabric-loader substrate ./bootstrap.sh checks out (gitignored). forbric-loader compiles its sources, so the
    # installer build (gate-m17) and gate-m0's bundled-baseline check cannot run without it; the first nightly
    # reported both as failures of the code under test.
    'fabric-loader',
    # tools/dev.py's state: the pinned fabric-api and energy jars and the natives.
    'forbric-kernel/.dev',
)
GIT = ('git',)
GH = ('gh',)
BASH = ('bash',)
PYTHON = (sys.executable,)


class NightlyError(Exception):
    pass


class Step:
    """One long command of the night: what ran, how it ended, where its output is."""

    def __init__(self, name, display, ran=False, returncode=None, seconds=0.0, timed_out=False, limit=0, note=''):
        self.name, self.display, self.ran = name, display, ran
        self.returncode, self.seconds, self.timed_out, self.limit, self.note = returncode, seconds, timed_out, limit, note

    @property
    def ok(self):
        return self.ran and not self.timed_out and self.returncode == 0

    def describe(self):
        if not self.ran:
            return 'not run' + (f' ({self.note})' if self.note else '')
        if self.timed_out:
            return f'TIMED OUT after {minutes(self.seconds)} (limit {minutes(self.limit)})'
        if self.returncode == 0:
            return f'passed in {minutes(self.seconds)}'
        return f'FAILED (exit {self.returncode}) in {minutes(self.seconds)}'

    def short(self):
        if not self.ran:
            return 'not run'
        if self.timed_out:
            return 'TIMED OUT'
        return 'passed' if self.returncode == 0 else f'FAILED (exit {self.returncode})'


class Night:
    def __init__(self, date, soak, dry_run):
        self.date, self.soak, self.dry_run = date, soak, dry_run
        self.started = datetime.datetime.now().astimezone()
        self.sha, self.subject, self.ref = '', '', ''
        self.errors, self.missing_fixtures = [], []
        self.integration = Step('integration', 'python3 tools/dev.py integration')
        self.gates = Step('gates', 'bash forbric-kernel/run/compat/gates-all.sh')
        self.junit = ''
        self.gate_results = []
        self.logs = ''

    @property
    def passed(self):
        return (not self.errors and self.integration.ok and self.gates.ok and bool(self.gate_results)
                and not any(verdict == 'RED' for _, verdict, _ in self.gate_results))


def minutes(seconds):
    return f'{seconds / 60:.0f} min' if seconds >= 60 else f'{seconds:.0f} s'


def soak_tonight(date, mode='auto'):
    """The soak is >= 7200 s of simulation on top of the half hour the rest takes: once a week is enough."""
    return mode == 'always' or (mode == 'auto' and date.weekday() == SUNDAY)


def gates_arguments(soak, jobs):
    # A weekday run skips the soak, so it cannot be --release: a release run fails on any --skip by design.
    # Sunday's run has nothing skipped and is the full acceptance run.
    return ['-j', str(jobs)] + (['--release'] if soak else ['--skip', SOAK_GATE])


def default_minecraft_dir():
    # The launch scripts' own default, so the tests and the gates of one night read one Minecraft install.
    if sys.platform == 'darwin':
        return Path.home() / 'Library/Application Support/minecraft'
    return Path.home() / '.minecraft'


class Runner:
    """Prints every command before running it. Under --dry-run it runs only the ones that change nothing."""

    def __init__(self, dry_run, out=None):
        self.dry_run = dry_run
        self.out = out or sys.stdout

    def say(self, text):
        print(text, file=self.out, flush=True)

    def show(self, command, cwd=None, env=None, skipped=False):
        text = shlex.join(str(part) for part in command)
        if env:
            text = ' '.join(f'{key}={shlex.quote(str(value))}' for key, value in env.items()) + ' ' + text
        if cwd:
            text = f'(cd {shlex.quote(str(cwd))} && {text})'
        self.say(('[dry-run] ' if skipped else '+ ') + text)

    def run(self, command, writes=True, check=True):
        skipped = self.dry_run and writes
        self.show(command, skipped=skipped)
        if skipped:
            return subprocess.CompletedProcess(command, 0, '', '')
        result = subprocess.run([str(part) for part in command], capture_output=True, text=True,
                stdin=subprocess.DEVNULL)
        if check and result.returncode:
            raise NightlyError(f'{shlex.join(str(p) for p in command)} exited {result.returncode}: '
                    + (result.stderr or result.stdout).strip()[-500:])
        return result

    def git(self, directory, *arguments, writes=True, check=True):
        return self.run(GIT + ('-C', str(directory)) + arguments, writes=writes, check=check)

    def symlink(self, source, target):
        self.show(['ln', '-s', source, target], skipped=self.dry_run)
        if not self.dry_run:
            target.parent.mkdir(parents=True, exist_ok=True)
            os.symlink(source, target, target_is_directory=source.is_dir())

    def unlink(self, path):
        self.show(['rm', path], skipped=self.dry_run)
        if not self.dry_run:
            path.unlink()

    def write_text(self, path, text):
        self.say(f'{"[dry-run] " if self.dry_run else ""}write {path} ({len(text.encode("utf-8"))} bytes)')
        if not self.dry_run:
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(text, encoding='utf-8', newline='\n')

    def step(self, step, command, cwd, env_overrides, timeout, log):
        """A long command with its output in a log file, stopped with everything it started at the timeout."""
        self.show(command, cwd=cwd, env=env_overrides, skipped=self.dry_run)
        step.limit = timeout
        if self.dry_run:
            step.note = 'dry run'
            return step
        log.parent.mkdir(parents=True, exist_ok=True)
        started = time.monotonic()
        with log.open('wb') as stream:
            try:
                # Its own session, so the timeout reaches gradle's test JVMs and a gate's game processes, not
                # only the shell or python at the top.
                process = subprocess.Popen([str(part) for part in command], cwd=cwd,
                        env=dict(os.environ, **env_overrides), stdout=stream, stderr=subprocess.STDOUT,
                        stdin=subprocess.DEVNULL, start_new_session=os.name == 'posix')
            except OSError as error:
                step.note = f'could not start: {error}'
                return step
            try:
                process.wait(timeout=timeout)
            except subprocess.TimeoutExpired:
                step.timed_out = True
                stop(process)
        step.ran, step.returncode, step.seconds = True, process.returncode, time.monotonic() - started
        self.say(f'  {step.name}: {step.describe()}; log {log}')
        return step


def stop(process, grace=60):
    if os.name != 'posix':
        # /T takes the whole tree; Popen.kill() alone would leave what the step started running.
        subprocess.run(['taskkill', '/F', '/T', '/PID', str(process.pid)], capture_output=True)
        process.wait()
        return
    # PermissionError as well as ProcessLookupError: once the leader has been reaped, macOS can answer EPERM for a
    # group that has nothing left this user may signal. Either way nothing of ours is left to stop.
    try:
        os.killpg(process.pid, signal.SIGTERM)
    except (ProcessLookupError, PermissionError):
        pass
    try:
        process.wait(timeout=grace)
    except subprocess.TimeoutExpired:
        pass
    # Whatever is still in the group (a game JVM that ignored TERM) goes now; the leader may already be gone.
    try:
        os.killpg(process.pid, signal.SIGKILL)
    except (ProcessLookupError, PermissionError):
        pass
    process.wait()


def toplevel(runner, path):
    result = runner.git(path, 'rev-parse', '--show-toplevel', writes=False, check=False)
    return Path(result.stdout.strip()).resolve() if result.returncode == 0 else None


def common_dir(runner, path):
    text = runner.git(path, 'rev-parse', '--git-common-dir', writes=False).stdout.strip()
    directory = Path(text)
    return (directory if directory.is_absolute() else path / directory).resolve()


def registered_worktrees(runner, repo):
    text = runner.git(repo, 'worktree', 'list', '--porcelain', writes=False).stdout
    return {Path(line[len('worktree '):]).resolve() for line in text.splitlines() if line.startswith('worktree ')}


def own_worktree(runner, repo, path, what):
    """path must be the top of a worktree of this repository: never the main checkout, never a folder in it."""
    if path.resolve() == repo.resolve():
        raise NightlyError(f'{what} {path} is the main checkout itself')
    if repo.resolve() in path.resolve().parents:
        raise NightlyError(f'{what} {path} is inside the main checkout; put it beside it')
    if not path.exists():
        if path.resolve() in registered_worktrees(runner, repo):
            # Deleted by hand but still registered: git refuses to add it again until the stale entry goes.
            runner.git(repo, 'worktree', 'prune')
        return False
    if toplevel(runner, path) != path.resolve() or common_dir(runner, path) != common_dir(runner, repo):
        raise NightlyError(f'{what} {path} exists but is not a worktree of {repo}; move it away or pass another --work')
    return True


def prepare_worktree(runner, repo, work, sha):
    if own_worktree(runner, repo, work, 'the nightly worktree'):
        runner.git(work, 'checkout', '--quiet', '--force', '--detach', sha)
        # Yesterday's build outputs, rundirs and links are not evidence about today's commit.
        runner.git(work, 'clean', '-ffdxq')
    else:
        runner.git(repo, 'worktree', 'add', '--quiet', '--detach', str(work), sha)


def link_fixtures(runner, repo, work):
    """Link each fixture the main checkout has; return the ones it does not have."""
    missing = []
    for relative in FIXTURES:
        source, target = repo / relative, work / relative
        if not source.exists():
            missing.append(relative)
            continue
        if target.is_symlink():
            if Path(os.readlink(target)) == source:
                continue
            runner.unlink(target)
        elif target.exists():
            raise NightlyError(f'{target} is a real file or directory in the tested commit; refusing to replace it with a link')
        runner.symlink(source, target)
    return missing


def junit_summary(runner, work):
    script = work / 'tools/junit_report.py'
    if not script.is_file():
        return '_tools/junit_report.py is not in the tested commit; no JUnit totals._'
    result = runner.run(PYTHON + (script, 'summary', '--title', 'JUnit totals', '--results',
            work / 'forbric-kernel/build/test-results', '--suites', 'test,transferTest', '--root', work),
            writes=False, check=False)
    if result.returncode:
        return f'_junit_report.py summary exited {result.returncode}:_ `{result.stderr.strip()[-300:]}`'
    return result.stdout.strip()


def read_gate_results(summary):
    """(gate, verdict, rest) for each RESULT line of build/gates/summary.txt."""
    results = []
    if not summary.is_file():
        return results
    for line in summary.read_text(encoding='utf-8', errors='replace').splitlines():
        parts = line.split(' ', 3)
        if len(parts) >= 3 and parts[0] == 'RESULT':
            results.append((parts[1], parts[2], parts[3] if len(parts) > 3 else ''))
    return results


def gate_counts(results):
    counts = Counter(verdict for _, verdict, _ in results)
    return ', '.join(f'{counts[v]} {v}' for v in ('GREEN', 'RED', 'EXPECTED_RED', 'SKIP') if counts[v]) or 'no RESULT lines'


def render_summary(night, slug=REPO_SLUG):
    """The page published on ci-results. It holds counts, test ids and gate verdicts only: never a log line, since
    a game log or a failing bytecode comparison can carry Mojang code, and never a path on the Mac."""
    verdict = 'DRY RUN' if night.dry_run else 'PASS' if night.passed else 'FAIL'
    subject = night.subject.replace('|', '\\|')
    lines = [f'# Forbric nightly {night.date}: {verdict}', '']
    if night.dry_run:
        lines += ['_Dry run: nothing was run, committed or pushed._', '']
    lines += ['| | |', '| --- | --- |',
            f'| commit | [`{night.sha[:12]}`](https://github.com/{slug}/commit/{night.sha}) {subject} |',
            f'| tested | `{night.ref}` on the developer\'s Mac (commit status `{STATUS_CONTEXT}`) |',
            f'| started | {night.started:%Y-%m-%d %H:%M %z} |',
            f'| soak (`{SOAK_GATE}`) | ' + ('run: a `--release` run, nothing skipped'
                    if night.soak else 'skipped: it runs on Sundays') + ' |',
            f'| integration | {night.integration.describe()} |',
            f'| gates | {night.gates.describe()}' + (f': {gate_counts(night.gate_results)}' if night.gates.ran else '') + ' |']
    if night.missing_fixtures:
        lines.append('| fixtures the main checkout lacks | ' + ', '.join(f'`{f}`' for f in night.missing_fixtures) + ' |')
    lines.append('')
    if night.errors:
        lines += ['**The night could not run:**', ''] + [f'- {error}' for error in night.errors] + ['']
    lines += [f'## Integration: `{night.integration.display}`', '']
    lines += [night.junit or '_No JUnit results: the step did not run._', '']
    lines += [f'## Gates: `{night.gates.display}`', '']
    if night.gates.ran and not night.gate_results:
        lines += ['_No `forbric-kernel/build/gates/summary.txt`: the run stopped before any gate reported._', '']
    attention = [r for r in night.gate_results if r[1] != 'GREEN']
    if attention:
        lines += [f'- `{gate}` {verdict} {rest}'.rstrip() for gate, verdict, rest in attention] + ['']
    if night.gate_results:
        lines += [f'<details><summary>All {len(night.gate_results)} RESULT lines</summary>', '', '```']
        lines += [f'RESULT {gate} {verdict} {rest}'.rstrip() for gate, verdict, rest in night.gate_results]
        lines += ['```', '', '</details>', '']
    if night.logs:
        lines += [f'Logs stay on the Mac, in the nightly worktree: `{night.logs}/` and `forbric-kernel/build/gates/`.', '']
    return '\n'.join(lines)


def status_description(night):
    parts = []
    if night.errors:
        parts.append('could not run: ' + night.errors[0])
    parts.append('integration ' + night.integration.short())
    gates = 'gates ' + night.gates.short()
    if night.gate_results:
        gates += ': ' + gate_counts(night.gate_results)
        red = [gate for gate, verdict, _ in night.gate_results if verdict == 'RED']
        if red:
            gates += ' (' + ' '.join(red) + ')'
    parts.append(gates)
    text = '; '.join(parts)
    return text if len(text) <= 140 else text[:139] + '…'  # GitHub's limit for a status description


def prepare_results(runner, repo, results, remote):
    remote_ref = f'refs/remotes/{remote}/{RESULTS_BRANCH}'
    on_remote = runner.git(repo, 'rev-parse', '--verify', '--quiet', remote_ref, writes=False, check=False).returncode == 0
    if own_worktree(runner, repo, results, 'the results worktree'):
        head = runner.git(results, 'symbolic-ref', '--quiet', 'HEAD', writes=False, check=False).stdout.strip()
        if head != f'refs/heads/{RESULTS_BRANCH}':
            raise NightlyError(f'{results} has {head or "a detached HEAD"} checked out, not {RESULTS_BRANCH}')
    elif runner.git(repo, 'rev-parse', '--verify', '--quiet', f'refs/heads/{RESULTS_BRANCH}',
            writes=False, check=False).returncode == 0:
        runner.git(repo, 'worktree', 'add', '--quiet', str(results), RESULTS_BRANCH)
    elif on_remote:
        runner.git(repo, 'worktree', 'add', '--quiet', '-b', RESULTS_BRANCH, str(results), remote_ref)
    else:
        # An orphan: the results share no history with the code, so the branch never drags the tree along.
        runner.git(repo, 'worktree', 'add', '--quiet', '--orphan', '-b', RESULTS_BRANCH, str(results))
    if on_remote and not runner.dry_run:
        has_head = runner.git(results, 'rev-parse', '--verify', '--quiet', 'HEAD', writes=False, check=False).returncode == 0
        behind = has_head and runner.git(results, 'merge-base', '--is-ancestor', remote_ref, 'HEAD',
                writes=False, check=False).returncode != 0
        if behind:
            # A night whose push failed left a commit here, and the remote moved on: put ours on top.
            if runner.git(results, 'rebase', remote_ref, check=False).returncode:
                runner.git(results, 'rebase', '--abort', check=False)
                raise NightlyError(f'{RESULTS_BRANCH} in {results} cannot be rebased onto {remote_ref}')


def publish_results(runner, repo, results, remote, night, text):
    """Commit the summary to ci-results and push that branch. True when it reached the remote."""
    # Fetched again: the tests ran for hours since the first fetch.
    runner.git(repo, 'fetch', '--quiet', remote, check=False)
    prepare_results(runner, repo, results, remote)
    dated = Path('results') / night.date.isoformat() / 'summary.md'
    runner.write_text(results / dated, text)
    runner.write_text(results / 'latest.md', f'Latest nightly: [{dated.as_posix()}]({dated.as_posix()})\n\n' + text)
    runner.git(results, 'add', '--', dated.as_posix(), 'latest.md')
    if not runner.dry_run and runner.git(results, 'diff', '--cached', '--quiet', writes=False, check=False).returncode == 0:
        runner.say('  results unchanged; nothing to commit')
    else:
        verdict = 'pass' if night.passed else 'FAIL'
        runner.git(results, 'commit', '--quiet', '-m', f'Nightly {night.date}: {verdict} on {night.sha[:12]}')
    runner.git(results, 'push', '--quiet', remote, RESULTS_BRANCH)
    return not runner.dry_run


def set_status(runner, night, slug, results_url):
    command = GH + ('api', f'repos/{slug}/statuses/{night.sha}', '-f', f'state={"success" if night.passed else "failure"}',
            '-f', f'context={STATUS_CONTEXT}', '-f', f'description={status_description(night)}')
    if results_url:
        command += ('-f', f'target_url={results_url}')
    runner.run(command)


def parse_arguments(argv):
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument('--repo', required=True, help='the main checkout; only read, apart from fetch and worktree bookkeeping')
    parser.add_argument('--work', default=str(Path.home() / 'Documents/Forbric-nightly'),
            help='the nightly worktree (default ~/Documents/Forbric-nightly); results go to <work>-results')
    parser.add_argument('--remote', default='origin')
    parser.add_argument('--branch', default='main', help='the branch of --remote to test')
    parser.add_argument('--forbric-old', help='FORBRIC_OLD for the tests and gates; default <repo>/forbric-loader')
    parser.add_argument('--mc-dir', help='MC_DIR; default $MC_DIR, else the launch scripts\' default Minecraft directory')
    parser.add_argument('--date', type=datetime.date.fromisoformat, help='the night, YYYY-MM-DD; default today')
    parser.add_argument('--soak', choices=('auto', 'always', 'never'), default='auto', help='auto: on Sundays only')
    parser.add_argument('--jobs', default='auto', help='gates-all.sh -j')
    parser.add_argument('--integration-timeout-min', type=float, default=120)
    parser.add_argument('--gates-timeout-min', type=float,
            help='default 180, or 360 on a night with the soak')
    parser.add_argument('--slug', default=REPO_SLUG, help='GitHub owner/repo for the commit status')
    parser.add_argument('--gh', help='the gh executable (default: gh on PATH)')
    parser.add_argument('--dry-run', action='store_true', help='print every command; run only those that change nothing')
    return parser.parse_args(argv)


def run_night(options, runner):
    repo = Path(options.repo).expanduser().resolve()
    work = Path(options.work).expanduser().absolute()
    results = Path(str(work) + '-results')
    night = Night(options.date or datetime.date.today(), False, options.dry_run)
    night.soak = soak_tonight(night.date, options.soak)
    if toplevel(runner, repo) != repo:
        raise NightlyError(f'{repo} is not the top of a git checkout')
    for path in (work, results):
        # A configuration error, not a verdict on the commit: refused before anything is fetched or posted.
        if path.resolve() == repo or repo in path.resolve().parents:
            raise NightlyError(f'{path} is the main checkout or inside it; the nightly works beside it')
    night.ref = f'{options.remote}/{options.branch}'
    runner.git(repo, 'fetch', '--quiet', '--prune', options.remote)
    night.sha = runner.git(repo, 'rev-parse', '--verify', f'{night.ref}^{{commit}}', writes=False).stdout.strip()
    night.subject = runner.git(repo, 'log', '-1', '--format=%s', night.sha, writes=False).stdout.strip()
    runner.say(f'nightly {night.date}: {night.ref} = {night.sha} {night.subject}')

    try:
        prepare_worktree(runner, repo, work, night.sha)
        night.missing_fixtures = link_fixtures(runner, repo, work)
    except (NightlyError, OSError) as error:
        night.errors.append(str(error))
    if not night.errors:
        logs = work / 'build/nightly'
        night.logs = 'build/nightly'
        env = {'FORBRIC_OLD': str(Path(options.forbric_old).expanduser() if options.forbric_old else repo / 'forbric-loader'),
                'MC_DIR': str(Path(options.mc_dir).expanduser() if options.mc_dir
                        else Path(os.environ.get('MC_DIR') or default_minecraft_dir()))}
        runner.step(night.integration, PYTHON + ('tools/dev.py', 'integration'), work, env,
                options.integration_timeout_min * 60, logs / 'integration.log')
        # Now, before the gates: gate-m0 runs the unit suite again and rewrites these reports.
        if night.integration.ran:
            night.junit = junit_summary(runner, work)
        arguments = gates_arguments(night.soak, options.jobs)
        night.gates.display += ' ' + ' '.join(arguments)
        limit = options.gates_timeout_min or (360 if night.soak else 180)
        runner.step(night.gates, BASH + ('forbric-kernel/run/compat/gates-all.sh',) + tuple(arguments), work, env,
                limit * 60, logs / 'gates.log')
        if night.gates.ran:
            night.gate_results = read_gate_results(work / 'forbric-kernel/build/gates/summary.txt')

    text = render_summary(night, options.slug)
    if options.dry_run:
        runner.say('[dry-run] summary.md would read:\n' + text)
    pushed = False
    try:
        pushed = publish_results(runner, repo, results, options.remote, night, text)
    except (NightlyError, OSError) as error:
        runner.say(f'results were not published: {error}')
    url = f'https://github.com/{options.slug}/blob/{RESULTS_BRANCH}/results/{night.date}/summary.md' if pushed else ''
    set_status(runner, night, options.slug, url)
    runner.say(f'nightly {night.date}: {"PASS" if night.passed else "FAIL"}')
    return night, pushed


def main(argv=None, out=None):
    options = parse_arguments(sys.argv[1:] if argv is None else argv)
    global GH
    if options.gh:
        GH = (options.gh,)
    runner = Runner(options.dry_run, out)
    try:
        night, pushed = run_night(options, runner)
    except NightlyError as error:
        runner.say(f'nightly: ERROR: {error}')
        return 2
    except KeyboardInterrupt:
        return 130
    if options.dry_run:
        return 0
    return 0 if night.passed and pushed else 1


if __name__ == '__main__':
    sys.exit(main())
