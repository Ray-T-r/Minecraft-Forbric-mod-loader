/*
 * Copyright 2026 The Forbric Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package net.forbric.kernel.mixin;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

import net.forbric.kernel.util.ForbricLog;

/**
 * Gives an {@code @Inject} handler the shape the merged target actually has, by wrapping it.
 *
 * <p>The last shape of the same problem the rest of this package is about, and the one nothing else could reach.
 * {@code LevelRenderer.addSkyPass}'s lambda is {@code (GpuBufferSlice, SkyRenderState)} and STATIC in vanilla and
 * on MinecraftForge's base; NeoForge's is {@code (SkyRenderState, Matrix4fc, GpuBufferSlice)} and an instance
 * method, and the merge kept NeoForge's body, so the duplicate-lambda pruner dropped the other. Apoli's
 * phasing-blindness sky-skip anchors on {@code SkyRenderer.renderSkyDisc}, which IS in the surviving lambda —
 * the injection point exists, the injection point is reachable, and the only thing wrong is that the handler
 * takes its two arguments in the other order, without the third, and from a static context.
 *
 * <p>So the handler is renamed aside and a new one, shaped like the target, is put in its place carrying the
 * original annotation. It loads the arguments the inner handler wants, in the order it wants them, and calls it.
 * Nothing about the mod's own code changes; it is handed the same values it was written to receive.
 *
 * <h2>When it fires, which is the whole safety of this</h2>
 *
 * <p>Only on EVIDENCE, never on a rule about what Mixin will accept. Two earlier versions tried the rule — one
 * strict, one "prefix" — and each wrapped eighteen handlers on one pack that were binding perfectly well and
 * producing not a single warning between them. Mixin's acceptance is looser than either; a wrap that is not
 * needed is not a repair, it is churn in the middle of every mod's injections.
 *
 * <p>The evidence is {@link net.forbric.kernel.transform.DuplicateLambdaPruneInjector}'s record. The handler must
 * fit EXACTLY a descriptor that pruner removed from this very class, and must not fit the one that survived. That
 * is not an inference about Mixin: it is the fact that the shape the mod was written for was in this class until
 * the merge chose the other ecosystem's body of the enclosing method.
 *
 * <h2>The mapping guard</h2>
 *
 * <p>Every parameter of the inner handler must occur EXACTLY ONCE among the target's parameters. Zero and there
 * is nothing to hand it; twice and the choice is a coin toss — and a coin toss here is not an exception, it is an
 * injection silently receiving the wrong object, which is the worst outcome this package can produce. Both are
 * refused, and the mixin fails exactly as it did before.
 *
 * <p>Refused too: a handler that captures locals after its {@code CallbackInfo} (the locals are the merged
 * method's, not the one it was written against, so a mapping by type would be inventing them), a name-only
 * selector that names more than one method (which target?), and a static target with an instance handler (there
 * is no receiver to call it on).
 *
 * <p>{@code -Dforbric.mixinHandlerShim=off} wraps nothing.
 */
public final class MixinHandlerShim {
	static final String PROPERTY = "forbric.mixinHandlerShim";

	/** The suffix the original handler is moved to. Distinctive, so a second pass recognises its own work. */
	static final String INNER_SUFFIX = "$forbricshim";

	private static final String INJECT_DESC = "Lorg/spongepowered/asm/mixin/injection/Inject;";
	private static final String CALLBACK_INFO = "org/spongepowered/asm/mixin/injection/callback/CallbackInfo";
	private static final String CALLBACK_INFO_RETURNABLE =
			"org/spongepowered/asm/mixin/injection/callback/CallbackInfoReturnable";

	private MixinHandlerShim() {
	}

	public static boolean enabled() {
		return !"off".equalsIgnoreCase(System.getProperty(PROPERTY, "on"));
	}

	/**
	 * Wraps every {@code @Inject} handler in {@code mixin} whose only target has a shape it can be handed.
	 *
	 * @return how many handlers were wrapped
	 */
	public static int adapt(ClassNode mixin, Function<String, ClassNode> targets) {
		if (!enabled() || mixin == null || mixin.methods == null || targets == null) return 0;
		List<String> targetNames = MixinOverloadPin.targetsOf(mixin);
		if (targetNames.isEmpty()) return 0;

		List<MethodNode> added = new ArrayList<>();
		int wrapped = 0;
		for (MethodNode handler : new ArrayList<>(mixin.methods)) {
			if (handler.name.endsWith(INNER_SUFFIX)) continue;
			AnnotationNode inject = injectOf(handler);
			if (inject == null) continue;
			String selector = onlyNameSelector(inject);
			if (selector == null) continue;

			MethodNode target = onlyTarget(selector, targetNames, targets);
			if (target == null) continue;
			if (!wasWrittenForAShapeTheMergeDropped(handler, selector, target, targetNames, targets)) continue;

			MethodNode outer = wrap(mixin, handler, inject, target);
			if (outer == null) continue;
			added.add(outer);
			wrapped++;
			ForbricLog.warn("[Forbric/Mixin] %s.%s was written for a differently shaped %s — the merge kept the "
					+ "other ecosystem's, which takes %s. Its handler is wrapped so it still receives the values it "
					+ "asked for, in the order it asked for them", mixin.name, handler.name, selector, target.desc);
		}
		mixin.methods.addAll(added);
		return wrapped;
	}

	/**
	 * Builds the outer handler and turns {@code handler} into its inner one, or null when it cannot be done.
	 *
	 * <p>Mutates {@code handler} only on success: it is renamed and loses the annotation, which the outer takes.
	 */
	private static MethodNode wrap(ClassNode mixin, MethodNode handler, AnnotationNode inject, MethodNode target) {
		Type[] wanted = Type.getArgumentTypes(handler.desc);
		Type[] available = Type.getArgumentTypes(target.desc);
		if (wanted.length == 0) return null;

		// Everything before the callback is a value to be handed over; the callback itself is the last parameter.
		String callback = wanted[wanted.length - 1].getInternalName();
		if (!CALLBACK_INFO.equals(callback) && !CALLBACK_INFO_RETURNABLE.equals(callback)) return null;

		boolean targetStatic = (target.access & Opcodes.ACC_STATIC) != 0;
		boolean handlerStatic = (handler.access & Opcodes.ACC_STATIC) != 0;
		if (targetStatic && !handlerStatic) return null;    // no receiver to call the inner one on

		int[] mapping = new int[wanted.length - 1];
		for (int i = 0; i < mapping.length; i++) {
			int found = -1;
			for (int j = 0; j < available.length; j++) {
				if (!available[j].equals(wanted[i])) continue;
				if (found >= 0) return null;    // twice: a coin toss, and a coin toss hands over the wrong object
				found = j;
			}
			if (found < 0) return null;
			mapping[i] = found;
		}

		String innerName = handler.name + INNER_SUFFIX;
		String outerDesc = Type.getMethodDescriptor(Type.VOID_TYPE, withCallback(available, wanted[wanted.length - 1]));
		MethodNode outer = new MethodNode(Opcodes.ASM9,
				(handler.access & ~Opcodes.ACC_STATIC) | (targetStatic ? Opcodes.ACC_STATIC : 0),
				handler.name, outerDesc, null, null);
		outer.visibleAnnotations = new ArrayList<>(List.of(inject));

		int[] slots = slotsOf(available, targetStatic);
		int callbackSlot = slots[available.length];
		int stack = 0;
		if (!handlerStatic) {
			outer.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
			stack++;
		}
		for (int i = 0; i < mapping.length; i++) {
			Type type = available[mapping[i]];
			outer.instructions.add(new VarInsnNode(type.getOpcode(Opcodes.ILOAD), slots[mapping[i]]));
			stack += type.getSize();
		}
		outer.instructions.add(new VarInsnNode(Opcodes.ALOAD, callbackSlot));
		stack++;
		outer.instructions.add(new MethodInsnNode(
				handlerStatic ? Opcodes.INVOKESTATIC : Opcodes.INVOKESPECIAL,
				mixin.name, innerName, handler.desc, false));
		outer.instructions.add(new InsnNode(Opcodes.RETURN));
		outer.maxStack = stack;
		outer.maxLocals = callbackSlot + 1;

		// Only now is the original touched: renamed, and stripped of the annotation the outer has taken over.
		handler.name = innerName;
		handler.visibleAnnotations = withoutInject(handler.visibleAnnotations);
		handler.invisibleAnnotations = withoutInject(handler.invisibleAnnotations);
		return outer;
	}

	private static Type[] withCallback(Type[] params, Type callback) {
		Type[] out = new Type[params.length + 1];
		System.arraycopy(params, 0, out, 0, params.length);
		out[params.length] = callback;
		return out;
	}

	/** Local slot of each parameter, plus one past the end for the callback; longs and doubles take two. */
	private static int[] slotsOf(Type[] params, boolean isStatic) {
		int[] slots = new int[params.length + 1];
		int slot = isStatic ? 0 : 1;
		for (int i = 0; i < params.length; i++) {
			slots[i] = slot;
			slot += params[i].getSize();
		}
		slots[params.length] = slot;
		return slots;
	}

	/** The one method {@code selector} names across the targets, or null when there is not exactly one. */
	private static MethodNode onlyTarget(String selector, List<String> targetNames,
			Function<String, ClassNode> targets) {
		MethodNode only = null;
		for (String targetName : targetNames) {
			ClassNode target = targets.apply(targetName);
			if (target == null || target.methods == null) continue;
			for (MethodNode method : target.methods) {
				if (!method.name.equals(selector)) continue;
				if (only != null) return null;
				only = method;
			}
		}
		return only;
	}

	/** The selector when the annotation carries exactly one, written as a bare name. */
	private static String onlyNameSelector(AnnotationNode inject) {
		if (inject.values == null) return null;
		for (int i = 0; i + 1 < inject.values.size(); i += 2) {
			if (!"method".equals(inject.values.get(i)) || !(inject.values.get(i + 1) instanceof List<?> raw)) continue;
			if (raw.size() != 1 || !(raw.get(0) instanceof String selector)) return null;
			return selector.indexOf('(') >= 0 ? null : selector;
		}
		return null;
	}

	/**
	 * Whether the handler fits a shape the pruner removed from the class the selector names, and not the one left.
	 *
	 * <p>Both halves. "Fits what was dropped" alone would wrap a handler that also binds to what survived, which
	 * needs nothing; "does not fit what survived" alone is every handler Mixin accepts by its own looser rules,
	 * which is the mistake this replaced.
	 */
	private static boolean wasWrittenForAShapeTheMergeDropped(MethodNode handler, String selector, MethodNode target,
			List<String> targetNames, Function<String, ClassNode> targets) {
		if (MixinOverloadPin.fits(handler.desc, target.desc)) return false;
		for (String targetName : targetNames) {
			ClassNode node = targets.apply(targetName);
			if (node == null) continue;
			for (String dropped : net.forbric.kernel.transform.DuplicateLambdaPruneInjector
					.droppedDescriptors(targetName, selector)) {
				if (MixinOverloadPin.fits(handler.desc, dropped)) return true;
			}
		}
		return false;
	}

	/**
	 * Whether Mixin would accept {@code handlerDesc} for {@code targetDesc} as written.
	 *
	 * <p>Mixin's own rule, not a stricter one and not a looser one: the parameters before the
	 * {@code CallbackInfo} are either ALL of the target's, in order, or NONE. Two earlier versions of this test
	 * got it wrong in both directions — a stricter one wrapped handlers that were binding perfectly well
	 * (eighteen of them on one pack, which is not a repair but churn in the middle of every mod's injections),
	 * and a "prefix" one did the same more quietly. Measured: with the rule below, that pack wraps what actually
	 * fails and nothing else.
	 */
	static boolean binds(String handlerDesc, String targetDesc) {
		Type[] handlerParams = Type.getArgumentTypes(handlerDesc);
		Type[] targetParams = Type.getArgumentTypes(targetDesc);
		int callback = -1;
		for (int i = 0; i < handlerParams.length; i++) {
			String internal = handlerParams[i].getSort() == Type.OBJECT ? handlerParams[i].getInternalName() : "";
			if (CALLBACK_INFO.equals(internal) || CALLBACK_INFO_RETURNABLE.equals(internal)) { callback = i; break; }
		}
		if (callback < 0) return false;
		if (callback == 0) return true;                       // a handler that asks for nothing always binds
		if (callback != targetParams.length) return false;    // otherwise it must ask for all of them
		for (int i = 0; i < callback; i++) {
			if (!handlerParams[i].equals(targetParams[i])) return false;
		}
		return true;
	}

	private static boolean staticnessAgrees(MethodNode handler, MethodNode target) {
		return ((handler.access & Opcodes.ACC_STATIC) != 0) == ((target.access & Opcodes.ACC_STATIC) != 0);
	}

	private static AnnotationNode injectOf(MethodNode method) {
		AnnotationNode found = find(method.visibleAnnotations);
		return found != null ? found : find(method.invisibleAnnotations);
	}

	private static AnnotationNode find(List<AnnotationNode> annotations) {
		if (annotations == null) return null;
		for (AnnotationNode annotation : annotations) {
			if (INJECT_DESC.equals(annotation.desc)) return annotation;
		}
		return null;
	}

	private static List<AnnotationNode> withoutInject(List<AnnotationNode> annotations) {
		if (annotations == null) return null;
		List<AnnotationNode> kept = new ArrayList<>();
		for (AnnotationNode annotation : annotations) {
			if (!INJECT_DESC.equals(annotation.desc)) kept.add(annotation);
		}
		return kept;
	}
}
