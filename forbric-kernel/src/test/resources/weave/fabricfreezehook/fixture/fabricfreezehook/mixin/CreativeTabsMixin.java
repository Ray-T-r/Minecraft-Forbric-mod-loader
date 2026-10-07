package fixture.fabricfreezehook.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import fixture.fabricfreezehook.Client;
import fixture.fabricfreezehook.Trace;
import net.minecraft.core.registries.BuiltInRegistries;

/**
 * Synthetic guest mixin in the shape of LiquidBounce's {@code MixinBuiltInRegistries}: an injector in
 * {@code bootStrap()} at its call of {@code freeze()}, whose handler builds creative tabs whose initialisers read the
 * client instance. On Fabric with fabric-registry-sync that call runs after the entrypoints, with the client there.
 */
@Mixin(BuiltInRegistries.class)
public class CreativeTabsMixin {
	@Inject(method = "bootStrap", at = @At(value = "INVOKE", target = "Lnet/minecraft/core/registries/BuiltInRegistries;freeze()V"))
	private static void initializeTabs(CallbackInfo info) {
		Trace.add(Client.instance == null ? "tabs:no-client" : "tabs:client");
	}
}
