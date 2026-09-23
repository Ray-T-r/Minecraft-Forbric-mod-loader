/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.mixin;

import java.util.Map;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;

/** Scoped replacement proofs. A repair's name or mere registration is never enough to resolve a loss. */
final class MixinEquivalentImplementations {
 static final String CONDITIONS="net.fabricmc.fabric.mixin.resource.conditions.SimpleJsonResourceReloadListenerMixin";
 static final String SKIP_DESC="(Ljava/util/Map;Lnet/minecraft/resources/Identifier;Ljava/lang/Object;Lorg/spongepowered/asm/mixin/injection/callback/CallbackInfo;)V";
 private static final String READER="net/minecraft/server/packs/resources/SimpleJsonResourceReloadListener";
 private static final String HELPER="net/forbric/kernel/runtime/KernelFabricConditions";
 private static final String FILTER_DESC="(Lcom/mojang/serialization/DataResult;Ljava/util/function/Consumer;)Lcom/mojang/serialization/DataResult;";
 private static final String PREFIX="(Lnet/minecraft/server/packs/resources/ResourceManager;Lnet/minecraft/resources/FileToIdConverter;Lcom/mojang/serialization/DynamicOps;Lcom/mojang/serialization/Codec;Ljava/util/Map;";
 private static final String ORIGINAL_SKIP="daf3ed46809c8399223bbd9e080f82264a6fc5c511ea006cf06d11adecdb26ca";
 // Audited NeoForge 26.2.0.88 consumers: an empty Optional skips insertion; non-empty data enters the map.
 private static final Map<String,String> CONSUMERS=Map.of(
  "lambda$scanDirectory$0(Lnet/minecraft/resources/Identifier;Lnet/minecraft/resources/Identifier;Ljava/util/Map;Ljava/util/Optional;)V","9f786840a42ee92c7f887edff24c25846101bcf9670a2ed36cfe19d7e6beb6c6",
  "lambda$scanDirectoryWithModifier$0(Lnet/minecraft/resources/Identifier;Ljava/util/Map;Ljava/util/Optional;)V","3b615345fd8eed2f7a6891f4f961ae0bc43aa9ddb8a164911cc27ba4d96de6bb");
 static final String CONDITIONAL_OPS="net/neoforged/neoforge/common/conditions/ConditionalOps";
 private static final String FUNNEL="createConditionalCodecWithConditions";
 private static final String FUNNEL_DESC="(Lcom/mojang/serialization/Codec;Ljava/lang/String;)Lcom/mojang/serialization/Codec;";
 private static final String WRAP_DESC="(Lcom/mojang/serialization/Codec;)Lcom/mojang/serialization/Codec;";
 private MixinEquivalentImplementations() { }
 /** The fabric:load_conditions evaluator SupersededMixins credits for the conditions mixin: ConditionalOps' one
  * codec factory returns through KernelFabricConditions.alsoAskFabric, every other public factory delegates into
  * the funnel, and the runtime switch has not turned the wrap into a pass-through. The name alone proves none of it. */
 static String conditionsFunnel(ClassNode ops){
  if("off".equalsIgnoreCase(System.getProperty("forbric.fabricConditions","on"))||!CONDITIONAL_OPS.equals(ops.name))return null;
  MethodNode factory=ops.methods.stream().filter(m->m.name.equals(FUNNEL)&&m.desc.equals(FUNNEL_DESC)).findFirst().orElse(null);
  if(factory==null)return null;
  AbstractInsnNode exit=null;
  for(AbstractInsnNode instruction:factory.instructions)if(instruction.getOpcode()==Opcodes.ARETURN){if(exit!=null)return null;exit=instruction;}
  AbstractInsnNode wrap=exit==null?null:exit.getPrevious();
  while(wrap!=null&&wrap.getOpcode()<0)wrap=wrap.getPrevious();
  if(!(wrap instanceof MethodInsnNode call)||call.getOpcode()!=Opcodes.INVOKESTATIC||call.itf||!call.owner.equals(HELPER)
    ||!call.name.equals("alsoAskFabric")||!call.desc.equals(WRAP_DESC))return null;
  // Every other public factory must reach the wrapped one through ConditionalOps' own calls, not merely exist.
  java.util.Set<String> reaches=new java.util.HashSet<>(java.util.Set.of(FUNNEL+FUNNEL_DESC));
  for(boolean grew=true;grew;){
   grew=false;
   for(MethodNode method:ops.methods){
    if(reaches.contains(method.name+method.desc))continue;
    for(AbstractInsnNode instruction:method.instructions)if(instruction instanceof MethodInsnNode c&&c.owner.equals(CONDITIONAL_OPS)
      &&reaches.contains(c.name+c.desc)){reaches.add(method.name+method.desc);grew=true;break;}
   }
  }
  int entries=0;
  for(MethodNode method:ops.methods){
   if(method==factory||(method.access&Opcodes.ACC_PUBLIC)==0||(method.access&Opcodes.ACC_STATIC)==0)continue;
   if(!method.name.startsWith("createConditionalCodec")&&!method.name.startsWith("decodeList"))continue;
   if(!reaches.contains(method.name+method.desc))return null;
   entries++;
  }
  return "ConditionalOps' single codec factory exit returns through KernelFabricConditions.alsoAskFabric and all "+entries
    +" other public factories reach it, so every consumer asks Fabric's conditions";
 }
 static boolean needsFingerprint(String mixin,MethodNode handler){return CONDITIONS.equals(mixin)&&handler.name.equals("skipData")&&handler.desc.equals(SKIP_DESC)
   ||WatchdogDumpEquivalence.names(mixin,handler.name,handler.desc);}
 static String proof(String mixin,String name,String desc,String fingerprint,ClassNode target){
  if(WatchdogDumpEquivalence.candidate(mixin,name,desc,fingerprint,target)&&WatchdogDumpEquivalence.helperProved())
   return "The actual watchdog report and its delegating entry use the final-defined audited NeoForge full-thread renderer; every frame is retained without Fabric's truncated-Object append patch";
  if(!CONDITIONS.equals(mixin)||!name.equals("skipData")||!desc.equals(SKIP_DESC)||!ORIGINAL_SKIP.equals(fingerprint)||!target.name.equals(READER))return null;
  for(var expected:CONSUMERS.entrySet()) {
   MethodNode consumer=target.methods.stream().filter(m->(m.name+m.desc).equals(expected.getKey())).findFirst().orElse(null);
   if(consumer==null||!expected.getValue().equals(MixinInstructionFingerprint.hash(consumer)))return null;
  }
  int readers=0;
  for(MethodNode method:target.methods){
   boolean reader=(method.name.equals("scanDirectory")&&method.desc.equals(PREFIX+")V"))
      ||(method.name.equals("scanDirectoryWithModifier")&&method.desc.equals(PREFIX+"Ljava/util/function/Consumer;)V"));
   int filters=0;
   for(AbstractInsnNode instruction:method.instructions)if(instruction instanceof MethodInsnNode call){
    if(call.owner.equals("com/mojang/serialization/DataResult")&&call.name.equals("ifSuccess"))return null;
    if(call.owner.equals(HELPER)&&call.name.equals("ifSuccessWithoutAForeignSkipMarker")&&call.desc.equals(FILTER_DESC)
      &&call.getOpcode()==Opcodes.INVOKESTATIC&&!call.itf)filters++;
   }
   if(reader){if(filters!=1)return null;readers++;}
  }
  return readers==2?"Both actual JSON readers filter the known foreign marker through KernelFabricConditions before the audited native Optional consumers; skipped data cannot enter either map":null;
 }
}
