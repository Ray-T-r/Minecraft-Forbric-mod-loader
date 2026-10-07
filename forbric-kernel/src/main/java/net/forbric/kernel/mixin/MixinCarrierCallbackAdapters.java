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
        if ("off".equalsIgnoreCase(System.getProperty(PROPERTY)) || mixin == null) return 0;
        List<String> owners = MixinFit.mixinTargets(mixin);
        if (owners.size() != 1) return 0;
        return switch(owners.getFirst()) {
            case "net/minecraft/client/multiplayer/ClientPacketListener" -> renameOperation(mixin,targets,"net/minecraft/client/multiplayer/ClientPacketListener","lambda$handleBlockEntityData$0","onDataPacket");
            case "net/minecraft/world/level/chunk/LevelChunk" -> renameOperation(mixin,targets,"net/minecraft/world/level/chunk/LevelChunk","lambda$replaceWithPacketData$0","handleUpdateTag");
            case "net/minecraft/world/entity/EntityFluidInteraction" -> fluid(mixin,targets);
            case "net/minecraft/client/resources/model/ModelManager" -> models(mixin,targets) + modelParser(mixin,targets);
            case "net/minecraft/world/level/entity/PersistentEntitySectionManager$Callback" -> section(mixin,targets);
            case "net/minecraft/client/gui/render/GuiRenderer" -> gui(mixin,targets);
            case "net/minecraft/world/item/ItemStack" -> placement(mixin,targets);
            default -> 0;
        };
    }
	private static int modelParser(ClassNode mixin,Function<String,ClassNode> targets){
		MethodNode handler=MixinCallbackShape.unique(mixin, m -> m.desc.equals("(Lorg/spongepowered/asm/mixin/injection/callback/CallbackInfoReturnable;Lnet/minecraft/resources/Identifier;Ljava/io/Reader;)V")
                && MixinCallbackShape.kind(m,"Inject") && MixinCallbackShape.selects(m,"lambda$loadBlockModels$2")
                && MixinCallbackShape.plainPoint(m,"INVOKE","Lnet/minecraft/client/resources/model/cuboid/CuboidModel;fromStream(Ljava/io/Reader;)Lnet/minecraft/client/resources/model/cuboid/CuboidModel;")
                && Boolean.TRUE.equals(MixinFit.value(MixinFit.injectorOf(m),"cancellable")));ClassNode target=targets.apply("net/minecraft/client/resources/model/ModelManager");MethodNode host=target==null?null:named(target,"lambda$loadBlockModels$2");if(handler==null||host==null||MixinFit.injectorOf(handler)==null)return 0;
		String live="Lnet/neoforged/neoforge/client/model/UnbakedModelParser;parse(Ljava/io/Reader;)Lnet/minecraft/client/resources/model/UnbakedModel;";if(MixinPlayerWorldCallbackAdapter.count(host,live)!=1)return 0;
		AnnotationNode at=MixinFit.atNodes(MixinFit.injectorOf(handler)).getFirst();if(live.equals(MixinFit.value(at,"target")))return 0;MixinPlayerWorldCallbackAdapter.set(at,"target",live);return 1;
	}
	private static int renameOperation(ClassNode mixin,Function<String,ClassNode> targets,String target,String host,String renamed) {
		MethodNode handler=MixinCallbackShape.unique(mixin, m -> m.desc.equals("(Lnet/minecraft/world/level/block/entity/BlockEntity;Lnet/minecraft/world/level/storage/ValueInput;L"+OP+";)V")
                && MixinCallbackShape.kind(m,"WrapOperation") && MixinCallbackShape.plainPoint(m,"INVOKE","Lnet/minecraft/world/level/block/entity/BlockEntity;loadWithComponents(Lnet/minecraft/world/level/storage/ValueInput;)V")
                && MixinFit.stringList(MixinFit.value(MixinFit.injectorOf(m),"method")).size()==1
                && MixinFit.stringList(MixinFit.value(MixinFit.injectorOf(m),"method")).getFirst().startsWith(host+"("));ClassNode owner=targets.apply(target);
		if(handler==null||owner==null||MixinFit.injectorOf(handler)==null)return 0;
		List<MethodInsnNode> calls=new ArrayList<>();
		for(MethodNode method:owner.methods)if(method.name.equals(host))for(var i:method.instructions)if(i instanceof MethodInsnNode call&&call.owner.equals("net/minecraft/world/level/block/entity/BlockEntity")&&call.name.equals(renamed))calls.add(call);
		if(calls.size()!=1)return 0;
		return MixinWrapOperationShim.adaptExplicit(mixin,handler,calls.getFirst());
	}
	private static int fluid(ClassNode mixin,Function<String,ClassNode> targets) {
		ClassNode target=targets.apply("net/minecraft/world/entity/EntityFluidInteraction");
		String live="update(Lnet/minecraft/world/entity/Entity;Ljava/util/function/Predicate;)V";
		MethodNode host=target==null?null:MixinPlayerWorldCallbackAdapter.selector(target,live);
		if(host==null||MixinPlayerWorldCallbackAdapter.count(host,"Lnet/minecraft/core/BlockPos$MutableBlockPos;getY()I")!=1)return 0;
		MethodNode clear=MixinCallbackShape.unique(mixin,m -> m.desc.equals("(Lnet/minecraft/world/entity/Entity;ZLorg/spongepowered/asm/mixin/injection/callback/CallbackInfo;)V")
                && MixinCallbackShape.kind(m,"Inject") && MixinCallbackShape.selects(m,"update(Lnet/minecraft/world/entity/Entity;Z)V") && MixinCallbackShape.plainPoint(m,"HEAD",null)),
                update=MixinCallbackShape.unique(mixin,m -> m.desc.equals("(Lnet/minecraft/world/entity/Entity;ZLorg/spongepowered/asm/mixin/injection/callback/CallbackInfo;Lnet/minecraft/world/level/material/FluidState;)V")
                        && MixinCallbackShape.kind(m,"Inject") && MixinCallbackShape.selects(m,"update(Lnet/minecraft/world/entity/Entity;Z)V") && MixinCallbackShape.plainPoint(m,"INVOKE","Lnet/minecraft/core/BlockPos$MutableBlockPos;getY()I"));if(clear==null||update==null)return 0;List<MethodNode> handlers=List.of(clear,update);
		for(MethodNode method:handlers){if(method==null||!method.desc.startsWith("(Lnet/minecraft/world/entity/Entity;Z"))return 0;for(var i:method.instructions)if(i instanceof VarInsnNode v&&v.var==2)return 0;}
		for(MethodNode method:handlers){MixinPlayerWorldCallbackAdapter.set(MixinFit.injectorOf(method),"method",List.of(live));method.desc=method.desc.replace("Entity;Z","Entity;Ljava/util/function/Predicate;");method.signature=null;if(method.localVariables!=null)for(var local:method.localVariables)if(local.index==2)local.desc="Ljava/util/function/Predicate;";}
		return 2;
	}
	private static int models(ClassNode mixin,Function<String,ClassNode> targets) {
		MethodNode handler=MixinCallbackShape.unique(mixin,m -> m.desc.equals("(Ljava/util/Map;Lnet/minecraft/client/resources/model/BlockStateModelLoader$LoadedModels;Lnet/minecraft/client/resources/model/ClientItemInfoLoader$LoadedClientInfos;Lorg/spongepowered/asm/mixin/injection/callback/CallbackInfoReturnable;Lnet/minecraft/client/resources/model/ModelDiscovery;)V")
                && MixinCallbackShape.kind(m,"Inject") && MixinCallbackShape.selects(m,"discoverModelDependencies")
                && MixinCallbackShape.plainPoint(m,"NEW","(Lnet/minecraft/client/resources/model/ResolvedModel;Ljava/util/Map;)Lnet/minecraft/client/resources/model/ModelManager$ResolvedModels;"));ClassNode target=targets.apply("net/minecraft/client/resources/model/ModelManager");
		if(handler==null||target==null||handler.name.endsWith("$forbricOriginal")||MixinFit.injectorOf(handler)==null)return 0;
		String extra="Lnet/neoforged/neoforge/client/model/standalone/StandaloneModelLoader$LoadedModels;";
		List<MethodNode> hosts=target.methods.stream().filter(m->m.name.equals("discoverModelDependencies")&&m.desc.contains(extra)).toList();
		if(hosts.size()!=1||MixinPlayerWorldCallbackAdapter.count(hosts.getFirst(),"Lnet/minecraft/client/resources/model/ModelManager$ResolvedModels;<init>(Lnet/minecraft/client/resources/model/ResolvedModel;Ljava/util/Map;)V")!=1)return 0;
		Type[] old=Type.getArgumentTypes(handler.desc);if(old.length!=5)return 0;
		List<Type> params=new ArrayList<>(List.of(old));params.add(3,Type.getType(extra));
		MethodNode outer=delegate(mixin,handler,params,3);MixinPlayerWorldCallbackAdapter.set(MixinFit.injectorOf(outer),"method",List.of(hosts.getFirst().name+hosts.getFirst().desc));
		return 1;
	}
	private static int section(ClassNode mixin,Function<String,ClassNode> targets) {
		MethodNode handler=MixinCallbackShape.unique(mixin,m -> m.desc.equals("(Lorg/spongepowered/asm/mixin/injection/callback/CallbackInfo;J)V") && MixinCallbackShape.kind(m,"Inject")
                && MixinCallbackShape.selects(m,"onMove()V") && MixinCallbackShape.plainPoint(m,"INVOKE","Lnet/minecraft/world/level/entity/PersistentEntitySectionManager$Callback;updateStatus(Lnet/minecraft/world/level/entity/Visibility;Lnet/minecraft/world/level/entity/Visibility;)V"));ClassNode target=targets.apply("net/minecraft/world/level/entity/PersistentEntitySectionManager$Callback");
		MethodNode host=target==null?null:named(target,"onMove");if(handler==null||host==null||handler.invisibleParameterAnnotations==null)return 0;
		int slot=-1;for(var i:host.instructions)if(i instanceof FieldInsnNode f&&f.name.equals("currentSectionKey")&&f.getOpcode()==Opcodes.GETFIELD){var next=i.getNext();while(next!=null&&next.getOpcode()<0)next=next.getNext();if(next instanceof VarInsnNode v&&v.getOpcode()==Opcodes.LSTORE){if(slot!=-1)return 0;slot=v.var;}}
		if(slot<0)return 0;List<AnnotationNode> a=handler.invisibleParameterAnnotations[1];if(a==null||a.size()!=1||!a.getFirst().desc.endsWith("/Local;"))return 0;
		if(Integer.valueOf(slot).equals(MixinFit.value(a.getFirst(),"index")))return 0;MixinPlayerWorldCallbackAdapter.set(a.getFirst(),"index",slot);return 1;
	}
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
