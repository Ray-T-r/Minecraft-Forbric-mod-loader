package org.mill.works;

import fixture.nativeentry.Trace;

public class Trough {
	public void fill(int flow) {
		Trace.add("fill " + flow);
	}
}
