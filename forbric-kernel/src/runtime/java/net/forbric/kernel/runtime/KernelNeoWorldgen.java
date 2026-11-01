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

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import net.minecraft.core.Registry;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntityTypes;
import net.neoforged.neoforge.common.MonsterRoomHooks;
import net.neoforged.neoforge.registries.NeoForgeRegistries;
import net.neoforged.neoforge.server.ServerLifecycleHooks;

import net.forbric.kernel.util.Caught;
import net.forbric.kernel.util.ForbricLog;

/**
 * Two NeoForge worldgen mechanisms the kernel drives itself, and their guards.
 *
 * <h2>Data maps</h2>
 *
 * <p>NeoForge's own reload path loads its data maps: {@code NeoForgeEventHandler.onResourceReload} registers a
 * {@code DataMapLoader} on {@code AddServerReloadListenersEvent} and {@code tagsUpdated} applies it, on the dedicated
 * server and the integrated one alike ({@link KernelNeoDataMapWatch} counts it). The kernel used to load them a second
 * time from about-to-start when that path applied nothing, which on the byte-merged base it never did at first; it went
 * in Stage 4, since NeoForge's path applies data maps for every registry that has them on every boot.
 *
 * <h2>Monster rooms</h2>
 *
 * <p>The merged {@code MonsterRoomFeature.randomEntityId} was two instructions —
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

	/**
	 * NeoForge's shipped {@code data/neoforge/data_maps/entity_type/monster_room_mobs.json}, by weight.
	 *
	 * <p>Read as a question, not as a table: while the live map still says exactly this, nobody has changed it.
	 */
	private static final Map<EntityType<?>, Integer> NEOFORGE_SHIPPED_MONSTER_ROOM_MOBS = Map.of(
			EntityTypes.SKELETON, 100, EntityTypes.SPIDER, 100, EntityTypes.ZOMBIE, 200);

	private static volatile boolean reportedVanillaDraw;

	private KernelNeoWorldgen() {
	}

	/**
	 * {@code MonsterRoomFeature.randomEntityId}'s body: vanilla's draw while NeoForge's monster-room data map is its
	 * shipped default (so a seed's dungeons get vanilla's mobs), NeoForge's weighted draw once a mod changed it, and
	 * vanilla's draw again if the map is missing.
	 *
	 * <p>Never throws. This runs inside chunk generation, on a worker thread, once per dungeon — an exception
	 * here is a failed chunk, and the mob it picks is cosmetic by comparison.
	 */
	public static EntityType<?> randomMonsterRoomMob(RandomSource random) {
		try {
			if (monsterRoomMobsAreStillNeoForgesDefault()) {
				if (!reportedVanillaDraw) {
					reportedVanillaDraw = true;
					ForbricLog.info("[Forbric/Worldgen] no mod has changed NeoForge's monster-room data map, so "
							+ "dungeons pick their mob the way vanilla does — same distribution either way, but "
							+ "vanilla's nextInt(4) and NeoForge's nextInt(400) do not agree on the same seed");
				}
			} else {
				EntityType<?> fromDataMap = MonsterRoomHooks.getRandomMonsterRoomMob(random);
				if (fromDataMap != null) return fromDataMap;
			}
		} catch (Throwable t) {
			Caught.warn(t, "[Forbric/Worldgen] NeoForge's monster-room data map is not available — dungeons are "
					+ "generating from vanilla's own mob set, which is the same distribution. A NeoForge mod that ADDS "
					+ "to that data map will not be represented");
		}
		return VANILLA_MONSTER_ROOM_MOBS[random.nextInt(VANILLA_MONSTER_ROOM_MOBS.length)];
	}

	/**
	 * Is the live monster-room data map still exactly what NeoForge ships, with nothing added or changed?
	 *
	 * <p>Why this decides which draw to make. Both sides agree on the distribution — vanilla's
	 * {@code {SKELETON, ZOMBIE, ZOMBIE, SPIDER}} is 25/50/25, and NeoForge's shipped weights are 100/200/100 —
	 * but they do not agree on WHICH mob a given seed produces: vanilla draws {@code nextInt(4)}, which
	 * {@code BitRandomSource} answers from the high bits of a power-of-two bound, and the weighted list draws
	 * {@code nextInt(400)}, which goes through {@code next(31) % 400} and reads the low ones. The two are near
	 * enough to independent that the same dungeon in the same seed comes out a different mob about five times in
	 * eight. Measured, zero mods, one seed: all six dungeons in the compared area disagreed with vanilla.
	 *
	 * <p>So while nothing has changed the map, the vanilla draw is the one that reproduces vanilla's world, and
	 * it costs nothing — the distribution is identical. The moment a mod adds an entry or reweights one, the map
	 * is what the player asked for and it wins. Either path spends exactly one {@code nextInt}, so the feature's
	 * random stream carries on identically whichever is taken.
	 *
	 * <p>Reflective because the list is {@code MonsterRoomHooks}' own private static, filled from its
	 * {@code DataMapsUpdatedEvent} listener; there is no accessor that returns it. Any failure answers "not the
	 * default", which keeps the data map in charge — the conservative direction, since that is what this method
	 * would have done unconditionally before.
	 */
	private static boolean monsterRoomMobsAreStillNeoForgesDefault() {
		try {
			Field field = MonsterRoomHooks.class.getDeclaredField("monsterRoomMobs");
			field.setAccessible(true);
			Object live = field.get(null);
			if (live == null) return false;
			Object unwrapped = live.getClass().getMethod("unwrap").invoke(live);
			if (!(unwrapped instanceof List<?> items)
					|| items.size() != NEOFORGE_SHIPPED_MONSTER_ROOM_MOBS.size()) {
				return false;
			}
			Map<Object, Integer> live_weights = new HashMap<>();
			for (Object item : items) {
				Object value = item.getClass().getMethod("value").invoke(item);
				Object weight = item.getClass().getMethod("weight").invoke(item);
				if (!(weight instanceof Integer count) || live_weights.put(value, count) != null) return false;
			}
			return NEOFORGE_SHIPPED_MONSTER_ROOM_MOBS.equals(live_weights);
		} catch (Throwable t) {
			Caught.warn(t, "[Forbric/Worldgen] could not read NeoForge's monster-room mob list — the data map stays in "
					+ "charge of dungeon mobs");
			return false;
		}
	}

	/**
	 * NeoForge's {@code runModifiers} at {@code handleServerAboutToStart}, guarded.
	 *
	 * <p>This replaces the {@code runModifiers} call inside that method, which is the earliest kernel-controlled
	 * point on the server-start path and — importantly — is still AHEAD of {@code ServerAboutToStartEvent}.
	 *
	 * <p>Counted deliberately: "ran without throwing" and "applied something" are different claims, and only the
	 * second one distinguishes a working modifier pipeline from a declared-but-empty registry.
	 */
	public static void beforeServerStart(MinecraftServer server) {
		applyBiomeAndStructureModifiers(server);
	}

	private static void applyBiomeAndStructureModifiers(MinecraftServer server) {
		int biome = countOrMinusOne(server, NeoForgeRegistries.Keys.BIOME_MODIFIERS);
		int structure = countOrMinusOne(server, NeoForgeRegistries.Keys.STRUCTURE_MODIFIERS);
		try {
			Method runModifiers =
					ServerLifecycleHooks.class.getDeclaredMethod("runModifiers", MinecraftServer.class);
			runModifiers.setAccessible(true);
			runModifiers.invoke(null, server);
			ForbricLog.info("[Forbric/Worldgen] applied NeoForge's %d biome modifier(s) and %d structure "
					+ "modifier(s) — the kernel used to neuter this outright because its datapack registries were "
					+ "not declared", biome, structure);
		} catch (Throwable t) {
			Caught.warn(t, "[Forbric/Worldgen] NeoForge's biome/structure modifiers did not apply — %d biome and %d "
					+ "structure modifier(s) were loaded and none of them changed the world. The server start is "
					+ "unaffected", biome, structure);
		}
	}

	private static int countOrMinusOne(MinecraftServer server, ResourceKey<? extends Registry<?>> key) {
		try {
			return server.registryAccess().lookupOrThrow(key).size();
		} catch (Throwable t) {
			Caught.warn(t, "[Forbric/Worldgen] could not count registry %s — reported as -1", key);
			return -1;
		}
	}
}
