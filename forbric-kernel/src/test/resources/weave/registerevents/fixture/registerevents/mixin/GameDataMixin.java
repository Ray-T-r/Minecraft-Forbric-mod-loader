package fixture.registerevents.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import fixture.registerevents.Trace;
import net.neoforged.neoforge.registries.GameData;
import net.neoforged.neoforge.registries.RegisterEvent;

/**
 * A guest mixin on NeoForge's GameData.postRegisterEvents: one injector at HEAD, and supermartijn642corelib's
 * GameDataMixin shape — a @ModifyVariable on the event local's STORE and an @Inject after the
 * ModLoader.postEventWrapContainerInModOrder call that reads it back, once per registry.
 */
@Mixin(value = GameData.class, remap = false)
public class GameDataMixin {
	@Unique
	private static RegisterEvent registerEvent;

	@Inject(method = "postRegisterEvents()V", at = @At("HEAD"), remap = false)
	private static void head(CallbackInfo info) {
		Trace.add("mixin:head");
	}

	@ModifyVariable(method = "postRegisterEvents()V", at = @At("STORE"), ordinal = 0, remap = false)
	private static RegisterEvent modifyRegisterEvent(RegisterEvent event) {
		registerEvent = event;
		return event;
	}

	@Inject(method = "postRegisterEvents()V", at = @At(value = "INVOKE",
			target = "Lnet/neoforged/fml/ModLoader;postEventWrapContainerInModOrder(Lnet/neoforged/bus/api/Event;)V",
			shift = At.Shift.AFTER), remap = false)
	private static void afterDispatch(CallbackInfo info) {
		Trace.add("mixin:after:" + registerEvent.getRegistryKey());
	}
}
