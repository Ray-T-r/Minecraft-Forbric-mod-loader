package net.bramble.sip.mixin;

import fixture.nativevariablepin.Trace;
import org.orchard.press.Mill;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/** A guest that sweetens every pour: the second boolean in scope there, as its own loader has it. */
@Mixin(Mill.class)
public abstract class MillMixin {
	@ModifyVariable(method = "crush", at = @At(value = "INVOKE", target = "Lorg/orchard/press/Cup;pour(Ljava/lang/String;Z)V"), ordinal = 1)
	private boolean sip$sweeten(boolean sweet) {
		Trace.add("sip " + sweet);
		return true;
	}
}
