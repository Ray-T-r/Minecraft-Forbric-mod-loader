package org.atelier.loom;

import org.atelier.scene.Walker;
import org.atelier.scene.World;

/** The carrier's event about one walker; its own answer asks the sky as the host once did. */
public class Notice {
	public final Walker walker;
	public boolean forced;

	public Notice(Walker walker) {
		this.walker = walker;
	}

	public boolean allows(World world) {
		if (forced) return true;
		return world.sky().open();
	}
}
