package org.harbor.deck;

import fixture.replaceddecision.Trace;

public class Lung {
	public void hold(String who) {
		Trace.add("hold " + who);
	}

	public void gasp(String who) {
		Trace.add("gasp " + who);
	}
}
