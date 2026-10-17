/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.runtime;

import java.lang.reflect.Field;
import java.util.List;

import net.forbric.api.Ecosystem;
import net.forbric.api.ModPresence;
import net.forbric.kernel.classloading.ForbricClassLoader;
import net.forbric.kernel.interop.EcosystemTabPlacement;
import net.forbric.kernel.util.ForbricLog;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.CreativeModeTab;
import net.forbric.kernel.util.Caught;

/**
 * Called at the head of NeoForge's {@code CreativeModeTabRegistry.setCreativeModeTabOrder} (CreativeTabOrderInjector)
 * with the order NeoForge is about to publish; returns it with each tab placed by its owner's native rule
 * ({@link EcosystemTabPlacement}).
 *
 * <p>A tab's owner is the ecosystem whose code built it: the class of its icon supplier, then of its item generator
 * (a lambda is read as the class that wrote it), as the game loader knows which jar defined that class. Only when
 * neither answers is the mod its namespace belongs to asked.
 */
public final class KernelCreativeTabOrder {
	private static volatile boolean reported;
	private static Field icon, generator;

	private KernelCreativeTabOrder() {
	}

	public static List<CreativeModeTab> place(List<CreativeModeTab> sorted) {
		try {
			List<CreativeModeTab> placed = EcosystemTabPlacement.place(sorted,
					tab -> key(tab) == null ? null : key(tab).getNamespace(),
					tab -> key(tab) == null ? "" : key(tab).getPath(),
					tab -> !tab.tabsBefore.isEmpty() || !tab.tabsAfter.isEmpty(),
					KernelCreativeTabOrder::owner);
			if (placed != sorted && !reported) {
				reported = true;
				ForbricLog.info("[Forbric/CreativeTabs] Fabric mods' creative tabs follow the Fabric pager's rule — after "
						+ "every vanilla tab, in id order — instead of NeoForge's registration chain, which put them between "
						+ "vanilla's tabs; NeoForge and MinecraftForge mods' tabs keep NeoForge's order");
			}
			return placed;
		} catch (Throwable t) {
			Caught.warn(t, "[Forbric/CreativeTabs] could not place Fabric mods' creative tabs by their native rule — NeoForge's "
					+ "order stands");
			return sorted;
		}
	}

	/** The ecosystem whose code built {@code tab}; else the one owning its namespace; null when neither answers. */
	static Ecosystem owner(CreativeModeTab tab) {
		try {
			if (icon == null) {
				Field i = CreativeModeTab.class.getDeclaredField("iconGenerator"), g = CreativeModeTab.class.getDeclaredField("displayItemsGenerator");
				i.setAccessible(true);
				g.setAccessible(true);
				icon = i;
				generator = g;
			}
			for (Object part : new Object[] { icon.get(tab), generator.get(tab) }) {
				Ecosystem byCode = ecosystemOfCode(part);
				if (byCode != null) return byCode;
			}
		} catch (ReflectiveOperationException | RuntimeException unreadable) {
			Caught.warn(unreadable, "[Forbric/CreativeTabs] could not read the generators of creative tab %s; its owner is judged by its namespace", tab);
			// fall through to the namespace
		}
		Identifier key = key(tab);
		return key == null ? null : ModPresence.soleEcosystem(key.getNamespace());
	}

	private static Ecosystem ecosystemOfCode(Object part) {
		if (part == null) return null;
		Class<?> type = part.getClass();
		try {
			type = type.getNestHost();   // a lambda's hidden class answers as the class that wrote it
		} catch (RuntimeException | LinkageError unresolved) {
			Caught.expected(unresolved);
			// keep the class itself
		}
		return type.getClassLoader() instanceof ForbricClassLoader loader ? loader.ecosystemOfClass(type) : null;
	}

	private static Identifier key(CreativeModeTab tab) {
		return BuiltInRegistries.CREATIVE_MODE_TAB.getKey(tab);
	}
}
