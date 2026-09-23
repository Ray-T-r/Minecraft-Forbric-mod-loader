package forbric.corpseprobe;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
@Mixin(targets="de.maxhenkel.corpse.entities.CorpseRenderer",remap=false)
public abstract class RenderProbe {
 @Unique private static boolean forbric$observedCorpseSubmit;
 @Inject(method="submit(Lde/maxhenkel/corpse/entities/CorpseRenderState;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;Lnet/minecraft/client/renderer/state/level/CameraRenderState;)V",at=@At("RETURN"),remap=false)
 private void forbric$observeCompletedSubmit(CallbackInfo callback) {
  if(!forbric$observedCorpseSubmit){forbric$observedCorpseSubmit=true;System.out.println("[CorpseRenderProbe] PASS actual corpse renderer submitted");}
 }
}
