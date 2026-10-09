package org.joml;

/** Fixture stand-in that keeps the three angles it was handed, so the probe can read the roll that arrived. */
public class Quaternionf {
	public float x, y, z;

	public Quaternionf rotationYXZ(float angleY, float angleX, float angleZ) {
		x = angleX;
		y = angleY;
		z = angleZ;
		return this;
	}
}
