/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.mixin;

import java.util.*;
import java.util.function.Function;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;
import net.forbric.kernel.util.ForbricLog;

/** Keeps Fabric's server/client ScopedValue binding across the carrier's added tags and leniency arguments. */
public final class FabricRegistryLoaderMixinAdapter {
	public static final String PROPERTY="forbric.fabricRegistryLoader";
	private static final String TARGET="net/minecraft/resources/RegistryDataLoader";
	private static final String FACTORY="L"+TARGET+"$LoaderFactory;";
	private static final String ARGS="Ljava/util/List;Ljava/util/List;Ljava/util/concurrent/Executor;";
	private static final String FUTURE="Ljava/util/concurrent/CompletableFuture;";
	private static final String RM="Lnet/minecraft/server/packs/resources/ResourceManager;";
	private static final String OP="com/llamalad7/mixinextras/injector/wrapoperation/Operation";
	private FabricRegistryLoaderMixinAdapter() { }
	public static boolean enabled(){return !"off".equalsIgnoreCase(System.getProperty(PROPERTY,"on"));}

    static boolean matches(ClassNode mixin) {
        return MixinCallbackShape.targets(mixin,TARGET) && mixin.fields.stream().anyMatch(f->f.desc.equals("Ljava/lang/ScopedValue;"))
                && mixin.methods.stream().anyMatch(m->m.desc.equals("(Ljava/lang/Object;"+ARGS+"L"+OP+";)"+FUTURE)
                    && MixinCallbackShape.kind(m,"WrapOperation")
                    && invokes(m,"java/lang/ScopedValue","where")&&invokes(m,"java/lang/ScopedValue$Carrier","call")
                    && MixinCallbackShape.plainPoint(m,"INVOKE","L"+TARGET+";load("+FACTORY+ARGS+")"+FUTURE));
    }

    private static boolean invokes(MethodNode method,String owner,String name){for(var instruction:method.instructions)if(instruction instanceof MethodInsnNode call&&call.owner.equals(owner)&&call.name.equals(name))return true;return false;}

	public static int adapt(ClassNode mixin,Function<String,ClassNode> targets){
		if(!enabled()||!MixinCallbackShape.targets(mixin,TARGET))return 0;
		ClassNode target=targets.apply(TARGET);if(target==null)return 0;
		String publicLive="load("+RM+ARGS+"Ljava/util/List;)"+FUTURE;
		String privateLive="load("+FACTORY+ARGS+"Z)"+FUTURE;
		MethodNode publicMethod=target.methods.stream().filter(m->(m.name+m.desc).equals(publicLive)).findFirst().orElse(null);
		MethodNode privateMethod=target.methods.stream().filter(m->(m.name+m.desc).equals(privateLive)).findFirst().orElse(null);
		if(publicMethod==null||privateMethod==null)return 0;
		long calls=java.util.stream.StreamSupport.stream(publicMethod.instructions.spliterator(),false).filter(i->i instanceof MethodInsnNode c&&c.owner.equals(TARGET)&&(c.name+c.desc).equals(privateLive)).count();
		if(calls!=1)return 0;
		MethodNode original=MixinCallbackShape.unique(mixin,m->m.desc.equals("(Ljava/lang/Object;"+ARGS+"L"+OP+";)"+FUTURE)&&MixinCallbackShape.kind(m,"WrapOperation")&&MixinCallbackShape.plainPoint(m,"INVOKE","L"+TARGET+";load("+FACTORY+ARGS+")"+FUTURE));
		MethodNode supply=MixinCallbackShape.unique(mixin,m->m.desc.equals("(Ljava/util/function/Supplier;)Ljava/util/function/Supplier;")&&MixinCallbackShape.kind(m,"ModifyArg")&&MixinCallbackShape.plainPoint(m,"INVOKE","Ljava/util/concurrent/CompletableFuture;supplyAsync(Ljava/util/function/Supplier;Ljava/util/concurrent/Executor;)Ljava/util/concurrent/CompletableFuture;"));
		if(original==null||supply==null||MixinFit.injectorOf(original)==null||MixinFit.injectorOf(supply)==null
                ||(original.access&Opcodes.ACC_STATIC)==0||(supply.access&Opcodes.ACC_STATIC)==0)return 0;
		AnnotationNode annotation=MixinFit.injectorOf(original);
		set(annotation,"method",List.of(publicLive));
		for(AnnotationNode at:MixinFit.atNodes(annotation))set(at,"target","L"+TARGET+";"+privateLive);
		set(MixinFit.injectorOf(supply),"method",List.of(privateLive));
		String handlerName=original.name;MixinCarrierCallbackAdapters.removeInjector(original,annotation);original.name+="$forbricOriginal";
		MethodNode wrapper=new MethodNode(Opcodes.ASM9,Opcodes.ACC_PRIVATE|Opcodes.ACC_STATIC,handlerName,
				"(Ljava/lang/Object;"+ARGS+"ZL"+OP+";)"+FUTURE,null,null);
		wrapper.visibleAnnotations=new ArrayList<>(List.of(annotation));
		// Keep @Coerce on LoaderFactory, which the upstream handler deliberately types as Object.
		wrapper.invisibleParameterAnnotations=original.invisibleParameterAnnotations==null?null:Arrays.copyOf(original.invisibleParameterAnnotations,6);
		InsnList code=wrapper.instructions;
		for(int i=0;i<4;i++)code.add(new VarInsnNode(Opcodes.ALOAD,i));
		code.add(new VarInsnNode(Opcodes.ALOAD,5));code.add(new InsnNode(Opcodes.ICONST_0));code.add(new LdcInsnNode("0,1,2,3"));
		code.add(new InsnNode(Opcodes.ICONST_5));code.add(new TypeInsnNode(Opcodes.ANEWARRAY,"java/lang/Object"));
		code.add(new InsnNode(Opcodes.DUP));code.add(new InsnNode(Opcodes.ICONST_4));code.add(new VarInsnNode(Opcodes.ILOAD,4));
		code.add(new MethodInsnNode(Opcodes.INVOKESTATIC,"java/lang/Boolean","valueOf","(Z)Ljava/lang/Boolean;",false));code.add(new InsnNode(Opcodes.AASTORE));
		code.add(new MethodInsnNode(Opcodes.INVOKESTATIC,"net/forbric/kernel/runtime/KernelWrapOperations","reordered","(L"+OP+";ZLjava/lang/String;[Ljava/lang/Object;)L"+OP+";",false));
		code.add(new MethodInsnNode(Opcodes.INVOKESTATIC,mixin.name,original.name,original.desc,false));code.add(new InsnNode(Opcodes.ARETURN));
		wrapper.maxLocals=6;wrapper.maxStack=11;mixin.methods.add(wrapper);
		ForbricLog.info("[Forbric/RegistrySync] restored the native Fabric registry loader callback and its async "
				+ "ScopedValue propagation on the carrier overloads, preserving pending tags and the leniency flag");
		return 2;
	}
	private static void set(AnnotationNode annotation,String key,Object value){
		for(int i=0;i<annotation.values.size();i+=2)if(key.equals(annotation.values.get(i))){annotation.values.set(i+1,value);return;}
		annotation.values.add(key);annotation.values.add(value);
	}
}
