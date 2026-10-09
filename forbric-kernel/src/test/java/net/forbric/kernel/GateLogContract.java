/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Holds a gate's log assertions to what the code really prints: reads a {@code check}/{@code check_absent}
 * pattern out of the gate script by its name and counts matches with the real {@code grep -acE}, as lib.sh does.
 * A gate that greps for wording nothing prints any more is red for every build, good or bad; this is how a unit
 * test notices first.
 */
public final class GateLogContract {
	private GateLogContract() { }

	/** The pattern of the check {@code gate} names {@code what}, exactly as bash hands it to grep. */
	public static String pattern(Path gate, String what) throws Exception {
		List<String> joined = new ArrayList<>();
		StringBuilder line = new StringBuilder();
		for (String raw : Files.readAllLines(gate)) {
			if (raw.endsWith("\\")) { line.append(raw, 0, raw.length() - 1).append(' '); continue; }
			joined.add(line.append(raw).toString());
			line.setLength(0);
		}
		Pattern check = Pattern.compile("^\\s*(?:check|check_absent)\\s+\"" + Pattern.quote(what)
				+ "\"\\s+(?:'([^']*)'|\"((?:[^\"\\\\]|\\\\.)*)\")\\s+\"\\$LOG\"");
		String found = null;
		for (String candidate : joined) {
			Matcher m = check.matcher(candidate);
			if (!m.find()) continue;
			assertNull(found, gate + " names \"" + what + "\" twice");
			// Double quotes: bash removes a backslash only before $ ` " \ and newline; the rest reach grep as written.
			found = m.group(1) != null ? m.group(1) : m.group(2).replaceAll("\\\\([$`\"\\\\])", "$1");
		}
		assertNotNull(found, gate + " has no check named \"" + what + "\"");
		return found;
	}

	/** How many lines of {@code text} {@code grep -acE pattern} counts, as the gates' check() does. */
	public static int count(Path scratch, String pattern, String text) throws Exception {
		Path log = Files.writeString(Files.createTempFile(scratch, "boot", ".log"), text);
		Process grep = new ProcessBuilder("grep", "-acE", pattern, log.toString()).redirectErrorStream(true).start();
		assertTrue(grep.waitFor(15, TimeUnit.SECONDS), "grep timed out");
		return Integer.parseInt(new String(grep.getInputStream().readAllBytes(), StandardCharsets.UTF_8).strip());
	}

	/**
	 * Runs {@code body} with System.out/err captured into {@code out[0]}. ForbricLog has no log4j binding under
	 * test, so its INFO lines go to System.out and its WARN/ERROR lines to System.err; both are kept.
	 */
	public static <T> T capture(Callable<T> body, String[] out) throws Exception {
		PrintStream originalOut = System.out, originalErr = System.err;
		ByteArrayOutputStream buffer = new ByteArrayOutputStream();
		PrintStream sink = new PrintStream(buffer, true, StandardCharsets.UTF_8);
		System.setOut(sink);
		System.setErr(sink);
		try {
			return body.call();
		} finally {
			System.setOut(originalOut);
			System.setErr(originalErr);
			out[0] = buffer.toString(StandardCharsets.UTF_8);
		}
	}
}
