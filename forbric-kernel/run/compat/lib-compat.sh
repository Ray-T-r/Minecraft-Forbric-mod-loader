#!/usr/bin/env bash
# WINSH/WINFILE are executable commands, parsed with shlex (never eval'd).
# A transfer succeeds only after an independent SHA-256/size check on both endpoints.
compat_transport() {
  python3 - "$@" <<'PY'
import hashlib, os, pathlib, re, shlex, subprocess, sys, tempfile, uuid

def invoke(key, arguments):
    command = shlex.split(os.environ.get(key, ''))
    if not command:
        raise RuntimeError(f'{key} must name the configured remote transport command')
    result = subprocess.run(command + arguments, timeout=240, text=True,
                            stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
    if result.returncode:
        raise RuntimeError(f'{key} exited {result.returncode}: {result.stdout}')
    return result.stdout

def ps(value):
    return "'" + value.replace("'", "''") + "'"

def powershell(command):
    sentinel = 'FORBRIC_REMOTE_OK_' + uuid.uuid4().hex
    wrapped = ("$ErrorActionPreference='Stop'; & { " + command +
               " }; if (-not $?) { throw 'remote command failed' }; Write-Output " + ps(sentinel))
    output = invoke('WINSH', ['--ps', wrapped])
    lines = output.splitlines()
    if sentinel not in lines:
        raise RuntimeError('remote command did not confirm success: ' + output)
    return '\n'.join(line for line in lines if line != sentinel)

def fingerprint(path):
    digest = hashlib.sha256()
    with pathlib.Path(path).open('rb') as source:
        for part in iter(lambda: source.read(1024 * 1024), b''):
            digest.update(part)
    return digest.hexdigest() + ':' + str(pathlib.Path(path).stat().st_size)

def remote_fingerprint(path):
    output = powershell('$file = Get-Item -LiteralPath ' + ps(path) +
                        "; if ($file.PSIsContainer) { throw 'expected a file' }; "
                        "Write-Output ('FORBRIC_FILE=' + (Get-FileHash -Algorithm SHA256 -LiteralPath " +
                        ps(path) + ").Hash.ToLowerInvariant() + ':' + $file.Length)")
    matches = re.findall(r'^FORBRIC_FILE=([a-f0-9]{64}:\d+)$', output, re.M)
    if len(matches) != 1:
        raise RuntimeError('remote file fingerprint missing: ' + output)
    return matches[0]

try:
    operation, *arguments = sys.argv[1:]
    if operation == 'ps':
        print(powershell(arguments[0]))
    elif operation == 'put':
        source, target = arguments
        expected = fingerprint(source)
        output = invoke('WINFILE', ['put', source, target])
        if remote_fingerprint(target) != expected:
            raise RuntimeError('upload fingerprint mismatch: ' + target)
        print(output, end='')
    elif operation == 'get':
        source, target = arguments
        expected = remote_fingerprint(source)
        target = pathlib.Path(target)
        target.parent.mkdir(parents=True, exist_ok=True)
        # A failed transfer must never be mistaken for a stale file from an earlier download.
        with tempfile.TemporaryDirectory(prefix='.forbric-download-', dir=target.parent) as temp:
            temporary = pathlib.Path(temp) / target.name
            output = invoke('WINFILE', ['get', source, str(temporary)])
            if not temporary.is_file() or fingerprint(temporary) != expected:
                raise RuntimeError('download missing or fingerprint mismatch: ' + source)
            temporary.replace(target)
        print(output, end='')
    else:
        raise ValueError('unknown transport operation: ' + operation)
except (OSError, ValueError, RuntimeError, subprocess.SubprocessError) as error:
    print('compat transport failed: ' + str(error), file=sys.stderr)
    sys.exit(1)
PY
}
remote_ps() { compat_transport ps "$1"; }
remote_put() { compat_transport put "$1" "$2"; }
remote_get() { compat_transport get "$1" "$2"; }
