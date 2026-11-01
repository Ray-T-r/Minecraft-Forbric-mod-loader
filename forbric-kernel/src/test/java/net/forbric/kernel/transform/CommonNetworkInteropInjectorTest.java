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

package net.forbric.kernel.transform;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import net.forbric.kernel.TestFixtures;
import net.forbric.kernel.TestFixtures.Fixture;

/**
 * {@link CommonNetworkInteropInjector} against the staged base and carrier: the play listener it no longer edits, the
 * hooks it splices resolving, and NeoForge's own once-per-connection initialisation guard, which replaced the kernel's.
 */
class CommonNetworkInteropInjectorTest {
	private static final Path STAGED_BASE = TestFixtures.stagedBase();
	private static final String LISTENER = "net/minecraft/client/multiplayer/ClientConfigurationPacketListenerImpl";
	private static final String CLIENT_REGISTRY = "net/neoforged/neoforge/client/network/registration/ClientNetworkRegistry";

	/**
	 * NeoForge initialises a client's connection to a non-NeoForge (or NeoForge) server once per connection itself, so
	 * the kernel no longer splices a guard into the client configuration listener.
	 *
	 * <p>The 26.2.0.38 beta listener ran {@code ClientNetworkRegistry.initializeOtherConnection} from three call sites per
	 * join and checked its own {@code initializedConnection} flag at one of them; the other two rebuilt every mod's default
	 * server config ("Overwriting non-null config ... with default server config"), re-injected the payload filters and
	 * re-sent the register payload, and the kernel spliced the flag check in front of them. From 26.2.0.88 the flag is
	 * gone and every site goes through {@code runConnectionInitialization}, which takes a per-connection lock and returns
	 * once the {@code CONNECTION_INITIALIZED} channel attribute is set; the splice matched nothing from then on and went in
	 * Stage 4. This pins NeoForge's guard on the staged pair: every listener call site reaches one of the two
	 * initialisers, both go through {@code runConnectionInitialization}, it consults {@code isConnectionInitialized}, and
	 * {@code configureOtherConnection} and {@code configureNeoForgeConnection} (the work that must not repeat) are reached
	 * only from the runnables an initialiser hands {@code runConnectionInitialization}, read from the bootstrap handle of
	 * the {@code invokedynamic} that builds the runnable passed to it. Another lambda of the same initialiser does not
	 * count: {@code initializeOtherConnection}'s event-loop hop is also a {@code lambda$initializeOtherConnection$...}, and
	 * a configure call there would run outside the guard. A carrier that drops any of that goes red here before a
	 * player's configs are rebuilt twice per join.
	 */
	@Test
	void neoForgeInitialisesEachClientConnectionOnceItself() throws Exception {
		TestFixtures.requireFile(Fixture.STAGED, STAGED_BASE, "staged base absent — skipping real-bytecode check");
		Path carrier = TestFixtures.stagedRoot().resolve(TestFixtures.NEO_CARRIER_JAR);
		TestFixtures.requireFile(Fixture.STAGED, carrier, "staged NeoForge carrier absent — skipping real-bytecode check");

		ClassNode listener = parse(readClass(LISTENER + ".class"));
		List<String> sites = new java.util.ArrayList<>();
		for (MethodNode m : listener.methods) {
			for (AbstractInsnNode insn : m.instructions) {
				if (insn instanceof MethodInsnNode call && CLIENT_REGISTRY.equals(call.owner)
						&& call.name.startsWith("initialize")) sites.add(m.name + " -> " + call.name);
			}
		}
		assertFalse(sites.isEmpty(), "the client configuration listener no longer calls NeoForge's initialisers at all; "
				+ "re-derive where NeoForge initialises a client connection");

		ClassNode registry;
		try (ZipFile zip = new ZipFile(carrier.toFile())) {
			ZipEntry e = zip.getEntry(CLIENT_REGISTRY + ".class");
			assertNotNull(e, CLIENT_REGISTRY + " missing from the staged carrier");
			try (InputStream in = zip.getInputStream(e)) {
				registry = parse(in.readAllBytes());
			}
		}
		for (String site : sites) {
			String initialiser = site.substring(site.indexOf("-> ") + 3);
			assertTrue(calls(registry, initialiser, "runConnectionInitialization"),
					site + ": NeoForge's " + initialiser + " no longer goes through runConnectionInitialization, its "
							+ "once-per-connection guard; every join may now re-run the whole initialisation");
		}
		assertTrue(calls(registry, "runConnectionInitialization", "isConnectionInitialized"),
				"runConnectionInitialization no longer consults isConnectionInitialized (the CONNECTION_INITIALIZED "
						+ "channel attribute): NeoForge stopped guarding re-initialisation");
		java.util.Set<String> guarded = guardedRunnables(registry);
		for (String site : sites) {
			String initialiser = site.substring(site.indexOf("-> ") + 3);
			assertTrue(guarded.stream().anyMatch(r -> r.startsWith(initialiser + " => ")),
					site + ": " + initialiser + " hands runConnectionInitialization no runnable of this class built by an "
							+ "invokedynamic just before the call; re-derive what NeoForge's guard runs: " + guarded);
		}
		java.util.Set<String> guardedNames = new java.util.TreeSet<>();
		for (String r : guarded) guardedNames.add(r.substring(r.indexOf(" => ") + 4));
		for (String work : List.of("configureOtherConnection", "configureNeoForgeConnection")) {
			List<String> callers = new java.util.ArrayList<>();
			for (MethodNode m : registry.methods) {
				for (AbstractInsnNode insn : m.instructions) {
					if (insn instanceof MethodInsnNode call && CLIENT_REGISTRY.equals(call.owner) && work.equals(call.name)) {
						callers.add(m.name);
					}
				}
			}
			assertFalse(callers.isEmpty(), work + " is gone; re-derive NeoForge's connection setup");
			for (String caller : callers) {
				assertTrue(guardedNames.contains(caller),
						work + " is reached from " + caller + ", which is not a runnable an initialiser hands "
								+ "runConnectionInitialization " + guardedNames + ", so it runs outside NeoForge's "
								+ "once-per-connection guard: " + callers);
			}
		}
	}

	/**
	 * Every runnable an {@code initialize*} method of {@code registry} hands {@code runConnectionInitialization}, as
	 * {@code "initializer => lambdaName"}: the implementation handle of the {@code invokedynamic} immediately before each
	 * such call, kept only when it names a method of the registry itself.
	 */
	private static java.util.Set<String> guardedRunnables(ClassNode registry) {
		java.util.Set<String> out = new java.util.TreeSet<>();
		for (MethodNode m : registry.methods) {
			if (!m.name.startsWith("initialize") || m.instructions == null) continue;
			for (AbstractInsnNode insn : m.instructions) {
				if (!(insn instanceof MethodInsnNode call) || !"runConnectionInitialization".equals(call.name)) continue;
				AbstractInsnNode prev = insn.getPrevious();
				while (prev != null && prev.getOpcode() < 0) prev = prev.getPrevious();
				if (prev instanceof org.objectweb.asm.tree.InvokeDynamicInsnNode indy && indy.bsmArgs.length > 1
						&& indy.bsmArgs[1] instanceof org.objectweb.asm.Handle impl && registry.name.equals(impl.getOwner())) {
					out.add(m.name + " => " + impl.getName());
				}
			}
		}
		return out;
	}

	/**
	 * NeoForge's own client configuration listener hands the server's {@code minecraft:register} to super (where
	 * fabric-api's addon waits) right before its own reply, so {@code shareMinecraftRegisterWithSuper} finds nothing to
	 * splice on the staged base and leaves the method as it is.
	 *
	 * <p>The repair exists for a NeoForge build whose override answers the register and returns before super (26.2.0.38
	 * beta did): Fabric's server then reads NeoForge's channel list as the client's whole declaration and kicks. From
	 * 26.2.0.88 NeoForge calls super ahead of {@code sendInitialListeningChannels} itself. This pins that shape on the
	 * staged base: if a NeoForge update drops the super call, this goes red and the repair is live again (it then splices
	 * the call, which {@code CommonNetworkInteropInjectorExecutionTest} covers on a stand-in of the old shape).
	 */
	@Test
	void theBasesConfigurationListenerHandsTheRegisterToSuperBeforeNeoForgesReply() throws Exception {
		TestFixtures.requireFile(Fixture.STAGED, STAGED_BASE, "staged base absent — skipping real-bytecode check");
		MethodNode handler = method(parse(readClass(LISTENER + ".class")), "handleCustomPayload",
				"(Lnet/minecraft/network/protocol/common/ClientboundCustomPayloadPacket;)V");
		int before = handler.instructions.size();
		assertEquals(0, CommonNetworkInteropInjector.shareMinecraftRegisterWithSuper(handler),
				"NeoForge's handleCustomPayload no longer hands minecraft:register to super right before "
						+ "sendInitialListeningChannels and its return (1 = the repair spliced the call in, -1 = not a shape "
						+ "it knows); re-derive whether fabric-api's addon still hears the register first");
		assertEquals(before, handler.instructions.size(), "the repair left the staged listener as it is");
	}

	/** Whether {@code owner.method} (any overload) calls a method named {@code callee}. */
	private static boolean calls(ClassNode owner, String method, String callee) {
		for (MethodNode m : owner.methods) {
			if (!m.name.equals(method) || m.instructions == null) continue;
			for (AbstractInsnNode insn : m.instructions) {
				if (insn instanceof MethodInsnNode call && callee.equals(call.name)) return true;
			}
		}
		return false;
	}

	/**
	 * NeoForge's own play listener hands every payload to its super, and the injector leaves it alone.
	 *
	 * <p>The merged base's {@code ServerGamePacketListenerImpl.handleCustomPayload} was MinecraftForge's override: ask
	 * {@code ForgeHooks.onCustomPayload}, drop the answer, return, never reaching NeoForge's dispatcher, so a NeoForge mod's
	 * play-phase packet to the server (Carry On's carry key) reached nobody. This injector rewrote it to fall through to
	 * {@code NetworkRegistry.handleModdedPayload} until Stage 4. On NeoForge's base the override is
	 * {@code super.handleCustomPayload(packet)}, whose body is NeoForge's dispatcher; fabric-api's handler waiting there is
	 * {@code FabricCommonPayloadMixinAdapter}'s. If a NeoForge update ever made this override drop payloads again, this
	 * goes red before Carry On does.
	 */
	@Test
	void theBasesPlayListenerHandsEveryPayloadToItsSuperAndIsLeftAlone() throws Exception {
		TestFixtures.requireFile(Fixture.STAGED, STAGED_BASE, "staged base absent — skipping real-bytecode check");
		String listener = "net/minecraft/server/network/ServerGamePacketListenerImpl";
		byte[] in = readClass(listener + ".class");
		assertSame(in, new CommonNetworkInteropInjector().transform(listener.replace('/', '.'), in, null),
				"the play listener is not one of this injector's targets any more");
		MethodNode handler = method(parse(in), "handleCustomPayload",
				"(Lnet/minecraft/network/protocol/common/ServerboundCustomPayloadPacket;)V");
		List<String> calls = new java.util.ArrayList<>();
		for (AbstractInsnNode insn : handler.instructions) {
			if (insn instanceof MethodInsnNode call) calls.add(call.getOpcode() + " " + call.owner + "." + call.name + call.desc);
		}
		assertEquals(List.of(Opcodes.INVOKESPECIAL + " net/minecraft/server/network/ServerCommonPacketListenerImpl.handleCustomPayload"
				+ "(Lnet/minecraft/network/protocol/common/ServerboundCustomPayloadPacket;)V"), calls,
				"the override's one call is its super, NeoForge's dispatcher");
	}

	/**
	 * Every hook this injector splices into the real listeners resolves to a real method, each listener judged on its own.
	 *
	 * <p>The tests above assert that a call was emitted, where it sits, and what it is handed — none of them
	 * assert the call goes anywhere. Owner, name and descriptor are three independent strings here, so renaming
	 * or re-signing the hook leaves all of them green and moves the failure to a {@code NoSuchMethodError} thrown
	 * from inside the game's packet handling. Each listener is a separate assertion, so one that stops needing the
	 * injection cannot hide whether the others' hooks resolve (it used to be one loop that stopped at its first failure).
	 */
	@Test
	void everyHookSplicedIntoTheRealListenersResolves() throws Exception {
		TestFixtures.requireFile(Fixture.STAGED, STAGED_BASE, "staged base absent — skipping real-bytecode check");
		List<org.junit.jupiter.api.function.Executable> perListener = new java.util.ArrayList<>();
		for (String entry : List.of(
				"net/minecraft/network/Connection",
				"net/minecraft/server/network/ServerConfigurationPacketListenerImpl")) {
			perListener.add(() -> {
				byte[] in = readClass(entry + ".class");
				byte[] out = new CommonNetworkInteropInjector().transform(entry.replace('/', '.'), in, null);
				assertTrue(out != in, entry + " must still need the injection");
				InteropHookAssertions.assertEveryInteropCallResolves(out);
			});
		}
		assertAll(perListener);
	}

	private static boolean storesFieldBefore(MethodNode m, String field, String hook) {
		boolean stored = false;
		for (AbstractInsnNode insn : m.instructions) {
			if (insn.getOpcode() == Opcodes.PUTFIELD && field.equals(((FieldInsnNode) insn).name)) stored = true;
			if (insn instanceof MethodInsnNode call && hook.equals(call.name)) return stored;
		}
		return false;
	}

	private static ClassNode transformed(String internalName) throws Exception {
		byte[] in = readClass(internalName + ".class");
		byte[] out = new CommonNetworkInteropInjector().transform(internalName.replace('/', '.'), in, null);
		assertTrue(out != in, internalName + " must still need the injection — if it stopped, re-derive the anchors");
		return parse(out);
	}

	// --- helpers -------------------------------------------------------------------------------------------------

	private static ClassNode parse(byte[] bytes) {
		ClassNode node = new ClassNode();
		new ClassReader(bytes).accept(node, ClassReader.EXPAND_FRAMES);
		return node;
	}

	private static MethodNode method(ClassNode node, String name, String desc) {
		for (MethodNode m : node.methods) {
			if (m.name.equals(name) && m.desc.equals(desc)) return m;
		}
		throw new AssertionError("no method " + name + desc);
	}

	private static byte[] readClass(String entry) throws Exception {
		try (ZipFile zip = new ZipFile(STAGED_BASE.toFile())) {
			ZipEntry found = zip.getEntry(entry);
			assertNotNull(found, entry + " missing from the staged merged base");
			try (InputStream in = zip.getInputStream(found)) {
				return in.readAllBytes();
			}
		}
	}
}
