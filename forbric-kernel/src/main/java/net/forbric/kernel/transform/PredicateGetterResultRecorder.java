/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.transform;

import java.util.*;
import net.forbric.api.Ecosystem;
import net.forbric.api.ForeignType;
import net.forbric.kernel.boot.KernelSharedPredicateContracts.Site;
import net.forbric.kernel.mixin.MixinInstructionFingerprint;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;

/** Transparent return recording supplies an already executed getter result to a separately proved query site. */
public final class PredicateGetterResultRecorder implements ClassTransformer {
 public static final String PROPERTY="forbric.predicateGetterRecords";
 public static final String RUNTIME="net/forbric/kernel/runtime/KernelSharedPredicateScopes";
 public static final String DESCRIPTOR="(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/String;)V";
 @Override public String name(){return "forbric-predicate-getter-results";}
 @Override public AnchorSet anchors(){return AnchorSet.scanned("getter records are inert unless an exact proved active query consumes the site");}
 @Override public byte[]transform(String name,byte[]bytes,TransformContext context){if(bytes==null||"off".equalsIgnoreCase(System.getProperty(PROPERTY,"on")))return bytes;ClassNode owner=new ClassNode();new ClassReader(bytes).accept(owner,0);int changed=0;String type="L"+ForeignType.FLUID_TYPE.internal(Ecosystem.NEOFORGE)+";";
  for(MethodNode method:owner.methods)if((method.access&(Opcodes.ACC_STATIC|Opcodes.ACC_NATIVE|Opcodes.ACC_ABSTRACT))==0&&method.desc.equals("()"+type)&&method.instructions.size()>0&&!recorded(method)){String site=new Site(owner.name,method.name,method.desc,MixinInstructionFingerprint.hash(method)).id();for(var instruction:method.instructions.toArray())if(instruction.getOpcode()==Opcodes.ARETURN){InsnList record=new InsnList();record.add(new InsnNode(Opcodes.DUP));record.add(new VarInsnNode(Opcodes.ALOAD,0));record.add(new LdcInsnNode(site));record.add(new MethodInsnNode(Opcodes.INVOKESTATIC,RUNTIME,"capture",DESCRIPTOR,false));method.instructions.insertBefore(instruction,record);}changed++;}
  if(changed==0)return bytes;ClassWriter writer=new ClassWriter(ClassWriter.COMPUTE_MAXS);owner.accept(writer);return writer.toByteArray();
 }
 private static boolean recorded(MethodNode method){for(var instruction:method.instructions)if(instruction instanceof MethodInsnNode call&&call.owner.equals(RUNTIME)&&call.name.equals("capture")&&call.desc.equals(DESCRIPTOR))return true;return false;}
}
