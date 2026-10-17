package com.gale.vane.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import fixture.carrierreturn.Trace;
import org.fern.loom.Dial;
import org.fern.loom.Spindle;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** A guest written against the compass's answers. */
@Mixin(Spindle.class)
public abstract class SpindleMixin {
	/** Turns the facing upright, and gives a spot with no facing the east. */
	@WrapOperation(method = "facing", at = @At(value = "INVOKE", target = "Lorg/fern/loom/Compass;read(Lorg/fern/loom/Dial;Ljava/lang/String;)Ljava/lang/String;"))
	private String vane$facing(Dial dial, String place, Operation<String> original) {
		String read = original.call(dial, place);
		Trace.add("vane " + place + " " + read);
		return read == null ? "east" : read.toUpperCase();
	}

	/** The same, where the carrier's one return is reached with a spot and without. */
	@WrapOperation(method = "tilt", at = @At(value = "INVOKE", target = "Lorg/fern/loom/Compass;read(Lorg/fern/loom/Dial;Ljava/lang/String;)Ljava/lang/String;"))
	private String vane$tilt(Dial dial, String place, Operation<String> original) {
		String read = original.call(dial, place);
		Trace.add("vane-tilt " + place + " " + read);
		return read == null ? "east" : read.toUpperCase();
	}

	/** Asks the compass about another spot than the one it was handed: the carrier's answer is not that answer. */
	@WrapOperation(method = "lean", at = @At(value = "INVOKE", target = "Lorg/fern/loom/Compass;read(Lorg/fern/loom/Dial;Ljava/lang/String;)Ljava/lang/String;"))
	private String vane$lean(Dial dial, String place, Operation<String> original) {
		String read = original.call(dial, "north");
		Trace.add("vane-lean " + place + " " + read);
		return read;
	}
}
