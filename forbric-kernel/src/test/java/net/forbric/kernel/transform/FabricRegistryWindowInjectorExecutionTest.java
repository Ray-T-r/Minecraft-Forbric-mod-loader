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

					import java.util.Iterator;
					import java.util.LinkedHashMap;
					import java.util.Map;
					import net.minecraft.resources.ResourceKey;

					public class MappedRegistry<T> implements Registry<T> {
						private final Map<String, T> byLocation = new LinkedHashMap<>();
						private boolean frozen;

						public void register(ResourceKey<T> key, T value) {
							validateWrite(key);
							byLocation.put(key.location(), value);
						}

						private void validateWrite(ResourceKey<T> key) {
							if (this.frozen) {
								throw new IllegalStateException("Registry is already frozen (trying to add key " + key + ")");
							}
						}

						public void unfreeze(boolean clearTags) {
							this.frozen = false;
						}

						public MappedRegistry<T> freeze() {
							this.frozen = true;
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

						static {
							((MappedRegistry<Registry<?>>) REGISTRY).register(new ResourceKey<>("minecraft:worldgen/structure_type"), STRUCTURE_TYPE);
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
