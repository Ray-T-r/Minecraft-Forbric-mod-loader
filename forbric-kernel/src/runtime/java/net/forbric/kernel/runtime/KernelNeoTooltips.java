/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.runtime;

import net.forbric.kernel.boot.KernelLifecycle;
import net.forbric.kernel.util.ForbricLog;
import net.neoforged.neoforge.common.tooltip.ItemTooltipHandler;
import net.neoforged.neoforge.event.RegisterTooltipAppendersEvent;

/**
 * NeoForge's item tooltip appenders, built on the merged base.
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
 */
public final class KernelNeoTooltips {
	private KernelNeoTooltips() {
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

	/** {@code ItemTooltipHandler.init}'s registration event, one container at a time. */
	public static void postRegisterAppenders(RegisterTooltipAppendersEvent event) {
		KernelLifecycle.postModBusEvent(event);
	}
}
