/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.mixin;

import java.util.*;
import java.util.function.Function;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;

/** Preserve authored Block-based control handlers around native BlockState queries. */
public final class MixinBlockQueryAdapters {
	public static final String PROPERTY="forbric.blockQueryAdapters";
	private static final String STATE="net/minecraft/world/level/block/state/BlockState", BLOCK="net/minecraft/world/level/block/Block", OP=MixinWrapOperationShim.OPERATION;
	private MixinBlockQueryAdapters() { }
	public static int adapt(ClassNode mixin,Function<String,ClassNode> targets) {
        if("off".equalsIgnoreCase(net.forbric.kernel.util.ForbricSwitches.get(PROPERTY)) || MixinFit.mixinTargets(mixin).size()!=1)return 0;
        return blockReceiver(mixin,targets,"getFriction") + blockReceiver(mixin,targets,"getExplosionResistance")
                + (MixinCallbackShape.targets(mixin,"net/minecraft/world/entity/LivingEntity")?scaffolding(mixin,targets):0);
	}
	private static int blockReceiver(ClassNode mixin,Function<String,ClassNode> targets,String query) {
		MethodNode original=MixinCallbackShape.unique(mixin,m -> m.desc.startsWith("(L"+BLOCK+";L"+OP+";") && Type.getReturnType(m.desc).equals(Type.FLOAT_TYPE)
                && MixinCallbackShape.kind(m,"WrapOperation") && MixinCallbackShape.plainPoint(m,"INVOKE","L"+BLOCK+";"+query+"()F"));if(original==null||!original.desc.startsWith("(L"+BLOCK+";L"+OP+";"))return 0;
		AnnotationNode inject=MixinFit.injectorOf(original);if(inject==null)return 0;List<AnnotationNode> points=MixinFit.atNodes(inject);if(points.size()!=1)return 0;
		List<MethodInsnNode> calls=new ArrayList<>();List<String> selectors=MixinFit.stringList(MixinFit.value(inject,"method"));
		for(String targetName:MixinFit.mixinTargets(mixin)){ClassNode target=targets.apply(targetName);if(target==null)return 0;for(MethodNode host:target.methods)if(selectors.contains(host.name)||selectors.contains(host.name+host.desc))for(var instruction:host.instructions)if(instruction instanceof MethodInsnNode call&&call.owner.equals(STATE)&&call.name.equals(query)&&Type.getReturnType(call.desc).equals(Type.FLOAT_TYPE))calls.add(call);}
		if(calls.size()!=1)return 0;MethodInsnNode live=calls.getFirst();Type[] nativeArgs=Type.getArgumentTypes(live.desc),old=Type.getArgumentTypes(original.desc);
        if (Arrays.stream(nativeArgs).anyMatch(t -> t.getSort()!=Type.OBJECT && t.getSort()!=Type.ARRAY)) return 0;
        boolean stat=(original.access&Opcodes.ACC_STATIC)!=0;
		List<Type> params=new ArrayList<>(List.of(Type.getObjectType(STATE)));params.addAll(List.of(nativeArgs));params.addAll(Arrays.asList(old).subList(1,old.length));
		MethodNode outer=new MethodNode(original.access,original.name,Type.getMethodDescriptor(Type.getReturnType(original.desc),params.toArray(Type[]::new)),null,null);outer.visibleAnnotations=new ArrayList<>(List.of(inject));
		outer.invisibleParameterAnnotations=MixinWrapOperationShim.shifted(original.invisibleParameterAnnotations,old.length,nativeArgs.length,2,params.size());
		MixinPlayerWorldCallbackAdapter.set(points.getFirst(),"target","L"+live.owner+";"+live.name+live.desc);
		int[] slots=new int[params.size()];int slot=stat?0:1;for(int i=0;i<params.size();i++){slots[i]=slot;slot+=params.get(i).getSize();}
		InsnList c=outer.instructions;if(!stat)c.add(new VarInsnNode(Opcodes.ALOAD,0));c.add(new VarInsnNode(Opcodes.ALOAD,slots[0]));c.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL,STATE,"getBlock","()L"+BLOCK+";",false));
		int op=1+nativeArgs.length;c.add(new VarInsnNode(Opcodes.ALOAD,slots[op]));c.add(new InsnNode(Opcodes.ICONST_0));c.add(new LdcInsnNode(""));c.add(new IntInsnNode(Opcodes.BIPUSH,op));c.add(new TypeInsnNode(Opcodes.ANEWARRAY,"java/lang/Object"));
		for(int i=0;i<op;i++){c.add(new InsnNode(Opcodes.DUP));c.add(new IntInsnNode(Opcodes.BIPUSH,i));c.add(new VarInsnNode(params.get(i).getOpcode(Opcodes.ILOAD),slots[i]));c.add(new InsnNode(Opcodes.AASTORE));}
		c.add(new MethodInsnNode(Opcodes.INVOKESTATIC,MixinWrapOperationShim.RUNTIME,"reordered","(L"+OP+";ZLjava/lang/String;[Ljava/lang/Object;)L"+OP+";",false));
		for(int i=op+1;i<params.size();i++)c.add(new VarInsnNode(params.get(i).getOpcode(Opcodes.ILOAD),slots[i]));
		original.name+="$forbricOriginal";MixinCarrierCallbackAdapters.removeInjector(original,inject);original.invisibleParameterAnnotations=null;original.visibleParameterAnnotations=null;
		c.add(MixinHandlerShim.callOwn(mixin,stat,original.name,original.desc));c.add(new InsnNode(Opcodes.FRETURN));outer.maxLocals=slot;outer.maxStack=slot+12;mixin.methods.add(outer);return 1;
	}
	static int scaffolding(ClassNode mixin,Function<String,ClassNode> targets){
		MethodNode original=MixinCallbackShape.unique(mixin,m -> m.desc.equals("(L"+STATE+";Ljava/lang/Object;L"+OP+";)Z") && MixinCallbackShape.instance(m)
                && MixinCallbackShape.kind(m,"WrapOperation") && MixinCallbackShape.selects(m,"handleOnClimbable(Lnet/minecraft/world/phys/Vec3;)Lnet/minecraft/world/phys/Vec3;")
                && MixinCallbackShape.plainPoint(m,"INVOKE","L"+STATE+";is(Ljava/lang/Object;)Z"));ClassNode target=targets.apply("net/minecraft/world/entity/LivingEntity");
		if(original==null||target==null||!original.desc.equals("(L"+STATE+";Ljava/lang/Object;L"+OP+";)Z"))return 0;
		MethodNode host=MixinCarrierCallbackAdapters.named(target,"handleOnClimbable");String live="L"+STATE+";isScaffolding(Lnet/minecraft/world/entity/LivingEntity;)Z";
		AnnotationNode inject=MixinFit.injectorOf(original);if(host==null||inject==null||MixinPlayerWorldCallbackAdapter.count(host,live)!=1)return 0;
		MixinPlayerWorldCallbackAdapter.set(MixinFit.atNodes(inject).getFirst(),"target",live);
		MethodNode wrapper=new MethodNode(Opcodes.ACC_PRIVATE,original.name,"(L"+STATE+";Lnet/minecraft/world/entity/LivingEntity;L"+OP+";)Z",null,null);wrapper.visibleAnnotations=new ArrayList<>(List.of(inject));
		InsnList c=wrapper.instructions;c.add(new VarInsnNode(Opcodes.ALOAD,0));c.add(new VarInsnNode(Opcodes.ALOAD,1));c.add(new FieldInsnNode(Opcodes.GETSTATIC,"net/minecraft/world/level/block/Blocks","SCAFFOLDING","L"+BLOCK+";"));c.add(new VarInsnNode(Opcodes.ALOAD,3));c.add(new InsnNode(Opcodes.ICONST_1));c.add(new LdcInsnNode(""));c.add(new InsnNode(Opcodes.ICONST_1));c.add(new TypeInsnNode(Opcodes.ANEWARRAY,"java/lang/Object"));c.add(new InsnNode(Opcodes.DUP));c.add(new InsnNode(Opcodes.ICONST_0));c.add(new VarInsnNode(Opcodes.ALOAD,2));c.add(new InsnNode(Opcodes.AASTORE));c.add(new MethodInsnNode(Opcodes.INVOKESTATIC,MixinWrapOperationShim.RUNTIME,"reordered","(L"+OP+";ZLjava/lang/String;[Ljava/lang/Object;)L"+OP+";",false));
		original.name+="$forbricOriginal";MixinCarrierCallbackAdapters.removeInjector(original,inject);c.add(MixinHandlerShim.callOwn(mixin,false,original.name,original.desc));c.add(new InsnNode(Opcodes.IRETURN));wrapper.maxStack=10;wrapper.maxLocals=4;mixin.methods.add(wrapper);return 1;
	}
}
