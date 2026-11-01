package net.minecraft.client.renderer.block.model;

/**
 * Fixture stand-in for NeoForge's quad: NeoForge made its wider constructor canonical and the vanilla-shaped one
 * delegates to it with NeoForge's defaults, so a caller of either builds the same kind of quad.
 */
public class BakedQuad {
	private final int[] vertices;
	private final int tintIndex;
	private final String direction;
	private final String normals;
	private final String colors;

	public BakedQuad(int[] vertices, int tintIndex, String direction) {
		this(vertices, tintIndex, direction, "UNSPECIFIED", "DEFAULT");
	}

	public BakedQuad(int[] vertices, int tintIndex, String direction, String normals, String colors) {
		this.vertices = vertices;
		this.tintIndex = tintIndex;
		this.direction = direction;
		this.normals = normals;
		this.colors = colors;
	}

	public String direction() {
		return direction;
	}
}
