package org.atelier.scene;

import fixture.nativecallinline.Trace;
import org.atelier.loom.Loom;
import org.atelier.loom.Notice;
import org.atelier.loom.Notices;
import org.atelier.loom.Relay;

/** The host as merged: each of the three calls a mod anchors on is now made inside one of the carrier's helpers. */
public class Stage extends Loom {
	public Panel front = new Panel("front");
	public final Clock clock = new Clock();

	@Override
	protected Panel front() {
		return front;
	}

	public void render(Brush brush, int x, int y) {
		Trace.add("render-begin");
		this.weave(brush, x, y, clock.real());
		Trace.add("render-end");
	}

	public void drop(boolean all) {
		Item removed = this.held;
		this.held = null;
		Relay.toss(this, removed, false, true);
	}

	@Override
	public Token discard(Item item, boolean silent, boolean named) {
		Trace.add("stage-discard:" + item.name);
		return super.discard(item, silent, named);
	}

	public void tick(World world) {
		if (!world.sky().open()) return;
		for (Walker walker : world.walkers) {
			Notice notice = Notices.raise(walker, world);
			if (notice.allows(world)) Trace.add("spawn-for:" + walker.name);
		}
	}
}
