package io.tern.tally;

import net.minecraft.fixture.kiln.Brazier;
import net.minecraft.fixture.kiln.Cinder;
import net.minecraft.fixture.kiln.Hearth;
import net.minecraft.fixture.kiln.Kiln;
import net.minecraft.fixture.kiln.Oven;
import net.minecraft.fixture.kiln.Range;
import net.minecraft.fixture.kiln.Spot;
import net.minecraft.fixture.kiln.Tool;

public class Probe {
	public static void probe() {
		Hearth hearth = new Hearth();
		Kiln[] kilns = { new Kiln(Range.of(3, 5)), new Brazier(), new Oven(), new Cinder() };
		Tool[] tools = { new Tool("pick", false), new Tool("silk", true) };
		StringBuilder out = new StringBuilder();
		int x = 0;
		for (boolean hushed : new boolean[] { false, true }) {
			for (Kiln kiln : kilns) {
				for (Tool tool : tools) {
					Kiln.LOG.clear();
					Hearth.hushed = hushed;
					hearth.breakAt(kiln, new Spot(x++), tool);
					out.append(out.isEmpty() ? "" : " | ").append(kiln.getClass().getSimpleName()).append('/').append(tool.name()).append('/')
							.append(hushed ? "hushed" : "loud").append(' ').append(Kiln.LOG);
				}
			}
		}
		System.out.println("[ValueRelay] " + out);
	}
}
