/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.mixin;

import java.util.*;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import net.forbric.kernel.boot.*;
import net.forbric.kernel.transform.*;

/** Installs a proved source alternative at the actual pre-event predicate island, preserving the native branch. */
public final class CrossHostPredicateIslandInjector implements ClassTransformer {
    private static final String RUNTIME="net/forbric/kernel/runtime/KernelFluidPredicateIslands";
    @Override public AnchorSet anchors(){return AnchorSet.scanned("registered native/source predicate islands");}
    @Override public byte[]transform(String name,byte[]bytes,TransformContext context){
        if(!MixinCrossHostPredicateIsland.enabled()||bytes==null)return bytes;ClassNode node=new ClassNode();new ClassReader(bytes).accept(node,0);boolean changed=false;
        for(var registered:MixinCrossHostPredicateIsland.plans())if(registered.plan().owner().equals(node.name)){
            var plan=registered.plan();MethodNode method=CrossHostPredicateIslandPlan.find(node,plan.method().name,plan.method().desc);if(method==null||!MixinInstructionFingerprint.hash(method).equals(MixinInstructionFingerprint.hash(plan.method())))continue;
            List<AbstractInsnNode>before=opcodes(method);int start=opcodes(plan.method()).indexOf(plan.start()),end=opcodes(plan.method()).indexOf(plan.end());if(start<0||end<0)continue;
            AbstractInsnNode nativeStart=before.get(start),event=before.get(end);LabelNode nativeLabel=new LabelNode(),eventLabel=new LabelNode();method.instructions.insertBefore(event,eventLabel);
            int decision=method.maxLocals;InsnList prefix=new InsnList();prefix.add(new VarInsnNode(Opcodes.ALOAD,0));prefix.add(new VarInsnNode(Opcodes.ALOAD,1));prefix.add(new VarInsnNode(Opcodes.ALOAD,plan.interactionLocal()));prefix.add(new LdcInsnNode(registered.key()));prefix.add(new MethodInsnNode(Opcodes.INVOKESTATIC,RUNTIME,"current","(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/String;)I",false));prefix.add(new VarInsnNode(Opcodes.ISTORE,decision));prefix.add(new VarInsnNode(Opcodes.ILOAD,decision));prefix.add(new JumpInsnNode(Opcodes.IFLT,nativeLabel));prefix.add(new VarInsnNode(Opcodes.ILOAD,decision));prefix.add(new InsnNode(Opcodes.ICONST_2));prefix.add(new InsnNode(Opcodes.IAND));prefix.add(new InsnNode(Opcodes.ICONST_1));prefix.add(new InsnNode(Opcodes.IUSHR));prefix.add(new VarInsnNode(Opcodes.ISTORE,plan.primaryLocal()));prefix.add(new VarInsnNode(Opcodes.ILOAD,decision));prefix.add(new InsnNode(Opcodes.ICONST_1));prefix.add(new InsnNode(Opcodes.IAND));prefix.add(new VarInsnNode(Opcodes.ISTORE,plan.boolLocal()));prefix.add(new VarInsnNode(Opcodes.ILOAD,decision));prefix.add(new InsnNode(Opcodes.ICONST_1));prefix.add(new JumpInsnNode(Opcodes.IF_ICMPNE,eventLabel));prefix.add(new InsnNode(Opcodes.ICONST_0));prefix.add(new VarInsnNode(Opcodes.ISTORE,plan.consumeLocal()));prefix.add(new JumpInsnNode(Opcodes.GOTO,eventLabel));prefix.add(nativeLabel);method.instructions.insertBefore(nativeStart,prefix);method.maxLocals=decision+1;
            KernelCrossHostPredicateContracts.host(registered.key(),new DefinedMethodContracts.MethodContract(node.name,method.name,method.desc,MixinInstructionFingerprint.hash(method)));changed=true;
        }
        if(!changed)return bytes;ClassWriter writer=new ClassWriter(ClassWriter.COMPUTE_FRAMES|ClassWriter.COMPUTE_MAXS){@Override protected String getCommonSuperClass(String left,String right){if(left.equals(right))return left;if(left.startsWith("[")||right.startsWith("["))return "java/lang/Object";Set<String>parents=new LinkedHashSet<>();for(String current=left;current!=null;){parents.add(current);ClassNode value=current.equals(node.name)?node:NativeGameReferences.current(current);current=value==null?null:value.superName;}for(String current=right;current!=null;){if(parents.contains(current))return current;ClassNode value=current.equals(node.name)?node:NativeGameReferences.current(current);current=value==null?null:value.superName;}return "java/lang/Object";}};node.accept(writer);return writer.toByteArray();
    }
    private static List<AbstractInsnNode>opcodes(MethodNode method){List<AbstractInsnNode>out=new ArrayList<>();for(AbstractInsnNode i:method.instructions)if(i.getOpcode()>=0)out.add(i);return out;}
}
