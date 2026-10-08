package net.minecraft.world.level.levelgen.structure.templatesystem;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.levelgen.structure.BoundingBox;

/** Stand-in: what vanilla's placeInWorld read placeEntities' arguments off, with vanilla's getter names. */
public class StructurePlaceSettings {
	private final Mirror mirror;
	private final Rotation rotation;
	private final BlockPos pivot = new BlockPos(1,2,3);
	private final BoundingBox box = new BoundingBox(16);
	private final boolean finalizeEntities = true;

	public StructurePlaceSettings(Mirror mirror, Rotation rotation) {
		this.mirror = mirror;
		this.rotation = rotation;
	}

	public Mirror getMirror() {
		return mirror;
	}

	public Rotation getRotation() {
		return rotation;
	}

	public BlockPos getRotationPivot() {
		return pivot;
	}

	public BoundingBox getBoundingBox() {
		return box;
	}

	public boolean shouldFinalizeEntities() {
		return finalizeEntities;
	}

	public boolean isIgnoreEntities() {
		return false;
	}
}
