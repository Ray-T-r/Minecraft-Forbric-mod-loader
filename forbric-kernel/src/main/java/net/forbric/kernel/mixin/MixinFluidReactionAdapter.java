/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.mixin;

import static net.forbric.kernel.mixin.MixinPlayerWorldCallbackAdapter.*;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.*;
import net.forbric.api.Ecosystem;
import net.forbric.api.ForeignType;
import net.forbric.kernel.transform.FluidInteractionsInjector;
import net.forbric.kernel.util.ForbricLog;

/** Restores fluid reaction callbacks without running vanilla's dead interaction loop beside the native registry. */
public final class MixinFluidReactionAdapter {
	static final String LIQUID = "net/minecraft/world/level/block/LiquidBlock";
	static final String FLUID_STATE = "net/minecraft/world/level/material/FluidState";
	static final String CALLBACK = "org/spongepowered/asm/mixin/injection/callback/CallbackInfoReturnable";
	static final String OPERATION = "com/llamalad7/mixinextras/injector/wrapoperation/Operation";
	static final String HANDLER = "(L" + LEVEL + ";" + POS + STATE + CIR + ")V";
	static final String INTERACT = "(L" + LEVEL + ";" + POS + ")Z";
	static final List<String> REGISTRIES = List.of(
			ForeignType.FLUID_INTERACTION_REGISTRY.internal(Ecosystem.NEOFORGE),
			ForeignType.FLUID_INTERACTION_REGISTRY.internal(Ecosystem.FORGE));
	private MixinFluidReactionAdapter() { }

	public static int adapt(ClassNode mixin, Function<String, ClassNode> targets) {
		int changed = repair(mixin, targets);
		if (changed > 0) ForbricLog.info("[Forbric/Mixin] fluid reaction callbacks now follow the native interaction sites");
		return changed;
	}

	/** The rewrite alone; {@link MixinPlayerWorldCallbackAdapter#asLoaded} also runs it for the preflight census. */
	static int repair(ClassNode mixin, Function<String, ClassNode> targets) {
		if (!MixinPlayerWorldCallbackAdapter.enabled() || mixin.methods.stream().anyMatch(m -> m.name.endsWith("$forbricOriginal"))) return 0;
        if (!MixinCallbackShape.targets(mixin, LIQUID)) return 0;
        int tail = afterUnhandled(mixin, targets);
        return tail != 0 ? tail : flowingReaction(mixin, targets);
	}

	private static int afterUnhandled(ClassNode mixin, Function<String, ClassNode> targets) {
		MethodNode original = MixinCallbackShape.unique(mixin, m -> HANDLER.equals(m.desc) && MixinCallbackShape.instance(m) && MixinCallbackShape.kind(m, "Inject") && MixinCallbackShape.selects(m, "shouldSpreadLiquid") && MixinCallbackShape.plainPoint(m, "TAIL", null));
		ClassNode target = targets.apply(LIQUID);
		if (original == null || target == null || (original.access & Opcodes.ACC_STATIC)!=0 || !HANDLER.equals(original.desc)) return 0;
		AnnotationNode inject = MixinFit.injectorOf(original);
		List<AnnotationNode> ats = inject == null ? List.of() : MixinFit.atNodes(inject);
		if (!selects(inject, "shouldSpreadLiquid") || ats.size()!=1 || !"TAIL".equals(MixinFit.value(ats.getFirst(),"value"))) return 0;
		List<MethodNode> hosts = new ArrayList<>(); List<MethodInsnNode> calls = new ArrayList<>();
		for (String name : List.of("onPlace", "neighborChanged")) {
			MethodNode host = named(target,name); if(host==null)return 0;
			List<MethodInsnNode> found = new ArrayList<>();
			for(var i:host.instructions)if(i instanceof MethodInsnNode c && REGISTRIES.contains(c.owner)
					&& c.name.equals("canInteract") && c.desc.equals(INTERACT) && c.getOpcode()==Opcodes.INVOKESTATIC)found.add(c);
			if(found.size()!=1)return 0;
			// The registry's answer decides the fluid tick: true (handled) jumps past scheduleTick. The wrap answers true
			// for a cell a callback converted, so it must keep meaning "handled" here.
			String tick="L"+LEVEL+";scheduleTick("+POS+"Lnet/minecraft/world/level/material/Fluid;I)V";
			if(count(host,tick)!=1||!(next(found.getFirst()) instanceof JumpInsnNode skip)||skip.getOpcode()!=Opcodes.IFNE
					||index(host,skip)>index(host,first(host,tick))||index(host,first(host,tick))>index(host,skip.label))return 0;
			hosts.add(host);calls.add(found.getFirst());
		}
		FabricFluidFlowMixinAdapter.retainOriginal(original,inject);
		for(int i=0;i<hosts.size();i++) {
			MethodNode host=hosts.get(i); MethodInsnNode call=calls.get(i);
			MethodNode wrap = new MethodNode(Opcodes.ACC_PRIVATE, "forbric$unhandledFluidReaction$"+host.name,
					"(L"+LEVEL+";"+POS+"L"+OPERATION+";)Z",null,null);
			wrap.visibleAnnotations = new ArrayList<>(List.of(annotation("WrapOperation",host.name+host.desc,
					List.of(at("L"+call.owner+";"+call.name+call.desc)),false)));
			InsnList code=wrap.instructions;
			code.add(new VarInsnNode(Opcodes.ALOAD,3)); code.add(new InsnNode(Opcodes.ICONST_2));code.add(new TypeInsnNode(Opcodes.ANEWARRAY,"java/lang/Object"));
			for(int n=0;n<2;n++){code.add(new InsnNode(Opcodes.DUP));code.add(new InsnNode(Opcodes.ICONST_0+n));code.add(new VarInsnNode(Opcodes.ALOAD,n+1));code.add(new InsnNode(Opcodes.AASTORE));}
			code.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE,OPERATION,"call","([Ljava/lang/Object;)Ljava/lang/Object;",true));
			code.add(new TypeInsnNode(Opcodes.CHECKCAST,"java/lang/Boolean"));code.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL,"java/lang/Boolean","booleanValue","()Z",false));
			LabelNode handled=new LabelNode();code.add(new JumpInsnNode(Opcodes.IFNE,handled));
			// Only with -Dforbric.fluidInteractions=off: onPlace's MinecraftForge registry is then neutered. Give NeoForge
			// the unhandled reaction before applying the source tail rule. With the repair on, MinecraftForge's registry
			// answers placement itself (vanilla's rules and its mods'), as on MinecraftForge, and asking NeoForge's too
			// would run NeoForge mods' rules on placement, which NeoForge's own onPlace never does.
			if(call.owner.equals(REGISTRIES.get(1))&&!FluidInteractionsInjector.enabled()) {
				code.add(new VarInsnNode(Opcodes.ALOAD,1));code.add(new VarInsnNode(Opcodes.ALOAD,2));
				code.add(new MethodInsnNode(Opcodes.INVOKESTATIC,REGISTRIES.getFirst(),"canInteract",INTERACT,false));
				code.add(new JumpInsnNode(Opcodes.IFNE,handled));
			}
			callback(code,4);
			code.add(new VarInsnNode(Opcodes.ALOAD,0));code.add(new VarInsnNode(Opcodes.ALOAD,1));code.add(new VarInsnNode(Opcodes.ALOAD,2));
			state(code,1,2);code.add(new VarInsnNode(Opcodes.ALOAD,4));
			code.add(new MethodInsnNode(Opcodes.INVOKESPECIAL,mixin.name,original.name,original.desc,false));
			code.add(new VarInsnNode(Opcodes.ALOAD,4));code.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL,CALLBACK,"getReturnValueZ","()Z",false));
			code.add(new InsnNode(Opcodes.ICONST_1));code.add(new InsnNode(Opcodes.IXOR));code.add(new InsnNode(Opcodes.IRETURN));
			code.add(handled);code.add(new FrameNode(Opcodes.F_SAME,0,null,0,null));code.add(new InsnNode(Opcodes.ICONST_1));code.add(new InsnNode(Opcodes.IRETURN));
			wrap.maxStack=6;wrap.maxLocals=5;mixin.methods.add(wrap);
		}
		return 2;
	}

	private static int flowingReaction(ClassNode mixin, Function<String,ClassNode> targets) {
		MethodNode original=MixinCallbackShape.unique(mixin, m -> HANDLER.equals(m.desc) && MixinCallbackShape.instance(m) && MixinCallbackShape.kind(m, "Inject") && MixinCallbackShape.selects(m, "shouldSpreadLiquid") && MixinCallbackShape.plainPoint(m, "INVOKE", "L"+FLUID_STATE+";isSource()Z"));ClassNode liquid=targets.apply(LIQUID);
		if(original==null||liquid==null||(original.access&Opcodes.ACC_STATIC)!=0||!HANDLER.equals(original.desc))return 0;
        // Moving a mixin to the registries is safe only for this closed callback and its target sound helper.
        // Extra fields, interfaces or target methods still belong to LiquidBlock and cannot move with it.
        if(!mixin.fields.isEmpty() || !mixin.interfaces.isEmpty() || !"java/lang/Object".equals(mixin.superName)
                || mixin.methods.stream().anyMatch(m -> m!=original && !m.name.equals("<init>") && !(m.name.equals("fizz") && m.desc.equals("(Lnet/minecraft/world/level/LevelAccessor;"+POS+")V") && shadow(m)))) return 0;
		AnnotationNode inject=MixinFit.injectorOf(original);
		if(!selects(inject,"shouldSpreadLiquid"))return 0;
		List<AnnotationNode> oldAt=MixinFit.atNodes(inject);
		if(oldAt.size()!=1||!("L"+FLUID_STATE+";isSource()Z").equals(MixinFit.value(oldAt.getFirst(),"target")))return 0;
		// Vanilla has a real shouldSpreadLiquid caller, so this adaptation is only for the registry carriers.
		MethodNode onPlace=named(liquid,"onPlace");
		if(onPlace==null||REGISTRIES.stream().noneMatch(r->count(onPlace,"L"+r+";canInteract"+INTERACT)==1))return 0;
		List<AnnotationNode> points=new ArrayList<>();
		// Placement asks MinecraftForge's registry and a neighbour change NeoForge's (FluidInteractionsInjector), so the
		// rule goes into both. With -Dforbric.fluidInteractions=off MinecraftForge's is neutered — no interact call to
		// inject at — and the blackstone fallback hands placement to NeoForge's, which then carries it alone.
		List<String> registries=FluidInteractionsInjector.enabled()?REGISTRIES:List.of(REGISTRIES.getFirst());
		String interact="interact(L"+LEVEL+";"+POS+POS+"L"+FLUID_STATE+";)V";
		for(String registry:registries) {
			ClassNode target=targets.apply(registry);if(target==null)return 0;
			MethodNode method=selector(target,"canInteract"+INTERACT);if(method==null)return 0;
			String member="L"+registry+"$FluidInteraction;"+interact;
			int any=0;for(var i:method.instructions)if(i instanceof MethodInsnNode c&&(c.name+c.desc).equals(interact))any++;
			if(count(method,member)!=1||any!=1)return 0;
			boolean neighbor=false;
			for(var i:method.instructions)if(i instanceof MethodInsnNode c&&c.owner.equals("net/minecraft/core/BlockPos")&&c.name.equals("relative")
					&& next(c) instanceof VarInsnNode v&&v.getOpcode()==Opcodes.ASTORE&&v.var==5)neighbor=true;
			if(!neighbor)return 0;points.add(at(member));
		}
		// One @At for both: each registry calls its own FluidInteraction.interact exactly once (checked above), and a
		// point without an owner matches that one call in either. Two owner-qualified points would each miss in the
		// other registry, which Mixin tolerates but the preflight census reads as a half-applied mixin.
		if(points.size()>1)points=List.of(at(interact));
		MethodNode fizz=selector(liquid,"fizz(Lnet/minecraft/world/level/LevelAccessor;"+POS+")V");
		if(fizz==null||(fizz.access&Opcodes.ACC_STATIC)!=0||count(fizz,"Lnet/minecraft/world/level/LevelAccessor;levelEvent(I"+POS+"I)V")!=1)return 0;
		MethodInsnNode fizzCall=null;VarInsnNode receiver=null;int thisLoads=0;
		for(var i:original.instructions) {
			if(i instanceof VarInsnNode v&&v.var==0)thisLoads++;
			if(i instanceof FieldInsnNode f&&f.getOpcode()!=Opcodes.GETSTATIC&&f.getOpcode()!=Opcodes.PUTSTATIC)return 0;
			if(i instanceof MethodInsnNode c&&c.owner.equals(mixin.name)&&c.name.equals("fizz")) {
				if(fizzCall!=null)return 0;fizzCall=c;
				var first=previous(previous(previous(c)));
				if(!(first instanceof VarInsnNode v)||v.var!=0||v.getOpcode()!=Opcodes.ALOAD)return 0;receiver=v;
			}
		}
		if(thisLoads!=1||fizzCall==null)return 0;
		if(mixin.invisibleAnnotations==null)return 0;
		AnnotationNode declaration=mixin.invisibleAnnotations.stream().filter(a->a.desc.equals("Lorg/spongepowered/asm/mixin/Mixin;")).findFirst().orElse(null);
		if(declaration==null)return 0;
		set(declaration,"value",registries.stream().map(Type::getObjectType).toList());
		FabricFluidFlowMixinAdapter.retainOriginal(original,inject);
		original.instructions.remove(receiver);fizzCall.name="forbric$fluidReactionFizz";fizzCall.setOpcode(Opcodes.INVOKESTATIC);fizzCall.itf=false;
		makeStatic(original,mixin.name);
		mixin.methods.removeIf(m->m.name.equals("fizz"));
		MethodNode f=new MethodNode(Opcodes.ASM9,fizz.access,fizz.name,fizz.desc,fizz.signature,fizz.exceptions.toArray(String[]::new));fizz.accept(f);f.name="forbric$fluidReactionFizz";f.visibleAnnotations=null;f.invisibleAnnotations=null;
		makeStatic(f,LIQUID);MixinCallbackShape.uniqueMember(f);mixin.methods.add(f);
		MethodNode outer=new MethodNode(Opcodes.ACC_PRIVATE|Opcodes.ACC_STATIC,"forbric$flowingFluidReaction",
				"(L"+LEVEL+";"+POS+CIR+POS+")V",null,null);
		outer.visibleAnnotations=new ArrayList<>(List.of(annotation("Inject","canInteract"+INTERACT,points,true)));
		outer.invisibleParameterAnnotations=local(4,3,5);outer.invisibleAnnotableParameterCount=4;
		InsnList code=outer.instructions;LabelNode done=new LabelNode();
		// At this point the registry already chose its first matching interaction. Only the vanilla flowing
		// lava/water pair is replaced; sources, other fluids and the earlier basalt interaction remain native.
		fluid(code,0,1);code.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL,FLUID_STATE,"getType","()Lnet/minecraft/world/level/material/Fluid;",false));
		code.add(new FieldInsnNode(Opcodes.GETSTATIC,"net/minecraft/world/level/material/Fluids","FLOWING_LAVA","Lnet/minecraft/world/level/material/FlowingFluid;"));code.add(new JumpInsnNode(Opcodes.IF_ACMPNE,done));
		fluid(code,0,3);code.add(new FieldInsnNode(Opcodes.GETSTATIC,"net/minecraft/tags/FluidTags","WATER","Lnet/minecraft/tags/TagKey;"));
		code.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL,FLUID_STATE,"is","(Lnet/minecraft/tags/TagKey;)Z",false));code.add(new JumpInsnNode(Opcodes.IFEQ,done));
		callback(code,4);code.add(new VarInsnNode(Opcodes.ALOAD,0));code.add(new VarInsnNode(Opcodes.ALOAD,1));state(code,0,1);code.add(new VarInsnNode(Opcodes.ALOAD,4));
		code.add(new MethodInsnNode(Opcodes.INVOKESTATIC,mixin.name,original.name,original.desc,false));
		code.add(new VarInsnNode(Opcodes.ALOAD,4));code.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL,CALLBACK,"isCancelled","()Z",false));code.add(new JumpInsnNode(Opcodes.IFEQ,done));
		code.add(new VarInsnNode(Opcodes.ALOAD,2));code.add(new FieldInsnNode(Opcodes.GETSTATIC,"java/lang/Boolean","TRUE","Ljava/lang/Boolean;"));
		code.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL,CALLBACK,"setReturnValue","(Ljava/lang/Object;)V",false));
		code.add(done);code.add(new FrameNode(Opcodes.F_SAME,0,null,0,null));code.add(new InsnNode(Opcodes.RETURN));outer.maxStack=6;outer.maxLocals=5;
		mixin.methods.add(outer);
		return 1;
	}

    private static boolean shadow(MethodNode method) {
        return java.util.stream.Stream.of(method.visibleAnnotations,method.invisibleAnnotations).filter(java.util.Objects::nonNull)
                .flatMap(java.util.Collection::stream).anyMatch(a -> a.desc.equals("Lorg/spongepowered/asm/mixin/Shadow;"));
    }

	private static void makeStatic(MethodNode m,String owner) {
		m.access=(m.access&~(Opcodes.ACC_PUBLIC|Opcodes.ACC_PROTECTED|Opcodes.ACC_ABSTRACT))|Opcodes.ACC_PRIVATE|Opcodes.ACC_STATIC;
		for(var i:m.instructions) {
			if(i instanceof VarInsnNode v)v.var--;
			if(i instanceof IincInsnNode v)v.var--;
			if(i instanceof FrameNode frame&&(frame.type==Opcodes.F_FULL||frame.type==Opcodes.F_NEW)&&frame.local!=null&&!frame.local.isEmpty()&&frame.local.getFirst().equals(owner))frame.local.removeFirst();
		}
		if(m.localVariables!=null){m.localVariables.removeIf(v->v.index==0);for(var v:m.localVariables)v.index--;}
		m.maxLocals--;
	}
	private static void callback(InsnList c,int slot) {
		c.add(new TypeInsnNode(Opcodes.NEW,CALLBACK));c.add(new InsnNode(Opcodes.DUP));c.add(new LdcInsnNode("forbricFluidReaction"));c.add(new InsnNode(Opcodes.ICONST_1));
		c.add(new FieldInsnNode(Opcodes.GETSTATIC,"java/lang/Boolean","TRUE","Ljava/lang/Boolean;"));
		c.add(new MethodInsnNode(Opcodes.INVOKESPECIAL,CALLBACK,"<init>","(Ljava/lang/String;ZLjava/lang/Object;)V",false));c.add(new VarInsnNode(Opcodes.ASTORE,slot));
	}
	private static void state(InsnList c,int level,int pos){c.add(new VarInsnNode(Opcodes.ALOAD,level));c.add(new VarInsnNode(Opcodes.ALOAD,pos));c.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL,LEVEL,"getBlockState","("+POS+")"+STATE,false));}
	private static void fluid(InsnList c,int level,int pos){c.add(new VarInsnNode(Opcodes.ALOAD,level));c.add(new VarInsnNode(Opcodes.ALOAD,pos));c.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL,LEVEL,"getFluidState","("+POS+")L"+FLUID_STATE+";",false));}
	private static AnnotationNode at(String target){AnnotationNode a=new AnnotationNode("Lorg/spongepowered/asm/mixin/injection/At;");a.values=new ArrayList<>(List.of("value","INVOKE","target",target));return a;}
	private static AnnotationNode annotation(String kind,String method,List<AnnotationNode> ats,boolean cancel){
		AnnotationNode a=new AnnotationNode(kind.equals("Inject")?"Lorg/spongepowered/asm/mixin/injection/Inject;":"Lcom/llamalad7/mixinextras/injector/wrapoperation/WrapOperation;");
		a.values=new ArrayList<>(List.of("method",List.of(method),"at",kind.equals("Inject")?ats:ats.getFirst(),"require",1));
		if(cancel){a.values.add("cancellable");a.values.add(true);}return a;
	}
}
