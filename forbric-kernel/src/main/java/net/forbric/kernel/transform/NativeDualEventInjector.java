/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.transform;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

import net.forbric.api.NativeEventDelivery;
import net.forbric.kernel.util.ByteScan;
import org.objectweb.asm.*;
import org.objectweb.asm.commons.AnalyzerAdapter;
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
 *       N-site's normal completion; either way the N-site cannot run again without the F-site. An F-site that dominates
 *       is recorded as well, for its cascade (what MinecraftForge's own listeners post inside its dispatch): a NeoForge
 *       event NeoForge's own listeners post inside the NeoForge call, whose MinecraftForge counterpart that cascade
 *       holds, is delivered natively too.</li>
 *   <li>Recorded, for any other F-site that can run BEFORE the N-site: its call is bracketed so that what the
 *       MinecraftForge hook actually constructs (at its own level, not its listeners') is committed for the caller,
 *       and the N-site's scope covers it. A hook that posts only under a condition, or a pair of calls both families
 *       guard with the same test, covers exactly the invocations in which the MinecraftForge event was delivered. The
 *       record belongs to that invocation of the caller, and is spent when a bridge stands down on it.</li>
 *   <li>Faithful, for an F-site after the N-site that the caller may bypass, judged from MinecraftForge's own patched
 *       body of the same method ({@link NativeDeliveryPaths}): every edge on which the caller commits to bypassing the
 *       F-site is classified as one MinecraftForge's body bypasses its call on as well (a guard both bodies have), or
 *       one only the merged game has (an exit NeoForge's patch added). The native call is then the delivery
 *       MinecraftForge itself would make, so the bridge stands down for the whole NeoForge delivery and leaves what it
 *       owes with the caller; each bypass edge settles it ({@link NativeEventDelivery#bypassed}): delivered on a way
 *       only the merged game has, excused on a shared one. A body that cannot be judged keeps its forward.</li>
 * </ul>
 *
 * <p>The caller's own calls stay where they are: a scope (or a recording) opens in front of the very hook call or post
 * it is for and closes right behind it, and an exception out of the call closes it on the way through the method's own
 * handlers, so a guest mixin that targets one of those calls ({@code @At("INVOKE")}) finds the instruction it targeted.
 * What a caller records, and what its stand-downs owe, belongs to one invocation of it, kept in a local of its own
 * ({@link NativeEventDelivery.Invocation}): a caller re-entered from inside its own NeoForge dispatch settles only its own.
 *
 * <p>Different branches, an F-site the caller may bypass where MinecraftForge's own body cannot be read, or a NeoForge
 * post repeated in a loop keep their forward. The NeoForge hook classes are instrumented to report each event they construct and to mark their
 * own dispatch, so a scope captures the hook's events by identity and never one a listener constructs; the
 * MinecraftForge hook classes report theirs the same way to a recording. Names of callers, mods and events never decide
 * anything: the ecosystems' namespaces and bus types are the whole vocabulary, and both are parameters.
 */
public final class NativeDualEventInjector implements ClassTransformer {
	static final String API = "net/forbric/api/NativeEventDelivery", SCOPE = API + "$Scope", RECORDING = API + "$Recording",
			INVOCATION = API + "$Invocation";
	/** {@code begin(natively, invocation, candidates, faithful, hook)}: a scope around a call into a NeoForge hook. */
	static final String BEGIN_HOOK = "(Ljava/lang/String;L" + INVOCATION + ";Ljava/lang/String;Ljava/lang/String;Ljava/lang/Class;)L" + SCOPE + ";";
	/** {@code begin(natively, invocation, candidates, faithful)}: a scope around an inline NeoForge post. */
	static final String BEGIN_POST = "(Ljava/lang/String;L" + INVOCATION + ";Ljava/lang/String;Ljava/lang/String;)L" + SCOPE + ";";
	/** {@code record(invocation, candidates)}: a recording around one MinecraftForge delivery. */
	static final String RECORD = "(L" + INVOCATION + ";Ljava/lang/String;)L" + RECORDING + ";";
	/** Deepest hook-to-hook delegation followed when reading what a hook delivers. */
	private static final int DEPTH = 3;

	/**
	 * The two families: whose hooks deliver natively (MinecraftForge) and whose events the bridges forward from
	 * (NeoForge), by namespace and bus type.
	 *
	 * @param forge        MinecraftForge's namespace, with a trailing slash
	 * @param forgeBus     the package MinecraftForge's buses live in; a {@code post} on a type there is a MinecraftForge post
	 * @param neo          NeoForge's namespace
	 * @param neoBus       NeoForge's bus interface; a {@code post} on it is a NeoForge post
	 * @param neoModLoader NeoForge's mod-bus dispatcher, or null: a static method of it that takes a {@code neoEvent}
	 *                     fans that event out to every mod's bus, and is a NeoForge post as well
	 * @param neoEvent     the type every NeoForge event extends
	 */
	public record Families(String forge, String forgeBus, String neo, String neoBus, String neoModLoader, String neoEvent) {
		public static final Families DEFAULT = new Families("net/minecraftforge/", "net/minecraftforge/eventbus/",
				"net/neoforged/", "net/neoforged/bus/api/IEventBus", "net/neoforged/fml/ModLoader", "net/neoforged/bus/api/Event");

		/** Without a mod-bus dispatcher: only {@code post} on the bus interface is a NeoForge post. */
		public Families(String forge, String forgeBus, String neo, String neoBus) {
			this(forge, forgeBus, neo, neoBus, null, null);
		}
	}

	private final Function<String, byte[]> resources;
	/** MinecraftForge's own patched game class by internal name, or null where none can be read. */
	private final Function<String, ClassNode> forgeReference;
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
		this(resources, bridged, families, owner -> null);
	}

	/**
	 * @param forgeReference MinecraftForge's own patched game class by internal name (the merged base's native reference
	 *                       of it), or null: what a caller's F-site is judged against when the caller may bypass it
	 */
	public NativeDualEventInjector(Function<String, byte[]> resources, Set<String> bridged, Families families,
			Function<String, ClassNode> forgeReference) {
		this.resources = Objects.requireNonNull(resources);
		this.forgeReference = Objects.requireNonNull(forgeReference);
		this.bridged = Set.copyOf(bridged);
		this.families = Objects.requireNonNull(families);
		this.callerPrefixes = new byte[][] {ByteScan.poolEntry(families.forge()), ByteScan.poolEntry(families.neo())};
		this.neoHookNeedles = families.neoModLoader() == null
				? new byte[][] {ByteScan.poolEntry(families.neoBus()), ByteScan.poolEntry("post")}
				: new byte[][] {ByteScan.poolEntry(families.neoBus()), ByteScan.poolEntry("post"), ByteScan.poolEntry(families.neoModLoader())};
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
			// The mod-bus dispatcher is what a NeoForge post goes through, not a hook that constructs one.
			if (internal.equals(families.neoModLoader())) return input;
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
		// Without stack map frames a bracket's handler could not be described.
		if ((node.version & 0xFFFF) < Opcodes.V1_7) return input;
		int changes = 0;
		Map<String, Optional<ClassNode>> read = new HashMap<>();
		for (MethodNode method : List.copyOf(node.methods)) {
			if ((method.access & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE)) != 0) continue;
			changes += standDown(node, method, read);
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
						if (neoCandidate(opcode, owner, name, descriptor)) neo = true;
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

	private boolean neoCandidate(int opcode, String owner, String name, String desc) {
		return (opcode == Opcodes.INVOKESTATIC && owner.startsWith(families.neo())) || neoPost(owner, name, desc);
	}

	private boolean forgePost(String owner, String name) {
		return owner.startsWith(families.forgeBus()) && name.equals("post");
	}

	/**
	 * A NeoForge post: {@code post} on its bus interface, or a static method of its mod-bus dispatcher that takes an
	 * event (which fans the event out to every mod's bus, phase by phase).
	 */
	private boolean neoPost(String owner, String name, String desc) {
		if (owner.equals(families.neoBus()) && name.equals("post")) return true;
		return families.neoModLoader() != null && owner.equals(families.neoModLoader()) && eventArgument(desc) >= 0;
	}

	/** The index of the first argument of {@code desc} whose type is NeoForge's event type, or -1. */
	private int eventArgument(String desc) {
		if (families.neoEvent() == null) return -1;
		String event = "L" + families.neoEvent() + ";";
		Type[] args = Type.getArgumentTypes(desc);
		for (int i = 0; i < args.length; i++) if (args[i].getDescriptor().equals(event)) return i;
		return -1;
	}

	/**
	 * A delivery site in the caller.
	 *
	 * @param maybe  the bridged MinecraftForge events the site can deliver (F-sites)
	 * @param always the ones it delivers whenever it runs (F-sites)
	 */
	private record Site(MethodInsnNode call, int index, boolean inline, Set<String> maybe, Set<String> always) { }

	/** One NeoForge call to scope: the MinecraftForge events proved, recorded as candidates, and faithful on its path. */
	private record ScopePlan(Site site, String proved, String candidates, Set<String> faithful) { }

	private int standDown(ClassNode owner, MethodNode method, Map<String, Optional<ClassNode>> read) {
		// A second pass over a class this already instrumented changes nothing.
		if (calls(method, API, null)) return 0;
		List<MethodInsnNode> forgeCalls = new ArrayList<>(), neoCalls = new ArrayList<>();
		for (AbstractInsnNode instruction : method.instructions) {
			if (!(instruction instanceof MethodInsnNode call)) continue;
			if (forgeCandidate(call.getOpcode(), call.owner, call.name)) forgeCalls.add(call);
			if (neoCandidate(call.getOpcode(), call.owner, call.name, call.desc)) neoCalls.add(call);
		}
		if (forgeCalls.isEmpty() || neoCalls.isEmpty()) return 0;
		List<Site> neoSites = new ArrayList<>();
		for (MethodInsnNode call : neoCalls) {
			boolean inline = neoPost(call.owner, call.name, call.desc);
			if (inline ? eventPosition(call) >= 0 : neoDelivers(call.owner, call.name, call.desc, 0)) {
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
				String event = eventPosition(call) >= 0 ? postedEvent(method, flow, call) : null;
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
		Bypasses bypasses = new Bypasses();
		Reference reference = null;
		List<ScopePlan> scopes = new ArrayList<>();
		for (Site neo : neoSites) {
			if (flow.frames[neo.index()] == null) continue;
			Set<String> proved = new TreeSet<>(), candidates = new TreeSet<>(), faithful = new TreeSet<>();
			for (Site forge : forgeSites) {
				int f = forge.index(), n = neo.index();
				if (!forge.always().isEmpty() && (dominates(flow, f, n) || postDominates(flow, f, n))) {
					// Proved from the bytecode alone. A delivery that comes first is recorded as well, for its cascade: what
					// MinecraftForge's own listeners post inside it, which NeoForge's own listeners post again inside the
					// NeoForge call (a bridge stands down for those too).
					proved.addAll(forge.always());
					if (dominates(flow, f, n)) recorded.add(forge);
				} else if (flow.reachesFrom(f, n)) {
					candidates.addAll(forge.maybe());
					recorded.add(forge);
				}
			}
			// What is left: F-sites after the NeoForge call that the caller may bypass. Judged against MinecraftForge's own
			// body, event by event; one that cannot be judged keeps its forward.
			Set<String> later = new TreeSet<>();
			for (Site forge : forgeSites) if (flow.reachesFrom(neo.index(), forge.index())) later.addAll(forge.maybe());
			later.removeAll(proved);
			later.removeAll(candidates);
			for (String event : later) {
				if (reference == null) reference = reference(owner.name, method, read);
				List<NativeDeliveryPaths.Edge> edges = reference.judge(flow, neo.index(), forgeSites, event);
				if (edges == null) continue;
				if (edges.isEmpty()) proved.add(event);
				else {
					faithful.add(event);
					bypasses.add(method, edges, event);
				}
			}
			if (proved.isEmpty() && candidates.isEmpty() && faithful.isEmpty()) continue;
			scopes.add(new ScopePlan(neo, String.join(";", proved), String.join(";", candidates), faithful));
		}
		if (scopes.isEmpty()) return 0;

		// Every bracket's handler carries the locals at its call, read before anything is inserted.
		Set<AbstractInsnNode> bracketed = new HashSet<>();
		for (ScopePlan plan : scopes) bracketed.add(plan.site().call());
		for (Site forge : recorded) bracketed.add(forge.call());
		Map<AbstractInsnNode, List<Object>> locals = localsAt(owner.name, method, bracketed);
		Map<AbstractInsnNode, List<TryCatchBlockNode>> covering = covering(method, bracketed);
		List<ScopePlan> live = new ArrayList<>();
		for (ScopePlan plan : scopes) {
			if (locals.get(plan.site().call()) != null) live.add(plan);
			else bypasses.drop(plan.faithful());
		}
		if (live.isEmpty()) return 0;
		List<Site> recording = new ArrayList<>();
		// A recording whose call cannot be bracketed records nothing, and the scopes it was for forward: never a loss.
		for (Site forge : recorded) if (locals.get(forge.call()) != null) recording.add(forge);
		boolean faithfulLive = false;
		for (ScopePlan plan : live) faithfulLive |= !plan.faithful().isEmpty();
		boolean invocation = !recording.isEmpty() || faithfulLive;
		int base = method.maxLocals, own = invocation ? base : -1;
		int scopeSlot = invocation ? base + 1 : base, recordSlot = scopeSlot + 1, temps = recordSlot + 1;
		for (ScopePlan plan : live) {
			MethodInsnNode call = plan.site().call();
			InsnList open = new InsnList();
			open.add(new LdcInsnNode(plan.proved()));
			open.add(own >= 0 ? new VarInsnNode(Opcodes.ALOAD, own) : new InsnNode(Opcodes.ACONST_NULL));
			open.add(new LdcInsnNode(plan.candidates()));
			open.add(new LdcInsnNode(String.join(";", plan.faithful())));
			if (plan.site().inline()) {
				open.add(new MethodInsnNode(Opcodes.INVOKESTATIC, API, "begin", BEGIN_POST, false));
			} else {
				open.add(new LdcInsnNode(Type.getObjectType(call.owner)));
				open.add(new MethodInsnNode(Opcodes.INVOKESTATIC, API, "begin", BEGIN_HOOK, false));
			}
			bracket(method, call, open, SCOPE, plan.site().inline() ? "capture" : null, scopeSlot, temps, locals.get(call), covering.get(call));
		}
		for (Site forge : recording) {
			MethodInsnNode call = forge.call();
			InsnList open = new InsnList();
			open.add(new VarInsnNode(Opcodes.ALOAD, own));
			open.add(new LdcInsnNode(String.join(";", forge.maybe())));
			open.add(new MethodInsnNode(Opcodes.INVOKESTATIC, API, "record", RECORD, false));
			bracket(method, call, open, RECORDING, forge.inline() ? "delivered" : null, recordSlot, temps, locals.get(call), covering.get(call));
		}
		if (invocation) {
			bypasses.apply(method, own);
			InsnList enter = new InsnList();
			enter.add(new LdcInsnNode(group));
			enter.add(new MethodInsnNode(Opcodes.INVOKESTATIC, API, "enterNative", "(Ljava/lang/String;)L" + INVOCATION + ";", false));
			enter.add(new VarInsnNode(Opcodes.ASTORE, own));
			method.instructions.insert(enter);
			// Assigned before anything else runs, so every frame of the method holds it.
			for (AbstractInsnNode instruction : method.instructions) {
				if (instruction instanceof FrameNode frame) frame.local = declare(frame.local, own, INVOCATION);
			}
		}
		return live.size();
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

	/**
	 * Which of a post's own arguments (the receiver not counted) is the event it posts, or -1 when none can be handed
	 * over: a bus's {@code post} takes it last, the mod-bus dispatcher takes it as its event-typed argument.
	 */
	private int eventPosition(MethodInsnNode call) {
		Type[] args = Type.getArgumentTypes(call.desc);
		if (call.getOpcode() == Opcodes.INVOKEINTERFACE || call.getOpcode() == Opcodes.INVOKEVIRTUAL) {
			return args.length > 0 && args[args.length - 1].getSort() == Type.OBJECT ? args.length - 1 : -1;
		}
		return call.getOpcode() == Opcodes.INVOKESTATIC && neoPost(call.owner, call.name, call.desc) ? eventArgument(call.desc) : -1;
	}

	/**
	 * Brackets {@code call} where it stands: {@code open} (leaving a scope or recording of {@code guardType}) is stored in
	 * {@code slot} right before the call, the post's event is handed over ({@code handOver}, for an inline post), and the
	 * guard is closed right after the call and on any exception out of it. The call instruction itself is the game's,
	 * untouched. The exception path is a handler after the method's last instruction, described by {@code locals} (the
	 * frame's locals at the call) and covered, in their order, by the method's own handlers that cover the call
	 * ({@code covering}), so its rethrow reaches exactly the handler the call's own exception would have.
	 *
	 * @param temps the first local free for the arguments stored while an event below the top is handed over
	 */
	private void bracket(MethodNode method, MethodInsnNode call, InsnList open, String guardType, String handOver, int slot, int temps,
			List<Object> locals, List<TryCatchBlockNode> covering) {
		InsnList before = new InsnList();
		before.add(open);
		before.add(new VarInsnNode(Opcodes.ASTORE, slot));
		if (handOver != null) {
			// The arguments above the event go into locals, the event is handed over, and they come back as they were.
			Type[] args = Type.getArgumentTypes(call.desc);
			int position = eventPosition(call);
			int[] stored = new int[args.length];
			for (int i = args.length - 1, next = temps; i > position; i--) {
				stored[i] = next;
				before.add(new VarInsnNode(args[i].getOpcode(Opcodes.ISTORE), next));
				next += args[i].getSize();
			}
			before.add(new InsnNode(Opcodes.DUP));
			before.add(new VarInsnNode(Opcodes.ALOAD, slot));
			before.add(new InsnNode(Opcodes.SWAP));
			before.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, guardType, handOver, "(Ljava/lang/Object;)V", false));
			for (int i = position + 1; i < args.length; i++) before.add(new VarInsnNode(args[i].getOpcode(Opcodes.ILOAD), stored[i]));
		}
		LabelNode start = new LabelNode(), end = new LabelNode();
		before.add(start);
		method.instructions.insertBefore(call, before);
		InsnList after = new InsnList();
		after.add(end);
		after.add(new VarInsnNode(Opcodes.ALOAD, slot));
		after.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, guardType, "close", "()V", false));
		method.instructions.insert(call, after);

		LabelNode handler = new LabelNode(), handled = new LabelNode();
		List<Object> frame = declare(locals, slot, guardType);
		method.instructions.add(handler);
		method.instructions.add(new FrameNode(Opcodes.F_NEW, frame.size(), frame.toArray(), 1, new Object[] {"java/lang/Throwable"}));
		method.instructions.add(new VarInsnNode(Opcodes.ALOAD, slot));
		method.instructions.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, guardType, "close", "()V", false));
		method.instructions.add(new InsnNode(Opcodes.ATHROW));
		method.instructions.add(handled);
		// Innermost first: the call's own exception is caught here before any handler of the method's.
		method.tryCatchBlocks.add(0, new TryCatchBlockNode(start, end, handler, null));
		for (TryCatchBlockNode outer : covering) method.tryCatchBlocks.add(new TryCatchBlockNode(handler, handled, outer.handler, outer.type));
	}

	/** The method's handlers whose range covers each of {@code calls}, in exception-table order. */
	private static Map<AbstractInsnNode, List<TryCatchBlockNode>> covering(MethodNode method, Set<AbstractInsnNode> calls) {
		Map<AbstractInsnNode, List<TryCatchBlockNode>> out = new HashMap<>();
		for (AbstractInsnNode call : calls) {
			int at = method.instructions.indexOf(call);
			List<TryCatchBlockNode> blocks = new ArrayList<>();
			if (method.tryCatchBlocks != null) {
				for (TryCatchBlockNode block : method.tryCatchBlocks) {
					if (method.instructions.indexOf(block.start) <= at && at < method.instructions.indexOf(block.end)) blocks.add(block);
				}
			}
			out.put(call, blocks);
		}
		return out;
	}

	/**
	 * The locals at each of {@code calls}, in a frame's form (one element for a long or a double), read from the method's
	 * own frames and the instructions between them; null for a call where they are unknown (no frame reaches it) or hold
	 * an object not yet constructed (a handler could not be described there).
	 */
	static Map<AbstractInsnNode, List<Object>> localsAt(String owner, MethodNode method, Set<AbstractInsnNode> calls) {
		Map<AbstractInsnNode, List<Object>> out = new HashMap<>();
		AnalyzerAdapter adapter = new AnalyzerAdapter(owner, method.access, method.name, method.desc, null);
		try {
			for (AbstractInsnNode instruction : method.instructions) {
				if (calls.contains(instruction)) out.put(instruction, frameLocals(adapter.locals));
				instruction.accept(adapter);
			}
		} catch (RuntimeException unreadable) {
			// Frames this cannot follow: nothing is bracketed where the locals were not read.
		}
		return out;
	}

	private static List<Object> frameLocals(List<Object> slots) {
		if (slots == null) return null;
		List<Object> out = new ArrayList<>(slots.size());
		for (int i = 0; i < slots.size(); i++) {
			Object type = slots.get(i);
			if (type instanceof Label || type == Opcodes.UNINITIALIZED_THIS) return null;
			out.add(type);
			if (type == Opcodes.LONG || type == Opcodes.DOUBLE) i++;
		}
		return out;
	}

	/**
	 * {@code local} (a frame's locals, one element for a long or a double) with {@code type} in slot {@code slot}: the
	 * slot's single-slot entry replaced, or the list padded with {@code TOP} to reach it. Never splits a long or double.
	 */
	static List<Object> declare(List<Object> local, int slot, Object type) {
		List<Object> out = new ArrayList<>(local == null ? List.of() : local);
		int at = 0, i = 0;
		for (; i < out.size() && at < slot; i++) at += out.get(i) == Opcodes.LONG || out.get(i) == Opcodes.DOUBLE ? 2 : 1;
		if (at > slot) throw new IllegalStateException("slot " + slot + " is the second half of a long or double");
		if (at < slot) {
			while (at++ < slot) out.add(Opcodes.TOP);
			out.add(type);
		} else if (i < out.size()) {
			Object was = out.get(i);
			out.set(i, type);
			if (was == Opcodes.LONG || was == Opcodes.DOUBLE) out.add(i + 1, Opcodes.TOP);
		} else {
			out.add(type);
		}
		return out;
	}

	/**
	 * One scope or recording the injector bracketed around a call of the game's: what tests and audits read back.
	 *
	 * @param call       the bracketed call, the game's own instruction
	 * @param scope      a scope around a NeoForge call (else a recording around a MinecraftForge one)
	 * @param natively   a scope's proved MinecraftForge events, {@code ;}-joined (a recording's: null)
	 * @param candidates the recorded events a scope may consume, or the events a recording records
	 * @param faithful   a scope's faithful events (a recording's: null)
	 * @param invocation whether it belongs to the caller's invocation (else it is a proved-only scope)
	 */
	public record Bracket(MethodInsnNode call, boolean scope, String natively, String candidates, String faithful, boolean invocation) { }

	/** The brackets of {@code method}, as {@link #standDown} writes them, in instruction order. */
	public static List<Bracket> brackets(MethodNode method) {
		List<Bracket> out = new ArrayList<>();
		for (AbstractInsnNode instruction : method.instructions) {
			if (!(instruction instanceof MethodInsnNode open) || open.getOpcode() != Opcodes.INVOKESTATIC || !open.owner.equals(API)) continue;
			boolean scope = open.name.equals("begin");
			if (!scope && !open.name.equals("record")) continue;
			List<AbstractInsnNode> args = new ArrayList<>();
			AbstractInsnNode at = open.getPrevious();
			for (int n = Type.getArgumentTypes(open.desc).length; n > 0 && at != null; at = at.getPrevious()) {
				if (at.getOpcode() < 0) continue;
				args.add(0, at);
				n--;
			}
			AbstractInsnNode call = open.getNext();
			while (call != null && !(call instanceof MethodInsnNode game && !game.owner.equals(API) && !game.owner.equals(SCOPE)
					&& !game.owner.equals(RECORDING) && call.getOpcode() >= 0 && !(call instanceof VarInsnNode))) {
				call = call.getNext();
			}
			if (!(call instanceof MethodInsnNode bracketedCall) || args.size() < 2) continue;
			if (scope) {
				out.add(new Bracket(bracketedCall, true, string(args.get(0)), string(args.get(2)), string(args.get(3)),
						args.get(1).getOpcode() == Opcodes.ALOAD));
			} else {
				out.add(new Bracket(bracketedCall, false, null, string(args.get(1)), null, true));
			}
		}
		return out;
	}

	private static String string(AbstractInsnNode instruction) {
		return instruction instanceof LdcInsnNode ldc && ldc.cst instanceof String text ? text : null;
	}

	/**
	 * One settlement the injector wrote on a bypass edge.
	 *
	 * @param branch           the branch the edge leaves
	 * @param jump             whether it is the branch's jump (or a switch case) rather than its fall-through
	 * @param events           the MinecraftForge events settled there, {@code ;}-joined
	 * @param forgeBypassesToo the verdict: MinecraftForge's own body bypasses its call on this way too
	 */
	public record Settlement(AbstractInsnNode branch, boolean jump, String events, boolean forgeBypassesToo) { }

	/** The settlements of {@code method}, as {@link #standDown} writes them. */
	public static List<Settlement> settlements(MethodNode method) {
		List<Settlement> out = new ArrayList<>();
		for (AbstractInsnNode instruction : method.instructions) {
			if (!(instruction instanceof MethodInsnNode call) || !call.owner.equals(API) || !call.name.equals("bypassed")) continue;
			AbstractInsnNode verdict = call.getPrevious(), events = verdict.getPrevious(), invocation = events.getPrevious();
			// Either right after its branch (a fall-through), or in a trampoline: label, frame, settlements, then on.
			AbstractInsnNode first = invocation;
			while (first.getPrevious() instanceof MethodInsnNode earlier && earlier.owner.equals(API) && earlier.name.equals("bypassed")) {
				first = earlier.getPrevious().getPrevious().getPrevious();
			}
			AbstractInsnNode before = first.getPrevious();
			AbstractInsnNode branch = null;
			boolean jump = false;
			if (before instanceof FrameNode && before.getPrevious() instanceof LabelNode trampoline) {
				for (AbstractInsnNode candidate : method.instructions) {
					if (candidate instanceof JumpInsnNode j && j.label == trampoline
							|| candidate instanceof TableSwitchInsnNode t && (t.dflt == trampoline || t.labels.contains(trampoline))
							|| candidate instanceof LookupSwitchInsnNode l && (l.dflt == trampoline || l.labels.contains(trampoline))) {
						branch = candidate;
						jump = true;
					}
				}
			} else {
				branch = before;
			}
			out.add(new Settlement(branch, jump, string(events), verdict.getOpcode() == Opcodes.ICONST_1));
		}
		return out;
	}

	// ---------------------------------------------------------------- faithful deliveries

	/** MinecraftForge's own body of one caller method, read once per caller, with its delivery sites per event. */
	private final class Reference {
		final MethodNode merged, forge;
		final Flow forgeFlow;
		final Map<String, Set<Integer>> sites = new HashMap<>();
		/** The two bodies aligned, read before anything is inserted into the caller. */
		int[] match;
		boolean aligned;

		Reference(MethodNode merged, MethodNode forge, Flow forgeFlow) {
			this.merged = merged;
			this.forge = forge;
			this.forgeFlow = forgeFlow;
		}

		/**
		 * The bypass edges of the caller's deliveries of {@code event} after the N-site at {@code n}, or null when they
		 * cannot be judged: every F-site that can deliver it after {@code n} must deliver it whenever it runs, at most once
		 * on any path, and {@code n} must not run again.
		 */
		List<NativeDeliveryPaths.Edge> judge(Flow flow, int n, List<Site> forgeSites, String event) {
			if (forge == null || forgeFlow == null || flow.reachesAgain(n, -1, -1)) return null;
			Set<Integer> later = new TreeSet<>();
			for (Site site : forgeSites) {
				if (!site.maybe().contains(event) || !flow.reachesFrom(n, site.index())) continue;
				if (!site.always().contains(event)) return null;
				later.add(site.index());
			}
			if (later.isEmpty()) return null;
			for (int f : later) for (int g : later) if (flow.reachesFrom(f, g)) return null;
			Set<Integer> theirs = sites.computeIfAbsent(event, this::sitesOf);
			if (theirs.isEmpty()) return null;
			if (!aligned) {
				match = NativeDeliveryPaths.align(merged, forge);
				aligned = true;
			}
			return NativeDeliveryPaths.judge(merged, flow, n, later, forge, forgeFlow, theirs, match);
		}

		/** The calls and posts in MinecraftForge's own body that can deliver {@code event}. */
		private Set<Integer> sitesOf(String event) {
			Set<Integer> out = new TreeSet<>();
			for (int i = 0; i < forge.instructions.size(); i++) {
				if (!(forge.instructions.get(i) instanceof MethodInsnNode call) || forgeFlow.frames[i] == null) continue;
				if (!forgeCandidate(call.getOpcode(), call.owner, call.name)) continue;
				boolean delivers = forgePost(call.owner, call.name)
						? eventPosition(call) >= 0 && event.equals(postedEvent(forge, forgeFlow, call))
						: forgeMaybe(call.owner, call.name, call.desc, 0).contains(event);
				if (delivers) out.add(i);
			}
			return out;
		}
	}

	/**
	 * MinecraftForge's own body of {@code merged}. Its class is read once per transformed class ({@code read}), and kept
	 * no longer: an analysis writes into the nodes it reads, so nothing is shared between class loads.
	 */
	private Reference reference(String owner, MethodNode merged, Map<String, Optional<ClassNode>> read) {
		ClassNode node = read.computeIfAbsent(owner, name -> {
			try {
				return Optional.ofNullable(forgeReference.apply(name));
			} catch (RuntimeException unreadable) {
				return Optional.empty();
			}
		}).orElse(null);
		MethodNode forge = null;
		if (node != null) {
			for (MethodNode m : node.methods) {
				if (m.name.equals(merged.name) && m.desc.equals(merged.desc) && m.instructions.size() > 0) forge = m;
			}
		}
		Flow forgeFlow = null;
		if (forge != null) {
			try {
				forgeFlow = new Flow(owner, forge);
			} catch (AnalyzerException unanalysable) {
				forge = null;
			}
		}
		return new Reference(merged, forge, forgeFlow);
	}

	/**
	 * The bypass edges of one caller, collected over its N-sites and events, and written into it once every NeoForge
	 * call is scoped: per edge, a {@link NativeEventDelivery#bypassed} call for the events MinecraftForge's own body
	 * bypasses there too, and one for those it does not.
	 */
	private static final class Bypasses {
		/** Per edge (its branch, kind and label), the events per verdict: true = MinecraftForge's body bypasses too. */
		private final Map<List<Object>, Map<Boolean, Set<String>>> edges = new LinkedHashMap<>();

		void add(MethodNode method, List<NativeDeliveryPaths.Edge> found, String event) {
			for (NativeDeliveryPaths.Edge edge : found) {
				List<Object> key = Arrays.asList(method.instructions.get(edge.from()), edge.kind(), edge.label());
				edges.computeIfAbsent(key, k -> new TreeMap<>()).computeIfAbsent(edge.forgeBypassesToo(), k -> new TreeSet<>()).add(event);
			}
		}

		/** Events whose N-site could not be scoped: nothing would be owed for them, so nothing settles them. */
		void drop(Set<String> events) {
			for (Map<Boolean, Set<String>> verdicts : edges.values()) for (Set<String> names : verdicts.values()) names.removeAll(events);
		}

		/** Writes the calls, each settling what the invocation in local {@code invocation} owes; whether any was written. */
		boolean apply(MethodNode method, int invocation) {
			boolean any = false;
			for (var entry : edges.entrySet()) {
				AbstractInsnNode branch = (AbstractInsnNode) entry.getKey().get(0);
				NativeDeliveryPaths.Kind kind = (NativeDeliveryPaths.Kind) entry.getKey().get(1);
				LabelNode label = (LabelNode) entry.getKey().get(2);
				InsnList calls = new InsnList();
				for (var verdict : entry.getValue().entrySet()) {
					if (verdict.getValue().isEmpty()) continue;
					calls.add(new VarInsnNode(Opcodes.ALOAD, invocation));
					calls.add(new LdcInsnNode(String.join(";", verdict.getValue())));
					calls.add(new InsnNode(verdict.getKey() ? Opcodes.ICONST_1 : Opcodes.ICONST_0));
					calls.add(new MethodInsnNode(Opcodes.INVOKESTATIC, API, "bypassed", "(L" + INVOCATION + ";Ljava/lang/String;Z)V", false));
				}
				if (calls.size() == 0) continue;
				any = true;
				if (kind == NativeDeliveryPaths.Kind.FALL) {
					method.instructions.insert(branch, calls);
					continue;
				}
				// A new edge into the old target: a trampoline after the method's last instruction (which never falls
				// through), carrying the target's own frame, then on to the target.
				FrameNode frame = NativeDeliveryPaths.frameAt(label);
				LabelNode trampoline = new LabelNode();
				method.instructions.add(trampoline);
				method.instructions.add(new FrameNode(Opcodes.F_NEW, frame.local.size(), frame.local.toArray(), frame.stack.size(),
						frame.stack.toArray()));
				method.instructions.add(calls);
				method.instructions.add(new JumpInsnNode(Opcodes.GOTO, label));
				if (branch instanceof JumpInsnNode jump) jump.label = trampoline;
				else if (branch instanceof TableSwitchInsnNode table) {
					if (table.dflt == label) table.dflt = trampoline;
					table.labels.replaceAll(l -> l == label ? trampoline : l);
				} else if (branch instanceof LookupSwitchInsnNode lookup) {
					if (lookup.dflt == label) lookup.dflt = trampoline;
					lookup.labels.replaceAll(l -> l == label ? trampoline : l);
				}
			}
			return any;
		}
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
			// An event its class keeps one instance of and posts every time (MinecraftForge's client tick posts
			// TickEvent.ClientTickEvent.Pre.INSTANCE): delivered the same as one constructed for the post.
			if (posts && singleton(instruction, families.forge()) != null) out.add(singleton(instruction, families.forge()));
			if (instruction instanceof MethodInsnNode call && call.getOpcode() == Opcodes.INVOKESTATIC
					&& call.owner.startsWith(families.forge()) && !call.owner.startsWith(families.forgeBus())
					&& !(call.owner + "." + call.name + call.desc).equals(owner + "." + name + desc)) {
				out.addAll(forgeDeliveries(call.owner, call.name, call.desc, depth + 1, always));
			}
		}
		return Set.copyOf(out);
	}

	/**
	 * The class whose own one instance {@code instruction} reads — {@code GETSTATIC C.f : LC;} with {@code C} in
	 * {@code namespace} — or null. A hook that posts that instance delivers a {@code C} without constructing one.
	 */
	static String singleton(AbstractInsnNode instruction, String namespace) {
		return instruction instanceof FieldInsnNode field && field.getOpcode() == Opcodes.GETSTATIC
				&& field.owner.startsWith(namespace) && field.desc.equals("L" + field.owner + ";") ? field.owner : null;
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
			if (instruction instanceof MethodInsnNode call && neoPost(call.owner, call.name, call.desc)) posts = true;
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
		if (!(named[0] && named[1]) && !(named.length > 2 && named[2])) return input;
		return instrument(input, families.neo(), this::neoPost, "constructed", "dispatching",
				"dispatched", true);
	}

	/** The same for MinecraftForge's hooks, reporting to the recording a caller opened around a call into one. */
	private byte[] instrumentForgeHooks(byte[] input) {
		if (!ByteScan.constantPoolPrefixes(input, forgeHookPrefixes)[0] || !ByteScan.constantPoolNames(input, post)[0]) return input;
		return instrument(input, families.forge(), (owner, name, desc) -> forgePost(owner, name), "nativeConstructed", "nativeDispatching",
				"nativeDispatched", false);
	}

	private interface PostTest {
		boolean posts(String owner, String name, String desc);
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
				if (instruction instanceof MethodInsnNode call && post.posts(call.owner, call.name, call.desc)) posts.add(call);
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
			// MinecraftForge's hooks also post an event class's one instance; reported the same way, so a recording
			// around such a hook commits what it delivered. NeoForge's are left as they are: a scope captures by
			// identity, and an instance shared by every post is no one post's event.
			if (!neo) {
				for (AbstractInsnNode instruction : method.instructions.toArray()) {
					if (singleton(instruction, namespace) == null) continue;
					InsnList report = new InsnList();
					report.add(new InsnNode(Opcodes.DUP));
					report.add(new MethodInsnNode(Opcodes.INVOKESTATIC, API, constructed, "(Ljava/lang/Object;)V", false));
					method.instructions.insert(instruction, report);
				}
			}
			for (MethodInsnNode call : posts) {
				method.instructions.insertBefore(call, new MethodInsnNode(Opcodes.INVOKESTATIC, API, dispatching, "()V", false));
				method.instructions.insert(call, new MethodInsnNode(Opcodes.INVOKESTATIC, API, dispatched, "()V", false));
			}
			reporting = edited = true;
		}
		// Recorded before the class is defined, so a caller never opens a live scope around an unreported hook.
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
		/** The edges without the exception edges. */
		final Map<Integer, Set<Integer>> normal = new HashMap<>();
		final Frame<SourceValue>[] frames;
		final boolean[] exit;

		Flow(String owner, MethodNode method) throws AnalyzerException {
			frames = new Analyzer<SourceValue>(new SourceInterpreter()) {
				@Override protected void newControlFlowEdge(int from, int to) {
					edges.computeIfAbsent(from, k -> new HashSet<>()).add(to);
					normal.computeIfAbsent(from, k -> new HashSet<>()).add(to);
				}

				@Override protected boolean newControlFlowExceptionEdge(int from, int to) {
					edges.computeIfAbsent(from, k -> new HashSet<>()).add(to);
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

	/** Whether {@code method} calls {@code owner}'s {@code name} (any of its methods for null). */
	private static boolean calls(MethodNode method, String owner, String name) {
		for (AbstractInsnNode i : method.instructions) {
			if (i instanceof MethodInsnNode call && call.owner.equals(owner) && (name == null || call.name.equals(name))) return true;
		}
		return false;
	}
}
