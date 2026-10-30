/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.transform;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import net.fabricmc.api.EnvType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@link RegistryRevertInjector}'s output, run, in the shape it has on the genuine NeoForge base (no MinecraftForge
 * wrapper sync at {@code applySnapshot}'s head, so the revert captures for itself): a connection's remap, then the
 * disconnect's {@code RegistryManager.revertToFrozen()}, puts the ids back where they were, through the kernel's real
 * {@code KernelRegistryRevert}. As shipped, the same disconnect dies on the frozen snapshot the kernel's freeze never
 * takes — the NullPointerException on every world leave the injector exists for.
 *
 * <p>{@code RegistryManager} is a stand-in under NeoForge's name, small enough to read: one registry, an id map that
 * {@code applySnapshot} overwrites, and the snapshot API the kernel's hook calls by reflection
 * ({@code takeSnapshot(SnapshotType.SYNC_TO_CLIENT)}, each snapshot's {@code getIds()}). The hook is the kernel's own
 * class, from the test classpath. The capture-stands-down shape (today's chain, after the wrapper sync) is
 * RegistryRevertInjectorTest's, read from the real carrier's bytes.
 */
@ExecutesInjector(RegistryRevertInjector.class)
class RegistryRevertInjectorExecutionTest {
	private static final String MANAGER = "net.neoforged.neoforge.registries.RegistryManager";
	private static final Map<String, String> STAND_INS = Map.of(MANAGER, """
			package net.neoforged.neoforge.registries;
			import java.util.*;
			public class RegistryManager {
				public enum SnapshotType { SYNC_TO_CLIENT, FULL }
				public static final class Snapshot {
					private final Map<Integer, String> ids;
					Snapshot(Map<Integer, String> ids) { this.ids = new TreeMap<>(ids); }
					public Map<Integer, String> getIds() { return ids; }
				}
				/** The one registry's live id map: what a remap changes and the revert must put back. */
				public static final Map<Integer, String> live = new TreeMap<>(Map.of(1, "stone", 2, "dirt"));
				public static final List<String> applied = new ArrayList<>();
				/** GameData.freezeData's snapshot: the kernel owns the freeze, so it is never taken. */
				private static Map<String, Snapshot> frozenSnapshot;
				public static Map<String, Snapshot> takeSnapshot(SnapshotType type) {
					return Map.of("minecraft:block", new Snapshot(live));
				}
				public static Snapshot snapshot(Map<Integer, String> ids) { return new Snapshot(ids); }
				public static Set<String> applySnapshot(Map<String, Snapshot> snapshot, boolean isLocalWorld) {
					applied.add(isLocalWorld ? "local" : "server");
					live.clear();
					live.putAll(snapshot.get("minecraft:block").getIds());
					return Set.of();
				}
				public static void revertToFrozen() { applySnapshot(frozenSnapshot, true); }
			}
			""");

	@AfterEach
	void forgetTheCapture() throws Exception {
		// KernelRegistryRevert's capture is the process's; a failure between capture and revert must not leak it.
		var originals = Class.forName("net.forbric.kernel.boot.KernelRegistryRevert").getDeclaredField("originals");
		originals.setAccessible(true);
		originals.set(null, null);
	}

	@Test
	void aRemapThenTheDisconnectsRevertPutsTheIdsBack(@TempDir Path work) throws Throwable {
		assertEquals(List.of(), RegistrySyncParityInjector.discoveredContracts(),
				"premise: no remap contract is noted in this JVM, so the revert's unmap pass has nothing to look for");
		Map<String, byte[]> original = InjectorExecution.compile(work, STAND_INS);
		String internal = MANAGER.replace('.', '/');

		// As shipped: the disconnect's revert dies on the snapshot nobody took.
		Class<?> shipped = InjectorExecution.load(original).loadClass(MANAGER);
		assertThrows(NullPointerException.class, () -> InjectorExecution.invokeStatic(shipped, "revertToFrozen"),
				"premise: NeoForge's own revertToFrozen dies when the frozen snapshot was never taken");

		Map<String, byte[]> classes = new HashMap<>(original);
		classes.put(internal, InjectorExecution.transform(new RegistryRevertInjector(), MANAGER, original.get(internal), EnvType.CLIENT));
		assertNotSame(original.get(internal), classes.get(internal), "the injector matched the stand-in");
		ClassLoader loader = InjectorExecution.load(classes);
		assertEquals("", InjectorExecution.verify(classes.get(internal), loader));
		Class<?> manager = loader.loadClass(MANAGER);
		@SuppressWarnings("unchecked")
		Map<Integer, String> live = (Map<Integer, String>) InjectorExecution.getStatic(manager, "live");
		List<?> applied = (List<?>) InjectorExecution.getStatic(manager, "applied");
		Map<Integer, String> before = Map.copyOf(live);

		// The connection: the server's ids arrive through applySnapshot, whose new head captures the ones they replace.
		Object server = InjectorExecution.invokeStatic(manager, "snapshot", Map.of(1, "dirt", 2, "stone"));
		InjectorExecution.invokeStatic(manager, "applySnapshot", Map.of("minecraft:block", server), false);
		assertEquals(Map.of(1, "dirt", 2, "stone"), live, "premise: the remap changed the ids");

		// The disconnect: the revert's body is the kernel's, which re-applies the capture through the same applySnapshot
		// (whose head must not capture the ids it is undoing).
		InjectorExecution.invokeStatic(manager, "revertToFrozen");
		assertEquals(before, live, "the pre-connection ids are back");
		assertEquals(List.of("server", "local"), applied, "the server's remap, then the revert's own application");

		// A second disconnect with no connection between: nothing captured, nothing applied, no NullPointerException.
		InjectorExecution.invokeStatic(manager, "revertToFrozen");
		assertEquals(List.of("server", "local"), applied, "nothing to revert");
		assertEquals(before, live);
	}
}
