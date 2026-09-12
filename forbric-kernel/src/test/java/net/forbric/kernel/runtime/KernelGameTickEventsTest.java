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
 * The one thing about the tick bridge that nothing else can check: which NeoForge tick goes to which
 * MinecraftForge hook.
 *
 * <h2>Why this is a source-text test, which is otherwise a bad idea</h2>
 *
 * <p>Measured, not assumed. Swapping {@code onPreServerTick} and {@code onPostServerTick} in
 * {@code KernelGameTickEvents}:
 *
 * <ul>
 *   <li>compiles — the two hooks share a descriptor,
 *       {@code (Ljava/util/function/BooleanSupplier;Lnet/minecraft/server/MinecraftServer;)V};
 *   <li>links and runs — both are public statics on the same class;
 *   <li>passes gate-m4 completely green, including both "bridged 20 ServerTickEvent.Pre/Post to MinecraftForge"
 *       assertions, because the kind in that log line comes from a different argument than the hook does.
 * </ul>
 *
 * <p>So the compiler, the runtime and the gates all agree with a wrong answer. Every Forge-family mod that pairs
 * work across the tick — a timer, a begin/flush batch, a snapshot-then-compare — would have its halves reversed,
 * and nothing anywhere would say so.
 *
 * <p>That leaves reading the source. The game-side source set is not on the test classpath (it compiles only
 * where the staged jars are, see build.gradle's compileRuntimeJava guard), so the compiled class is not
 * available here on every machine — but the FILE always is. The assertion is deliberately about pairing only,
 * not formatting: it asks which hook name appears inside which method, nothing else.
 *
 * <p>The correct pairing is not a convention, it is what the bytecode does: {@code javap -c} on
 * {@code ForgeEventFactory} shows {@code onPreServerTick} posting {@code TickEvent$ServerTickEvent$Pre} and
 * {@code onPostServerTick} posting {@code $Post}.
 */
class KernelGameTickEventsTest {
	private static final Path SOURCE =
			Path.of("src/runtime/java/net/forbric/kernel/runtime/KernelGameTickEvents.java");

	private static String bodyOf(String method) throws Exception {
		String source = Files.readString(SOURCE, StandardCharsets.UTF_8);
		int start = source.indexOf("public static void " + method + "(Object neoBus)");
		assertTrue(start >= 0, method + "(Object) is gone from " + SOURCE
				+ " — the boot side resolves it by that exact name at runtime, so its absence is not a compile "
				+ "error anywhere; it is a NoSuchMethodException once the server is already ticking");
		int end = source.indexOf("\n\t}", start);
		assertTrue(end > start, "could not find the end of " + method);
		return source.substring(start, end);
	}

	@Test
	void preGoesToThePreHookAndOnlyThePreHook() throws Exception {
		String body = bodyOf("installPre");

		assertTrue(body.contains("ServerTickEvent.Pre.class"), "installPre must subscribe to the Pre event");
		assertTrue(body.contains("onPreServerTick"), "installPre must forward to MinecraftForge's PRE hook");
		assertTrue(!body.contains("onPostServerTick"),
				"installPre forwards NeoForge's Pre tick to MinecraftForge's POST hook. Both families still tick "
						+ "and every assertion still passes; what breaks is order — Forge-family work scheduled "
						+ "before the tick runs after it, and vice versa");
	}

	@Test
	void postGoesToThePostHookAndOnlyThePostHook() throws Exception {
		String body = bodyOf("installPost");

		assertTrue(body.contains("ServerTickEvent.Post.class"), "installPost must subscribe to the Post event");
		assertTrue(body.contains("onPostServerTick"), "installPost must forward to MinecraftForge's POST hook");
		assertTrue(!body.contains("onPreServerTick"), "installPost forwards to MinecraftForge's PRE hook");
	}

	/**
	 * IEventBus declares eight addListener overloads and the short ones are not shorthand: {@code javap -c} shows
	 * {@code addListener(Class, Consumer)} opening with {@code getstatic EventPriority.NORMAL; iconst_0}. Reaching
	 * for the tidier call silently promotes the forward from LOWEST to NORMAL, so it would run before NeoForge
	 * mods that expect to see the event first — with nothing to show for it in any log.
	 */
	@Test
	void theForwardStaysAtLowestPriority() throws Exception {
		String source = Files.readString(SOURCE, StandardCharsets.UTF_8);

		assertTrue(source.contains("addListener(EventPriority.LOWEST, false, event, listener)"),
				"the four-argument addListener overload with EventPriority.LOWEST is the only correct call here; "
						+ "a shorter overload compiles and silently registers at NORMAL");
	}

	/**
	 * The time check must stay a supplier evaluated per call, and must answer true when it cannot answer.
	 *
	 * <p>Both halves look redundant in typed code, which is exactly why they are pinned. A snapshot would hand
	 * every deferred MinecraftForge task the answer from the top of the tick; a fail-closed catch would stop that
	 * work with no exception and no log, because the catch is deliberately silent.
	 */
	@Test
	void theTimeCheckStaysLazyAndFailsOpen() throws Exception {
		String source = Files.readString(SOURCE, StandardCharsets.UTF_8);

		assertTrue(source.contains("BooleanSupplier haveTime = () -> {"),
				"haveTime must stay a supplier: a snapshot answers for the whole tick");
		assertTrue(source.contains("return neoEvent.hasTime();"), "the supplier must ask the event each time");
		assertTrue(source.contains("return true;"),
				"the supplier must fail OPEN — returning false on an unexpected failure silently stops another "
						+ "family's deferred work");
	}
}
