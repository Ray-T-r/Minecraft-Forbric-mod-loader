package net.minecraft.client;

import net.neoforged.neoforge.client.event.ViewportEvent;
import org.joml.Quaternionf;

/**
 * Fixture stand-in for the merged camera: alignWithEntity posts the angles event and makes the ordinary and mirrored
 * calls through setRotation(FFF) with the event's roll, so its setRotation calls run (FF), (FFF), (FFF), (FF). The
 * two-argument overload is declared first and only delegates, as on the merged base.
 */
public class Camera {
	public static final int MINECART = 0, ORDINARY = 1, MIRRORED = 2, SLEEPING = 3;

	public int mode = ORDINARY;
	public float eventRoll;
	private final Quaternionf rotation = new Quaternionf();

	public void alignWithEntity(float partialTicks) {
		if (mode == MINECART) {
			setRotation(30.0F, 10.0F);
		} else if (mode == ORDINARY || mode == MIRRORED) {
			ViewportEvent.ComputeCameraAngles angles = new ViewportEvent.ComputeCameraAngles(30.0F, 10.0F, eventRoll);
			if (mode == ORDINARY) setRotation(angles.getYaw(), angles.getPitch(), angles.getRoll());
			else setRotation(angles.getYaw() + 180.0F, -angles.getPitch(), -angles.getRoll());
		}
		if (mode == SLEEPING) setRotation(90.0F, 0.0F);
	}

    /** Source-platform counterpart embedded as non-executable native-reference evidence by the weave test. */
    public void nativeAlignment(float partialTicks) {
        if(mode==MINECART)setRotation(30.0F,10.0F);
        else if(mode==ORDINARY||mode==MIRRORED) {
            float yaw=30.0F,pitch=10.0F;
            if(mode==ORDINARY)setRotation(yaw,pitch);
            else setRotation(yaw+180.0F,-pitch);
        }
        if(mode==SLEEPING)setRotation(90.0F,0.0F);
    }

	protected void setRotation(float yaw, float pitch) {
		setRotation(yaw, pitch, 0.0F);
	}

	protected void setRotation(float yaw, float pitch, float roll) {
		rotation.rotationYXZ(yaw, pitch, roll);
	}

	/** The roll that reached the rotation. */
	public float roll() {
		return rotation.z;
	}
}
