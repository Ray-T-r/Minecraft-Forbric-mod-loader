/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.mixin;
import java.util.*;
import java.util.function.Function;
import net.forbric.kernel.boot.DefinedMethodContracts;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import org.objectweb.asm.tree.analysis.*;

/** Structural relation between a nullable producer and the native record that represents its alternatives. */
final class MixinNullableLookupContracts {
 record LookupProof(List<DefinedMethodContracts.MethodContract> methods,List<net.forbric.kernel.boot.KernelCompositeCallbacks.FieldContract> fields){}
 private record Context(ClassNode owner,MethodNode method,List<String> inputs){}
 private MixinNullableLookupContracts(){}
 static LookupProof prove(ClassNode sourceOwner,MethodNode sourceHost,MethodInsnNode sourceCall,ClassNode currentOwner,MethodNode currentHost,MethodInsnNode currentCall,MixinNullableCompositeCallback.Shape shape,Function<String,ClassNode> classes,Function<String,ClassNode> sourceReferences,Function<String,ClassNode> nativeReferences,Function<String,ClassNode> runtimeReferences){
  try{
   if(sourceCall.getOpcode()!=Opcodes.INVOKESTATIC||currentCall.getOpcode()!=Opcodes.INVOKESTATIC||!sourceCall.owner.equals(sourceOwner.name))return null;
   ClassNode platform=nativeReferences.apply(currentOwner.name);if(platform==null)return null;MethodNode nativeHost=method(platform,currentHost.name,currentHost.desc);if(nativeHost==null)return null;
   var liveKeys=new MixinNullableCompositeCallback.Keys(currentOwner.name,currentHost,null);List<String> actual=liveKeys.arguments(currentCall);if(actual==null)return null;
   var platformKeys=new MixinNullableCompositeCallback.Keys(platform.name,nativeHost,null);List<MethodInsnNode> sites=new ArrayList<>();
   for(var instruction:nativeHost.instructions)if(instruction instanceof MethodInsnNode call&&call.getOpcode()==currentCall.getOpcode()&&call.owner.equals(currentCall.owner)&&call.name.equals(currentCall.name)&&call.desc.equals(currentCall.desc)&&actual.equals(platformKeys.arguments(call)))sites.add(call);
   if(sites.size()!=1)return decline("native invocation correspondence "+currentCall.owner+"."+currentCall.name+currentCall.desc);
   Type nullable=Type.getReturnType(sourceCall.desc);Proof proof=new Proof(nullable,shape,classes,sourceReferences,nativeReferences,runtimeReferences);
   MethodNode root=method(sourceOwner,sourceCall.name,sourceCall.desc);List<String> original=new MixinNullableCompositeCallback.Keys(sourceOwner.name,sourceHost,null).arguments(sourceCall);if(root==null||original==null)return null;
   List<Context> leaves=new ArrayList<>();if(!proof.sourceLeaves(new Context(sourceOwner,root,original),leaves,new HashSet<>()))return decline("source nullable closure");
   ClassNode live=classes.apply(currentCall.owner);MethodNode producer=live==null?null:method(live,currentCall.name,currentCall.desc);if(producer==null||!proof.nativeClosure(new Context(live,producer,actual),new HashSet<>()))return decline("native producer closure");
   for(Context leaf:leaves)if(!proof.corresponds(leaf))return decline("unmatched nullable production "+leaf.method.name+leaf.inputs+" projected="+proof.projected+" primitives="+proof.originalNullableCalls);
   proof.fieldOwners.put(shape.type().name,shape.type());List<net.forbric.kernel.boot.KernelCompositeCallbacks.FieldContract> fields=new ArrayList<>();for(var owner:proof.fieldOwners.values())for(var field:owner.fields)fields.add(new net.forbric.kernel.boot.KernelCompositeCallbacks.FieldContract(owner.name,field.name,field.desc,field.access));return new LookupProof(List.copyOf(proof.contracts.values()),List.copyOf(fields));
  }catch(AnalyzerException|RuntimeException unknown){return decline(unknown.toString());}
 }
 private static LookupProof decline(String reason){if(Boolean.getBoolean("forbric.mixinNullableCompositeDebug"))net.forbric.kernel.util.ForbricLog.debug("[Forbric/Mixin] nullable production proof declined: %s",reason);return null;}
 private static final class Proof{
  final Type nullable;final MixinNullableCompositeCallback.Shape shape;final Function<String,ClassNode> classes,sourceReferences,nativeReferences,runtimeReferences;
  final Map<String,DefinedMethodContracts.MethodContract> contracts=new TreeMap<>();final List<Context> nativeContexts=new ArrayList<>();final Set<String> projected=new HashSet<>();final Set<String> originalNullableCalls=new HashSet<>();
  final Map<String,ClassNode> fieldOwners=new TreeMap<>();
  Proof(Type nullable,MixinNullableCompositeCallback.Shape shape,Function<String,ClassNode> classes,Function<String,ClassNode> sourceReferences,Function<String,ClassNode> nativeReferences,Function<String,ClassNode> runtimeReferences){this.nullable=nullable;this.shape=shape;this.classes=classes;this.sourceReferences=sourceReferences;this.nativeReferences=nativeReferences;this.runtimeReferences=runtimeReferences;}
  ClassNode original(String owner){ClassNode node=nativeReferences.apply(owner);return node!=null?node:runtimeReferences.apply(owner);}
  boolean sourceLeaves(Context context,List<Context> leaves,Set<String> active)throws AnalyzerException{
   String identity=context.owner.name+context.method.name+context.method.desc+context.inputs;if(!active.add(identity))return false;
   try{var keys=keys(context);boolean delegated=false;
    for(var instruction:context.method.instructions)if(instruction instanceof MethodInsnNode call&&call.getOpcode()==Opcodes.INVOKESTATIC&&call.owner.equals(context.owner.name)&&Type.getReturnType(call.desc).equals(nullable)){
     MethodNode target=method(context.owner,call.name,call.desc);List<String> inputs=keys.arguments(call);if(target==null||inputs==null)return false;delegated=true;if(!sourceLeaves(new Context(context.owner,target,inputs),leaves,active))return false;
    }
    if(!delegated)leaves.add(context);return true;
   }finally{active.remove(identity);}
  }
  boolean nativeClosure(Context context,Set<String> active)throws AnalyzerException{
   String identity=context.owner.name+context.method.name+context.method.desc+context.inputs;if(active.contains(identity))return false;
   if(nativeContexts.stream().anyMatch(c->c.equals(context)))return true;active.add(identity);
   try{ClassNode raw=original(context.owner.name);MethodNode expected=raw==null?null:method(raw,context.method.name,context.method.desc);
    if(expected==null||(expected.access&Opcodes.ACC_STATIC)==0||expected.access!=context.method.access||!MixinInstructionFingerprint.hash(expected).equals(MixinInstructionFingerprint.hash(context.method)))return false;
    contracts.put(context.owner.name+context.method.name+context.method.desc,contract(context.owner,context.method));fieldOwners.put(context.owner.name,context.owner);nativeContexts.add(context);var keys=keys(context);
    for(var instruction:context.method.instructions){
     if(instruction instanceof MethodInsnNode call&&call.getOpcode()==Opcodes.INVOKESPECIAL&&call.owner.equals(shape.type().name)&&call.name.equals("<init>")){
      int field=constructorField(call.desc);if(field<0)return false;Frame<SourceValue> at=keys.frames[context.method.instructions.indexOf(call)];int start=at.getStackSize()-Type.getArgumentTypes(call.desc).length;String expression=keys.key(at.getStack(start+field),-1,new HashSet<>());if(expression!=null)projected.add(expression);
     }
     if(instruction instanceof MethodInsnNode call&&call.getOpcode()==Opcodes.INVOKESTATIC){ClassNode rawOwner=original(call.owner);if(rawOwner==null)continue;ClassNode current=classes.apply(call.owner);MethodNode next=current==null?null:method(current,call.name,call.desc);List<String> inputs=keys.arguments(call);
      if(next==null)return false;
      if(Type.getReturnType(call.desc).equals(nullable)){ClassNode source=sourceReferences.apply(call.owner);MethodNode old=source==null?null:method(source,call.name,call.desc);if(old!=null&&inputs!=null&&sameLocalDataflow(source.name,old,current.name,next)){originalNullableCalls.add(symbol(call.owner,call.name,call.desc,inputs));MethodNode rawMethod=method(rawOwner,call.name,call.desc);if(rawMethod==null||!MixinInstructionFingerprint.hash(rawMethod).equals(MixinInstructionFingerprint.hash(next)))return false;contracts.put(current.name+next.name+next.desc,contract(current,next));continue;}}
      if(inputs==null){if(Type.getReturnType(call.desc).equals(Type.getObjectType(shape.type().name)))return false;inputs=new ArrayList<>();for(int i=0;i<Type.getArgumentTypes(call.desc).length;i++)inputs.add("native:"+current.name+next.name+":"+i);}
      if(!nativeClosure(new Context(current,next,inputs),active))return false;
     }
     if(instruction instanceof InvokeDynamicInsnNode dynamic){if(!dynamic.bsm.getOwner().equals("java/lang/invoke/LambdaMetafactory"))continue;
      for(Object argument:dynamic.bsmArgs)if(argument instanceof Handle handle&&handle.getTag()==Opcodes.H_INVOKESTATIC){ClassNode rawOwner=original(handle.getOwner());if(rawOwner==null)continue;ClassNode current=classes.apply(handle.getOwner());MethodNode next=current==null?null:method(current,handle.getName(),handle.getDesc());if(next==null)return false;
       // Predicate captures are additional native context. Its element parameter is bound separately by
       // the domain proof; method closure/final fingerprints cover the whole callback implementation.
       List<String> inputs=new ArrayList<>();for(int i=0;i<Type.getArgumentTypes(next.desc).length;i++)inputs.add("predicate:"+i);if(!nativeClosure(new Context(current,next,inputs),active))return false;
      }
     }
    }return true;
   }finally{active.remove(identity);}
  }
  int constructorField(String descriptor)throws AnalyzerException{
   MethodNode constructor=method(shape.type(),"<init>",descriptor);if(constructor==null)return -1;Type[] args=Type.getArgumentTypes(descriptor);int selected=-1,slot=1;Map<Integer,Integer> parameters=new HashMap<>();for(int i=0;i<args.length;i++){parameters.put(slot,i);slot+=args[i].getSize();}
   var frames=new Analyzer<>(new SourceInterpreter()).analyze(shape.type().name,constructor);
   for(var instruction:constructor.instructions)if(instruction instanceof FieldInsnNode field&&field.getOpcode()==Opcodes.PUTFIELD&&field.owner.equals(shape.type().name)&&field.desc.equals(nullable.getDescriptor())){
    if(selected!=-1)return -1;Frame<SourceValue> at=frames[constructor.instructions.indexOf(field)];SourceValue value=at.getStack(at.getStackSize()-1);if(value.insns.size()!=1||!(value.insns.iterator().next()instanceof VarInsnNode load)||load.getOpcode()!=Opcodes.ALOAD||!parameters.containsKey(load.var))return -1;selected=parameters.get(load.var);
   }return selected;
  }
  boolean corresponds(Context source)throws AnalyzerException{
   String opaque=symbol(source.owner.name,source.method.name,source.method.desc,source.inputs);if(originalNullableCalls.contains(opaque)&&projected.contains(opaque))return true;
   var code=code(source.method);if(code.size()==3&&code.get(0)instanceof VarInsnNode&&code.get(1)instanceof FieldInsnNode&&code.get(2).getOpcode()==Opcodes.ARETURN){var keys=keys(source);var at=keys.frames[source.method.instructions.indexOf(code.get(2))];String field=keys.key(at.getStack(at.getStackSize()-1),-1,new HashSet<>());if(field!=null&&projected.contains(field))return true;}
   for(Context nativeContext:nativeContexts)if(entityProjection(source,nativeContext,this))return true;
   return false;
  }
 }
 private static MixinNullableCompositeCallback.Keys keys(Context context)throws AnalyzerException{Map<Integer,String> parameters=new HashMap<>();int slot=0;Type[]args=Type.getArgumentTypes(context.method.desc);if(args.length!=context.inputs.size())throw new IllegalArgumentException();for(int i=0;i<args.length;i++){parameters.put(slot,context.inputs.get(i));slot+=args[i].getSize();}return new MixinNullableCompositeCallback.Keys(context.owner.name,context.method,parameters);}
 private static DefinedMethodContracts.MethodContract contract(ClassNode owner,MethodNode method){return new DefinedMethodContracts.MethodContract(owner.name,method.name,method.desc,MixinInstructionFingerprint.hash(method));}
 private static String symbol(String owner,String name,String desc,List<String> inputs){return "call:"+owner+":"+name+desc+"("+String.join(",",inputs)+")";}
 private static MethodNode method(ClassNode owner,String name,String desc){return owner.methods.stream().filter(m->m.name.equals(name)&&m.desc.equals(desc)).findFirst().orElse(null);}
 private static List<AbstractInsnNode> code(MethodNode method){return DefaultMethodOverloadBridge.real(method);}
 /** The native query keeps the same spatial collection and random selection, extends its predicate domain,
  * and projects a selected original-domain object unchanged into the nullable record field. Native-only
  * alternatives and their selection/cancellation remain the platform's authoritative control flow. */
 private static boolean entityProjection(Context source,Context nativeContext,Proof proof){
  try{
   if(!Type.getReturnType(nativeContext.method.desc).equals(Type.getObjectType(proof.shape.type().name))||!source.method.tryCatchBlocks.isEmpty()||!nativeContext.method.tryCatchBlocks.isEmpty())return false;
   Type[] oldArgs=Type.getArgumentTypes(source.method.desc),newArgs=Type.getArgumentTypes(nativeContext.method.desc);if(oldArgs.length>newArgs.length||!Arrays.equals(oldArgs,Arrays.copyOf(newArgs,oldArgs.length))||!source.inputs.equals(nativeContext.inputs.subList(0,source.inputs.size())))return false;
   var old=code(source.method);var live=code(nativeContext.method);int oldQuery=query(old),newQuery=query(live);if(oldQuery<1||newQuery<1||!same(old.get(oldQuery),live.get(newQuery),Map.of()))return false;
   if(!(old.get(oldQuery-1)instanceof FieldInsnNode predicate)||predicate.getOpcode()!=Opcodes.GETSTATIC||!predicate.desc.equals("Ljava/util/function/Predicate;")||!(live.get(newQuery-1)instanceof InvokeDynamicInsnNode dynamic)||!dynamic.desc.endsWith("Ljava/util/function/Predicate;"))return false;
   int captured=Type.getArgumentTypes(dynamic.desc).length,newPrefix=newQuery-1-captured;if(oldQuery-1!=newPrefix)return false;
   for(int i=0;i<newPrefix;i++)if(!same(old.get(i),live.get(i),Map.of()))return false;
   if(old.size()!=oldQuery+16||!(old.get(oldQuery+1)instanceof VarInsnNode oldList)||oldList.getOpcode()!=Opcodes.ASTORE||!(live.get(newQuery+1)instanceof VarInsnNode newList)||newList.getOpcode()!=Opcodes.ASTORE)return false;
   Map<Integer,Integer> locals=Map.of(oldList.var,newList.var);for(int i=1;i<=11;i++)if(!same(old.get(oldQuery+i),live.get(newQuery+i),locals))return false;
   if(!(old.get(oldQuery+12)instanceof TypeInsnNode cast)||cast.getOpcode()!=Opcodes.CHECKCAST||!cast.desc.equals(proof.nullable.getInternalName())||old.get(oldQuery+13).getOpcode()!=Opcodes.ARETURN||old.get(oldQuery+14).getOpcode()!=Opcodes.ACONST_NULL||old.get(oldQuery+15).getOpcode()!=Opcodes.ARETURN
     ||!(old.get(oldQuery+4)instanceof JumpInsnNode empty)||empty.getOpcode()!=Opcodes.IFNE||next(empty.label)!=old.get(oldQuery+14))return false;
   int p=newQuery+12;if(!(live.get(p++)instanceof TypeInsnNode entity)||entity.getOpcode()!=Opcodes.CHECKCAST||!(live.get(p++)instanceof VarInsnNode selected)||selected.getOpcode()!=Opcodes.ASTORE
     ||!load(live.get(p++),selected.var)||!(live.get(p++)instanceof TypeInsnNode test)||test.getOpcode()!=Opcodes.INSTANCEOF||!test.desc.equals(proof.nullable.getInternalName())||!(live.get(p++)instanceof JumpInsnNode other)||other.getOpcode()!=Opcodes.IFEQ
     ||!load(live.get(p++),selected.var)||!(live.get(p++)instanceof TypeInsnNode narrowed)||narrowed.getOpcode()!=Opcodes.CHECKCAST||!narrowed.desc.equals(proof.nullable.getInternalName())||!(live.get(p++)instanceof VarInsnNode container)||container.getOpcode()!=Opcodes.ASTORE
     ||!(live.get(p++)instanceof TypeInsnNode allocation)||allocation.getOpcode()!=Opcodes.NEW||!allocation.desc.equals(proof.shape.type().name)||live.get(p++).getOpcode()!=Opcodes.DUP)return false;
   int argumentsAt=p;while(p<live.size()&&!(live.get(p)instanceof MethodInsnNode constructor&&constructor.getOpcode()==Opcodes.INVOKESPECIAL&&constructor.owner.equals(proof.shape.type().name)&&constructor.name.equals("<init>")))p++;
   if(p>=live.size()-1||live.get(p+1).getOpcode()!=Opcodes.ARETURN)return false;MethodInsnNode constructor=(MethodInsnNode)live.get(p);int field=proof.constructorField(constructor.desc);Type[] fields=Type.getArgumentTypes(constructor.desc);if(field<0||p-argumentsAt!=fields.length)return false;
   for(int i=0;i<fields.length;i++)if(i==field?!load(live.get(argumentsAt+i),container.var):live.get(argumentsAt+i).getOpcode()!=Opcodes.ACONST_NULL)return false;
   if(next(other.label)!=live.get(p+2)||!(live.get(newQuery+4)instanceof JumpInsnNode absent)||absent.getOpcode()!=Opcodes.IFNE||!(next(absent.label)instanceof FieldInsnNode emptyRecord)||emptyRecord.getOpcode()!=Opcodes.GETSTATIC||!emptyRecord.owner.equals(proof.shape.type().name)||!emptyRecord.desc.equals("L"+proof.shape.type().name+";")||next(emptyRecord).getOpcode()!=Opcodes.ARETURN||!emptyConstant(proof.shape.type(),emptyRecord))return false;
   ClassNode selector=proof.sourceReferences.apply(predicate.owner),currentSelector=proof.classes.apply(predicate.owner);if(selector==null||currentSelector==null)return false;FieldNode selectorField=currentSelector.fields.stream().filter(f->f.name.equals(predicate.name)&&f.desc.equals(predicate.desc)).findFirst().orElse(null);if(selectorField==null||(selectorField.access&(Opcodes.ACC_STATIC|Opcodes.ACC_FINAL))!=(Opcodes.ACC_STATIC|Opcodes.ACC_FINAL))return false;MethodNode initializer=method(selector,"<clinit>","()V"),currentInitializer=method(currentSelector,"<clinit>","()V");if(initializer==null||currentInitializer==null||!MixinInstructionFingerprint.hash(initializer).equals(MixinInstructionFingerprint.hash(currentInitializer)))return false;
   InvokeDynamicInsnNode factory=null;for(var instruction:initializer.instructions)if(instruction instanceof FieldInsnNode put&&put.getOpcode()==Opcodes.PUTSTATIC&&put.owner.equals(predicate.owner)&&put.name.equals(predicate.name)&&put.desc.equals(predicate.desc)){if(factory!=null||!(previous(put)instanceof InvokeDynamicInsnNode made))return false;factory=made;}
   Handle oldImplementation=implementation(factory),newImplementation=implementation(dynamic);if(oldImplementation==null||newImplementation==null||Type.getArgumentTypes(factory.desc).length!=0)return false;
   ClassNode oldType=proof.sourceReferences.apply(oldImplementation.getOwner()),newType=proof.classes.apply(newImplementation.getOwner());MethodNode oldBody=oldType==null?null:method(oldType,oldImplementation.getName(),oldImplementation.getDesc()),newBody=newType==null?null:method(newType,newImplementation.getName(),newImplementation.getDesc());if(oldBody==null||newBody==null||!predicateDomain(oldBody,newBody))return false;
   ClassNode currentOldType=proof.classes.apply(oldImplementation.getOwner());MethodNode currentOld=currentOldType==null?null:method(currentOldType,oldBody.name,oldBody.desc);if(currentOld==null||!MixinInstructionFingerprint.hash(oldBody).equals(MixinInstructionFingerprint.hash(currentOld)))return false;
   proof.contracts.put(currentSelector.name+currentInitializer.name+currentInitializer.desc,contract(currentSelector,currentInitializer));proof.contracts.put(currentOldType.name+currentOld.name+currentOld.desc,contract(currentOldType,currentOld));proof.fieldOwners.put(currentSelector.name,currentSelector);proof.fieldOwners.put(currentOldType.name,currentOldType);return true;
  }catch(AnalyzerException|RuntimeException unknown){return false;}
 }
 private static boolean emptyConstant(ClassNode type,FieldInsnNode field){MethodNode init=method(type,"<clinit>","()V");if(init==null)return false;int writes=0;for(var instruction:init.instructions)if(instruction instanceof FieldInsnNode put&&put.getOpcode()==Opcodes.PUTSTATIC&&put.owner.equals(field.owner)&&put.name.equals(field.name)&&put.desc.equals(field.desc)){
   writes++;if(!(previous(put)instanceof MethodInsnNode constructor)||constructor.getOpcode()!=Opcodes.INVOKESPECIAL||!constructor.owner.equals(type.name)||!constructor.name.equals("<init>"))return false;var value=previous(constructor);for(Type argument:Type.getArgumentTypes(constructor.desc)){if(argument.getSort()!=Type.OBJECT&&argument.getSort()!=Type.ARRAY||value.getOpcode()!=Opcodes.ACONST_NULL)return false;value=previous(value);}if(value.getOpcode()!=Opcodes.DUP||!(previous(value)instanceof TypeInsnNode allocation)||allocation.getOpcode()!=Opcodes.NEW||!allocation.desc.equals(type.name))return false;
  }return writes==1;}
 private static int query(List<AbstractInsnNode> code){int found=-1;for(int i=0;i<code.size();i++)if(code.get(i)instanceof MethodInsnNode call&&call.getOpcode()==Opcodes.INVOKEVIRTUAL&&Type.getReturnType(call.desc).equals(Type.getObjectType("java/util/List"))){if(found!=-1)return -1;found=i;}return found;}
 private static boolean same(AbstractInsnNode old,AbstractInsnNode live,Map<Integer,Integer> locals){
  if(old.getOpcode()!=live.getOpcode())return false;
  if(old instanceof VarInsnNode a)return live instanceof VarInsnNode b&&locals.getOrDefault(a.var,a.var)==b.var;
  if(old instanceof MethodInsnNode a)return live instanceof MethodInsnNode b&&a.owner.equals(b.owner)&&a.name.equals(b.name)&&a.desc.equals(b.desc)&&a.itf==b.itf;
  if(old instanceof FieldInsnNode a)return live instanceof FieldInsnNode b&&a.owner.equals(b.owner)&&a.name.equals(b.name)&&a.desc.equals(b.desc);
  if(old instanceof TypeInsnNode a)return live instanceof TypeInsnNode b&&a.desc.equals(b.desc);
  if(old instanceof LdcInsnNode a)return live instanceof LdcInsnNode b&&Objects.equals(a.cst,b.cst);
  if(old instanceof IntInsnNode a)return live instanceof IntInsnNode b&&a.operand==b.operand;
  return old instanceof InsnNode&&live instanceof InsnNode||old instanceof JumpInsnNode&&live instanceof JumpInsnNode;
 }
 private static Handle implementation(InvokeDynamicInsnNode dynamic){if(dynamic==null||!dynamic.bsm.getOwner().equals("java/lang/invoke/LambdaMetafactory")||!dynamic.bsm.getName().equals("metafactory")||dynamic.bsmArgs.length!=3||!(dynamic.bsmArgs[1]instanceof Handle handle)||handle.getTag()!=Opcodes.H_INVOKESTATIC)return null;return handle;}
 private static AbstractInsnNode previous(AbstractInsnNode node){for(var i=node.getPrevious();i!=null;i=i.getPrevious())if(i.getOpcode()>=0)return i;return null;}
 private static AbstractInsnNode next(AbstractInsnNode node){for(var i=node.getNext();i!=null;i=i.getNext())if(i.getOpcode()>=0)return i;return null;}
 private static boolean load(AbstractInsnNode node,int slot){return node instanceof VarInsnNode load&&load.getOpcode()==Opcodes.ALOAD&&load.var==slot;}
 /** Compiler temporary allocation may differ while every executable instruction and value origin agrees.
  * Compare loads against their aligned defining stores, rather than erasing local numbers indiscriminately. */
 private static boolean sameLocalDataflow(String oldOwner,MethodNode old,String liveOwner,MethodNode live)throws AnalyzerException{
  if(!old.desc.equals(live.desc)||((old.access^live.access)&Opcodes.ACC_STATIC)!=0||!old.tryCatchBlocks.isEmpty()||!live.tryCatchBlocks.isEmpty())return false;
  var a=code(old);var b=code(live);if(a.size()!=b.size())return false;var oldFrames=new Analyzer<>(new SourceInterpreter()).analyze(oldOwner,old);var liveFrames=new Analyzer<>(new SourceInterpreter()).analyze(liveOwner,live);
  for(int i=0;i<a.size();i++){AbstractInsnNode left=a.get(i),right=b.get(i);if(left.getOpcode()!=right.getOpcode())return false;
   if(left instanceof VarInsnNode x&&right instanceof VarInsnNode y){if(x.getOpcode()>=Opcodes.ILOAD&&x.getOpcode()<=Opcodes.ALOAD){Frame<SourceValue> before=oldFrames[old.instructions.indexOf(x)],after=liveFrames[live.instructions.indexOf(y)];if(before==null||after==null||!origins(before.getLocal(x.var),a,x.var,old).equals(origins(after.getLocal(y.var),b,y.var,live)))return false;}else if(x.getOpcode()<Opcodes.ISTORE||x.getOpcode()>Opcodes.ASTORE)return false;continue;}
   if(left instanceof JumpInsnNode x&&right instanceof JumpInsnNode y){if(a.indexOf(next(x.label))!=b.indexOf(next(y.label)))return false;continue;}
   if(!same(left,right,Map.of()))return false;
  }return true;
 }
 private static Set<String> origins(SourceValue value,List<AbstractInsnNode> code,int local,MethodNode method){Set<String> origins=new TreeSet<>();if(value.insns.isEmpty()){int slot=(method.access&Opcodes.ACC_STATIC)==0?1:0,index=0;if(slot==1&&local==0)origins.add("this");for(Type arg:Type.getArgumentTypes(method.desc)){if(slot==local)origins.add("parameter:"+index);slot+=arg.getSize();index++;}if(origins.isEmpty())origins.add("uninitialized:"+local);return origins;}for(var instruction:value.insns)origins.add("instruction:"+code.indexOf(instruction));return origins;}
 private static boolean predicateDomain(MethodNode old,MethodNode live){
  if((old.access&Opcodes.ACC_STATIC)==0||(live.access&Opcodes.ACC_STATIC)==0||Type.getArgumentTypes(old.desc).length!=1||!old.tryCatchBlocks.isEmpty()||!live.tryCatchBlocks.isEmpty())return false;
  Type element=Type.getArgumentTypes(old.desc)[0];Type[] args=Type.getArgumentTypes(live.desc);int elementSlot=-1,slot=0;for(Type arg:args){if(arg.equals(element)){if(elementSlot!=-1)return false;elementSlot=slot;}slot+=arg.getSize();}if(elementSlot<0)return false;
  Set<String> atoms=new TreeSet<>();for(int pass=0;pass<9;pass++){List<String> dimensions=List.copyOf(atoms);if(dimensions.size()>8)return false;for(int mask=0;mask<(1<<dimensions.size());mask++){Map<String,Boolean> values=new HashMap<>();for(int i=0;i<dimensions.size();i++)values.put(dimensions.get(i),(mask&(1<<i))!=0);Boolean before=evaluate(old,0,values,atoms),after=evaluate(live,elementSlot,values,atoms);if(before==null||after==null)return false;if(atoms.size()==dimensions.size()&&before&&!after)return false;}if(atoms.size()==dimensions.size())return true;}return false;
 }
 /** A bounded truth interpreter proves domain inclusion for every opaque predicate/capability verdict. It
  * never invokes a getter and assigns no meaning based on a game member's name. */
 private static Boolean evaluate(MethodNode method,int element,Map<String,Boolean> values,Set<String> atoms){
  var code=code(method);if(code.size()>64)return null;Map<Integer,Object> locals=new HashMap<>();int slot=0,index=0;for(Type type:Type.getArgumentTypes(method.desc)){locals.put(slot,slot==element?"element":"context:"+index++);slot+=type.getSize();}List<Object> stack=new ArrayList<>();int pc=0,steps=0;
  while(pc>=0&&pc<code.size()&&steps++<128){var instruction=code.get(pc);int opcode=instruction.getOpcode();
   if(instruction instanceof VarInsnNode variable){if(opcode==Opcodes.ALOAD){Object value=locals.get(variable.var);if(value==null)return null;stack.add(value);}else if(opcode==Opcodes.ASTORE)locals.put(variable.var,stack.removeLast());else return null;}
   else if(instruction instanceof TypeInsnNode type){if(opcode==Opcodes.INSTANCEOF){Object value=stack.removeLast();stack.add(atom("instance:"+type.desc+"("+value+")",values,atoms));}else if(opcode!=Opcodes.CHECKCAST)return null;}
   else if(instruction instanceof FieldInsnNode field){if(opcode==Opcodes.GETSTATIC)stack.add("field:"+field.owner+":"+field.name+field.desc);else if(opcode==Opcodes.GETFIELD)stack.add("field:"+field.owner+":"+field.name+field.desc+"("+stack.removeLast()+")");else return null;}
   else if(instruction instanceof MethodInsnNode call){Type[] args=Type.getArgumentTypes(call.desc);List<Object> passed=new ArrayList<>();for(int i=0;i<args.length;i++)passed.addFirst(stack.removeLast());if(opcode!=Opcodes.INVOKESTATIC)passed.addFirst(stack.removeLast());String symbol="call:"+call.owner+":"+call.name+call.desc+passed;Type result=Type.getReturnType(call.desc);if(result.equals(Type.BOOLEAN_TYPE))stack.add(atom(symbol,values,atoms));else if(result.getSort()==Type.OBJECT||result.getSort()==Type.ARRAY)stack.add(symbol);else return null;}
   else if(instruction instanceof JumpInsnNode jump){boolean taken;if(opcode==Opcodes.GOTO)taken=true;else{Object value=stack.removeLast();if(opcode==Opcodes.IFEQ||opcode==Opcodes.IFNE){if(!(value instanceof Boolean bool))return null;taken=bool==(opcode==Opcodes.IFNE);}else if(opcode==Opcodes.IFNULL||opcode==Opcodes.IFNONNULL){boolean nonnull=atom("nonnull:"+value,values,atoms);taken=nonnull==(opcode==Opcodes.IFNONNULL);}else return null;}if(taken){pc=code.indexOf(next(jump.label));continue;}}
   else if(opcode==Opcodes.ICONST_0||opcode==Opcodes.ICONST_1)stack.add(opcode==Opcodes.ICONST_1);
   else if(opcode==Opcodes.ACONST_NULL)stack.add("null");
   else if(opcode==Opcodes.IRETURN){if(stack.size()!=1||!(stack.getFirst()instanceof Boolean answer))return null;return answer;}
   else return null;pc++;
  }return null;
 }
 private static boolean atom(String symbol,Map<String,Boolean> values,Set<String> atoms){atoms.add(symbol);return values.getOrDefault(symbol,false);}
}
