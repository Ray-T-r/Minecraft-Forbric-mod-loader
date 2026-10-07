/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.mixin;

import java.util.*;
import java.util.function.Function;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import org.objectweb.asm.tree.analysis.*;
import net.forbric.kernel.util.ForbricLog;

/** A completed source decode guard owns that same evaluator/input within its lexical parse operation.
 * A private result sentinel and its pure cancellation consumer are transported together. */
public final class MixinDecodeScopeAdapter {
 public static final String PROPERTY="forbric.decodeSourceScopes";
 private static final String SCOPES="net/forbric/kernel/runtime/KernelSourceDecodeScopes",OP="com/llamalad7/mixinextras/injector/wrapoperation/Operation";
 private static final String INJECT="Lorg/spongepowered/asm/mixin/injection/Inject;",WRAP="Lcom/llamalad7/mixinextras/injector/wrapoperation/WrapOperation;",WRAPMETHOD="Lcom/llamalad7/mixinextras/injector/wrapmethod/WrapMethod;";
 private static final String LOCAL="Lcom/llamalad7/mixinextras/sugar/Local;",JSON="com/google/gson/JsonElement",OBJECT="com/google/gson/JsonObject",RESULT="com/mojang/serialization/DataResult",CI="org/spongepowered/asm/mixin/injection/callback/CallbackInfo";
 private static final Handle LAMBDA=new Handle(Opcodes.H_INVOKESTATIC,"java/lang/invoke/LambdaMetafactory","metafactory","(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;Ljava/lang/invoke/MethodType;Ljava/lang/invoke/MethodHandle;Ljava/lang/invoke/MethodType;)Ljava/lang/invoke/CallSite;",false);
 private record Guard(MethodInsnNode call,int input){ }
 private MixinDecodeScopeAdapter(){ }
 /** Re-check the final call and its data flow, after every guest transformation, before recording ownership. */
 public static void certify(ClassNode owner){
  for(MethodNode method:owner.methods)for(var instruction:method.instructions){
   if(!(instruction instanceof MethodInsnNode record)||record.getOpcode()!=Opcodes.INVOKESTATIC||!record.owner.equals(SCOPES)||!record.name.equals("record")||!record.desc.equals("(ZLjava/lang/Object;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)Z"))continue;
   var code=DefaultMethodOverloadBridge.real(method);int index=code.indexOf(record);if(index<5)continue;
   if(!(code.get(index-1)instanceof LdcInsnNode descriptor&&descriptor.cst instanceof String desc)
     ||!(code.get(index-2)instanceof LdcInsnNode name&&name.cst instanceof String member)
     ||!(code.get(index-3)instanceof LdcInsnNode declaration&&declaration.cst instanceof String type)
     ||!(code.get(index-4)instanceof VarInsnNode input&&input.getOpcode()==Opcodes.ALOAD)
     ||!(code.get(index-5)instanceof MethodInsnNode call)||call.getOpcode()!=Opcodes.INVOKESTATIC
     ||!call.owner.equals(type)||!call.name.equals(member)||!call.desc.equals(desc)
     ||!Type.getReturnType(desc).equals(Type.BOOLEAN_TYPE))continue;
   try{var frames=new Analyzer<>(new SourceInterpreter()).analyze(owner.name,method);var at=frames[method.instructions.indexOf(call)];var args=Type.getArgumentTypes(desc);
    if(args.length==0||!args[0].equals(Type.getObjectType(OBJECT))||at==null
      ||!derived(method,frames,at.getStack(at.getStackSize()-args.length),input.var,new HashSet<>())
      ||!verdictFlow(owner,method,call,record,frames,true))continue;
    List<net.forbric.kernel.boot.DefinedMethodContracts.MethodContract> dependencies=new ArrayList<>();
    if(code.get(index+2)instanceof FieldInsnNode marker){
     MethodNode consumer=owner.methods.stream().filter(m->markerBody(m,owner.name,marker.name)).findFirst().orElseThrow();MethodNode predicate=markerPredicateIntact(owner,consumer);
     for(MethodNode dependency:List.of(consumer,predicate))dependencies.add(new net.forbric.kernel.boot.DefinedMethodContracts.MethodContract(owner.name,dependency.name,dependency.desc,MixinInstructionFingerprint.hash(dependency)));
    }
    net.forbric.kernel.boot.KernelDecodeGuardWitnesses.register(new net.forbric.kernel.boot.DefinedMethodContracts.MethodContract(owner.name,method.name,method.desc,MixinInstructionFingerprint.hash(method)),dependencies);
   }catch(AnalyzerException|RuntimeException unproved){ }
  }
 }
 public static byte[] asLoaded(byte[] bytes,Function<String,byte[]> resources){
  ClassNode mixin=new ClassNode();new ClassReader(bytes).accept(mixin,0);
  Function<String,ClassNode> classes=name->{byte[] target=resources.apply(name+".class");if(target==null)return null;ClassNode node=new ClassNode();new ClassReader(target).accept(node,0);return node;};
  if(adapt(mixin,classes)==0)return bytes;ClassWriter writer=new ClassWriter(ClassWriter.COMPUTE_MAXS);mixin.accept(writer);return writer.toByteArray();
 }
 public static int adapt(ClassNode mixin,Function<String,ClassNode> classes){
  if("off".equalsIgnoreCase(System.getProperty(PROPERTY,"on")))return 0;
  var targets=MixinFit.mixinTargets(mixin);if(targets.size()!=1)return 0;ClassNode target=classes.apply(targets.getFirst());if(target==null)return 0;
  List<MethodNode> added=new ArrayList<>();int changed=0;
  for(MethodNode handler:new ArrayList<>(mixin.methods)){
   AnnotationNode injection=MixinFit.injectorOf(handler);if(injection==null||(handler.access&Opcodes.ACC_STATIC)==0||MixinFit.value(injection,"slice")!=null)continue;
   var selectors=MixinFit.stringList(MixinFit.value(injection,"method"));if(selectors.size()!=1)continue;MethodNode host=MixinStubRebind.bound(target,selectors.getFirst());if(host==null||(host.access&Opcodes.ACC_STATIC)==0)continue;
   var points=MixinFit.atNodes(injection);if(points.size()!=1||!"INVOKE".equals(MixinFit.value(points.getFirst(),"value"))||MixinFit.value(points.getFirst(),"ordinal")!=null||MixinFit.value(points.getFirst(),"shift")!=null)continue;
   Type[] arguments=Type.getArgumentTypes(handler.desc);int input=-1;
   if(INJECT.equals(injection.desc)&&Boolean.TRUE.equals(MixinFit.value(injection,"cancellable"))){
    Type[] nativeArgs=Type.getArgumentTypes(host.desc);
    if(Arrays.stream(nativeArgs).anyMatch(t->t.getSort()!=Type.OBJECT&&t.getSort()!=Type.ARRAY)||arguments.length!=nativeArgs.length+2||!Arrays.equals(nativeArgs,Arrays.copyOf(arguments,nativeArgs.length))||!arguments[nativeArgs.length].equals(Type.getObjectType("org/spongepowered/asm/mixin/injection/callback/CallbackInfoReturnable"))||Type.getReturnType(host.desc).getSort()!=Type.OBJECT||!Type.getReturnType(handler.desc).equals(Type.VOID_TYPE))continue;
    if(!arguments[arguments.length-1].equals(Type.getObjectType(JSON))||MixinStubRebind.sugar(handler,arguments.length-1,LOCAL)==null)continue;input=arguments.length-1;
   }else if(WRAP.equals(injection.desc)&&arguments.length>=4&&arguments[3].equals(Type.getObjectType(OP))&&Type.getReturnType(handler.desc).equals(Type.getObjectType(RESULT))){
    if(!forwardedParse(handler))continue;input=2;
   }else continue;
   Guard guard=guard(mixin,handler,input);if(guard==null)continue;
   if(INJECT.equals(injection.desc)){
    // The injector and native parse share their input; the source's own cancellation still returns its original value.
    if(added.stream().anyMatch(m->annotations(m).stream().anyMatch(a->a.desc.equals(WRAPMETHOD)&&MixinFit.stringList(MixinFit.value(a,"method")).contains(host.name+host.desc))))continue;
    instrument(handler,guard);added.add(methodScope(mixin,handler,host));changed++;
   }else{
    MethodNode consumer=markerConsumer(mixin,handler);if(consumer==null)continue;
    MethodInsnNode sink=consumerSink(target,host,consumer);if(sink==null)continue;
    String predicate=MixinHandlerShim.asideName(mixin.name,consumer.name,"$forbricmarkerpredicate");
    added.add(markerPredicate(mixin,consumer,predicate));added.add(consumerWrapper(mixin,host,sink,predicate));removeInjector(consumer);
    instrument(handler,guard);added.add(operationScope(mixin,handler,injection));changed+=2;
   }
  }
  mixin.methods.addAll(added);
  if(changed>0)ForbricLog.info("[Forbric/Mixin] %s retains source decode guards and their closed marker consumers; the same evaluator/input is judged once inside the native parse scope",mixin.name.replace('/','.'));
  return changed;
 }
 private static Guard guard(ClassNode owner,MethodNode method,int input){
  Type[]args=Type.getArgumentTypes(method.desc);int slot=DefaultMethodOverloadBridge.slots(args,true)[input];
  try { Frame<SourceValue>[] frames=new Analyzer<>(new SourceInterpreter()).analyze(owner.name,method);List<MethodInsnNode> found=new ArrayList<>();
   for(var i:method.instructions)if(i instanceof MethodInsnNode call&&call.getOpcode()==Opcodes.INVOKESTATIC&&Type.getReturnType(call.desc).equals(Type.BOOLEAN_TYPE)){
    Type[]passed=Type.getArgumentTypes(call.desc);if(passed.length==0||!passed[0].equals(Type.getObjectType(OBJECT)))continue;
    var frame=frames[method.instructions.indexOf(call)];if(frame!=null&&derived(method,frames,frame.getStack(frame.getStackSize()-passed.length),slot,new HashSet<>())&&verdictFlow(owner,method,call,null,frames,false))found.add(call);
   }
   return found.size()==1?new Guard(found.getFirst(),slot):null;
  }catch(AnalyzerException|RuntimeException unsupported){return null;}
 }
 /** A completed Boolean call owns decode only if false actually cancels it. Merely observing or discarding
  * a verdict cannot disable the native fallback. These closed suffixes also prohibit input mutation between
  * judgement and the original parse, and are rechecked against the final woven method. */
 private static boolean verdictFlow(ClassNode owner,MethodNode method,MethodInsnNode evaluator,MethodInsnNode record,Frame<SourceValue>[] frames,boolean finalBody){
  if(!method.tryCatchBlocks.isEmpty())return false;
  var code=DefaultMethodOverloadBridge.real(method);int at=code.indexOf(record==null?evaluator:record);
  if(at<0||at+1>=code.size()||!(code.get(at+1)instanceof JumpInsnNode keep)||keep.getOpcode()!=Opcodes.IFNE)return false;
  AbstractInsnNode kept=next(keep.label);int accepted=code.indexOf(kept),rejected=at+2;
  if(accepted<=rejected)return false;
  Type[] args=Type.getArgumentTypes(method.desc);int[] slots=DefaultMethodOverloadBridge.slots(args,true);int ci=-1;
  for(int i=0;i<args.length;i++)if(args[i].equals(Type.getObjectType("org/spongepowered/asm/mixin/injection/callback/CallbackInfoReturnable"))){if(ci!=-1)return false;ci=slots[i];}
  if(ci>=0){
   if(!Type.getReturnType(method.desc).equals(Type.VOID_TYPE)||accepted!=code.size()-1||kept.getOpcode()!=Opcodes.RETURN)return false;
   AbstractInsnNode last=code.get(accepted-1);if(!(last instanceof MethodInsnNode cancel)||cancel.getOpcode()!=Opcodes.INVOKEVIRTUAL
     ||!cancel.owner.equals("org/spongepowered/asm/mixin/injection/callback/CallbackInfoReturnable")||!cancel.name.equals("setReturnValue")||!cancel.desc.equals("(Ljava/lang/Object;)V"))return false;
   var cancellation=frames[method.instructions.indexOf(cancel)];if(cancellation==null||cancellation.getStackSize()<2
     ||!parameter(method,frames,cancellation.getStack(cancellation.getStackSize()-2),ci,new HashSet<>()))return false;
   for(var instruction:code.subList(rejected,accepted)){
    if(instruction instanceof JumpInsnNode||instruction instanceof TableSwitchInsnNode||instruction instanceof LookupSwitchInsnNode||instruction.getOpcode()>=Opcodes.IRETURN&&instruction.getOpcode()<=Opcodes.RETURN||instruction.getOpcode()==Opcodes.ATHROW)return false;
    if(instruction instanceof MethodInsnNode call&&call!=cancel&&(call.owner.equals(OP)||call.owner.equals(cancel.owner)))return false;
   }
   return true;
  }
  if(!Type.getReturnType(method.desc).equals(Type.getObjectType(RESULT))||accepted!=rejected+3
    ||!(code.get(rejected)instanceof FieldInsnNode marker)||marker.getOpcode()!=Opcodes.GETSTATIC||!marker.owner.equals(owner.name)||!marker.desc.equals("Ljava/lang/Object;")
    ||!(code.get(rejected+1)instanceof MethodInsnNode success)||success.getOpcode()!=Opcodes.INVOKESTATIC||!success.owner.equals(RESULT)||!success.name.equals("success")||!success.desc.equals("(Ljava/lang/Object;)L"+RESULT+";")||code.get(rejected+2).getOpcode()!=Opcodes.ARETURN
    ||!forwardedParse(method)||!parseTail(code.subList(accepted,code.size())))return false;
  // The producer's original cancellation consumer remains a closed role after transport. At initial
  // admission it still has @Inject; in the final class the immutable predicate must still call that body.
  List<MethodNode> consumers=owner.methods.stream().filter(m->markerBody(m,owner.name,marker.name)).toList();
  return consumers.size()==1&&(!finalBody||markerPredicateIntact(owner,consumers.getFirst())!=null);
 }
 private static boolean parameter(MethodNode method,Frame<SourceValue>[] frames,SourceValue value,int input,Set<AbstractInsnNode> active){
  if(value==null||value.insns.size()!=1)return false;var instruction=value.insns.iterator().next();if(!active.add(instruction))return false;
  try{var at=frames[method.instructions.indexOf(instruction)];if(at==null)return false;
   if(instruction instanceof VarInsnNode variable){if(variable.getOpcode()==Opcodes.ALOAD){var local=at.getLocal(variable.var);return variable.var==input&&local.insns.isEmpty()||parameter(method,frames,local,input,active);}if(variable.getOpcode()==Opcodes.ASTORE)return parameter(method,frames,at.getStack(at.getStackSize()-1),input,active);}
   return false;
  }finally{active.remove(instruction);}
 }
 private static boolean parseTail(List<AbstractInsnNode> code){
  if(code.size()!=18||!(code.get(15)instanceof MethodInsnNode call)||call.getOpcode()!=Opcodes.INVOKEINTERFACE||!call.owner.equals(OP)||!call.name.equals("call")||!call.desc.equals("([Ljava/lang/Object;)Ljava/lang/Object;")
    ||!(code.get(16)instanceof TypeInsnNode cast)||cast.getOpcode()!=Opcodes.CHECKCAST||!cast.desc.equals(RESULT)||code.get(17).getOpcode()!=Opcodes.ARETURN)return false;
  int p=0;if(!(code.get(p++)instanceof VarInsnNode op)||op.getOpcode()!=Opcodes.ALOAD||op.var!=3||integer(code.get(p++))!=3||!(code.get(p++)instanceof TypeInsnNode array)||array.getOpcode()!=Opcodes.ANEWARRAY||!array.desc.equals("java/lang/Object"))return false;
  for(int i=0;i<3;i++)if(code.get(p++).getOpcode()!=Opcodes.DUP||integer(code.get(p++))!=i||!(code.get(p++)instanceof VarInsnNode input)||input.getOpcode()!=Opcodes.ALOAD||input.var!=i||code.get(p++).getOpcode()!=Opcodes.AASTORE)return false;
  return true;
 }
 private static boolean markerBody(MethodNode method,String owner,String marker){
  Type[] args=Type.getArgumentTypes(method.desc);if((method.access&Opcodes.ACC_STATIC)==0||args.length!=4||(args[0].getSort()!=Type.OBJECT&&args[0].getSort()!=Type.ARRAY)||(args[1].getSort()!=Type.OBJECT&&args[1].getSort()!=Type.ARRAY)||!args[2].equals(Type.getObjectType("java/lang/Object"))||!args[3].equals(Type.getObjectType(CI))||!Type.getReturnType(method.desc).equals(Type.VOID_TYPE)||!method.tryCatchBlocks.isEmpty())return false;
  var code=DefaultMethodOverloadBridge.real(method);return code.size()==6&&code.get(0)instanceof VarInsnNode input&&input.getOpcode()==Opcodes.ALOAD&&input.var==2
    &&code.get(1)instanceof FieldInsnNode field&&field.getOpcode()==Opcodes.GETSTATIC&&field.owner.equals(owner)&&field.name.equals(marker)&&field.desc.equals("Ljava/lang/Object;")
    &&code.get(2)instanceof JumpInsnNode branch&&branch.getOpcode()==Opcodes.IF_ACMPNE&&next(branch.label)==code.getLast()
    &&code.get(3)instanceof VarInsnNode ci&&ci.getOpcode()==Opcodes.ALOAD&&ci.var==3
    &&code.get(4)instanceof MethodInsnNode cancel&&cancel.getOpcode()==Opcodes.INVOKEVIRTUAL&&cancel.owner.equals(CI)&&cancel.name.equals("cancel")&&cancel.desc.equals("()V")&&code.getLast().getOpcode()==Opcodes.RETURN;
 }
 private static MethodNode markerPredicateIntact(ClassNode owner,MethodNode consumer){
  MethodNode found=null;
  for(var method:owner.methods){if((method.access&Opcodes.ACC_STATIC)==0||!method.desc.equals("(Ljava/lang/Object;)Z")||!method.tryCatchBlocks.isEmpty())continue;
   var code=DefaultMethodOverloadBridge.real(method);if(code.size()!=14)continue;
   if(code.get(0)instanceof TypeInsnNode create&&create.getOpcode()==Opcodes.NEW&&create.desc.equals(CI)&&code.get(1).getOpcode()==Opcodes.DUP&&code.get(2)instanceof LdcInsnNode id&&id.cst instanceof String&&code.get(3).getOpcode()==Opcodes.ICONST_1
     &&code.get(4)instanceof MethodInsnNode init&&init.getOpcode()==Opcodes.INVOKESPECIAL&&init.owner.equals(CI)&&init.name.equals("<init>")&&init.desc.equals("(Ljava/lang/String;Z)V")
     &&code.get(5)instanceof VarInsnNode save&&save.getOpcode()==Opcodes.ASTORE&&save.var==1&&code.get(6).getOpcode()==Opcodes.ACONST_NULL&&code.get(7).getOpcode()==Opcodes.ACONST_NULL
     &&code.get(8)instanceof VarInsnNode value&&value.getOpcode()==Opcodes.ALOAD&&value.var==0&&code.get(9)instanceof VarInsnNode ci&&ci.getOpcode()==Opcodes.ALOAD&&ci.var==1
     &&code.get(10)instanceof MethodInsnNode call&&call.getOpcode()==Opcodes.INVOKESTATIC&&call.owner.equals(owner.name)&&call.name.equals(consumer.name)&&call.desc.equals(consumer.desc)
     &&code.get(11)instanceof VarInsnNode read&&read.getOpcode()==Opcodes.ALOAD&&read.var==1&&code.get(12)instanceof MethodInsnNode verdict&&verdict.getOpcode()==Opcodes.INVOKEVIRTUAL&&verdict.owner.equals(CI)&&verdict.name.equals("isCancelled")&&verdict.desc.equals("()Z")&&code.get(13).getOpcode()==Opcodes.IRETURN){if(found!=null)return null;found=method;}
  }return found;
 }
 private static boolean derived(MethodNode method,Frame<SourceValue>[]frames,SourceValue value,int input,Set<AbstractInsnNode> active){
  if(value==null)return false;if(value.insns.isEmpty())return false;if(value.insns.size()!=1)return false;var instruction=value.insns.iterator().next();if(!active.add(instruction))return false;
  try{var at=frames[method.instructions.indexOf(instruction)];if(at==null)return false;
   if(instruction instanceof VarInsnNode variable){if(variable.getOpcode()==Opcodes.ALOAD){var local=at.getLocal(variable.var);return variable.var==input&&local.insns.isEmpty()||derived(method,frames,local,input,active);}if(variable.getOpcode()==Opcodes.ASTORE)return derived(method,frames,at.getStack(at.getStackSize()-1),input,active);}
   if(instruction instanceof TypeInsnNode cast&&cast.getOpcode()==Opcodes.CHECKCAST&&cast.desc.equals(JSON))return derived(method,frames,at.getStack(at.getStackSize()-1),input,active);
   if(instruction instanceof MethodInsnNode call&&call.owner.equals(JSON)&&call.name.equals("getAsJsonObject")&&call.desc.equals("()L"+OBJECT+";"))return derived(method,frames,at.getStack(at.getStackSize()-1),input,active);
   return false;
  }finally{active.remove(instruction);}
 }
 private static void instrument(MethodNode method,Guard guard){
  InsnList after=new InsnList();after.add(new VarInsnNode(Opcodes.ALOAD,guard.input));after.add(new LdcInsnNode(guard.call.owner));after.add(new LdcInsnNode(guard.call.name));after.add(new LdcInsnNode(guard.call.desc));after.add(new MethodInsnNode(Opcodes.INVOKESTATIC,SCOPES,"record","(ZLjava/lang/Object;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)Z",false));method.instructions.insert(guard.call,after);method.maxStack+=4;
 }
 private static MethodNode methodScope(ClassNode owner,MethodNode source,MethodNode host){
  String name=MixinHandlerShim.asideName(owner.name,source.name,"$forbricdecodescope");Type[]args=Type.getArgumentTypes(host.desc),all=Arrays.copyOf(args,args.length+1);all[args.length]=Type.getObjectType(OP);
  MethodNode wrapper=new MethodNode(Opcodes.ACC_PRIVATE|Opcodes.ACC_STATIC,name,Type.getMethodDescriptor(Type.getReturnType(host.desc),all),null,null);
  AnnotationNode annotation=new AnnotationNode(WRAPMETHOD);annotation.values=new ArrayList<>(List.of("method",List.of(host.name+host.desc)));wrapper.visibleAnnotations=new ArrayList<>(List.of(annotation));
  loadOperation(wrapper,args.length,args);finishScope(wrapper,Type.getReturnType(host.desc));return wrapper;
 }
 private static MethodNode operationScope(ClassNode owner,MethodNode source,AnnotationNode annotation){
  MethodNode outer=new MethodNode(source.access,source.name,source.desc,null,source.exceptions.toArray(String[]::new));source.accept(outer);outer.instructions.clear();outer.tryCatchBlocks.clear();outer.localVariables=null;
  source.name=MixinHandlerShim.asideName(owner.name,source.name,"$forbricdecodesource");removeInjector(source);source.visibleParameterAnnotations=null;source.invisibleParameterAnnotations=null;
  Type[]args=Type.getArgumentTypes(source.desc);int[]slots=DefaultMethodOverloadBridge.slots(args,true);for(int i=0;i<args.length;i++)outer.instructions.add(new VarInsnNode(args[i].getOpcode(Opcodes.ILOAD),slots[i]));
  outer.instructions.add(new InvokeDynamicInsnNode("get",Type.getMethodDescriptor(Type.getObjectType("java/util/function/Supplier"),args),LAMBDA,Type.getMethodType("()Ljava/lang/Object;"),new Handle(Opcodes.H_INVOKESTATIC,owner.name,source.name,source.desc,false),Type.getMethodType("()L"+RESULT+";")));
  finishScope(outer,Type.getObjectType(RESULT));return outer;
 }
 private static void loadOperation(MethodNode method,int operation,Type[]args){
  int[]slots=DefaultMethodOverloadBridge.slots(Type.getArgumentTypes(method.desc),true);method.instructions.add(new VarInsnNode(Opcodes.ALOAD,slots[operation]));array(method,args,slots);
  method.instructions.add(new InvokeDynamicInsnNode("get","(L"+OP+";[Ljava/lang/Object;)Ljava/util/function/Supplier;",LAMBDA,Type.getMethodType("()Ljava/lang/Object;"),new Handle(Opcodes.H_INVOKEINTERFACE,OP,"call","([Ljava/lang/Object;)Ljava/lang/Object;",true),Type.getMethodType("()Ljava/lang/Object;")));
 }
 private static void finishScope(MethodNode method,Type result){method.instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC,SCOPES,"call","(Ljava/util/function/Supplier;)Ljava/lang/Object;",false));method.instructions.add(new TypeInsnNode(Opcodes.CHECKCAST,result.getInternalName()));method.instructions.add(new InsnNode(Opcodes.ARETURN));method.maxLocals=Arrays.stream(Type.getArgumentTypes(method.desc)).mapToInt(Type::getSize).sum();method.maxStack=method.maxLocals+6;}
 private static boolean forwardedParse(MethodNode method){
  Type[]args=Type.getArgumentTypes(method.desc);if(!args[0].equals(Type.getObjectType("com/mojang/serialization/Codec"))||!args[1].equals(Type.getObjectType("com/mojang/serialization/DynamicOps"))||!args[2].equals(Type.getObjectType("java/lang/Object")))return false;
  // Loading the right slot does not forward the original parameter if the handler overwrote that slot.
  for(var instruction:method.instructions)if(instruction instanceof VarInsnNode v&&v.getOpcode()==Opcodes.ASTORE&&v.var>=0&&v.var<=3||instruction instanceof IincInsnNode i&&i.var>=0&&i.var<=3)return false;
  var code=DefaultMethodOverloadBridge.real(method);List<MethodInsnNode>calls=code.stream().filter(i->i instanceof MethodInsnNode c&&c.owner.equals(OP)&&c.name.equals("call")).map(MethodInsnNode.class::cast).toList();if(calls.size()!=1)return false;
  int end=code.indexOf(calls.getFirst()),start=end-15;if(start<0)return false;int p=start;
  if(!(code.get(p++)instanceof VarInsnNode load)||load.getOpcode()!=Opcodes.ALOAD||load.var!=3||integer(code.get(p++))!=3||!(code.get(p++)instanceof TypeInsnNode array)||array.getOpcode()!=Opcodes.ANEWARRAY||!array.desc.equals("java/lang/Object"))return false;
  for(int i=0;i<3;i++)if(code.get(p++).getOpcode()!=Opcodes.DUP||integer(code.get(p++))!=i||!(code.get(p++)instanceof VarInsnNode value)||value.getOpcode()!=Opcodes.ALOAD||value.var!=i||code.get(p++).getOpcode()!=Opcodes.AASTORE)return false;
  // The original receiver, ops and input are forwarded in their original order.
  return p==end;
 }
 private static MethodNode markerConsumer(ClassNode owner,MethodNode producer){
  Set<String> markers=new HashSet<>();for(var instruction:producer.instructions)if(instruction instanceof FieldInsnNode field&&field.getOpcode()==Opcodes.GETSTATIC&&field.owner.equals(owner.name)&&field.desc.equals("Ljava/lang/Object;")&&next(field)instanceof MethodInsnNode success&&success.getOpcode()==Opcodes.INVOKESTATIC&&success.owner.equals(RESULT)&&success.name.equals("success")&&success.desc.equals("(Ljava/lang/Object;)L"+RESULT+";")&&next(success).getOpcode()==Opcodes.ARETURN)markers.add(field.name);
  if(markers.size()!=1)return null;String marker=markers.iterator().next();List<MethodNode>matches=new ArrayList<>();
  for(var method:owner.methods){AnnotationNode a=MixinFit.injectorOf(method);if(a==null||!INJECT.equals(a.desc)||!Boolean.TRUE.equals(MixinFit.value(a,"cancellable"))||(method.access&Opcodes.ACC_STATIC)==0)continue;var points=MixinFit.atNodes(a);if(points.size()!=1||!"HEAD".equals(MixinFit.value(points.getFirst(),"value")))continue;
   Type[]args=Type.getArgumentTypes(method.desc);if(args.length!=4||(args[0].getSort()!=Type.OBJECT&&args[0].getSort()!=Type.ARRAY)||(args[1].getSort()!=Type.OBJECT&&args[1].getSort()!=Type.ARRAY)||!args[2].equals(Type.getObjectType("java/lang/Object"))||!args[3].equals(Type.getObjectType(CI))||!Type.getReturnType(method.desc).equals(Type.VOID_TYPE))continue;
   var code=DefaultMethodOverloadBridge.real(method);if(code.size()!=6||!(code.get(0)instanceof VarInsnNode input)||input.getOpcode()!=Opcodes.ALOAD||input.var!=2||!(code.get(1)instanceof FieldInsnNode field)||field.getOpcode()!=Opcodes.GETSTATIC||!field.owner.equals(owner.name)||!field.name.equals(marker)||!field.desc.equals("Ljava/lang/Object;")||!(code.get(2)instanceof JumpInsnNode branch)||branch.getOpcode()!=Opcodes.IF_ACMPNE||next(branch.label)!=code.getLast()||!(code.get(3)instanceof VarInsnNode ci)||ci.getOpcode()!=Opcodes.ALOAD||ci.var!=3||!(code.get(4)instanceof MethodInsnNode cancel)||!cancel.owner.equals(CI)||!cancel.name.equals("cancel")||!cancel.desc.equals("()V")||code.getLast().getOpcode()!=Opcodes.RETURN)continue;matches.add(method);
  }
  return matches.size()==1?matches.getFirst():null;
 }
 private static MethodInsnNode consumerSink(ClassNode target,MethodNode host,MethodNode source){
  String selector=MixinFit.stringList(MixinFit.value(MixinFit.injectorOf(source),"method")).stream().findFirst().orElse(null);if(selector==null)return null;
  List<MethodInsnNode>parses=new ArrayList<>(),sinks=new ArrayList<>();for(var i:host.instructions)if(i instanceof MethodInsnNode call){if(call.owner.equals("com/mojang/serialization/Codec")&&call.name.equals("parse")&&call.desc.equals("(Lcom/mojang/serialization/DynamicOps;Ljava/lang/Object;)L"+RESULT+";"))parses.add(call);if(call.owner.equals(RESULT)&&call.name.equals("ifSuccess")||call.owner.equals("net/forbric/kernel/runtime/KernelFabricConditions")&&call.name.equals("ifSuccessWithoutAForeignSkipMarker"))sinks.add(call);}
  if(parses.size()!=1||sinks.size()!=1)return null;var sink=sinks.getFirst();
  try{Frame<SourceValue>[]frames=new Analyzer<>(new SourceInterpreter()).analyze(target.name,host);var frame=frames[host.instructions.indexOf(sink)];if(frame==null||frame.getStackSize()<2)return null;var parsed=frame.getStack(frame.getStackSize()-2);if(parsed.insns.size()!=1||!parsed.insns.contains(parses.getFirst()))return null;var consumer=frame.getStack(frame.getStackSize()-1);if(consumer.insns.size()!=1||!(consumer.insns.iterator().next()instanceof InvokeDynamicInsnNode lambda))return null;
   List<Handle> handles=Arrays.stream(lambda.bsmArgs).filter(v->v instanceof Handle h&&h.getOwner().equals(target.name)&&h.getTag()==Opcodes.H_INVOKESTATIC).map(Handle.class::cast).toList();if(handles.size()!=1)return null;Handle implementation=handles.getFirst();MethodNode body=target.methods.stream().filter(m->m.name.equals(implementation.getName())&&m.desc.equals(implementation.getDesc())).findFirst().orElse(null);if(body==null||(body.access&Opcodes.ACC_PRIVATE)==0||!selector.equals(body.name)&&!selector.equals(body.name+body.desc))return null;
   return sink;
  }catch(AnalyzerException|RuntimeException unsupported){return null;}
 }
 private static MethodNode markerPredicate(ClassNode owner,MethodNode source,String name){
  MethodNode predicate=new MethodNode(Opcodes.ACC_PRIVATE|Opcodes.ACC_STATIC,name,"(Ljava/lang/Object;)Z",null,null);predicate.visibleAnnotations=new ArrayList<>(List.of(new AnnotationNode("Lorg/spongepowered/asm/mixin/Unique;")));
  predicate.instructions.add(new TypeInsnNode(Opcodes.NEW,CI));predicate.instructions.add(new InsnNode(Opcodes.DUP));predicate.instructions.add(new LdcInsnNode(name));predicate.instructions.add(new InsnNode(Opcodes.ICONST_1));predicate.instructions.add(new MethodInsnNode(Opcodes.INVOKESPECIAL,CI,"<init>","(Ljava/lang/String;Z)V",false));predicate.instructions.add(new VarInsnNode(Opcodes.ASTORE,1));predicate.instructions.add(new InsnNode(Opcodes.ACONST_NULL));predicate.instructions.add(new InsnNode(Opcodes.ACONST_NULL));predicate.instructions.add(new VarInsnNode(Opcodes.ALOAD,0));predicate.instructions.add(new VarInsnNode(Opcodes.ALOAD,1));predicate.instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC,owner.name,source.name,source.desc,false));predicate.instructions.add(new VarInsnNode(Opcodes.ALOAD,1));predicate.instructions.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL,CI,"isCancelled","()Z",false));predicate.instructions.add(new InsnNode(Opcodes.IRETURN));predicate.maxLocals=2;predicate.maxStack=4;return predicate;
 }
 private static MethodNode consumerWrapper(ClassNode owner,MethodNode host,MethodInsnNode sink,String predicate){
  boolean instance=sink.getOpcode()!=Opcodes.INVOKESTATIC;Type[]passed=Type.getArgumentTypes(sink.desc);Type[]args=instance?new Type[]{Type.getObjectType(RESULT),Type.getObjectType("java/util/function/Consumer"),Type.getObjectType(OP)}:Arrays.copyOf(passed,passed.length+1);if(!instance)args[args.length-1]=Type.getObjectType(OP);
  String name=MixinHandlerShim.asideName(owner.name,predicate,"$forbricconsumer");MethodNode method=new MethodNode(Opcodes.ACC_PRIVATE|Opcodes.ACC_STATIC,name,Type.getMethodDescriptor(Type.getObjectType(RESULT),args),null,null);AnnotationNode a=new AnnotationNode(WRAP),at=new AnnotationNode("Lorg/spongepowered/asm/mixin/injection/At;");at.values=new ArrayList<>(List.of("value","INVOKE","target","L"+sink.owner+";"+sink.name+sink.desc));a.values=new ArrayList<>(List.of("method",List.of(host.name+host.desc),"at",List.of(at)));method.visibleAnnotations=new ArrayList<>(List.of(a));
  method.instructions.add(new VarInsnNode(Opcodes.ALOAD,2));method.instructions.add(new InsnNode(Opcodes.ICONST_2));method.instructions.add(new TypeInsnNode(Opcodes.ANEWARRAY,"java/lang/Object"));method.instructions.add(new InsnNode(Opcodes.DUP));method.instructions.add(new InsnNode(Opcodes.ICONST_0));method.instructions.add(new VarInsnNode(Opcodes.ALOAD,0));method.instructions.add(new InsnNode(Opcodes.AASTORE));method.instructions.add(new InsnNode(Opcodes.DUP));method.instructions.add(new InsnNode(Opcodes.ICONST_1));method.instructions.add(new VarInsnNode(Opcodes.ALOAD,1));method.instructions.add(new InvokeDynamicInsnNode("test","()Ljava/util/function/Predicate;",LAMBDA,Type.getMethodType("(Ljava/lang/Object;)Z"),new Handle(Opcodes.H_INVOKESTATIC,owner.name,predicate,"(Ljava/lang/Object;)Z",false),Type.getMethodType("(Ljava/lang/Object;)Z")));method.instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC,SCOPES,"consumer","(Ljava/util/function/Consumer;Ljava/util/function/Predicate;)Ljava/util/function/Consumer;",false));method.instructions.add(new InsnNode(Opcodes.AASTORE));method.instructions.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE,OP,"call","([Ljava/lang/Object;)Ljava/lang/Object;",true));method.instructions.add(new TypeInsnNode(Opcodes.CHECKCAST,RESULT));method.instructions.add(new InsnNode(Opcodes.ARETURN));method.maxLocals=3;method.maxStack=6;return method;
 }
 private static void array(MethodNode method,Type[]args,int[]slots){integer(method.instructions,args.length);method.instructions.add(new TypeInsnNode(Opcodes.ANEWARRAY,"java/lang/Object"));for(int i=0;i<args.length;i++){method.instructions.add(new InsnNode(Opcodes.DUP));integer(method.instructions,i);method.instructions.add(new VarInsnNode(args[i].getOpcode(Opcodes.ILOAD),slots[i]));method.instructions.add(new InsnNode(Opcodes.AASTORE));}}
 private static void integer(InsnList list,int n){if(n<=5)list.add(new InsnNode(Opcodes.ICONST_0+n));else list.add(new IntInsnNode(n<=127?Opcodes.BIPUSH:Opcodes.SIPUSH,n));}
 private static int integer(AbstractInsnNode node){return node.getOpcode()>=Opcodes.ICONST_0&&node.getOpcode()<=Opcodes.ICONST_5?node.getOpcode()-Opcodes.ICONST_0:node instanceof IntInsnNode n?n.operand:-1;}
 private static List<AnnotationNode>annotations(MethodNode method){List<AnnotationNode>list=new ArrayList<>();if(method.visibleAnnotations!=null)list.addAll(method.visibleAnnotations);if(method.invisibleAnnotations!=null)list.addAll(method.invisibleAnnotations);return list;}
 private static void removeInjector(MethodNode method){if(method.visibleAnnotations!=null)method.visibleAnnotations.removeIf(a->a.desc.equals(INJECT)||a.desc.equals(WRAP));if(method.invisibleAnnotations!=null)method.invisibleAnnotations.removeIf(a->a.desc.equals(INJECT)||a.desc.equals(WRAP));}
 private static AbstractInsnNode next(AbstractInsnNode i){for(var n=i.getNext();n!=null;n=n.getNext())if(n.getOpcode()>=0)return n;return null;}
}
