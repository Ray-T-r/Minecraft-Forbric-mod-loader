/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.mixin;

import java.util.*;

import org.objectweb.asm.Handle;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.*;
import org.objectweb.asm.tree.analysis.*;

/**
 * Follows explicit helper edges or an exact CFG fragment retained in an uncalled body. No mod, class or method
 * names authorize a move. The handler body, Operation, and invocation member remain the mod's own.
 */
final class MixinExecutionPathRetarget {
	static final String PROPERTY = "forbric.mixinRetarget.executionPaths";
	private MixinExecutionPathRetarget() { }

	static List<MixinRetarget.Rewrite> plan(ClassNode mixin, MethodNode handler, AnnotationNode injector,
			List<String> selectors, ClassNode target, ClassNode reference) {
		if ("off".equalsIgnoreCase(System.getProperty(PROPERTY, "on")) || selectors.size() != 1
				|| MixinFit.value(injector, "slice") != null || grouped(handler)
				|| MixinStubRebind.ecosystemOf(mixin.name) == null) return List.of();
		String selector = selectors.getFirst();
		MethodNode selected = MixinStubRebind.bound(target, selector);
		if (selected == null || selected.instructions == null) return List.of();
		List<AnnotationNode> points = MixinFit.atNodes(injector);
		if (points.isEmpty()) return List.of();
		List<String> members = new ArrayList<>();
		for (AnnotationNode at : points) {
			String kind = MixinFit.asString(MixinFit.value(at, "value"));
			String member = MixinFit.asString(MixinFit.value(at, "target"));
			if (!"INVOKE".equals(kind) || member == null || MixinFit.value(at, "slice") != null
					|| MixinFit.value(at, "ordinal") instanceof Number n && n.intValue() > 0) return List.of();
			members.add(member);
		}
		MethodNode original = reference == null ? null : MixinStubRebind.bound(reference, selector);
        if(original!=null&&MixinRetarget.AT_DRIVEN.contains(injector.desc)&&MixinRetarget.movableWhole(handler,injector,true,false)
                &&!java.util.stream.IntStream.range(0,Type.getArgumentTypes(handler.desc).length).anyMatch(parameter->MixinFit.sugar(handler,parameter))) {
            MethodNode nativeBody=NativeOverloadBody.destination(target,reference,original,selected);
            if(nativeBody!=null&&members.stream().allMatch(member->count(original,member)==1&&count(nativeBody,member)==1)
                    &&MixinRetarget.handlerFits(handler,injector,target,original,original))
                return List.of(new MixinRetarget.Rewrite(handler.name+handler.desc,MixinRetarget.Element.SELECTOR,selector,nativeBody.name+nativeBody.desc,
                    "the same native caller forwards every source operand to one verified widened body; one added-context early exit retains the complete source CFG and local producers"));
        }
		if (original != null && original.desc.equals(selected.desc) && members.stream().allMatch(m -> count(original, m) == 1)
				&& members.stream().allMatch(m -> !MixinFit.containsMember(selected, m))) {
			List<MixinRetarget.Rewrite> edge = helperEdge(handler, injector, selector, selected, original, points, members, target);
			if (!edge.isEmpty()) return edge;
		}
		List<MixinRetarget.Rewrite> thunk = lambdaThunk(mixin, handler, injector, selector, selected, original, members, target);
		if (!thunk.isEmpty()) return thunk;
		List<MixinRetarget.Rewrite> fragment = liveFragment(mixin, handler, injector, selector, selected, members, target);
		if (!fragment.isEmpty()) return fragment;
		return lambdaHelper(mixin, handler, injector, selector, selected, original, members, target);
	}

	/** The owning platform called this member once; one live ordinary method-reference thunk carries that same call. */
	private static List<MixinRetarget.Rewrite> lambdaThunk(ClassNode mixin, MethodNode handler, AnnotationNode injector,
			String selector, MethodNode selected, MethodNode original, List<String> members, ClassNode target) {
		if (original == null || !original.desc.equals(selected.desc) || !MixinRetarget.AT_DRIVEN.contains(injector.desc)
				|| !MixinRetarget.movableWhole(handler, injector, true, false) || members.stream().anyMatch(m -> count(original, m) != 1)) return List.of();
		net.forbric.api.Ecosystem ecosystem = MixinStubRebind.ecosystemOf(mixin.name);
		if (members.stream().anyMatch(m -> count(selected, m) > 0) && MergedBaseUncalledMethods.neverRuns(target, selected, ecosystem) == null) return List.of();
		Set<MethodNode> live = reachable(target, ecosystem); List<MethodNode> candidates = new ArrayList<>();
		Map<MethodNode,Map<Integer,Integer>> captures = new IdentityHashMap<>();
		for (MethodNode method : target.methods) {
			if (!live.contains(method) || !net.forbric.kernel.transform.LambdaInvocationThunkInjector.isThunk(target.name, method)
					|| !method.desc.equals(original.desc) || !sameStatic(original, method) || !oneOrdinaryReference(target, method)
					|| members.stream().anyMatch(m -> count(method, m) != 1)
					|| !MixinRetarget.handlerFits(handler, injector, target, original, method)) continue;
			// A target argument capture must still be the value supplied to the native invocation, not merely the same type.
			if (!parametersForwarded(target.name, original, (MethodInsnNode) invocation(original, members.getFirst()))) continue;
			Map<Integer,Integer> capture = MixinLocalOriginProof.prove(handler, target.name, original, invocation(original, members.getFirst()),
					method, invocation(method, members.getFirst()));
			if (capture == null) continue;
			captures.put(method, capture);
			candidates.add(method);
		}
		if (candidates.size() != 1) return List.of();
		MethodNode destination = candidates.getFirst();
		List<MixinRetarget.Rewrite> result = new ArrayList<>();
		result.add(new MixinRetarget.Rewrite(handler.name + handler.desc, MixinRetarget.Element.SELECTOR, selector,
				destination.name + destination.desc, "the owning platform's unique invocation and unchanged parameter slots are carried by one live method-reference thunk"));
		for (var capture : captures.get(destination).entrySet()) result.add(new MixinRetarget.Rewrite(handler.name + handler.desc,
				MixinRetarget.Element.LOCAL_INDEX, String.valueOf(capture.getKey()), String.valueOf(capture.getValue()),
				"the native parameter capture has one unchanged slot in the materialized thunk"));
		return List.copyOf(result);
	}

	private static boolean parametersForwarded(String owner, MethodNode method, MethodInsnNode call) {
		if (call == null || !call.owner.equals(owner) || !call.desc.equals(method.desc)
				|| (method.access & Opcodes.ACC_STATIC) != 0 || call.getOpcode() != Opcodes.INVOKEVIRTUAL) return false;
		Frame<SourceValue> frame;
		try { frame = new Analyzer<>(new SourceInterpreter()).analyze(owner, method)[method.instructions.indexOf(call)]; }
		catch (AnalyzerException | RuntimeException invalid) { return false; }
		Type[] arguments = Type.getArgumentTypes(method.desc); int start = frame == null ? -1 : frame.getStackSize() - arguments.length - 1;
		if (start < 0) return false;
		int slot = 0;
		for (int i = 0; i <= arguments.length; i++) {
			SourceValue value = frame.getStack(start + i); Type type = i == 0 ? Type.getObjectType(owner) : arguments[i - 1];
			if (value.insns.size() != 1 || !(value.insns.iterator().next() instanceof VarInsnNode load)
					|| load.var != slot || load.getOpcode() != type.getOpcode(Opcodes.ILOAD) || storedParameter(method, slot)) return false;
			slot += type.getSize();
		}
		return true;
	}

	/** Follow the thunk's sole callee only when its native invocation inputs and captures retain their producers. */
	private static List<MixinRetarget.Rewrite> lambdaHelper(ClassNode mixin, MethodNode handler, AnnotationNode injector,
			String selector, MethodNode selected, MethodNode original, List<String> members, ClassNode target) {
		if (original == null || !original.desc.equals(selected.desc) || members.size() != 1 || count(original, members.getFirst()) != 1
				|| !MixinRetarget.AT_DRIVEN.contains(injector.desc) || !MixinRetarget.movableWhole(handler, injector, true, false)) return List.of();
		net.forbric.api.Ecosystem ecosystem = MixinStubRebind.ecosystemOf(mixin.name);
		if (count(selected, members.getFirst()) > 0 && MergedBaseUncalledMethods.neverRuns(target, selected, ecosystem) == null) return List.of();
		Set<MethodNode> live = reachable(target, ecosystem); Map<MethodNode,Map<Integer,Integer>> candidates = new IdentityHashMap<>();
		MethodInsnNode nativeCall = (MethodInsnNode) invocation(original, members.getFirst());
		for (MethodNode thunk : target.methods) {
			if (!live.contains(thunk) || !oneOrdinaryReference(target, thunk) || !net.forbric.kernel.transform.LambdaInvocationThunkInjector.isThunk(target.name, thunk)) continue;
			MethodInsnNode edge = net.forbric.kernel.transform.LambdaInvocationThunkInjector.invocation(thunk);
			MethodNode helper = declared(target, edge.name, edge.desc);
			if (helper == null || helper == selected || !live.contains(helper) || (helper.access & Opcodes.ACC_PRIVATE) == 0
					|| !helper.desc.equals(original.desc) || !sameStatic(helper, original) || incoming(target, helper) != 1) continue;
			List<MethodInsnNode> anchors = new ArrayList<>();
			for (AbstractInsnNode instruction : helper.instructions) if (instruction instanceof MethodInsnNode call
					&& call.owner.equals(nativeCall.owner) && call.name.equals(nativeCall.name) && call.getOpcode() == nativeCall.getOpcode()
					&& (call.desc.equals(nativeCall.desc) || MixinWrapOperationShim.WRAP_OPERATION.equals(injector.desc)
					&& MixinAtWidenedCall.widens(nativeCall.desc, call.desc))) anchors.add(call);
			if (anchors.size() != 1 || !sameInvocationInputs(target.name, original, nativeCall, helper, anchors.getFirst())) continue;
			if (!MixinRetarget.handlerFits(handler, injector, target, original, anchors.getFirst().desc.equals(nativeCall.desc) ? helper : original)) continue;
			if (!anchors.getFirst().desc.equals(nativeCall.desc)) {
				MethodNode trial = new MethodNode(handler.access, handler.name, handler.desc, handler.signature,
						handler.exceptions == null ? null : handler.exceptions.toArray(String[]::new)); handler.accept(trial); AnnotationNode trialInjector = MixinFit.injectorOf(trial);
				set(trialInjector, "method", new ArrayList<>(List.of(helper.name + helper.desc)));
				if (MixinWrapOperationShim.wouldWrap(mixin.name, trial, List.of(helper)) == null) continue;
			}
			Map<Integer,Integer> captures = MixinLocalOriginProof.prove(handler, target.name, original, nativeCall, helper, anchors.getFirst());
			if (captures != null) candidates.put(helper, captures);
		}
		if (candidates.size() != 1) return List.of();
		MethodNode helper = candidates.keySet().iterator().next(); List<MixinRetarget.Rewrite> result = new ArrayList<>();
		result.add(new MixinRetarget.Rewrite(handler.name + handler.desc, MixinRetarget.Element.SELECTOR, selector, helper.name + helper.desc,
				"the owning platform's unique invocation inputs and captures retain their SSA producers in one private helper reached by a live method-reference thunk"));
		for (var capture : candidates.get(helper).entrySet()) result.add(new MixinRetarget.Rewrite(handler.name + handler.desc, MixinRetarget.Element.LOCAL_INDEX,
				String.valueOf(capture.getKey()), String.valueOf(capture.getValue()), "the native parameter capture has one unchanged producer in the thunk's helper"));
		return List.copyOf(result);
	}

	private static boolean sameInvocationInputs(String owner, MethodNode original, MethodInsnNode nativeCall, MethodNode current, MethodInsnNode presentCall) {
		Frame<SourceValue>[] before, after;
		try { before = new Analyzer<>(new SourceInterpreter()).analyze(owner, original); after = new Analyzer<>(new SourceInterpreter()).analyze(owner, current); }
		catch (AnalyzerException | RuntimeException invalid) { return false; }
		Frame<SourceValue> a = before[original.instructions.indexOf(nativeCall)], b = after[current.instructions.indexOf(presentCall)];
		int receiver = nativeCall.getOpcode() == Opcodes.INVOKESTATIC ? 0 : 1;
		int nativeInputs = Type.getArgumentTypes(nativeCall.desc).length + receiver, currentInputs = Type.getArgumentTypes(presentCall.desc).length + receiver;
		if (a == null || b == null || a.getStackSize() < nativeInputs || b.getStackSize() < currentInputs) return false;
		for (int i = 0; i < nativeInputs; i++) {
			String left = inputOrigin(owner, original, before, a.getStack(a.getStackSize() - nativeInputs + i), -1, new HashSet<>());
			String right = inputOrigin(owner, current, after, b.getStack(b.getStackSize() - currentInputs + i), -1, new HashSet<>());
			if (left == null || !left.equals(right)) return false;
		}
		return true;
	}

	private static String inputOrigin(String owner, MethodNode method, Frame<SourceValue>[] frames, SourceValue value, int local, Set<AbstractInsnNode> seen) {
		if (value == null) return null;
		if (value.insns.isEmpty()) { Type type = parameterType(owner, method, local); return type == null ? null : "parameter:" + local + ":" + type.getDescriptor(); }
		if (value.insns.size() != 1) return null; AbstractInsnNode source = value.insns.iterator().next(); if (!seen.add(source)) return null;
		Frame<SourceValue> frame = frames[method.instructions.indexOf(source)]; if (frame == null) return null;
		int opcode = source.getOpcode();
		if (source instanceof VarInsnNode variable && opcode >= Opcodes.ILOAD && opcode <= Opcodes.ALOAD) return inputOrigin(owner, method, frames, frame.getLocal(variable.var), variable.var, seen);
		if (source instanceof VarInsnNode && opcode >= Opcodes.ISTORE && opcode <= Opcodes.ASTORE || opcode == Opcodes.DUP || opcode == Opcodes.CHECKCAST)
			return frame.getStackSize() == 0 ? null : inputOrigin(owner, method, frames, frame.getStack(frame.getStackSize() - 1), -1, seen);
		if (source instanceof FieldInsnNode field && (opcode == Opcodes.GETFIELD || opcode == Opcodes.GETSTATIC)) {
			String receiver = opcode == Opcodes.GETSTATIC ? "static" : frame.getStackSize() == 0 ? null : inputOrigin(owner, method, frames, frame.getStack(frame.getStackSize() - 1), -1, seen);
			return receiver == null ? null : receiver + "/field:" + field.owner + "." + field.name + field.desc;
		}
		if (source instanceof LdcInsnNode constant) return "constant:" + constant.cst;
		if (source instanceof IntInsnNode constant) return "constant:" + opcode + ":" + constant.operand;
		if (opcode == Opcodes.ACONST_NULL || opcode >= Opcodes.ICONST_M1 && opcode <= Opcodes.DCONST_1) return "constant-op:" + opcode;
		return null;
	}
	private static Type parameterType(String owner, MethodNode method, int wanted) {
		int slot = (method.access & Opcodes.ACC_STATIC) == 0 ? 1 : 0; if (slot == 1 && wanted == 0) return Type.getObjectType(owner);
		for (Type type : Type.getArgumentTypes(method.desc)) { if (slot == wanted) return type; slot += type.getSize(); } return null;
	}
	private static void set(AnnotationNode annotation, String name, Object value) {
		for (int i = 0; i < annotation.values.size(); i += 2) if (name.equals(annotation.values.get(i))) { annotation.values.set(i + 1, value); return; }
		annotation.values.add(name); annotation.values.add(value);
	}

	/** The selected method itself calls precisely one helper that contains all missing members. */
	private static List<MixinRetarget.Rewrite> helperEdge(MethodNode handler, AnnotationNode injector, String selector,
			MethodNode selected, MethodNode original, List<AnnotationNode> points, List<String> members, ClassNode target) {
		List<MethodInsnNode> edges = new ArrayList<>();
		for (AbstractInsnNode instruction : selected.instructions) {
			if (!(instruction instanceof MethodInsnNode call) || !call.owner.equals(target.name)
					|| call.name.equals("<init>")) continue;
			MethodNode helper = declared(target, call.name, call.desc);
			if (helper == null || helper == selected || !sameStatic(selected, helper)) continue;
			if (members.stream().allMatch(m -> count(helper, m) == 1)) edges.add(call);
		}
		if (edges.size() != 1) return List.of();
		MethodInsnNode edge = edges.getFirst();
		MethodNode helper = declared(target, edge.name, edge.desc);
		// A second invocation or registration of that helper could turn one original call site into several.
		if (incoming(target, helper) != 1) return List.of();
		if (MixinRetarget.INJECT.equals(injector.desc)) {
			// The new point remains on the caller, but that alone does not prove a captured local still has the
			// value it had at the native point. Shared/local-capture contracts need native dataflow provenance.
			if (MixinFit.value(injector, "locals") != null) return List.of();
			List<MixinRetarget.Rewrite> result = new ArrayList<>();
			Map<Integer, Integer> captures = null;
			for (AnnotationNode at : points) {
				String member = MixinFit.asString(MixinFit.value(at, "target"));
				String[] shift = MixinFit.value(at, "shift") instanceof String[] value ? value : null;
				boolean after = shift != null && "AFTER".equals(shift[1]);
				if (shift != null && !after && !"NONE".equals(shift[1]) && !"BEFORE".equals(shift[1])) return List.of();
				if (!boundary(helper, member, after)) return List.of();
				AbstractInsnNode oldPoint = invocation(original, member), newPoint = edge;
				if (after) { oldPoint = next(oldPoint); newPoint = next(newPoint); }
				Map<TypeInsnNode, TypeInsnNode> roles = MixinLocalOriginProof.constructorRoles(handler, target.name, original,
						(MethodInsnNode) invocation(original, member), selected, edge, helper, (MethodInsnNode) invocation(helper, member));
				Map<Integer, Integer> observed = MixinLocalOriginProof.prove(handler, target.name, original, oldPoint, selected, newPoint, roles);
				if (observed == null || captures != null && !captures.equals(observed)) return List.of();
				captures = observed;
				result.add(new MixinRetarget.Rewrite(handler.name + handler.desc, MixinRetarget.Element.AT_TARGET, member,
						"L" + edge.owner + ";" + edge.name + edge.desc,
						"the selected method's unique helper edge reaches the exact invocation at its " + (after ? "tail" : "head")));
			}
			if (captures != null) for (var capture : captures.entrySet()) result.add(new MixinRetarget.Rewrite(handler.name + handler.desc,
					MixinRetarget.Element.LOCAL_INDEX, String.valueOf(capture.getKey()), String.valueOf(capture.getValue()),
					"the native capture has one conserved caller-frame producer or anchored constructor-argument role"));
			return List.copyOf(result);
		}
		if (!MixinRetarget.AT_DRIVEN.contains(injector.desc) || !capturesFollow(handler, injector, target, selected, helper, edge)) {
			return List.of();
		}
		return List.of(new MixinRetarget.Rewrite(handler.name + handler.desc, MixinRetarget.Element.SELECTOR, selector,
				helper.name + helper.desc, "the selected method's unique helper edge carries every exact invocation; captured arguments are forwarded unchanged"));
	}

	/** BEFORE/AFTER a first/last call remains on the caller, so its callback, cancellation and locals stay there. */
	private static boolean boundary(MethodNode helper, String member, boolean after) {
		if (helper.tryCatchBlocks != null && !helper.tryCatchBlocks.isEmpty()) return false;
		List<AbstractInsnNode> code = code(helper);
		int anchor = -1;
		for (int i = 0; i < code.size(); i++) if (matches(code.get(i), member)) anchor = i;
		if (anchor < 0) return false;
		if (after) {
			for (int i = anchor + 1; i < code.size(); i++) {
				int op = code.get(i).getOpcode();
				if (op < Opcodes.IRETURN || op > Opcodes.RETURN) return false;
			}
		} else {
			for (int i = 0; i < anchor; i++) if (!loadOrConstant(code.get(i))) return false;
		}
		return true;
	}

	/** A handler can take only invocation inputs and enclosing parameters whose same values reach the helper. */
	private static boolean capturesFollow(MethodNode handler, AnnotationNode injector, ClassNode owner, MethodNode selected,
			MethodNode helper, MethodInsnNode edge) {
		if (!MixinRetarget.handlerFits(handler, injector, owner, selected, helper)) return false;
		Type[] parameters = Type.getArgumentTypes(handler.desc), from = Type.getArgumentTypes(selected.desc), to = Type.getArgumentTypes(helper.desc);
		Frame<SourceValue> frame;
		try { frame = new Analyzer<>(new SourceInterpreter()).analyze(owner.name, selected)[selected.instructions.indexOf(edge)]; }
		catch (AnalyzerException | RuntimeException invalid) { return false; }
		if (frame == null || frame.getStackSize() < to.length) return false;
		int[] slots = slots(selected);
		for (int p = 0; p < parameters.length; p++) {
			AnnotationNode local = MixinStubRebind.sugar(handler, p, MixinRetarget.LOCAL_SUGAR);
			if (local == null) {
				if (MixinFit.sugar(handler, p)) return false;
				continue;
			}
			if (MixinFit.value(local, "name") != null || MixinFit.value(local, "index") != null
					|| MixinFit.value(local, "ordinal") instanceof Number ordinal && ordinal.intValue() > 0) return false;
			int original = unique(from, parameters[p]), destination = unique(to, parameters[p]);
			if (original < 0 || destination < 0 || storedParameter(selected, slots[original])) return false;
			SourceValue value = frame.getStack(frame.getStackSize() - to.length + destination);
			if (value.insns.size() != 1 || !(value.insns.iterator().next() instanceof VarInsnNode load)
					|| load.var != slots[original] || !loadOrConstant(load)) return false;
		}
		return true;
	}

	/** The retained native-shaped body is the provenance; the destination must be an exact closed CFG fragment of it. */
	private static List<MixinRetarget.Rewrite> liveFragment(ClassNode mixin, MethodNode handler, AnnotationNode injector,
			String selector, MethodNode selected, List<String> members, ClassNode target) {
		net.forbric.api.Ecosystem ecosystem = MixinStubRebind.ecosystemOf(mixin.name);
		if (ecosystem == null || MergedBaseUncalledMethods.neverRuns(target, selected, ecosystem) == null
				|| !MixinRetarget.movableWhole(handler, injector, true, false)
				|| !Type.VOID_TYPE.equals(Type.getReturnType(selected.desc))) return List.of();
		Set<MethodNode> live = reachable(target, ecosystem);
		List<MethodNode> candidates = new ArrayList<>();
		for (MethodNode helper : target.methods) {
			if (helper == selected || !live.contains(helper) || (helper.access & Opcodes.ACC_PRIVATE) == 0
					|| !sameStatic(selected, helper) || !helper.desc.equals(selected.desc)) continue;
			if (members.stream().anyMatch(m -> count(selected, m) != 1 || count(helper, m) != 1)) continue;
			if (!MixinRetarget.handlerFits(handler, injector, target, selected, helper) || !fragment(target, selected, helper)) continue;
			candidates.add(helper);
		}
		if (candidates.size() != 1) return List.of();
		MethodNode helper = candidates.getFirst();
		return List.of(new MixinRetarget.Rewrite(handler.name + handler.desc, MixinRetarget.Element.SELECTOR, selector,
				helper.name + helper.desc, "an exact closed CFG fragment of the retained uncalled body is reached by the live direct/lambda graph"));
	}

	static boolean fragment(ClassNode owner, MethodNode selected, MethodNode helper) {
		if (!selected.desc.equals(helper.desc) || !sameStatic(selected, helper)
				|| !Type.VOID_TYPE.equals(Type.getReturnType(helper.desc))
				|| !selected.tryCatchBlocks.isEmpty() || !helper.tryCatchBlocks.isEmpty()) return false;
		List<AbstractInsnNode> source = code(selected), destination = code(helper);
		if (destination.size() < 2 || destination.getLast().getOpcode() != Opcodes.RETURN) return false;
		int length = destination.size() - 1, arguments = parameterSlots(helper);
		for (int i = 0; i < length; i++) {
			AbstractInsnNode instruction = destination.get(i);
			int opcode = instruction.getOpcode();
			if (opcode >= Opcodes.IRETURN && opcode <= Opcodes.RETURN || opcode == Opcodes.ATHROW
					|| instruction instanceof VarInsnNode v && (v.var >= arguments || opcode >= Opcodes.ISTORE && opcode <= Opcodes.ASTORE)
					|| instruction instanceof IincInsnNode) return false;
		}
		for (int slot = 0; slot < arguments; slot++) if (storedParameter(selected, slot)) return false;
		Frame<BasicValue>[] frames;
		try { frames = new Analyzer<>(new BasicInterpreter()).analyze(owner.name, selected); }
		catch (AnalyzerException | RuntimeException invalid) { return false; }
		int found = 0;
		for (int start = 0; start + length <= source.size(); start++) {
			int end = start + length;
			Frame<BasicValue> begin = frames[selected.instructions.indexOf(source.get(start))];
			if (begin == null || begin.getStackSize() != 0) continue;
			if (end < source.size()) {
				Frame<BasicValue> exit = frames[selected.instructions.indexOf(source.get(end))];
				if (exit == null || exit.getStackSize() != 0) continue;
			}
			boolean same = true;
			for (int i = 0; i < length; i++) if (!same(destination.get(i), source.get(start + i), destination, source, start)) { same = false; break; }
			if (!same || entersMiddle(source, start, end)) continue;
			found++;
		}
		return found == 1;
	}

	private static boolean entersMiddle(List<AbstractInsnNode> source, int start, int end) {
		for (int i = 0; i < source.size(); i++) {
			if (i >= start && i < end) continue;
			for (LabelNode label : jumps(source.get(i))) {
				int target = position(source, label);
				if (target > start && target < end) return true;
			}
		}
		return false;
	}

	private static boolean same(AbstractInsnNode a, AbstractInsnNode b, List<AbstractInsnNode> from, List<AbstractInsnNode> to, int offset) {
		if (a.getOpcode() != b.getOpcode() || a.getType() != b.getType()) return false;
		if (a instanceof VarInsnNode x) return x.var == ((VarInsnNode) b).var;
		if (a instanceof IntInsnNode x) return x.operand == ((IntInsnNode) b).operand;
		if (a instanceof TypeInsnNode x) return x.desc.equals(((TypeInsnNode) b).desc);
		if (a instanceof FieldInsnNode x) { FieldInsnNode y = (FieldInsnNode) b; return x.owner.equals(y.owner) && x.name.equals(y.name) && x.desc.equals(y.desc); }
		if (a instanceof MethodInsnNode x) { MethodInsnNode y = (MethodInsnNode) b; return x.owner.equals(y.owner) && x.name.equals(y.name) && x.desc.equals(y.desc) && x.itf == y.itf; }
		if (a instanceof LdcInsnNode x) return Objects.equals(x.cst, ((LdcInsnNode) b).cst);
		if (a instanceof JumpInsnNode x) return position(from, x.label) + offset == position(to, ((JumpInsnNode) b).label);
		if (a instanceof InvokeDynamicInsnNode x) { InvokeDynamicInsnNode y = (InvokeDynamicInsnNode) b; return x.name.equals(y.name) && x.desc.equals(y.desc) && x.bsm.equals(y.bsm) && Arrays.deepEquals(x.bsmArgs, y.bsmArgs); }
		if (a instanceof TableSwitchInsnNode x) { TableSwitchInsnNode y = (TableSwitchInsnNode) b; return x.min == y.min && x.max == y.max && sameLabels(x.dflt, x.labels, y.dflt, y.labels, from, to, offset); }
		if (a instanceof LookupSwitchInsnNode x) { LookupSwitchInsnNode y = (LookupSwitchInsnNode) b; return x.keys.equals(y.keys) && sameLabels(x.dflt, x.labels, y.dflt, y.labels, from, to, offset); }
		return a instanceof InsnNode;
	}

	private static boolean sameLabels(LabelNode a, List<LabelNode> as, LabelNode b, List<LabelNode> bs,
			List<AbstractInsnNode> from, List<AbstractInsnNode> to, int offset) {
		if (as.size() != bs.size() || position(from, a) + offset != position(to, b)) return false;
		for (int i = 0; i < as.size(); i++) if (position(from, as.get(i)) + offset != position(to, bs.get(i))) return false;
		return true;
	}

	static Set<MethodNode> reachable(ClassNode owner, net.forbric.api.Ecosystem ecosystem) {
		Set<MethodNode> seen = Collections.newSetFromMap(new IdentityHashMap<>());
		Deque<MethodNode> pending = new ArrayDeque<>();
		for (MethodNode method : owner.methods) if ((method.access & Opcodes.ACC_PRIVATE) == 0 || method.name.equals("<init>") || method.name.equals("<clinit>")) {
			if (MergedBaseUncalledMethods.neverRuns(owner, method, ecosystem) == null) pending.add(method);
		}
		while (!pending.isEmpty()) {
			MethodNode method = pending.removeFirst();
			if (!seen.add(method)) continue;
			for (MethodNode next : references(owner, method)) if (!seen.contains(next)) pending.add(next);
		}
		return seen;
	}

	private static int incoming(ClassNode owner, MethodNode wanted) {
		int result = 0;
		for (MethodNode method : owner.methods) for (MethodNode reference : references(owner, method)) if (reference == wanted) result++;
		return result;
	}
	private static boolean oneOrdinaryReference(ClassNode owner, MethodNode wanted) {
		if (incoming(owner, wanted) != 1) return false;
		for (MethodNode method : owner.methods) for (AbstractInsnNode instruction : method.instructions)
			if (instruction instanceof InvokeDynamicInsnNode dynamic && dynamic.bsm.getOwner().equals("java/lang/invoke/LambdaMetafactory")
					&& dynamic.bsm.getName().equals("metafactory") && dynamic.bsmArgs.length == 3
					&& dynamic.bsmArgs[0] instanceof Type sam && sam.getSort() == Type.METHOD
					&& dynamic.bsmArgs[2] instanceof Type actual && actual.getSort() == Type.METHOD
					&& dynamic.bsmArgs[1] instanceof Handle handle && handle.getTag() == Opcodes.H_INVOKEVIRTUAL && !handle.isInterface()
					&& handle.getOwner().equals(owner.name) && handle.getName().equals(wanted.name) && handle.getDesc().equals(wanted.desc)) return true;
		return false;
	}

	private static List<MethodNode> references(ClassNode owner, MethodNode method) {
		List<MethodNode> result = new ArrayList<>();
		for (AbstractInsnNode instruction : method.instructions) {
			if (instruction instanceof MethodInsnNode call && call.owner.equals(owner.name)) {
				MethodNode declared = declared(owner, call.name, call.desc); if (declared != null) result.add(declared);
			} else if (instruction instanceof InvokeDynamicInsnNode indy && indy.bsm.getOwner().equals("java/lang/invoke/LambdaMetafactory")) {
				for (Object argument : indy.bsmArgs) if (argument instanceof Handle h && h.getOwner().equals(owner.name)) {
					MethodNode declared = declared(owner, h.getName(), h.getDesc()); if (declared != null) result.add(declared);
				}
			}
		}
		return result;
	}

	private static List<AbstractInsnNode> code(MethodNode method) {
		List<AbstractInsnNode> result = new ArrayList<>();
		for (AbstractInsnNode instruction : method.instructions) if (instruction.getOpcode() >= 0) result.add(instruction);
		return result;
	}
	private static List<LabelNode> jumps(AbstractInsnNode instruction) {
		if (instruction instanceof JumpInsnNode jump) return List.of(jump.label);
		if (instruction instanceof TableSwitchInsnNode table) { List<LabelNode> out = new ArrayList<>(table.labels); out.add(table.dflt); return out; }
		if (instruction instanceof LookupSwitchInsnNode lookup) { List<LabelNode> out = new ArrayList<>(lookup.labels); out.add(lookup.dflt); return out; }
		return List.of();
	}
	private static int position(List<AbstractInsnNode> code, LabelNode label) {
		AbstractInsnNode next = label;
		while (next != null && next.getOpcode() < 0) next = next.getNext();
		return next == null ? code.size() : code.indexOf(next);
	}
	private static boolean matches(AbstractInsnNode instruction, String member) {
		if (!(instruction instanceof MethodInsnNode call)) return false;
		return member.equals("L" + call.owner + ";" + call.name + call.desc);
	}
	private static AbstractInsnNode invocation(MethodNode method, String member) { for (AbstractInsnNode instruction : method.instructions) if (matches(instruction, member)) return instruction; return null; }
	private static AbstractInsnNode next(AbstractInsnNode instruction) { if (instruction == null) return null; instruction = instruction.getNext(); while (instruction != null && instruction.getOpcode() < 0) instruction = instruction.getNext(); return instruction; }
	private static int count(MethodNode method, String member) { int result = 0; for (AbstractInsnNode instruction : method.instructions) if (matches(instruction, member)) result++; return result; }
	private static MethodNode declared(ClassNode owner, String name, String desc) { return owner.methods.stream().filter(m -> m.name.equals(name) && m.desc.equals(desc)).findFirst().orElse(null); }
	private static boolean sameStatic(MethodNode a, MethodNode b) { return (a.access & Opcodes.ACC_STATIC) == (b.access & Opcodes.ACC_STATIC); }
	private static boolean grouped(MethodNode handler) { return has(handler.visibleAnnotations, MixinRetarget.GROUP) || has(handler.invisibleAnnotations, MixinRetarget.GROUP); }
	private static boolean has(List<AnnotationNode> annotations, String desc) { return annotations != null && annotations.stream().anyMatch(a -> a.desc.equals(desc)); }
	private static boolean storedParameter(MethodNode method, int slot) { for (AbstractInsnNode instruction : method.instructions) if (instruction instanceof VarInsnNode v && v.var == slot && v.getOpcode() >= Opcodes.ISTORE && v.getOpcode() <= Opcodes.ASTORE || instruction instanceof IincInsnNode increment && increment.var == slot) return true; return false; }
	private static int unique(Type[] types, Type type) { int found = -1; for (int i = 0; i < types.length; i++) if (types[i].equals(type)) { if (found >= 0) return -1; found = i; } return found; }
	private static int parameterSlots(MethodNode method) { int result = (method.access & Opcodes.ACC_STATIC) == 0 ? 1 : 0; for (Type type : Type.getArgumentTypes(method.desc)) result += type.getSize(); return result; }
	private static int[] slots(MethodNode method) { Type[] args = Type.getArgumentTypes(method.desc); int[] result = new int[args.length]; int slot = (method.access & Opcodes.ACC_STATIC) == 0 ? 1 : 0; for (int i = 0; i < args.length; i++) { result[i] = slot; slot += args[i].getSize(); } return result; }
	private static boolean loadOrConstant(AbstractInsnNode instruction) {
		int op = instruction.getOpcode();
		return instruction instanceof VarInsnNode && op >= Opcodes.ILOAD && op <= Opcodes.ALOAD
				|| instruction instanceof LdcInsnNode || op == Opcodes.ACONST_NULL || op >= Opcodes.ICONST_M1 && op <= Opcodes.DCONST_1
				|| op == Opcodes.BIPUSH || op == Opcodes.SIPUSH;
	}
}
