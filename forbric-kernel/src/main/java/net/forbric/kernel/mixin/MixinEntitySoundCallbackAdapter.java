/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.mixin;
import java.util.*;import java.util.function.BiFunction;import java.util.function.Function;
import org.objectweb.asm.*;import org.objectweb.asm.tree.*;
import net.forbric.api.Ecosystem;

/**
 * Carry an authored sound-group callback into native playback, with lazy native lookup and the captures it was written for.
 *
 * <p>A guest wraps vanilla's {@code state.getSoundType()} in a method that plays a block's sound. The native platform hands
 * that playback to the block ({@code BlockState.playStepSound}/{@code playFallSound}), whose implementation asks the state
 * for its sound group itself; KernelBoot's BlockSoundQueryInjector makes that ask consult {@code BlockSoundCallbackScope}.
 * So the wrap moves onto the native playback call in the methods its own selectors bind, opens a scope frame around it,
 * and the handler answers from inside the native query, handed the native query (for whatever state it passes on) as its
 * original. Only the methods the selectors bind: a step wrap written for {@code playStepSound} is not a wrap of the
 * muffled or combination steps, which vanilla plays without calling it.
 *
 * <p>Each {@code @Local} the handler captures is read where the wrap now stands, in the same method, at the slot holding
 * the value it captured at the vanilla call ({@link MixinLocalCapture}), never by the position it was declared in; a
 * target argument Mixin appends after the {@code Operation} is the {@code @Local(argsOnly = true)} it stands for
 * ({@link MixinHandlerShape#of(MethodNode, String, MethodNode)}) and is read the same way. Any other extra —
 * {@code @Share}, other sugar — is not served, and the handler is left as written. The point is read as Mixin reads its
 * target: whitespace and a dotted owner name the same call, and a target without an owner or descriptor names it where
 * vanilla's method makes no other such call ({@link MixinCallbackShape#names}).
 */
public final class MixinEntitySoundCallbackAdapter {
	public static final String PROPERTY="forbric.entitySoundCallbacks";
	private static final String STATE="net/minecraft/world/level/block/state/BlockState", SOUND="net/minecraft/world/level/block/SoundType", OP=MixinWrapOperationShim.OPERATION, SCOPE="net/forbric/kernel/interop/BlockSoundCallbackScope";
	private static final MixinFit.Member VANILLA=new MixinFit.Member(STATE,"getSoundType","()L"+SOUND+";");
	/** The native playback calls whose block implementation asks the scope (BlockSoundQueryInjector's two methods). */
	private static final List<MixinFit.Member> PLAYBACK=List.of(
			new MixinFit.Member(STATE,"playStepSound","(Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/entity/Entity;FF)V"),
			new MixinFit.Member(STATE,"playFallSound","(Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/entity/LivingEntity;)V"));
	private MixinEntitySoundCallbackAdapter() { }
	public static int adapt(ClassNode mixin,Function<String,ClassNode> targets){return adapt(mixin,targets,NativeGameReferences::reference);}
	public static int adapt(ClassNode mixin,Function<String,ClassNode> targets,BiFunction<Ecosystem,String,ClassNode> references){
		if(mixin==null||"off".equalsIgnoreCase(net.forbric.kernel.util.ForbricSwitches.get(PROPERTY))||MixinFit.mixinTargets(mixin).size()!=1)return 0;
		String owner=MixinFit.mixinTargets(mixin).getFirst();ClassNode target=targets.apply(owner);if(target==null)return 0;
		// One wrap of the vanilla query per mixin: two would be told apart only by guessing.
		List<MethodNode> wraps=mixin.methods.stream().filter(m->MixinCallbackShape.kind(m,"WrapOperation")&&at(m,VANILLA)).toList();
		if(wraps.size()!=1)return 0;MethodNode original=wraps.getFirst();
		MixinHandlerShape shape=MixinHandlerShape.of(original);
		if(shape==null||!shape.operands("(L"+STATE+";L"+OP+";)L"+SOUND+";"))return 0;
		List<MethodNode> hosts=MixinTargetSelectors.bound(original,target);if(hosts==null||hosts.isEmpty())return 0;
		ClassNode nativeClass=references==null?null:references.apply(MixinStubRebind.ecosystemOf(mixin.name),owner);
		List<MethodNode> natives=nativeClass==null?null:MixinTargetSelectors.bound(original,nativeClass);
		MixinFit.Member playback=null;Map<Integer,Integer> captures=null;List<MixinHandlerShape.Extra> extras=null;
		for(MethodNode host:hosts){
			if(host.instructions==null||calls(host,VANILLA).size()>0)return 0;   // the vanilla query is here: the wrap binds as written
			MixinFit.Member found=null;for(MixinFit.Member seam:PLAYBACK)if(!calls(host,seam).isEmpty()){if(found!=null)return 0;found=seam;}
			if(found==null||playback!=null&&!playback.equals(found))return 0;playback=found;
			MethodNode nativeHost=null;
			if(natives!=null){List<MethodNode> paired=natives.stream().filter(m->m.name.equals(host.name)).toList();if(paired.size()!=1)return 0;nativeHost=paired.getFirst();
				// One playback call where vanilla queried once: the occurrences pair in order.
				if(calls(nativeHost,VANILLA).size()!=calls(host,found).size()||!selectsOnly(original,nativeHost,VANILLA))return 0;}
			else if(!spelled(original,VANILLA))return 0;   // a point without owner or descriptor names VANILLA only where vanilla's body says so
			// The captures, read against the method the wrap was written for: a target argument it appends is an implicit @Local.
			MethodNode written=nativeHost!=null?nativeHost:host;
			MixinHandlerShape read=MixinHandlerShape.of(original,written.desc,written);
			if(read==null||read.extras().stream().anyMatch(e->e.role()!=MixinHandlerShape.Role.LOCAL))return 0;
			if(extras==null)extras=read.extras();
			List<MethodInsnNode> at=calls(host,found);
			for(int i=0;i<at.size();i++){
				Map<Integer,Integer> proved=MixinLocalCapture.slots(original,target.name,nativeHost,nativeHost==null?null:calls(nativeHost,VANILLA).get(i),host,at.get(i));
				if(proved==null||captures!=null&&!captures.equals(proved))return 0;   // one annotation must read the same slot everywhere
				captures=proved;
			}
		}
		if(playback==null||captures==null||extras==null)return 0;
		AnnotationNode injector=MixinFit.injectorOf(original);boolean stat=(original.access&Opcodes.ACC_STATIC)!=0;
		List<String> methods=new ArrayList<>();for(MethodNode host:hosts)methods.add(host.name+host.desc);
		MixinPlayerWorldCallbackAdapter.set(injector,"method",methods);MixinPlayerWorldCallbackAdapter.set(MixinFit.atNodes(injector).getFirst(),"target","L"+playback.owner()+";"+playback.name()+playback.desc());
		// The wrapper: (state, playback arguments, Operation, the handler's captures at their proven slots) → void.
		Type[] playArgs=Type.getArgumentTypes(playback.desc());
		List<Type> wrapperParams=new ArrayList<>();wrapperParams.add(Type.getObjectType(STATE));wrapperParams.addAll(List.of(playArgs));wrapperParams.add(Type.getObjectType(OP));for(var extra:extras)wrapperParams.add(extra.type());
		MethodNode wrapper=new MethodNode(Opcodes.ACC_PRIVATE|(stat?Opcodes.ACC_STATIC:0),original.name,Type.getMethodDescriptor(Type.VOID_TYPE,wrapperParams.toArray(Type[]::new)),null,null);wrapper.visibleAnnotations=new ArrayList<>(List.of(injector));
		@SuppressWarnings("unchecked") List<AnnotationNode>[] sugar=new List[wrapperParams.size()];int firstExtra=playArgs.length+2;
		for(int j=0;j<extras.size();j++)sugar[firstExtra+j]=new ArrayList<>(List.of(MixinLocalCapture.at(extras.get(j).sugar(),captures.get(extras.get(j).parameter()))));
		wrapper.invisibleParameterAnnotations=sugar;wrapper.invisibleAnnotableParameterCount=wrapperParams.size();
		original.name+="$forbricOriginal";MixinCarrierCallbackAdapters.removeInjector(original,injector);original.invisibleParameterAnnotations=null;original.visibleParameterAnnotations=null;
		MethodNode callback=callback(mixin,original,stat,extras);mixin.methods.add(callback);
		int[] slots=new int[wrapperParams.size()];int slot=stat?0:1;for(int i=0;i<wrapperParams.size();i++){slots[i]=slot;slot+=wrapperParams.get(i).getSize();}
		int op=playArgs.length+1,prev=slot;
		InsnList c=wrapper.instructions;
		// The frame's callback captures the handler's own values, read here where the wrap stands.
		List<Type> captured=new ArrayList<>();if(!stat){c.add(new VarInsnNode(Opcodes.ALOAD,0));captured.add(Type.getObjectType(mixin.name));}
		for(int j=0;j<extras.size();j++){c.add(new VarInsnNode(extras.get(j).type().getOpcode(Opcodes.ILOAD),slots[firstExtra+j]));captured.add(extras.get(j).type());}
		c.add(new InvokeDynamicInsnNode("apply",Type.getMethodDescriptor(Type.getType(Function.class),captured.toArray(Type[]::new)),new Handle(Opcodes.H_INVOKESTATIC,"java/lang/invoke/LambdaMetafactory","metafactory","(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;Ljava/lang/invoke/MethodType;Ljava/lang/invoke/MethodHandle;Ljava/lang/invoke/MethodType;)Ljava/lang/invoke/CallSite;",false),Type.getMethodType("(Ljava/lang/Object;)Ljava/lang/Object;"),new Handle(stat?Opcodes.H_INVOKESTATIC:Opcodes.H_INVOKEVIRTUAL,mixin.name,callback.name,callback.desc,false),Type.getMethodType("([Ljava/lang/Object;)Ljava/lang/Object;")));
		c.add(new MethodInsnNode(Opcodes.INVOKESTATIC,SCOPE,"enter","(Ljava/util/function/Function;)Ljava/lang/Object;",false));c.add(new VarInsnNode(Opcodes.ASTORE,prev));LabelNode start=new LabelNode(),end=new LabelNode(),fail=new LabelNode();c.add(start);
		// original.call(state, playback arguments...): the native playback, which asks the open frames.
		c.add(new VarInsnNode(Opcodes.ALOAD,slots[op]));pushInt(c,op);c.add(new TypeInsnNode(Opcodes.ANEWARRAY,"java/lang/Object"));
		for(int i=0;i<op;i++){c.add(new InsnNode(Opcodes.DUP));pushInt(c,i);Type type=wrapperParams.get(i);c.add(new VarInsnNode(type.getOpcode(Opcodes.ILOAD),slots[i]));box(c,type);c.add(new InsnNode(Opcodes.AASTORE));}
		c.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE,OP,"call","([Ljava/lang/Object;)Ljava/lang/Object;",true));c.add(new InsnNode(Opcodes.POP));c.add(end);leave(c,prev);c.add(new InsnNode(Opcodes.RETURN));c.add(fail);
		List<Object> locals=new ArrayList<>();if(!stat)locals.add(mixin.name);for(Type type:wrapperParams)locals.add(frameType(type));locals.add("java/lang/Object");
		c.add(new FrameNode(Opcodes.F_FULL,locals.size(),locals.toArray(),1,new Object[]{"java/lang/Throwable"}));c.add(new VarInsnNode(Opcodes.ASTORE,prev+1));leave(c,prev);c.add(new VarInsnNode(Opcodes.ALOAD,prev+1));c.add(new InsnNode(Opcodes.ATHROW));
		wrapper.tryCatchBlocks.add(new TryCatchBlockNode(start,end,fail,null));wrapper.maxStack=8+captured.size()*2;wrapper.maxLocals=prev+2;mixin.methods.add(wrapper);
		return 1;
	}
	/**
	 * Whether the handler's one point is an INVOKE that may name {@code member}, on every occurrence, however the target
	 * is spelled: whitespace, a dotted owner, and — told apart per method by {@link #selectsOnly} — no owner or no descriptor.
	 */
	private static boolean at(MethodNode handler,MixinFit.Member member){
		AnnotationNode injector=MixinFit.injectorOf(handler);if(injector==null||MixinFit.atNodes(injector).size()!=1)return false;
		AnnotationNode point=MixinFit.atNodes(injector).getFirst();
		if(!"INVOKE".equals(MixinFit.value(point,"value"))||MixinFit.value(point,"shift")!=null||MixinFit.value(point,"by")!=null||MixinFit.value(point,"opcode")!=null||MixinFit.value(point,"args")!=null)return false;
		Object ordinal=MixinFit.value(point,"ordinal");if(ordinal!=null&&!Integer.valueOf(-1).equals(ordinal))return false;
		return MixinCallbackShape.covers(point,member(member));
	}
	/** Whether the handler's point selects, in the vanilla body {@code nativeHost}, exactly the calls of {@code member}. */
	private static boolean selectsOnly(MethodNode handler,MethodNode nativeHost,MixinFit.Member member){
		return MixinCallbackShape.names(MixinFit.atNodes(MixinFit.injectorOf(handler)).getFirst(),member(member),nativeHost);
	}
	/** Whether the handler's point spells {@code member} with its owner and descriptor, so no body is needed to read it. */
	private static boolean spelled(MethodNode handler,MixinFit.Member member){
		return MixinCallbackShape.names(MixinFit.atNodes(MixinFit.injectorOf(handler)).getFirst(),member(member),null);
	}
	private static String member(MixinFit.Member member){return "L"+member.owner()+";"+member.name()+member.desc();}
	private static List<MethodInsnNode> calls(MethodNode method,MixinFit.Member member){List<MethodInsnNode> found=new ArrayList<>();for(var i:method.instructions)if(i instanceof MethodInsnNode call&&call.owner.equals(member.owner())&&call.name.equals(member.name())&&call.desc.equals(member.desc()))found.add(call);return found;}
	private static void leave(InsnList c,int slot){c.add(new VarInsnNode(Opcodes.ALOAD,slot));c.add(new MethodInsnNode(Opcodes.INVOKESTATIC,SCOPE,"leave","(Ljava/lang/Object;)V",false));}
	/** {@code (captures..., Object[] [state, pos, original]) → the handler's sound group}, its original run on the state it passes on. */
	private static MethodNode callback(ClassNode mixin,MethodNode original,boolean stat,List<MixinHandlerShape.Extra> extras){
		List<Type> params=new ArrayList<>();for(var extra:extras)params.add(extra.type());params.add(Type.getType(Object[].class));
		MethodNode fn=new MethodNode(Opcodes.ACC_PRIVATE|(stat?Opcodes.ACC_STATIC:0),"forbric$soundCallback",Type.getMethodDescriptor(Type.getType(Object.class),params.toArray(Type[]::new)),null,null);InsnList c=fn.instructions;
		int slot=stat?0:1;int[] slots=new int[params.size()];for(int i=0;i<params.size();i++){slots[i]=slot;slot+=params.get(i).getSize();}int args=slots[params.size()-1];
		if(!stat)c.add(new VarInsnNode(Opcodes.ALOAD,0));
		element(c,args,0,STATE);element(c,args,2,"java/util/function/Function");c.add(new MethodInsnNode(Opcodes.INVOKESTATIC,MixinWrapOperationShim.RUNTIME,"applied","(Ljava/util/function/Function;)L"+OP+";",false));
		for(int j=0;j<extras.size();j++)c.add(new VarInsnNode(extras.get(j).type().getOpcode(Opcodes.ILOAD),slots[j]));
		c.add(MixinHandlerShim.callOwn(mixin,stat,original.name,original.desc));c.add(new InsnNode(Opcodes.ARETURN));fn.maxStack=4+extras.size()*2;fn.maxLocals=slot;MixinCallbackShape.uniqueMember(fn);return fn;
	}
	private static void element(InsnList c,int array,int index,String type){c.add(new VarInsnNode(Opcodes.ALOAD,array));pushInt(c,index);c.add(new InsnNode(Opcodes.AALOAD));c.add(new TypeInsnNode(Opcodes.CHECKCAST,type));}
	private static void pushInt(InsnList c,int value){if(value>=-1&&value<=5)c.add(new InsnNode(Opcodes.ICONST_0+value));else c.add(new IntInsnNode(Opcodes.BIPUSH,value));}
	private static void box(InsnList c,Type type){
		String wrapper=switch(type.getSort()){case Type.BOOLEAN->"java/lang/Boolean";case Type.BYTE->"java/lang/Byte";case Type.CHAR->"java/lang/Character";case Type.SHORT->"java/lang/Short";case Type.INT->"java/lang/Integer";case Type.LONG->"java/lang/Long";case Type.FLOAT->"java/lang/Float";case Type.DOUBLE->"java/lang/Double";default->null;};
		if(wrapper!=null)c.add(new MethodInsnNode(Opcodes.INVOKESTATIC,wrapper,"valueOf","("+type.getDescriptor()+")L"+wrapper+";",false));
	}
	private static Object frameType(Type t){return switch(t.getSort()){case Type.BOOLEAN,Type.BYTE,Type.CHAR,Type.SHORT,Type.INT->Opcodes.INTEGER;case Type.FLOAT->Opcodes.FLOAT;case Type.LONG->Opcodes.LONG;case Type.DOUBLE->Opcodes.DOUBLE;default->t.getInternalName();};}
}
