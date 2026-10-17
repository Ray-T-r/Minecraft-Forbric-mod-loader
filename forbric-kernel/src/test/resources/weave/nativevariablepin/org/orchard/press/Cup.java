package org.orchard.press;

import fixture.nativevariablepin.Trace;

public final class Cup {
	private Cup() {
	}

	public static void pour(String juice, boolean sweet) {
		Trace.add("pour " + juice + " sweet=" + sweet);
	}
}
