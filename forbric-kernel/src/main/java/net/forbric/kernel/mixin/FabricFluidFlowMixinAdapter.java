/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.mixin;

import java.util.*;
import java.util.function.Function;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;
import net.forbric.kernel.util.ForbricLog;
import net.forbric.api.Ecosystem;
import net.forbric.api.ForeignType;

/** Runs Fabric's original veto before each carrier's fluid interaction, retaining all native scheduling. */
public final class FabricFluidFlowMixinAdapter {
	public static final String PROPERTY = "forbric.fabricFluidFlow";
	private static final String MIXIN = "net/fabricmc/fabric/mixin/block/LiquidBlockMixin";
	private static final String TARGET = "net/minecraft/world/level/block/LiquidBlock";
	private static final String LEVEL = "Lnet/minecraft/world/level/Level;";
	private static final String POS = "Lnet/minecraft/core/BlockPos;";
	private static final String STATE = "Lnet/minecraft/world/level/block/state/BlockState;";
	private static final String CIR = "org/spongepowered/asm/mixin/injection/callback/CallbackInfoReturnable";
	private static final String OPERATION = "com/llamalad7/mixinextras/injector/wrapoperation/Operation";
	private FabricFluidFlowMixinAdapter() { }

	public static int adapt(ClassNode mixin, Function<String, ClassNode> targets) {
		if (!MIXIN.equals(mixin.name) || "off".equalsIgnoreCase(System.getProperty(PROPERTY,"on"))) return 0;
		if (mixin.methods.stream().anyMatch(m -> m.name.endsWith("$forbricOriginal"))) return 0;
		List<MethodNode> callbacks = mixin.methods.stream().filter(m -> m.desc.equals("(" + LEVEL + POS + STATE + "L" + CIR + ";)V")
				&& MixinFit.injectorOf(m) != null).toList();
		if (callbacks.size() != 1) return 0;
		MethodNode original = callbacks.getFirst();
		ClassNode target = targets.apply(TARGET);
		if (original == null || target == null || MixinFit.injectorOf(original) == null) return 0;
		// Where the method the callback enters is called again (NativeEntryRelay asks it ahead of the carriers' decisions),
		// the callback runs there as written, as on Fabric; wrapping the registries too would run it twice.
		MethodNode bound = MixinTargetSelectors.one(original, target);
		if (bound != null) for (MethodNode method : target.methods) for (AbstractInsnNode i : method.instructions)
			if (i instanceof MethodInsnNode call && call.owner.equals(target.name) && call.name.equals(bound.name) && call.desc.equals(bound.desc)) {
				ForbricLog.info("[Forbric/FluidFlow] Fabric's ALLOW callback runs at the entry of %s, which the merged game asks again", bound.name);
				return 0;
			}
		List<MethodNode> hosts = new ArrayList<>(); List<MethodInsnNode> nativeCalls = new ArrayList<>();
		for (String name : List.of("onPlace","neighborChanged")) {
			MethodNode host = target.methods.stream().filter(m -> m.name.equals(name)).findFirst().orElse(null);
			if (host == null) return 0;
			List<MethodInsnNode> matches = new ArrayList<>();
			for (AbstractInsnNode i : host.instructions) if (i instanceof MethodInsnNode call
					&& call.getOpcode() == Opcodes.INVOKESTATIC && call.name.equals("canInteract")
					&& call.desc.equals("(" + LEVEL + POS + ")Z")
					&& Set.of(ForeignType.FLUID_INTERACTION_REGISTRY.internal(Ecosystem.FORGE),ForeignType.FLUID_INTERACTION_REGISTRY.internal(Ecosystem.NEOFORGE)).contains(call.owner)) matches.add(call);
			if (matches.size() != 1) return 0;
			hosts.add(host); nativeCalls.add(matches.getFirst());
		}
		MethodNode shape = target.methods.stream().filter(m -> m.name.equals("updateShape")
				&& m.desc.startsWith("(" + STATE + "Lnet/minecraft/world/level/LevelReader;Lnet/minecraft/world/level/ScheduledTickAccess;" + POS)).findFirst().orElse(null);
		if (shape == null) return 0;
		List<MethodInsnNode> schedules = new ArrayList<>();
		for (var instruction : shape.instructions) if (instruction instanceof MethodInsnNode call
				&& call.owner.equals("net/minecraft/world/level/ScheduledTickAccess") && call.name.equals("scheduleTick")
				&& call.desc.equals("(" + POS + "Lnet/minecraft/world/level/material/Fluid;I)V")) schedules.add(call);
		if (schedules.size() != 1) return 0;
		retainOriginal(original, MixinFit.injectorOf(original));
		for (int i=0;i<hosts.size();i++) mixin.methods.add(wrapper(mixin, original, hosts.get(i), nativeCalls.get(i)));
		mixin.methods.add(scheduleWrapper(mixin, original, shape, schedules.getFirst()));
		ForbricLog.info("[Forbric/FluidFlow] Fabric's original ALLOW callback now guards both carrier interactions "
				+ "and the neighbor-shape scheduling path; denied flow does not schedule a fluid tick");
		return 3;
	}

	/**
	 * Keeps a mod's {@code shouldSpreadLiquid} handler as a plain private method the wrappers call, under the kernel's
	 * {@code $forbricOriginal} name and marked {@code @Unique}. fabric-block-api and Create Fly both name their handler
	 * {@code shouldSpreadLiquid}, so both renames land on {@code shouldSpreadLiquid$forbricOriginal} in the same
	 * {@code LiquidBlock}: as a plain method Mixin kept the first and skipped the second ("Method overwrite conflict"),
	 * and the second mod's wrappers then called the first mod's body — with Create Fly installed fabric-api's
	 * {@code FluidFlowEvents.ALLOW} never fired. {@code @Unique} makes Mixin rename the later one and the calls to it.
	 */
	static void retainOriginal(MethodNode original, AnnotationNode injector) {
		original.visibleAnnotations.remove(injector);
		original.name += "$forbricOriginal";
		original.visibleAnnotations.add(new AnnotationNode("Lorg/spongepowered/asm/mixin/Unique;"));
	}

	private static MethodNode scheduleWrapper(ClassNode mixin, MethodNode original, MethodNode host, MethodInsnNode nativeCall) {
		String ticks = "Lnet/minecraft/world/level/ScheduledTickAccess;", fluid = "Lnet/minecraft/world/level/material/Fluid;";
		MethodNode method = new MethodNode(Opcodes.ASM9,Opcodes.ACC_PRIVATE,"forbric$fluidFlow$updateShape",
				"(" + ticks + POS + fluid + "IL" + OPERATION + ";" + host.desc.substring(1,host.desc.indexOf(')')) + ")V",null,null);
		AnnotationNode wrap = new AnnotationNode("Lcom/llamalad7/mixinextras/injector/wrapoperation/WrapOperation;");
		AnnotationNode at = new AnnotationNode("Lorg/spongepowered/asm/mixin/injection/At;");
		at.values = new ArrayList<>(List.of("value","INVOKE","target","L"+nativeCall.owner+";"+nativeCall.name+nativeCall.desc));
		wrap.values = new ArrayList<>(List.of("method",List.of(host.name+host.desc),"at",at,"require",1));
		method.visibleAnnotations = new ArrayList<>(List.of(wrap));
		// Capture the native host arguments as an exact prefix contract; no local-variable sugar is needed.
		var hostArgs = org.objectweb.asm.Type.getArgumentTypes(host.desc);
		int reader = 6 + hostArgs[0].getSize();
		int callback = 6 + Arrays.stream(hostArgs).mapToInt(org.objectweb.asm.Type::getSize).sum();
		InsnList code = method.instructions;
		code.add(new TypeInsnNode(Opcodes.NEW,CIR));code.add(new InsnNode(Opcodes.DUP));
		code.add(new LdcInsnNode("forbricFluidFlow"));code.add(new InsnNode(Opcodes.ICONST_1));code.add(new FieldInsnNode(Opcodes.GETSTATIC,"java/lang/Boolean","TRUE","Ljava/lang/Boolean;"));
		code.add(new MethodInsnNode(Opcodes.INVOKESPECIAL,CIR,"<init>","(Ljava/lang/String;ZLjava/lang/Object;)V",false));code.add(new VarInsnNode(Opcodes.ASTORE,callback));
		LabelNode schedule = new LabelNode();
		code.add(new VarInsnNode(Opcodes.ALOAD,reader));code.add(new TypeInsnNode(Opcodes.INSTANCEOF,"net/minecraft/world/level/Level"));code.add(new JumpInsnNode(Opcodes.IFEQ,schedule));
		code.add(new VarInsnNode(Opcodes.ALOAD,0));code.add(new VarInsnNode(Opcodes.ALOAD,reader));code.add(new TypeInsnNode(Opcodes.CHECKCAST,"net/minecraft/world/level/Level"));
		code.add(new VarInsnNode(Opcodes.ALOAD,2));code.add(new VarInsnNode(Opcodes.ALOAD,reader));code.add(new TypeInsnNode(Opcodes.CHECKCAST,"net/minecraft/world/level/Level"));code.add(new VarInsnNode(Opcodes.ALOAD,2));
		code.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL,"net/minecraft/world/level/Level","getBlockState","("+POS+")"+STATE,false));code.add(new VarInsnNode(Opcodes.ALOAD,callback));
		code.add(new MethodInsnNode(Opcodes.INVOKESPECIAL,mixin.name,original.name,original.desc,false));
		code.add(new VarInsnNode(Opcodes.ALOAD,callback));code.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL,CIR,"getReturnValueZ","()Z",false));code.add(new JumpInsnNode(Opcodes.IFNE,schedule));code.add(new InsnNode(Opcodes.RETURN));
		code.add(schedule);code.add(new FrameNode(Opcodes.F_APPEND,1,new Object[]{CIR},0,null));
		code.add(new VarInsnNode(Opcodes.ALOAD,5));code.add(new InsnNode(Opcodes.ICONST_4));code.add(new TypeInsnNode(Opcodes.ANEWARRAY,"java/lang/Object"));
		for(int i=0;i<4;i++){code.add(new InsnNode(Opcodes.DUP));code.add(new InsnNode(Opcodes.ICONST_0+i));code.add(new VarInsnNode(i==3?Opcodes.ILOAD:Opcodes.ALOAD,i+1));if(i==3)code.add(new MethodInsnNode(Opcodes.INVOKESTATIC,"java/lang/Integer","valueOf","(I)Ljava/lang/Integer;",false));code.add(new InsnNode(Opcodes.AASTORE));}
		code.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE,OPERATION,"call","([Ljava/lang/Object;)Ljava/lang/Object;",true));code.add(new InsnNode(Opcodes.POP));code.add(new InsnNode(Opcodes.RETURN));
		method.maxStack=6;method.maxLocals=callback+1;return method;
	}

	static MethodNode wrapper(ClassNode mixin, MethodNode original, MethodNode host, MethodInsnNode nativeCall) {
		MethodNode method = new MethodNode(Opcodes.ASM9,Opcodes.ACC_PRIVATE,"forbric$fluidFlow$"+host.name,
				"("+LEVEL+POS+"L"+OPERATION+";)Z",null,null);
		AnnotationNode wrap = new AnnotationNode("Lcom/llamalad7/mixinextras/injector/wrapoperation/WrapOperation;");
		AnnotationNode at = new AnnotationNode("Lorg/spongepowered/asm/mixin/injection/At;");
		at.values = new ArrayList<>(List.of("value","INVOKE","target","L"+nativeCall.owner+";"+nativeCall.name+nativeCall.desc));
		wrap.values = new ArrayList<>(List.of("method",List.of(host.name+host.desc),"at",at,"require",1));
		method.visibleAnnotations = new ArrayList<>(List.of(wrap));
		InsnList code = method.instructions;
		code.add(new TypeInsnNode(Opcodes.NEW,CIR)); code.add(new InsnNode(Opcodes.DUP));
		code.add(new LdcInsnNode("forbricFluidFlow")); code.add(new InsnNode(Opcodes.ICONST_1));
		code.add(new FieldInsnNode(Opcodes.GETSTATIC,"java/lang/Boolean","TRUE","Ljava/lang/Boolean;"));
		code.add(new MethodInsnNode(Opcodes.INVOKESPECIAL,CIR,"<init>","(Ljava/lang/String;ZLjava/lang/Object;)V",false));
		code.add(new VarInsnNode(Opcodes.ASTORE,4));
		code.add(new VarInsnNode(Opcodes.ALOAD,0));code.add(new VarInsnNode(Opcodes.ALOAD,1));code.add(new VarInsnNode(Opcodes.ALOAD,2));
		code.add(new VarInsnNode(Opcodes.ALOAD,1));code.add(new VarInsnNode(Opcodes.ALOAD,2));
		code.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL,"net/minecraft/world/level/Level","getBlockState","("+POS+")"+STATE,false));
		code.add(new VarInsnNode(Opcodes.ALOAD,4));
		code.add(new MethodInsnNode(Opcodes.INVOKESPECIAL,mixin.name,original.name,original.desc,false));
		code.add(new VarInsnNode(Opcodes.ALOAD,4));
		code.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL,CIR,"getReturnValueZ","()Z",false));
		LabelNode allowed = new LabelNode(); code.add(new JumpInsnNode(Opcodes.IFNE,allowed));
		code.add(new InsnNode(Opcodes.ICONST_1));code.add(new InsnNode(Opcodes.IRETURN));
		code.add(allowed);code.add(new FrameNode(Opcodes.F_APPEND,1,new Object[]{CIR},0,null));
		code.add(new VarInsnNode(Opcodes.ALOAD,3));code.add(new InsnNode(Opcodes.ICONST_2));code.add(new TypeInsnNode(Opcodes.ANEWARRAY,"java/lang/Object"));
		for(int i=0;i<2;i++) {code.add(new InsnNode(Opcodes.DUP));code.add(new InsnNode(i==0?Opcodes.ICONST_0:Opcodes.ICONST_1));code.add(new VarInsnNode(Opcodes.ALOAD,i+1));code.add(new InsnNode(Opcodes.AASTORE));}
		code.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE,OPERATION,"call","([Ljava/lang/Object;)Ljava/lang/Object;",true));
		code.add(new TypeInsnNode(Opcodes.CHECKCAST,"java/lang/Boolean"));code.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL,"java/lang/Boolean","booleanValue","()Z",false));code.add(new InsnNode(Opcodes.IRETURN));
		method.maxStack=6;method.maxLocals=5;
		return method;
	}
}
