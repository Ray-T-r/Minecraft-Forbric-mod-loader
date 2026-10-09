/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.transform;

import java.util.*;
import java.util.function.Function;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import net.forbric.kernel.util.ByteScan;
import net.forbric.kernel.util.ForbricLog;

/** Recognises complete registry walks with one unconditionally executed callback per element.
 * The only optional guard tests whether the element class implements that exact callback interface.
 * A local batch is published only at the root's normal return; an aborted walk publishes nothing. */
public final class RegistryElementCallbackInjector implements ClassTransformer {
    /** {@code -Dforbric.registryElementCallbacks=off} leaves every walk, and its late completion, where the mod put it. */
    public static final String PROPERTY="forbric.registryElementCallbacks";
    private static final String REGISTRY_OWNER="net/minecraft/world/level/block/Block", REGISTRY="BLOCK_STATE_REGISTRY";
    private static final String REGISTRY_DESC="Lnet/minecraft/core/IdMapper;", ELEMENT="net/minecraft/world/level/block/state/BlockState";
    private static final byte[][] CANDIDATE={ByteScan.needle(REGISTRY)};
    private static final String HOOK="net/forbric/kernel/interop/RegistryElementCallbacks";
    private final Function<String,ClassNode> declarations;
    public RegistryElementCallbackInjector(Function<String,ClassNode> declarations){this.declarations=declarations;}
    @Override public AnchorSet anchors(){return AnchorSet.scanned("closed per-element registry initializer loops");}
    @Override public byte[] transform(String name,byte[] bytes,TransformContext context){
        if(bytes==null||!ByteScan.containsAny(bytes,CANDIDATE)||"off".equalsIgnoreCase(net.forbric.kernel.util.ForbricSwitches.get(PROPERTY,"on")))return bytes;
        ClassNode node=new ClassNode();new ClassReader(bytes).accept(node,ClassReader.EXPAND_FRAMES);boolean changed=false;
        for(MethodNode method:node.methods){
            List<Loop> loops=loops(method);if(loops.isEmpty())continue;
            // The token occupies slot 0. Shift the original reference-only locals and their expanded stack maps.
            // This avoids reflective hierarchy loading by COMPUTE_FRAMES for arbitrary guest interfaces.
            for(AbstractInsnNode i:method.instructions){
                if(i instanceof VarInsnNode variable)variable.var++;
                if(i instanceof FrameNode frame){if(frame.local==null)frame.local=new ArrayList<>();frame.local.addFirst("java/lang/Object");}
            }
            if(method.localVariables!=null)for(LocalVariableNode local:method.localVariables)local.index++;
            for(List<LocalVariableAnnotationNode> annotations:Arrays.asList(method.visibleLocalVariableAnnotations,method.invisibleLocalVariableAnnotations))
                if(annotations!=null)for(LocalVariableAnnotationNode annotation:annotations)
                    for(int j=0;j<annotation.index.size();j++)annotation.index.set(j,annotation.index.get(j)+1);
            method.maxLocals++;
            InsnList begin=new InsnList();begin.add(new LdcInsnNode(Type.getObjectType(node.name)));begin.add(new LdcInsnNode(method.name));
            begin.add(new MethodInsnNode(Opcodes.INVOKESTATIC,HOOK,"begin","(Ljava/lang/Class;Ljava/lang/String;)Ljava/lang/Object;",false));
            begin.add(new VarInsnNode(Opcodes.ASTORE,0));method.instructions.insert(begin);
            for(Loop loop:loops){
                InsnList declare=new InsnList();declare.add(new VarInsnNode(Opcodes.ALOAD,0));
                declare.add(new LdcInsnNode(Type.getObjectType(loop.callback.owner)));declare.add(new LdcInsnNode(loop.callback.name));
                declare.add(new FieldInsnNode(Opcodes.GETSTATIC,REGISTRY_OWNER,REGISTRY,REGISTRY_DESC));
                declare.add(new MethodInsnNode(Opcodes.INVOKESTATIC,HOOK,"declare","(Ljava/lang/Object;Ljava/lang/Class;Ljava/lang/String;Ljava/lang/Object;)V",false));
                method.instructions.insertBefore(loop.registryRead,declare);
                method.instructions.insertBefore(loop.callback,new InsnNode(Opcodes.DUP));
                InsnList record=new InsnList();record.add(new VarInsnNode(Opcodes.ALOAD,0));
                record.add(new LdcInsnNode(Type.getObjectType(loop.callback.owner)));record.add(new LdcInsnNode(loop.callback.name));
                record.add(new FieldInsnNode(Opcodes.GETSTATIC,REGISTRY_OWNER,REGISTRY,REGISTRY_DESC));
                record.add(new MethodInsnNode(Opcodes.INVOKESTATIC,HOOK,"completed","(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Class;Ljava/lang/String;Ljava/lang/Object;)V",false));
                method.instructions.insert(loop.callback,record);
            }
            AbstractInsnNode exit=WorkerPoolShape.code(method).getLast();
            InsnList commit=new InsnList();commit.add(new VarInsnNode(Opcodes.ALOAD,0));
            commit.add(new MethodInsnNode(Opcodes.INVOKESTATIC,HOOK,"commit","(Ljava/lang/Object;)V",false));method.instructions.insertBefore(exit,commit);
            changed=true;
            // The only trace this mechanism leaves at transform time; the late-registration completion logs its own count.
            ForbricLog.info("[Forbric/RegistryCallbacks] %s.%s is a closed walk of the block-state registry with %d per-element "
                    +"callback(s) — a state registered after the walk will receive the same callback(s) once",
                    node.name.replace('/','.'),method.name,loops.size());
        }
        if(!changed)return bytes;ClassWriter writer=new ClassWriter(ClassWriter.COMPUTE_MAXS);node.accept(writer);return writer.toByteArray();
    }
    private record Loop(FieldInsnNode registryRead,MethodInsnNode callback){}
    private List<Loop> loops(MethodNode method){
        if((method.access&(Opcodes.ACC_STATIC|Opcodes.ACC_ABSTRACT|Opcodes.ACC_NATIVE|Opcodes.ACC_SYNCHRONIZED))!=Opcodes.ACC_STATIC
                ||!method.desc.equals("()V")||method.name.startsWith("<")||!method.tryCatchBlocks.isEmpty())return List.of();
        List<AbstractInsnNode> c=WorkerPoolShape.code(method);List<Loop> result=new ArrayList<>();Set<String> signatures=new HashSet<>();int i=0;
        while(i<c.size()-1){
            Type guarded=null;JumpInsnNode guard=null;
            if(c.get(i) instanceof LdcInsnNode literal&&literal.cst instanceof Type type){
                if(i+3>=c.size()||type.getSort()!=Type.OBJECT||!(c.get(i+1) instanceof LdcInsnNode element)
                        ||!Type.getObjectType(ELEMENT).equals(element.cst)
                        ||!call(c.get(i+2),Opcodes.INVOKEVIRTUAL,"java/lang/Class","isAssignableFrom","(Ljava/lang/Class;)Z")
                        ||!(c.get(i+3) instanceof JumpInsnNode jump)||jump.getOpcode()!=Opcodes.IFEQ)return List.of();
                guarded=type;guard=jump;i+=4;
            }
            // registry.iterator -> local; hasNext gate -> next/cast/store -> one callback -> same gate.
            if(i+14>=c.size()||!(c.get(i) instanceof FieldInsnNode registry)||registry.getOpcode()!=Opcodes.GETSTATIC
                    ||!registry.owner.equals(REGISTRY_OWNER)||!registry.name.equals(REGISTRY)||!registry.desc.equals(REGISTRY_DESC)
                    ||!call(c.get(i+1),Opcodes.INVOKEVIRTUAL,"net/minecraft/core/IdMapper","iterator","()Ljava/util/Iterator;")
                    ||!(c.get(i+2) instanceof VarInsnNode iterator)||iterator.getOpcode()!=Opcodes.ASTORE
                    ||!var(c.get(i+3),Opcodes.ALOAD,iterator.var)
                    ||!call(c.get(i+4),Opcodes.INVOKEINTERFACE,"java/util/Iterator","hasNext","()Z")
                    ||!(c.get(i+5) instanceof JumpInsnNode empty)||empty.getOpcode()!=Opcodes.IFEQ
                    ||!var(c.get(i+6),Opcodes.ALOAD,iterator.var)
                    ||!call(c.get(i+7),Opcodes.INVOKEINTERFACE,"java/util/Iterator","next","()Ljava/lang/Object;")
                    ||!cast(c.get(i+8),ELEMENT)||!(c.get(i+9) instanceof VarInsnNode element)||element.getOpcode()!=Opcodes.ASTORE
                    ||element.var==iterator.var||!var(c.get(i+10),Opcodes.ALOAD,element.var)
                    ||!(c.get(i+11) instanceof TypeInsnNode contractCast)||contractCast.getOpcode()!=Opcodes.CHECKCAST
                    ||!(c.get(i+12) instanceof MethodInsnNode callback)||callback.getOpcode()!=Opcodes.INVOKEINTERFACE||!callback.itf
                    ||!callback.owner.equals(contractCast.desc)||!callback.desc.equals("()V")
                    ||!(c.get(i+13) instanceof JumpInsnNode back)||back.getOpcode()!=Opcodes.GOTO
                    ||nextReal(back.label)!=c.get(i+3)||nextReal(empty.label)!=c.get(i+14)
                    ||guard!=null&&(nextReal(guard.label)!=c.get(i+14)||!guarded.getInternalName().equals(callback.owner))
                    ||!signatures.add(callback.owner+"#"+callback.name))return List.of();
            ClassNode contract=declarations.apply(callback.owner);
            MethodNode member=contract==null?null:WorkerPoolShape.method(contract,callback.name,"()V");
            if(contract==null||(contract.access&(Opcodes.ACC_PUBLIC|Opcodes.ACC_INTERFACE))!=(Opcodes.ACC_PUBLIC|Opcodes.ACC_INTERFACE)
                    ||member==null||(member.access&(Opcodes.ACC_PUBLIC|Opcodes.ACC_STATIC))!=Opcodes.ACC_PUBLIC)return List.of();
            result.add(new Loop(registry,callback));i+=14;
        }
        return !result.isEmpty()&&i==c.size()-1&&c.get(i).getOpcode()==Opcodes.RETURN?result:List.of();
    }
    private static AbstractInsnNode nextReal(AbstractInsnNode i){while(i!=null&&i.getOpcode()<0)i=i.getNext();return i;}
    private static boolean cast(AbstractInsnNode i,String owner){return i instanceof TypeInsnNode t&&t.getOpcode()==Opcodes.CHECKCAST&&t.desc.equals(owner);}
    private static boolean var(AbstractInsnNode i,int opcode,int slot){return i instanceof VarInsnNode v&&v.getOpcode()==opcode&&v.var==slot;}
    private static boolean call(AbstractInsnNode i,int opcode,String owner,String name,String desc){return i instanceof MethodInsnNode m&&m.getOpcode()==opcode&&m.owner.equals(owner)&&m.name.equals(name)&&m.desc.equals(desc);}
}
