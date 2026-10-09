/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.mixin;

import java.util.*;
import java.util.function.BiFunction;
import java.util.function.Function;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import net.forbric.api.Ecosystem;

/**
 * Keeps authored wraps of a vanilla query bound where the native platform asks a richer object the same question.
 *
 * <p>Vanilla asks {@code state.getBlock().getFriction()}; NeoForge asks {@code state.getFriction(level, pos, entity)} in
 * the same place, so the block can answer for where it stands. A mod's {@code @WrapOperation} or
 * {@code @ModifyExpressionValue} at {@code Block.getFriction()} then has nothing to bind to. The replacement is read off
 * the two bodies, never off a list of query names: in the methods the injector's selectors bind, the vanilla call is gone
 * and a call of the same name and return type stands in its place on a receiver from which vanilla's receiver is one
 * no-argument getter away ({@code BlockState.getBlock()}), vanilla's arguments among its own. Where the body the mod was
 * compiled against is at hand, each vanilla occurrence must pair, in order, with one replacement occurring on the very
 * receiver whose getter produced vanilla's, with the same argument values; only then is an ordinal translated.
 *
 * <p>A {@code @ModifyExpressionValue} handler reads only the value, so its point simply moves. A {@code @WrapOperation}
 * handler is wrapped: it is handed vanilla's operands (the receiver through the getter) and an original that makes the
 * native call with the call site's own operands — which is right only if the handler passes on exactly the operands it
 * was given. That is proved over the handler's data flow ({@link MixinOperationPassThrough}); a handler that passes
 * another block is refused, never silently answered for the one it was handed.
 *
 * <p>Same for vanilla's {@code state.is(Blocks.SCAFFOLDING)} in {@code LivingEntity.handleOnClimbable}, which NeoForge asks
 * as {@code state.isScaffolding(entity)}: the wrap moves only if the handler passes the compared block on unchanged.
 */
public final class MixinBlockQueryAdapters {
	public static final String PROPERTY="forbric.blockQueryAdapters";
	private static final String STATE="net/minecraft/world/level/block/state/BlockState", BLOCK="net/minecraft/world/level/block/Block", OP=MixinWrapOperationShim.OPERATION;
	private MixinBlockQueryAdapters() { }
	public static int adapt(ClassNode mixin,Function<String,ClassNode> targets) {return adapt(mixin,targets,NativeGameReferences::reference);}
	public static int adapt(ClassNode mixin,Function<String,ClassNode> targets,BiFunction<Ecosystem,String,ClassNode> references) {
		if(mixin==null||"off".equalsIgnoreCase(net.forbric.kernel.util.ForbricSwitches.get(PROPERTY))||MixinFit.mixinTargets(mixin).size()!=1)return 0;
		String owner=MixinFit.mixinTargets(mixin).getFirst();ClassNode target=targets.apply(owner);if(target==null)return 0;
		ClassNode nativeClass=references==null?null:references.apply(MixinStubRebind.ecosystemOf(mixin.name),owner);
		// One injector of a kind per vanilla call per mixin: two would be told apart only by guessing.
		Map<String,List<MethodNode>> byCall=new LinkedHashMap<>();
		for(MethodNode handler:mixin.methods){
			String kind=MixinCallbackShape.kind(handler,"WrapOperation")?"WrapOperation":MixinCallbackShape.kind(handler,"ModifyExpressionValue")?"ModifyExpressionValue":null;
			MixinFit.Member vanilla=kind==null?null:invoked(handler);
			if(vanilla!=null)byCall.computeIfAbsent(kind+" "+vanilla,k->new ArrayList<>()).add(handler);
		}
		int changed=0;
		for(List<MethodNode> handlers:byCall.values())if(handlers.size()==1)changed+=receiverQuery(mixin,handlers.getFirst(),target,nativeClass,targets);
		if(MixinCallbackShape.targets(mixin,"net/minecraft/world/entity/LivingEntity"))changed+=scaffolding(mixin,targets);
		return changed;
	}

	/** The replacement of a vanilla call: its member, vanilla's receiver getter on the new receiver, and where vanilla's arguments go. */
	private record Replacement(MixinFit.Member call,String getter,boolean receiverInterface,int[] arguments,Integer ordinal) { }

	private static int receiverQuery(ClassNode mixin,MethodNode handler,ClassNode target,ClassNode nativeClass,Function<String,ClassNode> targets){
		MixinFit.Member vanilla=invoked(handler);AnnotationNode injector=MixinFit.injectorOf(handler);
		boolean wrap=injector.desc.endsWith("/WrapOperation;");
		MixinHandlerShape shape=MixinHandlerShape.of(handler);if(shape==null)return 0;
		Type[] wanted=Type.getArgumentTypes(vanilla.desc());Type returns=Type.getReturnType(vanilla.desc());
		if(wrap){
			// (vanilla's receiver, vanilla's arguments, Operation) → vanilla's return, then any extras.
			List<Type> operands=new ArrayList<>();operands.add(Type.getObjectType(vanilla.owner()));operands.addAll(List.of(wanted));operands.add(Type.getObjectType(OP));
			if(!shape.returns().equals(returns)||!shape.operands().equals(operands))return 0;
		}else if(!shape.returns().equals(returns)||!shape.operands().equals(List.of(returns))||!shape.extras().isEmpty())return 0;
		Replacement replacement=derive(handler,vanilla,target,nativeClass,targets);if(replacement==null)return 0;
		if(wrap){
			Set<Integer> operands=new HashSet<>();for(int i=0;i<=wanted.length;i++)operands.add(i);
			if(!MixinOperationPassThrough.proves(mixin.name,handler,wanted.length+1,wanted.length+1,operands))return 0;
		}
		AnnotationNode point=MixinFit.atNodes(injector).getFirst();
		MixinPlayerWorldCallbackAdapter.set(point,"target","L"+replacement.call().owner()+";"+replacement.call().name()+replacement.call().desc());
		if(replacement.ordinal()!=null)MixinPlayerWorldCallbackAdapter.set(point,"ordinal",replacement.ordinal());
		if(!wrap){if(replacement.ordinal()!=null)CurrentBodyOrdinals.mark(handler);return 1;}
		MethodNode outer=wrapper(mixin,handler,injector,vanilla,replacement);
		if(replacement.ordinal()!=null)CurrentBodyOrdinals.mark(outer);
		return 1;
	}

	/**
	 * The call standing in for {@code vanilla} in every method the handler's selectors bind; null when the bodies do not
	 * show one, or the vanilla call is still there (the injector binds as written).
	 */
	private static Replacement derive(MethodNode handler,MixinFit.Member vanilla,ClassNode target,ClassNode nativeClass,Function<String,ClassNode> targets){
		List<MethodNode> hosts=MixinTargetSelectors.bound(handler,target);if(hosts==null||hosts.isEmpty())return null;
		List<MethodNode> natives=nativeClass==null?null:MixinTargetSelectors.bound(handler,nativeClass);
		if(nativeClass!=null&&(natives==null||natives.isEmpty()))return null;   // the mod's own selector names nothing it was compiled against
		Type returns=Type.getReturnType(vanilla.desc());Type[] wanted=Type.getArgumentTypes(vanilla.desc());
		// Same name and return, another receiver: those from which vanilla's receiver is one getter away are the candidates.
		MixinFit.Member found=null;String getter=null;Map<String,String> getters=new HashMap<>();
		for(MethodNode host:hosts){
			if(host.instructions==null||!calls(host,vanilla).isEmpty())return null;
			for(var insn:host.instructions)if(insn instanceof MethodInsnNode call&&call.getOpcode()!=Opcodes.INVOKESTATIC&&call.name.equals(vanilla.name())
					&&!call.owner.equals(vanilla.owner())&&Type.getReturnType(call.desc).equals(returns)){
				String reach=getters.computeIfAbsent(call.owner,receiver->Objects.requireNonNullElse(getter(receiver,vanilla.owner(),targets),""));
				if(reach.isEmpty())continue;
				MixinFit.Member candidate=new MixinFit.Member(call.owner,call.name,call.desc);
				if(found!=null&&!found.equals(candidate))return null;
				found=candidate;getter=reach;
			}
		}
		if(found==null)return null;
		int[] arguments=MixinWrapOperationShim.argumentMapping(wanted,Type.getArgumentTypes(found.desc()));if(arguments==null)return null;
		ClassNode receiver=targets.apply(found.owner());if(receiver==null)return null;
		Object ordinal=MixinFit.value(MixinFit.atNodes(MixinFit.injectorOf(handler)).getFirst(),"ordinal");
		boolean counted=ordinal instanceof Integer n&&n>=0;
		if(natives!=null){
			for(MethodNode host:hosts){
				List<MethodNode> paired=natives.stream().filter(m->m.name.equals(host.name)&&m.desc.equals(host.desc)).toList();if(paired.size()!=1)return null;
				if(!pairs(nativeClass,paired.getFirst(),vanilla,target,host,found,getter,arguments))return null;
			}
		}else if(counted)return null;   // an ordinal counts the vanilla body's calls: without that body it is not translated
		if(counted&&hosts.size()!=1)return null;
		return new Replacement(found,getter,(receiver.access&Opcodes.ACC_INTERFACE)!=0,arguments,counted?(Integer)ordinal:null);
	}

	/**
	 * In order, each vanilla occurrence's receiver is {@code getter} on the replacement occurrence's receiver, and each of its
	 * arguments the replacement's argument at the mapped position: the same values, by their producers.
	 */
	private static boolean pairs(ClassNode nativeClass,MethodNode nativeHost,MixinFit.Member vanilla,ClassNode target,MethodNode host,MixinFit.Member replacement,String getter,int[] arguments){
		List<NativeCallChanges.Site> before=NativeCallChanges.sites(nativeClass,nativeHost).stream().filter(s->is(s.call(),vanilla)).toList();
		List<NativeCallChanges.Site> after=NativeCallChanges.sites(target,host).stream().filter(s->is(s.call(),replacement)).toList();
		if(before.isEmpty()||before.size()!=after.size()||before.size()!=calls(nativeHost,vanilla).size()||after.size()!=calls(host,replacement).size())return false;
		for(int i=0;i<before.size();i++){
			List<NativeCallChanges.Expr> old=before.get(i).operands(),now=after.get(i).operands();
			NativeCallChanges.Expr receiver=old.getFirst();
			if(!receiver.kind().equals("call")||!receiver.symbol().endsWith(";"+getter)||receiver.inputs().size()!=1||!receiver.inputs().getFirst().equals(now.getFirst()))return false;
			for(int j=0;j<arguments.length;j++)if(!old.get(1+j).equals(now.get(1+arguments[j])))return false;
		}
		return true;
	}

	/** The one no-argument instance method of {@code owner} or its supertypes returning {@code type} ({@code name + desc}); null when none or several. */
	private static String getter(String owner,String type,Function<String,ClassNode> targets){
		Set<String> found=new LinkedHashSet<>();Deque<String> pending=new ArrayDeque<>(List.of(owner));Set<String> seen=new HashSet<>();
		while(!pending.isEmpty()){
			String name=pending.pop();if(name==null||!seen.add(name)||name.equals("java/lang/Object"))continue;
			ClassNode node;try{node=targets.apply(name);}catch(RuntimeException unavailable){node=null;}
			if(node==null)continue;
			for(MethodNode method:node.methods)if((method.access&(Opcodes.ACC_STATIC|Opcodes.ACC_SYNTHETIC|Opcodes.ACC_PRIVATE))==0&&method.desc.equals("()L"+type+";"))found.add(method.name+method.desc);
			pending.push(node.superName);if(node.interfaces!=null)for(String i:node.interfaces)pending.push(i);
		}
		return found.size()==1?found.iterator().next():null;
	}

	/** The handler's one point: an INVOKE of a member with an owner and a method descriptor, however spelled; null otherwise. */
	private static MixinFit.Member invoked(MethodNode handler){
		AnnotationNode injector=MixinFit.injectorOf(handler);if(injector==null||MixinFit.atNodes(injector).size()!=1)return null;
		AnnotationNode point=MixinFit.atNodes(injector).getFirst();
		if(!"INVOKE".equals(MixinFit.value(point,"value"))||MixinFit.value(point,"shift")!=null||MixinFit.value(point,"by")!=null||MixinFit.value(point,"opcode")!=null||MixinFit.value(point,"args")!=null)return null;
		MixinFit.Member member=MixinFit.parseMember(MixinFit.asString(MixinFit.value(point,"target")));
		return member==null||member.owner()==null||member.desc()==null||!member.desc().startsWith("(")?null:member;
	}

	private static boolean is(MethodInsnNode call,MixinFit.Member member){return call.owner.equals(member.owner())&&call.name.equals(member.name())&&call.desc.equals(member.desc());}
	private static List<MethodInsnNode> calls(MethodNode method,MixinFit.Member member){List<MethodInsnNode> found=new ArrayList<>();for(var insn:method.instructions)if(insn instanceof MethodInsnNode call&&is(call,member))found.add(call);return found;}

	/**
	 * The new handler: (the replacement's receiver, its arguments, Operation, the extras) → the original handler, handed
	 * vanilla's receiver through the getter, vanilla's arguments from their mapped places, and an original that makes the
	 * replacement call with the call site's own operands (proved to be what the handler passes on).
	 */
	private static MethodNode wrapper(ClassNode mixin,MethodNode original,AnnotationNode injector,MixinFit.Member vanilla,Replacement replacement){
		boolean stat=(original.access&Opcodes.ACC_STATIC)!=0;
		Type[] mergedArgs=Type.getArgumentTypes(replacement.call().desc()),wanted=Type.getArgumentTypes(vanilla.desc()),old=Type.getArgumentTypes(original.desc);
		List<Type> params=new ArrayList<>(List.of(Type.getObjectType(replacement.call().owner())));params.addAll(List.of(mergedArgs));params.addAll(Arrays.asList(old).subList(1+wanted.length,old.length));
		MethodNode outer=new MethodNode(original.access,original.name,Type.getMethodDescriptor(Type.getReturnType(original.desc),params.toArray(Type[]::new)),null,null);outer.visibleAnnotations=new ArrayList<>(List.of(injector));
		int shift=mergedArgs.length-wanted.length,head=wanted.length+2;
		outer.invisibleParameterAnnotations=MixinWrapOperationShim.shifted(original.invisibleParameterAnnotations,old.length,shift,head,params.size());
		outer.visibleParameterAnnotations=MixinWrapOperationShim.shifted(original.visibleParameterAnnotations,old.length,shift,head,params.size());
		int[] slots=new int[params.size()];int slot=stat?0:1;for(int i=0;i<params.size();i++){slots[i]=slot;slot+=params.get(i).getSize();}
		InsnList c=outer.instructions;if(!stat)c.add(new VarInsnNode(Opcodes.ALOAD,0));
		String getter=replacement.getter();
		c.add(new VarInsnNode(Opcodes.ALOAD,slots[0]));c.add(new MethodInsnNode(replacement.receiverInterface()?Opcodes.INVOKEINTERFACE:Opcodes.INVOKEVIRTUAL,replacement.call().owner(),getter.substring(0,getter.indexOf('(')),getter.substring(getter.indexOf('(')),replacement.receiverInterface()));
		for(int j=0;j<wanted.length;j++){int at=1+replacement.arguments()[j];c.add(new VarInsnNode(params.get(at).getOpcode(Opcodes.ILOAD),slots[at]));}
		// KernelWrapOperations.reordered(original, no receiver, nothing mapped, every replacement operand as the call site had it)
		int op=1+mergedArgs.length;c.add(new VarInsnNode(Opcodes.ALOAD,slots[op]));c.add(new InsnNode(Opcodes.ICONST_0));c.add(new LdcInsnNode(""));pushInt(c,op);c.add(new TypeInsnNode(Opcodes.ANEWARRAY,"java/lang/Object"));
		for(int i=0;i<op;i++){c.add(new InsnNode(Opcodes.DUP));pushInt(c,i);c.add(new VarInsnNode(params.get(i).getOpcode(Opcodes.ILOAD),slots[i]));box(c,params.get(i));c.add(new InsnNode(Opcodes.AASTORE));}
		c.add(new MethodInsnNode(Opcodes.INVOKESTATIC,MixinWrapOperationShim.RUNTIME,"reordered","(L"+OP+";ZLjava/lang/String;[Ljava/lang/Object;)L"+OP+";",false));
		for(int i=op+1;i<params.size();i++)c.add(new VarInsnNode(params.get(i).getOpcode(Opcodes.ILOAD),slots[i]));
		original.name+="$forbricOriginal";MixinCarrierCallbackAdapters.removeInjector(original,injector);original.invisibleParameterAnnotations=null;original.visibleParameterAnnotations=null;
		c.add(MixinHandlerShim.callOwn(mixin,stat,original.name,original.desc));c.add(new InsnNode(Type.getReturnType(original.desc).getOpcode(Opcodes.IRETURN)));outer.maxLocals=slot;outer.maxStack=slot+12;mixin.methods.add(outer);
		return outer;
	}

	static int scaffolding(ClassNode mixin,Function<String,ClassNode> targets){
		ClassNode target=targets.apply("net/minecraft/world/entity/LivingEntity");
		MethodNode original=MixinCallbackShape.unique(mixin,m -> MixinCallbackShape.shape(m,"(L"+STATE+";Ljava/lang/Object;L"+OP+";)Z") && MixinCallbackShape.instance(m)
                && MixinCallbackShape.kind(m,"WrapOperation") && MixinCallbackShape.binds(m,target,"handleOnClimbable(Lnet/minecraft/world/phys/Vec3;)Lnet/minecraft/world/phys/Vec3;")
                && MixinCallbackShape.plainPoint(m,"INVOKE","L"+STATE+";is(Ljava/lang/Object;)Z"));
		if(original==null||target==null||!original.desc.equals("(L"+STATE+";Ljava/lang/Object;L"+OP+";)Z"))return 0;
		MethodNode host=MixinCarrierCallbackAdapters.named(target,"handleOnClimbable");String live="L"+STATE+";isScaffolding(Lnet/minecraft/world/entity/LivingEntity;)Z";
		AnnotationNode inject=MixinFit.injectorOf(original);if(host==null||inject==null||MixinPlayerWorldCallbackAdapter.count(host,live)!=1)return 0;
		// The original asks isScaffolding(entity) of the state the handler passes, whatever block it compares: only a handler
		// that compares the block it was handed (vanilla's SCAFFOLDING) can be given that original.
		if(!MixinOperationPassThrough.proves(mixin.name,original,2,2,Set.of(1)))return 0;
		MixinPlayerWorldCallbackAdapter.set(MixinFit.atNodes(inject).getFirst(),"target",live);
		MethodNode wrapper=new MethodNode(Opcodes.ACC_PRIVATE,original.name,"(L"+STATE+";Lnet/minecraft/world/entity/LivingEntity;L"+OP+";)Z",null,null);wrapper.visibleAnnotations=new ArrayList<>(List.of(inject));
		InsnList c=wrapper.instructions;c.add(new VarInsnNode(Opcodes.ALOAD,0));c.add(new VarInsnNode(Opcodes.ALOAD,1));c.add(new FieldInsnNode(Opcodes.GETSTATIC,"net/minecraft/world/level/block/Blocks","SCAFFOLDING","L"+BLOCK+";"));c.add(new VarInsnNode(Opcodes.ALOAD,3));c.add(new InsnNode(Opcodes.ICONST_1));c.add(new LdcInsnNode(""));c.add(new InsnNode(Opcodes.ICONST_1));c.add(new TypeInsnNode(Opcodes.ANEWARRAY,"java/lang/Object"));c.add(new InsnNode(Opcodes.DUP));c.add(new InsnNode(Opcodes.ICONST_0));c.add(new VarInsnNode(Opcodes.ALOAD,2));c.add(new InsnNode(Opcodes.AASTORE));c.add(new MethodInsnNode(Opcodes.INVOKESTATIC,MixinWrapOperationShim.RUNTIME,"reordered","(L"+OP+";ZLjava/lang/String;[Ljava/lang/Object;)L"+OP+";",false));
		original.name+="$forbricOriginal";MixinCarrierCallbackAdapters.removeInjector(original,inject);c.add(MixinHandlerShim.callOwn(mixin,false,original.name,original.desc));c.add(new InsnNode(Opcodes.IRETURN));wrapper.maxStack=10;wrapper.maxLocals=4;mixin.methods.add(wrapper);return 1;
	}

	private static void pushInt(InsnList c,int value){if(value>=-1&&value<=5)c.add(new InsnNode(Opcodes.ICONST_0+value));else c.add(new IntInsnNode(Opcodes.BIPUSH,value));}
	private static void box(InsnList c,Type type){
		String wrapper=switch(type.getSort()){case Type.BOOLEAN->"java/lang/Boolean";case Type.BYTE->"java/lang/Byte";case Type.CHAR->"java/lang/Character";case Type.SHORT->"java/lang/Short";case Type.INT->"java/lang/Integer";case Type.LONG->"java/lang/Long";case Type.FLOAT->"java/lang/Float";case Type.DOUBLE->"java/lang/Double";default->null;};
		if(wrapper!=null)c.add(new MethodInsnNode(Opcodes.INVOKESTATIC,wrapper,"valueOf","("+type.getDescriptor()+")L"+wrapper+";",false));
	}
}
