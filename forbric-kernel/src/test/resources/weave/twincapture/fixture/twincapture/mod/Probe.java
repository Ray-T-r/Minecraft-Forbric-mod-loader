package fixture.twincapture.mod;

import fixture.twincapture.Renderer;
import fixture.twincapture.Renderers;
import fixture.twincapture.Sink;

public class Probe {
	public String run() {
		Sink sink = new Sink();
		Renderers.VANILLA.render("a", sink);
		Renderers.VANILLA.render(null, sink);
		Renderer live = Renderers.live();
		live.render("b", sink);
		live.render(null, sink);
		return "captured=" + Captures.SEEN + " submitted=" + sink.submitted + " shifted=" + Captures.SHIFTED;
	}
}
