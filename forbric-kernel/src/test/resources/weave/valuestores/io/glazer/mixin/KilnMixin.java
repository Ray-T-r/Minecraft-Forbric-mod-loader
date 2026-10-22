package io.glazer.mixin;

import java.util.Objects;

import fixture.valuestores.Trace;
import org.pottery.kiln.Kiln;
import org.pottery.kiln.Pot;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.llamalad7.mixinextras.sugar.Share;
import com.llamalad7.mixinextras.sugar.ref.LocalRef;

/**
 * A guest written against the native kiln: it remembers the raw pot right before it is baked, and glazes the baked pot at its
 * store, reading what it remembered.
 */
@Mixin(Kiln.class)
abstract class KilnMixin {
	@Shadow public Pot pot;

	@Inject(method = "fire", at = @At(value = "INVOKE", target = "Lorg/pottery/kiln/Pot;baked()Lorg/pottery/kiln/Pot;"))
	private void glazer$remember(CallbackInfo info, @Share("raw") LocalRef<Pot> raw) {
		raw.set(this.pot.copy());
	}

	@ModifyVariable(method = "fire", at = @At("STORE"), ordinal = 0)
	private Pot glazer$glaze(Pot value, @Share("raw") LocalRef<Pot> raw) {
		Objects.requireNonNull(raw.get(), "raw pot is null");
		Trace.add("glaze " + value + " from " + raw.get());
		return value.glazed();
	}
}
