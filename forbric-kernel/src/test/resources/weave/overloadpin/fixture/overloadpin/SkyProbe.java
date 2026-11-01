package fixture.overloadpin;

/** The harness's probe, kept apart from {@link SkyPass} so the class Mixin weaves is not the first the game loader defines. */
public class SkyProbe {
	public String render() {
		return new SkyPass().render();
	}
}
