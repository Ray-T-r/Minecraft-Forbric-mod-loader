package net.minecraft.client;

import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;

/** Fixture stand-in for the camera the guest was written against: every view's angles go through setRotation(FF). */
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
			setRotation(30.0F, 10.0F);
		}
		if (mode == SLEEPING) setRotation(90.0F, 0.0F);
	}

	protected void setRotation(float yaw, float pitch) {
		rotation.rotationYXZ(yaw, pitch, 0.0F);
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
