/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.transform;

import java.util.ArrayList;
import java.util.List;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import net.forbric.kernel.util.ForbricLog;
import net.forbric.api.Ecosystem;
import net.forbric.api.ForeignType;

/** Repairs the shared default-refill seam, before the native breathe event and without changing air/type predicates. */
public final class FabricFluidBreathingInjector implements ClassTransformer {
	public static final String PROPERTY="forbric.foreignFluidBreathing";
	private static final String TARGET="net.neoforged.neoforge.common.CommonHooks";
	private static final String LIVING="net/minecraft/world/entity/LivingEntity";
	private static final String TYPE=ForeignType.FLUID_TYPE.internal(Ecosystem.NEOFORGE);
	private static final String PREDICATE="net/neoforged/neoforge/fluids/InFluidPredicate";
	private static final String RUNTIME="net/forbric/kernel/runtime/KernelFluidBreathing";
	private static final String OBSERVATION=RUNTIME+"$Observation";
	private static final String DESC="(L"+LIVING+";Lnet/minecraft/server/level/ServerLevel;II)V";
	private static boolean enabled(){return !"off".equalsIgnoreCase(System.getProperty(PROPERTY,"on"))&&!"off".equalsIgnoreCase(System.getProperty(FabricFluidBehaviorInjector.PROPERTY,"on"));}
	@Override public String name(){return "forbric-foreign-fluid-breathing";}
	@Override public AnchorSet anchors(){return enabled()?AnchorSet.of(new AnchorSet.Anchor(TARGET,AnchorSet.Severity.REQUIRED,"non-drowning foreign fluid adapters hold their air supply instead of refilling")):AnchorSet.scanned("foreign adapter default refill disabled");}
	@Override public byte[]transform(String name,byte[]bytes,TransformContext context){
		if(!enabled()||!TARGET.equals(name)||bytes==null||bytes.length==0)return bytes;
		ClassNode node=new ClassNode();new ClassReader(bytes).accept(node,ClassReader.EXPAND_FRAMES);if(!repair(node))return bytes;
		ClassWriter writer=new ClassWriter(ClassWriter.COMPUTE_MAXS);node.accept(writer);ForbricLog.info("[Forbric/Fluid] non-drowning adapter defaults refill air before the native breathe event; native types retain their defaults");return writer.toByteArray();
	}
	static boolean repair(ClassNode node){
		MethodNode hook=node.methods.stream().filter(m->m.name.equals("onLivingBreathe")&&m.desc.equals(DESC)&&(m.access&Opcodes.ACC_STATIC)!=0).findFirst().orElse(null);
		if(hook==null)return declined("shared onLivingBreathe signature absent");
		for(var i:hook.instructions)if(i instanceof MethodInsnNode m&&m.owner.equals(RUNTIME))return false;
		List<InvokeDynamicInsnNode>factories=new ArrayList<>();List<AbstractInsnNode>clears=new ArrayList<>();int events=0;
		for(var i:hook.instructions){
			if(i instanceof TypeInsnNode t&&t.getOpcode()==Opcodes.NEW&&t.desc.equals("net/neoforged/neoforge/event/entity/living/LivingBreatheEvent"))events++;
			if(i.getOpcode()==Opcodes.ICONST_0&&next(i) instanceof VarInsnNode store&&store.getOpcode()==Opcodes.ISTORE&&store.var==3)clears.add(i);
			if(i instanceof InvokeDynamicInsnNode factory&&factory.desc.equals("()L"+PREDICATE+";"))for(Object argument:factory.bsmArgs)if(argument instanceof Handle handle&&handle.getOwner().equals(node.name)&&handle.getTag()==Opcodes.H_INVOKESTATIC){
				MethodNode body=node.methods.stream().filter(m->m.name.equals(handle.getName())&&m.desc.equals(handle.getDesc())).findFirst().orElse(null);
				if(body!=null&&drowningQuery(body))factories.add(factory);
			}
		}
		if(factories.size()!=1||clears.size()!=1||events!=1)return declined("drowning predicate / zero-refill seam / breathe event is not unique");
		if(!(next(factories.getFirst()) instanceof MethodInsnNode consumer&&consumer.getOpcode()==Opcodes.INVOKEVIRTUAL
				&&consumer.owner.equals("net/minecraft/world/entity/EntityFluidInteraction")&&consumer.name.equals("isEyeInFluidMatching")
				&&consumer.desc.equals("(Lnet/minecraft/world/entity/Entity;L"+PREDICATE+";)Z")))return declined("drowning predicate does not feed the native boolean consumer immediately");
		for(var i=factories.getFirst().getNext();i!=consumer;i=i.getNext())if(i instanceof LabelNode label)
			for(var edge:hook.instructions)if(edge instanceof JumpInsnNode jump&&jump.label==label
					||edge instanceof TableSwitchInsnNode table&&(table.dflt==label||table.labels.contains(label))
					||edge instanceof LookupSwitchInsnNode lookup&&(lookup.dflt==label||lookup.labels.contains(label)))return declined("drowning consumer has another control-flow entry");
		int observation=hook.maxLocals;hook.maxLocals++;
		InsnList enter=new InsnList();enter.add(new MethodInsnNode(Opcodes.INVOKESTATIC,RUNTIME,"observation","()L"+OBSERVATION+";",false));enter.add(new VarInsnNode(Opcodes.ASTORE,observation));hook.instructions.insert(enter);
		InsnList remember=new InsnList();remember.add(new VarInsnNode(Opcodes.ALOAD,observation));remember.add(new MethodInsnNode(Opcodes.INVOKESTATIC,RUNTIME,"remember","(L"+PREDICATE+";L"+OBSERVATION+";)L"+PREDICATE+";",false));hook.instructions.insert(factories.getFirst(),remember);
		InsnList refill=new InsnList();refill.add(new VarInsnNode(Opcodes.ALOAD,0));refill.add(new VarInsnNode(Opcodes.ILOAD,3));refill.add(new VarInsnNode(Opcodes.ALOAD,observation));refill.add(new MethodInsnNode(Opcodes.INVOKESTATIC,RUNTIME,"refillDefault","(L"+LIVING+";IL"+OBSERVATION+";)I",false));hook.instructions.insertBefore(clears.getFirst(),refill);hook.instructions.remove(clears.getFirst());
		for(var i:hook.instructions)if(i instanceof FrameNode frame){
			if(frame.type!=Opcodes.F_NEW)return declined("expanded frame unavailable");
			int slots=0;for(Object local:frame.local)slots+=local.equals(Opcodes.LONG)||local.equals(Opcodes.DOUBLE)?2:1;
			while(slots<observation){frame.local.add(Opcodes.TOP);slots++;}if(slots!=observation)return declined("frame extends beyond the captured observation local");frame.local.add(OBSERVATION);
		}return true;
	}
	private static boolean drowningQuery(MethodNode method){List<AbstractInsnNode>code=new ArrayList<>();for(var i:method.instructions)if(i.getOpcode()>=0)code.add(i);
		return method.desc.equals("(L"+LIVING+";L"+TYPE+";D)Z")&&code.size()==4&&code.get(0) instanceof VarInsnNode a&&a.getOpcode()==Opcodes.ALOAD&&a.var==0&&code.get(1) instanceof VarInsnNode b&&b.getOpcode()==Opcodes.ALOAD&&b.var==1
				&&code.get(2) instanceof MethodInsnNode call&&call.getOpcode()==Opcodes.INVOKEVIRTUAL&&call.owner.equals(LIVING)&&call.name.equals("canDrownInFluidType")&&call.desc.equals("(L"+TYPE+";)Z")&&code.get(3).getOpcode()==Opcodes.IRETURN;
	}
	private static AbstractInsnNode next(AbstractInsnNode node){for(var i=node.getNext();i!=null;i=i.getNext())if(i.getOpcode()>=0)return i;return null;}
	private static boolean declined(String reason){ForbricLog.warn("[Forbric/Fluid] shared foreign adapter refill seam was not applied: %s",reason);return false;}
}
