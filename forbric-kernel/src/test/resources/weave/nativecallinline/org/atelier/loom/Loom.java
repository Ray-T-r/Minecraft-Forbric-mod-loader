package org.atelier.loom;

import java.util.ArrayList;
import java.util.List;

import fixture.nativecallinline.Trace;
import org.atelier.scene.Actor;
import org.atelier.scene.Brush;
import org.atelier.scene.Panel;

/** The carrier's layered painting: every backdrop, then the front panel the host painted itself. */
public abstract class Loom extends Actor {
	private final List<Panel> backdrops = new ArrayList<>();

	public void push(Panel backdrop) {
		backdrops.add(backdrop);
	}

	protected abstract Panel front();

	protected void weave(Brush brush, int x, int y, float delta) {
		Trace.add("weave-begin");
		for (Panel backdrop : backdrops) weaveOne(backdrop, brush, Integer.MAX_VALUE, Integer.MAX_VALUE, delta);
		weaveOne(front(), brush, x, y, delta);
		Trace.add("weave-end");
	}

	private static void weaveOne(Panel panel, Brush brush, int x, int y, float delta) {
		if (!Bulletin.before(panel)) panel.paint(brush, x, y, delta);
		Bulletin.after(panel);
	}
}
