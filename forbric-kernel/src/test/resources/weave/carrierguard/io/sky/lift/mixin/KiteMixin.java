package io.sky.lift.mixin;

import fixture.carrierguard.Trace;
import org.meadow.kite.Kite;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** A guest whose winged kite flies and flutters without a part the kite knows. */
@Mixin(Kite.class)
public abstract class KiteMixin {
	@Inject(method = "canFly", at = @At(value = "FIELD", target = "Lorg/meadow/kite/Kite;PARTS:Ljava/util/List;", opcode = 178), cancellable = true)
	private void sky$allow(CallbackInfoReturnable<Boolean> cir) {
		Trace.add("sky-allow");
		if (((Kite) (Object) this).parts.contains("wing")) cir.setReturnValue(true);
	}

	@Inject(method = "flutter", at = @At(value = "INVOKE", target = "Lorg/meadow/kite/Picker;pick(Ljava/util/List;)Ljava/lang/String;"), cancellable = true)
	private void sky$flutter(CallbackInfo ci) {
		Trace.add("sky-flutter");
		if (((Kite) (Object) this).parts.contains("wing")) {
			Trace.add("custom flutter");
			ci.cancel();
		}
	}

	/** Only watches the pick: where the carrier goes around it, there is no pick to watch. */
	@Inject(method = "flutter", at = @At(value = "INVOKE", target = "Lorg/meadow/kite/Picker;pick(Ljava/util/List;)Ljava/lang/String;"))
	private void sky$watch(CallbackInfo ci) {
		Trace.add("sky-watch");
	}

	@Inject(method = "launch", at = @At(value = "INVOKE", target = "Lorg/meadow/kite/Launcher;go(Lorg/meadow/kite/Kite;)Ljava/lang/String;"), cancellable = true)
	private void sky$launch(CallbackInfoReturnable<Boolean> cir) {
		Trace.add("sky-launch");
	}
}
