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
import java.util.Set;
import java.util.function.Function;

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
	 * @param verdict    the decision
	 * @param unresolved human-readable anchors that did not resolve, for the log
	 * @param resolved   how many anchors resolved
	 * @param total      how many anchors were checked
	 */
	public record Result(Verdict verdict, List<String> unresolved, int resolved, int total,
			List<String> foreign) {
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
			return switch (verdict) {
				case FIT -> "all " + total + " anchor(s) resolve";
				case PARTIAL -> resolved + "/" + total + " anchors resolve, missing: " + String.join(", ", unresolved);
				case UNFIT -> "no anchor resolves (" + String.join(", ", unresolved) + ")";
				case HAZARD -> "orphaned @Shadow field(s): " + String.join(", ", unresolved);
			};
		}
	}

	/** {@code -Dforbric.mixinFit=strict} also drops PARTIAL mixins; see {@link Result#shouldSuppress()}. */
	public static boolean strict() {
		return "strict".equalsIgnoreCase(System.getProperty("forbric.mixinFit", "default"));
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
		ClassNode mixin = read(mixinBytes, false);
		List<String> targets = mixinTargets(mixin);
		if (targets.isEmpty()) return new Result(Verdict.FIT, List.of(), 0, 0, List.of());

		List<String> unresolved = new ArrayList<>();
		List<String> orphaned = new ArrayList<>();
		int resolved = 0;
		int total = 0;
		int softMisses = 0;

		List<String> foreign = new ArrayList<>();
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

			List<Anchor> anchors = new ArrayList<>(anchorsOf(mixin, target, targetResolver));
			// A renumbered anonymous class: every member anchor may resolve and still belong to a different class
			// than the one vanilla compiled at that name. Soft — it forces PARTIAL, never UNFIT.
			if (moved == null && gameOwned && MergedBaseAnonymousDrift.drifted(targetName)) {
				anchors.add(new Anchor("@Mixin target", targetName.substring(targetName.lastIndexOf('/') + 1)
						+ " is not the class vanilla compiled at that name (" + MergedBaseAnonymousDrift.describe(targetName)
						+ ")", false, true));
			}
			for (Anchor anchor : anchors) {
				total++;
				if (anchor.resolved) {
					resolved++;
				} else {
					unresolved.add(anchor.describe(targetName));
					if (anchor.soft) softMisses++;
					if (!gameOwned) foreign.add(anchor.describe(targetName));
				}
			}
			orphaned.addAll(orphanedShadowFields(mixin, target, targetResolver));
		}

		// An orphaned @Shadow field is the silent case: it resolves (the field is still declared) and then reads
		// null at runtime. It outranks the count-based verdicts precisely because nothing else detects it.
		if (!orphaned.isEmpty()) return new Result(Verdict.HAZARD, orphaned, resolved, total, List.of());
		if (total == 0 || unresolved.isEmpty()) return new Result(Verdict.FIT, List.of(), resolved, total, List.of());
		// UNFIT is "no HARD anchor resolves"; a soft miss alone is PARTIAL, whatever else is there.
		boolean anyHardResolved = resolved > 0 || unresolved.size() == softMisses;
		return new Result(anyHardResolved ? Verdict.PARTIAL : Verdict.UNFIT, unresolved, resolved, total,
				List.copyOf(foreign));
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

		Anchor(String kind, String detail, boolean resolved) {
			this(kind, detail, resolved, false);
		}

		Anchor(String kind, String detail, boolean resolved, boolean soft) {
			this.kind = kind;
			this.detail = detail;
			this.resolved = resolved;
			this.soft = soft;
		}

		String describe(String target) {
			return kind + " " + target.substring(target.lastIndexOf('/') + 1) + "." + detail;
		}
	}

	private static List<Anchor> anchorsOf(ClassNode mixin, ClassNode target, Function<String, byte[]> resolver) {
		List<Anchor> out = new ArrayList<>();

		// @Shadow fields: the member must still be declared (walking the superclass chain).
		if (mixin.fields != null) {
			for (FieldNode f : mixin.fields) {
				if (!has(f.visibleAnnotations, SHADOW_DESC) && !has(f.invisibleAnnotations, SHADOW_DESC)) continue;
				out.add(new Anchor("@Shadow field", f.name,
						findField(target, f.name, f.desc, resolver) != null));
			}
		}

		if (mixin.methods == null) return out;
		for (MethodNode m : mixin.methods) {
			if (m.name.startsWith("<")) continue;

			// @Shadow methods: must still exist by name+desc.
			if (has(m.visibleAnnotations, SHADOW_DESC) || has(m.invisibleAnnotations, SHADOW_DESC)) {
				out.add(new Anchor("@Shadow method", m.name + m.desc,
						findMethod(target, m.name, m.desc, resolver) != null));
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

			// An injector's `method` is a list of CANDIDATE selectors, not a conjunction. Mixin's default
			// require=1 counts matches across the whole list, so mods routinely ship alternative names to span
			// mappings or MC versions — Iris's LevelRenderer mixin carries both `lambda$addSkyPass$0` AND
			// `lambda$addSkyPass$8` for the same handler. Requiring EVERY selector to resolve reported those as
			// missing anchors and made 11 of Iris's 41 look unapplied when the injector was installed the whole
			// time. Judge the injector, not the selector: it is applied iff ANY selector resolves.
			List<String> selectors = stringList(value(injector, "method"));
			List<MethodNode> hits = new ArrayList<>();
			List<String> misses = new ArrayList<>();
			for (String selector : selectors) {
				List<MethodNode> targetMethods = resolveSelector(target, selector, resolver);
				if (!targetMethods.isEmpty()) hits.addAll(targetMethods); else misses.add(selector);
			}
			if (selectors.isEmpty()) continue;
			String where = misses.isEmpty() ? String.join("|", selectors)
					: hits.isEmpty() ? String.join("|", misses)
					: String.join("|", misses) + " (" + hits.size() + "/" + selectors.size() + " selectors hit)";
			out.add(new Anchor("@Inject target", where, !hits.isEmpty()));
			if (hits.isEmpty()) continue;

			// Each @At(INVOKE/FIELD, target=…) must name an instruction inside a method the injector actually
			// bound to — again ANY, for the same require=1 reason.
			for (AnnotationNode at : atNodes(injector)) {
				String atValue = asString(value(at, "value"));
				String atTarget = asString(value(at, "target"));
				if (atTarget == null || atValue == null) continue;
				if ("NEW".equals(atValue)) {
					out.add(newAnchor(injector, m, atTarget, hits));
					continue;
				}
				if (!RESOLVABLE_AT.contains(atValue)) continue;
				boolean anywhere = false;
				for (MethodNode hit : hits) {
					if (containsMember(hit, atTarget)) { anywhere = true; break; }
				}
				// The same move MixinAtWidenedCall will make, judged here too so the verdict and the rewrite
				// cannot disagree about whether this point resolves.
				if (!anywhere) {
					for (MethodNode hit : hits) {
						if (MixinAtWidenedCall.widenedIn(hit, atTarget) != null) { anywhere = true; break; }
					}
				}
				out.add(new Anchor("@At(" + atValue + ")",
						shortMember(atTarget) + " in " + hits.get(0).name, anywhere));
			}
		}
		return out;
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

			FieldNode declared = findField(target, f.name, f.desc, resolver);
			// Absent is a LOUD failure — Mixin reports it and relax soft-skips. Not this rule's business.
			if (declared == null) continue;
			// A compile-time constant carries a ConstantValue attribute and is initialized by the JVM with no
			// putstatic at all, so "never assigned" is meaningless for it. Missing this check false-positives on
			// every @Shadow'd `static final int` — MAX_PAYLOAD_SIZE, FLAG_INSIDE_FACE, MAX_DESCRIPTION_WIDTH_PIXELS.
			if (declared.value != null) continue;
			if ((declared.access & Opcodes.ACC_PRIVATE) == 0) continue;
			// PostMixinFixups seeds some orphans rather than letting them poison every reader; those are not
			// hazards. This runs on pre-mixin (and therefore pre-repair) bytes, so it must be asked explicitly.
			if (PostMixinFixups.isSeeded(target.name, f.name)) continue;
			if (writesField(mixin, f.name)) continue;
			if (nestWritesField(target, f.name, resolver)) continue;

			orphans.add(f.name + " (declared, never assigned)");
		}
		return orphans;
	}

	/** Whether the declaring class or any of its nestmates assigns {@code field}. */
	private static boolean nestWritesField(ClassNode target, String field, Function<String, byte[]> resolver) {
		if (writesField(target, field)) return true;
		for (String member : nestMembers(target)) {
			byte[] bytes = resolver.apply(member + ".class");
			if (bytes == null) continue;
			if (writesField(read(bytes, true), field)) return true;
		}
		return false;
	}

	private static List<String> nestMembers(ClassNode node) {
		Set<String> members = new LinkedHashSet<>();
		if (node.nestMembers != null) members.addAll(node.nestMembers);
		// Fall back to the InnerClasses attribute when NestMembers is absent (pre-11 class files, or stripped).
		if (node.innerClasses != null) {
			for (org.objectweb.asm.tree.InnerClassNode inner : node.innerClasses) {
				if (inner.name != null && inner.name.startsWith(node.name + "$")) members.add(inner.name);
			}
		}
		members.remove(node.name);
		return new ArrayList<>(members);
	}

	private static boolean writesField(ClassNode node, String field) {
		if (node.methods == null) return false;
		for (MethodNode m : node.methods) {
			if (m.instructions == null) continue;
			for (AbstractInsnNode insn : m.instructions) {
				if (insn instanceof FieldInsnNode fi
						&& (fi.getOpcode() == Opcodes.PUTFIELD || fi.getOpcode() == Opcodes.PUTSTATIC)
						&& fi.name.equals(field)) {
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
	private static List<MethodNode> resolveSelector(ClassNode target, String selector,
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

	static AnnotationNode injectorOf(MethodNode m) {
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
	static List<AnnotationNode> atNodes(AnnotationNode injector) {
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

	static Object value(AnnotationNode a, String key) {
		if (a == null || a.values == null) return null;
		for (int i = 0; i + 1 < a.values.size(); i += 2) {
			if (key.equals(a.values.get(i))) return a.values.get(i + 1);
		}
		return null;
	}

	static List<String> stringList(Object value) {
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
	private static Anchor newAnchor(AnnotationNode injector, MethodNode handler, String atTarget, List<MethodNode> hits) {
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
		// The same move MixinAtWidenedCall makes for an argument-blind injector, so verdict and rewrite agree.
		if (!resolved && wanted != null && MixinAtWidenedCall.argumentBlind(injector.desc)) {
			for (MethodNode hit : hits) if (MixinAtWidenedCall.widenedNewIn(hit, atTarget) != null) { resolved = true; break; }
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

	private static ClassNode read(byte[] bytes, boolean withCode) {
		ClassNode node = new ClassNode();
		int flags = ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES | (withCode ? 0 : ClassReader.SKIP_CODE);
		new ClassReader(bytes).accept(node, flags);
		return node;
	}
}
