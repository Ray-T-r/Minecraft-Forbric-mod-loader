package org.atelier.scene;

import java.util.List;

public class World {
	public final Sky sky = new Sky();
	public final List<Walker> walkers = List.of(new Walker("ada"), new Walker("bo"));

	public Sky sky() {
		return sky;
	}
}
