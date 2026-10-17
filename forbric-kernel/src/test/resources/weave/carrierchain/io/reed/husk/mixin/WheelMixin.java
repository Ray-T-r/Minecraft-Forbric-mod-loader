package io.reed.husk.mixin;

import fixture.carrierchain.Trace;
import org.brook.mill.Field;
import org.brook.mill.Grain;
import org.brook.mill.Sack;
import org.brook.mill.Stones;
import org.brook.mill.Wheel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;

/** A guest written against the wheel's own vanilla calls. */
@Mixin(Wheel.class)
public abstract class WheelMixin {
	/** Husks every grind of a turn, with the turn's own grain and sack in hand. */
	@Redirect(method = "turn", at = @At(value = "INVOKE", target = "Lorg/brook/mill/Stones;grind(Ljava/lang/String;Z)Ljava/lang/String;"))
	private String husk$grind(Stones stones, String kind, boolean fine, Grain grain, Sack sack) {
		Trace.add("husk-grind " + kind + " of " + sack.kind());
		return "husked " + stones.grind(kind, fine);
	}

	/** Marks a harvest a day later. */
	@Redirect(method = "harvest", at = @At(value = "INVOKE", target = "Lorg/brook/mill/Field;mark(ILjava/lang/String;)V"))
	private void husk$mark(Field field, int day, String kernel) {
		Trace.add("husk-mark " + kernel);
		field.mark(day + 1, kernel);
	}

	/** Husks a sift's grind. */
	@Redirect(method = "sift", at = @At(value = "INVOKE", target = "Lorg/brook/mill/Stones;grind(Ljava/lang/String;Z)Ljava/lang/String;"))
	private String husk$sift(Stones stones, String kind, boolean fine) {
		Trace.add("husk-sift " + kind);
		return "husked " + stones.grind(kind, fine);
	}

	/** Doubles a dip's added depth. */
	@ModifyExpressionValue(method = "dip", at = @At(value = "CONSTANT", args = "doubleValue=0.25"))
	private double husk$dip(double original) {
		Trace.add("husk-dip " + original);
		return original * 2;
	}
}
