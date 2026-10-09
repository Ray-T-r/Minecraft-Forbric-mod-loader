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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Supplier;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

/**
 * Decides whether a guest mixin still FITS the merged base, by resolving every anchor it names against the merged
 * target's actual bytecode.
 *
 * <h2>Why resolution and not provenance</h2>
 *
 * <p>The merged base IS NeoForge's patched Minecraft — {@code MergedBaseBuilder} takes NeoForge as the base and
 * splices Forge in, so its own report reads {@code forge=195 neo=10161 MERGED=611}. "The target is a class
 * Forge/NeoForge owns" therefore describes ~93% of every class in the jar: it is the normal state, not a hazard
 * signal. Measured over the 163 suppressions the previous owned-target rule actually made, 108 (66%) targeted a
 * class BYTE-IDENTICAL to NeoForge's own patched jar.
 *
 * <p>Nor does provenance answer the question even when it is exact. Iris's {@code MixinLevelRenderer} anchors on
 * {@code lambda$addSkyPass$8}, {@code lambda$addCloudsPass$3}, {@code lambda$addMainPass$1} — none of which exist in
 * the merged {@code LevelRenderer}, and none of which exist in the VANILLA jar either, because lambda numbering is
 * an artifact of the remap toolchain rather than of the merge. "Neo-identical" does not imply "vanilla-equivalent".
 * The only question that predicts breakage is the direct one: <em>do the members this mixin names still exist?</em>
 *
 * <h2>Why the decision is atomic per mixin</h2>
 *
 * <p>A partially applied mixin is worse than either extreme. {@code relax}'s {@code injectors.defaultRequire=0}
 * silently skips individual non-matching injections, which today leaves Iris's {@code MixinLevelRenderer} with 16 of
 * its 25 injections installed — frame-graph entry hooks bound, the lambda-side exits dropped, i.e. a shader pipeline
 * that binds render targets it never unbinds. So this returns one verdict for the whole mixin and the caller drops
 * all of it or none of it. {@code relax} stays as a second-chance net for what cannot be resolved statically
 * ({@code @At(value="CONSTANT")}, MixinExtras expressions, {@code ordinal}/{@code shift}).
 *
 * <h2>Conservative by construction</h2>
 *
 * <p>Anything this cannot parse counts as RESOLVED. A weak parser must never be the reason a working mixin is
 * dropped; the cost of a false negative is a mixin that misbehaves as it does today, while the cost of a false
 * positive is silently deleting behaviour that worked.
 *
 * <h2>What native drops as well</h2>
 *
 * <p>A miss is the merge's only when the mod's own platform had the member: vanilla 26.2 for a Fabric mod, its own
 * patched game for a MinecraftForge or NeoForge mod. An injector whose every target that platform lacks too, and which
 * nothing requires to inject, is one native Mixin drops without a word; it is counted neither way and listed in
 * {@link Result#nativeAbsent}, so the mixin is judged on the rest. See {@link NativeAbsentTargets}.
 */
public final class MixinFit {
	private static final String MIXIN_DESC = "Lorg/spongepowered/asm/mixin/Mixin;";
	private static final String SHADOW_DESC = "Lorg/spongepowered/asm/mixin/Shadow;";
	private static final String OVERWRITE_DESC = "Lorg/spongepowered/asm/mixin/Overwrite;";
	private static final String AT_DESC = "Lorg/spongepowered/asm/mixin/injection/At;";
	private static final String ACCESSOR_DESC = "Lorg/spongepowered/asm/mixin/gen/Accessor;";
	private static final String INVOKER_DESC = "Lorg/spongepowered/asm/mixin/gen/Invoker;";
	private static final String OPERATION_DESC = "Lcom/llamalad7/mixinextras/injector/wrapoperation/Operation;";
	private static final String WRAP_OPERATION_DESC = "Lcom/llamalad7/mixinextras/injector/wrapoperation/WrapOperation;";
	private static final String REDIRECT_DESC = "Lorg/spongepowered/asm/mixin/injection/Redirect;";
	private static final String GROUP_DESC = "Lorg/spongepowered/asm/mixin/injection/Group;";
	private static final String INJECT_DESC = "Lorg/spongepowered/asm/mixin/injection/Inject;";
	private static final String COERCE_DESC = "Lorg/spongepowered/asm/mixin/injection/Coerce;";
	private static final String SURROGATE_DESC = "Lorg/spongepowered/asm/mixin/injection/Surrogate;";
	private static final String CALLBACK_INFO_DESC = "Lorg/spongepowered/asm/mixin/injection/callback/CallbackInfo;";
	private static final String CALLBACK_INFO_RETURNABLE_DESC =
			"Lorg/spongepowered/asm/mixin/injection/callback/CallbackInfoReturnable;";

	/** Injector annotations whose {@code method} value names one or more target methods on the mixin's target. */
	static final Set<String> INJECTOR_DESCS = Set.of(
			"Lorg/spongepowered/asm/mixin/injection/Inject;",
			"Lorg/spongepowered/asm/mixin/injection/Redirect;",
			"Lorg/spongepowered/asm/mixin/injection/ModifyVariable;",
			"Lorg/spongepowered/asm/mixin/injection/ModifyArg;",
			"Lorg/spongepowered/asm/mixin/injection/ModifyArgs;",
			"Lorg/spongepowered/asm/mixin/injection/ModifyConstant;",
			"Lcom/llamalad7/mixinextras/injector/ModifyExpressionValue;",
			"Lcom/llamalad7/mixinextras/injector/ModifyReturnValue;",
			"Lcom/llamalad7/mixinextras/injector/WrapWithCondition;",
			"Lcom/llamalad7/mixinextras/injector/wrapoperation/WrapOperation;",
			"Lcom/llamalad7/mixinextras/injector/v2/WrapWithCondition;",
			"Lcom/llamalad7/mixinextras/injector/wrapmethod/WrapMethod;");

	/** {@code @At} values whose {@code target} names a member that must appear INSIDE the injected method. */
	static final Set<String> RESOLVABLE_AT = Set.of("INVOKE", "INVOKE_ASSIGN", "FIELD");

	public enum Verdict {
		/** Every anchor resolved; apply the mixin unmodified. */
		FIT,
		/** Some anchors resolved and some did not — the half-application case. Drop it. */
		PARTIAL,
		/** No anchor resolved; the mixin was going to be dead weight. Drop it. */
		UNFIT,
		/** Applies cleanly but would misbehave: it {@code @Shadow}s a field the merge orphaned. Drop it. */
		HAZARD
	}

	/**
	 * One {@code @Inject} Mixin will certainly reject with "Invalid descriptor": in a target where its selectors bind
	 * exactly one method, that method is not one the handler was written for, the handler captures no locals, and one of
	 * its {@code @At}s is sure to find a point in that method ({@link #findsAPoint}). Mixin checks the handler at each
	 * point it finds and throws {@code InvalidInjectionException} at the first, whatever {@code require} says (when the
	 * selectors bind two or more methods it skips the one that does not fit instead; where it finds no point it injects
	 * nothing and {@code require} counts that as any other miss), and the exception fails the mixin's application to that
	 * class, every injector after it included -- in a config that stays required, the game. A PARTIAL verdict alone said
	 * nothing about that.
	 *
	 * @param handler    the handler method's name
	 * @param desc       its descriptor
	 * @param reason     the refused binding, as the unresolved anchor names it
	 * @param everywhere whether no target binds it to a method it fits, so removing it loses nothing that would run
	 */
	public record Rejection(String handler, String desc, String reason, boolean everywhere) {
		String key() {
			return handler + desc;
		}
	}

	/**
	 * @param verdict       the decision
	 * @param unresolved    human-readable anchors that did not resolve, for the log
	 * @param resolved      how many anchors resolved
	 * @param total         how many anchors were checked
	 * @param nativeAbsent  injector targets the mod's own platform lacks too, which native Mixin drops without a word
	 *                      ({@link NativeAbsentTargets}); counted in none of the above, so they decide nothing
	 * @param soft          how many of {@code unresolved} are soft: an injector bound only where nothing runs, a drifted
	 *                      anonymous target — reasons for PARTIAL, never for UNFIT
	 * @param rejected      the injectors among the misses that Mixin will reject outright ({@link Rejection})
	 */
	public record Result(Verdict verdict, List<String> unresolved, int resolved, int total,
			List<String> foreign, List<String> nativeAbsent, int soft, List<Rejection> rejected) {
		public Result(Verdict verdict, List<String> unresolved, int resolved, int total, List<String> foreign) {
			this(verdict, unresolved, resolved, total, foreign, List.of(), 0, List.of());
		}

		/** The anchors that did not resolve at all: {@code unresolved} without the soft ones. */
		public int hardUnresolved() {
			return unresolved.size() - soft;
		}

		/** Whether every rejection in {@code later} was already one of this result's, by handler. */
		boolean coversRejectionsOf(Result later) {
			Set<String> mine = new java.util.HashSet<>();
			for (Rejection r : rejected) mine.add(r.key());
			for (Rejection r : later.rejected) if (!mine.contains(r.key())) return false;
			return true;
		}

		/**
		 * Whether the caller should drop this mixin.
		 *
		 * <p>{@code HAZARD} and {@code UNFIT} always drop: those are the silent cases nothing downstream detects.
		 *
		 * <p>{@code PARTIAL} drops only under {@link MixinFit#strict()}, and the default is deliberately NOT strict.
		 * Measured on the real client set, suppressing PARTIAL would newly drop 53 mixins that work today (e.g.
		 * {@code fabric-entity-events-v1:LivingEntityMixin}, where 23 of 26 anchors resolve — losing 23 working
		 * event hooks to avoid 3 dead ones). Keeping PARTIAL makes this change MONOTONIC against the old
		 * owned-target rule: 149 mixins are restored and nothing that works today stops working. Half-application
		 * is a real hazard, but it is the hazard we already ship, and trading it for a 53-mixin regression
		 * unmeasured is how the two previous over-broad generalisations in this package went wrong.
		 *
		 * <p>The report always lists PARTIAL, so the half-applied set is now visible instead of silent — which is
		 * what makes it possible to promote individual entries to {@link MergedBaseMixinCompat#SUPPRESSED_MIXINS}
		 * on evidence, one measured mixin at a time.
		 */
		public boolean shouldSuppress() {
			return switch (verdict) {
				case FIT -> false;
				case PARTIAL -> strict();
				case UNFIT, HAZARD -> true;
			};
		}

		/** A compact "why" for one log line. */
		public String reason() {
			String missing = String.join(", ", new LinkedHashSet<>(unresolved));
			return switch (verdict) {
				case FIT -> "all " + total + " anchor(s) resolve";
				case PARTIAL -> resolved + "/" + total + " anchors resolve, missing: " + missing;
				case UNFIT -> "no anchor resolves (" + missing + ")";
				case HAZARD -> "orphaned @Shadow field(s): " + missing;
			};
		}
	}

	/** {@code -Dforbric.mixinFit=strict} also drops PARTIAL mixins; see {@link Result#shouldSuppress()}. */
	public static boolean strict() {
		return "strict".equalsIgnoreCase(System.getProperty("forbric.mixinFit", "default"));
	}

	/**
	 * {@code -Dforbric.mixinFit.anchorMovers=off}: an {@code @At(INVOKE)}/{@code @At(NEW)} point on a call the carrier
	 * widened reads as resolved for any injector kind again, as before the verdict asked the adapters that move points.
	 */
	static final String ANCHOR_MOVERS_PROPERTY = "forbric.mixinFit.anchorMovers";

	static boolean asksAnchorMovers() {
		return !"off".equalsIgnoreCase(System.getProperty(ANCHOR_MOVERS_PROPERTY, "on"));
	}

	/**
	 * {@code -Dforbric.mixinFit.liveness=off}: an injector bound only to a merged-base method nothing in the merged game
	 * calls ({@link MergedBaseUncalledMethods}) reads as resolved again, and the final-class ledger counts a handler
	 * called only from such a method as attached, as before.
	 */
	static final String LIVENESS_PROPERTY = "forbric.mixinFit.liveness";

	static boolean asksLiveness() {
		return !"off".equalsIgnoreCase(System.getProperty(LIVENESS_PROPERTY, "on"));
	}

	/**
	 * {@code -Dforbric.mixinFit.handlerFit=off}: a name-only {@code @Inject} selector reads as resolved whenever the name
	 * binds, as before the verdict asked whether the method it binds is one the handler was written for.
	 */
	static final String HANDLER_FIT_PROPERTY = "forbric.mixinFit.handlerFit";

	static boolean asksHandlerFit() {
		return !"off".equalsIgnoreCase(System.getProperty(HANDLER_FIT_PROPERTY, "on"));
	}

	/**
	 * {@code -Dforbric.mixinFit.rejectionPoint=off}: a refused binding is a {@link Rejection} whatever its {@code @At} finds
	 * in the method it binds, as before the verdict asked whether Mixin would meet the handler at a point there at all.
	 */
	static final String REJECTION_POINT_PROPERTY = "forbric.mixinFit.rejectionPoint";

	static boolean asksRejectionPoint() {
		return !"off".equalsIgnoreCase(System.getProperty(REJECTION_POINT_PROPERTY, "on"));
	}

	private MixinFit() {
	}

	/**
	 * Resolve every anchor {@code mixinBytes} names against its {@code @Mixin} target(s).
	 *
	 * <p>{@code targetResolver} maps an internal class name ({@code net/minecraft/Foo}) to its MERGED bytes — it must
	 * serve POST-transform-chain bytes, because the chain both adds members (the {@code KeyMapping.MAP} initializer)
	 * and deletes them (the interface-default shadowing overrides). Resolving against raw jar bytes gives wrong
	 * answers. A null return means "not a merged-base class", which counts as resolved.
	 */
	public static Result evaluate(byte[] mixinBytes, Function<String, byte[]> targetResolver) {
		// Everything is the game's unless a caller says otherwise, which is the pre-existing behaviour: no target
		// is foreign, so no mixin is reported as a cross-mod break. Callers that can classify pass the predicate.
		return evaluate(mixinBytes, targetResolver, name -> true);
	}

	/**
	 * @param gameClass whether a binary class name belongs to the game or a carrier rather than to a guest mod.
	 *                  {@code DelegationPolicy::alwaysGame} is the production answer — it already knows which
	 *                  packages are the game, and reusing it keeps this from becoming a second prefix rule that
	 *                  drifts from the first
	 */
	public static Result evaluate(byte[] mixinBytes, Function<String, byte[]> targetResolver,
			java.util.function.Predicate<String> gameClass) {
		return evaluate(mixinBytes, targetResolver, gameClass, MixinAddedMembers.View.NONE);
	}

	/**
	 * @param added what other mixins add to the targets before this one is applied; a {@code @Shadow} of such a member
	 *              resolves, as it does on Fabric. {@link MixinAddedMembers#before} is the production answer
	 */
	public static Result evaluate(byte[] mixinBytes, Function<String, byte[]> targetResolver,
			java.util.function.Predicate<String> gameClass, MixinAddedMembers.View added) {
		return evaluate(mixinBytes, targetResolver, gameClass, added, NativeAbsentTargets.Context.NONE);
	}

	/**
	 * @param nativeView what lets an injector target the mod's own platform lacks too be told from one the merge lost;
	 *                   such an injector is left out of the verdict and named in {@link Result#nativeAbsent}.
	 *                   {@code Context.NONE} asks nothing and counts it as a miss, as before
	 */
	public static Result evaluate(byte[] mixinBytes, Function<String, byte[]> targetResolver,
			java.util.function.Predicate<String> gameClass, MixinAddedMembers.View added,
			NativeAbsentTargets.Context nativeView) {
		ClassNode mixin = read(mixinBytes, false);
		// Most checks need only annotation metadata. A same-mixin target also needs the body Mixin will add.
		if (mixin.methods.stream().anyMatch(MixinFit::selfAddedCandidate)) {
			mixin = new ClassNode();
			new ClassReader(mixinBytes).accept(mixin, ClassReader.SKIP_FRAMES);
		}
		List<String> targets = mixinTargets(mixin);
		if (targets.isEmpty()) return new Result(Verdict.FIT, List.of(), 0, 0, List.of());

		List<String> unresolved = new ArrayList<>();
		List<String> orphaned = new ArrayList<>();
		List<String> nativeAbsent = new ArrayList<>();
		int resolved = 0;
		int total = 0;
		int softMisses = 0;
		int bound = 0;

		List<String> foreign = new ArrayList<>();
		// Injectors Mixin will reject outright, by the first target that shows it, and those some target binds as written.
		Map<MethodNode, String> rejectedIn = new java.util.LinkedHashMap<>();
		Set<MethodNode> boundIn = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
		for (String declared : targets) {
			// The same move MixinAnonymousRetarget will make to the @Mixin annotation. Judged here too, because a
			// verdict about the class the mixin will NOT be applied to is worse than no verdict: Polymer's two
			// ByteBufCodecs mixins were suppressed as UNFIT for anchors that resolve perfectly in their real home.
			String moved = MixinAnonymousRetarget.home(declared, name -> targetResolver.apply(name + ".class") != null);
			String targetName = moved != null ? moved : declared;
			byte[] targetBytes = targetResolver.apply(targetName + ".class");
			// Not a class we can see (JDK, a mixin-generated type): nothing to prove, assume it fits.
			if (targetBytes == null) continue;
			ClassNode target = read(targetBytes, true);
			// Whether this target belongs to the game/carriers or to ANOTHER MOD. The distinction is the whole
			// value of the signal: an anchor that does not resolve on a merged-base class is routine (1226 of
			// them across every gate log in this repo, all of them on runs that pass), while one that does not
			// resolve on another mod's class means two mods that were built to fit no longer do. Across those
			// same 1226 there is not one of the latter.
			boolean gameOwned = gameClass.test(targetName.replace('/', '.'));

			// The platform is asked about the class it compiled at the name the mixin declares, so only when the mixin
			// lands there: a target MixinAnonymousRetarget moves, or another mod's class, is not the platform's to answer.
			// Minecraft's own libraries are (brigadier, DataFixerUpper): the kernel loads them beside the game rather than
			// as game classes, so they are not game-owned here, and NativeAbsentTargets answers for exactly the library
			// jars every platform loads, by the jar that serves the class.
			NativeAbsentTargets.Context asked = moved == null && nativeView != null
					&& (gameOwned || NativeAbsentTargets.inVanillaPackages(targetName))
					? nativeView : NativeAbsentTargets.Context.NONE;
			List<Anchor> anchors = new ArrayList<>(anchorsOf(mixin, target, targetResolver,
					added == null ? MixinAddedMembers.View.NONE : added, declared, asked));
			// A renumbered anonymous class: every member anchor may resolve and still belong to a different class
			// than the one vanilla compiled at that name. Soft — it forces PARTIAL, never UNFIT.
			if (moved == null && gameOwned && MergedBaseAnonymousDrift.drifted(targetName)) {
				anchors.add(new Anchor("@Mixin target", targetName.substring(targetName.lastIndexOf('/') + 1)
						+ " is not the class vanilla compiled at that name (" + MergedBaseAnonymousDrift.describe(targetName)
						+ ")", false, true));
			}
			for (Anchor anchor : anchors) {
				// Native Mixin drops it on the mod's own platform too, so it is neither a hit nor a miss here: the mixin
				// is judged on the rest, and Mixin drops this one exactly as native does.
				if (anchor.nativeAbsent) {
					nativeAbsent.add(anchor.describe(targetName));
					continue;
				}
				total++;
				if (anchor.rejectedBy != null) rejectedIn.putIfAbsent(anchor.rejectedBy, anchor.describe(targetName));
				if (anchor.injector != null && (anchor.resolved || anchor.bound)) boundIn.add(anchor.injector);
				if (anchor.resolved) {
					resolved++;
				} else {
					unresolved.add(anchor.describe(targetName));
					if (anchor.soft) softMisses++;
					if (anchor.bound) bound++;
					if (!gameOwned) foreign.add(anchor.describe(targetName));
				}
			}
			orphaned.addAll(orphanedShadowFields(mixin, target, targetResolver));
		}

		// An orphaned @Shadow field is the silent case: it resolves (the field is still declared) and then reads
		// null at runtime. It outranks the count-based verdicts precisely because nothing else detects it.
		List<String> absent = List.copyOf(nativeAbsent);
		if (!orphaned.isEmpty()) return new Result(Verdict.HAZARD, orphaned, resolved, total, List.of(), absent, 0, List.of());
		if (total == 0 || unresolved.isEmpty()) return new Result(Verdict.FIT, List.of(), resolved, total, List.of(), absent, 0, List.of());
		// UNFIT is "no HARD anchor resolves"; a soft miss alone is PARTIAL, whatever else is there. An injector bound where
		// nothing runs still bound — it is what resolved before liveness was asked — so it keeps a mixin from UNFIT.
		boolean anyHardResolved = resolved > 0 || bound > 0 || unresolved.size() == softMisses;
		return new Result(anyHardResolved ? Verdict.PARTIAL : Verdict.UNFIT, unresolved, resolved, total,
				List.copyOf(foreign), absent, softMisses, rejections(rejectedIn, boundIn));
	}

	/** The rejections, in the mixin's own order: each handler once, with whether some target binds it where it fits. */
	private static List<Rejection> rejections(Map<MethodNode, String> rejectedIn, Set<MethodNode> boundIn) {
		if (rejectedIn.isEmpty()) return List.of();
		List<Rejection> out = new ArrayList<>();
		rejectedIn.forEach((handler, reason) -> out.add(new Rejection(handler.name, handler.desc, reason, !boundIn.contains(handler))));
		return List.copyOf(out);
	}

	// ---------------------------------------------------------------------------------------------------------------
	// Anchors
	// ---------------------------------------------------------------------------------------------------------------

	private static final class Anchor {
		final String kind;
		final String detail;
		final boolean resolved;
		/** Listed in the reason and worth PARTIAL, but never UNFIT: a soft anchor cannot get a mixin dropped. */
		final boolean soft;
		/** The detail already names its own owner, which is not the mixin's target; see {@link #atDetail}. */
		final boolean ownerNamed;
		/**
		 * Bound, but where nothing runs it: a miss for the report, a hit for UNFIT. The binding is what used to count as
		 * resolved, and the mixin still applies there, so it must not become the reason a mixin is dropped.
		 */
		final boolean bound;
		/**
		 * An injector target the mod's own platform lacks too, on an injector nothing makes count: native Mixin drops it
		 * without a word, so it is reported apart and counted as neither resolved nor missing ({@link NativeAbsentTargets}).
		 */
		boolean nativeAbsent;
		/** The {@code @Group} the injector naming this anchor belongs to, or {@code null}; see {@link #settleGroups}. */
		String group;
		/** The injector whose target selectors this anchor is, for an {@code @Inject target} anchor; null otherwise. */
		MethodNode injector;
		/** That injector, when this miss is a binding Mixin will reject outright ({@link Rejection}); null otherwise. */
		MethodNode rejectedBy;
		/** That injector, so a group can tell its alternatives apart. */
		MethodNode handler;

		Anchor(String kind, String detail, boolean resolved) {
			this(kind, detail, resolved, false);
		}

		Anchor(String kind, String detail, boolean resolved, boolean soft) {
			this(kind, detail, resolved, soft, false);
		}

		Anchor(String kind, String detail, boolean resolved, boolean soft, boolean ownerNamed) {
			this(kind, detail, resolved, soft, ownerNamed, false);
		}

		Anchor(String kind, String detail, boolean resolved, boolean soft, boolean ownerNamed, boolean bound) {
			this.kind = kind;
			this.detail = detail;
			this.resolved = resolved;
			this.soft = soft;
			this.ownerNamed = ownerNamed;
			this.bound = bound;
		}

		/** An injector bound only to methods nothing in the merged game calls: soft, and bound. */
		static Anchor neverRuns(String detail) {
			return new Anchor("@Inject target", detail, false, true, false, true);
		}

		/** An injector whose every target the mod's own platform lacks too, and which nothing requires to inject. */
		static Anchor absentNatively(String detail) {
			Anchor anchor = new Anchor("@Inject target", detail, false);
			anchor.nativeAbsent = true;
			return anchor;
		}

		void alternativeOf(String group, MethodNode handler) {
			this.group = group;
			this.handler = handler;
		}

		String describe(String target) {
			if (ownerNamed) return kind + " " + detail;
			return kind + " " + target.substring(target.lastIndexOf('/') + 1) + "." + detail;
		}
	}

	private static List<Anchor> anchorsOf(ClassNode mixin, ClassNode target, Function<String, byte[]> resolver,
			MixinAddedMembers.View added, String declared, NativeAbsentTargets.Context nativeView) {
		ClassNode injectionTarget = withSelfAddedMethods(mixin, target);
		// The target again with its local variable tables, read once and only if an injector needs it.
		Supplier<ClassNode> withLocals = new Supplier<>() {
			private ClassNode read;

			@Override
			public ClassNode get() {
				if (read == null) {
					byte[] bytes = resolver.apply(target.name + ".class");
					if (bytes != null) {
						read = new ClassNode();
						new ClassReader(bytes).accept(read, ClassReader.SKIP_FRAMES);
						read = withSelfAddedMethods(mixin, read);
					}
				}
				return read;
			}
		};
		List<Anchor> out = new ArrayList<>();

		// @Shadow fields: the member must still be declared (walking the superclass chain), or be added by a mixin
		// Mixin applies to the target first (MixinAddedMembers: moreculling's shadow of fabric-renderer's mesh).
		if (mixin.fields != null) {
			for (FieldNode f : mixin.fields) {
				if (!has(f.visibleAnnotations, SHADOW_DESC) && !has(f.invisibleAnnotations, SHADOW_DESC)) continue;
				out.add(new Anchor("@Shadow field", f.name, findField(target, f.name, f.desc, resolver) != null
						|| addedBefore(added, declared, target.name, true, f.name, f.desc)));
			}
		}

		if (mixin.methods == null) return out;
		for (MethodNode m : mixin.methods) {
			if (m.name.startsWith("<")) continue;

			// @Shadow methods: must still exist by name+desc.
			if (has(m.visibleAnnotations, SHADOW_DESC) || has(m.invisibleAnnotations, SHADOW_DESC)) {
				out.add(new Anchor("@Shadow method", m.name + m.desc, findMethod(target, m.name, m.desc, resolver) != null
						|| addedBefore(added, declared, target.name, false, m.name, m.desc)));
				continue;
			}

			// @Overwrite replaces the same-signature method; if it is gone the overwrite silently does nothing.
			if (has(m.visibleAnnotations, OVERWRITE_DESC) || has(m.invisibleAnnotations, OVERWRITE_DESC)) {
				out.add(new Anchor("@Overwrite", m.name + m.desc,
						findMethod(target, m.name, m.desc, resolver) != null));
				continue;
			}

			// @Accessor / @Invoker: a generated getter, setter or invoker binds to a member by name and descriptor.
			// Reported, never used to suppress (KernelGuestMixinAdapter keeps every pure accessor mixin) — the
			// alternative is Mixin's InvalidAccessorException on every boot, naming a descriptor and nothing else.
			if (has(m.visibleAnnotations, ACCESSOR_DESC) || has(m.invisibleAnnotations, ACCESSOR_DESC)) {
				Anchor accessor = accessorAnchor(m, target, resolver);
				if (accessor != null) out.add(accessor);
				continue;
			}
			if (has(m.visibleAnnotations, INVOKER_DESC) || has(m.invisibleAnnotations, INVOKER_DESC)) {
				Anchor invoker = invokerAnchor(m, target, resolver);
				if (invoker != null) out.add(invoker);
				continue;
			}

			AnnotationNode injector = injectorOf(m);
			if (injector == null) continue;
			int first = out.size();
			injectorAnchors(mixin, m, injector, injectionTarget, resolver, withLocals, nativeView, out);
			String group = groupOf(m);
			if (group != null) {
				for (int i = first; i < out.size(); i++) out.get(i).alternativeOf(group, m);
			}
		}
		return countsGroups() ? settleGroups(out) : out;
	}

	/** An injector may select a concrete @Unique method which this same mixin adds before injection preparation.
	 * Existing target members win. Abstract/shadow/injector methods cannot fabricate a target. */
	static ClassNode withSelfAddedMethods(ClassNode mixin, ClassNode target) {
		if (mixin == null || target == null) return target;
		List<MethodNode> added = new ArrayList<>();
		for (MethodNode method : mixin.methods) {
			if (!selfAddedCandidate(method) || method.instructions == null || method.instructions.size() == 0) continue;
			if (target.methods.stream().anyMatch(m -> m.name.equals(method.name) && m.desc.equals(method.desc))) continue;
			added.add(method);
		}
		if (added.isEmpty()) return target;
		ClassNode view = new ClassNode(); target.accept(view);
		for (MethodNode method : added) {
			MethodNode copy = new MethodNode(method.access, method.name, method.desc, method.signature,
					method.exceptions == null ? null : method.exceptions.toArray(String[]::new));
			method.accept(copy); view.methods.add(copy);
		}
		return view;
	}

	private static boolean selfAddedCandidate(MethodNode method) {
		return !method.name.startsWith("<") && (method.access & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE)) == 0
				&& injectorOf(method) == null
				&& !has(method.visibleAnnotations, SHADOW_DESC) && !has(method.invisibleAnnotations, SHADOW_DESC)
				&& !has(method.visibleAnnotations, OVERWRITE_DESC) && !has(method.invisibleAnnotations, OVERWRITE_DESC)
				&& !has(method.visibleAnnotations, ACCESSOR_DESC) && !has(method.invisibleAnnotations, ACCESSOR_DESC)
				&& !has(method.visibleAnnotations, INVOKER_DESC) && !has(method.invisibleAnnotations, INVOKER_DESC)
				&& (has(method.visibleAnnotations, "Lorg/spongepowered/asm/mixin/Unique;")
					|| has(method.invisibleAnnotations, "Lorg/spongepowered/asm/mixin/Unique;"));
	}

	/** Whether a mixin applied first adds the member to the target, by the name the mixin declares it or its home. */
	private static boolean addedBefore(MixinAddedMembers.View added, String declared, String target, boolean field,
			String name, String desc) {
		if (added == MixinAddedMembers.View.NONE) return false;
		for (String owner : declared == null || declared.equals(target) ? List.of(target) : List.of(declared, target)) {
			if (field ? added.field(owner, name, desc) : added.method(owner, name, desc)) return true;
		}
		return false;
	}

	/**
	 * The anchors one injector names: its target selectors, then each {@code @At} point inside what they bind. Appended
	 * to {@code out}; nothing when the injector names no selector.
	 */
	private static void injectorAnchors(ClassNode mixin, MethodNode m, AnnotationNode injector, ClassNode target,
			Function<String, byte[]> resolver, Supplier<ClassNode> withLocals, NativeAbsentTargets.Context nativeView,
			List<Anchor> out) {
		// An injector's `method` is a list of CANDIDATE selectors, not a conjunction. Mixin's default
		// require=1 counts matches across the whole list, so mods routinely ship alternative names to span
		// mappings or MC versions — Iris's LevelRenderer mixin carries both `lambda$addSkyPass$0` AND
		// `lambda$addSkyPass$8` for the same handler. Requiring EVERY selector to resolve reported those as
		// missing anchors and made 11 of Iris's 41 look unapplied when the injector was installed the whole
		// time. Judge the injector, not the selector: it is applied iff ANY selector resolves.
		List<String> selectors = stringList(value(injector, "method"));
		List<MethodNode> hits = new ArrayList<>();
		List<String> misses = new ArrayList<>();
		String pinnedAs = null;
		// A name binds and is still a miss when Mixin rejects the handler for the method it binds ("Invalid descriptor"):
		// MoogsStructureLib's HEAD of placeEntities, whose vanilla shape is gone and whose name binds MinecraftForge's.
		List<String> refused = new ArrayList<>();
		// The method each refused selector binds, where Mixin would meet the handler.
		List<MethodNode> refusedIn = new ArrayList<>();
		for (String selector : selectors) {
			// A name-only @Inject that MixinOverloadPin moves off the first overload is judged where it will land:
			// its anchors are in the overload it was written for, not in the other ecosystem's declared first. The pin
			// lands only on an overload the handler takes strictly, so a pinned selector is never a refused one.
			MethodNode pinned = selectors.size() == 1 ? MixinOverloadPin.destination(mixin, m, selector, target) : null;
			if (pinned != null) pinnedAs = pinned.name + pinned.desc;
			List<MethodNode> targetMethods = pinned != null ? List.of(pinned) : resolveSelector(target, selector, resolver);
			String refusal = targetMethods.isEmpty() || pinned != null ? null
					: refusedBinding(mixin, m, injector, selector, target);
			if (refusal != null) {
				refused.add(refusal);
				refusedIn.add(firstNamed(target, boundName(selector)));
			}
			if (!targetMethods.isEmpty() && refusal == null) hits.addAll(targetMethods); else misses.add(selector);
		}
		if (selectors.isEmpty()) return;
		// The move MixinTwinRebind will make for an injector written for vanilla's signature of a method nothing in the
		// merged game calls, onto the overload the carrier added in its place, judged here too so the verdict and the
		// move cannot disagree: bound to vanilla's method it would read as never running, and an @Inject whose name binds
		// the overload first as refused. Asked of the target with code: the plan counts each anchor in both bodies.
		if (selectors.size() == 1 && MixinTwinRebind.mayMove(target.name, selectors.getFirst())) {
			ClassNode code = withLocals.get();
			MethodNode twin = code == null ? null : MixinTwinRebind.destination(mixin, m, code);
			if (twin != null) {
				MethodNode live = findMethod(target, twin.name, twin.desc, resolver);
				hits = new ArrayList<>(List.of(live != null ? live : twin));
				misses.clear();
				refused.clear();
				refusedIn.clear();
			}
		}
		// The move InsertedLambdaArgumentShim will make for a selector naming a lambda the pruner dropped, judged
		// here too: otherwise a one-injector mixin (fusion's sprite loader hook) is UNFIT, removed from its
		// config, and never reaches the shim that would have given it the live lambda. Captured locals are
		// proven from the local variable table, which the plain read skipped. MixinHandlerShim's first: a selector
		// spelling vanilla's descriptor of a lambda whose captures the merge reordered lands on the one live lambda.
		if (hits.isEmpty() && selectors.size() == 1) {
			MethodNode shimmed = MixinHandlerShim.destination(m, target);
			if (shimmed == null) shimmed = InsertedLambdaArgumentShim.destination(m, target);
			ClassNode locals = shimmed == null ? withLocals.get() : null;
			if (shimmed == null && locals != null) {
				MethodNode twin = InsertedLambdaArgumentShim.destination(m, locals);
				if (twin != null) shimmed = findMethod(target, twin.name, twin.desc, resolver);
				if (shimmed == null) shimmed = twin;
			}
			if (shimmed != null) {
				hits.add(shimmed);
				misses.clear();
				refused.clear();
				refusedIn.clear();
			}
		}
		String where = misses.isEmpty() ? String.join("|", selectors)
				: hits.isEmpty() ? String.join("|", misses)
				: String.join("|", misses) + " (" + hits.size() + "/" + selectors.size() + " selectors hit)";
		if (hits.isEmpty()) {
			// Not Enough Crashes' populateCrashReport: a name no vanilla 26.2 BlockEntity has either, in a Fabric mod.
			// Native Mixin skips an injector that matches nothing and need not inject, and says nothing; a miss here
			// would have been the whole mixin's, and a required loss native never has. A MinecraftForge or NeoForge
			// mod is asked about its own patched game, which may have the method the merge lost. Asked only when no
			// selector bound at all: a refused binding is a method the game HAS, bound where the handler does not fit.
			if (refused.isEmpty() && NativeAbsentTargets.dropsNatively(m, injector, selectors, target.name, nativeView)) {
				out.add(Anchor.absentNatively(where));
				return;
			}
			// One method bound, and refused: Mixin throws rather than skip it -- at the first point it finds there. Two or
			// more bound and Mixin skips each that does not fit, looking for a match among the others; no point found and
			// it injects nothing, which require counts as any other miss.
			boolean throwsThere = refused.size() == 1 && rejectsWhole(m, injector);
			boolean meets = throwsThere && meetsAPoint(injector, refusedIn.get(0));
			Anchor miss = new Anchor("@Inject target", refused.isEmpty() ? where : String.join("; ", refused)
					+ (throwsThere && !meets ? "; its @At may find no point there, and Mixin rejects a handler only at a point it finds" : ""),
					false);
			miss.injector = m;
			if (meets) miss.rejectedBy = m;
			out.add(miss);
			return;
		}
		// The move MixinStubRebind will make for an injector bound to a carrier stub its own platform ran as a body
		// (any Fabric mod's; a Forge-family mod's where the other carrier added the stub), judged here too so the
		// verdict and the rebind cannot disagree: its anchors are asked of the body it lands on.
		if (selectors.size() == 1 && hits.size() == 1 && MixinStubRebind.isCarrierStub(target, hits.get(0))) {
			// The rebind reads the selector off the annotation, and it runs after the pin: asked about the bare name it
			// would see the body Mixin binds first and move nothing (AxeItem.evaluateNewBlockState, body before stub).
			// This is MixinFit's own parse of the mixin, never the node Mixin applies.
			if (pinnedAs != null) setSelectors(injector, List.of(pinnedAs));
			MethodNode moved = MixinStubRebind.destination(mixin, m, target);
			// A @Local by name is checked against the body's local variable table, which this read skipped.
			ClassNode locals = moved == null ? withLocals.get() : null;
			if (locals != null) moved = MixinStubRebind.destination(mixin, m, locals);
			if (moved != null) hits = new ArrayList<>(List.of(moved));
		}
		// Bound is not run. An injector whose every method is one nothing in the merged game calls attaches and
		// never fires: Better Mount HUD's XP redirect in Hud.extractHotbarAndDecorations, whose vanilla caller
		// NeoForge's HUD layers replaced, and malilib's tooltip hook in the renamed tooltip body R3 moves it to. Soft —
		// it makes the mixin PARTIAL with the reason, never UNFIT, and no injector moves because of it.
		String never = neverRuns(mixin, target, hits, resolver);
		Anchor bound = never == null ? new Anchor("@Inject target", where, true) : Anchor.neverRuns(never);
		bound.injector = m;
		out.add(bound);

		// Each @At(INVOKE/FIELD, target=…) must name an instruction inside a method the injector actually
		// bound to — again ANY, for the same require=1 reason.
		for (AnnotationNode at : atNodes(injector)) {
			String atValue = asString(value(at, "value"));
			String atTarget = asString(value(at, "target"));
			if (atTarget == null || atValue == null) continue;
			if ("NEW".equals(atValue)) {
				out.add(newAnchor(injector, m, atTarget, hits, target.methods));
				continue;
			}
			if (!RESOLVABLE_AT.contains(atValue)) continue;
			boolean anywhere = false;
			for (MethodNode hit : hits) {
				if (containsMember(hit, atTarget)) { anywhere = true; break; }
			}
			// The move MixinAtWidenedCall or a reviewed MixinWrapOperationShim wrap will make, decided by the
			// adapter's own predicate so the verdict and the rewrite cannot disagree. It used to be "a widened call
			// exists", for any injector: creativecore's @Redirect of the decorator call NeoForge widened read FIT,
			// though no adapter moves a redirect (it would replace the carrier's call), and its require=1 miss then
			// went unannounced.
			//
			// A handler in a @Group is the exception, judged as before by whether the call is there in widened form:
			// it is one of the mod's own alternatives, never moved, and the group — not this point — has to hit.
			// Iris's addMainPass group names vanilla's six-argument call beside NeoForge's seven-argument one; judged
			// by the rewrite, the vanilla alternative would read as a miss in a group that is satisfied.
			if (!anywhere && (!asksAnchorMovers() || MixinAtWidenedCall.inGroup(m))) {
				for (MethodNode hit : hits) {
					if (MixinAtWidenedCall.widenedIn(hit, atTarget) != null) { anywhere = true; break; }
				}
			} else if (!anywhere) {
				ClassNode wideningTarget = target;
				if (injector.desc.equals("Lorg/spongepowered/asm/mixin/injection/ModifyArg;")
						&& org.objectweb.asm.Type.getArgumentTypes(m.desc).length > 1) {
					ClassNode completeTarget = withLocals.get();
					if (completeTarget != null) wideningTarget = completeTarget;
				}
				anywhere = MixinAtWidenedCall.wouldMove(m, injector, wideningTarget.methods, atValue, atTarget) != null
						|| MixinWrapOperationShim.wouldWrap(mixin, m, target) != null
						// …and MixinSubtypeOwnerRetarget's: the same call through another owner (Decoder.parse made as
						// Codec.parse, Monster.lookAt made as Mob.lookAt through the field the merge widened).
						|| MixinSubtypeOwnerRetarget.wouldMove(mixin.name, m, injector, atTarget, target,
								name -> withCode(resolver, name)) != null;
			}
			// An anchor into another class names that class in full and still says where it was looked for: owo's
			// Fabric and Quilt alternatives both anchor on a class called Hooks, and "Hooks.startServer in main" read
			// the same for the one the kernel now emits and the one that can never exist here.
			boolean elsewhere = ownedElsewhere(atTarget, target.name);
			String site = elsewhere ? simpleName(target.name) + "." + hits.get(0).name : hits.get(0).name;
			out.add(new Anchor("@At(" + atValue + ")", atDetail(atTarget, target.name) + " in " + site, anywhere, false,
					elsewhere));
		}
	}

	/**
	 * {@code -Dforbric.mixinFit.groups=off}: every injector of a {@code @Group} is judged on its own again, so a group's
	 * alternative that can never bind here keeps its mixin PARTIAL although another alternative did.
	 */
	static final String GROUPS_PROPERTY = "forbric.mixinFit.groups";

	static boolean countsGroups() {
		return !"off".equalsIgnoreCase(System.getProperty(GROUPS_PROPERTY, "on"));
	}

	/**
	 * The {@code @Group} an injector is in, by name, or {@code null} when it is in none. An unnamed group is the
	 * config's default group, which every unnamed {@code @Group} of the mixin shares; {@code ""} stands for it.
	 */
	static String groupOf(MethodNode m) {
		for (List<AnnotationNode> table : java.util.Arrays.asList(m.invisibleAnnotations, m.visibleAnnotations)) {
			if (table == null) continue;
			for (AnnotationNode a : table) {
				if (!GROUP_DESC.equals(a.desc)) continue;
				String name = asString(value(a, "name"));
				return name == null ? "" : name;
			}
		}
		return null;
	}

	/**
	 * Drops the misses of a {@code @Group}'s alternatives once one alternative binds completely.
	 *
	 * <p>A group is Mixin's way of saying "one of these": its {@code min}/{@code max} replace each member's own
	 * {@code require}, so a member that finds nothing is not a failure while another member of the group injects.
	 * owo's freeze hooks are the case: {@code MainMixin} and {@code MinecraftMixin} each carry a Fabric alternative
	 * ({@code net.fabricmc...Hooks.startServer}) and a Quilt one ({@code org.quiltmc...Hooks.startServer}) in a
	 * {@code @Group(min = 1, max = 1)}. Judged member by member, the Quilt miss kept both mixins PARTIAL -- a
	 * SUSPECTED finding on every boot -- after the Fabric call they bind to was put in the game.
	 *
	 * <p>Only toward FIT: nothing is added, and a group none of whose members binds completely keeps every miss.
	 */
	private static List<Anchor> settleGroups(List<Anchor> anchors) {
		Map<MethodNode, Boolean> complete = new java.util.IdentityHashMap<>();
		for (Anchor anchor : anchors) {
			if (anchor.group != null) complete.merge(anchor.handler, anchor.resolved, Boolean::logicalAnd);
		}
		Set<String> satisfied = new java.util.HashSet<>();
		for (Anchor anchor : anchors) {
			if (anchor.group != null && complete.get(anchor.handler)) satisfied.add(anchor.group);
		}
		if (satisfied.isEmpty()) return anchors;
		List<Anchor> kept = new ArrayList<>(anchors.size());
		for (Anchor anchor : anchors) {
			// A binding Mixin rejects outright throws whatever the group's count says, so it is never settled away.
			if (!anchor.resolved && anchor.group != null && satisfied.contains(anchor.group) && anchor.rejectedBy == null) continue;
			kept.add(anchor);
		}
		return kept;
	}

	// ---------------------------------------------------------------------------------------------------------------
	// The orphaned-field hazard
	// ---------------------------------------------------------------------------------------------------------------

	/**
	 * {@code @Shadow}ed fields the merged target still DECLARES but never ASSIGNS — the archetype that made the old
	 * owned-target rule exist. NeoForge won the byte-merge of {@code GuiRenderer.<init>}, re-typed its third
	 * parameter and replaced vanilla's {@code pictureInPictureRenderers} with its own
	 * {@code pictureInPictureRendererPools}; erasure hides the change from Mixin (both descriptors are just
	 * {@code List}), so fabric-rendering-v1's mixin applies with no error and then reads a field with zero
	 * {@code putfield} anywhere → NPE.
	 *
	 * <p>Only <b>private</b> fields are judged. That restriction is what makes a class-local scan SOUND: a private
	 * field can only be written by its declaring class and its nestmates, both of which are scanned here. A public
	 * or protected field may legitimately be written by anyone — {@code MovingBlockRenderState.biome} is public,
	 * read in-class and written by whoever populates the render state, and a naive scan wrongly calls it orphaned.
	 * Render-state DTOs are exactly what renderer mods target, so that would be a systematic false positive.
	 *
	 * <p>A field the mixin itself assigns is not orphaned either: {@code @Shadow @Final @Mutable} means the mod
	 * intends to replace it.
	 */
	private static List<String> orphanedShadowFields(ClassNode mixin, ClassNode target,
			Function<String, byte[]> resolver) {
		if (mixin.fields == null || mixin.fields.isEmpty()) return List.of();

		List<String> orphans = new ArrayList<>();
		for (FieldNode f : mixin.fields) {
			if (!has(f.visibleAnnotations, SHADOW_DESC) && !has(f.invisibleAnnotations, SHADOW_DESC)) continue;

			ClassNode declaring = hierarchy(target, resolver).stream().filter(c -> c.fields != null
					&& c.fields.stream().anyMatch(field -> field.name.equals(f.name) && field.desc.equals(f.desc)))
					.findFirst().orElse(null);
			// Absent is a LOUD failure — Mixin reports it and relax soft-skips. Not this rule's business.
			if (declaring == null) continue;
			FieldNode declared = declaring.fields.stream().filter(field -> field.name.equals(f.name)
					&& field.desc.equals(f.desc)).findFirst().orElseThrow();
			// A compile-time constant carries a ConstantValue attribute and is initialized by the JVM with no
			// putstatic at all, so "never assigned" is meaningless for it. Missing this check false-positives on
			// every @Shadow'd `static final int` — MAX_PAYLOAD_SIZE, FLAG_INSIDE_FACE, MAX_DESCRIPTION_WIDTH_PIXELS.
			if (declared.value != null) continue;
			if ((declared.access & Opcodes.ACC_PRIVATE) == 0) continue;
			// PostMixinFixups seeds some orphans rather than letting them poison every reader; those are not
			// hazards. This runs on pre-mixin (and therefore pre-repair) bytes, so it must be asked explicitly.
			if (PostMixinFixups.isSeeded(target.name, f.name)) continue;
			if (writesField(mixin, f.name, f.desc, mixin.name) || writesField(mixin, f.name, f.desc, declaring.name)) continue;
			if (nestWritesField(declaring, f.name, f.desc, resolver)) continue;

			orphans.add(f.name + " (declared, never assigned)");
		}
		return orphans;
	}

	/** Whether the declaring class or any of its nestmates assigns {@code field}. */
	private static boolean nestWritesField(ClassNode target, String field, String desc, Function<String, byte[]> resolver) {
		if (writesField(target, field, desc, target.name)) return true;
		for (String member : nestMembers(target, resolver)) {
			byte[] bytes = resolver.apply(member + ".class");
			if (bytes == null) continue;
			if (writesField(read(bytes, true), field, desc, target.name)) return true;
		}
		return false;
	}

	private static List<String> nestMembers(ClassNode node, Function<String, byte[]> resolver) {
		Set<String> members = new LinkedHashSet<>();
		if (node.nestMembers != null) members.addAll(node.nestMembers);
		// A member lists only NestHost; the host owns the complete member list. Private fields may be
		// assigned by that host or a sibling, e.g. monument rooms and fortress starts in C2ME.
		if (node.nestHostClass != null) {
			members.add(node.nestHostClass);
			byte[] hostBytes = resolver.apply(node.nestHostClass + ".class");
			if (hostBytes != null) {
				ClassNode host = read(hostBytes, false);
				if (host.nestMembers != null) members.addAll(host.nestMembers);
				if (host.innerClasses != null) for (var inner : host.innerClasses)
					if (inner.name != null && inner.name.startsWith(host.name + "$")) members.add(inner.name);
			}
		}
		// Fall back to the InnerClasses attribute when NestMembers is absent (pre-11 class files, or stripped).
		if (node.innerClasses != null) {
			for (org.objectweb.asm.tree.InnerClassNode inner : node.innerClasses) {
				if (inner.name != null && inner.name.startsWith(node.name + "$")) members.add(inner.name);
			}
		}
		members.remove(node.name);
		return new ArrayList<>(members);
	}

	private static boolean writesField(ClassNode node, String field, String desc, String owner) {
		if (node.methods == null) return false;
		for (MethodNode m : node.methods) {
			if (m.instructions == null) continue;
			for (AbstractInsnNode insn : m.instructions) {
				if (insn instanceof FieldInsnNode fi
						&& (fi.getOpcode() == Opcodes.PUTFIELD || fi.getOpcode() == Opcodes.PUTSTATIC)
						&& fi.name.equals(field) && fi.desc.equals(desc) && fi.owner.equals(owner)) {
					return true;
				}
			}
		}
		return false;
	}

	// ---------------------------------------------------------------------------------------------------------------
	// Member resolution
	// ---------------------------------------------------------------------------------------------------------------

	private static FieldNode findField(ClassNode node, String name, String desc, Function<String, byte[]> resolver) {
		for (ClassNode c : hierarchy(node, resolver)) {
			if (c.fields == null) continue;
			for (FieldNode f : c.fields) {
				if (f.name.equals(name) && f.desc.equals(desc)) return f;
			}
		}
		return null;
	}

	private static MethodNode findMethod(ClassNode node, String name, String desc, Function<String, byte[]> resolver) {
		List<MethodNode> all = findMethods(node, name, desc, resolver);
		return all.isEmpty() ? null : all.get(0);
	}

	/** Replaces an injector's {@code method} list. */
	private static void setSelectors(AnnotationNode injector, List<String> selectors) {
		if (injector.values == null) return;
		for (int i = 0; i + 1 < injector.values.size(); i += 2) {
			if ("method".equals(injector.values.get(i))) injector.values.set(i + 1, new ArrayList<>(selectors));
		}
	}

	/**
	 * The method a selector binds: the one with {@code desc}, or for a bare name the FIRST declared method of that
	 * name — the target's own first, then up the hierarchy.
	 *
	 * <p>Mixin configures a member selector with the single-match quantifier and {@code TargetSelectors} stops at the
	 * first declared match, so a bare name binds ONE overload. Commit 5a39483 judged it against every overload instead,
	 * and fabric-model-loading-api-v1's {@code discoverModelDependencies} read FIT because the four-arg overload has
	 * {@code ModelDiscovery.resolve()} — while at runtime its handler had zero references: Mixin had bound the
	 * three-arg stub declared first. MixinStubRebind now moves such a Fabric injector to the body; this reports what
	 * Mixin would do without it.
	 */
	private static List<MethodNode> findMethods(ClassNode node, String name, String desc,
			Function<String, byte[]> resolver) {
		for (ClassNode c : hierarchy(node, resolver)) {
			if (c.methods == null) continue;
			for (MethodNode m : c.methods) {
				if (m.name.equals(name) && (desc == null || m.desc.equals(desc))) return List.of(m);
			}
		}
		return List.of();
	}

	/**
	 * Why a name-only {@code @Inject} selector does not bind for {@code handler}: the method the name binds is not one the
	 * handler was written for, so Mixin rejects it ("Invalid descriptor") — or, beside other targets, skips it. Null when it
	 * is, when the selector spells a descriptor or a pattern, or when the handler has no callback to judge by.
	 *
	 * <p>Mixin binds a name-only selector to the FIRST method of that name the target declares (its default quantifier
	 * matches one) and accepts a handler that takes that method's arguments then its callback — {@code CallbackInfo} for a
	 * void method, {@code CallbackInfoReturnable} otherwise — or the callback alone; a {@code @Coerce} argument and the
	 * locals captured after the callback are not judged, and a {@code @Surrogate} Mixin finds may stand in
	 * ({@link #surrogateBinds}: exactly the callback descriptor, a visible annotation). On
	 * vanilla the name bound the method the mod was compiled against; on the merged base it can bind a carrier's
	 * overload. Every such case is a miss, whatever moves it afterwards: {@link MergedBaseCalleeSwaps#REPLACED} names the
	 * replacement in the line and MixinRetarget's R7 follows it, MixinHandlerShim wraps a lambda the merge reshaped. A
	 * selector {@link MixinOverloadPin} will spell never gets here: the verdict asks the pin first and judges the
	 * overload it lands on. Where the name is the injector's one binding, the miss is also a {@link Rejection}.
	 */
	static String refusedBinding(ClassNode mixin, MethodNode handler, AnnotationNode injector, String selector, ClassNode target) {
		if (!asksHandlerFit() || !INJECT_DESC.equals(injector.desc) || target.methods == null) return null;
		String name = boundName(selector);
		if (name == null) return null;
		MethodNode bound = firstNamed(target, name);
		if (bound == null || handlerFits(handler, bound.desc) || surrogateBinds(mixin, handler, bound.desc)) return null;
		String binds = "binds " + bound.name + bound.desc + ", which the handler was not written for";
		MergedBaseCalleeSwaps.Replaced row = MergedBaseCalleeSwaps.replaced(target.name, name, null,
				MixinStubRebind.ecosystemOf(mixin.name));
		if (row != null) {
			String vanilla = row.vanilla().substring(row.vanilla().indexOf('('));
			boolean declared = false;
			for (MethodNode m : target.methods) declared |= m.name.equals(name) && m.desc.equals(vanilla);
			if (!declared) {
				return name + vanilla + " is gone: the carrier replaced it with "
						+ row.replacement().substring(0, row.replacement().indexOf('(')) + ", and the name " + binds;
			}
		}
		// What the handler was written for, when the class says: a later overload of the name, or (MixinOverloadPin's
		// evidence) a body of it the duplicate-lambda pruner dropped.
		for (MethodNode m : target.methods) {
			if (m != bound && m.name.equals(name) && handlerFits(handler, m.desc)) {
				return name + " " + binds + "; it fits " + name + m.desc + ", declared later, and Mixin binds the first";
			}
		}
		for (String dropped : net.forbric.kernel.transform.DuplicateLambdaPruneInjector.droppedDescriptors(target.name, name)) {
			if (MixinOverloadPin.fits(handler.desc, dropped)) {
				return name + " " + binds + "; the shape it was written for, " + dropped + ", was a lambda of the "
						+ MixinOverloadPin.enclosing(name) + " body the byte merge did not keep";
			}
		}
		return name + " " + binds;
	}

	/**
	 * Whether a refused binding of this injector makes Mixin throw rather than skip: an {@code @Inject} that selects by
	 * {@code method} alone and captures no locals. With {@code locals} set, Mixin's descriptor check goes the capture way
	 * (CAPTURE_FAILSOFT, which the kernel makes of FAILHARD, warns and skips), and {@code PRINT} only prints.
	 */
	static boolean rejectsWhole(MethodNode handler, AnnotationNode injector) {
		if (!INJECT_DESC.equals(injector.desc) || injector.values == null) return false;
		for (int i = 0; i + 1 < injector.values.size(); i += 2) {
			Object key = injector.values.get(i);
			if ("target".equals(key)) return false;
			if ("locals".equals(key) && injector.values.get(i + 1) instanceof String[] e && e.length == 2
					&& !"NO_CAPTURE".equals(e[1])) return false;
		}
		return true;
	}

	/**
	 * The refused binding that makes Mixin reject {@code handler}'s {@code @Inject} in {@code mixin} as it now stands, or
	 * null: asked again of the node Mixin receives, after every adapter has had its say, by the same rule the verdict's
	 * {@link Rejection} uses -- in each target, the selectors bind one method and it is not one the handler was written
	 * for, and an {@code @At} is sure to find a point in it. Null when some target binds it where it fits or binds two
	 * methods, when a selector is anything but a name (a pinned or rebound selector spells its descriptor), when no point
	 * is certain, and when a target cannot be read.
	 *
	 * @param targets the targets WITH their instructions: the point is looked for in the method the name binds
	 */
	public static String stillRejected(ClassNode mixin, MethodNode handler, Function<String, ClassNode> targets) {
		AnnotationNode injector = injectorOf(handler);
		if (injector == null || !rejectsWhole(handler, injector) || !asksHandlerFit()) return null;
		List<String> selectors = stringList(value(injector, "method"));
		if (selectors.isEmpty()) return null;
		String reason = null;
		for (String targetName : mixinTargets(mixin)) {
			ClassNode target = targets.apply(targetName);
			if (target == null || target.methods == null) return null;
			target = withSelfAddedMethods(mixin, target);
			Set<MethodNode> bound = Collections.newSetFromMap(new java.util.IdentityHashMap<>());
			String refusal = null;
			for (String selector : selectors) {
				String name = boundName(selector);
				if (name == null) return null;
				for (MethodNode method : target.methods) {
					if (method.name.equals(name)) {
						bound.add(method);
						break;
					}
				}
				String refused = refusedBinding(mixin, handler, injector, selector, target);
				if (refused != null) refusal = refused;
			}
			if (bound.isEmpty()) continue;
			if (bound.size() > 1 || refusal == null || !meetsAPoint(injector, bound.iterator().next())) return null;
			reason = refusal;
		}
		return reason;
	}

	/** The method a name binds in {@code target}: the first it declares of that name, as Mixin's default quantifier takes. */
	private static MethodNode firstNamed(ClassNode target, String name) {
		if (name == null || target.methods == null) return null;
		for (MethodNode m : target.methods) if (m.name.equals(name)) return m;
		return null;
	}

	/** {@link #findsAPoint}, or true with {@code -Dforbric.mixinFit.rejectionPoint=off}. */
	private static boolean meetsAPoint(AnnotationNode injector, MethodNode bound) {
		return !asksRejectionPoint() || findsAPoint(injector, bound);
	}

	/**
	 * Whether one of {@code injector}'s {@code @At}s is sure to find a point in {@code bound}, so Mixin will meet the
	 * handler there. Mixin checks an {@code @Inject} handler's descriptor at each point it finds
	 * ({@code CallbackInjector.inject(Target, InjectionNode)}): a method it binds and finds no point in is a method it
	 * injects nothing into, and {@code require} counts it as any other miss -- no "Invalid descriptor".
	 *
	 * <p>Sure: {@code HEAD} (the first instruction); {@code RETURN} and {@code TAIL} where the method has a return of its
	 * own type (past {@code ordinal}); {@code INVOKE}, {@code INVOKE_ASSIGN} (a call that returns a value) and
	 * {@code FIELD} where the method has an instruction of that member (past {@code ordinal}, and of {@code opcode} for a
	 * field); {@code NEW} where it constructs that type (with those constructor arguments, when the target spells them).
	 * Anything else -- a slice, another kind of point, a target this cannot read, a method without code -- is
	 * not sure, and the binding stays an ordinary miss.
	 */
	static boolean findsAPoint(AnnotationNode injector, MethodNode bound) {
		if (bound == null || bound.instructions == null || bound.instructions.size() == 0) return false;
		if (value(injector, "slice") instanceof List<?> slices && !slices.isEmpty()) return false;
		for (AnnotationNode at : atNodes(injector)) {
			String kind = asString(value(at, "value"));
			if (kind == null || value(at, "slice") != null) continue;
			// HEAD takes the first instruction and TAIL the last return, whatever the ordinal; the others count past it.
			if ("HEAD".equals(kind) || ("TAIL".equals(kind) && returns(bound) > 0)) return true;
			int ordinal = value(at, "ordinal") instanceof Number n ? n.intValue() : -1;
			int found = switch (kind) {
				case "RETURN" -> returns(bound);
				case "INVOKE", "INVOKE_ASSIGN", "FIELD" -> occurrences(bound, kind, asString(value(at, "target")),
						value(at, "opcode") instanceof Number n ? n.intValue() : -1);
				case "NEW" -> constructions(bound, asString(value(at, "target")));
				default -> 0;
			};
			if (ordinal < 0 ? found > 0 : found > ordinal) return true;
		}
		return false;
	}

	/** How many returns of its own type {@code method} has: what {@code RETURN} and {@code TAIL} look for. */
	private static int returns(MethodNode method) {
		int opcode = Type.getReturnType(method.desc).getOpcode(Opcodes.IRETURN);
		int count = 0;
		for (AbstractInsnNode insn : method.instructions) if (insn.getOpcode() == opcode) count++;
		return count;
	}

	/** How many instructions of {@code method} an {@code INVOKE}, {@code INVOKE_ASSIGN} or {@code FIELD} point would take. */
	private static int occurrences(MethodNode method, String kind, String target, int opcode) {
		Member want = target == null ? null : parseMember(target);
		if (want == null) return 0;
		int count = 0;
		for (AbstractInsnNode insn : method.instructions) {
			if ("FIELD".equals(kind)) {
				if (insn instanceof FieldInsnNode fi && fi.name.equals(want.name) && (want.owner == null || fi.owner.equals(want.owner))
						&& (want.desc == null || fi.desc.equals(want.desc)) && (opcode <= 0 || fi.getOpcode() == opcode)) count++;
			} else if (insn instanceof MethodInsnNode mi && mi.name.equals(want.name) && (want.owner == null || mi.owner.equals(want.owner))
					&& (want.desc == null || mi.desc.equals(want.desc))
					&& ("INVOKE".equals(kind) || Type.getReturnType(mi.desc).getSort() != Type.VOID)) {
				count++;
			}
		}
		return count;
	}

	/**
	 * How many {@code new} of the type a {@code NEW} point names {@code method} has -- with a constructor descriptor, only
	 * those whose constructor call takes exactly those arguments.
	 */
	private static int constructions(MethodNode method, String target) {
		if (target == null || target.isEmpty()) return 0;
		String type;
		Type[] wanted = null;
		try {
			if (target.startsWith("(")) {
				Type ctor = Type.getMethodType(target);
				type = ctor.getReturnType().getInternalName();
				wanted = ctor.getArgumentTypes();
			} else {
				type = target.startsWith("L") && target.endsWith(";") ? target.substring(1, target.length() - 1) : target.replace('.', '/');
			}
		} catch (RuntimeException unreadable) {
			return 0;
		}
		int count = 0;
		for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
			if (!(insn instanceof org.objectweb.asm.tree.TypeInsnNode t) || t.getOpcode() != Opcodes.NEW || !type.equals(t.desc)) continue;
			if (wanted == null) {
				count++;
				continue;
			}
			for (AbstractInsnNode c = insn.getNext(); c != null; c = c.getNext()) {
				if (c instanceof MethodInsnNode call && call.getOpcode() == Opcodes.INVOKESPECIAL && "<init>".equals(call.name)
						&& type.equals(call.owner)) {
					if (java.util.Arrays.equals(Type.getArgumentTypes(call.desc), wanted)) count++;
					break;
				}
			}
		}
		return count;
	}

	/**
	 * Whether {@code mixin} declares a {@code @Surrogate} Mixin binds in {@code handler}'s place on a method of {@code desc}.
	 * When the handler does not fit, Mixin looks one up by the handler's name and exactly the callback descriptor --
	 * {@code desc}'s arguments, then {@code CallbackInfo} or {@code CallbackInfoReturnable} as its return calls for,
	 * returning void -- and takes it only with a visible {@code @Surrogate} ({@code Bytecode.findMethod},
	 * {@code Annotations.getVisible}); a {@code @Coerce} or the callback alone does not stand in there.
	 */
	static boolean surrogateBinds(ClassNode mixin, MethodNode handler, String desc) {
		if (mixin == null || mixin.methods == null || handler == null) return false;
		String callback = "(" + desc.substring(1, desc.indexOf(')'))
				+ (Type.getReturnType(desc).getSort() == Type.VOID ? CALLBACK_INFO_DESC : CALLBACK_INFO_RETURNABLE_DESC) + ")V";
		for (MethodNode m : mixin.methods) {
			if (m != handler && m.name.equals(handler.name) && m.desc.equals(callback) && has(m.visibleAnnotations, SURROGATE_DESC)) {
				return true;
			}
		}
		return false;
	}

	/** The method name a selector binds by name alone ({@code name} or {@code Lowner;name}), or null for any other form. */
	private static String boundName(String selector) {
		String name = selector.trim();
		int semi = name.indexOf(';');
		if (name.startsWith("L") && semi > 0) name = name.substring(semi + 1);
		if (name.isEmpty()) return null;
		for (char c : name.toCharArray()) if ("(*{}+/ =:".indexOf(c) >= 0) return null;
		return name;
	}

	/**
	 * Whether Mixin's {@code @Inject} accepts {@code handler} on a method of {@code desc}: its arguments then the
	 * callback its return calls for, or that callback alone. True when the handler takes no callback (not judged).
	 */
	static boolean handlerFits(MethodNode handler, String desc) {
		Type[] params = Type.getArgumentTypes(handler.desc);
		int callback = -1;
		for (int i = 0; i < params.length && callback < 0; i++) {
			String d = params[i].getDescriptor();
			if (CALLBACK_INFO_DESC.equals(d) || CALLBACK_INFO_RETURNABLE_DESC.equals(d)) callback = i;
		}
		if (callback < 0) return true;
		boolean returns = Type.getReturnType(desc).getSort() != Type.VOID;
		if (returns != CALLBACK_INFO_RETURNABLE_DESC.equals(params[callback].getDescriptor())) return false;
		if (callback == 0) return true;
		Type[] args = Type.getArgumentTypes(desc);
		if (callback != args.length) return false;
		for (int i = 0; i < callback; i++) {
			if (!params[i].equals(args[i]) && !coerced(handler, i)) return false;
		}
		return true;
	}

	/** Whether the handler's {@code index}-th parameter carries {@code @Coerce}. */
	private static boolean coerced(MethodNode handler, int index) {
		for (List<AnnotationNode>[] table : java.util.Arrays.asList(handler.visibleParameterAnnotations,
				handler.invisibleParameterAnnotations)) {
			if (table != null && index < table.length && has(table[index], COERCE_DESC)) return true;
		}
		return false;
	}

	/**
	 * "extractHotbarAndDecorations never runs: …" when every method the injector bound is one
	 * {@link MergedBaseUncalledMethods} lists for the mod's ecosystem, or a body a carrier renamed that nothing in the
	 * merged game calls ({@link CarrierRenames#neverRuns}: NeoForge's {@code ItemStack.addDetailsToTooltipComponents},
	 * where R3 moves malilib's tooltip hook), and the live bytes agree; null when any may run, the rule is off, or the
	 * mod's ecosystem is unknown (a config two mods claim).
	 */
	private static String neverRuns(ClassNode mixin, ClassNode target, List<MethodNode> hits,
			Function<String, byte[]> resolver) {
		if (!asksLiveness()) return null;
		for (MethodNode hit : hits) {
			if (!MergedBaseUncalledMethods.lists(hit.name, hit.desc) && !CarrierRenames.listsUncalled(target.name)) return null;
		}
		net.forbric.api.Ecosystem ecosystem = MixinStubRebind.ecosystemOf(mixin.name);
		if (ecosystem == null) return null;
		List<String> dead = new ArrayList<>();
		for (MethodNode hit : hits) {
			ClassNode owner = null;
			for (ClassNode c : hierarchy(target, resolver)) {
				if (c.methods != null && c.methods.stream().anyMatch(x -> x.name.equals(hit.name) && x.desc.equals(hit.desc))) {
					owner = c;
					break;
				}
			}
			String why = owner == null ? null : MergedBaseUncalledMethods.neverRuns(owner, hit, ecosystem, resolver);
			// Or a body a carrier renamed and nothing calls, where R3 moved the injector to bind as it would in the body.
			if (why == null && owner != null) why = CarrierRenames.neverRuns(owner, hit, ecosystem);
			if (why == null) return null;
			String line = hit.name + " never runs: " + why;
			if (!dead.contains(line)) dead.add(line);
		}
		return dead.isEmpty() ? null : String.join("; ", dead);
	}

	/** The target and its superclass chain, as far as the resolver can see. */
	private static List<ClassNode> hierarchy(ClassNode node, Function<String, byte[]> resolver) {
		List<ClassNode> chain = new ArrayList<>();
		ClassNode current = node;
		for (int guard = 0; current != null && guard < 32; guard++) {
			chain.add(current);
			if (current.superName == null || "java/lang/Object".equals(current.superName)) break;
			byte[] bytes = resolver.apply(current.superName + ".class");
			current = bytes == null ? null : read(bytes, true);
		}
		return chain;
	}

	/**
	 * A Mixin method selector: {@code name}, {@code name(desc)ret}, or {@code Lowner;name(desc)ret}. A bare name
	 * resolves to the FIRST declared method of that name, as Mixin binds it. Anything with a wildcard or a shape this does not
	 * understand resolves to the first method, and to "resolved" if there is none to compare against — see the
	 * conservatism note on the class.
	 */
	static List<MethodNode> resolveSelector(ClassNode target, String selector,
			Function<String, byte[]> resolver) {
		if (selector == null || selector.isBlank()) return List.of();
		String s = selector.trim();
		if (s.indexOf('*') >= 0) return firstMethod(target, resolver);  // wildcard: not our business to judge

		// Strip a fully-qualified owner prefix: Lnet/minecraft/Foo;bar()V
		int semi = s.indexOf(';');
		if (s.startsWith("L") && semi > 0) s = s.substring(semi + 1);

		int paren = s.indexOf('(');
		String name = paren >= 0 ? s.substring(0, paren) : s;
		String desc = paren >= 0 ? s.substring(paren) : null;
		if (name.isEmpty()) return firstMethod(target, resolver);

		// Mixin's full target-selector grammar also allows a REGEX name (/^with/) and an explicit
		// `desc=` clause. fabric-permission-api-v1's CommandSourceStackMixin uses both at once
		// (`/^with/ desc=/CommandSourceStack;$/`) to catch every withX() builder. Matching those means
		// implementing Mixin's selector engine; treating them as a plain method name means reporting a
		// miss for a selector Mixin resolves fine. Un-judgeable → RESOLVED, per the class conservatism note.
		if (name.charAt(0) == '/' || name.indexOf(' ') >= 0 || name.indexOf('=') >= 0) {
			return firstMethod(target, resolver);
		}
		return findMethods(target, name, desc, resolver);
	}

	/**
	 * Whether {@link #resolveSelector} answers {@code selector} by name rather than with its conservative fallback (a
	 * wildcard, a regex, a {@code desc=} clause, no name): only then is the method it returns the one Mixin binds.
	 */
	static boolean exactSelector(String selector) {
		if (selector == null || selector.isBlank()) return false;
		String s = selector.trim();
		if (s.indexOf('*') >= 0) return false;
		int semi = s.indexOf(';');
		if (s.startsWith("L") && semi > 0) s = s.substring(semi + 1);
		int paren = s.indexOf('(');
		String name = paren >= 0 ? s.substring(0, paren) : s;
		return !name.isEmpty() && name.charAt(0) != '/' && name.indexOf(' ') < 0 && name.indexOf('=') < 0;
	}

	private static List<MethodNode> firstMethod(ClassNode target, Function<String, byte[]> resolver) {
		return target.methods == null || target.methods.isEmpty() ? List.of() : List.of(target.methods.get(0));
	}

	/** Whether {@code method}'s body contains the invocation or field access {@code at} names. */
	static boolean containsMember(MethodNode method, String at) {
		Member want = parseMember(at);
		if (want == null || method.instructions == null) return true;  // unparseable: assume present

		for (AbstractInsnNode insn : method.instructions) {
			if (insn instanceof MethodInsnNode mi
					&& mi.name.equals(want.name)
					&& (want.owner == null || mi.owner.equals(want.owner))
					&& (want.desc == null || mi.desc.equals(want.desc))) {
				return true;
			}
			if (insn instanceof FieldInsnNode fi
					&& fi.name.equals(want.name)
					&& (want.owner == null || fi.owner.equals(want.owner))
					&& (want.desc == null || fi.desc.equals(want.desc))) {
				return true;
			}
		}
		return false;
	}

	record Member(String owner, String name, String desc) { // package-private for MixinFitTest
	}

	/** Parses {@code Lowner;name(args)ret} and {@code Lowner;name:Ldesc;}. Returns null when the shape is unfamiliar. */
	/**
	 * Splits a Mixin member target into owner/name/desc. Mixin accepts the owner in TWO forms and this used to
	 * understand only one.
	 *
	 * <p>{@code Lnet/minecraft/client/CameraType;isFirstPerson()Z} — descriptor form, handled from the start.
	 * {@code net/minecraft/client/CameraType.isFirstPerson()Z} — dotted form, equally legal and what Shoulder
	 * Surfing, malilib and litematica actually write. On the dotted form the old code found no {@code L…;}, left
	 * the owner null, and took everything before the {@code (} as the NAME — so it compared the method name
	 * against {@code "net/minecraft/client/CameraType.isFirstPerson"} and never matched anything.
	 *
	 * <p>Consequence, and the reason this is worth a long comment: EVERY dotted {@code @At(target=…)} was reported
	 * as an unresolved anchor, on every boot, forever. Nothing was wrongly suppressed — {@code PARTIAL} defaults to
	 * KEEP — but the log said 11 of Shoulder Surfing's mixins were half-applied when the anchors were all present
	 * (verified: {@code MouseHandler.turnPlayer} calls {@code CameraType.isFirstPerson} once in vanilla, in both
	 * patched bases AND in the merge). A diagnostic that cries wolf is worse than none: it cost a full audit pass
	 * to disbelieve. It would also have made {@code -Dforbric.mixinFit=strict} drop mixins that fit perfectly.
	 */
	static Member parseMember(String target) { // package-private for MixinFitTest
		// Mixin's own parser ignores whitespace INSIDE a member descriptor, and mods rely on it: Shoulder Surfing
		// writes "…EntityRenderer.createRenderState ()Lnet/…/EntityRenderState;" with a space before the descriptor.
		// Keeping it turned the name into "createRenderState " and no instruction ever matched — the same
		// cries-wolf failure as the dotted owner below, and visible in the report as a tell-tale double space.
		// A point with no target at all (MixinExtras' EXPRESSION, NEW by class) names no member: an adapter
		// that asked about one must not throw inside Mixin's read of the mixin, which drops the whole mixin.
		if (target == null) return null;
		String s = target.replaceAll("\\s+", "");
		if (s.isEmpty() || s.indexOf('*') >= 0) return null;

		String owner = null;
		int semi = s.indexOf(';');
		if (s.startsWith("L") && semi > 0) {
			owner = s.substring(1, semi);
			s = s.substring(semi + 1);
		} else {
			// Dotted form: the owner is everything before the LAST dot that precedes the descriptor/field separator.
			int cut = s.length();
			for (int i = 0; i < s.length(); i++) {
				char c = s.charAt(i);
				if (c == '(' || c == ':') { cut = i; break; }
			}
			int dot = s.lastIndexOf('.', cut - 1);
			if (dot > 0) {
				// A dotted owner may also use dots as package separators (com.example.Foo.bar) — internal names win.
				owner = s.substring(0, dot).replace('.', '/');
				s = s.substring(dot + 1);
			}
		}
		int paren = s.indexOf('(');
		if (paren >= 0) return new Member(owner, s.substring(0, paren), s.substring(paren));

		int colon = s.indexOf(':');
		if (colon >= 0) return new Member(owner, s.substring(0, colon), s.substring(colon + 1));
		return s.isEmpty() ? null : new Member(owner, s, null);
	}

	// ---------------------------------------------------------------------------------------------------------------
	// Annotation plumbing
	// ---------------------------------------------------------------------------------------------------------------

	/** Every class a {@code @Mixin} names, via {@code value} Classes and {@code targets} Strings. */
	public static List<String> mixinTargets(ClassNode mixin) {
		Set<String> targets = new LinkedHashSet<>();
		collectTargets(mixin.visibleAnnotations, targets);
		collectTargets(mixin.invisibleAnnotations, targets);
		return new ArrayList<>(targets);
	}

	private static void collectTargets(List<AnnotationNode> annotations, Set<String> out) {
		if (annotations == null) return;
		for (AnnotationNode a : annotations) {
			if (!MIXIN_DESC.equals(a.desc) || a.values == null) continue;
			for (int i = 0; i + 1 < a.values.size(); i += 2) {
				Object key = a.values.get(i);
				if (!"value".equals(key) && !"targets".equals(key)) continue;
				Object v = a.values.get(i + 1);
				if (v instanceof List<?> list) {
					for (Object element : list) addTarget(element, out);
				} else {
					addTarget(v, out);
				}
			}
		}
	}

	private static void addTarget(Object value, Set<String> out) {
		if (value instanceof Type type) {
			out.add(type.getInternalName());
		} else if (value instanceof String s) {
			String name = s.trim();
			if (name.startsWith("L") && name.endsWith(";")) name = name.substring(1, name.length() - 1);
			if (!name.isEmpty()) out.add(name.replace('.', '/'));
		}
	}

	// ---------------------------------------------------------------------------------------------------------------
	// Cast contracts
	// ---------------------------------------------------------------------------------------------------------------

	/**
	 * The duck-type interfaces a mixin implants on its target — its <em>cast contract</em>.
	 *
	 * <p>{@code @Mixin(Foo.class) class FooMixin implements Bar} makes the merged {@code Foo} implement {@code Bar},
	 * which is how a mod then writes {@code ((Bar) foo).something()}. Dropping such a mixin does not merely remove a
	 * feature, it converts every one of those casts into a {@code ClassCastException}. Verified live:
	 * {@code fabric-rendering-v1}'s {@code GuiRendererMixin implements GuiRendererExtensions} while its sibling
	 * {@code GameRendererMixin} does {@code checkcast GuiRendererExtensions} — suppressing the first alone turns the
	 * orphaned-field NPE into a CCE, which is strictly worse because it fires on a path that used to work.
	 */
	public static Set<String> contributedInterfaces(ClassNode mixin) {
		if (mixin.interfaces == null || mixin.interfaces.isEmpty()) return Set.of();
		Set<String> out = new LinkedHashSet<>();
		for (String itf : mixin.interfaces) {
			// Mixin's own infrastructure types are not the mod's contract.
			if (itf.startsWith("org/spongepowered/asm/") || itf.startsWith("com/llamalad7/mixinextras/")) continue;
			out.add(itf);
		}
		return out;
	}

	/**
	 * The methods of duck interface {@code contract} its implementer has to supply: the abstract ones, and the defaults
	 * that only throw — Fabric writes {@code throw new AssertionError("Implemented by mixin")} for every method its
	 * mixin implements, so an interface can be fully "default" and still have nothing behind it. A default that calls
	 * back into the contract ({@code switchToNextPage} is {@code switchToPage(getCurrentPage() + 1)}) is not counted:
	 * it works once the others do. Nor is one that can return — a guard throw before a real body is a working
	 * default. As {@code name + descriptor}.
	 */
	public static Set<String> implementerSupplies(ClassNode contract) {
		Set<String> out = new LinkedHashSet<>();
		if (contract.methods == null) return out;
		for (MethodNode m : contract.methods) {
			if ((m.access & (Opcodes.ACC_STATIC | Opcodes.ACC_PRIVATE)) != 0 || m.name.startsWith("<")) continue;
			if ((m.access & Opcodes.ACC_ABSTRACT) != 0) {
				out.add(m.name + m.desc);
				continue;
			}
			boolean throwsAny = false, returns = false, delegates = false;
			for (AbstractInsnNode insn : m.instructions) {
				int op = insn.getOpcode();
				if (op == Opcodes.ATHROW) throwsAny = true;
				if (op >= Opcodes.IRETURN && op <= Opcodes.RETURN) returns = true;
				if (insn instanceof MethodInsnNode call && call.owner.equals(contract.name)) delegates = true;
			}
			if (throwsAny && !returns && !delegates) out.add(m.name + m.desc);
		}
		return out;
	}

	/**
	 * The {@link #implementerSupplies} methods of {@code contract} that {@code target} does not declare with a body —
	 * empty when the target stands behind the interface on its own, whoever implants it.
	 */
	public static List<String> unsupplied(ClassNode contract, ClassNode target) {
		Set<String> declared = new LinkedHashSet<>();
		if (target.methods != null) {
			for (MethodNode m : target.methods) {
				if ((m.access & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_STATIC)) == 0) declared.add(m.name + m.desc);
			}
		}
		List<String> out = new ArrayList<>();
		for (String method : implementerSupplies(contract)) if (!declared.contains(method)) out.add(method);
		return out;
	}

	/** Whether {@code mixin} depends on any of {@code interfaces} — implements it, casts to it, or calls through it. */
	public static boolean referencesAny(ClassNode mixin, Set<String> interfaces) {
		if (interfaces.isEmpty()) return false;
		if (mixin.interfaces != null) {
			for (String itf : mixin.interfaces) {
				if (interfaces.contains(itf)) return true;
			}
		}
		if (mixin.methods == null) return false;
		for (MethodNode m : mixin.methods) {
			if (m.desc != null && mentions(m.desc, interfaces)) return true;
			if (m.instructions == null) continue;
			for (AbstractInsnNode insn : m.instructions) {
				if (insn instanceof org.objectweb.asm.tree.TypeInsnNode ti && interfaces.contains(ti.desc)) return true;
				if (insn instanceof MethodInsnNode mi
						&& (interfaces.contains(mi.owner) || mentions(mi.desc, interfaces))) {
					return true;
				}
				if (insn instanceof FieldInsnNode fi
						&& (interfaces.contains(fi.owner) || mentions(fi.desc, interfaces))) {
					return true;
				}
			}
		}
		return false;
	}

	private static boolean mentions(String desc, Set<String> interfaces) {
		for (String itf : interfaces) {
			if (desc.contains("L" + itf + ";")) return true;
		}
		return false;
	}

	/** Parses {@code bytes} into a node with code, for the cast-contract pass. */
	public static ClassNode parse(byte[] bytes) {
		return read(bytes, true);
	}

	public static AnnotationNode injectorOf(MethodNode m) {
		AnnotationNode a = firstOf(m.visibleAnnotations);
		return a != null ? a : firstOf(m.invisibleAnnotations);
	}

	private static AnnotationNode firstOf(List<AnnotationNode> annotations) {
		if (annotations == null) return null;
		for (AnnotationNode a : annotations) {
			if (INJECTOR_DESCS.contains(a.desc)) return a;
		}
		return null;
	}

	/** The {@code @At} annotations nested in an injector's {@code at}/{@code slice} values. */
	public static List<AnnotationNode> atNodes(AnnotationNode injector) {
		List<AnnotationNode> out = new ArrayList<>();
		Object at = value(injector, "at");
		if (at instanceof AnnotationNode single && AT_DESC.equals(single.desc)) {
			out.add(single);
		} else if (at instanceof List<?> list) {
			for (Object element : list) {
				if (element instanceof AnnotationNode a && AT_DESC.equals(a.desc)) out.add(a);
			}
		}
		return out;
	}

	public static Object value(AnnotationNode a, String key) {
		if (a == null || a.values == null) return null;
		for (int i = 0; i + 1 < a.values.size(); i += 2) {
			if (key.equals(a.values.get(i))) return a.values.get(i + 1);
		}
		return null;
	}

	public static List<String> stringList(Object value) {
		if (value instanceof String s) return List.of(s);
		if (!(value instanceof List<?> list)) return Collections.emptyList();
		List<String> out = new ArrayList<>();
		for (Object element : list) {
			if (element instanceof String s && !s.isBlank()) out.add(s);
		}
		return out;
	}

	static String asString(Object value) {
		if (value instanceof String s) return s;
		// @At(value=…) is a plain String; an enum would arrive as String[]{desc, name}.
		if (value instanceof String[] enumValue && enumValue.length == 2) return enumValue[1];
		return null;
	}

	private static boolean has(List<AnnotationNode> annotations, String desc) {
		if (annotations == null) return false;
		for (AnnotationNode a : annotations) {
			if (desc.equals(a.desc)) return true;
		}
		return false;
	}

	/**
	 * {@code @At(NEW)}: the handler of a {@code @WrapOperation} or {@code @Redirect} wraps a CONSTRUCTOR, and its
	 * leading parameters are that constructor's arguments. When the merge gave the call site a different
	 * constructor (NeoForge's {@code RenderPipeline$Snippet} takes 12 arguments where vanilla's takes 11), the
	 * anchor is not "absent" — the type is still constructed there — but Mixin rejects the handler at apply time
	 * ("has an invalid signature"), which drops the whole mixin. Judged by arity and types against every
	 * construction of the type inside the hit methods; other injector kinds only need the construction to exist.
	 */
	private static Anchor newAnchor(AnnotationNode injector, MethodNode handler, String atTarget, List<MethodNode> hits,
			List<MethodNode> declared) {
		String type;
		Type[] wanted = null;
		if (atTarget.startsWith("(")) {
			Type method = Type.getMethodType(atTarget);
			type = method.getReturnType().getInternalName();
			wanted = method.getArgumentTypes();
		} else {
			type = atTarget.startsWith("L") && atTarget.endsWith(";") ? atTarget.substring(1, atTarget.length() - 1) : atTarget;
		}
		Type[] expect = null;
		// The constructor's own arguments, as the handler receives them: before a @WrapOperation's Operation, and never
		// a MixinExtras sugar parameter (@Local, @Share) — those come from the target method, not the call.
		List<Type> own = new ArrayList<>();
		Type[] params = Type.getArgumentTypes(handler.desc);
		for (int i = 0; i < params.length; i++) {
			if (WRAP_OPERATION_DESC.equals(injector.desc) && OPERATION_DESC.equals(params[i].getDescriptor())) break;
			if (!sugar(handler, i)) own.add(params[i]);
		}
		if (WRAP_OPERATION_DESC.equals(injector.desc) || REDIRECT_DESC.equals(injector.desc)) expect = own.toArray(new Type[0]);
		boolean constructed = false;
		boolean resolved = false;
		int seen = -1;
		for (MethodNode hit : hits) {
			for (AbstractInsnNode insn = hit.instructions.getFirst(); insn != null; insn = insn.getNext()) {
				if (!(insn instanceof org.objectweb.asm.tree.TypeInsnNode t) || t.getOpcode() != Opcodes.NEW || !type.equals(t.desc)) continue;
				for (AbstractInsnNode c = insn.getNext(); c != null; c = c.getNext()) {
					if (c instanceof MethodInsnNode call && call.getOpcode() == Opcodes.INVOKESPECIAL && "<init>".equals(call.name) && type.equals(call.owner)) {
						Type[] args = Type.getArgumentTypes(call.desc);
						constructed = true;
						seen = args.length;
						if (wanted != null && !java.util.Arrays.equals(args, wanted)) break;
						if (expect == null || java.util.Arrays.equals(args, expect) || capturesTargetArgs(expect, args, hit)) resolved = true;
						break;
					}
				}
			}
		}
		String simple = type.substring(type.lastIndexOf('/') + 1);
		String where = " in " + hits.get(0).name;
		// The same move MixinAtWidenedCall makes for an argument-blind injector, by its own predicate, so verdict and
		// rewrite agree; a @Group alternative is judged as before, for the reason given at the INVOKE points above.
		if (!resolved && wanted != null && (!asksAnchorMovers() || MixinAtWidenedCall.inGroup(handler))) {
			if (MixinAtWidenedCall.argumentBlind(injector.desc)) {
				for (MethodNode hit : hits) if (MixinAtWidenedCall.widenedNewIn(hit, atTarget) != null) { resolved = true; break; }
			}
		} else if (!resolved && wanted != null && MixinAtWidenedCall.wouldMove(handler, injector, declared, "NEW", atTarget) != null) {
			resolved = true;
		}
		if (resolved) return new Anchor("@At(NEW)", simple + where, true);
		if (!constructed) return new Anchor("@At(NEW)", simple + " is not constructed" + where, false);
		if (expect == null) return new Anchor("@At(NEW)", simple + ": names the " + (wanted == null ? "?" : wanted.length)
				+ "-arg constructor, the call site constructs with " + seen + where, false);
		return new Anchor("@At(NEW)", simple + ": handler wraps a " + expect.length
				+ "-arg constructor, the call site constructs with " + seen + where, false);
	}

	/** Whether handler parameter {@code index} carries a MixinExtras sugar annotation (@Local, @Share, …). */
	static boolean sugar(MethodNode handler, int index) {
		for (List<AnnotationNode>[] set : java.util.Arrays.asList(handler.visibleParameterAnnotations, handler.invisibleParameterAnnotations)) {
			if (set == null || index >= set.length || set[index] == null) continue;
			for (AnnotationNode a : set[index]) if (a.desc.startsWith("Lcom/llamalad7/mixinextras/sugar/")) return true;
		}
		return false;
	}

	/** A @Redirect of NEW may also take the target method's arguments after the constructor's: args + host's own. */
	private static boolean capturesTargetArgs(Type[] expect, Type[] args, MethodNode host) {
		Type[] captured = Type.getArgumentTypes(host.desc);
		if (expect.length != args.length + captured.length) return false;
		for (int i = 0; i < args.length; i++) if (!expect[i].equals(args[i])) return false;
		for (int i = 0; i < captured.length; i++) if (!expect[args.length + i].equals(captured[i])) return false;
		return true;
	}

	private static Anchor accessorAnchor(MethodNode m, ClassNode target, Function<String, byte[]> resolver) {
		AnnotationNode a = annotation(m, ACCESSOR_DESC);
		String name = asString(value(a, "value"));
		Type[] params = Type.getArgumentTypes(m.desc);
		Type ret = Type.getReturnType(m.desc);
		String desc;
		if (params.length == 0 && ret.getSort() != Type.VOID) {
			desc = ret.getDescriptor();
			if (name == null || name.isEmpty()) name = derived(m.name, "get", "is");
		} else if (params.length == 1 && ret.getSort() == Type.VOID) {
			desc = params[0].getDescriptor();
			if (name == null || name.isEmpty()) name = derived(m.name, "set");
		} else {
			return null;    // not a shape this can judge
		}
		if (name == null) return null;
		return new Anchor("@Accessor field", name + ":" + desc, findField(target, name, desc, resolver) != null);
	}

	private static Anchor invokerAnchor(MethodNode m, ClassNode target, Function<String, byte[]> resolver) {
		AnnotationNode a = annotation(m, INVOKER_DESC);
		String name = asString(value(a, "value"));
		if (name == null || name.isEmpty()) name = derived(m.name, "invoke", "call");
		if (name == null || name.startsWith("<")) return null;    // constructor invokers: not judged
		return new Anchor("@Invoker method", name + m.desc, findMethod(target, name, m.desc, resolver) != null);
	}

	/** Mixin's implicit accessor naming: strip one of the prefixes and decapitalise. */
	private static String derived(String method, String... prefixes) {
		for (String prefix : prefixes) {
			if (method.length() > prefix.length() && method.startsWith(prefix)
					&& Character.isUpperCase(method.charAt(prefix.length()))) {
				String rest = method.substring(prefix.length());
				return Character.toLowerCase(rest.charAt(0)) + rest.substring(1);
			}
		}
		return null;
	}

	private static AnnotationNode annotation(MethodNode m, String desc) {
		for (List<AnnotationNode> table : new List[] { m.visibleAnnotations, m.invisibleAnnotations }) {
			if (table == null) continue;
			for (AnnotationNode a : table) if (desc.equals(a.desc)) return a;
		}
		return null;
	}

	private static String shortMember(String target) {
		Member m = parseMember(target);
		return m == null ? target : m.name();
	}

	/**
	 * An {@code @At} target as the report names it: {@code Owner.member} when the member belongs to another class
	 * than the mixin's target, the bare member otherwise (the target's name is prefixed by {@link Anchor#describe}).
	 *
	 * <p>It used to always be the bare member, so every call into ANOTHER class was reported under the target's
	 * name. owo's {@code MainMixin} anchors on Fabric Loader's {@code Hooks.startServer}, and the report said
	 * "missing: @At(INVOKE) Main.startServer in main" — a method that does not exist, which pointed triage at the
	 * merged {@code Main} instead of at the missing {@code Hooks} call that was the real cause.
	 *
	 * <p>The owner is named in full: owo's Quilt alternative anchors on a class ALSO called {@code Hooks}, and by simple
	 * name its miss read exactly like the Fabric call that had just been fixed.
	 */
	private static String atDetail(String atTarget, String targetName) {
		if (!ownedElsewhere(atTarget, targetName)) return shortMember(atTarget);
		Member m = parseMember(atTarget);
		return m.owner().replace('/', '.') + "." + m.name();
	}

	private static String simpleName(String internalName) {
		return internalName.substring(internalName.lastIndexOf('/') + 1);
	}

	private static boolean ownedElsewhere(String atTarget, String targetName) {
		Member m = parseMember(atTarget);
		return m != null && m.owner() != null && !m.owner().equals(targetName);
	}

	private static ClassNode read(byte[] bytes, boolean withCode) {
		ClassNode node = new ClassNode();
		int flags = ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES | (withCode ? 0 : ClassReader.SKIP_CODE);
		new ClassReader(bytes).accept(node, flags);
		return node;
	}

	/** {@code internalName} with everything, local variable tables included, as the adapters read it; null when unseen. */
	private static ClassNode withCode(Function<String, byte[]> resolver, String internalName) {
		byte[] bytes = resolver.apply(internalName + ".class");
		if (bytes == null) return null;
		ClassNode node = new ClassNode();
		new ClassReader(bytes).accept(node, 0);
		return node;
	}
}
