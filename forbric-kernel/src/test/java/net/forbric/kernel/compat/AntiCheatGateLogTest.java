package net.forbric.kernel.compat;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Exercise the production normalization, boundary split and both Grim assertions without starting a server. */
class AntiCheatGateLogTest {
    private static final Path GATE = Path.of("run/gate-m13-anticheat.sh");
    private static final Path LIB = Path.of("run/lib.sh").toAbsolutePath();
    private static final String JOIN = "[23:31:06 INFO]: ForbricKernel joined the game\n";
    private static final String MARKER = "[23:31:50 INFO]: [Server] FORBRIC-CONTROL\n";
    private static final String CONTROL = "> \r  \r[23:31:52 INFO]: \u001b[96mGrim \u001b[90m» "
            + "\u001b[97mForbricKernel \u001b[96mfailed \u001b[97mSimulation\u001b[96m "
            + "\u001b[97m(x\u001b[91m1\u001b[97m) \u001b[37m5.999436 /gl 1\u001b[0m\n";
    @TempDir Path temp;

    @Test
    void coloredRealShapedControlPassesAndRawEvidenceIsUnchanged() throws Exception {
        byte[] raw = (JOIN + MARKER + CONTROL).getBytes(StandardCharsets.UTF_8);
        Result result = run(raw);
        assertEquals(0, result.exit(), result.output());
        assertTrue(result.output().contains("BEFORE=0"), result.output());
        assertTrue(result.output().contains("AFTER=1"), result.output());
        assertArrayEquals(raw, Files.readAllBytes(temp.resolve("server-raw.log")));
        String plain = Files.readString(temp.resolve("gate-m13-server-plain.log"));
        assertFalse(plain.contains("\u001b"));
        assertTrue(plain.contains("ForbricKernel failed Simulation (x1) 5.999436"));
    }

    @Test
    void aMissingPositiveControlStillFails() throws Exception {
        Result result = run((JOIN + MARKER + "[23:32:02 INFO]: ForbricKernel disconnected\n")
                .getBytes(StandardCharsets.UTF_8));
        assertEquals(1, result.exit(), result.output());
        assertTrue(result.output().contains("FAIL Grim flagged the control move"), result.output());
    }

    @Test
    void aColoredLegitimateMovementViolationStillFails() throws Exception {
        Result result = run((JOIN + CONTROL + MARKER + CONTROL).getBytes(StandardCharsets.UTF_8));
        assertEquals(1, result.exit(), result.output());
        assertTrue(result.output().contains("FAIL no movement flag before the control"), result.output());
        assertTrue(result.output().contains("AFTER=1"), result.output());
    }

    @Test
    void coloredTimerFlagsKeepTheirExistingHostLoadClassification() throws Exception {
        Result result = run((JOIN + CONTROL.replace("Simulation", "TimerA") + MARKER + CONTROL)
                .getBytes(StandardCharsets.UTF_8));
        assertEquals(0, result.exit(), result.output());
        assertTrue(result.output().contains("host-load flag (ignored)"), result.output());
        assertTrue(result.output().contains("PASS no movement flag before the control (0)"), result.output());
    }

    private Result run(byte[] raw) throws Exception {
        Path input = temp.resolve("server-raw.log");
        Files.write(input, raw);
        String gate = Files.readString(GATE);
        String program = ". \"$1\"\nBUILD=\"$2\"\nSLOG=\"$3\"\nPLAYER=ForbricKernel\n"
                + section(gate, "M13_LOG_SPLIT") + "\n" + section(gate, "M13_LOG_ASSERTIONS")
                + "\nprintf 'BEFORE=%s\\n' \"$(grep -ac \"$FLAG_RE\" \"$BUILD/gate-m13-before-control.log\")\""
                + "\nprintf 'AFTER=%s\\n' \"$(grep -ac \"$FLAG_RE\" \"$BUILD/gate-m13-after-control.log\")\""
                + "\nexit \"$FAIL\"\n";
        Path log = temp.resolve("assertions.log");
        Process process = new ProcessBuilder(List.of("bash", "-c", program, "m13-log-test",
                LIB.toString(), temp.toString(), input.toString()))
                .redirectErrorStream(true).redirectOutput(log.toFile()).start();
        boolean ended = process.waitFor(15, TimeUnit.SECONDS);
        if (!ended) process.destroyForcibly();
        assertTrue(ended, "log-only gate fragment timed out");
        return new Result(process.exitValue(), Files.readString(log));
    }

    private static String section(String gate, String name) {
        int start = gate.indexOf("# " + name + "_BEGIN");
        assertTrue(start >= 0, "production fragment missing: " + name);
        start = gate.indexOf('\n', start) + 1;
        int end = gate.indexOf("# " + name + "_END", start);
        assertTrue(end > start, "production fragment end missing: " + name);
        return gate.substring(start, end);
    }

    private record Result(int exit, String output) {}
}
