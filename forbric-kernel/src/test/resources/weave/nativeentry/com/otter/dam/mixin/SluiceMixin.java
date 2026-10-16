package com.otter.dam.mixin;

import fixture.nativeentry.Trace;
import org.mill.works.Gate;
import org.mill.works.Sluice;
import org.mill.works.Trough;
import org.mill.works.Water;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** A guest that holds frozen water back at the entry of the decision method. */
@Mixin(Sluice.class)
public abstract class SluiceMixin {
	@Inject(method = "shouldPour", at = @At("HEAD"), cancellable = true)
	private void dam$hold(Water water, Gate gate, Trough trough, CallbackInfoReturnable<Boolean> cir) {
		Trace.add("dam:" + water.name);
		if (water.name.equals("ice")) cir.setReturnValue(false);
	}
}
