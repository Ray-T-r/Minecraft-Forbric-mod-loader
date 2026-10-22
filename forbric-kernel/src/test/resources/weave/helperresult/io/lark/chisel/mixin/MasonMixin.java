package io.lark.chisel.mixin;

import fixture.helperresult.Trace;
import org.quarry.stone.Mason;
import org.quarry.stone.Point;
import org.quarry.stone.Relic;
import org.quarry.stone.Slab;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.llamalad7.mixinextras.sugar.Local;

/** A guest written against the mason's own shatter call, reading two of the method's locals by name. */
@Mixin(Mason.class)
abstract class MasonMixin {
	@Inject(method = "chip", at = @At(value = "INVOKE", target = "Lorg/quarry/stone/Slab;shatter(Lorg/quarry/stone/Yard;Lorg/quarry/stone/Point;Lorg/quarry/stone/Slab;)V"))
	private void chisel$chip(Point at, CallbackInfoReturnable<Boolean> info, @Local(name = "relic") Relic relic, @Local(name = "shaped") Slab shaped) {
		Trace.add("chisel " + at + " " + relic + " " + shaped + " " + info.getId());
	}

	/** Written against the native lay body: it runs before the slab settles, so before it is sealed. */
	@Inject(method = "lay", at = @At(value = "INVOKE", target = "Lorg/quarry/stone/Slab;settle(Lorg/quarry/stone/Yard;Lorg/quarry/stone/Point;)V"))
	private void chisel$lay(Point at, CallbackInfoReturnable<Boolean> info) {
		Trace.add("chisel-lay " + at);
	}

	@Inject(method = "split", at = @At(value = "INVOKE", target = "Lorg/quarry/stone/Slab;shatter(Lorg/quarry/stone/Yard;Lorg/quarry/stone/Point;Lorg/quarry/stone/Slab;)V"))
	private void chisel$split(Point at, CallbackInfo info, @Local Slab slab) {
		Trace.add("chisel-split " + at + " " + slab);
	}
}
