/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.mixin;
import java.util.*;
import java.util.function.Function;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import org.objectweb.asm.tree.analysis.*;
/** Proven moved block-entity removal and a context-expanded call whose Fabric redirect is strictly a no-op. */
public final class FabricClientMixinAnchors {
 public static final String PROPERTY="forbric.fabricClientAnchors";
 private FabricClientMixinAnchors(){}
 public static int adapt(ClassNode mixin,Function<String,ClassNode> targets){
  if("off".equalsIgnoreCase(System.getProperty(PROPERTY,"on")))return 0;
  if(mixin.name.equals("net/fabricmc/fabric/mixin/event/lifecycle/client/LevelChunkMixin"))return removal(mixin,targets);
  if(mixin.name.equals("net/fabricmc/fabric/mixin/client/renderer/block/render/LevelRendererMixin"))return render(mixin,targets);
  return 0;
 }
 private static int removal(ClassNode mixin,Function<String,ClassNode> targets){
  String owner="net/minecraft/world/level/chunk/LevelChunk",desc="(Lnet/minecraft/core/BlockPos;L"+owner+"$EntityCreationType;)Lnet/minecraft/world/level/block/entity/BlockEntity;";
  MethodNode handler=find(mixin,"onRemoveBlockEntity","(Ljava/util/Map;Ljava/lang/Object;)Ljava/lang/Object;");ClassNode target=targets.apply(owner);
  if(handler==null||target==null||group(handler))return 0;MethodNode host=find(target,"getBlockEntity",desc);if(host==null)return 0;
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
 private static int render(ClassNode mixin,Function<String,ClassNode> targets){
  String model="net/minecraft/client/renderer/block/dispatch/BlockStateModel",tail="Lnet/minecraft/util/RandomSource;Ljava/util/List;)V";
  MethodNode handler=find(mixin,"cancelCollectParts","(L"+model+";"+tail);if(handler==null||group(handler))return 0;
  List<AbstractInsnNode> code=new ArrayList<>();for(var i:handler.instructions)if(i.getOpcode()>=0)code.add(i);if(code.size()!=1||code.getFirst().getOpcode()!=Opcodes.RETURN)return 0;
  ClassNode target=targets.apply("net/minecraft/client/renderer/LevelRenderer");if(target==null)return 0;
  MethodNode host=find(target,"submitBlockDestroyAnimation","(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;Lnet/minecraft/client/renderer/state/level/LevelRenderState;)V");if(host==null)return 0;
  String extended="(Lnet/minecraft/client/renderer/block/BlockAndTintGetter;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;"+tail;
  int matches=0;for(var i:host.instructions)if(i instanceof MethodInsnNode c&&c.owner.equals(model)&&c.name.equals("collectParts")){if(c.desc.equals("("+tail))return 0;if(c.desc.equals(extended))matches++;}if(matches!=1)return 0;
  AnnotationNode redirect=MixinFit.injectorOf(handler);if(redirect==null||MixinFit.atNodes(redirect).size()!=1)return 0;AnnotationNode at=MixinFit.atNodes(redirect).getFirst();if(!("L"+model+";collectParts("+tail).equals(MixinFit.value(at,"target")))return 0;
  set(at,"target","L"+model+";collectParts"+extended);handler.desc="(L"+model+";"+extended.substring(1);handler.signature=null;handler.parameters=null;handler.visibleParameterAnnotations=null;handler.invisibleParameterAnnotations=null;handler.localVariables=null;handler.maxLocals=7;return 1;
 }
 private static boolean group(MethodNode m){for(var list:Arrays.asList(m.visibleAnnotations,m.invisibleAnnotations))if(list!=null&&list.stream().anyMatch(a->a.desc.equals("Lorg/spongepowered/asm/mixin/injection/Group;")))return true;return false;}
 private static MethodNode find(ClassNode c,String n,String d){return c.methods.stream().filter(m->m.name.equals(n)&&m.desc.equals(d)).findFirst().orElse(null);}
 private static void set(AnnotationNode a,String key,Object value){for(int i=0;i<a.values.size();i+=2)if(key.equals(a.values.get(i))){a.values.set(i+1,value);return;}a.values.add(key);a.values.add(value);}
 private static void remove(AnnotationNode a,String key){for(int i=0;i<a.values.size();i+=2)if(key.equals(a.values.get(i))){a.values.remove(i+1);a.values.remove(i);return;}}
}
