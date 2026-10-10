/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.mixin;

import java.util.*;
import java.util.function.Function;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.*;
import net.forbric.kernel.util.ForbricLog;

/**
 * Follows a registry-loader injection onto the carrier's widened overloads, by the method the guest named.
 *
 * <p>The carrier widens {@code RegistryDataLoader}'s private loader with a trailing parameter (NeoForge's leniency flag)
 * and routes the public entry points to it, one of them through a widened twin of itself (pending tags). Vanilla's
 * private loader is still declared but no live body calls it, so a guest {@code @WrapOperation} on that call, and any
 * injector selecting that loader, bind to nothing. Each such injector moves to the corresponding live pair, derived from
 * the guest's own selector: the callee {@code C} it names maps to the one called overload {@code C'} whose parameters are
 * {@code C}'s followed by the carrier's; the host it selects maps to itself when it calls {@code C'}, else to the one
 * same-named overload it delegates to whose parameters extend its own and which calls {@code C'}. So a wrap on the
 * networked entry stays on the networked entry and a wrap on the resource-manager entry follows it into the widened twin.
 * The wrap keeps its handler: a wrapper of the handler's name takes the carrier's extra arguments and hands the original
 * an {@code Operation} that passes them through ({@code KernelWrapOperations.reordered}).
 *
 * <p>All or nothing per mixin: when one injector that names a dead overload cannot be mapped (an ambiguous name-only
 * selector, two widened candidates, captured locals the move would shift), nothing moves.
 *
 * <p>Every injector is read as Mixin reads it, never by spelling. Whether an injector is written for a dead loader is
 * the one method its selectors bind in the class the mod was compiled against ({@link MixinCallbackShape#written}), so a
 * bare name is the overload Mixin binds there, whatever the merged class declares beside it; only without that class (or
 * where its selectors bind no single method there) is it read by the methods it matches in the merged class
 * ({@link MixinTargetSelectors#reach}): an owner prefix, a dotted owner or whitespace is the same selector, and one that
 * binds a single method but matches several (a bare name shared by the dead loader and its live overloads) names neither
 * for certain. A point is the member it names ({@link MixinCallbackShape#member}) — one written without its owner or
 * descriptor in the method it was written for in the class the mod was compiled against — and, once moved, what it
 * selects in the widened body ({@link MixinCallbackShape#selected}).
 */
public final class FabricRegistryLoaderMixinAdapter {
	public static final String PROPERTY="forbric.fabricRegistryLoader";
	private static final String TARGET="net/minecraft/resources/RegistryDataLoader";
	private static final String FACTORY="L"+TARGET+"$LoaderFactory;";
	private static final String ARGS="Ljava/util/List;Ljava/util/List;Ljava/util/concurrent/Executor;";
	private static final String FUTURE="Ljava/util/concurrent/CompletableFuture;";
	private static final String OP="com/llamalad7/mixinextras/injector/wrapoperation/Operation";
	private static final String WRAP="Lcom/llamalad7/mixinextras/injector/wrapoperation/WrapOperation;";
	private FabricRegistryLoaderMixinAdapter() { }
	public static boolean enabled(){return !"off".equalsIgnoreCase(System.getProperty(PROPERTY,"on"));}

    /**
     * Whether the mixin carries the ScopedValue propagation protocol around the private loader's call, its point read as
     * {@link #adapt} reads it — one without its owner or descriptor in the method it was written for, in the class the
     * mod was compiled against ({@link NativeGameReferences}) — so a gate refusing what cannot be adapted sees every
     * spelling the adapter would serve.
     */
    static boolean matches(ClassNode mixin) {
        return matches(mixin,NativeGameReferences::reference);
    }
    /** {@code references} gives the class the mod was compiled against; null (or a null answer): none at hand. */
    static boolean matches(ClassNode mixin,java.util.function.BiFunction<net.forbric.api.Ecosystem,String,ClassNode> references) {
        if(!MixinCallbackShape.targets(mixin,TARGET))return false;
        ClassNode source=references==null?null:references.apply(MixinStubRebind.ecosystemOf(mixin.name),TARGET);
        return mixin.fields.stream().anyMatch(f->f.desc.equals("Ljava/lang/ScopedValue;"))
                && mixin.methods.stream().anyMatch(m->m.desc.equals("(Ljava/lang/Object;"+ARGS+"L"+OP+";)"+FUTURE)
                    && MixinCallbackShape.kind(m,"WrapOperation")
                    && invokes(m,"java/lang/ScopedValue","where")&&invokes(m,"java/lang/ScopedValue$Carrier","call")
                    && MixinCallbackShape.plainPoint(m,"INVOKE","L"+TARGET+";load("+FACTORY+ARGS+")"+FUTURE,MixinCallbackShape.written(m,source)));
    }

    private static boolean invokes(MethodNode method,String owner,String name){for(var instruction:method.instructions)if(instruction instanceof MethodInsnNode call&&call.owner.equals(owner)&&call.name.equals(name))return true;return false;}

	/** One injector's move: the live method it now selects, and for a wrap the widened callee it now names. */
	private record Move(MethodNode handler,AnnotationNode injector,MethodNode host,MethodNode callee,boolean wrap){}

	public static int adapt(ClassNode mixin,Function<String,ClassNode> targets){
		return adapt(mixin,targets,NativeGameReferences::reference);
	}
	/** {@code references} gives the class the mod was compiled against, where a point without its owner or descriptor is read. */
	static int adapt(ClassNode mixin,Function<String,ClassNode> targets,java.util.function.BiFunction<net.forbric.api.Ecosystem,String,ClassNode> references){
		if(!enabled()||!MixinCallbackShape.targets(mixin,TARGET))return 0;
		ClassNode target=targets.apply(TARGET);if(target==null)return 0;
		ClassNode source=references==null?null:references.apply(MixinStubRebind.ecosystemOf(mixin.name),TARGET);
		List<Move> moves=new ArrayList<>();
		Set<String> deadCallees=new LinkedHashSet<>();
		Map<String,MethodNode> widenedOf=new HashMap<>();
		// The wraps first: each names its own host and its own callee.
		for(MethodNode handler:mixin.methods){
			if(!MixinCallbackShape.kind(handler,"WrapOperation"))continue;
			AnnotationNode injector=MixinFit.injectorOf(handler);
			String callee=invokeTarget(handler,source);if(callee==null)continue;
			String calleeKey=callee.substring(callee.indexOf(';')+1);
			if(calledAnywhere(target,calleeKey))continue; // the call is live: the wrap binds as written
			MethodNode widened=widenedLive(target,calleeKey);
			if(widened==null)continue; // nothing replaced it here: not this repair's case
			deadCallees.add(calleeKey);widenedOf.put(calleeKey,widened);
			MethodNode host=MixinTargetSelectors.unambiguous(handler,target);
			MethodNode live=host==null?null:delegateCalling(target,host,widened);
			if(live==null||(handler.access&Opcodes.ACC_STATIC)==0||!handlerDescribes(handler,calleeKey))return 0;
			moves.add(new Move(handler,injector,live,widened,true));
		}
		if(deadCallees.isEmpty())return 0;
		// Then every other injector that selects a dead callee itself: it moves into the widened body, as long as the
		// trailing parameter cannot shift what it reads.
		for(MethodNode handler:mixin.methods){
			AnnotationNode injector=MixinFit.injectorOf(handler);
			if(injector==null||injector.desc.equals(WRAP)&&moves.stream().anyMatch(m->m.handler()==handler))continue;
			List<MixinTargetSelectors.Reach> reach=MixinTargetSelectors.reach(handler,target);
			if(reach==null)continue;
			String dead=null;boolean other=false;
			// Which loader it was written for is decided where it was written: the one method its injector binds in the
			// class the mod was compiled against. A bare name binds the first method of that name there, whatever the
			// merged class declares beside it or no longer declares.
			MethodNode compiled=MixinCallbackShape.written(handler,source);
			if(compiled!=null){
				String key=compiled.name+compiled.desc;
				MethodNode here=MixinTargetSelectors.one(handler,target);
				if(!deadCallees.contains(key)){
					// Written for a live method: not this repair's case — unless the merged class binds it to a dead loader
					// instead, which nothing here can map for certain.
					if(here!=null&&deadCallees.contains(here.name+here.desc))return 0;
					continue;
				}
				dead=key;reach=List.of();
			}
			for(MixinTargetSelectors.Reach selector:reach){
				List<String> matched=selector.matched().stream().map(m->m.name+m.desc).toList();
				String hit=matched.stream().filter(deadCallees::contains).findFirst().orElse(null);
				// A carrier that no longer declares the dead overload at all: a selector pinning it still names it.
				if(hit==null&&matched.isEmpty()&&deadCallees.contains(selector.spelled()))hit=selector.spelled();
				if(hit==null){other=true;continue;}
				// A selector binding one method that matches the dead overload and live ones names neither for certain.
				if(matched.size()>1&&selector.single())return 0;
				// One binding them all (a quantifier) binds the live overloads already: not this repair's case.
				if(matched.size()>1){other=true;continue;}
				if(dead!=null&&!dead.equals(hit))return 0;
				dead=hit;
			}
			if(dead==null)continue;
			if(other||!plain(handler,injector)||!hostBlind(handler,injector))return 0;
			MethodNode widened=widenedOf.get(dead);
			MethodNode written=source==null?null:find(source,dead.substring(0,dead.indexOf('(')),dead.substring(dead.indexOf('(')));
			for(AnnotationNode at:MixinFit.atNodes(injector)){
				// The member the point names where it was written, and exactly one instruction of it in the widened body.
				String member=MixinCallbackShape.member(at,written);
				List<AbstractInsnNode> there=MixinCallbackShape.selected(at,widened);
				if(member==null||there.size()!=1||!(there.getFirst() instanceof MethodInsnNode call)
						||!("L"+call.owner+";"+call.name+call.desc).equals(member))return 0;
			}
			moves.add(new Move(handler,injector,widened,null,false));
		}
		for(Move move:moves){
			if(!move.wrap()){set(move.injector(),"method",new ArrayList<>(List.of(move.host().name+move.host().desc)));continue;}
			set(move.injector(),"method",new ArrayList<>(List.of(move.host().name+move.host().desc)));
			for(AnnotationNode at:MixinFit.atNodes(move.injector()))set(at,"target","L"+TARGET+";"+move.callee().name+move.callee().desc);
			mixin.methods.add(wrapper(mixin,move.handler(),move.injector(),move.callee()));
		}
		ForbricLog.info("[Forbric/RegistrySync] restored the native Fabric registry loader callback of %s on the carrier "
				+ "overloads its own selectors map to, preserving pending tags and the leniency flag",mixin.name.replace('/','.'));
		for(Move move:moves)ForbricLog.info("[Forbric/RegistrySync] %s.%s now selects %s%s",mixin.name.replace('/','.'),
				move.wrap()?move.handler().name.replace("$forbricOriginal",""):move.handler().name,move.host().name,move.host().desc);
		return moves.size();
	}

	/**
	 * The one INVOKE point of a wrap, spelled {@code Lowner;name(desc)}, when it names a method of the target: read as
	 * Mixin reads a target ({@link MixinCallbackShape#member}), so whitespace or a dotted owner name the same member, and
	 * one without its owner or descriptor names what it selects in the method the wrap was written for in {@code source},
	 * the class the mod was compiled against.
	 */
	private static String invokeTarget(MethodNode handler,ClassNode source){
		List<AnnotationNode> ats=MixinFit.atNodes(MixinFit.injectorOf(handler));
		if(ats.size()!=1||!"INVOKE".equals(MixinFit.value(ats.getFirst(),"value")))return null;
		for(String key:List.of("shift","by","opcode","args","ordinal"))if(MixinFit.value(ats.getFirst(),key)!=null)return null;
		String named=MixinCallbackShape.member(ats.getFirst(),MixinCallbackShape.written(handler,source));
		MixinFit.Member member=MixinFit.parseMember(named);
		return member!=null&&TARGET.equals(member.owner())&&member.desc()!=null&&member.desc().startsWith("(")?named:null;
	}
	private static boolean calledAnywhere(ClassNode target,String key){
		for(MethodNode m:target.methods)for(AbstractInsnNode i:m.instructions)if(i instanceof MethodInsnNode c&&c.owner.equals(TARGET)&&(c.name+c.desc).equals(key))return true;
		return false;
	}
	/** The one called overload whose parameters are {@code key}'s followed by the carrier's, with the same return. */
	private static MethodNode widenedLive(ClassNode target,String key){
		String name=key.substring(0,key.indexOf('(')),desc=key.substring(key.indexOf('('));
		MethodNode found=null;
		for(MethodNode m:target.methods){
			if(!m.name.equals(name)||!MixinAtWidenedCall.widens(desc,m.desc)||(m.access&Opcodes.ACC_STATIC)==0||!calledAnywhere(target,m.name+m.desc))continue;
			if(found!=null)return null;
			found=m;
		}
		return found;
	}
	/** {@code host} when it calls {@code widened}, else the same-named overload it delegates to that does, followed. */
	private static MethodNode delegateCalling(ClassNode target,MethodNode host,MethodNode widened){
		MethodNode current=host;
		for(int depth=0;depth<4&&current!=null;depth++){
			int direct=calls(current,widened.name,widened.desc);
			if(direct==1)return current;
			if(direct>1)return null;
			MethodNode next=null;
			for(AbstractInsnNode i:current.instructions)if(i instanceof MethodInsnNode c&&c.owner.equals(TARGET)&&c.name.equals(current.name)
					&&MixinAtWidenedCall.widens(current.desc,c.desc)){
				MethodNode callee=find(target,c.name,c.desc);
				if(callee==null||next!=null&&next!=callee)return null;
				next=callee;
			}
			current=next;
		}
		return null;
	}
	private static int calls(MethodNode m,String name,String desc){int n=0;for(AbstractInsnNode i:m.instructions)if(i instanceof MethodInsnNode c&&c.owner.equals(TARGET)&&c.name.equals(name)&&c.desc.equals(desc))n++;return n;}
	private static MethodNode find(ClassNode node,String name,String desc){for(MethodNode m:node.methods)if(m.name.equals(name)&&m.desc.equals(desc))return m;return null;}
	/** A static wrap handler describing exactly the named call's arguments, then its Operation, returning its type. */
	private static boolean handlerDescribes(MethodNode handler,String key){
		Type call=Type.getMethodType(key.substring(key.indexOf('('))),own=Type.getMethodType(handler.desc);
		Type[] params=own.getArgumentTypes();
		return params.length==call.getArgumentTypes().length+1&&params[params.length-1].getInternalName().equals(OP)
				&&own.getReturnType().equals(call.getReturnType());
	}
	/** No slice, group or captured locals: appending a parameter to the body cannot change what it reads. */
	private static boolean plain(MethodNode handler,AnnotationNode injector){
		if(MixinFit.value(injector,"slice")!=null||MixinFit.value(injector,"locals")!=null)return false;
		for(List<AnnotationNode> list:Arrays.asList(handler.visibleAnnotations,handler.invisibleAnnotations))
			if(list!=null)for(AnnotationNode a:list)if(a.desc.endsWith("/Group;"))return false;
		for(List<AnnotationNode>[] params:Arrays.asList(handler.visibleParameterAnnotations,handler.invisibleParameterAnnotations))
			if(params!=null)for(List<AnnotationNode> list:params)if(list!=null)for(AnnotationNode a:list)
				if(a.desc.startsWith("Lcom/llamalad7/mixinextras/sugar/"))return false;
		for(AnnotationNode at:MixinFit.atNodes(injector)){
			if(!"INVOKE".equals(MixinFit.value(at,"value")))return false;
			for(String key:List.of("shift","by","opcode","args","ordinal"))if(MixinFit.value(at,key)!=null)return false;
		}
		return !MixinFit.atNodes(injector).isEmpty();
	}
	/** A handler that describes the call it anchors on, or a callback taking none of the host's parameters: the host
	 * gaining a trailing parameter changes nothing it declares. */
	private static boolean hostBlind(MethodNode handler,AnnotationNode injector){
		if(!injector.desc.equals("Lorg/spongepowered/asm/mixin/injection/Inject;"))return injector.desc.equals(WRAP)
				||injector.desc.equals("Lorg/spongepowered/asm/mixin/injection/ModifyArg;")||injector.desc.equals("Lorg/spongepowered/asm/mixin/injection/ModifyArgs;")
				||injector.desc.equals("Lorg/spongepowered/asm/mixin/injection/Redirect;")||injector.desc.startsWith("Lcom/llamalad7/mixinextras/injector/");
		Type[] params=Type.getArgumentTypes(handler.desc);
		return params.length==1&&params[0].getInternalName().startsWith("org/spongepowered/asm/mixin/injection/callback/CallbackInfo");
	}
	/** The handler renamed aside, and under its name a wrap shaped for the widened call that passes the extras through. */
	private static MethodNode wrapper(ClassNode mixin,MethodNode original,AnnotationNode annotation,MethodNode widened){
		Type[] own=Type.getArgumentTypes(original.desc),wide=Type.getArgumentTypes(widened.desc);
		int k=own.length-1,extra=wide.length-k;
		String handlerName=original.name;MixinCarrierCallbackAdapters.removeInjector(original,annotation);original.name+="$forbricOriginal";
		Type[] params=new Type[k+extra+1];
		System.arraycopy(own,0,params,0,k);System.arraycopy(wide,k,params,k,extra);params[k+extra]=Type.getObjectType(OP);
		MethodNode wrapper=new MethodNode(Opcodes.ASM9,Opcodes.ACC_PRIVATE|Opcodes.ACC_STATIC,handlerName,
				Type.getMethodDescriptor(Type.getReturnType(original.desc),params),null,null);
		wrapper.visibleAnnotations=new ArrayList<>(List.of(annotation));
		// Keep each named argument's annotations (@Coerce on a loosely typed one); the carrier's and the Operation have none.
		if(original.invisibleParameterAnnotations!=null){
			@SuppressWarnings("unchecked") List<AnnotationNode>[] kept=(List<AnnotationNode>[])new List[params.length];
			System.arraycopy(original.invisibleParameterAnnotations,0,kept,0,Math.min(k,original.invisibleParameterAnnotations.length));
			wrapper.invisibleParameterAnnotations=kept;
		}
		InsnList code=wrapper.instructions;
		int[] slot=new int[params.length];int next=0;
		for(int i=0;i<params.length;i++){slot[i]=next;next+=params[i].getSize();}
		for(int i=0;i<k;i++)code.add(new VarInsnNode(params[i].getOpcode(Opcodes.ILOAD),slot[i]));
		code.add(new VarInsnNode(Opcodes.ALOAD,slot[k+extra]));code.add(new InsnNode(Opcodes.ICONST_0));
		StringBuilder map=new StringBuilder();for(int i=0;i<k;i++){if(i>0)map.append(',');map.append(i);}
		code.add(new LdcInsnNode(map.toString()));
		code.add(new LdcInsnNode(wide.length));code.add(new TypeInsnNode(Opcodes.ANEWARRAY,"java/lang/Object"));
		for(int j=0;j<extra;j++){
			code.add(new InsnNode(Opcodes.DUP));code.add(new LdcInsnNode(k+j));
			code.add(new VarInsnNode(params[k+j].getOpcode(Opcodes.ILOAD),slot[k+j]));box(code,params[k+j]);
			code.add(new InsnNode(Opcodes.AASTORE));
		}
		code.add(new MethodInsnNode(Opcodes.INVOKESTATIC,"net/forbric/kernel/runtime/KernelWrapOperations","reordered","(L"+OP+";ZLjava/lang/String;[Ljava/lang/Object;)L"+OP+";",false));
		code.add(MixinHandlerShim.callOwn(mixin,true,original.name,original.desc));
		code.add(new InsnNode(Type.getReturnType(original.desc).getOpcode(Opcodes.IRETURN)));
		wrapper.maxLocals=next;wrapper.maxStack=k*2+8;
		return wrapper;
	}
	private static void box(InsnList code,Type type){
		String owner=switch(type.getSort()){case Type.BOOLEAN->"java/lang/Boolean";case Type.BYTE->"java/lang/Byte";case Type.CHAR->"java/lang/Character";
			case Type.SHORT->"java/lang/Short";case Type.INT->"java/lang/Integer";case Type.LONG->"java/lang/Long";case Type.FLOAT->"java/lang/Float";
			case Type.DOUBLE->"java/lang/Double";default->null;};
		if(owner!=null)code.add(new MethodInsnNode(Opcodes.INVOKESTATIC,owner,"valueOf","("+type.getDescriptor()+")L"+owner+";",false));
	}
	private static void set(AnnotationNode annotation,String key,Object value){
		for(int i=0;i<annotation.values.size();i+=2)if(key.equals(annotation.values.get(i))){annotation.values.set(i+1,value);return;}
		annotation.values.add(key);annotation.values.add(value);
	}
}
