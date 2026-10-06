/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.mixin;

import java.util.*;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.*;
import org.objectweb.asm.tree.analysis.*;

/** An ordinal follows its unique value sink in the indexed original method, never a guessed call order. */
final class InvocationOrdinals {
 private InvocationOrdinals() { }
 static Integer correspondence(String mixin,ClassNode target,MethodNode current,
   MixinAtWidenedCall.Member original,String widened,int ordinal){
  ClassNode nativeType=NativeGameReferences.reference(MixinStubRebind.ecosystemOf(mixin),target.name);
  if(nativeType==null)return null;
  MethodNode nativeMethod=nativeType.methods.stream().filter(m->m.name.equals(current.name)&&m.desc.equals(current.desc)).findFirst().orElse(null);
  if(nativeMethod==null)return null;
  return correspondence(nativeType.name,nativeMethod,current,original,widened,ordinal);
 }
 static Integer correspondence(String owner,MethodNode nativeMethod,MethodNode current,
   MixinAtWidenedCall.Member original,String widened,int ordinal){
  List<MethodInsnNode> before=calls(nativeMethod,original,original.descriptor()),after=calls(current,original,widened);
  if(ordinal<0||ordinal>=before.size()||before.size()!=after.size())return null;
  Map<MethodInsnNode,String> originalSinks=sinks(owner,nativeMethod,before),currentSinks=sinks(owner,current,after);
  if(originalSinks.size()!=before.size()||currentSinks.size()!=after.size())return null;
  if(new HashSet<>(originalSinks.values()).size()!=before.size()||new HashSet<>(currentSinks.values()).size()!=after.size()
    ||!new HashSet<>(originalSinks.values()).equals(new HashSet<>(currentSinks.values())))return null;
  String wanted=originalSinks.get(before.get(ordinal));
  for(int i=0;i<after.size();i++)if(wanted.equals(currentSinks.get(after.get(i))))return i;
  return null;
 }
 private static List<MethodInsnNode> calls(MethodNode method,MixinAtWidenedCall.Member member,String desc){
  List<MethodInsnNode> result=new ArrayList<>();
  for(var op:method.instructions)if(op instanceof MethodInsnNode call&&call.owner.equals(member.owner())
    &&call.name.equals(member.name())&&call.desc.equals(desc))result.add(call);
  return result;
 }
 private static Map<MethodInsnNode,String> sinks(String owner,MethodNode method,List<MethodInsnNode> candidates){
  Map<MethodInsnNode,String> result=new IdentityHashMap<>();Set<MethodInsnNode> invalid=Collections.newSetFromMap(new IdentityHashMap<>());
  Frame<SourceValue>[] frames;
  try{frames=new Analyzer<>(new Dependencies()).analyze(owner,method);}catch(AnalyzerException|RuntimeException bad){return Map.of();}
  for(var op:method.instructions){
   if(!(op instanceof FieldInsnNode field)||field.getOpcode()!=Opcodes.PUTSTATIC)continue;
   Frame<SourceValue> frame=frames[method.instructions.indexOf(op)];if(frame==null||frame.getStackSize()==0)continue;
   SourceValue value=frame.getStack(frame.getStackSize()-1);
   List<MethodInsnNode> producers=candidates.stream().filter(value.insns::contains).toList();
   if(producers.size()!=1){invalid.addAll(producers);continue;}
   MethodInsnNode producer=producers.getFirst();String sink=field.owner+"#"+field.name+":"+field.desc;
   if(result.putIfAbsent(producer,sink)!=null)invalid.add(producer);
  }
  invalid.forEach(result::remove);return result;
 }
 private static final class Dependencies extends SourceInterpreter {
  Dependencies(){super(Opcodes.ASM9);}
  @Override public SourceValue copyOperation(AbstractInsnNode op,SourceValue value){return value;}
  @Override public SourceValue unaryOperation(AbstractInsnNode op,SourceValue value){
   SourceValue produced=super.unaryOperation(op,value);return produced==null?null:combine(produced,List.of(value));
  }
  @Override public SourceValue binaryOperation(AbstractInsnNode op,SourceValue a,SourceValue b){
   SourceValue produced=super.binaryOperation(op,a,b);return produced==null?null:combine(produced,List.of(a,b));
  }
  @Override public SourceValue naryOperation(AbstractInsnNode op,List<? extends SourceValue> values){
   SourceValue produced=super.naryOperation(op,values);return produced==null?null:combine(produced,values);
  }
  private SourceValue combine(SourceValue result,List<? extends SourceValue> values){
   Set<AbstractInsnNode> sources=new HashSet<>(result.insns);for(SourceValue v:values)sources.addAll(v.insns);
   return new SourceValue(result.size,sources);
  }
 }
}
