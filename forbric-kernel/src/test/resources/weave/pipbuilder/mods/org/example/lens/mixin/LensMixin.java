package org.example.lens.mixin;

import java.util.ArrayList;
import java.util.List;

import com.llamalad7.mixinextras.sugar.Local;
import com.llamalad7.mixinextras.sugar.ref.LocalRef;
import fixture.pipbuilder.Named;
import net.minecraft.client.gui.render.GuiRenderer;
import net.minecraft.client.gui.render.pip.PictureInPictureRenderer;
import net.minecraft.client.renderer.feature.FeatureRenderDispatcher;
import net.minecraft.client.renderer.state.gui.GuiRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Injects before the builder and swaps the constructor's renderer list for one with its own renderer added. */
@Mixin(GuiRenderer.class)
public abstract class LensMixin {
	@Inject(method = "<init>", at = @At(value = "INVOKE", target = "Lcom/google/common/collect/ImmutableMap;builder()Lcom/google/common/collect/ImmutableMap$Builder;", shift = At.Shift.BEFORE))
	private void lens$init(GuiRenderState renderState, FeatureRenderDispatcher dispatcher, List<?> ignored, CallbackInfo ci,
			@Local LocalRef<List<PictureInPictureRenderer<?>>> renderers) {
		List<PictureInPictureRenderer<?>> mutable = new ArrayList<>(renderers.get());
		mutable.add(new Named("lens", Named.LensState.class));
		renderers.set(mutable);
	}
}
