package fixture.lateinjection;

import org.example.sconces.Mounted;

/** Lights a lamp, then asks it what the sconce mod made it: the cast is the one Minecraft.<init> died on. */
public class Probe {
	public String probe() {
		Lamp lamp = new Lamp();
		String glow = lamp.light();
		return glow + " | " + lamp.trail + " | " + ((Mounted) (Object) lamp).mountedOn();
	}
}
