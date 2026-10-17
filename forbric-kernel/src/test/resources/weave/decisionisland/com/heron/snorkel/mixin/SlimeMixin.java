package com.heron.snorkel.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import fixture.decisionisland.Trace;
import org.brook.fen.Liquid;
import org.brook.fen.Wader;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Slime one can drown in counts as water at the head check: the answer changes. */
@Mixin(Wader.class)
public abstract class SlimeMixin {
	@ModifyExpressionValue(method = "step", at = @At(value = "INVOKE", target = "Lorg/brook/fen/Wader;isSubmerged(Lorg/brook/fen/Liquid;)Z"))
	private boolean slime$drown(boolean under) {
		if (under) return true;
		boolean slime = ((Wader) (Object) this).in == Liquid.SLIME;
		if (slime) Trace.add("slime:under");
		return slime;
	}
}
