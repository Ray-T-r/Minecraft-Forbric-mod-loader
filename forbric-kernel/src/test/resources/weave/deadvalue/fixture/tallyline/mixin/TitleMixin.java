package fixture.tallyline.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import net.minecraft.client.gui.screens.TitleScreen;

/** Written against vanilla's title screen: the modded suffix of the version string becomes a count. */
@Mixin(TitleScreen.class)
public abstract class TitleMixin {
	@WrapOperation(method = "extractRenderState", at = @At(value = "INVOKE",
			target = "Lnet/minecraft/client/resources/language/I18n;get(Ljava/lang/String;[Ljava/lang/Object;)Ljava/lang/String;"))
	private String tally$count(String key, Object[] args, Operation<String> original) {
		return " (5 Things)";
	}
}
