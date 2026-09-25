/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.mixin;
import java.util.*;
import java.util.function.Function;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import net.forbric.kernel.transform.FabricItemContractTransformer;

/** Follow the actual native item decision while retaining Fabric's event/context and per-item override. */
public final class FabricEnchantmentMixinAdapter {
 private static final String ROOT="net/fabricmc/fabric/mixin/item/",STACK="net/minecraft/world/item/ItemStack",HOLDER="Lnet/minecraft/core/Holder;";
 private record Route(String mixin,String handler,String originalDesc,String host,String selector,String callOwner,String call,String context,String fingerprint,String caller){
  Route(String mixin,String handler,String originalDesc,String host,String selector,String callOwner,String call,String context,String fingerprint){this(mixin,handler,originalDesc,host,selector,callOwner,call,context,fingerprint,null);}
 }
 private static final List<Route> ROUTES=List.of(
  new Route("EnchantCommandMixin","callAllowEnchantingEvent","(Lnet/minecraft/world/item/enchantment/Enchantment;L"+STACK+";Lnet/minecraft/commands/CommandSourceStack;Ljava/util/Collection;"+HOLDER+")Z","net/minecraft/server/commands/EnchantCommand","enchant",STACK,"supportsEnchantment","ACCEPTABLE","306baaaf4d1e5b9a07f88fba0d917657229cd68f02f8b2b81e7711d96615721c"),
  new Route("EnchantRandomlyFunctionMixin","callAllowEnchantingEvent","(Lnet/minecraft/world/item/enchantment/Enchantment;L"+STACK+";ZL"+STACK+";"+HOLDER+")Z","net/minecraft/world/level/storage/loot/functions/EnchantRandomlyFunction","lambda$run$1",STACK,"supportsEnchantment","ACCEPTABLE","382f001750ed446c89b3d535321b00ce4e3426ba73eb2c98bdd103daf47d3282"),
  // The anvil: NeoForge's createResult only calls createResultInternal, where the one supportsEnchantment is.
  new Route("AnvilMenuMixin","callAllowEnchantingEvent","(Lnet/minecraft/world/item/enchantment/Enchantment;L"+STACK+";"+HOLDER+")Z","net/minecraft/world/inventory/AnvilMenu","createResultInternal",STACK,"supportsEnchantment","ACCEPTABLE","435315c8b267fce39331355800303cf84cb6cbdd9b427863228254f8a3337180","createResult"),
  new Route("EnchantmentHelperMixin","useCustomEnchantingChecks","(Lnet/minecraft/world/item/enchantment/Enchantment;L"+STACK+";L"+STACK+";Z"+HOLDER+")Z",FabricItemContractTransformer.HELPER,FabricItemContractTransformer.PRIMARY_HELPER,FabricItemContractTransformer.NATIVE,"isPrimaryItemFor","PRIMARY","3fd5cfcc5fca7bda021fde5f314be0177460b1edd91cc13ebcb05dbdaf9b0896"));
 private FabricEnchantmentMixinAdapter(){}
 public static int adapt(ClassNode mixin,Function<String,ClassNode> targets){
  if("off".equalsIgnoreCase(System.getProperty(FabricItemContractTransformer.PROPERTY,"on")))return 0;
  for(Route route:ROUTES)if(mixin.name.equals(ROOT+route.mixin())){
   MethodNode handler=mixin.methods.stream().filter(m->m.name.equals(route.handler())&&m.desc.equals(route.originalDesc())).findFirst().orElse(null);
   if(handler==null||!route.fingerprint().equals(MixinInstructionFingerprint.hash(handler)))return 0;
   for(var annotations:Arrays.asList(handler.visibleAnnotations,handler.invisibleAnnotations))if(annotations!=null&&annotations.stream().anyMatch(a->a.desc.equals("Lorg/spongepowered/asm/mixin/injection/Group;")))return 0;
   ClassNode target=targets.apply(route.host());if(target==null)return 0;
   List<MethodNode> hosts=target.methods.stream().filter(m->m.name.equals(route.selector())).toList();if(hosts.size()!=1)return 0;
   if(route.caller()!=null&&target.methods.stream().noneMatch(m->m.name.equals(route.caller())&&calls(m,target.name,route.selector())))return 0;
   int calls=0;for(var i:hosts.getFirst().instructions)if(i instanceof MethodInsnNode c&&c.owner.equals(route.callOwner())&&c.name.equals(route.call())&&c.desc.equals("("+HOLDER+")Z"))calls++;
   if(calls!=1)return 0;
   AnnotationNode redirect=MixinFit.injectorOf(handler);if(redirect==null||!redirect.desc.equals("Lorg/spongepowered/asm/mixin/injection/Redirect;"))return 0;
   List<AnnotationNode> points=MixinFit.atNodes(redirect);if(points.size()!=1)return 0;
   AnnotationNode at=points.getFirst();String original=String.valueOf(MixinFit.value(at,"target"));
   if(!original.equals("Lnet/minecraft/world/item/enchantment/Enchantment;"+(route.context().equals("PRIMARY")?"isPrimaryItem":"canEnchant")+"(L"+STACK+";)Z"))return 0;
   set(redirect,"method",new ArrayList<>(List.of(hosts.getFirst().name+hosts.getFirst().desc)));set(at,"target","L"+route.callOwner()+";"+route.call()+"("+HOLDER+")Z");
   handler.desc="(L"+route.callOwner()+";"+HOLDER+")Z";handler.signature=null;handler.parameters=null;handler.visibleParameterAnnotations=null;handler.invisibleParameterAnnotations=null;handler.localVariables=null;handler.tryCatchBlocks.clear();handler.instructions.clear();
   int slot=(handler.access&Opcodes.ACC_STATIC)!=0?0:1;handler.instructions.add(new VarInsnNode(Opcodes.ALOAD,slot));if(!route.callOwner().equals(STACK))handler.instructions.add(new TypeInsnNode(Opcodes.CHECKCAST,STACK));
   handler.instructions.add(new VarInsnNode(Opcodes.ALOAD,slot+1));handler.instructions.add(new FieldInsnNode(Opcodes.GETSTATIC,"net/fabricmc/fabric/api/item/v1/EnchantingContext",route.context(),"Lnet/fabricmc/fabric/api/item/v1/EnchantingContext;"));
   handler.instructions.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL,STACK,"canBeEnchantedWith","("+HOLDER+"Lnet/fabricmc/fabric/api/item/v1/EnchantingContext;)Z",false));handler.instructions.add(new InsnNode(Opcodes.IRETURN));handler.maxStack=3;handler.maxLocals=slot+2;return 1;
  }
  return 0;
 }
 private static boolean calls(MethodNode m,String owner,String name){for(var i:m.instructions)if(i instanceof MethodInsnNode c&&c.owner.equals(owner)&&c.name.equals(name))return true;return false;}
 private static void set(AnnotationNode a,String key,Object value){for(int i=0;i<a.values.size();i+=2)if(key.equals(a.values.get(i))){a.values.set(i+1,value);return;}a.values.add(key);a.values.add(value);}
}
