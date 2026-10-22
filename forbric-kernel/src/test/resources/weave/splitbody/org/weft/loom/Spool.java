package org.weft.loom;

public class Spool {
	public static void wind(String g) { Tapestry.LOG.add("wind:" + g); }
	public static void dye(String g) { Tapestry.LOG.add("dye:" + g); }
	public static void knot(String g) { Tapestry.LOG.add("knot:" + g); }
}
