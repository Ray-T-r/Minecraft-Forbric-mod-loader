package fixture.twincapture.mod.mixin;

import fixture.twincapture.Sink;
import fixture.twincapture.mod.Captures;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.LocalCapture;

/**
 * A TAIL locals capture written against vanilla, which has only {@code Renderers$1} and returns early when the item is
 * null, so the state is in scope at the tail. The target is named by String, as a mixin on an anonymous class must be.
 */
@Mixin(targets = "fixture.twincapture.Renderers$1")
public abstract class RendererCaptureMixin {
	@Inject(method = "render", at = @At("TAIL"), locals = LocalCapture.CAPTURE_FAILSOFT)
	private void twincapture$capture(String item, Sink sink, CallbackInfo ci, String state) {
		Captures.SEEN.add(state);
	}
}
