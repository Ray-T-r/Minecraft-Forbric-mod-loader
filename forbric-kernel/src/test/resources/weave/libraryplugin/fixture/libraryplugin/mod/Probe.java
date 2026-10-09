package fixture.libraryplugin.mod;

/** The harness's probe: what the plugin's base saw, and whether the mixin it decides on applied. */
public class Probe {
	public String report() {
		return "platform=" + Seen.platform + " gear=" + new Gear().value();
	}
}
