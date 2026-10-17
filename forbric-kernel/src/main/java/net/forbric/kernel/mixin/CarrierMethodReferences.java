/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.mixin;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Predicate;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FrameNode;
import org.objectweb.asm.tree.IincInsnNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.IntInsnNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.LineNumberNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.AnalyzerException;
import org.objectweb.asm.tree.analysis.BasicVerifier;
import org.objectweb.asm.tree.analysis.Frame;
import org.objectweb.asm.tree.analysis.SourceInterpreter;
import org.objectweb.asm.tree.analysis.SourceValue;

import net.forbric.api.Ecosystem;
import net.forbric.kernel.classloading.ForbricClassLoader;
import net.forbric.kernel.util.Caught;
import net.forbric.kernel.util.ForbricLog;

/**
 * A redirect written for a call inside a native lambda, where the carrier replaced the lambda with a method reference to a
 * method of its own whose inherited implementation decides the same question with the lambda's leftover captured value folded
 * in: the redirect moves onto the method reference's call, and the moved handler is the native lambda's own body with the
 * mod's handler at the call — so the lambda's polarity and its folded value are kept exactly as natively.
 *
 * <p>Vanilla's {@code EnchantmentHelper.getAvailableEnchantmentResults} filters with a lambda
 * {@code holder -> holder.value().isPrimaryItem(stack) || isBook}; NeoForge filters with {@code stack::isPrimaryItemFor},
 * whose inherited implementation answers true for a book first and otherwise asks the item. A mod's {@code @Redirect} of
 * {@code isPrimaryItem} inside the native lambda binds nothing: the lambda is gone and the method reference makes no call to
 * bind. {@link CarrierDecisions} moves a redirect from a vanilla call to the carrier's call that decides the same branch; this
 * is the same move where the decision is a whole lambda and the carrier's call is a method reference.
 *
 * <p>Nothing here knows a mod, a class or a method by name. The redirect moves when, read off the native class (the mod's
 * loader's) and the merged one:
 * <ul>
 *   <li>it is a {@code @Redirect} with one {@code INVOKE} point, no slice and no {@code @Group}, static, bound natively to one
 *       static method that is a lambda's implementation — the handle of exactly one {@code LambdaMetafactory} call in one
 *       method of the class — and absent merged; the point selects one call there (its ordinal's occurrence), which the
 *       handler does not make itself;</li>
 *   <li>the merged method makes exactly one {@code LambdaMetafactory} call of the same functional method and types, handed on to
 *       the same consumer, whose handle is a method reference to another class's instance method on a captured receiver; the
 *       lambda captured that receiver first, the same value, and exactly one more boolean — and the merged method holds that
 *       boolean's value in a local at the call (the same expression over its own values, {@link NativeCallChanges#localsAt});</li>
 *   <li>the lambda's answer depends on the call's answer and that boolean only, and the method reference's inherited
 *       implementation (followed through every level the receiver inherits) first tests that very boolean's expression,
 *       answering on its own one way what the lambda answers whatever the call says, and otherwise answers something that
 *       stands for the call's answer, as it is or negated, as the lambda's other way is — the folded disjunct, compared
 *       expression by expression after inlining the code that a receiver of that type cannot override;</li>
 *   <li>everything the lambda's body names, but the call, is public.</li>
 * </ul>
 *
 * <p>Then the merged method's method reference is materialized, before the class is defined, as a private static method of
 * the class taking the lambda's own parameters (the receiver, the folded boolean read from its local, the functional
 * arguments) and making the method reference's one call; the method reference names it. The redirect moves onto that call
 * there; the mod's handler keeps its body under its name with {@code $forbricOriginal} appended, and a new handler of the
 * call's shape, capturing the materialized method's parameters (the lambda's), runs the native lambda's body with the mod's
 * handler at the call. A receiver that overrides the carrier's method gets the mod's answer as natively it would (natively no
 * such receiver exists); the mod's own fallback, if it asks the carrier, reaches the override.
 *
 * <p>What is proved and what is not: the folded value is proved to be the very expression the lambda captured, tested first
 * with the lambda's polarity; the rest of the carrier's decision is taken as the carrier's stand-in for the vanilla call, as
 * {@link CarrierDecisions} takes a carrier's decision, not proved to answer what the vanilla call's body answers. NeoForge's
 * {@code isPrimaryItemFor}, for one, also says yes for an enchanted book whose enchantment names no primary items, where
 * vanilla's {@code isPrimaryItem} asks the enchantment's supported items. With the mod's redirect moved, the mod's handler
 * answers in that place, as natively. {@code -Dforbric.carrierMethodReferences=off} plans nothing.
 */
public final class CarrierMethodReferences {
	public static final String PROPERTY = "forbric.carrierMethodReferences";
	static final String PREFIX = "forbric$reference$";
	private static final String METAFACTORY_OWNER = "java/lang/invoke/LambdaMetafactory";
	private static final int DEPTH = 6;

	/**
	 * A method reference to materialize: in {@code host}.{@code method}, the one metafactory call of functional method
	 * {@code samName} with {@code samType}/{@code instantiated} and handle {@code handle}, captured as {@code factory}; it is
	 * handed the locals {@code extra} (of {@code extraTypes}) after its own captures and names {@code thunk} ({@code thunkDesc}).
	 */
	record Plan(String host, String method, String samName, String factory, Type samType, Type instantiated, Handle handle,
			List<Integer> extra, List<String> extraTypes, String thunk, String thunkDesc, String source, String handler) {
		boolean same(Plan other) {
			return host.equals(other.host) && method.equals(other.method) && thunk.equals(other.thunk) && thunkDesc.equals(other.thunkDesc);
		}
	}

	private static final Map<ClassLoader, List<Plan>> PLANS = Collections.synchronizedMap(new WeakHashMap<>());

	private CarrierMethodReferences() {
	}

	static boolean enabled() {
		return !"off".equalsIgnoreCase(System.getProperty(PROPERTY, "on"));
	}

	public static void release(ClassLoader loader) {
		PLANS.remove(loader);
	}

	private static List<Plan> plans(ClassLoader loader) {
		synchronized (PLANS) {
			return PLANS.computeIfAbsent(loader, ignored -> new CopyOnWriteArrayList<>());
		}
	}

	/** Moves every eligible redirect of {@code mixin}, registering the materialized reference with {@code loader}. */
	public static int adapt(ClassNode mixin, Function<String, ClassNode> code, BiFunction<Ecosystem, String, ClassNode> natives,
			ForbricClassLoader loader) {
		if (loader == null) return 0;
		return adapt(mixin, code, natives, plan -> register(loader, plan));
	}

	/**
	 * Moves every eligible redirect of {@code mixin}; {@code register} is handed each materialization the move needs, and answers
	 * whether it will be made before the class is defined (only then is the redirect moved). {@code code} must carry code.
	 */
	static int adapt(ClassNode mixin, Function<String, ClassNode> code, BiFunction<Ecosystem, String, ClassNode> natives, Predicate<Plan> register) {
		if (!enabled() || mixin == null || mixin.methods == null || code == null || natives == null) return 0;
		Ecosystem ecosystem = MixinStubRebind.ecosystemOf(mixin.name);
		if (ecosystem == null) return 0;
		int moved = 0;
		for (String target : MixinFit.mixinTargets(mixin)) {
			ClassNode original = natives.apply(ecosystem, target), merged = null;
			if (original == null) continue;
			for (MethodNode handler : List.copyOf(mixin.methods)) {
				AnnotationNode redirect = MixinFit.injectorOf(handler);
				if (redirect == null || !CarrierDecisions.REDIRECT.equals(redirect.desc) || annotated(handler, CarrierDecisions.GROUP)
						|| MixinFit.value(redirect, "slice") != null || (handler.access & Opcodes.ACC_STATIC) == 0) continue;
				List<AnnotationNode> points = MixinFit.atNodes(redirect);
				if (points.size() != 1 || !"INVOKE".equals(MixinFit.asString(MixinFit.value(points.getFirst(), "value")))) continue;
				if (merged == null) merged = code.apply(target);
				if (merged == null) break;
				Move move = plan(mixin, handler, redirect, points.getFirst(), original, merged, code);
				if (move == null || !register.test(move.plan())) continue;
				String name = handler.name;
				apply(mixin, handler, redirect, points.getFirst(), move, code);
				moved++;
				merged = null;
				ForbricLog.info("[Forbric/Mixin] %s.%s: %s is no longer called in %s.%s, a lambda the carrier replaced with a method reference to %s.%s,"
						+ " whose inherited implementation folds in the lambda's captured %s — the redirect moves onto that call, made in %s,"
						+ " and runs the native lambda with the mod's handler at the call", mixin.name.replace('/', '.'), name,
						move.call().owner.replace('/', '.') + "." + move.call().name, target.replace('/', '.'), move.lambda().name,
						move.plan().handle().getOwner().replace('/', '.'), move.plan().handle().getName(), Type.getType(move.plan().extraTypes().getFirst()).getClassName(),
						move.plan().thunk());
			}
		}
		return moved;
	}

	/** What a move needs: the materialization, the native lambda and its call, and the new handler's code. */
	record Move(Plan plan, MethodNode lambda, MethodInsnNode call, MethodNode wrapper) {
	}

	private static Move plan(ClassNode mixin, MethodNode handler, AnnotationNode redirect, AnnotationNode at, ClassNode original, ClassNode merged,
			Function<String, ClassNode> code) {
		// The native lambda the redirect is bound to: one static method that is one metafactory call's implementation, gone from
		// the merged class or left there dead (nothing in it names it), whatever its name binds there now.
		MethodNode lambda = MixinTargetSelectors.one(handler, original);
		if (lambda == null || (lambda.access & Opcodes.ACC_STATIC) == 0 || referenced(merged, lambda.name, lambda.desc)) return null;
		MethodNode nativeHost = null;
		InvokeDynamicInsnNode created = null;
		for (MethodNode method : original.methods) for (AbstractInsnNode instruction : method.instructions) {
			if (!(instruction instanceof InvokeDynamicInsnNode indy) || !metafactory(indy) || !(indy.bsmArgs[1] instanceof Handle handle)
					|| !handle.getOwner().equals(original.name) || !handle.getName().equals(lambda.name) || !handle.getDesc().equals(lambda.desc)) continue;
			if (created != null) return null;
			created = indy;
			nativeHost = method;
		}
		if (created == null) return null;
		// The call the point selects there, which the handler does not make itself.
		List<MethodInsnNode> calls = MixinCallbackShape.calls(at, lambda);
		int ordinal = MixinFit.value(at, "ordinal") instanceof Number number ? number.intValue() : -1;
		MethodInsnNode call = ordinal >= 0 ? (ordinal < calls.size() ? calls.get(ordinal) : null) : calls.size() == 1 ? calls.getFirst() : null;
		if (call == null || call.getOpcode() == Opcodes.INVOKESPECIAL) return null;
		for (AbstractInsnNode instruction : handler.instructions)
			if (instruction instanceof MethodInsnNode own && own.owner.equals(call.owner) && own.name.equals(call.name) && own.desc.equals(call.desc)) return null;

		// The merged method's one metafactory call of the same functional shape, handed to the same consumer: a method reference.
		MethodNode mergedHost = declared(merged, nativeHost.name, nativeHost.desc);
		if (mergedHost == null) return null;
		InvokeDynamicInsnNode reference = null;
		for (AbstractInsnNode instruction : mergedHost.instructions) {
			if (!(instruction instanceof InvokeDynamicInsnNode indy) || !metafactory(indy) || !indy.name.equals(created.name)
					|| !indy.bsmArgs[0].equals(created.bsmArgs[0]) || !indy.bsmArgs[2].equals(created.bsmArgs[2])
					|| !Type.getReturnType(indy.desc).equals(Type.getReturnType(created.desc))) continue;
			if (reference != null) return null;
			reference = indy;
		}
		if (reference == null || !(reference.bsmArgs[1] instanceof Handle handle) || !sameConsumer(created, reference)) return null;
		// A method reference to another class's instance method on its captured receiver; or one already materialized by an
		// earlier read of this mixin: a static method of the class that hands its parameters, unchanged, to such a method.
		Handle method = handle;
		boolean materialized = false;
		if (handle.getOwner().equals(merged.name) && handle.getTag() == Opcodes.H_INVOKESTATIC) {
			method = forwarded(declared(merged, handle.getName(), handle.getDesc()), Type.getArgumentTypes(reference.desc).length);
			materialized = true;
		}
		if (method == null || method.getOwner().equals(merged.name) || method.getTag() != Opcodes.H_INVOKEINTERFACE && method.getTag() != Opcodes.H_INVOKEVIRTUAL) return null;

		// Captures: the reference's receiver is the lambda's first capture; the lambda captures exactly one boolean more.
		Type[] lambdaCaptures = Type.getArgumentTypes(created.desc), referenceCaptures = Type.getArgumentTypes(reference.desc);
		if (referenceCaptures.length != (materialized ? 2 : 1) || lambdaCaptures.length != 2 || !lambdaCaptures[1].equals(Type.BOOLEAN_TYPE)) return null;
		List<NativeCallChanges.Expr> nativeCaptures = captures(original.name, nativeHost, created), mergedCaptures = captures(merged.name, mergedHost, reference);
		if (nativeCaptures == null || mergedCaptures == null || !nativeCaptures.getFirst().equals(mergedCaptures.getFirst())
				|| materialized && !nativeCaptures.equals(mergedCaptures)) return null;
		NativeCallChanges.Expr folded = nativeCaptures.get(1);
		List<Integer> slots = new ArrayList<>();
		if (!materialized) {
			Map<Integer, NativeCallChanges.Expr> held = NativeCallChanges.localsAt(merged.name, mergedHost, reference);
			if (held == null) return null;
			for (int slot : MixinLocalOriginProof.typedSlots(mergedHost, mergedHost.instructions.indexOf(reference), Type.BOOLEAN_TYPE, false))
				if (folded.equals(held.get(slot))) slots.add(slot);
			if (slots.size() != 1) return null;
		}
		// The lambda's parameters: its captures, then the functional arguments, which the reference hands its call.
		Type[] lambdaParameters = Type.getArgumentTypes(lambda.desc), samArguments = ((Type) created.bsmArgs[2]).getArgumentTypes();
		if (lambdaParameters.length != 2 + samArguments.length || !Type.getReturnType(lambda.desc).equals(Type.getReturnType(method.getDesc()))
				|| Type.getArgumentTypes(method.getDesc()).length != samArguments.length) return null;

		// The folded disjunct: the lambda decides on the call and the boolean alone; the reference's inherited implementation first
		// tests the boolean's very expression and answers on its own what the lambda answers whatever the call says.
		Symbolic.Tree lambdaTree = new Symbolic(code, false, call).run(lambda, inputs(lambda.desc, true), 0);
		Boolean[][] table = table(lambdaTree);
		if (table == null) return null;
		Symbolic chain = new Symbolic(code, true, null);
		Symbolic.Val receiver = new Symbolic.Val(Symbolic.input(0), referenceCaptures[0].getInternalName());
		List<Symbolic.Val> arguments = new ArrayList<>(List.of(receiver));
		for (int i = 0; i < samArguments.length; i++) arguments.add(new Symbolic.Val(Symbolic.input(2 + i), typeName(samArguments[i])));
		Symbolic.Tree decided = chain.call(new MethodInsnNode(method.getTag() == Opcodes.H_INVOKEINTERFACE ? Opcodes.INVOKEINTERFACE : Opcodes.INVOKEVIRTUAL,
				method.getOwner(), method.getName(), method.getDesc(), method.isInterface()), arguments, true, 0);
		Symbolic.E foldedValue = new Symbolic(code, false, null).value(folded, Map.of(nativeCaptures.getFirst(), receiver));
		if (decided == null || foldedValue == null || !folds(decided, foldedValue, table)) return null;

		// Everything the lambda's body names, but the call, the new handler can name too.
		if (!names(lambda, call, code)) return null;

		String thunkDesc = lambda.desc;
		if (materialized && !handle.getDesc().equals(thunkDesc)) return null;
		String thunk = materialized ? handle.getName() : PREFIX + digest(merged.name + "\0" + mergedHost.name + mergedHost.desc + "\0" + method + "\0" + slots + "\0" + thunkDesc);
		Plan plan = new Plan(merged.name, mergedHost.name + mergedHost.desc, reference.name, reference.desc, (Type) reference.bsmArgs[0],
				(Type) reference.bsmArgs[2], method, List.copyOf(slots), List.of(Type.BOOLEAN_TYPE.getDescriptor()), thunk, thunkDesc, mixin.name, handler.name);
		MethodNode wrapper = wrapper(mixin, handler, lambda, call, method, code);
		return wrapper == null ? null : new Move(plan, lambda, call, wrapper);
	}

	/** Rewrites the mixin for a planned move: the original handler renamed, the new one carrying the redirect onto the thunk's call. */
	private static void apply(ClassNode mixin, MethodNode handler, AnnotationNode redirect, AnnotationNode at, Move move, Function<String, ClassNode> code) {
		MethodNode wrapper = move.wrapper();
		handler.name = handler.name + CarrierDecisions.SUFFIX;
		boolean visible = handler.visibleAnnotations != null && handler.visibleAnnotations.remove(redirect);
		if (!visible && handler.invisibleAnnotations != null) handler.invisibleAnnotations.remove(redirect);
		set(redirect, "method", new ArrayList<>(List.of(move.plan().thunk() + move.plan().thunkDesc())));
		Handle method = move.plan().handle();
		set(at, "target", "L" + method.getOwner() + ";" + method.getName() + method.getDesc());
		remove(at, "ordinal");
		if (visible) wrapper.visibleAnnotations = new ArrayList<>(List.of(redirect));
		else wrapper.invisibleAnnotations = new ArrayList<>(List.of(redirect));
		mixin.methods.add(wrapper);
		CurrentBodyOrdinals.mark(wrapper);
	}

	/**
	 * The new handler: the reference's call's shape (its receiver, its arguments), then the thunk's parameters — the lambda's —
	 * captured; its body the native lambda's, with the mod's original handler at the call, handed the call's values and the
	 * lambda's parameters.
	 */
	private static MethodNode wrapper(ClassNode mixin, MethodNode handler, MethodNode lambda, MethodInsnNode call, Handle method, Function<String, ClassNode> code) {
		Type[] handlerParameters = Type.getArgumentTypes(handler.desc), lambdaParameters = Type.getArgumentTypes(lambda.desc);
		List<Type> callOperands = CarrierDecisions.operandTypes(call);
		if (!Type.getReturnType(handler.desc).equals(Type.getReturnType(call.desc)) || handlerParameters.length != callOperands.size() + lambdaParameters.length)
			return null;
		for (int i = 0; i < callOperands.size(); i++) if (!handlerParameters[i].equals(callOperands.get(i))) return null;
		for (int i = 0; i < lambdaParameters.length; i++) if (!handlerParameters[callOperands.size() + i].equals(lambdaParameters[i])) return null;
		for (List<AnnotationNode>[] table : Arrays.asList(handler.visibleParameterAnnotations, handler.invisibleParameterAnnotations))
			if (table != null) for (List<AnnotationNode> annotations : table) if (annotations != null && !annotations.isEmpty()) return null;

		List<Type> referenceOperands = new ArrayList<>();
		referenceOperands.add(Type.getObjectType(method.getOwner()));
		referenceOperands.addAll(List.of(Type.getArgumentTypes(method.getDesc())));
		List<Type> parameters = new ArrayList<>(referenceOperands);
		parameters.addAll(List.of(lambdaParameters));
		MethodNode wrapper = new MethodNode(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC, handler.name,
				Type.getMethodDescriptor(Type.getReturnType(lambda.desc), parameters.toArray(Type[]::new)), null, null);
		int base = 0;
		for (Type operand : referenceOperands) base += operand.getSize();
		int lambdaSize = 0;
		for (Type parameter : lambdaParameters) lambdaSize += parameter.getSize();
		int shift = base, next = base + Math.max(lambda.maxLocals, lambdaSize);
		Map<LabelNode, LabelNode> labels = new HashMap<>();
		for (AbstractInsnNode instruction : lambda.instructions) if (instruction instanceof LabelNode label) labels.put(label, new LabelNode());
		InsnList out = wrapper.instructions;
		for (AbstractInsnNode instruction : lambda.instructions) {
			if (instruction instanceof FrameNode || instruction instanceof LineNumberNode) continue;
			if (instruction instanceof LabelNode label) {
				out.add(labels.get(label));
				continue;
			}
			if (instruction == call) {
				int[] temps = new int[callOperands.size()];
				for (int i = 0; i < temps.length; i++) {
					temps[i] = next;
					next += callOperands.get(i).getSize();
				}
				for (int i = temps.length - 1; i >= 0; i--) out.add(new VarInsnNode(callOperands.get(i).getOpcode(Opcodes.ISTORE), temps[i]));
				for (int i = 0; i < temps.length; i++) out.add(new VarInsnNode(callOperands.get(i).getOpcode(Opcodes.ILOAD), temps[i]));
				int slot = base;
				for (Type parameter : lambdaParameters) {
					out.add(new VarInsnNode(parameter.getOpcode(Opcodes.ILOAD), slot));
					slot += parameter.getSize();
				}
				out.add(MixinHandlerShim.callOwn(mixin, true, handler.name + CarrierDecisions.SUFFIX, handler.desc));
				continue;
			}
			switch (instruction) {
				case VarInsnNode var -> out.add(new VarInsnNode(var.getOpcode(), var.var + shift));
				case IincInsnNode iinc -> out.add(new IincInsnNode(iinc.var + shift, iinc.incr));
				default -> out.add(instruction.clone(labels));
			}
		}
		wrapper.maxLocals = next;
		wrapper.maxStack = lambda.maxStack + parameters.size() + 4;
		try {
			new Analyzer<>(new BasicVerifier()).analyze(mixin.name, wrapper);
		} catch (AnalyzerException | RuntimeException unverifiable) {
			Caught.expected(unverifiable);
			return null;
		}
		return CarrierDefaultChains.framed(mixin, wrapper, code) ? wrapper : null;
	}

	// ---------------------------------------------------------------------------------------------------------------
	// The folded disjunct

	/**
	 * The lambda's answer for each answer of the call ({@code [c][d]}, c the call's, d the captured boolean's): read off its
	 * tree, whose every test must be one of the two and every answer a constant. Null otherwise.
	 */
	static Boolean[][] table(Symbolic.Tree tree) {
		if (tree == null) return null;
		Boolean[][] table = new Boolean[2][2];
		for (int c = 0; c < 2; c++) for (int d = 0; d < 2; d++) {
			Boolean answer = evaluate(tree, c == 1, d == 1);
			if (answer == null) return null;
			table[c][d] = answer;
		}
		return table;
	}

	private static Boolean evaluate(Symbolic.Tree tree, boolean c, boolean d) {
		while (tree instanceof Symbolic.Test test) {
			Boolean condition = truth(test.cond(), c, d);
			if (condition == null) return null;
			tree = condition ? test.yes() : test.no();
		}
		return tree instanceof Symbolic.Leaf leaf ? constant(leaf.value()) : null;
	}

	private static Boolean truth(Symbolic.E condition, boolean c, boolean d) {
		if (condition.op().equals("not")) {
			Boolean inner = truth(condition.in().getFirst(), c, d);
			return inner == null ? null : !inner;
		}
		if (condition.equals(Symbolic.CALL)) return c;
		if (condition.equals(Symbolic.input(1))) return d;
		return constant(condition);
	}

	private static Boolean constant(Symbolic.E value) {
		if (value != null && value.op().equals("const") && value.sym().matches("-?\\d+")) return Integer.parseInt(value.sym()) != 0;
		return null;
	}

	/**
	 * Whether the reference's decision {@code decided} first tests {@code folded} (or its negation) and answers on its own, on
	 * the way it holds, the constant the lambda answers there whatever the call says; and whether the lambda's other way
	 * answers the call's answer as it is, or negated — what the rest of the decision stands for.
	 */
	static boolean folds(Symbolic.Tree decided, Symbolic.E folded, Boolean[][] table) {
		if (!(decided instanceof Symbolic.Test root)) return false;
		boolean negated;
		if (root.cond().equals(folded)) negated = false;
		else if (root.cond().equals(Symbolic.not(folded)) || Symbolic.not(root.cond()).equals(folded)) negated = true;
		else return false;
		Symbolic.Tree whenHolds = negated ? root.no() : root.yes();
		Boolean alone = whenHolds instanceof Symbolic.Leaf leaf ? constant(leaf.value()) : null;
		if (alone == null || table[0][1] != alone || table[1][1] != alone) return false;
		boolean same = !table[0][0] && table[1][0], opposite = table[0][0] && !table[1][0];
		return same || opposite;
	}

	// ---------------------------------------------------------------------------------------------------------------
	// Materializing the method reference

	private static boolean register(ForbricClassLoader loader, Plan plan) {
		List<Plan> plans = plans(loader);
		for (Plan existing : plans) if (existing.same(plan)) return true;
		return loader.registerBeforeDefinition(plan.host().replace('/', '.'), () -> plans.add(plan));
	}

	/** {@code binary}'s pre-mixin bytes with every planned method reference materialized. Unchanged where no plan names it. */
	public static byte[] transform(ClassLoader loader, String binary, byte[] bytes) {
		if (bytes == null) return null;
		List<Plan> plans = PLANS.get(loader);
		if (plans == null || plans.isEmpty()) return bytes;
		String internal = binary.replace('.', '/');
		List<Plan> mine = plans.stream().filter(plan -> plan.host().equals(internal)).toList();
		if (mine.isEmpty()) return bytes;
		ClassNode node = new ClassNode();
		new ClassReader(bytes).accept(node, 0);
		boolean changed = false;
		for (Plan plan : mine) {
			if (materialize(node, plan)) changed = true;
			else ForbricLog.warn("[Forbric/Mixin] %s.%s: %s.%s no longer makes the method reference to %s.%s as planned; %s is not made",
					plan.source().replace('/', '.'), plan.handler(), binary, plan.method(), plan.handle().getOwner().replace('/', '.'), plan.handle().getName(), plan.thunk());
		}
		if (!changed) return bytes;
		ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
		node.accept(writer);
		return writer.toByteArray();
	}

	/** Gives {@code node} the plan's thunk and points the one matching method reference at it, handed the extra locals. */
	static boolean materialize(ClassNode node, Plan plan) {
		if (declared(node, plan.thunk(), plan.thunkDesc()) != null) return true;
		MethodNode method = node.methods.stream().filter(m -> (m.name + m.desc).equals(plan.method())).findFirst().orElse(null);
		if (method == null) return false;
		InvokeDynamicInsnNode reference = null;
		for (AbstractInsnNode instruction : method.instructions) {
			if (!(instruction instanceof InvokeDynamicInsnNode indy) || !metafactory(indy) || !indy.name.equals(plan.samName()) || !indy.desc.equals(plan.factory())
					|| !plan.samType().equals(indy.bsmArgs[0]) || !plan.instantiated().equals(indy.bsmArgs[2]) || !plan.handle().equals(indy.bsmArgs[1])) continue;
			if (reference != null) return false;
			reference = indy;
		}
		if (reference == null) return false;
		InsnList loads = new InsnList();
		for (int i = 0; i < plan.extra().size(); i++) loads.add(new VarInsnNode(Type.getType(plan.extraTypes().get(i)).getOpcode(Opcodes.ILOAD), plan.extra().get(i)));
		method.instructions.insertBefore(reference, loads);
		Type[] thunkParameters = Type.getArgumentTypes(plan.thunkDesc());
		int captured = Type.getArgumentTypes(plan.factory()).length + plan.extra().size();
		reference.desc = Type.getMethodDescriptor(Type.getReturnType(plan.factory()), Arrays.copyOf(thunkParameters, captured));
		reference.bsmArgs = reference.bsmArgs.clone();
		reference.bsmArgs[1] = new Handle(Opcodes.H_INVOKESTATIC, node.name, plan.thunk(), plan.thunkDesc(), (node.access & Opcodes.ACC_INTERFACE) != 0);
		MethodNode thunk = new MethodNode(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_SYNTHETIC, plan.thunk(), plan.thunkDesc(), null, null);
		Handle target = plan.handle();
		Type[] targetArguments = Type.getArgumentTypes(target.getDesc());
		// The receiver is the reference's first capture; its call's arguments are the functional arguments, after the captures.
		thunk.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
		int slot = 0;
		for (int i = 0; i < captured; i++) slot += thunkParameters[i].getSize();
		for (Type argument : targetArguments) {
			thunk.instructions.add(new VarInsnNode(argument.getOpcode(Opcodes.ILOAD), slot));
			slot += argument.getSize();
		}
		thunk.instructions.add(new MethodInsnNode(target.getTag() == Opcodes.H_INVOKEINTERFACE ? Opcodes.INVOKEINTERFACE : Opcodes.INVOKEVIRTUAL,
				target.getOwner(), target.getName(), target.getDesc(), target.isInterface()));
		thunk.instructions.add(new InsnNode(Type.getReturnType(target.getDesc()).getOpcode(Opcodes.IRETURN)));
		thunk.maxLocals = slot;
		thunk.maxStack = slot + 2;
		node.methods.add(thunk);
		return true;
	}

	// ---------------------------------------------------------------------------------------------------------------
	// Reading the bodies

	/** The values a metafactory call captures, each the expression the method computes it with; null when one is not read. */
	private static List<NativeCallChanges.Expr> captures(String owner, MethodNode method, InvokeDynamicInsnNode indy) {
		Frame<SourceValue>[] frames;
		try {
			frames = new Analyzer<>(new SourceInterpreter()).analyze(owner, method);
		} catch (AnalyzerException | RuntimeException unreadable) {
			Caught.expected(unreadable);
			return null;
		}
		Frame<SourceValue> frame = frames[method.instructions.indexOf(indy)];
		int count = Type.getArgumentTypes(indy.desc).length;
		if (frame == null || frame.getStackSize() < count) return null;
		Map<Integer, NativeCallChanges.Expr> locals = NativeCallChanges.localsAt(owner, method, indy);
		if (locals == null) return null;
		List<NativeCallChanges.Expr> out = new ArrayList<>();
		for (int i = frame.getStackSize() - count; i < frame.getStackSize(); i++) {
			VarInsnNode load = load(method, frames, frame.getStack(i));
			if (load == null) return null;
			// The local is read straight off: nothing between the load and the call can change it, or the frame says another value.
			Frame<SourceValue> at = frames[method.instructions.indexOf(load)];
			if (at == null || !at.getLocal(load.var).equals(frame.getLocal(load.var))) return null;
			NativeCallChanges.Expr expression = locals.get(load.var);
			if (expression == null) return null;
			out.add(expression);
		}
		return out;
	}

	/**
	 * The instance method a materialized reference hands its parameters to: {@code thunk} is static, loads its first parameter
	 * (the receiver) and then every parameter after the {@code captured} ones, in order, makes one instance call of another
	 * class with exactly those, and returns its answer. Null for any other body.
	 */
	static Handle forwarded(MethodNode thunk, int captured) {
		if (thunk == null || (thunk.access & Opcodes.ACC_STATIC) == 0) return null;
		List<AbstractInsnNode> code = new ArrayList<>();
		for (AbstractInsnNode instruction : thunk.instructions) if (instruction.getOpcode() >= 0) code.add(instruction);
		Type[] parameters = Type.getArgumentTypes(thunk.desc);
		if (captured < 1 || captured > parameters.length || code.size() != parameters.length - captured + 3) return null;
		if (!(code.getFirst() instanceof VarInsnNode receiver) || receiver.getOpcode() != Opcodes.ALOAD || receiver.var != 0) return null;
		int slot = 0;
		for (int i = 0; i < captured; i++) slot += parameters[i].getSize();
		for (int i = captured; i < parameters.length; i++) {
			if (!(code.get(1 + i - captured) instanceof VarInsnNode load) || load.getOpcode() != parameters[i].getOpcode(Opcodes.ILOAD) || load.var != slot) return null;
			slot += parameters[i].getSize();
		}
		if (!(code.get(code.size() - 2) instanceof MethodInsnNode call) || call.getOpcode() != Opcodes.INVOKEVIRTUAL && call.getOpcode() != Opcodes.INVOKEINTERFACE
				|| code.getLast().getOpcode() != Type.getReturnType(thunk.desc).getOpcode(Opcodes.IRETURN) || !Type.getReturnType(call.desc).equals(Type.getReturnType(thunk.desc))) return null;
		return new Handle(call.getOpcode() == Opcodes.INVOKEINTERFACE ? Opcodes.H_INVOKEINTERFACE : Opcodes.H_INVOKEVIRTUAL, call.owner, call.name, call.desc, call.itf);
	}

	/** Whether any method of {@code type} names its own method {@code name}{@code desc}: calls it, or makes a reference to it. */
	private static boolean referenced(ClassNode type, String name, String desc) {
		for (MethodNode method : type.methods) for (AbstractInsnNode instruction : method.instructions) {
			if (instruction instanceof MethodInsnNode call && call.owner.equals(type.name) && call.name.equals(name) && call.desc.equals(desc)) return true;
			if (instruction instanceof InvokeDynamicInsnNode indy) for (Object argument : indy.bsmArgs)
				if (argument instanceof Handle handle && handle.getOwner().equals(type.name) && handle.getName().equals(name) && handle.getDesc().equals(desc)) return true;
		}
		return false;
	}

	/** The local load a stack value is, seen through the copies ({@code dup}) a compiler makes of it; null for anything else. */
	private static VarInsnNode load(MethodNode method, Frame<SourceValue>[] frames, SourceValue value) {
		for (int hops = 0; hops < 8; hops++) {
			if (value.insns.size() != 1) return null;
			AbstractInsnNode producer = value.insns.iterator().next();
			if (producer instanceof VarInsnNode load && load.getOpcode() >= Opcodes.ILOAD && load.getOpcode() <= Opcodes.ALOAD) return load;
			if (producer.getOpcode() != Opcodes.DUP) return null;
			Frame<SourceValue> before = frames[method.instructions.indexOf(producer)];
			if (before == null || before.getStackSize() == 0) return null;
			value = before.getStack(before.getStackSize() - 1);
		}
		return null;
	}

	/** Whether the next instruction after each call is the same call: both functional objects are handed to the same consumer. */
	private static boolean sameConsumer(InvokeDynamicInsnNode a, InvokeDynamicInsnNode b) {
		AbstractInsnNode x = next(a), y = next(b);
		return x instanceof MethodInsnNode one && y instanceof MethodInsnNode two && one.getOpcode() == two.getOpcode() && one.owner.equals(two.owner)
				&& one.name.equals(two.name) && one.desc.equals(two.desc);
	}

	private static AbstractInsnNode next(AbstractInsnNode from) {
		AbstractInsnNode at = from.getNext();
		while (at != null && at.getOpcode() < 0) at = at.getNext();
		return at;
	}

	/** Whether every class and member the lambda names, but the call, is public: the new handler names them from the mixin. */
	private static boolean names(MethodNode lambda, MethodInsnNode call, Function<String, ClassNode> code) {
		for (AbstractInsnNode instruction : lambda.instructions) {
			if (instruction == call) continue;
			switch (instruction) {
				case MethodInsnNode made -> {
					if (!CarrierDefaultChains.publicMember(made.owner, made.name, made.desc, false, code)) return false;
				}
				case FieldInsnNode field -> {
					if (!CarrierDefaultChains.publicMember(field.owner, field.name, field.desc, true, code)) return false;
				}
				case TypeInsnNode type -> {
					if (!CarrierDefaultChains.publicType(type.desc, code)) return false;
				}
				case InvokeDynamicInsnNode dynamic -> {
					return false;
				}
				case LdcInsnNode ldc -> {
					if (ldc.cst instanceof Type type && type.getSort() == Type.OBJECT && !CarrierDefaultChains.publicType(type.getInternalName(), code)) return false;
				}
				default -> {
				}
			}
		}
		return lambda.tryCatchBlocks == null || lambda.tryCatchBlocks.isEmpty();
	}

	private static boolean metafactory(InvokeDynamicInsnNode indy) {
		return indy.bsm.getOwner().equals(METAFACTORY_OWNER) && indy.bsm.getName().equals("metafactory") && indy.bsmArgs.length == 3
				&& indy.bsmArgs[0] instanceof Type && indy.bsmArgs[1] instanceof Handle && indy.bsmArgs[2] instanceof Type;
	}

	private static List<Symbolic.Val> inputs(String desc, boolean isStatic) {
		List<Symbolic.Val> out = new ArrayList<>();
		Type[] arguments = Type.getArgumentTypes(desc);
		for (int i = 0; i < arguments.length; i++) out.add(new Symbolic.Val(Symbolic.input(i), typeName(arguments[i])));
		return out;
	}

	static String typeName(Type type) {
		return type.getSort() == Type.OBJECT ? type.getInternalName() : type.getDescriptor();
	}

	private static String digest(String identity) {
		try {
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(identity.getBytes(StandardCharsets.UTF_8))).substring(0, 16);
		} catch (NoSuchAlgorithmException impossible) {
			throw new AssertionError(impossible);
		}
	}

	private static MethodNode declared(ClassNode type, String name, String desc) {
		for (MethodNode method : type.methods) if (method.name.equals(name) && method.desc.equals(desc)) return method;
		return null;
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

	// ---------------------------------------------------------------------------------------------------------------

	/**
	 * A small symbolic evaluator: a method body as a tree of tests over expressions of its inputs, with the code a receiver of
	 * the type at hand cannot override inlined (a static, private or special call, a call on a final class, or — following the
	 * inherited implementation, for the carrier's default chain — the implementation a receiver of the declared type inherits).
	 * A call is named by the implementation it reaches from the receiver's type, so two spellings of one call read alike.
	 */
	static final class Symbolic {
		/** The marker the left-alone call's answer is. */
		static final E CALL = new E("call-answer", "", List.of());

		record E(String op, String sym, List<E> in) {
			E {
				in = List.copyOf(in);
			}
		}

		record Val(E e, String type) {
		}

		sealed interface Tree permits Test, Leaf {
		}

		record Test(E cond, Tree yes, Tree no) implements Tree {
		}

		record Leaf(E value) implements Tree {
		}

		static E input(int index) {
			return new E("input", Integer.toString(index), List.of());
		}

		static E not(E inner) {
			return inner.op().equals("not") ? inner.in().getFirst() : new E("not", "", List.of(inner));
		}

		private final Function<String, ClassNode> code;
		private final boolean followDefaults;
		private final MethodInsnNode leftAlone;
		private int budget = 4000;

		Symbolic(Function<String, ClassNode> code, boolean followDefaults, MethodInsnNode leftAlone) {
			this.code = code;
			this.followDefaults = followDefaults;
			this.leftAlone = leftAlone;
		}

		private record State(List<Val> stack, Map<Integer, Val> locals) {
			State copy() {
				return new State(new ArrayList<>(stack), new HashMap<>(locals));
			}
		}

		/** {@code body} run on {@code arguments} (the receiver first for an instance method); null where it cannot be read. */
		Tree run(MethodNode body, List<Val> arguments, int depth) {
			if (depth > DEPTH || body.instructions.size() == 0 || body.tryCatchBlocks != null && !body.tryCatchBlocks.isEmpty()) return null;
			Map<Integer, Val> locals = new HashMap<>();
			int slot = 0;
			for (Val argument : arguments) {
				locals.put(slot, argument);
				slot += argument.type().equals("J") || argument.type().equals("D") ? 2 : 1;
			}
			try {
				return walk(body, body.instructions.getFirst(), new State(new ArrayList<>(), locals), depth);
			} catch (IllegalStateException unreadable) {
				Caught.expected(unreadable);
				return null;
			}
		}

		private Tree walk(MethodNode body, AbstractInsnNode from, State state, int depth) {
			InsnList instructions = body.instructions;
			for (AbstractInsnNode instruction = from; instruction != null; instruction = instruction.getNext()) {
				if (--budget < 0) throw new IllegalStateException("too long");
				int opcode = instruction.getOpcode();
				if (opcode < 0 || opcode == Opcodes.NOP) continue;
				switch (instruction) {
					case VarInsnNode var -> {
						if (opcode >= Opcodes.ILOAD && opcode <= Opcodes.ALOAD) {
							Val value = state.locals().get(var.var);
							if (value == null) throw new IllegalStateException("unset local");
							state.stack().add(value);
						} else if (opcode >= Opcodes.ISTORE && opcode <= Opcodes.ASTORE) state.locals().put(var.var, pop(state));
						else throw new IllegalStateException("local");
					}
					case FieldInsnNode field -> {
						String symbol = field.owner + "." + field.name + ":" + field.desc;
						if (opcode == Opcodes.GETSTATIC) state.stack().add(new Val(new E("static", symbol, List.of()), typeName(Type.getType(field.desc))));
						else if (opcode == Opcodes.GETFIELD) state.stack().add(new Val(new E("field", symbol, List.of(pop(state).e())), typeName(Type.getType(field.desc))));
						else throw new IllegalStateException("field write");
					}
					case TypeInsnNode type -> {
						if (opcode == Opcodes.CHECKCAST) {
							Val value = pop(state);
							state.stack().add(new Val(value.e(), type.desc));
						} else if (opcode == Opcodes.INSTANCEOF) state.stack().add(new Val(new E("instanceof", type.desc, List.of(pop(state).e())), "Z"));
						else throw new IllegalStateException("allocation");
					}
					case MethodInsnNode call -> {
						Type[] arguments = Type.getArgumentTypes(call.desc);
						List<Val> inputs = new ArrayList<>();
						for (int i = arguments.length - 1; i >= 0; i--) inputs.addFirst(pop(state));
						if (opcode != Opcodes.INVOKESTATIC) inputs.addFirst(pop(state));
						AbstractInsnNode after = next(instruction);
						boolean tail = after != null && after.getOpcode() >= Opcodes.IRETURN && after.getOpcode() <= Opcodes.ARETURN;
						Tree result = call(call, inputs, tail, depth);
						if (result == null) throw new IllegalStateException("call");
						if (result instanceof Test && tail) return result;
						Type answer = Type.getReturnType(call.desc);
						if (answer.equals(Type.VOID_TYPE)) continue;
						E value = result instanceof Leaf leaf ? leaf.value() : bool((Test) result);
						if (value == null) value = opaque(call, inputs);
						state.stack().add(new Val(value, typeName(answer)));
					}
					case JumpInsnNode jump -> {
						if (opcode == Opcodes.GOTO) {
							instruction = jump.label;
							continue;
						}
						if (instructions.indexOf(jump.label) <= instructions.indexOf(jump)) throw new IllegalStateException("loop");
						E condition;
						boolean jumpsWhenHolds;
						switch (opcode) {
							case Opcodes.IFEQ, Opcodes.IFNE -> {
								condition = pop(state).e();
								jumpsWhenHolds = opcode == Opcodes.IFNE;
							}
							case Opcodes.IFNULL, Opcodes.IFNONNULL -> {
								condition = new E("null", "", List.of(pop(state).e()));
								jumpsWhenHolds = opcode == Opcodes.IFNULL;
							}
							case Opcodes.IF_ACMPEQ, Opcodes.IF_ACMPNE -> {
								E right = pop(state).e(), left = pop(state).e();
								condition = new E("same", "", List.of(left, right));
								jumpsWhenHolds = opcode == Opcodes.IF_ACMPEQ;
							}
							case Opcodes.IF_ICMPEQ, Opcodes.IF_ICMPNE -> {
								E right = pop(state).e(), left = pop(state).e();
								condition = new E("equal", "", List.of(left, right));
								jumpsWhenHolds = opcode == Opcodes.IF_ICMPEQ;
							}
							default -> throw new IllegalStateException("comparison");
						}
						Boolean known = constantTruth(condition);
						if (known != null) {
							if (known == jumpsWhenHolds) instruction = jump.label;
							continue;
						}
						Tree holds = walk(body, jumpsWhenHolds ? jump.label : jump.getNext(), state.copy(), depth);
						Tree fails = walk(body, jumpsWhenHolds ? jump.getNext() : jump.label, state.copy(), depth);
						return new Test(condition, holds, fails);
					}
					case LdcInsnNode ldc -> state.stack().add(new Val(new E("const", String.valueOf(ldc.cst), List.of()), ldc.cst instanceof String ? "java/lang/String" : "?"));
					case IntInsnNode number when opcode == Opcodes.BIPUSH || opcode == Opcodes.SIPUSH ->
							state.stack().add(new Val(new E("const", Integer.toString(number.operand), List.of()), "I"));
					default -> {
						if (opcode >= Opcodes.ICONST_M1 && opcode <= Opcodes.ICONST_5) state.stack().add(new Val(new E("const", Integer.toString(opcode - Opcodes.ICONST_0), List.of()), "I"));
						else if (opcode == Opcodes.ACONST_NULL) state.stack().add(new Val(new E("const", "null", List.of()), "null"));
						else if (opcode == Opcodes.POP) pop(state);
						else if (opcode == Opcodes.DUP) {
							Val top = pop(state);
							state.stack().add(top);
							state.stack().add(top);
						} else if (opcode >= Opcodes.IRETURN && opcode <= Opcodes.ARETURN) return new Leaf(pop(state).e());
						else if (opcode == Opcodes.RETURN) return new Leaf(null);
						else if (opcode == Opcodes.IAND || opcode == Opcodes.IOR || opcode == Opcodes.IXOR) {
							E right = pop(state).e(), left = pop(state).e();
							state.stack().add(new Val(new E("op" + opcode, "", List.of(left, right)), "I"));
						} else throw new IllegalStateException("opcode " + opcode);
					}
				}
			}
			throw new IllegalStateException("fell off");
		}

		/** A call: the left-alone call's answer, an inlined body, or the call itself, named by the implementation it reaches. */
		Tree call(MethodInsnNode call, List<Val> inputs, boolean tail, int depth) {
			if (leftAlone != null && call.owner.equals(leftAlone.owner) && call.name.equals(leftAlone.name) && call.desc.equals(leftAlone.desc))
				return new Leaf(CALL);
			CarrierDefaultChains.Body body = inlinable(call, inputs);
			if (body != null && CarrierDefaultChains.simple(body.method()) && depth < DEPTH) {
				Tree inlined = new Symbolic(code, followDefaults, leftAlone).withBudget(budget).run(body.method(), inputs, depth + 1);
				if (inlined != null && (inlined instanceof Leaf || tail || bool((Test) inlined) != null)) return inlined;
			}
			return Type.getReturnType(call.desc).equals(Type.VOID_TYPE) ? null : new Leaf(opaque(call, inputs));
		}

		private Symbolic withBudget(int remaining) {
			this.budget = Math.min(budget, remaining);
			return this;
		}

		/** The implementation {@code call} reaches whatever the receiver: static, private, special, final class; or the inherited default. */
		private CarrierDefaultChains.Body inlinable(MethodInsnNode call, List<Val> inputs) {
			int opcode = call.getOpcode();
			if (opcode == Opcodes.INVOKESTATIC || opcode == Opcodes.INVOKESPECIAL) return CarrierDefaultChains.resolve(call, code);
			String receiverType = inputs.isEmpty() ? null : inputs.getFirst().type();
			ClassNode receiver = receiverType != null && receiverType.contains("/") ? code.apply(receiverType) : null;
			ClassNode owner = code.apply(call.owner);
			MethodNode declaredHere = owner == null ? null : declared(owner, call.name, call.desc);
			if (declaredHere != null && (declaredHere.access & (Opcodes.ACC_PRIVATE | Opcodes.ACC_FINAL)) != 0 && (declaredHere.access & Opcodes.ACC_ABSTRACT) == 0)
				return new CarrierDefaultChains.Body(owner.name, (owner.access & Opcodes.ACC_INTERFACE) != 0, declaredHere);
			if (receiver != null && ((receiver.access & Opcodes.ACC_FINAL) != 0 || followDefaults))
				return CarrierDefaultChains.resolve(new MethodInsnNode(opcode == Opcodes.INVOKEINTERFACE && (receiver.access & Opcodes.ACC_INTERFACE) == 0
						? Opcodes.INVOKEVIRTUAL : opcode, receiver.name, call.name, call.desc, (receiver.access & Opcodes.ACC_INTERFACE) != 0), code);
			if (followDefaults && owner != null) return CarrierDefaultChains.resolve(call, code);
			return null;
		}

		/** A call left as a value, named by the implementation it reaches from the receiver's type where that is known. */
		private E opaque(MethodInsnNode call, List<Val> inputs) {
			String symbol = call.owner + "." + call.name + call.desc;
			if (call.getOpcode() != Opcodes.INVOKESTATIC && !inputs.isEmpty()) {
				String receiverType = inputs.getFirst().type();
				ClassNode receiver = receiverType != null && receiverType.contains("/") ? code.apply(receiverType) : null;
				if (receiver != null) {
					CarrierDefaultChains.Body reached = CarrierDefaultChains.resolve(new MethodInsnNode((receiver.access & Opcodes.ACC_INTERFACE) != 0
							? Opcodes.INVOKEINTERFACE : Opcodes.INVOKEVIRTUAL, receiver.name, call.name, call.desc, (receiver.access & Opcodes.ACC_INTERFACE) != 0), code);
					if (reached != null) symbol = reached.declarer() + "." + call.name + call.desc;
				}
			}
			List<E> in = new ArrayList<>();
			for (Val input : inputs) in.add(input.e());
			return new E("call", symbol, in);
		}

		/** A boolean tree as one condition: {@code c ? 1 : 0} is {@code c}, {@code c ? 0 : 1} its negation; null otherwise. */
		static E bool(Test test) {
			if (!(test.yes() instanceof Leaf yes) || !(test.no() instanceof Leaf no)) return null;
			Boolean a = constant(yes.value()), b = constant(no.value());
			if (a == null || b == null || a.equals(b)) return null;
			return a ? test.cond() : not(test.cond());
		}

		/** The expression {@code value} (read off the native method) as this evaluator writes it, the given native values as inputs. */
		E value(NativeCallChanges.Expr value, Map<NativeCallChanges.Expr, Val> inputs) {
			Val result = val(value, inputs, 0);
			return result == null ? null : result.e();
		}

		private Val val(NativeCallChanges.Expr value, Map<NativeCallChanges.Expr, Val> inputs, int depth) {
			if (inputs.containsKey(value)) return inputs.get(value);
			if (depth > DEPTH) return null;
			switch (value.kind()) {
				case "static-field" -> {
					MixinFit.Member member = MixinFit.parseMember(value.symbol());
					if (member == null) return null;
					return new Val(new E("static", member.owner() + "." + member.name() + ":" + member.desc(), List.of()), typeName(Type.getType(member.desc())));
				}
				case "integer", "constant" -> {
					return null;
				}
				case "call" -> {
					MixinFit.Member member = MixinFit.parseMember(value.symbol());
					if (member == null || member.owner() == null || member.desc() == null) return null;
					List<Val> arguments = new ArrayList<>();
					for (NativeCallChanges.Expr input : value.inputs()) {
						Val argument = val(input, inputs, depth + 1);
						if (argument == null) return null;
						arguments.add(argument);
					}
					ClassNode owner = code.apply(member.owner());
					if (owner == null) return null;
					boolean isStatic = Type.getArgumentTypes(member.desc()).length == arguments.size();
					MethodInsnNode call = new MethodInsnNode(isStatic ? Opcodes.INVOKESTATIC : (owner.access & Opcodes.ACC_INTERFACE) != 0 ? Opcodes.INVOKEINTERFACE
							: Opcodes.INVOKEVIRTUAL, member.owner(), member.name(), member.desc(), (owner.access & Opcodes.ACC_INTERFACE) != 0);
					Tree result = call(call, arguments, false, depth);
					E e = result instanceof Leaf leaf ? leaf.value() : result instanceof Test test ? bool(test) : null;
					return e == null ? null : new Val(e, typeName(Type.getReturnType(member.desc())));
				}
				default -> {
					return null;
				}
			}
		}

		private static Boolean constantTruth(E condition) {
			if (condition.op().equals("const") && condition.sym().matches("-?\\d+")) return Integer.parseInt(condition.sym()) != 0;
			return null;
		}

		private static Val pop(State state) {
			if (state.stack().isEmpty()) throw new IllegalStateException("empty stack");
			return state.stack().removeLast();
		}
	}
}
