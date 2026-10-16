package org.atelier.scene;

import fixture.nativecallinline.Trace;

public class Panel {
	public final String name;

	public Panel(String name) {
		this.name = name;
	}

	public void paint(Brush brush, int x, int y, float delta) {
		Trace.add("paint:" + name + "@" + x + "," + y);
	}
}
