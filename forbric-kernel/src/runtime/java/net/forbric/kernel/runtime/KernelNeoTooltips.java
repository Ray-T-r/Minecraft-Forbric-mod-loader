/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.runtime;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

import net.forbric.kernel.boot.KernelLifecycle;
import net.forbric.kernel.transform.GuestInjectorPruner;
import net.forbric.kernel.transform.NeoTooltipAppendersInjector;
import net.forbric.kernel.util.ForbricLog;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.TooltipDisplay;
import net.neoforged.neoforge.common.tooltip.ItemTooltipHandler;
import net.neoforged.neoforge.common.tooltip.TooltipAppender;
import net.neoforged.neoforge.common.tooltip.TooltipLocation;
import net.neoforged.neoforge.event.RegisterTooltipAppendersEvent;

/**
 * NeoForge's item tooltip appenders, built on the merged base, with Fabric's component tooltip providers in them.
 *
 * <p>The merged {@code ItemStack.addDetailsToTooltip} is NeoForge's dispatcher: it walks the head, middle and tail
 * appender lists {@code ItemTooltipHandler.init} fills — every vanilla component line (enchantments, lore, attribute
 * modifiers, potion effects, durability, …) and every mod's. Its only caller is {@code GameData.postRegisterEvents},
 * which the kernel replaces with its own copy of the tail, and that copy left it out: tooltips showed the name and
 * the item's own lines and nothing else. The kernel's tail calls {@link #init} in its place.
 *
 * <p>{@code init} posts {@code RegisterTooltipAppendersEvent} through {@code ModLoader.postEvent}, which stops at the
 * first mod that throws and cost every later mod its appenders; NeoTooltipAppendersInjector sends it through the
 * kernel's per-container delivery instead ({@link #postRegisterAppenders}).
 *
 * <p>fabric-item-api's {@code ItemComponentTooltipProviderRegistry} places a mod's component lines first, last, or
 * before or after a vanilla component's. Its own injectors assume vanilla's single tooltip body and cannot work on
 * NeoForge's dispatcher, so the pruner removes them and they are drawn here instead, in the same places: first right
 * after the item's own lines (ahead of NeoForge mods' there), last right above the advanced item id (after NeoForge
 * mods' there), and before/after each vanilla component around that component's own appender. Fabric's lists are
 * read on every tooltip, as Fabric reads them, so a provider registered after NeoForge's event — every client
 * entrypoint is — still shows. Only while fabric-item-api's injectors were actually pruned: otherwise the one that
 * does draw would draw the same lines a second time.
 */
public final class KernelNeoTooltips {
	private static final String IMPL = "net.fabricmc.fabric.impl.item.ItemComponentTooltipProviderRegistryImpl";
	private static final AtomicBoolean PROVIDER_FAILED = new AtomicBoolean();
	private static final AtomicBoolean ORDER_CHECKED = new AtomicBoolean();
	private static volatile Fabric fabric;
	private static volatile boolean resolved;

	private KernelNeoTooltips() {
	}

	/** fabric-item-api's registry, as its own mixin calls it. */
	record Fabric(MethodHandle hasModdedEntries, MethodHandle onFirst, MethodHandle onLast, MethodHandle onBefore,
			MethodHandle onAfter) {
	}

	/** Once per process: {@code init} adds to four static lists, so a second call would draw every line twice. */
	public static void init() {
		if (!ItemTooltipHandler.getVanillaAppenderOrder().isEmpty()) {
			ForbricLog.debug("[Forbric/Tooltips] NeoForge tooltip appenders already built");
			return;
		}
		ItemTooltipHandler.init();
		int vanilla = ItemTooltipHandler.getVanillaAppenderOrder().size();
		if (vanilla == 0) {
			ForbricLog.warn("[Forbric/Tooltips] NeoForge built its tooltip appenders with no vanilla component in them — "
					+ "item tooltips show no component lines");
		} else {
			ForbricLog.info("[Forbric/Tooltips] NeoForge tooltip appenders built: %d vanilla component appender(s) — item "
					+ "tooltips show enchantments, lore, attributes and durability again", vanilla);
		}
	}

	/** {@code ItemTooltipHandler.init}'s registration event, one container at a time; Fabric's first and last around it. */
	public static void postRegisterAppenders(RegisterTooltipAppendersEvent event) {
		Fabric bridge = fabric();
		if (bridge != null) event.registerAppender(TooltipLocation.POST_CUSTOM, KernelNeoTooltips::first);
		KernelLifecycle.postModBusEvent(event);
		if (bridge != null) {
			event.registerAppender(TooltipLocation.PRE_ITEM_INFO, KernelNeoTooltips::last);
			ForbricLog.info("[Forbric/Tooltips] fabric-item-api's component tooltip providers are drawn from NeoForge's "
					+ "appenders — first, last, and before/after each vanilla component");
		}
	}

	/** Called by {@code ItemTooltipHandler.addDataComponentAppenders} with each component's appender as it is listed. */
	public static TooltipAppender around(TooltipAppender inner, DataComponentType<?> type) {
		if (inner == null || type == null || !ItemTooltipHandler.getVanillaAppenderOrder().contains(type)) return inner;
		Fabric bridge = fabric();
		if (bridge == null) return inner;
		return (stack, context, display, player, flag, out) -> {
			if (modded(bridge)) call(bridge.onBefore(), stack, type, context, display, out, flag);
			inner.append(stack, context, display, player, flag, out);
			if (modded(bridge)) call(bridge.onAfter(), stack, type, context, display, out, flag);
		};
	}

	private static void first(ItemStack stack, Item.TooltipContext context, TooltipDisplay display, Player player,
			TooltipFlag flag, Consumer<Component> out) {
		Fabric bridge = fabric();
		if (bridge != null && modded(bridge)) call(bridge.onFirst(), stack, null, context, display, out, flag);
	}

	private static void last(ItemStack stack, Item.TooltipContext context, TooltipDisplay display, Player player,
			TooltipFlag flag, Consumer<Component> out) {
		Fabric bridge = fabric();
		if (bridge != null && modded(bridge)) call(bridge.onLast(), stack, null, context, display, out, flag);
	}

	private static boolean modded(Fabric bridge) {
		boolean modded;
		try {
			modded = (boolean) bridge.hasModdedEntries().invokeExact();
		} catch (Throwable t) {
			return false;
		}
		if (modded && ORDER_CHECKED.compareAndSet(false, true)) warnOnOrderDrift();
		return modded;
	}

	/** A provider that throws costs its own lines, once reported; the vanilla line beside it is always drawn. */
	private static void call(MethodHandle handle, ItemStack stack, DataComponentType<?> type, Item.TooltipContext context,
			TooltipDisplay display, Consumer<Component> out, TooltipFlag flag) {
		try {
			if (type == null) {
				handle.invokeExact(stack, context, display, out, flag);
			} else {
				Set<DataComponentType<?>> visited = new HashSet<>();
				visited.add(type);
				handle.invokeExact(stack, (DataComponentType<?>) type, context, display, out, flag, visited);
			}
		} catch (Throwable t) {
			if (PROVIDER_FAILED.compareAndSet(false, true)) {
				ForbricLog.warn("[Forbric/Tooltips] a Fabric component tooltip provider threw — its lines are left out of "
						+ "this tooltip, the rest is drawn", t);
			}
		}
	}

	/** fabric-item-api's registry, when it is installed, its injectors were pruned and the around splice is in. */
	static Fabric fabric() {
		if (resolved) return fabric;
		synchronized (KernelNeoTooltips.class) {
			if (resolved) return fabric;
			fabric = resolve();
			resolved = true;
			return fabric;
		}
	}

	private static Fabric resolve() {
		if (!GuestInjectorPruner.fabricTooltipBridgeOn()) return null;
		Class<?> impl;
		try {
			impl = Class.forName(IMPL, false, ItemStack.class.getClassLoader());
		} catch (ClassNotFoundException | LinkageError absent) {
			return null;
		}
		if (!GuestInjectorPruner.fabricTooltipInjectorsPruned() || !NeoTooltipAppendersInjector.aroundSpliced()) {
			ForbricLog.warn("[Forbric/Tooltips] fabric-item-api is installed but its tooltip injectors were %s — the "
					+ "kernel does not draw its component tooltip providers, so they show only where those injectors do",
					GuestInjectorPruner.fabricTooltipInjectorsPruned() ? "pruned without NeoForge's appender splice"
							: "not pruned");
			return null;
		}
		try {
			MethodHandles.Lookup lookup = MethodHandles.publicLookup();
			MethodType ends = MethodType.methodType(void.class, ItemStack.class, Item.TooltipContext.class, TooltipDisplay.class,
					Consumer.class, TooltipFlag.class);
			MethodType sides = MethodType.methodType(void.class, ItemStack.class, DataComponentType.class, Item.TooltipContext.class,
					TooltipDisplay.class, Consumer.class, TooltipFlag.class, Set.class);
			Fabric found = new Fabric(lookup.findStatic(impl, "hasModdedEntries", MethodType.methodType(boolean.class)),
					lookup.findStatic(impl, "onFirst", ends), lookup.findStatic(impl, "onLast", ends),
					lookup.findStatic(impl, "onBefore", sides), lookup.findStatic(impl, "onAfter", sides));
			return found;
		} catch (ReflectiveOperationException drifted) {
			ForbricLog.warn("[Forbric/Tooltips] fabric-item-api's tooltip registry is not the one the kernel draws from — "
					+ "a Fabric mod's component tooltip providers are not drawn", drifted);
			return null;
		}
	}

	/** Fabric's anchors are its own scrape of vanilla's order; one NeoForge does not list never fires. */
	private static void warnOnOrderDrift() {
		try {
			ClassLoader loader = ItemStack.class.getClassLoader();
			@SuppressWarnings("unchecked")
			List<DataComponentType<?>> order = (List<DataComponentType<?>>) Class.forName(
					"net.fabricmc.fabric.impl.item.VanillaTooltipProviderOrder", true, loader).getMethod("getVanillaOrder").invoke(null);
			List<DataComponentType<?>> neo = ItemTooltipHandler.getVanillaAppenderOrder();
			if (!neo.isEmpty() && !order.equals(neo)) {
				ForbricLog.warn("[Forbric/Tooltips] Fabric's vanilla tooltip order (%d) differs from NeoForge's (%d) — a "
						+ "provider anchored on a component NeoForge does not list is not drawn", order.size(), neo.size());
			}
		} catch (ReflectiveOperationException | LinkageError | RuntimeException unreadable) {
			ForbricLog.debug("[Forbric/Tooltips] could not read Fabric's vanilla tooltip order: %s", unreadable);
		}
	}
}
