package fixture.pebble.mixin;

import java.util.List;
import fixture.pebble.JoiningTileModel;
import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.client.renderer.pebble.PebbleMesher;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.client.model.data.ModelData;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** A guest written against the carrier's overload: it hides a block it simulates itself, at the overload's start. */
@Mixin(PebbleMesher.class)
public class PebbleMesherMixin {
	@Inject(method = "mesh(Ljava/util/List;FLnet/minecraft/client/renderer/block/BlockAndTintGetter;Lnet/minecraft/core/BlockPos;"
			+ "Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/client/renderer/block/dispatch/BlockStateModel;J"
			+ "Lnet/minecraftforge/client/model/data/ModelData;)V", at = @At("HEAD"), cancellable = true)
	private void hideSimulated(List<String> out, float shade, BlockAndTintGetter level, BlockPos pos, BlockState state,
			BlockStateModel model, long seed, ModelData data, CallbackInfo ci) {
		Integer joined = data.get(JoiningTileModel.JOINED);
		String tint = data.get(JoiningTileModel.TINT);
		System.out.println("[ModelDataEdge] guest edge ran for " + state.name() + " with "
				+ (joined == null ? "no join" : "joined " + joined) + (tint == null ? "" : " " + tint));
		if (state.name().equals("simulated")) ci.cancel();
	}
}
