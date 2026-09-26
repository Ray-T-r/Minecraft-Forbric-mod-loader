/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.runtime;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import it.unimi.dsi.fastutil.objects.AbstractObject2FloatMap;
import it.unimi.dsi.fastutil.objects.Object2FloatMap;
import it.unimi.dsi.fastutil.objects.Object2FloatOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectOpenHashSet;
import it.unimi.dsi.fastutil.objects.ObjectSet;

import net.forbric.kernel.util.ForbricLog;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.ItemLike;
import net.minecraft.world.level.block.ComposterBlock;
import net.neoforged.neoforge.registries.datamaps.builtin.Compostable;
import net.neoforged.neoforge.registries.datamaps.builtin.NeoForgeDataMaps;

/**
 * What the vanilla compostables map still has to say to the merged composter (CompostablesFallbackInjector).
 *
 * <p>The merged {@code ComposterBlock} asks only NeoForge's {@code neoforge:compostables} data map
 * ({@code getValue(ItemStack)}), while Fabric-side code still writes and wraps vanilla's {@code COMPOSTABLES}:
 * fabric-content-registries' {@code CompostableRegistry.add} puts into it, and BCLib wraps its {@code containsKey} and
 * {@code getFloat} calls to add every item with a compostable trait. Nothing read the map any more, so none of that
 * reached a composter — by hand, hopper or dropper.
 *
 * <p>The data map stays the decision for everything it lists. Only on a miss does the composter ask the vanilla map,
 * and then through {@link #fallback}, a view that hides vanilla's OWN entries: {@code bootStrap}'s {@code add} calls
 * are recorded as they are made ({@link #vanilla}), and an entry still holding the chance vanilla gave it is not
 * shown. Otherwise a datapack that removes wheat seeds from NeoForge's data map would see them come back through the
 * map bootstrap filled. What IS shown is what somebody else put there, or changed, after vanilla — and nothing at all
 * until the bootstrap was seen, so a declined transform cannot turn the whole vanilla table into "added by a mod".
 *
 * <p>The one thing this cannot honour: a mod removing or re-weighting a VANILLA compostable in the map. The data map
 * still lists it and answers first. That is said once, the first time the fallback is used.
 */
public final class KernelCompostables {
	private static final Object2FloatMap<Object> VANILLA = new Object2FloatOpenHashMap<>();
	private static volatile boolean recorded;
	private static final AtomicBoolean DIVERGENCE_CHECKED = new AtomicBoolean();
	private static volatile Delta<ItemLike> view;

	private KernelCompostables() {
	}

	/** Called by {@code ComposterBlock.bootStrap} with the arguments of each of its own {@code add(chance, item)} calls. */
	public static void vanilla(float chance, ItemLike item) {
		synchronized (VANILLA) {
			VANILLA.put(item.asItem(), chance);
		}
		recorded = true;
	}

	/** The vanilla map as the merged composter may still consult it: entries added or changed after bootstrap only. */
	public static Object2FloatMap<ItemLike> fallback() {
		Delta<ItemLike> current = view;
		if (current == null) {
			synchronized (KernelCompostables.class) {
				if (view == null) view = new Delta<>(ComposterBlock.COMPOSTABLES, VANILLA, () -> recorded);
				current = view;
			}
		}
		if (recorded && DIVERGENCE_CHECKED.compareAndSet(false, true)) reportDivergence();
		return current;
	}

	/**
	 * The composter's "is this compostable" answer from NeoForge's value and the fallback's {@code containsKey}.
	 * NeoForge's value decides whenever the data map listed the item (its chance is in [0, 1]; a miss is -1); on a miss
	 * a {@code true} from the map (or from whatever wraps its call) is any positive value, {@code false} stays -1.
	 */
	public static float orContains(float neoForge, boolean fallback) {
		return neoForge >= 0.0F ? neoForge : fallback ? 1.0F : -1.0F;
	}

	/** The composter's chance from NeoForge's value and the fallback's {@code getFloat}; the data map decides when it listed the item. */
	public static float orChance(float neoForge, float fallback) {
		return neoForge >= 0.0F ? neoForge : fallback;
	}

	/**
	 * The chance the merged composter itself would use for {@code item}, as a map: fabric-transfer's
	 * {@code ComposterWrapper} reads {@code COMPOSTABLES.getFloat} straight, so without this a Fabric pipe and the block
	 * would disagree about every item the data map and the vanilla map list differently.
	 */
	public static Object2FloatMap<ItemLike> effective() {
		return EFFECTIVE;
	}

	private static final Object2FloatMap<ItemLike> EFFECTIVE = new AbstractObject2FloatMap<>() {
		private static final long serialVersionUID = 1L;

		{
			defaultReturnValue(-1.0F);
		}

		@Override public float getFloat(Object key) {
			float neoForge = neoForge(key);
			return neoForge >= 0.0F ? neoForge : fallback().getFloat(key);
		}

		@Override public boolean containsKey(Object key) {
			return neoForge(key) >= 0.0F || fallback().containsKey(key);
		}

		@Override public int size() {
			return entries().size();
		}

		@Override public ObjectSet<Object2FloatMap.Entry<ItemLike>> object2FloatEntrySet() {
			return entries();
		}

		private ObjectSet<Object2FloatMap.Entry<ItemLike>> entries() {
			ObjectSet<Object2FloatMap.Entry<ItemLike>> out = new ObjectOpenHashSet<>();
			for (Item item : BuiltInRegistries.ITEM) {
				float chance = getFloat(item);
				if (containsKey(item)) out.add(new BasicEntry<>(item, chance));
			}
			return out;
		}
	};

	private static float neoForge(Object key) {
		if (!(key instanceof ItemLike like)) return -1.0F;
		Compostable compostable = like.asItem().builtInRegistryHolder().getData(NeoForgeDataMaps.COMPOSTABLES);
		return compostable == null ? -1.0F : compostable.chance();
	}

	/** Once: vanilla compostables a mod removed from, or re-weighted in, the vanilla map — the data map still decides them. */
	private static void reportDivergence() {
		List<String> removed = new ArrayList<>(), changed = new ArrayList<>();
		synchronized (VANILLA) {
			for (Object2FloatMap.Entry<Object> entry : VANILLA.object2FloatEntrySet()) {
				Object item = entry.getKey();
				if (!ComposterBlock.COMPOSTABLES.containsKey(item)) removed.add(String.valueOf(item));
				else if (Float.floatToIntBits(ComposterBlock.COMPOSTABLES.getFloat(item)) != Float.floatToIntBits(entry.getFloatValue())) {
					changed.add(String.valueOf(item));
				}
			}
		}
		if (!removed.isEmpty()) {
			ForbricLog.warn("[Forbric/Composter] a mod removed %d vanilla compostable(s) from ComposterBlock.COMPOSTABLES (%s); "
					+ "NeoForge's compostables data map still lists them and the merged composter follows the data map",
					removed.size(), sample(removed));
		}
		if (!changed.isEmpty()) {
			ForbricLog.warn("[Forbric/Composter] a mod changed the chance of %d vanilla compostable(s) in ComposterBlock.COMPOSTABLES "
					+ "(%s); the chance in NeoForge's compostables data map applies while it lists them", changed.size(), sample(changed));
		}
	}

	private static String sample(List<String> items) {
		return items.size() <= 8 ? String.join(", ", items) : String.join(", ", items.subList(0, 8)) + ", …";
	}

	/**
	 * A read-only face on {@code backing} without the entries {@code vanilla} recorded at the same chance. Nothing at all
	 * until {@code recorded} says the bootstrap was seen. Absent keys answer -1, as {@code COMPOSTABLES} does.
	 */
	static final class Delta<K> extends AbstractObject2FloatMap<K> {
		private static final long serialVersionUID = 1L;

		private final Object2FloatMap<K> backing;
		private final Object2FloatMap<Object> vanilla;
		private final java.util.function.BooleanSupplier recorded;

		Delta(Object2FloatMap<K> backing, Object2FloatMap<Object> vanilla, java.util.function.BooleanSupplier recorded) {
			this.backing = backing;
			this.vanilla = vanilla;
			this.recorded = recorded;
			defaultReturnValue(-1.0F);
		}

		@Override public boolean containsKey(Object key) {
			if (!recorded.getAsBoolean() || !backing.containsKey(key)) return false;
			synchronized (vanilla) {
				return !vanilla.containsKey(key)
						|| Float.floatToIntBits(vanilla.getFloat(key)) != Float.floatToIntBits(backing.getFloat(key));
			}
		}

		@Override public float getFloat(Object key) {
			return containsKey(key) ? backing.getFloat(key) : defaultReturnValue();
		}

		@Override public int size() {
			return object2FloatEntrySet().size();
		}

		@Override public ObjectSet<Object2FloatMap.Entry<K>> object2FloatEntrySet() {
			ObjectSet<Object2FloatMap.Entry<K>> out = new ObjectOpenHashSet<>();
			for (Object2FloatMap.Entry<K> entry : backing.object2FloatEntrySet()) {
				if (containsKey(entry.getKey())) out.add(new BasicEntry<>(entry.getKey(), entry.getFloatValue()));
			}
			return out;
		}
	}
}
