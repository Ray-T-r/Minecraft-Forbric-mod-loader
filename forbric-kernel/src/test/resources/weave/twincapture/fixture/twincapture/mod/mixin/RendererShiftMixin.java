package fixture.twincapture.mod.mixin;

import fixture.twincapture.Sink;
import fixture.twincapture.mod.Captures;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Another mod's injector on the same method, two nodes past the submit call: {@code Shift.BY} counts labels and line
 * numbers too, so on the folded body it lands after the join's label and runs on every path into the tail.
 */
@Mixin(targets = "fixture.twincapture.Renderers$1")
public abstract class RendererShiftMixin {
	@Inject(method = "render", at = @At(value = "INVOKE", target = "Lfixture/twincapture/Sink;submit(Ljava/lang/String;)V",
			shift = At.Shift.BY, by = 2))
	private void twinshift$mark(String item, Sink sink, CallbackInfo ci) {
		Captures.SHIFTED.add(String.valueOf(item));
	}
}
