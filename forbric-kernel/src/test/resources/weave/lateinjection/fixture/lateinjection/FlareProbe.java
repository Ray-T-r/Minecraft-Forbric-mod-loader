package fixture.lateinjection;

import org.example.sconces.Mounted;

/** Lights a lamp and flares it, then asks it what the sconce mod made it. */
public class FlareProbe {
	public String probe() {
		Lamp lamp = new Lamp();
		String glow = lamp.light();
		String flare = lamp.flare();
		return glow + " | " + flare + " | " + lamp.trail + " | " + ((Mounted) (Object) lamp).mountedOn();
	}
}
