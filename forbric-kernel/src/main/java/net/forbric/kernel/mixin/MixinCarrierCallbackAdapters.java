/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.mixin;

import java.util.*;
import java.util.function.BiFunction;
import java.util.function.Function;
import net.forbric.api.Ecosystem;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;

/** Atomic adaptations of structurally recognized callbacks to the live merged-game operations. */
public final class MixinCarrierCallbackAdapters {
	public static final String PROPERTY="forbric.carrierCallbackAdapters";
    private static final String OP=MixinWrapOperationShim.OPERATION;
    private MixinCarrierCallbackAdapters() { }
    public static int adapt(ClassNode mixin,Function<String,ClassNode> targets) {
        return adapt(mixin,targets,NativeGameReferences::reference);
    }
    public static int adapt(ClassNode mixin,Function<String,ClassNode> targets,java.util.function.BiFunction<net.forbric.api.Ecosystem,String,ClassNode> references) {
        if ("off".equalsIgnoreCase(net.forbric.kernel.util.ForbricSwitches.get(PROPERTY)) || mixin == null) return 0;
        List<String> owners = MixinFit.mixinTargets(mixin);
        if (owners.size() != 1) return 0;
        return renameOperation(mixin,targets) + fluid(mixin,targets,references) + models(mixin,targets,references)
                + modelParser(mixin,targets,references) + section(mixin,targets,references) + placement(mixin,targets,references);
    }
	private static final String MODELS = "net/minecraft/client/resources/model/ModelManager";
	private static final String FROM_STREAM = "Lnet/minecraft/client/resources/model/cuboid/CuboidModel;fromStream(Ljava/io/Reader;)Lnet/minecraft/client/resources/model/cuboid/CuboidModel;";
	private static final String PARSE = "Lnet/neoforged/neoforge/client/model/UnbakedModelParser;parse(Ljava/io/Reader;)Lnet/minecraft/client/resources/model/UnbakedModel;";
	/**
	 * NeoForge parses a block model with {@code UnbakedModelParser.parse(Reader)} where vanilla called
	 * {@code CuboidModel.fromStream(Reader)}, in the same place of the same method. An {@code @Inject} at the vanilla call —
	 * any extras, cancellable or not — or a {@code @ModifyArg} of its reader (the one argument both calls take) is pointed at
	 * NeoForge's call, each on its own, when the method it binds makes that call once and vanilla's no more (and, with the
	 * class the mod was compiled against, the native method made vanilla's once). A {@code @Local} is moved only when proved
	 * to hold, at NeoForge's call, what it held at vanilla's. Injectors typed by the vanilla call's result are left alone.
	 */
	private static int modelParser(ClassNode mixin,Function<String,ClassNode> targets,BiFunction<Ecosystem,String,ClassNode> references){
		if(!MixinCallbackShape.targets(mixin,MODELS))return 0;
		ClassNode target=targets.apply(MODELS),source=references==null?null:references.apply(MixinStubRebind.ecosystemOf(mixin.name),MODELS);
		if(target==null)return 0;
		int changed=0;
		for(MethodNode handler:MixinCallbackProofs.alone(mixin,m->(MixinCallbackShape.kind(m,"Inject")||MixinCallbackShape.kind(m,"ModifyArg"))&&atTheCall(m,FROM_STREAM))){
			MethodNode host=MixinTargetSelectors.one(handler,target);
			if(host==null||MixinPlayerWorldCallbackAdapter.count(host,PARSE)!=1||MixinPlayerWorldCallbackAdapter.count(host,FROM_STREAM)!=0)continue;
			MethodNode reference=source==null?null:MixinTargetSelectors.one(handler,source);
			if(source!=null&&(reference==null||!(reference.name+reference.desc).equals(host.name+host.desc)||MixinPlayerWorldCallbackAdapter.count(reference,FROM_STREAM)!=1))continue;
			if(!MixinHandlerShape.of(handler).locals().isEmpty()&&reference==null)continue;
			MixinCallbackProofs.Landing landing=at(handler,FROM_STREAM,PARSE,source,reference,target,host);
			if(landing==null)continue;
			MixinPlayerWorldCallbackAdapter.set(MixinFit.atNodes(MixinFit.injectorOf(handler)).getFirst(),"target",PARSE);
			landing.pin(handler,host);
			changed++;
		}
		return changed;
	}
	/** One {@code @At(INVOKE)} at {@code call}: no ordinal past the first, no {@code by}, {@code opcode} or {@code args}. */
	private static boolean atTheCall(MethodNode handler,String call){
		List<AnnotationNode> ats=MixinFit.atNodes(MixinFit.injectorOf(handler));if(ats.size()!=1)return false;AnnotationNode at=ats.getFirst();
		Object ordinal=MixinFit.value(at,"ordinal");
		return "INVOKE".equals(MixinFit.asString(MixinFit.value(at,"value")))&&call.equals(MixinFit.value(at,"target"))&&MixinFit.value(at,"by")==null
				&&MixinFit.value(at,"opcode")==null&&MixinFit.value(at,"args")==null&&(ordinal==null||ordinal instanceof Number n&&n.intValue()<=0);
	}
	/**
	 * Where a handler at the one call {@code was} of the native method lands at the one call {@code now} that replaced it in
	 * the live one: its extras are {@code @Local}s, {@code @Share}s and {@code @Cancellable}s, and each {@code @Local} holds at
	 * {@code now} what it held at {@code was}. Null when it does not land.
	 */
	private static MixinCallbackProofs.Landing at(MethodNode handler,String was,String now,ClassNode source,MethodNode reference,ClassNode target,MethodNode host){
		MixinHandlerShape shape=MixinHandlerShape.of(handler);
		if(!shape.extras().stream().allMatch(e->e.role()==MixinHandlerShape.Role.LOCAL||e.role()==MixinHandlerShape.Role.SHARE||e.role()==MixinHandlerShape.Role.CANCELLABLE))return null;
		MethodInsnNode live=MixinPlayerWorldCallbackAdapter.first(host,now);
		if(shape.locals().isEmpty())return new MixinCallbackProofs.Landing(Map.of(),live);
		MethodInsnNode before=MixinPlayerWorldCallbackAdapter.first(reference,was);
		Map<Integer,Integer> locals=MixinCallbackProofs.correspondLocals(handler,source.name,reference,before,target.name,host,live,java.util.function.IntUnaryOperator.identity());
		return locals==null?null:new MixinCallbackProofs.Landing(locals,live);
	}
    /** Any same-host call of a pure platform delegate can carry the original Operation; lambda names do not identify it. */
    private static int renameOperation(ClassNode mixin,Function<String,ClassNode> targets) {
        List<String> owners=MixinFit.mixinTargets(mixin);if(owners.size()!=1)return 0;
        ClassNode target=targets.apply(owners.getFirst());if(target==null)return 0;
        int changed=0;
        for(MethodNode handler:List.copyOf(mixin.methods)) {
            if(!MixinCallbackShape.kind(handler,"WrapOperation"))continue;
            AnnotationNode injector=MixinFit.injectorOf(handler);
            List<AnnotationNode> points=MixinFit.atNodes(injector);if(points.size()!=1)continue;
            MixinFit.Member member=MixinFit.parseMember(MixinFit.asString(MixinFit.value(points.getFirst(),"target")));
            if(member==null||member.owner()==null||member.desc()==null)continue;
            MethodNode host=MixinTargetSelectors.one(handler,target);if(host==null||MixinFit.containsMember(host,MixinFit.asString(MixinFit.value(points.getFirst(),"target"))))continue;
            List<MethodInsnNode> calls=new ArrayList<>();
            for(var instruction:host.instructions)if(instruction instanceof MethodInsnNode call&&pureDelegate(call,member,targets))calls.add(call);
            if(calls.size()!=1)continue;
            long sourceCount=mixin.methods.stream().filter(other->other.desc.equals(handler.desc)&&MixinCallbackShape.kind(other,"WrapOperation")
                    &&MixinTargetSelectors.one(other,target)==host
                    &&MixinFit.atNodes(MixinFit.injectorOf(other)).size()==1
                    &&member.equals(MixinFit.parseMember(MixinFit.asString(MixinFit.value(MixinFit.atNodes(MixinFit.injectorOf(other)).getFirst(),"target"))))).count();
            if(sourceCount!=1)continue;
            changed+=MixinWrapOperationShim.adaptExplicit(mixin,handler,calls.getFirst());
        }
        return changed;
    }
    private static boolean pureDelegate(MethodInsnNode call,MixinFit.Member wanted,Function<String,ClassNode> targets) {
        if(!call.owner.equals(wanted.owner()))return false;
        return delegateChain(call,wanted,name->{try{return targets.apply(name);}catch(RuntimeException unavailable){return null;}},new HashSet<>());
    }
    private static boolean delegateChain(MethodInsnNode call,MixinFit.Member wanted,Function<String,ClassNode> targets,Set<String> seen) {
        if(!seen.add(call.owner+call.name+call.desc))return false;
        ClassNode owner=targets.apply(call.owner);if(owner==null||!owner.name.equals(call.owner))return false;
        DefaultMethodOverloadBridge.Declaration declaration=DefaultMethodOverloadBridge.declaration(owner,call.name,call.desc,targets);
        if(declaration==null)return false;
        MethodNode method=declaration.method();if(!method.tryCatchBlocks.isEmpty())return false;
        List<AbstractInsnNode> body=Arrays.stream(method.instructions.toArray()).filter(i->i.getOpcode()>=0).toList();
        boolean stat=(method.access&Opcodes.ACC_STATIC)!=0;
        if(body.size()<2||body.getLast().getOpcode()!=Type.getReturnType(method.desc).getOpcode(Opcodes.IRETURN))return false;
        MethodInsnNode forwarded=body.get(body.size()-2) instanceof MethodInsnNode last?last:null;if(forwarded==null)return false;
        Type[] inputs=Type.getArgumentTypes(forwarded.desc),arguments=Type.getArgumentTypes(method.desc);
        int cursor=0;
        if(forwarded.getOpcode()!=Opcodes.INVOKESTATIC) {
            if(stat||!(body.get(cursor++) instanceof VarInsnNode self)||self.getOpcode()!=Opcodes.ALOAD||self.var!=0)return false;
            if(cursor<body.size()-2&&body.get(cursor) instanceof MethodInsnNode identity) {
                if(!identity(identity,declaration.owner(),wanted.owner()))return false;cursor++;
            }else if(cursor<body.size()-2&&body.get(cursor) instanceof TypeInsnNode cast) {
                if(cast.getOpcode()!=Opcodes.CHECKCAST||!cast.desc.equals(wanted.owner()))return false;cursor++;
            }
        }
        int[] slots=new int[arguments.length];int slot=stat?0:1;for(int p=0;p<arguments.length;p++){slots[p]=slot;slot+=arguments[p].getSize();}
        for(Type input:inputs) {
            if(cursor>=body.size()-2||!(body.get(cursor++) instanceof VarInsnNode load)||load.getOpcode()!=input.getOpcode(Opcodes.ILOAD))return false;
            boolean parameter=false;for(int p=0;p<arguments.length;p++)if(slots[p]==load.var&&arguments[p].equals(input))parameter=true;
            if(!parameter)return false;
        }
        if(cursor!=body.size()-2)return false;
        if(forwarded.owner.equals(wanted.owner())&&forwarded.name.equals(wanted.name())&&forwarded.desc.equals(wanted.desc()))return true;
        return delegateChain(forwarded,wanted,targets,seen);
    }
    private static boolean identity(MethodInsnNode call,ClassNode owner,String castType) {
        if(!call.owner.equals(owner.name)||!call.desc.equals("()L"+castType+";"))return false;
        List<MethodNode> methods=owner.methods.stream().filter(m->m.name.equals(call.name)&&m.desc.equals(call.desc)).toList();if(methods.size()!=1)return false;
        MethodNode method=methods.getFirst();if((method.access&Opcodes.ACC_PRIVATE)==0||(method.access&Opcodes.ACC_STATIC)!=0||!method.tryCatchBlocks.isEmpty())return false;
        List<AbstractInsnNode> body=Arrays.stream(method.instructions.toArray()).filter(i->i.getOpcode()>=0).toList();
        return body.size()==3&&body.get(0) instanceof VarInsnNode self&&self.getOpcode()==Opcodes.ALOAD&&self.var==0
                &&body.get(1) instanceof TypeInsnNode cast&&cast.getOpcode()==Opcodes.CHECKCAST&&cast.desc.equals(castType)&&body.get(2).getOpcode()==Opcodes.ARETURN;
    }

	private static final String FLUIDS = "net/minecraft/world/entity/EntityFluidInteraction";
	private static final String FLUID_NATIVE = "update(Lnet/minecraft/world/entity/Entity;Z)V", FLUID_LIVE = "update(Lnet/minecraft/world/entity/Entity;Ljava/util/function/Predicate;)V";
	private static final String CALLBACK = "Lorg/spongepowered/asm/mixin/injection/callback/CallbackInfo;", RETURNABLE = "Lorg/spongepowered/asm/mixin/injection/callback/CallbackInfoReturnable;";
	/**
	 * NeoForge's {@code EntityFluidInteraction.update} takes a {@code Predicate} of fluid types where vanilla's took a
	 * {@code boolean}, and keeps vanilla's signature only as a stub that delegates to it. Each {@code @Inject} written for
	 * vanilla's {@code update} moves to the live one on its own, whatever else the mixin holds: its points must occur there
	 * as in vanilla's body, its {@code @Local}s must be proved ({@link MixinCallbackProofs#land}), and a handler that takes
	 * the target's arguments must never read the boolean — which no longer exists, so its parameter becomes the
	 * {@code Predicate} it is not going to read.
	 */
	private static int fluid(ClassNode mixin,Function<String,ClassNode> targets,BiFunction<Ecosystem,String,ClassNode> references) {
		if(!MixinCallbackShape.targets(mixin,FLUIDS))return 0;
		ClassNode target=targets.apply(FLUIDS),source=references==null?null:references.apply(MixinStubRebind.ecosystemOf(mixin.name),FLUIDS);
		MethodNode live=target==null?null:MixinPlayerWorldCallbackAdapter.selector(target,FLUID_LIVE),reference=source==null?null:MixinPlayerWorldCallbackAdapter.selector(source,FLUID_NATIVE);
		if(live==null)return 0;
		String declared="(Lnet/minecraft/world/entity/Entity;Z"+CALLBACK+")V";
		int changed=0;
		for(MethodNode handler:MixinCallbackProofs.alone(mixin,m->MixinCallbackShape.kind(m,"Inject")&&MixinCallbackShape.binds(m,target,FLUID_NATIVE))){
			MixinHandlerShape shape=MixinHandlerShape.of(handler);
			boolean arguments=shape.operands(declared);
			if(!arguments&&!shape.operands("("+CALLBACK+")V"))continue;
			if(arguments&&!MixinCallbackProofs.unread(handler,MixinCallbackProofs.slots(handler)[1]))continue;
			MixinCallbackProofs.Landing landing=MixinCallbackProofs.land(handler,source==null?null:source.name,reference,target.name,live,j->j==0?0:-1,FLUID_NATIVE.substring(FLUID_NATIVE.indexOf('(')));
			if(landing==null)continue;
			MixinPlayerWorldCallbackAdapter.set(MixinFit.injectorOf(handler),"method",List.of(FLUID_LIVE));
			if(arguments)retype(handler,1,"Ljava/util/function/Predicate;");
			landing.pin(handler,live);
			changed++;
		}
		return changed;
	}
	/** Gives the unread parameter {@code parameter} of {@code method} the type {@code descriptor}: descriptor, frames, debug locals. */
	private static void retype(MethodNode method,int parameter,String descriptor){
		Type[] parameters=Type.getArgumentTypes(method.desc);int slot=MixinCallbackProofs.slots(method)[parameter];
		parameters[parameter]=Type.getType(descriptor);method.desc=Type.getMethodDescriptor(Type.getReturnType(method.desc),parameters);method.signature=null;
		int local=(method.access&Opcodes.ACC_STATIC)==0?parameter+1:parameter;
		for(var instruction:method.instructions)if(instruction instanceof FrameNode frame&&(frame.type==Opcodes.F_FULL||frame.type==Opcodes.F_NEW)&&frame.local!=null&&frame.local.size()>local)frame.local.set(local,Type.getType(descriptor).getInternalName());
		if(method.localVariables!=null)for(var variable:method.localVariables)if(variable.index==slot)variable.desc=descriptor;
	}
	private static final String DISCOVER = "discoverModelDependencies(Ljava/util/Map;Lnet/minecraft/client/resources/model/BlockStateModelLoader$LoadedModels;Lnet/minecraft/client/resources/model/ClientItemInfoLoader$LoadedClientInfos;)Lnet/minecraft/client/resources/model/ModelManager$ResolvedModels;";
	private static final String STANDALONE = "Lnet/neoforged/neoforge/client/model/standalone/StandaloneModelLoader$LoadedModels;";
	/**
	 * NeoForge's {@code ModelManager.discoverModelDependencies} takes the standalone models as one more argument, and keeps
	 * vanilla's signature only as a stub. Each {@code @Inject} written for vanilla's method moves to NeoForge's on its own:
	 * any point that occurs there as in vanilla's body, any proved {@code @Local}s ({@link MixinCallbackProofs#land}); a
	 * handler that takes the target's arguments is wrapped so it still receives vanilla's three, and one that takes only
	 * its callback simply selects NeoForge's method.
	 */
	private static int models(ClassNode mixin,Function<String,ClassNode> targets,BiFunction<Ecosystem,String,ClassNode> references) {
		if(!MixinCallbackShape.targets(mixin,MODELS))return 0;
		ClassNode target=targets.apply(MODELS),source=references==null?null:references.apply(MixinStubRebind.ecosystemOf(mixin.name),MODELS);
		if(target==null)return 0;
		List<MethodNode> hosts=target.methods.stream().filter(m->m.name.equals("discoverModelDependencies")&&m.desc.contains(STANDALONE)).toList();
		if(hosts.size()!=1)return 0;
		MethodNode live=hosts.getFirst(),reference=source==null?null:MixinPlayerWorldCallbackAdapter.selector(source,DISCOVER);
		// Where NeoForge inserted its argument: the one position whose removal leaves vanilla's arguments.
		String nativeDesc=DISCOVER.substring(DISCOVER.indexOf('('));
		Type[] old=Type.getArgumentTypes(nativeDesc),now=Type.getArgumentTypes(live.desc);
		if(now.length!=old.length+1)return 0;
		int added=-1;
		for(int p=0;p<now.length;p++){
			boolean same=now[p].equals(Type.getType(STANDALONE));for(int n=0;n<old.length&&same;n++)same=old[n].equals(now[n<p?n:n+1]);
			if(same){if(added>=0)return 0;added=p;}
		}
		if(added<0)return 0;
		final int inserted=added;
		String callback=Type.getReturnType(nativeDesc).equals(Type.VOID_TYPE)?CALLBACK:RETURNABLE;
		String declared=nativeDesc.substring(0,nativeDesc.indexOf(')'))+callback+")V";
		int changed=0;
		for(MethodNode handler:MixinCallbackProofs.alone(mixin,m->MixinCallbackShape.kind(m,"Inject")&&MixinCallbackShape.binds(m,target,DISCOVER))){
			MixinHandlerShape shape=MixinHandlerShape.of(handler);
			boolean arguments=shape.operands(declared);
			if(!arguments&&!shape.operands("("+callback+")V"))continue;
			MixinCallbackProofs.Landing landing=MixinCallbackProofs.land(handler,source==null?null:source.name,reference,target.name,live,
					j->j<inserted?j:j==inserted?-1:j-1,nativeDesc);
			if(landing==null)continue;
			landing.pin(handler,live);
			MethodNode carrier=handler;
			if(arguments){List<Type> params=new ArrayList<>(List.of(Type.getArgumentTypes(handler.desc)));params.add(inserted,Type.getType(STANDALONE));carrier=delegate(mixin,handler,params,inserted);}
			MixinPlayerWorldCallbackAdapter.set(MixinFit.injectorOf(carrier),"method",List.of(live.name+live.desc));
			changed++;
		}
		return changed;
	}
    private static int section(ClassNode mixin,Function<String,ClassNode> targets,java.util.function.BiFunction<net.forbric.api.Ecosystem,String,ClassNode> references) {
        List<String> owners=MixinFit.mixinTargets(mixin);if(owners.size()!=1)return 0;
        ClassNode target=targets.apply(owners.getFirst()),reference=references.apply(MixinStubRebind.ecosystemOf(mixin.name),owners.getFirst());
        if(target==null||reference==null)return 0;
        int changed=0;
        for(MethodNode handler:mixin.methods) {
            if(!MixinCallbackShape.kind(handler,"Inject"))continue;
            AnnotationNode injector=MixinFit.injectorOf(handler);
            List<AnnotationNode> points=MixinFit.atNodes(injector);if(points.size()!=1)continue;
            String member=MixinFit.asString(MixinFit.value(points.getFirst(),"target"));
            if(!"INVOKE".equals(MixinFit.value(points.getFirst(),"value"))||member==null)continue;
            MethodNode old=MixinTargetSelectors.one(handler,reference),live=MixinTargetSelectors.one(handler,target);if(old==null||live==null)continue;
            List<MethodInsnNode> before=invocations(old,member),after=invocations(live,member);if(before.size()!=1||after.size()!=1)continue;
            long declarations=mixin.methods.stream().filter(other->other.desc.equals(handler.desc)&&MixinCallbackShape.kind(other,"Inject")
                    &&MixinTargetSelectors.one(other,target)==live
                    &&MixinFit.atNodes(MixinFit.injectorOf(other)).size()==1
                    &&member.equals(MixinFit.asString(MixinFit.value(MixinFit.atNodes(MixinFit.injectorOf(other)).getFirst(),"target")))).count();
            if(declarations!=1)continue;
            Map<Integer,Integer> mapping=MixinLocalOriginProof.prove(handler,target.name,old,before.getFirst(),live,after.getFirst());
            if(mapping==null)mapping=CallOccurrenceAlignment.prefixLocals(handler,target.name,old,before.getFirst(),live,after.getFirst());
            if(mapping==null)continue;
            for(var local:mapping.entrySet()) {
                AnnotationNode sugar=MixinStubRebind.sugar(handler,local.getKey(),MixinRetarget.LOCAL_SUGAR);if(sugar==null)continue;
                if(Integer.valueOf(local.getValue()).equals(MixinFit.value(sugar,"index")))continue;
                MixinPlayerWorldCallbackAdapter.set(sugar,"index",local.getValue());changed++;
            }
        }
        return changed;
    }
    private static List<MethodInsnNode> invocations(MethodNode method,String member){List<MethodInsnNode> result=new ArrayList<>();for(var instruction:method.instructions)if(instruction instanceof MethodInsnNode call&&CallOccurrenceAlignment.member(call).equals(member))result.add(call);return result;}

	private static MethodNode delegate(ClassNode mixin,MethodNode handler,List<Type> params,int inserted) {
		AnnotationNode annotation=MixinFit.injectorOf(handler);Type[] old=Type.getArgumentTypes(handler.desc);boolean stat=(handler.access&Opcodes.ACC_STATIC)!=0;
		MethodNode outer=new MethodNode(handler.access,handler.name,Type.getMethodDescriptor(Type.getReturnType(handler.desc),params.toArray(Type[]::new)),null,null);
		outer.visibleAnnotations=new ArrayList<>(List.of(annotation));outer.invisibleParameterAnnotations=MixinWrapOperationShim.shifted(handler.invisibleParameterAnnotations,old.length,1,inserted,params.size());outer.visibleParameterAnnotations=MixinWrapOperationShim.shifted(handler.visibleParameterAnnotations,old.length,1,inserted,params.size());
		int[] slots=new int[params.size()];int slot=stat?0:1;for(int i=0;i<params.size();i++){slots[i]=slot;slot+=params.get(i).getSize();}
		if(!stat)outer.instructions.add(new VarInsnNode(Opcodes.ALOAD,0));for(int i=0;i<old.length;i++){int n=i>=inserted?i+1:i;outer.instructions.add(new VarInsnNode(old[i].getOpcode(Opcodes.ILOAD),slots[n]));}
		handler.name+="$forbricOriginal";removeInjector(handler,annotation);handler.visibleParameterAnnotations=null;handler.invisibleParameterAnnotations=null;
		outer.instructions.add(MixinHandlerShim.callOwn(mixin,stat,handler.name,handler.desc));outer.instructions.add(new InsnNode(Type.getReturnType(handler.desc).getOpcode(Opcodes.IRETURN)));outer.maxLocals=slot;outer.maxStack=slot+2;mixin.methods.add(outer);return outer;
	}
	private static int placement(ClassNode mixin,Function<String,ClassNode> targets,BiFunction<Ecosystem,String,ClassNode> references) { return MixinPlacementTransactionAdapter.adapt(mixin,targets,references); }
	static MethodNode named(ClassNode c,String name){return c.methods.stream().filter(m->m.name.equals(name)).findFirst().orElse(null);}
	static void removeInjector(MethodNode handler,AnnotationNode annotation){if(handler.visibleAnnotations!=null)handler.visibleAnnotations.remove(annotation);if(handler.invisibleAnnotations!=null)handler.invisibleAnnotations.remove(annotation);MixinCallbackShape.uniqueMember(handler);}
}
