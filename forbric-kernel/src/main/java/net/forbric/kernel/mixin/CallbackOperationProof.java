/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.mixin;
import java.util.*;
import org.objectweb.asm.*;import org.objectweb.asm.tree.*;
import net.forbric.kernel.boot.DefinedMethodContracts.MethodContract;
/** Closes the real weaver's Operation graph: array extraction, boxing and nested certified scopes must preserve every operand. */
final class CallbackOperationProof implements Opcodes {
 private static final String OP="com/llamalad7/mixinextras/injector/wrapoperation/Operation";
 record Wrapper(String name,String desc,boolean stat){}
 private final ClassNode owner;private final String helper,helperMethod;private final Set<Wrapper> wrappers;private final Map<String,MethodContract> contracts=new LinkedHashMap<>();private final Set<String> visiting=new HashSet<>();
 private CallbackOperationProof(ClassNode owner,String helper,String helperMethod,Set<Wrapper> wrappers){this.owner=owner;this.helper=helper;this.helperMethod=helperMethod;this.wrappers=wrappers;}
 static List<MethodContract> prove(ClassNode owner,String hostMethod,String helper,String helperMethod,Set<Wrapper> wrappers){
  CallbackOperationProof proof=new CallbackOperationProof(owner,helper,helperMethod,wrappers);MethodNode caller=NativeCallChanges.method(owner,hostMethod);if(caller==null)return List.of();
  List<NativeCallChanges.Site> roots=NativeCallChanges.sites(owner,caller).stream().filter(site->proof.wrapper(site.call())!=null).toList();if(roots.size()!=1)return List.of();var root=roots.getFirst();
  List<NativeCallChanges.Expr> host=NativeCallChanges.argumentValuesAt(owner,caller,root.call());if(host==null)return List.of();
  int count=Type.getArgumentTypes(helperMethod.substring(helperMethod.indexOf('('))).length,base=root.call().getOpcode()==INVOKESTATIC?0:1;
  if(root.operands().size()!=base+count+1+host.size()||!root.operands().subList(base+count+1,root.operands().size()).equals(host))return List.of();
  NativeCallChanges.Expr receiver=base==0?null:root.operands().getFirst();if(receiver!=null&&!receiver.equals(new NativeCallChanges.Expr("parameter","0")))return List.of();
  if(!proof.operation(caller,root.operands().get(base+count),root.operands().subList(base,base+count),host,receiver))return List.of();
  proof.record(caller);return List.copyOf(proof.contracts.values());
 }
 private Wrapper wrapper(MethodInsnNode call){if(!call.owner.equals(owner.name))return null;return wrappers.stream().filter(w->w.name.equals(call.name)&&w.desc.equals(call.desc)&&w.stat==(call.getOpcode()==INVOKESTATIC)).findFirst().orElse(null);}
 private boolean operation(MethodNode where,NativeCallChanges.Expr value,List<NativeCallChanges.Expr> args,List<NativeCallChanges.Expr> host,NativeCallChanges.Expr receiver){
  if(!value.kind().equals("dynamic"))return false;List<InvokeDynamicInsnNode> dynamic=owner.methods.stream().flatMap(m->Arrays.stream(m.instructions.toArray())).filter(InvokeDynamicInsnNode.class::isInstance).map(InvokeDynamicInsnNode.class::cast)
   .filter(i->symbol(i).equals(value.symbol())).toList();if(dynamic.size()!=1)return false;var factory=dynamic.getFirst();
  if(!Type.getReturnType(factory.desc).equals(Type.getObjectType(OP))||!factory.bsm.getOwner().equals("java/lang/invoke/LambdaMetafactory")||factory.bsmArgs.length!=3||!(factory.bsmArgs[1]instanceof Handle handle)
   ||handle.getTag()!=H_INVOKESTATIC||!handle.getOwner().equals(owner.name))return false;
  MethodNode body=NativeCallChanges.method(owner,handle.getName()+handle.getDesc());if(body==null||!body.tryCatchBlocks.isEmpty()||(body.access&(ACC_STATIC|ACC_PRIVATE))!=(ACC_STATIC|ACC_PRIVATE))return false;
  String id=body.name+body.desc;if(!visiting.add(id))return false;
  Type[] captures=Type.getArgumentTypes(factory.desc),parameters=Type.getArgumentTypes(body.desc);if(parameters.length!=captures.length+1||!parameters[parameters.length-1].getDescriptor().equals("[Ljava/lang/Object;")||value.inputs().size()!=captures.length)return false;
  Map<Integer,NativeCallChanges.Expr> locals=new HashMap<>();int slot=0;for(int p=0;p<captures.length;p++){if(!captures[p].equals(parameters[p]))return false;locals.put(slot,value.inputs().get(p));slot+=captures[p].getSize();}int array=slot;
  List<NativeCallChanges.Site> sites=NativeCallChanges.sites(owner,body);List<NativeCallChanges.Site> targets=sites.stream().filter(s->wrapper(s.call())!=null||isHelper(s.call())).toList();if(targets.size()!=1)return false;
  var target=targets.getFirst();List<AbstractInsnNode> executable=Arrays.stream(body.instructions.toArray()).filter(i->i.getOpcode()>=0).toList();int terminal=executable.indexOf(target.call());
  if(terminal<0||executable.size()!=terminal+4||executable.get(terminal+1).getOpcode()!=ACONST_NULL
      ||!(executable.get(terminal+2)instanceof TypeInsnNode cast)||cast.getOpcode()!=CHECKCAST||!cast.desc.equals("java/lang/Void")||executable.getLast().getOpcode()!=ARETURN)return false;
  List<NativeCallChanges.Site> checks=sites.stream().filter(s->argumentCheck(s.call())).toList();if(checks.size()!=1||body.instructions.indexOf(checks.getFirst().call())>=body.instructions.indexOf(target.call()))return false;
  List<NativeCallChanges.Expr> check=checks.getFirst().operands().stream().map(e->project(e,locals,array,args)).toList();if(check.size()!=3||check.stream().anyMatch(Objects::isNull)||!check.getFirst().kind().equals("operation-array")||!Objects.equals(integer(check.get(1)),args.size()))return false;
  for(AbstractInsnNode instruction:body.instructions){int op=instruction.getOpcode();if(instruction instanceof FieldInsnNode||instruction instanceof JumpInsnNode||instruction instanceof TableSwitchInsnNode||instruction instanceof LookupSwitchInsnNode||op==NEW||op==PUTSTATIC||op==MONITORENTER||op==MONITOREXIT||op==ATHROW)return false;
   if(instruction instanceof MethodInsnNode call&&!isHelper(call)&&wrapper(call)==null&&!unbox(call)&&!argumentCheck(call))return false;
  }
  List<NativeCallChanges.Expr> values=target.operands().stream().map(e->project(e,locals,array,args)).toList();if(values.stream().anyMatch(Objects::isNull))return false;
  boolean valid;
  if(isHelper(target.call()))valid=values.equals(args);
  else{int base=target.call().getOpcode()==INVOKESTATIC?0:1;if(values.size()!=base+args.size()+1+host.size()||!values.subList(base,base+args.size()).equals(args)||!values.subList(base+args.size()+1,values.size()).equals(host)||base==1&&!Objects.equals(receiver,values.getFirst()))return false;
   valid=operation(body,values.get(base+args.size()),args,host,receiver);
  }
  if(!valid)return false;record(body);visiting.remove(id);return true;
 }
 private NativeCallChanges.Expr project(NativeCallChanges.Expr expr,Map<Integer,NativeCallChanges.Expr> locals,int array,List<NativeCallChanges.Expr> args){
  if(expr.kind().equals("parameter")){int slot=Integer.parseInt(expr.symbol());return slot==array?new NativeCallChanges.Expr("operation-array",""):locals.get(slot);}
  List<NativeCallChanges.Expr> inputs=new ArrayList<>();for(var input:expr.inputs()){var mapped=project(input,locals,array,args);if(mapped==null)return null;inputs.add(mapped);}
  if(expr.kind().equals("array-read")){if(inputs.size()!=2||!inputs.getFirst().kind().equals("operation-array"))return null;Integer index=integer(inputs.get(1));return index!=null&&index>=0&&index<args.size()?args.get(index):null;}
  if(expr.kind().equals("call")){MixinFit.Member member=MixinFit.parseMember(expr.symbol());if(member!=null&&unbox(new MethodInsnNode(INVOKEVIRTUAL,member.owner(),member.name(),member.desc(),false)))return inputs.size()==1?inputs.getFirst():null;}
  return new NativeCallChanges.Expr(expr.kind(),expr.symbol(),inputs);
 }
 private boolean isHelper(MethodInsnNode call){return call.getOpcode()==INVOKESTATIC&&call.owner.equals(helper)&&(call.name+call.desc).equals(helperMethod);}
 private static boolean unbox(MethodInsnNode call){String primitive=switch(call.owner){case"java/lang/Boolean"->"Z";case"java/lang/Byte"->"B";case"java/lang/Character"->"C";case"java/lang/Short"->"S";case"java/lang/Integer"->"I";case"java/lang/Float"->"F";case"java/lang/Long"->"J";case"java/lang/Double"->"D";default->null;};String name=switch(primitive==null?"":primitive){case"Z"->"booleanValue";case"B"->"byteValue";case"C"->"charValue";case"S"->"shortValue";case"I"->"intValue";case"F"->"floatValue";case"J"->"longValue";case"D"->"doubleValue";default->"";};return primitive!=null&&call.getOpcode()==INVOKEVIRTUAL&&call.name.equals(name)&&call.desc.equals("()"+primitive);}
 private static boolean argumentCheck(MethodInsnNode call){return call.getOpcode()==INVOKESTATIC&&call.owner.equals("com/llamalad7/mixinextras/injector/wrapoperation/WrapOperationRuntime")&&call.name.equals("checkArgumentCount")&&call.desc.equals("([Ljava/lang/Object;ILjava/lang/String;)V");}
 private static Integer integer(NativeCallChanges.Expr value){try{if(value.kind().equals("integer"))return Integer.valueOf(value.symbol());if(value.kind().equals("literal")&&value.symbol().startsWith("java.lang.Integer:"))return Integer.valueOf(value.symbol().substring(18));if(value.kind().equals("constant")){int opcode=Integer.parseInt(value.symbol());return opcode>=ICONST_M1&&opcode<=ICONST_5?opcode-ICONST_0:null;}}catch(NumberFormatException invalid){}return null;}
 private static String symbol(InvokeDynamicInsnNode dynamic){return dynamic.name+dynamic.desc+dynamic.bsm+Arrays.toString(dynamic.bsmArgs);}
 private void record(MethodNode method){contracts.put(method.name+method.desc,new MethodContract(owner.name,method.name,method.desc,MixinInstructionFingerprint.hash(method)));}
}
