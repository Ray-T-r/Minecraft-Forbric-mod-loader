package fixture.pluginplatform;

/** The harness's probe: what the plugin saw, and what a class loaded after it became. */
public class Probe {
	public String report() {
		return "platform=" + Report.platform + " knot=" + Report.knot + " stamp=" + new Stamp().value();
	}
}
