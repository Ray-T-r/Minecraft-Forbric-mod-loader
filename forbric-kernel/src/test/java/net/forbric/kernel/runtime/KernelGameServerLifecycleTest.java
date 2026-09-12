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

package net.forbric.kernel.runtime;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;

/**
 * The ordering that decides whether a singleplayer world can be entered at all.
 *
 * <p>MinecraftForge's {@code handleServerStarted} opens its login gate as its LAST action. {@code javap -c}:
 *
 * <pre>
 *    0..11  ServerStartedEvent.BUS.post(new ServerStartedEvent(server))
 *   17..21  allowLogins.set(true)
 *      24   return
 * </pre>
 *
 * <p>with no exception table. One Forge-family mod whose {@code ServerStartedEvent} listener throws leaves
 * offset 17 unreached, and every connection is then refused with "Server is still starting" — including the
 * local client's to its own integrated server, so the world simply cannot be entered.
 *
 * <p>So the bridge forces the gate open BEFORE calling the hook. That is one statement in one order, protected
 * by nothing: rearranging it compiles, runs, and passes every gate, because no gate stages a Forge mod whose
 * started-listener throws. {@code grep -rl allowLogins run/} finds nothing.
 *
 * <p>Source text rather than bytecode for the reason given in {@link KernelGameTickEventsTest}: the game-side
 * source set compiles only where the staged jars are, so the class is not on this classpath everywhere — the
 * file is.
 */
class KernelGameServerLifecycleTest {
	private static final Path SOURCE =
			Path.of("src/runtime/java/net/forbric/kernel/runtime/KernelGameServerLifecycle.java");

	private static String source() throws Exception {
		return Files.readString(SOURCE, StandardCharsets.UTF_8);
	}

	@Test
	void theLoginGateIsForcedOpenBeforeTheHookIsCalled() throws Exception {
		String s = source();

		int force = s.indexOf("if (openLoginGate) forceAllowLogins();");
		int call = s.indexOf("forge.handle(server);");

		assertTrue(force >= 0, "the pre-open is gone — a throwing Forge started-listener now blocks every login");
		assertTrue(call >= 0, "the hook call is gone");
		assertTrue(force < call,
				"forceAllowLogins must run BEFORE the MinecraftForge hook. The hook opens the gate as its last "
						+ "action and has no exception table, so after it is not an order, it is a bet that no "
						+ "Forge mod's started-listener ever throws");
	}

	/** It must also sit outside the try, or the hook's own throw would skip it just the same. */
	@Test
	void thePreOpenIsNotInsideTheTryThatGuardsTheHook() throws Exception {
		String s = source();

		int force = s.indexOf("if (openLoginGate) forceAllowLogins();");
		int tryBlock = s.indexOf("\t\t\ttry {", force);
		int call = s.indexOf("forge.handle(server);");

		assertTrue(tryBlock > force && tryBlock < call,
				"the try that guards the hook must OPEN after the pre-open; moving the pre-open inside it would "
						+ "put it back on the path the hook's throw skips");
	}

	/** Only the started bridge pre-opens: handleServerStopping closes the gate FIRST, at offsets 0..4. */
	@Test
	void onlyTheStartedBridgePreOpensTheGate() throws Exception {
		String s = source();

		int started = s.indexOf("public static void installStarted(Object neoBus)");
		int stopping = s.indexOf("public static void installStopping(Object neoBus)");
		assertTrue(started >= 0 && stopping > started, "both entry points must exist, started first");

		String startedBody = s.substring(started, stopping);
		String stoppingBody = s.substring(stopping, s.indexOf("\n\t}", stopping));

		assertTrue(startedBody.contains("\"handleServerStarted\", true"),
				"the started bridge must pass openLoginGate = true");
		assertTrue(stoppingBody.contains("\"handleServerStopping\", false"),
				"the stopping bridge must pass openLoginGate = false — its hook closes the gate first, so forcing "
						+ "it open here would reopen a gate the server just shut");
	}
}
