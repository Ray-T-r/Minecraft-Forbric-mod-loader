package net.neoforged.neoforge.common;

import fixture.registerevents.Trace;

/** Stand-in: one step of postRegisterEvents' tail. */
public class CommonHooks {
	public static void modifyAttributes() {

		Trace.add("tail:modifyAttributes");
	}
}
