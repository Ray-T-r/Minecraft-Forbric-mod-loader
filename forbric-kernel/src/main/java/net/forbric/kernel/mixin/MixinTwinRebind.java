/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.mixin;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

import net.forbric.api.Ecosystem;
import net.forbric.kernel.util.ForbricLog;

/**
 * Moves a mod's injector off vanilla's signature of a method, which the merged base keeps but nothing in the merged game
 * calls, onto the overload the carrier added in its place — the one the merged game calls where vanilla called vanilla's —
 * and hands the handler the arguments it was written for.
 *
 * <p>NeoForge gave {@code ModelBlockRenderer.shouldRenderFace} the block's own position, for its face-hiding hooks:
 * {@code (level, pos, state, direction, neighborPos)}. MinecraftForge kept vanilla's {@code (level, state, direction,
 * neighborPos)}, so the merged class has both: NeoForge's first, the one the merged tesselate bodies call, and vanilla's
 * after it, private and called by nothing. LiquidBounce's X-Ray wraps the face test with a {@code @ModifyReturnValue}
 * selected by name that takes vanilla's four arguments after the result. Mixin binds the name to the first overload,
 * rejects the handler there and fails the whole mixin, and X-Ray's block filter, full-bright and transparency go with
 * it. A selector spelling vanilla's descriptor would bind, and never run.
 *
 * <p>Kept exactly:
 * <ul>
 *   <li>only along a row of {@code carrier-twins.txt}, which CarrierTwinCensusTest derives from the staged jars: vanilla's
 *       method is in {@code uncalled-methods.txt} for the mod's ecosystem and is no carrier stub
 *       ({@link MixinStubRebind}'s); the class declares exactly one other method of that name, with the same return type
 *       and static-ness, whose parameters include each of vanilla's, matched one to one by local variable name and type;
 *       and every method the ecosystem's own game calls vanilla's from calls that overload in the merged base;</li>
 *   <li>only while vanilla's method is still uncalled ({@link MergedBaseUncalledMethods}: no installed mod references it
 *       and nothing in its class calls it);</li>
 *   <li>one selector naming the method alone or with vanilla's descriptor, and binding vanilla's method or the overload
 *       (never a third); no slice, no other Mixin or MixinExtras annotation on the handler ({@code @Group}, an
 *       expression's {@code @Definition}) or its parameters ({@code @Local}, {@code @Coerce});</li>
 *   <li>an {@code @Inject} taking vanilla's arguments or none, then its callback, capturing no locals; or an
 *       {@code @At}-driven kind whose own contract can be told ({@link MixinStubRebind#intrinsicArity}), then a prefix of
 *       vanilla's arguments. Never a {@code @ModifyVariable}: the overload's locals are not vanilla's;</li>
 *   <li>every {@code @At} a {@code HEAD} or {@code TAIL}; a {@code RETURN}, held as often by both bodies when an ordinal or
 *       an {@code allow} counts them; or an {@code INVOKE}/{@code FIELD} point the overload holds exactly as often as
 *       vanilla's method, so an ordinal means what the mod wrote.</li>
 * </ul>
 * A handler taking arguments in other places than the overload has them is wrapped: the outer handler takes the
 * overload's arguments and carries the annotation, and hands the original vanilla's, read off the row.
 * {@code -Dforbric.mixinTwinRebind=off} leaves every selector as compiled.
 */
public final class MixinTwinRebind {
	public static final String PROPERTY = "forbric.mixinTwinRebind";
	static final String TABLE = "/net/forbric/kernel/mixin/carrier-twins.txt";
	private static final String INJECT = "Lorg/spongepowered/asm/mixin/injection/Inject;";
	private static final String MODIFY_VARIABLE = "Lorg/spongepowered/asm/mixin/injection/ModifyVariable;";
	private static final String CALLBACK_INFO = "Lorg/spongepowered/asm/mixin/injection/callback/CallbackInfo;";
	private static final String CALLBACK_INFO_RETURNABLE = "Lorg/spongepowered/asm/mixin/injection/callback/CallbackInfoReturnable;";

	/**
	 * @param owner      the class (internal name)
	 * @param name       the method's name
	 * @param vanilla    vanilla's descriptor, which the merged game no longer calls
	 * @param overload   the descriptor of the carrier's overload it calls instead
	 * @param positions  for each of vanilla's parameters, the overload's parameter carrying it
	 * @param ecosystems the mods compiled against vanilla's signature as the method that runs
	 */
	record Row(String owner, String name, String vanilla, String overload, int[] positions, Set<Ecosystem> ecosystems) {
		/** {@code owner#name(vanilla) -> (overload) | 0,2,3 | FABRIC,FORGE}, as the census writes it; null when not that. */
		static Row parse(String line) {
			String[] columns = line.split(" \\| ");
			if (columns.length != 3) return null;
			String[] sides = columns[0].split(" -> ");
			if (sides.length != 2) return null;
			int hash = sides[0].indexOf('#'), paren = sides[0].indexOf('(', Math.max(hash, 0));
			if (hash <= 0 || paren < 0 || !sides[1].trim().startsWith("(")) return null;
			try {
				String vanilla = sides[0].substring(paren).trim(), overload = sides[1].trim();
				String[] numbers = columns[1].trim().split(",");
				int[] positions = new int[numbers.length];
				for (int i = 0; i < numbers.length; i++) positions[i] = Integer.parseInt(numbers[i].trim());
				Type[] from = Type.getArgumentTypes(vanilla), to = Type.getArgumentTypes(overload);
				if (positions.length != from.length) return null;
				for (int i = 0; i < positions.length; i++) {
					if (positions[i] < 0 || positions[i] >= to.length || !to[positions[i]].equals(from[i])) return null;
				}
				Set<Ecosystem> ecosystems = EnumSet.noneOf(Ecosystem.class);
				for (String e : columns[2].trim().split(",")) ecosystems.add(Ecosystem.valueOf(e.trim()));
				return new Row(sides[0].substring(0, hash).trim(), sides[0].substring(hash + 1, paren).trim(), vanilla,
						overload, positions, Set.copyOf(ecosystems));
			} catch (IllegalArgumentException malformed) {
				return null;
			}
		}
	}

	/** {@code owner#name} → the rows of that method (one per vanilla descriptor). */
	private static volatile Map<String, List<Row>> rows;

	private MixinTwinRebind() {
	}

	static boolean enabled() {
		return !"off".equalsIgnoreCase(System.getProperty(PROPERTY, "on"));
	}

	static Map<String, List<Row>> rows() {
		Map<String, List<Row>> loaded = rows;
		if (loaded != null) return loaded;
		Map<String, List<Row>> read = new HashMap<>();
		try (InputStream in = MixinTwinRebind.class.getResourceAsStream(TABLE)) {
			if (in != null) {
				for (String line : new String(in.readAllBytes(), StandardCharsets.UTF_8).split("\n")) {
					if (line.isBlank() || line.startsWith("#")) continue;
					Row row = Row.parse(line.trim());
					if (row != null) read.computeIfAbsent(row.owner() + "#" + row.name(), k -> new ArrayList<>()).add(row);
				}
			}
		} catch (IOException unreadable) {
			ForbricLog.warn("[Forbric/Mixin] could not read %s; no injector moves off vanilla's uncalled signatures", TABLE);
		}
		Map<String, List<Row>> frozen = new HashMap<>();
		read.forEach((key, list) -> frozen.put(key, List.copyOf(list)));
		rows = loaded = Map.copyOf(frozen);
		return loaded;
	}

	private static volatile Set<String> owners;

	/** Whether any row is headed by a method of {@code owner}: most classes head none. */
	static boolean owns(String owner) {
		Set<String> loaded = owners;
		if (loaded == null) {
			Set<String> collected = new HashSet<>();
			for (String key : rows().keySet()) collected.add(key.substring(0, key.indexOf('#')));
			owners = loaded = Set.copyOf(collected);
		}
		return owner != null && loaded.contains(owner);
	}

	/**
	 * Whether a single selector of a mixin on {@code owner} names a method that heads a row — the cheap question asked of
	 * every injector before the plan reads the target's code.
	 */
	static boolean mayMove(String owner, String selector) {
		String[] split = split(selector);
		return split != null && owns(owner) && rows().containsKey(owner + "#" + split[0]);
	}

	/** Moves every injector of {@code mixin} that a row moves; returns how many. {@code targets} must return nodes WITH code. */
	public static int adapt(ClassNode mixin, Function<String, ClassNode> targets) {
		if (!enabled() || mixin == null || mixin.methods == null || targets == null) return 0;
		Ecosystem ecosystem = MixinStubRebind.ecosystemOf(mixin.name);
		List<String> owners = MixinOverloadPin.targetsOf(mixin);
		if (ecosystem == null || owners.size() != 1 || !owns(owners.getFirst())) return 0;
		ClassNode target = targets.apply(owners.getFirst());
		if (target == null || target.methods == null) return 0;
		int moved = 0;
		for (MethodNode handler : List.copyOf(mixin.methods)) {
			Plan plan = plan(mixin, handler, target, ecosystem);
			if (plan == null) continue;
			MethodNode outer = move(mixin, handler, target, plan);
			if (outer != handler) mixin.methods.add(outer);
			moved++;
		}
		return moved;
	}

	/**
	 * The overload {@link #adapt} moves {@code handler}'s injector to, or null when it moves nothing: MixinFit asks this so
	 * its verdict and the move cannot disagree. {@code target} must carry code.
	 */
	public static MethodNode destination(ClassNode mixin, MethodNode handler, ClassNode target) {
		if (!enabled() || mixin == null || handler == null || target == null || target.methods == null) return null;
		Ecosystem ecosystem = MixinStubRebind.ecosystemOf(mixin.name);
		if (ecosystem == null || MixinOverloadPin.targetsOf(mixin).size() != 1) return null;
		Plan plan = plan(mixin, handler, target, ecosystem);
		return plan == null ? null : plan.overload();
	}

	/**
	 * One move: the injector, its row, the two methods, how many leading handler parameters are the injector's own
	 * contract, how many of vanilla's arguments it captures after them (for an {@code @Inject}, before its callback), and
	 * whether the handler needs an outer one shaped for the overload.
	 */
	record Plan(AnnotationNode injector, Row row, MethodNode vanilla, MethodNode overload, int own, int captured,
			boolean inject, boolean wrap) {
	}

	static Plan plan(ClassNode mixin, MethodNode handler, ClassNode target, Ecosystem ecosystem) {
		if (handler.name.endsWith(MixinHandlerShim.INNER_SUFFIX)) return null;
		AnnotationNode injector = MixinFit.injectorOf(handler);
		if (injector == null || MODIFY_VARIABLE.equals(injector.desc)) return null;
		boolean inject = INJECT.equals(injector.desc);
		if (!inject && !MixinRetarget.AT_DRIVEN.contains(injector.desc)) return null;
		if (otherInjectionAnnotation(handler, injector) || sugar(handler) || MixinFit.value(injector, "slice") != null
				|| MixinFit.value(injector, "locals") != null || MixinFit.value(injector, "target") != null) return null;
		List<String> selectors = MixinFit.stringList(MixinFit.value(injector, "method"));
		if (selectors.size() != 1) return null;
		String[] split = split(selectors.getFirst());
		if (split == null) return null;
		Row row = row(target.name, split[0], split[1]);
		if (row == null || !row.ecosystems().contains(ecosystem)) return null;
		MethodNode vanilla = declared(target, row.name(), row.vanilla());
		MethodNode overload = declared(target, row.name(), row.overload());
		if (vanilla == null || overload == null || empty(vanilla) || empty(overload)) return null;
		if (MergedBaseUncalledMethods.neverRuns(target, vanilla, ecosystem) == null) return null;
		MethodNode bound = MixinStubRebind.bound(target, selectors.getFirst());
		if (bound != vanilla && bound != overload) return null;

		Type[] params = Type.getArgumentTypes(handler.desc);
		Type[] vanillaArgs = Type.getArgumentTypes(row.vanilla());
		int own, captured;
		if (inject) {
			int callback = -1;
			for (int i = 0; i < params.length && callback < 0; i++) {
				String d = params[i].getDescriptor();
				if (CALLBACK_INFO.equals(d) || CALLBACK_INFO_RETURNABLE.equals(d)) callback = i;
			}
			if (callback < 0 || callback != params.length - 1) return null;   // no callback, or locals after it
			if (callback != 0 && (callback != vanillaArgs.length
					|| !Arrays.equals(Arrays.copyOf(params, callback), vanillaArgs))) return null;
			own = 0;
			captured = callback;
		} else {
			own = MixinStubRebind.intrinsicArity(injector, params, params.length, overload);
			if (own < 0 || own > params.length) return null;
			captured = params.length - own;
			if (captured > vanillaArgs.length) return null;
			for (int i = 0; i < captured; i++) if (!params[own + i].equals(vanillaArgs[i])) return null;
		}
		if (!anchorsHold(injector, vanilla, overload)) return null;

		boolean identity = true;
		for (int i = 0; i < captured; i++) identity &= row.positions()[i] == i;
		// An @Inject takes all of the method's arguments or none, and the overload has more than vanilla's.
		boolean wrap = captured > 0 && (inject || !identity);
		// Bound to the overload already and taking what it is handed there: Mixin injects where the game runs.
		if (bound == overload && !wrap) return null;
		if (wrap && calledWithin(mixin, handler)) return null;   // the original is renamed; a caller of it would break
		return new Plan(injector, row, vanilla, overload, own, captured, inject, wrap);
	}

	/** The row for {@code name} of {@code owner}: the one vanilla descriptor {@code desc} heads, or for a bare name the only one. */
	private static Row row(String owner, String name, String desc) {
		List<Row> candidates = rows().get(owner + "#" + name);
		if (candidates == null) return null;
		if (desc == null) return candidates.size() == 1 ? candidates.getFirst() : null;
		for (Row row : candidates) if (row.vanilla().equals(desc)) return row;
		return null;
	}

	/**
	 * Whether every {@code @At} means the same in the overload as in vanilla's method: an edge point; a {@code RETURN},
	 * counted alike where an ordinal or {@code allow} counts it; an {@code INVOKE}/{@code FIELD} point held as often.
	 */
	private static boolean anchorsHold(AnnotationNode injector, MethodNode vanilla, MethodNode overload) {
		List<AnnotationNode> points = MixinFit.atNodes(injector);
		if (points.isEmpty()) return false;
		boolean allow = MixinFit.value(injector, "allow") != null;
		for (AnnotationNode at : points) {
			String value = MixinFit.asString(MixinFit.value(at, "value"));
			if (value == null || MixinFit.value(at, "slice") != null || MixinFit.value(at, "args") != null) return false;
			Object shift = MixinFit.value(at, "shift");
			if (shift != null && !(shift instanceof String[] e && Set.of("NONE", "BEFORE", "AFTER").contains(e[1]))) return false;
			switch (value) {
				case "HEAD", "TAIL" -> {
				}
				case "RETURN" -> {
					if ((allow || MixinFit.value(at, "ordinal") != null) && returns(vanilla) != returns(overload)) return false;
				}
				case "INVOKE", "INVOKE_ASSIGN", "FIELD" -> {
					String member = MixinFit.asString(MixinFit.value(at, "target"));
					if (member == null) return false;
					int inVanilla = count(vanilla, !"FIELD".equals(value), member);
					if (inVanilla <= 0 || inVanilla != count(overload, !"FIELD".equals(value), member)) return false;
				}
				default -> {
					return false;
				}
			}
		}
		return true;
	}

	/** How often {@code body} calls (or accesses, for a field) {@code member}; -1 when the member cannot be read. */
	private static int count(MethodNode body, boolean call, String member) {
		MixinFit.Member want = MixinFit.parseMember(member);
		if (want == null || want.name() == null) return -1;
		int found = 0;
		for (AbstractInsnNode insn : body.instructions) {
			String owner, name, desc;
			if (call && insn instanceof MethodInsnNode invoke) {
				owner = invoke.owner; name = invoke.name; desc = invoke.desc;
			} else if (!call && insn instanceof FieldInsnNode field) {
				owner = field.owner; name = field.name; desc = field.desc;
			} else {
				continue;
			}
			if (name.equals(want.name()) && (want.owner() == null || owner.equals(want.owner()))
					&& (want.desc() == null || desc.equals(want.desc()))) found++;
		}
		return found;
	}

	private static int returns(MethodNode body) {
		int found = 0;
		for (AbstractInsnNode insn : body.instructions) {
			if (insn.getOpcode() >= Opcodes.IRETURN && insn.getOpcode() <= Opcodes.RETURN) found++;
		}
		return found;
	}

	/** The handler to carry the injector after {@code plan}'s move: the same one, or a new outer one. */
	private static MethodNode move(ClassNode mixin, MethodNode handler, ClassNode target, Plan plan) {
		MethodNode overload = plan.overload();
		String name = handler.name;   // before the shim renames the original
		MethodNode carrier = plan.wrap() ? shim(mixin, handler, plan) : handler;
		AnnotationNode injector = plan.injector();
		for (int i = 0; i + 1 < injector.values.size(); i += 2) {
			if ("method".equals(injector.values.get(i))) injector.values.set(i + 1, new ArrayList<>(List.of(overload.name + overload.desc)));
		}
		ForbricLog.info("[Forbric/Mixin] %s: %s now targets %s.%s%s — the merged game calls it where vanilla called %s%s, "
				+ "which the merge keeps but nothing calls%s", mixin.name.replace('/', '.'), name,
				target.name.replace('/', '.'), overload.name, overload.desc, plan.vanilla().name, plan.vanilla().desc,
				plan.wrap() ? "; the handler still receives vanilla's arguments" : "");
		return carrier;
	}

	/**
	 * The outer handler: the injector's own parameters, then the overload's arguments (all of them for an {@code @Inject},
	 * followed by its callback; for the other kinds as many as reach the last one the handler captures), handing the
	 * original its own parameters and vanilla's arguments where the row says they are.
	 */
	private static MethodNode shim(ClassNode mixin, MethodNode handler, Plan plan) {
		boolean handlerStatic = (handler.access & Opcodes.ACC_STATIC) != 0;
		Type[] params = Type.getArgumentTypes(handler.desc);
		Type[] overloadArgs = Type.getArgumentTypes(plan.overload().desc);
		int[] positions = plan.row().positions();
		int own = plan.own(), captured = plan.captured();
		int reach = overloadArgs.length;
		if (!plan.inject()) {
			reach = 0;
			for (int i = 0; i < captured; i++) reach = Math.max(reach, positions[i] + 1);
		}
		List<Type> outerParams = new ArrayList<>(Arrays.asList(params).subList(0, own));
		int firstArg = outerParams.size();
		outerParams.addAll(Arrays.asList(overloadArgs).subList(0, reach));
		if (plan.inject()) outerParams.add(params[params.length - 1]);   // the callback
		MethodNode outer = new MethodNode(Opcodes.ASM9, handler.access, handler.name,
				Type.getMethodDescriptor(Type.getReturnType(handler.desc), outerParams.toArray(Type[]::new)), null, null);
		outer.visibleAnnotations = handler.visibleAnnotations == null ? null : new ArrayList<>(handler.visibleAnnotations);
		outer.invisibleAnnotations = handler.invisibleAnnotations == null ? null : new ArrayList<>(handler.invisibleAnnotations);
		int[] slots = new int[outerParams.size()];
		int slot = handlerStatic ? 0 : 1;
		for (int i = 0; i < outerParams.size(); i++) { slots[i] = slot; slot += outerParams.get(i).getSize(); }
		if (!handlerStatic) outer.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
		for (int i = 0; i < own; i++) outer.instructions.add(new VarInsnNode(params[i].getOpcode(Opcodes.ILOAD), slots[i]));
		for (int i = 0; i < captured; i++) {
			int from = firstArg + positions[i];
			outer.instructions.add(new VarInsnNode(outerParams.get(from).getOpcode(Opcodes.ILOAD), slots[from]));
		}
		if (plan.inject()) outer.instructions.add(new VarInsnNode(Opcodes.ALOAD, slots[outerParams.size() - 1]));
		outer.instructions.add(MixinHandlerShim.callOwn(mixin, handlerStatic, handler.name + MixinHandlerShim.INNER_SUFFIX,
				handler.desc));
		outer.instructions.add(new InsnNode(Type.getReturnType(handler.desc).getOpcode(Opcodes.IRETURN)));
		outer.maxLocals = slot;
		outer.maxStack = slot + 2;
		handler.name = handler.name + MixinHandlerShim.INNER_SUFFIX;
		handler.visibleAnnotations = without(handler.visibleAnnotations, plan.injector().desc);
		handler.invisibleAnnotations = without(handler.invisibleAnnotations, plan.injector().desc);
		return outer;
	}

	private static List<AnnotationNode> without(List<AnnotationNode> annotations, String desc) {
		if (annotations == null) return null;
		List<AnnotationNode> kept = new ArrayList<>();
		for (AnnotationNode a : annotations) if (!a.desc.equals(desc)) kept.add(a);
		return kept.isEmpty() ? null : kept;
	}

	/** {@code {name, descriptor or null}} of a selector naming a method alone or with its descriptor; null for any other form. */
	private static String[] split(String selector) {
		String s = selector.trim();
		if (s.indexOf('*') >= 0 || s.startsWith("/") || s.indexOf(' ') >= 0 || s.indexOf('=') >= 0) return null;
		int semi = s.indexOf(';');
		if (s.startsWith("L") && semi > 0 && (s.indexOf('(') < 0 || semi < s.indexOf('('))) s = s.substring(semi + 1);
		int paren = s.indexOf('(');
		String name = paren < 0 ? s : s.substring(0, paren);
		if (name.isEmpty() || name.indexOf(':') >= 0) return null;
		return new String[] {name, paren < 0 ? null : s.substring(paren)};
	}

	private static MethodNode declared(ClassNode target, String name, String desc) {
		for (MethodNode m : target.methods) if (m.name.equals(name) && m.desc.equals(desc)) return m;
		return null;
	}

	private static boolean empty(MethodNode method) {
		return method.instructions == null || method.instructions.size() == 0;
	}

	/**
	 * Whether the handler carries a Mixin or MixinExtras annotation besides its injector — a {@code @Group}, an expression's
	 * {@code @Definition}: part of the injection, which would have to move with it.
	 */
	private static boolean otherInjectionAnnotation(MethodNode handler, AnnotationNode injector) {
		for (List<AnnotationNode> list : Arrays.asList(handler.visibleAnnotations, handler.invisibleAnnotations)) {
			if (list != null) for (AnnotationNode a : list) if (a != injector && injection(a)) return true;
		}
		return false;
	}

	/** Whether a parameter carries a Mixin or MixinExtras annotation (a {@code @Local}, a {@code @Coerce}); nullability does not count. */
	private static boolean sugar(MethodNode handler) {
		for (List<AnnotationNode>[] lists : Arrays.asList(handler.visibleParameterAnnotations, handler.invisibleParameterAnnotations)) {
			if (lists == null) continue;
			for (List<AnnotationNode> list : lists) if (list != null) for (AnnotationNode a : list) if (injection(a)) return true;
		}
		return false;
	}

	private static boolean injection(AnnotationNode annotation) {
		return annotation.desc.startsWith("Lorg/spongepowered/asm/mixin/") || annotation.desc.startsWith("Lcom/llamalad7/mixinextras/");
	}

	/** Whether another method of the mixin calls {@code handler} by name and descriptor. */
	private static boolean calledWithin(ClassNode mixin, MethodNode handler) {
		for (MethodNode m : mixin.methods) {
			if (m == handler || m.instructions == null) continue;
			for (AbstractInsnNode insn : m.instructions) {
				if (insn instanceof MethodInsnNode call && call.owner.equals(mixin.name) && call.name.equals(handler.name)
						&& call.desc.equals(handler.desc)) return true;
			}
		}
		return false;
	}
}
