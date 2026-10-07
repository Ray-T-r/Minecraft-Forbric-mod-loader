/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.mixin;
import java.util.*;import java.util.function.Function;
import org.objectweb.asm.*;import org.objectweb.asm.tree.*;

/** Retain the authored breathing callbacks while the native loader computes air supply in CommonHooks. */
public final class MixinBreathingCallbackAdapter {
	public static final String PROPERTY="forbric.breathingCallbacks";
	private static final String LIVING="net/minecraft/world/entity/LivingEntity", LEVEL="net/minecraft/server/level/ServerLevel", OP=MixinWrapOperationShim.OPERATION, SCOPE="net/forbric/kernel/interop/BreathingCallbackScope";
	private MixinBreathingCallbackAdapter() { }
	public static int adapt(ClassNode mixin,Function<String,ClassNode> targets){
		if(!MixinCallbackShape.targets(mixin,LIVING)||"off".equalsIgnoreCase(System.getProperty(PROPERTY)))return 0;
		MethodNode lava=MixinCallbackShape.unique(mixin,m -> m.desc.equals("(L"+LIVING+";Lnet/minecraft/tags/TagKey;L"+OP+";L"+LEVEL+";)Z")
                && MixinCallbackShape.instance(m) && MixinCallbackShape.kind(m,"WrapOperation") && MixinCallbackShape.selects(m,"baseTick()V")
                && MixinCallbackShape.plainPoint(m,"INVOKE","L"+LIVING+";isEyeInFluid(Lnet/minecraft/tags/TagKey;)Z")),
                water=MixinCallbackShape.unique(mixin,m -> m.desc.equals("(L"+LIVING+";L"+OP+";L"+LEVEL+";)Z")
                        && MixinCallbackShape.instance(m) && MixinCallbackShape.kind(m,"WrapOperation") && MixinCallbackShape.selects(m,"baseTick()V")
                        && MixinCallbackShape.plainPoint(m,"INVOKE","Lnet/minecraft/world/effect/MobEffectUtil;hasWaterBreathing(L"+LIVING+";)Z"));ClassNode living=targets.apply(LIVING),hooks=targets.apply("net/neoforged/neoforge/common/CommonHooks");
		if(lava==null||water==null||living==null||hooks==null||MixinFit.injectorOf(lava)==null||MixinFit.injectorOf(water)==null)return 0;
		String nativeDesc="(L"+LIVING+";L"+LEVEL+";II)V", nativeCall="L"+hooks.name+";onLivingBreathe"+nativeDesc;
		MethodNode host=MixinCarrierCallbackAdapters.named(living,"baseTick");if(host==null||MixinPlayerWorldCallbackAdapter.count(host,nativeCall)!=1)return 0;
		MethodNode nativeHook=MixinPlayerWorldCallbackAdapter.selector(hooks,"onLivingBreathe"+nativeDesc);if(nativeHook==null||MixinPlayerWorldCallbackAdapter.count(nativeHook,"Lnet/minecraft/world/effect/MobEffectUtil;hasWaterBreathing(L"+LIVING+";)Z")!=1)return 0;
		for(MethodNode source:List.of(lava,water))for(var i:source.instructions)if(i instanceof VarInsnNode v&&v.var==0)return 0;
		for(MethodNode source:List.of(lava,water)){AnnotationNode a=MixinFit.injectorOf(source);MixinCarrierCallbackAdapters.removeInjector(source,a);source.name+="$forbricOriginal";source.invisibleParameterAnnotations=null;source.visibleParameterAnnotations=null;}
		MethodNode lavaFn=callback(mixin,lava,true),waterFn=callback(mixin,water,false);mixin.methods.add(lavaFn);mixin.methods.add(waterFn);
		MethodNode wrapper=new MethodNode(Opcodes.ACC_PRIVATE,"forbric$breathingCallback","(L"+LIVING+";L"+LEVEL+";IIL"+OP+";)V",null,null);
		AnnotationNode at=new AnnotationNode("Lorg/spongepowered/asm/mixin/injection/At;");at.values=new ArrayList<>(List.of("value","INVOKE","target",nativeCall));AnnotationNode wrap=new AnnotationNode(MixinWrapOperationShim.WRAP_OPERATION);wrap.values=new ArrayList<>(List.of("method",List.of("baseTick()V"),"at",at,"require",1));wrapper.visibleAnnotations=new ArrayList<>(List.of(wrap));
		InsnList c=wrapper.instructions;function(c,mixin,lavaFn);function(c,mixin,waterFn);c.add(new MethodInsnNode(Opcodes.INVOKESTATIC,SCOPE,"enter","(Ljava/util/function/Function;Ljava/util/function/Function;)Ljava/lang/Object;",false));c.add(new VarInsnNode(Opcodes.ASTORE,6));
		LabelNode start=new LabelNode(),end=new LabelNode(),fail=new LabelNode();c.add(start);c.add(new VarInsnNode(Opcodes.ALOAD,5));c.add(new InsnNode(Opcodes.ICONST_4));c.add(new TypeInsnNode(Opcodes.ANEWARRAY,"java/lang/Object"));
		for(int i=0;i<4;i++){c.add(new InsnNode(Opcodes.DUP));c.add(new IntInsnNode(Opcodes.BIPUSH,i));c.add(new VarInsnNode(i<2?Opcodes.ALOAD:Opcodes.ILOAD,i+1));if(i>=2)c.add(new MethodInsnNode(Opcodes.INVOKESTATIC,"java/lang/Integer","valueOf","(I)Ljava/lang/Integer;",false));c.add(new InsnNode(Opcodes.AASTORE));}
		c.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE,OP,"call","([Ljava/lang/Object;)Ljava/lang/Object;",true));c.add(new InsnNode(Opcodes.POP));c.add(end);leave(c);c.add(new InsnNode(Opcodes.RETURN));
		c.add(fail);c.add(new FrameNode(Opcodes.F_FULL,7,new Object[]{mixin.name,LIVING,LEVEL,Opcodes.INTEGER,Opcodes.INTEGER,OP,"java/lang/Object"},1,new Object[]{"java/lang/Throwable"}));c.add(new VarInsnNode(Opcodes.ASTORE,7));leave(c);c.add(new VarInsnNode(Opcodes.ALOAD,7));c.add(new InsnNode(Opcodes.ATHROW));wrapper.tryCatchBlocks.add(new TryCatchBlockNode(start,end,fail,null));wrapper.maxStack=7;wrapper.maxLocals=8;mixin.methods.add(wrapper);return 2;
	}
	private static void leave(InsnList c){c.add(new VarInsnNode(Opcodes.ALOAD,6));c.add(new MethodInsnNode(Opcodes.INVOKESTATIC,SCOPE,"leave","(Ljava/lang/Object;)V",false));}
	private static void function(InsnList c,ClassNode mixin,MethodNode method){c.add(new VarInsnNode(Opcodes.ALOAD,0));c.add(new InvokeDynamicInsnNode("apply","(L"+mixin.name+";)Ljava/util/function/Function;",new Handle(Opcodes.H_INVOKESTATIC,"java/lang/invoke/LambdaMetafactory","metafactory","(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;Ljava/lang/invoke/MethodType;Ljava/lang/invoke/MethodHandle;Ljava/lang/invoke/MethodType;)Ljava/lang/invoke/CallSite;",false),Type.getMethodType("(Ljava/lang/Object;)Ljava/lang/Object;"),new Handle(Opcodes.H_INVOKEVIRTUAL,mixin.name,method.name,method.desc,false),Type.getMethodType("([Ljava/lang/Object;)Ljava/lang/Object;")));}
	private static MethodNode callback(ClassNode mixin,MethodNode original,boolean lava){
		MethodNode fn=new MethodNode(Opcodes.ACC_PRIVATE,"forbric$"+(lava?"lava":"water")+"Callback","([Ljava/lang/Object;)Ljava/lang/Object;",null,null);InsnList c=fn.instructions;c.add(new VarInsnNode(Opcodes.ALOAD,0));element(c,0,LIVING);
		if(lava){c.add(new FieldInsnNode(Opcodes.GETSTATIC,"net/minecraft/tags/FluidTags","WATER","Lnet/minecraft/tags/TagKey;"));element(c,0,LIVING);c.add(new FieldInsnNode(Opcodes.GETSTATIC,"net/minecraft/tags/FluidTags","WATER","Lnet/minecraft/tags/TagKey;"));c.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL,LIVING,"isEyeInFluid","(Lnet/minecraft/tags/TagKey;)Z",false));c.add(new MethodInsnNode(Opcodes.INVOKESTATIC,"java/lang/Boolean","valueOf","(Z)Ljava/lang/Boolean;",false));}
		else{c.add(new VarInsnNode(Opcodes.ALOAD,1));c.add(new InsnNode(Opcodes.ICONST_1));c.add(new InsnNode(Opcodes.AALOAD));}
		c.add(new MethodInsnNode(Opcodes.INVOKESTATIC,MixinWrapOperationShim.RUNTIME,"constant","(Ljava/lang/Object;)L"+OP+";",false));element(c,lava?1:2,LEVEL);c.add(MixinHandlerShim.callOwn(mixin,false,original.name,original.desc));c.add(new MethodInsnNode(Opcodes.INVOKESTATIC,"java/lang/Boolean","valueOf","(Z)Ljava/lang/Boolean;",false));c.add(new InsnNode(Opcodes.ARETURN));fn.maxStack=7;fn.maxLocals=2;MixinCallbackShape.uniqueMember(fn);return fn;
	}
	private static void element(InsnList c,int index,String type){c.add(new VarInsnNode(Opcodes.ALOAD,1));c.add(new IntInsnNode(Opcodes.BIPUSH,index));c.add(new InsnNode(Opcodes.AALOAD));c.add(new TypeInsnNode(Opcodes.CHECKCAST,type));}
}
