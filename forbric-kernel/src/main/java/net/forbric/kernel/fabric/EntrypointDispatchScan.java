/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.fabric;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.Deque;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.AnalyzerException;
import org.objectweb.asm.tree.analysis.BasicInterpreter;
import org.objectweb.asm.tree.analysis.BasicValue;
import org.objectweb.asm.tree.analysis.Frame;
import org.objectweb.asm.tree.analysis.Interpreter;
import org.objectweb.asm.tree.analysis.Value;

import net.forbric.kernel.util.ByteScan;

/**
 * Which custom entrypoint keys a jar's own code dispatches, and how: read from its bytecode, not from a list.
 *
 * <p>Fabric Loader only stores entrypoint declarations. A key other than its four lifecycle keys runs only because
 * some mod's code asks Fabric Loader for it — {@code getEntrypoints(key, type)}, {@code getEntrypointContainers} or
 * {@code invokeEntrypoints} — and calls what it gets back. When that mod is a library shipped as a Fabric build AND
 * a Forge-family build, and arbitration loads the Forge-family one, the code that asked is gone with the Fabric
 * build. The Fabric mods declaring the key are still loaded and still declare it; nothing calls them, and they fail
 * far away on state they never set up ("Cannot get config value before config is loaded").
 *
 * <p>This answers, for one jar, every such dispatch it makes: the key, the entrypoint type, which method of that
 * type the jar invokes on each entrypoint, and from which of the jar's own lifecycle entrypoints the dispatch is
 * reached. All of it is read from the code:
 * <ul>
 * <li>the key and the type are data-flow facts — constants, a {@code static final} constant, a value kept in a
 *     local, or a parameter of a helper whose callers pass the constant (followed through any number of helpers);</li>
 * <li>the invoked method is the method reference the jar hands to the dispatch, or else the type's single abstract
 *     method; only a no-argument one can be called on the library's behalf, because arguments would be the losing
 *     build's own state;</li>
 * <li>the phase is call-graph reachability from the methods Fabric itself calls on the jar's {@code preLaunch},
 *     {@code main}, {@code client} and {@code server} entrypoints. A dispatch reached only through a callback (a
 *     lambda registered for later) has no phase: when it runs cannot be derived, so nothing claims it.</li>
 * </ul>
 */
public final class EntrypointDispatchScan {
	/** Fabric Loader's own query API: where a custom key is read. Platform names only. */
	private static final Set<String> LOADER_OWNERS = Set.of("net/fabricmc/loader/api/FabricLoader",
			"net/fabricmc/loader/impl/FabricLoaderImpl", "net/fabricmc/loader/FabricLoader");
	private static final Set<String> QUERIES = Set.of("getEntrypoints", "getEntrypointContainers", "invokeEntrypoints");
	private static final String QUERY_PREFIX = "(Ljava/lang/String;Ljava/lang/Class;";
	/** Cheap pre-check: a class that dispatches names one of the query methods in its constant pool. */
	private static final byte[][] QUERY_NAMES = QUERIES.stream().map(ByteScan::poolEntry).toArray(byte[][]::new);

	private EntrypointDispatchScan() {
	}

	/**
	 * The phase in which a dispatch runs, from the lifecycle entrypoint of the scanned jar that reaches it.
	 *
	 * <p>Declared in the order the phases run on either side; {@link #CLIENT} and {@link #SERVER} never both run.
	 */
	public enum Phase {
		/** Reached from the jar's {@code preLaunch}: before any mod initialises. */
		PRE_INIT("preLaunch", "onPreLaunch"),
		/** Reached from its {@code main}. */
		MAIN("main", "onInitialize"),
		/** Reached from its {@code client}. */
		CLIENT("client", "onInitializeClient"),
		/** Reached from its {@code server}. */
		SERVER("server", "onInitializeServer");

		private final String key;
		private final String method;

		Phase(String key, String method) {
			this.key = key;
			this.method = method;
		}

		/** The Fabric entrypoint key this phase is named after. */
		public String key() {
			return key;
		}

		/** Whether this phase runs on the given physical side. */
		public boolean runsOn(boolean client) {
			return client ? this != SERVER : this != CLIENT;
		}

		/** The earliest of {@code phases} that runs on the given side, or null. */
		public static Phase earliest(Set<Phase> phases, boolean client) {
			for (Phase phase : values()) if (phases.contains(phase) && phase.runsOn(client)) return phase;
			return null;
		}
	}

	/**
	 * One dispatch a jar makes.
	 *
	 * @param key              the entrypoint key it reads
	 * @param type             the entrypoint type it asks for (internal name)
	 * @param ownType          whether that type is declared by the scanned jar itself, i.e. the protocol is its own
	 * @param method           the no-argument method it invokes on each entrypoint, or null when that cannot be derived
	 * @param methodDescriptor that method's descriptor, or null
	 * @param phases           every lifecycle phase of the jar that reaches the dispatch directly; empty when only a
	 *                         callback reaches it
	 * @param site             the method that binds the key, for the log
	 */
	public record Dispatch(String key, String type, boolean ownType, String method, String methodDescriptor,
			Set<Phase> phases, String site) {
		public Dispatch {
			phases = phases.isEmpty() ? Set.of() : java.util.Collections.unmodifiableSet(EnumSet.copyOf(phases));
		}

		/** Whether everything needed to dispatch it on the jar's behalf was derived. */
		public boolean derivable() {
			return method != null && !phases.isEmpty();
		}

		/** Why it cannot be dispatched on the jar's behalf, or null when it can. */
		public String obstacle() {
			if (method == null) return "no argument-free method of " + type.replace('/', '.') + " is what it invokes";
			if (phases.isEmpty()) return "it runs from a callback, so when it runs cannot be derived";
			return null;
		}
	}

	/**
	 * What one jar's code says about the keys asked about.
	 *
	 * @param dispatches   the dispatches derived for them ({@link #scan})
	 * @param undetermined the query sites whose key or type no constant reaches — computed at run time, read through
	 *                     a method, or a parameter no caller in the jar binds — so what they dispatch could not be
	 *                     derived; each named as {@code owner.method}
	 * @param named        which of the keys asked about the jar holds as a string constant (an {@code ldc} operand or a
	 *                     constant field's value) — what a query of it could have read
	 */
	public record Report(List<Dispatch> dispatches, List<String> undetermined, Set<String> named) {
		static final Report NONE = new Report(List.of(), List.of(), Set.of());

		public Report {
			dispatches = List.copyOf(dispatches);
			undetermined = List.copyOf(undetermined);
			named = java.util.Collections.unmodifiableSet(new java.util.TreeSet<>(named));
		}
	}

	/** Every dispatch the jar makes, with phases from its own {@code fabric.mod.json}. Unreadable classes are skipped. */
	public static List<Dispatch> scan(Path jar) throws IOException {
		return scan(jar, null);
	}

	/**
	 * Only the dispatches of {@code keys}; a jar whose classes name none of them is not analysed at all, which is
	 * the common case and costs one read of the jar.
	 */
	public static List<Dispatch> scan(Path jar, Set<String> keys) throws IOException {
		return report(jar, keys).dispatches();
	}

	/** {@link #scan(Path, Set)}, together with the queries it could not derive and the keys the jar holds. */
	public static Report report(Path jar, Set<String> keys) throws IOException {
		Map<String, byte[]> classes = new LinkedHashMap<>();
		Map<String, List<String>> entrypoints = new LinkedHashMap<>();
		try (ZipFile zip = new ZipFile(jar.toFile())) {
			for (ZipEntry entry : zip.stream().toList()) {
				String name = entry.getName();
				if (name.equals("fabric.mod.json")) {
					try (InputStream in = zip.getInputStream(entry)) {
						KernelModMetadata metadata = FabricModMetadataParser.read(in);
						for (var declared : metadata.getEntrypoints().entrySet()) {
							for (var decl : declared.getValue()) {
								entrypoints.computeIfAbsent(declared.getKey(), k -> new ArrayList<>()).add(decl.value());
							}
						}
					} catch (RuntimeException unreadable) {
						// No phases then: what it dispatches is still worth knowing (a winner check needs only the keys).
					}
					continue;
				}
				if (!name.endsWith(".class") || name.startsWith("META-INF/") || name.endsWith("module-info.class")) continue;
				try (InputStream in = zip.getInputStream(entry)) {
					classes.put(name.substring(0, name.length() - 6), in.readAllBytes());
				}
			}
		}
		return report(classes, entrypoints, keys);
	}

	/**
	 * The pure half: {@code classes} by internal name, {@code entrypoints} as declared ({@code key -> values}), and
	 * the keys of interest ({@code null} for every key).
	 */
	public static List<Dispatch> scan(Map<String, byte[]> classes, Map<String, List<String>> entrypoints, Set<String> keys) {
		return report(classes, entrypoints, keys).dispatches();
	}

	/** The pure half of {@link #report(Path, Set)}; with {@code keys} null, {@link Report#named} is empty. */
	public static Report report(Map<String, byte[]> classes, Map<String, List<String>> entrypoints, Set<String> keys) {
		if (keys != null && keys.isEmpty()) return Report.NONE;
		if (!anyClassNames(classes, QUERY_NAMES)) return Report.NONE;
		// A key the jar dispatches is a string constant somewhere in it, or this cannot name the dispatch anyway.
		if (keys != null && !anyClassNames(classes, poolEntries(keys))) return Report.NONE;
		Analysis analysis = new Analysis(classes);
		List<Dispatch> found = analysis.run(entrypoints);
		if (keys == null) return new Report(found, analysis.undetermined(), Set.of());
		return new Report(found.stream().filter(dispatch -> keys.contains(dispatch.key())).toList(), analysis.undetermined(),
				analysis.constantsAmong(keys));
	}

	private static boolean anyClassNames(Map<String, byte[]> classes, byte[][] entries) {
		for (byte[] bytes : classes.values()) if (ByteScan.namesAny(bytes, entries)) return true;
		return false;
	}

	private static byte[][] poolEntries(Collection<String> strings) {
		return strings.stream().map(ByteScan::poolEntry).toArray(byte[][]::new);
	}

	/**
	 * Which of {@code strings} are a constant of some class in {@code jar} or in a jar it bundles — what a build that
	 * reads a key through any API, not only Fabric Loader's, must contain.
	 */
	public static Set<String> namedIn(Path jar, Set<String> strings) throws IOException {
		Set<String> named = new LinkedHashSet<>();
		if (strings.isEmpty()) return named;
		List<String> wanted = List.copyOf(strings);
		byte[][] entries = poolEntries(wanted);
		try (ZipFile zip = new ZipFile(jar.toFile())) {
			for (ZipEntry entry : zip.stream().toList()) {
				String name = entry.getName();
				if (name.endsWith(".class")) {
					try (InputStream in = zip.getInputStream(entry)) {
						mark(in.readAllBytes(), wanted, entries, named);
					}
				} else if (name.endsWith(".jar")) {
					try (java.util.zip.ZipInputStream nested = new java.util.zip.ZipInputStream(zip.getInputStream(entry))) {
						for (ZipEntry inner; (inner = nested.getNextEntry()) != null; ) {
							if (inner.getName().endsWith(".class")) mark(nested.readAllBytes(), wanted, entries, named);
						}
					} catch (IOException unreadableBundle) {
						// One unreadable bundle cannot show the key is named; the rest of the jar still can.
					}
				}
				if (named.size() == wanted.size()) break;
			}
		}
		return named;
	}

	private static void mark(byte[] bytes, List<String> wanted, byte[][] entries, Set<String> named) {
		boolean[] found = ByteScan.constantPoolNames(bytes, entries);
		for (int i = 0; i < found.length; i++) if (found[i]) named.add(wanted.get(i));
	}

	// -----------------------------------------------------------------------------------------------------------
	// The data flow: which values reach a query's key, type and consumer arguments.
	// -----------------------------------------------------------------------------------------------------------

	private static final int OTHER = 0, STR = 1, CLS = 2, PARAM = 3, LAMBDA = 4, NONE = 5;

	/** What is known of one value: a string or class constant, a parameter of the method, a lambda, or nothing. */
	private record Arg(int kind, Object payload) {
		static final Arg UNKNOWN = new Arg(OTHER, null);
		static final Arg ABSENT = new Arg(NONE, null);
	}

	/** A frame value: the verifier's type plus what is known of it. */
	private record Val(BasicValue basic, Arg arg) implements Value {
		@Override
		public int getSize() {
			return basic.getSize();
		}
	}

	/** One query, as the method that contains (or forwards to) it sees its three arguments. */
	private record Sink(Arg key, Arg type, Arg consumer) {
	}

	/** Constant propagation over the verifier's types: constants survive loads, stores, DUP and CHECKCAST. */
	private static final class Constants extends Interpreter<Val> {
		private final BasicInterpreter basic = new BasicInterpreter();
		private final int[] argumentOfLocal;
		private final Map<String, Arg> statics;

		Constants(MethodNode method, Map<String, Arg> statics) {
			super(Opcodes.ASM9);
			this.statics = statics;
			boolean instance = (method.access & Opcodes.ACC_STATIC) == 0;
			Type[] arguments = Type.getArgumentTypes(method.desc);
			int locals = instance ? 1 : 0;
			for (Type argument : arguments) locals += argument.getSize();
			argumentOfLocal = new int[Math.max(locals, 1)];
			java.util.Arrays.fill(argumentOfLocal, -1);
			int local = instance ? 1 : 0;
			for (int i = 0; i < arguments.length; i++) {
				argumentOfLocal[local] = i;
				local += arguments[i].getSize();
			}
		}

		private static Val of(BasicValue value, Arg arg) {
			return value == null ? null : new Val(value, arg);
		}

		@Override
		public Val newValue(Type type) {
			return of(basic.newValue(type), Arg.UNKNOWN);
		}

		@Override
		public Val newParameterValue(boolean isInstanceMethod, int local, Type type) {
			Val value = newValue(type);
			int argument = local < argumentOfLocal.length ? argumentOfLocal[local] : -1;
			return value == null || argument < 0 ? value : new Val(value.basic(), new Arg(PARAM, argument));
		}

		@Override
		public Val newOperation(AbstractInsnNode insn) throws AnalyzerException {
			BasicValue value = basic.newOperation(insn);
			if (insn instanceof LdcInsnNode ldc) {
				if (ldc.cst instanceof String string) return of(value, new Arg(STR, string));
				if (ldc.cst instanceof Type type && type.getSort() == Type.OBJECT) return of(value, new Arg(CLS, type.getInternalName()));
			}
			if (insn instanceof FieldInsnNode field && insn.getOpcode() == Opcodes.GETSTATIC) {
				Arg constant = statics.get(fieldKey(field.owner, field.name, field.desc));
				if (constant != null) return of(value, constant);
			}
			return of(value, Arg.UNKNOWN);
		}

		@Override
		public Val copyOperation(AbstractInsnNode insn, Val value) {
			return value;
		}

		@Override
		public Val unaryOperation(AbstractInsnNode insn, Val value) throws AnalyzerException {
			BasicValue result = basic.unaryOperation(insn, value.basic());
			if (insn.getOpcode() == Opcodes.CHECKCAST) return of(result, value.arg());
			return of(result, Arg.UNKNOWN);
		}

		@Override
		public Val binaryOperation(AbstractInsnNode insn, Val value1, Val value2) throws AnalyzerException {
			return of(basic.binaryOperation(insn, value1.basic(), value2.basic()), Arg.UNKNOWN);
		}

		@Override
		public Val ternaryOperation(AbstractInsnNode insn, Val value1, Val value2, Val value3) throws AnalyzerException {
			return of(basic.ternaryOperation(insn, value1.basic(), value2.basic(), value3.basic()), Arg.UNKNOWN);
		}

		@Override
		public Val naryOperation(AbstractInsnNode insn, List<? extends Val> values) throws AnalyzerException {
			List<BasicValue> basics = new ArrayList<>(values.size());
			for (Val value : values) basics.add(value.basic());
			BasicValue result = basic.naryOperation(insn, basics);
			if (insn instanceof InvokeDynamicInsnNode dynamic && dynamic.bsm.getOwner().equals("java/lang/invoke/LambdaMetafactory")
					&& dynamic.bsmArgs.length >= 2 && dynamic.bsmArgs[1] instanceof Handle target) {
				return of(result, new Arg(LAMBDA, target));
			}
			return of(result, Arg.UNKNOWN);
		}

		@Override
		public void returnOperation(AbstractInsnNode insn, Val value, Val expected) {
		}

		@Override
		public Val merge(Val value1, Val value2) {
			if (value1.equals(value2)) return value1;
			BasicValue merged = basic.merge(value1.basic(), value2.basic());
			Arg arg = value1.arg().equals(value2.arg()) ? value1.arg() : Arg.UNKNOWN;
			Val result = new Val(merged, arg);
			return result.equals(value1) ? value1 : result;
		}
	}

	private static String fieldKey(String owner, String name, String desc) {
		return owner + '.' + name + ':' + desc;
	}

	/** One jar's classes, its call graph and the fixed point of the query summaries. */
	private static final class Analysis {
		private final Map<String, ClassNode> nodes = new LinkedHashMap<>();
		/** {@code owner.name+desc} → method. */
		private final Map<String, MethodNode> methods = new LinkedHashMap<>();
		private final Map<String, String> ownerOf = new HashMap<>();
		private final Map<String, Arg> statics = new HashMap<>();
		private final Map<String, Frame<Val>[]> frames = new HashMap<>();
		private final Map<String, Set<String>> callees = new HashMap<>();
		private final Map<String, Set<String>> callers = new HashMap<>();
		/** What each method forwards: queries whose key or type is still one of its parameters. */
		private final Map<String, Set<Sink>> forwards = new HashMap<>();
		/** What each method binds: queries whose key and type are both constants there. */
		private final Map<String, Set<Sink>> binds = new HashMap<>();
		/** The methods holding a query, or a call into a forwarding method, whose key or type is no constant there. */
		private final Set<String> lost = new HashSet<>();

		Analysis(Map<String, byte[]> classes) {
			for (byte[] bytes : classes.values()) {
				try {
					ClassNode node = new ClassNode();
					new ClassReader(bytes).accept(node, ClassReader.SKIP_FRAMES);
					nodes.put(node.name, node);
				} catch (RuntimeException unreadable) {
					// One class this cannot parse cannot be a dispatch it reports; the rest still count.
				}
			}
			for (ClassNode node : nodes.values()) {
				for (MethodNode method : node.methods) {
					String ref = ref(node.name, method.name, method.desc);
					methods.put(ref, method);
					ownerOf.put(ref, node.name);
				}
			}
		}

		List<Dispatch> run(Map<String, List<String>> entrypoints) {
			collectStatics();
			Deque<String> work = new ArrayDeque<>();
			for (Map.Entry<String, MethodNode> entry : methods.entrySet()) {
				Set<String> targets = new LinkedHashSet<>();
				boolean queries = false;
				for (AbstractInsnNode insn : entry.getValue().instructions) {
					if (!(insn instanceof MethodInsnNode call)) continue;
					if (isQuery(call)) queries = true;
					targets.addAll(resolve(call));
				}
				callees.put(entry.getKey(), targets);
				for (String target : targets) callers.computeIfAbsent(target, k -> new LinkedHashSet<>()).add(entry.getKey());
				if (queries) work.add(entry.getKey());
			}
			Set<String> queued = new HashSet<>(work);
			while (!work.isEmpty()) {
				String ref = work.poll();
				queued.remove(ref);
				Set<Sink> before = forwards.getOrDefault(ref, Set.of());
				analyze(ref);
				if (!forwards.getOrDefault(ref, Set.of()).equals(before)) {
					for (String caller : callers.getOrDefault(ref, Set.of())) if (queued.add(caller)) work.add(caller);
				}
			}

			Map<String, Set<Phase>> reached = reach(entrypoints);
			Map<String, Dispatch> found = new LinkedHashMap<>();
			for (Map.Entry<String, Set<Sink>> bound : binds.entrySet()) {
				for (Sink sink : bound.getValue()) {
					String key = (String) sink.key().payload();
					String type = (String) sink.type().payload();
					String[] contract = contract(type, sink.consumer());
					Set<Phase> phases = reached.getOrDefault(bound.getKey(), Set.of());
					Dispatch dispatch = new Dispatch(key, type, nodes.containsKey(type), contract == null ? null : contract[0],
							contract == null ? null : contract[1], phases, site(bound.getKey()));
					found.merge(key + ' ' + type, dispatch, EntrypointDispatchScan::combine);
				}
			}
			List<Dispatch> out = new ArrayList<>(found.values());
			out.sort(Comparator.comparing(Dispatch::key).thenComparing(Dispatch::type));
			return List.copyOf(out);
		}

		/** {@code static final} String and class constants: ConstantValue attributes and single {@code <clinit>} stores. */
		private void collectStatics() {
			for (ClassNode node : nodes.values()) {
				for (FieldNode field : node.fields) {
					if ((field.access & Opcodes.ACC_STATIC) != 0 && field.value instanceof String string) {
						statics.put(fieldKey(node.name, field.name, field.desc), new Arg(STR, string));
					}
				}
			}
			// A constant may be built from another one; two rounds cover a constant read from a sibling's.
			for (int round = 0; round < 2; round++) {
				Map<String, Arg> stored = new HashMap<>();
				Set<String> conflicting = new HashSet<>();
				for (ClassNode node : nodes.values()) {
					MethodNode clinit = methods.get(ref(node.name, "<clinit>", "()V"));
					if (clinit == null) continue;
					Frame<Val>[] frames = analyzeFrames(node.name, clinit);
					if (frames == null) continue;
					for (int i = 0; i < clinit.instructions.size(); i++) {
						if (!(clinit.instructions.get(i) instanceof FieldInsnNode put) || put.getOpcode() != Opcodes.PUTSTATIC
								|| !put.owner.equals(node.name) || frames[i] == null || !isStaticFinal(node, put.name, put.desc)) continue;
						Arg value = frames[i].getStack(frames[i].getStackSize() - 1).arg();
						String field = fieldKey(put.owner, put.name, put.desc);
						if (value.kind() != STR && value.kind() != CLS || stored.containsKey(field) && !stored.get(field).equals(value)) {
							conflicting.add(field);
						} else {
							stored.put(field, value);
						}
					}
				}
				for (String field : conflicting) stored.remove(field);
				boolean grew = false;
				for (Map.Entry<String, Arg> entry : stored.entrySet()) {
					if (statics.putIfAbsent(entry.getKey(), entry.getValue()) == null) grew = true;
				}
				if (!grew) break;
			}
		}

		private static boolean isStaticFinal(ClassNode node, String name, String desc) {
			for (FieldNode field : node.fields) {
				if (field.name.equals(name) && field.desc.equals(desc)) {
					return (field.access & (Opcodes.ACC_STATIC | Opcodes.ACC_FINAL)) == (Opcodes.ACC_STATIC | Opcodes.ACC_FINAL);
				}
			}
			return false;
		}

		@SuppressWarnings("unchecked")
		private Frame<Val>[] analyzeFrames(String owner, MethodNode method) {
			if (method.instructions.size() == 0) return null;
			try {
				return new Analyzer<>(new Constants(method, statics)).analyze(owner, method);
			} catch (AnalyzerException | RuntimeException unanalysable) {
				return null;
			}
		}

		private Frame<Val>[] framesOf(String ref) {
			if (frames.containsKey(ref)) return frames.get(ref);
			Frame<Val>[] computed = analyzeFrames(ownerOf.get(ref), methods.get(ref));
			frames.put(ref, computed);
			return computed;
		}

		/** Recomputes what {@code ref} binds and forwards from its queries and the summaries of what it calls. */
		private void analyze(String ref) {
			MethodNode method = methods.get(ref);
			Frame<Val>[] frames = framesOf(ref);
			Set<Sink> forwarded = new LinkedHashSet<>();
			Set<Sink> bound = new LinkedHashSet<>();
			boolean dropped = false;
			if (frames != null) {
				for (int i = 0; i < method.instructions.size(); i++) {
					if (!(method.instructions.get(i) instanceof MethodInsnNode call) || frames[i] == null) continue;
					Frame<Val> frame = frames[i];
					if (isQuery(call)) {
						Sink sink = new Sink(argument(frame, call, 0), argument(frame, call, 1),
								call.name.equals("invokeEntrypoints") ? argument(frame, call, 2) : Arg.ABSENT);
						dropped |= !classify(sink, forwarded, bound);
						continue;
					}
					for (String target : resolve(call)) {
						for (Sink callee : forwards.getOrDefault(target, Set.of())) {
							dropped |= !classify(new Sink(substitute(callee.key(), frame, call), substitute(callee.type(), frame, call),
									substitute(callee.consumer(), frame, call)), forwarded, bound);
						}
					}
				}
			} else {
				// Not analysable: a query in it, or a call handing it on, binds nothing that can be named.
				for (AbstractInsnNode insn : method.instructions) {
					if (insn instanceof MethodInsnNode call && (isQuery(call)
							|| resolve(call).stream().anyMatch(target -> forwards.containsKey(target)))) {
						dropped = true;
						break;
					}
				}
			}
			if (forwarded.isEmpty()) forwards.remove(ref); else forwards.put(ref, forwarded);
			if (bound.isEmpty()) binds.remove(ref); else binds.put(ref, bound);
			if (dropped) lost.add(ref); else lost.remove(ref);
		}

		/** Files {@code sink} as bound or forwarded; false when neither, i.e. its key or type is computed at run time. */
		private static boolean classify(Sink sink, Set<Sink> forwarded, Set<Sink> bound) {
			int key = sink.key().kind(), type = sink.type().kind();
			if (key == STR && type == CLS) {
				bound.add(sink);
				return true;
			} else if ((key == STR || key == PARAM) && (type == CLS || type == PARAM)) {
				forwarded.add(sink);
				return true;
			}
			// Anything else is a key or type computed at run time: not a dispatch this can name.
			return false;
		}

		/**
		 * The query sites whose dispatch could not be derived: a key or type no constant reaches where it is asked
		 * for or where a caller hands it on, and a forwarding method no caller in the jar binds (it is called only
		 * from a lambda, or from outside the jar). After {@link #run}.
		 */
		List<String> undetermined() {
			Set<String> sites = new java.util.TreeSet<>();
			for (String ref : lost) sites.add(site(ref));
			for (String ref : forwards.keySet()) {
				if (callers.getOrDefault(ref, Set.of()).isEmpty()) sites.add(site(ref));
			}
			return List.copyOf(sites);
		}

		/**
		 * Which of {@code strings} the jar holds as a string constant: an {@code ldc} operand in some method or a
		 * constant field's value — not a name a class merely declares or references.
		 */
		Set<String> constantsAmong(Set<String> strings) {
			Set<String> found = new LinkedHashSet<>();
			for (ClassNode node : nodes.values()) {
				for (FieldNode field : node.fields) {
					if (field.value instanceof String string && strings.contains(string)) found.add(string);
				}
				for (MethodNode method : node.methods) {
					for (AbstractInsnNode insn : method.instructions) {
						if (insn instanceof LdcInsnNode ldc && ldc.cst instanceof String string && strings.contains(string)) found.add(string);
					}
				}
			}
			return found;
		}

		private static Arg argument(Frame<Val> frame, MethodInsnNode call, int index) {
			int count = Type.getArgumentTypes(call.desc).length;
			if (index >= count) return Arg.UNKNOWN;
			return frame.getStack(frame.getStackSize() - count + index).arg();
		}

		private static Arg substitute(Arg arg, Frame<Val> frame, MethodInsnNode call) {
			return arg.kind() == PARAM ? argument(frame, call, (Integer) arg.payload()) : arg;
		}

		private static boolean isQuery(MethodInsnNode call) {
			return LOADER_OWNERS.contains(call.owner) && QUERIES.contains(call.name) && call.desc.startsWith(QUERY_PREFIX);
		}

		/** The jar's own methods a call can land in: exact for static/special, every override in the jar for virtual. */
		private Set<String> resolve(MethodInsnNode call) {
			if (!nodes.containsKey(call.owner)) return Set.of();
			if (call.getOpcode() == Opcodes.INVOKESTATIC || call.getOpcode() == Opcodes.INVOKESPECIAL) {
				for (String owner = call.owner; owner != null && nodes.containsKey(owner); owner = nodes.get(owner).superName) {
					String ref = ref(owner, call.name, call.desc);
					if (methods.containsKey(ref)) return Set.of(ref);
				}
				return Set.of();
			}
			Set<String> targets = new LinkedHashSet<>();
			for (String type : related(call.owner)) {
				String ref = ref(type, call.name, call.desc);
				if (methods.containsKey(ref)) targets.add(ref);
			}
			return targets;
		}

		private final Map<String, Set<String>> related = new HashMap<>();
		private Map<String, Set<String>> subtypes;

		/** {@code type} with every supertype and subtype the jar declares: where a virtual call on it can land. */
		private Set<String> related(String type) {
			Set<String> known = related.get(type);
			if (known != null) return known;
			if (subtypes == null) {
				subtypes = new HashMap<>();
				for (ClassNode node : nodes.values()) {
					if (node.superName != null) subtypes.computeIfAbsent(node.superName, k -> new LinkedHashSet<>()).add(node.name);
					for (String parent : node.interfaces) subtypes.computeIfAbsent(parent, k -> new LinkedHashSet<>()).add(node.name);
				}
			}
			Set<String> all = new LinkedHashSet<>();
			walk(type, all, name -> {
				ClassNode node = nodes.get(name);
				if (node == null) return List.of();
				List<String> up = new ArrayList<>(node.interfaces);
				if (node.superName != null) up.add(node.superName);
				return up;
			});
			walk(type, all, name -> subtypes.getOrDefault(name, Set.of()));
			Set<String> result = Set.copyOf(all);
			related.put(type, result);
			return result;
		}

		private static void walk(String start, Set<String> into, java.util.function.Function<String, Collection<String>> next) {
			Deque<String> queue = new ArrayDeque<>(List.of(start));
			Set<String> seen = new HashSet<>(queue);
			while (!queue.isEmpty()) {
				String name = queue.poll();
				into.add(name);
				for (String following : next.apply(name)) if (seen.add(following)) queue.add(following);
			}
		}

		/** Every method, with the lifecycle phases of the jar that reach it through direct calls and class initialisation. */
		private Map<String, Set<Phase>> reach(Map<String, List<String>> entrypoints) {
			Map<String, Set<Phase>> reached = new HashMap<>();
			for (Phase phase : Phase.values()) {
				Deque<String> queue = new ArrayDeque<>(roots(entrypoints.getOrDefault(phase.key(), List.of()), phase));
				Set<String> seen = new HashSet<>(queue);
				while (!queue.isEmpty()) {
					String ref = queue.poll();
					reached.computeIfAbsent(ref, k -> EnumSet.noneOf(Phase.class)).add(phase);
					for (String next : edges(ref)) if (seen.add(next)) queue.add(next);
				}
			}
			return reached;
		}

		/** What Fabric calls on an entrypoint of {@code phase}: its class initialiser, constructor, and the phase method. */
		private Collection<String> roots(List<String> values, Phase phase) {
			Set<String> roots = new LinkedHashSet<>();
			for (String value : values) {
				int separator = value.indexOf("::");
				String owner = (separator < 0 ? value : value.substring(0, separator)).replace('.', '/');
				ClassNode node = nodes.get(owner);
				if (node == null) continue;
				for (MethodNode method : node.methods) {
					if (method.name.equals("<clinit>") || method.name.equals("<init>")) roots.add(ref(owner, method.name, method.desc));
					else if (separator >= 0 && method.name.equals(value.substring(separator + 2))) roots.add(ref(owner, method.name, method.desc));
				}
				if (separator < 0) {
					for (String type = owner; type != null && nodes.containsKey(type); type = nodes.get(type).superName) {
						String ref = ref(type, phase.method, "()V");
						if (methods.containsKey(ref)) {
							roots.add(ref);
							break;
						}
					}
				}
			}
			return roots;
		}

		/** Direct calls plus the class initialisers an instruction triggers. Never a lambda: that runs later, if ever. */
		private Set<String> edges(String ref) {
			Set<String> edges = new LinkedHashSet<>(callees.getOrDefault(ref, Set.of()));
			for (AbstractInsnNode insn : methods.get(ref).instructions) {
				String owner = null;
				if (insn instanceof TypeInsnNode type && insn.getOpcode() == Opcodes.NEW) owner = type.desc;
				else if (insn instanceof FieldInsnNode field && (insn.getOpcode() == Opcodes.GETSTATIC || insn.getOpcode() == Opcodes.PUTSTATIC)) owner = field.owner;
				else if (insn instanceof MethodInsnNode call && insn.getOpcode() == Opcodes.INVOKESTATIC) owner = call.owner;
				if (owner == null) continue;
				String clinit = ref(owner, "<clinit>", "()V");
				if (methods.containsKey(clinit)) edges.add(clinit);
			}
			return edges;
		}

		/**
		 * The no-argument method the dispatch calls on each entrypoint: the method reference it passes, or the type's
		 * single abstract method. Null when neither names one.
		 */
		private String[] contract(String type, Arg consumer) {
			if (consumer.kind() == LAMBDA) {
				Handle target = (Handle) consumer.payload();
				boolean onEntrypoint = target.getTag() == Opcodes.H_INVOKEINTERFACE || target.getTag() == Opcodes.H_INVOKEVIRTUAL;
				if (onEntrypoint && target.getOwner().equals(type) && Type.getArgumentTypes(target.getDesc()).length == 0) {
					return new String[] {target.getName(), target.getDesc()};
				}
			}
			ClassNode node = nodes.get(type);
			if (node == null) return null;
			Map<String, String[]> open = new LinkedHashMap<>();
			Set<String> implemented = new HashSet<>();
			collectAbstract(node, open, implemented, new HashSet<>());
			open.keySet().removeAll(implemented);
			if (open.size() != 1) return null;
			String[] only = open.values().iterator().next();
			return Type.getArgumentTypes(only[1]).length == 0 ? only : null;
		}

		private void collectAbstract(ClassNode node, Map<String, String[]> open, Set<String> implemented, Set<String> seen) {
			if (!seen.add(node.name)) return;
			for (MethodNode method : node.methods) {
				if ((method.access & Opcodes.ACC_STATIC) != 0 || method.name.startsWith("<")) continue;
				String signature = method.name + method.desc;
				if ((method.access & Opcodes.ACC_ABSTRACT) == 0) implemented.add(signature);
				else if (!isObjectMethod(method)) open.putIfAbsent(signature, new String[] {method.name, method.desc});
			}
			for (String parent : node.interfaces) {
				ClassNode next = nodes.get(parent);
				if (next != null) collectAbstract(next, open, implemented, seen);
			}
			ClassNode superclass = node.superName == null ? null : nodes.get(node.superName);
			if (superclass != null) collectAbstract(superclass, open, implemented, seen);
		}

		private static boolean isObjectMethod(MethodNode method) {
			return method.name.equals("equals") && method.desc.equals("(Ljava/lang/Object;)Z")
					|| method.name.equals("hashCode") && method.desc.equals("()I")
					|| method.name.equals("toString") && method.desc.equals("()Ljava/lang/String;");
		}

		private String site(String ref) {
			MethodNode method = methods.get(ref);
			return ownerOf.get(ref).replace('/', '.') + '.' + method.name;
		}
	}

	/** Two sites dispatching one key and type: the union of what reaches them; a derivable contract wins. */
	private static Dispatch combine(Dispatch a, Dispatch b) {
		Set<Phase> phases = EnumSet.noneOf(Phase.class);
		phases.addAll(a.phases());
		phases.addAll(b.phases());
		Dispatch richer = a.method() != null ? a : b;
		String site = a.phases().isEmpty() && !b.phases().isEmpty() ? b.site() : a.site();
		return new Dispatch(a.key(), a.type(), a.ownType(), richer.method(), richer.methodDescriptor(), phases, site);
	}

	private static String ref(String owner, String name, String desc) {
		return owner + '.' + name + desc;
	}
}
