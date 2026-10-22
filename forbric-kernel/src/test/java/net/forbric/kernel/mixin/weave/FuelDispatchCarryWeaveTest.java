/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.mixin.weave;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import net.fabricmc.api.EnvType;
import net.forbric.api.Ecosystem;
import net.forbric.kernel.transform.FabricFuelValuesInjector;

/**
 * FabricFuelValuesInjector's carry through the real Mixin and MixinExtras weave and the kernel's real pipeline (its
 * post-Mixin stage included), on guests with names of their own.
 *
 * <p>A client on a NeoForge connection builds vanilla's fuel table — where a guest's {@code @WrapOperation} of the
 * {@code Builder.remove(TagKey)} before {@code build()} fires the public fuel events, and a guest's
 * {@code @ModifyReturnValue} adds its own fuel — and right after it the carrier's from the data map, which it keeps. The
 * probe plays a server and a client through a startup and a reload. Natively each phase fires the events twice and the
 * return hook twice (the server's table, the client's table). Carried, the client's data-map table gets the native
 * build's result — the listener calls replayed, the return hook's entry put on — and each phase fires twice, with the
 * same tables as when everything fires again ({@code -Dforbric.fabricFuel.carry=off}: three times). Without the wrap,
 * nothing proves the events fired at the native point, so the data-map table fires them itself.
 */
@net.forbric.kernel.transform.ExecutesInjector(FabricFuelValuesInjector.class)
class FuelDispatchCarryWeaveTest {
	private static final String MOD = "hearthkeep";
	private static final String CONFIG = "hearthkeep.mixins.json";
	private static final String NO_WRAP_CONFIG = "hearthkeep-nowrap.mixins.json";

	/** Both the server's and the client's tables hold the data map's coal, the listener's peat and the hook's kiln brick. */
	private static final String TABLES = " server={coal=1600, peat=800, kiln_brick=300} clientVanilla={coal=1600, peat=800, kiln_brick=300}"
			+ " clientDataMap={coal=1600, peat=800, kiln_brick=300}";
	private static final String CARRIED = WeaveHarnessMain.DONE + " startup builds=2 exclusions=2 hooks=2" + TABLES
			+ " | reload builds=2 exclusions=2 hooks=2" + TABLES;
	private static final String FIRED_AGAIN = WeaveHarnessMain.DONE + " startup builds=3 exclusions=3 hooks=3" + TABLES
			+ " | reload builds=3 exclusions=3 hooks=3" + TABLES;
	/** No wrap: the events fire only where the kernel fires them (the server's table, the client's data-map table). */
	private static final String NO_WRAP_TABLES = " server={coal=1600, peat=800, kiln_brick=300} clientVanilla={coal=1600, kiln_brick=300}"
			+ " clientDataMap={coal=1600, peat=800, kiln_brick=300}";
	private static final String NO_WRAP = WeaveHarnessMain.DONE + " startup builds=2 exclusions=2 hooks=2" + NO_WRAP_TABLES
			+ " | reload builds=2 exclusions=2 hooks=2" + NO_WRAP_TABLES;
	private static final String CARRY_LOG = "[Forbric/Fuel] the data-map fuel table got the result of the native fuel-event dispatch";
	private static final String DELTA_LOG = "[Forbric/Fuel] the data-map fuel table got what vanillaBurnTimes' return hooks did";

	@TempDir static Path work;
	private static Path fixture;
	private static WeaveHarness.Result carried, off, noWrap;

	private static final Map<String, String> SOURCES = Map.ofEntries(
			Map.entry("net/minecraft/core/HolderLookup.java", "package net.minecraft.core; public interface HolderLookup<T> { interface Provider { } }"),
			Map.entry("net/minecraft/core/RegistryAccess.java", "package net.minecraft.core; public interface RegistryAccess extends HolderLookup.Provider { }"),
			Map.entry("net/minecraft/world/flag/FeatureFlagSet.java", "package net.minecraft.world.flag; public class FeatureFlagSet { }"),
			Map.entry("net/minecraft/tags/TagKey.java", "package net.minecraft.tags; public record TagKey<T>(String id) { }"),
			Map.entry("net/minecraft/tags/ItemTags.java", """
					package net.minecraft.tags;
					import net.minecraft.world.item.Item;
					public class ItemTags {
					 public static final TagKey<Item> NON_FLAMMABLE_WOOD = new TagKey<>("minecraft:non_flammable_wood");
					 public static final TagKey<Item> SOGGY = new TagKey<>("minecraft:soggy");
					}
					"""),
			Map.entry("net/minecraft/core/Holder.java", """
					package net.minecraft.core;
					import java.util.Set;
					import net.minecraft.tags.TagKey;
					public record Holder<T>(Set<TagKey<T>> tags) { public boolean is(TagKey<T> tag) { return tags.contains(tag); } }
					"""),
			Map.entry("net/minecraft/world/item/Item.java", """
					package net.minecraft.world.item;
					import java.util.Set;
					import net.minecraft.core.Holder;
					import net.minecraft.tags.TagKey;
					public class Item {
					 private final String id; private final Holder<Item> holder;
					 public Item(String id, Set<TagKey<Item>> tags) { this.id = id; this.holder = new Holder<>(tags); }
					 public Holder<Item> builtInRegistryHolder() { return holder; }
					 @Override public String toString() { return id; }
					}
					"""),
			Map.entry("net/minecraft/world/item/Items.java", """
					package net.minecraft.world.item;
					import java.util.Set;
					import net.minecraft.tags.ItemTags;
					public class Items {
					 public static final Item COAL = new Item("coal", Set.of());
					 public static final Item BAMBOO = new Item("bamboo", Set.of(ItemTags.SOGGY));
					 public static final Item CRIMSON_PLANKS = new Item("crimson_planks", Set.of(ItemTags.NON_FLAMMABLE_WOOD));
					 public static final Item PEAT = new Item("peat", Set.of());
					 public static final Item KILN_BRICK = new Item("kiln_brick", Set.of());
					}
					"""),
			// Vanilla's shape: the two-argument overload forwards to the three-argument one, which builds the table and ends
			// in remove(NON_FLAMMABLE_WOOD).build().
			Map.entry("net/minecraft/world/level/block/entity/FuelValues.java", """
					package net.minecraft.world.level.block.entity;
					import java.util.LinkedHashMap;
					import java.util.Map;
					import net.minecraft.core.HolderLookup;
					import net.minecraft.tags.ItemTags;
					import net.minecraft.tags.TagKey;
					import net.minecraft.world.flag.FeatureFlagSet;
					import net.minecraft.world.item.Item;
					import net.minecraft.world.item.Items;
					public class FuelValues {
					 private final Map<Item, Integer> values;
					 FuelValues(Map<Item, Integer> values) { this.values = values; }
					 public Map<Item, Integer> view() { return values; }
					 public FuelValues with(Item item, int time) { Map<Item, Integer> copy = new LinkedHashMap<>(values); copy.put(item, time); return new FuelValues(copy); }
					 public static FuelValues vanillaBurnTimes(HolderLookup.Provider registries, FeatureFlagSet features) {
					  return vanillaBurnTimes(registries, features, 200);
					 }
					 public static FuelValues vanillaBurnTimes(HolderLookup.Provider registries, FeatureFlagSet features, int unit) {
					  return new Builder(registries, features).add(Items.COAL, unit * 8).add(Items.CRIMSON_PLANKS, unit)
					    .remove(ItemTags.NON_FLAMMABLE_WOOD).build();
					 }
					 public static class Builder {
					  private final FeatureFlagSet enabledFeatures;
					  private final Map<Item, Integer> values = new LinkedHashMap<>();
					  public Builder(HolderLookup.Provider registries, FeatureFlagSet features) { this.enabledFeatures = features; }
					  public Builder add(Item item, int time) { values.put(item, time); return this; }
					  public Builder remove(TagKey<Item> tag) { values.keySet().removeIf(item -> item.builtInRegistryHolder().is(tag)); return this; }
					  public FuelValues build() { return new FuelValues(new LinkedHashMap<>(values)); }
					 }
					}
					"""),
			Map.entry("net/neoforged/neoforge/common/DataMapHooks.java", """
					package net.neoforged.neoforge.common;
					import net.minecraft.core.RegistryAccess;
					import net.minecraft.world.flag.FeatureFlagSet;
					import net.minecraft.world.item.Items;
					import net.minecraft.world.level.block.entity.FuelValues;
					public class DataMapHooks {
					 public static FuelValues populateFuelValues(RegistryAccess registries, FeatureFlagSet features) {
					  FuelValues.Builder builder = new FuelValues.Builder(registries, features);
					  builder.add(Items.COAL, 1600);
					  builder.add(Items.BAMBOO, 50);
					  return builder.build();
					 }
					}
					"""),
			// fabric-api's public API, as a library contract.
			Map.entry("net/fabricmc/fabric/api/event/Event.java", """
					package net.fabricmc.fabric.api.event;
					import java.util.ArrayList;
					import java.util.List;
					import java.util.function.Function;
					public class Event<T> {
					 private final List<T> listeners = new ArrayList<>();
					 private final Function<List<T>, T> factory;
					 public Event(Function<List<T>, T> factory) { this.factory = factory; }
					 public void register(T listener) { listeners.add(listener); }
					 public T invoker() { return factory.apply(List.copyOf(listeners)); }
					}
					"""),
			Map.entry("net/fabricmc/fabric/api/registry/FuelValueEvents.java", """
					package net.fabricmc.fabric.api.registry;
					import net.fabricmc.fabric.api.event.Event;
					import net.minecraft.world.level.block.entity.FuelValues;
					public final class FuelValueEvents {
					 public interface Context { int baseSmeltTime(); }
					 public interface BuildCallback { void build(FuelValues.Builder builder, Context context); }
					 public interface ExclusionsCallback { void buildExclusions(FuelValues.Builder builder, Context context); }
					 public static final Event<BuildCallback> BUILD = new Event<>(listeners -> (builder, context) -> {
					  for (BuildCallback listener : listeners) listener.build(builder, context);
					 });
					 public static final Event<ExclusionsCallback> EXCLUSIONS = new Event<>(listeners -> (builder, context) -> {
					  for (ExclusionsCallback listener : listeners) listener.buildExclusions(builder, context);
					 });
					}
					"""),
			// A guest that fires the public events around vanilla's native removal, as a content library does.
			Map.entry("org/hearthkeep/mixin/HearthMixin.java", """
					package org.hearthkeep.mixin;
					import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
					import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
					import com.llamalad7.mixinextras.sugar.Local;
					import net.fabricmc.fabric.api.registry.FuelValueEvents;
					import net.minecraft.core.HolderLookup;
					import net.minecraft.tags.TagKey;
					import net.minecraft.world.flag.FeatureFlagSet;
					import net.minecraft.world.item.Item;
					import net.minecraft.world.level.block.entity.FuelValues;
					import org.spongepowered.asm.mixin.Mixin;
					import org.spongepowered.asm.mixin.injection.At;
					@Mixin(FuelValues.class)
					public class HearthMixin {
					 @WrapOperation(method = "vanillaBurnTimes(Lnet/minecraft/core/HolderLookup$Provider;Lnet/minecraft/world/flag/FeatureFlagSet;I)Lnet/minecraft/world/level/block/entity/FuelValues;",
					   at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/block/entity/FuelValues$Builder;remove(Lnet/minecraft/tags/TagKey;)Lnet/minecraft/world/level/block/entity/FuelValues$Builder;"))
					 private static FuelValues.Builder stoke(FuelValues.Builder builder, TagKey<Item> tag, Operation<FuelValues.Builder> original,
					   @Local(argsOnly = true) HolderLookup.Provider registries, @Local(argsOnly = true) FeatureFlagSet features, @Local(argsOnly = true) int unit) {
					  FuelValueEvents.Context context = () -> unit;
					  FuelValueEvents.BUILD.invoker().build(builder, context);
					  original.call(builder, tag);
					  FuelValueEvents.EXCLUSIONS.invoker().buildExclusions(builder, context);
					  return builder;
					 }
					}
					"""),
			// A guest that adds its own fuel on vanillaBurnTimes' return.
			Map.entry("org/hearthkeep/mixin/KilnMixin.java", """
					package org.hearthkeep.mixin;
					import com.llamalad7.mixinextras.injector.ModifyReturnValue;
					import net.minecraft.world.item.Items;
					import net.minecraft.world.level.block.entity.FuelValues;
					import org.hearthkeep.Counters;
					import org.spongepowered.asm.mixin.Mixin;
					import org.spongepowered.asm.mixin.injection.At;
					@Mixin(FuelValues.class)
					public class KilnMixin {
					 @ModifyReturnValue(method = "vanillaBurnTimes(Lnet/minecraft/core/HolderLookup$Provider;Lnet/minecraft/world/flag/FeatureFlagSet;I)Lnet/minecraft/world/level/block/entity/FuelValues;",
					   at = @At("RETURN"))
					 private static FuelValues kiln(FuelValues table) {
					  Counters.hooks++;
					  return table.with(Items.KILN_BRICK, 300);
					 }
					}
					"""),
			Map.entry("org/hearthkeep/Counters.java", "package org.hearthkeep; public class Counters { public static int builds, exclusions, hooks; }"),
			// Another mod's listeners: peat burns, so would crimson planks (vanilla's removal takes them), soggy items do not.
			Map.entry("org/hearthkeep/Peat.java", """
					package org.hearthkeep;
					import net.fabricmc.fabric.api.registry.FuelValueEvents;
					import net.minecraft.tags.ItemTags;
					import net.minecraft.world.item.Items;
					public class Peat {
					 public static void register() {
					  FuelValueEvents.BUILD.register((builder, context) -> {
					   Counters.builds++;
					   builder.add(Items.PEAT, context.baseSmeltTime() * 4);
					   builder.add(Items.CRIMSON_PLANKS, 300);
					  });
					  FuelValueEvents.EXCLUSIONS.register((builder, context) -> {
					   Counters.exclusions++;
					   builder.remove(ItemTags.SOGGY);
					  });
					 }
					}
					"""),
			// A server and a client, through a startup and a reload: the server builds from the data map; the client builds
			// vanilla's table (its listener's constructor, handleUpdateTags) and then the data map's (handleUpdateRecipes).
			Map.entry("org/hearthkeep/Probe.java", """
					package org.hearthkeep;
					import java.util.ArrayList;
					import java.util.List;
					import java.util.stream.Collectors;
					import net.minecraft.core.RegistryAccess;
					import net.minecraft.world.flag.FeatureFlagSet;
					import net.minecraft.world.level.block.entity.FuelValues;
					import net.neoforged.neoforge.common.DataMapHooks;
					public class Probe {
					 public String probe() {
					  Peat.register();
					  RegistryAccess server = new RegistryAccess() { }, client = new RegistryAccess() { };
					  FeatureFlagSet features = new FeatureFlagSet();
					  List<String> phases = new ArrayList<>();
					  for (String phase : List.of("startup", "reload")) {
					   Counters.builds = Counters.exclusions = Counters.hooks = 0;
					   FuelValues onServer = DataMapHooks.populateFuelValues(server, features);
					   FuelValues clientVanilla = FuelValues.vanillaBurnTimes(client, features);
					   FuelValues clientDataMap = DataMapHooks.populateFuelValues(client, features);
					   phases.add(phase + " builds=" + Counters.builds + " exclusions=" + Counters.exclusions + " hooks=" + Counters.hooks
					     + " server=" + show(onServer) + " clientVanilla=" + show(clientVanilla) + " clientDataMap=" + show(clientDataMap));
					  }
					  return String.join(" | ", phases);
					 }
					 static String show(FuelValues table) {
					  return table.view().entrySet().stream().map(e -> e.getKey() + "=" + e.getValue()).collect(Collectors.joining(", ", "{", "}"));
					 }
					}
					"""));

	@BeforeAll static void weave() throws Exception {
		List<Path> sources = new ArrayList<>();
		for (var source : SOURCES.entrySet()) {
			Path file = work.resolve("src").resolve(source.getKey());
			Files.createDirectories(file.getParent());
			Files.writeString(file, source.getValue());
			sources.add(file);
		}
		sources.add(Path.of("src/runtime/java/net/forbric/kernel/runtime/KernelFabricFuel.java"));
		sources.add(Path.of("src/runtime/java/net/forbric/kernel/runtime/KernelFuelValueContext.java"));
		Path json = work.resolve(CONFIG), noWrapJson = work.resolve(NO_WRAP_CONFIG);
		Files.writeString(json, "{\"required\":true,\"package\":\"org.hearthkeep.mixin\",\"mixins\":[\"HearthMixin\",\"KilnMixin\"],"
				+ "\"injectors\":{\"defaultRequire\":1}}");
		Files.writeString(noWrapJson, "{\"required\":true,\"package\":\"org.hearthkeep.mixin\",\"mixins\":[\"KilnMixin\"],"
				+ "\"injectors\":{\"defaultRequire\":1}}");
		fixture = WeaveHarness.fixture(work, "fuelcarry", sources, Map.of(CONFIG, json, NO_WRAP_CONFIG, noWrapJson), List.of("-g"));
		carried = run("carried", CONFIG, Map.of());
		off = run("off", CONFIG, Map.of(FabricFuelValuesInjector.CARRY_PROPERTY, "off"));
		noWrap = run("nowrap", NO_WRAP_CONFIG, Map.of());
	}

	@Test void eachNativeBuildFiresTheEventsAndTheReturnHookOnceAndTheClientsDataMapTableGetsTheirResult() throws Exception {
		assertTrue(carriedHolds(carried), carried.describe() + "\nfindings: " + carried.findings());
		WeaveHarness.assertWovenAndVerified(carried, "net/minecraft/world/level/block/entity/FuelValues", fixture);
		WeaveHarness.assertWovenAndVerified(carried, "net/minecraft/world/level/block/entity/FuelValues$Builder", fixture);
	}

	@Test void switchedOffTheClientsDataMapTableFiresEverythingAgain() {
		assertTrue(offHolds(off), off.describe());
	}

	@Test void withoutTheWrapNothingProvesTheNativeDispatchAndTheDataMapTableFiresTheEventsItself() {
		assertTrue(noWrap.printedLine(NO_WRAP), noWrap.describe());
		assertFalse(noWrap.printed(CARRY_LOG), "no dispatch was carried: " + noWrap.describe());
		assertTrue(noWrap.printed(DELTA_LOG), "the return hook is carried all the same: " + noWrap.describe());
	}

	@Test void theControlFlipsEveryAssertion() {
		assertTrue(carriedHolds(carried) && !carriedHolds(off), "the carry predicate does not separate the runs");
		assertTrue(offHolds(off) && !offHolds(carried), "the control predicate does not separate the runs");
	}

	private static boolean carriedHolds(WeaveHarness.Result run) {
		return run.printedLine(CARRIED) && run.printed(CARRY_LOG) && run.printed(DELTA_LOG) && losses(run).isEmpty();
	}

	private static boolean offHolds(WeaveHarness.Result run) {
		return run.printedLine(FIRED_AGAIN) && !run.printed(CARRY_LOG) && !run.printed(DELTA_LOG) && losses(run).isEmpty();
	}

	/**
	 * Confirmed losses only: the audit marks both guests SUSPECTED ("attached only inside a forwarding stub") in every run,
	 * this fixture's three-argument overload being called only through the two-argument one; the counts above are what
	 * show each guest running.
	 */
	private static List<WeaveHarness.Finding> losses(WeaveHarness.Result run) {
		return run.findings().stream().filter(f -> f.id().startsWith("mixin-injector:") && f.modId().equals(MOD) && f.confirmedRequired()).toList();
	}

	private static WeaveHarness.Result run(String label, String config, Map<String, String> properties) throws Exception {
		return WeaveHarness.run(work, label, fixture, List.of(new WeaveHarness.Config(config, MOD, Ecosystem.FABRIC)),
				List.of(FabricFuelValuesInjector.class), EnvType.CLIENT, "org.hearthkeep.Probe", "probe", properties);
	}
}
