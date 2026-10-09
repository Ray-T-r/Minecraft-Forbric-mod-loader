/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.mixin;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.*;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.AnalyzerException;
import org.objectweb.asm.tree.analysis.BasicInterpreter;
import org.objectweb.asm.tree.analysis.BasicValue;
import org.objectweb.asm.tree.analysis.Frame;
import org.objectweb.asm.tree.analysis.SourceInterpreter;
import org.objectweb.asm.tree.analysis.SourceValue;

import net.forbric.kernel.util.ForbricLog;

/**
 * A locals capture on a carrier's delegating stub follows the body into the method that carries it.
 *
 * <p>A carrier that widened a vanilla method keeps vanilla's signature as a stub and puts the body in the new overload
 * (a row of {@code carrier-stubs.txt}): Forge's {@code SpriteSourceList.list(ResourceManager)} forwards to
 * {@code list(ResourceManager, Set)}. A Fabric mod's {@code @Inject} with {@code locals = CAPTURE_*}, anchored in vanilla's
 * body, then lands on the stub, where its anchor and its locals are gone. MixinStubRebind moves the other injectors on such
 * a stub but not a locals capture, whose captured values Mixin reads positionally after the target's arguments. This
 * wraps it: the wrapper takes the body's arguments, its callback and the same captured locals, binds the body, and hands
 * the mod's handler the stub's arguments (read off the stub's own forwarding) and the locals. Continuity's capture of the
 * atlas loader map was the first seen.
 *
 * <p>Proved for each handler: Mixin binds it to one method that heads a carrier-stubs row and purely forwards every one of
 * its arguments to the body ({@link MixinStubRebind#delegation}); its operands are exactly the stub's arguments and
 * callback and every other parameter is a captured local; its one point is an {@code INVOKE} (no shift, no occurrence past
 * the first) the stub does not make and the body makes once; and at that call the body's locals after its arguments, in
 * slot order, begin with locals of exactly the captured types — each holding a value on every path there (a local the table
 * puts in scope but no path wrote refuses it), typed by the table, or by its producer where the body has no table. Only
 * that handler moves: every other injector of the mixin, on the stub or not, is left to the passes that own it. One locals
 * capture per stub per mixin; two stay as compiled. {@code -Dforbric.spriteLoaderCallbacks=off} moves none.
 */
public final class MixinSpriteLoaderCallbackAdapter {
	public static final String PROPERTY = "forbric.spriteLoaderCallbacks";
	private static final String CALLBACK = "Lorg/spongepowered/asm/mixin/injection/callback/CallbackInfo;";
	private static final String RETURNABLE = "Lorg/spongepowered/asm/mixin/injection/callback/CallbackInfoReturnable;";
	private static final String ATLAS = "net/minecraft/client/renderer/texture/atlas/SpriteSourceList";

	private MixinSpriteLoaderCallbackAdapter() { }

	/** One handler that can follow: the stub it is bound to, the forwarding, and the types it captures. */
	private record Capture(MethodNode handler, MethodNode stub, MixinStubRebind.Delegation delegation, List<Type> captured) { }

	public static int adapt(ClassNode mixin, Function<String, ClassNode> targets) {
		if ("off".equalsIgnoreCase(net.forbric.kernel.util.ForbricSwitches.get(PROPERTY, "on")) || mixin == null || mixin.methods == null) return 0;
		List<String> owners = MixinFit.mixinTargets(mixin);
		if (owners.size() != 1) return 0;
		ClassNode target = targets.apply(owners.getFirst());
		if (target == null || !MixinStubRebind.ownsCarrierStub(target.name)) return 0;
		Map<MethodNode, List<Capture>> byStub = new IdentityHashMap<>();
		for (MethodNode handler : new ArrayList<>(mixin.methods)) {
			Capture capture = capture(handler, target);
			if (capture != null) byStub.computeIfAbsent(capture.stub(), s -> new ArrayList<>()).add(capture);
		}
		int changed = 0;
		for (List<Capture> captures : byStub.values()) {
			if (captures.size() != 1) continue;
			wrap(mixin, captures.getFirst());
			changed++;
		}
		if (changed > 0) {
			ForbricLog.info("[Forbric/Mixin] %s: %d locals capture(s) moved off a carrier's delegating stub of %s onto the body it "
					+ "forwards to, still handed the stub's arguments and the locals they captured", mixin.name, changed, target.name);
			// The line the atlas listing's case has always printed, which run evidence reads; it says nothing a trigger uses.
			if (target.name.equals(ATLAS)) ForbricLog.info("[Forbric/Mixin] atlas callbacks now use the metadata-aware list overload "
					+ "and capture its actual loader map (%d injector(s))", changed);
		}
		return changed;
	}

	private static Capture capture(MethodNode handler, ClassNode target) {
		if (!MixinCallbackShape.kind(handler, "Inject") || !MixinCallbackShape.instance(handler)) return null;
		AnnotationNode inject = MixinFit.injectorOf(handler);
		if (!(MixinFit.value(inject, "locals") instanceof String[] locals) || locals.length != 2 || !locals[1].startsWith("CAPTURE_")) return null;
		List<AnnotationNode> points = MixinFit.atNodes(inject);
		if (points.size() != 1) return null;
		AnnotationNode at = points.getFirst();
		String member = MixinFit.asString(MixinFit.value(at, "target"));
		Object ordinal = MixinFit.value(at, "ordinal");
		if (member == null || !MixinCallbackShape.beforePoint(handler, "INVOKE", member)
				|| ordinal != null && !Integer.valueOf(0).equals(ordinal) && !Integer.valueOf(-1).equals(ordinal)) return null;
		MethodNode stub = MixinTargetSelectors.one(handler, target);
		if (stub == null || !MixinStubRebind.isCarrierStub(target, stub)) return null;
		MixinStubRebind.Delegation delegation = MixinStubRebind.delegation(target, stub);
		if (delegation == null) return null;
		for (int position : delegation.positions()) if (position < 0) return null;
		MixinHandlerShape shape = MixinHandlerShape.of(handler);
		List<Type> operands = new ArrayList<>(List.of(Type.getArgumentTypes(stub.desc)));
		operands.add(Type.getType(Type.getReturnType(stub.desc).equals(Type.VOID_TYPE) ? CALLBACK : RETURNABLE));
		if (shape == null || !shape.returns().equals(Type.VOID_TYPE) || !shape.operands().equals(operands) || shape.extras().isEmpty()
				|| shape.extras().stream().anyMatch(extra -> extra.role() != MixinHandlerShape.Role.CAPTURED)) return null;
		MethodNode body = delegation.delegate();
		if (MixinPlayerWorldCallbackAdapter.count(stub, member) != 0 || MixinPlayerWorldCallbackAdapter.count(body, member) != 1) return null;
		List<Type> captured = shape.extras().stream().map(MixinHandlerShape.Extra::type).toList();
		if (!capturedAt(target.name, body, MixinPlayerWorldCallbackAdapter.first(body, member), captured)) return null;
		return new Capture(handler, stub, delegation, captured);
	}

	/**
	 * Whether, at {@code anchor}, the body's locals after its arguments begin, in slot order, with locals of exactly the
	 * {@code captured} types: what a locals capture there hands over.
	 */
	static boolean capturedAt(String owner, MethodNode body, AbstractInsnNode anchor, List<Type> captured) {
		Frame<BasicValue> held;
		Frame<SourceValue> sources;
		Frame<SourceValue>[] all;
		int at = body.instructions.indexOf(anchor);
		try {
			held = new Analyzer<>(new BasicInterpreter()).analyze(owner, body)[at];
			all = new Analyzer<>(new SourceInterpreter()).analyze(owner, body);
			sources = all[at];
		} catch (AnalyzerException | RuntimeException unanalysable) {
			return false;
		}
		if (held == null || sources == null) return false;
		int slot = (body.access & Opcodes.ACC_STATIC) == 0 ? 1 : 0;
		for (Type argument : Type.getArgumentTypes(body.desc)) slot += argument.getSize();
		for (Type type : captured) {
			for (;; slot++) {
				if (slot >= held.getLocals()) return false;
				boolean written = held.getLocal(slot) != BasicValue.UNINITIALIZED_VALUE;
				LocalVariableNode named = inScope(body, slot, at);
				if (written) break;
				if (named != null) return false;   // the table puts a local here that no path wrote: not the shape it says
			}
			BasicValue value = held.getLocal(slot);
			LocalVariableNode named = inScope(body, slot, at);
			if (named != null ? !named.desc.equals(type.getDescriptor()) : !produced(body, all, sources.getLocal(slot), type)) return false;
			if (type.getSort() == Type.OBJECT || type.getSort() == Type.ARRAY ? !value.isReference() : !value.getType().equals(basic(type))) return false;
			slot += type.getSize();
		}
		return true;
	}

	private static Type basic(Type type) {
		return switch (type.getSort()) {
			case Type.BOOLEAN, Type.CHAR, Type.BYTE, Type.SHORT, Type.INT -> Type.INT_TYPE;
			default -> type;
		};
	}

	private static LocalVariableNode inScope(MethodNode body, int slot, int at) {
		if (body.localVariables == null) return null;
		for (LocalVariableNode local : body.localVariables) {
			if (local.index == slot && body.instructions.indexOf(local.start) <= at && at < body.instructions.indexOf(local.end)) return local;
		}
		return null;
	}

	/**
	 * Where the body has no table: whether every store reaching the slot stores a value produced as {@code type} — by
	 * {@code new}, a cast or a call returning it — or, for the JDK's own types, as one assignable to it.
	 */
	private static boolean produced(MethodNode body, Frame<SourceValue>[] frames, SourceValue slot, Type type) {
		if (slot.insns.isEmpty()) return false;
		for (AbstractInsnNode store : slot.insns) {
			if (!(store instanceof VarInsnNode variable) || variable.getOpcode() < Opcodes.ISTORE || variable.getOpcode() > Opcodes.ASTORE) return false;
			Frame<SourceValue> frame = frames[body.instructions.indexOf(store)];
			if (frame == null) return false;
			SourceValue stored = frame.getStack(frame.getStackSize() - 1);
			if (stored.insns.size() != 1) return false;
			AbstractInsnNode producer = stored.insns.iterator().next();
			// A {@code new} is stored through its {@code dup}: the value is what the duplicated one was.
			for (int guard = 0; producer.getOpcode() == Opcodes.DUP && guard < 8; guard++) {
				Frame<SourceValue> at = frames[body.instructions.indexOf(producer)];
				if (at == null || at.getStack(at.getStackSize() - 1).insns.size() != 1) return false;
				producer = at.getStack(at.getStackSize() - 1).insns.iterator().next();
			}
			Type made = producer instanceof TypeInsnNode allocation && (producer.getOpcode() == Opcodes.NEW || producer.getOpcode() == Opcodes.CHECKCAST)
					? Type.getObjectType(allocation.desc)
					: producer instanceof MethodInsnNode call && !call.name.equals("<init>") ? Type.getReturnType(call.desc) : null;
			if (made == null || !assignable(made, type)) return false;
		}
		return true;
	}

	private static boolean assignable(Type made, Type wanted) {
		if (made.equals(wanted)) return true;
		if (made.getSort() != Type.OBJECT || wanted.getSort() != Type.OBJECT
				|| !made.getInternalName().startsWith("java/") || !wanted.getInternalName().startsWith("java/")) return false;
		try {
			ClassLoader platform = ClassLoader.getPlatformClassLoader();
			return Class.forName(wanted.getClassName(), false, platform).isAssignableFrom(Class.forName(made.getClassName(), false, platform));
		} catch (ClassNotFoundException | LinkageError unknown) {
			return false;
		}
	}

	/**
	 * The handler keeps its body under the kernel's {@code $forbricOriginal} name; a wrapper of its name takes the body's
	 * arguments, its callback and the captured locals, binds the body, and calls it with the stub's arguments.
	 */
	private static void wrap(ClassNode mixin, Capture capture) {
		MethodNode original = capture.handler(), stub = capture.stub(), body = capture.delegation().delegate();
		AnnotationNode inject = MixinFit.injectorOf(original);
		String name = original.name;
		original.name += "$forbricOriginal";
		original.visibleAnnotations.remove(inject);
		MixinCallbackShape.uniqueMember(original);
		Type[] bodyArguments = Type.getArgumentTypes(body.desc), stubArguments = Type.getArgumentTypes(stub.desc);
		Type callback = Type.getType(Type.getReturnType(stub.desc).equals(Type.VOID_TYPE) ? CALLBACK : RETURNABLE);
		List<Type> parameters = new ArrayList<>(List.of(bodyArguments));
		parameters.add(callback);
		parameters.addAll(capture.captured());
		MethodNode wrapper = new MethodNode(Opcodes.ASM9, Opcodes.ACC_PRIVATE, name,
				Type.getMethodDescriptor(Type.VOID_TYPE, parameters.toArray(Type[]::new)), null, null);
		MixinPlayerWorldCallbackAdapter.set(inject, "method", List.of(body.name + body.desc));
		wrapper.visibleAnnotations = new ArrayList<>(List.of(inject));
		int[] bodySlots = new int[bodyArguments.length];
		int slot = 1;
		for (int a = 0; a < bodyArguments.length; a++) { bodySlots[a] = slot; slot += bodyArguments[a].getSize(); }
		InsnList code = wrapper.instructions;
		code.add(new VarInsnNode(Opcodes.ALOAD, 0));
		for (int s = 0; s < stubArguments.length; s++) {
			int position = capture.delegation().positions()[s];
			code.add(new VarInsnNode(bodyArguments[position].getOpcode(Opcodes.ILOAD), bodySlots[position]));
		}
		code.add(new VarInsnNode(Opcodes.ALOAD, slot));
		int next = slot + 1;
		for (Type type : capture.captured()) {
			code.add(new VarInsnNode(type.getOpcode(Opcodes.ILOAD), next));
			next += type.getSize();
		}
		code.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, mixin.name, original.name, original.desc, false));
		code.add(new InsnNode(Opcodes.RETURN));
		wrapper.maxLocals = next;
		wrapper.maxStack = Type.getArgumentsAndReturnSizes(original.desc) >> 2;
		mixin.methods.add(wrapper);
	}
}
