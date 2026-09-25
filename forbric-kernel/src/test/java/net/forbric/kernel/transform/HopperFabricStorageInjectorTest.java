package net.forbric.kernel.transform;

import static org.junit.jupiter.api.Assertions.*;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.BasicVerifier;

/** NeoForge's hopper asking Fabric's item storage lookup, on the real merged HopperBlockEntity. */
@ResourceLock("system-properties")
class HopperFabricStorageInjectorTest {
	private static final Path STAGED = Path.of(System.getProperty("forbric.stagedRoot", "../forbric-loader/run"));
	private static final Path MERGED = STAGED.resolve("merged-base/patched-mc-merged-26.2.jar");
	private static final Path NEO = STAGED.resolve("neoforge-patched/patched-mc-neoforge-26.2.jar");
	private static final Path FORGE = STAGED.resolve("forge-patched/patched-mc-forge-26.2.jar");
	private static final Path FABRIC_API = Path.of(System.getProperty("user.dir"), "run/client-merged-pack/mods/fabric-api-0.155.2+26.2.jar");
	private static final String HOPPER = HopperFabricStorageInjector.HOPPER_INTERNAL;

	@AfterEach void reset() { System.clearProperty(HopperFabricStorageInjector.PROPERTY); }

	/** What Fabric's mixin anchors on is exactly what NeoForge's body no longer calls. */
	@Test void premiseMergedBodiesAreNeoForgesAndFabricsAnchorsAreAbsent() throws Exception {
		ClassNode merged = node(NativeCoremodParityTest.read(MERGED, HOPPER));
		MethodNode eject = method(merged, "ejectItems"), suck = method(merged, "suckInItems");
		assertEquals(1, calls(eject, HOPPER, "getContainerOrHandlerAt"));
		assertEquals(0, calls(eject, HOPPER, "getAttachedContainer"));
		assertEquals(1, calls(suck, HOPPER, "getSourceContainerOrHandler"));
		assertEquals(0, calls(suck, HOPPER, "getSourceContainer"));
		Assumptions.assumeTrue(Files.isRegularFile(FABRIC_API), "the pack's fabric-api is absent");
		byte[] mixin = nested(FABRIC_API, "fabric-transfer-api-v1", "net/fabricmc/fabric/mixin/transfer/HopperBlockEntityMixin.class");
		assertNotNull(mixin);
		List<String> targets = new ArrayList<>();
		for (MethodNode handler : node(mixin).methods) {
			if (handler.visibleAnnotations == null) continue;
			for (AnnotationNode a : handler.visibleAnnotations) collectTargets(a, targets);
		}
		assertTrue(targets.stream().anyMatch(t -> t.contains(";getAttachedContainer(")), targets.toString());
		assertTrue(targets.stream().anyMatch(t -> t.contains(";getSourceContainer(")), targets.toString());
	}

	@Test void bothFoundNothingBranchesAskFabric() throws Exception {
		byte[] original = NativeCoremodParityTest.read(MERGED, HOPPER);
		ClassNode before = node(original);
		byte[] out = new HopperFabricStorageInjector().transform(HopperFabricStorageInjector.HOPPER, original, null);
		assertNotSame(original, out);
		ClassNode after = node(out);

		MethodNode eject = method(after, "ejectItems");
		MethodInsnNode insert = call(eject, HopperFabricStorageInjector.RUNTIME, "insert");
		assertEquals(Opcodes.IRETURN, insert.getNext().getOpcode(), "insert's answer is the found-nothing return");
		assertEquals(Opcodes.ALOAD, insert.getPrevious().getOpcode());
		assertEquals(size(method(before, "ejectItems")) + 3, size(eject), "iconst_0 became four instructions");
		assertEquals(method(before, "ejectItems").maxLocals, eject.maxLocals);
		assertEquals(count(method(before, "ejectItems"), Opcodes.IRETURN), count(eject, Opcodes.IRETURN));

		MethodNode suck = method(after, "suckInItems");
		MethodInsnNode extract = call(suck, HopperFabricStorageInjector.RUNTIME, "extract");
		assertEquals(count(method(before, "suckInItems"), Opcodes.IRETURN) + 1, count(suck, Opcodes.IRETURN));
		assertEquals(method(before, "suckInItems").maxLocals, suck.maxLocals);
		for (String unchanged : List.of("getItemsAtAndAbove", "getSourceContainerOrHandler", "extractHook", "isGridAligned")) {
			assertEquals(callsNamed(method(before, "suckInItems"), unchanged), callsNamed(suck, unchanged), unchanged);
		}
		assertEquals(getstatics(method(before, "suckInItems"), "DOWN"), getstatics(suck, "DOWN"), "lithium's FIELD DOWN anchor count");
		new Analyzer<>(new BasicVerifier()).analyze(HOPPER, eject);
		new Analyzer<>(new BasicVerifier()).analyze(HOPPER, suck);

		// The new branch target's frame is the handler check's target frame with the answer on the stack.
		ClassNode expanded = new ClassNode();
		new ClassReader(out).accept(expanded, ClassReader.EXPAND_FRAMES);
		MethodNode suckExpanded = method(expanded, "suckInItems");
		MethodInsnNode extractExpanded = call(suckExpanded, HopperFabricStorageInjector.RUNTIME, "extract");
		JumpInsnNode iflt = (JumpInsnNode) extractExpanded.getNext().getNext();
		assertEquals(Opcodes.IFLT, iflt.getOpcode());
		FrameNode carry = frameAfter(iflt.label);
		FrameNode handlerTarget = frameBefore(extractExpanded);
		assertEquals(List.of(Opcodes.INTEGER), carry.stack);
		assertEquals(handlerTarget.local, carry.local);
		assertTrue(handlerTarget.stack.isEmpty());

		assertSame(out, new HopperFabricStorageInjector().transform(HopperFabricStorageInjector.HOPPER, out, null), "a second pass changes nothing");
	}

	@Test void neoForgesOwnBodyGetsTheSameEditAndMinecraftForgesIsLeftAlone() throws Exception {
		byte[] neo = NativeCoremodParityTest.read(NEO, HOPPER);
		assertNotSame(neo, new HopperFabricStorageInjector().transform(HopperFabricStorageInjector.HOPPER, neo, null));
		byte[] forge = NativeCoremodParityTest.read(FORGE, HOPPER);
		assertSame(forge, new HopperFabricStorageInjector().transform(HopperFabricStorageInjector.HOPPER, forge, null),
				"MinecraftForge's body calls getAttachedContainer, where Fabric's own mixin applies");
	}

	@Test void theSwitchLeavesTheHopperAlone() throws Exception {
		System.setProperty(HopperFabricStorageInjector.PROPERTY, "off");
		byte[] original = NativeCoremodParityTest.read(MERGED, HOPPER);
		assertSame(original, new HopperFabricStorageInjector().transform(HopperFabricStorageInjector.HOPPER, original, null));
	}

	/** Both or neither: a drifted suckInItems leaves ejectItems alone too. */
	@Test void bothOrNeither() throws Exception {
		ClassNode drifted = node(NativeCoremodParityTest.read(MERGED, HOPPER));
		MethodNode suck = method(drifted, "suckInItems");
		for (AbstractInsnNode insn : suck.instructions.toArray()) {
			if (insn instanceof MethodInsnNode c && c.name.equals("itemHandler")) {
				suck.instructions.insertBefore(insn, new InsnNode(Opcodes.NOP));
				AbstractInsnNode jump = insn.getNext();
				while (jump.getOpcode() < 0) jump = jump.getNext();
				suck.instructions.insertBefore(jump, new InsnNode(Opcodes.DUP));
				suck.instructions.insertBefore(jump, new InsnNode(Opcodes.POP));
			}
		}
		ClassWriter writer = new ClassWriter(0);
		drifted.accept(writer);
		byte[] bytes = writer.toByteArray();
		assertSame(bytes, new HopperFabricStorageInjector().transform(HopperFabricStorageInjector.HOPPER, bytes, null));
	}

	private static FrameNode frameAfter(LabelNode label) {
		for (AbstractInsnNode i = label; i != null; i = i.getNext()) if (i instanceof FrameNode f) return f;
		throw new AssertionError("no frame");
	}

	private static FrameNode frameBefore(AbstractInsnNode insn) {
		for (AbstractInsnNode i = insn; i != null; i = i.getPrevious()) if (i instanceof FrameNode f) return f;
		throw new AssertionError("no frame");
	}

	private static MethodNode method(ClassNode node, String name) {
		return node.methods.stream().filter(m -> m.name.equals(name)).findFirst().orElseThrow();
	}

	private static int calls(MethodNode m, String owner, String name) {
		return (int) Arrays.stream(m.instructions.toArray()).filter(i -> i instanceof MethodInsnNode c && c.owner.equals(owner) && c.name.equals(name)).count();
	}

	private static int callsNamed(MethodNode m, String name) {
		return (int) Arrays.stream(m.instructions.toArray()).filter(i -> i instanceof MethodInsnNode c && c.name.equals(name)).count();
	}

	private static MethodInsnNode call(MethodNode m, String owner, String name) {
		return Arrays.stream(m.instructions.toArray()).filter(i -> i instanceof MethodInsnNode c && c.owner.equals(owner) && c.name.equals(name))
				.map(MethodInsnNode.class::cast).findFirst().orElseThrow(() -> new AssertionError(name));
	}

	private static int getstatics(MethodNode m, String name) {
		return (int) Arrays.stream(m.instructions.toArray()).filter(i -> i instanceof FieldInsnNode f && f.getOpcode() == Opcodes.GETSTATIC && f.name.equals(name)).count();
	}

	private static int count(MethodNode m, int opcode) {
		return (int) Arrays.stream(m.instructions.toArray()).filter(i -> i.getOpcode() == opcode).count();
	}

	private static int size(MethodNode m) {
		return (int) Arrays.stream(m.instructions.toArray()).filter(i -> i.getOpcode() >= 0).count();
	}

	private static void collectTargets(AnnotationNode a, List<String> out) {
		if (a.values == null) return;
		for (int i = 0; i + 1 < a.values.size(); i += 2) {
			Object v = a.values.get(i + 1);
			if ("target".equals(a.values.get(i)) && v instanceof String s) out.add(s);
			if (v instanceof AnnotationNode nested) collectTargets(nested, out);
			if (v instanceof List<?> list) for (Object o : list) if (o instanceof AnnotationNode nested) collectTargets(nested, out);
		}
	}

	private static byte[] nested(Path outer, String module, String entry) throws Exception {
		try (ZipFile zip = new ZipFile(outer.toFile())) {
			for (ZipEntry jar : zip.stream().toList()) {
				if (!jar.getName().startsWith("META-INF/jars/" + module)) continue;
				Path tmp = Files.createTempFile("forbric-nested", ".jar");
				try (InputStream in = zip.getInputStream(jar)) { Files.write(tmp, in.readAllBytes()); }
				try (ZipFile inner = new ZipFile(tmp.toFile())) {
					ZipEntry found = inner.getEntry(entry);
					if (found != null) try (InputStream in = inner.getInputStream(found)) { return in.readAllBytes(); }
				} finally {
					Files.deleteIfExists(tmp);
				}
			}
		}
		return null;
	}

	private static ClassNode node(byte[] bytes) {
		ClassNode node = new ClassNode();
		new ClassReader(bytes).accept(node, 0);
		return node;
	}
}
