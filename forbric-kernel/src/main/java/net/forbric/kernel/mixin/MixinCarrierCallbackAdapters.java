/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.mixin;

import java.util.*;
import java.util.function.Function;
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
        if ("off".equalsIgnoreCase(System.getProperty(PROPERTY)) || mixin == null) return 0;
        List<String> owners = MixinFit.mixinTargets(mixin);
        if (owners.size() != 1) return 0;
        return renameOperation(mixin,targets) + fluid(mixin,targets) + models(mixin,targets)
                + modelParser(mixin,targets) + section(mixin,targets,references) + gui(mixin,targets) + placement(mixin,targets);
    }
	private static int modelParser(ClassNode mixin,Function<String,ClassNode> targets){
        if(!MixinCallbackShape.targets(mixin,"net/minecraft/client/resources/model/ModelManager"))return 0;
		MethodNode handler=MixinCallbackShape.unique(mixin, m -> m.desc.equals("(Lorg/spongepowered/asm/mixin/injection/callback/CallbackInfoReturnable;Lnet/minecraft/resources/Identifier;Ljava/io/Reader;)V")
                && MixinCallbackShape.kind(m,"Inject") && MixinFit.stringList(MixinFit.value(MixinFit.injectorOf(m),"method")).size()==1
                && MixinCallbackShape.plainPoint(m,"INVOKE","Lnet/minecraft/client/resources/model/cuboid/CuboidModel;fromStream(Ljava/io/Reader;)Lnet/minecraft/client/resources/model/cuboid/CuboidModel;")
                && Boolean.TRUE.equals(MixinFit.value(MixinFit.injectorOf(m),"cancellable")));ClassNode target=targets.apply("net/minecraft/client/resources/model/ModelManager");MethodNode host=target==null||handler==null?null:MixinStubRebind.bound(target,MixinFit.stringList(MixinFit.value(MixinFit.injectorOf(handler),"method")).getFirst());if(handler==null||host==null||MixinFit.injectorOf(handler)==null)return 0;
		String live="Lnet/neoforged/neoforge/client/model/UnbakedModelParser;parse(Ljava/io/Reader;)Lnet/minecraft/client/resources/model/UnbakedModel;";if(MixinPlayerWorldCallbackAdapter.count(host,live)!=1)return 0;
		AnnotationNode at=MixinFit.atNodes(MixinFit.injectorOf(handler)).getFirst();if(live.equals(MixinFit.value(at,"target")))return 0;MixinPlayerWorldCallbackAdapter.set(at,"target",live);return 1;
	}
    /** Any same-host call of a pure platform delegate can carry the original Operation; lambda names do not identify it. */
    private static int renameOperation(ClassNode mixin,Function<String,ClassNode> targets) {
        List<String> owners=MixinFit.mixinTargets(mixin);if(owners.size()!=1)return 0;
        ClassNode target=targets.apply(owners.getFirst());if(target==null)return 0;
        int changed=0;
        for(MethodNode handler:List.copyOf(mixin.methods)) {
            if(!MixinCallbackShape.kind(handler,"WrapOperation"))continue;
            AnnotationNode injector=MixinFit.injectorOf(handler);List<String> selectors=MixinFit.stringList(MixinFit.value(injector,"method"));
            List<AnnotationNode> points=MixinFit.atNodes(injector);if(selectors.size()!=1||points.size()!=1)continue;
            MixinFit.Member member=MixinFit.parseMember(MixinFit.asString(MixinFit.value(points.getFirst(),"target")));
            if(member==null||member.owner()==null||member.desc()==null)continue;
            MethodNode host=MixinStubRebind.bound(target,selectors.getFirst());if(host==null||MixinFit.containsMember(host,MixinFit.asString(MixinFit.value(points.getFirst(),"target"))))continue;
            List<MethodInsnNode> calls=new ArrayList<>();
            for(var instruction:host.instructions)if(instruction instanceof MethodInsnNode call&&pureDelegate(call,member,targets))calls.add(call);
            if(calls.size()!=1)continue;
            long sourceCount=mixin.methods.stream().filter(other->other.desc.equals(handler.desc)&&MixinCallbackShape.kind(other,"WrapOperation")
                    &&MixinFit.stringList(MixinFit.value(MixinFit.injectorOf(other),"method")).equals(selectors)
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

	private static int fluid(ClassNode mixin,Function<String,ClassNode> targets) {
        if(!MixinCallbackShape.targets(mixin,"net/minecraft/world/entity/EntityFluidInteraction"))return 0;
		ClassNode target=targets.apply("net/minecraft/world/entity/EntityFluidInteraction");
		String live="update(Lnet/minecraft/world/entity/Entity;Ljava/util/function/Predicate;)V";
		MethodNode host=target==null?null:MixinPlayerWorldCallbackAdapter.selector(target,live);
		if(host==null||MixinPlayerWorldCallbackAdapter.count(host,"Lnet/minecraft/core/BlockPos$MutableBlockPos;getY()I")!=1)return 0;
		MethodNode clear=MixinCallbackShape.unique(mixin,m -> m.desc.equals("(Lnet/minecraft/world/entity/Entity;ZLorg/spongepowered/asm/mixin/injection/callback/CallbackInfo;)V")
                && MixinCallbackShape.kind(m,"Inject") && MixinCallbackShape.selects(m,"update(Lnet/minecraft/world/entity/Entity;Z)V") && MixinCallbackShape.plainPoint(m,"HEAD",null)),
                update=MixinCallbackShape.unique(mixin,m -> m.desc.equals("(Lnet/minecraft/world/entity/Entity;ZLorg/spongepowered/asm/mixin/injection/callback/CallbackInfo;Lnet/minecraft/world/level/material/FluidState;)V")
                        && MixinCallbackShape.kind(m,"Inject") && MixinCallbackShape.selects(m,"update(Lnet/minecraft/world/entity/Entity;Z)V") && MixinCallbackShape.plainPoint(m,"INVOKE","Lnet/minecraft/core/BlockPos$MutableBlockPos;getY()I"));if(clear==null||update==null)return 0;List<MethodNode> handlers=List.of(clear,update);
		for(MethodNode method:handlers){if(method==null||!method.desc.startsWith("(Lnet/minecraft/world/entity/Entity;Z"))return 0;int omitted=((method.access&Opcodes.ACC_STATIC)==0?1:0)+Type.getArgumentTypes(method.desc)[0].getSize();
                for(var i:method.instructions){if(i instanceof VarInsnNode v&&v.var==omitted)return 0;if(i instanceof IincInsnNode increment&&increment.var==omitted)return 0;}}
		for(MethodNode method:handlers){MixinPlayerWorldCallbackAdapter.set(MixinFit.injectorOf(method),"method",List.of(live));method.desc=method.desc.replace("Entity;Z","Entity;Ljava/util/function/Predicate;");method.signature=null;
                int replaced=((method.access&Opcodes.ACC_STATIC)==0?1:0)+1;
                for(var instruction:method.instructions)if(instruction instanceof FrameNode frame&&(frame.type==Opcodes.F_FULL||frame.type==Opcodes.F_NEW)&&frame.local!=null&&frame.local.size()>replaced)frame.local.set(replaced,"java/util/function/Predicate");
                if(method.localVariables!=null)for(var local:method.localVariables)if(local.index==((method.access&Opcodes.ACC_STATIC)==0?1:0)+1)local.desc="Ljava/util/function/Predicate;";}
		return 2;
	}
	private static int models(ClassNode mixin,Function<String,ClassNode> targets) {
        if(!MixinCallbackShape.targets(mixin,"net/minecraft/client/resources/model/ModelManager"))return 0;
		MethodNode handler=MixinCallbackShape.unique(mixin,m -> m.desc.equals("(Ljava/util/Map;Lnet/minecraft/client/resources/model/BlockStateModelLoader$LoadedModels;Lnet/minecraft/client/resources/model/ClientItemInfoLoader$LoadedClientInfos;Lorg/spongepowered/asm/mixin/injection/callback/CallbackInfoReturnable;Lnet/minecraft/client/resources/model/ModelDiscovery;)V")
                && MixinCallbackShape.kind(m,"Inject") && MixinCallbackShape.selects(m,"discoverModelDependencies")
                && MixinCallbackShape.plainPoint(m,"NEW","(Lnet/minecraft/client/resources/model/ResolvedModel;Ljava/util/Map;)Lnet/minecraft/client/resources/model/ModelManager$ResolvedModels;"));ClassNode target=targets.apply("net/minecraft/client/resources/model/ModelManager");
		if(handler==null||target==null||handler.name.endsWith("$forbricOriginal")||MixinFit.injectorOf(handler)==null)return 0;
		String extra="Lnet/neoforged/neoforge/client/model/standalone/StandaloneModelLoader$LoadedModels;";
		List<MethodNode> hosts=target.methods.stream().filter(m->m.name.equals("discoverModelDependencies")&&m.desc.contains(extra)).toList();
		if(hosts.size()!=1||MixinPlayerWorldCallbackAdapter.count(hosts.getFirst(),"Lnet/minecraft/client/resources/model/ModelManager$ResolvedModels;<init>(Lnet/minecraft/client/resources/model/ResolvedModel;Ljava/util/Map;)V")!=1)return 0;
		Type[] old=Type.getArgumentTypes(handler.desc);if(old.length!=5)return 0;
		Type[] live=Type.getArgumentTypes(hosts.getFirst().desc);
        int callback=-1;for(int p=0;p<old.length;p++)if(old[p].getSort()==Type.OBJECT&&old[p].getInternalName().equals("org/spongepowered/asm/mixin/injection/callback/CallbackInfoReturnable"))callback=p;
        if(callback<0||live.length!=callback+1)return 0;
        int added=-1;for(int p=0;p<live.length;p++) {
            boolean same=live[p].equals(Type.getType(extra));for(int n=0;n<callback&&same;n++)same=old[n].equals(live[n<p?n:n+1]);
            if(same){if(added>=0)return 0;added=p;}
        }
        if(added<0)return 0;
        List<Type> params=new ArrayList<>(List.of(old));params.add(added,Type.getType(extra));
        MethodNode outer=delegate(mixin,handler,params,added);MixinPlayerWorldCallbackAdapter.set(MixinFit.injectorOf(outer),"method",List.of(hosts.getFirst().name+hosts.getFirst().desc));
		return 1;
	}
    private static int section(ClassNode mixin,Function<String,ClassNode> targets,java.util.function.BiFunction<net.forbric.api.Ecosystem,String,ClassNode> references) {
        List<String> owners=MixinFit.mixinTargets(mixin);if(owners.size()!=1)return 0;
        ClassNode target=targets.apply(owners.getFirst()),reference=references.apply(MixinStubRebind.ecosystemOf(mixin.name),owners.getFirst());
        if(target==null||reference==null)return 0;
        int changed=0;
        for(MethodNode handler:mixin.methods) {
            if(!MixinCallbackShape.kind(handler,"Inject"))continue;
            AnnotationNode injector=MixinFit.injectorOf(handler);List<String> selectors=MixinFit.stringList(MixinFit.value(injector,"method"));
            List<AnnotationNode> points=MixinFit.atNodes(injector);if(selectors.size()!=1||points.size()!=1)continue;
            String member=MixinFit.asString(MixinFit.value(points.getFirst(),"target"));
            if(!"INVOKE".equals(MixinFit.value(points.getFirst(),"value"))||member==null)continue;
            MethodNode old=MixinStubRebind.bound(reference,selectors.getFirst()),live=MixinStubRebind.bound(target,selectors.getFirst());if(old==null||live==null)continue;
            List<MethodInsnNode> before=invocations(old,member),after=invocations(live,member);if(before.size()!=1||after.size()!=1)continue;
            long declarations=mixin.methods.stream().filter(other->other.desc.equals(handler.desc)&&MixinCallbackShape.kind(other,"Inject")
                    &&MixinFit.stringList(MixinFit.value(MixinFit.injectorOf(other),"method")).equals(selectors)
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
		outer.visibleAnnotations=new ArrayList<>(List.of(annotation));outer.invisibleParameterAnnotations=MixinWrapOperationShim.shifted(handler.invisibleParameterAnnotations,old.length,1,inserted,params.size());
		int[] slots=new int[params.size()];int slot=stat?0:1;for(int i=0;i<params.size();i++){slots[i]=slot;slot+=params.get(i).getSize();}
		if(!stat)outer.instructions.add(new VarInsnNode(Opcodes.ALOAD,0));for(int i=0;i<old.length;i++){int n=i>=inserted?i+1:i;outer.instructions.add(new VarInsnNode(old[i].getOpcode(Opcodes.ILOAD),slots[n]));}
		handler.name+="$forbricOriginal";removeInjector(handler,annotation);handler.visibleParameterAnnotations=null;handler.invisibleParameterAnnotations=null;
		outer.instructions.add(MixinHandlerShim.callOwn(mixin,stat,handler.name,handler.desc));outer.instructions.add(new InsnNode(Type.getReturnType(handler.desc).getOpcode(Opcodes.IRETURN)));outer.maxLocals=slot;outer.maxStack=slot+2;mixin.methods.add(outer);return outer;
	}
	private static int gui(ClassNode mixin,Function<String,ClassNode> targets) {
        if(!MixinCallbackShape.targets(mixin,"net/minecraft/client/gui/render/GuiRenderer"))return 0;
		MethodNode handler=MixinCallbackShape.unique(mixin,m -> m.desc.equals("(L"+OP+";)Lcom/google/common/collect/ImmutableMap$Builder;") && MixinCallbackShape.kind(m,"WrapOperation")
                && MixinCallbackShape.selects(m,"<init>") && MixinCallbackShape.plainPoint(m,"INVOKE","Lcom/google/common/collect/ImmutableMap;builder()Lcom/google/common/collect/ImmutableMap$Builder;"));ClassNode target=targets.apply("net/minecraft/client/gui/render/GuiRenderer");if(handler==null||target==null||!handler.desc.equals("(L"+OP+";)Lcom/google/common/collect/ImmutableMap$Builder;"))return 0;
		String owner="net/forbric/kernel/runtime/KernelForgePipRenderers";
		int calls=0;for(MethodNode method:target.methods)if(method.name.equals("<init>"))calls+=MixinPlayerWorldCallbackAdapter.count(method,"L"+owner+";build(Ljava/util/List;)Ljava/util/Map;");
		if(calls!=1)return 0;AnnotationNode annotation=MixinFit.injectorOf(handler);if(annotation==null)return 0;
		MixinPlayerWorldCallbackAdapter.set(MixinFit.atNodes(annotation).getFirst(),"target","L"+owner+";build(Ljava/util/List;)Ljava/util/Map;");
		MethodNode outer=new MethodNode(Opcodes.ACC_PRIVATE,handler.name,"(Ljava/util/List;L"+OP+";)Ljava/util/Map;",null,null);outer.visibleAnnotations=new ArrayList<>(List.of(annotation));
		handler.name+="$forbricOriginal";removeInjector(handler,annotation);
		MethodNode builder=new MethodNode(Opcodes.ACC_PRIVATE|Opcodes.ACC_STATIC,"forbric$rendererBuilder","(Ljava/util/Map;[Ljava/lang/Object;)Ljava/lang/Object;",null,null);
		builder.instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC,"com/google/common/collect/ImmutableMap","builder","()Lcom/google/common/collect/ImmutableMap$Builder;",false));builder.instructions.add(new VarInsnNode(Opcodes.ALOAD,0));builder.instructions.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL,"com/google/common/collect/ImmutableMap$Builder","putAll","(Ljava/util/Map;)Lcom/google/common/collect/ImmutableMap$Builder;",false));builder.instructions.add(new InsnNode(Opcodes.ARETURN));builder.maxStack=2;builder.maxLocals=2;MixinCallbackShape.uniqueMember(builder);mixin.methods.add(builder);
		InsnList code=outer.instructions;
		// The native registration event runs once; the source builder adds its exact singleton instances.
		code.add(new VarInsnNode(Opcodes.ALOAD,2));code.add(new InsnNode(Opcodes.ICONST_1));code.add(new TypeInsnNode(Opcodes.ANEWARRAY,"java/lang/Object"));code.add(new InsnNode(Opcodes.DUP));code.add(new InsnNode(Opcodes.ICONST_0));code.add(new VarInsnNode(Opcodes.ALOAD,1));code.add(new InsnNode(Opcodes.AASTORE));code.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE,OP,"call","([Ljava/lang/Object;)Ljava/lang/Object;",true));code.add(new TypeInsnNode(Opcodes.CHECKCAST,"java/util/Map"));code.add(new VarInsnNode(Opcodes.ASTORE,3));
		code.add(new VarInsnNode(Opcodes.ALOAD,0));code.add(new VarInsnNode(Opcodes.ALOAD,3));code.add(new InvokeDynamicInsnNode("call","(Ljava/util/Map;)L"+OP+";",new Handle(Opcodes.H_INVOKESTATIC,"java/lang/invoke/LambdaMetafactory","metafactory","(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;Ljava/lang/invoke/MethodType;Ljava/lang/invoke/MethodHandle;Ljava/lang/invoke/MethodType;)Ljava/lang/invoke/CallSite;",false),Type.getMethodType("([Ljava/lang/Object;)Ljava/lang/Object;"),new Handle(Opcodes.H_INVOKESTATIC,mixin.name,builder.name,builder.desc,false),Type.getMethodType("([Ljava/lang/Object;)Ljava/lang/Object;")));
		code.add(MixinHandlerShim.callOwn(mixin,false,handler.name,handler.desc));code.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL,"com/google/common/collect/ImmutableMap$Builder","build","()Lcom/google/common/collect/ImmutableMap;",false));code.add(new InsnNode(Opcodes.ARETURN));outer.maxStack=6;outer.maxLocals=4;mixin.methods.add(outer);return 1;
	}

	private static int placement(ClassNode mixin,Function<String,ClassNode> targets) { return MixinPlacementTransactionAdapter.adapt(mixin,targets); }
	static MethodNode named(ClassNode c,String name){return c.methods.stream().filter(m->m.name.equals(name)).findFirst().orElse(null);}
	static void removeInjector(MethodNode handler,AnnotationNode annotation){if(handler.visibleAnnotations!=null)handler.visibleAnnotations.remove(annotation);if(handler.invisibleAnnotations!=null)handler.invisibleAnnotations.remove(annotation);MixinCallbackShape.uniqueMember(handler);}
}
