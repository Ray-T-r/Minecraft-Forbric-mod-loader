package com.zurrtum.create.client.mixin;

import fixture.createkeyboard.Trail;
import net.minecraft.client.KeyboardHandler;
import net.minecraft.client.input.KeyEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Shaped like Create Fly's KeyboardHandlerMixin, written against vanilla's keyPress: one hook at its sixth return
 * ({@code RETURN} ordinal 5) and one at its TAIL, both through one helper told which it was. In vanilla 26.2 the sixth
 * return IS the final return — the key release returns at the fifth — so natively both hooks run on a press and on a
 * repeat, and neither on a release.
 */
@Mixin(KeyboardHandler.class)
public class KeyboardHandlerMixin {
	@Inject(method = "keyPress(JILnet/minecraft/client/input/KeyEvent;)V", at = @At(value = "RETURN", ordinal = 5))
	private void onKeyReleased(long handle, int action, KeyEvent event, CallbackInfo ci) {
		onKey(event, false);
	}

	@Inject(method = "keyPress(JILnet/minecraft/client/input/KeyEvent;)V", at = @At("TAIL"))
	private void onKey(long handle, int action, KeyEvent event, CallbackInfo ci) {
		onKey(event, true);
	}

	@Unique
	private void onKey(KeyEvent input, boolean pressed) {
		Trail.add("create " + (pressed ? "press " : "release ") + input.key());
	}
}
