package com.example.keyecho.mixin;

import fixture.keyexits.Trail;
import net.minecraft.client.KeyboardHandler;
import net.minecraft.client.input.KeyEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * An unrelated guest written against vanilla's keyPress: one hook on its key-release return (ordinal 4), one on its
 * last return (ordinal 5) and one at TAIL, which takes only the callback.
 */
@Mixin(KeyboardHandler.class)
public class EchoKeysMixin {
	@Inject(method = "keyPress", at = @At(value = "RETURN", ordinal = 4))
	private void heardRelease(long handle, int action, KeyEvent event, CallbackInfo ci) {
		Trail.add("keyecho release " + event.key());
	}

	@Inject(method = "keyPress", at = @At(value = "RETURN", ordinal = 5))
	private void heardFinal(long handle, int action, KeyEvent event, CallbackInfo ci) {
		Trail.add("keyecho final " + event.key());
	}

	@Inject(method = "keyPress", at = @At("TAIL"))
	private void heardTail(CallbackInfo ci) {
		Trail.add("keyecho tail");
	}
}
