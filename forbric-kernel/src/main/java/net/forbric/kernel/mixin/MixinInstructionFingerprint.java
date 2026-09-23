/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.mixin;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;
/** Executable instructions and control-flow only; debug, frames, access and max-stack metadata are excluded. */
final class MixinInstructionFingerprint {
 private MixinInstructionFingerprint() { }
 static String hash(MethodNode original) {
  MethodNode method=new MethodNode(original.access&Opcodes.ACC_STATIC,"body",original.desc,null,null);
  var labels=new java.util.IdentityHashMap<LabelNode,LabelNode>();for(var instruction:original.instructions)if(instruction instanceof LabelNode label)labels.put(label,new LabelNode());
  for(var instruction:original.instructions)if(instruction.getOpcode()>=0||instruction instanceof LabelNode)method.instructions.add(instruction.clone(labels));
  for(var t:original.tryCatchBlocks)method.tryCatchBlocks.add(new TryCatchBlockNode(labels.get(t.start),labels.get(t.end),labels.get(t.handler),t.type));
  org.objectweb.asm.ClassWriter writer=new org.objectweb.asm.ClassWriter(0);writer.visit(Opcodes.V21,Opcodes.ACC_PUBLIC,"audit/Method",null,"java/lang/Object",null);method.accept(writer);writer.visitEnd();
  try{return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(writer.toByteArray()));}
  catch(java.security.NoSuchAlgorithmException impossible){throw new AssertionError(impossible);}
 
 }
}
