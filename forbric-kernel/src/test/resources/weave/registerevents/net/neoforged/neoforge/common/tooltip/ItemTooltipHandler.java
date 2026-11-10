package net.neoforged.neoforge.common.tooltip;

import fixture.registerevents.Trace;

/** Stand-in: one step of postRegisterEvents' tail. */
public class ItemTooltipHandler {
	public static void init() {

		Trace.add("tail:init");
	}
}
