package forbric.stubmixins;

import java.util.Map;

import com.google.gson.JsonElement;
import com.google.gson.JsonPrimitive;
import com.llamalad7.mixinextras.sugar.Local;
import net.minecraft.locale.Language;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArgs;
import org.spongepowered.asm.mixin.injection.invoke.arg.Args;

/**
 * malilib's shape: keys under this mod's prefix keep their number formats (%02d, %.2f) instead of vanilla's rewrite
 * to %s. On the merged base loadFromJson(InputStream, BiConsumer) is a stub passing a no-op component consumer to
 * NeoForge's three-argument body, which is where the entries are read and the one the game calls.
 */
@Mixin(Language.class)
public abstract class LanguageFormatMixin {
	@ModifyArgs(method = "loadFromJson(Ljava/io/InputStream;Ljava/util/function/BiConsumer;)V",
			at = @At(value = "INVOKE", target = "Ljava/util/function/BiConsumer;accept(Ljava/lang/Object;Ljava/lang/Object;)V"))
	private static void forbric$keepFormat(Args args, @Local(name = "entry") Map.Entry<String, JsonElement> entry) {
		String key = args.get(0);
		if (key.startsWith("forbricstub.") && entry.getValue() instanceof JsonPrimitive primitive) args.set(1, primitive.getAsString());
	}
}
