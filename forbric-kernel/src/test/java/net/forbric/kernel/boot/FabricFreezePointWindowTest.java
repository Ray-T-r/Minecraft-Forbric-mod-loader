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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.LookupSwitchInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TableSwitchInsnNode;
import org.objectweb.asm.tree.TryCatchBlockNode;

import net.fabricmc.api.EnvType;
import net.forbric.api.Side;
import net.forbric.kernel.ExpectCaught;
import net.forbric.kernel.transform.ExecutesInjector;
import net.forbric.kernel.transform.FabricFreezePointInjector;
import net.forbric.kernel.transform.InjectorExecution;

/**
 * WHEN the kernel reaches Fabric's registry freeze point, and what calling it does (issue #52).
 *
 * <p>On Fabric with fabric-api, fabric-registry-sync moves {@code BuiltInRegistries.freeze()} out of {@code Bootstrap}
 * to after every {@code main} entrypoint on a server, and every {@code main} and {@code client} one on a client. The
 * kernel keeps that freeze in {@code Bootstrap}, so the Fabric injectors on it are moved onto two hooks instead, and
 * {@code KernelLifecycle.fabricFreezePoint} is the call that stands in for the moved freeze. Create Fly's TAIL injector
 * creates its registries in the root: run in {@code Bootstrap}, before {@code Create.onInitialize}, it died on
 * "Registry is already frozen (minecraft:root / create:arm_interaction_point_type)" and the game never started.
 *
 * <p>The first half pins the call graph, as {@link FabricMainEntrypointWindowTest} does: there is no game here to boot.
 * javac copies a finally block once per way out of its try, and a hook that is in the right place in one copy and the
 * wrong place in another is wrong on exactly the path nobody runs by hand — so these walk every path out of the method
 * rather than reading the instructions top to bottom. The second half calls {@code fabricFreezePoint} against a
 * stand-in {@code BuiltInRegistries}.
 */
@ResourceLock("system-properties")
@ExecutesInjector(FabricFreezePointInjector.class)
class FabricFreezePointWindowTest {
	private static final String LIFECYCLE = "net/forbric/kernel/boot/KernelLifecycle";
	private static final String SIDE = Type.getInternalName(Side.class);
	private static final String HEAD_HOOK = FabricFreezePointInjector.HEAD_HOOK;
	private static final String TAIL_HOOK = FabricFreezePointInjector.TAIL_HOOK;

	// The steps a walk records, in the words the failure messages print.
	private static final String MAINS = "main entrypoints";
	private static final String CLIENTS = "client entrypoints";
	private static final String HEAD = "HEAD";
	private static final String TAIL = "TAIL";
	private static final String OPEN_ROOT = "open root";
	private static final String CLOSE_ROOT = "close root";
	private static final String CLOSE_WINDOW = "close window";
	private static final String RETURN = "return";
	private static final String THROW = "throw";

	private static final String FROZEN = "Registry is already frozen (minecraft:root / create:arm_interaction_point_type)";

	@BeforeEach
	@AfterEach
	void reset() {
		KernelLifecycle.forgetFabricFreezePoint();
		System.clearProperty(FabricFreezePointInjector.PROPERTY);
	}

	/**
	 * A client freezes where Fabric's {@code client.MinecraftMixin.afterModInit} does: after main, then client, then
	 * the HEAD half while the reopened window is still open, the freeze that closes it, and the TAIL half.
	 */
	@Test
	void theClientReachesTheFreezePointAfterMainAndClientAroundTheWindowsFreeze() throws Exception {
		MethodNode method = method("onClientEntrypoints");

		Walk run = walk(method, method.instructions.getFirst(), Set.of(MAINS, CLIENTS, HEAD, CLOSE_WINDOW, TAIL), null, false);

		assertEquals(Set.of(
				List.of(MAINS, CLIENTS, HEAD, CLOSE_WINDOW, TAIL, RETURN),
				// The window was never reopened (unfreeze threw): nothing to close, both halves still run.
				List.of(MAINS, CLIENTS, HEAD, TAIL, RETURN)), run.exits(),
				"onClientEntrypoints, run through: the HEAD half after the client entrypoints and before the freeze "
						+ "that closes their window, the TAIL half after it");
	}

	/**
	 * Every copy of the finally, including the one a throw takes. The client entrypoints are where a Fabric mod's
	 * failure surfaces; one that skipped the freeze point would leave Create's registries uncreated for the rest of
	 * the run, and that path is the copy nobody reads.
	 */
	@Test
	void everyWayOutOfTheClientEntrypointsReachesBothHalvesInOrder() throws Exception {
		MethodNode method = method("onClientEntrypoints");
		AbstractInsnNode clients = onlyCall(method, "runClientEntrypoints");

		Walk out = walk(method, clients, Set.of(HEAD, CLOSE_WINDOW, TAIL), null, true);

		assertEquals(Set.of(
				List.of(HEAD, CLOSE_WINDOW, TAIL, RETURN),
				List.of(HEAD, TAIL, RETURN),
				List.of(HEAD, CLOSE_WINDOW, TAIL, THROW),
				List.of(HEAD, TAIL, THROW)), out.exits(),
				"every path from runClientEntrypoints to the end of onClientEntrypoints, a throwing one included");
		List<AbstractInsnNode> sites = calls(method, "fabricFreezePoint");
		assertTrue(sites.size() >= 4, "the freeze point is called from a finally, which javac copies: " + sites.size());
		assertTrue(out.visited().containsAll(sites), "a copy of the finally was never walked, so never checked");
	}

	/**
	 * A server freezes where Fabric's {@code MainMixin.afterModInit} does: after every main, inside the span the root
	 * registry is open for them — Create Fly creates its registries there when fabric-api is absent — and the TAIL
	 * half only once the registration window is frozen again.
	 */
	@Test
	void theServerReachesHeadAfterItsMainsWithTheRootOpenAndTailAfterTheWindowFreezes() throws Exception {
		MethodNode method = method("registerNeoForgeContent");

		Walk run = walk(method, method.instructions.getFirst(),
				Set.of(OPEN_ROOT, MAINS, HEAD, CLOSE_ROOT, CLOSE_WINDOW, TAIL), false, false);

		assertEquals(Set.of(
				List.of(OPEN_ROOT, MAINS, HEAD, CLOSE_ROOT, CLOSE_WINDOW, TAIL, RETURN),
				// The `if (closeWindow)` the bytecode cannot see is always true here.
				List.of(OPEN_ROOT, MAINS, HEAD, CLOSE_ROOT, TAIL, RETURN)), run.exits(),
				"registerNeoForgeContent on a dedicated server, run through");
	}

	@Test
	void everyWayOutOfTheServerMainsReachesBothHalvesInOrder() throws Exception {
		MethodNode method = method("registerNeoForgeContent");
		AbstractInsnNode mains = onlyCall(method, "runMainEntrypoints");

		Walk out = walk(method, mains, Set.of(HEAD, CLOSE_ROOT, CLOSE_WINDOW, TAIL), false, true);

		assertEquals(Set.of(
				List.of(HEAD, CLOSE_ROOT, CLOSE_WINDOW, TAIL, RETURN),
				List.of(HEAD, CLOSE_ROOT, TAIL, RETURN),
				List.of(HEAD, CLOSE_ROOT, CLOSE_WINDOW, TAIL, THROW),
				List.of(HEAD, CLOSE_ROOT, TAIL, THROW)), out.exits(),
				"every path from runMainEntrypoints to the end of registerNeoForgeContent on a dedicated server, a "
						+ "throwing one included");
		assertTrue(out.visited().containsAll(calls(method, "fabricFreezePoint")),
				"a copy of a finally was never walked, so never checked");
	}

	/**
	 * Both calls in the registration window ask {@code side.isClient()} first, and on a client neither is reachable,
	 * not even on a throw. Each hook runs once per process, so a HEAD half spent here, before {@code Minecraft}
	 * exists, would be a no-op in {@code onClientEntrypoints} — the client's own freeze point would run only the TAIL
	 * half, and before the client entrypoints Fabric runs first.
	 */
	@Test
	void aClientNeverReachesTheFreezePointFromTheRegistrationWindow() throws Exception {
		MethodNode method = method("registerNeoForgeContent");
		boolean asks = calls(method, "isClient").stream().anyMatch(c -> SIDE.equals(((MethodInsnNode) c).owner));
		assertTrue(asks, "registerNeoForgeContent no longer asks side.isClient()");

		Walk client = walk(method, method.instructions.getFirst(), Set.of(HEAD, TAIL), true, true);

		assertEquals(Set.of(List.of(RETURN), List.of(THROW)), client.exits(),
				"on a client registerNeoForgeContent must reach neither half of the freeze point");
		List<AbstractInsnNode> sites = calls(method, "fabricFreezePoint");
		assertFalse(sites.isEmpty(), "registerNeoForgeContent no longer calls the freeze point at all");
		assertFalse(sites.stream().anyMatch(client.visited()::contains), "a client reaches a fabricFreezePoint call");
	}

	@Test
	void eachHookRunsOncePerProcess(@TempDir Path dir) throws Throwable {
		ClassLoader game = standIn(dir, COUNTING);

		KernelLifecycle.fabricFreezePoint(game, HEAD_HOOK);
		KernelLifecycle.fabricFreezePoint(game, HEAD_HOOK);
		assertEquals(1, count(game, "heads"), "the HEAD half ran again: every moved handler would register twice");
		assertEquals(0, count(game, "tails"), "the HEAD half spent the TAIL half's call");

		KernelLifecycle.fabricFreezePoint(game, TAIL_HOOK);
		KernelLifecycle.fabricFreezePoint(game, TAIL_HOOK);
		assertEquals(1, count(game, "tails"));

		// Per process, not per class: the server window and the client one are both in the one JVM.
		ClassLoader another = standIn(dir, COUNTING);
		KernelLifecycle.fabricFreezePoint(another, HEAD_HOOK);
		KernelLifecycle.fabricFreezePoint(another, TAIL_HOOK);
		assertEquals(0, count(another, "heads") + count(another, "tails"));
	}

	/**
	 * Fabric never runs the TAIL half without the HEAD half. A server whose window failed before its mains reaches only
	 * the TAIL call in the outer finally; running it there would make Create's TAIL injector fail a second time,
	 * against registries nobody created, and bury the first failure under it.
	 */
	@Test
	void theTailHalfWaitsForTheHeadHalf(@TempDir Path dir) throws Throwable {
		ClassLoader game = standIn(dir, COUNTING);

		KernelLifecycle.fabricFreezePoint(game, TAIL_HOOK);
		assertEquals(0, count(game, "tails"), "the TAIL half ran without the HEAD half");

		KernelLifecycle.fabricFreezePoint(game, HEAD_HOOK);
		KernelLifecycle.fabricFreezePoint(game, TAIL_HOOK);
		assertEquals(1, count(game, "heads"));
		assertEquals(1, count(game, "tails"), "a TAIL call skipped before HEAD spent the real one");
	}

	/**
	 * Create Fly's own failure, thrown from the TAIL half: reported with the mod's exception — not reflection's
	 * wrapper — and not rethrown, the way a failing entrypoint in the same window is. Nor is it tried again: the
	 * handlers before the one that threw have already run, and a second call would run them twice.
	 */
	@ExpectCaught("Registry is already frozen")
	@Test
	void aHookThatThrowsIsReportedAndNotRethrown(@TempDir Path dir) throws Throwable {
		ClassLoader game = standIn(dir, THROWING);
		KernelLifecycle.fabricFreezePoint(game, HEAD_HOOK);

		String log = capture(() -> KernelLifecycle.fabricFreezePoint(game, TAIL_HOOK));
		assertTrue(log.contains(TAIL_HOOK) && log.contains(FROZEN), "the failure was not reported with its cause:\n" + log);
		assertFalse(log.contains("InvocationTargetException"), "reported reflection's wrapper, not the mod's exception:\n" + log);

		KernelLifecycle.fabricFreezePoint(game, TAIL_HOOK);
		assertEquals(1, count(game, "heads"));
		assertEquals(1, count(game, "tails"), "a failed TAIL half was run again");
	}

	/**
	 * A {@code BuiltInRegistries} without the hooks (the injector switched off, so nothing was moved onto them), or no
	 * {@code BuiltInRegistries} at all: nothing waits for the freeze point, so there is nothing to call or warn about.
	 */
	@Test
	void aGameWithoutTheHooksIsLeftAlone(@TempDir Path dir) throws Exception {
		ClassLoader bare = standIn(dir, BARE);
		try (URLClassLoader none = new URLClassLoader(new URL[0], null)) {
			String log = capture(() -> {
				KernelLifecycle.fabricFreezePoint(bare, HEAD_HOOK);
				KernelLifecycle.fabricFreezePoint(bare, TAIL_HOOK);
				KernelLifecycle.forgetFabricFreezePoint();
				KernelLifecycle.fabricFreezePoint(none, HEAD_HOOK);
				KernelLifecycle.fabricFreezePoint(none, TAIL_HOOK);
			});
			assertFalse(log.contains("WARN"), "warned about a game with nothing waiting for the freeze point:\n" + log);
		}
	}

	@Test
	void switchedOffNothingIsCalled(@TempDir Path dir) throws Throwable {
		ClassLoader game = standIn(dir, COUNTING);

		System.setProperty(FabricFreezePointInjector.PROPERTY, "off");
		KernelLifecycle.fabricFreezePoint(game, HEAD_HOOK);
		KernelLifecycle.fabricFreezePoint(game, TAIL_HOOK);
		assertEquals(0, count(game, "heads") + count(game, "tails"),
				"switched off, the moved injectors stay on the bootstrap freeze and the kernel calls nothing");

		// The same stand-in, switched back on, is reached: the zero above is the switch, not a dead loader.
		System.clearProperty(FabricFreezePointInjector.PROPERTY);
		KernelLifecycle.fabricFreezePoint(game, HEAD_HOOK);
		assertEquals(1, count(game, "heads"));
	}

	/**
	 * The pairing the whole fix hangs on: the hooks {@link FabricFreezePointInjector} adds are the ones
	 * {@code fabricFreezePoint} looks up. A name, descriptor or access flag that drifts apart on one side lands the
	 * kernel in its "no hook" branch, which is a debug line — and every moved handler would silently never run.
	 * Mixin's part, merging the handler into the hook, is played here by one call inserted where it puts it.
	 */
	@Test
	void aHandlerMovedOntoTheInjectorsHookRunsAtTheFreezePoint(@TempDir Path dir) throws Throwable {
		Map<String, byte[]> compiled = InjectorExecution.compile(dir, Map.of(FabricFreezePointInjector.TARGET, HOOKLESS));
		String internal = FabricFreezePointInjector.TARGET.replace('.', '/');
		byte[] original = compiled.get(internal);

		byte[] hooked = InjectorExecution.transform(new FabricFreezePointInjector(), FabricFreezePointInjector.TARGET,
				original, EnvType.SERVER);
		assertNotSame(original, hooked, "FabricFreezePointInjector left BuiltInRegistries without the hooks");
		byte[] merged = mergeHandler(hooked, internal);
		assertEquals("", InjectorExecution.verify(merged, null));
		ClassLoader game = InjectorExecution.load(Map.of(internal, merged));

		KernelLifecycle.fabricFreezePoint(game, HEAD_HOOK);
		assertEquals(1, count(game, "heads"), "the handler on the injector's HEAD hook never ran");
		KernelLifecycle.fabricFreezePoint(game, TAIL_HOOK);
		assertEquals(1, count(game, "tails"), "the handler on the injector's TAIL hook never ran");
	}

	// ---- the stand-ins -------------------------------------------------------------------------------------------

	/** Counts each hook's calls. */
	private static final String COUNTING = """
			package net.minecraft.core.registries;

			public final class BuiltInRegistries {
				public static int heads;
				public static int tails;

				public static void %s() {
					heads++;
				}

				public static void %s() {
					tails++;
				}
			}
			""".formatted(HEAD_HOOK, TAIL_HOOK);

	/** Create Fly's TAIL injector, as it ran in Bootstrap: counts, then throws what the frozen root threw at it. */
	private static final String THROWING = """
			package net.minecraft.core.registries;

			public final class BuiltInRegistries {
				public static int heads;
				public static int tails;

				public static void %s() {
					heads++;
				}

				public static void %s() {
					tails++;
					throw new IllegalStateException("%s");
				}
			}
			""".formatted(HEAD_HOOK, TAIL_HOOK, FROZEN);

	/** The class with neither hook: the injector switched off, or a game it never reached. */
	private static final String BARE = """
			package net.minecraft.core.registries;

			public final class BuiltInRegistries {
			}
			""";

	/** Before the injector: what the handlers Mixin moves onto the hooks do, and no hooks. */
	private static final String HOOKLESS = """
			package net.minecraft.core.registries;

			public final class BuiltInRegistries {
				public static int heads;
				public static int tails;

				static void movedHead() {
					heads++;
				}

				static void movedTail() {
					tails++;
				}
			}
			""";

	private static ClassLoader standIn(Path dir, String source) throws Exception {
		return InjectorExecution.load(InjectorExecution.compile(dir, Map.of(FabricFreezePointInjector.TARGET, source)));
	}

	private static int count(ClassLoader game, String field) throws Exception {
		return (Integer) InjectorExecution.getStatic(Class.forName(FabricFreezePointInjector.TARGET, false, game), field);
	}

	/** Puts a call to {@code movedHead}/{@code movedTail} at the start of each hook, where Mixin puts a HEAD handler. */
	private static byte[] mergeHandler(byte[] hooked, String internal) {
		ClassNode node = new ClassNode();
		new ClassReader(hooked).accept(node, 0);
		Map<String, String> handlers = Map.of(HEAD_HOOK, "movedHead", TAIL_HOOK, "movedTail");
		int merged = 0;
		for (MethodNode method : node.methods) {
			String handler = handlers.get(method.name);
			if (handler == null || !FabricFreezePointInjector.HOOK_DESC.equals(method.desc)) continue;
			method.instructions.insert(new MethodInsnNode(Opcodes.INVOKESTATIC, internal, handler, "()V", false));
			merged++;
		}
		assertEquals(2, merged, "the injector's output does not carry both hooks as ()V methods");
		ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
		node.accept(writer);
		return writer.toByteArray();
	}

	/**
	 * ForbricLog has no log4j binding under test and splits by level, {@code info} to {@code System.out} and
	 * {@code warn} to {@code System.err}; both are captured so a warning cannot go unseen.
	 */
	private static String capture(Runnable body) {
		PrintStream originalOut = System.out;
		PrintStream originalErr = System.err;
		ByteArrayOutputStream buffer = new ByteArrayOutputStream();
		PrintStream sink = new PrintStream(buffer, true, StandardCharsets.UTF_8);
		System.setOut(sink);
		System.setErr(sink);
		try {
			body.run();
		} finally {
			System.setOut(originalOut);
			System.setErr(originalErr);
		}
		return buffer.toString(StandardCharsets.UTF_8);
	}

	// ---- the walk ------------------------------------------------------------------------------------------------

	/** How a walk left the method — each way's steps, then {@link #RETURN} or {@link #THROW} — and what it touched. */
	private record Walk(Set<List<String>> exits, Set<AbstractInsnNode> visited) {
	}

	/**
	 * Every way out of {@code method} from {@code from}, as the {@code tracked} steps it takes in order.
	 *
	 * <p>With {@code exceptions}, a call or a rethrow also goes to the first handler covering it — the first, as the
	 * JVM picks: the exception table alone would also let an inner finally's throw skip straight to the outer one,
	 * which no execution does. Two calls are taken not to throw: {@code side.isClient()}, on an enum constant, and
	 * {@code fabricFreezePoint} itself, which catches everything (pinned by the tests above). A throw nothing catches
	 * leaves the method; one a catch-all handler takes does not.
	 *
	 * <p>A branch straight on {@code side.isClient()} goes the one way {@code isClient} says, or both when it is null.
	 */
	private static Walk walk(MethodNode method, AbstractInsnNode from, Set<String> tracked, Boolean isClient,
			boolean exceptions) {
		InsnList insns = method.instructions;
		Set<List<String>> exits = new LinkedHashSet<>();
		Set<AbstractInsnNode> visited = new HashSet<>();
		Set<String> seen = new HashSet<>();
		Deque<Object[]> todo = new ArrayDeque<>();
		todo.push(new Object[] { from, List.of() });
		while (!todo.isEmpty()) {
			Object[] next = todo.pop();
			AbstractInsnNode insn = (AbstractInsnNode) next[0];
			@SuppressWarnings("unchecked")
			List<String> steps = (List<String>) next[1];
			if (insn == null || !seen.add(insns.indexOf(insn) + " " + steps)) continue;
			visited.add(insn);

			String step = step(insn);
			if (step != null && (tracked.contains(step) || step.endsWith("(?)"))) steps = plus(steps, step);
			if (steps.size() > 16) {
				exits.add(plus(steps, "a loop through a tracked step"));
				continue;
			}

			boolean caught = false;
			if (exceptions && mayThrow(insn)) {
				int at = insns.indexOf(insn);
				for (TryCatchBlockNode block : method.tryCatchBlocks) {
					if (insns.indexOf(block.start) > at || at >= insns.indexOf(block.end)) continue;
					todo.push(new Object[] { block.handler, steps });
					if (block.type == null || "java/lang/Throwable".equals(block.type)) {
						caught = true;
						break;
					}
				}
			}

			int op = insn.getOpcode();
			if (op >= Opcodes.IRETURN && op <= Opcodes.RETURN) {
				exits.add(plus(steps, RETURN));
			} else if (op == Opcodes.ATHROW) {
				if (!caught) exits.add(plus(steps, THROW));
			} else if (insn instanceof JumpInsnNode jump) {
				Boolean taken = guard(jump, isClient);
				if (taken == null || taken) todo.push(new Object[] { jump.label, steps });
				if (op != Opcodes.GOTO && (taken == null || !taken)) todo.push(new Object[] { insn.getNext(), steps });
			} else if (insn instanceof TableSwitchInsnNode table) {
				todo.push(new Object[] { table.dflt, steps });
				for (LabelNode label : table.labels) todo.push(new Object[] { label, steps });
			} else if (insn instanceof LookupSwitchInsnNode lookup) {
				todo.push(new Object[] { lookup.dflt, steps });
				for (LabelNode label : lookup.labels) todo.push(new Object[] { label, steps });
			} else {
				todo.push(new Object[] { insn.getNext(), steps });
			}
		}
		return new Walk(exits, visited);
	}

	/** What one instruction means to these walks, or null; "(?)" for a call whose argument the walk cannot read. */
	private static String step(AbstractInsnNode insn) {
		if (!(insn instanceof MethodInsnNode call)) return null;
		AbstractInsnNode argument = previous(call);
		if (call.owner.endsWith("/KernelFabricEcosystem")) {
			return switch (call.name) {
				case "runMainEntrypoints" -> MAINS;
				case "runClientEntrypoints" -> CLIENTS;
				default -> null;
			};
		}
		if (!LIFECYCLE.equals(call.owner)) return null;
		return switch (call.name) {
			case "fabricFreezePoint" -> {
				Object hook = argument instanceof LdcInsnNode ldc ? ldc.cst : null;
				yield HEAD_HOOK.equals(hook) ? HEAD : TAIL_HOOK.equals(hook) ? TAIL : "fabricFreezePoint(?)";
			}
			case "rootRegistry" -> argument != null && argument.getOpcode() == Opcodes.ICONST_1 ? OPEN_ROOT
					: argument != null && argument.getOpcode() == Opcodes.ICONST_0 ? CLOSE_ROOT : "rootRegistry(?)";
			case "closeClientEntrypointWindow", "closeRegistrationWindow" -> CLOSE_WINDOW;
			default -> null;
		};
	}

	private static boolean mayThrow(AbstractInsnNode insn) {
		if (insn.getOpcode() == Opcodes.ATHROW || insn instanceof InvokeDynamicInsnNode) return true;
		if (!(insn instanceof MethodInsnNode call)) return false;
		if (SIDE.equals(call.owner) && "isClient".equals(call.name)) return false;
		return !(LIFECYCLE.equals(call.owner) && "fabricFreezePoint".equals(call.name));
	}

	/** Whether a branch straight on {@code side.isClient()} is taken; null when it is not one, or either way goes. */
	private static Boolean guard(JumpInsnNode jump, Boolean isClient) {
		if (isClient == null || (jump.getOpcode() != Opcodes.IFEQ && jump.getOpcode() != Opcodes.IFNE)) return null;
		if (!(previous(jump) instanceof MethodInsnNode call) || !SIDE.equals(call.owner) || !"isClient".equals(call.name)) {
			return null;
		}
		return jump.getOpcode() == Opcodes.IFNE ? isClient : !isClient;
	}

	private static AbstractInsnNode previous(AbstractInsnNode insn) {
		AbstractInsnNode p = insn.getPrevious();
		while (p != null && p.getOpcode() < 0) p = p.getPrevious();
		return p;
	}

	private static List<String> plus(List<String> steps, String step) {
		List<String> out = new ArrayList<>(steps);
		out.add(step);
		return List.copyOf(out);
	}

	private static List<AbstractInsnNode> calls(MethodNode method, String name) {
		List<AbstractInsnNode> out = new ArrayList<>();
		for (AbstractInsnNode insn : method.instructions.toArray()) {
			if (insn instanceof MethodInsnNode call && name.equals(call.name)) out.add(insn);
		}
		return out;
	}

	private static AbstractInsnNode onlyCall(MethodNode method, String name) {
		List<AbstractInsnNode> calls = calls(method, name);
		assertEquals(1, calls.size(), method.name + " calls " + name + " " + calls.size() + " times; this test walks from one");
		return calls.get(0);
	}

	private static MethodNode method(String name) throws Exception {
		Path compiled = Path.of(System.getProperty("user.dir"), "build", "classes", "java", "main",
				"net", "forbric", "kernel", "boot", "KernelLifecycle.class");
		assertTrue(Files.isRegularFile(compiled),
				"KernelLifecycle not found in the compiled src/main classes, which exist before any test runs");

		ClassNode node = new ClassNode();
		new ClassReader(Files.readAllBytes(compiled)).accept(node, 0);
		return node.methods.stream().filter(m -> name.equals(m.name)).findFirst()
				.orElseThrow(() -> new AssertionError("KernelLifecycle." + name + " is gone"));
	}
}
