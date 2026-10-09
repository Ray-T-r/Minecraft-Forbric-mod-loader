/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.mixin;

import static net.forbric.kernel.mixin.MixinPlayerWorldCallbackAdapter.*;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.*;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.AnalyzerException;
import org.objectweb.asm.tree.analysis.Frame;
import org.objectweb.asm.tree.analysis.SourceInterpreter;
import org.objectweb.asm.tree.analysis.SourceValue;
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
	/**
	 * The native method the fluid callbacks are written for, however their selectors spell it. Its bare name stays a
	 * string of the kernel (joined at class initialisation, not folded into one constant): the uncalled-method census
	 * reads a kernel string naming a method as a repair that may make its callbacks run, and these adapters are that
	 * repair for shouldSpreadLiquid — without it, MixinFit would call the very callbacks they relocate dead.
	 */
	static final String SPREAD = String.join("", "shouldSpreadLiquid", "(L" + LEVEL + ";" + POS + STATE + ")Z");
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
		ClassNode target = targets.apply(LIQUID);
		MethodNode original = MixinCallbackShape.unique(mixin, m -> MixinCallbackShape.shape(m, HANDLER) && MixinCallbackShape.instance(m) && MixinCallbackShape.kind(m, "Inject") && MixinCallbackShape.binds(m, target, SPREAD) && MixinCallbackShape.plainPoint(m, "TAIL", null));
		if (original == null || target == null || (original.access & Opcodes.ACC_STATIC)!=0 || !HANDLER.equals(original.desc)) return 0;
		AnnotationNode inject = MixinFit.injectorOf(original);
		List<AnnotationNode> ats = inject == null ? List.of() : MixinFit.atNodes(inject);
		if (!MixinCallbackShape.binds(original, target, SPREAD) || ats.size()!=1 || !"TAIL".equals(MixinFit.value(ats.getFirst(),"value"))) return 0;
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

	/**
	 * A callback at vanilla's lava-meets-water reaction — {@code INVOKE FluidState.isSource} in {@code shouldSpreadLiquid},
	 * the point vanilla reaches for lava, source or flowing, beside water — moves to where the registries now make that
	 * reaction: right before the interaction their {@code canInteract} chose, when the cell is lava and the neighbour that
	 * interaction is for is water, which is vanilla's own test for reaching the point. Whatever else the handler decides
	 * (source or flowing, which block, a fizz or none) is the handler's: it gets a fresh callback, as Mixin's own would be at
	 * that point, and if it cancels, canInteract answers what {@code shouldSpreadLiquid} would have returned — false (the
	 * cell reacted) is "handled", true is "not handled, tick" — and the registry's own interaction does not run.
	 *
	 * <p>The whole mixin moves to the registries, so it may hold nothing else that belongs to the liquid block: no fields
	 * or interfaces; besides the handler only its constructor, static helpers that move with it, and shadows of liquid-block
	 * methods whose bodies never read the block (they move as static copies). The handler may read {@code this} only as
	 * the receiver of such a shadow call. One such handler per mixin; any other shape stays as compiled.
	 */
	private static int flowingReaction(ClassNode mixin, Function<String,ClassNode> targets) {
		ClassNode liquid=targets.apply(LIQUID);
		if(liquid==null)return 0;
		MethodNode original=MixinCallbackShape.unique(mixin, m -> MixinCallbackShape.shape(m, HANDLER) && MixinCallbackShape.instance(m)
				&& MixinCallbackShape.kind(m, "Inject") && MixinCallbackShape.binds(m, liquid, SPREAD) && reactionPoint(m));
		if(original==null||!HANDLER.equals(original.desc))return 0;
		if(!mixin.fields.isEmpty()||!mixin.interfaces.isEmpty()||!"java/lang/Object".equals(mixin.superName))return 0;
		Set<String> hierarchy=hierarchy(liquid,targets);
		Map<String,MethodNode> relocated=new LinkedHashMap<>();
		for(MethodNode m:mixin.methods) {
			if(m==original||m.name.equals("<init>"))continue;
			if(shadow(m)) {
				MethodNode body=(m.access&Opcodes.ACC_STATIC)!=0?null:selector(liquid,m.name+m.desc);
				if(body==null||(body.access&(Opcodes.ACC_STATIC|Opcodes.ACC_ABSTRACT))!=0||!receiverFree(body,hierarchy))return 0;
				relocated.put(m.name+m.desc,body);
			} else if((m.access&Opcodes.ACC_STATIC)==0||MixinFit.injectorOf(m)!=null)return 0;
		}
		Map<MethodInsnNode,VarInsnNode> calls=shadowCalls(mixin.name,original,relocated.keySet());
		if(calls==null)return 0;
		AnnotationNode inject=MixinFit.injectorOf(original);
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
		AnnotationNode declaration=declaration(mixin);
		if(declaration==null)return 0;
		// The class form and the string form name the same targets; the registries replace both.
		set(declaration,"value",registries.stream().map(Type::getObjectType).toList());
		remove(declaration,"targets");
		FabricFluidFlowMixinAdapter.retainOriginal(original,inject);
		for(var call:calls.entrySet()) {
			original.instructions.remove(call.getValue());
			MethodInsnNode c=call.getKey();
			c.name=relocatedName(c.name);c.setOpcode(Opcodes.INVOKESTATIC);c.itf=false;
		}
		makeStatic(original,mixin.name);
		mixin.methods.removeIf(m->relocated.containsKey(m.name+m.desc)&&shadow(m));
		for(MethodNode body:relocated.values()) {
			MethodNode f=new MethodNode(Opcodes.ASM9,body.access,body.name,body.desc,body.signature,body.exceptions.toArray(String[]::new));
			body.accept(f);f.name=relocatedName(body.name);f.visibleAnnotations=null;f.invisibleAnnotations=null;
			f.visibleParameterAnnotations=null;f.invisibleParameterAnnotations=null;
			makeStatic(f,LIQUID);MixinCallbackShape.uniqueMember(f);mixin.methods.add(f);
		}
		MethodNode outer=new MethodNode(Opcodes.ACC_PRIVATE|Opcodes.ACC_STATIC,"forbric$flowingFluidReaction",
				"(L"+LEVEL+";"+POS+CIR+POS+")V",null,null);
		outer.visibleAnnotations=new ArrayList<>(List.of(annotation("Inject","canInteract"+INTERACT,points,true)));
		outer.invisibleParameterAnnotations=local(4,3,5);outer.invisibleAnnotableParameterCount=4;
		InsnList code=outer.instructions;LabelNode done=new LabelNode();
		// The registry already chose its first matching interaction for this neighbour. Vanilla reaches the point for lava
		// beside water: the same test, on this cell and this neighbour. What the handler then does is its own.
		fluid(code,0,1);code.add(new FieldInsnNode(Opcodes.GETSTATIC,"net/minecraft/tags/FluidTags","LAVA","Lnet/minecraft/tags/TagKey;"));
		code.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL,FLUID_STATE,"is","(Lnet/minecraft/tags/TagKey;)Z",false));code.add(new JumpInsnNode(Opcodes.IFEQ,done));
		fluid(code,0,3);code.add(new FieldInsnNode(Opcodes.GETSTATIC,"net/minecraft/tags/FluidTags","WATER","Lnet/minecraft/tags/TagKey;"));
		code.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL,FLUID_STATE,"is","(Lnet/minecraft/tags/TagKey;)Z",false));code.add(new JumpInsnNode(Opcodes.IFEQ,done));
		// A callback at an INVOKE point starts without a return value, as Mixin's own does there.
		code.add(new TypeInsnNode(Opcodes.NEW,CALLBACK));code.add(new InsnNode(Opcodes.DUP));code.add(new LdcInsnNode("forbricFluidReaction"));code.add(new InsnNode(Opcodes.ICONST_1));
		code.add(new MethodInsnNode(Opcodes.INVOKESPECIAL,CALLBACK,"<init>","(Ljava/lang/String;Z)V",false));code.add(new VarInsnNode(Opcodes.ASTORE,4));
		code.add(new VarInsnNode(Opcodes.ALOAD,0));code.add(new VarInsnNode(Opcodes.ALOAD,1));state(code,0,1);code.add(new VarInsnNode(Opcodes.ALOAD,4));
		code.add(new MethodInsnNode(Opcodes.INVOKESTATIC,mixin.name,original.name,original.desc,false));
		code.add(new VarInsnNode(Opcodes.ALOAD,4));code.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL,CALLBACK,"isCancelled","()Z",false));code.add(new JumpInsnNode(Opcodes.IFEQ,done));
		// Cancelled, shouldSpreadLiquid would have returned the handler's value: false (the cell reacted) is the registry's
		// "handled", true is "not handled" and the fluid ticks; either way the registry's own interaction does not run.
		code.add(new VarInsnNode(Opcodes.ALOAD,2));code.add(new VarInsnNode(Opcodes.ALOAD,4));
		code.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL,CALLBACK,"getReturnValueZ","()Z",false));code.add(new InsnNode(Opcodes.ICONST_1));code.add(new InsnNode(Opcodes.IXOR));
		code.add(new MethodInsnNode(Opcodes.INVOKESTATIC,"java/lang/Boolean","valueOf","(Z)Ljava/lang/Boolean;",false));
		code.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL,CALLBACK,"setReturnValue","(Ljava/lang/Object;)V",false));
		code.add(done);code.add(new FrameNode(Opcodes.F_SAME,0,null,0,null));code.add(new InsnNode(Opcodes.RETURN));outer.maxStack=6;outer.maxLocals=5;
		mixin.methods.add(outer);
		return 1;
	}

	/** The reaction point: before {@code FluidState.isSource}, the one call of it vanilla's shouldSpreadLiquid makes. */
	private static boolean reactionPoint(MethodNode m) {
		if(!MixinCallbackShape.beforePoint(m,"INVOKE","L"+FLUID_STATE+";isSource()Z"))return false;
		Object ordinal=MixinFit.value(MixinFit.atNodes(MixinFit.injectorOf(m)).getFirst(),"ordinal");
		return ordinal==null||Integer.valueOf(-1).equals(ordinal)||Integer.valueOf(0).equals(ordinal);
	}

	/** The static copy of a relocated shadow: {@code fizz} becomes {@code forbric$fluidReactionFizz}. */
	private static String relocatedName(String shadow) {
		return "forbric$fluidReaction"+Character.toUpperCase(shadow.charAt(0))+shadow.substring(1);
	}

	/** The liquid block and its superclasses, as far as {@code targets} resolves them. */
	private static Set<String> hierarchy(ClassNode liquid,Function<String,ClassNode> targets) {
		Set<String> out=new HashSet<>();
		for(ClassNode c=liquid;c!=null&&out.add(c.name)&&c.superName!=null&&!"java/lang/Object".equals(c.superName);)c=targets.apply(c.superName);
		return out;
	}

	/**
	 * A body that can run as a static copy outside the block: it never reads its receiver, names no member of the block's
	 * own hierarchy (whose private and protected members another class cannot reach) and makes no dynamic call.
	 */
	private static boolean receiverFree(MethodNode body,Set<String> hierarchy) {
		for(var i:body.instructions) {
			if(i instanceof VarInsnNode v&&v.var==0||i instanceof IincInsnNode n&&n.var==0||i instanceof InvokeDynamicInsnNode)return false;
			if(i instanceof MethodInsnNode c&&hierarchy.contains(c.owner)||i instanceof FieldInsnNode f&&hierarchy.contains(f.owner))return false;
		}
		return true;
	}

	/**
	 * Each call of a relocated shadow in {@code handler}, with the {@code aload_0} that is its receiver; null unless every
	 * read of {@code this} in the handler is one such receiver, used by that call alone.
	 */
	private static Map<MethodInsnNode,VarInsnNode> shadowCalls(String owner,MethodNode handler,Set<String> relocated) {
		Frame<SourceValue>[] frames;
		try { frames=new Analyzer<>(new SourceInterpreter()).analyze(owner,handler); }
		catch(AnalyzerException|RuntimeException unanalysable){ return null; }
		Map<MethodInsnNode,VarInsnNode> calls=new LinkedHashMap<>();
		Set<AbstractInsnNode> receivers=Collections.newSetFromMap(new IdentityHashMap<>());
		for(var i:handler.instructions) {
			if(i instanceof IincInsnNode n&&n.var==0)return null;
			if(!(i instanceof MethodInsnNode c)||!c.owner.equals(owner)||!relocated.contains(c.name+c.desc))continue;
			if(c.getOpcode()!=Opcodes.INVOKEVIRTUAL&&c.getOpcode()!=Opcodes.INVOKESPECIAL)return null;
			Frame<SourceValue> frame=frames[handler.instructions.indexOf(c)];
			if(frame==null)return null;
			SourceValue receiver=frame.getStack(frame.getStackSize()-Type.getArgumentTypes(c.desc).length-1);
			if(receiver.insns.size()!=1||!(receiver.insns.iterator().next() instanceof VarInsnNode load)
					||load.getOpcode()!=Opcodes.ALOAD||load.var!=0||!receivers.add(load))return null;
			calls.put(c,load);
		}
		for(var i:handler.instructions)if(i instanceof VarInsnNode v&&v.var==0&&!receivers.contains(v))return null;
		return calls;
	}

	private static AnnotationNode declaration(ClassNode mixin) {
		for(List<AnnotationNode> annotations:Arrays.asList(mixin.invisibleAnnotations,mixin.visibleAnnotations)) {
			if(annotations!=null)for(AnnotationNode a:annotations)if(a.desc.equals("Lorg/spongepowered/asm/mixin/Mixin;"))return a;
		}
		return null;
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
