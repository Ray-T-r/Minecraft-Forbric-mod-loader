/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.mixin;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
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
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.IincInsnNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.IntInsnNode;
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
import net.forbric.kernel.classloading.ForbricClassLoader;
import net.forbric.kernel.transform.MergedBaseFrameRecomputer;
import net.forbric.kernel.util.ForbricLog;

/**
 * Calls a native decision method again where the surviving carrier replaced its call with a decision of its own, so a mod's
 * injector at that method's entry runs where its own loader runs it, with its own semantics, while the carrier still
 * decides everything else.
 *
 * <p>Vanilla's {@code LiquidBlock.onPlace} and {@code neighborChanged} ask {@code shouldSpreadLiquid(level, pos, state)}
 * and schedule a fluid tick when it answers true; it runs the lava and water reactions. The carriers ask their fluid
 * interaction registries instead — {@code !canInteract(level, pos)}, which run the same reactions and their mods' — and
 * nothing calls {@code shouldSpreadLiquid} any more. A cancellable {@code @Inject} at its {@code HEAD} that answers false
 * where a mod's own reaction happened (a mod's own fluid reaction, a flow-veto event) binds and never runs.
 *
 * <p>Relayed, each such caller asks the native method again, first, with the native arguments, and the carrier's own
 * decision only when the native method lets it through: {@code if (shouldSpreadLiquid(level, pos, state) &&
 * !canInteract(level, pos)) scheduleTick(…)}. The native method's body is kept, behind an entry that answers the value that
 * lets the carrier decide (true here) — so with no mod in it nothing changes, and a mod's {@code HEAD} injector that
 * cancels with false stops the tick and the carrier's reactions, exactly as natively it stops the tick and vanilla's.
 *
 * <p>Nothing here knows a mod, a class or a method by name. A method is relayed when one guest injector's only point is
 * {@code HEAD} of it, and:
 * <ul>
 *   <li>it returns {@code boolean}, is private, and nothing in the merged class calls it or names it in a method handle;</li>
 *   <li>the class the mod was compiled against (the native reference of its ecosystem) calls it, and every such call is
 *       handed plain reads of the caller's own parameters (or {@code this}) and is followed by a conditional jump over a
 *       block of code;</li>
 *   <li>the same caller in the merged class makes, instead, exactly one call of a {@code boolean} method the native caller
 *       never makes, handed plain reads or constants with nothing on the stack below, followed by a conditional jump over a
 *       block identical, instruction for instruction, to the native one — the place where the carrier decides what the
 *       native method decided;</li>
 *   <li>every native caller lets the block run on the same answer, which becomes the entry's answer.</li>
 * </ul>
 * When the class is transformed, a relay is declined where any mixin binds the method with a {@code RETURN} point: the
 * entry's own return would be one of its points, answering the carrier's value. The class's frames are recomputed from the
 * hierarchy read as resources. {@code -Dforbric.nativeEntryRelay=off} plans nothing.
 */
public final class NativeEntryRelay {
	public static final String PROPERTY = "forbric.nativeEntryRelay";

	/** One caller: the {@code occurrence}-th call of the carrier's {@code decision} in {@code host}, asked after the native method. */
	record Site(String host, String decision, int opcode, int occurrence, List<Integer> loads, int nativeOpcode, int jump) {
	}

	/** The relayed method of {@code owner}, the field its entry reads, the answer it gives, and its callers. */
	record Plan(String owner, String method, String field, int answer, List<Site> sites, String source, String handler) {
		boolean same(Plan other) {
			return owner.equals(other.owner) && method.equals(other.method);
		}
	}

	private record Bound(String mixin, String target, String name, String point) {
	}

	private static final class Registry {
		final List<Plan> plans = new CopyOnWriteArrayList<>();
		final Set<Bound> bound = ConcurrentHashMap.newKeySet();
	}

	private static final Map<ClassLoader, Registry> REGISTRIES = Collections.synchronizedMap(new WeakHashMap<>());

	private NativeEntryRelay() {
	}

	static boolean enabled() {
		return !"off".equalsIgnoreCase(System.getProperty(PROPERTY, "on"));
	}

	public static void release(ClassLoader loader) {
		REGISTRIES.remove(loader);
	}

	private static Registry registry(ClassLoader loader) {
		synchronized (REGISTRIES) {
			return REGISTRIES.computeIfAbsent(loader, ignored -> new Registry());
		}
	}

	/** Plans and registers the relays {@code mixin}'s entry injectors need; returns how many it registered. */
	public static int adapt(ClassNode mixin, Function<String, ClassNode> code, BiFunction<Ecosystem, String, ClassNode> natives,
			ForbricClassLoader loader) {
		if (!enabled() || mixin == null || loader == null) return 0;
		Registry registry = registry(loader);
		remember(registry, mixin);
		Ecosystem ecosystem = MixinStubRebind.ecosystemOf(mixin.name);
		if (ecosystem == null) return 0;
		int planned = 0;
		for (String target : MixinFit.mixinTargets(mixin)) {
			ClassNode original = natives.apply(ecosystem, target), merged = null;
			if (original == null) continue;
			for (MethodNode handler : mixin.methods) {
				AnnotationNode injector = MixinFit.injectorOf(handler);
				if (injector == null) continue;
				List<AnnotationNode> points = MixinFit.atNodes(injector);
				if (points.size() != 1 || !"HEAD".equals(MixinFit.asString(MixinFit.value(points.getFirst(), "value")))) continue;
				if (merged == null) merged = code.apply(target);
				if (merged == null) break;
				MethodNode was = MixinTargetSelectors.one(handler, original), method = MixinTargetSelectors.one(handler, merged);
				if (was == null || method == null || !was.name.equals(method.name) || !was.desc.equals(method.desc)) continue;
				Plan plan = derive(original, merged, method, mixin.name, handler.name);
				if (plan == null) continue;
				if (register(loader, registry, plan)) {
					planned++;
					merged = null;
					ForbricLog.info("[Forbric/Mixin] %s.%s: %s.%s is asked again by %s, ahead of the carrier's own decision there, so an injector at "
							+ "its entry runs where %s runs it", mixin.name.replace('/', '.'), handler.name, target.replace('/', '.'), plan.method(),
							plan.sites().stream().map(Site::host).map(h -> h.substring(0, h.indexOf('('))).toList(), ecosystem);
				}
			}
		}
		return planned;
	}

	private static void remember(Registry registry, ClassNode mixin) {
		for (String target : MixinFit.mixinTargets(mixin)) {
			for (MethodNode method : mixin.methods) {
				AnnotationNode injector = MixinFit.injectorOf(method);
				if (injector == null) continue;
				for (String selector : MixinFit.stringList(MixinFit.value(injector, "method"))) {
					String spelled = selector.replace(" ", "");
					if (spelled.startsWith("L") && spelled.contains(";")) spelled = spelled.substring(spelled.indexOf(';') + 1);
					int paren = spelled.indexOf('(');
					String name = paren < 0 ? spelled : spelled.substring(0, paren);
					for (AnnotationNode at : MixinFit.atNodes(injector))
						registry.bound.add(new Bound(mixin.name, target, name, MixinFit.asString(MixinFit.value(at, "value"))));
				}
			}
		}
	}

	/** The relay that makes {@code method}'s entry run where it runs natively, or null. */
	static Plan derive(ClassNode original, ClassNode merged, MethodNode method, String source, String handler) {
		if (!Type.getReturnType(method.desc).equals(Type.BOOLEAN_TYPE) || (method.access & Opcodes.ACC_PRIVATE) == 0) return null;
		if (named(merged, method)) return null;
		List<Site> sites = new ArrayList<>();
		int answer = -1;
		for (MethodNode caller : original.methods) {
			List<MethodInsnNode> calls = new ArrayList<>();
			for (AbstractInsnNode instruction : caller.instructions)
				if (instruction instanceof MethodInsnNode call && call.owner.equals(original.name) && call.name.equals(method.name) && call.desc.equals(method.desc))
					calls.add(call);
			if (calls.isEmpty()) continue;
			if (calls.size() != 1) return null;
			MethodNode host = NativeCallInlining.method(merged, caller.name + caller.desc);
			if (host == null) return null;
			Site site = site(original.name, caller, calls.getFirst(), merged.name, host);
			if (site == null) return null;
			int lets = site.jump() == Opcodes.IFEQ ? 1 : 0;
			if (answer >= 0 && answer != lets) return null;
			answer = lets;
			sites.add(site);
		}
		if (sites.isEmpty()) return null;
		return new Plan(merged.name, method.name + method.desc, "forbric$nativeEntry$" + method.name + "$" + Integer.toHexString(method.desc.hashCode()), answer, List.copyOf(sites), source, handler);
	}

	/** Whether anything in {@code owner} calls {@code method} or names it in a handle. */
	private static boolean named(ClassNode owner, MethodNode method) {
		for (MethodNode m : owner.methods) for (AbstractInsnNode instruction : m.instructions) {
			if (instruction instanceof MethodInsnNode call && call.owner.equals(owner.name) && call.name.equals(method.name) && call.desc.equals(method.desc))
				return true;
			if (instruction instanceof InvokeDynamicInsnNode dynamic) for (Object argument : dynamic.bsmArgs)
				if (argument instanceof Handle handle && handle.getOwner().equals(owner.name) && handle.getName().equals(method.name)
						&& handle.getDesc().equals(method.desc)) return true;
		}
		return false;
	}

	private static Site site(String nativeOwner, MethodNode caller, MethodInsnNode call, String owner, MethodNode host) {
		Frame<SourceValue>[] nativeFrames = frames(nativeOwner, caller);
		if (nativeFrames == null) return null;
		Frame<SourceValue> frame = nativeFrames[caller.instructions.indexOf(call)];
		int operands = Type.getArgumentTypes(call.desc).length + (call.getOpcode() == Opcodes.INVOKESTATIC ? 0 : 1);
		if (frame == null || frame.getStackSize() != operands) return null;
		List<Integer> loads = new ArrayList<>();
		int parameterSlots = (Type.getArgumentsAndReturnSizes(caller.desc) >> 2) - ((caller.access & Opcodes.ACC_STATIC) != 0 ? 1 : 0);
		for (int k = 0; k < operands; k++) {
			SourceValue value = frame.getStack(k);
			if (value.insns.size() != 1 || !(value.insns.iterator().next() instanceof VarInsnNode load) || load.getOpcode() < Opcodes.ILOAD
					|| load.getOpcode() > Opcodes.ALOAD || load.var >= parameterSlots || stores(caller, load.var) || stores(host, load.var)) return null;
			loads.add(load.getOpcode() << 16 | load.var);
		}
		AbstractInsnNode next = nextReal(call);
		if (!(next instanceof JumpInsnNode jump) || jump.getOpcode() != Opcodes.IFEQ && jump.getOpcode() != Opcodes.IFNE) return null;
		List<String> block = block(caller, jump);
		if (block == null) return null;
		Set<String> nativeCalls = new TreeSet<>();
		for (AbstractInsnNode instruction : caller.instructions) if (instruction instanceof MethodInsnNode other) nativeCalls.add(other.name + other.desc);
		Frame<SourceValue>[] frames = frames(owner, host);
		if (frames == null) return null;
		MethodInsnNode decision = null;
		for (AbstractInsnNode instruction : host.instructions) {
			if (!(instruction instanceof MethodInsnNode candidate) || candidate.name.startsWith("<") || nativeCalls.contains(candidate.name + candidate.desc)
					|| !Type.getReturnType(candidate.desc).equals(Type.BOOLEAN_TYPE)) continue;
			if (!(nextReal(candidate) instanceof JumpInsnNode skip) || skip.getOpcode() != Opcodes.IFEQ && skip.getOpcode() != Opcodes.IFNE) continue;
			if (!block.equals(block(host, skip))) continue;
			Frame<SourceValue> at = frames[host.instructions.indexOf(candidate)];
			int count = Type.getArgumentTypes(candidate.desc).length + (candidate.getOpcode() == Opcodes.INVOKESTATIC ? 0 : 1);
			if (at == null || at.getStackSize() != count || start(host, frames, candidate) == null) continue;
			if (decision != null) return null;
			decision = candidate;
		}
		if (decision == null) return null;
		return new Site(host.name + host.desc, member(decision), decision.getOpcode(), occurrence(host, decision), List.copyOf(loads), call.getOpcode(),
				jump.getOpcode());
	}

	private static List<String> block(MethodNode method, JumpInsnNode jump) {
		return ReplacedDecisionValues.block(method, jump);
	}

	/** The first instruction pushing {@code call}'s operands, when they are plain reads or constants in one straight stretch. */
	private static AbstractInsnNode start(MethodNode method, Frame<SourceValue>[] frames, MethodInsnNode call) {
		Frame<SourceValue> frame = frames[method.instructions.indexOf(call)];
		int count = Type.getArgumentTypes(call.desc).length + (call.getOpcode() == Opcodes.INVOKESTATIC ? 0 : 1);
		AbstractInsnNode first = call;
		Set<AbstractInsnNode> producers = Collections.newSetFromMap(new IdentityHashMap<>());
		for (int k = 0; k < count; k++) {
			SourceValue value = frame.getStack(frame.getStackSize() - count + k);
			if (value.insns.size() != 1) return null;
			AbstractInsnNode producer = value.insns.iterator().next();
			int opcode = producer.getOpcode();
			if (!(producer instanceof VarInsnNode) && !(opcode >= Opcodes.ACONST_NULL && opcode <= Opcodes.SIPUSH) && !(producer instanceof LdcInsnNode)
					&& !(producer instanceof FieldInsnNode field && field.getOpcode() == Opcodes.GETSTATIC)) return null;
			producers.add(producer);
			if (method.instructions.indexOf(producer) < method.instructions.indexOf(first)) first = producer;
		}
		for (AbstractInsnNode at = first; at != call; at = at.getNext()) if (at.getOpcode() >= 0 && !producers.contains(at)) return null;
		return first;
	}

	private static boolean register(ForbricClassLoader loader, Registry registry, Plan plan) {
		for (Plan existing : registry.plans) if (existing.same(plan)) return true;
		return loader.registerBeforeDefinition(plan.owner().replace('/', '.'), () -> registry.plans.add(plan));
	}

	/** {@code binary}'s pre-mixin bytes with its planned relays in place; unchanged where no plan names it. */
	public static byte[] transform(ClassLoader loader, String binary, byte[] bytes) {
		if (bytes == null) return null;
		Registry registry = REGISTRIES.get(loader);
		if (registry == null || registry.plans.isEmpty()) return bytes;
		String internal = binary.replace('.', '/');
		List<Plan> mine = registry.plans.stream().filter(plan -> plan.owner().equals(internal)).toList();
		if (mine.isEmpty()) return bytes;
		ClassNode node = new ClassNode();
		new ClassReader(bytes).accept(node, ClassReader.SKIP_FRAMES);
		boolean changed = false;
		for (Plan plan : mine) {
			String name = plan.method().substring(0, plan.method().indexOf('('));
			Bound returning = registry.bound.stream().filter(b -> b.target().equals(internal) && b.name().equals(name) && "RETURN".equals(b.point()))
					.findFirst().orElse(null);
			if (returning != null) {
				ForbricLog.warn("[Forbric/Mixin] %s.%s is not asked again: mixin %s binds it with a RETURN point, which its relayed entry would answer",
						binary, plan.method(), returning.mixin().replace('/', '.'));
				continue;
			}
			String declined = apply(node, plan);
			if (declined != null) {
				ForbricLog.warn("[Forbric/Mixin] %s.%s is not asked again: %s", binary, plan.method(), declined);
				continue;
			}
			changed = true;
		}
		if (!changed) return bytes;
		try {
			MergedBaseFrameRecomputer hierarchy = new MergedBaseFrameRecomputer(path -> resource(loader, path));
			ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS) {
				@Override protected String getCommonSuperClass(String a, String b) {
					return hierarchy.commonSuperClass(a, b);
				}
			};
			node.accept(writer);
			return writer.toByteArray();
		} catch (RuntimeException unwritable) {
			ForbricLog.warn("[Forbric/Mixin] could not write " + binary + " with its native decisions asked again; it stays as merged", unwritable);
			return bytes;
		}
	}

	private static String apply(ClassNode node, Plan plan) {
		MethodNode method = NativeCallInlining.method(node, plan.method());
		if (method == null) return "the method is gone";
		if (named(node, method)) return "the class calls it already";
		List<MethodNode> hosts = new ArrayList<>();
		List<InsnList> asks = new ArrayList<>();
		List<AbstractInsnNode> starts = new ArrayList<>();
		for (Site site : plan.sites()) {
			MethodNode host = NativeCallInlining.method(node, site.host());
			if (host == null) return site.host() + " is gone";
			MethodInsnNode decision = nth(host, site.decision(), site.opcode(), site.occurrence());
			Frame<SourceValue>[] frames = frames(node.name, host);
			AbstractInsnNode start = decision == null || frames == null ? null : start(host, frames, decision);
			if (start == null || !(nextReal(decision) instanceof JumpInsnNode skip)) return "the carrier's decision in " + site.host() + " changed";
			InsnList ask = new InsnList();
			for (int load : site.loads()) ask.add(new VarInsnNode(load >>> 16, load & 0xFFFF));
			ask.add(new MethodInsnNode(site.nativeOpcode(), node.name, method.name, method.desc, false));
			ask.add(new JumpInsnNode(site.jump(), skip.label));
			hosts.add(host);
			asks.add(ask);
			starts.add(start);
		}
		for (int i = 0; i < hosts.size(); i++) hosts.get(i).instructions.insertBefore(starts.get(i), asks.get(i));
		node.fields.add(new FieldNode(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_SYNTHETIC, plan.field(), "Z", null, null));
		InsnList entry = new InsnList();
		LabelNode body = new LabelNode();
		entry.add(new FieldInsnNode(Opcodes.GETSTATIC, node.name, plan.field(), "Z"));
		entry.add(new JumpInsnNode(Opcodes.IFNE, body));
		entry.add(new InsnNode(plan.answer() == 1 ? Opcodes.ICONST_1 : Opcodes.ICONST_0));
		entry.add(new InsnNode(Opcodes.IRETURN));
		entry.add(body);
		method.instructions.insert(entry);
		return null;
	}

	private static AbstractInsnNode nextReal(AbstractInsnNode instruction) {
		for (AbstractInsnNode at = instruction.getNext(); at != null; at = at.getNext()) if (at.getOpcode() >= 0) return at;
		return null;
	}

	private static boolean stores(MethodNode method, int slot) {
		for (AbstractInsnNode instruction : method.instructions) {
			if (instruction instanceof VarInsnNode variable && variable.var == slot && variable.getOpcode() >= Opcodes.ISTORE && variable.getOpcode() <= Opcodes.ASTORE)
				return true;
			if (instruction instanceof IincInsnNode increment && increment.var == slot) return true;
		}
		return false;
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

	private static byte[] resource(ClassLoader loader, String path) {
		try {
			if (loader instanceof ForbricClassLoader forbric) {
				try (InputStream in = forbric.getGameResourceAsStream(path)) {
					if (in != null) return in.readAllBytes();
				}
			}
			try (InputStream in = ClassLoader.getSystemResourceAsStream(path)) {
				return in == null ? null : in.readAllBytes();
			}
		} catch (IOException unreadable) {
			return null;
		}
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
