/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.mixin;

import java.util.List;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.function.Function;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

import net.forbric.api.Ecosystem;
import net.forbric.kernel.util.ForbricLog;

/**
 * Moves an injector anchored inside a vanilla method onto the overload a carrier moved the body to, when the vanilla
 * signature was kept as a builder stub: it makes a builder, hands it to the overload, and returns what the builder builds.
 *
 * <p>NeoForge turned {@code UnbakedCuboidGeometry.bake(List, TextureSlots, ModelBaker, ModelState, ModelDebugName)} into
 * {@code new QuadCollection.Builder()}, {@code bake(builder, …, a material lookup)}, {@code builder.build()}: vanilla's loop,
 * and its {@code FaceBakery.bakeQuad} call, are in the overload. Create's {@code @WrapOperation} on {@code bakeQuad} (its
 * normals fix) bound the stub by its full descriptor, where nothing is to wrap, and never ran. MixinStubRebind does not take
 * such a stub: it returns the builder's product, not the overload's result, so a {@code HEAD} or {@code RETURN} of one is
 * not one of the other.
 *
 * <p>Kept exactly: the stub is nothing but the builder's construction ({@code new B()} into a local), loads, constants and
 * capturing lambdas as arguments, one call of a same-name overload of the same class and static-ness that returns nothing
 * and takes the builder, and the return of one no-argument call on the builder whose type is the stub's; every point is a
 * call, field or allocation the native method makes, the stub does not, and the overload makes as often; the handler takes
 * nothing of the target's but MixinExtras {@code @Local}s by type alone that the overload decides at every point
 * (MixinStubRebind's rule) — no target argument, no capture, no {@code @Share}; no slice, no callback group.
 * {@code -Dforbric.builderStubRebind=off} leaves every injector where it was written.
 */
public final class MixinBuilderStubRebind {
	public static final String PROPERTY = "forbric.builderStubRebind";
	private static final Set<String> CALL_POINTS = Set.of("INVOKE", "INVOKE_ASSIGN", "FIELD", "NEW");
	private static final Set<String> KINDS = Set.of("WrapOperation", "Redirect", "WrapWithCondition", "ModifyExpressionValue",
			"ModifyArg", "ModifyArgs", "Inject");

	private MixinBuilderStubRebind() {
	}

	public static int adapt(ClassNode mixin, Function<String, ClassNode> targets) {
		return adapt(mixin, targets, NativeGameReferences::reference);
	}

	static int adapt(ClassNode mixin, Function<String, ClassNode> targets, BiFunction<Ecosystem, String, ClassNode> references) {
		if ("off".equalsIgnoreCase(net.forbric.kernel.util.ForbricSwitches.get(PROPERTY, "on")) || mixin == null || mixin.methods == null
				|| targets == null || references == null) return 0;
		Ecosystem ecosystem = MixinStubRebind.ecosystemOf(mixin.name);
		List<String> owners = MixinOverloadPin.targetsOf(mixin);
		if (ecosystem == null || owners.size() != 1) return 0;
		ClassNode current = null, original = null;
		int moved = 0;
		for (MethodNode handler : mixin.methods) {
			AnnotationNode injector = MixinFit.injectorOf(handler);
			if (injector == null || MixinFit.value(injector, "slice") != null || MixinFit.groupOf(handler) != null) continue;
			List<AnnotationNode> points = MixinFit.atNodes(injector);
			if (points.isEmpty() || !points.stream().allMatch(MixinBuilderStubRebind::callPoint)) continue;
			if (MixinFit.stringList(MixinFit.value(injector, "method")).size() != 1) continue;
			if (current == null) {
				current = targets.apply(owners.getFirst());
				original = references.apply(ecosystem, owners.getFirst());
				if (current == null || original == null) return moved;
			}
			MethodNode stub = MixinTargetSelectors.one(handler, current), written = MixinTargetSelectors.one(handler, original);
			if (stub == null || written == null || !(stub.name + stub.desc).equals(written.name + written.desc)) continue;
			MethodNode body = builderDelegate(current, stub);
			if (body == null || !anchorsMoved(points, written, stub, body) || !handlerFits(handler, current, body, points)) continue;
			MixinPlayerWorldCallbackAdapter.set(injector, "method", new java.util.ArrayList<>(List.of(body.name + body.desc)));
			moved++;
			ForbricLog.info("[Forbric/Mixin] %s: %s now injects into %s.%s%s — a carrier kept %s%s as a builder stub that hands a builder to "
					+ "that overload, which makes every call the injector names", mixin.name.replace('/', '.'), handler.name,
					current.name.replace('/', '.'), body.name, body.desc, stub.name, stub.desc);
		}
		return moved;
	}

	private static boolean callPoint(AnnotationNode at) {
		return CALL_POINTS.contains(MixinFit.asString(MixinFit.value(at, "value"))) && MixinFit.value(at, "slice") == null;
	}

	/** Every point: made by the native method, not by the stub, and by the overload as often as natively. */
	private static boolean anchorsMoved(List<AnnotationNode> points, MethodNode written, MethodNode stub, MethodNode body) {
		for (AnnotationNode at : points) {
			List<AbstractInsnNode> before = MixinCallbackProofs.points(written, at), left = MixinCallbackProofs.points(stub, at),
					after = MixinCallbackProofs.points(body, at);
			if (before == null || left == null || after == null || before.isEmpty() || !left.isEmpty() || after.size() != before.size()) return false;
		}
		return true;
	}

	/** The handler takes the point's own values and, past them, only by-type {@code @Local}s the overload decides. */
	private static boolean handlerFits(MethodNode handler, ClassNode owner, MethodNode body, List<AnnotationNode> points) {
		MixinHandlerShape shape = MixinHandlerShape.of(handler);
		if (shape == null || !KINDS.contains(shape.kind())) return false;
		// An @Inject's own part is the CallbackInfo alone: target arguments in front of it are the stub's, not the body's.
		if ("Inject".equals(shape.kind()) && shape.operands().size() != 1) return false;
		for (MixinHandlerShape.Extra extra : shape.extras()) {
			if (extra.role() == MixinHandlerShape.Role.CANCELLABLE) continue;
			if (extra.role() != MixinHandlerShape.Role.LOCAL || !byTypeOnly(extra.sugar())) return false;
			if (!MixinStubRebind.theOnlyLocalOfItsType(owner, body, extra.type(), points)) return false;
		}
		return true;
	}

	private static boolean byTypeOnly(AnnotationNode local) {
		return local != null && MixinFit.value(local, "ordinal") == null && MixinFit.value(local, "index") == null
				&& MixinFit.value(local, "name") == null && !Boolean.TRUE.equals(MixinFit.value(local, "argsOnly"));
	}

	/**
	 * The overload {@code stub} hands a builder to, when {@code stub} is exactly: {@code new B()} stored in a local; loads,
	 * constants, static fields and lambda factories; one call of a same-name, same-static-ness overload of {@code owner}
	 * returning nothing that takes a {@code B}; then that local, one no-argument call on {@code B} of the stub's return type,
	 * and the return. Null otherwise.
	 */
	static MethodNode builderDelegate(ClassNode owner, MethodNode stub) {
		if (stub.instructions == null || (stub.access & (Opcodes.ACC_BRIDGE | Opcodes.ACC_SYNTHETIC | Opcodes.ACC_ABSTRACT)) != 0) return null;
		List<AbstractInsnNode> code = java.util.Arrays.stream(stub.instructions.toArray()).filter(i -> i.getOpcode() >= 0).toList();
		if (code.size() < 8) return null;
		if (!(code.get(0) instanceof TypeInsnNode created) || created.getOpcode() != Opcodes.NEW || code.get(1).getOpcode() != Opcodes.DUP
				|| !(code.get(2) instanceof MethodInsnNode init) || init.getOpcode() != Opcodes.INVOKESPECIAL || !init.owner.equals(created.desc)
				|| !init.name.equals("<init>") || !init.desc.equals("()V") || !(code.get(3) instanceof VarInsnNode stored)
				|| stored.getOpcode() != Opcodes.ASTORE) return null;
		int builder = stored.var, n = code.size();
		if (code.get(n - 1).getOpcode() != Opcodes.ARETURN || !(code.get(n - 2) instanceof MethodInsnNode build)
				|| build.getOpcode() != Opcodes.INVOKEVIRTUAL || !build.owner.equals(created.desc) || !build.desc.startsWith("()")
				|| !Type.getReturnType(build.desc).equals(Type.getReturnType(stub.desc)) || !(code.get(n - 3) instanceof VarInsnNode reloaded)
				|| reloaded.getOpcode() != Opcodes.ALOAD || reloaded.var != builder || !(code.get(n - 4) instanceof MethodInsnNode call)) return null;
		boolean isStatic = (stub.access & Opcodes.ACC_STATIC) != 0;
		if (!call.owner.equals(owner.name) || !call.name.equals(stub.name) || call.desc.equals(stub.desc)
				|| (call.getOpcode() == Opcodes.INVOKESTATIC) != isStatic || Type.getReturnType(call.desc).getSort() != Type.VOID
				|| java.util.Arrays.stream(Type.getArgumentTypes(call.desc)).noneMatch(t -> t.getSort() == Type.OBJECT && t.getInternalName().equals(created.desc)))
			return null;
		boolean builderPassed = false;
		for (AbstractInsnNode instruction : code.subList(4, n - 4)) {
			int opcode = instruction.getOpcode();
			if (instruction instanceof VarInsnNode load && opcode >= Opcodes.ILOAD && opcode <= Opcodes.ALOAD) {
				builderPassed |= load.var == builder && opcode == Opcodes.ALOAD;
				continue;
			}
			if (instruction instanceof LdcInsnNode || opcode >= Opcodes.ACONST_NULL && opcode <= Opcodes.SIPUSH) continue;
			if (instruction instanceof FieldInsnNode field && field.getOpcode() == Opcodes.GETSTATIC) continue;
			if (instruction instanceof InvokeDynamicInsnNode lambda && "java/lang/invoke/LambdaMetafactory".equals(lambda.bsm.getOwner())) continue;
			return null;
		}
		if (!builderPassed) return null;
		for (MethodNode candidate : owner.methods) {
			if (candidate.name.equals(call.name) && candidate.desc.equals(call.desc) && candidate.instructions != null
					&& candidate.instructions.size() > 0 && ((candidate.access ^ stub.access) & Opcodes.ACC_STATIC) == 0) return candidate;
		}
		return null;
	}
}
