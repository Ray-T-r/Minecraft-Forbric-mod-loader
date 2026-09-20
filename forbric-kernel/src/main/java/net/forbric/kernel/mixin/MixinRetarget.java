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
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.LookupSwitchInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TableSwitchInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

/**
 * Rebinds a guest injector from a merge-added DELEGATING STUB to the method that carries the body it wants.
 *
 * <p>NeoForge's patch of {@code SimpleContainer.setItem(int, ItemStack)} moved the body — including the
 * {@code setChanged()} call — into a new {@code setItem(int, ItemStack, boolean)} and left the vanilla-shaped
 * method as {@code aload/iload/aload/iconst_0/invokevirtual setItem(…Z)V/return}. fabric-transfer-api-v1's
 * {@code SimpleContainerMixin} selects the vanilla shape by explicit descriptor and {@code @Redirect}s the
 * {@code setChanged()} inside it; on the merged base the call is not there, the mixin reads PARTIAL, and every
 * hopper or pipe transfer through a Fabric mod spams {@code setChanged} for each intermediate step. The same
 * shape hits {@code BaseContainerBlockEntity.setItem}.
 *
 * <p>Rule R1: an injector whose selector carries an explicit descriptor and resolves to a method that is a pure
 * delegating stub — loads, constants, argument construction ({@code NEW/DUP/INVOKESPECIAL <init>/CHECKCAST}),
 * exactly one call to a same-owner same-name method with a different descriptor, and a return; no branch —
 * whose {@code @At} member is absent from the stub but present in that delegate, has its selector rewritten to
 * the delegate, provided the handler does not depend on the stub's parameter list: {@code @At}-driven kinds
 * ({@code @Redirect}, {@code @WrapOperation}, {@code @ModifyArg(s)}, {@code @ModifyExpressionValue},
 * {@code @ModifyReturnValue}, {@code @ModifyConstant}, {@code @WrapWithCondition}), or an {@code @Inject} that
 * captures nothing or exactly the delegate's parameters; and every {@code @Local} sugar parameter must name a
 * type the delegate's own parameters carry (fabric-content-registries' {@code FuelValuesMixin} captures the
 * {@code HolderLookup.Provider} and {@code FeatureFlagSet} that only the stub has, so it is left alone).
 *
 * <p>The rewrite is applied to the {@link ClassNode} Mixin receives from the bytecode provider
 * ({@code ForbricMixinService.getClassNode}), never to jar bytes: the plan is computed once by
 * {@link KernelGuestMixinAdapter} when it sees the PARTIAL verdict, kept only if the rewritten mixin re-evaluates
 * better, and remembered by the mixin's internal name. {@code -Dforbric.mixinRetarget=off} computes no plan and
 * edits nothing — the PARTIAL lines return exactly as before.
 */
public final class MixinRetarget {
	public static final String PROPERTY = "forbric.mixinRetarget";

	static final String INJECT = "Lorg/spongepowered/asm/mixin/injection/Inject;";
	static final String LOCAL_SUGAR = "Lcom/llamalad7/mixinextras/sugar/Local;";
	static final String CALLBACK_INFO = "Lorg/spongepowered/asm/mixin/injection/callback/CallbackInfo;";
	static final String CALLBACK_INFO_RETURNABLE = "Lorg/spongepowered/asm/mixin/injection/callback/CallbackInfoReturnable;";

	/** Injector kinds whose handler signature is derived from the {@code @At} member, not the target method. */
	static final Set<String> AT_DRIVEN = Set.of(
			"Lorg/spongepowered/asm/mixin/injection/Redirect;",
			"Lorg/spongepowered/asm/mixin/injection/ModifyArg;",
			"Lorg/spongepowered/asm/mixin/injection/ModifyArgs;",
			"Lorg/spongepowered/asm/mixin/injection/ModifyConstant;",
			"Lcom/llamalad7/mixinextras/injector/ModifyExpressionValue;",
			"Lcom/llamalad7/mixinextras/injector/ModifyReturnValue;",
			"Lcom/llamalad7/mixinextras/injector/WrapWithCondition;",
			"Lcom/llamalad7/mixinextras/injector/wrapoperation/WrapOperation;",
			"Lcom/llamalad7/mixinextras/injector/v2/WrapWithCondition;");

	/** One selector rewrite inside one handler's injector annotation. */
	public record Rewrite(String handler, String from, String to, String why) {
	}

	public record Plan(String mixin, List<Rewrite> rewrites) {
		public boolean isEmpty() {
			return rewrites.isEmpty();
		}

		public String describe() {
			List<String> parts = new ArrayList<>();
			for (Rewrite r : rewrites) parts.add(r.from() + " → " + r.to() + " (" + r.why() + ")");
			return String.join("; ", parts);
		}
	}

	private static final Map<String, Plan> PLANS = Collections.synchronizedMap(new LinkedHashMap<>());

	private MixinRetarget() {
	}

	public static boolean enabled() {
		return !"off".equalsIgnoreCase(System.getProperty(PROPERTY, "on"));
	}

	/** Computes R1 for {@code mixin} (parsed with code) against its targets, resolved through {@code resolver}. */
	static Plan plan(ClassNode mixin, Function<String, byte[]> resolver) {
		if (!enabled() || mixin.methods == null) return new Plan(mixin.name, List.of());
		List<Rewrite> rewrites = new ArrayList<>();
		for (String targetName : MixinFit.mixinTargets(mixin)) {
			byte[] targetBytes = resolver.apply(targetName + ".class");
			if (targetBytes == null) continue;
			ClassNode target = MixinFit.parse(targetBytes);
			for (MethodNode handler : mixin.methods) {
				AnnotationNode injector = MixinFit.injectorOf(handler);
				if (injector == null) continue;
				List<String> selectors = MixinFit.stringList(MixinFit.value(injector, "method"));
				for (String selector : selectors) {
					Rewrite rewrite = rewriteFor(handler, injector, selector, target, resolver);
					if (rewrite != null) rewrites.add(rewrite);
				}
			}
		}
		return new Plan(mixin.name, List.copyOf(rewrites));
	}

	private static Rewrite rewriteFor(MethodNode handler, AnnotationNode injector, String selector, ClassNode target,
			Function<String, byte[]> resolver) {
		String s = selector.trim();
		if (s.indexOf('*') >= 0 || s.indexOf('/') == 0 || s.indexOf(' ') >= 0 || s.indexOf('=') >= 0) return null;
		int semi = s.indexOf(';');
		if (s.startsWith("L") && semi > 0) s = s.substring(semi + 1);
		int paren = s.indexOf('(');
		if (paren <= 0) return null;    // a name-only selector already binds to every overload
		String name = s.substring(0, paren);
		String desc = s.substring(paren);

		// The stub and its owner, walking the hierarchy the way Mixin resolves a selector.
		ClassNode owner = null;
		MethodNode stub = null;
		ClassNode current = target;
		for (int guard = 0; current != null && guard < 32 && stub == null; guard++) {
			for (MethodNode m : current.methods) {
				if (m.name.equals(name) && m.desc.equals(desc)) { owner = current; stub = m; break; }
			}
			if (stub != null) break;
			if (current.superName == null || "java/lang/Object".equals(current.superName)) break;
			byte[] bytes = resolver.apply(current.superName + ".class");
			current = bytes == null ? null : MixinFit.parse(bytes);
		}
		if (stub == null) return null;
		MethodNode delegate = delegateOf(owner, stub);
		if (delegate == null) return null;

		// At least one @At member is absent from the stub and present in the delegate — the merge moved it.
		boolean moved = false;
		for (AnnotationNode at : MixinFit.atNodes(injector)) {
			String atValue = MixinFit.asString(MixinFit.value(at, "value"));
			String atTarget = MixinFit.asString(MixinFit.value(at, "target"));
			if (atValue == null || atTarget == null || !MixinFit.RESOLVABLE_AT.contains(atValue)) continue;
			if (!MixinFit.containsMember(stub, atTarget) && MixinFit.containsMember(delegate, atTarget)) moved = true;
		}
		if (!moved) return null;
		if (!handlerFits(handler, injector, delegate)) return null;

		return new Rewrite(handler.name, selector, name + delegate.desc, "merge-added delegating stub");
	}

	/**
	 * The same-owner same-name method {@code stub} delegates to, when {@code stub} is nothing but that delegation:
	 * loads and constants, argument construction, exactly one call to a different descriptor of its own name, and
	 * a return. Anything else — a branch, a second call, a field write — is a body of its own.
	 */
	static MethodNode delegateOf(ClassNode owner, MethodNode stub) {
		if (stub.instructions == null || stub.instructions.size() == 0) return null;
		MethodInsnNode delegation = null;
		for (AbstractInsnNode insn = stub.instructions.getFirst(); insn != null; insn = insn.getNext()) {
			int op = insn.getOpcode();
			if (op < 0) continue;
			if (insn instanceof JumpInsnNode || insn instanceof TableSwitchInsnNode || insn instanceof LookupSwitchInsnNode) return null;
			if (insn instanceof MethodInsnNode call) {
				if (op == Opcodes.INVOKESPECIAL && "<init>".equals(call.name)) continue;    // argument construction
				if (delegation == null && call.owner.equals(owner.name) && call.name.equals(stub.name)
						&& !call.desc.equals(stub.desc)) {
					delegation = call;
					continue;
				}
				return null;
			}
			if (isLoadOrConstant(insn) || op == Opcodes.NEW || op == Opcodes.DUP || op == Opcodes.CHECKCAST
					|| (op >= Opcodes.IRETURN && op <= Opcodes.RETURN)) {
				continue;
			}
			return null;
		}
		if (delegation == null) return null;
		for (MethodNode m : owner.methods) {
			if (m.name.equals(delegation.name) && m.desc.equals(delegation.desc)) return m;
		}
		return null;
	}

	private static boolean isLoadOrConstant(AbstractInsnNode insn) {
		int op = insn.getOpcode();
		if (insn instanceof VarInsnNode) {
			return op == Opcodes.ALOAD || op == Opcodes.ILOAD || op == Opcodes.LLOAD || op == Opcodes.FLOAD || op == Opcodes.DLOAD;
		}
		if (insn instanceof LdcInsnNode) return true;
		return op == Opcodes.ACONST_NULL || (op >= Opcodes.ICONST_M1 && op <= Opcodes.DCONST_1)
				|| op == Opcodes.BIPUSH || op == Opcodes.SIPUSH;
	}

	/** Whether {@code handler}'s signature survives the move from the stub's parameter list to the delegate's. */
	static boolean handlerFits(MethodNode handler, AnnotationNode injector, MethodNode delegate) {
		Type[] params = Type.getArgumentTypes(handler.desc);
		Type[] delegateParams = Type.getArgumentTypes(delegate.desc);
		List<Type> plain = new ArrayList<>();
		for (int i = 0; i < params.length; i++) {
			if (isAnnotated(handler, i, LOCAL_SUGAR)) {
				// A @Local is captured by TYPE from the target's locals; the delegate's own parameters are the
				// only locals this can reason about, so the type has to be among them.
				boolean present = false;
				for (Type t : delegateParams) if (t.equals(params[i])) present = true;
				if (!present) return false;
				continue;
			}
			plain.add(params[i]);
		}
		if (AT_DRIVEN.contains(injector.desc)) return true;
		if (INJECT.equals(injector.desc)) {
			if (!plain.isEmpty()) {
				String last = plain.get(plain.size() - 1).getDescriptor();
				if (CALLBACK_INFO.equals(last) || CALLBACK_INFO_RETURNABLE.equals(last)) plain.remove(plain.size() - 1);
			}
			if (plain.isEmpty()) return true;
			// Mixin's argument capture requires the target's parameters EXACTLY.
			if (plain.size() != delegateParams.length) return false;
			for (int i = 0; i < plain.size(); i++) if (!plain.get(i).equals(delegateParams[i])) return false;
			return true;
		}
		return false;    // @ModifyVariable and friends index the target's locals: not movable by rule
	}

	private static boolean isAnnotated(MethodNode handler, int parameter, String desc) {
		return isAnnotated(handler.visibleParameterAnnotations, parameter, desc)
				|| isAnnotated(handler.invisibleParameterAnnotations, parameter, desc);
	}

	private static boolean isAnnotated(List<AnnotationNode>[] annotations, int parameter, String desc) {
		if (annotations == null || parameter >= annotations.length || annotations[parameter] == null) return false;
		for (AnnotationNode a : annotations[parameter]) if (desc.equals(a.desc)) return true;
		return false;
	}

	// ---------------------------------------------------------------------------------------------------------------
	// Serving the plan
	// ---------------------------------------------------------------------------------------------------------------

	/** Edits the injector annotations of {@code node} in place per {@code plan}; returns how many took. */
	static int apply(ClassNode node, Plan plan) {
		if (node.methods == null) return 0;
		int applied = 0;
		for (Rewrite rewrite : plan.rewrites()) {
			for (MethodNode m : node.methods) {
				if (!m.name.equals(rewrite.handler())) continue;
				AnnotationNode injector = MixinFit.injectorOf(m);
				if (injector == null || injector.values == null) continue;
				for (int i = 0; i + 1 < injector.values.size(); i += 2) {
					if (!"method".equals(injector.values.get(i))) continue;
					Object v = injector.values.get(i + 1);
					if (v instanceof List<?> list) {
						@SuppressWarnings("unchecked") List<Object> selectors = (List<Object>) list;
						for (int k = 0; k < selectors.size(); k++) {
							if (rewrite.from().equals(selectors.get(k))) { selectors.set(k, rewrite.to()); applied++; }
						}
					} else if (rewrite.from().equals(v)) {
						injector.values.set(i + 1, rewrite.to());
						applied++;
					}
				}
			}
		}
		return applied;
	}

	/** {@code mixinBytes} with {@code plan} applied, for re-evaluation. */
	static byte[] rewritten(byte[] mixinBytes, Plan plan) {
		ClassNode node = MixinFit.parse(mixinBytes);
		apply(node, plan);
		ClassWriter writer = new ClassWriter(0);
		node.accept(writer);
		return writer.toByteArray();
	}

	/** Remembers {@code plan} for {@link #applyRemembered}. */
	static void remember(Plan plan) {
		PLANS.put(plan.mixin(), plan);
	}

	/** The plan remembered for a mixin class, by binary or internal name; null when none. */
	public static Plan planFor(String className) {
		return PLANS.get(className.replace('.', '/'));
	}

	/** Applies the remembered plan, if any, to the node the bytecode provider is about to hand Mixin. */
	public static int applyRemembered(String className, ClassNode node) {
		Plan plan = planFor(className);
		return plan == null ? 0 : apply(node, plan);
	}

	/** Test seam. */
	static void reset() {
		PLANS.clear();
	}
}
