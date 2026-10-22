package io.wren.soot;

import net.minecraft.fixture.kiln.Furnace;

public class Probe {
	public static void probe() {
		Furnace f = new Furnace();
		f.burn("oak");
		f.burn("coal");
		System.out.println("[LiveFragment] log=" + Furnace.LOG);
	}
}
