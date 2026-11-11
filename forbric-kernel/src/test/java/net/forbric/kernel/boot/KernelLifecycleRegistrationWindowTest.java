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

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TryCatchBlockNode;

/**
 * Two orderings the registration window depends on, both of which were wrong and neither of which fails loudly.
 *
 * <p>These are bytecode-shape tests: {@code registerNeoForgeContent} and {@code driveNativeRegistration} do
 * nothing off-game — every step resolves a carrier class reflectively — so what is assertable here is the
 * control flow, which is precisely what the defects were.
 */
class KernelLifecycleRegistrationWindowTest {
	/**
	 * Everything that closes the window must be reachable from a finally. Inline at the end of the try, anything
	 * that threw after {@code unfreeze} left every registry writable for the rest of the run and skipped the
	 * block-item link, the block-state bookkeeping and the {@code registriesLoaded} latch —
	 * which surface as an empty creative tab, a player kicked at spawn on the first block update, and mods
	 * refusing to register render layers.
	 */
	@Test
	void theRegistrationWindowIsClosedFromAFinally() throws Exception {
		ClassNode node = compiled();
		assertTrue(node != null,
				"KernelLifecycle not found in the compiled src/main classes, which exist before any test runs");
		MethodNode content = method(node, "registerNeoForgeContent");

		boolean hasFinally = content.tryCatchBlocks.stream().anyMatch(b -> b.type == null);
		assertTrue(hasFinally,
				"registerNeoForgeContent must have a finally covering the span it unfreezes the registries in");

		MethodNode close = method(node, "closeRegistrationWindow");
		List<String> steps = callsIn(close);
		for (String required : List.of("linkBlockItems", "freeze", "completeBlockStateBookkeeping")) {
			assertTrue(steps.contains(required),
					"closing the window must still " + required + " — each of these is a failure the kernel has "
							+ "already paid for once");
		}
		assertTrue(callsIn(content).contains("closeRegistrationWindow"),
				"registerNeoForgeContent must route its close through closeRegistrationWindow");
	}

	/**
	 * The window registerNeoForgeContent fires RegisterEvent in is the one {@code openRegistrationWindow} opens: NeoForge's
	 * vanilla snapshot first (its unfreeze callback empties the blockstate→id map, and the snapshot is what it is rebuilt
	 * from), then the unfreeze.
	 */
	@Test
	void theRegistrationWindowSnapshotsAndOpensBeforeRegisterEvent() throws Exception {
		ClassNode node = compiled();
		assertTrue(node != null,
				"KernelLifecycle not found in the compiled src/main classes, which exist before any test runs");
		MethodNode content = method(node, "registerNeoForgeContent");
		int open = firstCall(content, "openRegistrationWindow");
		int register = firstCall(content, "dispatchRegisterEvents");
		assertTrue(open >= 0, "registerNeoForgeContent must open its window through openRegistrationWindow");
		assertTrue(register > open, "the window must be open before RegisterEvent fires");
		MethodNode opening = method(node, "openRegistrationWindow");
		int snapshot = -1;
		for (AbstractInsnNode insn : opening.instructions.toArray()) {
			if (insn instanceof org.objectweb.asm.tree.LdcInsnNode ldc && "vanillaSnapshot".equals(ldc.cst)) {
				snapshot = opening.instructions.indexOf(insn);
				break;
			}
		}
		int unfreeze = firstCall(opening, "unfreeze");
		assertTrue(snapshot >= 0 && unfreeze > snapshot,
				"opening must take NeoForge's vanilla snapshot before it unfreezes");
	}

	/**
	 * The game buses must open before the setup lifecycle, where genuine NeoForge opens them.
	 *
	 * <p>{@code CommonModLoader.begin} starts {@code NeoForge.EVENT_BUS} right after its "Config loading" task and
	 * before {@code load()} posts common setup. The kernel started it last, after every setup phase — and
	 * {@code IEventBus.post} on a bus that has not started returns silently, so a mod posting its own API event
	 * during construct, RegisterEvent or common setup posted into nothing: no listener, no error, no log.
	 */
	@Test
	void theGameBusesOpenBeforeTheSetupLifecycle() throws Exception {
		ClassNode node = compiled();
		assertTrue(node != null,
				"KernelLifecycle not found in the compiled src/main classes, which exist before any test runs");
		MethodNode drive = method(node, "driveNativeRegistration");

		int configs = firstCall(drive, "loadEarlyConfigs");
		int buses = firstCall(drive, "startGameBuses");
		int setup = firstCall(drive, "fireModSetupLifecycle");
		assertTrue(configs >= 0 && buses >= 0 && setup >= 0, "driveNativeRegistration lost one of its steps");
		assertTrue(buses > configs,
				"the buses open after the config pass, as in CommonModLoader.begin");
		assertTrue(buses < setup,
				"the buses must be open BEFORE the setup lifecycle: a mod that posts its own event from common "
						+ "setup posts into a bus nothing dispatches, silently");
	}

	/**
	 * The client entrypoint window empties NeoForge's blockstate→id map right before the freeze that closes it, so that
	 * freeze's bake numbers every state once (lens A of lane S's wave-2 review: without it each client id was its
	 * dedicated-server id plus the whole state count, NeoForge's probe block 64733 against 32366).
	 */
	@Test
	void theClientWindowEmptiesTheBlockStateMapRightBeforeItsFreeze() throws Exception {
		ClassNode node = compiled();
		assertTrue(node != null,
				"KernelLifecycle not found in the compiled src/main classes, which exist before any test runs");
		emptiesTheMapRightBeforeTheFreeze(method(node, "closeClientEntrypointWindow"), "the client window");
		boolean neoForgesMap = false;
		for (AbstractInsnNode insn : method(node, "clearBlockStateIds").instructions.toArray()) {
			if (insn instanceof org.objectweb.asm.tree.LdcInsnNode ldc && "getBlockStateIDMap".equals(ldc.cst)) neoForgesMap = true;
		}
		assertTrue(neoForgesMap, "the map cleared is NeoForge's own, GameData.getBlockStateIDMap()");
	}

	/**
	 * The main registration window empties NeoForge's blockstate→id map right before the freeze that closes it, as the
	 * client window does: fabric-registry-sync's StateIdTracker has added each block's states to that map as the window
	 * registered it, and the freeze's bake appends the whole registry after whatever is there, so without the clear every
	 * block-state id on a dedicated server was its native NeoForge id plus the count Fabric's tracker added (lane K1: a
	 * mcw-bridges pier at 35212 against native 33186; a Forbric client joining died on "No value with id"). The premise,
	 * on the real bytes, is BlockStateIdNumberingStagedTest's.
	 */
	@Test
	void theRegistrationWindowEmptiesTheBlockStateMapRightBeforeItsFreeze() throws Exception {
		ClassNode node = compiled();
		assertTrue(node != null,
				"KernelLifecycle not found in the compiled src/main classes, which exist before any test runs");
		emptiesTheMapRightBeforeTheFreeze(method(node, "closeRegistrationWindow"), "the registration window");
	}

	/**
	 * The creative tabs are sorted again where the CLIENT's last registration window closes, after its freeze, and not where
	 * the main window closes. NeoForge's own tail sort (end of {@code GameData.postRegisterEvents}) already sees everything
	 * the main window registers, so a second sort there found the same list on a dedicated server and on a client, re-sort
	 * on and off (Stage 4 C5 lane K3b). On a client the Fabric mains and client entrypoints register later, in
	 * {@code onClientEntrypoints}: without a sort when that window closes, a Fabric mod's tab (mcw-stairs, measured) stayed
	 * out of NeoForge's {@code SORTED_TABS} — the only list the creative screen's strip reads — until an integrated
	 * server's reload, and for good on a client joining a dedicated server.
	 */
	@Test
	void theCreativeTabsAreSortedWhereTheClientsLastRegistrationWindowCloses() throws Exception {
		ClassNode node = compiled();
		assertTrue(node != null,
				"KernelLifecycle not found in the compiled src/main classes, which exist before any test runs");
		MethodNode client = method(node, "closeClientEntrypointWindow");
		int freeze = firstCall(client, "freeze"), sort = firstCall(client, "sortNeoCreativeTabs");
		assertTrue(sort >= 0, "closing the client entrypoint window must sort the creative tabs: a Fabric mod's tab "
				+ "registered there is otherwise not in NeoForge's strip on a multiplayer join");
		assertTrue(sort > freeze, "after the freeze, once every tab is registered");
		List<String> sorters = new ArrayList<>();
		for (MethodNode m : node.methods) {
			if (callsIn(m).contains("sortNeoCreativeTabs")) sorters.add(m.name);
		}
		assertTrue(sorters.equals(List.of("closeClientEntrypointWindow")),
				"the client window's close is the one place the kernel sorts the tabs again: " + sorters);
	}

	/** Every freeze the kernel drives is the one right after a clear: the only callers of freeze are the two closes. */
	@Test
	void everyFreezeTheKernelDrivesIsTheOneRightAfterAClear() throws Exception {
		ClassNode node = compiled();
		assertTrue(node != null,
				"KernelLifecycle not found in the compiled src/main classes, which exist before any test runs");
		List<String> callers = new ArrayList<>();
		for (MethodNode m : node.methods) {
			for (AbstractInsnNode insn : m.instructions.toArray()) {
				if (insn instanceof MethodInsnNode call && call.owner.equals(node.name) && call.name.equals("freeze")) {
					callers.add(m.name);
				}
			}
		}
		callers.sort(null);
		assertTrue(callers.equals(List.of("closeClientEntrypointWindow", "closeRegistrationWindow")),
				"freeze(cl) is called only where a clear precedes it; a new caller needs the clear too: " + callers);
	}

	private static void emptiesTheMapRightBeforeTheFreeze(MethodNode close, String what) {
		int clear = firstCall(close, "clearBlockStateIds"), freeze = firstCall(close, "freeze");
		assertTrue(clear >= 0, what + " must empty the blockstate map before freezing");
		assertTrue(freeze > clear, what + " must empty it BEFORE the freeze, whose bake refills it");
		List<String> between = new ArrayList<>();
		AbstractInsnNode[] insns = close.instructions.toArray();
		for (int i = clear + 1; i < freeze; i++) if (insns[i] instanceof MethodInsnNode call) between.add(call.name);
		assertTrue(between.isEmpty(), "nothing runs between the clear and the freeze in " + what + ": " + between);
	}

	/**
	 * Each static hook family reports a failure at a catch of its own, because a {@code Caught} site keeps one first message
	 * for the gates' file: {@code modifyAttributes} threw into GameData's unfreeze/snapshot site and a gate's net missed it
	 * (G-B1's wave-2 handoff). So neither the attribute events nor the other static hooks go through invokeGameDataOn.
	 */
	@Test
	void theAttributeEventsAndTheOtherStaticHooksReportAtTheirOwnCatchSites() throws Exception {
		ClassNode node = compiled();
		assertTrue(node != null,
				"KernelLifecycle not found in the compiled src/main classes, which exist before any test runs");
		for (String name : List.of("postAttributeEvents", "invokeStaticOn")) {
			List<String> calls = callsIn(method(node, name));
			assertTrue(!calls.contains("invokeGameDataOn") && !calls.contains("invokeStaticOn"),
					name + " must not report through another hook's catch: " + calls);
			assertTrue(calls.contains("warn") && calls.contains("invoke"), name + " invokes and reports itself: " + calls);
		}
	}

	private static ClassNode compiled() throws Exception {
		Path file = Path.of(System.getProperty("user.dir"), "build", "classes", "java", "main",
				"net", "forbric", "kernel", "boot", "KernelLifecycle.class");
		if (!Files.isRegularFile(file)) return null;
		ClassNode node = new ClassNode();
		new ClassReader(Files.readAllBytes(file)).accept(node, 0);
		return node;
	}

	private static MethodNode method(ClassNode node, String name) {
		return node.methods.stream().filter(m -> name.equals(m.name)).findFirst()
				.orElseThrow(() -> new AssertionError(name + " is gone"));
	}

	private static List<String> callsIn(MethodNode m) {
		List<String> out = new ArrayList<>();
		for (AbstractInsnNode insn : m.instructions.toArray()) {
			if (insn instanceof MethodInsnNode call) out.add(call.name);
		}
		return out;
	}

	private static int firstCall(MethodNode m, String name) {
		AbstractInsnNode[] insns = m.instructions.toArray();
		for (int i = 0; i < insns.length; i++) {
			if (insns[i] instanceof MethodInsnNode call && name.equals(call.name)) return i;
		}
		return -1;
	}

	/** Unused import guard: TryCatchBlockNode is read through the stream above. */
	@SuppressWarnings("unused")
	private static final Class<?> KEEP = TryCatchBlockNode.class;
}
