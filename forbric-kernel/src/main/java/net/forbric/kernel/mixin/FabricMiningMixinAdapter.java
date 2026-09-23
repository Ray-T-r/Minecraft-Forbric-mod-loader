/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.mixin;
import java.util.*;
import java.util.function.Function;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import net.forbric.kernel.transform.FabricItemContractTransformer;
/** Keep native reset decisions; a same-item Fabric override can explicitly keep the current mining action. */
public final class FabricMiningMixinAdapter {
 private static final String MIXIN="net/fabricmc/fabric/mixin/item/client/MultiPlayerGameModeMixin",TARGET="net/minecraft/client/multiplayer/MultiPlayerGameMode",STACK="net/minecraft/world/item/ItemStack";
 private FabricMiningMixinAdapter(){}
 public static int adapt(ClassNode mixin,Function<String,ClassNode> targets){
  if(!mixin.name.equals(MIXIN)||"off".equalsIgnoreCase(System.getProperty(FabricItemContractTransformer.PROPERTY,"on")))return 0;
  MethodNode handler=mixin.methods.stream().filter(m->m.name.equals("fabricItemContinueBlockBreakingInject")&&m.desc.equals("(L"+STACK+";L"+STACK+";)Z")).findFirst().orElse(null);
  if(handler==null||!MixinInstructionFingerprint.hash(handler).equals("fc6337bc35275d9c053f2ef2d3a4dfcdfbe245555bf6691174bdd227221e9b82"))return 0;
  for(var list:Arrays.asList(handler.visibleAnnotations,handler.invisibleAnnotations))if(list!=null&&list.stream().anyMatch(a->a.desc.equals("Lorg/spongepowered/asm/mixin/injection/Group;")))return 0;
  ClassNode target=targets.apply(TARGET);if(target==null)return 0;
  MethodNode host=target.methods.stream().filter(m->m.name.equals("sameDestroyTarget")&&m.desc.equals("(Lnet/minecraft/core/BlockPos;)Z")).findFirst().orElse(null);if(host==null)return 0;
  int nativeCalls=0,oldCalls=0;for(var instruction:host.instructions)if(instruction instanceof MethodInsnNode call&&call.owner.equals(STACK)){if(call.name.equals("shouldCauseBlockBreakReset")&&call.desc.equals("(L"+STACK+";)Z"))nativeCalls++;if(call.name.equals("isSameItemSameComponents"))oldCalls++;}
  if(nativeCalls!=1||oldCalls!=0)return 0;
  AnnotationNode redirect=MixinFit.injectorOf(handler);if(redirect==null||!redirect.desc.equals("Lorg/spongepowered/asm/mixin/injection/Redirect;"))return 0;
  List<AnnotationNode> points=MixinFit.atNodes(redirect);if(points.size()!=1)return 0;
  AnnotationNode at=points.getFirst();if(!("L"+STACK+";isSameItemSameComponents(L"+STACK+";L"+STACK+";)Z").equals(MixinFit.value(at,"target")))return 0;
  for(int i=0;i<at.values.size();i+=2)if(at.values.get(i).equals("target"))at.values.set(i+1,"L"+STACK+";shouldCauseBlockBreakReset(L"+STACK+";)Z");
  handler.instructions.clear();handler.tryCatchBlocks.clear();handler.localVariables=null;var out=handler.instructions;
  LabelNode keep=new LabelNode(),reset=new LabelNode();
  out.add(new VarInsnNode(Opcodes.ALOAD,1));out.add(new VarInsnNode(Opcodes.ALOAD,2));out.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL,STACK,"shouldCauseBlockBreakReset","(L"+STACK+";)Z",false));out.add(new JumpInsnNode(Opcodes.IFEQ,keep));
  out.add(new VarInsnNode(Opcodes.ALOAD,1));out.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL,STACK,"getItem","()Lnet/minecraft/world/item/Item;",false));out.add(new VarInsnNode(Opcodes.ALOAD,2));out.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL,STACK,"getItem","()Lnet/minecraft/world/item/Item;",false));out.add(new JumpInsnNode(Opcodes.IF_ACMPNE,reset));
  out.add(new VarInsnNode(Opcodes.ALOAD,1));out.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL,STACK,"getItem","()Lnet/minecraft/world/item/Item;",false));out.add(new VarInsnNode(Opcodes.ALOAD,0));out.add(new FieldInsnNode(Opcodes.GETFIELD,MIXIN,"minecraft","Lnet/minecraft/client/Minecraft;"));out.add(new FieldInsnNode(Opcodes.GETFIELD,"net/minecraft/client/Minecraft","player","Lnet/minecraft/client/player/LocalPlayer;"));out.add(new VarInsnNode(Opcodes.ALOAD,1));out.add(new VarInsnNode(Opcodes.ALOAD,2));
  out.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL,"net/minecraft/world/item/Item","allowContinuingBlockBreaking","(Lnet/minecraft/world/entity/player/Player;L"+STACK+";L"+STACK+";)Z",false));out.add(new JumpInsnNode(Opcodes.IFNE,keep));
  out.add(reset);out.add(new FrameNode(Opcodes.F_SAME,0,null,0,null));out.add(new InsnNode(Opcodes.ICONST_1));out.add(new InsnNode(Opcodes.IRETURN));out.add(keep);out.add(new FrameNode(Opcodes.F_SAME,0,null,0,null));out.add(new InsnNode(Opcodes.ICONST_0));out.add(new InsnNode(Opcodes.IRETURN));handler.maxStack=4;handler.maxLocals=3;return 1;
 }
}
