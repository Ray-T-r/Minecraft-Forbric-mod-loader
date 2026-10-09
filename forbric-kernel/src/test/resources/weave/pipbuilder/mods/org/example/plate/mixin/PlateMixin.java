package org.example.plate.mixin;

import com.google.common.collect.ImmutableMap;
import fixture.pipbuilder.Named;
import net.minecraft.client.gui.render.GuiRenderer;
import net.minecraft.client.gui.render.pip.PictureInPictureRenderer;
import net.minecraft.client.renderer.state.gui.pip.PictureInPictureRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** Redirects the builder call to a builder of its own that already holds its renderer. */
@Mixin(GuiRenderer.class)
public abstract class PlateMixin {
	@Redirect(method = "<init>", at = @At(value = "INVOKE", target = "Lcom/google/common/collect/ImmutableMap;builder()Lcom/google/common/collect/ImmutableMap$Builder;"))
	private ImmutableMap.Builder<Class<? extends PictureInPictureRenderState>, PictureInPictureRenderer<?>> plate$builder() {
		ImmutableMap.Builder<Class<? extends PictureInPictureRenderState>, PictureInPictureRenderer<?>> own = ImmutableMap.builder();
		return own.put(Named.PlateState.class, new Named("plate", Named.PlateState.class));
	}
}
