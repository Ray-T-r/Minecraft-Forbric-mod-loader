/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.tools;

import java.util.*;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;

/** Reverses only an actual interface default's parameter loads + empty trailing-array delegate.
 * The old implementation proves the empty protocol. Any other array is explicitly unsupported. */
final class EmptyArrayContractBridge {
    static int repair(ClassNode target,ContractGraph graph){
        if((target.access&(Opcodes.ACC_INTERFACE|Opcodes.ACC_ABSTRACT))!=0)return 0;int added=0;
        for(MethodNode required:graph.interfaceContracts(target).values()){
            if((required.access&Opcodes.ACC_ABSTRACT)==0||graph.implementation(target.name,required.name,required.desc)!=null)continue;
            if(ContractGraph.ownMethod(target,required.name,required.desc)!=null)
                throw new IllegalStateException("Incompatible existing method occupies interface slot "+target.name+"#"+required.name+required.desc+": no public instance implementation, and a duplicate-signature bridge cannot preserve the old method");
            Type[] extended=Type.getArgumentTypes(required.desc);if(extended.length==0||extended[extended.length-1].getSort()!=Type.ARRAY)continue;
            MethodNode old=witness(target,required,graph);if(old==null)continue;
            Type[] args=Type.getArgumentTypes(old.desc);MethodNode bridge=new MethodNode(Opcodes.ACC_PUBLIC|Opcodes.ACC_SYNTHETIC,required.name,required.desc,required.signature,null);
            int array=1;for(Type arg:args)array+=arg.getSize();LabelNode unsupported=new LabelNode();
            bridge.instructions.add(new VarInsnNode(Opcodes.ALOAD,array));bridge.instructions.add(new JumpInsnNode(Opcodes.IFNULL,unsupported));
            bridge.instructions.add(new VarInsnNode(Opcodes.ALOAD,array));bridge.instructions.add(new InsnNode(Opcodes.ARRAYLENGTH));bridge.instructions.add(new JumpInsnNode(Opcodes.IFNE,unsupported));
            bridge.instructions.add(new VarInsnNode(Opcodes.ALOAD,0));int slot=1;for(Type arg:args){bridge.instructions.add(new VarInsnNode(arg.getOpcode(Opcodes.ILOAD),slot));slot+=arg.getSize();}
            bridge.instructions.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL,target.name,old.name,old.desc,false));bridge.instructions.add(new InsnNode(Type.getReturnType(old.desc).getOpcode(Opcodes.IRETURN)));
            bridge.instructions.add(unsupported);bridge.instructions.add(new FrameNode(Opcodes.F_SAME,0,null,0,null));
            bridge.instructions.add(new TypeInsnNode(Opcodes.NEW,"java/lang/UnsupportedOperationException"));bridge.instructions.add(new InsnNode(Opcodes.DUP));
            bridge.instructions.add(new LdcInsnNode("Uncomposed interface protocol "+target.name+"#"+required.name+required.desc+": native default proves only an empty trailing array; nonempty/null input cannot be preserved"));
            bridge.instructions.add(new MethodInsnNode(Opcodes.INVOKESPECIAL,"java/lang/UnsupportedOperationException","<init>","(Ljava/lang/String;)V",false));bridge.instructions.add(new InsnNode(Opcodes.ATHROW));
            bridge.maxLocals=array+1;bridge.maxStack=Math.max(3,array);target.methods.add(bridge);graph.replace(target);added++;
            System.out.println("[interop-partial-protocol] "+target.name+"#"+required.name+required.desc+": proved empty-array delegate to "+old.name+old.desc+"; other input explicitly rejected");
        }return added;
    }
    private static MethodNode witness(ClassNode target,MethodNode required,ContractGraph graph){
        Type[] full=Type.getArgumentTypes(required.desc);Type[] args=Arrays.copyOf(full,full.length-1);String oldDesc=Type.getMethodDescriptor(Type.getReturnType(required.desc),args);
        Set<String> seen=new HashSet<>();Deque<String> pending=new ArrayDeque<>();ClassNode cursor=target;
        while(cursor!=null){pending.addAll(cursor.interfaces);cursor=graph.node(cursor.superName);}
        List<MethodNode> candidates=new ArrayList<>();
        while(!pending.isEmpty()){String owner=pending.remove();if(!seen.add(owner))continue;ClassNode itf=graph.node(owner);if(itf==null)continue;pending.addAll(itf.interfaces);
            for(MethodNode method:itf.methods){if(!method.desc.equals(oldDesc)||(method.access&(Opcodes.ACC_STATIC|Opcodes.ACC_ABSTRACT|Opcodes.ACC_PRIVATE))!=0||!method.tryCatchBlocks.isEmpty())continue;
                List<AbstractInsnNode> code=ContractGraph.code(method);if(code.size()!=args.length+5||!(code.get(0) instanceof VarInsnNode self)||self.getOpcode()!=Opcodes.ALOAD||self.var!=0)continue;
                int slot=1;boolean loads=true;for(int i=0;i<args.length;i++){if(!(code.get(i+1) instanceof VarInsnNode arg)||arg.getOpcode()!=args[i].getOpcode(Opcodes.ILOAD)||arg.var!=slot){loads=false;break;}slot+=args[i].getSize();}if(!loads)continue;
                int at=args.length+1;if(code.get(at).getOpcode()!=Opcodes.ICONST_0||!(code.get(at+1) instanceof TypeInsnNode array)||array.getOpcode()!=Opcodes.ANEWARRAY||!array.desc.equals(Type.getType(full[full.length-1].getDescriptor().substring(1)).getInternalName()))continue;
                if(!(code.get(at+2) instanceof MethodInsnNode call)||call.getOpcode()!=Opcodes.INVOKEINTERFACE||!call.name.equals(required.name)||!call.desc.equals(required.desc)||!graph.assignable("L"+owner+";","L"+call.owner+";")||code.get(at+3).getOpcode()!=Type.getReturnType(required.desc).getOpcode(Opcodes.IRETURN))continue;
                MethodNode implementation=classMethod(target,method.name,method.desc,graph);if(implementation!=null)candidates.add(implementation);
            }
        }
        Map<String,MethodNode> unique=new LinkedHashMap<>();for(MethodNode candidate:candidates)unique.put(candidate.name+candidate.desc,candidate);return unique.size()==1?unique.values().iterator().next():null;
    }
    private static MethodNode classMethod(ClassNode target,String name,String desc,ContractGraph graph){
        Set<String> seen=new HashSet<>();while(target!=null&&seen.add(target.name)){MethodNode method=ContractGraph.ownMethod(target,name,desc);if(method!=null)return (method.access&(Opcodes.ACC_PUBLIC|Opcodes.ACC_STATIC|Opcodes.ACC_ABSTRACT))==Opcodes.ACC_PUBLIC?method:null;target=graph.node(target.superName);}return null;
    }
}
