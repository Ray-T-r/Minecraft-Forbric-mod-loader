package net.forbric.kernel.compat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CompatTransportTest {
    @TempDir Path temp;

    @Test
    void quotedPathsRoundTripThroughVerifiedTransports() throws Exception {
        Path source = Files.writeString(temp.resolve("local source.bin"), "round-trip fixture");
        Path remote = temp.resolve("remote's file.bin");
        assertEquals(0, transport(Map.of(), "remote_put", source.toString(), remote.toString()).exit());
        Path target = temp.resolve("download.bin");
        assertEquals(0, transport(Map.of(), "remote_get", remote.toString(), target.toString()).exit());
        assertEquals(Files.readString(source), Files.readString(target));
    }

    @Test
    void anExitZeroUploadThatDidNothingCannotPass() throws Exception {
        Path source = Files.writeString(temp.resolve("source.bin"), "new bytes");
        Path remote = Files.writeString(temp.resolve("remote.bin"), "stale bytes");
        var result = transport(Map.of("FAKE_DROP_PUT", "1"), "remote_put", source.toString(), remote.toString());
        assertNotEquals(0, result.exit());
        assertTrue(result.output().contains("upload fingerprint mismatch"), result.output());
    }

    @Test
    void anArtifactAlreadyThereByteForByteIsNotSentAgain() throws Exception {
        // Through a throttling relay the 35 MB merged base alone stalled every later remote call for minutes.
        Path source = Files.writeString(temp.resolve("artifact.jar"), "same bytes");
        Path remote = Files.writeString(temp.resolve("remote artifact.jar"), "same bytes");
        var result = transport(Map.of("FAKE_FAIL_PUT", "1"), "remote_put", source.toString(), remote.toString());
        assertEquals(0, result.exit(), result.output());
        assertTrue(result.output().contains("unchanged: "), result.output());
    }

    @Test
    void aMissingOrCorruptDownloadCannotReuseAStaleLocalFile() throws Exception {
        Path remote = Files.writeString(temp.resolve("remote.bin"), "correct bytes");
        Path target = Files.writeString(temp.resolve("target.bin"), "old local bytes");
        for (String defect : List.of("FAKE_DROP_GET", "FAKE_CORRUPT_GET")) {
            var result = transport(Map.of(defect, "1"), "remote_get", remote.toString(), target.toString());
            assertNotEquals(0, result.exit());
            assertTrue(result.output().contains("download missing or fingerprint mismatch"), result.output());
            assertEquals("old local bytes", Files.readString(target));
        }
        try (var files = Files.list(temp)) {
            assertFalse(files.anyMatch(file -> file.getFileName().toString().startsWith(".forbric-download-")));
        }
    }

    @Test
    void remoteShellExitZeroWithoutTheSuccessSentinelFails() throws Exception {
        var result = transport(Map.of("FAKE_PS_FAILURE", "1"), "remote_ps", "Write-Output 'fixture'");
        assertNotEquals(0, result.exit());
        assertTrue(result.output().contains("remote command did not confirm success"), result.output());
    }

    private DriverTools.Result transport(Map<String, String> extra, String... arguments) throws Exception {
        Path fake = Files.writeString(temp.resolve("fake transport.py"), """
                import hashlib, os, pathlib, re, shutil, sys
                operation, *args = sys.argv[1:]
                if operation == 'shell':
                    command = args[-1]
                    if os.environ.get('FAKE_PS_FAILURE'):
                        print('remote failed but transport returns zero'); sys.exit(0)
                    sentinel = re.search(r"Write-Output '(FORBRIC_REMOTE_OK_[a-f0-9]+)'$", command).group(1)
                    paths = re.findall(r"-LiteralPath '((?:[^']|'')*)'", command)
                    if paths:
                        file = pathlib.Path(paths[0].replace("''", "'"))
                        if not file.is_file(): print('missing remote file'); sys.exit(0)
                        print('FORBRIC_FILE=' + hashlib.sha256(file.read_bytes()).hexdigest() + ':' + str(file.stat().st_size))
                    print(sentinel)
                elif operation == 'file':
                    action, source, destination = args
                    if os.environ.get('FAKE_FAIL_' + action.upper()): print('transport should not have been used'); sys.exit(1)
                    if os.environ.get('FAKE_DROP_' + action.upper()): sys.exit(0)
                    if action == 'get' and os.environ.get('FAKE_CORRUPT_GET'):
                        pathlib.Path(destination).write_text('corrupt')
                    else:
                        shutil.copy2(source, destination)
                """);
        var command = new java.util.ArrayList<>(List.of("bash", "-c", ". \"$1\"; shift; \"$@\"", "compat",
                DriverTools.COMPAT.resolve("lib-compat.sh").toString()));
        command.addAll(List.of(arguments));
        Path log = temp.resolve("transport-output.txt");
        ProcessBuilder builder = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(log.toFile());
        String executable = System.getenv().getOrDefault("PYTHON", "python3") + " '" + fake.toString().replace("'", "'\\''") + "'";
        builder.environment().put("WINSH", executable + " shell");
        builder.environment().put("WINFILE", executable + " file");
        builder.environment().putAll(extra);
        Process process = builder.start();
        boolean finished = process.waitFor(20, TimeUnit.SECONDS);
        if (!finished) process.destroyForcibly();
        assertTrue(finished, "transport fixture timed out");
        return new DriverTools.Result(process.exitValue(), Files.readString(log));
    }
}
