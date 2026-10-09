/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.mixin;
import java.util.*;import java.util.function.BiFunction;import java.util.function.Function;
import org.objectweb.asm.*;import org.objectweb.asm.tree.*;
import net.forbric.api.Ecosystem;

/** Keep Authored redstone, collision and block-break controls around the native operations and event gates. */
public final class MixinBlockInteractionAdapters {
	public static final String PROPERTY="forbric.blockInteractionAdapters";
	private static final String STATE="net/minecraft/world/level/block/state/BlockState", BLOCK="net/minecraft/world/level/block/Block", POS="net/minecraft/core/BlockPos", OP=MixinWrapOperationShim.OPERATION;
	private MixinBlockInteractionAdapters() { }
	public static int adapt(ClassNode mixin,Function<String,ClassNode> targets){
		return adapt(mixin,targets,NativeGameReferences::reference);
	}
	/**
	 * {@code references} gives the class the mod was compiled against. The bounce and left-click hosts are found by the
	 * native method the handler was written for, which a bare name, a wildcard or a pattern names only in that class
	 * ({@link MixinTargetSelectors#nativeMember}); without it only a selector spelling the descriptor names it.
	 */
	static int adapt(ClassNode mixin,Function<String,ClassNode> targets,BiFunction<Ecosystem,String,ClassNode> references){
		if("off".equalsIgnoreCase(net.forbric.kernel.util.ForbricSwitches.get(PROPERTY)))return 0;
        List<String> owners=MixinFit.mixinTargets(mixin);if(owners.size()!=1)return 0;
        Function<String,ClassNode> sources=owner->references==null?null:references.apply(MixinStubRebind.ecosystemOf(mixin.name),owner);
        return switch(owners.getFirst()){
            case "net/minecraft/world/level/SignalGetter"->signal(mixin,targets);
            case "net/minecraft/world/entity/Entity"->bounce(mixin,targets,sources);
            case "net/minecraft/client/multiplayer/MultiPlayerGameMode"->leftClick(mixin,targets,sources)+breaking(mixin,targets,false);
            case "net/minecraft/server/level/ServerPlayerGameMode"->breaking(mixin,targets,true);
            default->0;
        };
	}
	private static int signal(ClassNode mixin,Function<String,ClassNode> targets){
		ClassNode target=targets.apply("net/minecraft/world/level/SignalGetter");
		MethodNode original=MixinCallbackShape.unique(mixin,m -> MixinCallbackShape.shape(m,"(L"+STATE+";Lnet/minecraft/world/level/BlockGetter;L"+POS+";L"+OP+";)Z",MixinHandlerShape.Want.local("Lnet/minecraft/core/Direction;"))
                && MixinCallbackShape.instance(m) && MixinCallbackShape.kind(m,"WrapOperation") && MixinCallbackShape.binds(m,target,"getSignal(L"+POS+";Lnet/minecraft/core/Direction;)I")
                && MixinCallbackShape.plainPoint(m,"INVOKE","L"+STATE+";isRedstoneConductor(Lnet/minecraft/world/level/BlockGetter;L"+POS+";)Z"));MethodNode host=target==null?null:MixinCarrierCallbackAdapters.named(target,"getSignal");
		String live="L"+STATE+";shouldCheckWeakPower(Lnet/minecraft/world/level/SignalGetter;L"+POS+";Lnet/minecraft/core/Direction;)Z";
		if(original==null||host==null||Type.getArgumentTypes(original.desc).length!=5||!Type.getArgumentTypes(original.desc)[3].equals(Type.getObjectType(OP))||MixinPlayerWorldCallbackAdapter.count(host,live)!=1)return 0;
		AnnotationNode inject=MixinFit.injectorOf(original);if(inject==null)return 0;MixinPlayerWorldCallbackAdapter.set(MixinFit.atNodes(inject).getFirst(),"target",live);
		String desc="(L"+STATE+";Lnet/minecraft/world/level/SignalGetter;L"+POS+";Lnet/minecraft/core/Direction;L"+OP+";)Z";
		MethodNode outer=new MethodNode(Opcodes.ACC_PRIVATE,original.name,desc,null,null);outer.visibleAnnotations=new ArrayList<>(List.of(inject));InsnList c=outer.instructions;
		load(c,0,1,2,3);c.add(new VarInsnNode(Opcodes.ALOAD,5));c.add(new InsnNode(Opcodes.ICONST_1));c.add(new LdcInsnNode("0,1"));c.add(new InsnNode(Opcodes.ICONST_3));c.add(new TypeInsnNode(Opcodes.ANEWARRAY,"java/lang/Object"));c.add(new InsnNode(Opcodes.DUP));c.add(new InsnNode(Opcodes.ICONST_2));c.add(new VarInsnNode(Opcodes.ALOAD,4));c.add(new InsnNode(Opcodes.AASTORE));reordered(c);c.add(new VarInsnNode(Opcodes.ALOAD,4));finish(mixin,original,outer,inject,Opcodes.IRETURN,6);return 1;
	}
	private static int bounce(ClassNode mixin,Function<String,ClassNode> targets,Function<String,ClassNode> sources){
		MethodNode original=MixinCallbackShape.unique(mixin,m->MixinCallbackShape.shape(m,"(Lnet/minecraft/world/entity/Entity;L"+BLOCK+";L"+OP+";)D",MixinHandlerShape.Want.local("L"+STATE+";")) && MixinCallbackShape.instance(m) && MixinCallbackShape.kind(m,"WrapOperation") && MixinCallbackShape.plainPoint(m,"INVOKE","Lnet/minecraft/world/entity/Entity;getBlockBounciness(L"+BLOCK+";)D"));ClassNode target=targets.apply("net/minecraft/world/entity/Entity");if(original==null||target==null)return 0;
		if(!original.desc.equals("(Lnet/minecraft/world/entity/Entity;L"+BLOCK+";L"+OP+";L"+STATE+";)D"))return 0;
		String authored=MixinTargetSelectors.nativeMember(original,sources.apply(target.name),target.name);if(authored==null)return 0;String hostName=authored.substring(0,authored.indexOf('('));
		String call="getBlockBounciness(L"+POS+";L"+STATE+";)D";List<MethodNode> hosts=target.methods.stream().filter(m->m.name.equals(hostName)&&MixinPlayerWorldCallbackAdapter.count(m,"L"+target.name+";"+call)==1).toList();if(hosts.size()!=1)return 0;
		AnnotationNode inject=MixinFit.injectorOf(original);List<AnnotationNode> ats=MixinFit.atNodes(inject);if(ats.size()!=1)return 0;MixinPlayerWorldCallbackAdapter.set(inject,"method",List.of(hosts.getFirst().name+hosts.getFirst().desc));MixinPlayerWorldCallbackAdapter.set(ats.getFirst(),"target","L"+target.name+";"+call);
		MethodNode outer=new MethodNode(Opcodes.ACC_PRIVATE,original.name,"(Lnet/minecraft/world/entity/Entity;L"+POS+";L"+STATE+";L"+OP+";)D",null,null);outer.visibleAnnotations=new ArrayList<>(List.of(inject));InsnList c=outer.instructions;
		load(c,0,1,3);c.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL,STATE,"getBlock","()L"+BLOCK+";",false));fixed(c,List.of(Type.getObjectType(target.name),Type.getObjectType(POS),Type.getObjectType(STATE)),new int[]{1,2,3},4);c.add(new VarInsnNode(Opcodes.ALOAD,3));finish(mixin,original,outer,inject,Opcodes.DRETURN,5);return 1;
	}
	private static int leftClick(ClassNode mixin,Function<String,ClassNode> targets,Function<String,ClassNode> sources){
        ClassNode target=targets.apply("net/minecraft/client/multiplayer/MultiPlayerGameMode");if(target==null)return 0;
        Set<String> live=reachableMethods(target);int changed=0;
        List<MethodNode> handlers=mixin.methods.stream().filter(m -> MixinCallbackShape.shape(m,"(Lnet/minecraft/client/multiplayer/MultiPlayerGameMode;L"+POS+";L"+OP+";)Z",MixinHandlerShape.Want.local("Lnet/minecraft/core/Direction;"))
                && MixinCallbackShape.instance(m) && MixinCallbackShape.kind(m,"WrapOperation")
                && MixinCallbackShape.plainPoint(m,"INVOKE","Lnet/minecraft/client/multiplayer/MultiPlayerGameMode;destroyBlock(L"+POS+";)Z")).toList();
        ClassNode source=handlers.isEmpty()?null:sources.apply(target.name);
        // Two handlers written for one native method are ambiguous, whatever each selector looks like.
        Map<MethodNode,String> authored=new HashMap<>();Set<String> selections=new HashSet<>();
        for(MethodNode handler:handlers){String member=MixinTargetSelectors.nativeMember(handler,source,target.name);authored.put(handler,member);
            if(!selections.add(member!=null?member:String.valueOf(MixinTargetSelectors.selectors(handler))))return 0;}
		for(MethodNode handler:handlers) {
        AnnotationNode inject=MixinFit.injectorOf(handler);String member=authored.get(handler);if(member==null)continue;
        int descriptor=member.indexOf('(');
        String name=member.substring(0,descriptor),desc=member.substring(descriptor);
		List<MethodNode> hosts=target.methods.stream().filter(m->m.name.equals(name)&&sameArgumentProjection(desc,m.desc)
                &&live.contains(m.name+m.desc)&&MixinPlayerWorldCallbackAdapter.count(m,"L"+target.name+";destroyBlock(L"+POS+";)Z")==1).toList();if(hosts.size()!=1)continue;String selector=hosts.getFirst().name+hosts.getFirst().desc;
		if(MixinCallbackShape.binds(handler,target,selector))continue;MixinPlayerWorldCallbackAdapter.set(inject,"method",List.of(selector));changed++;
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
        try {
            if(!Type.getReturnType(before).equals(Type.getReturnType(after)))return false;
            Type[] source=Type.getArgumentTypes(before),current=Type.getArgumentTypes(after);int matched=0;
            for(Type parameter:current)if(matched<source.length&&parameter.equals(source[matched]))matched++;
            return matched==source.length;
        }catch(IllegalArgumentException invalid){return false;}
    }
	private static int breaking(ClassNode mixin,Function<String,ClassNode> targets,boolean server){
		String owner=server?"net/minecraft/server/level/ServerPlayerGameMode":"net/minecraft/client/multiplayer/MultiPlayerGameMode";ClassNode target=targets.apply(owner);
		MethodNode original=MixinCallbackShape.unique(mixin,m -> MixinCallbackShape.shape(m,server?
                "(Lnet/minecraft/server/level/ServerLevel;L"+POS+";ZL"+OP+";)Z":
                "(Lnet/minecraft/world/level/Level;L"+POS+";L"+STATE+";IL"+OP+";)Z",MixinHandlerShape.Want.local("L"+STATE+";"),MixinHandlerShape.Want.local("L"+BLOCK+";"))
                && MixinCallbackShape.instance(m) && MixinCallbackShape.kind(m,"WrapOperation") && MixinCallbackShape.binds(m,target,"destroyBlock(L"+POS+";)Z")
                && MixinCallbackShape.plainPoint(m,"INVOKE",server?"Lnet/minecraft/server/level/ServerLevel;removeBlock(L"+POS+";Z)Z":"Lnet/minecraft/world/level/Level;setBlock(L"+POS+";L"+STATE+";I)Z"));MethodNode host=target==null?null:MixinCarrierCallbackAdapters.named(target,"destroyBlock");
		if(original==null||host==null||MixinFit.injectorOf(original)==null||Type.getArgumentTypes(original.desc).length!=(server?6:7))return 0;
		List<MethodInsnNode> calls=new ArrayList<>();for(var i:host.instructions)if(i instanceof MethodInsnNode call&&(server?call.owner.equals(owner)&&call.name.equals("removeBlock"):call.owner.equals(STATE)&&call.name.equals("onDestroyedByPlayer")))calls.add(call);
		if(calls.isEmpty()||(!server&&calls.size()!=1)||calls.stream().map(c->c.desc).distinct().count()!=1)return 0;
		MethodInsnNode live=calls.getFirst();Type[] nativeArgs=Type.getArgumentTypes(live.desc);List<Type> params=new ArrayList<>(List.of(Type.getObjectType(live.owner)));params.addAll(List.of(nativeArgs));params.add(Type.getObjectType(OP));params.add(Type.getObjectType(BLOCK));
		AnnotationNode inject=MixinFit.injectorOf(original);MixinPlayerWorldCallbackAdapter.set(MixinFit.atNodes(inject).getFirst(),"target","L"+live.owner+";"+live.name+live.desc);
		MethodNode outer=new MethodNode(Opcodes.ACC_PRIVATE,original.name,Type.getMethodDescriptor(Type.BOOLEAN_TYPE,params.toArray(Type[]::new)),null,null);outer.visibleAnnotations=new ArrayList<>(List.of(inject));
		int[] slots=new int[params.size()];int slot=1;for(int i=0;i<params.size();i++){slots[i]=slot;slot+=params.get(i).getSize();}int op=1+nativeArgs.length;
		outer.invisibleParameterAnnotations=new List[params.size()];outer.invisibleParameterAnnotations[op+1]=original.invisibleParameterAnnotations[server?5:6];
		InsnList c=outer.instructions;c.add(new VarInsnNode(Opcodes.ALOAD,0));
		if(server){c.add(new VarInsnNode(Opcodes.ALOAD,0));c.add(new FieldInsnNode(Opcodes.GETFIELD,mixin.name,"level","Lnet/minecraft/server/level/ServerLevel;"));c.add(new VarInsnNode(Opcodes.ALOAD,slots[1]));c.add(new InsnNode(Opcodes.ICONST_0));}
		else{load(c,slots[1],slots[2],slots[6]);c.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL,"net/minecraft/world/level/material/FluidState","createLegacyBlock","()L"+STATE+";",false));c.add(new IntInsnNode(Opcodes.BIPUSH,11));}
		fixed(c,params.subList(0,op),Arrays.copyOf(slots,op),slots[op]);c.add(new VarInsnNode(Opcodes.ALOAD,server?slots[2]:slots[0]));c.add(new VarInsnNode(Opcodes.ALOAD,slots[op+1]));finish(mixin,original,outer,inject,Opcodes.IRETURN,slot);return 1;
	}
	private static void load(InsnList c,int... slots){for(int slot:slots)c.add(new VarInsnNode(Opcodes.ALOAD,slot));}
	private static void reordered(InsnList c){c.add(new MethodInsnNode(Opcodes.INVOKESTATIC,MixinWrapOperationShim.RUNTIME,"reordered","(L"+OP+";ZLjava/lang/String;[Ljava/lang/Object;)L"+OP+";",false));}
	private static void fixed(InsnList c,List<Type> params,int[] slots,int operation){c.add(new VarInsnNode(Opcodes.ALOAD,operation));c.add(new InsnNode(Opcodes.ICONST_0));c.add(new LdcInsnNode(""));c.add(new IntInsnNode(Opcodes.BIPUSH,params.size()));c.add(new TypeInsnNode(Opcodes.ANEWARRAY,"java/lang/Object"));for(int i=0;i<params.size();i++){c.add(new InsnNode(Opcodes.DUP));c.add(new IntInsnNode(Opcodes.BIPUSH,i));Type type=params.get(i);c.add(new VarInsnNode(type.getOpcode(Opcodes.ILOAD),slots[i]));if(type.equals(Type.BOOLEAN_TYPE))c.add(new MethodInsnNode(Opcodes.INVOKESTATIC,"java/lang/Boolean","valueOf","(Z)Ljava/lang/Boolean;",false));c.add(new InsnNode(Opcodes.AASTORE));}reordered(c);}
	private static void finish(ClassNode mixin,MethodNode original,MethodNode outer,AnnotationNode inject,int exit,int locals){original.name+="$forbricOriginal";MixinCarrierCallbackAdapters.removeInjector(original,inject);original.invisibleParameterAnnotations=null;original.visibleParameterAnnotations=null;outer.instructions.add(MixinHandlerShim.callOwn(mixin,false,original.name,original.desc));outer.instructions.add(new InsnNode(exit));outer.maxStack=locals+14;outer.maxLocals=locals;mixin.methods.add(outer);}
}
