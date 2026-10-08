/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.tools;

import java.util.*;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;

/** A restricted successful-return proof: allocations, initialized final statics, and closed native calls.
 * An opaque argument/field or virtual receiver without one exact allocating producer is never assumed non-null. */
final class NonNullReturnProof {
    static boolean method(ContractGraph graph,String owner,MethodNode method){return method(graph,owner,method,new HashSet<>());}
    private static boolean method(ContractGraph graph,String owner,MethodNode method,Set<String> visiting){
        if(method==null||!visiting.add(owner+"#"+method.name+method.desc))return false;List<AbstractInsnNode> code=ContractGraph.code(method);boolean returns=false;
        for(int i=0;i<code.size();i++)if(code.get(i).getOpcode()==Opcodes.ARETURN){returns=true;if(!value(graph,owner,method,code,i-1,new HashSet<>(visiting)))return false;}
        return returns;
    }
    private static boolean value(ContractGraph graph,String owner,MethodNode method,List<AbstractInsnNode> code,int index,Set<String> visiting){
        if(index<0)return false;AbstractInsnNode i=code.get(index);
        if(i instanceof MethodInsnNode call){
            if(call.name.equals("<init>")&&call.getOpcode()==Opcodes.INVOKESPECIAL)return allocated(code,index,call.owner);
            if(call.getOpcode()!=Opcodes.INVOKESTATIC&&!exactReceiver(graph,method,code,index,call.owner,visiting))return false;
            return method(graph,call.owner,graph.method(call.owner,call.name,call.desc),visiting);
        }
        if(i instanceof FieldInsnNode field&&field.getOpcode()==Opcodes.GETSTATIC){
            FieldNode f=graph.field(field.owner,field.name,field.desc);ClassNode klass=graph.node(field.owner);
            if(f==null||(f.access&Opcodes.ACC_FINAL)==0||klass==null)return false;MethodNode init=ContractGraph.ownMethod(klass,"<clinit>","()V");if(init==null)return false;
            List<AbstractInsnNode> body=ContractGraph.code(init);
            if(!init.tryCatchBlocks.isEmpty()||body.stream().anyMatch(instruction->instruction instanceof JumpInsnNode||instruction instanceof TableSwitchInsnNode||instruction instanceof LookupSwitchInsnNode))return false;
            boolean wrote=false;
            for(int j=0;j<body.size();j++)if(body.get(j) instanceof FieldInsnNode write&&write.getOpcode()==Opcodes.PUTSTATIC&&write.owner.equals(field.owner)&&write.name.equals(field.name)&&write.desc.equals(field.desc)){
                wrote=true;if(!value(graph,field.owner,init,body,j-1,new HashSet<>(visiting)))return false;
            }return wrote;
        }
        if(i instanceof VarInsnNode load&&load.getOpcode()==Opcodes.ALOAD){
            Set<Integer> writers=reachingStores(method,code,index,load.var);if(writers==null||writers.isEmpty())return false;for(int j:writers)if(!value(graph,owner,method,code,j-1,new HashSet<>(visiting)))return false;return true;
        }
        return i.getOpcode()==Opcodes.NEW||i.getOpcode()==Opcodes.ANEWARRAY||i.getOpcode()==Opcodes.NEWARRAY||i.getOpcode()==Opcodes.MULTIANEWARRAY;
    }
    private static boolean exactReceiver(ContractGraph graph,MethodNode method,List<AbstractInsnNode> code,int call,String owner,Set<String> visiting){
        if(Type.getArgumentTypes(((MethodInsnNode)code.get(call)).desc).length!=0||call==0||!(code.get(call-1) instanceof VarInsnNode load)||load.getOpcode()!=Opcodes.ALOAD)return false;
        Set<Integer> writers=reachingStores(method,code,call-1,load.var);if(writers==null||writers.isEmpty())return false;
        for(int i:writers){if(i==0||!(code.get(i-1) instanceof MethodInsnNode factory)||factory.getOpcode()!=Opcodes.INVOKESTATIC)return false;
            MethodNode producer=graph.method(factory.owner,factory.name,factory.desc);if(producer==null)return false;List<AbstractInsnNode> p=ContractGraph.code(producer);
            boolean produced=false;for(int j=0;j<p.size();j++)if(p.get(j).getOpcode()==Opcodes.ARETURN){produced=true;if(j==0||!(p.get(j-1) instanceof MethodInsnNode constructor)||!constructor.name.equals("<init>")||!constructor.owner.equals(owner)||!allocated(p,j-1,owner))return false;}
            if(!produced)return false;
        }
        return true;
    }
    private static Set<Integer> reachingStores(MethodNode method,List<AbstractInsnNode> code,int use,int slot){
        List<Set<Integer>> incoming=new ArrayList<>();for(int i=0;i<code.size();i++)incoming.add(new HashSet<>());
        for(int i=0;i<code.size();i++){AbstractInsnNode instruction=code.get(i);
            if(instruction instanceof JumpInsnNode jump){edge(incoming,i,position(code,jump.label));if(jump.getOpcode()!=Opcodes.GOTO&&jump.getOpcode()!=Opcodes.JSR)edge(incoming,i,i+1);}
            else if(instruction instanceof TableSwitchInsnNode table){edge(incoming,i,position(code,table.dflt));for(LabelNode label:table.labels)edge(incoming,i,position(code,label));}
            else if(instruction instanceof LookupSwitchInsnNode lookup){edge(incoming,i,position(code,lookup.dflt));for(LabelNode label:lookup.labels)edge(incoming,i,position(code,label));}
            else if(instruction.getOpcode()!=Opcodes.ATHROW&&!(instruction.getOpcode()>=Opcodes.IRETURN&&instruction.getOpcode()<=Opcodes.RETURN))edge(incoming,i,i+1);
        }
        for(TryCatchBlockNode handler:method.tryCatchBlocks){int start=position(code,handler.start),end=position(code,handler.end),target=position(code,handler.handler);for(int i=start;i<end;i++)edge(incoming,i,target);}
        Set<Integer> result=new HashSet<>(),seen=new HashSet<>();Deque<Integer> pending=new ArrayDeque<>(incoming.get(use));
        while(!pending.isEmpty()){int at=pending.remove();if(!seen.add(at))continue;AbstractInsnNode instruction=code.get(at);
            if(instruction instanceof VarInsnNode store&&store.getOpcode()==Opcodes.ASTORE&&store.var==slot){result.add(at);continue;}
            if(at==0)return null;pending.addAll(incoming.get(at));
        }return result;
    }
    private static void edge(List<Set<Integer>> incoming,int from,int to){if(to>=0&&to<incoming.size())incoming.get(to).add(from);}
    private static int position(List<AbstractInsnNode> code,AbstractInsnNode label){while(label!=null&&label.getOpcode()<0)label=label.getNext();return label==null?code.size():code.indexOf(label);}
    private static boolean allocated(List<AbstractInsnNode> code,int constructor,String type){
        for(int i=constructor-1;i>=0;i--){AbstractInsnNode instruction=code.get(i);
            if(instruction instanceof JumpInsnNode||instruction.getOpcode()==Opcodes.ASTORE||instruction.getOpcode()==Opcodes.ARETURN)return false;
            if(instruction instanceof TypeInsnNode created&&created.getOpcode()==Opcodes.NEW)return created.desc.equals(type)&&i+1<code.size()&&code.get(i+1).getOpcode()==Opcodes.DUP;
        }return false;
    }
}
