/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.transform;
import org.objectweb.asm.*;import org.objectweb.asm.tree.*;

public final class HudContextQueryInjector implements ClassTransformer {
	public static final String TARGET="net.minecraft.client.gui.Hud";
	@Override public AnchorSet anchors(){return AnchorSet.of(new AnchorSet.Anchor(TARGET,AnchorSet.Severity.REQUIRED,"a scoped HUD callback cannot suppress the native contextual bar"));}
	@Override public byte[] transform(String name,byte[] bytes,TransformContext context){if(!TARGET.equals(name))return bytes;ClassNode node=new ClassNode();new ClassReader(bytes).accept(node,0);int changes=0;
		for(MethodNode method:node.methods)if(method.name.equals("updateContextualBarRenderer")&&method.desc.equals("()V"))for(var i:method.instructions.toArray())if(i instanceof MethodInsnNode call&&call.owner.equals(node.name)&&call.name.equals("nextContextualInfoState")&&call.desc.equals("()Lnet/minecraft/client/gui/Hud$ContextualInfo;")){call.setOpcode(Opcodes.INVOKESTATIC);call.owner="net/forbric/kernel/runtime/KernelHudContextQuery";call.name="next";call.desc="(Lnet/minecraft/client/gui/Hud;)Ljava/lang/Object;";call.itf=false;method.instructions.insert(call,new TypeInsnNode(Opcodes.CHECKCAST,"net/minecraft/client/gui/Hud$ContextualInfo"));changes++;}
		if(changes!=1)return bytes;ClassWriter writer=new ClassWriter(0);node.accept(writer);return writer.toByteArray();}
}
