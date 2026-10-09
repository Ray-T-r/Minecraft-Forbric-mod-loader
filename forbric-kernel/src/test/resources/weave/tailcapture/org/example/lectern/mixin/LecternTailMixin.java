package org.example.lectern.mixin;

import java.util.List;

import fixture.tailcapture.Lines;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.LocalCapture;

/**
 * A TAIL locals capture written another way than Item Glint Relight's: another mod, class and method; the target named by
 * string; an owner-qualified selector; CAPTURE_FAILHARD; two captured locals, the first a primitive. Beside it a TAIL
 * capture of a local the method declares before its guard, which the tail holds on every path and must stay at TAIL.
 */
@Mixin(targets = "net.minecraft.client.renderer.LecternRenderer")
public abstract class LecternTailMixin {
	@Inject(method = "Lnet/minecraft/client/renderer/LecternRenderer;submit(Ljava/lang/Object;Ljava/util/List;I)V",
			at = @At("TAIL"), locals = LocalCapture.CAPTURE_FAILHARD)
	private void lecternlog$afterSubmit(Object book, List<String> out, int light, CallbackInfo ci, int lines, String title) {
		Lines.SEEN.add(title + "#" + lines);
	}

	@Inject(method = "label", at = @At("TAIL"), locals = LocalCapture.CAPTURE_FAILHARD)
	private void lecternlog$afterLabel(Object book, List<String> out, CallbackInfo ci, String tag) {
		Lines.SEEN.add("label " + tag);
	}
}
