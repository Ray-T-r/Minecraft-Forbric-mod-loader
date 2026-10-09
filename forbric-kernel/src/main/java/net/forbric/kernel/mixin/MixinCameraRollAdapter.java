/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.mixin;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.function.Function;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.*;

import net.forbric.kernel.util.ForbricLog;

/**
 * Relates a source condition wrapper on a two-float call to the current call by native operand origins and CFG
 * conditions. A widened call is admitted only when its short overload completely delegates with a zero default.
 * Pure getter/constructor projections identify event birth operands; event dispatch and its changed live values
 * remain in place. Missing references, ambiguous points and unknown projections retain the original callback.
 */
public final class MixinCameraRollAdapter {
	public static final String PROPERTY = "forbric.cameraRollCallbacks";
	static final String CAMERA = "net/minecraft/client/Camera";
	static final String SHORT = "L" + CAMERA + ";setRotation(FF)V";
	static final String LONG = "L" + CAMERA + ";setRotation(FFF)V";

	private MixinCameraRollAdapter() { }

	public static boolean enabled() {
		return !"off".equalsIgnoreCase(System.getProperty(PROPERTY, "on"));
	}

	/** Returns the number of source callbacks whose complete correspondence was proved. */
    public static int adapt(ClassNode mixin,Function<String,ClassNode> targets) {
        return adapt(mixin,targets,NativeGameReferences::reference);
    }
    public static int adapt(ClassNode mixin,Function<String,ClassNode> targets,
            java.util.function.BiFunction<net.forbric.api.Ecosystem,String,ClassNode> references) {
        List<String> owners=MixinFit.mixinTargets(mixin);
        if(!enabled()||owners.size()!=1)return 0;
        ClassNode camera=targets.apply(owners.getFirst()),source=references.apply(MixinStubRebind.ecosystemOf(mixin.name),owners.getFirst());
        if(camera==null||source==null)return 0;
        record Move(MethodNode handler,CallOccurrenceAlignment.Match match) { }
        List<Move> moves=new ArrayList<>();
        for(MethodNode handler:mixin.methods) {
            // Already counted over the current body (by this pass or another): its ordinal is no longer a native count.
            if(CurrentBodyOrdinals.counted(handler))continue;
            AnnotationNode inject=MixinFit.injectorOf(handler);
            if(inject==null||!MixinCallbackShape.instance(handler)||!MixinCallbackShape.kind(handler,"WrapWithCondition")
                    ||MixinFit.atNodes(inject).size()!=1)continue;
            AnnotationNode at=MixinFit.atNodes(inject).getFirst();
            String member=MixinFit.asString(MixinFit.value(at,"target"));MixinFit.Member wanted=MixinFit.parseMember(member);
            List<String> selectors=MixinFit.stringList(MixinFit.value(inject,"method"));
            if(wanted==null||!camera.name.equals(wanted.owner())||!"(FF)V".equals(wanted.desc())||selectors.size()!=1
                    ||!MixinCallbackShape.point(handler,"INVOKE",member))continue;
            MethodNode nativeAlign=MixinStubRebind.bound(source,selectors.getFirst()),align=MixinStubRebind.bound(camera,selectors.getFirst());
            if(nativeAlign==null||align==null||!nativeAlign.desc.equals(align.desc))return 0;
            Object value=MixinFit.value(MixinFit.atNodes(inject).getFirst(),"ordinal");if(!(value instanceof Integer ordinal)||ordinal<0)return 0;
            Type[] arguments=Type.getArgumentTypes(handler.desc);
            if(arguments.length<3||!arguments[0].equals(Type.getObjectType(camera.name))||!arguments[1].equals(Type.FLOAT_TYPE)
                    ||!arguments[2].equals(Type.FLOAT_TYPE)||!Type.getReturnType(handler.desc).equals(Type.BOOLEAN_TYPE))return 0;
            CallOccurrenceAlignment.Match match=CallOccurrenceAlignment.prefixCall(source,nativeAlign,camera,align,member,ordinal,targets);
            if(match==null)return 0;
            if(!match.call().desc.equals("(FF)V")&&(!match.call().desc.equals("(FFF)V")||!shortDelegate(camera,wanted,match.call())))return 0;
            moves.add(new Move(handler,match));
        }
        if(moves.isEmpty())return 0;
        Set<String> claimed=new java.util.HashSet<>();
        for(Move move:moves)if(!claimed.add(CallOccurrenceAlignment.member(move.match().call())+"#"+move.match().ordinal()))return 0;
        MethodNode roll=MixinCallbackShape.unique(mixin,m->m.desc.equals("(F)F")&&MixinCallbackShape.kind(m,"ModifyArg")
                &&MixinCallbackShape.plainPoint(m,"INVOKE","Lorg/joml/Quaternionf;rotationYXZ(FFF)Lorg/joml/Quaternionf;"));
        int changed=0;
        for(Move move:moves) {
            if(move.match().call().desc.equals("(FFF)V"))widen(move.handler());
            retarget(move.handler(),CallOccurrenceAlignment.member(move.match().call()),move.match().ordinal());changed++;
            // The ordinal now counts the merged body: ThinnedCallOrdinals must not read it as a native count again.
            CurrentBodyOrdinals.mark(move.handler());
        }
        if(roll!=null) {
            List<MethodNode> candidates=camera.methods.stream().filter(m->MixinFit.stringList(MixinFit.value(MixinFit.injectorOf(roll),"method")).stream().anyMatch(selector->selector.equals(m.name)||selector.equals(m.name+m.desc))
                    &&java.util.Arrays.stream(m.instructions.toArray()).anyMatch(i->i instanceof MethodInsnNode call&&call.owner.equals("org/joml/Quaternionf")
                        &&call.name.equals("rotationYXZ")&&call.desc.equals("(FFF)Lorg/joml/Quaternionf;"))).toList();
            if(candidates.size()==1){set(MixinFit.injectorOf(roll),"method",new ArrayList<>(List.of(candidates.getFirst().name+candidates.getFirst().desc)));changed++;}
        }
        ForbricLog.info("[Forbric/Mixin] The camera roll callback now wraps the merged alignWithEntity's setRotation(FFF) calls; "
                +"native/current operand origins and control-flow conditions determine every occurrence");
        return changed;
    }
    /** The current short overload must still be the complete original argument forwarding plus a zero default. */
    private static boolean shortDelegate(ClassNode owner,MixinFit.Member source,MethodInsnNode destination) {
        MethodNode shortMethod=method(owner,source.name(),source.desc());if(shortMethod==null||!shortMethod.tryCatchBlocks.isEmpty())return false;
        List<AbstractInsnNode> code=Arrays.stream(shortMethod.instructions.toArray()).filter(i->i.getOpcode()>=0).toList();
        return code.size()==6&&code.get(0) instanceof VarInsnNode self&&self.getOpcode()==Opcodes.ALOAD&&self.var==0
                &&code.get(1) instanceof VarInsnNode a&&a.getOpcode()==Opcodes.FLOAD&&a.var==1
                &&code.get(2) instanceof VarInsnNode b&&b.getOpcode()==Opcodes.FLOAD&&b.var==2&&code.get(3).getOpcode()==Opcodes.FCONST_0
                &&code.get(4) instanceof MethodInsnNode call&&call.getOpcode()==destination.getOpcode()&&call.owner.equals(destination.owner)
                &&call.name.equals(destination.name)&&call.desc.equals(destination.desc)&&code.get(5).getOpcode()==Opcodes.RETURN;
    }

	private static void retarget(MethodNode method, String target, int ordinal) {
		AnnotationNode at = MixinFit.atNodes(MixinFit.injectorOf(method)).getFirst();
		set(at, "target", target);
		set(at, "ordinal", ordinal);
	}

	private static void set(AnnotationNode annotation, String key, Object value) {
		if (annotation.values == null) annotation.values = new ArrayList<>();
		for (int i = 0; i < annotation.values.size(); i += 2) {
			if (key.equals(annotation.values.get(i))) {
				annotation.values.set(i + 1, value);
				return;
			}
		}
		annotation.values.add(key);
		annotation.values.add(value);
	}

	private static MethodNode method(ClassNode node, String name, String desc) {
		return node.methods.stream().filter(m -> m.name.equals(name) && m.desc.equals(desc)).findFirst().orElse(null);
	}

	/**
	 * Adds the carrier's roll as the handler's fourth parameter ({@code Camera, yaw, pitch, roll}), which is local
	 * slot 4 of these instance handlers: every local, frame entry and parameter annotation from slot 4 on moves by one.
	 */
	private static void widen(MethodNode method) {
		List<Type> args = new ArrayList<>(Arrays.asList(Type.getArgumentTypes(method.desc)));
		args.add(3, Type.FLOAT_TYPE);
		method.desc = Type.getMethodDescriptor(Type.getReturnType(method.desc), args.toArray(Type[]::new));
        method.signature=null;
		for (AbstractInsnNode insn : method.instructions) {
			if (insn instanceof VarInsnNode var && var.var >= 4) var.var++;
			if (insn instanceof IincInsnNode inc && inc.var >= 4) inc.var++;
			if (insn instanceof FrameNode frame && (frame.type == Opcodes.F_NEW || frame.type == Opcodes.F_FULL)
					&& frame.local != null && frame.local.size() >= 4) frame.local.add(4, Opcodes.FLOAT);
		}
		if (method.localVariables != null) for (LocalVariableNode local : method.localVariables) if (local.index >= 4) local.index++;
		method.visibleParameterAnnotations = widened(method.visibleParameterAnnotations);
		method.invisibleParameterAnnotations = widened(method.invisibleParameterAnnotations);
		if (method.visibleAnnotableParameterCount > 0) method.visibleAnnotableParameterCount++;
		if (method.invisibleAnnotableParameterCount > 0) method.invisibleAnnotableParameterCount++;
		if (method.parameters != null) method.parameters.add(3, new ParameterNode("forbric$carrierRoll", 0));
		method.maxLocals++;
	}

	@SuppressWarnings("unchecked")
	private static List<AnnotationNode>[] widened(List<AnnotationNode>[] annotations) {
		if (annotations == null) return null;
		List<AnnotationNode>[] expanded = new List[annotations.length + 1];
		System.arraycopy(annotations, 0, expanded, 0, Math.min(3, annotations.length));
		if (annotations.length > 3) System.arraycopy(annotations, 3, expanded, 4, annotations.length - 3);
		return expanded;
	}
}
