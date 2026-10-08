/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.tools;

import java.util.*;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;

/** Restores a native append-only void hook while keeping the other side's complete body and its own tail hook. */
final class TailHookComposition {
    static MethodNode merge(ClassNode owner,MethodNode vanilla,MethodNode base,MethodNode other,String basePkg,String otherPkg,ContractGraph graph){
        if(vanilla==null||!Type.getReturnType(base.desc).equals(Type.VOID_TYPE)||base.name.startsWith("<")||!base.desc.equals(other.desc)
                ||(base.access&Opcodes.ACC_STATIC)!=(other.access&Opcodes.ACC_STATIC)||returns(vanilla)!=1||returns(base)!=1||returns(other)!=1)return null;
        Suffix suffix=suffix(owner,other,otherPkg,graph);if(suffix==null)return null;
        MethodNode stripped=copy(other);List<AbstractInsnNode> strippedCode=ContractGraph.code(stripped);
        for(int i=suffix.start;i<strippedCode.size()-1;i++)stripped.instructions.remove(strippedCode.get(i));
        if(!canonical(stripped).equals(canonical(vanilla)))return null;
        MethodNode definition=graph.method(suffix.call.owner,suffix.call.name,suffix.call.desc);if(definition==null||(definition.access&Opcodes.ACC_STATIC)==0)return null;
        if(Arrays.stream(base.instructions.toArray()).anyMatch(i->i instanceof MethodInsnNode call&&call.owner.equals(suffix.call.owner)&&call.name.equals(suffix.call.name)&&call.desc.equals(suffix.call.desc)))return null;
        MethodNode result=copy(base);List<AbstractInsnNode> destination=ContractGraph.code(result);Suffix own=suffix(owner,result,basePkg,graph);
        AbstractInsnNode anchor=own==null?destination.get(destination.size()-1):destination.get(own.start);
        InsnList addition=new InsnList();List<AbstractInsnNode> source=ContractGraph.code(other);
        for(int i=suffix.start;i<source.size()-1;i++)addition.add(source.get(i).clone(new HashMap<>()));
        result.instructions.insertBefore(anchor,addition);return result;
    }
    private record Suffix(int start,MethodInsnNode call){}
    private static Suffix suffix(ClassNode owner,MethodNode method,String pkg,ContractGraph graph){
        List<AbstractInsnNode> c=ContractGraph.code(method);if(c.size()<2||c.get(c.size()-1).getOpcode()!=Opcodes.RETURN
                ||!(c.get(c.size()-2) instanceof MethodInsnNode call)||call.getOpcode()!=Opcodes.INVOKESTATIC||!call.owner.startsWith(pkg)||!Type.getReturnType(call.desc).equals(Type.VOID_TYPE))return null;
        Type[] args=Type.getArgumentTypes(method.desc);Map<Integer,Type> slots=new HashMap<>();int slot=(method.access&Opcodes.ACC_STATIC)==0?1:0;
        if(slot==1)slots.put(0,Type.getObjectType(owner.name));for(Type argument:args){slots.put(slot,argument);slot+=argument.getSize();}
        int cursor=c.size()-3;Type[] inputs=Type.getArgumentTypes(call.desc);
        for(int argument=inputs.length-1;argument>=0;argument--){
            Type expected=inputs[argument];while(cursor>=0&&c.get(cursor) instanceof FieldInsnNode field&&field.getOpcode()==Opcodes.GETFIELD){
                FieldNode declaration=graph.field(field.owner,field.name,field.desc);if(declaration==null||(declaration.access&Opcodes.ACC_STATIC)!=0||!graph.assignable(Type.getType(field.desc),expected))return null;
                expected=Type.getObjectType(field.owner);cursor--;
            }
            if(cursor<0||!(c.get(cursor) instanceof VarInsnNode load)||!slots.containsKey(load.var)||load.getOpcode()!=slots.get(load.var).getOpcode(Opcodes.ILOAD)
                    ||!graph.assignable(slots.get(load.var),expected))return null;cursor--;
        }
        int start=cursor+1;
        for(AbstractInsnNode instruction:method.instructions){if(instruction instanceof JumpInsnNode jump&&position(c,jump.label)>start)return null;
            if(instruction instanceof TableSwitchInsnNode table&&(position(c,table.dflt)>start||table.labels.stream().anyMatch(label->position(c,label)>start)))return null;
            if(instruction instanceof LookupSwitchInsnNode lookup&&(position(c,lookup.dflt)>start||lookup.labels.stream().anyMatch(label->position(c,label)>start)))return null;}
        for(TryCatchBlockNode handler:method.tryCatchBlocks)if(position(c,handler.handler)>=start||position(c,handler.end)>start)return null;
        return new Suffix(start,call);
    }
    private static int returns(MethodNode method){return (int)Arrays.stream(method.instructions.toArray()).filter(i->i.getOpcode()==Opcodes.RETURN).count();}
    private static MethodNode copy(MethodNode method){MethodNode copy=new MethodNode(method.access,method.name,method.desc,method.signature,method.exceptions.toArray(String[]::new));method.accept(copy);return copy;}
    private static List<String> canonical(MethodNode method){
        List<AbstractInsnNode> code=ContractGraph.code(method);List<String> result=new ArrayList<>();
        for(AbstractInsnNode i:code){
            if(i instanceof JumpInsnNode jump)result.add(i.getOpcode()+" jump "+position(code,jump.label));
            else if(i instanceof TableSwitchInsnNode table)result.add(i.getOpcode()+" table "+table.min+" "+table.max+" "+position(code,table.dflt)+" "+table.labels.stream().map(l->position(code,l)).toList());
            else if(i instanceof LookupSwitchInsnNode lookup)result.add(i.getOpcode()+" switch "+lookup.keys+" "+position(code,lookup.dflt)+" "+lookup.labels.stream().map(l->position(code,l)).toList());
            else result.add(FieldContractReconciler.token(i));
        }
        for(TryCatchBlockNode handler:method.tryCatchBlocks)result.add("handler "+position(code,handler.start)+" "+position(code,handler.end)+" "+position(code,handler.handler)+" "+handler.type);
        return result;
    }
    private static int position(List<AbstractInsnNode> code,AbstractInsnNode label){while(label!=null&&label.getOpcode()<0)label=label.getNext();return label==null?code.size():code.indexOf(label);}
}
