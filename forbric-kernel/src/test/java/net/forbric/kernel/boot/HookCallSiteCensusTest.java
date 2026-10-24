package net.forbric.kernel.boot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/**
 * Pins the census's comparison rules on synthetic jars.
 *
 * <p>The staged counterpart asserts what the real merged base looks like today; this asserts what the tool
 * MEANS, which is the half that has to survive every rebuild. Each rule here is one the hand-run {@code javap}
 * censuses had to apply by eye, which is the same as saying nothing checked that they were applied.
 */
class HookCallSiteCensusTest {
	private static final String HOOKS = "forge/Hooks";
	@TempDir Path temporary;

	@Test void aHookWithNoCallSiteIsDeadAndOneWithACallSiteIsNot() throws Exception {
		Path carrier = carrier(hooks(hook("onCalled", "()V"), hook("onNeverCalled", "()V")));
		Path base = base(callerCalling("game/Level", List.of(new Call("onCalled", "()V"))));
		var census = HookCallSiteCensus.of(carrier, HOOKS, List.of(base));
		assertEquals(List.of("onCalled()V", "onNeverCalled()V"), census.declared());
		assertEquals(java.util.Set.of("onCalled()V"), census.live());
		assertEquals(java.util.Set.of("onNeverCalled()V"), census.dead());
	}

	@Test void twoHooksWithTheSameNameAreJudgedSeparatelyByDescriptor() throws Exception {
		// The shape that makes a name-only census lie: an overload pair where the merge kept one call site.
		Path carrier = carrier(hooks(hook("onChat", "()V"), hook("onChat", "(I)V")));
		Path base = base(callerCalling("game/Level", List.of(new Call("onChat", "(I)V"))));
		var census = HookCallSiteCensus.of(carrier, HOOKS, List.of(base));
		assertEquals(java.util.Set.of("onChat(I)V"), census.live());
		assertEquals(java.util.Set.of("onChat()V"), census.dead());
	}

	@Test void aHookClassCallingItsOwnHookIsNotTheGameReachingIt() throws Exception {
		// forge/Hooks.onOuter calls forge/Hooks.onInner. If self-calls counted, a whole dead family would
		// read live as long as one unreachable entry point delegated into it.
		ClassWriter cw = new ClassWriter(0);
		cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, HOOKS, null, "java/lang/Object", null);
		emit(cw, "onInner", "()V", List.of());
		emit(cw, "onOuter", "()V", List.of(new Call("onInner", "()V")));
		cw.visitEnd();
		Path carrier = carrier(Map.of(HOOKS + ".class", cw.toByteArray()));
		// The base contains a COPY of the hook class (as a carrier jar handed in as a base would) and nothing else.
		Path base = carrier(Map.of(HOOKS + ".class", cw.toByteArray()));
		var census = HookCallSiteCensus.of(carrier, HOOKS, List.of(base));
		assertEquals(java.util.Set.of("onInner()V", "onOuter()V"), census.dead());
		assertTrue(census.live().isEmpty(), census.live().toString());
	}

	@Test void privateAndInstanceMembersAreNotHooks() throws Exception {
		ClassWriter cw = new ClassWriter(0);
		cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, HOOKS, null, "java/lang/Object", null);
		emit(cw, "onPublicStatic", "()V", List.of());
		MethodVisitor priv = cw.visitMethod(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC, "helper", "()V", null, null);
		priv.visitCode(); priv.visitInsn(Opcodes.RETURN); priv.visitMaxs(0, 0); priv.visitEnd();
		MethodVisitor inst = cw.visitMethod(Opcodes.ACC_PUBLIC, "instanceMethod", "()V", null, null);
		inst.visitCode(); inst.visitInsn(Opcodes.RETURN); inst.visitMaxs(0, 1); inst.visitEnd();
		MethodVisitor syn = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC | Opcodes.ACC_SYNTHETIC,
				"lambda$0", "()V", null, null);
		syn.visitCode(); syn.visitInsn(Opcodes.RETURN); syn.visitMaxs(0, 0); syn.visitEnd();
		cw.visitEnd();
		Path carrier = carrier(Map.of(HOOKS + ".class", cw.toByteArray()));
		var census = HookCallSiteCensus.of(carrier, HOOKS, List.of(base(callerCalling("game/Level", List.of()))));
		assertEquals(List.of("onPublicStatic()V"), census.declared());
	}

	@Test void anEventIsOnlyDeadWhenEveryHookThatPostsItIsDead() throws Exception {
		ClassWriter cw = new ClassWriter(0);
		cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, HOOKS, null, "java/lang/Object", null);
		posts(cw, "onA", "forge/SharedEvent");
		posts(cw, "onB", "forge/SharedEvent");
		posts(cw, "onC", "forge/LonelyEvent");
		cw.visitEnd();
		Path carrier = carrier(Map.of(HOOKS + ".class", cw.toByteArray()));
		// Only onA survives. SharedEvent still gets posted; LonelyEvent never does.
		Path base = base(callerCalling("game/Level", List.of(new Call("onA", "()V"))));
		var census = HookCallSiteCensus.of(carrier, HOOKS, List.of(base));
		assertEquals(java.util.Set.of("onA()V", "onB()V"), census.postersOf().get("forge/SharedEvent"));
		assertEquals(java.util.Set.of("forge/LonelyEvent"), census.deadEvents());
		assertTrue(census.liveEvents().contains("forge/SharedEvent"), census.liveEvents().toString());
		// The whole point of doing this per event rather than per hook: onB is dead and its event is not.
		assertTrue(census.dead().contains("onB()V"));
		assertFalse(census.deadEvents().contains("forge/SharedEvent"));
	}

	@Test void onlyTheHooksOwnEcosystemNamespaceCountsAsAnEventItPosts() throws Exception {
		// Without this the census calls every ArrayList a hook allocates an "event nothing posts": on the real
		// base that was 171 of them, burying the fourteen that were real.
		ClassWriter cw = new ClassWriter(0);
		cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, HOOKS, null, "java/lang/Object", null);
		posts(cw, "onThing", "forge/RealEvent");
		posts(cw, "onOther", "java/util/ArrayList");
		cw.visitEnd();
		Path carrier = carrier(Map.of(HOOKS + ".class", cw.toByteArray()));
		var census = HookCallSiteCensus.of(carrier, HOOKS, List.of(base(callerCalling("game/Level", List.of()))));
		assertEquals(java.util.Set.of("forge/RealEvent"), census.postersOf().keySet());
		assertEquals(java.util.Set.of("forge/RealEvent"), census.deadEvents());
	}

	// ---- reach across hook classes (HookCallSiteCensus.all) ----

	private static final String FACTORY = "forge/Factory";

	/** A class with one public static hook per entry: name -> the calls it makes ({owner, name}) and the type it constructs. */
	private static byte[] hookClass(String owner, Map<String, List<String[]>> calls, Map<String, String> constructs) {
		ClassWriter cw = new ClassWriter(0);
		cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, owner, null, "java/lang/Object", null);
		java.util.Set<String> names = new java.util.TreeSet<>(calls.keySet());
		names.addAll(constructs.keySet());
		for (String name : names) {
			int access = name.startsWith("lambda$") ? Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_SYNTHETIC
					: Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC;
			MethodVisitor mv = cw.visitMethod(access, name, "()V", null, null);
			mv.visitCode();
			for (String[] call : calls.getOrDefault(name, List.of())) mv.visitMethodInsn(Opcodes.INVOKESTATIC, call[0], call[1], "()V", false);
			if (constructs.containsKey(name)) {
				mv.visitTypeInsn(Opcodes.NEW, constructs.get(name));
				mv.visitInsn(Opcodes.POP);
			}
			mv.visitInsn(Opcodes.RETURN);
			mv.visitMaxs(4, 4);
			mv.visitEnd();
		}
		cw.visitEnd();
		return cw.toByteArray();
	}

	/** A class {@code name extends superName}, carried in a jar so the census can read whether it is an event. */
	private static byte[] type(String name, String superName) {
		ClassWriter cw = new ClassWriter(0);
		cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, name, null, superName, null);
		cw.visitEnd();
		return cw.toByteArray();
	}

	/** forge/Hooks.onOuter calls forge/Factory.onInner, the only poster of forge/InnerEvent. */
	private Path outerAndInner(Map<String, byte[]> extra) throws Exception {
		Map<String, byte[]> entries = new LinkedHashMap<>();
		entries.put(HOOKS + ".class", hookClass(HOOKS, Map.of("onOuter", List.<String[]>of(new String[] {FACTORY, "onInner"})), Map.of()));
		entries.put(FACTORY + ".class", hookClass(FACTORY, Map.of(), Map.of("onInner", "forge/InnerEvent")));
		entries.putAll(extra);
		return carrier(entries);
	}

	private static Map<String, Path> both(Path carrier) {
		Map<String, Path> hooks = new LinkedHashMap<>();
		hooks.put(HOOKS, carrier);
		hooks.put(FACTORY, carrier);
		return hooks;
	}

	@Test void aCarrierCallerThatIsAHookNothingReachesReachesNothing() throws Exception {
		// MinecraftForge's breathe event, in miniature: its factory method's one caller is another hook the game never
		// calls. Counting any carrier caller called the event posted.
		Path carrier = outerAndInner(Map.of());
		var dead = HookCallSiteCensus.all(both(carrier), List.of(base(callerCalling("game/Level", List.of()))), List.of(carrier), List.of());
		assertEquals(java.util.Set.of(), dead.get(FACTORY).reachedOnlyViaCarrier());
		assertEquals(java.util.Set.of("forge/InnerEvent"), dead.get(FACTORY).deadEvents());
		// And once the game calls the outer hook, the inner one is reached through it.
		var reached = HookCallSiteCensus.all(both(carrier), List.of(base(callerCalling("game/Level", List.of(new Call("onOuter", "()V"))))),
				List.of(carrier), List.of());
		assertEquals(java.util.Set.of("onInner()V"), reached.get(FACTORY).reachedOnlyViaCarrier());
		assertEquals(java.util.Set.of(), reached.get(FACTORY).deadEvents());
	}

	@Test void aCarrierCallerThatIsNotAHookStillCounts() throws Exception {
		// Whether the runtime reaches an instance method, a lambda or a non-hook class is beyond a call-site count, so
		// such a caller still counts: every error stays on the side of calling a dead event live.
		Map<String, byte[]> helper = Map.of("forge/Helper.class",
				hookClass("forge/Helper", Map.of("help", List.<String[]>of(new String[] {FACTORY, "onInner"})), Map.of()));
		Path carrier = outerAndInner(helper);
		var all = HookCallSiteCensus.all(both(carrier), List.of(base(callerCalling("game/Level", List.of()))), List.of(carrier), List.of());
		assertEquals(java.util.Set.of("onInner()V"), all.get(FACTORY).reachedOnlyViaCarrier());
		assertEquals(java.util.Set.of(), all.get(FACTORY).deadEvents());
	}

	@Test void aLambdaInAHookClassReachesItsOwnHook() throws Exception {
		// NeoForge's chat decorator: the game calls a hook that hands out a lambda, and the lambda calls the hook that
		// posts the event. A hook class calling its own hook from a dead hook is nothing; from a lambda it is the runtime.
		Map<String, byte[]> entries = new LinkedHashMap<>();
		entries.put(HOOKS + ".class", hookClass(HOOKS, Map.of("lambda$0", List.<String[]>of(new String[] {HOOKS, "onChat"})),
				Map.of("onChat", "forge/ChatEvent")));
		Path carrier = carrier(entries);
		var all = HookCallSiteCensus.all(Map.of(HOOKS, carrier), List.of(base(callerCalling("game/Level", List.of()))), List.of(carrier),
				List.of());
		assertEquals(java.util.Set.of("onChat()V"), all.get(HOOKS).reachedOnlyViaCarrier());
		assertEquals(java.util.Set.of(), all.get(HOOKS).deadEvents());
	}

	@Test void onlyAnEventCountsAsAnEventAHookPosts() throws Exception {
		// A codec, a map or a result holder a hook allocates in its own namespace is not an event nothing posts. A type
		// whose supertypes reach a family event base is; one the carrier does not carry cannot be told apart, and counts.
		Map<String, byte[]> entries = new LinkedHashMap<>();
		entries.put(HOOKS + ".class", hookClass(HOOKS, Map.of(),
				Map.of("onHolder", "forge/Holder", "onReal", "forge/RealEvent", "onUnknown", "forge/UnknownEvent")));
		entries.put("forge/Holder.class", type("forge/Holder", "java/lang/Object"));
		entries.put("forge/RealEvent.class", type("forge/RealEvent", "forge/BaseEvent"));
		entries.put("forge/BaseEvent.class", type("forge/BaseEvent", "net/minecraftforge/eventbus/internal/Event"));
		Path carrier = carrier(entries);
		var census = HookCallSiteCensus.of(carrier, HOOKS, List.of(base(callerCalling("game/Level", List.of()))));
		assertEquals(java.util.Set.of("forge/RealEvent", "forge/UnknownEvent"), census.postersOf().keySet());
		assertTrue(HookCallSiteCensus.isEvent("forge/RealEvent", "forge/", Map.of("forge/RealEvent", new String[] {"net/neoforged/bus/api/Event"})));
		assertFalse(HookCallSiteCensus.isEvent("forge/Codec", "forge/", Map.of("forge/Codec", new String[] {"com/mojang/serialization/Codec"})),
				"a supertype outside the family cannot lead to a family event base");
	}

	@Test void aDeadEventTheKernelPostsIsDeliveredByTheKernelAndStillDead() throws Exception {
		// The kernel calling a dead hook, or constructing a dead event itself, delivers it — which tells a bridged or
		// repaired event from one nothing delivers. It does not make the merged game post it.
		Path carrier = outerAndInner(Map.of(FACTORY + "2.class", hookClass(FACTORY + "2", Map.of(), Map.of("onLonely", "forge/LonelyEvent"))));
		Map<String, Path> hooks = both(carrier);
		hooks.put(FACTORY + "2", carrier);
		Path kernel = base(Map.of("kernel/Bridge.class", hookClass("kernel/Bridge",
				Map.of("forward", List.<String[]>of(new String[] {HOOKS, "onOuter"})), Map.of("post", "forge/LonelyEvent"))));
		var all = HookCallSiteCensus.all(hooks, List.of(base(callerCalling("game/Level", List.of()))), List.of(carrier), List.of(kernel));
		assertEquals(java.util.Set.of("onInner()V"), all.get(FACTORY).viaKernel(), "reached through the kernel's call of the outer hook");
		assertEquals(java.util.Set.of("forge/InnerEvent"), all.get(FACTORY).deadEvents());
		assertEquals(java.util.Set.of("forge/InnerEvent"), all.get(FACTORY).kernelDelivered());
		assertEquals(java.util.Set.of("forge/LonelyEvent"), all.get(FACTORY + "2").kernelDelivered(), "constructed by the kernel itself");
		var without = HookCallSiteCensus.all(hooks, List.of(base(callerCalling("game/Level", List.of()))), List.of(carrier), List.of());
		assertEquals(java.util.Set.of(), without.get(FACTORY).kernelDelivered());
	}

	/** A class whose one static method hands out {@code target} as a method reference (an invokedynamic, no invoke). */
	private static Map<String, byte[]> referencing(String owner, String targetOwner, String target) {
		ClassWriter cw = new ClassWriter(0);
		cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, owner, null, "java/lang/Object", null);
		MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "install", "()Ljava/lang/Runnable;", null, null);
		mv.visitCode();
		org.objectweb.asm.Handle metafactory = new org.objectweb.asm.Handle(Opcodes.H_INVOKESTATIC, "java/lang/invoke/LambdaMetafactory",
				"metafactory", "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;"
						+ "Ljava/lang/invoke/MethodType;Ljava/lang/invoke/MethodHandle;Ljava/lang/invoke/MethodType;)Ljava/lang/invoke/CallSite;",
				false);
		mv.visitInvokeDynamicInsn("run", "()Ljava/lang/Runnable;", metafactory, org.objectweb.asm.Type.getType("()V"),
				new org.objectweb.asm.Handle(Opcodes.H_INVOKESTATIC, targetOwner, target, "()V", false), org.objectweb.asm.Type.getType("()V"));
		mv.visitInsn(Opcodes.ARETURN);
		mv.visitMaxs(1, 0);
		mv.visitEnd();
		cw.visitEnd();
		return Map.of(owner + ".class", cw.toByteArray());
	}

	@Test void aMethodReferenceToAHookReachesIt() throws Exception {
		// The kernel hands ForgeEventFactory::onPreClientTick to a listener: no invoke names the hook, and an invoke-only
		// count called it unreached by the kernel. The game handing one out is the game reaching it.
		Path carrier = outerAndInner(Map.of());
		Path kernel = base(referencing("kernel/Ticks", HOOKS, "onOuter"));
		var byKernel = HookCallSiteCensus.all(both(carrier), List.of(base(callerCalling("game/Level", List.of()))), List.of(carrier),
				List.of(kernel));
		assertEquals(java.util.Set.of("onInner()V"), byKernel.get(FACTORY).viaKernel());
		assertEquals(java.util.Set.of("forge/InnerEvent"), byKernel.get(FACTORY).kernelDelivered());
		var byGame = HookCallSiteCensus.all(both(carrier), List.of(base(referencing("game/Level", HOOKS, "onOuter"))), List.of(carrier),
				List.of());
		assertEquals(java.util.Set.of("onOuter()V"), byGame.get(HOOKS).live());
		assertEquals(java.util.Set.of(), byGame.get(FACTORY).deadEvents());
	}

	@Test void theGameIsCountedAsTheLoadTimeViewLeavesIt() throws Exception {
		// routePlaceItemHookToNeoForge, in miniature: the staged game calls the outer hook, and a repair sends the call to
		// another owner as the class loads. What runs posts nothing, so the event is dead; the staged bytes alone said live.
		Path carrier = outerAndInner(Map.of());
		Path game = base(callerCalling("game/Level", List.of(new Call("onOuter", "()V"))));
		java.util.function.BiFunction<String, byte[], byte[]> reroute = (name, bytes) -> {
			if (!name.equals("game/Level")) return bytes;
			org.objectweb.asm.tree.ClassNode node = new org.objectweb.asm.tree.ClassNode();
			new org.objectweb.asm.ClassReader(bytes).accept(node, 0);
			for (var m : node.methods) for (var insn : m.instructions) {
				if (insn instanceof org.objectweb.asm.tree.MethodInsnNode call && call.owner.equals(HOOKS)) call.owner = "other/Hooks";
			}
			ClassWriter cw = new ClassWriter(0);
			node.accept(cw);
			return cw.toByteArray();
		};
		var staged = HookCallSiteCensus.all(both(carrier), List.of(game), List.of(carrier), List.of());
		assertEquals(java.util.Set.of(), staged.get(FACTORY).deadEvents());
		var loaded = HookCallSiteCensus.all(both(carrier), List.of(game), List.of(carrier), List.of(), reroute);
		assertEquals(java.util.Set.of("forge/InnerEvent"), loaded.get(FACTORY).deadEvents());
		assertEquals(java.util.Set.of(), loaded.get(HOOKS).live());
	}

	/** A game class calling {@code hook} from {@code tick}; {@code onClass}/{@code onMethod} carry a family's @OnlyIn(CLIENT). */
	private static Map<String, byte[]> sided(String owner, String hook, boolean onClass, boolean onMethod) {
		ClassWriter cw = new ClassWriter(0);
		cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, owner, null, "java/lang/Object", null);
		if (onClass) onlyInClient(cw.visitAnnotation("Lforge/api/distmarker/OnlyIn;", true));
		MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "tick", "()V", null, null);
		if (onMethod) onlyInClient(mv.visitAnnotation("Lforge/api/distmarker/OnlyIn;", true));
		mv.visitCode();
		mv.visitMethodInsn(Opcodes.INVOKESTATIC, HOOKS, hook, "()V", false);
		mv.visitInsn(Opcodes.RETURN);
		mv.visitMaxs(1, 1);
		mv.visitEnd();
		cw.visitEnd();
		return Map.of(owner + ".class", cw.toByteArray());
	}

	private static void onlyInClient(org.objectweb.asm.AnnotationVisitor av) {
		av.visitEnum("value", "Lforge/api/distmarker/Dist;", "CLIENT");
		av.visitEnd();
	}

	@Test void aPostingSiteIsClientOnlyByItsFamilysMarkerOnTheMethodOrItsClass() throws Exception {
		// Where an event is posted decides whether a dedicated server ever posts it natively. The families' merge of the
		// client and server jars marks what only the client jar has; a package name says nothing of the kind.
		Map<String, byte[]> entries = new LinkedHashMap<>();
		entries.put(HOOKS + ".class", hookClass(HOOKS, Map.of("onOuter", List.<String[]>of(new String[] {HOOKS, "onC"})),
				Map.of("onA", "forge/AEvent", "onB", "forge/BEvent", "onC", "forge/CEvent")));
		Path carrier = carrier(entries);
		Map<String, byte[]> game = new LinkedHashMap<>();
		game.putAll(sided("game/ClientOnly", "onA", true, false));
		game.putAll(sided("game/Common", "onB", false, true));
		game.putAll(sided("game/Server", "onOuter", false, false));
		var sites = HookCallSiteCensus.postingSites(Map.of(HOOKS, carrier), List.of(base(game)), List.of(carrier));
		assertEquals(java.util.Set.of(new HookCallSiteCensus.PostingSite("game/ClientOnly#tick()V", true, false)), sites.get("forge/AEvent"));
		assertEquals(java.util.Set.of(new HookCallSiteCensus.PostingSite("game/Common#tick()V", true, false)), sites.get("forge/BEvent"));
		assertEquals(java.util.Set.of(new HookCallSiteCensus.PostingSite("game/Server#tick()V", false, false)), sites.get("forge/CEvent"),
				"reached through the outer hook the unmarked class calls");
	}

	@Test void theEcosystemRootIsTheFirstTwoPackageSegments() {
		assertEquals("net/minecraftforge/",
				HookCallSiteCensus.namespaceOf("net/minecraftforge/event/ForgeEventFactory"));
		assertEquals("net/neoforged/",
				HookCallSiteCensus.namespaceOf("net/neoforged/neoforge/event/EventHooks"));
		assertEquals("forge/", HookCallSiteCensus.namespaceOf("forge/Hooks"));
	}

	@Test void amethodCallingBothEcosystemsHookClassesIsTheDoublePostShape() throws Exception {
		// The only way one path delivers a bridged event twice: a bridge fires on the OTHER ecosystem's event,
		// so unless something fires both, the two never meet. "The base also posts it directly" is not this,
		// and reading it as this produced a finding on the real base that did not survive being checked.
		Map<String, byte[]> entries = new LinkedHashMap<>();
		entries.putAll(callsHooks("game/Single", List.of("net/minecraftforge/event/ForgeEventFactory")));
		entries.putAll(callsHooks("game/Double", List.of("net/minecraftforge/event/ForgeEventFactory",
				"net/neoforged/neoforge/event/EventHooks")));
		// An ordinary utility in the same namespace is not a hook entry point; counting it would flag methods
		// that post nothing.
		entries.putAll(callsHooks("game/Utility", List.of("net/minecraftforge/event/ForgeEventFactory",
				"net/neoforged/neoforge/common/util/Whatever")));
		Path jar = base(entries);
		assertEquals(List.of("game/Double#hook()V"), HookCallSiteCensus.methodsCallingBothFamilies(List.of(jar)));
	}

	@Test void theHookClassRuleNamesEntryPointsNotNamespaces() {
		assertTrue(HookCallSiteCensus.isHookClass("net/minecraftforge/event/ForgeEventFactory", "net/minecraftforge/"));
		assertTrue(HookCallSiteCensus.isHookClass("net/neoforged/neoforge/event/EventHooks", "net/neoforged/"));
		assertFalse(HookCallSiteCensus.isHookClass("net/minecraftforge/common/util/BlockSnapshot", "net/minecraftforge/"));
		assertFalse(HookCallSiteCensus.isHookClass("net/neoforged/neoforge/event/EventHooks", "net/minecraftforge/"));
	}

	private static Map<String, byte[]> callsHooks(String owner, List<String> hookOwners) {
		ClassWriter cw = new ClassWriter(0);
		cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, owner, null, "java/lang/Object", null);
		MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "hook", "()V", null, null);
		mv.visitCode();
		for (String h : hookOwners) mv.visitMethodInsn(Opcodes.INVOKESTATIC, h, "on", "()V", false);
		mv.visitInsn(Opcodes.RETURN);
		mv.visitMaxs(4, 4);
		mv.visitEnd();
		cw.visitEnd();
		Map<String, byte[]> out = new LinkedHashMap<>();
		out.put(owner + ".class", cw.toByteArray());
		return out;
	}

	@Test void theSummaryLeadsWithTheDenominator() throws Exception {
		Path carrier = carrier(hooks(hook("onCalled", "()V"), hook("onNeverCalled", "()V")));
		Path base = base(callerCalling("game/Level", List.of(new Call("onCalled", "()V"))));
		String summary = HookCallSiteCensus.of(carrier, HOOKS, List.of(base)).summary();
		// A run that scanned nothing must not read like a run that found nothing, so the declared count is first.
		assertTrue(summary.contains("2 declared, 1 called by the game"), summary);
		assertTrue(summary.contains("1 called by nothing"), summary);
		assertTrue(summary.startsWith("[Forbric/Hooks] " + HOOKS + ":"), summary);
	}

	// ---- fixture helpers ----

	private record Call(String name, String desc) {
	}

	/** A hook class declaring exactly these public static methods, each with an empty body. */
	private static Map<String, byte[]> hooks(Call... declared) {
		ClassWriter cw = new ClassWriter(0);
		cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, HOOKS, null, "java/lang/Object", null);
		for (Call c : declared) emit(cw, c.name(), c.desc(), List.of());
		cw.visitEnd();
		Map<String, byte[]> out = new LinkedHashMap<>();
		out.put(HOOKS + ".class", cw.toByteArray());
		return out;
	}

	private static Call hook(String name, String desc) {
		return new Call(name, desc);
	}

	private Path carrier(Map<String, byte[]> entries) throws Exception {
		return write(temporary.resolve("carrier-" + entries.hashCode() + ".jar"), entries);
	}

	private Path base(Map<String, byte[]> entries) throws Exception {
		return write(temporary.resolve("base-" + entries.hashCode() + ".jar"), entries);
	}

	private static Path write(Path jar, Map<String, byte[]> entries) throws Exception {
		try (OutputStream os = Files.newOutputStream(jar); ZipOutputStream zos = new ZipOutputStream(os)) {
			for (Map.Entry<String, byte[]> e : entries.entrySet()) {
				zos.putNextEntry(new ZipEntry(e.getKey()));
				zos.write(e.getValue());
				zos.closeEntry();
			}
		}
		return jar;
	}

	private static Map<String, byte[]> callerCalling(String owner, List<Call> calls) {
		ClassWriter cw = new ClassWriter(0);
		cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, owner, null, "java/lang/Object", null);
		MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "tick", "()V", null, null);
		mv.visitCode();
		for (Call c : calls) mv.visitMethodInsn(Opcodes.INVOKESTATIC, HOOKS, c.name(), c.desc(), false);
		mv.visitInsn(Opcodes.RETURN);
		mv.visitMaxs(4, 4);
		mv.visitEnd();
		cw.visitEnd();
		Map<String, byte[]> out = new LinkedHashMap<>();
		out.put(owner + ".class", cw.toByteArray());
		return out;
	}

	private static void emit(ClassWriter cw, String name, String desc, List<Call> selfCalls) {
		MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, name, desc, null, null);
		mv.visitCode();
		for (Call c : selfCalls) mv.visitMethodInsn(Opcodes.INVOKESTATIC, HOOKS, c.name(), c.desc(), false);
		mv.visitInsn(Opcodes.RETURN);
		mv.visitMaxs(4, 4);
		mv.visitEnd();
	}

	private static void posts(ClassWriter cw, String name, String eventType) {
		MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, name, "()V", null, null);
		mv.visitCode();
		mv.visitTypeInsn(Opcodes.NEW, eventType);
		mv.visitInsn(Opcodes.POP);
		mv.visitInsn(Opcodes.RETURN);
		mv.visitMaxs(4, 4);
		mv.visitEnd();
	}
}
