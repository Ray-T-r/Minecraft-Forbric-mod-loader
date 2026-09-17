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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.BasicVerifier;

/**
 * The once-per-configuration-phase guard on NeoForge's {@code initializeOtherConnection} call sites in the client
 * configuration listener: an unguarded site gets {@code initializedConnection} checked in front of its
 * {@code isOther} test, a site NeoForge already guards is left alone, and the real merged class still verifies.
 */
class CommonNetworkInteropInjectorTest {
	private static final Path MERGED_BASE =
			Path.of(System.getProperty("user.dir"), "..", "forbric-loader", "run", "merged-base",
					"patched-mc-merged-26.2.jar").normalize();
	private static final String LISTENER = "net/minecraft/client/multiplayer/ClientConfigurationPacketListenerImpl";
	private static final String LISTENER_NAME = LISTENER.replace('/', '.');
	private static final String CONNECTION_TYPE = "net/neoforged/neoforge/network/connection/ConnectionType";
	private static final String CLIENT_REGISTRY = "net/neoforged/neoforge/client/network/registration/ClientNetworkRegistry";
	private static final String INITIALIZE_DESC = "(L" + LISTENER + ";)V";
	private static final String INTEROP = "net/forbric/kernel/interop/PayloadInterop";

	@Test
	void theUnguardedSiteGetsTheFlagCheckAndTheGuardedOneIsLeftAlone() throws Exception {
		byte[] in = listener();
		byte[] out = new CommonNetworkInteropInjector().transform(LISTENER_NAME, in, null);
		assertTrue(out != in);
		ClassNode node = parse(out);

		MethodNode unguarded = method(node, "handleEnabledFeatures");
		assertEquals(1, flagChecks(unguarded), "the flag is now checked once");
		JumpInsnNode ifne = flagJump(unguarded);
		assertEquals(Opcodes.IFNE, ifne.getOpcode());
		// It jumps where NeoForge's own isOther guard jumps: past the initialisation.
		AbstractInsnNode isOtherJump = null;
		for (AbstractInsnNode insn : unguarded.instructions) {
			if (insn.getOpcode() == Opcodes.IFEQ) isOtherJump = insn;
		}
		assertNotNull(isOtherJump);
		assertEquals(((JumpInsnNode) isOtherJump).label, ifne.label);
		new Analyzer<>(new BasicVerifier()).analyze(node.name, unguarded);

		MethodNode guarded = method(node, "handleConfigurationFinished");
		assertEquals(1, flagChecks(guarded), "NeoForge's own check stays the only one");
		new Analyzer<>(new BasicVerifier()).analyze(node.name, guarded);
	}

	@Test
	void theRealMergedListenerGetsExactlyTwoNewGuardsAndVerifies() throws Exception {
		assumeTrue(Files.isRegularFile(MERGED_BASE), "staged merged base absent — skipping real-bytecode check");
		byte[] in = readClass(LISTENER + ".class");
		ClassNode before = parse(in);
		byte[] out = new CommonNetworkInteropInjector().transform(LISTENER_NAME, in, null);
		assertTrue(out != in);
		ClassNode after = parse(out);
		int added = 0;
		for (MethodNode m : after.methods) {
			MethodNode was = method(before, m.name, m.desc);
			int delta = flagChecks(m) - flagChecks(was);
			assertTrue(delta >= 0 && delta <= 1, m.name + " gained " + delta + " flag check(s)");
			added += delta;
			if (delta > 0) new Analyzer<>(new BasicVerifier()).analyze(after.name, m);
		}
		if (listenerCarriesTheFlag(before)) {
			assertEquals(2, added,
					"the brand-payload site and the enabled-features site — the third was already guarded");
		} else {
			assertEquals(0, added, "no flag to read means nothing to splice");
			assertTrue(neoForgeGuardsInitialisationItself(),
					"the carrier dropped ClientConfigurationPacketListenerImpl.initializedConnection, so the kernel "
							+ "splices no guard — and NeoForge must then be guarding re-initialisation itself. It "
							+ "does so from 26.2.0.88 via runConnectionInitialization + the CONNECTION_INITIALIZED "
							+ "channel attribute. If neither guard exists, every join re-runs the whole "
							+ "initialisation: every mod's server config rebuilt, filters re-injected, register "
							+ "payload re-sent");
		}
	}

	private static boolean listenerCarriesTheFlag(ClassNode listener) {
		return listener.fields.stream().anyMatch(f -> "initializedConnection".equals(f.name) && "Z".equals(f.desc));
	}

	/** NeoForge's own guard: {@code runConnectionInitialization} consults {@code isConnectionInitialized}. */
	private static boolean neoForgeGuardsInitialisationItself() throws Exception {
		java.nio.file.Path carrier = Path.of(System.getProperty("user.dir"), "..", "forbric-loader", "run",
				"neoforge-runtime", "neoforge-runtime.jar").normalize();
		if (!Files.isRegularFile(carrier)) return true; // nothing staged to contradict it
		try (java.util.zip.ZipFile zip = new java.util.zip.ZipFile(carrier.toFile())) {
			java.util.zip.ZipEntry e = zip.getEntry(
					"net/neoforged/neoforge/client/network/registration/ClientNetworkRegistry.class");
			if (e == null) return false;
			ClassNode node;
			try (InputStream in = zip.getInputStream(e)) {
				node = parse(in.readAllBytes());
			}
			for (MethodNode m : node.methods) {
				if (!"runConnectionInitialization".equals(m.name) || m.instructions == null) continue;
				for (AbstractInsnNode insn : m.instructions) {
					if (insn instanceof MethodInsnNode call && "isConnectionInitialized".equals(call.name)) {
						return true;
					}
				}
			}
			return false;
		}
	}

	/**
	 * The play-phase server handler must fall through to NeoForge when MinecraftForge does not take the payload.
	 *
	 * <p>The merged {@code ServerGamePacketListenerImpl.handleCustomPayload} is MinecraftForge's override and its
	 * whole body is: ask {@code ForgeHooks.onCustomPayload}, {@code POP} the answer, {@code RETURN}. It never
	 * calls {@code super}, and NeoForge's dispatch lives on exactly that super. So a NeoForge mod's play-phase
	 * packet to the server arrived, was offered to MinecraftForge, declined and stopped — no exception, no log,
	 * the mod's server handler simply never ran. Every GUI button, keybind action and config-sync request a
	 * NeoForge mod sends upward was dead, singleplayer included.
	 */
	@Test
	void thePlayServerHandlerFallsThroughToNeoForge() throws Exception {
		assumeTrue(Files.isRegularFile(MERGED_BASE), "staged merged base absent — skipping real-bytecode check");
		// The rewrite is behind a switch that defaults OFF — see playFallThroughEnabled for why — so the test
		// turns it on for itself. What is asserted is the SHAPE of the rewrite when it does run, which is what a
		// future edit could break without anyone noticing.
		String previous = System.getProperty("forbric.playPayloadFallThrough");
		System.setProperty("forbric.playPayloadFallThrough", "on");
		try {
			assertFallThroughShape();
		} finally {
			if (previous == null) System.clearProperty("forbric.playPayloadFallThrough");
			else System.setProperty("forbric.playPayloadFallThrough", previous);
		}
	}

	/** The default must be the old behaviour: a disconnected player is worse than a dropped packet. */
	@Test
	void thePlayFallThroughIsOffByDefault() {
		assertFalse(CommonNetworkInteropInjector.playFallThroughEnabled(),
				"the fall-through reaches fabric-api's own server-play handler for the first time on this base, and "
						+ "that handler throws \"Unknown addon\" and ends the connection — until the Fabric half is "
						+ "fixed, the default stays at the silent drop");
	}

	private void assertFallThroughShape() throws Exception {
		String listener = "net/minecraft/server/network/ServerGamePacketListenerImpl";
		ClassNode node = transformed(listener);
		MethodNode handler = method(node, "handleCustomPayload",
				"(Lnet/minecraft/network/protocol/common/ServerboundCustomPayloadPacket;)V");

		boolean popsTheAnswer = false;
		boolean callsSuper = false;
		boolean branches = false;
		for (AbstractInsnNode insn : handler.instructions) {
			if (insn.getOpcode() == Opcodes.POP) popsTheAnswer = true;
			if (insn.getOpcode() == Opcodes.IFNE) branches = true;
			if (insn instanceof MethodInsnNode call && call.getOpcode() == Opcodes.INVOKESPECIAL
					&& "net/minecraft/server/network/ServerCommonPacketListenerImpl".equals(call.owner)
					&& "handleCustomPayload".equals(call.name)) {
				callsSuper = true;
			}
		}

		assertFalse(popsTheAnswer,
				"the hook's answer must be branched on, not discarded — discarding it is the whole defect");
		assertTrue(branches, "MinecraftForge taking the payload must skip the fall-through");
		assertTrue(callsSuper,
				"and not taking it must reach ServerCommonPacketListenerImpl.handleCustomPayload, which is where "
						+ "NeoForge's dispatcher lives");

		// GATED. Falling through unconditionally was measured to be worse than the bug: NeoForge's dispatcher is
		// strict about ids it does not know, so a Fabric mod's play payload arriving here ended the connection
		// with "IllegalStateException: Unknown addon" and a client that used to join could no longer stay in a
		// world. The fall-through must ask whether NeoForge owns the payload first.
		boolean gated = false;
		for (AbstractInsnNode insn : handler.instructions) {
			if (insn instanceof MethodInsnNode call && INTEROP.equals(call.owner)
					&& "neoForgeWillHandle".equals(call.name)) {
				gated = true;
			}
		}
		assertTrue(gated,
				"the fall-through must be gated on NeoForge actually owning the payload — ungated it disconnects "
						+ "the player on the first Fabric play payload");
		// The frame authored at the branch target has to be right, or the class fails verification at link time
		// and every play-phase packet on the server becomes a VerifyError instead.
		new Analyzer<>(new BasicVerifier()).analyze(node.name, handler);
	}

	@Test
	void theMergedConnectionStartsForgesNetworkingWhenItGoesActive() throws Exception {
		assumeTrue(Files.isRegularFile(MERGED_BASE), "staged merged base absent — skipping real-bytecode check");
		String connection = "net/minecraft/network/Connection";
		ClassNode node = transformed(connection);
		MethodNode active = method(node, "channelActive", "(Lio/netty/channel/ChannelHandlerContext;)V");

		int hooks = 0;
		for (AbstractInsnNode insn : active.instructions) {
			if (!(insn instanceof MethodInsnNode call) || !INTEROP.equals(call.owner)) continue;
			assertEquals("onConnectionActive", call.name);
			// It must land after the channel field is stored (Forge's own moment) and before the disconnect check.
			AbstractInsnNode next = insn.getNext();
			while (next != null && next.getOpcode() < 0) next = next.getNext();
			assertEquals(Opcodes.ALOAD, next.getOpcode());
			hooks++;
		}
		assertEquals(1, hooks, "exactly one activation hook");
		assertTrue(storesFieldBefore(active, "channel", "onConnectionActive"),
				"Forge's handler needs the netty channel, so the hook must follow the channel store");
		new Analyzer<>(new BasicVerifier()).analyze(node.name, active);
	}

	@Test
	void theMergedServerGathersForgesConfigurationTasksAfterNeoForges() throws Exception {
		assumeTrue(Files.isRegularFile(MERGED_BASE), "staged merged base absent — skipping real-bytecode check");
		ClassNode node = transformed("net/minecraft/server/network/ServerConfigurationPacketListenerImpl");
		MethodNode run = method(node, "runConfiguration", "()V");

		int index = 0, neo = -1, forge = -1;
		for (AbstractInsnNode insn : run.instructions) {
			if (insn instanceof MethodInsnNode call) {
				if ("configureEarlyTasks".equals(call.name)) neo = index;
				if (INTEROP.equals(call.owner) && "gatherForgeConfigurationTasks".equals(call.name)) forge = index;
			}
			index++;
		}
		assertTrue(neo >= 0, "NeoForge's early-task call is the anchor and must still be there");
		assertTrue(forge > neo, "Forge's tasks are gathered after NeoForge's, into the same queue");
		new Analyzer<>(new BasicVerifier()).analyze(node.name, run);
	}

	@Test
	void configurationTasksStartThroughForgesContextInstead() throws Exception {
		assumeTrue(Files.isRegularFile(MERGED_BASE), "staged merged base absent — skipping real-bytecode check");
		ClassNode node = transformed("net/minecraft/server/network/ServerConfigurationPacketListenerImpl");
		MethodNode start = method(node, "startNextTask", "()V");

		MethodInsnNode dispatch = null;
		for (AbstractInsnNode insn : start.instructions) {
			if (insn instanceof MethodInsnNode call && "net/minecraft/server/network/ConfigurationTask".equals(call.owner)
					&& "start".equals(call.name)) {
				assertNull(dispatch, "one dispatch only");
				dispatch = call;
			}
		}
		assertNotNull(dispatch);
		assertEquals("(Lnet/minecraftforge/network/config/ConfigurationTaskContext;)V", dispatch.desc,
				"Forge's own tasks throw on the vanilla overload; every other task reaches it through the default");
		AbstractInsnNode arg = dispatch.getPrevious();
		while (arg != null && arg.getOpcode() < 0) arg = arg.getPrevious();
		assertTrue(arg instanceof FieldInsnNode field && "taskContext".equals(field.name)
				&& Opcodes.GETFIELD == field.getOpcode(), "the argument is the listener's own task context");
		// Nothing builds the captured consumer any more (other lambdas in the method — the error message's string
		// concatenation — are untouched).
		for (AbstractInsnNode insn : start.instructions) {
			if (!(insn instanceof org.objectweb.asm.tree.InvokeDynamicInsnNode indy)) continue;
			assertFalse(indy.desc.endsWith(")Ljava/util/function/Consumer;"),
					"the packet-sending consumer is no longer built: " + indy.name + indy.desc);
		}
		new Analyzer<>(new BasicVerifier()).analyze(node.name, start);
	}

	@Test
	void theClientRunsForgesConfigurationCompleteBeforeItEntersPlay() throws Exception {
		assumeTrue(Files.isRegularFile(MERGED_BASE), "staged merged base absent — skipping real-bytecode check");
		ClassNode node = transformed("net/minecraft/client/multiplayer/ClientConfigurationPacketListenerImpl");
		MethodNode finished = method(node, "handleConfigurationFinished",
				"(Lnet/minecraft/network/protocol/configuration/ClientboundFinishConfigurationPacket;)V");

		int index = 0, neo = -1, forge = -1, reply = -1;
		for (AbstractInsnNode insn : finished.instructions) {
			if (insn instanceof MethodInsnNode call) {
				if ("onConfigurationFinished".equals(call.name)) neo = index;
				if (INTEROP.equals(call.owner) && "onClientConfigurationFinished".equals(call.name)) forge = index;
				if ("send".equals(call.name) && reply < 0 && forge >= 0) reply = index;
			}
			index++;
		}
		assertTrue(neo >= 0 && forge > neo, "Forge's hook runs after NeoForge's own finish");
		assertTrue(reply > forge, "…and before the client tells the server it is entering play");
		new Analyzer<>(new BasicVerifier()).analyze(node.name, finished);
	}

	/**
	 * Every hook this injector splices into the real merged listeners resolves to a real method.
	 *
	 * <p>The tests above assert that a call was emitted, where it sits, and what it is handed — none of them
	 * assert the call goes anywhere. Owner, name and descriptor are three independent strings here, so renaming
	 * or re-signing the hook leaves all of them green and moves the failure to a {@code NoSuchMethodError} thrown
	 * from inside the game's packet handling.
	 */
	@Test
	void everyHookSplicedIntoTheRealListenersResolves() throws Exception {
		assumeTrue(Files.isRegularFile(MERGED_BASE), "staged merged base absent — skipping real-bytecode check");
		for (String entry : List.of(
				"net/minecraft/network/Connection",
				"net/minecraft/server/network/ServerConfigurationPacketListenerImpl",
				"net/minecraft/client/multiplayer/ClientConfigurationPacketListenerImpl",
				"net/minecraft/client/multiplayer/ClientCommonPacketListenerImpl",
				"net/minecraft/server/network/ServerCommonPacketListenerImpl")) {
			byte[] in = readClass(entry + ".class");
			byte[] out = new CommonNetworkInteropInjector().transform(entry.replace('/', '.'), in, null);
			assertTrue(out != in, entry + " must still need the injection");
			InteropHookAssertions.assertEveryInteropCallResolves(out);
		}
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

	/** A stand-in listener: one site guarded the NeoForge way, one not, both otherwise shaped like the real ones. */
	private static byte[] listener() {
		ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
		cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, LISTENER, null, "java/lang/Object", null);
		cw.visitField(Opcodes.ACC_PRIVATE, "connectionType", "L" + CONNECTION_TYPE + ";", null, null).visitEnd();
		cw.visitField(Opcodes.ACC_PRIVATE, "initializedConnection", "Z", null, null).visitEnd();
		site(cw, "handleEnabledFeatures", false);
		site(cw, "handleConfigurationFinished", true);
		cw.visitEnd();
		return cw.toByteArray();
	}

	private static void site(ClassWriter cw, String name, boolean alreadyGuarded) {
		MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC, name, "()V", null, null);
		mv.visitCode();
		Label skip = new Label();
		if (alreadyGuarded) {
			mv.visitVarInsn(Opcodes.ALOAD, 0);
			mv.visitFieldInsn(Opcodes.GETFIELD, LISTENER, "initializedConnection", "Z");
			mv.visitJumpInsn(Opcodes.IFNE, skip);
		}
		mv.visitVarInsn(Opcodes.ALOAD, 0);
		mv.visitFieldInsn(Opcodes.GETFIELD, LISTENER, "connectionType", "L" + CONNECTION_TYPE + ";");
		mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, CONNECTION_TYPE, "isOther", "()Z", false);
		mv.visitJumpInsn(Opcodes.IFEQ, skip);
		mv.visitVarInsn(Opcodes.ALOAD, 0);
		mv.visitInsn(Opcodes.ICONST_1);
		mv.visitFieldInsn(Opcodes.PUTFIELD, LISTENER, "initializedConnection", "Z");
		mv.visitVarInsn(Opcodes.ALOAD, 0);
		mv.visitMethodInsn(Opcodes.INVOKESTATIC, CLIENT_REGISTRY, "initializeOtherConnection", INITIALIZE_DESC, false);
		mv.visitLabel(skip);
		mv.visitInsn(Opcodes.RETURN);
		mv.visitMaxs(0, 0);
		mv.visitEnd();
	}

	private static int flagChecks(MethodNode m) {
		int n = 0;
		for (AbstractInsnNode insn : m.instructions) {
			if (insn.getOpcode() == Opcodes.GETFIELD && "initializedConnection".equals(((FieldInsnNode) insn).name)) n++;
		}
		return n;
	}

	private static JumpInsnNode flagJump(MethodNode m) {
		for (AbstractInsnNode insn : m.instructions) {
			if (insn.getOpcode() == Opcodes.GETFIELD && "initializedConnection".equals(((FieldInsnNode) insn).name)) {
				AbstractInsnNode next = insn.getNext();
				while (next != null && next.getOpcode() < 0) next = next.getNext();
				return (JumpInsnNode) next;
			}
		}
		throw new AssertionError("no flag check in " + m.name);
	}

	private static ClassNode parse(byte[] bytes) {
		ClassNode node = new ClassNode();
		new ClassReader(bytes).accept(node, ClassReader.EXPAND_FRAMES);
		return node;
	}

	private static MethodNode method(ClassNode node, String name) {
		for (MethodNode m : node.methods) {
			if (m.name.equals(name)) return m;
		}
		throw new AssertionError("no method " + name);
	}

	private static MethodNode method(ClassNode node, String name, String desc) {
		for (MethodNode m : node.methods) {
			if (m.name.equals(name) && m.desc.equals(desc)) return m;
		}
		throw new AssertionError("no method " + name + desc);
	}

	private static byte[] readClass(String entry) throws Exception {
		try (ZipFile zip = new ZipFile(MERGED_BASE.toFile())) {
			ZipEntry found = zip.getEntry(entry);
			assertNotNull(found, entry + " missing from the staged merged base");
			try (InputStream in = zip.getInputStream(found)) {
				return in.readAllBytes();
			}
		}
	}
}
