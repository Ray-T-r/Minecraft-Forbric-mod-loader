package org.atelier.loom;

import fixture.nativecallinline.Trace;
import org.atelier.scene.Panel;

/** The carrier's own events around each panel it paints. */
public final class Bulletin {
	private Bulletin() {
	}

	public static boolean before(Panel panel) {
		Trace.add("before:" + panel.name);
		return false;
	}

	public static void after(Panel panel) {
		Trace.add("after:" + panel.name);
	}

	public static void post(Notice notice) {
		Trace.add("notice:" + notice.walker.name);
	}
}
