/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.transform;

import java.util.*;
import java.util.function.Function;
import net.forbric.api.Ecosystem;
import net.forbric.kernel.mixin.MixinInstructionFingerprint;
import net.forbric.kernel.mixin.NativeGameReferences;
import net.forbric.kernel.util.ForbricLog;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;

/** A constructor closure may recover its original argument scope only when the expanded body is the indexed original exactly. */
public final class NativeConstructorFacadeRestorer implements ClassTransformer {
 public static final String PROPERTY="forbric.nativeConstructorFacades";
 private final NativeGameReferences references;
 public NativeConstructorFacadeRestorer(Function<String,byte[]> resources){references=new NativeGameReferences(resources);}
 @Override public AnchorSet anchors(){return AnchorSet.scanned("constructor closures whose expanded executable body equals an indexed native method");}
 @Override public byte[] transform(String name,byte[] bytes,TransformContext context){
  if(bytes==null||"off".equalsIgnoreCase(System.getProperty(PROPERTY,"on")))return bytes;
  ClassNode current=new ClassNode();new ClassReader(bytes).accept(current,0);int changed=restore(current,eco->references.get(eco,current.name));
  if(changed==0)return bytes;ClassWriter writer=new ClassWriter(0);current.accept(writer);return writer.toByteArray();
 }
 public static int restore(ClassNode owner,Function<Ecosystem,ClassNode> originals){
  int changed=0;Map<Ecosystem,ClassNode> cache=new EnumMap<>(Ecosystem.class);
  for(MethodNode wrapper:owner.methods){MethodNode expanded=expand(owner,wrapper);if(expanded==null)continue;
   String expected=MixinInstructionFingerprint.hash(expanded);MethodNode matching=null;
   for(Ecosystem eco:Ecosystem.values()){
    ClassNode original=cache.computeIfAbsent(eco,originals);if(original==null)continue;
    for(MethodNode method:original.methods)if(method.name.equals(wrapper.name)&&method.desc.equals(wrapper.desc)
      &&(method.access&Opcodes.ACC_STATIC)==(wrapper.access&Opcodes.ACC_STATIC)
      &&expected.equals(MixinInstructionFingerprint.hash(method))){matching=method;break;}
    if(matching!=null)break;
   }
   if(matching==null)continue;
   MethodNode body=new MethodNode(matching.access,matching.name,matching.desc,null,null);matching.accept(body);
   wrapper.instructions=body.instructions;wrapper.tryCatchBlocks=body.tryCatchBlocks;wrapper.localVariables=body.localVariables;
   wrapper.visibleLocalVariableAnnotations=body.visibleLocalVariableAnnotations;wrapper.invisibleLocalVariableAnnotations=body.invisibleLocalVariableAnnotations;
   wrapper.maxLocals=body.maxLocals;wrapper.maxStack=body.maxStack;changed++;
   ForbricLog.info("[Forbric/Mixin] %s.%s%s recovers its constructor argument scope; expansion equals the indexed native executable body",
     owner.name.replace('/','.'),wrapper.name,wrapper.desc);
  }return changed;
 }
 private static MethodNode expand(ClassNode owner,MethodNode wrapper){
  if((wrapper.access&(Opcodes.ACC_STATIC|Opcodes.ACC_ABSTRACT|Opcodes.ACC_NATIVE|Opcodes.ACC_SYNCHRONIZED))!=Opcodes.ACC_STATIC
    ||!wrapper.tryCatchBlocks.isEmpty())return null;
  List<AbstractInsnNode> w=real(wrapper);if(w.size()<5||w.size()>32
    ||!(w.getFirst() instanceof TypeInsnNode created)||created.getOpcode()!=Opcodes.NEW||w.get(1).getOpcode()!=Opcodes.DUP
    ||!(w.get(w.size()-2) instanceof MethodInsnNode edge)||edge.getOpcode()!=Opcodes.INVOKESTATIC||!edge.owner.equals(owner.name)
    ||w.getLast().getOpcode()!=Type.getReturnType(wrapper.desc).getOpcode(Opcodes.IRETURN))return null;
  Type[] parameters=Type.getArgumentTypes(wrapper.desc),args=Type.getArgumentTypes(edge.desc);
  if(args.length==0||!args[0].equals(Type.getObjectType(created.desc))||!Type.getReturnType(edge.desc).equals(Type.getReturnType(wrapper.desc)))return null;
  MethodNode helper=owner.methods.stream().filter(m->m.name.equals(edge.name)&&m.desc.equals(edge.desc)).findFirst().orElse(null);
  if(helper==null||helper==wrapper||(helper.access&(Opcodes.ACC_STATIC|Opcodes.ACC_ABSTRACT|Opcodes.ACC_NATIVE|Opcodes.ACC_SYNCHRONIZED))!=Opcodes.ACC_STATIC
    ||!helper.tryCatchBlocks.isEmpty())return null;
  MethodInsnNode ctor=null;int ctorAt=-1;
  for(int i=2;i<w.size()-2;i++)if(w.get(i) instanceof MethodInsnNode call){
   if(ctor!=null||call.getOpcode()!=Opcodes.INVOKESPECIAL||!call.owner.equals(created.desc)||!call.name.equals("<init>"))return null;
   ctor=call;ctorAt=i;
  }
  if(ctor==null||ctorAt!=2+Type.getArgumentTypes(ctor.desc).length||w.size()-ctorAt-3!=args.length-1)return null;
  Map<Integer,Type> sourceSlots=new HashMap<>();int slot=0;for(Type type:parameters){sourceSlots.put(slot,type);slot+=type.getSize();}
  Type[] ctorArgs=Type.getArgumentTypes(ctor.desc);
  for(int i=0;i<ctorArgs.length;i++)if(!parameter(w.get(i+2),ctorArgs[i],sourceSlots))return null;
  Map<Integer,Integer> mapping=new HashMap<>();int helperSlot=1;
  for(int i=1;i<args.length;i++){
   AbstractInsnNode operand=w.get(ctorAt+i);if(!parameter(operand,args[i],sourceSlots))return null;
   mapping.put(helperSlot,((VarInsnNode)operand).var);helperSlot+=args[i].getSize();
  }
  List<AbstractInsnNode> h=real(helper);if(h.isEmpty()||!(h.getFirst() instanceof VarInsnNode load)||load.getOpcode()!=Opcodes.ALOAD||load.var!=0)return null;
  if(h.getLast().getOpcode()!=w.getLast().getOpcode())return null;
  for(int i=1;i<h.size();i++){
   AbstractInsnNode instruction=h.get(i);int opcode=instruction.getOpcode();
   if(instruction instanceof VarInsnNode variable){
    if(opcode<Opcodes.ILOAD||opcode>Opcodes.ALOAD||!mapping.containsKey(variable.var))return null;
   }else if(instruction instanceof IincInsnNode||instruction instanceof JumpInsnNode||instruction instanceof TableSwitchInsnNode
      ||instruction instanceof LookupSwitchInsnNode||opcode==Opcodes.ATHROW||opcode==Opcodes.MONITORENTER||opcode==Opcodes.MONITOREXIT
      ||opcode>=Opcodes.IRETURN&&opcode<=Opcodes.RETURN&&i!=h.size()-1)return null;
  }
  MethodNode expanded=new MethodNode(wrapper.access,wrapper.name,wrapper.desc,null,null);
  for(int i=0;i<=ctorAt;i++)expanded.instructions.add(w.get(i).clone(new HashMap<>()));
  for(int i=1;i<h.size();i++){AbstractInsnNode copy=h.get(i).clone(new HashMap<>());if(copy instanceof VarInsnNode v)v.var=mapping.get(v.var);expanded.instructions.add(copy);}
  expanded.maxLocals=wrapper.maxLocals;expanded.maxStack=Math.max(wrapper.maxStack,helper.maxStack);return expanded;
 }
 private static boolean parameter(AbstractInsnNode instruction,Type type,Map<Integer,Type> slots){return instruction instanceof VarInsnNode v
   &&type.equals(slots.get(v.var))&&v.getOpcode()==type.getOpcode(Opcodes.ILOAD);}
 private static List<AbstractInsnNode> real(MethodNode method){List<AbstractInsnNode> c=new ArrayList<>();for(var i:method.instructions)if(i.getOpcode()>=0)c.add(i);return c;}
}
