package net.minecraft.world.level.levelgen.structure.templatesystem;

import java.util.List;

/** A stand-in: the processors of one placement. */
public class StructurePlaceSettings {
	private final List<StructureProcessor> processors;

    private final net.minecraft.world.level.block.Mirror mirror=net.minecraft.world.level.block.Mirror.NONE;
    private final net.minecraft.world.level.block.Rotation rotation=net.minecraft.world.level.block.Rotation.NONE;
    private final net.minecraft.core.BlockPos pivot=null;
    private final net.minecraft.world.level.levelgen.structure.BoundingBox box=null;
    private final boolean finalizeEntities=true;
    public net.minecraft.world.level.block.Mirror getMirror(){return mirror;}
    public net.minecraft.world.level.block.Rotation getRotation(){return rotation;}
    public net.minecraft.core.BlockPos getRotationPivot(){return pivot;}
    public net.minecraft.world.level.levelgen.structure.BoundingBox getBoundingBox(){return box;}
    public boolean shouldFinalizeEntities(){return finalizeEntities;}

	public StructurePlaceSettings(List<StructureProcessor> processors) {
		this.processors = processors;
	}

	public List<StructureProcessor> getProcessors() {
		return processors;
	}
}
