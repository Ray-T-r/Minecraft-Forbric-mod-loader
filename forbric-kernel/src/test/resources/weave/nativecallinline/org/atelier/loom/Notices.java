package org.atelier.loom;

import org.atelier.scene.Walker;
import org.atelier.scene.World;

public final class Notices {
	private Notices() {
	}

	public static Notice raise(Walker walker, World world) {
		Notice notice = new Notice(walker);
		Bulletin.post(notice);
		return notice;
	}
}
