package io.tern.tally.mixin;

import net.minecraft.fixture.kiln.Hearth;
import net.minecraft.fixture.kiln.Kiln;
import net.minecraft.fixture.kiln.Range;
import net.minecraft.fixture.kiln.Spot;
import net.minecraft.fixture.kiln.Tool;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Cancels the method at its start while the hearth is hushed; handed every argument, the range included. */
@Mixin(Kiln.class)
abstract class KilnHushMixin {
	@Inject(method = "vent", at = @At("HEAD"), cancellable = true)
	private void hush$quiet(Hearth level, Spot pos, Tool tool, Range range, CallbackInfo info) {
		Kiln.LOG.add("hush " + range);
		if (Hearth.hushed) info.cancel();
	}
}
