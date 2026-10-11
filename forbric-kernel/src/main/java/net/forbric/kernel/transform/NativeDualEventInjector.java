/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.transform;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

import net.forbric.api.NativeEventDelivery;
import net.forbric.kernel.util.ByteScan;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import org.objectweb.asm.tree.analysis.*;

/**
 * Lets a bridge stand down wherever the merged game delivers its MinecraftForge event itself, on the same path as the
 * NeoForge event the bridge forwards from.
 *
 * <h2>Why</h2>
 *
 * <p>A bridge listens to a NeoForge event and posts the MinecraftForge counterpart, because the byte merge used to keep
 * one family's hook at each site. A merged method that now calls BOTH families' hooks on one path posts the
 * MinecraftForge event natively and forwarded, and a mod gets it twice. This scopes the NeoForge delivery on such a
 * path with the MinecraftForge events delivered natively there, so a bridge for one of them skips exactly that event
 * (the bridge side is {@code GameEventMultiplexer}'s guard, which reads {@link NativeEventDelivery#covered}).
 *
 * <h2>The sites</h2>
 *
 * <ul>
 *   <li>A MinecraftForge delivery (F-site): a static call into a MinecraftForge hook whose body, read from the game's
 *       resources, constructs a MinecraftForge event and posts on a MinecraftForge bus (directly or through another
 *       MinecraftForge hook it calls); or an inline post on a MinecraftForge bus whose argument is a constructed
 *       MinecraftForge event.</li>
 *   <li>A NeoForge delivery (N-site): a static call into a NeoForge hook that constructs and posts events itself, or
 *       an inline post on NeoForge's bus.</li>
 * </ul>
 *
 * <p>Only the MinecraftForge events some bridge re-posts are looked for (the set handed in), so nothing is touched that
 * no bridge would act on, and the order of the two families' calls is irrelevant.
 *
 * <h2>Same path</h2>
 *
 * <ul>
 *   <li>Proved, when the F-site's hook delivers on every path to a normal return: in the caller's control-flow graph
 *       with its exception edges, the F-site's normal completion dominates the N-site, or the F-site post-dominates the
 *       N-site's normal completion; either way the N-site cannot run again without the F-site. Only the NeoForge call
 *       changes.</li>
 *   <li>Recorded, for any other F-site that can run BEFORE the N-site: its call is wrapped so that what the
 *       MinecraftForge hook actually constructs (at its own level, not its listeners') is committed for the caller,
 *       and the N-site's scope covers it. A hook that posts only under a condition, or a pair of calls both families
 *       guard with the same test, covers exactly the invocations in which the MinecraftForge event was delivered. The
 *       record is cleared when the caller is entered, and spent when a bridge stands down on it.</li>
 * </ul>
 *
 * <p>Different branches, a delivery that can be skipped after the NeoForge one, or a NeoForge post repeated in a loop
 * keep their forward. The NeoForge hook classes are instrumented to report each event they construct and to mark their
 * own dispatch, so a scope captures the hook's events by identity and never one a listener constructs; the
 * MinecraftForge hook classes report theirs the same way to a recording. Names of callers, mods and events never decide
 * anything: the ecosystems' namespaces and bus types are the whole vocabulary, and both are parameters.
 */
public final class NativeDualEventInjector implements ClassTransformer {
	static final String API = "net/forbric/api/NativeEventDelivery", SCOPE = API + "$Scope", RECORDING = API + "$Recording";
	/** Every NeoForge-delivery wrapper this writes starts with this. */
	public static final String WRAPPER_PREFIX = "forbric$nativeDelivery$";
	/** Every recorded MinecraftForge-delivery wrapper this writes starts with this. */
	public static final String SOURCE_PREFIX = "forbric$nativeSource$";
	/** Deepest hook-to-hook delegation followed when reading what a hook delivers. */
	private static final int DEPTH = 3;

	/**
	 * The two families: whose hooks deliver natively (MinecraftForge) and whose events the bridges forward from
	 * (NeoForge), by namespace and bus type.
	 *
	 * @param forge    MinecraftForge's namespace, with a trailing slash
	 * @param forgeBus the package MinecraftForge's buses live in; a {@code post} on a type there is a MinecraftForge post
	 * @param neo      NeoForge's namespace
	 * @param neoBus   NeoForge's bus interface; a {@code post} on it is a NeoForge post
	 */
	public record Families(String forge, String forgeBus, String neo, String neoBus) {
		public static final Families DEFAULT = new Families("net/minecraftforge/", "net/minecraftforge/eventbus/",
				"net/neoforged/", "net/neoforged/bus/api/IEventBus");
	}

	private final Function<String, byte[]> resources;
	private final Set<String> bridged;
	private final Families families;
	private final byte[][] callerPrefixes;
	private final byte[][] neoHookNeedles;
	private final byte[][] forgeHookPrefixes;
	private final byte[][] post;
	/** Hook classes read from the game's resources, by internal name; read-only once cached. */
	private final Map<String, Optional<ClassNode>> parsed = new ConcurrentHashMap<>();
	private final Map<String, Set<String>> forgeAlways = new ConcurrentHashMap<>(), forgeMaybe = new ConcurrentHashMap<>();
	private final Map<String, Boolean> neoDeliveries = new ConcurrentHashMap<>();
	private final java.util.concurrent.atomic.LongAdder callersParsed = new java.util.concurrent.atomic.LongAdder();

	/**
	 * @param resources the game's class resources by path ({@code a/b/C.class}), for the hooks' bodies
	 * @param bridged   MinecraftForge event internal names some bridge re-posts from a NeoForge event
	 */
	public NativeDualEventInjector(Function<String, byte[]> resources, Set<String> bridged) {
		this(resources, bridged, Families.DEFAULT);
	}

	public NativeDualEventInjector(Function<String, byte[]> resources, Set<String> bridged, Families families) {
		this.resources = Objects.requireNonNull(resources);
		this.bridged = Set.copyOf(bridged);
		this.families = Objects.requireNonNull(families);
		this.callerPrefixes = new byte[][] {ByteScan.poolEntry(families.forge()), ByteScan.poolEntry(families.neo())};
		this.neoHookNeedles = new byte[][] {ByteScan.poolEntry(families.neoBus()), ByteScan.poolEntry("post")};
		this.forgeHookPrefixes = new byte[][] {ByteScan.poolEntry(families.forgeBus())};
		this.post = new byte[][] {ByteScan.poolEntry("post")};
	}

	@Override public String name() {
		return "forbric-native-dual-events";
	}

	@Override public AnchorSet anchors() {
		return AnchorSet.scanned("callers that deliver a bridged MinecraftForge event natively on a NeoForge delivery's "
				+ "path, and the two families' hooks whose constructions they observe");
	}

	/** Caller classes this instance parsed: the ones neither the pool prefilter nor the call scan could rule out. */
	long classesParsed() {
		return callersParsed.sum();
	}

	@Override public byte[] transform(String name, byte[] input, TransformContext context) {
		if (input == null || input.length < 10 || bridged.isEmpty()) return input;
		String internal = name.replace('.', '/');
		try {
			if (internal.startsWith(families.neo())) return instrumentNeoHooks(input);
			if (internal.startsWith(families.forge())) {
				return internal.startsWith(families.forgeBus()) ? input : instrumentForgeHooks(input);
			}
			return standDown(input);
		} catch (RuntimeException unprovable) {
			// A shape this cannot read proves nothing; the bridges keep forwarding, as before this existed.
			return input;
		}
	}

	// ---------------------------------------------------------------- callers

	private byte[] standDown(byte[] input) {
		boolean[] named = ByteScan.constantPoolPrefixes(input, callerPrefixes);
		if (!named[0] || !named[1] || !callsBothFamilies(input)) return input;
		ClassNode node = new ClassNode();
		new ClassReader(input).accept(node, ClassReader.EXPAND_FRAMES);
		callersParsed.increment();
		boolean itf = (node.access & Opcodes.ACC_INTERFACE) != 0;
		if (itf && (node.version & 0xFFFF) < Opcodes.V9) return input;
		int changes = 0;
		for (MethodNode method : List.copyOf(node.methods)) {
			if ((method.access & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE)) != 0) continue;
			if (method.name.startsWith(WRAPPER_PREFIX) || method.name.startsWith(SOURCE_PREFIX)) continue;
			changes += standDown(node, method, itf);
		}
		if (changes == 0) return input;
		ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
		node.accept(writer);
		return writer.toByteArray();
	}

	/** Whether one method of the class calls into both families: the only way it can deliver an event twice. */
	private boolean callsBothFamilies(byte[] input) {
		boolean[] found = new boolean[1];
		new ClassReader(input).accept(new ClassVisitor(Opcodes.ASM9) {
			@Override public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
				if (found[0]) return null;
				return new MethodVisitor(Opcodes.ASM9) {
					boolean forge, neo;
					@Override public void visitMethodInsn(int opcode, String owner, String name, String descriptor, boolean isInterface) {
						if (forgeCandidate(opcode, owner, name)) forge = true;
						if (neoCandidate(opcode, owner, name)) neo = true;
						if (forge && neo) found[0] = true;
					}
				};
			}
		}, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
		return found[0];
	}

	private boolean forgeCandidate(int opcode, String owner, String name) {
		return (opcode == Opcodes.INVOKESTATIC && owner.startsWith(families.forge()) && !owner.startsWith(families.forgeBus()))
				|| forgePost(owner, name);
	}

	private boolean neoCandidate(int opcode, String owner, String name) {
		return (opcode == Opcodes.INVOKESTATIC && owner.startsWith(families.neo())) || neoPost(owner, name);
	}

	private boolean forgePost(String owner, String name) {
		return owner.startsWith(families.forgeBus()) && name.equals("post");
	}

	private boolean neoPost(String owner, String name) {
		return owner.equals(families.neoBus()) && name.equals("post");
	}

	/**
	 * A delivery site in the caller.
	 *
	 * @param maybe  the bridged MinecraftForge events the site can deliver (F-sites)
	 * @param always the ones it delivers whenever it runs (F-sites)
	 */
	private record Site(MethodInsnNode call, int index, boolean inline, Set<String> maybe, Set<String> always) { }

	private int standDown(ClassNode owner, MethodNode method, boolean itf) {
		List<MethodInsnNode> forgeCalls = new ArrayList<>(), neoCalls = new ArrayList<>();
		for (AbstractInsnNode instruction : method.instructions) {
			if (!(instruction instanceof MethodInsnNode call)) continue;
			if (forgeCandidate(call.getOpcode(), call.owner, call.name)) forgeCalls.add(call);
			if (neoCandidate(call.getOpcode(), call.owner, call.name)) neoCalls.add(call);
		}
		if (forgeCalls.isEmpty() || neoCalls.isEmpty()) return 0;
		List<Site> neoSites = new ArrayList<>();
		for (MethodInsnNode call : neoCalls) {
			boolean inline = neoPost(call.owner, call.name);
			if (inline ? inlineWrappable(call) : neoDelivers(call.owner, call.name, call.desc, 0)) {
				neoSites.add(new Site(call, method.instructions.indexOf(call), inline, Set.of(), Set.of()));
			}
		}
		if (neoSites.isEmpty()) return 0;
		Flow flow;
		try {
			flow = new Flow(owner.name, method);
		} catch (AnalyzerException unanalysable) {
			return 0;
		}
		List<Site> forgeSites = new ArrayList<>();
		for (MethodInsnNode call : forgeCalls) {
			int index = method.instructions.indexOf(call);
			if (flow.frames[index] == null) continue;
			Set<String> maybe, always;
			boolean inline = forgePost(call.owner, call.name);
			if (inline) {
				String event = inlineWrappable(call) ? postedEvent(method, flow, call) : null;
				maybe = always = event != null && bridged.contains(event) ? Set.of(event) : Set.of();
			} else {
				maybe = bridgedOf(forgeMaybe(call.owner, call.name, call.desc, 0));
				always = bridgedOf(forgeAlways(call.owner, call.name, call.desc, 0));
			}
			if (!maybe.isEmpty()) forgeSites.add(new Site(call, index, inline, maybe, always));
		}
		if (forgeSites.isEmpty()) return 0;

		String group = identity(owner.name + "#" + method.name + method.desc).substring(0, 32);
		Set<Site> recorded = new LinkedHashSet<>();
		int changes = 0;
		for (Site neo : neoSites) {
			if (flow.frames[neo.index()] == null) continue;
			Set<String> proved = new TreeSet<>(), candidates = new TreeSet<>();
			for (Site forge : forgeSites) {
				int f = forge.index(), n = neo.index();
				if (!forge.always().isEmpty() && (dominates(flow, f, n) || postDominates(flow, f, n))) {
					// Proved from the bytecode alone: the caller is left exactly as it was apart from the NeoForge call.
					proved.addAll(forge.always());
				} else if (flow.reachesFrom(f, n)) {
					candidates.addAll(forge.maybe());
					recorded.add(forge);
				}
			}
			if (proved.isEmpty() && candidates.isEmpty()) continue;
			if (wrapNeo(owner, neo, String.join(";", proved), group, String.join(";", candidates), itf)) changes++;
		}
		if (changes == 0) return 0;
		boolean recording = false;
		for (Site forge : recorded) recording |= wrapForge(owner, forge, group, itf);
		if (recording) {
			InsnList enter = new InsnList();
			enter.add(new LdcInsnNode(group));
			enter.add(new MethodInsnNode(Opcodes.INVOKESTATIC, API, "enterNative", "(Ljava/lang/String;)V", false));
			method.instructions.insert(enter);
		}
		return changes;
	}

	private Set<String> bridgedOf(Set<String> events) {
		Set<String> out = new TreeSet<>(events);
		out.retainAll(bridged);
		return Set.copyOf(out);
	}

	/**
	 * Whether every path to the NeoForge site at {@code n} passes the MinecraftForge site at {@code f} and completes it
	 * normally (an exception edge out of {@code f} borrows nothing), and {@code n} cannot come round again without it.
	 */
	static boolean dominates(Flow flow, int f, int n) {
		return !flow.reaches(0, n, -1, f, f + 1) && !flow.reachesAgain(n, f, f + 1);
	}

	/**
	 * Whether the MinecraftForge site at {@code f} runs after every normal completion of the NeoForge site at {@code n},
	 * before the method can leave, and {@code n} cannot come round again without it.
	 */
	static boolean postDominates(Flow flow, int f, int n) {
		return !flow.reachesExit(n + 1, f) && !flow.reaches(n + 1, n, f, -1, -1);
	}

	/** The event class an inline post's argument was constructed as, traced back through locals. */
	private static String postedEvent(MethodNode method, Flow flow, MethodInsnNode post) {
		Frame<SourceValue> frame = flow.frames[method.instructions.indexOf(post)];
		if (frame == null || frame.getStackSize() < 1) return null;
		return constructed(method, flow, frame.getStack(frame.getStackSize() - 1), new HashSet<>());
	}

	private static String constructed(MethodNode method, Flow flow, SourceValue value, Set<AbstractInsnNode> seen) {
		if (value == null || value.insns.size() != 1) return null;
		AbstractInsnNode source = value.insns.iterator().next();
		if (!seen.add(source)) return null;
		if (source instanceof TypeInsnNode type && type.getOpcode() == Opcodes.NEW) return type.desc;
		Frame<SourceValue> frame = flow.frames[method.instructions.indexOf(source)];
		if (frame == null) return null;
		int opcode = source.getOpcode();
		if (opcode == Opcodes.DUP || opcode == Opcodes.ASTORE || opcode == Opcodes.CHECKCAST) {
			return frame.getStackSize() < 1 ? null : constructed(method, flow, frame.getStack(frame.getStackSize() - 1), seen);
		}
		if (source instanceof VarInsnNode load && opcode == Opcodes.ALOAD) return constructed(method, flow, frame.getLocal(load.var), seen);
		return null;
	}

	/** An inline post whose last argument, the event, is an object a wrapper can hand over. */
	private static boolean inlineWrappable(MethodInsnNode call) {
		if (call.getOpcode() != Opcodes.INVOKEINTERFACE && call.getOpcode() != Opcodes.INVOKEVIRTUAL) return false;
		Type[] args = Type.getArgumentTypes(call.desc);
		return args.length > 0 && args[args.length - 1].getSort() == Type.OBJECT;
	}

	/** A wrapper's descriptor: the call's own, with an inline post's receiver as its first parameter. */
	private static String wrapperDesc(MethodInsnNode call, boolean inline) {
		return inline ? "(L" + call.owner + ";" + call.desc.substring(1) : call.desc;
	}

	private boolean wrapNeo(ClassNode owner, Site site, String proved, String group, String candidates, boolean itf) {
		MethodInsnNode call = site.call();
		String desc = wrapperDesc(call, site.inline());
		String name = WRAPPER_PREFIX + identity((site.inline() ? "post " : "hook ") + call.owner + "." + call.name + call.desc
				+ " " + proved + " " + group + " " + candidates);
		MethodNode existing = find(owner, name, desc);
		if (existing == null) {
			InsnList open = new InsnList();
			open.add(new LdcInsnNode(proved));
			open.add(new LdcInsnNode(group));
			open.add(new LdcInsnNode(candidates));
			if (site.inline()) {
				open.add(new MethodInsnNode(Opcodes.INVOKESTATIC, API, "begin",
						"(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)L" + SCOPE + ";", false));
			} else {
				open.add(new LdcInsnNode(Type.getObjectType(call.owner)));
				open.add(new MethodInsnNode(Opcodes.INVOKESTATIC, API, "begin",
						"(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/Class;)L" + SCOPE + ";", false));
			}
			owner.methods.add(wrapper(name, desc, call, open, SCOPE, site.inline() ? "capture" : null));
		} else if (!calls(existing, API, "begin")) {
			return false;
		}
		redirect(call, owner.name, name, desc, itf);
		return true;
	}

	private boolean wrapForge(ClassNode owner, Site site, String group, boolean itf) {
		MethodInsnNode call = site.call();
		String candidates = String.join(";", site.maybe());
		String desc = wrapperDesc(call, site.inline());
		String name = SOURCE_PREFIX + identity((site.inline() ? "post " : "hook ") + call.owner + "." + call.name + call.desc
				+ " " + group + " " + candidates);
		MethodNode existing = find(owner, name, desc);
		if (existing == null) {
			InsnList open = new InsnList();
			open.add(new LdcInsnNode(group));
			open.add(new LdcInsnNode(candidates));
			open.add(new MethodInsnNode(Opcodes.INVOKESTATIC, API, "record",
					"(Ljava/lang/String;Ljava/lang/String;)L" + RECORDING + ";", false));
			owner.methods.add(wrapper(name, desc, call, open, RECORDING, site.inline() ? "delivered" : null));
		} else if (!calls(existing, API, "record")) {
			return false;
		}
		redirect(call, owner.name, name, desc, itf);
		return true;
	}

	private static MethodNode find(ClassNode owner, String name, String desc) {
		for (MethodNode m : owner.methods) if (m.name.equals(name) && m.desc.equals(desc)) return m;
		return null;
	}

	private static void redirect(MethodInsnNode call, String owner, String name, String desc, boolean itf) {
		call.setOpcode(Opcodes.INVOKESTATIC);
		call.owner = owner;
		call.name = name;
		call.desc = desc;
		call.itf = itf;
	}

	/**
	 * {@code static ret name(args) { T guard = <open>; [guard.<handOver>(lastArg);] try { return call(args); } finally {
	 * guard.close(); } }}, with {@code T} the scope or recording type {@code open} leaves on the stack.
	 */
	private static MethodNode wrapper(String name, String desc, MethodInsnNode call, InsnList open, String guardType, String handOver) {
		MethodNode method = new MethodNode(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_SYNTHETIC, name, desc, null, null);
		Type[] args = Type.getArgumentTypes(desc);
		Type result = Type.getReturnType(desc);
		List<Object> locals = new ArrayList<>();
		int slot = 0, last = 0;
		for (Type arg : args) {
			locals.add(frameType(arg));
			last = slot;
			slot += arg.getSize();
		}
		int guard = slot, value = guard + 1;
		locals.add(guardType);
		InsnList code = method.instructions;
		code.add(open);
		code.add(new VarInsnNode(Opcodes.ASTORE, guard));
		LabelNode start = new LabelNode(), end = new LabelNode(), handler = new LabelNode();
		code.add(start);
		if (handOver != null) {
			code.add(new VarInsnNode(Opcodes.ALOAD, guard));
			code.add(new VarInsnNode(Opcodes.ALOAD, last));
			code.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, guardType, handOver, "(Ljava/lang/Object;)V", false));
		}
		slot = 0;
		for (Type arg : args) {
			code.add(new VarInsnNode(arg.getOpcode(Opcodes.ILOAD), slot));
			slot += arg.getSize();
		}
		code.add(new MethodInsnNode(call.getOpcode(), call.owner, call.name, call.desc, call.itf));
		code.add(end);
		if (result.getSort() != Type.VOID) code.add(new VarInsnNode(result.getOpcode(Opcodes.ISTORE), value));
		code.add(new VarInsnNode(Opcodes.ALOAD, guard));
		code.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, guardType, "close", "()V", false));
		if (result.getSort() != Type.VOID) code.add(new VarInsnNode(result.getOpcode(Opcodes.ILOAD), value));
		code.add(new InsnNode(result.getOpcode(Opcodes.IRETURN)));
		code.add(handler);
		code.add(new FrameNode(Opcodes.F_NEW, locals.size(), locals.toArray(), 1, new Object[] {"java/lang/Throwable"}));
		code.add(new VarInsnNode(Opcodes.ASTORE, value));
		code.add(new VarInsnNode(Opcodes.ALOAD, guard));
		code.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, guardType, "close", "()V", false));
		code.add(new VarInsnNode(Opcodes.ALOAD, value));
		code.add(new InsnNode(Opcodes.ATHROW));
		method.tryCatchBlocks.add(new TryCatchBlockNode(start, end, handler, null));
		method.maxLocals = value + Math.max(1, result.getSize());
		method.maxStack = Math.max(4, slot + 1);
		return method;
	}

	private static Object frameType(Type type) {
		return switch (type.getSort()) {
			case Type.LONG -> Opcodes.LONG;
			case Type.DOUBLE -> Opcodes.DOUBLE;
			case Type.FLOAT -> Opcodes.FLOAT;
			case Type.ARRAY -> type.getDescriptor();
			case Type.OBJECT -> type.getInternalName();
			default -> Opcodes.INTEGER;
		};
	}

	// ---------------------------------------------------------------- what the hooks deliver

	/**
	 * The MinecraftForge events a call to this hook delivers whenever it runs: constructed and posted on a
	 * MinecraftForge bus on every path to a normal return, by the hook or by a MinecraftForge hook it calls on every
	 * such path.
	 */
	Set<String> forgeAlways(String owner, String name, String desc, int depth) {
		return forgeDeliveries(owner, name, desc, depth, true);
	}

	/** The MinecraftForge events a call to this hook can deliver: constructed by it, or a hook it calls, that posts. */
	Set<String> forgeMaybe(String owner, String name, String desc, int depth) {
		return forgeDeliveries(owner, name, desc, depth, false);
	}

	private Set<String> forgeDeliveries(String owner, String name, String desc, int depth, boolean always) {
		Map<String, Set<String>> cache = always ? forgeAlways : forgeMaybe;
		String key = owner + "." + name + desc;
		Set<String> known = cache.get(key);
		if (known != null) return known;
		if (depth > DEPTH) return Set.of();
		// The cached hook classes are shared by the threads that load classes, and an Analyzer or an InsnList index
		// writes into the nodes it reads, so the first reading of each hook is serialized; later ones hit the cache.
		synchronized (parsed) {
			known = cache.get(key);
			if (known != null) return known;
			Set<String> out = readForgeDeliveries(owner, name, desc, depth, always);
			cache.putIfAbsent(key, out);
			return out;
		}
	}

	private Set<String> readForgeDeliveries(String owner, String name, String desc, int depth, boolean always) {
		MethodNode hook = staticMethod(owner, name, desc);
		if (hook == null) return Set.of();
		Flow flow;
		try {
			flow = new Flow(owner, hook);
		} catch (AnalyzerException unanalysable) {
			return Set.of();
		}
		List<Integer> exits = new ArrayList<>();
		for (int i = 0; i < hook.instructions.size(); i++) {
			int opcode = hook.instructions.get(i).getOpcode();
			if (opcode >= Opcodes.IRETURN && opcode <= Opcodes.RETURN && flow.frames[i] != null) exits.add(i);
		}
		if (always && exits.isEmpty()) return Set.of();
		boolean posts = false;
		for (int i = 0; i < hook.instructions.size() && !posts; i++) {
			if (hook.instructions.get(i) instanceof MethodInsnNode call && forgePost(call.owner, call.name) && flow.frames[i] != null
					&& (!always || flow.onEveryPath(i, exits))) posts = true;
		}
		Set<String> out = new TreeSet<>();
		for (int i = 0; i < hook.instructions.size(); i++) {
			AbstractInsnNode instruction = hook.instructions.get(i);
			if (flow.frames[i] == null || (always && !flow.onEveryPath(i, exits))) continue;
			if (posts && instruction instanceof TypeInsnNode type && type.getOpcode() == Opcodes.NEW
					&& type.desc.startsWith(families.forge())) out.add(type.desc);
			if (instruction instanceof MethodInsnNode call && call.getOpcode() == Opcodes.INVOKESTATIC
					&& call.owner.startsWith(families.forge()) && !call.owner.startsWith(families.forgeBus())
					&& !(call.owner + "." + call.name + call.desc).equals(owner + "." + name + desc)) {
				out.addAll(forgeDeliveries(call.owner, call.name, call.desc, depth + 1, always));
			}
		}
		return Set.copyOf(out);
	}

	/**
	 * Whether a call to this NeoForge hook can post an event whose construction it reports: the hook constructs and
	 * posts itself, or calls a hook of its own class that does. Its class is then one {@link #instrumentNeoHooks}
	 * rewrites.
	 */
	boolean neoDelivers(String owner, String name, String desc, int depth) {
		String key = owner + "." + name + desc;
		Boolean known = neoDeliveries.get(key);
		if (known != null) return known;
		if (depth > DEPTH) return false;
		synchronized (parsed) {
			known = neoDeliveries.get(key);
			return known != null ? known : readNeoDelivers(owner, name, desc, depth, key);
		}
	}

	private boolean readNeoDelivers(String owner, String name, String desc, int depth, String key) {
		MethodNode hook = staticMethod(owner, name, desc);
		boolean out = false;
		if (hook != null) {
			out = constructsAndPosts(hook);
			for (AbstractInsnNode instruction : hook.instructions) {
				if (out) break;
				if (instruction instanceof MethodInsnNode call && call.getOpcode() == Opcodes.INVOKESTATIC
						&& call.owner.equals(owner) && !(call.name + call.desc).equals(name + desc)) {
					out = neoDelivers(call.owner, call.name, call.desc, depth + 1);
				}
			}
		}
		neoDeliveries.putIfAbsent(key, out);
		return out;
	}

	private boolean constructsAndPosts(MethodNode hook) {
		boolean posts = false, constructs = false;
		for (AbstractInsnNode instruction : hook.instructions) {
			if (instruction instanceof MethodInsnNode call && neoPost(call.owner, call.name)) posts = true;
			if (instruction instanceof TypeInsnNode type && type.getOpcode() == Opcodes.NEW && type.desc.startsWith(families.neo())) {
				constructs = true;
			}
		}
		return posts && constructs;
	}

	private MethodNode staticMethod(String owner, String name, String desc) {
		ClassNode node = read(owner);
		if (node == null) return null;
		for (MethodNode method : node.methods) {
			if (method.name.equals(name) && method.desc.equals(desc)) {
				int access = method.access;
				return (access & Opcodes.ACC_STATIC) != 0 && (access & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE)) == 0 ? method : null;
			}
		}
		return null;
	}

	private ClassNode read(String owner) {
		Optional<ClassNode> cached = parsed.get(owner);
		if (cached != null) return cached.orElse(null);
		byte[] bytes = resources.apply(owner + ".class");
		if (bytes == null) return null;
		ClassNode node = new ClassNode();
		new ClassReader(bytes).accept(node, ClassReader.SKIP_FRAMES | ClassReader.SKIP_DEBUG);
		Optional<ClassNode> read = Optional.ofNullable(owner.equals(node.name) ? node : null);
		Optional<ClassNode> raced = parsed.putIfAbsent(owner, read);
		return (raced == null ? read : raced).orElse(null);
	}

	// ---------------------------------------------------------------- the hooks themselves

	/**
	 * Has each static NeoForge hook that posts report the events it constructs and bracket its own posts, so a scope
	 * opened around a call into it captures the hook's own events and none a listener constructs.
	 */
	private byte[] instrumentNeoHooks(byte[] input) {
		boolean[] named = ByteScan.constantPoolNames(input, neoHookNeedles);
		if (!named[0] || !named[1]) return input;
		return instrument(input, families.neo(), (owner, name) -> neoPost(owner, name), "constructed", "dispatching",
				"dispatched", true);
	}

	/** The same for MinecraftForge's hooks, reporting to the recording a caller opened around a call into one. */
	private byte[] instrumentForgeHooks(byte[] input) {
		if (!ByteScan.constantPoolPrefixes(input, forgeHookPrefixes)[0] || !ByteScan.constantPoolNames(input, post)[0]) return input;
		return instrument(input, families.forge(), this::forgePost, "nativeConstructed", "nativeDispatching",
				"nativeDispatched", false);
	}

	private interface PostTest {
		boolean posts(String owner, String name);
	}

	private byte[] instrument(byte[] input, String namespace, PostTest post, String constructed, String dispatching,
			String dispatched, boolean neo) {
		ClassNode node = new ClassNode();
		new ClassReader(input).accept(node, ClassReader.EXPAND_FRAMES);
		boolean reporting = false, edited = false;
		for (MethodNode method : node.methods) {
			if ((method.access & Opcodes.ACC_STATIC) == 0 || (method.access & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE)) != 0) continue;
			if (calls(method, API, dispatching)) {
				// Already instrumented by an earlier pass: a second one would report every construction twice.
				reporting = true;
				continue;
			}
			List<MethodInsnNode> posts = new ArrayList<>();
			for (AbstractInsnNode instruction : method.instructions) {
				if (instruction instanceof MethodInsnNode call && post.posts(call.owner, call.name)) posts.add(call);
			}
			if (posts.isEmpty()) continue;
			Frame<SourceValue>[] frames;
			try {
				frames = new Analyzer<>(new SourceInterpreter()).analyze(node.name, method);
			} catch (AnalyzerException unanalysable) {
				continue;
			}
			List<MethodInsnNode> constructions = new ArrayList<>();
			for (int i = 0; i + 1 < method.instructions.size(); i++) {
				if (!(method.instructions.get(i) instanceof MethodInsnNode init) || init.getOpcode() != Opcodes.INVOKESPECIAL
						|| !init.name.equals("<init>") || !init.owner.startsWith(namespace)) continue;
				Frame<SourceValue> after = frames[i + 1];
				if (after == null || after.getStackSize() < 1) continue;
				if (init.owner.equals(madeBy(method, frames, after.getStack(after.getStackSize() - 1)))) constructions.add(init);
			}
			for (MethodInsnNode init : constructions) {
				InsnList report = new InsnList();
				report.add(new InsnNode(Opcodes.DUP));
				report.add(new MethodInsnNode(Opcodes.INVOKESTATIC, API, constructed, "(Ljava/lang/Object;)V", false));
				method.instructions.insert(init, report);
			}
			for (MethodInsnNode call : posts) {
				method.instructions.insertBefore(call, new MethodInsnNode(Opcodes.INVOKESTATIC, API, dispatching, "()V", false));
				method.instructions.insert(call, new MethodInsnNode(Opcodes.INVOKESTATIC, API, dispatched, "()V", false));
			}
			reporting = edited = true;
		}
		// Recorded before the class is defined, so a wrapper never opens a live scope around an unreported hook.
		if (reporting && neo) NativeEventDelivery.instrumented(node.name);
		if (!edited) return input;
		ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
		node.accept(writer);
		return writer.toByteArray();
	}

	/**
	 * The class a {@code NEW} that {@code value} still is was allocating: the value left on the stack once
	 * {@code <init>} has consumed its {@code DUP}. The analyzer may attribute both copies to the {@code DUP}, so one
	 * {@code DUP} is followed back to the {@code NEW} it copied.
	 */
	private static String madeBy(MethodNode method, Frame<SourceValue>[] frames, SourceValue value) {
		if (value == null || value.insns.size() != 1) return null;
		AbstractInsnNode source = value.insns.iterator().next();
		if (source.getOpcode() == Opcodes.DUP) {
			Frame<SourceValue> before = frames[method.instructions.indexOf(source)];
			if (before == null || before.getStackSize() < 1) return null;
			SourceValue copied = before.getStack(before.getStackSize() - 1);
			if (copied.insns.size() != 1) return null;
			source = copied.insns.iterator().next();
		}
		return source instanceof TypeInsnNode made && made.getOpcode() == Opcodes.NEW ? made.desc : null;
	}

	// ---------------------------------------------------------------- control flow

	/** A method's control-flow graph, exception edges included, with the source frames an inline post is traced on. */
	static final class Flow {
		final Map<Integer, Set<Integer>> edges = new HashMap<>();
		final Frame<SourceValue>[] frames;
		final boolean[] exit;

		Flow(String owner, MethodNode method) throws AnalyzerException {
			frames = new Analyzer<SourceValue>(new SourceInterpreter()) {
				@Override protected void newControlFlowEdge(int from, int to) {
					edges.computeIfAbsent(from, k -> new HashSet<>()).add(to);
				}

				@Override protected boolean newControlFlowExceptionEdge(int from, int to) {
					newControlFlowEdge(from, to);
					return true;
				}
			}.analyze(owner, method);
			exit = new boolean[method.instructions.size()];
			for (int i = 0; i < exit.length; i++) {
				int opcode = method.instructions.get(i).getOpcode();
				exit[i] = (opcode >= Opcodes.IRETURN && opcode <= Opcodes.RETURN) || opcode == Opcodes.ATHROW;
			}
		}

		/**
		 * Whether {@code goal} is reachable from {@code start} without passing node {@code avoided} and without the edge
		 * {@code omittedFrom → omittedTo} (-1 for neither).
		 */
		boolean reaches(int start, int goal, int avoided, int omittedFrom, int omittedTo) {
			if (start == avoided || start >= exit.length) return false;
			Set<Integer> seen = new HashSet<>();
			ArrayDeque<Integer> work = new ArrayDeque<>();
			work.add(start);
			while (!work.isEmpty()) {
				int at = work.removeFirst();
				if (at == goal) return true;
				if (!seen.add(at)) continue;
				for (int next : edges.getOrDefault(at, Set.of())) {
					if (next == avoided || (at == omittedFrom && next == omittedTo)) continue;
					work.add(next);
				}
			}
			return false;
		}

		/** Whether {@code node} can run again once it has run, without the edge {@code omittedFrom → omittedTo}. */
		boolean reachesAgain(int node, int omittedFrom, int omittedTo) {
			for (int next : edges.getOrDefault(node, Set.of())) {
				if (node == omittedFrom && next == omittedTo) continue;
				if (reaches(next, node, -1, omittedFrom, omittedTo)) return true;
			}
			return false;
		}

		/** Whether {@code goal} can run after {@code node} has run. */
		boolean reachesFrom(int node, int goal) {
			for (int next : edges.getOrDefault(node, Set.of())) if (reaches(next, goal, -1, -1, -1)) return true;
			return false;
		}

		/** Whether a return or throw is reachable from {@code start} without passing node {@code avoided}. */
		boolean reachesExit(int start, int avoided) {
			if (start == avoided || start >= exit.length) return false;
			Set<Integer> seen = new HashSet<>();
			ArrayDeque<Integer> work = new ArrayDeque<>();
			work.add(start);
			while (!work.isEmpty()) {
				int at = work.removeFirst();
				if (!seen.add(at)) continue;
				if (exit[at]) return true;
				for (int next : edges.getOrDefault(at, Set.of())) if (next != avoided) work.add(next);
			}
			return false;
		}

		/** Whether node {@code node} lies on every path from the entry to each of {@code exits}. */
		boolean onEveryPath(int node, List<Integer> exits) {
			for (int exitIndex : exits) if (exitIndex != node && reaches(0, exitIndex, node, -1, -1)) return false;
			return true;
		}
	}

	private static String identity(String id) {
		try {
			return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
					.digest(id.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
		} catch (java.security.NoSuchAlgorithmException impossible) {
			throw new AssertionError(impossible);
		}
	}

	private static boolean calls(MethodNode method, String owner, String name) {
		for (AbstractInsnNode i : method.instructions) {
			if (i instanceof MethodInsnNode call && call.owner.equals(owner) && call.name.equals(name)) return true;
		}
		return false;
	}
}
