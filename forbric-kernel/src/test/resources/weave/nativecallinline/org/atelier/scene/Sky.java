package org.atelier.scene;

import fixture.nativecallinline.Trace;

public class Sky {
	public boolean open() {
		Trace.add("sky-open");
		return true;
	}
}
