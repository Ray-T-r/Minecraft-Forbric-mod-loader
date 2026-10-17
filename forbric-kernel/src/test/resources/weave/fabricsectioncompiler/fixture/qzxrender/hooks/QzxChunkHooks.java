package fixture.qzxrender.hooks;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.llamalad7.mixinextras.sugar.Local;
import com.llamalad7.mixinextras.sugar.Share;
import com.llamalad7.mixinextras.sugar.ref.LocalRef;
import com.mojang.blaze3d.vertex.VertexSorting;

import net.fabricmc.fabric.api.client.renderer.v1.Renderer;
import net.fabricmc.fabric.api.client.renderer.v1.mesh.QuadEmitter;
import net.fabricmc.fabric.api.client.renderer.v1.render.AltModelBlockRenderer;
import net.minecraft.client.renderer.SectionBufferBuilderPack;
import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.client.renderer.block.BlockQuadOutput;
import net.minecraft.client.renderer.block.ModelBlockRenderer;
import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.client.renderer.chunk.RenderSectionRegion;
import net.minecraft.client.renderer.chunk.SectionCompiler;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.block.state.BlockState;

/**
 * The same chunk takeover as the fabric-renderer-api-shaped mixin beside it, under names that share nothing with it: its
 * own package, class, handlers, share keys, and the selector spelled with its descriptor. Only the platform's names
 * (the game's classes, the public renderer API, the merged local the setup reads) are the same.
 */
@Mixin(SectionCompiler.class)
abstract class QzxChunkHooks {
	@Inject(method = "compile(Lnet/minecraft/core/SectionPos;Lnet/minecraft/client/renderer/chunk/RenderSectionRegion;Lcom/mojang/blaze3d/vertex/VertexSorting;Lnet/minecraft/client/renderer/SectionBufferBuilderPack;)Lnet/minecraft/client/renderer/chunk/SectionCompiler$Results;",
			at = @At(value = "INVOKE", target = "Lnet/minecraft/core/BlockPos;betweenClosed(Lnet/minecraft/core/BlockPos;Lnet/minecraft/core/BlockPos;)Ljava/lang/Iterable;"))
	private void qzxPrepare(SectionPos at, RenderSectionRegion area, VertexSorting order, SectionBufferBuilderPack packs,
			CallbackInfoReturnable<SectionCompiler.Results> cir, @Local(name = "startedLayers") Map<String, List<String>> layers,
			@Share("qzxPainter") LocalRef<AltModelBlockRenderer> painter, @Share("qzxSink") LocalRef<QuadEmitter> sink) {
		painter.set(Renderer.get().altModelBlockRenderer());
		sink.set(Renderer.get().quadEmitter(quad -> layers.computeIfAbsent("solid", layer -> new ArrayList<>()).add(quad)));
	}

	@Redirect(method = "compile(Lnet/minecraft/core/SectionPos;Lnet/minecraft/client/renderer/chunk/RenderSectionRegion;Lcom/mojang/blaze3d/vertex/VertexSorting;Lnet/minecraft/client/renderer/SectionBufferBuilderPack;)Lnet/minecraft/client/renderer/chunk/SectionCompiler$Results;",
			at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/block/ModelBlockRenderer;tesselateBlock(Lnet/minecraft/client/renderer/block/BlockQuadOutput;FFFLnet/minecraft/client/renderer/block/BlockAndTintGetter;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/client/renderer/block/dispatch/BlockStateModel;J)V"))
	private void qzxPaint(ModelBlockRenderer original, BlockQuadOutput out, float x, float y, float z, BlockAndTintGetter level,
			BlockPos pos, BlockState state, BlockStateModel model, long seed,
			@Share("qzxPainter") LocalRef<AltModelBlockRenderer> painter, @Share("qzxSink") LocalRef<QuadEmitter> sink) {
		painter.get().tesselateBlock(sink.get(), x, y, z, level, pos, state, model, seed);
	}
}
