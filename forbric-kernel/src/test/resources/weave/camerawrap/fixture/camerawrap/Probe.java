package fixture.camerawrap;

import net.minecraft.client.Camera;

/** Aligns one camera per view and reports the yaws the wrapper saw and the roll that reached each rotation. */
public class Probe {
	public String probe() {
		StringBuilder out = new StringBuilder();
		String[] names = {"minecart", "ordinary", "mirrored", "sleeping"};
		for (int mode : new int[] {Camera.MINECART, Camera.ORDINARY, Camera.MIRRORED, Camera.SLEEPING}) {
			Views.SEEN.clear();
			Camera camera = new Camera();
			camera.mode = mode;
			camera.eventRoll = 5.0F;
			camera.alignWithEntity(0.5F);
			out.append(out.length() == 0 ? "" : " ").append(names[mode]).append('=').append(Views.SEEN).append('/').append(camera.roll());
		}
		return out.toString();
	}
}
