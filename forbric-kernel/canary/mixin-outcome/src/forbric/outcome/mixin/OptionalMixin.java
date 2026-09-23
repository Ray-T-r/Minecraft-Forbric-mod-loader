package forbric.outcome.mixin;
import forbric.outcome.OutcomeTarget;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
@Mixin(OutcomeTarget.class)
public abstract class OptionalMixin {
 @Inject(method="run",at=@At("HEAD")) private void present(CallbackInfo info){System.out.println("[M36Outcome] optional present handler ran");}
 @Inject(method="run",at=@At(value="INVOKE",target="Ljava/lang/String;length()I"),require=0) private void missing(CallbackInfo info){throw new AssertionError("absent site executed");}
}
