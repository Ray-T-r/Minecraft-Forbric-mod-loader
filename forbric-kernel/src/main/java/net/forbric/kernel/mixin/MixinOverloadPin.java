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

import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;

import net.forbric.kernel.util.ForbricLog;

/**
 * Pins a name-only {@code @Inject} selector to the overload the handler was actually written for.
 *
 * <p>A mixin usually names its target by NAME alone — {@code @Inject(method = "lambda$addSkyPass$0")} — because
 * on the loader it was built for there is only one method with that name. The byte merge routinely produces two:
 * each ecosystem patched the same vanilla method differently, and the merge kept both declarations side by side.
 * {@code LevelRenderer} carries {@code lambda$addSkyPass$0(GpuBufferSlice, SkyRenderState)} — vanilla's, the one
 * Apoli's sky-skip is written against — AND NeoForge's {@code (SkyRenderState, Matrix4fc, GpuBufferSlice)}.
 *
 * <p>Mixin resolves such a selector to the FIRST match and then checks the handler against it. When that is the
 * other ecosystem's overload the descriptors do not agree, and the result is not "try the sibling" — it is
 * {@code InvalidInjectionException}, which fails the whole mixin class and takes every other injection in it
 * with it. The target the mod wanted is right there, one entry further down the list.
 *
 * <p>So the selector is made explicit: {@code name(descriptor)returnType}, chosen by matching the handler's own
 * parameters. Nothing is invented — the descriptor written in is one the target class really declares — and the
 * choice is only made when exactly ONE overload fits. Zero fits or two fits and the selector is left exactly as
 * the mod wrote it, because a wrong pin would be worse than the exception: the injection would apply, silently,
 * to the wrong method.
 *
 * <p>Only {@code @Inject}. Its handler-compatibility rule is the one that can be checked here — the target's
 * parameters, then a {@code CallbackInfo}, then whatever locals the handler captures. The other injectors
 * ({@code @Redirect}, {@code @ModifyArg}, the MixinExtras ones) each have a different and less mechanical rule,
 * and guessing at one of those is exactly the wrong pin this declines to make.
 *
 * <p>{@code -Dforbric.mixinOverloadPin=off} leaves every selector alone.
 */
public final class MixinOverloadPin {
	static final String PROPERTY = "forbric.mixinOverloadPin";

	private static final String INJECT_DESC = "Lorg/spongepowered/asm/mixin/injection/Inject;";
	private static final String MIXIN_DESC = "Lorg/spongepowered/asm/mixin/Mixin;";
	private static final String CALLBACK_INFO = "org/spongepowered/asm/mixin/injection/callback/CallbackInfo";
	private static final String CALLBACK_INFO_RETURNABLE =
			"org/spongepowered/asm/mixin/injection/callback/CallbackInfoReturnable";

	/**
	 * Mixin class → why one of its injections cannot bind.
	 *
	 * <p>Kept because the diagnosis is made when the mixin is READ and the mod is marked when it fails to APPLY,
	 * which are two different moments and two different classes. Without this the log would carry the reason and
	 * the load report — the thing a player actually reads — would still say "InvalidInjectionException".
	 */
	private static final java.util.Map<String, String> REASONS = new java.util.concurrent.ConcurrentHashMap<>();

	/** What the kernel worked out about {@code mixinClass}, in binary form, or null. */
	public static String reasonFor(String mixinClass) {
		return mixinClass == null ? null : REASONS.get(mixinClass.replace('.', '/'));
	}

	private MixinOverloadPin() {
	}

	public static boolean enabled() {
		return !"off".equalsIgnoreCase(System.getProperty(PROPERTY, "on"));
	}

	/**
	 * Rewrites every name-only {@code @Inject} selector in {@code mixin} that has exactly one fitting overload.
	 *
	 * @param targets resolves a target class's internal name to its node, or null when it cannot be read; a
	 *                target that cannot be read simply leaves its selectors alone
	 * @return how many selectors were pinned
	 */
	public static int pin(ClassNode mixin, Function<String, ClassNode> targets) {
		if (!enabled() || mixin == null || mixin.methods == null || targets == null) return 0;

		List<String> targetNames = targetsOf(mixin);
		if (targetNames.isEmpty()) return 0;

		int pinned = 0;
		for (MethodNode method : mixin.methods) {
			AnnotationNode inject = annotation(method, INJECT_DESC);
			if (inject == null) continue;
			pinned += pinSelectors(mixin, method, inject, targetNames, targets);
		}
		return pinned;
	}

	@SuppressWarnings("unchecked")
	private static int pinSelectors(ClassNode mixin, MethodNode handler, AnnotationNode inject,
			List<String> targetNames, Function<String, ClassNode> targets) {
		List<Object> values = inject.values;
		if (values == null) return 0;

		int explained = 0;
		for (int i = 0; i + 1 < values.size(); i += 2) {
			if (!"method".equals(values.get(i)) || !(values.get(i + 1) instanceof List<?> raw)) continue;
			for (Object entry : raw) {
				if (!(entry instanceof String selector) || selector.indexOf('(') >= 0) continue;
				if (explain(mixin, handler, selector, targetNames, targets)) explained++;
			}
		}
		return explained;
	}

	/**
	 * Logs why {@code selector} cannot bind, when the reason is a lambda chain the merge dropped.
	 *
	 * <p>Says nothing at all in every other case — including a selector that binds perfectly well, which is
	 * almost all of them. The conditions are deliberately all-or-nothing: the class must declare that name with a
	 * shape the handler does NOT fit, and the pruner must have dropped a same-named body the handler DOES fit.
	 * Anything less and the diagnosis would be a guess wearing a fact's clothes.
	 *
	 * @return whether anything was said
	 */
	static boolean explain(ClassNode mixin, MethodNode handler, String selector, List<String> targetNames,
			Function<String, ClassNode> targets) {
		for (String targetName : targetNames) {
			ClassNode target = targets.apply(targetName);
			if (target == null || target.methods == null) continue;

			boolean namePresent = false;
			for (MethodNode method : target.methods) {
				if (!method.name.equals(selector)) continue;
				namePresent = true;
				// It binds; there is nothing to explain.
				if (fits(handler.desc, method.desc)) return false;
			}
			if (!namePresent) continue;

			for (String dropped : net.forbric.kernel.transform.DuplicateLambdaPruneInjector
					.droppedDescriptors(targetName, selector)) {
				if (!fits(handler.desc, dropped)) continue;
				ForbricLog.warn("[Forbric/Mixin] %s.%s cannot bind to %s.%s: the shape it was written for, %s, was "
						+ "a lambda of the body the byte merge did NOT keep — the merge took the other ecosystem's "
						+ "%s, whose lambda has a different shape, so this injection has no live target here",
						mixin.name, handler.name, targetName, selector, dropped, enclosing(selector));
				REASONS.put(mixin.name, "its " + handler.name + " targets " + selector + ", a lambda of the "
						+ enclosing(selector) + " body the byte merge did not keep");
				return true;
			}
		}
		return false;
	}

	/** The method a {@code lambda$foo$0} belongs to, for a sentence a reader can act on. */
	static String enclosing(String lambdaName) {
		if (!lambdaName.startsWith("lambda$")) return lambdaName;
		int last = lambdaName.lastIndexOf('$');
		return last > "lambda$".length() ? lambdaName.substring("lambda$".length(), last) : lambdaName;
	}

	/**
	 * Whether an {@code @Inject} handler of {@code handlerDesc} is the one written for {@code targetDesc}.
	 *
	 * <p>The rule Mixin itself applies, in the shape that can be decided from descriptors alone: the target's
	 * parameters in order, then a {@code CallbackInfo} (or {@code CallbackInfoReturnable}), then any number of
	 * captured locals. Deliberately strict — Mixin also accepts a handler taking only a PREFIX of the target's
	 * parameters, and honouring that here would let a no-argument handler fit every overload at once, which is
	 * the ambiguity this whole class exists to avoid.
	 */
	static boolean fits(String handlerDesc, String targetDesc) {
		Type[] handlerParams = Type.getArgumentTypes(handlerDesc);
		Type[] targetParams = Type.getArgumentTypes(targetDesc);
		if (handlerParams.length < targetParams.length + 1) return false;
		for (int i = 0; i < targetParams.length; i++) {
			if (!handlerParams[i].equals(targetParams[i])) return false;
		}
		String callback = handlerParams[targetParams.length].getInternalName();
		return CALLBACK_INFO.equals(callback) || CALLBACK_INFO_RETURNABLE.equals(callback);
	}

	/** The internal names the {@code @Mixin} annotation points at, from both {@code value} and {@code targets}. */
	static List<String> targetsOf(ClassNode mixin) {
		List<String> names = new ArrayList<>();
		AnnotationNode annotation = annotation(mixin.visibleAnnotations, MIXIN_DESC);
		if (annotation == null) annotation = annotation(mixin.invisibleAnnotations, MIXIN_DESC);
		if (annotation == null || annotation.values == null) return names;

		for (int i = 0; i + 1 < annotation.values.size(); i += 2) {
			Object key = annotation.values.get(i);
			Object value = annotation.values.get(i + 1);
			if (!(value instanceof List<?> entries)) continue;
			for (Object entry : entries) {
				if ("value".equals(key) && entry instanceof Type type) names.add(type.getInternalName());
				else if ("targets".equals(key) && entry instanceof String binary) names.add(binary.replace('.', '/'));
			}
		}
		return names;
	}

	private static AnnotationNode annotation(MethodNode method, String descriptor) {
		AnnotationNode found = annotation(method.visibleAnnotations, descriptor);
		return found != null ? found : annotation(method.invisibleAnnotations, descriptor);
	}

	private static AnnotationNode annotation(List<AnnotationNode> annotations, String descriptor) {
		if (annotations == null) return null;
		for (AnnotationNode annotation : annotations) {
			if (descriptor.equals(annotation.desc)) return annotation;
		}
		return null;
	}
}
