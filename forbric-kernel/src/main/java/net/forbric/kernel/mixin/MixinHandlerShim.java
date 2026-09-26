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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
 * <h2>The second evidence: a lambda the merge kept once, with its captures reordered</h2>
 *
 * <p>The pruner only knows about names the merge carried TWICE. {@code WorldLoader.load}'s {@code lambda$load$1} it
 * carried once — NeoForge's — and NeoForge's patch passes {@code staticLayerTags} on to
 * {@code RegistryDataLoader.load}; javac orders a lambda's captures by first use, so that one capture moved from
 * sixth to fourth and nothing else changed. wover-events (bclib, WorldWeaver) hooks that lambda's HEAD with a
 * vanilla-shaped handler to publish the worldgen registries; Mixin rejected the descriptor, the whole required mixin
 * aborted, and a dedicated server with bclib died on "Failed to load datapacks" before it made a world.
 *
 * <p>So {@code lambda-permutations.txt}, which LambdaPermutationCensusTest re-derives from the vanilla jar and the
 * staged merged base, lists every vanilla lambda the merged class declares once with the same parameters in another
 * order — and the order is taken from the local variable NAMES, each vanilla parameter matching exactly one merged
 * one by name and type. The type-only guard below would refuse WorldLoader's (two {@code List}s, two
 * {@code Executor}s); a row does not need it, because a row is not a guess. A row is used only when the handler fits
 * its vanilla descriptor, does not fit the live one, and the live descriptor IS the row's merged one: a table left
 * stale by a base rebuild is a no-op, never a wrong mapping. A selector that spells the vanilla descriptor is
 * rewritten to the live one. {@code -Dforbric.lambdaPermutations=off} turns this source off alone.
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

	/** {@code -Dforbric.lambdaPermutations=off}: no wrap on the census table's evidence; the pruner's still counts. */
	static final String TABLE_PROPERTY = "forbric.lambdaPermutations";

	/** The shipped census of reordered lambdas; LambdaPermutationCensusTest pins it to the staged artifacts. */
	static final String TABLE = "/net/forbric/kernel/mixin/lambda-permutations.txt";
	private static volatile Map<String, Permutation> permutations;

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

	static boolean tableEnabled() {
		return !"off".equalsIgnoreCase(System.getProperty(TABLE_PROPERTY, "on"));
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
			Plan plan = plan(handler, targetNames, targets);
			if (plan == null) continue;
			String selector = plan.live().name;
			MethodNode outer = wrap(mixin, handler, plan);
			added.add(outer);
			wrapped++;
			if (plan.row() == null) {
				ForbricLog.warn("[Forbric/Mixin] %s.%s was written for a differently shaped %s — the merge kept the "
						+ "other ecosystem's, which takes %s. Its handler is wrapped so it still receives the values it "
						+ "asked for, in the order it asked for them", mixin.name, outer.name, selector, plan.live().desc);
			} else {
				ForbricLog.warn("[Forbric/Mixin] %s.%s was written for a differently shaped %s — vanilla's %s; the merged "
						+ "base keeps the same parameters in another order, %s (a carrier patch uses a capture earlier). Its "
						+ "handler is wrapped so it still receives the values it asked for, in the order it asked for them",
						mixin.name, outer.name, selector, plan.row().vanillaDesc(), plan.live().desc);
			}
		}
		mixin.methods.addAll(added);
		return wrapped;
	}

	/**
	 * The method {@code handler}'s injection lands on once {@link #adapt} has wrapped it, or null when it will not be
	 * wrapped — the same decision, nothing changed. {@link MixinFit} asks this for a selector that spells vanilla's
	 * descriptor of a reordered lambda: without it that injector reads as a miss and a one-injector mixin is removed
	 * from its config before the wrap could ever run.
	 */
	public static MethodNode destination(MethodNode handler, ClassNode target) {
		if (!enabled() || handler == null || target == null || target.methods == null) return null;
		Plan plan = plan(handler, List.of(target.name), name -> name.equals(target.name) ? target : null);
		return plan == null ? null : plan.live();
	}

	/**
	 * One wrap, decided: the injector, the one method it lands on, which target argument each handler argument is,
	 * the selector to write when the mod spelled vanilla's descriptor, and the census row when that was the evidence.
	 */
	private record Plan(AnnotationNode inject, MethodNode live, int[] mapping, String selector, Permutation row) {
	}

	private static Plan plan(MethodNode handler, List<String> targetNames, Function<String, ClassNode> targets) {
		if (handler.name.endsWith(INNER_SUFFIX)) return null;
		AnnotationNode inject = injectOf(handler);
		if (inject == null) return null;
		String selector = onlySelector(inject);
		if (selector == null) return null;
		int paren = selector.indexOf('(');
		String name = paren < 0 ? selector : selector.substring(0, paren);

		String[] owner = new String[1];
		MethodNode target = onlyTarget(name, targetNames, targets, owner);
		if (target == null) return null;

		Type[] wanted = Type.getArgumentTypes(handler.desc);
		Type[] available = Type.getArgumentTypes(target.desc);
		if (wanted.length == 0) return null;
		// Everything before the callback is a value to be handed over; the callback itself is the last parameter.
		String callback = wanted[wanted.length - 1].getInternalName();
		if (!CALLBACK_INFO.equals(callback) && !CALLBACK_INFO_RETURNABLE.equals(callback)) return null;
		boolean targetStatic = (target.access & Opcodes.ACC_STATIC) != 0;
		boolean handlerStatic = (handler.access & Opcodes.ACC_STATIC) != 0;
		if (targetStatic && !handlerStatic) return null;    // no receiver to call the inner one on

		// The pruner's record first, for a bare name only: its guard maps by type and refuses a repeated one.
		if (paren < 0 && wasWrittenForAShapeTheMergeDropped(handler, name, target, targetNames, targets)) {
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
			return new Plan(inject, target, mapping, null, null);
		}

		// Then the census: the row's own order, proven by name and type when it was derived.
		Permutation row = rowFor(owner[0], name, paren < 0 ? null : selector.substring(paren), handler, target);
		if (row == null || row.perm().length != wanted.length - 1) return null;
		for (int i = 0; i < row.perm().length; i++) if (!available[row.perm()[i]].equals(wanted[i])) return null;
		return new Plan(inject, target, row.perm().clone(), paren < 0 ? null : name + target.desc, row);
	}

	/**
	 * The census row that is evidence for this handler on this live method, or null: the handler fits vanilla's
	 * descriptor and not the live one, the live one is the row's merged one, and a spelled selector names vanilla's.
	 */
	private static Permutation rowFor(String owner, String name, String spelled, MethodNode handler, MethodNode live) {
		if (!tableEnabled() || owner == null) return null;
		Permutation row = permutations().get(owner + "#" + name);
		if (row == null || !live.desc.equals(row.mergedDesc())) return null;
		if (spelled != null && !spelled.equals(row.vanillaDesc())) return null;
		if (!MixinOverloadPin.fits(handler.desc, row.vanillaDesc()) || MixinOverloadPin.fits(handler.desc, live.desc)) return null;
		return row;
	}

	/**
	 * One census row: {@code owner#name vanillaDesc -> mergedDesc p0,p1,…}, where {@code p[i]} is the merged index
	 * holding vanilla's parameter {@code i}.
	 */
	record Permutation(String owner, String name, String vanillaDesc, String mergedDesc, int[] perm) {
		/** The row, or null when it is not one reordering of one parameter list into the other. */
		static Permutation parse(String line) {
			try {
				String row = line.trim();
				int arrow = row.indexOf(" -> "), hash = row.indexOf('#');
				if (arrow < 0 || hash <= 0 || hash > arrow) return null;
				String left = row.substring(0, arrow);
				int paren = left.indexOf('(', hash);
				if (paren < 0) return null;
				String[] right = row.substring(arrow + 4).trim().split(" ");
				if (right.length != 2) return null;
				String vanillaDesc = left.substring(paren), mergedDesc = right[0];
				Type[] from = Type.getArgumentTypes(vanillaDesc), to = Type.getArgumentTypes(mergedDesc);
				if (vanillaDesc.equals(mergedDesc) || from.length != to.length
						|| !Type.getReturnType(vanillaDesc).equals(Type.getReturnType(mergedDesc))) return null;
				String[] indices = right[1].split(",");
				if (indices.length != from.length) return null;
				int[] perm = new int[from.length];
				boolean[] taken = new boolean[to.length];
				for (int i = 0; i < perm.length; i++) {
					perm[i] = Integer.parseInt(indices[i]);
					if (perm[i] < 0 || perm[i] >= to.length || taken[perm[i]] || !to[perm[i]].equals(from[i])) return null;
					taken[perm[i]] = true;
				}
				return new Permutation(left.substring(0, hash), left.substring(hash + 1, paren), vanillaDesc, mergedDesc, perm);
			} catch (RuntimeException malformed) {
				return null;
			}
		}
	}

	private static Map<String, Permutation> permutations() {
		Map<String, Permutation> rows = permutations;
		if (rows != null) return rows;
		List<String> lines = new ArrayList<>();
		try (java.io.InputStream in = MixinHandlerShim.class.getResourceAsStream(TABLE)) {
			if (in != null) lines.addAll(List.of(new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8).split("\n")));
		} catch (java.io.IOException unreadable) {
			ForbricLog.warn("[Forbric/Mixin] could not read %s; no handler is wrapped on its evidence", TABLE);
		}
		permutations = index(lines);
		return permutations;
	}

	private static Map<String, Permutation> index(List<String> lines) {
		Map<String, Permutation> rows = new HashMap<>();
		for (String line : lines) {
			if (line.isBlank() || line.startsWith("#")) continue;
			Permutation row = Permutation.parse(line);
			if (row == null) {
				ForbricLog.warn("[Forbric/Mixin] %s: ignoring a row that is not one reordering: %s", TABLE, line.trim());
				continue;
			}
			rows.put(row.owner() + "#" + row.name(), row);
		}
		return Map.copyOf(rows);
	}

	/** Test seam: these rows instead of the shipped table; {@code null} goes back to the shipped one. */
	static void useTable(List<String> rows) {
		permutations = rows == null ? null : index(rows);
	}

	/**
	 * Builds the outer handler and turns {@code handler} into its inner one.
	 *
	 * <p>Mutates {@code handler}: it is renamed and loses the annotation, which the outer takes — with the selector
	 * rewritten to the live descriptor when the mod spelled vanilla's.
	 */
	private static MethodNode wrap(ClassNode mixin, MethodNode handler, Plan plan) {
		AnnotationNode inject = plan.inject();
		MethodNode target = plan.live();
		int[] mapping = plan.mapping();
		Type[] wanted = Type.getArgumentTypes(handler.desc);
		Type[] available = Type.getArgumentTypes(target.desc);
		boolean targetStatic = (target.access & Opcodes.ACC_STATIC) != 0;
		boolean handlerStatic = (handler.access & Opcodes.ACC_STATIC) != 0;
		if (plan.selector() != null) {
			for (int i = 0; i + 1 < inject.values.size(); i += 2) {
				if ("method".equals(inject.values.get(i))) inject.values.set(i + 1, new ArrayList<>(List.of(plan.selector())));
			}
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

	/**
	 * The one method named {@code name} across the targets, or null when there is not exactly one; its class's
	 * internal name goes into {@code owner[0]}.
	 */
	private static MethodNode onlyTarget(String name, List<String> targetNames, Function<String, ClassNode> targets,
			String[] owner) {
		MethodNode only = null;
		for (String targetName : targetNames) {
			ClassNode target = targets.apply(targetName);
			if (target == null || target.methods == null) continue;
			for (MethodNode method : target.methods) {
				if (!method.name.equals(name)) continue;
				if (only != null) return null;
				only = method;
				owner[0] = targetName;
			}
		}
		return only;
	}

	/**
	 * The selector when the annotation carries exactly one: a bare name, or {@code name(descriptor)} — the second
	 * form only ever meets the census, which is the one source that knows which descriptor a mod could have spelled.
	 */
	private static String onlySelector(AnnotationNode inject) {
		if (inject.values == null) return null;
		for (int i = 0; i + 1 < inject.values.size(); i += 2) {
			if (!"method".equals(inject.values.get(i)) || !(inject.values.get(i + 1) instanceof List<?> raw)) continue;
			if (raw.size() != 1 || !(raw.get(0) instanceof String selector)) return null;
			String s = selector.trim();
			int paren = s.indexOf('('), semi = s.indexOf(';');
			if (s.isEmpty() || semi >= 0 && (paren < 0 || semi < paren)) return null;   // an owner-qualified selector
			if (s.indexOf('*') >= 0 || s.startsWith("/") || s.indexOf(' ') >= 0 || s.indexOf('=') >= 0) return null;
			return s;
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
