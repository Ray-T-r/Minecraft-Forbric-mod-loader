package fixture.lanternview;

import net.minecraft.client.Camera;

/** Aligns one camera per view and reports the yaw and roll that reached the rotation and how often the hook ran. */
public class Probe {
	public String probe() {
		StringBuilder out = new StringBuilder();
		String[] names = {"minecart", "ordinary", "sleeping"};
		for (int mode : new int[] {Camera.ORDINARY, Camera.SLEEPING, Camera.MINECART}) {
			Angles.calls = 0;
			Camera camera = new Camera();
			camera.mode = mode;
			camera.eventRoll = 5.0F;
			camera.alignWithEntity(0.5F);
			out.append(out.length() == 0 ? "" : " ").append(names[mode]).append("=yaw:").append(camera.yaw())
					.append(",roll:").append(camera.roll()).append(",hook:").append(Angles.calls);
		}
		return out.toString();
	}
}
