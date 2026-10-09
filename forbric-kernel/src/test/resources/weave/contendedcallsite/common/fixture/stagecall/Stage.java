package fixture.stagecall;

/** The target both mods want: one call each to two paints. The probe is render() itself. */
public class Stage {
	public String render() {
		return Paints.solid("cloak") + "|" + Paints.outline("trim");
	}
}
