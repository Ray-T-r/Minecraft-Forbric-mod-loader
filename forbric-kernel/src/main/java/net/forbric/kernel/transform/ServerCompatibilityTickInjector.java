/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.transform;

import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;

/** At every normal completed server tick, outside event dispatch and save callbacks. */
public final class ServerCompatibilityTickInjector implements ClassTransformer {
 private static final String TARGET="net.minecraft.server.MinecraftServer";
 private static final String OWNER="net/forbric/kernel/runtime/KernelGameServerLifecycle";
 @Override public String name(){return "forbric-late-server-compatibility";}
 @Override public AnchorSet anchors(){return AnchorSet.of(new AnchorSet.Anchor(TARGET,AnchorSet.Severity.REQUIRED,
   "confirmed required losses discovered after startup must stop a strict dedicated server through normal saving"));}
 @Override public byte[] transform(String name,byte[] bytes,TransformContext context){
  if(!TARGET.equals(name)||bytes==null||bytes.length==0)return bytes;
  ClassNode node=new ClassNode();new ClassReader(bytes).accept(node,0);boolean changed=false;
  for(MethodNode method:node.methods){
   if(!method.name.equals("tickServer")||!method.desc.equals("(Ljava/util/function/BooleanSupplier;)V"))continue;
   for(AbstractInsnNode instruction:method.instructions)if(instruction instanceof MethodInsnNode call&&call.owner.equals(OWNER)&&call.name.equals("onCompatibilityTick"))return bytes;
   for(AbstractInsnNode instruction:method.instructions.toArray())if(instruction.getOpcode()==Opcodes.RETURN){
    InsnList hook=new InsnList();hook.add(new VarInsnNode(Opcodes.ALOAD,0));hook.add(new MethodInsnNode(Opcodes.INVOKESTATIC,OWNER,"onCompatibilityTick","(Ljava/lang/Object;)V",false));method.instructions.insertBefore(instruction,hook);changed=true;
   }
   method.maxStack=Math.max(method.maxStack,1);
  }
  if(!changed)return bytes;
  ClassWriter writer=new ClassWriter(0);node.accept(writer);return writer.toByteArray();
 }
}
