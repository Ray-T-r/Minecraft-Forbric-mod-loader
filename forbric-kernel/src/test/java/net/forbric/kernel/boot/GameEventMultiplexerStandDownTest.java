/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.boot;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.util.*;
import java.util.function.Consumer;

import net.forbric.api.GameEventBridge;
import net.forbric.api.NativeEventDelivery;
import net.forbric.kernel.transform.InjectorExecution;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The bridge side of the stand-down: every bridge the multiplexer installs for a {@link DeadEventAudit#BRIDGED} row is
 * handed a view of NeoForge's bus whose typed listeners skip an event a native delivery covered, and nothing else.
 * The bridge classes are untouched, so this is the whole of what changed for them.
 */
class GameEventMultiplexerStandDownTest {
	private static final String DEATH = "net/minecraftforge/event/entity/living/LivingDeathEvent";

	/** A stand-in for NeoForge's bus interface, defined child-first so it is the one {@code guarded} resolves. */
	private static ClassLoader stubBus(Path work) throws Exception {
		Map<String, byte[]> classes = InjectorExecution.compile(work, Map.of(
				"net.neoforged.bus.api.IEventBus", """
						package net.neoforged.bus.api;
						import java.util.function.Consumer;
						public interface IEventBus {
						    void addListener(Object priority, boolean receiveCanceled, Class<?> type, Consumer<?> listener);
						    void addListener(Consumer<?> untyped);
						    Object post(Object event);
						}
						""",
				"stub.Bus", """
						package stub;
						import java.util.*;
						import java.util.function.Consumer;
						public final class Bus implements net.neoforged.bus.api.IEventBus {
						    public final List<Consumer<Object>> listeners = new ArrayList<>();
						    @SuppressWarnings("unchecked")
						    public void addListener(Object priority, boolean receiveCanceled, Class<?> type, Consumer<?> listener) { listeners.add((Consumer<Object>) listener); }
						    @SuppressWarnings("unchecked")
						    public void addListener(Consumer<?> untyped) { listeners.add((Consumer<Object>) untyped); }
						    public Object post(Object event) { for (Consumer<Object> l : List.copyOf(listeners)) l.accept(event); return event; }
						}
						"""));
		return new ClassLoader(GameEventMultiplexerStandDownTest.class.getClassLoader()) {
			@Override protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
				synchronized (getClassLoadingLock(name)) {
					byte[] bytes = classes.get(name.replace('.', '/'));
					if (bytes == null) return super.loadClass(name, resolve);
					Class<?> found = findLoadedClass(name);
					return found != null ? found : defineClass(name, bytes, 0, bytes.length);
				}
			}
		};
	}

	private record Stand(Object raw, Object view, Class<?> busType) {
		void addTyped(Consumer<Object> listener) throws Exception {
			busType.getMethod("addListener", Object.class, boolean.class, Class.class, Consumer.class)
					.invoke(view, "LOWEST", false, Object.class, listener);
		}

		void post(Object event) throws Exception {
			busType.getMethod("post", Object.class).invoke(view, event);
		}

		@SuppressWarnings("unchecked")
		List<Consumer<Object>> registered() throws Exception {
			return (List<Consumer<Object>>) raw.getClass().getField("listeners").get(raw);
		}
	}

	private static Stand stand(Path work, GameEventBridge bridge) throws Exception {
		ClassLoader cl = stubBus(work);
		Object raw = cl.loadClass("stub.Bus").getConstructor().newInstance();
		return new Stand(raw, GameEventMultiplexer.guarded(cl, raw, bridge), cl.loadClass("net.neoforged.bus.api.IEventBus"));
	}

	@Test
	void aBridgedListenerSkipsExactlyTheEventsANativeDeliveryCovered(@TempDir Path work) throws Exception {
		Stand stand = stand(work, GameEventBridge.LIVING_DEATH);
		assertNotSame(stand.raw(), stand.view(), "a BRIDGED bridge installs on the guarded view");
		List<Object> forwarded = new ArrayList<>();
		stand.addTyped(forwarded::add);
		assertNotSame(forwarded, stand.registered().get(0), "the listener NeoForge holds is the guard, not the bridge's");

		Object plain = new Object(), covered = new Object(), other = new Object();
		stand.post(plain);
		try (NativeEventDelivery.Scope scope = NativeEventDelivery.begin(DEATH, null, "")) {
			scope.capture(covered);
			stand.post(covered);
			stand.post(other);
		}
		try (NativeEventDelivery.Scope scope = NativeEventDelivery.begin("net/minecraftforge/event/level/ChunkEvent$Load", null, "")) {
			Object chunk = new Object();
			scope.capture(chunk);
			stand.post(chunk);
			assertTrue(forwarded.contains(chunk), "another bridge's native delivery is not this bridge's");
		}
		assertTrue(forwarded.contains(plain), "no scope: forwarded as before");
		assertFalse(forwarded.contains(covered), "the captured event was delivered natively: skipped");
		assertTrue(forwarded.contains(other), "a different event of the same moment, nested or not, still forwards");
		Object after = new Object();
		stand.post(after);
		assertTrue(forwarded.contains(after), "the scope is gone once its call returned");
	}

	@Test
	void anUntypedListenerAndAnUnbridgedBridgeAreLeftAlone(@TempDir Path work) throws Exception {
		Stand stand = stand(work, GameEventBridge.LIVING_DEATH);
		Consumer<Object> untyped = event -> { };
		stand.busType().getMethod("addListener", Consumer.class).invoke(stand.view(), untyped);
		assertSame(untyped, stand.registered().get(0), "an overload that infers its type from the listener is passed through");
		assertEquals(stand.view(), stand.view());
		assertEquals(stand.raw().hashCode(), stand.view().hashCode());
		assertTrue(stand.view().toString().contains("stand-down"));

		Stand tick = stand(work, GameEventBridge.SERVER_TICK_PRE);
		assertSame(tick.raw(), tick.view(), "no BRIDGED row, no native counterpart to stand down for: NeoForge's own bus");
	}

	/**
	 * The view is only useful if the bridges cast what they are handed to the interface it implements: every install
	 * entry of a game-side bridge class that the multiplexer can guard receives its bus as {@code Object} and casts it.
	 */
	@Test
	void everyGuardableBridgeCastsItsBusToTheInterfaceTheViewImplements() throws Exception {
		Path runtime = Path.of(System.getProperty("forbric.testRuntimeClasses", "build/classes/java/runtime"));
		net.forbric.kernel.TestFixtures.require(net.forbric.kernel.TestFixtures.Fixture.GAME_SIDE,
				java.nio.file.Files.isDirectory(runtime), "game-side classes absent: " + runtime);
		String bus = GameEventMultiplexer.NEO_BUS.replace('.', '/');
		for (String bridge : List.of("KernelGameEntityEvents", "KernelGameWorldEvents", "KernelGameServerEvents",
				"KernelGamePlayerTrackingEvents", "KernelGameBlockEvents", "KernelGamePlayerEvents", "KernelGameDamageEvents")) {
			Path file = runtime.resolve("net/forbric/kernel/runtime/" + bridge + ".class");
			org.objectweb.asm.tree.ClassNode node = new org.objectweb.asm.tree.ClassNode();
			new org.objectweb.asm.ClassReader(java.nio.file.Files.readAllBytes(file)).accept(node, 0);
			int entries = 0;
			for (org.objectweb.asm.tree.MethodNode m : node.methods) {
				if (!m.name.startsWith("install") || !m.desc.equals("(Ljava/lang/Object;)V")) continue;
				entries++;
				boolean casts = false;
				for (org.objectweb.asm.tree.AbstractInsnNode insn : m.instructions) {
					if (insn instanceof org.objectweb.asm.tree.TypeInsnNode type && type.getOpcode() == org.objectweb.asm.Opcodes.CHECKCAST
							&& type.desc.equals(bus)) casts = true;
				}
				assertTrue(casts, bridge + "." + m.name + " must cast its bus to " + bus + " for the guarded view to reach it");
			}
			assertTrue(entries > 0, bridge + " has no install entry");
		}
	}
}
