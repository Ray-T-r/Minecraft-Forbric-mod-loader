package com.kestrel.gill.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import fixture.replaceddecision.Trace;
import org.harbor.deck.Diver;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** A guest whose gilled diver counts as under water wherever the diver's own question is asked. */
@Mixin(Diver.class)
public abstract class DiverMixin {
	@ModifyExpressionValue(method = "tick", at = @At(value = "INVOKE", target = "Lorg/harbor/deck/Diver;isUnder(Lorg/harbor/deck/Tide;)Z"))
	private boolean gill$wet(boolean under) {
		Trace.add("gill:" + under);
		return under || ((Diver) (Object) this).name.equals("gilled");
	}

	@ModifyExpressionValue(method = "dive", at = @At(value = "INVOKE", target = "Lorg/harbor/deck/Diver;isUnder(Lorg/harbor/deck/Tide;)Z"))
	private boolean gill$dive(boolean under) {
		Trace.add("gill-dive:" + under);
		return under || ((Diver) (Object) this).name.equals("gilled");
	}

	@ModifyExpressionValue(method = "surface", at = @At(value = "INVOKE", target = "Lorg/harbor/deck/Diver;isUnder(Lorg/harbor/deck/Tide;)Z"))
	private boolean gill$surface(boolean under) {
		Trace.add("gill-surface:" + under);
		return under || ((Diver) (Object) this).name.equals("gilled");
	}
}
