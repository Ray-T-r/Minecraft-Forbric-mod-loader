package org.tavern.hall;

import fixture.consumerstores.Trace;

public final class Chime {
	private Chime() {
	}

	public static void play(String where, String tone, float volume) {
		Trace.add("play " + tone + " in " + where);
	}
}
