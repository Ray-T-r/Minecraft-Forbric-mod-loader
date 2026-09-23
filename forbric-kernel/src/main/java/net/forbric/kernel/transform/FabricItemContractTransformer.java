/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.transform;

import java.util.function.Predicate;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;

/** Native item decisions remain the default; the actual Fabric events and item overrides run above them. */
public final class FabricItemContractTransformer implements ClassTransformer {
 public static final String PROPERTY="forbric.fabricItemContracts";
 public static final String API="net/fabricmc/fabric/api/item/v1/FabricItem";
 public static final String HELPER="net/minecraft/world/item/enchantment/EnchantmentHelper";
 public static final String STACK="net/minecraft/world/item/ItemStack", HOLDER="Lnet/minecraft/core/Holder;";
 public static final String NATIVE="net/neoforged/neoforge/common/extensions/IItemStackExtension";
 public static final String PRIMARY_HELPER="forbric$primaryEnchantment";
 public static final String PRIMARY_DESC="(L"+STACK+";"+HOLDER+")Z";
 private final Predicate<String> present;
 public FabricItemContractTransformer(Predicate<String> present){this.present=present;}
 private boolean enabled(){return !"off".equalsIgnoreCase(System.getProperty(PROPERTY,"on"))&&present.test(API)&&present.test(NATIVE);}
 @Override public String name(){return "forbric-fabric-item-contracts";}
 @Override public AnchorSet anchors(){return !enabled()?AnchorSet.scanned("Fabric item API absent or adapter explicitly disabled"):AnchorSet.of(
   new AnchorSet.Anchor(API.replace('/','.'),AnchorSet.Severity.REQUIRED,"Fabric enchantment defaults must retain native item decisions"),
   new AnchorSet.Anchor(HELPER.replace('/','.'),AnchorSet.Severity.REQUIRED,"the native primary-item method reference needs a real Fabric injection site"));}
 @Override public byte[] transform(String name,byte[] bytes,TransformContext context){
  if(bytes==null||(!name.equals(API.replace('/','.'))&&!name.equals(HELPER.replace('/','.')))||!enabled())return bytes;
  ClassNode node=new ClassNode();new ClassReader(bytes).accept(node,0);boolean changed=name.equals(API.replace('/','.'))?nativeFallback(node):primarySite(node);
  if(!changed)return bytes;ClassWriter writer=new ClassWriter(ClassWriter.COMPUTE_MAXS);node.accept(writer);return writer.toByteArray();
 }
 private static boolean nativeFallback(ClassNode node){
  String desc="(L"+STACK+";"+HOLDER+"Lnet/fabricmc/fabric/api/item/v1/EnchantingContext;)Z";
  MethodNode method=node.methods.stream().filter(m->m.name.equals("canBeEnchantedWith")&&m.desc.equals(desc)).findFirst().orElse(null);if(method==null)return false;
  if(!net.forbric.kernel.mixin.MixinInstructionFingerprint.hash(method).equals("73289ca5407a7788a3e5e7c25883d1497bc9f863ba2d9bd2a2a36eca3117737c"))return false;
  int primary=0,supported=0,values=0,other=0;
  for(var i:method.instructions)if(i instanceof MethodInsnNode c){
   if(c.owner.equals("net/minecraft/world/item/enchantment/Enchantment")&&c.name.equals("isPrimaryItem")&&c.desc.equals("(L"+STACK+";)Z"))primary++;
   else if(c.owner.equals("net/minecraft/world/item/enchantment/Enchantment")&&c.name.equals("canEnchant")&&c.desc.equals("(L"+STACK+";)Z"))supported++;
   else if(c.owner.equals("net/minecraft/core/Holder")&&c.name.equals("value")&&c.desc.equals("()Ljava/lang/Object;"))values++;
   else other++;
  }
  if(primary!=1||supported!=1||values!=2||other!=0)return false;
  method.instructions.clear();method.tryCatchBlocks.clear();method.localVariables=null;
  LabelNode acceptable=new LabelNode();var out=method.instructions;
  out.add(new VarInsnNode(Opcodes.ALOAD,3));out.add(new FieldInsnNode(Opcodes.GETSTATIC,"net/fabricmc/fabric/api/item/v1/EnchantingContext","PRIMARY","Lnet/fabricmc/fabric/api/item/v1/EnchantingContext;"));out.add(new JumpInsnNode(Opcodes.IF_ACMPNE,acceptable));
  out.add(new VarInsnNode(Opcodes.ALOAD,1));out.add(new VarInsnNode(Opcodes.ALOAD,2));out.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE,NATIVE,"isPrimaryItemFor","("+HOLDER+")Z",true));out.add(new InsnNode(Opcodes.IRETURN));
  out.add(acceptable);out.add(new FrameNode(Opcodes.F_SAME,0,null,0,null));out.add(new VarInsnNode(Opcodes.ALOAD,1));out.add(new VarInsnNode(Opcodes.ALOAD,2));out.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE,NATIVE,"supportsEnchantment","("+HOLDER+")Z",true));out.add(new InsnNode(Opcodes.IRETURN));method.maxStack=2;method.maxLocals=4;return true;
 }
 private static boolean primarySite(ClassNode node){
  if(node.methods.stream().anyMatch(m->m.name.equals(PRIMARY_HELPER)))return false;
  MethodNode method=node.methods.stream().filter(m->m.name.equals("getAvailableEnchantmentResults")&&m.desc.equals("(IL"+STACK+";Ljava/util/stream/Stream;)Ljava/util/List;")).findFirst().orElse(null);if(method==null)return false;
  java.util.List<InvokeDynamicInsnNode> sites=new java.util.ArrayList<>();
  for(var instruction:method.instructions)if(instruction instanceof InvokeDynamicInsnNode dynamic
    &&dynamic.bsm.getOwner().equals("java/lang/invoke/LambdaMetafactory")&&dynamic.bsm.getName().equals("metafactory")
    &&dynamic.bsmArgs.length==3&&dynamic.bsmArgs[0].equals(Type.getMethodType("(Ljava/lang/Object;)Z"))
    &&dynamic.bsmArgs[2].equals(Type.getMethodType("("+HOLDER+")Z"))&&dynamic.desc.equals("(L"+STACK+";)Ljava/util/function/Predicate;"))
   for(Object arg:dynamic.bsmArgs)if(arg instanceof Handle handle&&handle.getTag()==Opcodes.H_INVOKEINTERFACE&&handle.getOwner().equals(NATIVE)&&handle.getName().equals("isPrimaryItemFor")&&handle.getDesc().equals("("+HOLDER+")Z"))sites.add(dynamic);
  if(sites.size()!=1)return false;
  InvokeDynamicInsnNode site=sites.getFirst();for(int i=0;i<site.bsmArgs.length;i++)if(site.bsmArgs[i] instanceof Handle h&&h.getOwner().equals(NATIVE)&&h.getName().equals("isPrimaryItemFor"))site.bsmArgs[i]=new Handle(Opcodes.H_INVOKESTATIC,HELPER,PRIMARY_HELPER,PRIMARY_DESC,false);
  MethodNode bridge=new MethodNode(Opcodes.ACC_PRIVATE|Opcodes.ACC_STATIC|Opcodes.ACC_SYNTHETIC,PRIMARY_HELPER,PRIMARY_DESC,null,null);
  bridge.instructions.add(new VarInsnNode(Opcodes.ALOAD,0));bridge.instructions.add(new VarInsnNode(Opcodes.ALOAD,1));bridge.instructions.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE,NATIVE,"isPrimaryItemFor","("+HOLDER+")Z",true));bridge.instructions.add(new InsnNode(Opcodes.IRETURN));bridge.maxStack=2;bridge.maxLocals=2;node.methods.add(bridge);return true;
 }
}
