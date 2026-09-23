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

package net.forbric.kernel.boot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * gate-m9 asserts no load-report row says the same thing twice, and that check has to be able to see one.
 *
 * <p>A pass on a green report proves nothing: a step that looks at the wrong file, or splits on the wrong thing,
 * passes on every report there is. So the gate's own block is executed here against a report that DOES repeat a
 * reason, and it has to fail.
 *
 * <p>The gate's block is run, not a copy of it.
 */
class LoadReportDedupContractTest {
	@TempDir Path temporary;

	private static final String REPEATED = "    partly did not run — tooltip provider ordering is decided by "
			+ "NeoForge's ItemTooltipHandler on this base; ItemComponentTooltipProviderRegistry entries are "
			+ "recorded but not applied; tooltip provider ordering is decided by NeoForge's ItemTooltipHandler on "
			+ "this base; ItemComponentTooltipProviderRegistry entries are recorded but not applied";

	@Test
	void aReportThatRepeatsAReasonIsRed() throws Exception {
		Path report = write("Forbric load report\n\n  Item API  (itemapi)\n" + REPEATED + "\n");
		Process process = start(report);
		// Read once: the stream is consumed, and asking twice would hand the second caller an empty string.
		String output = output(process);
		assertNotEquals(0, process.waitFor(), output);
		assertTrue(output.contains("repeats a reason"), output);
	}

	@Test
	void aReportWithTwoDistinctReasonsOnOneRowIsGreen() throws Exception {
		Path report = write("Forbric load report\n\n  Item API  (itemapi)\n"
				+ "    partly did not run — its mixin A did not apply; its deferred task threw\n");
		Process process = start(report);
		assertEquals(0, process.waitFor(), "two things wrong is two reasons, not a repeat: " + output(process));
	}

	/** A missing report is red, not green — "nothing to read" must not pass as "nothing repeated". */
	@Test
	void aMissingReportIsRed() throws Exception {
		Process process = start(temporary.resolve("absent.txt"));
		assertNotEquals(0, process.waitFor(), output(process));
	}

	@Test void anExplicitlyHealthyRuntimeInventoryExplainsTheAbsentFailureReport() throws Exception {
		Path facts = temporary.resolve("compatibility-report.json");
		Files.writeString(facts, "{\"schemaVersion\":1,\"confirmedRequired\":0,\"mods\":[{\"status\":\"OK\"}],\"catalogFailures\":[]}");
		Process healthy = start(temporary.resolve("load-report.txt"));
		assertEquals(0, healthy.waitFor(), output(healthy));
		Files.writeString(facts, Files.readString(facts).replace("OK", "DEGRADED"));
		Process missingFailure = start(temporary.resolve("load-report.txt"));
		assertNotEquals(0, missingFailure.waitFor(), output(missingFailure));
		Files.writeString(facts, "{\"schemaVersion\":1,\"confirmedRequired\":0,\"mods\":[],\"catalogFailures\":[]}");
		Process empty = start(temporary.resolve("load-report.txt"));
		assertNotEquals(0, empty.waitFor(), "an empty denominator must not explain a missing report: " + output(empty));
	}

	private Path write(String content) throws Exception {
		Path report = temporary.resolve("load-report.txt");
		Files.writeString(report, content, StandardCharsets.UTF_8);
		return report;
	}

	private static String output(Process process) throws Exception {
		return new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
	}

	private static Process start(Path report) throws Exception {
		String script = Files.readString(Path.of("run/gate-m9-client.sh"));
		int begin = script.indexOf("# M9_LOAD_REPORT_DEDUP_BEGIN");
		int end = script.indexOf("# M9_LOAD_REPORT_DEDUP_END", begin);
		assertTrue(begin >= 0 && end > begin, "missing executable load-report dedup contract in gate-m9");
		String body = script.substring(begin, end)
				.replace("$RUNDIR/.forbric-kernel/load-report.txt", report.toString())
				.replace("[ $? -eq 0 ] || FAIL=1", "");
		ProcessBuilder builder = new ProcessBuilder("bash", "-c", "set -e\n" + body);
		builder.redirectErrorStream(true);
		return builder.start();
	}
}
