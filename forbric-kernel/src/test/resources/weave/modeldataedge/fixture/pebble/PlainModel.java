package fixture.pebble;

import java.util.List;
import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
import net.minecraft.client.resources.model.sprite.Material;
import net.minecraft.util.RandomSource;

/** A model of neither family: it answers only the context-free questions. */
public class PlainModel implements BlockStateModel {
	@Override public void collectParts(RandomSource random, List<BlockStateModelPart> parts) {
		parts.add(() -> "plain");
	}

	@Override public Material.Baked particleMaterial() {
		return new Material.Baked("plain");
	}
}
