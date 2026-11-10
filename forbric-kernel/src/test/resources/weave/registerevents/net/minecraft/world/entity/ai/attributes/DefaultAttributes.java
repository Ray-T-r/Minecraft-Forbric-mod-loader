package net.minecraft.world.entity.ai.attributes;

import fixture.registerevents.Trace;

/** Stand-in: the kernel initialises it before the attribute events. */
public class DefaultAttributes {
	static {
		Trace.add("DefaultAttributes:init");
	}
}
