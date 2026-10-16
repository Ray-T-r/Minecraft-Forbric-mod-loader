package net.minecraft.client.renderer.block.dispatch;

import java.util.List;
import net.minecraft.client.resources.model.sprite.Material;
import net.minecraft.util.RandomSource;
import net.minecraftforge.client.extensions.IForgeBlockStateModel;
import net.neoforged.neoforge.client.extensions.BlockStateModelExtension;

/** Stand-in for the merged interface: it extends both families' model extensions, and declares neither's questions. */
public interface BlockStateModel extends BlockStateModelExtension, IForgeBlockStateModel {
	void collectParts(RandomSource random, List<BlockStateModelPart> parts);

	Material.Baked particleMaterial();
}
