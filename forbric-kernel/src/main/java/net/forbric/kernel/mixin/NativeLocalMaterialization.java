/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.mixin;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BiFunction;
import java.util.function.Function;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.LocalVariableNode;
import org.objectweb.asm.tree.LookupSwitchInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TableSwitchInsnNode;
import org.objectweb.asm.tree.TryCatchBlockNode;
import org.objectweb.asm.tree.VarInsnNode;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.AnalyzerException;
import org.objectweb.asm.tree.analysis.Frame;
import org.objectweb.asm.tree.analysis.SourceInterpreter;
import org.objectweb.asm.tree.analysis.SourceValue;

import net.forbric.api.Ecosystem;
import net.forbric.kernel.classloading.ForbricClassLoader;
import net.forbric.kernel.util.ForbricLog;

/**
 * Gives a value the merged method computes on the stack the local the native method keeps it in, where a mod's
 * {@code @Local} asks for that local at a call the value is handed to.
 *
 * <p>Vanilla often names a value before handing it on — {@code ItemAttributeModifiers modifiers = getOrDefault(...);
 * modifiers.forEach(slot, consumer);} — where a carrier hands the same call's result straight over:
 * {@code getAttributeModifiers().forEach(slot, consumer)}. The call is there, so an injector anchored on it binds, but a
 * {@code @Local ItemAttributeModifiers} has nothing to read and MixinExtras refuses the injection.
 *
 * <p>Nothing here knows a mod, a class or a method by name. A value is given its local when, for one injector with one
 * call point and no slice:
 * <ul>
 *   <li>the method its selectors bind is the same method natively (the class its ecosystem's mods were compiled against)
 *       and merged, and its point selects as many calls in both, the occurrence it picks being the same one;</li>
 *   <li>a {@code @Local} of the handler resolves, as MixinExtras resolves it, to one local at the native call, and that
 *       local is what the native call is handed as one of its operands, loaded from it with nothing in between able to
 *       change it ({@link MixinLocalOriginProof#atCall});</li>
 *   <li>at the merged call no local resolves for it, and the same operand is the result of one instruction in the same
 *       straight stretch (no jump or jump target between it and the call), of the type the local is declared with.</li>
 * </ul>
 * The merged method then also stores that result, right where it is computed, into a new local named and typed as the
 * native one, visible from there to just after the call. The stack, and what the method does, are unchanged.
 *
 * <p>The plan is registered before the target is defined, so later adapters and Mixin read the method with the local. {@code -Dforbric.nativeLocalMaterialization=off} plans nothing.
 */
public final class NativeLocalMaterialization {
	public static final String PROPERTY = "forbric.nativeLocalMaterialization";

	/** In {@code method}, the {@code occurrence}-th call of {@code member}: its operand {@code operand} gets a local. */
	record Plan(String host, String method, String member, int opcode, int occurrence, int operand, String name, String desc,
			String source, String handler) {
		boolean same(Plan other) {
			return host.equals(other.host) && method.equals(other.method) && member.equals(other.member) && opcode == other.opcode
					&& occurrence == other.occurrence && operand == other.operand;
		}
	}

	private static final Map<ClassLoader, List<Plan>> PLANS = Collections.synchronizedMap(new WeakHashMap<>());

	private NativeLocalMaterialization() {
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

	/** Plans and registers the locals {@code mixin}'s injectors ask for; returns how many it registered. */
	public static int adapt(ClassNode mixin, Function<String, ClassNode> code, BiFunction<Ecosystem, String, ClassNode> natives,
			ForbricClassLoader loader) {
		if (!enabled() || mixin == null || loader == null) return 0;
		Ecosystem ecosystem = MixinStubRebind.ecosystemOf(mixin.name);
		if (ecosystem == null) return 0;
		int planned = 0;
		for (String target : MixinFit.mixinTargets(mixin)) {
			ClassNode original = natives.apply(ecosystem, target), merged = null;
			if (original == null) continue;
			for (MethodNode handler : mixin.methods) {
				AnnotationNode injector = MixinFit.injectorOf(handler);
				if (injector == null || MixinFit.value(injector, "slice") != null) continue;
				if (!hasLocal(handler)) continue;
				if (merged == null) merged = code.apply(target);
				if (merged == null) break;
				for (Plan plan : derive(merged, original, handler, injector, mixin.name)) {
					if (register(loader, plan)) {
						planned++;
						merged = null;
						ForbricLog.info("[Forbric/Mixin] %s.%s: the merged %s.%s hands %s's operand %d over without the local %s %s %s keeps it "
								+ "in; it now keeps it there too, so the @Local reads it", mixin.name.replace('/', '.'), handler.name,
								target.replace('/', '.'), plan.method(), plan.member(), plan.operand(), Type.getType(plan.desc()).getClassName(),
								plan.name(), ecosystem);
					}
				}
			}
		}
		return planned;
	}

	private static boolean hasLocal(MethodNode handler) {
		int count = Type.getArgumentTypes(handler.desc).length;
		for (int i = 0; i < count; i++) if (MixinStubRebind.sugar(handler, i, MixinRetarget.LOCAL_SUGAR) != null) return true;
		return false;
	}

	/** The locals one handler asks for that the merged method could keep and does not. */
	static List<Plan> derive(ClassNode merged, ClassNode original, MethodNode handler, AnnotationNode injector, String source) {
		List<Plan> out = new ArrayList<>();
		List<AnnotationNode> points = MixinFit.atNodes(injector);
		if (points.size() != 1) return out;
		AnnotationNode at = points.getFirst();
		String kind = MixinFit.asString(MixinFit.value(at, "value"));
		if (!"INVOKE".equals(kind) && !"INVOKE_ASSIGN".equals(kind)) return out;
		MethodNode was = MixinTargetSelectors.one(handler, original), host = MixinTargetSelectors.one(handler, merged);
		if (was == null || host == null || !was.name.equals(host.name) || !was.desc.equals(host.desc)) return out;
		List<MethodInsnNode> before = MixinCallbackShape.calls(at, was), after = MixinCallbackShape.calls(at, host);
		if (before.isEmpty() || before.size() != after.size()) return out;
		int ordinal = MixinFit.value(at, "ordinal") instanceof Number number ? number.intValue() : -1;
		List<Integer> picked = new ArrayList<>();
		if (ordinal >= 0) { if (ordinal < before.size()) picked.add(ordinal); } else for (int i = 0; i < before.size(); i++) picked.add(i);
		Type[] parameters = Type.getArgumentTypes(handler.desc);
		for (int p = 0; p < parameters.length; p++) {
			AnnotationNode local = MixinStubRebind.sugar(handler, p, MixinRetarget.LOCAL_SUGAR);
			if (local == null || parameters[p].getSort() != Type.OBJECT || parameters[p].getInternalName().startsWith("com/llamalad7/"))
				continue;
			List<Plan> forLocal = new ArrayList<>();
			for (int index : picked) {
				Plan plan = plan(original.name, was, before.get(index), merged.name, host, after.get(index), local, parameters[p], source, handler.name);
				if (plan == null) { forLocal.clear(); break; }
				forLocal.add(plan);
			}
			if (!forLocal.isEmpty() && forLocal.stream().map(Plan::operand).distinct().count() == 1) out.addAll(forLocal);
		}
		return out;
	}

	private static Plan plan(String nativeOwner, MethodNode was, MethodInsnNode nativeCall, String owner, MethodNode host,
			MethodInsnNode mergedCall, AnnotationNode local, Type type, String source, String handler) {
		int nativeAt = was.instructions.indexOf(nativeCall), mergedAt = host.instructions.indexOf(mergedCall);
		int slot = MixinLocalOriginProof.slot(local, type, was, nativeAt);
		if (slot < 0 || MixinLocalOriginProof.slot(local, type, host, mergedAt) >= 0) return null;
		MixinLocalOriginProof.CallValue value = MixinLocalOriginProof.atCall(nativeOwner, was, nativeCall, slot);
		if (value == null || value.operand() < 0) return null;
		LocalVariableNode declared = declared(was, slot, nativeAt);
		if (declared == null || !Type.getType(declared.desc).equals(type)) return null;
		Frame<SourceValue>[] frames = frames(owner, host);
		Frame<SourceValue> frame = frames == null ? null : frames[mergedAt];
		int operands = Type.getArgumentTypes(mergedCall.desc).length + (mergedCall.getOpcode() == Opcodes.INVOKESTATIC ? 0 : 1);
		if (frame == null || frame.getStackSize() < operands) return null;
		SourceValue handed = frame.getStack(frame.getStackSize() - operands + value.operand());
		if (handed.insns.size() != 1) return null;
		AbstractInsnNode producer = handed.insns.iterator().next();
		if (producer instanceof VarInsnNode || producer.getOpcode() == Opcodes.DUP) return null;
		if (!(producer instanceof MethodInsnNode made) || !Type.getReturnType(made.desc).equals(type)) return null;
		if (!straight(host, producer, mergedCall)) return null;
		return new Plan(owner, host.name + host.desc, member(mergedCall), mergedCall.getOpcode(), occurrence(host, mergedCall), value.operand(),
				declared.name, declared.desc, source, handler);
	}

	private static LocalVariableNode declared(MethodNode method, int slot, int at) {
		if (method.localVariables == null) return null;
		for (LocalVariableNode local : method.localVariables) {
			if (local.index != slot) continue;
			int start = method.instructions.indexOf(local.start), end = method.instructions.indexOf(local.end);
			if (start <= at && at < end) return local;
		}
		return null;
	}

	private static boolean straight(MethodNode method, AbstractInsnNode from, AbstractInsnNode to) {
		Set<LabelNode> targets = Collections.newSetFromMap(new IdentityHashMap<>());
		for (AbstractInsnNode instruction : method.instructions) {
			if (instruction instanceof JumpInsnNode jump) targets.add(jump.label);
			else if (instruction instanceof TableSwitchInsnNode table) { targets.add(table.dflt); targets.addAll(table.labels); }
			else if (instruction instanceof LookupSwitchInsnNode lookup) { targets.add(lookup.dflt); targets.addAll(lookup.labels); }
		}
		for (TryCatchBlockNode block : method.tryCatchBlocks) targets.add(block.handler);
		for (AbstractInsnNode at = from.getNext(); at != null; at = at.getNext()) {
			if (at == to) return true;
			if (at instanceof LabelNode label && targets.contains(label)) return false;
			if (at instanceof JumpInsnNode || at instanceof TableSwitchInsnNode || at instanceof LookupSwitchInsnNode) return false;
			int opcode = at.getOpcode();
			if (opcode >= Opcodes.IRETURN && opcode <= Opcodes.RETURN || opcode == Opcodes.ATHROW) return false;
		}
		return false;
	}

	private static boolean register(ForbricClassLoader loader, Plan plan) {
		List<Plan> plans = plans(loader);
		for (Plan existing : plans) if (existing.same(plan)) return false;
		return loader.registerBeforeDefinition(plan.host().replace('/', '.'), () -> plans.add(plan));
	}

	/** {@code binary}'s pre-mixin bytes with every planned local kept. Unchanged where no plan names the class. */
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
			MethodNode method = method(node, plan.method());
			MethodInsnNode call = method == null ? null : nth(method, plan.member(), plan.opcode(), plan.occurrence());
			if (call == null || !keep(node.name, method, call, plan)) {
				ForbricLog.warn("[Forbric/Mixin] %s.%s: %s.%s no longer hands %s its operand %d as planned; no local is kept",
						plan.source().replace('/', '.'), plan.handler(), binary, plan.method(), plan.member(), plan.operand());
				continue;
			}
			changed = true;
		}
		if (!changed) return bytes;
		ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
		node.accept(writer);
		return writer.toByteArray();
	}

	private static boolean keep(String owner, MethodNode method, MethodInsnNode call, Plan plan) {
		Frame<SourceValue>[] frames = frames(owner, method);
		int at = method.instructions.indexOf(call);
		Frame<SourceValue> frame = frames == null || at < 0 ? null : frames[at];
		int operands = Type.getArgumentTypes(call.desc).length + (call.getOpcode() == Opcodes.INVOKESTATIC ? 0 : 1);
		if (frame == null || frame.getStackSize() < operands) return false;
		SourceValue handed = frame.getStack(frame.getStackSize() - operands + plan.operand());
		if (handed.insns.size() != 1 || !(handed.insns.iterator().next() instanceof MethodInsnNode producer)
				|| !Type.getReturnType(producer.desc).equals(Type.getType(plan.desc())) || !straight(method, producer, call)) return false;
		int slot = method.maxLocals;
		LabelNode start = new LabelNode(), end = new LabelNode();
		InsnList store = new InsnList();
		store.add(new InsnNode(Opcodes.DUP));
		store.add(new VarInsnNode(Opcodes.ASTORE, slot));
		store.add(start);
		method.instructions.insert(producer, store);
		method.instructions.insert(call, end);
		if (method.localVariables == null) method.localVariables = new ArrayList<>();
		method.localVariables.add(new LocalVariableNode(plan.name(), plan.desc(), null, start, end, slot));
		method.maxLocals = slot + 1;
		method.maxStack = method.maxStack + 1;
		return true;
	}

	private static MethodNode method(ClassNode node, String nameAndDesc) {
		for (MethodNode method : node.methods) if ((method.name + method.desc).equals(nameAndDesc)) return method;
		return null;
	}

	private static String member(MethodInsnNode call) {
		return "L" + call.owner + ";" + call.name + call.desc;
	}

	private static int occurrence(MethodNode body, MethodInsnNode call) {
		int n = 0;
		for (AbstractInsnNode instruction : body.instructions) {
			if (instruction == call) return n;
			if (instruction instanceof MethodInsnNode other && other.getOpcode() == call.getOpcode() && member(other).equals(member(call))) n++;
		}
		return -1;
	}

	private static MethodInsnNode nth(MethodNode body, String member, int opcode, int occurrence) {
		int n = 0;
		for (AbstractInsnNode instruction : body.instructions) {
			if (instruction instanceof MethodInsnNode call && call.getOpcode() == opcode && member(call).equals(member)) {
				if (n == occurrence) return call;
				n++;
			}
		}
		return null;
	}

	@SuppressWarnings("unchecked")
	private static Frame<SourceValue>[] frames(String owner, MethodNode method) {
		try {
			return new Analyzer<>(new SourceInterpreter()).analyze(owner, method);
		} catch (AnalyzerException | RuntimeException unanalysable) {
			return null;
		}
	}
}
