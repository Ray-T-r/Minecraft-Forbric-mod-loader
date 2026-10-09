/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.mixin;
import java.util.*;import java.util.function.BiFunction;import java.util.function.Function;
import org.objectweb.asm.*;import org.objectweb.asm.tree.*;
import net.forbric.api.Ecosystem;

/**
 * Draw the authored contextual overlay at native contextual-bar selection, retaining graphics, timing and F1 behavior.
 *
 * <p>A wrap of vanilla's {@code nextContextualInfoState()} in {@code extractHotbarAndDecorations} moves to NeoForge's
 * {@code updateContextualBarRenderer()} in {@code extractRenderState}, which takes the same graphics and delta tracker.
 * The handler may ask for either, both or neither, as a {@code @Local} or as the target argument Mixin appends — the
 * same value read the same way ({@link MixinHandlerShape#of(MethodNode, String, MethodNode)}); its point is read as
 * Mixin reads it, in vanilla's method when the class the mod was compiled against is at hand.
 */
public final class MixinHudContextAdapter {
	public static final String PROPERTY="forbric.hudContextCallbacks";
	private static final String HUD="net/minecraft/client/gui/Hud", GRAPHICS="net/minecraft/client/gui/GuiGraphicsExtractor", DELTA="net/minecraft/client/DeltaTracker", OP=MixinWrapOperationShim.OPERATION, SCOPE="net/forbric/kernel/interop/HudContextCallbackScope";
	private static final String WRITTEN="extractHotbarAndDecorations(L"+GRAPHICS+";L"+DELTA+";)V", HOST="extractRenderState(L"+GRAPHICS+";L"+DELTA+";)V";
	private static final String NEXT="L"+HUD+";nextContextualInfoState()L"+HUD+"$ContextualInfo;", UPDATE="L"+HUD+";updateContextualBarRenderer()V";
	/** What the wrapper can hand the handler: the graphics and the delta tracker, in this order. */
	private static final List<MixinHandlerShape.Want> OFFERED=List.of(MixinHandlerShape.Want.local("L"+GRAPHICS+";"),MixinHandlerShape.Want.local("L"+DELTA+";"));
	private MixinHudContextAdapter() { }
	public static int adapt(ClassNode mixin,Function<String,ClassNode> targets){return adapt(mixin,targets,NativeGameReferences::reference);}
	/** {@code references} gives the class the mod was compiled against, in which its point is read. */
	static int adapt(ClassNode mixin,Function<String,ClassNode> targets,BiFunction<Ecosystem,String,ClassNode> references){
		if(!MixinCallbackShape.targets(mixin,HUD)||"off".equalsIgnoreCase(net.forbric.kernel.util.ForbricSwitches.get(PROPERTY)))return 0;
		ClassNode target=targets.apply(HUD),source=references==null?null:references.apply(MixinStubRebind.ecosystemOf(mixin.name),HUD);
		MethodNode host=target==null?null:MixinPlayerWorldCallbackAdapter.selector(target,HOST);
		if(host==null||MixinPlayerWorldCallbackAdapter.count(host,UPDATE)!=1)return 0;
		MethodNode original=MixinCallbackShape.unique(mixin,m->MixinCallbackShape.kind(m,"WrapOperation")&&MixinCallbackShape.instance(m)
				&&MixinCallbackShape.binds(m,target,WRITTEN)&&served(m)!=null
				&&MixinCallbackShape.plainPoint(m,"INVOKE",NEXT,source==null?null:MixinTargetSelectors.one(m,source)));
		if(original==null)return 0;
		MixinHandlerShape shape=read(original);Map<Integer,Integer> served=served(original);
		AnnotationNode inject=MixinFit.injectorOf(original);MixinPlayerWorldCallbackAdapter.set(inject,"method",List.of(HOST));MixinPlayerWorldCallbackAdapter.set(MixinFit.atNodes(inject).getFirst(),"target",UPDATE);
		MethodNode callback=callback(mixin,original,shape,served);mixin.methods.add(callback);
		MethodNode wrapper=new MethodNode(Opcodes.ACC_PRIVATE,original.name,"(L"+HUD+";L"+OP+";L"+GRAPHICS+";L"+DELTA+";)V",null,null);wrapper.visibleAnnotations=new ArrayList<>(List.of(inject));
		wrapper.invisibleParameterAnnotations=wrapperLocals(original,shape,served);
		original.name+="$forbricOriginal";MixinCarrierCallbackAdapters.removeInjector(original,inject);original.invisibleParameterAnnotations=null;original.visibleParameterAnnotations=null;
		InsnList c=wrapper.instructions;c.add(new VarInsnNode(Opcodes.ALOAD,0));c.add(new VarInsnNode(Opcodes.ALOAD,3));c.add(new VarInsnNode(Opcodes.ALOAD,4));c.add(new InvokeDynamicInsnNode("apply","(L"+mixin.name+";L"+GRAPHICS+";L"+DELTA+";)Ljava/util/function/Function;",new Handle(Opcodes.H_INVOKESTATIC,"java/lang/invoke/LambdaMetafactory","metafactory","(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;Ljava/lang/invoke/MethodType;Ljava/lang/invoke/MethodHandle;Ljava/lang/invoke/MethodType;)Ljava/lang/invoke/CallSite;",false),Type.getMethodType("(Ljava/lang/Object;)Ljava/lang/Object;"),new Handle(Opcodes.H_INVOKEVIRTUAL,mixin.name,callback.name,callback.desc,false),Type.getMethodType("([Ljava/lang/Object;)Ljava/lang/Object;")));
		c.add(new MethodInsnNode(Opcodes.INVOKESTATIC,SCOPE,"enter","(Ljava/util/function/Function;)Ljava/lang/Object;",false));c.add(new VarInsnNode(Opcodes.ASTORE,5));LabelNode start=new LabelNode(),end=new LabelNode(),fail=new LabelNode();c.add(start);c.add(new VarInsnNode(Opcodes.ALOAD,2));c.add(new InsnNode(Opcodes.ICONST_1));c.add(new TypeInsnNode(Opcodes.ANEWARRAY,"java/lang/Object"));c.add(new InsnNode(Opcodes.DUP));c.add(new InsnNode(Opcodes.ICONST_0));c.add(new VarInsnNode(Opcodes.ALOAD,1));c.add(new InsnNode(Opcodes.AASTORE));c.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE,OP,"call","([Ljava/lang/Object;)Ljava/lang/Object;",true));c.add(new InsnNode(Opcodes.POP));c.add(end);leave(c);c.add(new InsnNode(Opcodes.RETURN));c.add(fail);c.add(new FrameNode(Opcodes.F_FULL,6,new Object[]{mixin.name,HUD,OP,GRAPHICS,DELTA,"java/lang/Object"},1,new Object[]{"java/lang/Throwable"}));c.add(new VarInsnNode(Opcodes.ASTORE,6));leave(c);c.add(new VarInsnNode(Opcodes.ALOAD,6));c.add(new InsnNode(Opcodes.ATHROW));wrapper.tryCatchBlocks.add(new TryCatchBlockNode(start,end,fail,null));wrapper.maxStack=6;wrapper.maxLocals=7;mixin.methods.add(wrapper);return 1;
	}
	/** The handler read against vanilla's method, where a target argument it appends is that method's graphics or delta tracker. */
	private static MixinHandlerShape read(MethodNode handler){return MixinHandlerShape.of(handler,WRITTEN.substring(WRITTEN.indexOf('(')),null);}
	/** For each of the handler's extras, which offer serves it (0 graphics, 1 delta tracker); null when it is not this callback. */
	private static Map<Integer,Integer> served(MethodNode handler){
		MixinHandlerShape shape=read(handler);
		if(shape==null||!shape.operands("(L"+HUD+";L"+OP+";)L"+HUD+"$ContextualInfo;"))return null;
		return shape.within(OFFERED);
	}
	/**
	 * The wrapper's {@code @Local}s for the graphics (parameter 2) and the delta tracker (parameter 3): the handler's own
	 * annotation where it asked with one, else the {@code argsOnly} one that reads the same argument of the new host.
	 */
	@SuppressWarnings("unchecked")
	private static List<AnnotationNode>[] wrapperLocals(MethodNode original,MixinHandlerShape shape,Map<Integer,Integer> served){
		List<AnnotationNode>[] table=new List[4];
		for(int offer=0;offer<OFFERED.size();offer++){
			List<AnnotationNode> annotations=null;
			for(var entry:served.entrySet()){
				MixinHandlerShape.Extra extra=shape.extras().get(entry.getKey());
				if(entry.getValue()==offer&&!extra.implicit()&&original.invisibleParameterAnnotations!=null&&extra.parameter()<original.invisibleParameterAnnotations.length)
					annotations=original.invisibleParameterAnnotations[extra.parameter()];
			}
			if(annotations==null){AnnotationNode local=new AnnotationNode(MixinHandlerShape.LOCAL);local.values=new ArrayList<>(List.of("argsOnly",Boolean.TRUE));annotations=new ArrayList<>(List.of(local));}
			table[2+offer]=annotations;
		}
		return table;
	}
	private static void leave(InsnList c){c.add(new VarInsnNode(Opcodes.ALOAD,5));c.add(new MethodInsnNode(Opcodes.INVOKESTATIC,SCOPE,"leave","(Ljava/lang/Object;)V",false));}
	private static MethodNode callback(ClassNode mixin,MethodNode original,MixinHandlerShape shape,Map<Integer,Integer> served){
		MethodNode fn=new MethodNode(Opcodes.ACC_PRIVATE,"forbric$hudContextCallback","(L"+GRAPHICS+";L"+DELTA+";[Ljava/lang/Object;)Ljava/lang/Object;",null,null);InsnList c=fn.instructions;LabelNode draw=new LabelNode();
		// args: [hud, original]; the original (the next callback frame inward, or the native selection) runs on the HUD
		// the handler passes on, so a hidden HUD hands its own HUD inward and a drawing handler's call is passed through.
		c.add(new VarInsnNode(Opcodes.ALOAD,0));c.add(new TypeInsnNode(Opcodes.CHECKCAST,HUD));c.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL,HUD,"isHidden","()Z",false));c.add(new JumpInsnNode(Opcodes.IFEQ,draw));element(c,1,"java/util/function/Function");element(c,0,HUD);c.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE,"java/util/function/Function","apply","(Ljava/lang/Object;)Ljava/lang/Object;",true));c.add(new InsnNode(Opcodes.ARETURN));c.add(draw);c.add(new FrameNode(Opcodes.F_SAME,0,null,0,null));
		c.add(new VarInsnNode(Opcodes.ALOAD,0));element(c,0,HUD);element(c,1,"java/util/function/Function");c.add(new MethodInsnNode(Opcodes.INVOKESTATIC,MixinWrapOperationShim.RUNTIME,"applied","(Ljava/util/function/Function;)L"+OP+";",false));
		// The handler's extras in its own order, each the graphics (slot 1) or the delta tracker (slot 2).
		for(int i=0;i<shape.extras().size();i++)c.add(new VarInsnNode(Opcodes.ALOAD,1+served.get(i)));
		c.add(MixinHandlerShim.callOwn(mixin,false,original.name+"$forbricOriginal",original.desc));c.add(new InsnNode(Opcodes.ARETURN));fn.maxStack=6;fn.maxLocals=4;MixinCallbackShape.uniqueMember(fn);return fn;
	}
	private static void element(InsnList c,int index,String type){c.add(new VarInsnNode(Opcodes.ALOAD,3));c.add(new IntInsnNode(Opcodes.BIPUSH,index));c.add(new InsnNode(Opcodes.AALOAD));c.add(new TypeInsnNode(Opcodes.CHECKCAST,type));}
}
