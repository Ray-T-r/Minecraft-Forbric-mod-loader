/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.mixin;

import java.util.*;
import java.util.function.Function;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;

/** Keep placement context across the whole native placement transaction, including cancellation and rollback. */
final class MixinPlacementTransactionAdapter {
	private static final String CONTEXT="net/minecraft/world/item/context/UseOnContext", CIR="org/spongepowered/asm/mixin/injection/callback/CallbackInfoReturnable", REF="com/llamalad7/mixinextras/sugar/ref/LocalRef";
	static int adapt(ClassNode mixin,Function<String,ClassNode> targets,java.util.function.BiFunction<net.forbric.api.Ecosystem,String,ClassNode> references) {
		return adapt(mixin,targets);
	}
	static int adapt(ClassNode mixin,Function<String,ClassNode> targets) {
		String selector="useOn(L"+CONTEXT+";)Lnet/minecraft/world/InteractionResult;";
		if(!MixinCallbackShape.targets(mixin,"net/minecraft/world/item/ItemStack"))return 0;
		ClassNode target=targets.apply("net/minecraft/world/item/ItemStack");
        MethodNode cache=MixinCallbackShape.unique(mixin,m -> MixinCallbackShape.shape(m,"(L"+CONTEXT+";L"+CIR+";)V",MixinHandlerShape.Want.local("Lnet/minecraft/world/item/Item;"),MixinHandlerShape.Want.share("L"+REF+";")) && MixinCallbackShape.instance(m)
                && MixinCallbackShape.kind(m,"Inject") && MixinCallbackShape.binds(m,target,selector)
                && MixinCallbackShape.plainPoint(m,"INVOKE","Lnet/minecraft/world/item/Item;"+selector)),
                use=MixinCallbackShape.unique(mixin,m -> MixinCallbackShape.shape(m,"(L"+CONTEXT+";L"+CIR+";)V",MixinHandlerShape.Want.local("Lnet/minecraft/world/entity/player/Player;"),MixinHandlerShape.Want.share("L"+REF+";")) && MixinCallbackShape.instance(m)
                        && MixinCallbackShape.kind(m,"Inject") && MixinCallbackShape.binds(m,target,selector)
                        && MixinCallbackShape.plainPoint(m,"INVOKE","Lnet/minecraft/world/InteractionResult$Success;wasItemInteraction()Z"));
		if(!MixinCallbackShape.targets(mixin,"net/minecraft/world/item/ItemStack")||cache==null||use==null||target==null||Type.getArgumentTypes(cache.desc).length!=4||Type.getArgumentTypes(use.desc).length!=4)return 0;
		if(MixinFit.injectorOf(cache)==null||MixinFit.injectorOf(use)==null)return 0;
        if(!contextSnapshot(cache) || !MixinCallbackShape.noReceiver(use)) return 0;
        for(var instruction:use.instructions)if(instruction instanceof VarInsnNode variable && variable.var==2)return 0;
		MethodNode host=MixinPlayerWorldCallbackAdapter.named(target,"useOn");
		if(host==null||host.instructions==null)return 0;
		boolean nativeTransaction=false;for(var instruction:host.instructions)if(instruction instanceof MethodInsnNode call&&call.name.equals("onPlaceItemIntoWorld"))nativeTransaction=true;
		if(!nativeTransaction)return 0;
		for(MethodNode original:List.of(cache,use)) {
			boolean caching=original==cache;AnnotationNode inject=MixinFit.injectorOf(original);
			List<AnnotationNode> points=MixinFit.atNodes(inject);if(points.size()!=1)return 0;
			AnnotationNode at=points.getFirst();at.values=new ArrayList<>(List.of("value",caching?"HEAD":"RETURN"));
			MethodNode wrapper=new MethodNode(Opcodes.ACC_PRIVATE,original.name,"(L"+CONTEXT+";L"+CIR+";L"+REF+";)V",null,null);
			wrapper.visibleAnnotations=new ArrayList<>(List.of(inject));
			if(original.invisibleParameterAnnotations!=null){wrapper.invisibleParameterAnnotations=new List[3];wrapper.invisibleParameterAnnotations[2]=original.invisibleParameterAnnotations[3];}
			original.name+="$forbricOriginal";MixinCarrierCallbackAdapters.removeInjector(original,inject);original.invisibleParameterAnnotations=null;original.visibleParameterAnnotations=null;
			InsnList c=wrapper.instructions;LabelNode done=new LabelNode();
			if(!caching){c.add(new VarInsnNode(Opcodes.ALOAD,1));c.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL,CONTEXT,"getPlayer","()Lnet/minecraft/world/entity/player/Player;",false));c.add(new VarInsnNode(Opcodes.ASTORE,4));
				c.add(new VarInsnNode(Opcodes.ALOAD,2));c.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL,CIR,"getReturnValue","()Ljava/lang/Object;",false));c.add(new TypeInsnNode(Opcodes.INSTANCEOF,"net/minecraft/world/InteractionResult$Success"));c.add(new JumpInsnNode(Opcodes.IFEQ,done));c.add(new VarInsnNode(Opcodes.ALOAD,4));c.add(new JumpInsnNode(Opcodes.IFNULL,done));}
			c.add(new VarInsnNode(Opcodes.ALOAD,0));c.add(new VarInsnNode(Opcodes.ALOAD,1));c.add(new VarInsnNode(Opcodes.ALOAD,2));
			if(caching){c.add(new VarInsnNode(Opcodes.ALOAD,0));c.add(new TypeInsnNode(Opcodes.CHECKCAST,target.name));c.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL,target.name,"getItem","()Lnet/minecraft/world/item/Item;",false));}
			else c.add(new VarInsnNode(Opcodes.ALOAD,4));
			c.add(new VarInsnNode(Opcodes.ALOAD,3));c.add(MixinHandlerShim.callOwn(mixin,false,original.name,original.desc));
			if(!caching){c.add(done);c.add(new FrameNode(Opcodes.F_APPEND,1,new Object[]{"net/minecraft/world/entity/player/Player"},0,null));}
			c.add(new InsnNode(Opcodes.RETURN));wrapper.maxStack=6;wrapper.maxLocals=caching?4:5;mixin.methods.add(wrapper);
		}
		return 2;
	}
    /** Only a side-effect-free BlockPlaceContext snapshot stored in the shared ref may move to transaction entry. */
    private static boolean contextSnapshot(MethodNode method) {
        List<AbstractInsnNode> c=Arrays.stream(method.instructions.toArray()).filter(i->i.getOpcode()>=0).toList();
        if(c.size()!=16 || !method.tryCatchBlocks.isEmpty())return false;
        return load(c.get(0),3) && c.get(1) instanceof TypeInsnNode item && item.getOpcode()==Opcodes.INSTANCEOF && item.desc.equals("net/minecraft/world/item/BlockItem")
                && c.get(2) instanceof JumpInsnNode nonBlock && nonBlock.getOpcode()==Opcodes.IFEQ && MixinPlayerWorldCallbackAdapter.next(nonBlock.label)==c.get(15)
                && load(c.get(3),1) && call(c.get(4),CONTEXT,"getLevel","()Lnet/minecraft/world/level/Level;")
                && c.get(5) instanceof VarInsnNode store && store.getOpcode()==Opcodes.ASTORE && load(c.get(6),store.var)
                && call(c.get(7),"net/minecraft/world/level/Level","isClientSide","()Z")
                && c.get(8) instanceof JumpInsnNode client && client.getOpcode()==Opcodes.IFNE && MixinPlayerWorldCallbackAdapter.next(client.label)==c.get(15)
                && load(c.get(9),4) && c.get(10) instanceof TypeInsnNode context && context.getOpcode()==Opcodes.NEW && context.desc.equals("net/minecraft/world/item/context/BlockPlaceContext")
                && c.get(11).getOpcode()==Opcodes.DUP && load(c.get(12),1)
                && call(c.get(13),context.desc,"<init>","(L"+CONTEXT+";)V") && call(c.get(14),REF,"set","(Ljava/lang/Object;)V")
                && c.get(15).getOpcode()==Opcodes.RETURN;
    }
    private static boolean load(AbstractInsnNode instruction,int slot) {return instruction instanceof VarInsnNode variable && variable.getOpcode()==Opcodes.ALOAD && variable.var==slot;}
    private static boolean call(AbstractInsnNode instruction,String owner,String name,String desc) {return instruction instanceof MethodInsnNode call && call.owner.equals(owner) && call.name.equals(name) && call.desc.equals(desc);}

}
