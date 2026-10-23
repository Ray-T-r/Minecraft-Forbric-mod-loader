package io.wren.latch;

import net.minecraft.fixture.weir.Face;
import net.minecraft.fixture.weir.Kind;
import net.minecraft.fixture.weir.Stone;
import net.minecraft.fixture.weir.Weir;

public class Probe {
	public static void probe() {
		Weir weir = new Weir();
		Stone latch = new Stone("latch", Kind.LATCH), rill = new Stone("rill", Kind.RILL), clay = new Stone("clay", Kind.CLAY), gate = new Stone("gate", Kind.GATE);
		StringBuilder out = new StringBuilder();
		Object[][] cases = {{latch, clay, clay, Face.EAST}, {latch, clay, clay, Face.NORTH}, {clay, latch, clay, Face.EAST}, {rill, clay, latch, Face.WEST},
				{latch, clay, latch, Face.NORTH}, {gate, clay, clay, Face.NORTH}, {rill, clay, clay, Face.SOUTH}};
		for (Object[] c : cases) {
			Weir.LOG.clear();
			String side = weir.side((Stone) c[0], (Stone) c[1], (Stone) c[2], (Face) c[3]);
			out.append(out.isEmpty() ? "" : " | ").append(c[0]).append('/').append(c[1]).append('/').append(c[2]).append('/').append(c[3])
					.append(" -> ").append(side).append(' ').append(Weir.LOG.stream().filter(l -> l.startsWith("latch")).toList());
		}
		System.out.println("[UncalledDecision] " + out);
	}
}
