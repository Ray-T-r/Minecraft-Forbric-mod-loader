package forbric.outcome;
import java.util.*;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.*;
public final class OutcomePlugin implements IMixinConfigPlugin {
 public void onLoad(String pkg){}
 public String getRefMapperConfig(){return null;}
 public boolean shouldApplyMixin(String target,String mixin){String mode=System.getProperty("forbric.outcomeMode", "declined");return mixin.endsWith("RequiredMixin")?mode.equals("required"):mode.equals("optional");}
 public void acceptTargets(Set<String> mine,Set<String> others){}
 public List<String> getMixins(){return null;}
 public void preApply(String target,ClassNode node,String mixin,IMixinInfo info){}
 public void postApply(String target,ClassNode node,String mixin,IMixinInfo info){}
}
