package net.forbric.kernel.transform;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.BasicVerifier;

/** NeoForge's furnace tick against MinecraftForge's instance methods, on the real classes, and the census that found it. */
@ResourceLock("system-properties")
class FurnaceTickCallsInjectorTest {
	private static final Path STAGED = Path.of(System.getProperty("forbric.stagedRoot", "../forbric-loader/run"));
	private static final Path MERGED = STAGED.resolve("merged-base/patched-mc-merged-26.2.jar");
	private static final Path NEO = STAGED.resolve("neoforge-patched/patched-mc-neoforge-26.2.jar");
	private static final Path FORGE = STAGED.resolve("forge-patched/patched-mc-forge-26.2.jar");
	private static final String FURNACE = "net/minecraft/world/level/block/entity/AbstractFurnaceBlockEntity";

	@AfterEach void reset() { System.clearProperty(FurnaceTickCallsInjector.PROPERTY); }

	@Test void theTickCallsEachInstanceMethodOnTheFurnaceItTicks() throws Exception {
		byte[] original = NativeCoremodParityTest.read(MERGED, FURNACE);
		assertEquals(List.of("canBurn", "consumeFuel", "burn"), mismatches(node(original)).stream().map(c -> c.name).toList(),
				"premise: NeoForge's tick calls MinecraftForge's three instance methods as static");
		byte[] out = new FurnaceTickCallsInjector().transform(FurnaceTickCallsInjector.FURNACE, original, null);
		ClassNode furnace = node(out);
		assertTrue(mismatches(furnace).isEmpty());
		MethodNode tick = method(furnace, "serverTick");
		for (String name : List.of("canBurn", "consumeFuel", "burn")) {
			MethodInsnNode call = Arrays.stream(tick.instructions.toArray()).filter(i -> i instanceof MethodInsnNode c
					&& c.name.equals("forbric$" + name)).map(MethodInsnNode.class::cast).findFirst().orElseThrow(() -> new AssertionError(name));
			assertTrue(call.getPrevious() instanceof VarInsnNode load && load.var == 3 && load.getOpcode() == Opcodes.ALOAD,
					"the ticked furnace (serverTick's blockEntity) is pushed as the bridge's last argument");
			MethodNode bridge = method(furnace, "forbric$" + name);
			assertTrue(Arrays.stream(bridge.instructions.toArray()).anyMatch(i -> i instanceof MethodInsnNode c
					&& c.getOpcode() == Opcodes.INVOKEVIRTUAL && c.name.equals(name)), "and the bridge calls it virtually, so an override wins");
			new Analyzer<>(new BasicVerifier()).analyze(FURNACE, bridge);
		}
		new Analyzer<>(new BasicVerifier()).analyze(FURNACE, tick);
		assertSame(out, new FurnaceTickCallsInjector().transform(FurnaceTickCallsInjector.FURNACE, out, null), "a second pass changes nothing");
	}

	@Test void eitherCarriersOwnFurnaceIsLeftAlone() throws Exception {
		for (Path jar : List.of(NEO, FORGE)) {
			byte[] own = NativeCoremodParityTest.read(jar, FURNACE);
			assertSame(own, new FurnaceTickCallsInjector().transform(FurnaceTickCallsInjector.FURNACE, own, null), jar.toString());
		}
	}

	@Test void theSwitchLeavesTheFurnaceAlone() throws Exception {
		System.setProperty(FurnaceTickCallsInjector.PROPERTY, "off");
		byte[] original = NativeCoremodParityTest.read(MERGED, FURNACE);
		assertSame(original, new FurnaceTickCallsInjector().transform(FurnaceTickCallsInjector.FURNACE, original, null));
	}

	/**
	 * Every call in the merged base whose static-ness disagrees with the method it names in its own class. Each carrier
	 * jar alone has none; the merged base has exactly the furnace's three, which this transformer bridges. A new one
	 * fails here instead of in a player's world.
	 */
	@Test void theFurnaceIsTheMergedBasesOnlyStaticInstanceMismatch() throws Exception {
		Assumptions.assumeTrue(Files.isRegularFile(MERGED), "actual game required");
		Map<String, ClassNode> classes = new HashMap<>();
		try (ZipFile zip = new ZipFile(MERGED.toFile())) {
			for (ZipEntry entry : Collections.list(zip.entries())) {
				if (!entry.getName().endsWith(".class")) continue;
				ClassNode node = new ClassNode();
				new ClassReader(zip.getInputStream(entry).readAllBytes()).accept(node, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
				classes.put(node.name, node);
			}
		}
		List<String> found = new ArrayList<>();
		for (ClassNode owner : classes.values()) {
			for (MethodNode method : owner.methods) {
				for (AbstractInsnNode insn : method.instructions) {
					if (!(insn instanceof MethodInsnNode call) || call.getOpcode() == Opcodes.INVOKESPECIAL || call.getOpcode() == Opcodes.INVOKEDYNAMIC) continue;
					ClassNode target = classes.get(call.owner);
					if (target == null) continue;
					MethodNode declared = target.methods.stream().filter(m -> m.name.equals(call.name) && m.desc.equals(call.desc)).findFirst().orElse(null);
					if (declared == null) continue;
					boolean isStatic = (declared.access & Opcodes.ACC_STATIC) != 0;
					if (isStatic != (call.getOpcode() == Opcodes.INVOKESTATIC)) found.add(owner.name + "." + method.name + " -> " + call.owner + "." + call.name);
				}
			}
		}
		Collections.sort(found);
		assertEquals(List.of(FURNACE + ".serverTick -> " + FURNACE + ".burn", FURNACE + ".serverTick -> " + FURNACE + ".canBurn",
				FURNACE + ".serverTick -> " + FURNACE + ".consumeFuel"), found);
	}

	private static List<MethodInsnNode> mismatches(ClassNode furnace) {
		List<MethodInsnNode> out = new ArrayList<>();
		for (MethodNode method : furnace.methods) for (AbstractInsnNode insn : method.instructions) {
			if (insn instanceof MethodInsnNode call && call.getOpcode() == Opcodes.INVOKESTATIC && call.owner.equals(furnace.name)) {
				furnace.methods.stream().filter(m -> m.name.equals(call.name) && m.desc.equals(call.desc) && (m.access & Opcodes.ACC_STATIC) == 0)
						.findFirst().ifPresent(m -> out.add(call));
			}
		}
		return out;
	}

	private static ClassNode node(byte[] bytes) {
		ClassNode node = new ClassNode();
		new ClassReader(bytes).accept(node, 0);
		return node;
	}

	private static MethodNode method(ClassNode node, String name) {
		return node.methods.stream().filter(m -> m.name.equals(name)).findFirst().orElseThrow(() -> new AssertionError(name));
	}
}
