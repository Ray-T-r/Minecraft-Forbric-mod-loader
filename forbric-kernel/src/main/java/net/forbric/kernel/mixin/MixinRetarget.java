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
 * {@code @ModifyReturnValue}, {@code @ModifyConstant}, {@code @WrapWithCondition}) whose captures of the target's
 * arguments, if any, the stub passes to the delegate in place (MixinStubRebind's rule, shared), or an {@code @Inject}
 * that captures nothing or exactly the delegate's parameters; and every {@code @Local} sugar parameter must name a
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
	/** {@code -Dforbric.mixinRetarget.sugarBoundary=off}: any parameter annotation ends an {@code @At}-driven handler's call part. */
	static final String SUGAR_BOUNDARY_PROPERTY = "forbric.mixinRetarget.sugarBoundary";
	/** {@code -Dforbric.mixinRetargetSplit=off}: R3 refuses two fits again, dispatcher or not (R4 off). */
	static final String SPLIT_PROPERTY = "forbric.mixinRetargetSplit";
	/**
	 * {@code -Dforbric.mixinExtractedHelper=off}: no {@code @Inject} point follows a call into a carrier's helper along a
	 * census row (R5); the reviewed rows have {@code -Dforbric.mixinAbsorbedCall=off}.
	 */
	static final String EXTRACTED_HELPER_PROPERTY = "forbric.mixinExtractedHelper";

	static final String INJECT = "Lorg/spongepowered/asm/mixin/injection/Inject;";
	static final String LOCAL_SUGAR = "Lcom/llamalad7/mixinextras/sugar/Local;";
	static final String GROUP = "Lorg/spongepowered/asm/mixin/injection/Group;";
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

	/** What a rewrite edits: the injector's {@code method} selector, or one of its {@code @At.target}s. */
	public enum Element { SELECTOR, AT_TARGET }

	/** One rewrite inside one handler's injector annotation. */
	public record Rewrite(String handler, Element element, String from, String to, String why) {
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
				List<Rewrite> own = new ArrayList<>();
				for (String selector : selectors) {
					Rewrite rewrite = rewriteFor(handler, injector, selector, target, resolver);
					if (rewrite != null) own.add(rewrite);
				}
				own.addAll(swappedCallees(handler, injector, selectors, target, resolver));
				own.addAll(renamedBodies(mixin.name, handler, injector, selectors, target, resolver));
				// The selector moves when the method is a stub, a rename or a split; the point moves only when the method
				// keeps a body of its own and the call went one level down. Never both for one handler.
				if (own.stream().noneMatch(r -> r.element() == Element.SELECTOR)) {
					own.addAll(movedCalls(mixin.name, handler, injector, selectors, target, resolver));
				}
				rewrites.addAll(own);
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
		if (paren <= 0) return null;    // a name-only selector on a stub is MixinStubRebind's (Fabric mods, carrier-added stubs)
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
		if (!handlerFits(handler, injector, owner, stub, delegate)) return null;

		return new Rewrite(handler.name, Element.SELECTOR, selector, name + delegate.desc, "merge-added delegating stub");
	}

	/**
	 * Rule R2: an {@code @At(INVOKE)} whose member misses in every method the injector bound to, where a
	 * {@link MergedBaseCalleeSwaps} row names the callee the merged body calls instead — present there, the vanilla
	 * name absent — is rewritten to the merged callee. Only {@code @At}-driven kinds: the handler's shape is the
	 * callee's, which is identical on both sides by construction (same descriptor).
	 */
	private static List<Rewrite> swappedCallees(MethodNode handler, AnnotationNode injector, List<String> selectors,
			ClassNode target, Function<String, byte[]> resolver) {
		if (!AT_DRIVEN.contains(injector.desc)) return List.of();
		List<MethodNode> hits = new ArrayList<>();
		for (String selector : selectors) hits.addAll(resolveSelector(target, selector, resolver));
		if (hits.isEmpty()) return List.of();
		List<Rewrite> out = new ArrayList<>();
		for (AnnotationNode at : MixinFit.atNodes(injector)) {
			String atValue = MixinFit.asString(MixinFit.value(at, "value"));
			String atTarget = MixinFit.asString(MixinFit.value(at, "target"));
			if (!"INVOKE".equals(atValue) || atTarget == null) continue;
			MixinFit.Member want = MixinFit.parseMember(atTarget);
			if (want == null || want.owner() == null || want.desc() == null) continue;
			boolean anywhere = false;
			for (MethodNode hit : hits) if (MixinFit.containsMember(hit, atTarget)) anywhere = true;
			if (anywhere) continue;
			for (MethodNode hit : hits) {
				MergedBaseCalleeSwaps.Swap swap = MergedBaseCalleeSwaps.find(target.name, hit.name + hit.desc, want.owner(),
						want.name(), want.desc());
				if (swap == null) continue;
				if (!MixinFit.containsMember(hit, swap.mergedMember())) continue;
				out.add(new Rewrite(handler.name, Element.AT_TARGET, atTarget, swap.mergedMember(),
						"callee the merge swapped: " + swap.vanillaName() + " → " + swap.mergedName()));
				break;
			}
		}
		return out;
	}

	/**
	 * Rule R3: a selector whose method has lost its body to a carrier's RENAME, where the class still declares the
	 * renamed body under the SAME descriptor.
	 *
	 * <p>NeoForge's patch of {@code ItemStack.addDetailsToTooltip} moved vanilla's body into a private
	 * {@code addDetailsToTooltipComponents} with the identical descriptor and made the original a dispatcher over
	 * its own {@code ItemTooltipHandler}. fabric-item-api-v1's {@code ItemStackMixin} has five injections into
	 * {@code addDetailsToTooltip} — two {@code @ModifyArg}s, two {@code @Inject}s and a {@code @ModifyExpression
	 * Value}, all sharing one {@code LocalIntRef} index — and on the merged base every one of their anchors is in
	 * the renamed method. Five of fourteen anchors miss, the mixin reads PARTIAL, and a Fabric mod registering a
	 * tooltip provider has its entry recorded and never applied.
	 *
	 * <p>R1 cannot take this: it needs an explicit descriptor in the selector and a body that is nothing but a
	 * delegation, and this dispatcher is neither. What makes the rewrite safe instead is the IDENTICAL descriptor
	 * — the handler's parameters, its {@code CallbackInfo} and every {@code @Local} it captures stay exactly as
	 * valid as they were, because the two methods take the same arguments.
	 *
	 * <p>Demanded, all of it: every resolvable {@code @At} member of the injector absent from the method the
	 * selector names, present in the renamed one, and exactly ONE method in the class fitting that description.
	 * A second candidate and the rule declines — a rewrite to the wrong body is an injection running somewhere
	 * the mod did not ask for, silently, which is worse than the anchors simply missing — unless R4 can tell which of
	 * them is a piece of the method the mod named.
	 */
	private static List<Rewrite> renamedBodies(String mixinName, MethodNode handler, AnnotationNode injector,
			List<String> selectors, ClassNode target, Function<String, byte[]> resolver) {
		List<AnnotationNode> ats = MixinFit.atNodes(injector);
		if (ats.isEmpty()) return List.of();

		List<Rewrite> out = new ArrayList<>();
		for (String selector : selectors) {
			List<MethodNode> named = resolveSelector(target, selector, resolver);
			// Mixin injects into the target's OWN method; a superclass method of the same name and descriptor is
			// the one it overrides, not a second candidate. apoli-legacy selects "startSleepInBed" by bare name
			// and ServerPlayer overrides Player's, which made this rule decline a body NeoForge moved into a lambda.
			List<MethodNode> own = named.stream().filter(target.methods::contains).toList();
			if (!own.isEmpty()) named = own;
			if (named.size() != 1) continue;    // an overload set is R1's ambiguity, not this rule's business
			MethodNode selected = named.get(0);

			List<String> wanted = resolvableMembers(ats);
			if (wanted.isEmpty()) continue;
			for (String member : wanted) {
				// One anchor still here means the body did not move; there is nothing to retarget.
				if (MixinFit.containsMember(selected, member)) { wanted = List.of(); break; }
			}
			if (wanted.isEmpty()) continue;

			List<MethodNode> fits = new ArrayList<>();
			for (MethodNode candidate : target.methods) {
				// Same descriptor AND same static-ness: an instance handler cannot bind into a static body.
				if (candidate == selected || !candidate.desc.equals(selected.desc)
						|| (candidate.access & Opcodes.ACC_STATIC) != (selected.access & Opcodes.ACC_STATIC)) continue;
				boolean all = true;
				for (String member : wanted) {
					if (!MixinFit.containsMember(candidate, member)) { all = false; break; }
				}
				if (all) fits.add(candidate);
			}
			if (fits.size() > 1) {
				// Two fits: refuse, unless the method is a carrier's split of vanilla's body and exactly one of them is
				// the piece it dispatches to (R4).
				Rewrite split = splitHelper(mixinName, handler, injector, selector, target, selected, fits, wanted);
				if (split != null) out.add(split);
				continue;
			}
			if (fits.isEmpty()) continue;
			MethodNode renamed = fits.get(0);

			out.add(new Rewrite(handler.name, Element.SELECTOR, selector, renamed.name + renamed.desc,
					"a carrier renamed the vanilla body to " + renamed.name + " and left a dispatcher of the same "
							+ "shape behind"));
		}
		return out;
	}

	/**
	 * Rule R4, R3's tie-break for a body a carrier SPLIT: the selected method is a pure dispatcher over same-shaped
	 * helpers it added ({@link CarrierHelpers#dispatchedHelpers}), and of the methods that make every call the
	 * injector anchors on, exactly one is among them.
	 *
	 * <p>NeoForge cut {@code Hud.extractPlayerHealth} into four HUD layers. Better Mount HUD {@code @Redirect}s the
	 * {@code getVehicleMaxHearts} check that hides the hunger bar while riding; the call now sits in
	 * {@code extractFoodLevel} AND in {@code extractVehicleHealth}, both {@code (GuiGraphicsExtractor)V}, so R3 refused
	 * and the hunger bar vanished on every mount. Only {@code extractFoodLevel} is a piece of
	 * {@code extractPlayerHealth}; the other is a layer of its own, where the redirect would hide the mount's hearts.
	 *
	 * <p>Kept exactly, and the table is the only thing that authorizes a move: every anchor on a {@code SPLIT} row of
	 * {@code carrier-helpers.txt} to that one helper, for the mod's own ecosystem (MinecraftForge keeps vanilla's
	 * shape, so its mods move too; NeoForge's mods were compiled against the split and do not), and made exactly once
	 * there. The handler must not depend on anything but the call and the arguments the dispatcher hands on in
	 * place: an {@code @At}-driven kind, or an {@code @Inject} that cannot cancel (cancelling in the helper would skip
	 * only that piece where vanilla skipped the rest of the method) and captures no locals; no sugar, no slice, no
	 * {@code @Group}, and no point but calls and field accesses. {@code -Dforbric.mixinRetargetSplit=off} refuses two
	 * fits as before.
	 */
	private static Rewrite splitHelper(String mixinName, MethodNode handler, AnnotationNode injector, String selector,
			ClassNode target, MethodNode selected, List<MethodNode> fits, List<String> wanted) {
		if ("off".equalsIgnoreCase(System.getProperty(SPLIT_PROPERTY, "on"))) return null;
		net.forbric.api.Ecosystem ecosystem = MixinStubRebind.ecosystemOf(mixinName);
		if (ecosystem == null || !movableWhole(handler, injector)) return null;
		List<MethodInsnNode> pieces = CarrierHelpers.dispatchedHelpers(target, selected);
		if (pieces == null) return null;
		MethodNode helper = null;
		for (MethodNode fit : fits) {
			boolean piece = false;
			for (MethodInsnNode call : pieces) if (call.name.equals(fit.name) && call.desc.equals(fit.desc)) piece = true;
			if (!piece) continue;
			if (helper != null) return null;    // two pieces make the call: which half the mod meant is a guess
			helper = fit;
		}
		if (helper == null) return null;
		for (String member : wanted) {
			CarrierHelpers.Row row = CarrierHelpers.find(target.name, selected.name + selected.desc, member,
					CarrierHelpers.Shape.SPLIT, ecosystem);
			if (row == null || !row.helper().equals(helper.name + helper.desc)) return null;
			if (CarrierHelpers.occurrences(helper, member) != 1) return null;
		}
		return new Rewrite(handler.name, Element.SELECTOR, selector, helper.name + helper.desc,
				"a carrier split the vanilla body into helpers, and " + helper.name + " is the piece that makes the call");
	}

	/**
	 * Rule R5: an {@code @Inject} whose {@code @At(INVOKE)} names a call the carrier moved out of the method into a
	 * helper it added — the method keeps its body and calls the helper once, and the call is the helper's first or last
	 * act — has its point moved to the helper call. The selector stays: the handler still binds to the method it was
	 * written for, with its arguments and its callback, at the same program point.
	 *
	 * <p>NeoForge made {@code AbstractContainerScreen.extractSlot} hand the slot's item to its overridable
	 * {@code renderSlotContents}, which draws it and then, as its last act, the item decorations. Highlighter's
	 * MinecraftForge build injects AFTER the {@code itemDecorations} call in {@code extractSlot} to draw its "new item"
	 * mark; the call was gone, and no container screen ever showed a mark. AFTER {@code renderSlotContents} is what
	 * vanilla's AFTER {@code itemDecorations} was: the instruction before {@code extractSlot}'s return, on every path
	 * that drew the item. A screen that overrides {@code renderSlotContents} gets the mark over its own contents. One
	 * difference: other mods' {@code RETURN} injections into {@code extractSlot} now sit at the same instruction, so
	 * their order against this one follows Mixin's application order rather than always coming after.
	 *
	 * <p>Only along a {@code HEAD} or {@code TAIL} row of {@code carrier-helpers.txt} for the mod's ecosystem, re-checked
	 * on the live bytes ({@link CarrierHelpers#reached}): BEFORE the call needs it at the helper's head, AFTER needs it at
	 * the tail, and no other shift moves. Only an {@code @Inject} that captures no locals and has no sugar, slice or
	 * {@code @Group}; other kinds' handlers describe the call, and moving them would need the helper to have no other
	 * caller, which a protected override point cannot promise. {@code -Dforbric.mixinExtractedHelper=off} leaves the
	 * point as compiled. An AFTER point with no census row may still follow a reviewed row of
	 * {@link MergedBaseAbsorbedCalls} ({@link #absorbedCall}).
	 */
	private static List<Rewrite> movedCalls(String mixinName, MethodNode handler, AnnotationNode injector,
			List<String> selectors, ClassNode target, Function<String, byte[]> resolver) {
		if (!INJECT.equals(injector.desc) || selectors.size() != 1) return List.of();
		boolean census = !"off".equalsIgnoreCase(System.getProperty(EXTRACTED_HELPER_PROPERTY, "on"));
		if (!census && !MergedBaseAbsorbedCalls.enabled()) return List.of();
		net.forbric.api.Ecosystem ecosystem = MixinStubRebind.ecosystemOf(mixinName);
		if (ecosystem == null || !plainInject(handler, injector)) return List.of();
		List<MethodNode> named = resolveSelector(target, selectors.get(0), resolver);
		List<MethodNode> own = named.stream().filter(target.methods::contains).toList();
		if (own.size() != 1) return List.of();
		MethodNode method = own.get(0);

		List<Rewrite> out = new ArrayList<>();
		for (AnnotationNode at : MixinFit.atNodes(injector)) {
			String member = MixinFit.asString(MixinFit.value(at, "target"));
			if (!"INVOKE".equals(MixinFit.asString(MixinFit.value(at, "value"))) || member == null
					|| MixinFit.containsMember(method, member)) continue;
			String shift = MixinFit.asString(MixinFit.value(at, "shift"));
			CarrierHelpers.Shape shape = shift == null || "BEFORE".equals(shift) ? CarrierHelpers.Shape.HEAD
					: "AFTER".equals(shift) ? CarrierHelpers.Shape.TAIL : null;
			// The reference made the call once, so any ordinal past the first missed natively too.
			if (shape == null || MixinFit.value(at, "ordinal") instanceof Integer ordinal && ordinal > 0) continue;
			CarrierHelpers.Row row = census ? CarrierHelpers.find(target.name, method.name + method.desc, member, shape, ecosystem) : null;
			if (row == null) {
				Rewrite absorbed = shape == CarrierHelpers.Shape.TAIL
						? absorbedCall(handler, target, method, member, ecosystem, resolver) : null;
				if (absorbed != null) out.add(absorbed);
				continue;
			}
			int paren = row.helper().indexOf('(');
			MethodNode helper = CarrierHelpers.declared(target, row.helper().substring(0, paren), row.helper().substring(paren));
			if (helper == null || !CarrierHelpers.reached(target, method, helper, row.member()).contains(shape)) continue;
			out.add(new Rewrite(handler.name, Element.AT_TARGET, member, "L" + target.name + ";" + helper.name + helper.desc,
					"the carrier moved the call into " + helper.name + ", whose " + (shape == CarrierHelpers.Shape.TAIL
							? "last" : "first") + " act it is"));
		}
		return out;
	}

	/**
	 * R5's reviewed tier: AFTER a call the surviving carrier absorbed into a static hook of its own
	 * ({@link MergedBaseAbsorbedCalls}, each row with the argument for it) is AFTER the hook call. The hook does more
	 * than the call, so no census can prove this; what is re-checked on the live bytes is the shape the review was
	 * about: the method calls the hook once, as its last act, and the hook — read through the same resolver — makes the
	 * call once. puzzleslib's FOG_COLOR event (FogRendererFabricMixin) sets its colour AFTER vanilla's final
	 * {@code dest.set}, which NeoForge moved into {@code ClientHooks.getFogColor}.
	 */
	private static Rewrite absorbedCall(MethodNode handler, ClassNode target, MethodNode method, String member,
			net.forbric.api.Ecosystem ecosystem, Function<String, byte[]> resolver) {
		MergedBaseAbsorbedCalls.Absorbed row = MergedBaseAbsorbedCalls.find(target.name, method.name + method.desc, member, ecosystem);
		if (row == null) return null;
		if (!CarrierHelpers.edges(method, row.hook()).contains(CarrierHelpers.Shape.TAIL)) return null;
		MixinFit.Member hook = MixinFit.parseMember(row.hook());
		byte[] hookBytes = resolver.apply(hook.owner() + ".class");
		if (hookBytes == null) return null;
		MethodNode body = CarrierHelpers.declared(MixinFit.parse(hookBytes), hook.name(), hook.desc());
		if (body == null || CarrierHelpers.occurrences(body, row.member()) != 1) return null;
		return new Rewrite(handler.name, Element.AT_TARGET, member, row.hook(), "the carrier absorbed the call into "
				+ hook.owner().substring(hook.owner().lastIndexOf('/') + 1) + "." + hook.name() + ", a reviewed row of "
				+ "MergedBaseAbsorbedCalls");
	}

	/** An {@code @Inject} bound by its own arguments and callback only: no locals capture, sugar, slice or {@code @Group}. */
	private static boolean plainInject(MethodNode handler, AnnotationNode injector) {
		List<AnnotationNode> annotations = new ArrayList<>();
		if (handler.visibleAnnotations != null) annotations.addAll(handler.visibleAnnotations);
		if (handler.invisibleAnnotations != null) annotations.addAll(handler.invisibleAnnotations);
		if (annotations.stream().anyMatch(a -> GROUP.equals(a.desc))) return false;
		if (MixinFit.value(injector, "slice") != null || MixinFit.value(injector, "locals") != null) return false;
		for (int i = 0; i < Type.getArgumentTypes(handler.desc).length; i++) if (MixinFit.sugar(handler, i)) return false;
		return true;
	}

	/**
	 * Whether an injector's handler depends on nothing but the call it anchors on and the target's own arguments,
	 * so that it means the same in a piece of the method as in the whole: see {@link #splitHelper}.
	 */
	private static boolean movableWhole(MethodNode handler, AnnotationNode injector) {
		List<AnnotationNode> annotations = new ArrayList<>();
		if (handler.visibleAnnotations != null) annotations.addAll(handler.visibleAnnotations);
		if (handler.invisibleAnnotations != null) annotations.addAll(handler.invisibleAnnotations);
		if (annotations.stream().anyMatch(a -> GROUP.equals(a.desc))) return false;
		if (MixinFit.value(injector, "slice") != null) return false;
		if (INJECT.equals(injector.desc)) {
			if (Boolean.TRUE.equals(MixinFit.value(injector, "cancellable")) || MixinFit.value(injector, "locals") != null) return false;
		} else if (!AT_DRIVEN.contains(injector.desc)) {
			return false;
		}
		List<AnnotationNode> points = MixinFit.atNodes(injector);
		if (points.isEmpty()) return false;
		for (AnnotationNode at : points) {
			String value = MixinFit.asString(MixinFit.value(at, "value"));
			if (value == null || !MixinFit.RESOLVABLE_AT.contains(value) || MixinFit.value(at, "target") == null) return false;
		}
		for (int i = 0; i < Type.getArgumentTypes(handler.desc).length; i++) if (MixinFit.sugar(handler, i)) return false;
		return true;
	}

	/** The {@code @At} members this kernel can look for in a method body — the rest say nothing either way. */
	private static List<String> resolvableMembers(List<AnnotationNode> ats) {
		List<String> members = new ArrayList<>();
		for (AnnotationNode at : ats) {
			String atValue = MixinFit.asString(MixinFit.value(at, "value"));
			String atTarget = MixinFit.asString(MixinFit.value(at, "target"));
			if (atValue == null || atTarget == null || !MixinFit.RESOLVABLE_AT.contains(atValue)) continue;
			members.add(atTarget);
		}
		return members;
	}

	/** A selector's methods on the target's hierarchy: every overload for a bare name, the one for a descriptor. */
	private static List<MethodNode> resolveSelector(ClassNode target, String selector, Function<String, byte[]> resolver) {
		String s = selector.trim();
		if (s.indexOf('*') >= 0 || s.startsWith("/") || s.indexOf(' ') >= 0 || s.indexOf('=') >= 0) return List.of();
		int semi = s.indexOf(';');
		if (s.startsWith("L") && semi > 0) s = s.substring(semi + 1);
		int paren = s.indexOf('(');
		String name = paren >= 0 ? s.substring(0, paren) : s;
		String desc = paren >= 0 ? s.substring(paren) : null;
		List<MethodNode> out = new ArrayList<>();
		ClassNode current = target;
		for (int guard = 0; current != null && guard < 32; guard++) {
			for (MethodNode m : current.methods) if (m.name.equals(name) && (desc == null || m.desc.equals(desc))) out.add(m);
			if (current.superName == null || "java/lang/Object".equals(current.superName)) break;
			byte[] bytes = resolver.apply(current.superName + ".class");
			current = bytes == null ? null : MixinFit.parse(bytes);
		}
		return out;
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

	/**
	 * Whether {@code handler}'s signature survives the move from the stub's parameter list to the delegate's.
	 *
	 * <p>An {@code @At}-driven handler used to pass on its kind alone, but Mixin lets every one of those kinds take a
	 * prefix of the target's arguments after its own contract; torrential's {@code @ModifyReturnValue} on
	 * {@code FuelValues.vanillaBurnTimes} takes all three of the stub's, which the delegate does not have. The rule is
	 * MixinStubRebind's, so the two adapters and MixinFit (which asks MixinStubRebind) cannot disagree.
	 */
	static boolean handlerFits(MethodNode handler, AnnotationNode injector, ClassNode owner, MethodNode stub, MethodNode delegate) {
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
		if (AT_DRIVEN.contains(injector.desc)) {
			if (!MixinStubRebind.capturesGuarded()) return true;
			int end = params.length;
			for (int i = 0; i < params.length; i++) if (trailingSugar(handler, i)) { end = i; break; }
			int own = MixinStubRebind.intrinsicArity(injector, params, end, delegate);
			return own >= 0 && own <= end && MixinStubRebind.capturesSurvive(injector, params, own, end, stub,
					own == end ? null : MixinStubRebind.delegation(owner, stub));
		}
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

	/**
	 * Whether an {@code @At}-driven handler's trailing part — what MixinExtras fills from the target, not the call —
	 * begins at {@code parameter}: a MixinExtras sugar parameter ({@code @Local}, {@code @Share}, …).
	 *
	 * <p>Any annotation used to count. A {@code @Coerce} receiver is part of the call's shape, and Kotlin (and some Java
	 * mods) put an invisible {@code @NotNull} on every handler parameter; with the call's part ending at parameter 0,
	 * the injector's own contract read as longer than the handler and R1 declined a move it made before the captures
	 * were told apart. {@code -Dforbric.mixinRetarget.sugarBoundary=off} counts any annotation again.
	 */
	private static boolean trailingSugar(MethodNode handler, int parameter) {
		if ("off".equalsIgnoreCase(System.getProperty(SUGAR_BOUNDARY_PROPERTY, "on"))) {
			return MixinStubRebind.annotated(handler, parameter);
		}
		return MixinFit.sugar(handler, parameter);
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
				if (rewrite.element() == Element.AT_TARGET) {
					for (AnnotationNode at : MixinFit.atNodes(injector)) {
						if (at.values == null) continue;
						for (int i = 0; i + 1 < at.values.size(); i += 2) {
							if ("target".equals(at.values.get(i)) && rewrite.from().equals(at.values.get(i + 1))) {
								at.values.set(i + 1, rewrite.to());
								applied++;
							}
						}
					}
					continue;
				}
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
