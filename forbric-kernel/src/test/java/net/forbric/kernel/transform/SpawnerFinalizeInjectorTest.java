/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.transform;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Consumer;
import java.util.zip.ZipFile;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.BasicVerifier;
import org.objectweb.asm.util.TraceClassVisitor;

import net.fabricmc.api.EnvType;
import net.forbric.api.CompatibilityFindings;

class SpawnerFinalizeInjectorTest {
	private final SpawnerFinalizeInjector injector = new SpawnerFinalizeInjector();
	private final TransformContext context = new TransformContext(EnvType.SERVER, false, "mojmap");
	@BeforeEach @AfterEach void clearFindings() { CompatibilityFindings.reset(); }

	@Test void explicitOffSwitchLeavesTheLegacyCallerIntactAndMakesNoRequiredAnchorPromise() throws Exception {
		String property = "forbric.spawnerFinalize";
		String previous = System.getProperty(property);
		try {
			byte[] raw = staged("merged-base/patched-mc-merged-26.2.jar", "net/minecraft/world/level/BaseSpawner");
			byte[] legacy = new ForbricMergedBaseCompatTransformer().transform(SpawnerFinalizeInjector.TARGET, raw, context);
			System.setProperty(property, "off");
			assertSame(legacy, injector.transform(SpawnerFinalizeInjector.TARGET, legacy, context));
			assertEquals(SpawnerFinalizeInjector.OLD_DESC, hook(host(parse(legacy))).desc);
			assertTrue(injector.anchors().anchors().isEmpty());
			assertFalse(injector.anchors().isUndeclared());
			assertTrue(CompatibilityFindings.all().isEmpty(), "explicit negative-control disable is not an unknown caller");
			System.setProperty(property, "on");
			assertNotSame(legacy, injector.transform(SpawnerFinalizeInjector.TARGET, legacy, context));
		} finally {
			if (previous == null) System.clearProperty(property); else System.setProperty(property, previous);
		}
	}

	@Test void realMergedCallerSuppliesTheSameInputThatLoadedItsMobAfterTheLegacyRepair() throws Exception {
		byte[] raw = staged("merged-base/patched-mc-merged-26.2.jar", "net/minecraft/world/level/BaseSpawner");
		byte[] legacy = new ForbricMergedBaseCompatTransformer().transform(SpawnerFinalizeInjector.TARGET, raw, context);
		assertEquals(SpawnerFinalizeInjector.RUNTIME, hook(host(parse(legacy))).owner);
		verifyExchange(legacy);
	}

	@Test void nativeNeoCallerAlsoWorksAndItsEventResultIsNotUsedAsASpawnVeto() throws Exception {
		byte[] nativeNeo = staged("neoforge-patched/patched-mc-neoforge-26.2.jar", "net/minecraft/world/level/BaseSpawner");
		verifyExchange(nativeNeo);
		for (String jar : List.of("forge-patched/patched-mc-forge-26.2.jar", "neoforge-patched/patched-mc-neoforge-26.2.jar")) {
			MethodNode tick = host(parse(staged(jar, "net/minecraft/world/level/BaseSpawner")));
			assertTrue(calls(tick).stream().anyMatch(c -> c.name.equals("tryAddFreshEntityWithPassengers")));
			assertFalse(calls(tick).stream().anyMatch(c -> c.name.equals("isCanceled")),
					"native caller does not promote event cancellation into a direct world-insertion veto");
		}
	}

	@Test void wrongEntityOriginOverwrittenInputAndAmbiguousReachingStoresAreRejected() throws Exception {
		for (Consumer<ClassNode> mutation : List.<Consumer<ClassNode>>of(
				n -> create(host(n)).owner = "example/OtherInput",
				n -> calls(host(n)).stream().filter(c -> c.name.equals("loadEntityRecursive")).findFirst().orElseThrow().name = "loadDifferentEntity",
				n -> {
					MethodNode m = host(n); int slot = ((VarInsnNode) nextReal(create(m))).var;
					InsnList overwrite = new InsnList(); overwrite.add(new InsnNode(Opcodes.ACONST_NULL)); overwrite.add(new VarInsnNode(Opcodes.ASTORE, slot));
					m.instructions.insertBefore(hook(m), overwrite); m.maxStack++;
				},
				n -> {
					MethodNode m = host(n); int slot = ((VarInsnNode) nextReal(create(m))).var; LabelNode join = new LabelNode();
					InsnList conditional = new InsnList(); conditional.add(new InsnNode(Opcodes.ICONST_0));
					conditional.add(new JumpInsnNode(Opcodes.IFEQ, join)); conditional.add(new InsnNode(Opcodes.ACONST_NULL));
					conditional.add(new VarInsnNode(Opcodes.ASTORE, slot)); conditional.add(join);
					m.instructions.insertBefore(hook(m), conditional); m.maxStack++;
				})) {
			ClassNode n = parse(staged("merged-base/patched-mc-merged-26.2.jar", "net/minecraft/world/level/BaseSpawner"));
			mutation.accept(n); byte[] bytes = write(n);
			assertSame(bytes, injector.transform(SpawnerFinalizeInjector.TARGET, bytes, context));
			assertTrue(CompatibilityFindings.all().stream().anyMatch(f -> f.detail().contains("no unique live ValueInput")));
			CompatibilityFindings.reset();
		}
	}

	@Test void aCallerThatAlreadyCarriesMinecraftForgesFinalizeHookIsNotRedirectedASecondTime() throws Exception {
		ClassNode restored = parse(staged("merged-base/patched-mc-merged-26.2.jar", "net/minecraft/world/level/BaseSpawner"));
		MethodNode tick = host(restored);
		InsnList forge = new InsnList();
		for (int i = 0; i < 5; i++) forge.add(new InsnNode(Opcodes.ACONST_NULL));
		forge.add(new VarInsnNode(Opcodes.ALOAD, 0));
		forge.add(new MethodInsnNode(Opcodes.INVOKESTATIC, SpawnerFinalizeInjector.FORGE, SpawnerFinalizeInjector.FORGE_HOOK,
				SpawnerFinalizeInjector.FORGE_DESC, false));
		forge.add(new InsnNode(Opcodes.POP));
		tick.instructions.insert(nextReal(hook(tick)), forge); tick.maxStack += 6;
		byte[] bytes = write(restored);
		new Analyzer<>(new BasicVerifier()).analyze(restored.name, host(parse(bytes)));

		byte[] legacy = new ForbricMergedBaseCompatTransformer().transform(SpawnerFinalizeInjector.TARGET, bytes, context);
		assertEquals(SpawnerFinalizeInjector.NEO, hook(host(parse(legacy))).owner,
				"the legacy repair must not route a caller that already posts MinecraftForge's event itself");
		assertSame(legacy, injector.transform(SpawnerFinalizeInjector.TARGET, legacy, context));
		MethodNode after = host(parse(legacy));
		assertEquals(SpawnerFinalizeInjector.OLD_DESC, hook(after).desc);
		assertEquals(1, calls(after).stream().filter(c -> c.owner.equals(SpawnerFinalizeInjector.FORGE)).count());
		assertTrue(calls(after).stream().noneMatch(c -> c.owner.equals(SpawnerFinalizeInjector.RUNTIME)));
		var findings = CompatibilityFindings.all(); assertEquals(1, findings.size());
		assertEquals("spawner-finalize-direct-composition", findings.getFirst().id());
		assertEquals(net.forbric.api.CompatibilityFinding.Confidence.SUSPECTED, findings.getFirst().confidence());
		assertFalse(findings.getFirst().required());

		// Negative control: the same caller without the restored call is still routed through the kernel.
		CompatibilityFindings.reset();
		byte[] plain = staged("merged-base/patched-mc-merged-26.2.jar", "net/minecraft/world/level/BaseSpawner");
		byte[] routed = injector.transform(SpawnerFinalizeInjector.TARGET,
				new ForbricMergedBaseCompatTransformer().transform(SpawnerFinalizeInjector.TARGET, plain, context), context);
		assertEquals(SpawnerFinalizeInjector.RUNTIME, hook(host(parse(routed))).owner);
		assertTrue(CompatibilityFindings.all().isEmpty());
	}

	@Test void unknownSignaturesOrPartiallyChangedCallersAreNotGuessed() throws Exception {
		for (Consumer<ClassNode> mutation : List.<Consumer<ClassNode>>of(
				n -> hook(host(n)).desc = "()V",
				n -> hook(host(n)).itf = true,
				n -> host(n).access |= Opcodes.ACC_STATIC,
				n -> host(n).instructions.insert(hook(host(n)), new InsnNode(Opcodes.NOP)),
				n -> host(n).instructions.insert(hook(host(n)).clone(null)))) {
			ClassNode n = parse(staged("merged-base/patched-mc-merged-26.2.jar", "net/minecraft/world/level/BaseSpawner"));
			mutation.accept(n); byte[] bytes = write(n); assertSame(bytes, injector.transform(SpawnerFinalizeInjector.TARGET, bytes, context));
		}
	}

	private void verifyExchange(byte[] original) throws Exception {
		ClassNode before = parse(original); MethodNode beforeHost = host(before); MethodInsnNode beforeCall = hook(beforeHost);
		int actualInputSlot = ((VarInsnNode) nextReal(create(beforeHost))).var;
		byte[] changed = injector.transform(SpawnerFinalizeInjector.TARGET, original, context); assertNotSame(original, changed);
		ClassNode after = parse(changed); MethodNode afterHost = host(after); MethodInsnNode afterCall = hook(afterHost);
		assertEquals(SpawnerFinalizeInjector.RUNTIME, afterCall.owner); assertEquals(SpawnerFinalizeInjector.NEW_DESC, afterCall.desc);
		assertInstanceOf(VarInsnNode.class, previousReal(afterCall)); VarInsnNode input = (VarInsnNode) previousReal(afterCall);
		assertEquals(Opcodes.ALOAD, input.getOpcode()); assertEquals(actualInputSlot, input.var);
		assertEquals(beforeHost.maxLocals, afterHost.maxLocals); new Analyzer<>(new BasicVerifier()).analyze(after.name, afterHost);
		assertEquals(0, calls(afterHost).stream().filter(c -> c.name.equals("finalizeSpawn")).count(), "the caller must not add a second finalizer");
		assertSame(changed, injector.transform(SpawnerFinalizeInjector.TARGET, changed, context));
		afterHost.instructions.remove(input); afterCall.owner = beforeCall.owner; afterCall.desc = beforeCall.desc;
		afterHost.maxStack = beforeHost.maxStack;
		assertEquals(trace(before), trace(after), "only one parameter load and call descriptor may change; the native continuation stays intact");
	}

	private static MethodNode host(ClassNode n) { return n.methods.stream().filter(m -> m.name.equals("serverTick")).findFirst().orElseThrow(); }
	private static List<MethodInsnNode> calls(MethodNode m) { return java.util.Arrays.stream(m.instructions.toArray()).filter(i -> i instanceof MethodInsnNode).map(i -> (MethodInsnNode) i).toList(); }
	private static MethodInsnNode hook(MethodNode m) { return calls(m).stream().filter(c -> c.name.equals("finalizeMobSpawnSpawner")).findFirst().orElseThrow(); }
	private static MethodInsnNode create(MethodNode m) { return calls(m).stream().filter(c -> c.owner.equals("net/minecraft/world/level/storage/TagValueInput") && c.name.equals("create")).findFirst().orElseThrow(); }
	private static AbstractInsnNode nextReal(AbstractInsnNode n) { do { n = n.getNext(); } while (n != null && n.getOpcode() < 0); return n; }
	private static AbstractInsnNode previousReal(AbstractInsnNode n) { do { n = n.getPrevious(); } while (n != null && n.getOpcode() < 0); return n; }
	private static ClassNode parse(byte[] bytes) { ClassNode n = new ClassNode(); new ClassReader(bytes).accept(n, 0); return n; }
	private static byte[] write(ClassNode n) { ClassWriter w = new ClassWriter(0); n.accept(w); return w.toByteArray(); }
	private static String trace(ClassNode n) { StringWriter out = new StringWriter(); n.accept(new TraceClassVisitor(new PrintWriter(out))); return out.toString(); }
	private static byte[] staged(String jar, String entry) throws Exception {
		String old = System.getenv("FORBRIC_OLD"); Path run = old == null ? Path.of("..", "forbric-loader", "run") : Path.of(old, "run");
		Path path = run.resolve(jar); assumeTrue(Files.isRegularFile(path), "staged artifact absent: " + path);
		try (ZipFile zip = new ZipFile(path.toFile())) { return zip.getInputStream(zip.getEntry(entry + ".class")).readAllBytes(); }
	}
}
