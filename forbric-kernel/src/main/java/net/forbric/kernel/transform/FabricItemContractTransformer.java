/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.transform;

import java.util.*;
import java.util.function.Predicate;
import net.forbric.kernel.mixin.HandlerFlow;
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
 private static final String CONTEXT="net/fabricmc/fabric/api/item/v1/EnchantingContext",ENCHANTMENT="net/minecraft/world/item/enchantment/Enchantment";
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
  if(!vanillaDecisionDefault(node.name,method))return false;
  method.instructions.clear();method.tryCatchBlocks.clear();method.localVariables=null;
  LabelNode acceptable=new LabelNode();var out=method.instructions;
  out.add(new VarInsnNode(Opcodes.ALOAD,3));out.add(new FieldInsnNode(Opcodes.GETSTATIC,CONTEXT,"PRIMARY","L"+CONTEXT+";"));out.add(new JumpInsnNode(Opcodes.IF_ACMPNE,acceptable));
  out.add(new VarInsnNode(Opcodes.ALOAD,1));out.add(new VarInsnNode(Opcodes.ALOAD,2));out.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE,NATIVE,"isPrimaryItemFor","("+HOLDER+")Z",true));out.add(new InsnNode(Opcodes.IRETURN));
  out.add(acceptable);out.add(new FrameNode(Opcodes.F_SAME,0,null,0,null));out.add(new VarInsnNode(Opcodes.ALOAD,1));out.add(new VarInsnNode(Opcodes.ALOAD,2));out.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE,NATIVE,"supportsEnchantment","("+HOLDER+")Z",true));out.add(new InsnNode(Opcodes.IRETURN));method.maxStack=2;method.maxLocals=4;return true;
 }
 /**
  * Whether the public default {@code canBeEnchantedWith(stack, enchantment, context)} is vanilla's two decisions and
  * nothing else, read by its data flow rather than by one release's bytes: it answers
  * {@code enchantment.value().isPrimaryItem(stack)} when {@code context} is the public {@code EnchantingContext.PRIMARY},
  * and {@code enchantment.value().canEnchant(stack)} otherwise — every return is one of those two answers, the
  * comparison decides which runs, and the body calls, writes and throws nothing else. Another fabric-api build that keeps
  * that contract is rewritten whatever its bytes; one whose default decides anything else is left as written.
  */
 static boolean vanillaDecisionDefault(String owner,MethodNode method){
  if((method.access&(Opcodes.ACC_STATIC|Opcodes.ACC_ABSTRACT))!=0)return false;
  HandlerFlow flow=HandlerFlow.of(owner,method);if(flow==null||!flow.acyclic())return false;
  Predicate<MethodInsnNode> value=c->c.owner.equals("net/minecraft/core/Holder")&&c.name.equals("value")&&c.desc.equals("()Ljava/lang/Object;");
  Predicate<MethodInsnNode> primary=c->c.owner.equals(ENCHANTMENT)&&c.name.equals("isPrimaryItem")&&c.desc.equals("(L"+STACK+";)Z");
  Predicate<MethodInsnNode> supported=c->c.owner.equals(ENCHANTMENT)&&c.name.equals("canEnchant")&&c.desc.equals("(L"+STACK+";)Z");
  if(!flow.onlyCalls(value.or(primary).or(supported)))return false;
  List<MethodInsnNode> primaries=flow.calls(primary),supports=flow.calls(supported);if(primaries.size()!=1||supports.size()!=1)return false;
  MethodInsnNode isPrimary=primaries.getFirst(),canEnchant=supports.getFirst();
  Set<AbstractInsnNode> returned=flow.returned();if(returned.size()!=2||!returned.contains(isPrimary)||!returned.contains(canEnchant))return false;
  for(MethodInsnNode decision:List.of(isPrimary,canEnchant)){
   if(!(HandlerFlow.only(flow.argument(decision,0))instanceof MethodInsnNode holder)||!value.test(holder)||!HandlerFlow.onlyParameter(flow.argument(holder,0),2))return false;
   if(!HandlerFlow.onlyParameter(flow.argument(decision,1),1))return false;
  }
  // The one comparison of the context parameter with the public PRIMARY constant decides which of the two runs.
  List<JumpInsnNode> tests=new ArrayList<>();
  for(AbstractInsnNode instruction:method.instructions)if(instruction instanceof JumpInsnNode jump&&(jump.getOpcode()==Opcodes.IF_ACMPEQ||jump.getOpcode()==Opcodes.IF_ACMPNE)){
   Set<AbstractInsnNode> a=flow.operand(jump,0,2),b=flow.operand(jump,1,2);
   if(HandlerFlow.onlyParameter(a,3)&&primaryConstant(HandlerFlow.only(b))||HandlerFlow.onlyParameter(b,3)&&primaryConstant(HandlerFlow.only(a)))tests.add(jump);
  }
  if(tests.size()!=1)return false;JumpInsnNode test=tests.getFirst();
  Predicate<AbstractInsnNode> either=i->i==isPrimary||i==canEnchant;
  if(flow.reaches(0,either,Set.of(test)))return false;
  int jumped=method.instructions.indexOf(test.label),fallen=method.instructions.indexOf(test)+1;
  int same=test.getOpcode()==Opcodes.IF_ACMPEQ?jumped:fallen,other=test.getOpcode()==Opcodes.IF_ACMPEQ?fallen:jumped;
  return flow.reaches(same,i->i==isPrimary,Set.of())&&!flow.reaches(same,i->i==canEnchant,Set.of())
    &&flow.reaches(other,i->i==canEnchant,Set.of())&&!flow.reaches(other,i->i==isPrimary,Set.of());
 }
 private static boolean primaryConstant(AbstractInsnNode instruction){return instruction instanceof FieldInsnNode field&&field.getOpcode()==Opcodes.GETSTATIC&&field.owner.equals(CONTEXT)&&field.name.equals("PRIMARY")&&field.desc.equals("L"+CONTEXT+";");}
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
