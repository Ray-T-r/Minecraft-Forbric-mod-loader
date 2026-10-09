package net.neoforged.neoforge.client.event;

/** Fixture stand-in: the angles event alignWithEntity posts, carrying the roll it then passes on. */
public class ViewportEvent {
	public static final class ComputeCameraAngles {
		private final float yaw, pitch, roll;

		public ComputeCameraAngles(float yaw, float pitch, float roll) {
			this.yaw = yaw;
			this.pitch = pitch;
			this.roll = roll;
		}

		public float getYaw() {
			return yaw;
		}

		public float getPitch() {
			return pitch;
		}

		public float getRoll() {
			return roll;
		}
	}
}
