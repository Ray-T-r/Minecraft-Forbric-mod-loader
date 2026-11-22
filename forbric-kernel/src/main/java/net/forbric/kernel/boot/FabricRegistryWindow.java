/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.boot;

import java.util.function.BooleanSupplier;

import net.forbric.kernel.util.ForbricLog;

/**
 * Whether the built-in registries — the root ({@code BuiltInRegistries.REGISTRY}) and every registry in it — are still
 * inside the window a Fabric game with fabric-api keeps them writable: from {@code Bootstrap} to Fabric's registry freeze
 * point, after every main (a server) or main and client (a client) entrypoint.
 *
 * <p>On Fabric, {@code fabric-registry-sync-v0}'s {@code BootstrapMixin} redirects {@code BuiltInRegistries.bootStrap()}
 * in {@code Bootstrap} to {@code createContents()} alone, and its {@code MainMixin} (client: {@code MinecraftMixin}) runs
 * the freeze after the entrypoints (javap, fabric-api 0.161.0+26.2). Until then a mod may register — a new registry into
 * the root ({@code BuiltInRegistries.registerSimple}, {@code FabricRegistryBuilder}) or an entry into a built-in registry
 * ({@code Registry.register}) — from whatever code runs first, and mods do it from class initialisers, so WHEN depends on
 * who touches the class first.
 *
 * <p>The kernel freezes them in {@code Bootstrap}, as NeoForge does, and opens them only in its own windows (NeoForge's
 * {@code RegisterEvent}, the Fabric entrypoints). A NeoForge mod that wakes a Fabric mod's class before those windows
 * therefore made the Fabric mod write into a frozen registry. Cold Sweat gathers its registries during mod loading by
 * processing {@code DataPackRegistryEvent.NewRegistry} itself, which initialises {@code RegistryDataLoader}; WorldWeaver's
 * mixin on that initialiser runs its datapack-registry entrypoints, whose classes create {@code wover:wover/biome_codec}
 * through {@code registerSimple} and register {@code wover:random_nbt_structure} into {@code worldgen/structure_type}.
 * "Registry is already frozen" was thrown after vanilla's {@code internalRegister} had already put the new key in
 * {@code LOADERS}, so the key was in NeoForge's registration order but not in the root: {@code GameData.postRegisterEvents}
 * died on {@code Objects.requireNonNull}, rolled every registry back to vanilla, and the server stopped. Neither mod does
 * that on its own loader: alone on NeoForge nothing wakes WorldWeaver, alone on Fabric the registries are open.
 *
 * <p>So while this window is open, a write to a frozen built-in registry reopens that registry
 * ({@code FabricRegistryWindowInjector} asks {@code KernelFabricRegistryWindow} at {@code MappedRegistry.validateWrite})
 * and the write lands. It stays open, as on Fabric, until a freeze the kernel or NeoForge already makes closes it again —
 * the registration window's, the Fabric entrypoint window's, NeoForge's {@code NewRegistryEvent.fill} for the root — and
 * that freeze binds the new entry like any other. Only built-in registries (not a world's datapack registries), only with
 * Fabric mods installed (a NeoForge-only game keeps NeoForge's frozen registries and its error), and never after
 * {@link #close}: a write after Fabric's freeze point fails as it does on Fabric. {@code -Dforbric.fabricRegistryWindow=off}
 * keeps them frozen outside the kernel's own windows.
 */
public final class FabricRegistryWindow {
	public static final String PROPERTY = "forbric.fabricRegistryWindow";

	private static volatile boolean closed;
	private static volatile BooleanSupplier fabricPresent = KernelFabricEcosystem::active;

	private FabricRegistryWindow() {
	}

	public static boolean enabled() {
		return !"off".equalsIgnoreCase(System.getProperty(PROPERTY, "on"));
	}

	/** Whether a write to a frozen built-in registry is admitted now: switched on, Fabric mods installed, Fabric's freeze point not reached. */
	public static boolean isOpen() {
		return !closed && enabled() && fabricPresent.getAsBoolean();
	}

	/** Fabric's registry freeze point: from here a write to a frozen registry fails, as it does on Fabric. Idempotent. */
	public static void close() {
		closed = true;
	}

	/** Called by the game-side hook after it reopened a frozen built-in registry for {@code key}. */
	public static void reopened(String key) {
		ForbricLog.info("[Forbric/RegistrySync] reopened a frozen built-in registry for %s, written before Fabric's registry "
				+ "freeze point — with fabric-api the registries stay writable until every Fabric entrypoint has run, so what a "
				+ "Fabric mod registers from code another mod woke early lands, as on Fabric; the next freeze closes it again "
				+ "(-D%s=off keeps it frozen)", key, PROPERTY);
	}

	/**
	 * Called by the game-side hook when a freeze before Fabric's freeze point skipped its intrusive-holder check for
	 * {@code registry}: on a client the registration window's freeze comes before the Fabric main entrypoints, and a holder
	 * built early by a Fabric mod's class is registered there.
	 */
	public static void carried(String registry, int count, String holders) {
		ForbricLog.info("[Forbric/RegistrySync] %s: %d intrusive holder(s) not registered yet at a freeze before Fabric's "
				+ "registry freeze point %s — with fabric-api nothing checks them until every Fabric entrypoint has run, so "
				+ "they stay listed and the freeze at that point checks them (-D%s=off checks them here)",
				registry, count, holders, PROPERTY);
	}

	/** Tests only: a fresh window, with {@code present} answering whether Fabric mods are installed (null: the real answer). */
	public static void resetForTests(BooleanSupplier present) {
		closed = false;
		fabricPresent = present == null ? KernelFabricEcosystem::active : present;
	}
}
