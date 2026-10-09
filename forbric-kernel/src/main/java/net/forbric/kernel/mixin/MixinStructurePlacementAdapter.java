/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.mixin;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.IntUnaryOperator;

import org.objectweb.asm.Type;
import org.objectweb.asm.tree.*;

import net.forbric.api.Ecosystem;

/**
 * Moves a mod's callbacks on vanilla's {@code StructureTemplate.placeEntities} to the method the merged game calls in its
 * place.
 *
 * <p>NeoForge's {@code placeInWorld} calls {@code addEntitiesToWorld(level, pos, settings, reporter)} where vanilla's called
 * {@code placeEntities(level, pos, mirror, rotation, pivot, box, finalize, reporter)}, and nothing in the merged game calls
 * vanilla's method any more. Each injector written for it is moved on its own, whatever else the mixin holds:
 * <ul>
 * <li>an {@code @Inject} at the call in {@code placeInWorld}: its point names the call the merged method makes there;
 * <li>an injector inside {@code placeEntities} — at a call, field access or allocation, or the method's head or tail —
 * whose handler does not take {@code placeEntities}' own parameters ({@code @WrapOperation} and the other call-driven
 * injectors take the call's operands; an {@code @Inject} only its {@code CallbackInfo}): it selects
 * {@code addEntitiesToWorld}, where each of its points is found as often as vanilla's body had it.
 * </ul>
 * The replacement is proved, not assumed: exactly one merged method calls {@code addEntitiesToWorld}, and where the class
 * the mod was compiled against is at hand, that method calls vanilla's once in it. Which method a handler was written
 * for is read off that class (or, without it, off a selector that spells the descriptor). A {@code @Local} the handler
 * asks for moves only to the slot proved to hold what the native slot held ({@link MixinCallbackProofs#correspondLocals});
 * without the native class only an {@code argsOnly} parameter of a type each method has once is read the same way.
 * Two handlers of one kind at the same point are left as they are.
 */
public final class MixinStructurePlacementAdapter {
	public static final String PROPERTY = "forbric.structurePlacementCallbacks";
	static final String TARGET = "net/minecraft/world/level/levelgen/structure/templatesystem/StructureTemplate";
	static final String OLD = "placeEntities(Lnet/minecraft/world/level/ServerLevelAccessor;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/Mirror;Lnet/minecraft/world/level/block/Rotation;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/levelgen/structure/BoundingBox;ZLnet/minecraft/util/ProblemReporter;)V";
	static final String LIVE = "addEntitiesToWorld(Lnet/minecraft/world/level/ServerLevelAccessor;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/levelgen/structure/templatesystem/StructurePlaceSettings;Lnet/minecraft/util/ProblemReporter;)V";
	private static final String CALLBACK = "Lorg/spongepowered/asm/mixin/injection/callback/CallbackInfo;";
	private static final Set<String> BODY = Set.of("Inject", "WrapOperation", "Redirect", "ModifyArg", "ModifyExpressionValue", "WrapWithCondition");
	private static final Set<MixinHandlerShape.Role> EXTRAS = Set.of(MixinHandlerShape.Role.LOCAL, MixinHandlerShape.Role.SHARE, MixinHandlerShape.Role.CANCELLABLE);

	private MixinStructurePlacementAdapter() { }

	public static int adapt(ClassNode mixin, Function<String, ClassNode> targets) {
		return adapt(mixin, targets, NativeGameReferences::reference);
	}

	/**
	 * {@code references} gives the class the mod was compiled against: the merged game no longer declares the native
	 * {@code placeEntities}, so which method a bare name or pattern bound there is read off the native class
	 * ({@link MixinTargetSelectors#nativeMember}); without it only a selector spelling that descriptor names it.
	 */
	static int adapt(ClassNode mixin, Function<String, ClassNode> targets, BiFunction<Ecosystem, String, ClassNode> references) {
		if (!MixinCallbackShape.targets(mixin, TARGET) || "off".equalsIgnoreCase(net.forbric.kernel.util.ForbricSwitches.get(PROPERTY))) return 0;
		ClassNode target = targets.apply(TARGET), source = references == null ? null : references.apply(MixinStubRebind.ecosystemOf(mixin.name), TARGET);
		MethodNode place = target == null ? null : MixinPlayerWorldCallbackAdapter.selector(target, LIVE);
		if (place == null) return 0;
		// The one merged call of addEntitiesToWorld is the replacement; natively, its method called placeEntities once.
		MethodNode caller = null;
		MethodInsnNode liveCall = null;
		int calls = 0;
		for (MethodNode method : target.methods) for (AbstractInsnNode instruction : method.instructions)
			if (instruction instanceof MethodInsnNode call && MixinPlayerWorldCallbackAdapter.member(call).equals("L" + TARGET + ";" + LIVE)) {
				calls++;
				caller = method;
				liveCall = call;
			}
		if (calls != 1) return 0;
		MethodNode nativeCaller = source == null ? null : MixinPlayerWorldCallbackAdapter.selector(source, caller.name + caller.desc);
		if (source != null && (nativeCaller == null || MixinPlayerWorldCallbackAdapter.count(nativeCaller, "L" + TARGET + ";" + OLD) != 1)) return 0;
		MethodInsnNode nativeCall = nativeCaller == null ? null : MixinPlayerWorldCallbackAdapter.first(nativeCaller, "L" + TARGET + ";" + OLD);
		MethodNode nativeBody = source == null ? null : MixinPlayerWorldCallbackAdapter.selector(source, OLD);
		int[] arguments = nativeCall == null ? null
				: MixinCallbackProofs.argumentCorrespondence(source.name, nativeCaller, nativeCall, target.name, caller, liveCall);
		IntUnaryOperator nativeParameter = arguments == null ? null : j -> arguments[j];

		List<Runnable> moves = new ArrayList<>();
		int changed = 0;
		String callerMember = caller.name + caller.desc;
		final MethodNode host = caller;
		final MethodInsnNode hostCall = liveCall;
		MethodNode pick = MixinCallbackShape.unique(mixin, m -> MixinCallbackShape.kind(m, "Inject") && pointsAtTheCall(m)
				&& MixinCallbackShape.binds(m, target, callerMember));
		if (pick != null && callerMember.equals(MixinTargetSelectors.nativeMember(pick, source, TARGET))) {
			MixinHandlerShape shape = MixinHandlerShape.of(pick);
			String callback = Type.getReturnType(caller.desc).equals(Type.VOID_TYPE) ? CALLBACK : MixinHandlerShape.RETURNABLE;
			Map<Integer, Integer> locals = shape.locals().isEmpty() ? Map.of() : nativeCall == null ? null
					: MixinCallbackProofs.correspondLocals(pick, source.name, nativeCaller, nativeCall, target.name, caller, liveCall, IntUnaryOperator.identity());
			String parameters = caller.desc.substring(1, caller.desc.indexOf(')'));
			if ((shape.operands("(" + callback + ")V") || shape.operands("(" + parameters + callback + ")V")) && extrasServed(shape) && locals != null) {
				AnnotationNode at = MixinFit.atNodes(MixinFit.injectorOf(pick)).getFirst();
				boolean moved = ("L" + TARGET + ";" + OLD).equals(MixinFit.value(at, "target"));
				if (moved) changed++;
				moves.add(() -> {
					if (moved) MixinPlayerWorldCallbackAdapter.set(at, "target", "L" + TARGET + ";" + LIVE);
					MixinCallbackProofs.pinLocals(pick, host, hostCall, locals);
				});
			}
		}
		IntUnaryOperator bodyParameter = nativeParameter == null ? j -> -1 : nativeParameter;
		for (MethodNode handler : MixinCallbackProofs.alone(mixin, m -> body(m) && authoredFor(m, source))) {
			MixinHandlerShape shape = MixinHandlerShape.of(handler);
			if (shape.kind().equals("Inject") && !shape.operands("(" + CALLBACK + ")V")) continue;
			MixinCallbackProofs.Landing landing = MixinCallbackProofs.land(handler, source == null ? null : source.name, nativeBody, target.name, place,
					nativeBody == null ? null : bodyParameter, OLD.substring(OLD.indexOf('(')));
			if (landing == null) continue;
			changed++;
			moves.add(() -> {
				MixinPlayerWorldCallbackAdapter.set(MixinFit.injectorOf(handler), "method", List.of(LIVE));
				landing.pin(handler, place);
			});
		}
		moves.forEach(Runnable::run);
		return changed;
	}

	/**
	 * The mixin as this adapter will hand it to Mixin, for judging whether it fits before Mixin loads it
	 * ({@code KernelGuestMixinAdapter}): a mixin whose only callbacks are on vanilla's {@code placeEntities} binds nothing
	 * in the merged class as compiled, and would otherwise be left out as dead weight before this adapter ever saw it.
	 */
	public static byte[] asLoaded(byte[] bytes, Function<String, byte[]> resource) {
		try {
			ClassNode mixin = new ClassNode();
			new org.objectweb.asm.ClassReader(bytes).accept(mixin, 0);
			if (!MixinCallbackShape.targets(mixin, TARGET)) return bytes;
			Function<String, ClassNode> targets = name -> {
				byte[] found = resource.apply(name + ".class");
				if (found == null) return null;
				ClassNode node = new ClassNode();
				new org.objectweb.asm.ClassReader(found).accept(node, 0);
				return node;
			};
			if (adapt(mixin, targets) == 0) return bytes;
			org.objectweb.asm.ClassWriter out = new org.objectweb.asm.ClassWriter(0);
			mixin.accept(out);
			return out.toByteArray();
		} catch (RuntimeException unreadable) {
			return bytes;
		}
	}

	/** One {@code @At(INVOKE)} at vanilla's call of {@code placeEntities}, or at the merged call it was already moved to. */
	private static boolean pointsAtTheCall(MethodNode handler) {
		List<AnnotationNode> ats = MixinFit.atNodes(MixinFit.injectorOf(handler));
		if (ats.size() != 1) return false;
		AnnotationNode at = ats.getFirst();
		Object target = MixinFit.value(at, "target"), ordinal = MixinFit.value(at, "ordinal");
		return "INVOKE".equals(MixinFit.asString(MixinFit.value(at, "value")))
				&& (("L" + TARGET + ";" + OLD).equals(target) || ("L" + TARGET + ";" + LIVE).equals(target))
				&& MixinFit.value(at, "by") == null && MixinFit.value(at, "opcode") == null && MixinFit.value(at, "args") == null
				&& (ordinal == null || ordinal instanceof Number n && n.intValue() <= 0);
	}

	/** The handler's extras are ones a moved handler still receives: proved {@code @Local}s, {@code @Share}, {@code @Cancellable}. */
	private static boolean extrasServed(MixinHandlerShape shape) {
		return shape.extras().stream().allMatch(extra -> EXTRAS.contains(extra.role()));
	}

	/** An injector kind this adapter moves into {@code addEntitiesToWorld}, written as Mixin reads it (no slice, group or dynamic target). */
	private static boolean body(MethodNode handler) {
		AnnotationNode injector = MixinFit.injectorOf(handler);
		if (injector == null) return false;
		String kind = injector.desc.substring(injector.desc.lastIndexOf('/') + 1, injector.desc.length() - 1);
		return BODY.contains(kind) && MixinCallbackShape.kind(handler, kind);
	}

	/** Whether the handler was written for the native {@code placeEntities}, the method the merged game no longer calls. */
	private static boolean authoredFor(MethodNode handler, ClassNode source) {
		return OLD.equals(MixinTargetSelectors.nativeMember(handler, source, TARGET));
	}
}
