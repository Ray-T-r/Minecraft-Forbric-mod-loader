package com.minstrel.tune.mixin;

import fixture.consumerstores.Trace;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.tavern.hall.Bell;

/** A tuner: it hears the tone the bell stores and tunes a low one up. */
@Mixin(Bell.class)
public abstract class BellMixin {
	@ModifyVariable(method = "ring", at = @At("STORE"), name = "tone")
	private String tune$tone(String tone) {
		Trace.add("tune " + tone);
		return "low".equals(tone) ? "low+" : tone;
	}
}
