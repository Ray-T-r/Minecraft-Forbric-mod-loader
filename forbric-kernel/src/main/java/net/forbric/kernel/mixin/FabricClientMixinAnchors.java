/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.mixin;
import java.util.*;
import java.util.function.Function;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import org.objectweb.asm.tree.analysis.*;
/** Proved map removal, and any empty redirect of a call the carrier widened, discovered from source contracts without private SDK names. */
public final class FabricClientMixinAnchors {
 public static final String PROPERTY="forbric.fabricClientAnchors";
 private FabricClientMixinAnchors(){}
 public static int adapt(ClassNode mixin,Function<String,ClassNode> targets){
  if("off".equalsIgnoreCase(System.getProperty(PROPERTY,"on")))return 0;
  return removal(mixin,targets)+render(mixin,targets);
 }
 private static int removal(ClassNode mixin,Function<String,ClassNode> targets){
  String owner="net/minecraft/world/level/chunk/LevelChunk",desc="(Lnet/minecraft/core/BlockPos;L"+owner+"$EntityCreationType;)Lnet/minecraft/world/level/block/entity/BlockEntity;";
  if(!MixinCallbackShape.targets(mixin,owner))return 0;
  MethodNode handler=MixinCallbackShape.unique(mixin,method->method.desc.equals("(Ljava/util/Map;Ljava/lang/Object;)Ljava/lang/Object;")&&MixinFit.injectorOf(method)!=null&&MixinFit.injectorOf(method).desc.equals("Lorg/spongepowered/asm/mixin/injection/Redirect;"));
  if(handler==null||group(handler))return 0;ClassNode target=targets.apply(owner); // the handler first, then the target's code
  if(target==null)return 0;MethodNode host=find(target,"getBlockEntity",desc);if(host==null)return 0;
  AnnotationNode injector=MixinFit.injectorOf(handler);if(injector==null||!injector.desc.equals("Lorg/spongepowered/asm/mixin/injection/Redirect;"))return 0;
  if(!MixinFit.stringList(MixinFit.value(injector,"method")).equals(List.of("getBlockEntity"+desc)))return 0;
  Object slice=MixinFit.value(injector,"slice");
  if(!(slice instanceof AnnotationNode sliced)||!(MixinFit.value(sliced,"from") instanceof AnnotationNode from)
    ||!("L"+owner+";createBlockEntity(Lnet/minecraft/core/BlockPos;)Lnet/minecraft/world/level/block/entity/BlockEntity;").equals(MixinFit.value(from,"target")))return 0;
  List<AnnotationNode> ats=MixinFit.atNodes(injector).stream().filter(a->"Ljava/util/Map;remove(Ljava/lang/Object;)Ljava/lang/Object;".equals(MixinFit.value(a,"target"))).toList();if(ats.size()!=1)return 0;
  Object originalOrdinal=MixinFit.value(ats.getFirst(),"ordinal");if(originalOrdinal!=null&&!Integer.valueOf(0).equals(originalOrdinal))return 0;
  try{
   Frame<SourceValue>[] frames=new Analyzer<>(new SourceInterpreter()).analyze(owner,host);int ordinal=0,selected=-1,matches=0,selectedInstruction=-1,factory=-1,factories=0;
   for(var instruction:host.instructions)if(instruction instanceof MethodInsnNode call&&call.owner.equals(owner)&&call.name.equals("createBlockEntity")&&call.desc.equals("(Lnet/minecraft/core/BlockPos;)Lnet/minecraft/world/level/block/entity/BlockEntity;")){factory=host.instructions.indexOf(instruction);factories++;}
   for(AbstractInsnNode instruction:host.instructions)if(instruction instanceof MethodInsnNode call&&call.owner.equals("java/util/Map")&&call.name.equals("remove")&&call.desc.equals("(Ljava/lang/Object;)Ljava/lang/Object;")){
    Frame<SourceValue> frame=frames[host.instructions.indexOf(instruction)];
    if(frame!=null&&frame.getStackSize()>=2){SourceValue receiver=frame.getStack(frame.getStackSize()-2);if(receiver.insns.size()==1&&receiver.insns.iterator().next() instanceof FieldInsnNode field&&field.getOpcode()==Opcodes.GETFIELD&&field.owner.equals(owner)&&field.name.equals("blockEntities")&&field.desc.equals("Ljava/util/Map;")){selected=ordinal;selectedInstruction=host.instructions.indexOf(instruction);matches++;}}
    ordinal++;
   }
   if(matches!=1||factories!=1||selectedInstruction>=factory)return 0;set(ats.getFirst(),"ordinal",selected);remove(injector,"slice");return 1;
  }catch(AnalyzerException malformed){return 0;}
 }
 /**
  * A {@code @Redirect} whose handler is nothing but {@code return}, on a void call the surviving carrier widened.
  *
  * <p>Such a redirect says "this call does not happen here". When the carrier replaced the call in the selected body
  * with the same method taking more context (NeoForge's {@code collectParts(BlockAndTintGetter, BlockPos, BlockState,
  * RandomSource, List)} for vanilla's {@code collectParts(RandomSource, List)}), the replaced call is the one to skip, and
  * since the handler reads none of its arguments it can take the wider argument list unchanged. Any mixin, any target
  * and any host its own selector names: the call the guest named must be absent from every selected body, and each must
  * make exactly one call of one widened descriptor of it (same owner, name, kind and void return, the named parameters
  * an ordered part of the wider list). A handler with a body, a value to return, a group or a slice is not touched.
  */
 private static int render(ClassNode mixin,Function<String,ClassNode> targets){
  // The handlers first: resolving a target with code is the expensive part, and almost no mixin has an empty redirect.
  List<EmptyRedirect> empty=new ArrayList<>();
  for(MethodNode handler:mixin.methods){
   if(!MixinCallbackShape.kind(handler,"Redirect")||group(handler))continue;
   List<AbstractInsnNode> code=new ArrayList<>();for(var i:handler.instructions)if(i.getOpcode()>=0)code.add(i);
   if(code.size()!=1||code.getFirst().getOpcode()!=Opcodes.RETURN)continue;
   AnnotationNode redirect=MixinFit.injectorOf(handler);List<AnnotationNode> ats=MixinFit.atNodes(redirect);
   if(ats.size()!=1||!"INVOKE".equals(MixinFit.value(ats.getFirst(),"value")))continue;
   AnnotationNode at=ats.getFirst();boolean plain=true;
   for(String key:List.of("ordinal","shift","by","args","opcode"))if(MixinFit.value(at,key)!=null)plain=false;
   MixinAtWidenedCall.Member named=MixinAtWidenedCall.parse((String)MixinFit.value(at,"target"));
   if(!plain||named==null||Type.getReturnType(named.descriptor()).getSort()!=Type.VOID)continue;
   empty.add(new EmptyRedirect(handler,redirect,at,named));
  }
  if(empty.isEmpty())return 0;
  List<ClassNode> classes=new ArrayList<>();
  for(String name:MixinFit.mixinTargets(mixin)){ClassNode target=targets.apply(name);if(target!=null)classes.add(target);}
  if(classes.isEmpty())return 0;
  int moved=0;
  for(EmptyRedirect candidate:empty){
   MethodNode handler=candidate.handler();AnnotationNode redirect=candidate.redirect(),at=candidate.at();MixinAtWidenedCall.Member named=candidate.named();
   List<MethodNode> hosts=new ArrayList<>();
   for(String selector:MixinFit.stringList(MixinFit.value(redirect,"method")))for(ClassNode target:classes){MethodNode host=selected(target,selector);if(host!=null&&!hosts.contains(host))hosts.add(host);}
   if(hosts.isEmpty())continue;
   MethodInsnNode wide=null;boolean fits=true;
   for(MethodNode host:hosts){
    int matches=0;
    for(var i:host.instructions)if(i instanceof MethodInsnNode c&&c.owner.equals(named.owner())&&c.name.equals(named.name())){
     if(c.desc.equals(named.descriptor())){fits=false;break;}
     if(!within(named.descriptor(),c.desc))continue;
     if(wide!=null&&(!wide.desc.equals(c.desc)||wide.getOpcode()!=c.getOpcode())){fits=false;break;}
     wide=c;matches++;
    }
    if(matches!=1)fits=false;
    if(!fits)break;
   }
   if(!fits||wide==null)continue;
   boolean instance=wide.getOpcode()!=Opcodes.INVOKESTATIC;
   Type[] params=Type.getArgumentTypes(handler.desc),own=Type.getArgumentTypes(named.descriptor()),widened=Type.getArgumentTypes(wide.desc);
   int lead=instance?1:0;if(params.length<lead+own.length)continue;
   boolean describes=true;for(int i=0;i<own.length;i++)if(!params[lead+i].equals(own[i]))describes=false;
   if(!describes)continue;
   List<Type> shaped=new ArrayList<>();if(instance)shaped.add(params[0]);shaped.addAll(List.of(widened));
   for(int i=lead+own.length;i<params.length;i++)shaped.add(params[i]); // the host's arguments the handler captured
   set(at,"target","L"+named.owner()+";"+named.name()+wide.desc);
   handler.desc=Type.getMethodDescriptor(Type.VOID_TYPE,shaped.toArray(Type[]::new));handler.signature=null;handler.parameters=null;
   handler.visibleParameterAnnotations=null;handler.invisibleParameterAnnotations=null;handler.visibleAnnotableParameterCount=0;handler.invisibleAnnotableParameterCount=0;
   handler.localVariables=null;handler.maxLocals=(Type.getArgumentsAndReturnSizes(handler.desc)>>2)-((handler.access&Opcodes.ACC_STATIC)!=0?1:0);
   net.forbric.kernel.util.ForbricLog.info("[Forbric/Mixin] %s.%s: an empty @Redirect of %s.%s%s now skips the carrier's %s%s in %s, the call that "
     +"replaced it",mixin.name.replace('/','.'),handler.name,named.owner().replace('/','.'),named.name(),named.descriptor(),named.name(),wide.desc,
     hosts.stream().map(h->h.name).distinct().toList());
   moved++;
  }
  return moved;
 }
 /** A handler that is nothing but {@code return}, redirecting one plain INVOKE of a void call. */
 private record EmptyRedirect(MethodNode handler,AnnotationNode redirect,AnnotationNode at,MixinAtWidenedCall.Member named){}
 /** Whether {@code wide} takes {@code named}'s parameters, in order, among more. */
 private static boolean within(String named,String wide){
  Type[] own=Type.getArgumentTypes(named),all=Type.getArgumentTypes(wide);
  if(all.length<=own.length||!Type.getReturnType(named).equals(Type.getReturnType(wide)))return false;
  int j=0;for(Type t:all)if(j<own.length&&t.equals(own[j]))j++;
  return j==own.length;
 }
 /** What Mixin binds a selector to: the method of that name and descriptor, or the first of that name. */
 private static MethodNode selected(ClassNode target,String selector){
  int paren=selector.indexOf('(');
  for(MethodNode m:target.methods)if(paren<0?m.name.equals(selector):(m.name+m.desc).equals(selector))return m;
  return null;
 }
 private static boolean group(MethodNode m){for(var list:Arrays.asList(m.visibleAnnotations,m.invisibleAnnotations))if(list!=null&&list.stream().anyMatch(a->a.desc.equals("Lorg/spongepowered/asm/mixin/injection/Group;")))return true;return false;}
 private static MethodNode find(ClassNode c,String n,String d){return c.methods.stream().filter(m->m.name.equals(n)&&m.desc.equals(d)).findFirst().orElse(null);}
 private static void set(AnnotationNode a,String key,Object value){for(int i=0;i<a.values.size();i+=2)if(key.equals(a.values.get(i))){a.values.set(i+1,value);return;}a.values.add(key);a.values.add(value);}
 private static void remove(AnnotationNode a,String key){for(int i=0;i<a.values.size();i+=2)if(key.equals(a.values.get(i))){a.values.remove(i+1);a.values.remove(i);return;}}
}
