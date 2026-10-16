package org.atelier.scene;

import fixture.nativecallinline.Trace;
import org.atelier.loom.Loom;

/** The host as the mod's own loader has it: it makes each call itself. */
public class Stage extends Loom {
	public Panel front = new Panel("front");
	public final Clock clock = new Clock();

	@Override
	protected Panel front() {
		return front;
	}

	public void render(Brush brush, int x, int y) {
		Trace.add("render-begin");
		this.front.paint(brush, x, y, clock.game());
		Trace.add("render-end");
	}

	public void drop(boolean all) {
		Item removed = this.held;
		this.held = null;
		this.discard(removed, false, true);
	}

	@Override
	public Token discard(Item item, boolean silent, boolean named) {
		Trace.add("stage-discard:" + item.name);
		return super.discard(item, silent, named);
	}

	public void tick(World world) {
		if (!world.sky().open()) return;
		for (Walker walker : world.walkers) {
			if (world.sky().open()) Trace.add("spawn-for:" + walker.name);
		}
	}
}
