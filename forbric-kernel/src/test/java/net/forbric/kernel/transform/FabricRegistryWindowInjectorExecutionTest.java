/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.transform;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.InvocationTargetException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;

import net.fabricmc.api.EnvType;
import net.forbric.kernel.boot.FabricRegistryWindow;

/**
 * {@link FabricRegistryWindowInjector}'s output, run: before Fabric's registry freeze point a write into a frozen
 * built-in registry — a new registry into the root, an entry into a registry in it — reopens that registry and lands, as
 * on a Fabric game with fabric-api, where nothing is frozen until every Fabric entrypoint has run. Measured where it
 * broke: Cold Sweat (NeoForge) processing {@code DataPackRegistryEvent.NewRegistry} during mod loading woke WorldWeaver's
 * {@code RegistryDataLoader} initialiser, whose {@code registerSimple} of {@code wover:wover/biome_codec} threw "Registry is
 * already frozen" after vanilla had listed the key, and NeoForge's {@code GameData.postRegisterEvents} stopped the server on
 * the registry that was not there; its next class registered {@code wover:random_nbt_structure} into the frozen
 * {@code worldgen/structure_type}.
 *
 * <p>The hook is the kernel's real {@code KernelFabricRegistryWindow}, compiled from {@code src/runtime/java} against
 * stand-ins: a {@code MappedRegistry} whose {@code validateWrite(ResourceKey)} is vanilla's frozen check, and a
 * {@code BuiltInRegistries} holding the root with one registry in it.
 */
@ExecutesInjector(FabricRegistryWindowInjector.class)
@ResourceLock("system-properties")
class FabricRegistryWindowInjectorExecutionTest {
	private static final Path HOOK_SOURCE = Path.of("src/runtime/java/net/forbric/kernel/runtime/KernelFabricRegistryWindow.java");
	private static final String MAPPED = FabricRegistryWindowInjector.TARGET;
	private static final String BUILT_IN = "net.minecraft.core.registries.BuiltInRegistries";
	private static final String KEY = "net.minecraft.resources.ResourceKey";

	private static final Map<String, String> STAND_INS = Map.of(
			KEY, "package net.minecraft.resources; public record ResourceKey<T>(String location) { }",
			"net.minecraft.core.Registry", "package net.minecraft.core; public interface Registry<T> extends Iterable<T> { }",
			MAPPED, """
					package net.minecraft.core;

					import java.util.IdentityHashMap;
					import java.util.Iterator;
					import java.util.LinkedHashMap;
					import java.util.Map;
					import net.minecraft.resources.ResourceKey;

					public class MappedRegistry<T> implements Registry<T> {
						private final Map<String, T> byLocation = new LinkedHashMap<>();
						private boolean frozen;
						private Map<T, String> unregisteredIntrusiveHolders;
						private String name = "test:registry";

						public MappedRegistry() {
						}

						/** A registry with intrusive holders (ENTITY_TYPE, BLOCK, ITEM, …): NeoForge's freeze keeps the map. */
						public MappedRegistry(String name) {
							this.name = name;
							this.unregisteredIntrusiveHolders = new IdentityHashMap<>();
						}

						public ResourceKey<Registry<T>> key() {
							return new ResourceKey<>(name);
						}

						/** What EntityType's constructor does: the holder is listed until the value is registered. */
						public void createIntrusiveHolder(T value) {
							if (this.unregisteredIntrusiveHolders == null) throw new IllegalStateException("This registry can't create intrusive holders");
							this.unregisteredIntrusiveHolders.put(value, "Reference{null=" + value + "}");
						}

						public boolean listed(T value) {
							return this.unregisteredIntrusiveHolders != null && this.unregisteredIntrusiveHolders.containsKey(value);
						}

						public void register(ResourceKey<T> key, T value) {
							validateWrite(key);
							byLocation.put(key.location(), value);
							if (this.unregisteredIntrusiveHolders != null) this.unregisteredIntrusiveHolders.remove(value);
						}

						private void validateWrite(ResourceKey<T> key) {
							if (this.frozen) {
								throw new IllegalStateException("Registry is already frozen (trying to add key " + key + ")");
							}
						}

						public void unfreeze(boolean clearTags) {
							this.frozen = false;
						}

						/** NeoForge's base: the flag first, then the intrusive-holder check, and the map is kept. */
						public Registry<T> freeze() {
							this.frozen = true;
							if (this.unregisteredIntrusiveHolders != null && !this.unregisteredIntrusiveHolders.isEmpty()) {
								throw new IllegalStateException("Some intrusive holders were not registered: " + this.unregisteredIntrusiveHolders.values());
							}
							return this;
						}

						public boolean frozen() {
							return frozen;
						}

						public boolean contains(String location) {
							return byLocation.containsKey(location);
						}

						@Override
						public Iterator<T> iterator() {
							return byLocation.values().iterator();
						}
					}
					""",
			BUILT_IN, """
					package net.minecraft.core.registries;

					import net.minecraft.core.MappedRegistry;
					import net.minecraft.core.Registry;
					import net.minecraft.resources.ResourceKey;

					public class BuiltInRegistries {
						public static final Registry<Registry<?>> REGISTRY = new MappedRegistry<>();
						public static final MappedRegistry<Object> STRUCTURE_TYPE = new MappedRegistry<>();
						public static final MappedRegistry<Object> ENTITY_TYPE = new MappedRegistry<>("minecraft:entity_type");

						static {
							((MappedRegistry<Registry<?>>) REGISTRY).register(new ResourceKey<>("minecraft:worldgen/structure_type"), STRUCTURE_TYPE);
							((MappedRegistry<Registry<?>>) REGISTRY).register(new ResourceKey<>("minecraft:entity_type"), ENTITY_TYPE);
						}

						/** What Bootstrap leaves: every built-in registry frozen, the root too. */
						public static void freezeAll() {
							STRUCTURE_TYPE.freeze();
							((MappedRegistry<?>) REGISTRY).freeze();
						}
					}
					""");

	@AfterEach void reset() {
		System.clearProperty(FabricRegistryWindow.PROPERTY);
		FabricRegistryWindow.resetForTests(null);
	}

	private static Map<String, byte[]> compile(Path work) throws Exception {
		assertTrue(Files.isRegularFile(HOOK_SOURCE), "the game-side hook's source is part of the checkout: " + HOOK_SOURCE.toAbsolutePath());
		Map<String, String> sources = new HashMap<>(STAND_INS);
		sources.put("net/forbric/kernel/runtime/KernelFabricRegistryWindow.java", Files.readString(HOOK_SOURCE));
		return InjectorExecution.compile(work, sources);
	}

	private static ClassLoader withInjected(Map<String, byte[]> original) {
		String internal = MAPPED.replace('.', '/');
		byte[] asked = InjectorExecution.transform(new FabricRegistryWindowInjector(), MAPPED, original.get(internal), EnvType.SERVER);
		assertNotSame(original.get(internal), asked, "validateWrite was given the question");
		Map<String, byte[]> classes = new HashMap<>(original);
		classes.put(internal, asked);
		ClassLoader loader = InjectorExecution.load(classes);
		assertEquals("", InjectorExecution.verify(asked, loader));
		return loader;
	}

	/** The built-in registries as the kernel's Bootstrap leaves them: all frozen. */
	private static Class<?> frozenBuiltIns(ClassLoader loader) throws Throwable {
		Class<?> builtIn = Class.forName(BUILT_IN, true, loader);
		InjectorExecution.invokeStatic(builtIn, "freezeAll");
		return builtIn;
	}

	private static void register(ClassLoader loader, Object registry, String location) throws Throwable {
		Object key = InjectorExecution.construct(loader.loadClass(KEY), location);
		registry.getClass().getMethod("register", loader.loadClass(KEY), Object.class).invoke(registry, key, new Object());
	}

	private static String frozenFailure(ClassLoader loader, Object registry, String location) {
		InvocationTargetException thrown = assertThrows(InvocationTargetException.class, () -> register(loader, registry, location));
		assertInstanceOf(IllegalStateException.class, thrown.getCause());
		return thrown.getCause().getMessage();
	}

	@Test void aWriteBeforeFabricsFreezePointReopensTheBuiltInRegistryAndLands(@TempDir Path work) throws Throwable {
		FabricRegistryWindow.resetForTests(() -> true);
		Map<String, byte[]> original = compile(work);
		ClassLoader loader = withInjected(original);
		Class<?> builtIn = frozenBuiltIns(loader);
		Object root = InjectorExecution.getStatic(builtIn, "REGISTRY");
		Object structureTypes = InjectorExecution.getStatic(builtIn, "STRUCTURE_TYPE");

		register(loader, root, "wover:wover/biome_codec");
		assertEquals(true, InjectorExecution.invoke(root, "contains", "wover:wover/biome_codec"), "the new registry is in the root");
		assertEquals(false, InjectorExecution.invoke(root, "frozen"),
				"and the root stays open, as on Fabric, until the next freeze the kernel or NeoForge makes");
		register(loader, structureTypes, "wover:random_nbt_structure");
		assertEquals(true, InjectorExecution.invoke(structureTypes, "contains", "wover:random_nbt_structure"),
				"an entry into a registry in the root lands too");

		// A frozen registry that is not built in (a world's datapack registry) still refuses.
		Object other = InjectorExecution.construct(loader.loadClass(MAPPED));
		InjectorExecution.invoke(other, "freeze");
		assertTrue(frozenFailure(loader, other, "mod:thing").startsWith("Registry is already frozen"));

		// Fabric's freeze point: from there every frozen registry refuses, as it does on Fabric.
		InjectorExecution.invokeStatic(builtIn, "freezeAll");
		FabricRegistryWindow.close();
		assertTrue(frozenFailure(loader, root, "late:registry").startsWith("Registry is already frozen"));
		assertTrue(frozenFailure(loader, structureTypes, "late:structure").startsWith("Registry is already frozen"));
		assertEquals(false, InjectorExecution.invoke(root, "contains", "late:registry"));

		// Premise: as shipped, the same early write into the frozen root throws.
		FabricRegistryWindow.resetForTests(() -> true);
		ClassLoader shipped = InjectorExecution.load(original);
		Object shippedRoot = InjectorExecution.getStatic(frozenBuiltIns(shipped), "REGISTRY");
		assertTrue(frozenFailure(shipped, shippedRoot, "wover:wover/biome_codec").startsWith("Registry is already frozen"),
				"premise: without the injector the root refuses the early write");
	}

	/**
	 * A client's two freezes. NeoForge's attribute events (the tail of its registration) ran a Fabric mod's mixin on
	 * {@code DefaultAttributes.hasSupplier}, which initialised the mod's entity class; its initialiser built an
	 * {@code EntityType} (Better Nether's {@code naga_projectile}) that the mod's {@code onInitialize} registers, and on a
	 * client that runs after the registration window's freeze. That freeze threw "Some intrusive holders were not registered"
	 * and {@code GameData.freezeData} stopped part-way. Before Fabric's freeze point the freeze now carries the holder; the
	 * entrypoint registers it; the freeze at Fabric's freeze point checks it, and throws for one nothing registered.
	 */
	@Test void aFreezeBeforeFabricsFreezePointCarriesUnregisteredIntrusiveHoldersToIt(@TempDir Path work) throws Throwable {
		FabricRegistryWindow.resetForTests(() -> true);
		Map<String, byte[]> original = compile(work);
		ClassLoader loader = withInjected(original);
		Object entityTypes = InjectorExecution.getStatic(Class.forName(BUILT_IN, true, loader), "ENTITY_TYPE");

		Object projectile = "entity.betternether.naga_projectile";
		InjectorExecution.invoke(entityTypes, "createIntrusiveHolder", projectile);
		InjectorExecution.invoke(entityTypes, "freeze");
		assertEquals(true, InjectorExecution.invoke(entityTypes, "frozen"), "the registration window's freeze completes");
		assertEquals(true, InjectorExecution.invoke(entityTypes, "listed", projectile), "and the holder is still listed for the next one");

		// The Fabric entrypoint window: reopened, the main entrypoint registers the value it built early.
		InjectorExecution.invoke(entityTypes, "unfreeze", false);
		register(loader, entityTypes, "betternether:naga_projectile", projectile);
		FabricRegistryWindow.close();
		InjectorExecution.invoke(entityTypes, "freeze");
		assertEquals(false, InjectorExecution.invoke(entityTypes, "listed", projectile));

		// At and after Fabric's freeze point a holder nothing registered fails the freeze, as on Fabric.
		InjectorExecution.invoke(entityTypes, "unfreeze", false);
		InjectorExecution.invoke(entityTypes, "createIntrusiveHolder", "entity.mod.never_registered");
		IllegalStateException unregistered = assertThrows(IllegalStateException.class, () -> InjectorExecution.invoke(entityTypes, "freeze"));
		assertTrue(unregistered.getMessage().startsWith("Some intrusive holders were not registered"), unregistered.getMessage());

		// Without Fabric mods there is no later freeze to carry them to: NeoForge's check, as on NeoForge.
		FabricRegistryWindow.resetForTests(() -> false);
		Object neoOnly = InjectorExecution.construct(loader.loadClass(MAPPED), "neo:entity_type");
		InjectorExecution.invoke(neoOnly, "createIntrusiveHolder", "entity.neo.thing");
		assertThrows(IllegalStateException.class, () -> InjectorExecution.invoke(neoOnly, "freeze"));

		// Premise: as shipped, the registration window's freeze throws on the early-built holder.
		FabricRegistryWindow.resetForTests(() -> true);
		ClassLoader shipped = InjectorExecution.load(original);
		Object shippedEntityTypes = InjectorExecution.getStatic(Class.forName(BUILT_IN, true, shipped), "ENTITY_TYPE");
		InjectorExecution.invoke(shippedEntityTypes, "createIntrusiveHolder", projectile);
		IllegalStateException premise = assertThrows(IllegalStateException.class, () -> InjectorExecution.invoke(shippedEntityTypes, "freeze"));
		assertTrue(premise.getMessage().startsWith("Some intrusive holders were not registered"), premise.getMessage());
	}

	@Test void aFreezeOfAnotherShapeIsLeftAsItIs(@TempDir Path work) throws Throwable {
		Map<String, byte[]> original = compile(work);
		org.objectweb.asm.tree.ClassNode node = new org.objectweb.asm.tree.ClassNode();
		new org.objectweb.asm.ClassReader(original.get(MAPPED.replace('.', '/'))).accept(node, 0);
		node.methods.removeIf(m -> m.name.equals("freeze"));
		assertFalse(FabricRegistryWindowInjector.carryIntrusiveHolders(node), "no freeze() of that shape: nothing to ask");
	}

	private static void register(ClassLoader loader, Object registry, String location, Object value) throws Throwable {
		Object key = InjectorExecution.construct(loader.loadClass(KEY), location);
		registry.getClass().getMethod("register", loader.loadClass(KEY), Object.class).invoke(registry, key, value);
	}

	@Test void withoutFabricModsTheRegistriesKeepNeoForgesFreeze(@TempDir Path work) throws Throwable {
		FabricRegistryWindow.resetForTests(() -> false);
		ClassLoader loader = withInjected(compile(work));
		Class<?> builtIn = frozenBuiltIns(loader);
		assertTrue(frozenFailure(loader, InjectorExecution.getStatic(builtIn, "REGISTRY"), "neo:registry")
				.startsWith("Registry is already frozen"), "a NeoForge-only game has no Fabric window: the root refuses, as on NeoForge");
		assertTrue(frozenFailure(loader, InjectorExecution.getStatic(builtIn, "STRUCTURE_TYPE"), "neo:structure")
				.startsWith("Registry is already frozen"));
	}

	@Test void switchedOffLeavesTheClassAsItIs(@TempDir Path work) throws Throwable {
		System.setProperty(FabricRegistryWindow.PROPERTY, "off");
		Map<String, byte[]> original = compile(work);
		String internal = MAPPED.replace('.', '/');
		assertSame(original.get(internal),
				InjectorExecution.transform(new FabricRegistryWindowInjector(), MAPPED, original.get(internal), EnvType.SERVER));
	}
}
