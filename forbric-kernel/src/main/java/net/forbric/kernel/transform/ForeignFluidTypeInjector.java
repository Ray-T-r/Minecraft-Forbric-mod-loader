/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.transform;

import java.util.*;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import net.forbric.api.Ecosystem;
import net.forbric.api.ForeignType;
import net.forbric.kernel.util.ForbricLog;

/** Repairs only the public SDK lookup's proved terminal unsupported branch; native queries and caches stay intact. */
public final class ForeignFluidTypeInjector implements ClassTransformer {
    public static final String PROPERTY="forbric.foreignFluidTypes";
    static final String FLUID="net.minecraft.world.level.material.Fluid",FLUID_INTERNAL=FLUID.replace('.','/');
    static final String TYPE=ForeignType.FLUID_TYPE.internal(Ecosystem.NEOFORGE),FORGE_TYPE=ForeignType.FLUID_TYPE.internal(Ecosystem.FORGE);
    static final String RUNTIME="net/forbric/kernel/runtime/KernelFluidTypes",SCOPES="net/forbric/api/LookupOutcomes";
    static final String NEO_HOOKS="net/neoforged/neoforge/common/CommonHooks",FORGE_HOOKS="net/minecraftforge/common/ForgeHooks";
    private static final Set<String> REPORTED=java.util.concurrent.ConcurrentHashMap.newKeySet();
    static boolean enabled(){return !"off".equalsIgnoreCase(System.getProperty(PROPERTY,"on"));}
    @Override public String name(){return "forbric-foreign-fluid-types";}
    @Override public AnchorSet anchors(){return !enabled()?AnchorSet.scanned("foreign fluid lookups disabled with -D"+PROPERTY):AnchorSet.of(new AnchorSet.Anchor(FLUID,AnchorSet.Severity.REQUIRED,"foreign fluid types require an uncached fallback for each retained SDK getter"));}
    @Override public byte[] transform(String binary,byte[] bytes,TransformContext context){
        if(!enabled()||bytes==null||bytes.length==0)return bytes;String owner=binary.replace('.','/');
        if(!owner.equals(FLUID_INTERNAL)&&!owner.equals(NEO_HOOKS)&&!owner.equals(FORGE_HOOKS))return bytes;
        ClassNode node=new ClassNode();new ClassReader(bytes).accept(node,ClassReader.EXPAND_FRAMES);int changed;
        if(owner.equals(FLUID_INTERNAL))changed=repair(node);else changed=unsupportedDefault(node,owner.equals(NEO_HOOKS)?TYPE:FORGE_TYPE,owner.equals(NEO_HOOKS)?"foreignNeoLookup":"foreignForgeLookup");
        if(changed==0)return bytes;ClassWriter out=new ClassWriter(ClassWriter.COMPUTE_MAXS);node.accept(out);
        if(REPORTED.add(owner))ForbricLog.info("[Forbric/Fluid] %s: %d source-proved fluid-type lookup/cache contract(s) repaired — a fluid with no type of "
                +"a platform's own gets the one its fluid tags imply, instead of \"Mod fluids must override getFluidType\"",binary,changed);
        return out.toByteArray();
    }
    static int repair(ClassNode fluid){
        int changed=0;for(String type:List.of(TYPE,FORGE_TYPE)){MethodNode getter=find(fluid,"getFluidType","()L"+type+";");if(getter!=null&&cacheScope(fluid,getter,type))changed++;}
        // Older merged inputs omitted the concrete Forge getter. Preserve its actual public lookup, without a cache.
        if(find(fluid,"getFluidType","()L"+FORGE_TYPE+";")==null&&fluid.interfaces.contains("net/minecraftforge/common/extensions/IForgeFluid")){
            MethodNode getter=new MethodNode(Opcodes.ACC_PUBLIC,"getFluidType","()L"+FORGE_TYPE+";",null,null);getter.instructions.add(new VarInsnNode(Opcodes.ALOAD,0));getter.instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC,FORGE_HOOKS,"getVanillaFluidType","(L"+FLUID_INTERNAL+";)L"+FORGE_TYPE+";",false));getter.instructions.add(new InsnNode(Opcodes.ARETURN));getter.maxLocals=1;getter.maxStack=1;fluid.methods.add(getter);changed++;
        }return changed;
    }
    /** Both families have the same public cache contract; private field names and exception text are irrelevant. */
    private static boolean cacheScope(ClassNode owner,MethodNode method,String type){
        if((method.access&(Opcodes.ACC_STATIC|Opcodes.ACC_ABSTRACT|Opcodes.ACC_NATIVE|Opcodes.ACC_SYNCHRONIZED))!=0||!method.tryCatchBlocks.isEmpty())return false;
        List<AbstractInsnNode> code=real(method);if(code.size()!=10)return false;
        if(!load(code.get(0),0)||!(code.get(1)instanceof FieldInsnNode first)||first.getOpcode()!=Opcodes.GETFIELD||!first.owner.equals(owner.name)||!first.desc.equals("L"+type+";")
            ||!(code.get(2)instanceof JumpInsnNode cached)||cached.getOpcode()!=Opcodes.IFNONNULL||!load(code.get(3),0)||!load(code.get(4),0)
            ||!(code.get(5)instanceof MethodInsnNode lookup)||lookup.getOpcode()!=Opcodes.INVOKESTATIC||!lookup.name.equals("getVanillaFluidType")||!lookup.owner.equals(type.equals(TYPE)?NEO_HOOKS:FORGE_HOOKS)||!lookup.desc.equals("(L"+FLUID_INTERNAL+";)L"+type+";")
            ||!(code.get(6)instanceof FieldInsnNode write)||write.getOpcode()!=Opcodes.PUTFIELD||!same(first,write)||!load(code.get(7),0)
            ||!(code.get(8)instanceof FieldInsnNode last)||last.getOpcode()!=Opcodes.GETFIELD||!same(first,last)||code.get(9).getOpcode()!=Opcodes.ARETURN||next(cached.label)!=code.get(7))return false;
        int flag=method.maxLocals,value=flag+1;LabelNode nativeCache=new LabelNode(),shared=new LabelNode(),nativeAnswer=new LabelNode(),answer=new LabelNode();
        // This closed getter has one native branch; replace its old frame with the explicit merge frames below.
        for(AbstractInsnNode instruction:method.instructions.toArray())if(instruction instanceof FrameNode)method.instructions.remove(instruction);
        cached.label=shared;
        InsnList initialize=new InsnList();initialize.add(new InsnNode(Opcodes.ICONST_0));initialize.add(new VarInsnNode(Opcodes.ISTORE,flag));initialize.add(new InsnNode(Opcodes.ACONST_NULL));initialize.add(new VarInsnNode(Opcodes.ASTORE,value));method.instructions.insert(initialize);
        InsnList before=new InsnList();before.add(new InsnNode(Opcodes.DUP));before.add(new LdcInsnNode(Type.getObjectType(type)));before.add(new MethodInsnNode(Opcodes.INVOKESTATIC,SCOPES,"nativeLookup","(Ljava/lang/Object;Ljava/lang/Class;)V",false));method.instructions.insertBefore(lookup,before);
        InsnList cache=new InsnList();cache.add(new VarInsnNode(Opcodes.ALOAD,0));cache.add(new LdcInsnNode(Type.getObjectType(type)));cache.add(new MethodInsnNode(Opcodes.INVOKESTATIC,SCOPES,"takeForeign","(Ljava/lang/Object;Ljava/lang/Class;)Z",false));cache.add(new JumpInsnNode(Opcodes.IFEQ,nativeCache));
        cache.add(new VarInsnNode(Opcodes.ASTORE,value));cache.add(new InsnNode(Opcodes.POP));cache.add(new InsnNode(Opcodes.ICONST_1));cache.add(new VarInsnNode(Opcodes.ISTORE,flag));cache.add(new JumpInsnNode(Opcodes.GOTO,shared));cache.add(nativeCache);
        Object[] locals={owner.name,Opcodes.INTEGER,type};cache.add(new FrameNode(Opcodes.F_NEW,3,locals,2,new Object[]{owner.name,type}));method.instructions.insertBefore(write,cache);
        // Every path, including the foreign fallback, reaches the same original return instruction.
        InsnList choose=new InsnList();choose.add(shared);choose.add(new FrameNode(Opcodes.F_NEW,3,locals,0,null));choose.add(new VarInsnNode(Opcodes.ILOAD,flag));choose.add(new JumpInsnNode(Opcodes.IFEQ,nativeAnswer));choose.add(new VarInsnNode(Opcodes.ALOAD,value));choose.add(new JumpInsnNode(Opcodes.GOTO,answer));choose.add(nativeAnswer);choose.add(new FrameNode(Opcodes.F_NEW,3,locals,0,null));method.instructions.insertBefore(code.get(7),choose);
        method.instructions.insertBefore(code.get(9),answer);method.instructions.insert(answer,new FrameNode(Opcodes.F_NEW,3,locals,1,new Object[]{type}));
        method.maxLocals=value+1;method.maxStack+=2;return true;
    }
    /** Preserve every builtin/milk branch and all its effects. Only the pure last unsupported throw gains a fallback. */
    static int unsupportedDefault(ClassNode owner,String type,String fallback){
        MethodNode method=find(owner,"getVanillaFluidType","(L"+FLUID_INTERNAL+";)L"+type+";");if(method==null||(method.access&(Opcodes.ACC_PUBLIC|Opcodes.ACC_STATIC))!=(Opcodes.ACC_PUBLIC|Opcodes.ACC_STATIC)||!method.tryCatchBlocks.isEmpty())return 0;
        List<AbstractInsnNode> code=real(method);if(code.stream().anyMatch(i->i instanceof MethodInsnNode call&&call.owner.equals(RUNTIME))||code.stream().noneMatch(i->i.getOpcode()==Opcodes.ARETURN)||code.size()<6)return 0;
        int at=code.size()-5;if(!(code.get(at)instanceof TypeInsnNode error)||error.getOpcode()!=Opcodes.NEW||!error.desc.equals("java/lang/RuntimeException")||code.get(at+1).getOpcode()!=Opcodes.DUP||!(code.get(at+2)instanceof LdcInsnNode message)||!(message.cst instanceof String)
            ||!(code.get(at+3)instanceof MethodInsnNode constructor)||constructor.getOpcode()!=Opcodes.INVOKESPECIAL||!constructor.owner.equals(error.desc)||!constructor.name.equals("<init>")||!constructor.desc.equals("(Ljava/lang/String;)V")||code.getLast().getOpcode()!=Opcodes.ATHROW)return 0;
        for(AbstractInsnNode instruction:method.instructions.toArray())if(instruction.getOpcode()==Opcodes.ARETURN){InsnList nativeResult=new InsnList();nativeResult.add(new VarInsnNode(Opcodes.ALOAD,0));nativeResult.add(new LdcInsnNode(Type.getObjectType(type)));nativeResult.add(new MethodInsnNode(Opcodes.INVOKESTATIC,SCOPES,"nativeLookup","(Ljava/lang/Object;Ljava/lang/Class;)V",false));method.instructions.insertBefore(instruction,nativeResult);}
        LabelNode foreign=new LabelNode();InsnList choose=new InsnList();choose.add(new VarInsnNode(Opcodes.ALOAD,0));choose.add(new JumpInsnNode(Opcodes.IFNONNULL,foreign));method.instructions.insertBefore(error,choose);
        method.instructions.add(foreign);method.instructions.add(new FrameNode(Opcodes.F_NEW,1,new Object[]{FLUID_INTERNAL},0,null));method.instructions.add(new VarInsnNode(Opcodes.ALOAD,0));method.instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC,RUNTIME,fallback,"(L"+FLUID_INTERNAL+";)L"+type+";",false));method.instructions.add(new InsnNode(Opcodes.ARETURN));return 1;
    }

    private static boolean load(AbstractInsnNode instruction,int slot){return instruction instanceof VarInsnNode variable&&variable.getOpcode()==Opcodes.ALOAD&&variable.var==slot;}
    private static boolean same(FieldInsnNode a,FieldInsnNode b){return a.owner.equals(b.owner)&&a.name.equals(b.name)&&a.desc.equals(b.desc);}
    private static AbstractInsnNode next(AbstractInsnNode instruction){while(instruction!=null&&instruction.getOpcode()<0)instruction=instruction.getNext();return instruction;}
    private static List<AbstractInsnNode> real(MethodNode method){return Arrays.stream(method.instructions.toArray()).filter(i->i.getOpcode()>=0).toList();}
    private static MethodNode find(ClassNode owner,String name,String desc){return owner.methods.stream().filter(m->m.name.equals(name)&&m.desc.equals(desc)).findFirst().orElse(null);}
}
