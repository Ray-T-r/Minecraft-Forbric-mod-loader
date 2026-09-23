/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.runtime;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.nio.file.Files;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.UnaryOperator;

import net.forbric.api.CompatibilityFinding;
import net.forbric.api.CompatibilityFindings;
import net.forbric.kernel.boot.KernelRuntimeClasses;
import net.forbric.kernel.transform.PortalSpawnInjector;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;

/** Executes the actual native onPlace body with a guarded second direct hook, not a hand-written wrapper. */
class PortalDirectRestorationTest {
	private static final String TARGET = "net.minecraft.world.level.block.BaseFireBlock";
	private static final String NEO = "net/neoforged/neoforge/event/EventHooks";
	private static final String FORGE = "net/minecraftforge/event/ForgeEventFactory";
	@TempDir Path temporary;
	@BeforeEach @AfterEach void reset() { CompatibilityFindings.reset(); System.clearProperty(PortalSpawnInjector.PROPERTY); }

	@Test void everyInjectedPortalEntrypointResolvesOnTheCompiledGameRuntime() throws Exception {
		String binary = "net.forbric.kernel.runtime.KernelPortalSpawn";
		assertEquals(KernelRuntimeClasses.Origin.COMPILED, KernelRuntimeClasses.all().get(binary));
		// KernelRuntimeClasses.Call is the reflective, parent-typed boot seam. These injected signatures
		// instead name game types, so resolve their exact emitted descriptors directly against runtime bytes.
		Path runtime = Path.of(System.getProperty("forbric.test.runtimeClasses", "build/classes/java/runtime"));
		byte[] compiled = Files.readAllBytes(runtime.resolve(binary.replace('.', '/') + ".class"));
		byte[] nativeCaller = ForgeSpawnFixture.staged("merged-base/patched-mc-merged-26.2.jar", TARGET);
		for (byte[] caller : List.of(adapt(nativeCaller), adapt(write(restoredCaller())))) {
			ClassNode definition = new ClassNode(); new ClassReader(compiled).accept(definition, 0);
			MethodInsnNode injected = injectedCall(caller);
			assertPortalCallResolves(injected, definition);
			for (int mutation = 0; mutation < 4; mutation++) {
				ClassNode broken = new ClassNode(); new ClassReader(compiled).accept(broken, 0);
				MethodNode method = broken.methods.stream().filter(m -> m.name.equals(injected.name) && m.desc.equals(injected.desc)).findFirst().orElseThrow();
				if (mutation == 0) broken.methods.remove(method);
				if (mutation == 1) method.desc = "()Ljava/util/Optional;";
				if (mutation == 2) method.access &= ~Opcodes.ACC_STATIC;
				if (mutation == 3) method.access &= ~Opcodes.ACC_PUBLIC;
				assertThrows(AssertionError.class, () -> assertPortalCallResolves(injected, broken));
			}
		}
	}

	private static MethodInsnNode injectedCall(byte[] caller) {
		ClassNode node = new ClassNode(); new ClassReader(caller).accept(node, 0);
		List<MethodInsnNode> calls = new java.util.ArrayList<>();
		for (var instruction : host(node).instructions) if (instruction instanceof MethodInsnNode call
				&& call.owner.equals("net/forbric/kernel/runtime/KernelPortalSpawn")) calls.add(call);
		assertEquals(1, calls.size(), "the caller must contain exactly one runtime portal entrypoint");
		return calls.getFirst();
	}

	private static void assertPortalCallResolves(MethodInsnNode call, ClassNode definition) {
		assertEquals(definition.name, call.owner); assertFalse(call.itf); assertEquals(Opcodes.INVOKESTATIC, call.getOpcode());
		assertTrue(definition.methods.stream().anyMatch(m -> m.name.equals(call.name) && m.desc.equals(call.desc)
				&& (m.access & (Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC)) == (Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC)),
				"injected portal entrypoint is not public static with the exact descriptor: " + call.name + call.desc);
	}

	@Test void unadaptedDirectPairReallyDoublePostsAndAdaptedCallerPreservesTheForgeResult() throws Exception {
		byte[] original = write(restoredCaller());
		try (PortalSpawnFixture f = new PortalSpawnFixture(temporary.resolve("before"), false, original)) {
			f.installLegacyBridge(); Object shape = f.shape(); f.place(Optional.of(shape));
			assertEquals(List.of("neo", "forge", "forge"), f.trace(), "the negative control must expose the actual duplicate");
			assertSame(shape, f.get("builtShape"));
		}
		byte[] changed = adapt(original); assertNotSame(original, changed);
		assertSame(changed, adapt(changed), "already scoped callers are idempotent");
		try (PortalSpawnFixture f = new PortalSpawnFixture(temporary.resolve("after"), false, changed)) {
			f.installLegacyBridge(); Object originalShape = f.shape(), neo = f.shape(), forge = f.shape();
			f.set("neoResult", (UnaryOperator<Object>) input -> Optional.of(neo));
			AtomicReference<Object> forgeInput = new AtomicReference<>();
			f.set("forgeResult", (UnaryOperator<Object>) input -> { forgeInput.set(input); return Optional.of(forge); });
			f.place(Optional.of(originalShape));
			assertEquals(List.of("neo", "forge"), f.trace());
			assertEquals(Optional.of(neo), forgeInput.get());
			assertSame(forge, f.get("builtShape"), "the real Optional consumer must use the final replacement");
			assertFalse(f.guarded());
			f.set("neoResult", (UnaryOperator<Object>) input -> input);
			f.set("forgeResult", (UnaryOperator<Object>) input -> input);
			f.directNeo(Optional.of(originalShape));
			assertEquals(List.of("neo", "forge", "neo", "forge"), f.trace(), "independent producers still retain their forward");
		}
		assertTrue(CompatibilityFindings.all().isEmpty(), "a proved repair must not invent a necessary failure");
	}

	@Test void bothCancellationBarriersAndEmptyInputCannotBeRevived() throws Exception {
		byte[] changed = adapt(write(restoredCaller()));
		for (boolean carriers : List.of(false, true)) {
			try (PortalSpawnFixture f = new PortalSpawnFixture(temporary.resolve("veto-" + carriers), carriers, changed)) {
				f.installLegacyBridge(); Object shape = f.shape();
				f.set("forgeResult", (UnaryOperator<Object>) input -> Optional.of(shape));
				f.set("neoCanceled", true); f.place(Optional.of(shape));
				assertNull(f.get("builtShape")); assertEquals(1, f.count("neoCalls")); assertEquals(0, f.count("forgeCalls"));
				f.set("neoCanceled", false); f.set("forgeCanceled", true); f.place(Optional.of(shape));
				assertNull(f.get("builtShape")); assertEquals(2, f.count("neoCalls")); assertEquals(1, f.count("forgeCalls"));
				f.set("forgeCanceled", false); f.place(Optional.empty());
				assertNull(f.get("builtShape")); assertEquals(2, f.count("neoCalls")); assertEquals(1, f.count("forgeCalls"));
				f.place(Optional.of(shape));
				assertSame(shape, f.get("builtShape")); assertEquals(3, f.count("neoCalls")); assertEquals(2, f.count("forgeCalls"));
			}
		}
	}

	@Test void directCallerExceptionsRestoreScopeWithoutCatchingTheNativeForgeFailure() throws Exception {
		try (PortalSpawnFixture f = new PortalSpawnFixture(temporary, false, adapt(write(restoredCaller())))) {
			f.installLegacyBridge(); Object shape = f.shape(); RuntimeException failure = new IllegalStateException("listener");
			f.set("neoFailure", failure);
			assertSame(failure, assertThrows(IllegalStateException.class, () -> f.place(Optional.of(shape))));
			assertFalse(f.guarded()); assertEquals(0, f.count("forgeCalls"));
			f.set("neoFailure", null); f.set("forgeFailure", failure);
			assertSame(failure, assertThrows(IllegalStateException.class, () -> f.place(Optional.of(shape))));
			assertFalse(f.guarded()); assertNull(f.get("builtShape"));
			f.set("forgeFailure", null); f.directNeo(Optional.of(shape));
			assertEquals(2, f.count("forgeCalls"));
		}
	}

	@Test void nestedRestoredCallersPreserveOuterScopeAndDispatchEachFamilyOnce() throws Exception {
		try (PortalSpawnFixture f = new PortalSpawnFixture(temporary, false, adapt(write(restoredCaller())))) {
			f.installLegacyBridge(); Object outer = f.shape(), inner = f.shape();
			f.set("nested", (Runnable) () -> {
				try { assertTrue(f.guarded()); f.place(Optional.of(inner)); assertTrue(f.guarded()); }
				catch (Exception failure) { throw new AssertionError(failure); }
			});
			f.place(Optional.of(outer));
			assertEquals(List.of("neo", "neo", "forge", "forge"), f.trace());
			assertSame(outer, f.get("builtShape")); assertFalse(f.guarded());
		}
	}

	@Test void unknownDualCallShapesRemainUnchangedAndOnlySuspected() throws Exception {
		for (Consumer<MethodNode> mutation : List.<Consumer<MethodNode>>of(
				m -> neoGuard(m).setOpcode(Opcodes.IFNE),
				m -> forgeGuard(m).setOpcode(Opcodes.IFNE),
				m -> ((VarInsnNode) previousCode(forge(m))).var = 1,
				m -> ((VarInsnNode) nextCode(forge(m))).var = 7,
				m -> m.instructions.set(nextCode(forge(m)), new InsnNode(Opcodes.POP)),
				m -> m.instructions.insert(new InsnNode(Opcodes.NOP)),
				m -> { forge(m).owner = NEO; neo(m).owner = FORGE; },
				m -> { MethodInsnNode forge = forge(m); m.instructions.insert(forge, forge.clone(null)); },
				m -> { LabelNode entry = new LabelNode(); m.instructions.insertBefore(previousCode(previousCode(previousCode(forge(m)))), entry); m.instructions.insert(new JumpInsnNode(Opcodes.GOTO, entry)); },
				m -> m.access |= Opcodes.ACC_SYNCHRONIZED)) {
			ClassNode caller = restoredCaller(); mutation.accept(host(caller)); byte[] bytes = write(caller);
			assertSame(bytes, adapt(bytes));
			var findings = CompatibilityFindings.all(); assertEquals(1, findings.size());
			assertEquals(CompatibilityFinding.Confidence.SUSPECTED, findings.getFirst().confidence());
			assertFalse(findings.getFirst().required()); assertFalse(findings.getFirst().confirmedRequired());
			assertTrue(findings.getFirst().detail().contains("remain unchanged")); CompatibilityFindings.reset();
		}
	}

	@Test void offSwitchDoesNotClaimTheDirectCallerHasBeenRepaired() throws Exception {
		byte[] bytes = write(restoredCaller()); System.setProperty(PortalSpawnInjector.PROPERTY, "off");
		assertSame(bytes, adapt(bytes)); assertTrue(CompatibilityFindings.all().isEmpty());
	}

	private static byte[] adapt(byte[] bytes) { return new PortalSpawnInjector().transform(TARGET, bytes, null); }
	private static MethodNode host(ClassNode caller) { return caller.methods.stream().filter(m -> m.name.equals("onPlace")).findFirst().orElseThrow(); }
	private static MethodInsnNode hook(MethodNode host, String owner) {
		for (var instruction : host.instructions) if (instruction instanceof MethodInsnNode call && call.owner.equals(owner) && call.name.equals("onTrySpawnPortal")) return call;
		throw new AssertionError("missing hook " + owner);
	}
	private static MethodInsnNode neo(MethodNode host) { return hook(host, NEO); }
	private static MethodInsnNode forge(MethodNode host) { return hook(host, FORGE); }
	private static JumpInsnNode neoGuard(MethodNode host) { return (JumpInsnNode) nextCode(nextCode(nextCode(nextCode(neo(host))))); }
	private static JumpInsnNode forgeGuard(MethodNode host) { return (JumpInsnNode) nextCode(nextCode(nextCode(nextCode(forge(host))))); }
	private static AbstractInsnNode nextCode(AbstractInsnNode instruction) { do { instruction = instruction.getNext(); } while (instruction != null && instruction.getOpcode() < 0); return instruction; }
	private static AbstractInsnNode previousCode(AbstractInsnNode instruction) { do { instruction = instruction.getPrevious(); } while (instruction != null && instruction.getOpcode() < 0); return instruction; }
	private static byte[] write(ClassNode caller) { ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS); caller.accept(writer); return writer.toByteArray(); }
	private static ClassNode restoredCaller() throws Exception {
		ClassNode caller = new ClassNode(); new ClassReader(ForgeSpawnFixture.staged("merged-base/patched-mc-merged-26.2.jar", TARGET)).accept(caller, 0);
		MethodNode host = host(caller); MethodInsnNode neo = neo(host); JumpInsnNode barrier = neoGuard(host);
		int slot = ((VarInsnNode) nextCode(neo)).var;
		InsnList restored = new InsnList();
		restored.add(new VarInsnNode(Opcodes.ALOAD, 2)); restored.add(new VarInsnNode(Opcodes.ALOAD, 3)); restored.add(new VarInsnNode(Opcodes.ALOAD, slot));
		restored.add(new MethodInsnNode(Opcodes.INVOKESTATIC, FORGE, neo.name, neo.desc, false));
		restored.add(new VarInsnNode(Opcodes.ASTORE, slot)); restored.add(new VarInsnNode(Opcodes.ALOAD, slot));
		restored.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/util/Optional", "isPresent", "()Z", false));
		restored.add(new JumpInsnNode(Opcodes.IFEQ, barrier.label)); host.instructions.insert(barrier, restored);
		return caller;
	}
}
