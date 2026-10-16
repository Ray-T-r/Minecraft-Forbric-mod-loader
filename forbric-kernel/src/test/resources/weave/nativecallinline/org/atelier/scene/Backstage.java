package org.atelier.scene;

import fixture.nativecallinline.Trace;

/** A subclass with its own layered painting: the guarded inline must leave its dispatch alone. */
public class Backstage extends Stage {
	@Override
	protected void weave(Brush brush, int x, int y, float delta) {
		Trace.add("backstage-weave");
	}
}
