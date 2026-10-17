package io.wren.sift.mixin;

import fixture.methodreference.Trace;
import org.meadow.sieve.Pouch;
import org.meadow.sieve.Seed;
import org.meadow.sieve.Sieve;
import org.meadow.sieve.Trait;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** A guest written against the native lambda: it decides the trait question itself, with the lambda's own captures in hand. */
@Mixin(Sieve.class)
abstract class SieveMixin {
	@Redirect(method = "lambda$pick$0", at = @At(value = "INVOKE", target = "Lorg/meadow/sieve/Trait;fits(Lorg/meadow/sieve/Pouch;)Z"))
	private static boolean sift$fits(Trait trait, Pouch pouch, Pouch captured, boolean bare, Seed seed) {
		Trace.add("sift " + seed + (bare ? " bare" : ""));
		return seed.name().startsWith("w");
	}
}
