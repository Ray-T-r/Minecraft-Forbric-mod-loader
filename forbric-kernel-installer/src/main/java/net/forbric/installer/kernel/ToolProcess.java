/*
 * Copyright 2026 The Forbric Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package net.forbric.installer.kernel;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Runs the tools the install spawns as child processes — today the link checker ({@link LinkCheckTool}) — with
 * their output kept for an error message.
 *
 * <p>A child process rather than code called in-process because those tools call {@code System.exit()} and carry
 * their own dependencies (ASM); a child {@code java} needs nothing the installer does not already have.
 */
final class ToolProcess {

	private final Consumer<String> log;
	private final String prefix;

	/** @param prefix the log tag a run is announced under, e.g. {@code "[link-check]"} */
	ToolProcess(Consumer<String> log, String prefix) {
		this.log = log;
		this.prefix = prefix;
	}

	/** Absolute path to the {@code java} launcher of the JVM the installer is running under. */
	static String javaBin() {
		String home = System.getProperty("java.home");
		boolean win = System.getProperty("os.name", "").toLowerCase().contains("win");
		return home + java.io.File.separator + "bin" + java.io.File.separator + (win ? "java.exe" : "java");
	}

	/**
	 * Runs {@code cmd}, collecting the last 40 output lines into {@code tail}, and RETURNS the exit code instead
	 * of throwing on it.
	 *
	 * <p>A tool reports its finding through its exit code — the link check exits 1 when the jars carry a dangling
	 * reference — so the caller reads it; nothing else should re-implement the streaming.
	 */
	int exec(List<String> cmd, String label, List<String> tail) throws IOException {
		log.accept(prefix + " " + label + " …");
		ProcessBuilder pb = new ProcessBuilder(cmd).redirectErrorStream(true);
		Process p = pb.start();
		try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
			String line;
			while ((line = r.readLine()) != null) {
				tail.add(line);
				if (tail.size() > 40) tail.remove(0);
			}
		}
		try {
			return p.waitFor();
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			p.destroyForcibly();
			throw new IOException("interrupted while running " + label, e);
		}
	}
}
