package fixture.qzxmodels.hooks;

import java.util.function.Function;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import com.mojang.serialization.Codec;

import fixture.qzxmodels.QzxDialects;
import net.minecraft.client.renderer.block.dispatch.BlockStateModel;

/** The same two codec redirects as the Fabric-shaped mixin beside it, under names that share nothing with it. */
@Mixin(BlockStateModel.Unbaked.class)
interface QzxCodecHooks {
	@Redirect(method = "<clinit>()V", at = @At(value = "INVOKE",
			target = "Lcom/mojang/serialization/Codec;flatComapMap(Ljava/util/function/Function;Ljava/util/function/Function;)Lcom/mojang/serialization/Codec;",
			ordinal = 0))
	private static Codec<?> qzxLists(Codec<?> built, Function<?, ?> in, Function<?, ?> out) {
		return QzxDialects.QZX_LISTS;
	}

	@Redirect(method = "<clinit>()V", at = @At(value = "INVOKE",
			target = "Lcom/mojang/serialization/Codec;flatComapMap(Ljava/util/function/Function;Ljava/util/function/Function;)Lcom/mojang/serialization/Codec;",
			ordinal = 1))
	private static Codec<?> qzxSingle(Codec<?> built, Function<?, ?> in, Function<?, ?> out) {
		return QzxDialects.QZX_SINGLE;
	}
}
