package net.forbric.kernel.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import net.forbric.kernel.TestFixtures;
import net.forbric.kernel.TestFixtures.Fixture;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;

/**
 * The three composed providers must do, instruction for instruction, what the carrier's own per-root providers
 * do: fire that root's AttachCapabilitiesEvent bus and ask it for listeners. Nothing invented.
 */
class KernelForgeCapabilitiesShapeTest {
	private static final Path RUNTIME = Path.of(System.getProperty("forbric.testRuntimeClasses", "build/classes/java/runtime"));
	private static final Path FORGE = TestFixtures.stagedRoot().resolve(TestFixtures.FORGE_CARRIER_JAR);
	private static final Map<String, String> TWINS = Map.of(
			"KernelForgeCapabilities$Entities", "net/minecraftforge/common/capabilities/CapabilityProvider$Entities",
			"KernelForgeCapabilities$BlockEntities", "net/minecraftforge/common/capabilities/CapabilityProvider$BlockEntities",
			"KernelForgeCapabilities$Levels", "net/minecraftforge/common/capabilities/CapabilityProvider$Levels");

	@Test
	void eachComposedProviderFiresAndAsksExactlyWhatTheCarriersOwnDoes() throws Exception {
		TestFixtures.requireFiles(Fixture.STAGED, "staged Forge carrier absent", FORGE);
		TestFixtures.require(Fixture.GAME_SIDE, Files.isDirectory(RUNTIME.resolve("net/forbric/kernel/runtime")),
				"runtime not compiled: " + RUNTIME);
		for (Map.Entry<String, String> twin : TWINS.entrySet()) {
			Path compiled = RUNTIME.resolve("net/forbric/kernel/runtime/" + twin.getKey() + ".class");
			assertTrue(Files.isRegularFile(compiled), "the compiled game side has no " + compiled);
			ClassNode ours = parse(Files.readAllBytes(compiled));
			ClassNode theirs = parse(bytesOf(twin.getValue()));
			assertEquals("net/minecraftforge/common/capabilities/CapabilityProvider$AsField", superOf(ours),
					twin.getKey() + " must be an AsField, the shape Forge composes into LevelChunk");
			assertEquals(shape(theirs, "fireAttachCapabilitiesEvent"), shape(ours, "fireAttachCapabilitiesEvent"),
					twin.getKey() + ".fireAttachCapabilitiesEvent must be the carrier's own sequence");
			assertEquals(shape(theirs, "shouldFireAttachCapabilitiesEvent"), shape(ours, "shouldFireAttachCapabilitiesEvent"),
					twin.getKey() + ".shouldFireAttachCapabilitiesEvent must be the carrier's own sequence");
		}
	}

	private static String superOf(ClassNode node) {
		// Composed sits between: Entities -> Composed -> AsField. Walk one level through the compiled output.
		if (node.superName.endsWith("KernelForgeCapabilities$Composed")) {
			try {
				return parse(Files.readAllBytes(RUNTIME.resolve("net/forbric/kernel/runtime/KernelForgeCapabilities$Composed.class"))).superName;
			} catch (Exception e) {
				throw new AssertionError(e);
			}
		}
		return node.superName;
	}

	/** The opcode/owner/name sequence of the non-bridge method with that name: GETSTATIC BUS, NEW, INVOKESPECIAL, fire, CHECKCAST. */
	private static List<String> shape(ClassNode node, String name) {
		MethodNode chosen = null;
		for (MethodNode m : node.methods) {
			if (!name.equals(m.name) || (m.access & org.objectweb.asm.Opcodes.ACC_BRIDGE) != 0) continue;
			if (chosen == null || m.instructions.size() > chosen.instructions.size()) chosen = m;
		}
		assertNotNull(chosen, node.name + " lacks " + name);
		List<String> out = new ArrayList<>();
		for (AbstractInsnNode insn : chosen.instructions) {
			if (insn instanceof FieldInsnNode f) out.add("F" + f.getOpcode() + " " + f.owner + "." + f.name);
			// Only NEW is part of the shape. The carrier's method takes the root type directly; ours takes
			// ICapabilityProviderImpl and CHECKCASTs it to the root first — a cast, not a different sequence.
			else if (insn instanceof TypeInsnNode t && t.getOpcode() == org.objectweb.asm.Opcodes.NEW) out.add("NEW " + t.desc);
			else if (insn instanceof MethodInsnNode m) out.add("M" + m.getOpcode() + " " + m.owner + "." + m.name);
		}
		// The carrier's version has a CHECKCAST of the owner argument first (its parameter is typed B); ours
		// casts from ICapabilityProviderImpl to the root — same opcode, so the sequence compares equal.
		return out;
	}

	private static ClassNode parse(byte[] bytes) {
		ClassNode node = new ClassNode();
		new ClassReader(bytes).accept(node, 0);
		return node;
	}

	private static byte[] bytesOf(String internal) throws Exception {
		try (ZipFile zip = new ZipFile(FORGE.toFile())) {
			ZipEntry entry = zip.getEntry(internal + ".class");
			assertNotNull(entry, internal);
			try (InputStream in = zip.getInputStream(entry)) {
				return in.readAllBytes();
			}
		}
	}
}
