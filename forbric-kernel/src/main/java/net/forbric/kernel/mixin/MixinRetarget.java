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

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FrameNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.LookupSwitchInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TableSwitchInsnNode;
import org.objectweb.asm.tree.TryCatchBlockNode;
import org.objectweb.asm.tree.VarInsnNode;

import net.forbric.kernel.util.ForbricLog;

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
	/** {@code -Dforbric.mixinRetarget.split=off}: R3 refuses two fits again, dispatcher or not (R4 off). */
	static final String SPLIT_PROPERTY = "forbric.mixinRetarget.split";
	/**
	 * {@code -Dforbric.mixinRetarget.renameCensus=off}: R3 moves to the one same-shaped method that carries every anchor on
	 * the bytes alone again, with no {@link CarrierRenames} row and no live call to prove a carrier renamed the body to it.
	 */
	static final String RENAME_CENSUS_PROPERTY = "forbric.mixinRetarget.renameCensus";
	/**
	 * {@code -Dforbric.mixinRetarget.extractedHelper=off}: no {@code @Inject} point follows a call into a carrier's
	 * helper along a census row (R5); the reviewed rows have {@code -Dforbric.mixinAbsorbedCall=off}.
	 */
	static final String EXTRACTED_HELPER_PROPERTY = "forbric.mixinRetarget.extractedHelper";
	/**
	 * {@code -Dforbric.mixinRetarget.substitutedCall=off}: no {@code @Inject} point follows a call the carrier
	 * substituted along a {@link MergedBaseCalleeSwaps#SUBSTITUTED} row (R6).
	 */
	static final String SUBSTITUTED_CALL_PROPERTY = "forbric.mixinRetarget.substitutedCall";
	/**
	 * {@code -Dforbric.mixinRetarget.substitutedCall.guard=off}: an R6 move keeps the handler as the mod wrote it, so a
	 * {@code LinkageError} from it propagates into the method it was moved into, as it would natively.
	 */
	static final String SUBSTITUTED_CALL_GUARD_PROPERTY = "forbric.mixinRetarget.substitutedCall.guard";
	/**
	 * The suffix an R6-guarded handler's own body moves to, under the guard that keeps its name and annotation, before
	 * the mixin's mark ({@link MixinHandlerShim#asideName}).
	 */
	static final String GUARDED_SUFFIX = "$forbricguard";
	/**
	 * {@code -Dforbric.mixinRetarget.replacedCall=off}: no {@code @Inject} follows a vanilla method the carrier replaced,
	 * along a {@link MergedBaseCalleeSwaps#REPLACED} row (R7).
	 */
	static final String REPLACED_CALL_PROPERTY = "forbric.mixinRetarget.replacedCall";
	/**
	 * The suffix an R7-moved handler's own body moves to, under the method that reads its arguments off the replacement's,
	 * before the mixin's mark ({@link MixinHandlerShim#asideName}).
	 */
	static final String PROJECTED_SUFFIX = "$forbricreplaced";
	private static final String SELF = "net/forbric/kernel/mixin/MixinRetarget";
	private static final String LINKAGE_ERROR = "java/lang/LinkageError";
	/** Handlers whose {@code LinkageError} R6's guard has reported: once each, however many models they skip. */
	private static final Set<String> SKIPPED = java.util.concurrent.ConcurrentHashMap.newKeySet();

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

	/**
	 * What a rewrite edits: the injector's {@code method} selector, one of its {@code @At.target}s, or the handler itself:
	 * (R6) put behind a guard, {@code from} the handler and {@code to} where its body moves; or (R7) given a method of its
	 * name that takes the replacement's arguments, {@code from} the handler and {@code to} the replacement it selects.
	 */
	public enum Element { SELECTOR, AT_TARGET, GUARD, PROJECT, LOCAL_INDEX }

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
		return plan(mixin, resolver, MergedBaseCalleeSwaps.sources());
	}

	/** A test may provide the verified source-class seam explicitly instead of changing process-wide readers. */
	static Plan plan(ClassNode mixin, Function<String, byte[]> resolver,
			java.util.function.BiFunction<net.forbric.api.Ecosystem, String, ClassNode> references) {
		if (!enabled() || mixin.methods == null) return new Plan(mixin.name, List.of());
		try (MergedBaseCalleeSwaps.Scope evidence = MergedBaseCalleeSwaps.using(name -> {
			byte[] bytes = resolver.apply(name + ".class");
			if (bytes == null) return null;
			ClassNode node = new ClassNode(); new ClassReader(bytes).accept(node, ClassReader.SKIP_FRAMES); return node;
		}, references)) {
		List<Rewrite> rewrites = new ArrayList<>();
		List<String> targets = MixinFit.mixinTargets(mixin);
		// R4 and R5 write one target's piece or helper into the annotation every target shares: with a second target,
		// a move that helps one could break an anchor that resolves on the other, and the adapter only counts the total.
		boolean oneTarget = targets.size() == 1;
		for (String targetName : targets) {
			byte[] targetBytes = resolver.apply(targetName + ".class");
			if (targetBytes == null) continue;
			ClassNode target = new ClassNode();
			// Native/current local conservation needs the debug scopes, not just the instruction skeleton.
			new ClassReader(targetBytes).accept(target, ClassReader.SKIP_FRAMES);
			for (MethodNode handler : mixin.methods) {
				AnnotationNode injector = MixinFit.injectorOf(handler);
				if (injector == null) continue;
					List<String> selectors = MixinFit.stringList(MixinFit.value(injector, "method"));
					List<Rewrite> own = new ArrayList<>();
					boolean executionPathMoved = false;
					for (String selector : selectors) {
						Rewrite rewrite = rewriteFor(handler, injector, selector, target, resolver);
						if (rewrite != null) own.add(rewrite);
					}
					if (oneTarget && own.isEmpty()) {
						own.addAll(MixinExecutionPathRetarget.plan(mixin, handler, injector, selectors, target,
								references.apply(MixinStubRebind.ecosystemOf(mixin.name), target.name)));
						executionPathMoved = !own.isEmpty();
					}
					if (!executionPathMoved) own.addAll(swappedCallees(mixin.name, handler, injector, selectors, target, resolver));
					if (own.stream().noneMatch(r -> r.element() == Element.SELECTOR)) {
						own.addAll(renamedBodies(mixin, oneTarget, handler, injector, selectors, target, resolver, true));
					}
				// The selector moves when the method is a stub, a rename or a split; the point moves only when the method
				// keeps a body of its own and the call went one level down. Never both for one handler.
					if (oneTarget && !executionPathMoved && own.stream().noneMatch(r -> r.element() == Element.SELECTOR)) {
					own.addAll(movedCalls(mixin.name, handler, injector, selectors, target, resolver));
				}
				// Neither moved nor split: the method kept its body and the call its place, and only the callee changed.
				if (oneTarget && own.isEmpty()) own.addAll(substitutedCalls(mixin.name, handler, injector, selectors, target, resolver));
				// …or the method the mod names, or the one it anchors in, is a vanilla private the carrier replaced outright.
				if (oneTarget && own.isEmpty()) own.addAll(replacedCalls(mixin.name, handler, injector, selectors, target));
				if (oneTarget && own.isEmpty()) {
					Rewrite blockUpdate = MixinChunkStatusRetarget.plan(mixin, handler, selectors, target,
							references.apply(MixinStubRebind.ecosystemOf(mixin.name), target.name));
					if (blockUpdate != null) own.add(blockUpdate);
				}
				rewrites.addAll(own);
			}
		}
		return new Plan(mixin.name, List.copyOf(rewrites));
		}
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
	private static List<Rewrite> swappedCallees(String mixinName, MethodNode handler, AnnotationNode injector, List<String> selectors,
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
						want.name(), want.desc(), MixinStubRebind.ecosystemOf(mixinName), handler,
						INJECT.equals(injector.desc) && plainInject(handler, injector));
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
	 * renamed body under the SAME descriptor and still calls it.
	 *
	 * <p>NeoForge's patch of {@code PersistentEntitySectionManager.addEntity} posts its entity-join event and hands the
	 * rest to a new {@code addEntityWithoutEvent} with the identical descriptor: vanilla's body, renamed. Carpet's scarpet
	 * events and architectury's entity-add event anchor inside that body; on the merged base every one of their anchors
	 * is in the renamed method, and the mixin reads PARTIAL.
	 *
	 * <p>R1 cannot take this: it needs an explicit descriptor in the selector and a body that is nothing but a
	 * delegation, and this dispatcher is neither. What makes the rewrite safe instead is the IDENTICAL descriptor
	 * together with a WHOLE body: the handler's parameters and its {@code CallbackInfo} bind as they did, and the
	 * body it was written against — its locals, the points a slice or a {@code @Share} spans — is all in the renamed
	 * method.
	 *
	 * <p>Demanded, all of it: every resolvable {@code @At} member of the injector absent from the method the selector
	 * names, present in the renamed one, exactly ONE method in the class fitting that description, and that one proven
	 * the carrier's rename of this body: {@link CarrierRenames} rows for the mod's ecosystem, every anchor one of the
	 * calls or field accesses they say moved there (an anchor the reference's method never made misses on the mod's own
	 * game too) and bound no more often than the reference's method made it (an ordinal only where the count is the
	 * same), and the live class still calling it. The bytes alone are not proof. An audit of the moves they made over 655 mod jars
	 * found seven of 15 wrong: a vanilla method of the same shape that happened to carry the anchor (text_styles'
	 * {@code Style.withColor(I)} into {@code withShadowColor(I)}, which {@code withColor} never calls), and a renamed
	 * body nothing calls (malilib's tooltip hook into NeoForge's {@code addDetailsToTooltipComponents}, where it bound,
	 * never ran, and read as fitting). The rows' ecosystems also keep a NeoForge mod where it is: it was compiled against
	 * the rename, and misses natively the same way.
	 *
	 * <p>A renamed body nothing calls is still where the mod's body is, and a required injector that binds nowhere stops
	 * a strict launch: kept out of it, malilib's last tooltip hook stopped every strict client with malilib (Litematica,
	 * MiniHUD, Tweakeroo) and made the default policy ask. So a pair the census marks {@link CarrierRenames#UNCALLED}
	 * moves too, when the method is private and the live class does not call it, and the move says the injector never
	 * runs there, as MixinFit and the final-class check then report it (a never-running injector marks its mod's row and
	 * stops nothing). {@code -Dforbric.mixinRetarget.renameCensus.uncalled=off} leaves it unbound.
	 *
	 * <p>A {@link CarrierRenames.Kind#PIECE PIECE} pair carries only part of the body — NeoForge's
	 * {@code addDetailsToTooltipTail} is the advanced tail of vanilla's tooltip, {@code lambda$startSleepInBed$0} the
	 * checks before the sleep — and there the descriptor is not enough: cancelling returns from the piece where vanilla
	 * returned from the method, a captured local or a slice may lie in another piece, and a {@code @Share} no longer
	 * reaches the injectors that stayed. Its handler must depend on nothing but the call and the target's arguments, as
	 * R4's must ({@link #movableWhole}), and the arguments must be the method's: a handler that takes any, plainly or
	 * as an {@code @Local(argsOnly = true)}, moves only when the method hands the piece its own
	 * ({@link CarrierRenames#handsOwnArguments}) — NeoForge's {@code extractEntityInInventoryFollowsMouse} hands its
	 * piece angles where it was given the mouse position; a method that stores into a parameter before handing it on
	 * does not hand on its own. fabric-item-api's {@code postTooltipsAdvanced} shares an index with the injectors left in
	 * {@code addDetailsToTooltip}, and stays.
	 *
	 * <p>A handler that can cancel moves into a piece only where the cancel still leaves the method with the value the
	 * handler gave: a {@link CarrierRenames.Exit#LEFT LEFT} pair, whose method returns the piece's result whenever it is
	 * an {@code Either} left (re-checked on the live bytes, {@link CarrierRenames#returnsLefts}), and a handler whose
	 * every cancel is {@code setReturnValue(Either.left(..))} ({@link #cancelsOnlyWithLefts}). NeoForge's
	 * {@code startSleepInBed} hands its lambda's answer to {@code EventHooks.canPlayerStartSleeping} and returns it when it
	 * names a problem, so apoli's {@code preventAvianSleep} and fabric-entity-events' {@code @Cancellable}
	 * {@code redirectSleepDirection} cancel the lambda the way vanilla's own checks in it say no: NeoForge's
	 * {@code CanPlayerSleepEvent} sees the problem, as it sees vanilla's, and the method returns it. apoli cancels with
	 * {@code Either.left(null)}, which DFU's {@code left()} cannot read ({@code Optional.of}): NeoForge's hook throws on it
	 * inside {@code startSleepInBed}. On vanilla's {@code BedBlock} path that is no change — on apoli's own game
	 * {@code BedBlock} throws on {@code problem.message()} — but a caller that checks for a null problem (another mod's
	 * sleeping bag or bed) fails here too, where on apoli's own game it would not. That is no regression: before the
	 * census R3 moved the handler there on the bytes alone. {@code -Dforbric.mixinRetarget.renameCensus.leftExit=off}
	 * keeps every handler that can cancel out of a piece.
	 *
	 * <p>On a whole {@link CarrierRenames.Kind#RENAME RENAME} a handler that shares a value moves only with every handler
	 * of its mixin that shares it in the same method ({@link #shareGroupMoves}).
	 *
	 * <p>A second candidate and the rule declines — a rewrite to the wrong body is an injection running somewhere the
	 * mod did not ask for, silently, which is worse than the anchors simply missing — unless R4 can tell which of them
	 * is a piece of the method the mod named. {@code -Dforbric.mixinRetarget.renameCensus=off} moves on the bytes alone
	 * again, as before the census.
	 */
	private static List<Rewrite> renamedBodies(ClassNode mixin, boolean oneTarget, MethodNode handler,
			AnnotationNode injector, List<String> selectors, ClassNode target, Function<String, byte[]> resolver, boolean shares) {
		String mixinName = mixin.name;
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
				Rewrite split = oneTarget ? splitHelper(mixinName, handler, injector, selector, target, selected, fits, wanted) : null;
				if (split != null) out.add(split);
				continue;
			}
			if (fits.isEmpty()) continue;
			MethodNode renamed = fits.get(0);
			boolean census = !"off".equalsIgnoreCase(System.getProperty(RENAME_CENSUS_PROPERTY, "on"));
			String why = "a carrier renamed the vanilla body to " + renamed.name + " and left a dispatcher of the same shape behind";
			if (census) {
				Carried carried = renamedByCarrier(mixin, handler, injector, target, selected, renamed, ats);
				if (carried == null) continue;
				// A value the handler shares lives per method: its partners in selected must all move with it.
				if (shares && !shareGroupMoves(mixin, oneTarget, handler, target, selected, renamed, resolver)) continue;
				why = !carried.called()
						? "a carrier renamed the vanilla body to " + renamed.name + ", which nothing in the merged game calls: bound "
								+ "there as in the body, the injector never runs (carrier-renames.txt)"
						: carried.kind() == CarrierRenames.Kind.RENAME
						? "a carrier renamed the vanilla body to " + renamed.name + ", which the class still calls (carrier-renames.txt)"
						: "a carrier renamed the vanilla body to pieces, and " + renamed.name + ", which the class still calls, "
								+ "is the one that makes the call (carrier-renames.txt)";
			}
			out.add(new Rewrite(handler.name, Element.SELECTOR, selector, renamed.name + renamed.desc, why));
		}
		return out;
	}

	/** How a renamed method carries a body for one injector: whole or a piece, and whether anything calls it. */
	private record Carried(CarrierRenames.Kind kind, boolean called) {
	}

	/**
	 * {@code -Dforbric.mixinRetarget.renameCensus.uncalled=off}: no injector moves into a renamed body nothing calls
	 * ({@link CarrierRenames#UNCALLED}); it stays where its anchors are gone, and a required one is a loss again.
	 */
	static final String UNCALLED_PROPERTY = "forbric.mixinRetarget.renameCensus.uncalled";

	/**
	 * How {@code renamed} carries the body of {@code selected} that a carrier renamed, as far as this injector of a mod
	 * of the mixin's ecosystem is concerned; null when it is not proven to. {@link CarrierRenames} rows say so, every
	 * anchor is one of the calls they say moved, {@code target} still calls it, and for a piece the handler is one that
	 * means the same in a piece of the method as in the whole.
	 *
	 * <p>Or the rows mark the renamed method {@link CarrierRenames#UNCALLED}, and the live class still declares it
	 * private and never calls it: NeoForge keeps vanilla's tooltip body as {@code addDetailsToTooltipComponents} and
	 * draws tooltips from its own appenders. There the injector binds where the body it was written against is, and never
	 * runs — which MixinFit and the final-class check then say, instead of a required injector that found no anchor.
	 * What it would mean there does not matter, only that it binds as it would in the body: nothing that could make the
	 * bind itself fail, as R4 asks ({@link #movableWhole}: no captured locals, no slice or {@code @Group}, no sugar but
	 * the method's arguments and a cancel).
	 */
	private static Carried renamedByCarrier(ClassNode mixin, MethodNode handler, AnnotationNode injector,
			ClassNode target, MethodNode selected, MethodNode renamed, List<AnnotationNode> ats) {
		List<CarrierRenames.Row> rows = CarrierRenames.find(target.name, selected.name + selected.desc,
				renamed.name + renamed.desc, MixinStubRebind.ecosystemOf(mixin.name));
		if (rows.isEmpty()) return null;
		boolean called = CarrierRenames.called(rows);
		if (called ? !CarrierRenames.called(target, renamed)
				: "off".equalsIgnoreCase(System.getProperty(UNCALLED_PROPERTY, "on")) || CarrierRenames.called(target, renamed)
						|| (renamed.access & Opcodes.ACC_PRIVATE) == 0) return null;
		for (AnnotationNode at : ats) {
			String value = MixinFit.asString(MixinFit.value(at, "value"));
			String anchor = MixinFit.asString(MixinFit.value(at, "target"));
			if (value == null || anchor == null || !MixinFit.RESOLVABLE_AT.contains(value)) continue;
			int ordinal = MixinFit.value(at, "ordinal") instanceof Integer n ? n : -1;
			if (!CarrierRenames.carries(rows, renamed, anchor, ordinal)) return null;
		}
		CarrierRenames.Kind kind = CarrierRenames.kind(rows);
		if (!called) return movableWhole(handler, injector, true, true) ? new Carried(kind, false) : null;
		if (kind == CarrierRenames.Kind.PIECE) {
			// The method's arguments are the piece's when it has none, or hands the piece its own.
			boolean sameArguments = Type.getArgumentTypes(selected.desc).length == 0
					|| CarrierRenames.handsOwnArguments(target, selected, renamed);
			// A cancel returns from the piece. It means what it meant in the method only when the method returns what the
			// handler cancels with: the table and the live bytes say the method returns the piece's lefts, and every value
			// the handler can cancel with is a left.
			boolean cancelsAsBefore = leftsReturned(rows, target, selected, renamed) && cancelsOnlyWithLefts(mixin, handler, injector);
			if (!movableWhole(handler, injector, sameArguments, cancelsAsBefore)
					|| !sameArguments && takesArguments(handler, injector, renamed)) return null;
		}
		return new Carried(kind, true);
	}

	/** {@code -Dforbric.mixinRetarget.renameCensus.leftExit=off}: no handler that can cancel moves into a piece. */
	static final String LEFT_EXIT_PROPERTY = "forbric.mixinRetarget.renameCensus.leftExit";

	/** The rows mark the pair {@link CarrierRenames.Exit#LEFT} and the live method still returns the piece's lefts. */
	private static boolean leftsReturned(List<CarrierRenames.Row> rows, ClassNode target, MethodNode selected, MethodNode renamed) {
		return !"off".equalsIgnoreCase(System.getProperty(LEFT_EXIT_PROPERTY, "on"))
				&& CarrierRenames.exit(rows) == CarrierRenames.Exit.LEFT && CarrierRenames.returnsLefts(target, selected, renamed);
	}

	private static final String CANCELLABLE_SUGAR = "Lcom/llamalad7/mixinextras/sugar/Cancellable;";
	private static final String CALLBACKS = "org/spongepowered/asm/mixin/injection/callback/";

	/**
	 * Whether every value the handler can cancel its target with is an {@code Either.left(..)}: the callback of a
	 * cancellable {@code @Inject} and every {@code @Cancellable} one is only ever handed a value made by
	 * {@code Either.left} right there ({@code setReturnValue}), read, or passed on to a method of the mixin's own — a
	 * lambda it creates, a private helper — that does the same; never {@code cancel()}ed, stored in a field, returned, or
	 * given to anything else. A handler that cannot cancel passes. apoli's {@code preventAvianSleep} passes its callback
	 * to a lambda over its powers that sets {@code Either.left(null)}; fabric-entity-events' {@code redirectSleepDirection}
	 * sets {@code Either.left(OTHER_PROBLEM)} itself.
	 */
	static boolean cancelsOnlyWithLefts(ClassNode mixin, MethodNode handler, AnnotationNode injector) {
		Type[] params = Type.getArgumentTypes(handler.desc);
		List<Integer> callbacks = new ArrayList<>();
		if (INJECT.equals(injector.desc) && Boolean.TRUE.equals(MixinFit.value(injector, "cancellable"))) {
			for (int i = 0; i < params.length; i++) {
				String desc = params[i].getDescriptor();
				if (CALLBACK_INFO.equals(desc) || CALLBACK_INFO_RETURNABLE.equals(desc)) { callbacks.add(i); break; }
			}
			if (callbacks.isEmpty()) return false;
		}
		for (int i = 0; i < params.length; i++) if (MixinStubRebind.sugar(handler, i, CANCELLABLE_SUGAR) != null) callbacks.add(i);
		for (int callback : callbacks) if (!onlyLefts(mixin, handler, callback, new java.util.HashSet<>(), 0)) return false;
		return true;
	}

	/** Whether {@code method}'s parameter {@code index}, a callback, is used only as {@link #cancelsOnlyWithLefts} allows. */
	private static boolean onlyLefts(ClassNode mixin, MethodNode method, int index, Set<String> seen, int depth) {
		if (depth > 4 || method.instructions == null || method.instructions.size() == 0) return false;
		if (!seen.add(method.name + method.desc + "#" + index)) return true;
		boolean isStatic = (method.access & Opcodes.ACC_STATIC) != 0;
		Type[] params = Type.getArgumentTypes(method.desc);
		if (index >= params.length) return false;
		int slot = isStatic ? 0 : 1;
		for (int i = 0; i < index; i++) slot += params[i].getSize();
		CallbackUses uses = new CallbackUses(mixin, slot);
		try {
			new org.objectweb.asm.tree.analysis.Analyzer<>(uses).analyze(mixin.name, method);
		} catch (org.objectweb.asm.tree.analysis.AnalyzerException unreadable) {
			return false;
		}
		if (uses.misused) return false;
		for (Map.Entry<MethodNode, Integer> passed : uses.passed.entrySet()) {
			if (!onlyLefts(mixin, passed.getKey(), passed.getValue(), seen, depth + 1)) return false;
		}
		return true;
	}

	/** A value in {@link CallbackUses}: whether it may be the callback, and whether it is surely an {@code Either.left}. */
	private record Tracked(org.objectweb.asm.tree.analysis.BasicValue basic, boolean callback, boolean left)
			implements org.objectweb.asm.tree.analysis.Value {
		@Override
		public int getSize() {
			return basic.getSize();
		}
	}

	/** Follows a callback through a method and records every use {@link #cancelsOnlyWithLefts} does not allow. */
	private static final class CallbackUses extends org.objectweb.asm.tree.analysis.Interpreter<Tracked> {
		private final org.objectweb.asm.tree.analysis.BasicInterpreter basic = new org.objectweb.asm.tree.analysis.BasicInterpreter();
		private final ClassNode mixin;
		private final int slot;
		boolean misused;
		final Map<MethodNode, Integer> passed = new LinkedHashMap<>();

		CallbackUses(ClassNode mixin, int slot) {
			super(Opcodes.ASM9);
			this.mixin = mixin;
			this.slot = slot;
		}

		private static Tracked plain(org.objectweb.asm.tree.analysis.BasicValue value) {
			return value == null ? null : new Tracked(value, false, false);
		}

		@Override
		public Tracked newValue(Type type) {
			return plain(basic.newValue(type));
		}

		@Override
		public Tracked newParameterValue(boolean isInstanceMethod, int local, Type type) {
			return new Tracked(basic.newParameterValue(isInstanceMethod, local, type), local == slot, false);
		}

		@Override
		public Tracked newOperation(AbstractInsnNode insn) throws org.objectweb.asm.tree.analysis.AnalyzerException {
			return plain(basic.newOperation(insn));
		}

		@Override
		public Tracked copyOperation(AbstractInsnNode insn, Tracked value) throws org.objectweb.asm.tree.analysis.AnalyzerException {
			return new Tracked(basic.copyOperation(insn, value.basic()), value.callback(), value.left());
		}

		@Override
		public Tracked unaryOperation(AbstractInsnNode insn, Tracked value) throws org.objectweb.asm.tree.analysis.AnalyzerException {
			org.objectweb.asm.tree.analysis.BasicValue out = basic.unaryOperation(insn, value.basic());
			// A cast keeps what the value is; anything else done to the callback is a use this cannot follow.
			if (insn.getOpcode() == Opcodes.CHECKCAST) return out == null ? null : new Tracked(out, value.callback(), value.left());
			if (value.callback()) misused = true;
			return plain(out);
		}

		@Override
		public Tracked binaryOperation(AbstractInsnNode insn, Tracked one, Tracked two) throws org.objectweb.asm.tree.analysis.AnalyzerException {
			if (one.callback() || two.callback()) misused = true;
			return plain(basic.binaryOperation(insn, one.basic(), two.basic()));
		}

		@Override
		public Tracked ternaryOperation(AbstractInsnNode insn, Tracked one, Tracked two, Tracked three)
				throws org.objectweb.asm.tree.analysis.AnalyzerException {
			if (one.callback() || two.callback() || three.callback()) misused = true;
			return plain(basic.ternaryOperation(insn, one.basic(), two.basic(), three.basic()));
		}

		@Override
		public Tracked naryOperation(AbstractInsnNode insn, List<? extends Tracked> values) throws org.objectweb.asm.tree.analysis.AnalyzerException {
			List<org.objectweb.asm.tree.analysis.BasicValue> raw = new ArrayList<>();
			for (Tracked value : values) raw.add(value.basic());
			org.objectweb.asm.tree.analysis.BasicValue out = basic.naryOperation(insn, raw);
			if (insn instanceof MethodInsnNode call) {
				boolean receiver = call.getOpcode() != Opcodes.INVOKESTATIC;
				for (int i = receiver ? 1 : 0; i < values.size(); i++) {
					if (values.get(i).callback()) pass(call.owner, call.name, call.desc, i - (receiver ? 1 : 0), call.getOpcode());
				}
				if (receiver && values.get(0).callback()) {
					String name = call.name;
					boolean reads = name.startsWith("getReturnValue") || name.equals("isCancelled") || name.equals("isCancellable")
							|| name.equals("getId");
					boolean setsLeft = name.equals("setReturnValue") && values.size() == 2 && values.get(1).left();
					if (!call.owner.startsWith(CALLBACKS) || !reads && !setsLeft) misused = true;
				}
				boolean left = call.getOpcode() == Opcodes.INVOKESTATIC && call.owner.equals("com/mojang/datafixers/util/Either")
						&& call.name.equals("left") && call.desc.equals("(Ljava/lang/Object;)Lcom/mojang/datafixers/util/Either;");
				return out == null ? null : new Tracked(out, false, left);
			}
			if (insn instanceof org.objectweb.asm.tree.InvokeDynamicInsnNode indy) {
				org.objectweb.asm.Handle body = indy.bsm.getOwner().equals("java/lang/invoke/LambdaMetafactory") && indy.bsmArgs.length > 1
						&& indy.bsmArgs[1] instanceof org.objectweb.asm.Handle h ? h : null;
				for (int i = 0; i < values.size(); i++) {
					if (!values.get(i).callback()) continue;
					if (body == null) { misused = true; continue; }
					boolean instance = body.getTag() != Opcodes.H_INVOKESTATIC;
					// A lambda's captured values come first among its body's parameters, the receiver before them.
					pass(body.getOwner(), body.getName(), body.getDesc(), i - (instance ? 1 : 0), instance ? Opcodes.INVOKESPECIAL : Opcodes.INVOKESTATIC);
				}
				return plain(out);
			}
			for (Tracked value : values) if (value.callback()) misused = true;
			return plain(out);
		}

		/** The callback handed on as parameter {@code index} of {@code owner.name desc}: only to a method of the mixin's own. */
		private void pass(String owner, String name, String desc, int index, int opcode) {
			MethodNode to = null;
			if (owner.equals(mixin.name) && index >= 0) {
				for (MethodNode m : mixin.methods) if (m.name.equals(name) && m.desc.equals(desc)) to = m;
			}
			boolean own = to != null && ((to.access & (Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC)) != 0)
					&& ((to.access & Opcodes.ACC_STATIC) != 0) == (opcode == Opcodes.INVOKESTATIC);
			if (!own) { misused = true; return; }
			passed.put(to, index);
		}

		@Override
		public void returnOperation(AbstractInsnNode insn, Tracked value, Tracked expected) {
			if (value.callback()) misused = true;
		}

		@Override
		public Tracked merge(Tracked one, Tracked two) {
			org.objectweb.asm.tree.analysis.BasicValue merged = basic.merge(one.basic(), two.basic());
			return new Tracked(merged, one.callback() || two.callback(), one.left() && two.left());
		}
	}

	/**
	 * Whether every handler of {@code mixin} that shares a value with {@code handler} in {@code selected} — transitively,
	 * in the mixin's own namespace — moves to {@code renamed} too. MixinExtras keeps one value per key per target method,
	 * so a sharer left behind (a HEAD handler that fills it, say) would hand the moved one a fresh value. A key in an
	 * explicit namespace may be shared with injectors of other mixins this cannot see, and keeps the handler where it is.
	 */
	private static boolean shareGroupMoves(ClassNode mixin, boolean oneTarget, MethodNode handler, ClassNode target,
			MethodNode selected, MethodNode renamed, Function<String, byte[]> resolver) {
		if (namespacedShare(handler)) return false;
		Set<String> keys = new java.util.HashSet<>(MixinStubRebind.shareKeys(handler));
		if (keys.isEmpty()) return true;
		List<MethodNode> group = new ArrayList<>(List.of(handler));
		for (boolean grew = true; grew; ) {
			grew = false;
			for (MethodNode other : mixin.methods) {
				if (group.contains(other) || java.util.Collections.disjoint(MixinStubRebind.shareKeys(other), keys)
						|| !MixinStubRebind.mayBind(other, target, selected)) continue;
				group.add(other);
				keys.addAll(MixinStubRebind.shareKeys(other));
				grew = true;
			}
		}
		for (MethodNode other : group) {
			if (other == handler) continue;
			AnnotationNode theirs = MixinFit.injectorOf(other);
			if (theirs == null || namespacedShare(other)) return false;
			List<String> selectors = MixinFit.stringList(MixinFit.value(theirs, "method"));
			boolean moves = renamedBodies(mixin, oneTarget, other, theirs, selectors, target, resolver, false).stream()
					.anyMatch(r -> r.element() == Element.SELECTOR && r.to().equals(renamed.name + renamed.desc));
			if (!moves) return false;
		}
		return true;
	}

	/** Whether any {@code @Share} on the handler names a namespace of its own. */
	private static boolean namespacedShare(MethodNode handler) {
		for (int i = 0; i < Type.getArgumentTypes(handler.desc).length; i++) {
			AnnotationNode share = MixinStubRebind.sugar(handler, i, SHARE_SUGAR);
			if (share != null && MixinFit.value(share, "namespace") != null) return true;
		}
		return false;
	}

	private static final String SHARE_SUGAR = "Lcom/llamalad7/mixinextras/sugar/Share;";

	/**
	 * Whether the handler takes arguments of the method it injects into: an {@code @Inject}'s parameters before its
	 * callback, or what an {@code @At}-driven handler takes after the access's own receiver, arguments and
	 * {@code Operation} ({@link MixinStubRebind#intrinsicArity}, read in {@code body}, where the anchor is). A shape that
	 * cannot be read counts as taking them.
	 */
	private static boolean takesArguments(MethodNode handler, AnnotationNode injector, MethodNode body) {
		Type[] params = Type.getArgumentTypes(handler.desc);
		int end = params.length;
		for (int i = 0; i < params.length; i++) if (MixinFit.sugar(handler, i)) { end = i; break; }
		if (INJECT.equals(injector.desc)) {
			for (int i = 0; i < end; i++) {
				String desc = params[i].getDescriptor();
				if (CALLBACK_INFO.equals(desc) || CALLBACK_INFO_RETURNABLE.equals(desc)) return i > 0;
			}
			return end > 0;
		}
		int own = MixinStubRebind.intrinsicArity(injector, params, end, body);
		return own < 0 || own < end;
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
	 * {@code @Group}, and no point but calls and field accesses; and a mixin with one target, since the new selector
	 * names that target's piece. {@code -Dforbric.mixinRetarget.split=off} refuses two fits as before.
	 */
	private static Rewrite splitHelper(String mixinName, MethodNode handler, AnnotationNode injector, String selector,
			ClassNode target, MethodNode selected, List<MethodNode> fits, List<String> wanted) {
		if ("off".equalsIgnoreCase(System.getProperty(SPLIT_PROPERTY, "on"))) return null;
		net.forbric.api.Ecosystem ecosystem = MixinStubRebind.ecosystemOf(mixinName);
		if (ecosystem == null || !movableWhole(handler, injector, false, false)) return null;
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
	 * {@code @Group}, in a mixin with one target (the new point names that target's helper); other kinds' handlers
	 * describe the call, and moving them would need the helper to have no other caller, which a protected override
	 * point cannot promise. {@code -Dforbric.mixinRetarget.extractedHelper=off}
	 * leaves the point as compiled. An AFTER point with no census row may still follow a proven extraction in
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
	 * A static extraction preserves AFTER only when the original call remains the hook's unconditional final
	 * effect with the same operands. Additional event or state changes after it invalidate that correspondence.
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
		return new Rewrite(handler.name, Element.AT_TARGET, member, row.hook(), row.because());
	}

	/**
	 * Rule R6: an {@code @Inject} whose {@code @At(INVOKE)} names a call the surviving carrier SUBSTITUTED — the method
	 * kept its body, instruction for instruction and local for local, and makes a different call taking the same
	 * arguments at that one instruction ({@link MergedBaseCalleeSwaps#SUBSTITUTED}) — has its point moved to the call
	 * the merged body makes. The selector stays, and with it everything the handler is bound to: the method's
	 * arguments, its callback, and the locals at that instruction, which the row's census proves are the ones the mod
	 * was compiled against.
	 *
	 * <p>NeoForge substituted {@code UnbakedModelParser.parse} for {@code CuboidModel.fromStream} in
	 * {@code ModelManager.lambda$loadBlockModels$2}, which parses each block-model file. fusion's MinecraftForge build
	 * records the id of the model about to be parsed BEFORE {@code fromStream}, and its hook in the vanilla model
	 * deserializer names every fusion model it builds with that id (the connected-texture models Rechiseled and
	 * Anti-Blocks ship). The anchor missed and the handler attached nowhere, so every fusion model was built as
	 * {@code fusion:unknown} and its warnings and bake errors named that instead of the file; and since fusion requires
	 * its mixins, a STRICT client asked to continue or quit on every launch.
	 *
	 * <p>R2 cannot take this: its rows keep the callee's descriptor, because the kinds it moves are shaped by the
	 * callee. Only an {@code @Inject} follows a substitution, since its handler sees neither the call's arguments nor
	 * its result, only the point. And only with no slice, {@code @Group}, sugar, parameter annotation or {@code @At}
	 * args; a shift of BEFORE or AFTER and no ordinal past the first (the row's call is made once); for a mod of an
	 * ecosystem the row lists (the others were compiled against the replacement, or against neither, and miss natively
	 * too); in a mixin with one target. A handler that captures locals moves only when the live method's local variable
	 * table holds exactly those at the new call ({@link InsertedLambdaArgumentShim#localsAtCall}) — Mixin reads the same
	 * table to decide what it captures; with no table there is no proof and the point stays.
	 *
	 * <p>The handler then runs where it never ran on this base, and a {@code LinkageError} from it — code compiled
	 * against another base — is not an {@code Exception}: in the one row's method it would escape the per-file catch,
	 * fail the whole resource reload, and a failed reload drops every resource pack. So the move also puts the handler
	 * behind a guard ({@link Element#GUARD}) that says so once and skips it, which is where things stood before the
	 * move. {@code -Dforbric.mixinRetarget.substitutedCall=off} leaves every such point as compiled, and
	 * {@code -Dforbric.mixinRetarget.substitutedCall.guard=off} moves it without the guard.
	 */
	private static List<Rewrite> substitutedCalls(String mixinName, MethodNode handler, AnnotationNode injector,
			List<String> selectors, ClassNode target, Function<String, byte[]> resolver) {
		if (!INJECT.equals(injector.desc) || selectors.size() != 1
				|| "off".equalsIgnoreCase(System.getProperty(SUBSTITUTED_CALL_PROPERTY, "on"))) return List.of();
		net.forbric.api.Ecosystem ecosystem = MixinStubRebind.ecosystemOf(mixinName);
		if (ecosystem == null) return List.of();
		List<MethodNode> own = resolveSelector(target, selectors.get(0), resolver).stream().filter(target.methods::contains).toList();
		if (own.size() != 1) return List.of();
		MethodNode method = own.get(0);
		Type[] captured = callbackLocals(handler, injector, method);
		if (captured == null) return List.of();

		List<Rewrite> out = new ArrayList<>();
		MethodNode withLocals = null;
		for (AnnotationNode at : MixinFit.atNodes(injector)) {
			String member = MixinFit.asString(MixinFit.value(at, "target"));
			if (!"INVOKE".equals(MixinFit.asString(MixinFit.value(at, "value"))) || member == null
					|| MixinFit.containsMember(method, member) || MixinFit.value(at, "args") != null) continue;
			String shift = MixinFit.asString(MixinFit.value(at, "shift"));
			if (shift != null && !"BEFORE".equals(shift) && !"AFTER".equals(shift)) continue;
			if (MixinFit.value(at, "ordinal") instanceof Integer ordinal && ordinal > 0) continue;
			MergedBaseCalleeSwaps.Substitution row = MergedBaseCalleeSwaps.substitution(target.name,
					method.name + method.desc, member, ecosystem);
			if (row == null || CarrierHelpers.occurrences(method, row.replacement()) != 1) continue;
			if (captured.length > 0) {
				if (withLocals == null) withLocals = withLocalVariables(target.name, method, resolver);
				if (withLocals == null || !InsertedLambdaArgumentShim.localsAtCall(row.replacement(), withLocals, captured)) continue;
			}
			MixinFit.Member callee = MixinFit.parseMember(row.replacement());
			out.add(new Rewrite(handler.name, Element.AT_TARGET, member, row.replacement(), "the carrier substituted "
					+ callee.owner().substring(callee.owner().lastIndexOf('/') + 1) + "." + callee.name() + " for this call "
					+ "at the same instruction of an otherwise unchanged body, a census-pinned row of MergedBaseCalleeSwaps"));
		}
		if (!out.isEmpty() && !"off".equalsIgnoreCase(System.getProperty(SUBSTITUTED_CALL_GUARD_PROPERTY, "on"))) {
			out.add(new Rewrite(handler.name, Element.GUARD, handler.name + handler.desc,
					MixinHandlerShim.asideName(mixinName, handler.name, GUARDED_SUFFIX),
					"a LinkageError from the moved handler skips it instead of failing the method it now runs in"));
		}
		return out;
	}

	/**
	 * Rule R7: an {@code @Inject} along a {@link MergedBaseCalleeSwaps#REPLACED} row — a private vanilla method the
	 * surviving carrier replaced at its one call site with one of its own, renamed and taking what vanilla's arguments are
	 * read from.
	 *
	 * <p>NeoForge's {@code StructureTemplate.placeInWorld} calls {@code addEntitiesToWorld(level, pos, settings, reporter)}
	 * where vanilla's called {@code placeEntities(level, pos, mirror, rotation, pivot, boundingBox, finalize, reporter)},
	 * each read off the settings; vanilla's method is gone from the merged base, MinecraftForge's reshaped one beside it
	 * runs nowhere. MoogsStructureLib's Fabric {@code EntityProcessorMixin} records the placement's context BEFORE the
	 * vanilla call, clears it AFTER, and at the HEAD of {@code placeEntities} runs its own entity processors and cancels.
	 * Both points missed, and its name-only HEAD selector bound MinecraftForge's overload, whose descriptor its handler
	 * does not have: an {@code InvalidInjectionException} the kernel reports as a failed feature, and the server stopped
	 * once its world was loaded.
	 *
	 * <p>Two moves, both only for a mod of an ecosystem the row lists, only for an {@code @Inject} with no sugar, slice,
	 * {@code @Group} or locals capture, in a mixin with one target:
	 * <ul>
	 *   <li>an {@code @At(INVOKE)} on the vanilla method, in the row's caller, BEFORE or AFTER and no ordinal past the
	 *       first, points at the replacement's call (the caller makes it exactly once, and never the vanilla one);</li>
	 *   <li>a selector naming the vanilla method — by name, or by vanilla's descriptor — whose every point is
	 *       {@code HEAD}, {@code RETURN} or {@code TAIL}, and whose handler takes vanilla's arguments and its callback
	 *       (or the callback alone), moves to the replacement: the handler is renamed aside and a method of its name and
	 *       annotation takes the replacement's arguments and hands the original vanilla's, read off them as the row says
	 *       ({@link Element#PROJECT}).</li>
	 * </ul>
	 * The target must declare the replacement and must not declare the vanilla method itself (then Mixin binds it
	 * natively). {@code -Dforbric.mixinRetarget.replacedCall=off} leaves both as compiled.
	 */
	private static List<Rewrite> replacedCalls(String mixinName, MethodNode handler, AnnotationNode injector,
			List<String> selectors, ClassNode target) {
		if (!INJECT.equals(injector.desc) || selectors.size() != 1
				|| "off".equalsIgnoreCase(System.getProperty(REPLACED_CALL_PROPERTY, "on"))) return List.of();
		net.forbric.api.Ecosystem ecosystem = MixinStubRebind.ecosystemOf(mixinName);
		if (ecosystem == null || !plainInject(handler, injector)) return List.of();
		String selector = selectors.get(0).trim();
		if (selector.startsWith("L") && selector.indexOf(';') > 0) selector = selector.substring(selector.indexOf(';') + 1);
		int paren = selector.indexOf('(');
		String name = paren < 0 ? selector : selector.substring(0, paren), desc = paren < 0 ? null : selector.substring(paren);
		List<Rewrite> out = new ArrayList<>();

		MergedBaseCalleeSwaps.Replaced named = MergedBaseCalleeSwaps.replaced(target.name, name, desc, ecosystem);
		if (named != null && replacementOf(target, named) != null && CarrierHelpers.declared(target, name, vanillaDesc(named)) == null) {
			if (projectable(handler, injector, named)) {
				Rewrite projection = new Rewrite(handler.name, Element.PROJECT, handler.name + handler.desc, named.replacement(),
						"the carrier replaced " + name + " with " + named.replacement().substring(0, named.replacement().indexOf('('))
								+ "; the handler reads vanilla's arguments off its arguments");
				MergedBaseCalleeSwaps.projection(projection, named);
				out.add(projection);
			}
			return out;
		}

		MethodNode caller = CarrierHelpers.declared(target, name, desc == null ? "" : desc);
		if (caller == null) {
			for (MethodNode m : target.methods) if (m.name.equals(name) && caller == null) caller = m;
		}
		if (caller == null) return out;
		for (AnnotationNode at : MixinFit.atNodes(injector)) {
			String member = MixinFit.asString(MixinFit.value(at, "target"));
			if (!"INVOKE".equals(MixinFit.asString(MixinFit.value(at, "value"))) || member == null
					|| MixinFit.containsMember(caller, member) || MixinFit.value(at, "args") != null) continue;
			String shift = MixinFit.asString(MixinFit.value(at, "shift"));
			if (shift != null && !"BEFORE".equals(shift) && !"AFTER".equals(shift)) continue;
			if (MixinFit.value(at, "ordinal") instanceof Integer ordinal && ordinal > 0) continue;
			MixinFit.Member want = MixinFit.parseMember(member);
			if (want == null || (want.owner() != null && !want.owner().equals(target.name))) continue;
			MergedBaseCalleeSwaps.Replaced row = MergedBaseCalleeSwaps.replaced(target.name, want.name(), want.desc(), ecosystem);
			if (row == null || !row.caller().equals(caller.name + caller.desc) || replacementOf(target, row) == null) continue;
			if (CarrierHelpers.occurrences(caller, row.replacementMember()) != 1) continue;
			out.add(new Rewrite(handler.name, Element.AT_TARGET, member, row.replacementMember(), "the carrier replaced "
					+ want.name() + " with " + row.replacement().substring(0, row.replacement().indexOf('(')) + " at this, its one "
					+ "call, a reviewed row of MergedBaseCalleeSwaps"));
		}
		return out;
	}

	private static String vanillaDesc(MergedBaseCalleeSwaps.Replaced row) {
		return row.vanilla().substring(row.vanilla().indexOf('('));
	}

	private static MethodNode replacementOf(ClassNode target, MergedBaseCalleeSwaps.Replaced row) {
		int paren = row.replacement().indexOf('(');
		return CarrierHelpers.declared(target, row.replacement().substring(0, paren), row.replacement().substring(paren));
	}

	/**
	 * R7's selector move applies: every point is the method's HEAD, RETURN or TAIL, and the handler is void and takes
	 * vanilla's arguments then its callback, or the callback alone.
	 */
	private static boolean projectable(MethodNode handler, AnnotationNode injector, MergedBaseCalleeSwaps.Replaced row) {
		List<AnnotationNode> points = MixinFit.atNodes(injector);
		if (points.isEmpty()) return false;
		for (AnnotationNode at : points) {
			String value = MixinFit.asString(MixinFit.value(at, "value"));
			if (!"HEAD".equals(value) && !"RETURN".equals(value) && !"TAIL".equals(value)) return false;
		}
		if (!Type.VOID_TYPE.equals(Type.getReturnType(handler.desc))) return false;
		Type[] params = Type.getArgumentTypes(handler.desc);
		Type[] vanilla = Type.getArgumentTypes(vanillaDesc(row));
		if (params.length != 1 && params.length != vanilla.length + 1) return false;
		for (int i = 0; i + 1 < params.length; i++) if (!params[i].equals(vanilla[i])) return false;
		String callback = params[params.length - 1].getDescriptor();
		return (CALLBACK_INFO.equals(callback) || CALLBACK_INFO_RETURNABLE.equals(callback))
				&& row.arguments().size() == vanilla.length;
	}

	/**
	 * The locals an {@code @Inject} handler captures after its callback, empty when it captures none; null when the
	 * handler is not a plain callback of {@code method}: void, its arguments and callback (or the callback alone), no
	 * sugar or other parameter annotation, no slice, no {@code @Group}, and locals only under a {@code CAPTURE_*} mode.
	 */
	private static Type[] callbackLocals(MethodNode handler, AnnotationNode injector, MethodNode method) {
		List<AnnotationNode> annotations = new ArrayList<>();
		if (handler.visibleAnnotations != null) annotations.addAll(handler.visibleAnnotations);
		if (handler.invisibleAnnotations != null) annotations.addAll(handler.invisibleAnnotations);
		if (annotations.stream().anyMatch(a -> GROUP.equals(a.desc)) || MixinFit.value(injector, "slice") != null) return null;
		for (List<AnnotationNode>[] set : java.util.Arrays.asList(handler.visibleParameterAnnotations, handler.invisibleParameterAnnotations)) {
			if (set != null) for (List<AnnotationNode> list : set) if (list != null && !list.isEmpty()) return null;
		}
		if (!Type.VOID_TYPE.equals(Type.getReturnType(handler.desc))) return null;
		Type[] params = Type.getArgumentTypes(handler.desc);
		Type[] args = Type.getArgumentTypes(method.desc);
		int callback;
		if (params.length == 1) {
			callback = 0;
		} else {
			if (params.length < args.length + 1) return null;
			for (int i = 0; i < args.length; i++) if (!params[i].equals(args[i])) return null;
			callback = args.length;
		}
		String descriptor = params.length == 0 ? null : params[callback].getDescriptor();
		if (!CALLBACK_INFO.equals(descriptor) && !CALLBACK_INFO_RETURNABLE.equals(descriptor)) return null;
		Type[] captured = java.util.Arrays.copyOfRange(params, callback + 1, params.length);
		Object locals = MixinFit.value(injector, "locals");
		String mode = MixinFit.asString(locals);
		boolean capturing = mode != null && !"NO_CAPTURE".equals(mode);
		if (capturing && !mode.startsWith("CAPTURE_")) return null;
		if (!capturing && captured.length > 0) return null;
		return captured;
	}

	/** {@code method} read again from the target's bytes WITH its local variable table, which MixinFit's read drops. */
	private static MethodNode withLocalVariables(String targetName, MethodNode method, Function<String, byte[]> resolver) {
		byte[] bytes = resolver.apply(targetName + ".class");
		if (bytes == null) return null;
		ClassNode node = new ClassNode();
		new ClassReader(bytes).accept(node, ClassReader.SKIP_FRAMES);
		return CarrierHelpers.declared(node, method.name, method.desc);
	}

	/**
	 * Called from R6's guard, inside the target class, when a moved handler throws a {@code LinkageError}. The
	 * handler is skipped and the method carries on, as it did before the move; the first time for each handler that
	 * is said, with the error, because nothing else would say it.
	 */
	public static void hookDidNotLink(LinkageError error, String hook) {
		if (!SKIPPED.add(hook)) return;
		ForbricLog.warn("[Forbric/Mixin] %s does not link on the merged base (%s) — skipped wherever it runs, as it was "
				+ "before its injection point was moved to the call the merge substituted, instead of failing the method "
				+ "around it (for a block-model hook, the whole resource reload, which drops every resource pack)",
				hook, String.valueOf(error));
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
	 * so that it means the same in a piece of the method as in the whole: see {@link #splitHelper}. With
	 * {@code argumentsInPlace} (the method hands the piece its own arguments) an {@code @Local(argsOnly = true)} is
	 * such an argument; with {@code cancelsAsBefore} (a cancel leaves the method as it did, see {@link #renamedBodies})
	 * a cancellable {@code @Inject} or a {@code @Cancellable} callback is allowed; any other sugar is not.
	 */
		static boolean movableWhole(MethodNode handler, AnnotationNode injector, boolean argumentsInPlace,
			boolean cancelsAsBefore) {
		List<AnnotationNode> annotations = new ArrayList<>();
		if (handler.visibleAnnotations != null) annotations.addAll(handler.visibleAnnotations);
		if (handler.invisibleAnnotations != null) annotations.addAll(handler.invisibleAnnotations);
		if (annotations.stream().anyMatch(a -> GROUP.equals(a.desc))) return false;
		if (MixinFit.value(injector, "slice") != null) return false;
		if (INJECT.equals(injector.desc)) {
			if (Boolean.TRUE.equals(MixinFit.value(injector, "cancellable")) && !cancelsAsBefore
					|| MixinFit.value(injector, "locals") != null) return false;
		} else if (!AT_DRIVEN.contains(injector.desc)) {
			return false;
		}
		List<AnnotationNode> points = MixinFit.atNodes(injector);
		if (points.isEmpty()) return false;
		for (AnnotationNode at : points) {
			String value = MixinFit.asString(MixinFit.value(at, "value"));
			if (value == null || !MixinFit.RESOLVABLE_AT.contains(value) || MixinFit.value(at, "target") == null) return false;
		}
		for (int i = 0; i < Type.getArgumentTypes(handler.desc).length; i++) {
			if (!MixinFit.sugar(handler, i) || argumentsInPlace && argumentLocal(handler, i)) continue;
			if (cancelsAsBefore && onlySugar(handler, i, CANCELLABLE_SUGAR)) continue;
			return false;
		}
		return true;
	}

	/** Whether {@code desc} is the only MixinExtras sugar on the handler's parameter {@code index}. */
	private static boolean onlySugar(MethodNode handler, int index, String desc) {
		boolean found = false;
		for (List<AnnotationNode>[] set : java.util.Arrays.asList(handler.visibleParameterAnnotations, handler.invisibleParameterAnnotations)) {
			if (set == null || index >= set.length || set[index] == null) continue;
			for (AnnotationNode a : set[index]) {
				if (!a.desc.startsWith("Lcom/llamalad7/mixinextras/sugar/")) continue;
				if (!a.desc.equals(desc)) return false;
				found = true;
			}
		}
		return found;
	}

	/** Whether every sugar on the handler's parameter {@code index} is an {@code @Local(argsOnly = true)}: a target argument. */
	private static boolean argumentLocal(MethodNode handler, int index) {
		boolean local = false;
		for (List<AnnotationNode>[] set : java.util.Arrays.asList(handler.visibleParameterAnnotations, handler.invisibleParameterAnnotations)) {
			if (set == null || index >= set.length || set[index] == null) continue;
			for (AnnotationNode a : set[index]) {
				if (!a.desc.startsWith("Lcom/llamalad7/mixinextras/sugar/")) continue;
				if (!LOCAL_SUGAR.equals(a.desc) || !Boolean.TRUE.equals(MixinFit.value(a, "argsOnly"))) return false;
				local = true;
			}
		}
		return local;
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
			for (int i = 0; i < params.length; i++) if (MixinStubRebind.trailingSugar(handler, i)) { end = i; break; }
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
			if (rewrite.element() == Element.LOCAL_INDEX) {
				for (MethodNode handler : node.methods) if ((handler.name + handler.desc).equals(rewrite.handler())) {
					AnnotationNode local = MixinStubRebind.sugar(handler, Integer.parseInt(rewrite.from()), LOCAL_SUGAR);
					if (local == null) continue;
					if (local.values == null) local.values = new ArrayList<>();
					for (int i = local.values.size() - 2; i >= 0; i -= 2) if (Set.of("name", "ordinal", "index").contains(local.values.get(i))) {
						local.values.remove(i + 1); local.values.remove(i);
					}
					local.values.addAll(List.of("index", Integer.parseInt(rewrite.to()))); applied++;
				}
				continue;
			}
			if (rewrite.element() == Element.GUARD) {
				if (guard(node, rewrite)) applied++;
				continue;
			}
			if (rewrite.element() == Element.PROJECT) {
				if (project(node, rewrite)) applied++;
				continue;
			}
			for (MethodNode m : node.methods) {
				if (!m.name.equals(rewrite.handler()) && !(m.name + m.desc).equals(rewrite.handler())) continue;
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

	/**
	 * R6's guard: the handler {@code rewrite.from()} names keeps its name, descriptor and injector annotation, and its
	 * body moves to {@code rewrite.to()}. What now carries the annotation calls that body inside a {@code try} and, on
	 * a {@code LinkageError}, reports it ({@link #hookDidNotLink}) and returns — the callback left as the handler found
	 * it, so the target method carries on as if the handler had not been there. Any other throwable passes through
	 * untouched, as it does natively.
	 *
	 * <p>The handler's frame is written by hand, as in GuestMixinPluginGuard: the starting locals and one
	 * {@code LinkageError}. Mixin writes the target class with {@code COMPUTE_FRAMES} anyway; the frame is for anything
	 * that writes the mixin class node without it. Returns false, changing nothing, when the handler is not there or
	 * already guarded.
	 */
	private static boolean guard(ClassNode mixin, Rewrite rewrite) {
		MethodNode handler = null;
		for (MethodNode m : mixin.methods) {
			if ((m.name + m.desc).equals(rewrite.from()) && MixinFit.injectorOf(m) != null) handler = m;
		}
		if (handler == null) return false;
		for (MethodNode m : mixin.methods) if (m.name.equals(rewrite.to()) && m.desc.equals(handler.desc)) return false;
		AnnotationNode injector = MixinFit.injectorOf(handler);
		boolean isStatic = (handler.access & Opcodes.ACC_STATIC) != 0;

		MethodNode outer = new MethodNode(Opcodes.ASM9, handler.access, handler.name, handler.desc, handler.signature,
				handler.exceptions == null ? null : handler.exceptions.toArray(new String[0]));
		boolean visible = handler.visibleAnnotations != null && handler.visibleAnnotations.remove(injector);
		if (!visible && handler.invisibleAnnotations != null) handler.invisibleAnnotations.remove(injector);
		if (visible) outer.visibleAnnotations = new ArrayList<>(List.of(injector));
		else outer.invisibleAnnotations = new ArrayList<>(List.of(injector));

		LabelNode start = new LabelNode(), end = new LabelNode(), caught = new LabelNode();
		outer.tryCatchBlocks.add(new TryCatchBlockNode(start, end, caught, LINKAGE_ERROR));
		InsnList code = outer.instructions;
		code.add(start);
		int slot = 0;
		if (!isStatic) code.add(new VarInsnNode(Opcodes.ALOAD, slot++));
		for (Type arg : Type.getArgumentTypes(handler.desc)) {
			code.add(new VarInsnNode(arg.getOpcode(Opcodes.ILOAD), slot));
			slot += arg.getSize();
		}
		code.add(MixinHandlerShim.callOwn(mixin, isStatic, rewrite.to(), handler.desc));
		code.add(end);
		code.add(new InsnNode(Opcodes.RETURN));
		code.add(caught);
		code.add(new FrameNode(Opcodes.F_SAME1, 0, null, 1, new Object[] {LINKAGE_ERROR}));
		code.add(new LdcInsnNode(mixin.name.replace('/', '.') + "." + handler.name));
		code.add(new MethodInsnNode(Opcodes.INVOKESTATIC, SELF, "hookDidNotLink",
				"(L" + LINKAGE_ERROR + ";Ljava/lang/String;)V", false));
		code.add(new InsnNode(Opcodes.RETURN));
		outer.maxLocals = slot;
		outer.maxStack = Math.max(slot, 2);

		handler.name = rewrite.to();
		mixin.methods.add(outer);
		return true;
	}

	/**
	 * R7's selector move: the handler {@code rewrite.from()} names is renamed aside, and a method of its name, access and
	 * injector annotation, selecting {@code rewrite.to()}, takes the replacement's arguments and its callback and hands
	 * the original vanilla's arguments read off them — each a parameter, or a no-argument {@code invokevirtual} on one —
	 * then the callback. Returns false, changing nothing, when the handler is not there or no row names the replacement.
	 */
	private static boolean project(ClassNode mixin, Rewrite rewrite) {
		MethodNode handler = null;
		for (MethodNode m : mixin.methods) {
			if ((m.name + m.desc).equals(rewrite.from()) && MixinFit.injectorOf(m) != null) handler = m;
		}
		MergedBaseCalleeSwaps.Replaced row = MergedBaseCalleeSwaps.projection(rewrite);
		if (handler == null || row == null) return false;
		AnnotationNode injector = MixinFit.injectorOf(handler);
		boolean isStatic = (handler.access & Opcodes.ACC_STATIC) != 0;
		Type[] params = Type.getArgumentTypes(handler.desc);
		Type callback = params[params.length - 1];
		Type[] replacement = Type.getArgumentTypes(rewrite.to().substring(rewrite.to().indexOf('(')));
		Type[] outerParams = java.util.Arrays.copyOf(replacement, replacement.length + 1);
		outerParams[replacement.length] = callback;

		MethodNode outer = new MethodNode(Opcodes.ASM9, handler.access, handler.name,
				Type.getMethodDescriptor(Type.VOID_TYPE, outerParams), null,
				handler.exceptions == null ? null : handler.exceptions.toArray(new String[0]));
		boolean visible = handler.visibleAnnotations != null && handler.visibleAnnotations.remove(injector);
		if (!visible && handler.invisibleAnnotations != null) handler.invisibleAnnotations.remove(injector);
		if (visible) outer.visibleAnnotations = new ArrayList<>(List.of(injector));
		else outer.invisibleAnnotations = new ArrayList<>(List.of(injector));
		for (int i = 0; i + 1 < injector.values.size(); i += 2) {
			if ("method".equals(injector.values.get(i))) injector.values.set(i + 1, new ArrayList<>(List.of(rewrite.to())));
		}

		int[] slots = new int[outerParams.length];
		int slot = isStatic ? 0 : 1;
		for (int i = 0; i < outerParams.length; i++) {
			slots[i] = slot;
			slot += outerParams[i].getSize();
		}
		InsnList code = outer.instructions;
		int stack = 0;
		if (!isStatic) {
			code.add(new VarInsnNode(Opcodes.ALOAD, 0));
			stack++;
		}
		if (params.length > 1) {
			for (String argument : row.arguments()) {
				int dot = argument.indexOf('.');
				int index = Integer.parseInt(argument.substring(1, dot < 0 ? argument.length() : dot));
				code.add(new VarInsnNode(replacement[index].getOpcode(Opcodes.ILOAD), slots[index]));
				if (dot >= 0) {
					String getter = argument.substring(dot + 1);
					int paren = getter.indexOf('(');
					code.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, replacement[index].getInternalName(),
							getter.substring(0, paren), getter.substring(paren), false));
				}
				stack += 2;
			}
		}
		code.add(new VarInsnNode(Opcodes.ALOAD, slots[replacement.length]));
		stack++;
		String aside = MixinHandlerShim.asideName(mixin.name, handler.name, PROJECTED_SUFFIX);
		code.add(MixinHandlerShim.callOwn(mixin, isStatic, aside, handler.desc));
		code.add(new InsnNode(Opcodes.RETURN));
		outer.maxLocals = slot;
		outer.maxStack = stack;

		handler.name = aside;
		mixin.methods.add(outer);
		return true;
	}

	/** A plan the adapter takes: the mixin rewritten by it, and the verdict on what Mixin will receive. */
	record Adoption(Plan plan, byte[] rewritten, MixinFit.Result after) {
	}

	/**
	 * The plan KernelGuestMixinAdapter takes for a mixin judged {@code fit}, or null: one is asked for whenever the
	 * verdict is PARTIAL or UNFIT, and taken when the rewritten mixin, judged by {@code evaluate}, misses fewer anchors --
	 * or fewer outright, an anchor now binding where nothing runs -- and is no longer one the adapter drops. UNFIT too: a mixin whose only injector is a selector the merge took over
	 * (MoogsStructureLib's HEAD of placeEntities alone) would otherwise be removed before the plan that moves it was
	 * asked. MixinFitLivenessCensusStagedTest judges with this same decision.
	 *
	 * <p>Never a rewrite that keeps an injector Mixin rejects outright ({@link MixinFit.Rejection}) where the mixin was
	 * UNFIT: that mixin is left out cleanly, and the rewrite would hand Mixin a binding that fails the class. A PARTIAL
	 * one's rewrite may keep only the rejections it already had, which the adapter then prunes or answers for as it
	 * would without the plan; a rewrite that adds one is not taken.
	 */
	static Adoption adopt(byte[] judged, MixinFit.Result fit, Function<String, byte[]> resolver,
			Function<byte[], MixinFit.Result> evaluate) {
		if (fit.verdict() != MixinFit.Verdict.PARTIAL && fit.verdict() != MixinFit.Verdict.UNFIT) return null;
		Plan plan = plan(MixinFit.parse(judged), resolver);
		if (plan.isEmpty()) return null;
		byte[] rewritten = rewritten(judged, plan);
		MixinFit.Result after = evaluate.apply(rewritten);
		// Fewer anchors missing, or fewer missing outright where one now binds in a method nothing runs (R3's move of
		// malilib's tooltip hook into the renamed tooltip body nothing calls): the final-class check then reports that
		// injector as never running, not as missing.
		boolean binds = after.unresolved().size() < fit.unresolved().size() || after.hardUnresolved() < fit.hardUnresolved();
		if (!binds || after.shouldSuppress()) return null;
		if (fit.verdict() == MixinFit.Verdict.UNFIT ? !after.rejected().isEmpty() : !fit.coversRejectionsOf(after)) return null;
		return new Adoption(plan, rewritten, after);
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
		SKIPPED.clear();
		MergedBaseCalleeSwaps.reset();
	}

	/** Test seam: the handlers R6's guard has skipped for a {@code LinkageError}, as {@code mixin.handler}. */
	static Set<String> skippedHooks() {
		return Set.copyOf(SKIPPED);
	}
}
