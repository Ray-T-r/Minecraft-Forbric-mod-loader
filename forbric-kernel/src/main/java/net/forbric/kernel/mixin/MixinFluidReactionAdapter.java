/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.mixin;

import static net.forbric.kernel.mixin.MixinPlayerWorldCallbackAdapter.*;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import java.util.function.Predicate;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;
import net.forbric.api.Ecosystem;
import net.forbric.api.ForeignType;
import net.forbric.kernel.util.ForbricLog;

/**
 * Runs a Fabric mod's fluid callback on {@code shouldSpreadLiquid} where vanilla runs it, on a liquid block whose
 * neighbour change asks NeoForge's fluid interaction registry instead ({@link #neighbourChangeOnly}): a TAIL handler, a
 * handler at vanilla's reaction point, or a HEAD handler (fabric-block-api's flow veto, Create Fly's fluid reaction).
 *
 * <p>Two older rewrites served the merged base, whose placement asked MinecraftForge's registry and whose neighbour change
 * asked NeoForge's, so neither host called {@code shouldSpreadLiquid}: a TAIL handler wrapped into both registry calls, and
 * a reaction-point handler moved whole into both registries' {@code canInteract}. Both needed placement to ask a registry;
 * NeoForge's own base asks {@code shouldSpreadLiquid} there, so they never applied and went with the merged base.
 */
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
	/**
	 * Off: no wrap on a neighbour change for a base whose placement asks vanilla's {@code shouldSpreadLiquid} and whose
	 * neighbour change asks a registry ({@link #neighbourChangeOnly}); the handler then runs on placement only.
	 */
	public static final String NEIGHBOUR_PROPERTY = "forbric.fluidReactionNeighbourChange";
	/**
	 * Off: a HEAD handler gets no wrap on a neighbour change (the TAIL and reaction-point wraps stay); it then runs on
	 * placement only, as NeoForge's base runs it.
	 */
	public static final String HEAD_PROPERTY = "forbric.fluidReactionNeighbourChange.head";
	static final String RUNTIME = "net/forbric/kernel/runtime/KernelFluidReactions";
	static final String REACHES = "(L" + LIQUID + ";L" + LEVEL + ";" + POS + ")Z";
	static final String TICK = "L" + LEVEL + ";scheduleTick(" + POS + "Lnet/minecraft/world/level/material/Fluid;I)V";
	private MixinFluidReactionAdapter() { }

	static boolean neighbourChangeEnabled() {
		return !"off".equalsIgnoreCase(net.forbric.kernel.util.ForbricSwitches.get(NEIGHBOUR_PROPERTY, "on"));
	}

	static boolean headEnabled() {
		return !"off".equalsIgnoreCase(net.forbric.kernel.util.ForbricSwitches.get(HEAD_PROPERTY, "on"));
	}

	public static int adapt(ClassNode mixin, Function<String, ClassNode> targets) {
		int changed = repair(mixin, targets);
		if (changed > 0) ForbricLog.info("[Forbric/Mixin] fluid reaction callbacks now follow the native interaction sites");
		return changed;
	}

	/** The rewrite alone; {@link MixinPlayerWorldCallbackAdapter#asLoaded} also runs it for the preflight census. */
	static int repair(ClassNode mixin, Function<String, ClassNode> targets) {
		if (!MixinPlayerWorldCallbackAdapter.enabled() || mixin.methods.stream().anyMatch(m -> m.name.endsWith("$forbricOriginal"))) return 0;
        if (!MixinCallbackShape.targets(mixin, LIQUID)) return 0;
        return neighbourChangeOnly(mixin, targets);
	}

	/**
	 * The registry's answer decides the fluid tick: true (handled) jumps past the host's one scheduleTick. A wrap answers
	 * true for a cell a callback converted, so it must keep meaning "handled" there.
	 */
	private static boolean handledSkipsTick(MethodNode host, MethodInsnNode call) {
		return count(host,TICK)==1&&next(call) instanceof JumpInsnNode skip&&skip.getOpcode()==Opcodes.IFNE
				&&index(host,skip)<=index(host,first(host,TICK))&&index(host,first(host,TICK))<=index(host,skip.label);
	}

	/**
	 * {@code @WrapOperation} of the registry's {@code call} in {@code host}: the registry first; where it answers "not
	 * handled", the TAIL handler {@code callee} runs as at the end of {@code shouldSpreadLiquid}, with a callback holding the
	 * {@code true} it was about to return, and its answer, negated, is the registry's.
	 */
	private static MethodNode unhandledWrap(ClassNode mixin, String name, MethodNode host, MethodInsnNode call, MethodNode callee) {
		MethodNode wrap = new MethodNode(Opcodes.ACC_PRIVATE, name, "(L"+LEVEL+";"+POS+"L"+OPERATION+";)Z",null,null);
		wrap.visibleAnnotations = new ArrayList<>(List.of(wrapOperation(host.name+host.desc,at("L"+call.owner+";"+call.name+call.desc))));
		InsnList code=wrap.instructions;
		askRegistry(code);
		LabelNode handled=new LabelNode();code.add(new JumpInsnNode(Opcodes.IFNE,handled));
		callback(code,4);
		code.add(new VarInsnNode(Opcodes.ALOAD,0));code.add(new VarInsnNode(Opcodes.ALOAD,1));code.add(new VarInsnNode(Opcodes.ALOAD,2));
		state(code,1,2);code.add(new VarInsnNode(Opcodes.ALOAD,4));
		code.add(new MethodInsnNode(Opcodes.INVOKESPECIAL,mixin.name,callee.name,callee.desc,false));
		code.add(new VarInsnNode(Opcodes.ALOAD,4));code.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL,CALLBACK,"getReturnValueZ","()Z",false));
		code.add(new InsnNode(Opcodes.ICONST_1));code.add(new InsnNode(Opcodes.IXOR));code.add(new InsnNode(Opcodes.IRETURN));
		code.add(handled);code.add(new FrameNode(Opcodes.F_SAME,0,null,0,null));code.add(new InsnNode(Opcodes.ICONST_1));code.add(new InsnNode(Opcodes.IRETURN));
		wrap.maxStack=6;wrap.maxLocals=5;
		return wrap;
	}

	/** {@code operation.call(level, pos)} as a boolean on the stack: the registry's own answer, its interaction made. */
	private static void askRegistry(InsnList code) {
		code.add(new VarInsnNode(Opcodes.ALOAD,3)); code.add(new InsnNode(Opcodes.ICONST_2));code.add(new TypeInsnNode(Opcodes.ANEWARRAY,"java/lang/Object"));
		for(int n=0;n<2;n++){code.add(new InsnNode(Opcodes.DUP));code.add(new InsnNode(Opcodes.ICONST_0+n));code.add(new VarInsnNode(Opcodes.ALOAD,n+1));code.add(new InsnNode(Opcodes.AASTORE));}
		code.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE,OPERATION,"call","([Ljava/lang/Object;)Ljava/lang/Object;",true));
		code.add(new TypeInsnNode(Opcodes.CHECKCAST,"java/lang/Boolean"));code.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL,"java/lang/Boolean","booleanValue","()Z",false));
	}

	/**
	 * A base that asks vanilla's {@code shouldSpreadLiquid} on placement and a registry on a neighbour change: NeoForge
	 * 26.2.0.93's {@code LiquidBlock} (javap: {@code onPlace} calls {@code shouldSpreadLiquid} at offset 4, {@code neighborChanged}
	 * calls {@code FluidInteractionRegistry.canInteract} at offset 2 and never {@code shouldSpreadLiquid}). Vanilla asks
	 * {@code shouldSpreadLiquid} in both, so a Fabric handler in it runs on placement and on a neighbour change; here it binds
	 * as compiled and runs on placement alone. Carpet's renewable blackstone (a TAIL hook) and renewable deepslate (a hook
	 * before {@code FluidState.isSource}, vanilla's lava-meets-water reaction) left lava under blue ice as lava, and flowing
	 * lava below zero beside water as cobblestone, whenever the block beside it was the one that changed.
	 *
	 * <p>The handler keeps its injector, and a copy of it ({@code $forbricOriginal}, {@code @Unique}) runs from a new
	 * {@code @WrapOperation} of the neighbour change's registry call, where vanilla would have run it: a TAIL handler where
	 * the registry answers "not handled" ({@link #unhandledWrap}); the reaction-point handler before the registry, when
	 * vanilla's walk reaches the point for this cell ({@code KernelFluidReactions.reachesWaterReaction}), with a fresh
	 * callback as Mixin's own at an INVOKE point; a HEAD handler before the registry every time, as vanilla enters
	 * {@code shouldSpreadLiquid} on every neighbour change, with a fresh callback as Mixin's own at HEAD. If the reaction-point
	 * or HEAD handler cancels, its answer, negated, is the registry's and the registry's interaction does not run (a HEAD
	 * cancel with false: no tick and no reaction, as natively), else the registry runs as written. One such handler per
	 * mixin (a TAIL or reaction-point handler first, a HEAD handler only where the mixin has neither), an instance
	 * {@code @Inject} that Mixin binds to {@code shouldSpreadLiquid} alone, no slice or group; only a mixin known to be a
	 * Fabric mod's (on NeoForge a NeoForge mod's runs on placement alone too, and
	 * a mixin whose family is not known — a config two families claim — is left alone, as {@link MixinShearsRelay} and
	 * {@link FabricItemDamageMixinAdapter} leave it). Placement and the neighbour change
	 * must each make their one call, and the registry's answer must skip the tick. {@link #NEIGHBOUR_PROPERTY} off leaves the
	 * handler as compiled; {@link #HEAD_PROPERTY} off leaves a HEAD handler alone.
	 *
	 * <p>On the merged base {@code NativeEntryRelay} served a HEAD handler instead: nothing there called
	 * {@code shouldSpreadLiquid}, so it asked the method again in both hosts. NeoForge's base calls it on placement, so that
	 * relay plans nothing here, and this HEAD wrap carries the neighbour change.
	 */
	private static int neighbourChangeOnly(ClassNode mixin, Function<String, ClassNode> targets) {
		if (!neighbourChangeEnabled() || MixinStubRebind.ecosystemOf(mixin.name) != Ecosystem.FABRIC) return 0;
		ClassNode liquid = targets.apply(LIQUID);
		if (liquid == null) return 0;
		MethodNode onPlace = named(liquid, "onPlace"), changed = named(liquid, "neighborChanged");
		if (onPlace == null || changed == null || spreadCalls(onPlace) != 1 || !registryCalls(onPlace).isEmpty()) return 0;
		List<MethodInsnNode> asked = registryCalls(changed);
		if (asked.size() != 1 || spreadCalls(changed) != 0 || !handledSkipsTick(changed, asked.getFirst())) return 0;
		Predicate<MethodNode> callback = m -> MixinCallbackShape.shape(m, HANDLER) && MixinCallbackShape.instance(m)
				&& MixinCallbackShape.kind(m, "Inject") && MixinCallbackShape.binds(m, liquid, SPREAD);
		MethodNode handler = MixinCallbackShape.unique(mixin, m -> callback.test(m) && (MixinCallbackShape.plainPoint(m, "TAIL", null) || reactionPoint(m)));
		if (handler == null && headEnabled()) handler = MixinCallbackShape.unique(mixin, m -> callback.test(m) && MixinCallbackShape.plainPoint(m, "HEAD", null));
		if (handler == null || !HANDLER.equals(handler.desc)) return 0;
		boolean tail = MixinCallbackShape.plainPoint(handler, "TAIL", null), head = MixinCallbackShape.plainPoint(handler, "HEAD", null);
		MethodNode copy = MixinShearsRelay.copyOf(handler, handler.name + "$forbricOriginal");
		copy.access = (handler.access & ~(Opcodes.ACC_PUBLIC | Opcodes.ACC_PROTECTED)) | Opcodes.ACC_PRIVATE;
		copy.visibleAnnotations = new ArrayList<>(List.of(new AnnotationNode("Lorg/spongepowered/asm/mixin/Unique;")));
		mixin.methods.add(copy);
		MethodInsnNode call = asked.getFirst();
		String name = "forbric$neighbourFluidReaction";
		mixin.methods.add(tail ? unhandledWrap(mixin, name, changed, call, copy) : reactionWrap(mixin, name, changed, call, copy, !head));
		ForbricLog.info("[Forbric/Mixin] %s: %s now also runs on a neighbour change, at %s's canInteract in %s — the base's "
				+ "placement asks vanilla's shouldSpreadLiquid, where it binds as compiled, and its neighbour change asks the registry",
				mixin.name.replace('/', '.'), handler.name, call.owner.substring(call.owner.lastIndexOf('/') + 1), changed.name);
		return 1;
	}

	/**
	 * {@code @WrapOperation} of the registry's {@code call} in {@code host} for a handler at vanilla's reaction point
	 * ({@code reached}: only when vanilla's walk reaches the point for this cell) or at HEAD (every time): {@code callee}
	 * runs with a fresh callback; cancelled, its answer negated is the registry's and the registry's interaction does not
	 * run; otherwise (and when the walk does not reach the point) the registry runs as written.
	 */
	private static MethodNode reactionWrap(ClassNode mixin, String name, MethodNode host, MethodInsnNode call, MethodNode callee, boolean reached) {
		MethodNode wrap = new MethodNode(Opcodes.ACC_PRIVATE, name, "(L"+LEVEL+";"+POS+"L"+OPERATION+";)Z",null,null);
		wrap.visibleAnnotations = new ArrayList<>(List.of(wrapOperation(host.name+host.desc,at("L"+call.owner+";"+call.name+call.desc))));
		InsnList code=wrap.instructions;LabelNode registry=new LabelNode();
		if (reached) {
			code.add(new VarInsnNode(Opcodes.ALOAD,0));code.add(new TypeInsnNode(Opcodes.CHECKCAST,LIQUID));
			code.add(new VarInsnNode(Opcodes.ALOAD,1));code.add(new VarInsnNode(Opcodes.ALOAD,2));
			code.add(new MethodInsnNode(Opcodes.INVOKESTATIC,RUNTIME,"reachesWaterReaction",REACHES,false));code.add(new JumpInsnNode(Opcodes.IFEQ,registry));
		}
		// A callback at HEAD or an INVOKE point starts without a return value, as Mixin's own does there.
		code.add(new TypeInsnNode(Opcodes.NEW,CALLBACK));code.add(new InsnNode(Opcodes.DUP));code.add(new LdcInsnNode("forbricFluidReaction"));code.add(new InsnNode(Opcodes.ICONST_1));
		code.add(new MethodInsnNode(Opcodes.INVOKESPECIAL,CALLBACK,"<init>","(Ljava/lang/String;Z)V",false));code.add(new VarInsnNode(Opcodes.ASTORE,4));
		code.add(new VarInsnNode(Opcodes.ALOAD,0));code.add(new VarInsnNode(Opcodes.ALOAD,1));code.add(new VarInsnNode(Opcodes.ALOAD,2));
		state(code,1,2);code.add(new VarInsnNode(Opcodes.ALOAD,4));
		code.add(new MethodInsnNode(Opcodes.INVOKESPECIAL,mixin.name,callee.name,callee.desc,false));
		code.add(new VarInsnNode(Opcodes.ALOAD,4));code.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL,CALLBACK,"isCancelled","()Z",false));code.add(new JumpInsnNode(Opcodes.IFEQ,registry));
		code.add(new VarInsnNode(Opcodes.ALOAD,4));code.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL,CALLBACK,"getReturnValueZ","()Z",false));
		code.add(new InsnNode(Opcodes.ICONST_1));code.add(new InsnNode(Opcodes.IXOR));code.add(new InsnNode(Opcodes.IRETURN));
		code.add(registry);code.add(new FrameNode(Opcodes.F_SAME,0,null,0,null));
		askRegistry(code);code.add(new InsnNode(Opcodes.IRETURN));
		wrap.maxStack=6;wrap.maxLocals=5;
		return wrap;
	}

	/** Calls of vanilla's {@code shouldSpreadLiquid} on the liquid block in {@code host}. */
	private static int spreadCalls(MethodNode host) {
		int n=0;
		for(var i:host.instructions)if(i instanceof MethodInsnNode c&&c.owner.equals(LIQUID)&&(c.name+c.desc).equals(SPREAD)
				&&(c.getOpcode()==Opcodes.INVOKEVIRTUAL||c.getOpcode()==Opcodes.INVOKESPECIAL))n++;
		return n;
	}

	/** The static {@code canInteract(Level, BlockPos)} calls of a fluid interaction registry in {@code host}. */
	private static List<MethodInsnNode> registryCalls(MethodNode host) {
		List<MethodInsnNode> found=new ArrayList<>();
		for(var i:host.instructions)if(i instanceof MethodInsnNode c&&REGISTRIES.contains(c.owner)
				&&c.name.equals("canInteract")&&c.desc.equals(INTERACT)&&c.getOpcode()==Opcodes.INVOKESTATIC)found.add(c);
		return found;
	}

	/** The reaction point: before {@code FluidState.isSource}, the one call of it vanilla's shouldSpreadLiquid makes. */
	private static boolean reactionPoint(MethodNode m) {
		if(!MixinCallbackShape.beforePoint(m,"INVOKE","L"+FLUID_STATE+";isSource()Z"))return false;
		Object ordinal=MixinFit.value(MixinFit.atNodes(MixinFit.injectorOf(m)).getFirst(),"ordinal");
		return ordinal==null||Integer.valueOf(-1).equals(ordinal)||Integer.valueOf(0).equals(ordinal);
	}

	private static void callback(InsnList c,int slot) {
		c.add(new TypeInsnNode(Opcodes.NEW,CALLBACK));c.add(new InsnNode(Opcodes.DUP));c.add(new LdcInsnNode("forbricFluidReaction"));c.add(new InsnNode(Opcodes.ICONST_1));
		c.add(new FieldInsnNode(Opcodes.GETSTATIC,"java/lang/Boolean","TRUE","Ljava/lang/Boolean;"));
		c.add(new MethodInsnNode(Opcodes.INVOKESPECIAL,CALLBACK,"<init>","(Ljava/lang/String;ZLjava/lang/Object;)V",false));c.add(new VarInsnNode(Opcodes.ASTORE,slot));
	}
	private static void state(InsnList c,int level,int pos){c.add(new VarInsnNode(Opcodes.ALOAD,level));c.add(new VarInsnNode(Opcodes.ALOAD,pos));c.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL,LEVEL,"getBlockState","("+POS+")"+STATE,false));}
	private static AnnotationNode at(String target){AnnotationNode a=new AnnotationNode("Lorg/spongepowered/asm/mixin/injection/At;");a.values=new ArrayList<>(List.of("value","INVOKE","target",target));return a;}
	private static AnnotationNode wrapOperation(String method,AnnotationNode at){
		AnnotationNode a=new AnnotationNode("Lcom/llamalad7/mixinextras/injector/wrapoperation/WrapOperation;");
		a.values=new ArrayList<>(List.of("method",List.of(method),"at",at,"require",1));
		return a;
	}
}
