package net.minecraft.client;

import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.ViewportEvent;
import org.joml.Quaternionf;

/**
 * Fixture stand-in for the merged camera: the minecart branch sets its angles with setRotation(FF) and then its position
 * with setPosition(Vec3); the ordinary branch posts the angles event and sets them with setRotation(FFF), the event's roll
 * appended; the sleeper's setRotation(FF) comes last. The two-float overload is declared first and only forwards, with a
 * zero roll. {@code nativeAlignment} is the source platform's alignWithEntity, embedded as native reference evidence.
 */
public class Camera {
	public static final int MINECART = 0, ORDINARY = 1, SLEEPING = 2;

	public int mode = ORDINARY;
	public float eventRoll;
	public Vec3 position;
	private final Quaternionf rotation = new Quaternionf();

	public void alignWithEntity(float partialTicks) {
		if (mode == MINECART) {
			setRotation(30.0F, 10.0F);
			setPosition(Vec3.ZERO);
		} else {
			ViewportEvent.ComputeCameraAngles angles = new ViewportEvent.ComputeCameraAngles(30.0F, 10.0F, eventRoll);
			setRotation(angles.getYaw(), angles.getPitch(), angles.getRoll());
		}
		if (mode == SLEEPING) setRotation(90.0F, 0.0F);
	}

	public void nativeAlignment(float partialTicks) {
		if (mode == MINECART) {
			setRotation(30.0F, 10.0F);
			setPosition(Vec3.ZERO);
		} else {
			setRotation(30.0F, 10.0F);
		}
		if (mode == SLEEPING) setRotation(90.0F, 0.0F);
	}

	protected void setRotation(float yaw, float pitch) {
		setRotation(yaw, pitch, 0.0F);
	}

	protected void setRotation(float yaw, float pitch, float roll) {
		rotation.rotationYXZ(yaw, pitch, roll);
	}

	protected void setPosition(Vec3 at) {
		position = at;
	}

	public float yaw() {
		return rotation.y;
	}

	public float roll() {
		return rotation.z;
	}
}
