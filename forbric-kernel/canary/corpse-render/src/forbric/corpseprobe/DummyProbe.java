package forbric.corpseprobe;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
@Mixin(targets="de.maxhenkel.corpse.entities.DummyPlayer",remap=false)
public abstract class DummyProbe {
 @Inject(method="<init>(Lnet/minecraft/client/multiplayer/ClientLevel;Lcom/mojang/authlib/GameProfile;Ljava/util/EnumMap;B)V",at=@At("RETURN"),remap=false)
 private void forbric$observeCompletedDummy(CallbackInfo callback) {
  double value=((LivingEntity)(Object)this).getAttributeValue(Attributes.NAME_TAG_DISTANCE);
  System.out.println("[CorpseRenderProbe] "+(value==0?"PASS":"FAIL")+" actual dummy name-tag distance="+value);
 }
}
