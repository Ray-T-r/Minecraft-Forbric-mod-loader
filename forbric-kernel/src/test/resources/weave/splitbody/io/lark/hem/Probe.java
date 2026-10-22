package io.lark.hem;

import org.weft.loom.Tapestry;

public class Probe {
	public static void probe() {
		Tapestry t = new Tapestry();
		for (String g : new String[] { "a", null, "veto", "b" }) t.show(g);
		System.out.println("[SplitBody] log=" + Tapestry.LOG);
	}
}
