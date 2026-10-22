/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.mixin;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.function.Function;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.LookupSwitchInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TableSwitchInsnNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.AnalyzerException;
import org.objectweb.asm.tree.analysis.Frame;
import org.objectweb.asm.tree.analysis.SourceInterpreter;
import org.objectweb.asm.tree.analysis.SourceValue;

import net.forbric.api.Ecosystem;
import net.forbric.kernel.util.ByteScan;
import net.forbric.kernel.util.Caught;
import net.forbric.kernel.util.ForbricLog;

/**
 * Hands a value a guest changed to the carrier's consumer of that value, where the carrier replaced the consumer the value
 * has natively and the merged method now computes it for nothing.
 *
 * <p>Vanilla's {@code TitleScreen.extractRenderState} builds its version string — {@code "Minecraft " + version}, a demo
 * suffix, the modded suffix — and draws it bottom-left with {@code GuiGraphicsExtractor.text}. NeoForge keeps the string
 * and draws its {@code BrandingControl} lines instead, through a lambda that calls the same {@code text}; its first line is
 * the same {@code "Minecraft " + version}, computed on its own. Mod Menu changes the modded suffix to "(67 Mods)": on the
 * merged screen its handler ran every frame and the string it built was stored and never read. The title screen kept
 * saying "Minecraft 26.2".
 *
 * <p>Post-Mixin, by the woven class's shape and the method the guest's platform has; nothing here knows a mod, a class or
 * a method by name. A method is handled when, all proved:
 * <ul>
 *   <li>a local of it is stored and never read for anything but its own next value — dead after its last store — and no
 *       jump goes backward in the method (no loop can bring a read round);</li>
 *   <li>the value stored there is computed, through its def chain, by a call of a handler Mixin merged into the class
 *       ({@code @MixinMerged}): a guest changed it; without one nothing is done, and the carrier's screen stays as the carrier
 *       draws it;</li>
 *   <li>the same method of the class the guest's platform has (its native reference) stores a local whose first value is the
 *       same expression, and reads its final value exactly once, as an argument of a call — the native consumer;</li>
 *   <li>after the last store, the merged method creates lambdas of its own class that make that same call with that
 *       argument taken from the lambda's own parameters (what the carrier hands it, item by item), and hands each lambda to a
 *       call outside the class — the carrier's replacement of the consumer.</li>
 * </ul>
 * Then the first value is recorded, the final one offered around each such carrier call, and in each lambda the consumer's
 * argument goes through {@link net.forbric.kernel.util.NativeValueRelay#substitute}: the one item equal to the first value —
 * the carrier's own computation of it — is drawn as the guest made it, once per run of the method, as the native consumer
 * drew it once; nothing else changes. Stack-neutral
 * inserts, no new locals or branch targets. {@code -Dforbric.nativeDeadValueConsumers=off} leaves the class as Mixin wove it.
 */
public final class NativeDeadValueConsumers {
	public static final String PROPERTY = "forbric.nativeDeadValueConsumers";
	static final String RELAY = "net/forbric/kernel/util/NativeValueRelay";
	private static final String MERGED = "Lorg/spongepowered/asm/mixin/transformer/meta/MixinMerged;";
	private static final byte[] MERGED_NEEDLE = ByteScan.needle("org/spongepowered/asm/mixin/transformer/meta/MixinMerged");
	private static final String METAFACTORY = "java/lang/invoke/LambdaMetafactory";

	private NativeDeadValueConsumers() {
	}

	static boolean enabled() {
		return !"off".equalsIgnoreCase(System.getProperty(PROPERTY, "on"));
	}

	/** {@code bytes} as Mixin wove them, or with every proved dead value handed to its carrier consumer. */
	public static byte[] apply(String internal, byte[] bytes, BiFunction<Ecosystem, String, ClassNode> natives,
			Function<String, Ecosystem> ecosystems) {
		if (!enabled() || bytes == null || natives == null || ecosystems == null || !ByteScan.contains(bytes, MERGED_NEEDLE)) return bytes;
		try {
			ClassNode node = new ClassNode();
			new ClassReader(bytes).accept(node, 0);
			if (!handle(node, natives, ecosystems)) return bytes;
			ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
			node.accept(writer);
			return writer.toByteArray();
		} catch (RuntimeException failure) {
			Caught.warn(failure, "[Forbric/Mixin] NativeDeadValueConsumers left %s as Mixin wove it", internal);
			return bytes;
		}
	}

	/** What one handled method needs rewritten. */
	private record Plan(MethodNode method, VarInsnNode firstStore, int slot, List<Twin> twins, String mixin) {
	}

	/** One carrier consumer: the lambda, the producer of the consumer's argument in it, the argument's type, and the carrier call. */
	private record Twin(MethodNode lambda, AbstractInsnNode argument, Type type, MethodInsnNode carrierCall) {
	}

	static boolean handle(ClassNode node, BiFunction<Ecosystem, String, ClassNode> natives, Function<String, Ecosystem> ecosystems) {
		Set<String> handlers = new HashSet<>();
		java.util.Map<String, String> mixinOf = new java.util.HashMap<>();
		for (MethodNode method : node.methods) {
			String mixin = mergedFrom(method);
			if (mixin == null) continue;
			handlers.add(method.name + method.desc);
			mixinOf.put(method.name + method.desc, mixin);
		}
		if (handlers.isEmpty()) return false;
		List<Plan> plans = new ArrayList<>();
		for (MethodNode method : node.methods) {
			if (handlers.contains(method.name + method.desc) || method.instructions == null || method.instructions.size() == 0) continue;
			Plan plan = plan(node, method, handlers, mixinOf, natives, ecosystems);
			if (plan != null) plans.add(plan);
		}
		for (Plan plan : plans) rewrite(node, plan);
		return !plans.isEmpty();
	}

	private static Plan plan(ClassNode node, MethodNode method, Set<String> handlers, java.util.Map<String, String> mixinOf,
			BiFunction<Ecosystem, String, ClassNode> natives, Function<String, Ecosystem> ecosystems) {
		AbstractInsnNode[] code = method.instructions.toArray();
		boolean guest = false;
		for (int i = 0; i < code.length; i++) {
			if (code[i] instanceof JumpInsnNode jump && method.instructions.indexOf(jump.label) < i) return null;
			guest |= code[i] instanceof MethodInsnNode call && call.owner.equals(node.name) && handlers.contains(call.name + call.desc);
		}
		if (!guest) return null;   // no handler is called here: nothing a guest computed can be in it
		Frame<SourceValue>[] frames = frames(node.name, method);
		if (frames == null) return null;
		Set<Integer> slots = new LinkedHashSet<>();
		for (AbstractInsnNode insn : code) if (insn instanceof VarInsnNode store && store.getOpcode() == Opcodes.ASTORE) slots.add(store.var);
		for (int slot : slots) {
			// The slot's reference accesses: a primitive the compiler kept in the same slot elsewhere is another variable.
			List<VarInsnNode> stores = new ArrayList<>(), loads = new ArrayList<>();
			for (AbstractInsnNode insn : code) {
				if (!(insn instanceof VarInsnNode access) || access.var != slot) continue;
				if (access.getOpcode() == Opcodes.ASTORE) stores.add(access);
				else if (access.getOpcode() == Opcodes.ALOAD) loads.add(access);
			}
			// The def chains of every store: what each stored value was computed from, within the method.
			Set<AbstractInsnNode> chain = new HashSet<>();
			for (VarInsnNode store : stores) slice(method, frames, store, chain);
			if (!chain.containsAll(loads)) continue;   // read for something besides its own next value
			String mixin = null;
			for (AbstractInsnNode insn : chain) {
				if (insn instanceof MethodInsnNode call && call.owner.equals(node.name) && handlers.contains(call.name + call.desc)) {
					mixin = mixinOf.get(call.name + call.desc);
					break;
				}
			}
			if (mixin == null) continue;   // no guest changed it
			Ecosystem ecosystem = ecosystems.apply(mixin.replace('.', '/'));
			ClassNode reference = ecosystem == null ? null : natives.apply(ecosystem, node.name);
			MethodNode nativeMethod = reference == null ? null : method(reference, method.name, method.desc);
			if (nativeMethod == null) continue;
			VarInsnNode first = stores.getFirst();
			Consumer consumer = nativeConsumer(reference, nativeMethod, expression(method, frames, stored(frames, method, first), 0));
			if (consumer == null) continue;
			List<Twin> twins = twins(node, method, frames, stores.getLast(), consumer.call(), consumer.argument());
			if (twins.isEmpty()) continue;
			return new Plan(method, first, slot, twins, mixin);
		}
		return null;
	}

	/** The native consumer: the call that reads the value, and which of its arguments the value is. */
	private record Consumer(MethodInsnNode call, int argument) {
	}

	/** The call that reads, once, the final value of the native local whose first value is {@code expression}; null otherwise. */
	private static Consumer nativeConsumer(ClassNode reference, MethodNode method, String expression) {
		Frame<SourceValue>[] frames = frames(reference.name, method);
		if (frames == null || expression == null) return null;
		Consumer found = null;
		Set<Integer> seen = new HashSet<>();
		for (AbstractInsnNode insn : method.instructions) {
			if (!(insn instanceof VarInsnNode first) || first.getOpcode() != Opcodes.ASTORE || !seen.add(first.var)) continue;
			if (!expression.equals(expression(method, frames, stored(frames, method, first), 0))) continue;
			List<VarInsnNode> stores = new ArrayList<>(), loads = new ArrayList<>();
			for (AbstractInsnNode access : method.instructions) {
				if (!(access instanceof VarInsnNode variable) || variable.var != first.var) continue;
				if (variable.getOpcode() == Opcodes.ASTORE) stores.add(variable);
				else if (variable.getOpcode() == Opcodes.ALOAD) loads.add(variable);
			}
			Set<AbstractInsnNode> chain = new HashSet<>();
			for (VarInsnNode store : stores) slice(method, frames, store, chain);
			List<VarInsnNode> consumed = loads.stream().filter(load -> !chain.contains(load)).toList();
			if (consumed.size() != 1) return null;
			List<OperandStack.Use> uses = OperandStack.uses(method, frames, consumed.getFirst());
			if (uses.size() != 1 || !(uses.getFirst().instruction() instanceof MethodInsnNode call)) return null;
			int arguments = Type.getArgumentTypes(call.desc).length, fromTop = uses.getFirst().fromTop();
			if (fromTop >= arguments || Type.getArgumentTypes(call.desc)[arguments - 1 - fromTop].getSort() != Type.OBJECT) return null;
			if (found != null) return null;
			found = new Consumer(call, arguments - 1 - fromTop);
		}
		return found;
	}

	/**
	 * The lambdas the merged method creates after {@code lastStore} that make {@code consumer}'s call with its argument taken
	 * from their own (not captured) parameters, each handed to a call outside the class.
	 */
	private static List<Twin> twins(ClassNode node, MethodNode method, Frame<SourceValue>[] frames, VarInsnNode lastStore, MethodInsnNode consumer,
			int argument) {
		List<Twin> out = new ArrayList<>();
		int after = method.instructions.indexOf(lastStore);
		for (AbstractInsnNode insn : method.instructions) {
			if (!(insn instanceof InvokeDynamicInsnNode indy) || method.instructions.indexOf(indy) < after || !METAFACTORY.equals(indy.bsm.getOwner())
					|| indy.bsmArgs.length < 2 || !(indy.bsmArgs[1] instanceof Handle implementation) || !implementation.getOwner().equals(node.name)) continue;
			MethodNode lambda = method(node, implementation.getName(), implementation.getDesc());
			if (lambda == null || lambda.instructions == null) continue;
			List<OperandStack.Use> uses = OperandStack.uses(method, frames, indy);
			if (uses.size() != 1 || !(uses.getFirst().instruction() instanceof MethodInsnNode carrier) || carrier.owner.equals(node.name)) continue;
			boolean receiver = implementation.getTag() != Opcodes.H_INVOKESTATIC;
			int captured = Type.getArgumentTypes(indy.desc).length - (receiver ? 1 : 0);
			Frame<SourceValue>[] inside = frames(node.name, lambda);
			if (inside == null) continue;
			Type[] parameters = Type.getArgumentTypes(lambda.desc);
			for (AbstractInsnNode call : lambda.instructions) {
				if (!(call instanceof MethodInsnNode twin) || !twin.owner.equals(consumer.owner) || !twin.name.equals(consumer.name)
						|| !twin.desc.equals(consumer.desc) || twin.getOpcode() != consumer.getOpcode()) continue;
				Frame<SourceValue> frame = inside[lambda.instructions.indexOf(twin)];
				int arguments = Type.getArgumentTypes(twin.desc).length;
				if (frame == null) continue;
				SourceValue value = frame.getStack(frame.getStackSize() - arguments + argument);
				if (value.insns.size() != 1 || !(value.insns.iterator().next() instanceof VarInsnNode load) || load.getOpcode() != Opcodes.ALOAD) continue;
				int parameter = parameterAt(lambda, parameters, load.var);
				if (parameter < captured || writes(lambda, load.var)) continue;   // a captured value, or not the carrier's item
				out.add(new Twin(lambda, load, Type.getArgumentTypes(twin.desc)[argument], carrier));
			}
		}
		return out;
	}

	private static void rewrite(ClassNode node, Plan plan) {
		InsnList base = new InsnList();
		base.add(new VarInsnNode(Opcodes.ALOAD, plan.slot()));
		base.add(new MethodInsnNode(Opcodes.INVOKESTATIC, RELAY, "base", "(Ljava/lang/Object;)V", false));
		plan.method().instructions.insert(plan.firstStore(), base);
		Set<MethodInsnNode> carriers = new LinkedHashSet<>();
		Set<AbstractInsnNode> arguments = new HashSet<>();
		for (Twin twin : plan.twins()) {
			carriers.add(twin.carrierCall());
			if (!arguments.add(twin.argument())) continue;
			InsnList substitute = new InsnList();
			substitute.add(new MethodInsnNode(Opcodes.INVOKESTATIC, RELAY, "substitute", "(Ljava/lang/Object;)Ljava/lang/Object;", false));
			substitute.add(new TypeInsnNode(Opcodes.CHECKCAST, twin.type().getInternalName()));
			twin.lambda().instructions.insert(twin.argument(), substitute);
		}
		for (MethodInsnNode carrier : carriers) {
			InsnList offer = new InsnList();
			offer.add(new VarInsnNode(Opcodes.ALOAD, plan.slot()));
			offer.add(new MethodInsnNode(Opcodes.INVOKESTATIC, RELAY, "offer", "(Ljava/lang/Object;)V", false));
			plan.method().instructions.insertBefore(carrier, offer);
			plan.method().instructions.insert(carrier, new MethodInsnNode(Opcodes.INVOKESTATIC, RELAY, "close", "()V", false));
		}
		ForbricLog.info("[Forbric/Mixin] %s.%s: the value %s's handler changed is stored and never read — the carrier replaced its native "
				+ "consumer — and now reaches the carrier's own %s, in place of the one item equal to its first value (%d carrier call(s))",
				node.name.replace('/', '.'), plan.method().name, plan.mixin(),
				plan.twins().stream().map(t -> t.lambda().name).distinct().toList(), carriers.size());
	}

	/** The mixin a method was merged from ({@code @MixinMerged}), or null. */
	private static String mergedFrom(MethodNode method) {
		for (List<AnnotationNode> list : java.util.Arrays.asList(method.visibleAnnotations, method.invisibleAnnotations)) {
			if (list == null) continue;
			for (AnnotationNode annotation : list) if (MERGED.equals(annotation.desc)) return MixinFit.asString(MixinFit.value(annotation, "mixin"));
		}
		return null;
	}

	/**
	 * Adds to {@code chain} every instruction whose value reaches {@code consumer}'s operands within the method, following a
	 * load of a local into the stores that reach it.
	 */
	private static void slice(MethodNode method, Frame<SourceValue>[] frames, AbstractInsnNode consumer, Set<AbstractInsnNode> chain) {
		java.util.ArrayDeque<AbstractInsnNode> work = new java.util.ArrayDeque<>();
		work.add(consumer);
		while (!work.isEmpty()) {
			AbstractInsnNode at = work.poll();
			int index = method.instructions.indexOf(at);
			Frame<SourceValue> frame = index < 0 ? null : frames[index];
			if (frame == null) continue;
			if (at instanceof VarInsnNode load && load.getOpcode() >= Opcodes.ILOAD && load.getOpcode() <= Opcodes.ALOAD) {
				for (AbstractInsnNode store : frame.getLocal(load.var).insns) if (chain.add(store)) work.add(store);
				continue;
			}
			int pops = Math.min(OperandStack.pops(at), frame.getStackSize());
			for (int k = 0; k < pops; k++)
				for (AbstractInsnNode source : frame.getStack(frame.getStackSize() - 1 - k).insns) if (chain.add(source)) work.add(source);
		}
	}

	private static SourceValue stored(Frame<SourceValue>[] frames, MethodNode method, AbstractInsnNode store) {
		Frame<SourceValue> frame = frames[method.instructions.indexOf(store)];
		return frame == null || frame.getStackSize() == 0 ? null : frame.getStack(frame.getStackSize() - 1);
	}

	/**
	 * A value as the expression that computes it over the method's own values, comparable across methods: calls and
	 * dynamic calls (with their bootstrap's constant arguments, so two string templates differ), fields, constants, casts,
	 * and a local by the value its one reaching store wrote; parameters by position.
	 */
	static String expression(MethodNode method, Frame<SourceValue>[] frames, SourceValue value, int depth) {
		if (value == null || depth > 10) return "?";
		if (value.insns.size() != 1) return value.insns.isEmpty() ? "?" : "join";
		AbstractInsnNode producer = value.insns.iterator().next();
		Frame<SourceValue> frame = frames[method.instructions.indexOf(producer)];
		if (frame == null) return "?";
		if (producer instanceof VarInsnNode load && load.getOpcode() >= Opcodes.ILOAD && load.getOpcode() <= Opcodes.ALOAD) {
			SourceValue local = frame.getLocal(load.var);
			if (local.insns.isEmpty()) return "param" + load.var;
			if (local.insns.size() == 1 && local.insns.iterator().next() instanceof VarInsnNode store) return expression(method, frames, stored(frames, method, store), depth + 1);
			return "local";
		}
		StringBuilder text = new StringBuilder();
		if (producer instanceof MethodInsnNode call) text.append(call.owner).append('.').append(call.name).append(call.desc);
		else if (producer instanceof InvokeDynamicInsnNode indy) {
			text.append("indy:").append(indy.bsm.getOwner()).append('.').append(indy.bsm.getName()).append(':').append(indy.name).append(indy.desc);
			for (Object argument : indy.bsmArgs) if (argument instanceof String || argument instanceof Number) text.append('|').append(argument);
		} else if (producer instanceof FieldInsnNode field) text.append(field.getOpcode()).append(field.owner).append('.').append(field.name);
		else if (producer instanceof LdcInsnNode ldc) return "ldc:" + ldc.cst;
		else if (producer instanceof org.objectweb.asm.tree.IntInsnNode number) return "int:" + number.operand;
		else if (producer instanceof TypeInsnNode type) text.append(type.getOpcode()).append(type.desc);
		else text.append("op").append(producer.getOpcode());
		int pops = Math.min(OperandStack.pops(producer), frame.getStackSize());
		text.append('(');
		for (int k = pops - 1; k >= 0; k--) text.append(expression(method, frames, frame.getStack(frame.getStackSize() - 1 - k), depth + 1)).append(k == 0 ? "" : ",");
		return text.append(')').toString();
	}

	/** Which parameter (0-based, receiver excluded) local {@code slot} of {@code lambda} is; -1 when it is no parameter. */
	private static int parameterAt(MethodNode lambda, Type[] parameters, int slot) {
		int at = (lambda.access & Opcodes.ACC_STATIC) == 0 ? 1 : 0;
		for (int i = 0; i < parameters.length; i++) {
			if (at == slot) return i;
			at += parameters[i].getSize();
		}
		return -1;
	}

	private static boolean writes(MethodNode method, int slot) {
		for (AbstractInsnNode insn : method.instructions)
			if (insn instanceof VarInsnNode store && store.var == slot && store.getOpcode() >= Opcodes.ISTORE && store.getOpcode() <= Opcodes.ASTORE) return true;
		return false;
	}

	@SuppressWarnings("unchecked")
	private static Frame<SourceValue>[] frames(String owner, MethodNode method) {
		try {
			return new Analyzer<>(new SourceInterpreter()).analyze(owner, method);
		} catch (AnalyzerException invalid) {
			Caught.expected(invalid);
			return null;
		}
	}

	private static MethodNode method(ClassNode node, String name, String desc) {
		for (MethodNode method : node.methods) if (method.name.equals(name) && method.desc.equals(desc)) return method;
		return null;
	}

}
