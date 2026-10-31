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
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

import net.forbric.kernel.TestFixtures;
import net.forbric.kernel.TestFixtures.Fixture;

/**
 * The once-per-configuration-phase guard on NeoForge's {@code initializeOtherConnection} call sites in the client
 * configuration listener: an unguarded site gets {@code initializedConnection} checked in front of its
 * {@code isOther} test, a site NeoForge already guards is left alone, and the real merged class still verifies.
 */
class CommonNetworkInteropInjectorTest {
	private static final Path MERGED_BASE = TestFixtures.stagedBase();
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
		TestFixtures.requireFile(Fixture.STAGED, MERGED_BASE, "staged merged base absent — skipping real-bytecode check");
		byte[] in = readClass(LISTENER + ".class");
		ClassNode before = parse(in);
		byte[] out = new CommonNetworkInteropInjector().transform(LISTENER_NAME, in, null);
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
		java.nio.file.Path carrier = TestFixtures.stagedRoot().resolve(TestFixtures.NEO_CARRIER_JAR);
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
	 * The play-phase server handler must reach NeoForge's dispatcher when MinecraftForge does not take the payload.
	 *
	 * <p>The merged {@code ServerGamePacketListenerImpl.handleCustomPayload} is MinecraftForge's override and its
	 * whole body is: ask {@code ForgeHooks.onCustomPayload}, {@code POP} the answer, {@code RETURN}. It never
	 * reaches NeoForge's dispatch. So a NeoForge mod's play-phase packet to the server arrived, was offered to
	 * MinecraftForge, declined and stopped — no exception, no log, the mod's server handler simply never ran. Carry
	 * On's carry key is one such packet: with it dropped, nothing could ever be picked up.
	 */
	@Test
	void thePlayServerHandlerReachesNeoForgesDispatcher() throws Exception {
		TestFixtures.requireFile(Fixture.STAGED, MERGED_BASE, "staged merged base absent — skipping real-bytecode check");
		String listener = "net/minecraft/server/network/ServerGamePacketListenerImpl";
		ClassNode node = transformed(listener);
		MethodNode handler = method(node, "handleCustomPayload",
				"(Lnet/minecraft/network/protocol/common/ServerboundCustomPayloadPacket;)V");

		boolean popsTheAnswer = false;
		boolean callsSuper = false;
		boolean callsNeoForge = false;
		boolean branches = false;
		boolean gated = false;
		for (AbstractInsnNode insn : handler.instructions) {
			if (insn.getOpcode() == Opcodes.POP) popsTheAnswer = true;
			if (insn.getOpcode() == Opcodes.IFNE) branches = true;
			if (!(insn instanceof MethodInsnNode call)) continue;
			if ("handleCustomPayload".equals(call.name)
					&& "net/minecraft/server/network/ServerCommonPacketListenerImpl".equals(call.owner)) {
				callsSuper = true;
			}
			if (call.getOpcode() == Opcodes.INVOKESTATIC && "handleModdedPayload".equals(call.name)
					&& "net/neoforged/neoforge/network/registration/NetworkRegistry".equals(call.owner)) {
				callsNeoForge = true;
			}
			if (INTEROP.equals(call.owner) && "neoForgeWillHandle".equals(call.name)) gated = true;
		}

		assertFalse(popsTheAnswer,
				"the hook's answer must be branched on, not discarded — discarding it is the whole defect");
		assertTrue(branches, "MinecraftForge taking the payload must skip the hand-over");
		assertTrue(callsNeoForge,
				"and not taking it must reach NeoForge's NetworkRegistry.handleModdedPayload, the call NeoForge's own "
						+ "super body makes for a mod payload");
		assertFalse(callsSuper,
				"NOT through super: fabric-api's handler in ServerCommonPacketListenerImpl.handleCustomPayload serves "
						+ "only the configuration listener and throws \"Unknown addon\" for this one — measured, it "
						+ "disconnected the player the moment Carry On sent its key");
		// GATED: NeoForge's dispatcher disconnects on a channel it never registered, where vanilla's empty play
		// override drops a payload nobody took.
		assertTrue(gated,
				"the hand-over must be gated on NeoForge actually owning the payload — ungated, NeoForge disconnects "
						+ "on any payload nobody else took");
		// The frame authored at the branch target has to be right, or the class fails verification at link time
		// and every play-phase packet on the server becomes a VerifyError instead.
		new Analyzer<>(new BasicVerifier()).analyze(node.name, handler);
	}

	/** On unless switched off: a dropped packet is a mod that silently does not work. */
	@Test
	void thePlayHandOverIsOnByDefaultAndHasAnOffSwitch() {
		String previous = System.getProperty("forbric.playPayloadFallThrough");
		try {
			System.clearProperty("forbric.playPayloadFallThrough");
			assertTrue(CommonNetworkInteropInjector.playFallThroughEnabled(),
					"on by default — off, every NeoForge mod's play-phase packet to the server is dropped");
			System.setProperty("forbric.playPayloadFallThrough", "off");
			assertFalse(CommonNetworkInteropInjector.playFallThroughEnabled(), "-Dforbric.playPayloadFallThrough=off");
		} finally {
			if (previous == null) System.clearProperty("forbric.playPayloadFallThrough");
			else System.setProperty("forbric.playPayloadFallThrough", previous);
		}
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
		TestFixtures.requireFile(Fixture.STAGED, MERGED_BASE, "staged merged base absent — skipping real-bytecode check");
		for (String entry : List.of(
				"net/minecraft/network/Connection",
				"net/minecraft/server/network/ServerConfigurationPacketListenerImpl",
				"net/minecraft/server/network/ServerGamePacketListenerImpl")) {
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
