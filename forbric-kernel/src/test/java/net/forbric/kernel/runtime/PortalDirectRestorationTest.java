/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.runtime;

import net.forbric.kernel.TestFixtures;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.nio.file.Files;
import java.util.ArrayList;
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

/**
 * Executes the actual native onPlace body with a second direct hook, not a hand-written wrapper, in both orders a
 * restoring merge writes: NeoForge's call behind its barrier and then MinecraftForge's behind the same one, or
 * MinecraftForge's statement straight before NeoForge's (a merge that keeps both sides' edits puts MinecraftForge's code
 * first where both inserted at one place). The staged caller is used as it is when it already carries an order;
 * otherwise that order is restored by hand onto the staged NeoForge-only body.
 */
class PortalDirectRestorationTest {
	private static final String TARGET = "net.minecraft.world.level.block.BaseFireBlock";
	private static final String NEO = "net/neoforged/neoforge/event/EventHooks";
	private static final String FORGE = "net/minecraftforge/event/ForgeEventFactory";
	@TempDir Path temporary;
	@BeforeEach @AfterEach void reset() { CompatibilityFindings.reset(); System.clearProperty(PortalSpawnInjector.PROPERTY); }

	/** The two native orders. {@code first} and {@code second} are the fixture's names for the two families' hooks. */
	private enum Order {
		NEO_FIRST_GUARDED("neo", "forge"), FORGE_FIRST("forge", "neo");

		final String first, second;

		Order(String first, String second) { this.first = first; this.second = second; }

		List<String> entrypoints() {
			return this == NEO_FIRST_GUARDED ? List.of("onTrySpawnPortalNeoOnly", "onTrySpawnPortalForgeOnly")
					: List.of("onTrySpawnPortalForgeOnly", "onTrySpawnPortalNeoOnly");
		}
	}

	@Test void everyInjectedPortalEntrypointResolvesOnTheCompiledGameRuntime() throws Exception {
		String binary = "net.forbric.kernel.runtime.KernelPortalSpawn";
		assertEquals(KernelRuntimeClasses.Origin.COMPILED, KernelRuntimeClasses.all().get(binary));
		// KernelRuntimeClasses.Call is the reflective, parent-typed boot seam. These injected signatures
		// instead name game types, so resolve their exact emitted descriptors directly against runtime bytes.
		Path runtime = Path.of(System.getProperty("forbric.test.runtimeClasses", "build/classes/java/runtime"));
		TestFixtures.requireFiles(TestFixtures.Fixture.GAME_SIDE, "compiled game side", runtime.resolve(binary.replace('.', '/') + ".class"));
		byte[] compiled = Files.readAllBytes(runtime.resolve(binary.replace('.', '/') + ".class"));
		byte[] nativeCaller = ForgeSpawnFixture.staged(TestFixtures.BASE_JAR, TARGET);
		List<byte[]> callers = new ArrayList<>(List.of(adapt(nativeCaller)));
		for (Order order : Order.values()) callers.add(adapt(write(restoredCaller(order))));
		for (byte[] caller : callers) {
			for (MethodInsnNode injected : injectedCalls(caller)) {
				ClassNode definition = new ClassNode(); new ClassReader(compiled).accept(definition, 0);
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
		for (Order order : Order.values()) {
			assertEquals(order.entrypoints(), injectedCalls(adapt(write(restoredCaller(order)))).stream().map(call -> call.name).toList(),
					order + ": a restored caller routes each family's own call through its runtime entrypoint, in the base's order");
		}
	}

	private static List<MethodInsnNode> injectedCalls(byte[] caller) {
		ClassNode node = new ClassNode(); new ClassReader(caller).accept(node, 0);
		List<MethodInsnNode> calls = new java.util.ArrayList<>();
		for (var instruction : host(node).instructions) if (instruction instanceof MethodInsnNode call
				&& call.owner.equals("net/forbric/kernel/runtime/KernelPortalSpawn")) calls.add(call);
		assertFalse(calls.isEmpty(), "the caller must contain a runtime portal entrypoint");
		return calls;
	}

	private static void assertPortalCallResolves(MethodInsnNode call, ClassNode definition) {
		assertEquals(definition.name, call.owner); assertFalse(call.itf); assertEquals(Opcodes.INVOKESTATIC, call.getOpcode());
		assertTrue(definition.methods.stream().anyMatch(m -> m.name.equals(call.name) && m.desc.equals(call.desc)
				&& (m.access & (Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC)) == (Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC)),
				"injected portal entrypoint is not public static with the exact descriptor: " + call.name + call.desc);
	}

	@Test void unadaptedDirectPairReallyDoublePostsAndAdaptedCallerPreservesTheForgeResult() throws Exception {
		for (Order order : Order.values()) {
			byte[] original = write(restoredCaller(order));
			try (PortalSpawnFixture f = new PortalSpawnFixture(temporary.resolve("before-" + order), false, original)) {
				f.installLegacyBridge(); Object shape = f.shape(); f.place(Optional.of(shape));
				// NeoForge's post forwards to MinecraftForge through the legacy bridge, beside MinecraftForge's own call.
				assertEquals(order == Order.NEO_FIRST_GUARDED ? List.of("neo", "forge", "forge") : List.of("forge", "neo", "forge"),
						f.trace(), order + ": the negative control must expose the actual duplicate");
				assertSame(shape, f.get("builtShape"));
			}
			byte[] changed = adapt(original); assertNotSame(original, changed, order.toString());
			assertSame(changed, adapt(changed), "already scoped callers are idempotent");
			try (PortalSpawnFixture f = new PortalSpawnFixture(temporary.resolve("after-" + order), false, changed)) {
				f.installLegacyBridge(); Object originalShape = f.shape(), first = f.shape(), second = f.shape();
				f.set(order.first + "Result", (UnaryOperator<Object>) input -> Optional.of(first));
				AtomicReference<Object> secondInput = new AtomicReference<>();
				f.set(order.second + "Result", (UnaryOperator<Object>) input -> { secondInput.set(input); return Optional.of(second); });
				f.place(Optional.of(originalShape));
				assertEquals(List.of(order.first, order.second), f.trace(), order.toString());
				assertEquals(Optional.of(first), secondInput.get(), order + ": the second hook is handed the first's replacement");
				assertSame(second, f.get("builtShape"), order + ": the real Optional consumer must use the final replacement");
				assertFalse(f.guarded());
				f.set("neoResult", (UnaryOperator<Object>) input -> input);
				f.set("forgeResult", (UnaryOperator<Object>) input -> input);
				f.directNeo(Optional.of(originalShape));
				assertEquals(List.of(order.first, order.second, "neo", "forge"), f.trace(), "independent producers still retain their forward");
			}
		}
		assertTrue(CompatibilityFindings.all().isEmpty(), "a proved repair must not invent a necessary failure");
	}

	@Test void bothCancellationBarriersAndEmptyInputCannotBeRevived() throws Exception {
		for (Order order : Order.values()) {
			byte[] changed = adapt(write(restoredCaller(order)));
			String first = order.first, second = order.second;
			for (boolean carriers : List.of(false, true)) {
				try (PortalSpawnFixture f = new PortalSpawnFixture(temporary.resolve("veto-" + order + "-" + carriers), carriers, changed)) {
					f.installLegacyBridge(); Object shape = f.shape();
					f.set(second + "Result", (UnaryOperator<Object>) input -> Optional.of(shape));
					f.set(first + "Canceled", true); f.place(Optional.of(shape));
					assertNull(f.get("builtShape")); assertEquals(1, f.count(first + "Calls")); assertEquals(0, f.count(second + "Calls"));
					f.set(first + "Canceled", false); f.set(second + "Canceled", true); f.place(Optional.of(shape));
					assertNull(f.get("builtShape")); assertEquals(2, f.count(first + "Calls")); assertEquals(1, f.count(second + "Calls"));
					f.set(second + "Canceled", false); f.place(Optional.empty());
					assertNull(f.get("builtShape")); assertEquals(2, f.count(first + "Calls")); assertEquals(1, f.count(second + "Calls"));
					f.place(Optional.of(shape));
					assertSame(shape, f.get("builtShape")); assertEquals(3, f.count(first + "Calls")); assertEquals(2, f.count(second + "Calls"));
				}
			}
		}
	}

	@Test void directCallerNeoFailurePropagatesAndForgeFailureKeepsTheResultItWasHanded() throws Exception {
		for (Order order : Order.values()) {
			try (PortalSpawnFixture f = new PortalSpawnFixture(temporary.resolve("failure-" + order), false, adapt(write(restoredCaller(order))))) {
				f.installLegacyBridge(); Object shape = f.shape(), neo = f.shape(); RuntimeException failure = new IllegalStateException("listener");
				boolean neoFirst = order == Order.NEO_FIRST_GUARDED;
				f.set("neoFailure", failure);
				assertSame(failure, assertThrows(IllegalStateException.class, () -> f.place(Optional.of(shape))));
				assertFalse(f.guarded());
				assertEquals(neoFirst ? 0 : 1, f.count("forgeCalls"), order + ": MinecraftForge runs only if it comes before NeoForge's failure");
				// The wrapper on a base that lost Forge's call, and the legacy bridge before it, keep the result a
				// MinecraftForge listener was handed when it throws. The same caller rebuilt with Forge's call restored
				// keeps that policy, in either order.
				f.set("neoFailure", null); f.set("forgeFailure", failure);
				AtomicReference<Object> neoInput = new AtomicReference<>();
				f.set("neoResult", (UnaryOperator<Object>) input -> { neoInput.set(input); return Optional.of(neo); });
				f.place(Optional.of(shape));
				assertFalse(f.guarded()); assertEquals(neoFirst ? 1 : 2, f.count("forgeCalls"));
				if (!neoFirst) assertEquals(Optional.of(shape), neoInput.get(), "a failed MinecraftForge listener hands NeoForge what it was handed");
				assertSame(neo, f.get("builtShape"), "a failed MinecraftForge listener must not discard NeoForge's result");
				f.set("forgeFailure", null); f.set("forgeResult", (UnaryOperator<Object>) input -> null);
				f.place(Optional.of(shape));
				assertNull(f.get("builtShape"), "a hook answering null refuses, as through the wrapper");
				f.set("forgeResult", (UnaryOperator<Object>) input -> input); f.set("neoResult", (UnaryOperator<Object>) input -> input);
				int before = f.count("forgeCalls");
				f.directNeo(Optional.of(shape));
				assertEquals(before + 1, f.count("forgeCalls"), "independent producers still retain their forward");
			}
		}
	}

	@Test void nestedRestoredCallersPreserveOuterScopeAndDispatchEachFamilyOnce() throws Exception {
		for (Order order : Order.values()) {
			try (PortalSpawnFixture f = new PortalSpawnFixture(temporary.resolve("nested-" + order), false, adapt(write(restoredCaller(order))))) {
				f.installLegacyBridge(); Object outer = f.shape(), inner = f.shape();
				f.set("nested", (Runnable) () -> {
					try { assertTrue(f.guarded()); f.place(Optional.of(inner)); assertTrue(f.guarded()); }
					catch (Exception failure) { throw new AssertionError(failure); }
				});
				f.place(Optional.of(outer));
				// The nested placement runs inside NeoForge's dispatch of the outer one.
				assertEquals(order == Order.NEO_FIRST_GUARDED ? List.of("neo", "neo", "forge", "forge") : List.of("forge", "neo", "forge", "neo"),
						f.trace(), order.toString());
				assertSame(outer, f.get("builtShape")); assertFalse(f.guarded());
			}
		}
	}

	@Test void unknownDualCallShapesRemainUnchangedAndOnlySuspected() throws Exception {
		// The finding describes the base, not the repair: switched off, the legacy forward still runs beside it.
		for (Order order : Order.values()) for (String setting : List.of("on", "off")) for (Consumer<MethodNode> mutation : List.<Consumer<MethodNode>>of(
				m -> guards(m).getFirst().setOpcode(Opcodes.IFNE),
				m -> guards(m).getLast().setOpcode(Opcodes.IFNE),
				m -> ((VarInsnNode) previousCode(forge(m))).var = 1,
				m -> ((VarInsnNode) nextCode(forge(m))).var = 7,
				m -> m.instructions.set(nextCode(forge(m)), new InsnNode(Opcodes.POP)),
				m -> m.instructions.insert(new InsnNode(Opcodes.NOP)),
				m -> { MethodInsnNode forge = forge(m), neo = neo(m); forge.owner = NEO; neo.owner = FORGE; },
				m -> { MethodInsnNode forge = forge(m); m.instructions.insert(forge, forge.clone(null)); },
				m -> { LabelNode entry = new LabelNode(); m.instructions.insertBefore(previousCode(previousCode(previousCode(forge(m)))), entry); m.instructions.insert(new JumpInsnNode(Opcodes.GOTO, entry)); },
				m -> m.access |= Opcodes.ACC_SYNCHRONIZED)) {
			System.setProperty(PortalSpawnInjector.PROPERTY, setting);
			ClassNode caller = restoredCaller(order); mutation.accept(host(caller)); byte[] bytes = write(caller);
			assertSame(bytes, adapt(bytes), order.toString());
			var findings = CompatibilityFindings.all(); assertEquals(1, findings.size(), order.toString());
			assertEquals(CompatibilityFinding.Confidence.SUSPECTED, findings.getFirst().confidence());
			assertFalse(findings.getFirst().required()); assertFalse(findings.getFirst().confirmedRequired());
			assertTrue(findings.getFirst().detail().contains("remain unchanged")); CompatibilityFindings.reset();
		}
	}

	/**
	 * The switch turns Forbric's portal composition off; the legacy forward stays installed. A base that restored
	 * MinecraftForge's own call must then lose it again, or the forward and the native call both post Forge.
	 */
	@Test void switchedOffRestoredCallerFallsBackToTheLegacyForwardAlone() throws Exception {
		for (Order order : Order.values()) {
			byte[] restored = write(restoredCaller(order)); System.setProperty(PortalSpawnInjector.PROPERTY, "off");
			byte[] reverted = adapt(restored);
			try (PortalSpawnFixture f = new PortalSpawnFixture(temporary.resolve("off-" + order), false, reverted)) {
				f.installLegacyBridge(); Object shape = f.shape(), replacement = f.shape();
				f.place(Optional.of(shape));
				assertEquals(List.of("neo", "forge"), f.trace(), order + ": MinecraftForge sees the portal once, through the legacy forward");
				assertSame(shape, f.get("builtShape"));
				f.set("forgeResult", (UnaryOperator<Object>) input -> Optional.of(replacement)); f.place(Optional.of(shape));
				assertSame(shape, f.get("builtShape"), "switched off, only the event-only forward remains: it carries no replacement");
				f.set("forgeResult", (UnaryOperator<Object>) input -> input); f.set("forgeCanceled", true); f.place(Optional.of(shape));
				assertNull(f.get("builtShape"), "a MinecraftForge refusal still carries");
				f.set("forgeCanceled", false); f.set("neoCanceled", true); f.place(Optional.of(shape));
				assertNull(f.get("builtShape")); assertEquals(4, f.count("neoCalls")); assertEquals(3, f.count("forgeCalls"));
			}
			ClassNode node = new ClassNode(); new ClassReader(reverted).accept(node, 0);
			List<String> hooks = new java.util.ArrayList<>();
			for (var instruction : host(node).instructions) if (instruction instanceof MethodInsnNode call && call.name.startsWith("onTrySpawnPortal")) hooks.add(call.owner + "." + call.name);
			assertEquals(List.of(NEO + ".onTrySpawnPortal"), hooks, "NeoForge's native call alone, with no kernel entrypoint");
			assertSame(reverted, adapt(reverted), "a reverted caller is idempotent");
			assertTrue(CompatibilityFindings.all().isEmpty(), "switching the composition off does not claim a repair or a failure");
			System.clearProperty(PortalSpawnInjector.PROPERTY);
		}
	}

	private static byte[] adapt(byte[] bytes) { return new PortalSpawnInjector().transform(TARGET, bytes, null); }
	private static MethodNode host(ClassNode caller) { return caller.methods.stream().filter(m -> m.name.equals("onPlace")).findFirst().orElseThrow(); }
	private static MethodInsnNode hook(MethodNode host, String owner) {
		for (var instruction : host.instructions) if (instruction instanceof MethodInsnNode call && call.owner.equals(owner) && call.name.equals("onTrySpawnPortal")) return call;
		throw new AssertionError("missing hook " + owner);
	}
	private static MethodInsnNode neo(MethodNode host) { return hook(host, NEO); }
	private static MethodInsnNode forge(MethodNode host) { return hook(host, FORGE); }
	/** Every {@code if (!shape.isPresent())} barrier of the caller, in order. */
	private static List<JumpInsnNode> guards(MethodNode host) {
		List<JumpInsnNode> guards = new ArrayList<>();
		for (var instruction : host.instructions) if (instruction instanceof JumpInsnNode jump && jump.getOpcode() == Opcodes.IFEQ
				&& previousCode(jump) instanceof MethodInsnNode call && call.name.equals("isPresent")) guards.add(jump);
		return guards;
	}
	private static AbstractInsnNode nextCode(AbstractInsnNode instruction) { do { instruction = instruction.getNext(); } while (instruction != null && instruction.getOpcode() < 0); return instruction; }
	private static AbstractInsnNode previousCode(AbstractInsnNode instruction) { do { instruction = instruction.getPrevious(); } while (instruction != null && instruction.getOpcode() < 0); return instruction; }
	private static byte[] write(ClassNode caller) { ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS); caller.accept(writer); return writer.toByteArray(); }

	/**
	 * The staged caller itself when the merge restored MinecraftForge's call in {@code order}; otherwise the staged
	 * NeoForge-only body ({@link #neoOnly}) with that order restored by hand.
	 */
	private static ClassNode restoredCaller(Order order) throws Exception {
		ClassNode caller = new ClassNode(); new ClassReader(ForgeSpawnFixture.staged(TestFixtures.BASE_JAR, TARGET)).accept(caller, 0);
		MethodNode host = host(caller);
		boolean forge = host.instructions.size() > 0 && java.util.Arrays.stream(host.instructions.toArray())
				.anyMatch(i -> i instanceof MethodInsnNode call && call.owner.equals(FORGE) && call.name.equals("onTrySpawnPortal"));
		if (forge && order(host) == order) return caller;
		if (forge) neoOnly(host);
		MethodInsnNode neo = neo(host);
		int slot = ((VarInsnNode) nextCode(neo)).var;
		InsnList restored = new InsnList();
		restored.add(new VarInsnNode(Opcodes.ALOAD, 2)); restored.add(new VarInsnNode(Opcodes.ALOAD, 3)); restored.add(new VarInsnNode(Opcodes.ALOAD, slot));
		restored.add(new MethodInsnNode(Opcodes.INVOKESTATIC, FORGE, neo.name, neo.desc, false));
		restored.add(new VarInsnNode(Opcodes.ASTORE, slot));
		if (order == Order.NEO_FIRST_GUARDED) {
			JumpInsnNode barrier = guards(host).getFirst();
			restored.add(new VarInsnNode(Opcodes.ALOAD, slot));
			restored.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/util/Optional", "isPresent", "()Z", false));
			restored.add(new JumpInsnNode(Opcodes.IFEQ, barrier.label)); host.instructions.insert(barrier, restored);
		} else {
			host.instructions.insertBefore(previousCode(previousCode(previousCode(neo))), restored);
		}
		return caller;
	}

	/** The order of a caller that carries both families' calls. */
	private static Order order(MethodNode host) {
		return host.instructions.indexOf(forge(host)) < host.instructions.indexOf(neo(host)) ? Order.FORGE_FIRST : Order.NEO_FIRST_GUARDED;
	}

	/** Takes MinecraftForge's statement out of a restored caller, and the barrier it repeated, leaving NeoForge's body. */
	private static void neoOnly(MethodNode host) {
		MethodInsnNode forge = forge(host);
		AbstractInsnNode store = nextCode(forge), arguments = previousCode(previousCode(previousCode(forge)));
		AbstractInsnNode after = nextCode(store);
		List<AbstractInsnNode> statement = new ArrayList<>();
		for (AbstractInsnNode at = arguments; at != after; at = at.getNext()) if (at.getOpcode() >= 0) statement.add(at);
		statement.forEach(host.instructions::remove);
		// Two barriers in a row on the same local and target are one barrier.
		List<JumpInsnNode> guards = guards(host);
		for (int i = 1; i < guards.size(); i++) {
			JumpInsnNode previous = guards.get(i - 1), current = guards.get(i);
			AbstractInsnNode load = previousCode(previousCode(current));
			if (nextCode(previous) == load && previous.label == current.label) {
				host.instructions.remove(previousCode(current));
				host.instructions.remove(load);
				host.instructions.remove(current);
				break;
			}
		}
	}
}
