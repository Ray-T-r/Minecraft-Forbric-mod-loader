/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.mixin;
import java.util.*;import java.util.function.BiFunction;import java.util.function.Function;import java.util.function.IntUnaryOperator;
import org.objectweb.asm.*;import org.objectweb.asm.tree.*;
import net.forbric.api.Ecosystem;

/**
 * Keep Authored redstone, collision and block-break controls around the native operations and event gates.
 *
 * <p>Each control wraps a vanilla call the platform replaced. What the handler captures beside the call's operands is
 * whatever {@code @Local}s it asks for — annotated, or the target arguments Mixin appends after its {@code Operation},
 * which are the {@code @Local(argsOnly = true)}s they stand for ({@link MixinCallbackShape#captures}) — never a list one
 * mod happened to declare. Each is proved to the slot holding its value where the replacement call stands
 * ({@link MixinCallbackProofs#replacedCallLocals}: by producer when the class the mod was compiled against is at hand)
 * and handed on by a {@code @Local} of that slot; a handler whose selector moves instead has them proved and pinned where
 * it lands ({@link MixinCallbackProofs#land}). A handler with any other extra, or a capture that is not proved, stays as
 * written.
 */
public final class MixinBlockInteractionAdapters {
	public static final String PROPERTY="forbric.blockInteractionAdapters";
	private static final String STATE="net/minecraft/world/level/block/state/BlockState", BLOCK="net/minecraft/world/level/block/Block", POS="net/minecraft/core/BlockPos", OP=MixinWrapOperationShim.OPERATION;
	private static final String SIGNAL="net/minecraft/world/level/SignalGetter", DIRECTION="net/minecraft/core/Direction", ENTITY="net/minecraft/world/entity/Entity";
	private static final String GET_SIGNAL="getSignal(L"+POS+";L"+DIRECTION+";)I", DESTROY_BLOCK="destroyBlock(L"+POS+";)Z";
	private MixinBlockInteractionAdapters() { }
	public static int adapt(ClassNode mixin,Function<String,ClassNode> targets){
		return adapt(mixin,targets,NativeGameReferences::reference);
	}
	/**
	 * {@code references} gives the class the mod was compiled against. The bounce and left-click hosts are found by the
	 * native method the handler was written for, which a bare name, a wildcard or a pattern names only in that class
	 * ({@link MixinTargetSelectors#nativeMember}); without it only a selector spelling the descriptor names it. Its body is
	 * also where each capture's value is read; without it a capture is proved only as far as the live body can say.
	 */
	static int adapt(ClassNode mixin,Function<String,ClassNode> targets,BiFunction<Ecosystem,String,ClassNode> references){
		if("off".equalsIgnoreCase(net.forbric.kernel.util.ForbricSwitches.get(PROPERTY)))return 0;
        List<String> owners=MixinFit.mixinTargets(mixin);if(owners.size()!=1)return 0;
        Function<String,ClassNode> sources=owner->references==null?null:references.apply(MixinStubRebind.ecosystemOf(mixin.name),owner);
        return switch(owners.getFirst()){
            case SIGNAL->signal(mixin,targets,sources);
            case ENTITY->bounce(mixin,targets,sources);
            case "net/minecraft/client/multiplayer/MultiPlayerGameMode"->leftClick(mixin,targets,sources)+breaking(mixin,targets,sources,false);
            case "net/minecraft/server/level/ServerPlayerGameMode"->breaking(mixin,targets,sources,true);
            default->0;
        };
	}
	/** The method of the class the mod was compiled against that the handler binds: where a point it writes without an owner is read. */
	private static MethodNode written(MethodNode handler,ClassNode source){return source==null?null:MixinTargetSelectors.one(handler,source);}
	/** {@link #written}, else {@code bound}: the method its selectors bind here, of the descriptor the handler was written for. */
	private static MethodNode written(MethodNode handler,ClassNode source,MethodNode bound){MethodNode found=written(handler,source);return found!=null?found:bound;}
	/** A method that is only its descriptor: where the handler was written for a method known by its selector alone. */
	private static MethodNode declared(String name,String desc){return new MethodNode(Opcodes.ACC_PRIVATE,name,desc,null,null);}

	private static int signal(ClassNode mixin,Function<String,ClassNode> targets,Function<String,ClassNode> sources){
		ClassNode target=targets.apply(SIGNAL),source=sources.apply(SIGNAL);
		MethodNode host=target==null?null:MixinPlayerWorldCallbackAdapter.selector(target,GET_SIGNAL);
		String vanilla="L"+STATE+";isRedstoneConductor(Lnet/minecraft/world/level/BlockGetter;L"+POS+";)Z";
		MethodNode original=MixinCallbackShape.unique(mixin,m -> MixinCallbackShape.kind(m,"WrapOperation") && MixinCallbackShape.instance(m)
				&& MixinCallbackShape.binds(m,target,GET_SIGNAL) && MixinCallbackShape.plainPoint(m,"INVOKE",vanilla,written(m,source))
				&& MixinCallbackShape.captures(m,written(m,source,host),"(L"+STATE+";Lnet/minecraft/world/level/BlockGetter;L"+POS+";L"+OP+";)Z"));
		String live="L"+STATE+";shouldCheckWeakPower(L"+SIGNAL+";L"+POS+";L"+DIRECTION+";)Z";
		if(original==null||host==null||MixinPlayerWorldCallbackAdapter.count(host,live)!=1)return 0;
		MethodNode authored=written(original,source,host);
		Map<Integer,Integer> locals=proved(original,source,written(original,source),authored.desc,target,host,Collections.singletonList(MixinPlayerWorldCallbackAdapter.first(host,live)));
		if(locals==null)return 0;
		List<MixinHandlerShape.Extra> extras=MixinCallbackShape.read(original,authored).extras();
		AnnotationNode inject=MixinFit.injectorOf(original);MixinPlayerWorldCallbackAdapter.set(MixinFit.atNodes(inject).getFirst(),"target",live);
		// (state, getter, pos, direction, Operation, the captures): the original runs on the state, getter and pos the
		// handler passes, with the call's own direction.
		MethodNode outer=outer(original,inject,Type.BOOLEAN_TYPE,types(STATE,SIGNAL,POS,DIRECTION,OP),extras,locals);InsnList c=outer.instructions;
		load(c,0,1,2,3);c.add(new VarInsnNode(Opcodes.ALOAD,5));c.add(new InsnNode(Opcodes.ICONST_1));c.add(new LdcInsnNode("0,1"));c.add(new InsnNode(Opcodes.ICONST_3));c.add(new TypeInsnNode(Opcodes.ANEWARRAY,"java/lang/Object"));c.add(new InsnNode(Opcodes.DUP));c.add(new InsnNode(Opcodes.ICONST_2));c.add(new VarInsnNode(Opcodes.ALOAD,4));c.add(new InsnNode(Opcodes.AASTORE));reordered(c);
		finish(mixin,original,outer,inject,Opcodes.IRETURN,loadExtras(c,extras,6));return 1;
	}
	private static int bounce(ClassNode mixin,Function<String,ClassNode> targets,Function<String,ClassNode> sources){
		ClassNode target=targets.apply(ENTITY),source=sources.apply(ENTITY);if(target==null)return 0;
		String operands="(L"+ENTITY+";L"+BLOCK+";L"+OP+";)D";
		MethodNode original=MixinCallbackShape.unique(mixin,m->MixinCallbackShape.kind(m,"WrapOperation") && MixinCallbackShape.instance(m)
				&& MixinCallbackShape.plainPoint(m,"INVOKE","L"+ENTITY+";getBlockBounciness(L"+BLOCK+";)D",written(m,source))
				&& authored(m,source,target)!=null && MixinCallbackShape.captures(m,authored(m,source,target),operands));
		if(original==null)return 0;
		MethodNode authored=authored(original,source,target);String hostName=authored.name;
		String call="L"+target.name+";getBlockBounciness(L"+POS+";L"+STATE+";)D";List<MethodNode> hosts=target.methods.stream().filter(m->m.name.equals(hostName)&&MixinPlayerWorldCallbackAdapter.count(m,call)==1).toList();if(hosts.size()!=1)return 0;
		MethodNode host=hosts.getFirst();
		Map<Integer,Integer> locals=proved(original,source,written(original,source),authored.desc,target,host,Collections.singletonList(MixinPlayerWorldCallbackAdapter.first(host,call)));
		if(locals==null)return 0;
		List<MixinHandlerShape.Extra> extras=MixinCallbackShape.read(original,authored).extras();
		AnnotationNode inject=MixinFit.injectorOf(original);List<AnnotationNode> ats=MixinFit.atNodes(inject);if(ats.size()!=1)return 0;MixinPlayerWorldCallbackAdapter.set(inject,"method",List.of(host.name+host.desc));MixinPlayerWorldCallbackAdapter.set(ats.getFirst(),"target",call);
		// (entity, pos, state, Operation, the captures): the original is handed the state's block, and runs the native query.
		MethodNode outer=outer(original,inject,Type.DOUBLE_TYPE,types(target.name,POS,STATE,OP),extras,locals);InsnList c=outer.instructions;
		load(c,0,1,3);c.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL,STATE,"getBlock","()L"+BLOCK+";",false));fixed(c,List.of(Type.getObjectType(target.name),Type.getObjectType(POS),Type.getObjectType(STATE)),new int[]{1,2,3},4);
		finish(mixin,original,outer,inject,Opcodes.DRETURN,loadExtras(c,extras,5));return 1;
	}
	/** The native method the bounce handler was written for ({@link MixinTargetSelectors#nativeMember}): its body when at hand, else its descriptor. */
	private static MethodNode authored(MethodNode handler,ClassNode source,ClassNode target){
		MethodNode found=written(handler,source);if(found!=null)return found;
		String member=MixinTargetSelectors.nativeMember(handler,source,target.name);if(member==null||member.indexOf('(')<=0)return null;
		return declared(member.substring(0,member.indexOf('(')),member.substring(member.indexOf('(')));
	}
	private static int leftClick(ClassNode mixin,Function<String,ClassNode> targets,Function<String,ClassNode> sources){
        ClassNode target=targets.apply("net/minecraft/client/multiplayer/MultiPlayerGameMode");if(target==null)return 0;
        Set<String> live=reachableMethods(target);int changed=0;
        ClassNode written=sources.apply(target.name);
        String destroy="Lnet/minecraft/client/multiplayer/MultiPlayerGameMode;destroyBlock(L"+POS+";)Z";
        // Its captures are whatever @Locals it asks for: where it lands they are proved and pinned (MixinCallbackProofs#land).
        List<MethodNode> handlers=mixin.methods.stream().filter(m -> MixinCallbackShape.kind(m,"WrapOperation") && MixinCallbackShape.instance(m)
                && operands(m,"(Lnet/minecraft/client/multiplayer/MultiPlayerGameMode;L"+POS+";L"+OP+";)Z")
                && MixinCallbackShape.plainPoint(m,"INVOKE",destroy,written(m,written))).toList();
        ClassNode source=handlers.isEmpty()?null:written;
        // Two handlers written for one native method are ambiguous, whatever each selector looks like.
        Map<MethodNode,String> authored=new HashMap<>();Set<String> selections=new HashSet<>();
        for(MethodNode handler:handlers){String member=MixinTargetSelectors.nativeMember(handler,source,target.name);authored.put(handler,member);
            if(!selections.add(member!=null?member:String.valueOf(MixinTargetSelectors.selectors(handler))))return 0;}
		for(MethodNode handler:handlers) {
        AnnotationNode inject=MixinFit.injectorOf(handler);String member=authored.get(handler);if(member==null)continue;
        int descriptor=member.indexOf('(');
        String name=member.substring(0,descriptor),desc=member.substring(descriptor);
		List<MethodNode> hosts=target.methods.stream().filter(m->m.name.equals(name)&&sameArgumentProjection(desc,m.desc)
                &&live.contains(m.name+m.desc)&&MixinPlayerWorldCallbackAdapter.count(m,destroy)==1).toList();if(hosts.size()!=1)continue;MethodNode host=hosts.getFirst();String selector=host.name+host.desc;
		// Bound, as written, to a method of the descriptor it was written for: Mixin reads every capture there as written.
		boolean bound=MixinCallbackShape.binds(handler,target,selector);
		if(bound&&host.desc.equals(desc))continue;
		// Each capture must hold, in the method it lands in, what it held in the one it was written for: the platform's
		// added context sits among the native parameters, which keep their order (MixinCallbackProofs#projection).
		MixinCallbackProofs.Landing landing=MixinCallbackProofs.land(handler,source==null?null:source.name,written(handler,source),target.name,host,carried(desc,host.desc),desc);
		if(landing==null)continue;
		int pinned=landing.point()==null||landing.locals().isEmpty()?0:MixinCallbackProofs.pinLocals(handler,host,landing.point(),landing.locals());
		// Already bound to the platform's method of another descriptor — a bare name binds the first method of that
		// name — nothing moves; but a target argument it appends would be read from that method's arguments, so the
		// captures are pinned where they stand there.
		if(bound){if(pinned>0)changed++;continue;}
		MixinPlayerWorldCallbackAdapter.set(inject,"method",List.of(selector));changed++;
        }
        return changed;
	}
    /** A retained alternate private body is not an active lambda implementation. Follow actual own calls/handles. */
    private static Set<String> reachableMethods(ClassNode owner) {
        Map<String,MethodNode> declarations=new HashMap<>();for(MethodNode method:owner.methods)declarations.put(method.name+method.desc,method);
        Set<String> reached=new HashSet<>();Deque<String> pending=new ArrayDeque<>();
        for(MethodNode method:owner.methods)if((method.access&Opcodes.ACC_PRIVATE)==0||method.name.equals("<clinit>"))pending.add(method.name+method.desc);
        while(!pending.isEmpty()) {
            String key=pending.removeFirst();if(!reached.add(key))continue;MethodNode method=declarations.get(key);if(method==null)continue;
            for(var instruction:method.instructions) {
                if(instruction instanceof MethodInsnNode call&&call.owner.equals(owner.name))pending.add(call.name+call.desc);
                if(instruction instanceof InvokeDynamicInsnNode call)for(Object argument:call.bsmArgs)addReference(owner.name,argument,pending);
                if(instruction instanceof LdcInsnNode literal)addReference(owner.name,literal.cst,pending);
            }
        }
        return reached;
    }
    private static void addReference(String owner,Object value,Deque<String> pending) {
        if(value instanceof Handle handle&&handle.getOwner().equals(owner)&&handle.getTag()>=Opcodes.H_INVOKEVIRTUAL)pending.add(handle.getName()+handle.getDesc());
        else if(value instanceof ConstantDynamic constant){for(int i=0;i<constant.getBootstrapMethodArgumentCount();i++)addReference(owner,constant.getBootstrapMethodArgument(i),pending);}
    }
    /** Added carrier context may sit between original operands; the original parameter order and return survive. */
    private static boolean sameArgumentProjection(String before,String after) {
        return MixinCallbackProofs.projection(before,after)!=null;
    }
    /** Which native parameter each live one carries ({@link MixinCallbackProofs#projection}); none where the native ones cannot be placed. */
    private static IntUnaryOperator carried(String nativeDesc,String liveDesc){IntUnaryOperator projection=MixinCallbackProofs.projection(nativeDesc,liveDesc);return projection!=null?projection:j->-1;}
	private static int breaking(ClassNode mixin,Function<String,ClassNode> targets,Function<String,ClassNode> sources,boolean server){
		String owner=server?"net/minecraft/server/level/ServerPlayerGameMode":"net/minecraft/client/multiplayer/MultiPlayerGameMode";ClassNode target=targets.apply(owner),source=sources.apply(owner);
		MethodNode host=target==null?null:MixinPlayerWorldCallbackAdapter.selector(target,DESTROY_BLOCK);
		String operands=server?"(Lnet/minecraft/server/level/ServerLevel;L"+POS+";ZL"+OP+";)Z":"(Lnet/minecraft/world/level/Level;L"+POS+";L"+STATE+";IL"+OP+";)Z";
		MethodNode original=MixinCallbackShape.unique(mixin,m -> MixinCallbackShape.kind(m,"WrapOperation") && MixinCallbackShape.instance(m)
				&& MixinCallbackShape.binds(m,target,DESTROY_BLOCK) && MixinCallbackShape.captures(m,written(m,source,host),operands)
				&& MixinCallbackShape.plainPoint(m,"INVOKE",server?"Lnet/minecraft/server/level/ServerLevel;removeBlock(L"+POS+";Z)Z":"Lnet/minecraft/world/level/Level;setBlock(L"+POS+";L"+STATE+";I)Z",written(m,source)));
		if(original==null||host==null||MixinFit.injectorOf(original)==null)return 0;
		List<MethodInsnNode> calls=new ArrayList<>();for(var i:host.instructions)if(i instanceof MethodInsnNode call&&(server?call.owner.equals(owner)&&call.name.equals("removeBlock"):call.owner.equals(STATE)&&call.name.equals("onDestroyedByPlayer")))calls.add(call);
		if(calls.isEmpty()||(!server&&calls.size()!=1)||calls.stream().map(c->c.desc).distinct().count()!=1)return 0;
		MethodNode authored=written(original,source,host);
		Map<Integer,Integer> locals=proved(original,source,written(original,source),authored.desc,target,host,calls);
		if(locals==null)return 0;
		List<MixinHandlerShape.Extra> extras=MixinCallbackShape.read(original,authored).extras();
		MethodInsnNode live=calls.getFirst();Type[] liveArgs=Type.getArgumentTypes(live.desc);List<Type> params=new ArrayList<>(List.of(Type.getObjectType(live.owner)));params.addAll(List.of(liveArgs));params.add(Type.getObjectType(OP));
		AnnotationNode inject=MixinFit.injectorOf(original);MixinPlayerWorldCallbackAdapter.set(MixinFit.atNodes(inject).getFirst(),"target","L"+live.owner+";"+live.name+live.desc);
		// (the live call's receiver and arguments, Operation, the captures): the original is handed the native operands
		// rebuilt from them, and its Operation runs the live call as made.
		MethodNode outer=outer(original,inject,Type.BOOLEAN_TYPE,params,extras,locals);
		int[] slots=new int[params.size()];int slot=1;for(int i=0;i<params.size();i++){slots[i]=slot;slot+=params.get(i).getSize();}int op=1+liveArgs.length;
		InsnList c=outer.instructions;c.add(new VarInsnNode(Opcodes.ALOAD,0));
		if(server){c.add(new VarInsnNode(Opcodes.ALOAD,0));c.add(new FieldInsnNode(Opcodes.GETFIELD,mixin.name,"level","Lnet/minecraft/server/level/ServerLevel;"));c.add(new VarInsnNode(Opcodes.ALOAD,slots[1]));c.add(new InsnNode(Opcodes.ICONST_0));}
		else{load(c,slots[1],slots[2],slots[6]);c.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL,"net/minecraft/world/level/material/FluidState","createLegacyBlock","()L"+STATE+";",false));c.add(new IntInsnNode(Opcodes.BIPUSH,11));}
		fixed(c,params.subList(0,op),Arrays.copyOf(slots,op),slots[op]);finish(mixin,original,outer,inject,Opcodes.IRETURN,loadExtras(c,extras,slot));return 1;
	}
	/**
	 * The handler's captures, each the slot of {@code host} it reads at every one of {@code livePoints}, the calls that took
	 * the place of its point in the native method {@code reference} (null when not at hand; {@code nativeDesc} is that
	 * method's descriptor either way): {@link MixinCallbackProofs#replacedCallLocals}. Null when one is not proved.
	 */
	private static Map<Integer,Integer> proved(MethodNode handler,ClassNode source,MethodNode reference,String nativeDesc,ClassNode target,MethodNode host,List<? extends AbstractInsnNode> livePoints){
		if(livePoints.stream().anyMatch(Objects::isNull))return null;
		AbstractInsnNode nativePoint=null;
		if(reference!=null){List<AbstractInsnNode> was=MixinCallbackProofs.points(reference,MixinFit.atNodes(MixinFit.injectorOf(handler)).getFirst());if(was==null||was.size()!=1)return null;nativePoint=was.getFirst();}
		return MixinCallbackProofs.replacedCallLocals(handler,source==null?null:source.name,reference,nativePoint,nativeDesc,target.name,host,livePoints,carried(nativeDesc,host.desc));
	}
	/** Whether the handler's operands and return, read as its injector reads them, are {@code descriptor}'s. */
	private static boolean operands(MethodNode handler,String descriptor){MixinHandlerShape shape=MixinHandlerShape.of(handler);return shape!=null&&shape.operands(descriptor);}
	/**
	 * The wrapper that takes {@code original}'s injector: {@code operands} (the live call's and its {@code Operation}),
	 * then one parameter per capture of {@code extras}, in the handler's order, each a {@code @Local} of the slot it was
	 * proved to ({@code locals}, by handler parameter).
	 */
	@SuppressWarnings("unchecked")
	private static MethodNode outer(MethodNode original,AnnotationNode inject,Type returns,List<Type> operands,List<MixinHandlerShape.Extra> extras,Map<Integer,Integer> locals){
		List<Type> params=new ArrayList<>(operands);for(var extra:extras)params.add(extra.type());
		MethodNode outer=new MethodNode(Opcodes.ACC_PRIVATE,original.name,Type.getMethodDescriptor(returns,params.toArray(Type[]::new)),null,null);outer.visibleAnnotations=new ArrayList<>(List.of(inject));
		List<AnnotationNode>[] table=new List[params.size()];
		for(int j=0;j<extras.size();j++)table[operands.size()+j]=new ArrayList<>(List.of(MixinLocalCapture.at(extras.get(j).sugar(),locals.get(extras.get(j).parameter()))));
		outer.invisibleParameterAnnotations=table;outer.invisibleAnnotableParameterCount=params.size();
		return outer;
	}
	/** Loads the wrapper's captures, from slot {@code first} on, in the handler's order; the slot past the last. */
	private static int loadExtras(InsnList c,List<MixinHandlerShape.Extra> extras,int first){int slot=first;for(var extra:extras){c.add(new VarInsnNode(extra.type().getOpcode(Opcodes.ILOAD),slot));slot+=extra.type().getSize();}return slot;}
	private static List<Type> types(String... names){List<Type> out=new ArrayList<>();for(String name:names)out.add(Type.getObjectType(name));return out;}
	private static void load(InsnList c,int... slots){for(int slot:slots)c.add(new VarInsnNode(Opcodes.ALOAD,slot));}
	private static void reordered(InsnList c){c.add(new MethodInsnNode(Opcodes.INVOKESTATIC,MixinWrapOperationShim.RUNTIME,"reordered","(L"+OP+";ZLjava/lang/String;[Ljava/lang/Object;)L"+OP+";",false));}
	private static void fixed(InsnList c,List<Type> params,int[] slots,int operation){c.add(new VarInsnNode(Opcodes.ALOAD,operation));c.add(new InsnNode(Opcodes.ICONST_0));c.add(new LdcInsnNode(""));c.add(new IntInsnNode(Opcodes.BIPUSH,params.size()));c.add(new TypeInsnNode(Opcodes.ANEWARRAY,"java/lang/Object"));for(int i=0;i<params.size();i++){c.add(new InsnNode(Opcodes.DUP));c.add(new IntInsnNode(Opcodes.BIPUSH,i));Type type=params.get(i);c.add(new VarInsnNode(type.getOpcode(Opcodes.ILOAD),slots[i]));if(type.equals(Type.BOOLEAN_TYPE))c.add(new MethodInsnNode(Opcodes.INVOKESTATIC,"java/lang/Boolean","valueOf","(Z)Ljava/lang/Boolean;",false));c.add(new InsnNode(Opcodes.AASTORE));}reordered(c);}
	private static void finish(ClassNode mixin,MethodNode original,MethodNode outer,AnnotationNode inject,int exit,int locals){original.name+="$forbricOriginal";MixinCarrierCallbackAdapters.removeInjector(original,inject);original.invisibleParameterAnnotations=null;original.visibleParameterAnnotations=null;outer.instructions.add(MixinHandlerShim.callOwn(mixin,false,original.name,original.desc));outer.instructions.add(new InsnNode(exit));outer.maxStack=locals+14;outer.maxLocals=locals;mixin.methods.add(outer);}
}
