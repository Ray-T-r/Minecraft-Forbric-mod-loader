package fixture.crosshostreader;
import net.minecraft.world.entity.*;
/** One base tick per case; prints what was asked, in order, and the carrier's breathe event. */
public final class Probe{
 public void probe(){
  net.neoforged.neoforge.common.NeoForgeMod.WATER_TYPE.value();
  // The kernel's physical tag view over the interaction, as the boot registers it: the predicate copy that makes the tag
  // queries itself (no island to read) is checked against it.
  try(var in=EntityFluidInteraction.class.getResourceAsStream("/net/minecraft/world/entity/EntityFluidInteraction.class")){
   org.objectweb.asm.tree.ClassNode node=new org.objectweb.asm.tree.ClassNode();new org.objectweb.asm.ClassReader(in.readAllBytes()).accept(node,0);
   var update=node.methods.stream().filter(m->m.name.equals("update")).findFirst().orElseThrow();
   var witness=new net.forbric.kernel.boot.DefinedMethodContracts.MethodContract(node.name,"update","()V",net.forbric.kernel.boot.DefinedMethodContracts.fingerprint(update));
   net.forbric.kernel.boot.KernelTagSourceContracts.register("reader-physical",new net.forbric.kernel.boot.KernelTagSourceContracts.Schema(node.name,"sourceMap","ready",
     "net/minecraft/world/entity/EntityFluidInteraction$Tracker","net/minecraft/tags/TagKey",witness,java.util.List.of(witness),java.util.List.of(),java.util.Map.of(),java.util.List.of(),null,null));
  }catch(java.io.IOException unreadable){throw new java.io.UncheckedIOException(unreadable);}
  StringBuilder out=new StringBuilder();
  for(String test:new String[]{"pool","pool-breather","pool-tank","water","water-breather","air"}){
   com.tern.gill.Tank.full=test.endsWith("tank");
   LivingEntity body=new LivingEntity();body.breather=test.endsWith("breather");body.submerged=test.startsWith("water");body.pooled=test.startsWith("pool");
   body.interaction.nativeMap.clear();
   if(body.pooled)body.interaction.nativeMap.put(new net.neoforged.neoforge.fluids.OwnedType(),new EntityFluidInteraction.Tracker());
   if(body.submerged)body.interaction.nativeMap.put((net.neoforged.neoforge.fluids.FluidType)net.neoforged.neoforge.common.NeoForgeMod.WATER_TYPE.value(),new EntityFluidInteraction.Tracker());
   Trace.LINES.clear();body.baseTick();
   out.append(out.length()==0?"":" | ").append(test).append(": ").append(String.join(", ",Trace.LINES));
  }
  System.out.println("[CrossHostReader] "+out);
 }
}
