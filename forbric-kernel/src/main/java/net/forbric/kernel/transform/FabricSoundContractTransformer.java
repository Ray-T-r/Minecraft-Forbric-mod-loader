/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.transform;
import java.util.function.Predicate;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
/** Change only the native default. A sound overriding the native method still owns stream creation. */
public final class FabricSoundContractTransformer implements ClassTransformer {
 public static final String PROPERTY="forbric.fabricSoundContracts",SOUND="net/minecraft/client/resources/sounds/SoundInstance",API="net/fabricmc/fabric/api/client/sound/v1/FabricSoundInstance";
 public static final String LIBRARY="Lnet/minecraft/client/sounds/SoundBufferLibrary;",DETAIL="Lnet/minecraft/client/resources/sounds/Sound;",FUTURE="Ljava/util/concurrent/CompletableFuture;",DESC="("+LIBRARY+DETAIL+"Z)"+FUTURE;
 private final Predicate<String> present;
 public FabricSoundContractTransformer(Predicate<String> present){this.present=present;}
 private boolean enabled(){return !"off".equalsIgnoreCase(System.getProperty(PROPERTY,"on"))&&present.test(API);}
 @Override public String name(){return "forbric-fabric-sound-contracts";}
 @Override public AnchorSet anchors(){return enabled()?AnchorSet.of(new AnchorSet.Anchor(SOUND.replace('/','.'),AnchorSet.Severity.REQUIRED,"native default streaming must dispatch Fabric sound overrides")):AnchorSet.scanned("Fabric sound API absent or adapter explicitly disabled");}
 @Override public byte[] transform(String name,byte[] bytes,TransformContext context){
  if(!SOUND.replace('/','.').equals(name)||bytes==null||!enabled())return bytes;
  ClassNode node=new ClassNode();new ClassReader(bytes).accept(node,0);if(!node.interfaces.contains(API))return bytes;
  MethodNode method=node.methods.stream().filter(m->m.name.equals("getStream")&&m.desc.equals(DESC)).findFirst().orElse(null);
  if(method==null||!net.forbric.kernel.mixin.MixinInstructionFingerprint.hash(method).equals("f719cc50a5bbcc1ba62004e254cdf15d21e4010e40d389d0bc7d58b7c2acb846"))return bytes;
  method.instructions.clear();method.tryCatchBlocks.clear();method.localVariables=null;var out=method.instructions;
  out.add(new VarInsnNode(Opcodes.ALOAD,0));out.add(new TypeInsnNode(Opcodes.CHECKCAST,API));out.add(new VarInsnNode(Opcodes.ALOAD,1));out.add(new VarInsnNode(Opcodes.ALOAD,2));
  out.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL,"net/minecraft/client/resources/sounds/Sound","getPath","()Lnet/minecraft/resources/Identifier;",false));out.add(new VarInsnNode(Opcodes.ILOAD,3));
  out.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE,API,"getAudioStream","("+LIBRARY+"Lnet/minecraft/resources/Identifier;Z)"+FUTURE,true));out.add(new InsnNode(Opcodes.ARETURN));method.maxStack=4;method.maxLocals=4;
  ClassWriter writer=new ClassWriter(0);node.accept(writer);return writer.toByteArray();
 }
}
