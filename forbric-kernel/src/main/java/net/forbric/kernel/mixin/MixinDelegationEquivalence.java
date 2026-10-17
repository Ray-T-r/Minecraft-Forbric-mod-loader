/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.mixin;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

import org.objectweb.asm.Handle;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.IincInsnNode;
import org.objectweb.asm.tree.IntInsnNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.LookupSwitchInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.MultiANewArrayInsnNode;
import org.objectweb.asm.tree.TableSwitchInsnNode;
import org.objectweb.asm.tree.TryCatchBlockNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

/**
 * A value modifier the merged method already applies: report-only proof that an argument modifier which found no call to
 * attach to is no loss, because the method its selector binds hands the same-named call the same computation of the same
 * value. No guest, game or handler name is listed, and no body hash.
 *
 * <p>The handler (a {@code @ModifyArg} on one {@code INVOKE} point) must be a type-guarded delegation, read by its data
 * flow: it answers {@code F((T) value)} when its argument is a {@code T}, and the argument unchanged otherwise, calling
 * nothing else. The method its selector binds — or, when that is a forwarding stub, the overload it forwards to — must make
 * exactly one call of the point's owner and name whose modified argument is the answer of a static method {@code G} taking
 * a {@code T}: the guard then always holds there, and the native answer was {@code F(value)}. {@code G} must be
 * {@code F}'s equal: the same instructions, constants and handlers, calling the same members, where a call into
 * {@code F}'s own class or its nested classes is a call into {@code G}'s at the same name, compared in turn — a
 * javac-generated switch map included. NeoForge's thread-dump renderer and fabric-crash-report-info's are such a pair.
 */
final class MixinDelegationEquivalence {
	private static final String MODIFY_ARG = "Lorg/spongepowered/asm/mixin/injection/ModifyArg;";
	private static final Map<String, Plan> PLANS = new ConcurrentHashMap<>();

	private MixinDelegationEquivalence() { }

	/** One remembered type-guarded delegation: the handler as written, the delegate it answers with, and the guard type. */
	record Plan(MethodNode handler, MethodInsnNode delegate, String guard, String pointOwner, String pointName, int index) { }

	static void reset() { PLANS.clear(); }

	/** Remembers every type-guarded value modifier of {@code mixin}, as the final-application ledger reads it. */
	static void remember(ClassNode mixin) {
		String prefix = mixin.name.replace('/', '.') + "#";
		PLANS.keySet().removeIf(key -> key.startsWith(prefix));
		for (MethodNode handler : mixin.methods) {
			Plan plan = plan(mixin.name, handler);
			if (plan != null) PLANS.put(prefix + handler.name + handler.desc, plan);
		}
	}

	/** Whether {@code handler} is a type-guarded delegation this proof can be asked about. */
	static boolean understood(String owner, MethodNode handler) {
		return plan(owner, handler) != null;
	}

	private static Plan plan(String owner, MethodNode handler) {
		AnnotationNode injector = MixinFit.injectorOf(handler);
		if (injector == null || !MODIFY_ARG.equals(injector.desc) || MixinFit.value(injector, "slice") != null) return null;
		List<AnnotationNode> points = MixinFit.atNodes(injector);
		if (points.size() != 1 || !"INVOKE".equals(MixinFit.value(points.getFirst(), "value"))) return null;
		MixinFit.Member member = MixinFit.parseMember(MixinFit.asString(MixinFit.value(points.getFirst(), "target")));
		if (member == null || member.owner() == null || member.desc() == null) return null;
		Type[] pointArgs = Type.getArgumentTypes(member.desc());
		Object explicit = MixinFit.value(injector, "index");
		int index = explicit instanceof Integer i ? i : pointArgs.length == 1 ? 0 : -1;
		Type[] args = Type.getArgumentTypes(handler.desc);
		if (index < 0 || index >= pointArgs.length || args.length != 1 || args[0].getSort() != Type.OBJECT
				|| !Type.getReturnType(handler.desc).equals(args[0])) return null;
		int slot = (handler.access & Opcodes.ACC_STATIC) != 0 ? 0 : 1;
		HandlerFlow flow = HandlerFlow.of(owner, handler);
		if (flow == null || !flow.acyclic()) return null;
		List<MethodInsnNode> delegates = flow.calls(c -> c.getOpcode() == Opcodes.INVOKESTATIC);
		if (delegates.size() != 1 || !flow.onlyCalls(delegates::contains)) return null;
		MethodInsnNode delegate = delegates.getFirst();
		Type[] delegateArgs = Type.getArgumentTypes(delegate.desc);
		if (delegateArgs.length != 1 || delegateArgs[0].getSort() != Type.OBJECT || !HandlerFlow.onlyParameter(flow.argument(delegate, 0), slot)) return null;
		// Every answer is the delegate's or the argument itself, and the delegate runs only past an instanceof of its type.
		for (AbstractInsnNode answer : flow.returned()) if (answer != delegate && !HandlerFlow.isParameter(answer, slot)) return null;
		boolean guarded = false;
		for (AbstractInsnNode instruction : handler.instructions)
			if (instruction instanceof TypeInsnNode test && test.getOpcode() == Opcodes.INSTANCEOF && test.desc.equals(delegateArgs[0].getInternalName())
					&& HandlerFlow.onlyParameter(flow.operand(test, 0, 1), slot)) guarded = true;
		if (!guarded) return null;
		MethodNode copy = new MethodNode(Opcodes.ASM9, handler.access, handler.name, handler.desc, handler.signature, null);
		handler.accept(copy);
		return new Plan(copy, delegate, delegateArgs[0].getInternalName(), member.owner(), member.name(), index);
	}

	/**
	 * The proof for the remembered handler {@code name + descriptor} of {@code mixin} against the defined {@code target};
	 * null when it does not hold. {@code classes} gives a class by internal name, as the current pipeline holds it.
	 */
	static String proof(String mixin, String name, String descriptor, ClassNode target, Function<String, ClassNode> classes) {
		Plan plan = PLANS.get(mixin.replace('/', '.') + "#" + name + descriptor);
		if (plan == null || classes == null) return null;
		MethodNode host = MixinTargetSelectors.unambiguous(plan.handler(), target);
		if (host == null) return null;
		MethodNode body = forwardedTo(target, host);
		MethodInsnNode replacement = null;
		HandlerFlow flow = HandlerFlow.of(target.name, body);
		if (flow == null) return null;
		for (MethodInsnNode call : flow.calls(c -> c.owner.equals(plan.pointOwner()) && c.name.equals(plan.pointName()))) {
			if (plan.index() >= Type.getArgumentTypes(call.desc).length) continue;
			if (!(HandlerFlow.only(flow.argument(call, plan.index() + (call.getOpcode() == Opcodes.INVOKESTATIC ? 0 : 1))) instanceof MethodInsnNode made)
					|| made.getOpcode() != Opcodes.INVOKESTATIC || !made.desc.equals("(L" + plan.guard() + ";)" + Type.getReturnType(plan.delegate().desc).getDescriptor()))
				continue;
			if (replacement != null) return null;
			replacement = made;
		}
		if (replacement == null) return null;
		ClassNode fabricOwner = classes.apply(plan.delegate().owner), nativeOwner = classes.apply(replacement.owner);
		MethodNode guest = fabricOwner == null ? null : declared(fabricOwner, plan.delegate().name, plan.delegate().desc);
		MethodNode carrier = nativeOwner == null ? null : declared(nativeOwner, replacement.name, replacement.desc);
		if (guest == null || carrier == null) return null;
		Map<String, String> owners = new HashMap<>();
		owners.put(fabricOwner.name, nativeOwner.name);
		if (!new Comparison(classes, owners).same(fabricOwner, guest, nativeOwner, carrier)) return null;
		return "The method the selector binds already hands " + plan.pointName() + " the answer of a static method whose body equals the "
				+ "handler's delegate instruction for instruction (its own nested classes included), on a value of the type the "
				+ "handler's guard admits; the modifier would compute the same argument";
	}

	/** {@code host}, or the overload of its name it forwards to when it only forwards and returns that answer. */
	private static MethodNode forwardedTo(ClassNode owner, MethodNode host) {
		HandlerFlow flow = HandlerFlow.of(owner.name, host);
		if (flow == null) return host;
		List<MethodInsnNode> calls = flow.calls(c -> true);
		List<MethodInsnNode> own = calls.stream().filter(c -> c.owner.equals(owner.name) && c.name.equals(host.name) && !c.desc.equals(host.desc)).toList();
		if (own.size() != 1 || calls.size() != 1 || !flow.returned().equals(Set.of(own.getFirst()))) return host;
		MethodNode delegate = declared(owner, own.getFirst().name, own.getFirst().desc);
		return delegate == null ? host : delegate;
	}

	private static MethodNode declared(ClassNode owner, String name, String desc) {
		for (MethodNode method : owner.methods) if (method.name.equals(name) && method.desc.equals(desc)) return method;
		return null;
	}

	/** Instruction-for-instruction equality of two bodies, a call or field of one side's own classes mapped to the other's. */
	private static final class Comparison {
		private final Function<String, ClassNode> classes;
		private final Map<String, String> owners;
		private final Set<String> compared = new HashSet<>();

		Comparison(Function<String, ClassNode> classes, Map<String, String> owners) {
			this.classes = classes;
			this.owners = owners;
		}

		/** The other side's name for {@code owner}: a mapped class, or a nested class of one at the same suffix; else itself. */
		private String mapped(String owner) {
			String direct = owners.get(owner);
			if (direct != null) return direct;
			for (Map.Entry<String, String> entry : new ArrayList<>(owners.entrySet())) {
				if (owner.startsWith(entry.getKey() + "$")) {
					String nested = entry.getValue() + owner.substring(entry.getKey().length());
					owners.put(owner, nested);
					return nested;
				}
			}
			return owner;
		}

		private boolean ownClass(String owner) {
			return !mapped(owner).equals(owner) || owners.containsKey(owner);
		}

		boolean same(ClassNode aOwner, MethodNode a, ClassNode bOwner, MethodNode b) {
			if (!compared.add(aOwner.name + "." + a.name + a.desc)) return true;
			if ((a.access & ~Opcodes.ACC_SYNTHETIC) != (b.access & ~Opcodes.ACC_SYNTHETIC) || !a.desc.equals(b.desc)) return false;
			List<AbstractInsnNode> x = real(a), y = real(b);
			if (x.size() != y.size() || a.tryCatchBlocks.size() != b.tryCatchBlocks.size()) return false;
			for (int i = 0; i < a.tryCatchBlocks.size(); i++) {
				TryCatchBlockNode p = a.tryCatchBlocks.get(i), q = b.tryCatchBlocks.get(i);
				if (!Objects.equals(p.type == null ? null : mapped(p.type), q.type) || index(x, p.start) != index(y, q.start)
						|| index(x, p.end) != index(y, q.end) || index(x, p.handler) != index(y, q.handler)) return false;
			}
			for (int i = 0; i < x.size(); i++) if (!sameInstruction(x, x.get(i), y, y.get(i))) return false;
			return true;
		}

		private boolean sameInstruction(List<AbstractInsnNode> xs, AbstractInsnNode x, List<AbstractInsnNode> ys, AbstractInsnNode y) {
			if (x.getOpcode() != y.getOpcode() || x.getType() != y.getType()) return false;
			if (x instanceof MethodInsnNode p && y instanceof MethodInsnNode q) {
				if (!mapped(p.owner).equals(q.owner) || !p.name.equals(q.name) || !p.desc.equals(q.desc) || p.itf != q.itf) return false;
				return !ownClass(p.owner) || callee(p.owner, p.name, p.desc, q.owner);
			}
			if (x instanceof FieldInsnNode p && y instanceof FieldInsnNode q) {
				if (!mapped(p.owner).equals(q.owner) || !p.name.equals(q.name) || !p.desc.equals(q.desc)) return false;
				return !ownClass(p.owner) || field(p.owner, p.name, p.desc, q.owner);
			}
			if (x instanceof TypeInsnNode p && y instanceof TypeInsnNode q) return mapped(p.desc).equals(q.desc);
			if (x instanceof VarInsnNode p && y instanceof VarInsnNode q) return p.var == q.var;
			if (x instanceof IincInsnNode p && y instanceof IincInsnNode q) return p.var == q.var && p.incr == q.incr;
			if (x instanceof IntInsnNode p && y instanceof IntInsnNode q) return p.operand == q.operand;
			if (x instanceof LdcInsnNode p && y instanceof LdcInsnNode q) return sameConstant(p.cst, q.cst);
			if (x instanceof JumpInsnNode p && y instanceof JumpInsnNode q) return index(xs, p.label) == index(ys, q.label);
			if (x instanceof TableSwitchInsnNode p && y instanceof TableSwitchInsnNode q) {
				if (p.min != q.min || p.max != q.max || p.labels.size() != q.labels.size() || index(xs, p.dflt) != index(ys, q.dflt)) return false;
				for (int i = 0; i < p.labels.size(); i++) if (index(xs, p.labels.get(i)) != index(ys, q.labels.get(i))) return false;
				return true;
			}
			if (x instanceof LookupSwitchInsnNode p && y instanceof LookupSwitchInsnNode q) {
				if (!p.keys.equals(q.keys) || p.labels.size() != q.labels.size() || index(xs, p.dflt) != index(ys, q.dflt)) return false;
				for (int i = 0; i < p.labels.size(); i++) if (index(xs, p.labels.get(i)) != index(ys, q.labels.get(i))) return false;
				return true;
			}
			if (x instanceof InvokeDynamicInsnNode p && y instanceof InvokeDynamicInsnNode q) {
				if (!p.name.equals(q.name) || !p.desc.equals(q.desc) || !sameConstant(p.bsm, q.bsm) || p.bsmArgs.length != q.bsmArgs.length) return false;
				for (int i = 0; i < p.bsmArgs.length; i++) if (!sameConstant(p.bsmArgs[i], q.bsmArgs[i])) return false;
				return true;
			}
			if (x instanceof MultiANewArrayInsnNode p && y instanceof MultiANewArrayInsnNode q) return mapped(p.desc).equals(q.desc) && p.dims == q.dims;
			return true; // operand-less instructions: the opcode decides
		}

		private boolean sameConstant(Object p, Object q) {
			if (p instanceof Type t && q instanceof Type u) return t.getSort() == Type.OBJECT ? mapped(t.getInternalName()).equals(u.getInternalName()) : t.equals(u);
			if (p instanceof Handle h && q instanceof Handle k) {
				if (h.getTag() != k.getTag() || !mapped(h.getOwner()).equals(k.getOwner()) || !h.getName().equals(k.getName()) || !h.getDesc().equals(k.getDesc())) return false;
				if (!ownClass(h.getOwner())) return true;
				return h.getTag() > Opcodes.H_PUTSTATIC ? callee(h.getOwner(), h.getName(), h.getDesc(), k.getOwner())
						: field(h.getOwner(), h.getName(), h.getDesc(), k.getOwner());
			}
			return Objects.equals(p, q);
		}

		/** A call into one side's own classes: the callee's body there equals the other side's. */
		private boolean callee(String aOwner, String name, String desc, String bOwner) {
			ClassNode a = classes.apply(aOwner), b = classes.apply(bOwner);
			MethodNode p = a == null ? null : declared(a, name, desc), q = b == null ? null : declared(b, name, desc);
			return p != null && q != null && same(a, p, b, q);
		}

		/** A field of one side's own classes: declared alike on both, and both classes' initializers equal. */
		private boolean field(String aOwner, String name, String desc, String bOwner) {
			ClassNode a = classes.apply(aOwner), b = classes.apply(bOwner);
			if (a == null || b == null) return false;
			FieldNode p = a.fields.stream().filter(f -> f.name.equals(name) && f.desc.equals(desc)).findFirst().orElse(null);
			FieldNode q = b.fields.stream().filter(f -> f.name.equals(name) && f.desc.equals(desc)).findFirst().orElse(null);
			if (p == null || q == null || p.access != q.access || !Objects.equals(p.value, q.value)) return false;
			MethodNode pi = declared(a, "<clinit>", "()V"), qi = declared(b, "<clinit>", "()V");
			return pi == null && qi == null || pi != null && qi != null && same(a, pi, b, qi);
		}

		private static List<AbstractInsnNode> real(MethodNode method) {
			List<AbstractInsnNode> out = new ArrayList<>();
			for (AbstractInsnNode instruction : method.instructions) if (instruction.getOpcode() >= 0) out.add(instruction);
			return out;
		}

		/** The index among the real instructions of the first one at or after {@code label}. */
		private static int index(List<AbstractInsnNode> real, LabelNode label) {
			AbstractInsnNode at = label;
			while (at != null && at.getOpcode() < 0) at = at.getNext();
			return at == null ? real.size() : real.indexOf(at);
		}
	}
}
