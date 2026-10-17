/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.mixin;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.function.Function;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.FrameNode;
import org.objectweb.asm.tree.IincInsnNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.LineNumberNode;
import org.objectweb.asm.tree.LookupSwitchInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.MultiANewArrayInsnNode;
import org.objectweb.asm.tree.TableSwitchInsnNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.AnalyzerException;
import org.objectweb.asm.tree.analysis.BasicVerifier;

import net.forbric.api.Ecosystem;
import net.forbric.kernel.util.ForbricLog;

/**
 * A redirect of a vanilla call the merged method no longer makes, moved onto the carrier's call that makes it for it.
 *
 * <p>A carrier routes some vanilla calls through a method of its own: vanilla's {@code soundBuffers.getStream(path, looping)}
 * becomes {@code sound.getStream(soundBuffers, sound, looping)}, whose inherited implementation (an interface default, a
 * superclass's method) makes the vanilla call — so that a carrier mod can override the method and stream from elsewhere. A
 * mod's {@code @Redirect} of the vanilla call then binds nowhere, although for every receiver that does not override the
 * carrier's method the vanilla call is still made, once, through it.
 *
 * <p>This moves the redirect onto the carrier's call. The new handler, of the carrier's call's shape, asks the runtime
 * ({@code KernelDispatch}) whether the receiver still inherits the implementation that leads to the vanilla call: if it does,
 * it runs that implementation inline — the very instructions, down every level whose own inherited implementation leads on —
 * with the mod's handler, as written, in place of the vanilla call; if the receiver overrides it (a carrier mod's block,
 * sound, item), or is null, it makes the carrier's call untouched, as the game would without the mod.
 *
 * <p>A constant is treated the same way: vanilla's {@code goDownInWater} pushes its sinking step itself; NeoForge calls
 * {@code sinkInFluid}, whose default pushes the same constant (and scales it by its own swim-speed attribute). A
 * {@code @ModifyExpressionValue} of that {@code CONSTANT}, or a {@code @ModifyConstant} of it, becomes a redirect of the
 * carrier's call that runs the default with the mod's modifier handed the constant where the default pushes it.
 *
 * <p>It moves only where all of this is read off the classes, never off a name:
 * <ul>
 *   <li>the redirect has one {@code INVOKE} point (the modifier one constant, nothing else said about it), no slice and no
 *       {@code @Group}; its handler takes the vanilla call's values (or the constant) and, after them, only arguments of the
 *       method it targets, with no parameter annotations;</li>
 *   <li>the native method (the mod's loader's) makes the vanilla call once (or the occurrence the ordinal names), or pushes
 *       the constant once, and the merged method never does;</li>
 *   <li>the merged method makes exactly one virtual call the native method never makes whose selected implementation leads
 *       to the vanilla call: makes it exactly once, or makes exactly one call that leads to it, at most four levels down,
 *       with no loop, handler, lambda or monitor on the way (so it is made at most once per call);</li>
 *   <li>everything else those implementations touch is public, or private to the class that declares them (then inlined
 *       too), so the inlined code means the same in the mixin's target;</li>
 *   <li>the first implementation is not the target class's own: a helper the carrier added to the target class is
 *       {@link NativeCallInlining}'s, which inlines it at its call in the class itself.</li>
 * </ul>
 * The mod's handler keeps its name with {@code $forbricOriginal} appended. {@code -Dforbric.carrierDefaultChains=off} leaves
 * every redirect as compiled.
 *
 * <p>What this does not see: the implementations are inlined as the merged base has them, so another mod's mixin applied to
 * one of them (an injector into the interface default itself) does not run on the path the moved redirect takes. A receiver
 * that overrides the carrier's method never sees the mod's handler — the carrier's override decides there, as without the
 * mod; natively no such receiver exists.
 */
public final class CarrierDefaultChains {
	public static final String PROPERTY = "forbric.carrierDefaultChains";
	static final String DISPATCH = "net/forbric/kernel/runtime/KernelDispatch";
	private static final String INHERITS = "(Ljava/lang/Object;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)Z";
	private static final String REDIRECT = CarrierDecisions.REDIRECT;
	private static final int DEPTH = 4;
	private static final int SIZE = 160;

	private CarrierDefaultChains() {
	}

	static boolean enabled() {
		return !"off".equalsIgnoreCase(System.getProperty(PROPERTY, "on"));
	}

	/** A method's selected implementation: the class that declares it and the method there. */
	record Body(String declarer, boolean itf, MethodNode method) {
	}

	/**
	 * One level: the call into it, the implementation it selects (guarded: by virtual dispatch, asked at run time), and the
	 * instruction in that implementation the chain goes on at — the next level's call, or the vanilla call itself.
	 */
	record Link(MethodInsnNode call, Body body, boolean guarded, AbstractInsnNode site, Link next) {
	}

	/** Moves every eligible redirect of {@code mixin}; returns how many. {@code code} must return nodes WITH code. */
	public static int adapt(ClassNode mixin, Function<String, ClassNode> code, BiFunction<Ecosystem, String, ClassNode> natives) {
		if (!enabled() || mixin == null || mixin.methods == null) return 0;
		Ecosystem ecosystem = MixinStubRebind.ecosystemOf(mixin.name);
		if (ecosystem == null) return 0;
		Map<String, java.util.Optional<ClassNode>> read = new HashMap<>();
		Function<String, ClassNode> classes = name -> read.computeIfAbsent(name, n -> java.util.Optional.ofNullable(code.apply(n))).orElse(null);
		int moved = 0;
		for (String target : MixinFit.mixinTargets(mixin)) {
			ClassNode original = natives.apply(ecosystem, target), merged = null;
			if (original == null) continue;
			for (MethodNode handler : List.copyOf(mixin.methods)) {
				AnnotationNode injector = MixinFit.injectorOf(handler);
				if (injector == null || annotated(handler, CarrierDecisions.GROUP) || MixinFit.value(injector, "slice") != null) continue;
				Object constant = REDIRECT.equals(injector.desc) ? null : constantOf(injector);
				if (!REDIRECT.equals(injector.desc) && constant == null) continue;
				List<AnnotationNode> points = MixinFit.atNodes(injector);
				AnnotationNode at = points.size() == 1 ? points.getFirst() : null;
				if (constant == null && (at == null || !"INVOKE".equals(MixinFit.asString(MixinFit.value(at, "value"))))) continue;
				if (merged == null) merged = classes.apply(target);
				if (merged == null) break;
				MethodNode was = MixinTargetSelectors.one(handler, original), selected = MixinTargetSelectors.one(handler, merged);
				if (was == null || selected == null || !was.name.equals(selected.name) || !was.desc.equals(selected.desc)) continue;
				AbstractInsnNode vanilla;
				if (constant != null) {
					// A constant the native method pushes once and the merged method never.
					if (!pushes(selected, constant).isEmpty() || pushes(was, constant).size() != 1) continue;
					vanilla = pushes(was, constant).getFirst();
				} else {
					if (!MixinCallbackShape.calls(at, selected).isEmpty()) continue;
					List<MethodInsnNode> nativeCalls = MixinCallbackShape.calls(at, was);
					int ordinal = MixinFit.value(at, "ordinal") instanceof Number number ? number.intValue() : -1;
					MethodInsnNode call = ordinal >= 0 ? (ordinal < nativeCalls.size() ? nativeCalls.get(ordinal) : null)
							: nativeCalls.size() == 1 ? nativeCalls.getFirst() : null;
					if (call == null || call.getOpcode() == Opcodes.INVOKESPECIAL) continue;
					vanilla = call;
				}
				Link chain = standIn(was, selected, vanilla, classes);
				if (chain == null) continue;
				// A helper the carrier added to the target class itself is NativeCallInlining's (it inlines it at its call, in the
				// class, where every other mixin is checked); only an implementation of another class is inlined here.
				if (chain.body().declarer().equals(merged.name)) continue;
				MethodNode wrapper = relocate(mixin, handler, injector, at, vanilla, chain, selected, classes);
				if (wrapper == null) continue;
				CurrentBodyOrdinals.mark(wrapper);
				moved++;
				ForbricLog.info("[Forbric/Mixin] %s.%s: %s in %s; the carrier calls %s there, whose inherited implementation %s%s"
						+ " — the %s moves onto it and runs that implementation with the mod's handler wherever the receiver inherits it",
						mixin.name.replace('/', '.'), wrapper.name, vanilla instanceof MethodInsnNode call ? call.owner.replace('/', '.') + "." + call.name + " is no longer called"
								: "the constant " + constant + " is no longer pushed", target.replace('/', '.') + "." + selected.name,
						chain.call().owner.replace('/', '.') + "." + chain.call().name, vanilla instanceof MethodInsnNode ? "makes that call" : "pushes it", path(chain),
						vanilla instanceof MethodInsnNode ? "redirect" : "constant's modifier");
			}
		}
		return moved;
	}

	private static String path(Link chain) {
		StringBuilder out = new StringBuilder();
		for (Link link = chain.next(); link != null; link = link.next()) out.append(out.isEmpty() ? " (through " : ", ").append(link.call().owner.replace('/', '.')).append('.').append(link.call().name);
		return out.isEmpty() ? "" : out.append(')').toString();
	}

	// ---------------------------------------------------------------------------------------------------------------
	// The chain

	/** The one virtual call of {@code selected} the native method never makes whose implementation leads to {@code call}. */
	static Link standIn(MethodNode was, MethodNode selected, AbstractInsnNode call, Function<String, ClassNode> code) {
		Set<String> nativeMembers = new HashSet<>();
		for (AbstractInsnNode instruction : was.instructions) if (instruction instanceof MethodInsnNode other) nativeMembers.add(other.name + other.desc);
		Map<String, Integer> occurrences = new HashMap<>();
		for (AbstractInsnNode instruction : selected.instructions) if (instruction instanceof MethodInsnNode other) occurrences.merge(member(other), 1, Integer::sum);
		Search search = new Search(call, code);
		Link found = null;
		for (AbstractInsnNode instruction : selected.instructions) {
			if (!(instruction instanceof MethodInsnNode candidate) || candidate.getOpcode() != Opcodes.INVOKEVIRTUAL && candidate.getOpcode() != Opcodes.INVOKEINTERFACE
					|| nativeMembers.contains(candidate.name + candidate.desc)) continue;
			Link link = search.link(candidate, true, 0);
			if (search.exhausted) return null;
			if (link == null) continue;
			if (found != null || occurrences.get(member(candidate)) != 1) return null;
			found = link;
		}
		return found;
	}

	/** Finds the levels that lead to one vanilla call, each implementation read once, within a budget. */
	static final class Search {
		private final AbstractInsnNode call;
		private final Function<String, ClassNode> code;
		private final Map<String, Link> known = new HashMap<>();
		private final Set<String> failed = new HashSet<>();
		private int budget = 400;
		boolean exhausted;

		Search(AbstractInsnNode call, Function<String, ClassNode> code) {
			this.call = call;
			this.code = code;
		}

		/**
		 * The level {@code into} opens, when its implementation leads to the vanilla call exactly once: makes it once, or makes
		 * exactly one call that does, and no other call that does; null otherwise.
		 */
		Link link(MethodInsnNode into, boolean guarded, int depth) {
			if (depth > DEPTH) return null;
			String key = member(into) + into.getOpcode() + guarded;
			if (failed.contains(key)) return null;
			Link memo = known.get(key);
			if (memo != null) return new Link(into, memo.body(), guarded, memo.site(), memo.next());
			if (--budget < 0) {
				exhausted = true;
				return null;
			}
			Link found = find(into, guarded, depth);
			if (found == null) failed.add(key);
			else known.put(key, found);
			return found;
		}

		private Link find(MethodInsnNode into, boolean guarded, int depth) {
			Body body = resolve(into, code);
			if (body == null || !simple(body.method())) return null;
			AbstractInsnNode site = null;
			Link next = null;
			int leads = 0;
			for (AbstractInsnNode instruction : body.method().instructions) {
				if (same(instruction, call)) {
					site = instruction;
					leads++;
					continue;
				}
				if (!(instruction instanceof MethodInsnNode made) || made.name.startsWith("<")) continue;
				boolean virtual = (made.getOpcode() == Opcodes.INVOKEVIRTUAL || made.getOpcode() == Opcodes.INVOKEINTERFACE) && !ownPrivate(made, body, code);
				Link deeper = link(made, virtual, depth + 1);
				if (exhausted) return null;
				if (deeper == null) continue;
				site = made;
				next = deeper;
				leads++;
			}
			return leads == 1 ? new Link(into, body, guarded, site, next) : null;
		}
	}

	/**
	 * The implementation {@code call} selects as its owner declares it: the owner's own method or its nearest superclass's,
	 * else the one maximally specific interface default. {@code INVOKESPECIAL} and private calls resolve the same way, from
	 * the owner they name.
	 */
	static Body resolve(MethodInsnNode call, Function<String, ClassNode> code) {
		Set<String> seen = new HashSet<>();
		// The platform's classes (Object at the top of every chain) declare nothing a game call resolves to here.
		for (String owner = call.owner; owner != null && !owner.startsWith("java/") && seen.add(owner);) {
			ClassNode type = code.apply(owner);
			if (type == null) return null;
			MethodNode own = declared(type, call.name, call.desc);
			if (own != null) return (own.access & Opcodes.ACC_ABSTRACT) != 0 && (type.access & Opcodes.ACC_INTERFACE) == 0 ? null
					: (own.access & Opcodes.ACC_ABSTRACT) != 0 ? defaultOf(call, code) : new Body(type.name, (type.access & Opcodes.ACC_INTERFACE) != 0, own);
			if ((type.access & Opcodes.ACC_INTERFACE) != 0) break;
			owner = type.superName;
		}
		return defaultOf(call, code);
	}

	/** The one maximally specific non-abstract interface default for {@code call}, over every interface its owner has. */
	private static Body defaultOf(MethodInsnNode call, Function<String, ClassNode> code) {
		Map<String, ClassNode> interfaces = new HashMap<>();
		List<String> pending = new ArrayList<>();
		Set<String> classes = new HashSet<>();
		for (String owner = call.owner; owner != null && !owner.startsWith("java/") && classes.add(owner);) {
			ClassNode type = code.apply(owner);
			if (type == null) return null;
			if ((type.access & Opcodes.ACC_INTERFACE) != 0) {
				pending.add(type.name);
				break;
			}
			pending.addAll(type.interfaces);
			owner = type.superName;
		}
		while (!pending.isEmpty()) {
			String name = pending.removeFirst();
			if (interfaces.containsKey(name) || name.startsWith("java/")) continue;
			ClassNode type = code.apply(name);
			if (type == null) return null;
			interfaces.put(name, type);
			pending.addAll(type.interfaces);
		}
		Set<String> candidates = new LinkedHashSet<>();
		for (ClassNode type : interfaces.values()) {
			MethodNode own = declared(type, call.name, call.desc);
			if (own != null && (own.access & (Opcodes.ACC_STATIC | Opcodes.ACC_PRIVATE)) == 0) candidates.add(type.name);
		}
		candidates.removeIf(candidate -> candidates.stream().anyMatch(other -> !other.equals(candidate) && extendsInterface(other, candidate, interfaces)));
		if (candidates.size() != 1) return null;
		ClassNode type = interfaces.get(candidates.iterator().next());
		MethodNode method = declared(type, call.name, call.desc);
		return (method.access & Opcodes.ACC_ABSTRACT) != 0 ? null : new Body(type.name, true, method);
	}

	private static boolean extendsInterface(String sub, String sup, Map<String, ClassNode> interfaces) {
		ClassNode type = interfaces.get(sub);
		if (type == null) return false;
		for (String parent : type.interfaces) if (parent.equals(sup) || extendsInterface(parent, sup, interfaces)) return true;
		return false;
	}

	/** Straight-line or forward-branching code only: no handler, loop, lambda, monitor or subroutine; small. */
	static boolean simple(MethodNode method) {
		if ((method.access & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE | Opcodes.ACC_SYNCHRONIZED)) != 0) return false;
		if (method.tryCatchBlocks != null && !method.tryCatchBlocks.isEmpty()) return false;
		int real = 0;
		InsnList instructions = method.instructions;
		for (AbstractInsnNode instruction : instructions) {
			if (instruction.getOpcode() < 0) continue;
			if (++real > SIZE) return false;
			int at = instructions.indexOf(instruction);
			switch (instruction) {
				case InvokeDynamicInsnNode dynamic -> { return false; }
				case JumpInsnNode jump -> {
					if (jump.getOpcode() == Opcodes.JSR || instructions.indexOf(jump.label) <= at) return false;
				}
				case TableSwitchInsnNode table -> {
					if (instructions.indexOf(table.dflt) <= at) return false;
					for (LabelNode label : table.labels) if (instructions.indexOf(label) <= at) return false;
				}
				case LookupSwitchInsnNode lookup -> {
					if (instructions.indexOf(lookup.dflt) <= at) return false;
					for (LabelNode label : lookup.labels) if (instructions.indexOf(label) <= at) return false;
				}
				default -> {
					int opcode = instruction.getOpcode();
					if (opcode == Opcodes.MONITORENTER || opcode == Opcodes.MONITOREXIT || opcode == Opcodes.RET) return false;
				}
			}
		}
		return true;
	}

	/** A call of a private method of the class that declares {@code body}: inlined, never dispatched. */
	private static boolean ownPrivate(MethodInsnNode made, Body body, Function<String, ClassNode> code) {
		if (!made.owner.equals(body.declarer())) return false;
		ClassNode owner = code.apply(made.owner);
		MethodNode target = owner == null ? null : declared(owner, made.name, made.desc);
		return target != null && (target.access & Opcodes.ACC_PRIVATE) != 0;
	}

	/** Whether {@code made}, inside an inlined implementation, is itself inlined: a private call or a non-virtual super call. */
	private static boolean inlined(MethodInsnNode made, Body body, Function<String, ClassNode> code) {
		return !made.name.startsWith("<") && (made.getOpcode() == Opcodes.INVOKESPECIAL || ownPrivate(made, body, code));
	}

	// ---------------------------------------------------------------------------------------------------------------
	// Access: the inlined code must mean the same in the mixin's target

	/**
	 * Whether every class and member the chain's implementations name (but those it inlines) is public, and so is every
	 * deeper level's own call, which the new handler makes for a receiver that overrides it.
	 */
	static boolean accessible(Link chain, Function<String, ClassNode> code) {
		for (Link link = chain; link != null; link = link.next()) {
			if (!accessible(link.body(), link.site(), code, 0)) return false;
			if (link != chain && link.guarded() && !publicMember(link.call().owner, link.call().name, link.call().desc, false, code)) return false;
		}
		return true;
	}

	private static boolean accessible(Body body, AbstractInsnNode site, Function<String, ClassNode> code, int depth) {
		if (depth > DEPTH) return false;
		for (AbstractInsnNode instruction : body.method().instructions) {
			if (instruction == site) continue;
			switch (instruction) {
				case MethodInsnNode made -> {
					if (inlined(made, body, code)) {
						Body helper = resolve(made, code);
						if (helper == null || !simple(helper.method()) || !accessible(helper, null, code, depth + 1)) return false;
					} else if (!publicMember(made.owner, made.name, made.desc, false, code)) return false;
				}
				case FieldInsnNode field -> {
					if (!publicMember(field.owner, field.name, field.desc, true, code)) return false;
				}
				case TypeInsnNode type -> {
					if (!publicType(type.desc, code)) return false;
				}
				case MultiANewArrayInsnNode array -> {
					if (!publicType(array.desc, code)) return false;
				}
				case LdcInsnNode ldc -> {
					if (ldc.cst instanceof Type type && !publicType(type.getSort() == Type.OBJECT ? type.getInternalName() : type.getDescriptor(), code)) return false;
				}
				default -> {
				}
			}
		}
		return true;
	}

	private static boolean publicType(String name, Function<String, ClassNode> code) {
		if (name.startsWith("[")) {
			Type element = Type.getType(name).getElementType();
			return element.getSort() != Type.OBJECT || publicType(element.getInternalName(), code);
		}
		if (name.startsWith("java/")) {
			try {
				return (Class.forName(name.replace('/', '.'), false, ClassLoader.getPlatformClassLoader()).getModifiers() & java.lang.reflect.Modifier.PUBLIC) != 0;
			} catch (ClassNotFoundException | LinkageError unknown) {
				return false;
			}
		}
		ClassNode type = code.apply(name);
		return type != null && (type.access & Opcodes.ACC_PUBLIC) != 0;
	}

	private static boolean publicMember(String owner, String name, String desc, boolean field, Function<String, ClassNode> code) {
		if (owner.startsWith("[")) return true;
		if (!publicType(owner, code)) return false;
		Set<String> seen = new HashSet<>();
		List<String> pending = new ArrayList<>(List.of(owner));
		while (!pending.isEmpty()) {
			String each = pending.removeFirst();
			if (!seen.add(each)) continue;
			// A member the game's classes do not declare may be the platform's (Object's own methods), read off the platform.
			if (each.startsWith("java/")) {
				if (platformPublic(each, name, desc, field)) return true;
				continue;
			}
			ClassNode type = code.apply(each);
			if (type == null) return false;
			if (field) {
				for (FieldNode declared : type.fields) if (declared.name.equals(name) && declared.desc.equals(desc)) return (declared.access & Opcodes.ACC_PUBLIC) != 0;
			} else {
				MethodNode declared = declared(type, name, desc);
				if (declared != null) return (declared.access & Opcodes.ACC_PUBLIC) != 0;
			}
			if (type.superName != null) pending.add(type.superName);
			pending.addAll(type.interfaces);
		}
		return false;
	}

	/** Whether the platform class {@code owner} has a public member {@code name}/{@code desc}, its own or inherited. */
	private static boolean platformPublic(String owner, String name, String desc, boolean field) {
		try {
			Class<?> type = Class.forName(owner.replace('/', '.'), false, ClassLoader.getPlatformClassLoader());
			if ((type.getModifiers() & java.lang.reflect.Modifier.PUBLIC) == 0) return false;
			if (field) {
				for (java.lang.reflect.Field each : type.getFields()) if (each.getName().equals(name) && Type.getDescriptor(each.getType()).equals(desc)) return true;
				return false;
			}
			for (java.lang.reflect.Method each : type.getMethods()) if (each.getName().equals(name) && Type.getMethodDescriptor(each).equals(desc)) return true;
			if (name.equals("<init>")) for (java.lang.reflect.Constructor<?> each : type.getConstructors()) if (Type.getConstructorDescriptor(each).equals(desc)) return true;
			return false;
		} catch (ClassNotFoundException | LinkageError unknown) {
			return false;
		}
	}

	// ---------------------------------------------------------------------------------------------------------------
	// The move

	private static MethodNode relocate(ClassNode mixin, MethodNode handler, AnnotationNode injector, AnnotationNode at, AbstractInsnNode vanilla, Link chain,
			MethodNode selected, Function<String, ClassNode> code) {
		if (!accessible(chain, code)) return null;
		for (List<AnnotationNode>[] table : Arrays.asList(handler.visibleParameterAnnotations, handler.invisibleParameterAnnotations))
			if (table != null) for (List<AnnotationNode> annotations : table) if (annotations != null && !annotations.isEmpty()) return null;
		boolean isStatic = (handler.access & Opcodes.ACC_STATIC) != 0;
		// What the handler is handed in place of the vanilla value: the call's operands, or the constant itself.
		List<Type> nativeOperands = vanilla instanceof MethodInsnNode call ? CarrierDecisions.operandTypes(call) : List.of(constantType(vanilla));
		List<Type> carrierOperands = CarrierDecisions.operandTypes(chain.call());
		Type[] parameters = Type.getArgumentTypes(handler.desc);
		Type answer = vanilla instanceof MethodInsnNode call ? Type.getReturnType(call.desc) : constantType(vanilla), carrierAnswer = Type.getReturnType(chain.call().desc);
		if (answer == null) return null;
		if (!Type.getReturnType(handler.desc).equals(answer) || parameters.length < nativeOperands.size()) return null;
		for (int i = 0; i < nativeOperands.size(); i++) if (!parameters[i].equals(nativeOperands.get(i))) return null;
		List<Type> captured = List.of(parameters).subList(nativeOperands.size(), parameters.length);
		Type[] targetArguments = Type.getArgumentTypes(selected.desc);
		if (captured.size() > targetArguments.length) return null;
		for (int i = 0; i < captured.size(); i++) if (!captured.get(i).equals(targetArguments[i])) return null;
		String name = handler.name;
		for (MethodNode other : mixin.methods) if (other.name.equals(name + CarrierDecisions.SUFFIX)) return null;

		List<Type> wrapperParameters = new ArrayList<>(carrierOperands);
		wrapperParameters.addAll(captured);
		MethodNode wrapper = new MethodNode(Opcodes.ACC_PRIVATE | (isStatic ? Opcodes.ACC_STATIC : 0), name,
				Type.getMethodDescriptor(carrierAnswer, wrapperParameters.toArray(Type[]::new)), null, null);
		int[] slots = new int[wrapperParameters.size()];
		int next = isStatic ? 0 : 1;
		for (int i = 0; i < slots.length; i++) {
			slots[i] = next;
			next += wrapperParameters.get(i).getSize();
		}
		String original = name + CarrierDecisions.SUFFIX;
		Emitter emitter = new Emitter(mixin, isStatic, original, handler.desc, code, next);
		for (int i = 0; i < carrierOperands.size(); i++) emitter.out.add(new VarInsnNode(carrierOperands.get(i).getOpcode(Opcodes.ILOAD), slots[i]));
		for (int i = 0; i < captured.size(); i++) emitter.captured.add(new VarInsnNode(captured.get(i).getOpcode(Opcodes.ILOAD), slots[carrierOperands.size() + i]));
		emitter.link(chain);
		emitter.out.add(new InsnNode(carrierAnswer.getOpcode(Opcodes.IRETURN)));
		wrapper.instructions = emitter.out;
		wrapper.maxLocals = emitter.next;
		wrapper.maxStack = emitter.stack;
		try {
			new Analyzer<>(new BasicVerifier()).analyze(mixin.name, wrapper);
		} catch (AnalyzerException | RuntimeException unverifiable) {
			return null;
		}
		if (!framed(mixin, wrapper, code)) return null;

		handler.name = original;
		boolean visible = handler.visibleAnnotations != null && handler.visibleAnnotations.remove(injector);
		if (!visible && handler.invisibleAnnotations != null) handler.invisibleAnnotations.remove(injector);
		AnnotationNode moved = injector;
		if (vanilla instanceof MethodInsnNode) {
			set(at, "target", member(chain.call()));
			remove(at, "ordinal");
		} else {
			// The constant's modifier becomes a redirect of the carrier's call, with the same selectors and require counts.
			moved = new AnnotationNode(REDIRECT);
			AnnotationNode point = new AnnotationNode("Lorg/spongepowered/asm/mixin/injection/At;");
			point.values = new ArrayList<>(List.of("value", "INVOKE", "target", member(chain.call())));
			moved.values = new ArrayList<>(List.of("method", MixinFit.value(injector, "method"), "at", point));
			for (String key : List.of("require", "expect", "allow")) if (MixinFit.value(injector, key) != null) set(moved, key, MixinFit.value(injector, key));
		}
		if (visible) wrapper.visibleAnnotations = new ArrayList<>(List.of(moved));
		else wrapper.invisibleAnnotations = new ArrayList<>(List.of(moved));
		mixin.methods.add(wrapper);
		return wrapper;
	}

	// ---------------------------------------------------------------------------------------------------------------
	// Constants

	static final String MODIFY_EXPRESSION_VALUE = "Lcom/llamalad7/mixinextras/injector/ModifyExpressionValue;";
	static final String MODIFY_CONSTANT = "Lorg/spongepowered/asm/mixin/injection/ModifyConstant;";

	/**
	 * The one constant a {@code @ModifyExpressionValue(at = @At(value = "CONSTANT", args = "<kind>Value=..."))} or a
	 * {@code @ModifyConstant(constant = @Constant(<kind>Value = ...))} modifies, with nothing else said about it (no
	 * ordinal, no zero-condition expansion); null for anything else.
	 */
	static Object constantOf(AnnotationNode injector) {
		if (MODIFY_EXPRESSION_VALUE.equals(injector.desc)) {
			List<AnnotationNode> points = MixinFit.atNodes(injector);
			if (points.size() != 1 || !"CONSTANT".equals(MixinFit.asString(MixinFit.value(points.getFirst(), "value")))) return null;
			AnnotationNode at = points.getFirst();
			for (String key : List.of("ordinal", "opcode", "shift", "by", "target")) if (MixinFit.value(at, key) != null) return null;
			List<String> args = MixinFit.stringList(MixinFit.value(at, "args"));
			if (args.size() != 1) return null;
			int equals = args.getFirst().indexOf('=');
			return equals < 0 ? null : parse(args.getFirst().substring(0, equals).strip(), args.getFirst().substring(equals + 1).strip());
		}
		if (MODIFY_CONSTANT.equals(injector.desc)) {
			Object constants = MixinFit.value(injector, "constant");
			List<?> list = constants instanceof List<?> l ? l : constants == null ? List.of() : List.of(constants);
			if (list.size() != 1 || !(list.getFirst() instanceof AnnotationNode constant) || constant.values == null || constant.values.size() != 2) return null;
			Object value = constant.values.get(1);
			return switch (String.valueOf(constant.values.getFirst())) {
				case "intValue", "floatValue", "longValue", "doubleValue", "stringValue", "classValue" -> value instanceof org.objectweb.asm.Type || value instanceof Number || value instanceof String ? value : null;
				default -> null;
			};
		}
		return null;
	}

	/** A CONSTANT point's {@code <kind>Value=text}, as Mixin reads it; null for a kind it does not have. */
	private static Object parse(String key, String text) {
		try {
			String plain = text.replaceAll("[dDfFlL]$", "");
			return switch (key) {
				case "intValue" -> Integer.parseInt(plain);
				case "floatValue" -> Float.parseFloat(plain);
				case "longValue" -> Long.parseLong(plain);
				case "doubleValue" -> Double.parseDouble(plain);
				case "stringValue" -> text;
				case "classValue" -> Type.getObjectType(text.replace('.', '/'));
				default -> null;
			};
		} catch (NumberFormatException unreadable) {
			return null;
		}
	}

	/** The instructions of {@code method} that push {@code constant}. */
	static List<AbstractInsnNode> pushes(MethodNode method, Object constant) {
		List<AbstractInsnNode> out = new ArrayList<>();
		for (AbstractInsnNode instruction : method.instructions) if (constant.equals(pushed(instruction))) out.add(instruction);
		return out;
	}

	/** The constant {@code instruction} pushes; null when it pushes none. */
	static Object pushed(AbstractInsnNode instruction) {
		int opcode = instruction.getOpcode();
		if (instruction instanceof LdcInsnNode ldc) return ldc.cst;
		if (opcode >= Opcodes.ICONST_M1 && opcode <= Opcodes.ICONST_5) return opcode - Opcodes.ICONST_0;
		if (opcode == Opcodes.LCONST_0 || opcode == Opcodes.LCONST_1) return (long) (opcode - Opcodes.LCONST_0);
		if (opcode >= Opcodes.FCONST_0 && opcode <= Opcodes.FCONST_2) return (float) (opcode - Opcodes.FCONST_0);
		if (opcode == Opcodes.DCONST_0 || opcode == Opcodes.DCONST_1) return (double) (opcode - Opcodes.DCONST_0);
		if ((opcode == Opcodes.BIPUSH || opcode == Opcodes.SIPUSH) && instruction instanceof org.objectweb.asm.tree.IntInsnNode number) return number.operand;
		return null;
	}

	private static Type constantType(AbstractInsnNode instruction) {
		return switch (pushed(instruction)) {
			case Integer ignored -> Type.INT_TYPE;
			case Long ignored -> Type.LONG_TYPE;
			case Float ignored -> Type.FLOAT_TYPE;
			case Double ignored -> Type.DOUBLE_TYPE;
			case String ignored -> Type.getType(String.class);
			case Type ignored -> Type.getType(Class.class);
			case null, default -> null;
		};
	}

	/** Whether {@code instruction} is the vanilla operation {@code vanilla} is: the same call, or the same constant pushed. */
	private static boolean same(AbstractInsnNode instruction, AbstractInsnNode vanilla) {
		if (vanilla instanceof MethodInsnNode call)
			return instruction instanceof MethodInsnNode made && made.owner.equals(call.owner) && made.name.equals(call.name) && made.desc.equals(call.desc)
					&& made.getOpcode() == call.getOpcode();
		Object constant = pushed(vanilla);
		return constant != null && constant.equals(pushed(instruction));
	}

	/** Writes the inlined chain: each level's implementation, guarded where it is selected at run time. */
	private static final class Emitter {
		final InsnList out = new InsnList(), captured = new InsnList();
		final ClassNode mixin;
		final boolean isStatic;
		final String original, originalDesc;
		final Function<String, ClassNode> code;
		int next, stack = 8;

		Emitter(ClassNode mixin, boolean isStatic, String original, String originalDesc, Function<String, ClassNode> code, int next) {
			this.mixin = mixin;
			this.isStatic = isStatic;
			this.original = original;
			this.originalDesc = originalDesc;
			this.code = code;
			this.next = next;
		}

		/** The operands of {@code call} are on the stack: stores them, and returns each one's slot (receiver first). */
		int[] store(MethodInsnNode call) {
			List<Type> operands = CarrierDecisions.operandTypes(call);
			int[] temps = new int[operands.size()];
			for (int i = 0; i < temps.length; i++) {
				temps[i] = next;
				next += operands.get(i).getSize();
			}
			for (int i = temps.length - 1; i >= 0; i--) out.add(new VarInsnNode(operands.get(i).getOpcode(Opcodes.ISTORE), temps[i]));
			stack += temps.length + 2;
			return temps;
		}

		void load(MethodInsnNode call, int[] temps) {
			List<Type> operands = CarrierDecisions.operandTypes(call);
			for (int i = 0; i < temps.length; i++) out.add(new VarInsnNode(operands.get(i).getOpcode(Opcodes.ILOAD), temps[i]));
		}

		/** The level {@code link} opens, its call's operands on the stack; leaves what the call would leave. */
		void link(Link link) {
			int[] temps = store(link.call());
			LabelNode fallback = new LabelNode(), end = new LabelNode();
			if (link.guarded()) {
				out.add(new VarInsnNode(Opcodes.ALOAD, temps[0]));
				out.add(new JumpInsnNode(Opcodes.IFNULL, fallback));
				out.add(new VarInsnNode(Opcodes.ALOAD, temps[0]));
				out.add(new LdcInsnNode(link.body().declarer().replace('/', '.')));
				out.add(new LdcInsnNode(link.call().name));
				out.add(new LdcInsnNode(link.call().desc));
				out.add(new MethodInsnNode(Opcodes.INVOKESTATIC, DISPATCH, "inherits", INHERITS, false));
				out.add(new JumpInsnNode(Opcodes.IFEQ, fallback));
			}
			inline(link.body(), temps, link);
			if (link.guarded()) {
				out.add(new JumpInsnNode(Opcodes.GOTO, end));
				out.add(fallback);
				load(link.call(), temps);
				MethodInsnNode call = link.call();
				out.add(new MethodInsnNode(call.getOpcode(), call.owner, call.name, call.desc, call.itf));
				out.add(end);
			}
		}

		/** {@code body}'s instructions, its parameters in {@code temps}; at {@code link}'s site, the next level or the handler. */
		void inline(Body body, int[] temps, Link link) {
			MethodNode method = body.method();
			stack += method.maxStack + 4;
			boolean instance = (method.access & Opcodes.ACC_STATIC) == 0;
			List<Type> parameters = new ArrayList<>();
			if (instance) parameters.add(Type.getObjectType(body.declarer()));
			parameters.addAll(List.of(Type.getArgumentTypes(method.desc)));
			Map<Integer, Integer> slots = new HashMap<>();
			int size = 0;
			for (int i = 0; i < parameters.size(); i++) {
				slots.put(size, temps[i]);
				size += parameters.get(i).getSize();
			}
			int base = next, parameterSize = size;
			next += Math.max(0, method.maxLocals - parameterSize) + 1;
			Map<LabelNode, LabelNode> labels = new HashMap<>();
			for (AbstractInsnNode instruction : method.instructions) if (instruction instanceof LabelNode label) labels.put(label, new LabelNode());
			LabelNode end = new LabelNode();
			Function<Integer, Integer> slot = var -> var < parameterSize ? slots.get(var) : base + var - parameterSize;
			for (AbstractInsnNode instruction : method.instructions) {
				if (instruction instanceof FrameNode || instruction instanceof LineNumberNode) continue;
				if (instruction instanceof LabelNode label) {
					out.add(labels.get(label));
					continue;
				}
				int opcode = instruction.getOpcode();
				if (opcode >= Opcodes.IRETURN && opcode <= Opcodes.RETURN) {
					out.add(new JumpInsnNode(Opcodes.GOTO, end));
					continue;
				}
				if (link != null && instruction == link.site()) {
					if (link.next() != null) link(link.next());
					else handler(link.site());
					continue;
				}
				switch (instruction) {
					case VarInsnNode var -> out.add(new VarInsnNode(opcode, slot.apply(var.var)));
					case IincInsnNode iinc -> out.add(new IincInsnNode(slot.apply(iinc.var), iinc.incr));
					case MethodInsnNode made when inlined(made, body, code) -> inline(resolve(made, code), store(made), null);
					default -> out.add(instruction.clone(labels));
				}
			}
			out.add(end);
		}

		/** The vanilla call's operands are on the stack: hands them, and the captured arguments, to the mod's handler. */
		void handler(AbstractInsnNode site) {
			if (site instanceof MethodInsnNode call) {
				int[] temps = store(call);
				if (!isStatic) out.add(new VarInsnNode(Opcodes.ALOAD, 0));
				load(call, temps);
			} else {
				// The constant: the mod's modifier is handed it, and its answer stands where the constant stood.
				if (!isStatic) out.add(new VarInsnNode(Opcodes.ALOAD, 0));
				out.add(site.clone(Map.of()));
			}
			for (AbstractInsnNode argument : captured) out.add(argument.clone(Map.of()));
			out.add(MixinHandlerShim.callOwn(mixin, isStatic, original, originalDesc));
		}
	}

	/**
	 * Gives {@code wrapper} stack map frames: written alone in a class of the mixin's name, the common supertypes read off the
	 * game's classes. False when that cannot be done (the wrapper is then not used).
	 */
	private static boolean framed(ClassNode mixin, MethodNode wrapper, Function<String, ClassNode> code) {
		try {
			ClassNode shell = new ClassNode();
			shell.version = Math.max(mixin.version, Opcodes.V1_8) & 0xFFFF;
			shell.access = Opcodes.ACC_PUBLIC;
			shell.name = mixin.name;
			shell.superName = "java/lang/Object";
			shell.methods.add(wrapper);
			ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS) {
				@Override
				protected String getCommonSuperClass(String a, String b) {
					return common(a, b, code);
				}
			};
			shell.accept(writer);
			ClassNode read = new ClassNode();
			new ClassReader(writer.toByteArray()).accept(read, 0);
			MethodNode framed = read.methods.getFirst();
			wrapper.instructions = framed.instructions;
			wrapper.maxLocals = framed.maxLocals;
			wrapper.maxStack = framed.maxStack;
			wrapper.localVariables = null;
			return true;
		} catch (RuntimeException unframed) {
			return false;
		}
	}

	/** The nearest common superclass of two classes, read off {@code code}; {@code java/lang/Object} when one is unknown. */
	private static String common(String a, String b, Function<String, ClassNode> code) {
		List<String> ancestors = new ArrayList<>();
		for (String each = a; each != null; each = superOf(each, code)) ancestors.add(each);
		for (String each = b; each != null; each = superOf(each, code)) if (ancestors.contains(each)) return each;
		return "java/lang/Object";
	}

	private static String superOf(String name, Function<String, ClassNode> code) {
		if (name.equals("java/lang/Object")) return null;
		ClassNode type = name.startsWith("java/") ? null : code.apply(name);
		if (type == null || (type.access & Opcodes.ACC_INTERFACE) != 0) return "java/lang/Object";
		return type.superName;
	}

	// ---------------------------------------------------------------------------------------------------------------

	private static MethodNode declared(ClassNode type, String name, String desc) {
		for (MethodNode method : type.methods) if (method.name.equals(name) && method.desc.equals(desc)) return method;
		return null;
	}

	private static String member(MethodInsnNode call) {
		return "L" + call.owner + ";" + call.name + call.desc;
	}

	private static boolean annotated(MethodNode method, String desc) {
		for (List<AnnotationNode> list : Arrays.asList(method.visibleAnnotations, method.invisibleAnnotations))
			if (list != null) for (AnnotationNode annotation : list) if (annotation.desc.equals(desc)) return true;
		return false;
	}

	private static void set(AnnotationNode annotation, String key, Object value) {
		if (annotation.values == null) annotation.values = new ArrayList<>();
		for (int i = 0; i + 1 < annotation.values.size(); i += 2) if (key.equals(annotation.values.get(i))) {
			annotation.values.set(i + 1, value);
			return;
		}
		annotation.values.add(key);
		annotation.values.add(value);
	}

	private static void remove(AnnotationNode annotation, String key) {
		if (annotation.values == null) return;
		for (int i = 0; i + 1 < annotation.values.size(); i += 2) if (key.equals(annotation.values.get(i))) {
			annotation.values.remove(i + 1);
			annotation.values.remove(i);
			return;
		}
	}
}
