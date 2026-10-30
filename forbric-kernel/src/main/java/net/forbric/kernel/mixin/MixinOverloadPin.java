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
import org.objectweb.asm.tree.MethodNode;

import net.forbric.kernel.util.ForbricLog;

/**
 * Pins a name-only {@code @Inject} selector to the overload the handler was actually written for, and says why a
 * selector cannot bind when that is two files away.
 *
 * <p>A mixin usually names its target by NAME alone — {@code @Inject(method = "handleCustomClickAction")} — because
 * on the loader it was built for there is only one method with that name. The byte merge sometimes produces two:
 * NeoForge's patch put {@code MinecraftServer.handleCustomClickAction(Identifier, Optional, ServerPlayer, GameProfile)}
 * — fire NeoForge's event, then call vanilla's — in front of vanilla's {@code (Identifier, Optional)}, and the merge
 * kept both, NeoForge's first.
 *
 * <p>Mixin binds a bare name to the FIRST declared method of that name (the selector's default quantifier is one
 * match) and only then checks the handler against it. When that is the other ecosystem's overload the descriptors do
 * not agree, and the result is not "try the sibling" — it is {@code InvalidInjectionException}, which fails the whole
 * mixin class and takes every other injection in it along. carpet-org-addition's dialog hook did exactly that: its
 * handler takes vanilla's two arguments, and vanilla's method is one entry further down the list.
 *
 * <p>So such a selector is made explicit, {@code name(descriptor)returnType}, and only where Mixin's own binding
 * fails: the injector names that one bare selector and nothing else (with several, Mixin skips a target that does
 * not fit instead of failing, so the injection works and a pin would only add one), and the first overload does not
 * take the handler's arguments by Mixin's rule (all of the target's parameters, or none). A handler that loosens
 * that rule with {@code @Coerce} is left alone. A selector whose first overload binds is never touched, even when
 * another overload would fit better — that is Mixin's choice, the mod may rely on it, and an earlier version that
 * pinned by fit alone would have moved 26 working selectors on the popular set. The replacement must then be the ONE other overload the handler
 * fits exactly: the same parameters, the callback its return type takes, the same static-ness. Zero fits or two and
 * the selector stays as the mod wrote it, because a guess would put the injection, silently, on a method the mod did
 * not ask for. Nothing is invented: the descriptor written in is one the target class declares. A mixin with several
 * targets is pinned only when every target that declares the name lands on the same descriptor.
 *
 * <p>{@link MixinFit} asks {@link #destination} before it judges a name-only selector, so a pinned injector is judged
 * on the overload it lands on and never reads as refused. What the pin leaves alone -- two overloads the handler fits,
 * a {@code @Coerce}, several selectors, a lambda, the pin switched off -- is MixinFit's handler-fit rule's: a binding
 * the handler was not written for is a miss, and one Mixin would reject outright (its {@code @At} sure to find a point
 * there) is taken out of its mixin by {@link net.forbric.kernel.transform.GuestInjectorPruner} (or the mixin is left
 * out) before Mixin reads it. The explanation below is what reaches the log and the report when Mixin does meet such a
 * binding (with {@code -Dforbric.guestInjectorPruner.refused=off} or {@code -Dforbric.mixinFit.handlerFit=off}). Both
 * read a {@code @Surrogate} as Mixin looks one up -- the handler's name, exactly the first overload's callback
 * descriptor, a visible annotation: that overload binds, and nothing is pinned.
 *
 * <p>Lambdas are left to the pruner and the shims ({@link MixinHandlerShim}, {@link InsertedLambdaArgumentShim}): a
 * lambda's name is a counter, so two bodies sharing {@code lambda$foo$0} are not two overloads of one method. When a
 * lambda selector cannot bind because the shape it was written for was a body the merge dropped, the kernel says so
 * instead, in the log and on the mod's row of the load report.
 *
 * <p>Only {@code @Inject}. Its handler-compatibility rule is the one that can be checked here — the target's
 * parameters, then a {@code CallbackInfo}, then whatever locals the handler captures. The other injectors
 * ({@code @Redirect}, {@code @ModifyArg}, the MixinExtras ones) each have a different and less mechanical rule,
 * and guessing at one of those is exactly the wrong pin this declines to make.
 *
 * <p>{@code -Dforbric.mixinOverloadPin=off} leaves every selector alone and explains nothing.
 */
public final class MixinOverloadPin {
	static final String PROPERTY = "forbric.mixinOverloadPin";

	private static final String INJECT_DESC = "Lorg/spongepowered/asm/mixin/injection/Inject;";
	private static final String COERCE_DESC = "Lorg/spongepowered/asm/mixin/injection/Coerce;";
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
	 * Pins every name-only {@code @Inject} selector in {@code mixin} that Mixin would bind to an overload its handler
	 * cannot take, when exactly one other overload fits; explains the ones that still cannot bind.
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

	/**
	 * The method a name-only {@code selector} on {@code handler} lands on in {@code target} once {@link #pin} has run,
	 * or null when the selector would be left as written — the same decision for this one target (with several
	 * targets, {@link #pin} also needs them to agree). {@link MixinFit} asks this before it judges the injector's
	 * anchors in the first overload, which is not where the injection will be.
	 */
	public static MethodNode destination(MethodNode handler, String selector, ClassNode target) {
		return destination(null, handler, selector, target);
	}

	/** As above, with the mixin that declares {@code handler}, whose {@code @Surrogate}s Mixin may bind instead of it. */
	public static MethodNode destination(ClassNode mixin, MethodNode handler, String selector, ClassNode target) {
		if (handler == null || selector == null || target == null || target.methods == null || !bareName(selector)) return null;
		AnnotationNode inject = annotation(handler, INJECT_DESC);
		if (inject == null || !pinnable(handler, inject)) return null;
		String pinned = pinnedSelector(mixin, handler, selector.trim(), List.of(target.name), name -> target);
		if (pinned == null) return null;
		String desc = pinned.substring(pinned.indexOf('('));
		for (MethodNode method : target.methods) {
			if (method.name.equals(selector.trim()) && method.desc.equals(desc)) return method;
		}
		return null;
	}

	/**
	 * Whether Mixin's failure on this injector is the whole-class one a pin prevents: exactly one entry in
	 * {@code method}, no {@code target} selectors beside it, and no {@code @Coerce} parameter (Mixin accepts a supertype
	 * there, which {@link MixinHandlerShim#binds} does not model, so a handler that binds could read as one that does not).
	 */
	static boolean pinnable(MethodNode handler, AnnotationNode inject) {
		if (inject.values == null) return false;
		int selectors = 0;
		for (int i = 0; i + 1 < inject.values.size(); i += 2) {
			Object key = inject.values.get(i);
			if ("target".equals(key)) return false;
			if ("method".equals(key) && inject.values.get(i + 1) instanceof List<?> raw) selectors += raw.size();
		}
		return selectors == 1 && !coerces(handler.visibleParameterAnnotations) && !coerces(handler.invisibleParameterAnnotations);
	}

	private static boolean coerces(List<AnnotationNode>[] parameters) {
		if (parameters == null) return false;
		for (List<AnnotationNode> annotations : parameters) {
			if (annotations != null && annotation(annotations, COERCE_DESC) != null) return true;
		}
		return false;
	}

	@SuppressWarnings("unchecked")
	private static int pinSelectors(ClassNode mixin, MethodNode handler, AnnotationNode inject,
			List<String> targetNames, Function<String, ClassNode> targets) {
		List<Object> values = inject.values;
		if (values == null) return 0;
		boolean pinnable = pinnable(handler, inject);

		int pinned = 0;
		for (int i = 0; i + 1 < values.size(); i += 2) {
			if (!"method".equals(values.get(i)) || !(values.get(i + 1) instanceof List<?> raw)) continue;
			List<Object> selectors = new ArrayList<>(raw);
			boolean changed = false;
			for (int j = 0; j < selectors.size(); j++) {
				if (!(selectors.get(j) instanceof String selector) || !bareName(selector)) continue;
				String name = selector.trim();
				String explicit = pinnable ? pinnedSelector(mixin, handler, name, targetNames, targets) : null;
				if (explicit == null) {
					explain(mixin, handler, name, targetNames, targets);
					continue;
				}
				selectors.set(j, explicit);
				changed = true;
				pinned++;
				ForbricLog.warn("[Forbric/Mixin] %s.%s selects %s by name, and the merged class declares more than one "
						+ "method of that name: Mixin would bind the first, which does not take this handler's arguments, "
						+ "and fail the whole mixin. Pinned to %s, the one overload the handler was written for",
						mixin.name, handler.name, name, explicit);
			}
			if (changed) values.set(i + 1, selectors);
		}
		return pinned;
	}

	/**
	 * {@code name(descriptor)} for the one overload {@code handler} fits, when Mixin would bind the bare {@code name}
	 * to an overload that cannot take it; null to leave the selector as written.
	 *
	 * <p>A {@code @Surrogate} of the handler's name in {@code mixin} that takes the first overload is Mixin's own answer
	 * there -- it binds the surrogate instead of failing -- so that first overload binds and nothing is pinned, as
	 * {@link MixinFit}'s handler-fit rule reads the same mixin.
	 */
	static String pinnedSelector(ClassNode mixin, MethodNode handler, String name, List<String> targetNames,
			Function<String, ClassNode> targets) {
		if (!enabled() || name.startsWith("lambda$")) return null;
		String chosen = null;
		boolean needed = false;
		for (String targetName : targetNames) {
			ClassNode target = targets.apply(targetName);
			// A target that cannot be read is one whose first overload is unknown, and so whose outcome is too.
			if (target == null || target.methods == null) return null;
			MethodNode first = null;
			List<MethodNode> fitting = new ArrayList<>();
			for (MethodNode method : target.methods) {
				if (!method.name.equals(name)) continue;
				if (first == null) first = method;
				if (takes(handler, method)) fitting.add(method);
			}
			// No method of that name: the selector matches nothing there, pinned or not.
			if (first == null) continue;
			String here;
			if (MixinHandlerShim.binds(handler.desc, first.desc) || surrogateTakes(mixin, handler, first)) {
				here = first.desc;
			} else {
				if (fitting.size() != 1) return null;
				here = fitting.get(0).desc;
				needed = true;
			}
			if (chosen != null && !chosen.equals(here)) return null;
			chosen = here;
		}
		return needed ? name + chosen : null;
	}

	/**
	 * Whether {@code mixin} declares a {@code @Surrogate} of {@code handler}'s name that Mixin binds to {@code target}:
	 * exactly the callback descriptor and a visible annotation, as Mixin looks one up ({@link MixinFit#surrogateBinds}).
	 */
	private static boolean surrogateTakes(ClassNode mixin, MethodNode handler, MethodNode target) {
		return MixinFit.surrogateBinds(mixin, handler, target.desc);
	}

	/**
	 * Whether {@code handler} is an {@code @Inject} handler written for exactly {@code target}: {@link #fits}, the
	 * callback its return type takes ({@code CallbackInfo} for void, {@code CallbackInfoReturnable} otherwise), and
	 * the same static-ness. Stricter than Mixin on purpose — it only ever picks a replacement.
	 */
	static boolean takes(MethodNode handler, MethodNode target) {
		if (!fits(handler.desc, target.desc)) return false;
		if (((handler.access & Opcodes.ACC_STATIC) != 0) != ((target.access & Opcodes.ACC_STATIC) != 0)) return false;
		String callback = Type.getArgumentTypes(handler.desc)[Type.getArgumentTypes(target.desc).length].getInternalName();
		boolean returnsVoid = Type.getReturnType(target.desc).getSort() == Type.VOID;
		return returnsVoid ? CALLBACK_INFO.equals(callback) : CALLBACK_INFO_RETURNABLE.equals(callback);
	}

	/** A plain method name: no descriptor, owner, wildcard, regex or {@code desc=} clause. */
	static boolean bareName(String selector) {
		String s = selector.trim();
		return !s.isEmpty() && s.indexOf('(') < 0 && s.indexOf(';') < 0 && s.indexOf('*') < 0 && !s.startsWith("/")
				&& s.indexOf(' ') < 0 && s.indexOf('=') < 0;
	}

	/**
	 * Logs why {@code selector} cannot bind, when the kernel knows: a lambda chain the merge dropped or a lambda a carrier
	 * patch reshaped from vanilla's ({@link LambdaShapeEvidence}), or overloads the merge put side by side with the other
	 * ecosystem's first.
	 *
	 * <p>Says nothing at all in every other case — including a selector that binds perfectly well, which is
	 * almost all of them. Whether it binds is judged on the overload Mixin actually picks, the first of that name; an
	 * earlier version accepted ANY fitting overload as "it binds" and so stayed silent about exactly the case Mixin
	 * fails on. The conditions are deliberately all-or-nothing: the handler must fit what was dropped or what was put
	 * behind, never merely fail to fit what is first. Anything less and the diagnosis would be a guess wearing a fact's
	 * clothes.
	 *
	 * @return whether anything was said
	 */
	static boolean explain(ClassNode mixin, MethodNode handler, String selector, List<String> targetNames,
			Function<String, ClassNode> targets) {
		for (String targetName : targetNames) {
			ClassNode target = targets.apply(targetName);
			if (target == null || target.methods == null) continue;

			List<MethodNode> named = new ArrayList<>();
			for (MethodNode method : target.methods) if (method.name.equals(selector)) named.add(method);
			if (named.isEmpty()) continue;
			// It binds; there is nothing to explain.
			if (MixinHandlerShim.binds(handler.desc, named.get(0).desc)) return false;

			if (!selector.startsWith("lambda$")) {
				List<String> written = new ArrayList<>();
				for (MethodNode method : named.subList(1, named.size())) if (takes(handler, method)) written.add(method.desc);
				if (written.isEmpty()) continue;
				String wanted = String.join(" or ", written);
				ForbricLog.warn("[Forbric/Mixin] %s.%s cannot bind to %s.%s: the merged class declares %d methods of that "
						+ "name, a bare name binds the first, %s, and the handler was written for %s%s",
						mixin.name, handler.name, targetName, selector, named.size(), named.get(0).desc, wanted,
						written.size() > 1 ? " — more than one, so the kernel will not choose" : "");
				REASONS.put(mixin.name, "its " + handler.name + " selects " + selector + " by name, which binds the first of "
						+ named.size() + " methods the merged class declares with that name, " + named.get(0).desc
						+ "; the handler was written for " + wanted);
				return true;
			}

			for (LambdaShapeEvidence.Shape gone : LambdaShapeEvidence.vanished(target, selector)) {
				String dropped = gone.descriptor();
				// A shape the reference alone knows is told only where no MinecraftForge compile of the class is in the game:
				// on the merged base these diagnoses say what they said before the reference was evidence.
				if (!fits(handler.desc, dropped) || !LambdaShapeEvidence.diagnosable(target, gone)) continue;
				if (!gone.dropped()) {
					ForbricLog.warn("[Forbric/Mixin] %s.%s cannot bind to %s.%s: the shape it was written for, %s, is "
							+ "vanilla's — a carrier patch changed the captures of that lambda of %s, which here takes %s, so "
							+ "this injection has no target of its shape", mixin.name, handler.name, targetName, selector, dropped,
							enclosing(selector), named.get(0).desc);
					REASONS.put(mixin.name, "its " + handler.name + " targets " + selector + ", a lambda of "
							+ enclosing(selector) + " whose captures a carrier patch changed from vanilla's");
					return true;
				}
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

	/** Test seam: forget what earlier mixins were diagnosed with. */
	static void clearReasons() {
		REASONS.clear();
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
