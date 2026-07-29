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

	/** Injector annotations whose {@code method} value names one or more target methods on the mixin's target. */
	private static final Set<String> INJECTOR_DESCS = Set.of(
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
	private static final Set<String> RESOLVABLE_AT = Set.of("INVOKE", "INVOKE_ASSIGN", "FIELD");

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
	public record Result(Verdict verdict, List<String> unresolved, int resolved, int total) {
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
		ClassNode mixin = read(mixinBytes, false);
		List<String> targets = mixinTargets(mixin);
		if (targets.isEmpty()) return new Result(Verdict.FIT, List.of(), 0, 0);

		List<String> unresolved = new ArrayList<>();
		List<String> orphaned = new ArrayList<>();
		int resolved = 0;
		int total = 0;

		for (String targetName : targets) {
			byte[] targetBytes = targetResolver.apply(targetName + ".class");
			// Not a class we can see (JDK, another mod, a mixin-generated type): nothing to prove, assume it fits.
			if (targetBytes == null) continue;
			ClassNode target = read(targetBytes, true);

			for (Anchor anchor : anchorsOf(mixin, target, targetResolver)) {
				total++;
				if (anchor.resolved) {
					resolved++;
				} else {
					unresolved.add(anchor.describe(targetName));
				}
			}
			orphaned.addAll(orphanedShadowFields(mixin, target, targetResolver));
		}

		// An orphaned @Shadow field is the silent case: it resolves (the field is still declared) and then reads
		// null at runtime. It outranks the count-based verdicts precisely because nothing else detects it.
		if (!orphaned.isEmpty()) return new Result(Verdict.HAZARD, orphaned, resolved, total);
		if (total == 0 || unresolved.isEmpty()) return new Result(Verdict.FIT, List.of(), resolved, total);
		return new Result(resolved == 0 ? Verdict.UNFIT : Verdict.PARTIAL, unresolved, resolved, total);
	}

	// ---------------------------------------------------------------------------------------------------------------
	// Anchors
	// ---------------------------------------------------------------------------------------------------------------

	private static final class Anchor {
		final String kind;
		final String detail;
		final boolean resolved;

		Anchor(String kind, String detail, boolean resolved) {
			this.kind = kind;
			this.detail = detail;
			this.resolved = resolved;
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
				MethodNode targetMethod = resolveSelector(target, selector, resolver);
				if (targetMethod != null) hits.add(targetMethod); else misses.add(selector);
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
				if (atTarget == null || atValue == null || !RESOLVABLE_AT.contains(atValue)) continue;
				boolean anywhere = false;
				for (MethodNode hit : hits) {
					if (containsMember(hit, atTarget)) { anywhere = true; break; }
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
		for (ClassNode c : hierarchy(node, resolver)) {
			if (c.methods == null) continue;
			for (MethodNode m : c.methods) {
				if (m.name.equals(name) && (desc == null || m.desc.equals(desc))) return m;
			}
		}
		return null;
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
	 * A Mixin method selector: {@code name}, {@code name(desc)ret}, or {@code Lowner;name(desc)ret}. Anything with a
	 * wildcard or a shape this does not understand resolves to the first same-named method, and to "resolved" if
	 * there is none to compare against — see the conservatism note on the class.
	 */
	private static MethodNode resolveSelector(ClassNode target, String selector, Function<String, byte[]> resolver) {
		if (selector == null || selector.isBlank()) return null;
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
		return findMethod(target, name, desc, resolver);
	}

	private static MethodNode firstMethod(ClassNode target, Function<String, byte[]> resolver) {
		return target.methods == null || target.methods.isEmpty() ? null : target.methods.get(0);
	}

	/** Whether {@code method}'s body contains the invocation or field access {@code at} names. */
	private static boolean containsMember(MethodNode method, String at) {
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

	private record Member(String owner, String name, String desc) {
	}

	/** Parses {@code Lowner;name(args)ret} and {@code Lowner;name:Ldesc;}. Returns null when the shape is unfamiliar. */
	private static Member parseMember(String target) {
		String s = target.trim();
		if (s.isEmpty() || s.indexOf('*') >= 0) return null;

		String owner = null;
		int semi = s.indexOf(';');
		if (s.startsWith("L") && semi > 0) {
			owner = s.substring(1, semi);
			s = s.substring(semi + 1);
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

	private static AnnotationNode injectorOf(MethodNode m) {
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
	private static List<AnnotationNode> atNodes(AnnotationNode injector) {
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

	private static Object value(AnnotationNode a, String key) {
		if (a == null || a.values == null) return null;
		for (int i = 0; i + 1 < a.values.size(); i += 2) {
			if (key.equals(a.values.get(i))) return a.values.get(i + 1);
		}
		return null;
	}

	private static List<String> stringList(Object value) {
		if (value instanceof String s) return List.of(s);
		if (!(value instanceof List<?> list)) return Collections.emptyList();
		List<String> out = new ArrayList<>();
		for (Object element : list) {
			if (element instanceof String s && !s.isBlank()) out.add(s);
		}
		return out;
	}

	private static String asString(Object value) {
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
