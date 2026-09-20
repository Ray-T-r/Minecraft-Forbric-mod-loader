/*
 * Copyright 2026 The Forbric Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package net.forbric.kernel.runtime;

import java.lang.reflect.Method;
import java.util.Map;

import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.util.RandomSource;
import net.minecraft.util.profiling.InactiveProfiler;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntityTypes;
import net.neoforged.neoforge.common.conditions.ICondition;
import net.neoforged.neoforge.common.MonsterRoomHooks;
import net.neoforged.neoforge.registries.DataMapLoader;
import net.neoforged.neoforge.registries.NeoForgeRegistries;
import net.neoforged.neoforge.server.ServerLifecycleHooks;

import net.forbric.kernel.util.ForbricLog;
import net.forbric.kernel.util.Reflect;

/**
 * The three NeoForge worldgen mechanisms the byte merge left with no driver, and their guards.
 *
 * <h2>Data maps</h2>
 *
 * <p>NeoForge loads its data maps from a {@code DataMapLoader} that genuine NeoForge attaches to the server's
 * resource reload, in the patched {@code ReloadableServerResources}. The merge kept MinecraftForge's version of
 * that class, and a constant-pool scan of the whole merged base finds {@code DataMapLoader} named by NOTHING —
 * so no data map has ever been loaded in a Forbric instance. That is ten built-ins (compostables, furnace fuels,
 * oxidizables, waxables, strippables, parrot imitations, vibration frequencies, villager gifts, villager
 * distances, monster-room mobs) plus every data map any NeoForge mod declares, all silently empty.
 *
 * <h2>Monster rooms</h2>
 *
 * <p>The visible half of that. The merged {@code MonsterRoomFeature.randomEntityId} is two instructions —
 * {@code invokestatic MonsterRoomHooks.getRandomMonsterRoomMob} — reading a static {@code WeightedList} that only
 * a {@code DataMapsUpdatedEvent} listener fills, so it was null and threw. The kernel had answered that by
 * neutering {@code MonsterRoomFeature.place} outright, which means NO dungeon, therefore no spawner and no
 * dungeon chest, in EVERY world every player generated, mods or no mods.
 *
 * <p>So the call goes through {@link #randomMonsterRoomMob} instead, which falls back to vanilla's own set when
 * the data map is not there. The fallback is not a guess: vanilla's {@code MonsterRoomFeature.MOBS} is
 * {@code {SKELETON, ZOMBIE, ZOMBIE, SPIDER}} and NeoForge's shipped
 * {@code data/neoforge/data_maps/entity_type/monster_room_mobs.json} is skeleton 100 / spider 100 / zombie 200 —
 * the same distribution, which is what makes a fallback honest rather than a different game.
 *
 * <h2>Biome and structure modifiers</h2>
 *
 * <p>{@code ServerLifecycleHooks.runModifiers} was neutered too, because {@code neoforge:biome_modifier} was not
 * declared. It is now — {@code KernelLifecycle.registerDataPackRegistries} posts NeoForge's
 * {@code DataPackRegistryEvent.NewRegistry} and both modifier registries come back among the declared ones. But
 * un-neutering it alone would put an unguarded {@code lookupOrThrow} on the path of every server start, so the
 * CALL SITE inside {@code handleServerAboutToStart} is redirected here instead, where a failure costs the
 * modifiers and not the boot.
 */
public final class KernelNeoWorldgen {

	/**
	 * Vanilla's own monster-room set, in vanilla's own proportions — {@code ZOMBIE} twice, as the array has it.
	 */
	private static final EntityType<?>[] VANILLA_MONSTER_ROOM_MOBS = {
			EntityTypes.SKELETON, EntityTypes.ZOMBIE, EntityTypes.ZOMBIE, EntityTypes.SPIDER,
	};

	private static volatile boolean reportedFallback;
	private static volatile boolean reportedDataMaps;

	private KernelNeoWorldgen() {
	}

	/**
	 * {@code MonsterRoomFeature.randomEntityId}'s body: NeoForge's data map when it has one, vanilla's set when
	 * it does not.
	 *
	 * <p>Never throws. This runs inside chunk generation, on a worker thread, once per dungeon — an exception
	 * here is a failed chunk, and the mob it picks is cosmetic by comparison.
	 */
	public static EntityType<?> randomMonsterRoomMob(RandomSource random) {
		try {
			EntityType<?> fromDataMap = MonsterRoomHooks.getRandomMonsterRoomMob(random);
			if (fromDataMap != null) return fromDataMap;
		} catch (Throwable t) {
			if (!reportedFallback) {
				reportedFallback = true;
				ForbricLog.warn("[Forbric/Worldgen] NeoForge's monster-room data map is not available (%s) — "
						+ "dungeons are generating from vanilla's own mob set, which is the same distribution. "
						+ "A NeoForge mod that ADDS to that data map will not be represented",
						String.valueOf(Reflect.unwrap(t)));
			}
		}
		return VANILLA_MONSTER_ROOM_MOBS[random.nextInt(VANILLA_MONSTER_ROOM_MOBS.length)];
	}

	/** {@code -Dforbric.neoDataMapFallback=off}: never run the kernel's about-to-start load, so a gate can prove NeoForge's path alone. */
	// The watch over NeoForge's own path lives in KernelNeoDataMapWatch, which carries no static game state.
	public static final String FALLBACK_PROPERTY = "forbric.neoDataMapFallback";

	static boolean fallbackEnabled() {
		return !"off".equalsIgnoreCase(System.getProperty(FALLBACK_PROPERTY, "on"));
	}

	/**
	 * Loads NeoForge's data maps over the server's own packs and applies them, which posts its
	 * {@code DataMapsUpdatedEvent} per registry — the event every consumer of a data map listens for.
	 *
	 * <p>The FALLBACK, not the path. NeoForge's own reload-listener path is carried whole by the merged base
	 * (see {@link KernelNeoDataMapWatch}); this runs from the kernel's server-about-to-start listener only when
	 * that path applied nothing, with the server's live condition context — the first version of this ran
	 * unconditionally with an EMPTY context and overwrote the genuine, correctly-judged apply. Maps loaded this
	 * way are NOT rebuilt by {@code /reload}.
	 */
	private static void loadDataMaps(MinecraftServer server) {
		try {
			DataMapLoader loader = new DataMapLoader();
			ICondition.IContext context;
			try {
				context = server.getServerResources().managers().getConditionContext();
			} catch (Throwable noLiveContext) {
				context = ICondition.IContext.EMPTY;
			}
			loader.injectContext(context, server.registryAccess());
			ResourceManager resources = server.getResourceManager();
			// Both members are private, and both have to be reached: the synchronous `load` overload is the only
			// one that does not hand back a CompletableFuture wired into a reload barrier this kernel does not
			// have, and `apply` reads what it produced out of the field rather than taking it as an argument.
			Method load = DataMapLoader.class.getDeclaredMethod("load", ResourceManager.class, ProfilerFiller.class);
			load.setAccessible(true);
			Object results = load.invoke(loader, resources, InactiveProfiler.INSTANCE);
			java.lang.reflect.Field resultsField = DataMapLoader.class.getDeclaredField("results");
			resultsField.setAccessible(true);
			resultsField.set(loader, results);
			loader.apply(server.registryAccess());
			int loaded = results instanceof Map<?, ?> map ? map.size() : -1;

			if (!reportedDataMaps) {
				reportedDataMaps = true;
				Registry<EntityType<?>> entityTypes = server.registryAccess().lookupOrThrow(Registries.ENTITY_TYPE);
				int monsterRoom = entityTypes
						.getDataMap(net.neoforged.neoforge.registries.datamaps.builtin.NeoForgeDataMaps
								.MONSTER_ROOM_MOBS)
						.size();
				ForbricLog.info("[Forbric/Worldgen] loaded NeoForge's data maps from the kernel's about-to-start fallback "
						+ "for %d registr(ies) with the live condition context — NeoForge's own reload path had applied "
						+ "none. %d entity type(s) can spawn in a monster room", loaded, monsterRoom);
			}
		} catch (Throwable t) {
			ForbricLog.warn("[Forbric/Worldgen] NeoForge's data maps did not load — its built-ins (furnace fuels, "
					+ "compostables, oxidizables, monster-room mobs and the rest) and every mod's own data map "
					+ "stay empty. Dungeons still generate, from vanilla's mob set", Reflect.unwrap(t));
		}
	}

	/**
	 * Everything NeoForge does at {@code handleServerAboutToStart} that the merge left with no driver, in the
	 * order genuine NeoForge does it.
	 *
	 * <p>This replaces the {@code runModifiers} call inside that method, which is the earliest kernel-controlled
	 * point on the server-start path and — importantly — is still AHEAD of {@code ServerAboutToStartEvent}. Data
	 * maps go first for that reason: a mod reading one from its own about-to-start listener has to find it
	 * filled, and genuine NeoForge fills them during the resource reload, earlier still.
	 *
	 * <p>Counted deliberately: "ran without throwing" and "applied something" are different claims, and only the
	 * second one distinguishes a working modifier pipeline from a declared-but-empty registry.
	 */
	public static void beforeServerStart(MinecraftServer server) {
		int applied = KernelNeoDataMapWatch.appliedTotal();
		if (applied > 0) {
			ForbricLog.info("[Forbric/Worldgen] NeoForge's own reload path already applied data maps for %d registr%s — "
					+ "the kernel's about-to-start fallback stood down", applied, applied == 1 ? "y" : "ies");
		} else if (fallbackEnabled()) {
			loadDataMaps(server);
		} else {
			ForbricLog.warn("[Forbric/Worldgen] -D%s=off and NeoForge's own path applied no data maps — they stay empty",
					FALLBACK_PROPERTY);
		}
		applyBiomeAndStructureModifiers(server);
	}

	private static void applyBiomeAndStructureModifiers(MinecraftServer server) {
		int biome = countOrMinusOne(server, NeoForgeRegistries.Keys.BIOME_MODIFIERS);
		int structure = countOrMinusOne(server, NeoForgeRegistries.Keys.STRUCTURE_MODIFIERS);
		// MinecraftForge's modifiers ride inside this same pass (ForgeWorldModifierInjector splices them into the
		// lists runModifiers materialises); the round-trip audit decides beforehand whether they may.
		KernelForgeWorldgen.auditRoundTrip(server);
		try {
			Method runModifiers =
					ServerLifecycleHooks.class.getDeclaredMethod("runModifiers", MinecraftServer.class);
			runModifiers.setAccessible(true);
			runModifiers.invoke(null, server);
			ForbricLog.info("[Forbric/Worldgen] applied NeoForge's %d biome modifier(s) and %d structure "
					+ "modifier(s) — the kernel used to neuter this outright because its datapack registries were "
					+ "not declared", biome, structure);
			KernelForgeWorldgen.summarize();
		} catch (Throwable t) {
			ForbricLog.warn("[Forbric/Worldgen] NeoForge's biome/structure modifiers did not apply — "
					+ biome + " biome and " + structure + " structure modifier(s) were loaded and none of them "
					+ "changed the world. The server start is unaffected", Reflect.unwrap(t));
		}
	}

	private static int countOrMinusOne(MinecraftServer server, ResourceKey<? extends Registry<?>> key) {
		try {
			return server.registryAccess().lookupOrThrow(key).size();
		} catch (Throwable t) {
			return -1;
		}
	}
}
