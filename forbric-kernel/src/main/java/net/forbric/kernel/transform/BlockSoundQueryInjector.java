/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.transform;
import org.objectweb.asm.*;import org.objectweb.asm.tree.*;

/** Restore the original group query inside the native block sound implementation, without replacing sound playback. */
public final class BlockSoundQueryInjector implements ClassTransformer {
	public static final String TARGET="net.neoforged.neoforge.common.extensions.IBlockExtension";
	@Override public AnchorSet anchors(){return AnchorSet.of(new AnchorSet.Anchor(TARGET,AnchorSet.Severity.REQUIRED,"guest step and landing sound groups are lost in the native block sound methods"));}
	@Override public byte[] transform(String name,byte[] bytes,TransformContext context){if(!TARGET.equals(name))return bytes;ClassNode node=new ClassNode();new ClassReader(bytes).accept(node,0);int count=0;
		for(MethodNode method:node.methods)if(method.name.equals("playStepSound")||method.name.equals("playFallSound"))for(var i:method.instructions)if(i instanceof MethodInsnNode call&&call.owner.equals("net/minecraft/world/level/block/state/BlockState")&&call.name.equals("getSoundType")&&call.desc.equals("(Lnet/minecraft/world/level/LevelReader;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/entity/Entity;)Lnet/minecraft/world/level/block/SoundType;")){
			call.setOpcode(Opcodes.INVOKESTATIC);call.owner="net/forbric/kernel/runtime/KernelBlockSoundQuery";call.name="sound";call.desc="(Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/world/level/LevelReader;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/entity/Entity;)Lnet/minecraft/world/level/block/SoundType;";call.itf=false;count++;
		}
		if(count!=2)return bytes;ClassWriter writer=new ClassWriter(0);node.accept(writer);return writer.toByteArray();}
}
