/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.mixin;
import java.util.*;
import java.util.function.Function;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import net.forbric.kernel.transform.FabricItemContractTransformer;

/**
 * The enchanting table's primary-item question for fabric-item-api's {@code EnchantmentHelperMixin}: the one enchanting route
 * {@link CarrierDecisions} cannot move by structure, so it is still named here.
 *
 * <p>The command, loot and anvil routes this adapter used to name are moved by {@link CarrierDecisions}: there the carrier
 * asks {@code supportsEnchantment} where vanilla asked {@code canEnchant}, and the two ways on are the same. The table is
 * different in three ways, each of which leaves nothing structural to move onto. Natively the decision is a lambda
 * {@code holder.value().isPrimaryItem(itemStack) || isBook}; NeoForge replaced it with a method reference to
 * {@code IItemStackExtension.isPrimaryItemFor}, so the merged class has no call to stand in for the native one, only a
 * handle. The default chain behind that handle never calls {@code Enchantment.isPrimaryItem}: NeoForge rewrote its body
 * ({@code supportsEnchantment} instead of {@code isSupportedItem}), so no call in the chain is the native call either. And
 * the carrier folded the book check into its question, so the native residue {@code || isBook} after the redirected call
 * has no counterpart: a redirect on the carrier's question answers for books too, where natively the book check would win.
 * What remains is the transformer's typed bridge ({@link FabricItemContractTransformer#PRIMARY_HELPER}) and this route,
 * pinned to the exact handler body it replaces with the same call on the same values; any other body is left as compiled.
 */
public final class FabricEnchantmentMixinAdapter {
 private static final String ROOT="net/fabricmc/fabric/mixin/item/",STACK="net/minecraft/world/item/ItemStack",HOLDER="Lnet/minecraft/core/Holder;";
 private record Route(String mixin,String handler,String originalDesc,String host,String selector,String callOwner,String call,String context,String fingerprint){}
 private static final List<Route> ROUTES=List.of(
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
   int calls=0;for(var i:hosts.getFirst().instructions)if(i instanceof MethodInsnNode c&&c.owner.equals(route.callOwner())&&c.name.equals(route.call())&&c.desc.equals("("+HOLDER+")Z"))calls++;
   if(calls!=1)return 0;
   AnnotationNode redirect=MixinFit.injectorOf(handler);if(redirect==null||!redirect.desc.equals("Lorg/spongepowered/asm/mixin/injection/Redirect;"))return 0;
   List<AnnotationNode> points=MixinFit.atNodes(redirect);if(points.size()!=1)return 0;
   AnnotationNode at=points.getFirst();String original=String.valueOf(MixinFit.value(at,"target"));
   if(!original.equals("Lnet/minecraft/world/item/enchantment/Enchantment;isPrimaryItem(L"+STACK+";)Z"))return 0;
   set(redirect,"method",new ArrayList<>(List.of(hosts.getFirst().name+hosts.getFirst().desc)));set(at,"target","L"+route.callOwner()+";"+route.call()+"("+HOLDER+")Z");
   handler.desc="(L"+route.callOwner()+";"+HOLDER+")Z";handler.signature=null;handler.parameters=null;handler.visibleParameterAnnotations=null;handler.invisibleParameterAnnotations=null;handler.localVariables=null;handler.tryCatchBlocks.clear();handler.instructions.clear();
   int slot=(handler.access&Opcodes.ACC_STATIC)!=0?0:1;handler.instructions.add(new VarInsnNode(Opcodes.ALOAD,slot));handler.instructions.add(new TypeInsnNode(Opcodes.CHECKCAST,STACK));
   handler.instructions.add(new VarInsnNode(Opcodes.ALOAD,slot+1));handler.instructions.add(new FieldInsnNode(Opcodes.GETSTATIC,"net/fabricmc/fabric/api/item/v1/EnchantingContext",route.context(),"Lnet/fabricmc/fabric/api/item/v1/EnchantingContext;"));
   handler.instructions.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL,STACK,"canBeEnchantedWith","("+HOLDER+"Lnet/fabricmc/fabric/api/item/v1/EnchantingContext;)Z",false));handler.instructions.add(new InsnNode(Opcodes.IRETURN));handler.maxStack=3;handler.maxLocals=slot+2;return 1;
  }
  return 0;
 }
 private static void set(AnnotationNode a,String key,Object value){for(int i=0;i<a.values.size();i+=2)if(key.equals(a.values.get(i))){a.values.set(i+1,value);return;}a.values.add(key);a.values.add(value);}
}
