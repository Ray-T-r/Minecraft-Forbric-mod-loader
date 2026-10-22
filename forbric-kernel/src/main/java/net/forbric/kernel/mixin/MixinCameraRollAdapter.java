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
 *
 * <p>Each wrapper is decided on its own: one this cannot prove stays as compiled and the mixin's others still move;
 * only two wrappers on one current occurrence keep the whole mixin as compiled. A wrapper without an ordinal wraps every
 * native occurrence, so every one must be matched: per current overload it then binds all of that overload's calls when
 * they are all counterparts (otherwise each by its ordinal), the mod's own handler taking the first and copies of it the
 * rest — on the merged camera it keeps the two two-float calls and a widened copy takes the two three-float ones.
 */
public final class MixinCameraRollAdapter {
	public static final String PROPERTY = "forbric.cameraRollCallbacks";
	static final String CAMERA = "net/minecraft/client/Camera";
	static final String SHORT = "L" + CAMERA + ";setRotation(FF)V";
	static final String LONG = "L" + CAMERA + ";setRotation(FFF)V";

	private MixinCameraRollAdapter() { }

	public static boolean enabled() {
		return !"off".equalsIgnoreCase(net.forbric.kernel.util.ForbricSwitches.get(PROPERTY, "on"));
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
        int argumentLists=adaptArgumentLists(mixin,camera,source,targets);
        return argumentLists+adaptConditions(mixin,camera,source,targets);
    }

    /** {@code -Dforbric.cameraRollCallbacks.argumentLists=off}: no {@code @ModifyArgs} moves; the condition wrappers still do. */
    public static final String ARGUMENT_LISTS_PROPERTY="forbric.cameraRollCallbacks.argumentLists";
    private static final String MODIFY_ARGS="Lorg/spongepowered/asm/mixin/injection/ModifyArgs;";
    private static final String ARGS="org/spongepowered/asm/mixin/injection/invoke/arg/Args";

    /**
     * Moves a {@code @ModifyArgs} from the call its point picks natively to the merged call that plays that call's role,
     * where the carrier made that call through a widened overload of the same method.
     *
     * <p>Vanilla's {@code Camera.alignWithEntity} sets the ordinary view's angles with {@code setRotation(FF)}; the merged
     * camera first posts NeoForge's angles event and sets them with {@code setRotation(FFF)}, the event's roll appended. A
     * guest that hooks that call by its vanilla point — {@code setRotation(FF)}, ordinal 0 after the minecart branch's
     * {@code setPosition} — matched the first two-float call left in that slice, the sleeper's, and its angles callback ran
     * only in bed.
     *
     * <p>Moved only when every link is proved: the native slice and ordinal pick exactly one call; the merged call that
     * plays its role is found by its operand origins and branch conditions ({@link CallOccurrenceAlignment#prefixCall}),
     * and is a call of the same method with the named descriptor's parameters as its leading ones; the named overload is a
     * pure forwarding to that one with constants for the appended parameters (what it would pass, had the carrier kept
     * the old call); and the handler only reads and writes the call's arguments by constant indices below the named
     * descriptor's count ({@link #argumentsWithin}) — so the appended ones are untouched, as natively, where they did not
     * exist. Its point then names that call by its ordinal in the merged method, the slice dropped.
     */
    static int adaptArgumentLists(ClassNode mixin,ClassNode owner,ClassNode source,Function<String,ClassNode> targets) {
        if("off".equalsIgnoreCase(net.forbric.kernel.util.ForbricSwitches.get(ARGUMENT_LISTS_PROPERTY,"on")))return 0;
        int moved=0;
        for(MethodNode handler:mixin.methods) {
            if(CurrentBodyOrdinals.counted(handler)||MixinFit.groupOf(handler)!=null)continue;
            AnnotationNode inject=MixinFit.injectorOf(handler);
            if(inject==null||!MODIFY_ARGS.equals(inject.desc)||MixinFit.atNodes(inject).size()!=1)continue;
            AnnotationNode at=MixinFit.atNodes(inject).getFirst();
            if(!"INVOKE".equals(MixinFit.asString(MixinFit.value(at,"value")))||MixinFit.value(at,"shift")!=null)continue;
            MixinFit.Member wanted=MixinFit.parseMember(MixinFit.asString(MixinFit.value(at,"target")));
            if(wanted==null||wanted.desc()==null||!wanted.desc().startsWith("(")||wanted.owner()!=null&&!owner.name.equals(wanted.owner()))continue;
            String member="L"+owner.name+";"+wanted.name()+wanted.desc();
            Type[] handlerArguments=Type.getArgumentTypes(handler.desc);
            if(handlerArguments.length==0||!handlerArguments[0].equals(Type.getObjectType(ARGS)))continue;
            List<MethodNode> nativeBound=MixinTargetSelectors.bound(handler,source),bound=MixinTargetSelectors.bound(handler,owner);
            if(nativeBound==null||bound==null||nativeBound.size()!=1||bound.size()!=1)continue;
            MethodNode nativeAlign=nativeBound.getFirst(),align=bound.getFirst();
            if(!nativeAlign.desc.equals(align.desc)||((nativeAlign.access^align.access)&Opcodes.ACC_STATIC)!=0)continue;
            AnnotationNode slice=NativeSliceOccurrences.slice(inject);
            if(MixinFit.value(inject,"slice")!=null&&slice==null)continue;
            List<MethodInsnNode> nativeSelected=slice==null?MixinCallbackShape.calls(at,nativeAlign):NativeSliceOccurrences.selected(at,slice,nativeAlign);
            if(slice==null&&nativeSelected!=null&&MixinFit.value(at,"ordinal") instanceof Integer n)
                nativeSelected=n>=0&&n<nativeSelected.size()?List.of(nativeSelected.get(n)):List.of();
            if(nativeSelected==null||nativeSelected.size()!=1)continue;
            int nativeOrdinal=0;
            for(var instruction:nativeAlign.instructions) {
                if(instruction==nativeSelected.getFirst())break;
                if(instruction instanceof MethodInsnNode call&&member.equals(CallOccurrenceAlignment.member(call)))nativeOrdinal++;
            }
            CallOccurrenceAlignment.Match match=CallOccurrenceAlignment.prefixCall(source,nativeAlign,owner,align,member,nativeOrdinal,targets);
            if(match==null||match.call().desc.equals(wanted.desc()))continue;
            if(!shortForwards(owner,wanted,match.call())||!argumentsWithin(mixin,handler,0,Type.getArgumentTypes(wanted.desc()).length))continue;
            String longer="L"+owner.name+";"+wanted.name()+match.call().desc;
            set(at,"target",longer);
            set(at,"ordinal",match.ordinal());
            MixinPlayerWorldCallbackAdapter.remove(at,"slice");
            MixinPlayerWorldCallbackAdapter.remove(inject,"slice");
            CurrentBodyOrdinals.mark(handler);
            moved++;
            ForbricLog.info("[Forbric/Mixin] %s.%s: the %s%s call its point picks in %s.%s is made on the merged body as %s%s (ordinal %d), "
                    +"the carrier's widened overload, with the same operands under the same conditions; the %s%s it named there plays "
                    +"another role, so the point now names the widened call, and the handler touches only the arguments the old one had",
                    mixin.name.replace('/','.'),handler.name,wanted.name(),wanted.desc(),owner.name.replace('/','.'),align.name,
                    wanted.name(),match.call().desc,match.ordinal(),wanted.name(),wanted.desc());
        }
        return moved;
    }

    /**
     * Whether {@code owner}'s overload {@code source} is a pure forwarding to {@code destination}: its receiver (when it
     * has one) and every parameter loaded in order, a constant for each parameter the destination appends, the one call,
     * and a return of what it returned. That is the value the appended parameters have wherever the old call is made.
     */
    static boolean shortForwards(ClassNode owner,MixinFit.Member source,MethodInsnNode destination) {
        MethodNode shortMethod=method(owner,source.name(),source.desc());
        if(shortMethod==null||shortMethod.instructions==null||!shortMethod.tryCatchBlocks.isEmpty()
                ||!destination.owner.equals(owner.name)||!destination.name.equals(source.name()))return false;
        Type[] shortArguments=Type.getArgumentTypes(source.desc()),longArguments=Type.getArgumentTypes(destination.desc);
        if(longArguments.length<=shortArguments.length||!Type.getReturnType(source.desc()).equals(Type.getReturnType(destination.desc)))return false;
        for(int i=0;i<shortArguments.length;i++)if(!shortArguments[i].equals(longArguments[i]))return false;
        boolean instance=(shortMethod.access&Opcodes.ACC_STATIC)==0;
        if(instance==(destination.getOpcode()==Opcodes.INVOKESTATIC))return false;
        List<AbstractInsnNode> code=Arrays.stream(shortMethod.instructions.toArray()).filter(i->i.getOpcode()>=0).toList();
        int at=0,slot=0;
        if(instance){if(!(code.get(at++) instanceof VarInsnNode self)||self.getOpcode()!=Opcodes.ALOAD||self.var!=0)return false;slot=1;}
        for(Type argument:shortArguments) {
            if(at>=code.size()||!(code.get(at++) instanceof VarInsnNode load)||load.getOpcode()!=argument.getOpcode(Opcodes.ILOAD)||load.var!=slot)return false;
            slot+=argument.getSize();
        }
        for(int i=shortArguments.length;i<longArguments.length;i++)if(at>=code.size()||!constant(code.get(at++),longArguments[i]))return false;
        if(at+2!=code.size()||!(code.get(at) instanceof MethodInsnNode call)||call.getOpcode()!=destination.getOpcode()
                ||!call.owner.equals(destination.owner)||!call.name.equals(destination.name)||!call.desc.equals(destination.desc))return false;
        return code.get(at+1).getOpcode()==Type.getReturnType(source.desc()).getOpcode(Opcodes.IRETURN);
    }

    /** Whether {@code insn} pushes a constant of {@code type}. */
    private static boolean constant(AbstractInsnNode insn,Type type) {
        int opcode=insn.getOpcode();
        return switch(type.getSort()) {
            case Type.BOOLEAN,Type.CHAR,Type.BYTE,Type.SHORT,Type.INT->opcode>=Opcodes.ICONST_M1&&opcode<=Opcodes.ICONST_5||opcode==Opcodes.BIPUSH
                    ||opcode==Opcodes.SIPUSH||insn instanceof LdcInsnNode ldc&&ldc.cst instanceof Integer;
            case Type.FLOAT->opcode>=Opcodes.FCONST_0&&opcode<=Opcodes.FCONST_2||insn instanceof LdcInsnNode ldc&&ldc.cst instanceof Float;
            case Type.LONG->opcode==Opcodes.LCONST_0||opcode==Opcodes.LCONST_1||insn instanceof LdcInsnNode ldc&&ldc.cst instanceof Long;
            case Type.DOUBLE->opcode==Opcodes.DCONST_0||opcode==Opcodes.DCONST_1||insn instanceof LdcInsnNode ldc&&ldc.cst instanceof Double;
            default->opcode==Opcodes.ACONST_NULL;
        };
    }

    /**
     * Whether the {@code Args} in {@code parameter} of {@code method} is only read and written by {@code Args.get(int)} and
     * {@code Args.set(int, Object)} with a constant index below {@code count}, in the method itself or in a lambda of the
     * mixin it is captured into (checked there the same way). Any other use — {@code size()}, {@code setAll}, a store, a
     * call or a field that takes it — could see the arguments the widened call appends, and answers no.
     */
    static boolean argumentsWithin(ClassNode mixin,MethodNode method,int parameter,int count) {
        return argumentsWithin(mixin,method,parameter,count,new java.util.HashSet<>());
    }

    private static boolean argumentsWithin(ClassNode mixin,MethodNode method,int parameter,int count,Set<String> seen) {
        if(method==null||method.instructions==null||!seen.add(method.name+method.desc))return method!=null&&method.instructions!=null;
        Type[] arguments=Type.getArgumentTypes(method.desc);
        if(parameter>=arguments.length||!arguments[parameter].equals(Type.getObjectType(ARGS)))return false;
        int slot=(method.access&Opcodes.ACC_STATIC)==0?1:0;
        for(int i=0;i<parameter;i++)slot+=arguments[i].getSize();
        org.objectweb.asm.tree.analysis.Frame<org.objectweb.asm.tree.analysis.SourceValue>[] frames;
        try {
            frames=new org.objectweb.asm.tree.analysis.Analyzer<>(new org.objectweb.asm.tree.analysis.SourceInterpreter()).analyze(mixin.name,method);
        } catch(org.objectweb.asm.tree.analysis.AnalyzerException invalid) {
            net.forbric.kernel.util.Caught.expected(invalid);
            return false;
        }
        for(AbstractInsnNode insn:method.instructions.toArray()) {
            if(!(insn instanceof VarInsnNode load)||load.var!=slot)continue;
            if(load.getOpcode()!=Opcodes.ALOAD)return false;   // the parameter slot reused or overwritten: not one value
            // Every instruction that takes this load's value, and as which operand.
            List<OperandStack.Use> uses=OperandStack.uses(method,frames,load);
            if(uses.isEmpty())return false;
            for(OperandStack.Use use:uses)
                if(!allowedUse(mixin,use.instruction(),use.fromTop(),frames[method.instructions.indexOf(use.instruction())],count,seen))return false;
        }
        return true;
    }

    private static boolean allowedUse(ClassNode mixin,AbstractInsnNode use,int fromTop,
            org.objectweb.asm.tree.analysis.Frame<org.objectweb.asm.tree.analysis.SourceValue> frame,int count,Set<String> seen) {
        if(use instanceof MethodInsnNode call&&call.owner.equals(ARGS)&&call.getOpcode()==Opcodes.INVOKEVIRTUAL) {
            int arity=Type.getArgumentTypes(call.desc).length;
            if(fromTop!=arity)return false;   // the Args must be the receiver
            boolean get=call.name.equals("get")&&call.desc.equals("(I)Ljava/lang/Object;"),put=call.name.equals("set")&&call.desc.equals("(ILjava/lang/Object;)V");
            if(!get&&!put)return false;
            var index=frame.getStack(frame.getStackSize()-arity);
            if(index.insns.size()!=1)return false;
            Integer value=intConstant(index.insns.iterator().next());
            return value!=null&&value>=0&&value<count;
        }
        if(use instanceof org.objectweb.asm.tree.InvokeDynamicInsnNode indy&&indy.bsm.getOwner().equals("java/lang/invoke/LambdaMetafactory")
                &&indy.bsmArgs.length>=2&&indy.bsmArgs[1] instanceof org.objectweb.asm.Handle implementation&&implementation.getOwner().equals(mixin.name)) {
            int captured=Type.getArgumentTypes(indy.desc).length,position=captured-1-fromTop;
            MethodNode lambda=method(mixin,implementation.getName(),implementation.getDesc());
            if(lambda==null)return false;
            boolean receiver=implementation.getTag()!=Opcodes.H_INVOKESTATIC;
            if(receiver&&position==0)return false;   // the Args as the lambda's receiver: not a capture this reads
            return argumentsWithin(mixin,lambda,receiver?position-1:position,count,seen);
        }
        return use.getOpcode()==Opcodes.POP;
    }

    private static Integer intConstant(AbstractInsnNode insn) {
        int opcode=insn.getOpcode();
        if(opcode>=Opcodes.ICONST_M1&&opcode<=Opcodes.ICONST_5)return opcode-Opcodes.ICONST_0;
        if(insn instanceof org.objectweb.asm.tree.IntInsnNode push&&(opcode==Opcodes.BIPUSH||opcode==Opcodes.SIPUSH))return push.operand;
        if(insn instanceof LdcInsnNode ldc&&ldc.cst instanceof Integer value)return value;
        return null;
    }

    /** The condition wrappers on two-float calls; the rest of this class's contract. */
    static int adaptConditions(ClassNode mixin,ClassNode camera,ClassNode source,Function<String,ClassNode> targets) {
        // One handler's native occurrences, each matched to the merged call that stands for it; a handler without an ordinal
        // wraps every occurrence natively, so it stands for all of them.
        record Move(MethodNode handler,MethodNode align,List<CallOccurrenceAlignment.Match> matches,boolean everyOccurrence,int nativeOrdinal) { }
        List<Move> moves=new ArrayList<>();
        for(MethodNode handler:mixin.methods) {
            // Already counted over the current body (by this pass or another): its ordinal is no longer a native count.
            if(CurrentBodyOrdinals.counted(handler))continue;
            AnnotationNode inject=MixinFit.injectorOf(handler);
            if(inject==null||!MixinCallbackShape.instance(handler)||!MixinCallbackShape.kind(handler,"WrapWithCondition")
                    ||MixinFit.atNodes(inject).size()!=1)continue;
            AnnotationNode at=MixinFit.atNodes(inject).getFirst();
            // The call the target names as Mixin resolves it (whitespace, a dotted owner; no owner, where the native method
            // makes that call on the camera alone), counted below by that member.
            MixinFit.Member wanted=MixinFit.parseMember(MixinFit.asString(MixinFit.value(at,"target")));
            if(wanted==null||wanted.owner()!=null&&!camera.name.equals(wanted.owner())||!"(FF)V".equals(wanted.desc()))continue;
            String member="L"+camera.name+";"+wanted.name()+wanted.desc();
            // However the selectors are written: several bound methods are not one alignment; none is a miss.
            List<MethodNode> nativeBound=MixinTargetSelectors.bound(handler,source),bound=MixinTargetSelectors.bound(handler,camera);
            if(nativeBound==null||bound==null||nativeBound.size()>1||bound.size()>1)continue;
            MethodNode nativeAlign=nativeBound.isEmpty()?null:nativeBound.getFirst(),align=bound.isEmpty()?null:bound.getFirst();
            // A handler this cannot prove stays as compiled; it does not cost the mixin's other handlers their move.
            if(nativeAlign==null||align==null||!nativeAlign.desc.equals(align.desc))continue;
            if(!MixinCallbackShape.point(handler,"INVOKE",member,nativeAlign))continue;
            Type[] arguments=Type.getArgumentTypes(handler.desc);
            if(arguments.length<3||!arguments[0].equals(Type.getObjectType(camera.name))||!arguments[1].equals(Type.FLOAT_TYPE)
                    ||!arguments[2].equals(Type.FLOAT_TYPE)||!Type.getReturnType(handler.desc).equals(Type.BOOLEAN_TYPE))continue;
            Object value=MixinFit.value(at,"ordinal");
            boolean every=value==null||Integer.valueOf(-1).equals(value);
            int occurrences=occurrences(nativeAlign,member);
            List<Integer> nativeOrdinals=every?java.util.stream.IntStream.range(0,occurrences).boxed().toList()
                    :value instanceof Integer ordinal&&ordinal>=0&&ordinal<occurrences?List.of(ordinal):List.of();
            if(nativeOrdinals.isEmpty())continue;
            List<CallOccurrenceAlignment.Match> matches=new ArrayList<>();
            for(int ordinal:nativeOrdinals) {
                CallOccurrenceAlignment.Match match=CallOccurrenceAlignment.prefixCall(source,nativeAlign,camera,align,member,ordinal,targets);
                if(match==null||!match.call().desc.equals("(FF)V")&&(!match.call().desc.equals("(FFF)V")||!shortDelegate(camera,wanted,match.call()))) {
                    matches=null;break;
                }
                matches.add(match);
            }
            if(matches==null)continue;
            moves.add(new Move(handler,align,matches,every,every?-1:nativeOrdinals.getFirst()));
        }
        if(moves.isEmpty())return 0;
        // Two handlers on one merged occurrence: which wrap the mod meant to run first is not this pass's to decide.
        Set<String> claimed=new java.util.HashSet<>();
        for(Move move:moves)for(var match:move.matches())if(!claimed.add(CallOccurrenceAlignment.member(match.call())+"#"+match.ordinal()))return 0;
        MethodNode roll=MixinCallbackShape.unique(mixin,m->MixinCallbackShape.shape(m,"(F)F")&&MixinCallbackShape.kind(m,"ModifyArg")
                &&MixinCallbackShape.plainPoint(m,"INVOKE","Lorg/joml/Quaternionf;rotationYXZ(FFF)Lorg/joml/Quaternionf;",MixinTargetSelectors.one(m,source)));
        int changed=0;
        for(Move move:moves) {
            // Per merged overload: one handler for all of its calls when the handler wrapped every occurrence and they are
            // all counterparts, else one per occurrence. The first keeps the mod's handler; the rest are copies of it.
            java.util.Map<String,List<Integer>> byCall=new java.util.LinkedHashMap<>();
            for(var match:move.matches())byCall.computeIfAbsent(CallOccurrenceAlignment.member(match.call()),k->new ArrayList<>()).add(match.ordinal());
            List<java.util.Map.Entry<String,Integer>> points=new ArrayList<>();
            for(var call:byCall.entrySet()) {
                if(move.everyOccurrence()&&call.getValue().size()==occurrences(move.align(),call.getKey()))points.add(java.util.Map.entry(call.getKey(),-1));
                else for(int ordinal:call.getValue())points.add(java.util.Map.entry(call.getKey(),ordinal));
            }
            MethodNode handler=move.handler();
            boolean unchanged=points.size()==1&&points.getFirst().getKey().equals(SHORT)&&points.getFirst().getValue()==move.nativeOrdinal();
            if(unchanged)continue;
            List<MethodNode> copies=new ArrayList<>();
            for(int n=1;n<points.size();n++) {
                MethodNode copy=new MethodNode(handler.access,handler.name+"$forbricOccurrence"+n,handler.desc,handler.signature,
                        handler.exceptions==null?null:handler.exceptions.toArray(String[]::new));
                handler.accept(copy);copies.add(copy);
            }
            for(int n=0;n<points.size();n++) {
                MethodNode target=n==0?handler:copies.get(n-1);
                String call=points.get(n).getKey();
                // The mod's own handler may keep its point (its two-float calls are still all there); its copies take the rest.
                boolean same=n==0&&call.equals(SHORT)&&points.get(n).getValue()==move.nativeOrdinal();
                if(!same) {
                    if(call.equals(LONG))widen(target);
                    retarget(target,call,points.get(n).getValue());changed++;
                }
                // The point now counts the merged body: ThinnedCallOrdinals must not read it as a native count again,
                // and this pass must not split the handler again.
                CurrentBodyOrdinals.mark(target);
                if(n>0)mixin.methods.add(target);
            }
        }
        if(changed==0)return 0;
        if(roll!=null) {
            // The roll modifier was written for the method its selector binds natively; in the merged class that method's
            // name family may carry the rotation in another overload (setRotation(FF) now delegates to setRotation(FFF)).
            List<MethodNode> nativeRoll=MixinTargetSelectors.bound(roll,source);
            Set<String> family=nativeRoll==null?Set.of():nativeRoll.stream().map(m->m.name).collect(java.util.stream.Collectors.toSet());
            List<MethodNode> candidates=camera.methods.stream().filter(m->family.contains(m.name)&&java.util.Arrays.stream(m.instructions.toArray()).anyMatch(i->i instanceof MethodInsnNode call&&call.owner.equals("org/joml/Quaternionf")
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

	/** Points the handler at {@code target}'s occurrence {@code ordinal}, or at every occurrence when it is -1. */
	private static void retarget(MethodNode method, String target, int ordinal) {
		AnnotationNode at = MixinFit.atNodes(MixinFit.injectorOf(method)).getFirst();
		set(at, "target", target);
		if (ordinal >= 0) set(at, "ordinal", ordinal);
		else MixinPlayerWorldCallbackAdapter.remove(at, "ordinal");
	}

	/** How many calls of {@code member} ({@code Lowner;name(desc)}) {@code method} makes. */
	private static int occurrences(MethodNode method, String member) {
		int n = 0;
		for (AbstractInsnNode insn : method.instructions) if (insn instanceof MethodInsnNode call && member.equals(CallOccurrenceAlignment.member(call))) n++;
		return n;
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
