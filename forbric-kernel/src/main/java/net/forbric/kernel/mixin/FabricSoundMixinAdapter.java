/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.mixin;
import java.util.*;
import java.util.function.Function;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import net.forbric.kernel.transform.FabricSoundContractTransformer;
/** Rebind the upstream stream redirect only after the default fallback actually contains Fabric dispatch. */
public final class FabricSoundMixinAdapter {
 private static final String MIXIN="net/fabricmc/fabric/mixin/client/sound/SoundEngineMixin",ENGINE="net/minecraft/client/sounds/SoundEngine";
 private FabricSoundMixinAdapter(){}
 public static int adapt(ClassNode mixin,Function<String,ClassNode> targets){
  if(!mixin.name.equals(MIXIN)||"off".equalsIgnoreCase(System.getProperty(FabricSoundContractTransformer.PROPERTY,"on")))return 0;
  String sound=FabricSoundContractTransformer.SOUND,library=FabricSoundContractTransformer.LIBRARY,future=FabricSoundContractTransformer.FUTURE;
  String original="("+library+"Lnet/minecraft/resources/Identifier;ZL"+sound+";)"+future;
  MethodNode handler=mixin.methods.stream().filter(m->m.name.equals("getStream")&&m.desc.equals(original)).findFirst().orElse(null);
  if(handler==null||!MixinInstructionFingerprint.hash(handler).equals("b142d6b0548fe93bb213b2d23d239046b46ef38b080e215a012e5d2732ae73ad"))return 0;
  for(var list:Arrays.asList(handler.visibleAnnotations,handler.invisibleAnnotations))if(list!=null&&list.stream().anyMatch(a->a.desc.equals("Lorg/spongepowered/asm/mixin/injection/Group;")))return 0;
  ClassNode declaration=targets.apply(sound),engine=targets.apply(ENGINE);if(declaration==null||engine==null)return 0;
  MethodNode fallback=declaration.methods.stream().filter(m->m.name.equals("getStream")&&m.desc.equals(FabricSoundContractTransformer.DESC)).findFirst().orElse(null);if(fallback==null)return 0;
  int dispatch=0;for(var i:fallback.instructions)if(i instanceof MethodInsnNode c&&c.owner.equals(FabricSoundContractTransformer.API)&&c.name.equals("getAudioStream"))dispatch++;if(dispatch!=1)return 0;
  MethodNode play=engine.methods.stream().filter(m->m.name.equals("play")&&m.desc.equals("(L"+sound+";)Lnet/minecraft/client/sounds/SoundEngine$PlayResult;")).findFirst().orElse(null);if(play==null)return 0;
  int calls=0;for(var i:play.instructions)if(i instanceof MethodInsnNode c&&c.owner.equals(sound)&&c.name.equals("getStream")&&c.desc.equals(FabricSoundContractTransformer.DESC))calls++;if(calls!=1)return 0;
  AnnotationNode redirect=MixinFit.injectorOf(handler);if(redirect==null||MixinFit.atNodes(redirect).size()!=1)return 0;AnnotationNode at=MixinFit.atNodes(redirect).getFirst();
  if(!("Lnet/minecraft/client/sounds/SoundBufferLibrary;getStream(Lnet/minecraft/resources/Identifier;Z)"+future).equals(MixinFit.value(at,"target")))return 0;
  for(int i=0;i<at.values.size();i+=2)if(at.values.get(i).equals("target"))at.values.set(i+1,"L"+sound+";getStream"+FabricSoundContractTransformer.DESC);
  handler.desc="(L"+sound+";"+FabricSoundContractTransformer.DESC.substring(1);handler.signature=null;handler.parameters=null;handler.visibleParameterAnnotations=null;handler.invisibleParameterAnnotations=null;handler.localVariables=null;handler.tryCatchBlocks.clear();handler.instructions.clear();
  handler.instructions.add(new VarInsnNode(Opcodes.ALOAD,1));handler.instructions.add(new VarInsnNode(Opcodes.ALOAD,2));handler.instructions.add(new VarInsnNode(Opcodes.ALOAD,3));handler.instructions.add(new VarInsnNode(Opcodes.ILOAD,4));handler.instructions.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE,sound,"getStream",FabricSoundContractTransformer.DESC,true));handler.instructions.add(new InsnNode(Opcodes.ARETURN));handler.maxStack=4;handler.maxLocals=5;return 1;
 }
}
