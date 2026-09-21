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
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;

import com.google.gson.JsonElement;
import com.mojang.datafixers.util.Pair;
import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.DynamicOps;
import com.mojang.serialization.JsonOps;
import com.mojang.serialization.MapCodec;

import net.forbric.api.ModCatalog;
import net.forbric.kernel.util.ForbricLog;
import net.forbric.kernel.util.Reflect;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.RegistryOps;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.random.Weighted;
import net.minecraft.util.random.WeightedList;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeGenerationSettings;
import net.minecraft.world.level.biome.BiomeSpecialEffects;
import net.minecraft.world.level.biome.MobSpawnSettings;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraftforge.registries.ForgeRegistries;
import net.neoforged.neoforge.registries.DataPackRegistryEvent;
import net.neoforged.neoforge.server.ServerLifecycleHooks;

/**
 * Brings a MinecraftForge mod's {@code data/<ns>/forge/biome_modifier} and {@code forge/structure_modifier}
 * files into the world — inside NeoForge's own single modifier pass.
 *
 * <p>Three things kept them out: nothing declared the {@code forge:biome_modifier} datapack registry on the
 * merged base (the loader asks only NeoForge's {@code DataPackRegistriesHooks}); Forge's own pass links against
 * Forge-typed accessors ({@code Biome.modifiableBiomeInfo()} returning Forge's type) the merged classes do not
 * declare; and two of Forge's builders link against {@code WeightedList$Builder} defaults the merge lost. The
 * design: (1) declare both Forge registries through a second {@code DataPackRegistryEvent.NewRegistry}, exactly
 * as the Fabric mirror does, with Forge's own {@code DIRECT_CODEC} wrapped leniently so a file whose serializer
 * did not register under the kernel becomes a no-op modifier and names its mod DEGRADED instead of failing the
 * whole world load; (2) splice one instruction into each list NeoForge's {@code runModifiers} materialises,
 * appending ONE bridge element per family; (3) the bridge is called by NeoForge's phase loop once per (element,
 * phase) and forwards that phase to every Forge modifier in Forge registry order, over Forge's own builders, and
 * writes back only when the four vanilla parts encode differently — the same JSON compare NeoForge uses to decide
 * "modified". Forge's own {@code runModifiers} is never invoked. Ordering inside a phase: NeoForge's modifiers
 * first, then MinecraftForge's; phases interleave across families, which two separate passes could never do.
 *
 * <p>Before the pass, every biome and structure is round-tripped NeoForge → Forge → NeoForge and compared the
 * same way; one difference stands the whole bridge down for the boot, loudly, with every Forge modifier's
 * namespace marked DEGRADED — a silent partial translation being the one outcome this must never produce.
 *
 * <p>{@code -Dforbric.forgeWorldgen=off}: no declaration, no splice (the injector stands down), the shippers
 * scan names each mod that loses the feature. {@code -Dforbric.forgeWorldgen.lenient=off}: Forge's strict codec,
 * so bad data fails the world load exactly as genuine Forge would.
 */
public final class KernelForgeWorldgen {
	public static final String PROPERTY = "forbric.forgeWorldgen";
	public static final String LENIENT_PROPERTY = "forbric.forgeWorldgen.lenient";

	private static final Set<String> REPORTED_TYPES = Collections.newSetFromMap(new ConcurrentHashMap<>());
	private static volatile boolean standDown;
	private static volatile String standDownReason;
	private static final Set<String> CHANGED_BIOMES = ConcurrentHashMap.newKeySet();
	private static final Set<String> CHANGED_STRUCTURES = ConcurrentHashMap.newKeySet();
	private static volatile int bridgedBiomeModifiers, bridgedStructureModifiers;

	private KernelForgeWorldgen() {
	}

	public static boolean enabled() {
		return !"off".equalsIgnoreCase(System.getProperty(PROPERTY, "on"));
	}

	// ------------------------------------------------------------------------------------------------------
	// D2: the WeightedList$Builder default the merge lost

	/**
	 * Stands in for {@code IForgeWeightedList$Builder.removeIf(Predicate<E>)}'s default body — the merged
	 * {@code WeightedList$Builder} implements no Forge interface, so the default is gone and its only caller,
	 * {@code ForgeBiomeModifiers$RemoveSpawnsBiomeModifier.modify}, is redirected here. This is that one line.
	 */
	public static <E> WeightedList.Builder<E> removeIfValue(WeightedList.Builder<E> builder, Predicate<E> predicate) {
		return builder.removeIf((Weighted<E> weighted) -> predicate.test(weighted.value()));
	}

	// ------------------------------------------------------------------------------------------------------
	// D3 ①: declare forge:biome_modifier and forge:structure_modifier on NeoForge's list

	/** Called with a fresh {@code DataPackRegistryEvent.NewRegistry}; the boot side processes it afterwards. */
	public static void declareForgeModifierRegistries(Object newRegistryEvent) {
		if (!enabled()) {
			ForbricLog.warn("[Forbric/Worldgen] -D%s=off — forge:biome_modifier and forge:structure_modifier are not "
					+ "declared; a MinecraftForge mod's biome and structure modifiers will not apply", PROPERTY);
			return;
		}
		DataPackRegistryEvent.NewRegistry event = (DataPackRegistryEvent.NewRegistry) newRegistryEvent;
		int biomeSerializers = countOrMinusOne(() -> ForgeRegistries.BIOME_MODIFIER_SERIALIZERS.get().getKeys().size());
		int structureSerializers = countOrMinusOne(() -> ForgeRegistries.STRUCTURE_MODIFIER_SERIALIZERS.get().getKeys().size());
		event.dataPackRegistry(ForgeRegistries.Keys.BIOME_MODIFIERS,
				lenient(net.minecraftforge.common.world.BiomeModifier.DIRECT_CODEC, NOOP_BIOME, "biome",
						id -> ForgeRegistries.BIOME_MODIFIER_SERIALIZERS.get().containsKey(Identifier.parse(id))));
		event.dataPackRegistry(ForgeRegistries.Keys.STRUCTURE_MODIFIERS,
				lenient(net.minecraftforge.common.world.StructureModifier.DIRECT_CODEC, NOOP_STRUCTURE, "structure",
						id -> ForgeRegistries.STRUCTURE_MODIFIER_SERIALIZERS.get().containsKey(Identifier.parse(id))));
		ForbricLog.info("[Forbric/Worldgen] declared forge:biome_modifier and forge:structure_modifier on NeoForge's "
				+ "datapack-registry list (unsynced, as ForgeMod declares them) — MinecraftForge's serializer registries "
				+ "hold %d biome and %d structure modifier serializer(s)", biomeSerializers, structureSerializers);
	}

	private static int countOrMinusOne(java.util.function.IntSupplier count) {
		try {
			return count.getAsInt();
		} catch (Throwable t) {
			return -1;
		}
	}

	private static final net.minecraftforge.common.world.BiomeModifier NOOP_BIOME =
			new net.minecraftforge.common.world.BiomeModifier() {
				@Override
				public void modify(Holder<Biome> biome, Phase phase,
						net.minecraftforge.common.world.ModifiableBiomeInfo.BiomeInfo.Builder builder) {
				}

				@Override
				public MapCodec<? extends net.minecraftforge.common.world.BiomeModifier> codec() {
					return MapCodec.unit(this);
				}
			};

	private static final net.minecraftforge.common.world.StructureModifier NOOP_STRUCTURE =
			new net.minecraftforge.common.world.StructureModifier() {
				@Override
				public void modify(Holder<Structure> structure, Phase phase,
						net.minecraftforge.common.world.ModifiableStructureInfo.StructureInfo.Builder builder) {
				}

				@Override
				public MapCodec<? extends net.minecraftforge.common.world.StructureModifier> codec() {
					return MapCodec.unit(this);
				}
			};

	/**
	 * Forge's strict codec, except that a {@code type} naming a serializer MinecraftForge's registry does not
	 * have decodes to a no-op modifier and names the owning namespace DEGRADED. Anything else — no type, an
	 * unparseable one, a known one with bad fields — goes to Forge's codec and fails the way Forge fails: the
	 * leniency covers "this serializer never registered under the kernel", not "this file is broken".
	 */
	static <M> Codec<M> lenient(Codec<M> strict, M noop, String kind, Predicate<String> known) {
		return new Codec<>() {
			@Override
			public <T> DataResult<Pair<M, T>> decode(DynamicOps<T> ops, T input) {
				if (!"off".equalsIgnoreCase(System.getProperty(LENIENT_PROPERTY, "on"))) {
					String decision = lenientDecision(typeOf(ops, input), known);
					if (decision.startsWith("SKIP:")) {
						String type = typeOf(ops, input);
						String namespace = decision.substring("SKIP:".length());
						if (REPORTED_TYPES.add(type)) {
							ForbricLog.warn("[Forbric/Worldgen] MinecraftForge %s modifier type '%s' has no serializer under "
									+ "the kernel — files of that type are skipped, not applied; %s is marked DEGRADED",
									kind, type, namespace);
						}
						ModCatalog.mark(namespace, ModCatalog.Status.DEGRADED, "its " + kind + " modifier type " + type
								+ " has no serializer under the kernel — that file is skipped");
						return DataResult.success(Pair.of(noop, ops.empty()));
					}
				}
				return strict.decode(ops, input);
			}

			@Override
			public <T> DataResult<T> encode(M value, DynamicOps<T> ops, T prefix) {
				return strict.encode(value, ops, prefix);
			}

			@Override
			public String toString() {
				return "Forbric(" + strict + ")";
			}
		};
	}

	/**
	 * Pure and JDK-typed: {@code SKIP:<namespace>} for a well-formed type id the registry lacks, else
	 * {@code STRICT}. The id grammar is vanilla's ({@code [a-z0-9_.-]+} namespace, {@code [a-z0-9/._-]+} path,
	 * {@code minecraft} when unqualified); a malformed id keeps Forge's own error, never a silent skip.
	 */
	static String lenientDecision(String type, Predicate<String> known) {
		if (type == null) return "STRICT";
		int colon = type.indexOf(':');
		String namespace = colon < 0 ? "minecraft" : type.substring(0, colon);
		String path = colon < 0 ? type : type.substring(colon + 1);
		if (!namespace.matches("[a-z0-9_.-]+") || !path.matches("[a-z0-9/._-]+")) return "STRICT";
		try {
			return known.test(namespace + ":" + path) ? "STRICT" : "SKIP:" + namespace;
		} catch (Throwable t) {
			return "STRICT";
		}
	}

	private static <T> String typeOf(DynamicOps<T> ops, T input) {
		try {
			Optional<Map<T, T>> map = ops.getMapValues(input)
					.map(stream -> stream.collect(java.util.stream.Collectors.toMap(Pair::getFirst, Pair::getSecond, (a, b) -> b)))
					.result();
			if (map.isEmpty()) return null;
			for (Map.Entry<T, T> entry : map.get().entrySet()) {
				if (ops.getStringValue(entry.getKey()).result().filter("type"::equals).isPresent()) {
					return ops.getStringValue(entry.getValue()).result().orElse(null);
				}
			}
			return null;
		} catch (Throwable t) {
			return null;
		}
	}

	// ------------------------------------------------------------------------------------------------------
	// D3 ②: the two splice targets inside NeoForge's runModifiers

	/** Spliced after NeoForge's biome-modifier list is materialised; returns it with ONE bridge appended. */
	public static List<net.neoforged.neoforge.common.world.BiomeModifier> withMinecraftForgeBiomeModifiers(
			List<net.neoforged.neoforge.common.world.BiomeModifier> neo) {
		if (!enabled() || standDown) return neo;
		MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
		if (server == null) {
			ForbricLog.warn("[Forbric/Worldgen] NeoForge's current server is unset inside its own modifier pass — "
					+ "MinecraftForge biome modifiers are not bridged this boot");
			return neo;
		}
		Optional<Registry<net.minecraftforge.common.world.BiomeModifier>> registry =
				server.registryAccess().lookup(ForgeRegistries.Keys.BIOME_MODIFIERS);
		if (registry.isEmpty() || registry.get().size() == 0) {
			ForbricLog.info("[Forbric/Worldgen] 0 MinecraftForge biome modifier(s) — nothing to bridge");
			return neo;
		}
		List<String> keys = new ArrayList<>();
		List<Map.Entry<ResourceKey<net.minecraftforge.common.world.BiomeModifier>, net.minecraftforge.common.world.BiomeModifier>> ordered = new ArrayList<>();
		for (Map.Entry<ResourceKey<net.minecraftforge.common.world.BiomeModifier>, net.minecraftforge.common.world.BiomeModifier> entry : registry.get().entrySet()) {
			ordered.add(entry);
			keys.add(entry.getKey().identifier().toString());
		}
		bridgedBiomeModifiers = ordered.size();
		ForbricLog.info("[Forbric/Worldgen] bridging %d MinecraftForge biome modifier(s) into NeoForge's pass: %s",
				ordered.size(), keys);
		List<net.neoforged.neoforge.common.world.BiomeModifier> all = new ArrayList<>(neo);
		all.add(new ForgeBiomeBridge(ordered, server.registryAccess()));
		return all;
	}

	/** The structure twin. */
	public static List<net.neoforged.neoforge.common.world.StructureModifier> withMinecraftForgeStructureModifiers(
			List<net.neoforged.neoforge.common.world.StructureModifier> neo) {
		if (!enabled() || standDown) return neo;
		MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
		if (server == null) return neo;
		Optional<Registry<net.minecraftforge.common.world.StructureModifier>> registry =
				server.registryAccess().lookup(ForgeRegistries.Keys.STRUCTURE_MODIFIERS);
		if (registry.isEmpty() || registry.get().size() == 0) {
			ForbricLog.info("[Forbric/Worldgen] 0 MinecraftForge structure modifier(s) — nothing to bridge");
			return neo;
		}
		List<String> keys = new ArrayList<>();
		List<Map.Entry<ResourceKey<net.minecraftforge.common.world.StructureModifier>, net.minecraftforge.common.world.StructureModifier>> ordered = new ArrayList<>();
		for (Map.Entry<ResourceKey<net.minecraftforge.common.world.StructureModifier>, net.minecraftforge.common.world.StructureModifier> entry : registry.get().entrySet()) {
			ordered.add(entry);
			keys.add(entry.getKey().identifier().toString());
		}
		bridgedStructureModifiers = ordered.size();
		ForbricLog.info("[Forbric/Worldgen] bridging %d MinecraftForge structure modifier(s) into NeoForge's pass: %s",
				ordered.size(), keys);
		List<net.neoforged.neoforge.common.world.StructureModifier> all = new ArrayList<>(neo);
		all.add(new ForgeStructureBridge(ordered, server.registryAccess()));
		return all;
	}

	/** After NeoForge's pass: what the bridge changed. Never "done". */
	public static void summarize() {
		if (!enabled()) return;
		if (standDown) {
			ForbricLog.warn("[Forbric/Worldgen] MinecraftForge modifiers were NOT applied this boot: %s", standDownReason);
			return;
		}
		if (bridgedBiomeModifiers == 0 && bridgedStructureModifiers == 0) return;
		ForbricLog.info("[Forbric/Worldgen] MinecraftForge modifiers changed %d biome(s) and %d structure(s)",
				CHANGED_BIOMES.size(), CHANGED_STRUCTURES.size());
	}

	// ------------------------------------------------------------------------------------------------------
	// D3 ③: the bridges — one NeoForge element per family, forwarding each phase to Forge's modifiers

	private static final Map<net.neoforged.neoforge.common.world.BiomeModifier.Phase, net.minecraftforge.common.world.BiomeModifier.Phase> BIOME_PHASES = biomePhases();
	private static final Map<net.neoforged.neoforge.common.world.StructureModifier.Phase, net.minecraftforge.common.world.StructureModifier.Phase> STRUCTURE_PHASES = structurePhases();

	static List<String> phaseNames() {
		List<String> names = new ArrayList<>();
		for (net.neoforged.neoforge.common.world.BiomeModifier.Phase phase : net.neoforged.neoforge.common.world.BiomeModifier.Phase.values()) {
			names.add(phase.name());
		}
		return names;
	}

	static net.minecraftforge.common.world.BiomeModifier.Phase forgePhaseFor(String name) {
		return net.minecraftforge.common.world.BiomeModifier.Phase.valueOf(name);
	}

	private static Map<net.neoforged.neoforge.common.world.BiomeModifier.Phase, net.minecraftforge.common.world.BiomeModifier.Phase> biomePhases() {
		Map<net.neoforged.neoforge.common.world.BiomeModifier.Phase, net.minecraftforge.common.world.BiomeModifier.Phase> map =
				new EnumMap<>(net.neoforged.neoforge.common.world.BiomeModifier.Phase.class);
		try {
			for (net.neoforged.neoforge.common.world.BiomeModifier.Phase phase : net.neoforged.neoforge.common.world.BiomeModifier.Phase.values()) {
				map.put(phase, forgePhaseFor(phase.name()));
			}
		} catch (Throwable t) {
			standDown("the two families' biome-modifier phases no longer share names: " + t);
		}
		return map;
	}

	private static Map<net.neoforged.neoforge.common.world.StructureModifier.Phase, net.minecraftforge.common.world.StructureModifier.Phase> structurePhases() {
		Map<net.neoforged.neoforge.common.world.StructureModifier.Phase, net.minecraftforge.common.world.StructureModifier.Phase> map =
				new EnumMap<>(net.neoforged.neoforge.common.world.StructureModifier.Phase.class);
		try {
			for (net.neoforged.neoforge.common.world.StructureModifier.Phase phase : net.neoforged.neoforge.common.world.StructureModifier.Phase.values()) {
				map.put(phase, net.minecraftforge.common.world.StructureModifier.Phase.valueOf(phase.name()));
			}
		} catch (Throwable t) {
			standDown("the two families' structure-modifier phases no longer share names: " + t);
		}
		return map;
	}

	private static void standDown(String reason) {
		if (!standDown) {
			standDown = true;
			standDownReason = reason;
			ForbricLog.warn("[Forbric/Worldgen] MinecraftForge modifier bridge standing down: %s", reason);
		}
	}

	/** NeoForge's builder has four private, non-final sub-builders; resolved once, any failure stands down. */
	private static final Field[] NEO_BIOME_BUILDER_FIELDS = neoBuilderFields(
			net.neoforged.neoforge.common.world.ModifiableBiomeInfo.BiomeInfo.Builder.class,
			"climateSettings", "effects", "generationSettings", "mobSpawnSettings");
	private static final Field[] NEO_STRUCTURE_BUILDER_FIELDS = neoBuilderFields(
			net.neoforged.neoforge.common.world.ModifiableStructureInfo.StructureInfo.Builder.class, "structureSettings");

	private static Field[] neoBuilderFields(Class<?> builder, String... names) {
		try {
			Field[] fields = new Field[names.length];
			for (int i = 0; i < names.length; i++) {
				fields[i] = builder.getDeclaredField(names[i]);
				fields[i].setAccessible(true);
			}
			return fields;
		} catch (Throwable t) {
			standDown("NeoForge's " + builder.getSimpleName() + " no longer has the sub-builder fields the bridge writes back: " + t);
			return new Field[0];
		}
	}

	private static void copyFields(Field[] fields, Object from, Object into) throws IllegalAccessException {
		for (Field field : fields) field.set(into, field.get(from));
	}

	private static net.minecraftforge.common.world.ModifiableBiomeInfo.BiomeInfo forgeBiomeInfo(
			net.neoforged.neoforge.common.world.ModifiableBiomeInfo.BiomeInfo neo) {
		return new net.minecraftforge.common.world.ModifiableBiomeInfo.BiomeInfo(neo.climateSettings(), neo.effects(),
				neo.generationSettings(), neo.mobSpawnSettings());
	}

	private static net.neoforged.neoforge.common.world.ModifiableBiomeInfo.BiomeInfo neoBiomeInfo(
			net.minecraftforge.common.world.ModifiableBiomeInfo.BiomeInfo forge) {
		return new net.neoforged.neoforge.common.world.ModifiableBiomeInfo.BiomeInfo(forge.climateSettings(), forge.effects(),
				forge.generationSettings(), forge.mobSpawnSettings());
	}

	/** The four vanilla parts, encoded the way NeoForge decides "modified" — a JSON compare under RegistryOps. */
	private static List<Optional<JsonElement>> encodeBiome(net.neoforged.neoforge.common.world.ModifiableBiomeInfo.BiomeInfo info,
			RegistryAccess registries) {
		RegistryOps<JsonElement> ops = RegistryOps.create(JsonOps.INSTANCE, registries);
		return List.of(Biome.ClimateSettings.CODEC.codec().encodeStart(ops, info.climateSettings()).result(),
				BiomeSpecialEffects.CODEC.encodeStart(ops, info.effects()).result(),
				BiomeGenerationSettings.CODEC.codec().encodeStart(ops, info.generationSettings()).result(),
				MobSpawnSettings.CODEC.codec().encodeStart(ops, info.mobSpawnSettings()).result());
	}

	private static Optional<JsonElement> encodeStructure(Structure.StructureSettings settings, RegistryAccess registries) {
		return Structure.StructureSettings.CODEC.codec().encodeStart(RegistryOps.create(JsonOps.INSTANCE, registries), settings).result();
	}

	static final class ForgeBiomeBridge implements net.neoforged.neoforge.common.world.BiomeModifier {
		private final List<Map.Entry<ResourceKey<net.minecraftforge.common.world.BiomeModifier>, net.minecraftforge.common.world.BiomeModifier>> modifiers;
		private final RegistryAccess registries;
		private final Set<ResourceKey<net.minecraftforge.common.world.BiomeModifier>> dropped = new LinkedHashSet<>();

		ForgeBiomeBridge(List<Map.Entry<ResourceKey<net.minecraftforge.common.world.BiomeModifier>, net.minecraftforge.common.world.BiomeModifier>> modifiers,
				RegistryAccess registries) {
			this.modifiers = modifiers;
			this.registries = registries;
		}

		@Override
		public void modify(Holder<Biome> holder, Phase phase,
				net.neoforged.neoforge.common.world.ModifiableBiomeInfo.BiomeInfo.Builder builder) {
			if (standDown) return;
			net.minecraftforge.common.world.BiomeModifier.Phase forgePhase = BIOME_PHASES.get(phase);
			if (forgePhase == null) return;
			try {
				net.neoforged.neoforge.common.world.ModifiableBiomeInfo.BiomeInfo snapshot = builder.build();
				net.minecraftforge.common.world.ModifiableBiomeInfo.BiomeInfo.Builder forge =
						net.minecraftforge.common.world.ModifiableBiomeInfo.BiomeInfo.Builder.copyOf(forgeBiomeInfo(snapshot));
				for (Map.Entry<ResourceKey<net.minecraftforge.common.world.BiomeModifier>, net.minecraftforge.common.world.BiomeModifier> entry : modifiers) {
					if (dropped.contains(entry.getKey())) continue;
					try {
						entry.getValue().modify(holder, forgePhase, forge);
					} catch (Throwable t) {
						dropped.add(entry.getKey());
						String namespace = entry.getKey().identifier().getNamespace();
						ForbricLog.warn("[Forbric/Worldgen] MinecraftForge biome modifier " + entry.getKey().identifier()
								+ " threw during " + phase + " — dropped for the rest of this pass; " + namespace
								+ " is marked DEGRADED", Reflect.unwrap(t));
						ModCatalog.mark(namespace, ModCatalog.Status.DEGRADED, "its biome modifier "
								+ entry.getKey().identifier() + " threw during " + phase + " and was dropped");
					}
				}
				net.neoforged.neoforge.common.world.ModifiableBiomeInfo.BiomeInfo rebuilt = neoBiomeInfo(forge.build());
				if (encodeBiome(rebuilt, registries).equals(encodeBiome(snapshot, registries))) return;
				net.neoforged.neoforge.common.world.ModifiableBiomeInfo.BiomeInfo.Builder fresh =
						net.neoforged.neoforge.common.world.ModifiableBiomeInfo.BiomeInfo.Builder.copyOf(rebuilt);
				copyFields(NEO_BIOME_BUILDER_FIELDS, fresh, builder);
				holder.unwrapKey().ifPresent(key -> CHANGED_BIOMES.add(key.identifier().toString()));
			} catch (Throwable t) {
				standDown("bridging biome " + holder.unwrapKey().map(k -> k.identifier().toString()).orElse("?")
						+ " during " + phase + " failed: " + Reflect.unwrap(t));
			}
		}

		@Override
		public MapCodec<? extends net.neoforged.neoforge.common.world.BiomeModifier> codec() {
			return MapCodec.unit(this);
		}
	}

	static final class ForgeStructureBridge implements net.neoforged.neoforge.common.world.StructureModifier {
		private final List<Map.Entry<ResourceKey<net.minecraftforge.common.world.StructureModifier>, net.minecraftforge.common.world.StructureModifier>> modifiers;
		private final RegistryAccess registries;
		private final Set<ResourceKey<net.minecraftforge.common.world.StructureModifier>> dropped = new LinkedHashSet<>();

		ForgeStructureBridge(List<Map.Entry<ResourceKey<net.minecraftforge.common.world.StructureModifier>, net.minecraftforge.common.world.StructureModifier>> modifiers,
				RegistryAccess registries) {
			this.modifiers = modifiers;
			this.registries = registries;
		}

		@Override
		public void modify(Holder<Structure> holder, Phase phase,
				net.neoforged.neoforge.common.world.ModifiableStructureInfo.StructureInfo.Builder builder) {
			if (standDown) return;
			net.minecraftforge.common.world.StructureModifier.Phase forgePhase = STRUCTURE_PHASES.get(phase);
			if (forgePhase == null) return;
			try {
				net.neoforged.neoforge.common.world.ModifiableStructureInfo.StructureInfo snapshot = builder.build();
				net.minecraftforge.common.world.ModifiableStructureInfo.StructureInfo.Builder forge =
						net.minecraftforge.common.world.ModifiableStructureInfo.StructureInfo.Builder.copyOf(
								new net.minecraftforge.common.world.ModifiableStructureInfo.StructureInfo(snapshot.structureSettings()));
				for (Map.Entry<ResourceKey<net.minecraftforge.common.world.StructureModifier>, net.minecraftforge.common.world.StructureModifier> entry : modifiers) {
					if (dropped.contains(entry.getKey())) continue;
					try {
						entry.getValue().modify(holder, forgePhase, forge);
					} catch (Throwable t) {
						dropped.add(entry.getKey());
						String namespace = entry.getKey().identifier().getNamespace();
						ForbricLog.warn("[Forbric/Worldgen] MinecraftForge structure modifier " + entry.getKey().identifier()
								+ " threw during " + phase + " — dropped for the rest of this pass; " + namespace
								+ " is marked DEGRADED", Reflect.unwrap(t));
						ModCatalog.mark(namespace, ModCatalog.Status.DEGRADED, "its structure modifier "
								+ entry.getKey().identifier() + " threw during " + phase + " and was dropped");
					}
				}
				Structure.StructureSettings rebuilt = forge.build().structureSettings();
				if (encodeStructure(rebuilt, registries).equals(encodeStructure(snapshot.structureSettings(), registries))) return;
				net.neoforged.neoforge.common.world.ModifiableStructureInfo.StructureInfo.Builder fresh =
						net.neoforged.neoforge.common.world.ModifiableStructureInfo.StructureInfo.Builder.copyOf(
								new net.neoforged.neoforge.common.world.ModifiableStructureInfo.StructureInfo(rebuilt));
				copyFields(NEO_STRUCTURE_BUILDER_FIELDS, fresh, builder);
				holder.unwrapKey().ifPresent(key -> CHANGED_STRUCTURES.add(key.identifier().toString()));
			} catch (Throwable t) {
				standDown("bridging structure " + holder.unwrapKey().map(k -> k.identifier().toString()).orElse("?")
						+ " during " + phase + " failed: " + Reflect.unwrap(t));
			}
		}

		@Override
		public MapCodec<? extends net.neoforged.neoforge.common.world.StructureModifier> codec() {
			return MapCodec.unit(this);
		}
	}

	// ------------------------------------------------------------------------------------------------------
	// D3 ④: the pre-flight round-trip audit

	/**
	 * NeoForge → Forge → NeoForge for every biome and structure, compared by the same encodings. Runs only when a
	 * Forge modifier registry is non-empty; one difference stands the bridge down for the boot and marks every
	 * Forge modifier's namespace DEGRADED — loudly, rather than translating some biomes and not others.
	 */
	public static void auditRoundTrip(MinecraftServer server) {
		if (!enabled() || standDown) return;
		RegistryAccess registries = server.registryAccess();
		Optional<Registry<net.minecraftforge.common.world.BiomeModifier>> biomeModifiers = registries.lookup(ForgeRegistries.Keys.BIOME_MODIFIERS);
		Optional<Registry<net.minecraftforge.common.world.StructureModifier>> structureModifiers = registries.lookup(ForgeRegistries.Keys.STRUCTURE_MODIFIERS);
		boolean anyBiome = biomeModifiers.isPresent() && biomeModifiers.get().size() > 0;
		boolean anyStructure = structureModifiers.isPresent() && structureModifiers.get().size() > 0;
		if (!anyBiome && !anyStructure) return;
		int biomes = 0, structures = 0, differ = 0;
		String first = null;
		try {
			for (Map.Entry<ResourceKey<Biome>, Biome> entry : registries.lookupOrThrow(Registries.BIOME).entrySet()) {
				biomes++;
				net.neoforged.neoforge.common.world.ModifiableBiomeInfo.BiomeInfo original = entry.getValue().modifiableBiomeInfo().getOriginalBiomeInfo();
				net.neoforged.neoforge.common.world.ModifiableBiomeInfo.BiomeInfo back = neoBiomeInfo(
						net.minecraftforge.common.world.ModifiableBiomeInfo.BiomeInfo.Builder.copyOf(forgeBiomeInfo(original)).build());
				List<Optional<JsonElement>> a = encodeBiome(original, registries), b = encodeBiome(back, registries);
				if (!sameContent(a, b)) {
					differ++;
					if (first == null) first = entry.getKey().identifier() + " (part " + firstDifferingPart(a, b) + ")";
				}
			}
			for (Map.Entry<ResourceKey<Structure>, Structure> entry : registries.lookupOrThrow(Registries.STRUCTURE).entrySet()) {
				structures++;
				Structure.StructureSettings original = entry.getValue().modifiableStructureInfo().getOriginalStructureInfo().structureSettings();
				Structure.StructureSettings back = net.minecraftforge.common.world.ModifiableStructureInfo.StructureInfo.Builder.copyOf(
						new net.minecraftforge.common.world.ModifiableStructureInfo.StructureInfo(original)).build().structureSettings();
				if (!sameContent(List.of(encodeStructure(original, registries)),
						List.of(encodeStructure(back, registries)))) {
					differ++;
					if (first == null) first = entry.getKey().identifier() + " (structure settings)";
				}
			}
		} catch (Throwable t) {
			standDown("the round-trip audit itself failed: " + Reflect.unwrap(t));
			markAllDegraded(biomeModifiers, structureModifiers, "the MinecraftForge modifier bridge stood down before the world loaded");
			return;
		}
		ForbricLog.info("[Forbric/Worldgen] MinecraftForge builder round-trip: %d biome(s) and %d structure(s) checked, %d differ",
				biomes, structures, differ);
		if (differ > 0) {
			ForbricLog.warn("[Forbric/Worldgen] first difference: %s — MinecraftForge's builders would not reproduce this "
					+ "world's data unchanged, so its modifiers are NOT applied this boot", first);
			standDown(differ + " biome/structure(s) do not survive the MinecraftForge builder round-trip, first: " + first);
			markAllDegraded(biomeModifiers, structureModifiers, "its biome/structure modifiers were not applied: "
					+ "MinecraftForge's builders do not reproduce this world's data unchanged (first: " + first + ")");
		}
	}

	private static String firstDifferingPart(List<Optional<JsonElement>> a, List<Optional<JsonElement>> b) {
		String[] parts = { "climate", "effects", "generation", "mob spawns" };
		for (int i = 0; i < parts.length; i++) {
			if (!normalised(a.get(i)).equals(normalised(b.get(i)))) return parts[i];
		}
		return "?";
	}

	/**
	 * Whether two encoded parts describe the same WORLD, ignoring containers that hold nothing.
	 *
	 * <p>A straight JSON compare is too strict here, and its strictness had a price: MinecraftForge's builders
	 * rebuild a biome faithfully but do not keep an empty container the datapack happened to write down.
	 * Stellarity's biomes list all ten decoration steps with the last one empty and all seven spawner categories
	 * with six of them empty; the rebuilt copy stops at the last step that has anything in it. Nothing generates
	 * differently — an empty step and an absent step are the same instruction — but 36 of 98 biomes "differed",
	 * the audit concluded the builders would corrupt the world, and EVERY MinecraftForge biome and structure
	 * modifier in the pack stood down. Animal Garden's was the one that showed it.
	 *
	 * <p>Only empties are forgiven, and only where an empty means nothing: trailing empty arrays, and object
	 * members whose value is an empty array or object. A container that LOST content still differs, which is the
	 * case the audit exists for.
	 */
	private static boolean sameContent(List<Optional<JsonElement>> a, List<Optional<JsonElement>> b) {
		if (a.size() != b.size()) return false;
		for (int i = 0; i < a.size(); i++) {
			if (!normalised(a.get(i)).equals(normalised(b.get(i)))) return false;
		}
		return true;
	}

	static Optional<String> normalised(Optional<JsonElement> encoded) {
		return encoded.map(WorldDataShape::comparable);
	}


	private static void markAllDegraded(Optional<Registry<net.minecraftforge.common.world.BiomeModifier>> biomes,
			Optional<Registry<net.minecraftforge.common.world.StructureModifier>> structures, String detail) {
		Set<String> namespaces = new LinkedHashSet<>();
		biomes.ifPresent(r -> r.keySet().forEach(id -> namespaces.add(id.getNamespace())));
		structures.ifPresent(r -> r.keySet().forEach(id -> namespaces.add(id.getNamespace())));
		for (String namespace : namespaces) ModCatalog.mark(namespace, ModCatalog.Status.DEGRADED, detail);
	}
}
