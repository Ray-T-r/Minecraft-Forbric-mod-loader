package com.heron.snorkel.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import fixture.decisionisland.Trace;
import org.brook.fen.Liquid;
import org.brook.fen.Wader;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** A snorkel: it notices lava at the head check, and a tank holds the breath when the charm does not. */
@Mixin(Wader.class)
public abstract class WaderMixin {
	@WrapOperation(method = "step", at = @At(value = "INVOKE", target = "Lorg/brook/fen/Wader;isSubmerged(Lorg/brook/fen/Liquid;)Z"))
	private boolean snorkel$lava(Wader self, Liquid liquid, Operation<Boolean> original) {
		boolean under = original.call(self, liquid);
		if (!under && self.in == Liquid.LAVA) Trace.add("snorkel:lava");
		return under;
	}

	@WrapOperation(method = "step", at = @At(value = "INVOKE", target = "Lorg/brook/fen/Charms;canHoldBreath(Lorg/brook/fen/Wader;)Z"))
	private boolean snorkel$tank(Wader self, Operation<Boolean> original) {
		if (original.call(self)) return true;
		Trace.add("snorkel:tank" + self.tank);
		return self.tank-- > 0;
	}
}
