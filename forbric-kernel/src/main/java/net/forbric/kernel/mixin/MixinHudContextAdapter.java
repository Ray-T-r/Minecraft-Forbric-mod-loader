/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.mixin;
import java.util.*;import java.util.function.Function;
import org.objectweb.asm.*;import org.objectweb.asm.tree.*;

/** Draw the authored contextual overlay at native contextual-bar selection, retaining graphics, timing and F1 behavior. */
public final class MixinHudContextAdapter {
	public static final String PROPERTY="forbric.hudContextCallbacks";
	private static final String HUD="net/minecraft/client/gui/Hud", GRAPHICS="net/minecraft/client/gui/GuiGraphicsExtractor", DELTA="net/minecraft/client/DeltaTracker", OP=MixinWrapOperationShim.OPERATION, SCOPE="net/forbric/kernel/interop/HudContextCallbackScope";
	private MixinHudContextAdapter() { }
	public static int adapt(ClassNode mixin,Function<String,ClassNode> targets){
		if(!MixinCallbackShape.targets(mixin,HUD)||"off".equalsIgnoreCase(net.forbric.kernel.util.ForbricSwitches.get(PROPERTY)))return 0;
        ClassNode target=targets.apply(HUD);
        MethodNode original=MixinCallbackShape.unique(mixin,m -> MixinCallbackShape.shape(m,"(L"+HUD+";L"+OP+";)L"+HUD+"$ContextualInfo;",MixinHandlerShape.Want.local("L"+GRAPHICS+";"),MixinHandlerShape.Want.local("L"+DELTA+";"))
                && MixinCallbackShape.instance(m) && MixinCallbackShape.kind(m,"WrapOperation")
                && MixinCallbackShape.binds(m,target,"extractHotbarAndDecorations(L"+GRAPHICS+";L"+DELTA+";)V")
                && MixinCallbackShape.plainPoint(m,"INVOKE","L"+HUD+";nextContextualInfoState()L"+HUD+"$ContextualInfo;"));MethodNode host=target==null?null:MixinPlayerWorldCallbackAdapter.selector(target,"extractRenderState(L"+GRAPHICS+";L"+DELTA+";)V");
		if(original==null||host==null||MixinFit.injectorOf(original)==null||!Type.getReturnType(original.desc).equals(Type.getObjectType(HUD+"$ContextualInfo"))||MixinPlayerWorldCallbackAdapter.count(host,"L"+HUD+";updateContextualBarRenderer()V")!=1)return 0;
		AnnotationNode inject=MixinFit.injectorOf(original);MixinPlayerWorldCallbackAdapter.set(inject,"method",List.of(host.name+host.desc));MixinPlayerWorldCallbackAdapter.set(MixinFit.atNodes(inject).getFirst(),"target","L"+HUD+";updateContextualBarRenderer()V");
		MethodNode callback=callback(mixin,original);mixin.methods.add(callback);
		MethodNode wrapper=new MethodNode(Opcodes.ACC_PRIVATE,original.name,"(L"+HUD+";L"+OP+";L"+GRAPHICS+";L"+DELTA+";)V",null,null);wrapper.visibleAnnotations=new ArrayList<>(List.of(inject));wrapper.invisibleParameterAnnotations=original.invisibleParameterAnnotations;
		original.name+="$forbricOriginal";MixinCarrierCallbackAdapters.removeInjector(original,inject);original.invisibleParameterAnnotations=null;original.visibleParameterAnnotations=null;
		InsnList c=wrapper.instructions;c.add(new VarInsnNode(Opcodes.ALOAD,0));c.add(new VarInsnNode(Opcodes.ALOAD,3));c.add(new VarInsnNode(Opcodes.ALOAD,4));c.add(new InvokeDynamicInsnNode("apply","(L"+mixin.name+";L"+GRAPHICS+";L"+DELTA+";)Ljava/util/function/Function;",new Handle(Opcodes.H_INVOKESTATIC,"java/lang/invoke/LambdaMetafactory","metafactory","(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;Ljava/lang/invoke/MethodType;Ljava/lang/invoke/MethodHandle;Ljava/lang/invoke/MethodType;)Ljava/lang/invoke/CallSite;",false),Type.getMethodType("(Ljava/lang/Object;)Ljava/lang/Object;"),new Handle(Opcodes.H_INVOKEVIRTUAL,mixin.name,callback.name,callback.desc,false),Type.getMethodType("([Ljava/lang/Object;)Ljava/lang/Object;")));
		c.add(new MethodInsnNode(Opcodes.INVOKESTATIC,SCOPE,"enter","(Ljava/util/function/Function;)Ljava/lang/Object;",false));c.add(new VarInsnNode(Opcodes.ASTORE,5));LabelNode start=new LabelNode(),end=new LabelNode(),fail=new LabelNode();c.add(start);c.add(new VarInsnNode(Opcodes.ALOAD,2));c.add(new InsnNode(Opcodes.ICONST_1));c.add(new TypeInsnNode(Opcodes.ANEWARRAY,"java/lang/Object"));c.add(new InsnNode(Opcodes.DUP));c.add(new InsnNode(Opcodes.ICONST_0));c.add(new VarInsnNode(Opcodes.ALOAD,1));c.add(new InsnNode(Opcodes.AASTORE));c.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE,OP,"call","([Ljava/lang/Object;)Ljava/lang/Object;",true));c.add(new InsnNode(Opcodes.POP));c.add(end);leave(c);c.add(new InsnNode(Opcodes.RETURN));c.add(fail);c.add(new FrameNode(Opcodes.F_FULL,6,new Object[]{mixin.name,HUD,OP,GRAPHICS,DELTA,"java/lang/Object"},1,new Object[]{"java/lang/Throwable"}));c.add(new VarInsnNode(Opcodes.ASTORE,6));leave(c);c.add(new VarInsnNode(Opcodes.ALOAD,6));c.add(new InsnNode(Opcodes.ATHROW));wrapper.tryCatchBlocks.add(new TryCatchBlockNode(start,end,fail,null));wrapper.maxStack=6;wrapper.maxLocals=7;mixin.methods.add(wrapper);return 1;
	}
	private static void leave(InsnList c){c.add(new VarInsnNode(Opcodes.ALOAD,5));c.add(new MethodInsnNode(Opcodes.INVOKESTATIC,SCOPE,"leave","(Ljava/lang/Object;)V",false));}
	private static MethodNode callback(ClassNode mixin,MethodNode original){
		MethodNode fn=new MethodNode(Opcodes.ACC_PRIVATE,"forbric$hudContextCallback","(L"+GRAPHICS+";L"+DELTA+";[Ljava/lang/Object;)Ljava/lang/Object;",null,null);InsnList c=fn.instructions;LabelNode draw=new LabelNode();
		c.add(new VarInsnNode(Opcodes.ALOAD,0));c.add(new TypeInsnNode(Opcodes.CHECKCAST,HUD));c.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL,HUD,"isHidden","()Z",false));c.add(new JumpInsnNode(Opcodes.IFEQ,draw));element(c,1,"java/util/function/Supplier");c.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE,"java/util/function/Supplier","get","()Ljava/lang/Object;",true));c.add(new InsnNode(Opcodes.ARETURN));c.add(draw);c.add(new FrameNode(Opcodes.F_SAME,0,null,0,null));
		c.add(new VarInsnNode(Opcodes.ALOAD,0));element(c,0,HUD);element(c,1,"java/util/function/Supplier");c.add(new MethodInsnNode(Opcodes.INVOKESTATIC,MixinWrapOperationShim.RUNTIME,"supplied","(Ljava/util/function/Supplier;)L"+OP+";",false));c.add(new VarInsnNode(Opcodes.ALOAD,1));c.add(new VarInsnNode(Opcodes.ALOAD,2));c.add(MixinHandlerShim.callOwn(mixin,false,original.name+"$forbricOriginal",original.desc));c.add(new InsnNode(Opcodes.ARETURN));fn.maxStack=6;fn.maxLocals=4;MixinCallbackShape.uniqueMember(fn);return fn;
	}
	private static void element(InsnList c,int index,String type){c.add(new VarInsnNode(Opcodes.ALOAD,3));c.add(new IntInsnNode(Opcodes.BIPUSH,index));c.add(new InsnNode(Opcodes.AALOAD));c.add(new TypeInsnNode(Opcodes.CHECKCAST,type));}
}
