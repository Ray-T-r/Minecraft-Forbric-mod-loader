package fixture.tailcapture;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.client.renderer.LecternRenderer;

/** Submits a book and an empty lectern, labels both; reports what the guest saw and what the renderer wrote. */
public class Probe {
	public String probe() {
		LecternRenderer renderer = new LecternRenderer();
		List<String> out = new ArrayList<>();
		renderer.submit("atlas", out, 2);
		renderer.submit(null, out, 5);
		renderer.label("x", out);
		renderer.label(null, out);
		return "seen=" + Lines.SEEN + " out=" + out;
	}
}
