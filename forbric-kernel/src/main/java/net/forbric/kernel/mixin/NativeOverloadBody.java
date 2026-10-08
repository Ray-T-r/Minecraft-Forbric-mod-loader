/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.mixin;

import java.util.*;
import net.forbric.api.Ecosystem;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import org.objectweb.asm.tree.analysis.*;

/** A native widened body may add one context-dependent early exit while retaining the complete source body. */
final class NativeOverloadBody implements Opcodes {
    private NativeOverloadBody() { }
    static MethodNode destination(ClassNode target,ClassNode source,MethodNode original,MethodNode selected) {
        if(source==null||original==null||selected==null||!Type.getReturnType(original.desc).equals(Type.VOID_TYPE)
                ||!original.tryCatchBlocks.isEmpty()||incoming(target,selected)!=0)return null;
        List<Map.Entry<MethodNode,MethodInsnNode>> nativeCalls=callers(source,original);if(nativeCalls.size()!=1)return null;
        MethodNode caller=nativeCalls.getFirst().getKey(),currentCaller=NativeCallChanges.method(target,caller.name+caller.desc);if(currentCaller==null)return null;
        var input=NativeCallChanges.sites(source,caller).stream().filter(site->site.call()==nativeCalls.getFirst().getValue()).findFirst().orElse(null);if(input==null)return null;
        List<MethodNode> matches=new ArrayList<>();
        for(MethodNode candidate:target.methods) {
            if(candidate==selected||!candidate.name.equals(original.name)||!candidate.tryCatchBlocks.isEmpty()
                    ||((candidate.access^original.access)&ACC_STATIC)!=0||!prefix(original.desc,candidate.desc))continue;
            List<NativeCallChanges.Site> reached=NativeCallChanges.sites(target,currentCaller).stream().filter(site->site.call().owner.equals(target.name)
                    &&site.call().name.equals(candidate.name)&&site.call().desc.equals(candidate.desc)).toList();
            if(reached.size()!=1||incoming(target,candidate)!=1||reached.getFirst().operands().size()<=input.operands().size()
                    ||!input.operands().equals(reached.getFirst().operands().subList(0,input.operands().size())))continue;
            boolean nativeBody=false;
            for(Ecosystem family:Ecosystem.values()) {ClassNode nativeOwner=MergedBaseCalleeSwaps.source(family,target.name);
                MethodNode nativeCandidate=NativeCallChanges.method(nativeOwner,candidate.name+candidate.desc),nativeCaller=NativeCallChanges.method(nativeOwner,currentCaller.name+currentCaller.desc);
                if(nativeCandidate!=null&&nativeCaller!=null&&MixinInstructionFingerprint.hash(nativeCandidate).equals(MixinInstructionFingerprint.hash(candidate))
                        &&MixinInstructionFingerprint.hash(nativeCaller).equals(MixinInstructionFingerprint.hash(currentCaller))){nativeBody=true;break;}}
            if(nativeBody&&retainsBody(target.name,original,candidate))matches.add(candidate);
        }
        return matches.size()==1?matches.getFirst():null;
    }
    private static boolean prefix(String source,String current) {
        if(!Type.getReturnType(source).equals(Type.getReturnType(current)))return false;
        Type[] old=Type.getArgumentTypes(source),now=Type.getArgumentTypes(current);return now.length>old.length&&Arrays.equals(old,Arrays.copyOf(now,old.length));
    }
    private static boolean retainsBody(String owner,MethodNode original,MethodNode current) {
        MethodNode mapped=copy(original);mapped.desc=current.desc;
        int oldParameters=slots(original),additional=slots(current)-oldParameters;
        for(AbstractInsnNode instruction:mapped.instructions) {
            if(instruction instanceof VarInsnNode variable&&variable.var>=oldParameters)variable.var+=additional;
            if(instruction instanceof IincInsnNode step&&step.var>=oldParameters)step.var+=additional;
        }
        List<AbstractInsnNode> nativeCode=code(mapped),liveCode=code(current);int gap=liveCode.size()-nativeCode.size();if(gap<3||gap>64||liveCode.size()>512)return false;
        String expected=MixinInstructionFingerprint.hash(mapped);int proofs=0;
        for(int start=0;start+gap<liveCode.size();start++) {
            if(!gate(owner,current,liveCode,start,start+gap,oldParameters,oldParameters+additional))continue;
            MethodNode reduced=copy(current);List<AbstractInsnNode> instructions=code(reduced);for(int i=start;i<start+gap;i++)reduced.instructions.remove(instructions.get(i));
            if(expected.equals(MixinInstructionFingerprint.hash(reduced)))proofs++;
        }
        return proofs==1;
    }
    private static boolean gate(String owner,MethodNode method,List<AbstractInsnNode> instructions,int start,int end,int addedStart,int addedEnd) {
        try {
            var frames=new Analyzer<>(new BasicInterpreter()).analyze(owner,method);
            int first=method.instructions.indexOf(instructions.get(start)),after=method.instructions.indexOf(instructions.get(end));
            if(frames[first]==null||frames[after]==null||frames[first].getStackSize()!=0||frames[after].getStackSize()!=0)return false;
            Set<Integer> sourceLocals=new HashSet<>();for(int i=0;i<instructions.size();i++)if(i<start||i>=end){if(instructions.get(i)instanceof VarInsnNode local)sourceLocals.add(local.var);if(instructions.get(i)instanceof IincInsnNode local)sourceLocals.add(local.var);}
            boolean context=false,exit=false,conditional=false,call=false;
            for(int i=0;i<instructions.size();i++) {
                AbstractInsnNode instruction=instructions.get(i);boolean inside=i>=start&&i<end;
                if(instruction instanceof JumpInsnNode branch){int destination=position(instructions,branch.label);
                    if(!inside&&destination>=start&&destination<end)return false;
                    if(inside){conditional|=branch.getOpcode()!=GOTO;if(destination>=start&&destination<=end)continue;
                        if(destination<0||destination>=instructions.size()||instructions.get(destination).getOpcode()!=RETURN)return false;exit=true;}
                }
                if(!inside)continue;
                if(instruction instanceof VarInsnNode local){context|=local.var>=addedStart&&local.var<addedEnd&&local.getOpcode()>=ILOAD&&local.getOpcode()<=ALOAD;
                    if(local.getOpcode()>=ISTORE&&local.getOpcode()<=ASTORE&&sourceLocals.contains(local.var))return false;}
                if(instruction instanceof IincInsnNode local&&sourceLocals.contains(local.var))return false;
                if(instruction instanceof MethodInsnNode)call=true;
                if(instruction instanceof LookupSwitchInsnNode||instruction instanceof TableSwitchInsnNode||instruction.getOpcode()==ATHROW
                        ||instruction.getOpcode()==MONITORENTER||instruction.getOpcode()==MONITOREXIT||instruction.getOpcode()>=IRETURN&&instruction.getOpcode()<=RETURN)return false;
            }
            return context&&exit&&conditional&&call;
        }catch(AnalyzerException|RuntimeException invalid){return false;}
    }
    private static int position(List<AbstractInsnNode> code,LabelNode label){AbstractInsnNode real=label;while(real!=null&&real.getOpcode()<0)real=real.getNext();return code.indexOf(real);}
    private static int slots(MethodNode method){return ((method.access&ACC_STATIC)==0?1:0)+Arrays.stream(Type.getArgumentTypes(method.desc)).mapToInt(Type::getSize).sum();}
    private static List<AbstractInsnNode> code(MethodNode method){return Arrays.stream(method.instructions.toArray()).filter(instruction->instruction.getOpcode()>=0).toList();}
    private static int incoming(ClassNode owner,MethodNode wanted){int count=callers(owner,wanted).size();for(MethodNode method:owner.methods)for(AbstractInsnNode instruction:method.instructions)
        if(instruction instanceof InvokeDynamicInsnNode dynamic)for(Object value:dynamic.bsmArgs)if(value instanceof Handle handle&&handle.getOwner().equals(owner.name)&&handle.getName().equals(wanted.name)&&handle.getDesc().equals(wanted.desc))count++;return count;}
    private static List<Map.Entry<MethodNode,MethodInsnNode>> callers(ClassNode owner,MethodNode wanted){List<Map.Entry<MethodNode,MethodInsnNode>> calls=new ArrayList<>();for(MethodNode method:owner.methods)for(AbstractInsnNode instruction:method.instructions){
        if(instruction instanceof MethodInsnNode call&&call.owner.equals(owner.name)&&call.name.equals(wanted.name)&&call.desc.equals(wanted.desc))calls.add(Map.entry(method,call));
    }return calls;}
    private static MethodNode copy(MethodNode source){MethodNode result=new MethodNode(source.access,source.name,source.desc,null,null);source.accept(result);return result;}
}
