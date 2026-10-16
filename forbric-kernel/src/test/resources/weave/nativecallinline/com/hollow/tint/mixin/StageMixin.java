package com.hollow.tint.mixin;

import com.llamalad7.mixinextras.injector.v2.WrapWithCondition;
import com.llamalad7.mixinextras.sugar.Local;
import fixture.nativecallinline.Trace;
import org.atelier.scene.Brush;
import org.atelier.scene.Item;
import org.atelier.scene.Panel;
import org.atelier.scene.Stage;
import org.atelier.scene.Walker;
import org.atelier.scene.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** A guest written for the native host: each injector anchors on a call the host makes itself there. */
@Mixin(Stage.class)
public abstract class StageMixin {
	@WrapWithCondition(method = "render", at = @At(value = "INVOKE", target = "Lorg/atelier/scene/Panel;paint(Lorg/atelier/scene/Brush;IIF)V"))
	private boolean tint$gate(Panel panel, Brush brush, int x, int y, float delta) {
		Trace.add("gate:" + panel.name + "@" + x + "," + y);
		return true;
	}

	@Inject(method = "render", at = @At(value = "INVOKE", target = "Lorg/atelier/scene/Panel;paint(Lorg/atelier/scene/Brush;IIF)V", shift = At.Shift.AFTER))
	private void tint$after(Brush brush, int x, int y, CallbackInfo ci) {
		Trace.add("tint-after");
	}

	@Inject(method = "drop(Z)V", at = @At(value = "INVOKE", target = "Lorg/atelier/scene/Stage;discard(Lorg/atelier/scene/Item;ZZ)Lorg/atelier/scene/Token;"), cancellable = true)
	private void tint$toss(boolean all, CallbackInfo ci, @Local Item removed) {
		Trace.add("toss:" + removed.name);
		if (removed.name.equals("pear")) ci.cancel();
	}

	@Inject(method = "tick", at = @At(value = "INVOKE", target = "Lorg/atelier/scene/Sky;open()Z", ordinal = 1))
	private void tint$cache(World world, CallbackInfo ci, @Local Walker walker) {
		Trace.add("cache:" + walker.name);
	}
}
