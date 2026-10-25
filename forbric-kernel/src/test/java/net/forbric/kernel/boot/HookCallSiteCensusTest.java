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

	@Test void aCarrierCallerThatIsNotAHookCountsOnlyWhenSomethingReachesIt() throws Exception {
		// MinecraftForge's container-open event, in miniature: a public method of a carrier class that is not a hook class
		// (IForgeServerPlayer.openMenu) calls the hook that posts it, and nothing the game runs calls that method. Counting any
		// carrier caller called the event posted; it is posted only when a guest calls that API.
		Map<String, byte[]> helper = Map.of("forge/Helper.class",
				hookClass("forge/Helper", Map.of("help", List.<String[]>of(new String[] {FACTORY, "onInner"})), Map.of()));
		Path carrier = outerAndInner(helper);
		var unreached = HookCallSiteCensus.all(both(carrier), List.of(base(callerCalling("game/Level", List.of()))), List.of(carrier), List.of());
		assertEquals(java.util.Set.of(), unreached.get(FACTORY).reachedOnlyViaCarrier());
		assertEquals(java.util.Set.of("forge/InnerEvent"), unreached.get(FACTORY).deadEvents());
		assertEquals(java.util.Set.of("forge/InnerEvent"), unreached.get(FACTORY).guestPosted(), "a guest calling forge/Helper.help posts it");
		assertEquals(List.of(FACTORY + ".onInner()V", "forge/Helper.help()V", "<a guest calling public API>"),
				unreached.get(FACTORY).witness().get("onInner()V"));
		// Once the game calls it, the hook is reached through it, and the census says why.
		Map<String, byte[]> game = callerCallingOn("game/Level", "forge/Helper", List.of(new Call("help", "()V")));
		var reached = HookCallSiteCensus.all(both(carrier), List.of(base(game)), List.of(carrier), List.of());
		assertEquals(java.util.Set.of("onInner()V"), reached.get(FACTORY).reachedOnlyViaCarrier());
		assertEquals(java.util.Set.of(), reached.get(FACTORY).deadEvents());
		assertEquals(List.of(FACTORY + ".onInner()V", "forge/Helper.help()V", "<game>"), reached.get(FACTORY).witness().get("onInner()V"));
	}

	@Test void aMethodAFamilysOwnGameCallsIsItsEntryNotAGuestsApi() throws Exception {
		// MinecraftForge's patched LiquidBlock calls FluidInteractionRegistry.canInteract: a public method the family's own
		// game enters is not API a guest is assumed to call, so a merged game that does not call it has simply lost a path.
		Map<String, byte[]> helper = Map.of("forge/Helper.class",
				hookClass("forge/Helper", Map.of("help", List.<String[]>of(new String[] {FACTORY, "onInner"})), Map.of()));
		Path carrier = outerAndInner(helper);
		Path nativeGame = base(callerCallingOn("game/NativeLevel", "forge/Helper", List.of(new Call("help", "()V"))));
		var census = HookCallSiteCensus.all(both(carrier), List.of(base(callerCalling("game/Level", List.of()))), List.of(carrier),
				List.of(), null, List.of(nativeGame));
		assertEquals(java.util.Set.of("forge/InnerEvent"), census.get(FACTORY).deadEvents());
		assertEquals(java.util.Set.of(), census.get(FACTORY).guestPosted());
	}

	@Test void aLambdaInAHookClassReachesItsOwnHook() throws Exception {
		// NeoForge's chat decorator: the game calls a hook that hands out a lambda, and the lambda calls the hook that posts
		// the event. A hook class calling its own hook from a dead hook is nothing; from a lambda the game's call hands out it
		// is the runtime reaching it. The lambda counts only because the hook the game calls hands it out.
		ClassWriter cw = new ClassWriter(0);
		cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, HOOKS, null, "java/lang/Object", null);
		posts(cw, "onChat", "forge/ChatEvent");
		MethodVisitor lambda = cw.visitMethod(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_SYNTHETIC, "lambda$0", "()V", null, null);
		lambda.visitCode();
		lambda.visitMethodInsn(Opcodes.INVOKESTATIC, HOOKS, "onChat", "()V", false);
		lambda.visitInsn(Opcodes.RETURN);
		lambda.visitMaxs(1, 0);
		lambda.visitEnd();
		MethodVisitor decorator = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "decorator", "()Ljava/lang/Runnable;", null, null);
		decorator.visitCode();
		decorator.visitInvokeDynamicInsn("run", "()Ljava/lang/Runnable;", METAFACTORY, org.objectweb.asm.Type.getType("()V"),
				new org.objectweb.asm.Handle(Opcodes.H_INVOKESTATIC, HOOKS, "lambda$0", "()V", false), org.objectweb.asm.Type.getType("()V"));
		decorator.visitInsn(Opcodes.ARETURN);
		decorator.visitMaxs(1, 0);
		decorator.visitEnd();
		cw.visitEnd();
		Path carrier = carrier(Map.of(HOOKS + ".class", cw.toByteArray()));
		var called = HookCallSiteCensus.all(Map.of(HOOKS, carrier),
				List.of(base(callerCalling("game/Level", List.of(new Call("decorator", "()Ljava/lang/Runnable;"))))), List.of(carrier), List.of());
		assertEquals(java.util.Set.of("onChat()V"), called.get(HOOKS).reachedOnlyViaCarrier());
		assertEquals(java.util.Set.of(), called.get(HOOKS).deadEvents());
		var uncalled = HookCallSiteCensus.all(Map.of(HOOKS, carrier), List.of(base(callerCalling("game/Level", List.of()))),
				List.of(carrier), List.of());
		assertEquals(java.util.Set.of("forge/ChatEvent"), uncalled.get(HOOKS).deadEvents(), "a lambda nothing hands out is reached by nothing");
	}

	@Test void aGameClassInheritingACarrierDefaultMethodReachesIt() throws Exception {
		// A merged game class implementing a family's extension interface: the game calling the method on itself resolves to
		// the interface's default method, unless the game class declares the method itself.
		Map<String, byte[]> ext = new LinkedHashMap<>();
		ClassWriter cw = new ClassWriter(0);
		cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_INTERFACE | Opcodes.ACC_ABSTRACT, "forge/IExt", null, "java/lang/Object", null);
		MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "openMenu", "()V", null, null);
		mv.visitCode();
		mv.visitMethodInsn(Opcodes.INVOKESTATIC, FACTORY, "onInner", "()V", false);
		mv.visitInsn(Opcodes.RETURN);
		mv.visitMaxs(1, 1);
		mv.visitEnd();
		cw.visitEnd();
		ext.put("forge/IExt.class", cw.toByteArray());
		Path carrier = outerAndInner(ext);
		for (boolean overrides : List.of(false, true)) {
			Map<String, byte[]> game = new LinkedHashMap<>(gameClass("game/Player", "java/lang/Object", new String[] {"forge/IExt"}, overrides ? "openMenu" : null));
			game.putAll(virtualCaller("game/Level", "game/Player", "openMenu", "()V"));
			var census = HookCallSiteCensus.all(both(carrier), List.of(base(game)), List.of(carrier), List.of());
			assertEquals(overrides ? java.util.Set.of("forge/InnerEvent") : java.util.Set.of(), census.get(FACTORY).deadEvents(),
					overrides ? "the game class's own openMenu shadows the default" : "the default method is what the game's call runs");
		}
	}

	@Test void aVirtualCallDispatchesOnlyIntoACarrierClassSomethingConstructs() throws Exception {
		// A carrier class overriding a game method: the game's virtual call can run it only once something has constructed it.
		Map<String, byte[]> impl = new LinkedHashMap<>();
		impl.put("forge/Impl.class", overriding("forge/Impl", "game/Base", "run"));
		impl.put("forge/Maker.class", hookClass("forge/Maker", Map.of(), Map.of("make", "forge/Impl")));
		Path carrier = outerAndInner(impl);
		Map<String, byte[]> game = new LinkedHashMap<>(gameClass("game/Base", "java/lang/Object", new String[0], "run"));
		game.putAll(virtualCaller("game/Level", "game/Base", "run", "()V"));
		var none = HookCallSiteCensus.all(both(carrier), List.of(base(game)), List.of(carrier), List.of());
		assertEquals(java.util.Set.of("forge/InnerEvent"), none.get(FACTORY).deadEvents(), "nothing constructs forge/Impl");
		game.putAll(callerCallingOn("game/Spawner", "forge/Maker", List.of(new Call("make", "()V"))));
		var constructed = HookCallSiteCensus.all(both(carrier), List.of(base(game)), List.of(carrier), List.of());
		assertEquals(java.util.Set.of(), constructed.get(FACTORY).deadEvents(), "the game constructs forge/Impl through forge/Maker");
	}

	@Test void theRuntimeEntersAListenerAModClassAndAStaticInitializer() throws Exception {
		Map<String, byte[]> entries = new LinkedHashMap<>();
		entries.put("forge/Listener.class", annotatedCaller("forge/Listener", "Lnet/minecraftforge/eventbus/api/listener/SubscribeEvent;", false));
		Path listener = outerAndInner(entries);
		assertEquals(java.util.Set.of(), HookCallSiteCensus.all(both(listener), List.of(base(callerCalling("game/Level", List.of()))),
				List.of(listener), List.of()).get(FACTORY).deadEvents(), "a listener is entered by its bus");
		entries.clear();
		entries.put("forge/MyMod.class", annotatedCaller("forge/MyMod", "Lnet/minecraftforge/fml/common/Mod;", true));
		Path mod = outerAndInner(entries);
		assertEquals(java.util.Set.of(), HookCallSiteCensus.all(both(mod), List.of(base(callerCalling("game/Level", List.of()))),
				List.of(mod), List.of()).get(FACTORY).deadEvents(), "a mod class is constructed by its loader");
		entries.clear();
		entries.put("forge/Registry.class", initializerCaller("forge/Registry"));
		Path registry = outerAndInner(entries);
		assertEquals(java.util.Set.of("forge/InnerEvent"), HookCallSiteCensus.all(both(registry), List.of(base(callerCalling("game/Level", List.of()))),
				List.of(registry), List.of()).get(FACTORY).deadEvents(), "nothing touches forge/Registry");
		assertEquals(java.util.Set.of(), HookCallSiteCensus.all(both(registry), List.of(base(staticReader("game/Level", "forge/Registry"))),
				List.of(registry), List.of()).get(FACTORY).deadEvents(), "the game reading its static field runs its initializer");
	}

	@Test void aKernelReflectiveLookupByBinaryNameIsACall() throws Exception {
		// PayloadInterop: factory.getMethod("gatherLoginConfigTasks", ...) on the class it loaded by its binary name.
		Path carrier = outerAndInner(Map.of());
		Map<String, Path> hooks = both(carrier);
		Path direct = base(reflector("kernel/Direct", "forge.Factory", "onInner", true, null));
		Path slashed = base(reflector("kernel/Slashed", FACTORY, "onInner", true, null));
		Path noLookup = base(reflector("kernel/NoLookup", "forge.Factory", "onInner", false, null));
		Map<String, byte[]> viaHelper = new LinkedHashMap<>(reflector("kernel/Caller", "forge.Factory", "onInner", false, "kernel/Helper"));
		viaHelper.putAll(reflector("kernel/Helper", null, null, true, null));
		Path helper = base(viaHelper);
		Path game = base(callerCalling("game/Level", List.of()));
		assertEquals(java.util.Set.of("forge/InnerEvent"), HookCallSiteCensus.all(hooks, List.of(game), List.of(carrier), List.of(direct))
				.get(FACTORY).kernelDelivered(), "a binary name, a method name and a reflective lookup are a call");
		assertEquals(java.util.Set.of("forge/InnerEvent"), HookCallSiteCensus.all(hooks, List.of(game), List.of(carrier), List.of(helper))
				.get(FACTORY).kernelDelivered(), "a helper that does the lookup makes its caller the reflective one");
		assertEquals(java.util.Set.of(), HookCallSiteCensus.all(hooks, List.of(game), List.of(carrier), List.of(slashed))
				.get(FACTORY).kernelDelivered(), "a slashed internal name is a transformer matching bytecode, not a lookup");
		assertEquals(java.util.Set.of(), HookCallSiteCensus.all(hooks, List.of(game), List.of(carrier), List.of(noLookup))
				.get(FACTORY).kernelDelivered(), "the names alone, with no lookup, are not a call");
	}

	private static final org.objectweb.asm.Handle METAFACTORY = new org.objectweb.asm.Handle(Opcodes.H_INVOKESTATIC,
			"java/lang/invoke/LambdaMetafactory", "metafactory", "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;"
					+ "Ljava/lang/invoke/MethodType;Ljava/lang/invoke/MethodType;Ljava/lang/invoke/MethodHandle;Ljava/lang/invoke/MethodType;)"
					+ "Ljava/lang/invoke/CallSite;", false);

	/** A game class whose {@code tick} calls each of {@code calls} statically on {@code target}. */
	private static Map<String, byte[]> callerCallingOn(String owner, String target, List<Call> calls) {
		ClassWriter cw = new ClassWriter(0);
		cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, owner, null, "java/lang/Object", null);
		MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "tick", "()V", null, null);
		mv.visitCode();
		for (Call c : calls) {
			mv.visitMethodInsn(Opcodes.INVOKESTATIC, target, c.name(), c.desc(), false);
			if (!c.desc().endsWith(")V")) mv.visitInsn(Opcodes.POP);
		}
		mv.visitInsn(Opcodes.RETURN);
		mv.visitMaxs(4, 4);
		mv.visitEnd();
		cw.visitEnd();
		return Map.of(owner + ".class", cw.toByteArray());
	}

	/** A game class {@code name extends superName implements interfaces}, declaring {@code declares()V} when not null. */
	private static Map<String, byte[]> gameClass(String name, String superName, String[] interfaces, String declares) {
		ClassWriter cw = new ClassWriter(0);
		cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, name, null, superName, interfaces);
		if (declares != null) {
			MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC, declares, "()V", null, null);
			mv.visitCode();
			mv.visitInsn(Opcodes.RETURN);
			mv.visitMaxs(0, 1);
			mv.visitEnd();
		}
		cw.visitEnd();
		return Map.of(name + ".class", cw.toByteArray());
	}

	/** A game class whose {@code tick} calls {@code name+desc} virtually on {@code receiver}. */
	private static Map<String, byte[]> virtualCaller(String owner, String receiver, String name, String desc) {
		ClassWriter cw = new ClassWriter(0);
		cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, owner, null, "java/lang/Object", null);
		MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "tick", "(L" + receiver + ";)V", null, null);
		mv.visitCode();
		mv.visitVarInsn(Opcodes.ALOAD, 1);
		mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, receiver, name, desc, false);
		mv.visitInsn(Opcodes.RETURN);
		mv.visitMaxs(1, 2);
		mv.visitEnd();
		cw.visitEnd();
		return Map.of(owner + ".class", cw.toByteArray());
	}

	/** A carrier class extending {@code superName} whose {@code method()V} calls forge/Factory.onInner. */
	private static byte[] overriding(String name, String superName, String method) {
		ClassWriter cw = new ClassWriter(0);
		cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, name, null, superName, null);
		MethodVisitor init = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
		init.visitCode();
		init.visitVarInsn(Opcodes.ALOAD, 0);
		init.visitMethodInsn(Opcodes.INVOKESPECIAL, superName, "<init>", "()V", false);
		init.visitInsn(Opcodes.RETURN);
		init.visitMaxs(1, 1);
		init.visitEnd();
		MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC, method, "()V", null, null);
		mv.visitCode();
		mv.visitMethodInsn(Opcodes.INVOKESTATIC, FACTORY, "onInner", "()V", false);
		mv.visitInsn(Opcodes.RETURN);
		mv.visitMaxs(1, 1);
		mv.visitEnd();
		cw.visitEnd();
		return cw.toByteArray();
	}

	/**
	 * A carrier class carrying {@code annotation}: on its class with a constructor calling forge/Factory.onInner when
	 * {@code onClass}, else on a method calling it.
	 */
	private static byte[] annotatedCaller(String name, String annotation, boolean onClass) {
		ClassWriter cw = new ClassWriter(0);
		cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, name, null, "java/lang/Object", null);
		if (onClass) cw.visitAnnotation(annotation, true).visitEnd();
		MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC, onClass ? "<init>" : "on", "()V", null, null);
		if (!onClass) mv.visitAnnotation(annotation, true).visitEnd();
		mv.visitCode();
		mv.visitMethodInsn(Opcodes.INVOKESTATIC, FACTORY, "onInner", "()V", false);
		mv.visitInsn(Opcodes.RETURN);
		mv.visitMaxs(1, 1);
		mv.visitEnd();
		cw.visitEnd();
		return cw.toByteArray();
	}

	/** A carrier class whose static initializer calls forge/Factory.onInner, with a static field to read. */
	private static byte[] initializerCaller(String name) {
		ClassWriter cw = new ClassWriter(0);
		cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, name, null, "java/lang/Object", null);
		cw.visitField(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "X", "I", null, null).visitEnd();
		MethodVisitor mv = cw.visitMethod(Opcodes.ACC_STATIC, "<clinit>", "()V", null, null);
		mv.visitCode();
		mv.visitMethodInsn(Opcodes.INVOKESTATIC, FACTORY, "onInner", "()V", false);
		mv.visitInsn(Opcodes.RETURN);
		mv.visitMaxs(1, 0);
		mv.visitEnd();
		cw.visitEnd();
		return cw.toByteArray();
	}

	/** A game class whose {@code tick} reads {@code owner.X}. */
	private static Map<String, byte[]> staticReader(String name, String owner) {
		ClassWriter cw = new ClassWriter(0);
		cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, name, null, "java/lang/Object", null);
		MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "tick", "()V", null, null);
		mv.visitCode();
		mv.visitFieldInsn(Opcodes.GETSTATIC, owner, "X", "I");
		mv.visitInsn(Opcodes.POP);
		mv.visitInsn(Opcodes.RETURN);
		mv.visitMaxs(1, 1);
		mv.visitEnd();
		cw.visitEnd();
		return Map.of(name + ".class", cw.toByteArray());
	}

	/**
	 * A kernel class whose {@code run} holds {@code className} and {@code methodName} as constants (when not null), looks a
	 * method up with {@code Class.getMethod} when {@code looksUp}, and calls {@code helper.find(String,String)} when not null.
	 */
	private static Map<String, byte[]> reflector(String name, String className, String methodName, boolean looksUp, String helper) {
		ClassWriter cw = new ClassWriter(0);
		cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, name, null, "java/lang/Object", null);
		MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, helper == null && className == null ? "find" : "run",
				helper == null && className == null ? "(Ljava/lang/String;Ljava/lang/String;)V" : "()V", null, null);
		mv.visitCode();
		if (className != null) {
			mv.visitLdcInsn(className);
			mv.visitLdcInsn(methodName);
			if (helper != null) mv.visitMethodInsn(Opcodes.INVOKESTATIC, helper, "find", "(Ljava/lang/String;Ljava/lang/String;)V", false);
			else {
				mv.visitInsn(Opcodes.POP2);
			}
		}
		if (looksUp) {
			mv.visitLdcInsn(org.objectweb.asm.Type.getType("Ljava/lang/Object;"));
			mv.visitLdcInsn("x");
			mv.visitInsn(Opcodes.ICONST_0);
			mv.visitTypeInsn(Opcodes.ANEWARRAY, "java/lang/Class");
			mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/Class", "getMethod",
					"(Ljava/lang/String;[Ljava/lang/Class;)Ljava/lang/reflect/Method;", false);
			mv.visitInsn(Opcodes.POP);
		}
		mv.visitInsn(Opcodes.RETURN);
		mv.visitMaxs(4, 2);
		mv.visitEnd();
		cw.visitEnd();
		return Map.of(name + ".class", cw.toByteArray());
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

	/**
	 * An event whose constructor calls a hook posting another event. MinecraftForge's {@code BreakEvent} asks
	 * {@code ForgeHooks.isCorrectToolForDrops} from its constructor, which posts {@code PlayerEvent$HarvestCheck}: a hook
	 * constructing the break posts a harvest check too (the hook's events and call-site counts follow the constructor), and
	 * the kernel posting a break delivers one — but on the break's path only, so it is not what the kernel posts itself
	 * ({@code kernelConstructs}, what proves a bridge's or a repair's row): filing the harvest check under the break bridge
	 * would silence the audit for a check the game asks on paths no break takes.
	 */
	@Test void anEventsConstructorPostsForItsHookButNotAsTheKernelsOwnDelivery() throws Exception {
		String base = "net/minecraftforge/eventbus/internal/Event", brk = "forge/BreakEvent", harvest = "forge/HarvestEvent";
		Map<String, byte[]> entries = new LinkedHashMap<>();
		ClassWriter hooks = new ClassWriter(0);
		hooks.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, HOOKS, null, "java/lang/Object", null);
		constructing(hooks, "onBreak", brk);
		constructing(hooks, "onHarvest", harvest);
		hooks.visitEnd();
		entries.put(HOOKS + ".class", hooks.toByteArray());
		entries.put(harvest + ".class", event(harvest, base, null));
		entries.put(brk + ".class", event(brk, base, new String[] {HOOKS, "onHarvest"}));
		Path carrier = carrier(entries);
		ClassWriter bridge = new ClassWriter(0);
		bridge.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "kernel/Bridge", null, "java/lang/Object", null);
		constructing(bridge, "forward", brk);
		bridge.visitEnd();
		Path kernel = base(Map.of("kernel/Bridge.class", bridge.toByteArray()));
		var census = HookCallSiteCensus.all(Map.of(HOOKS, carrier), List.of(base(callerCalling("game/Level", List.of()))), List.of(carrier),
				List.of(kernel)).get(HOOKS);
		assertEquals(java.util.Set.of(brk, harvest), census.postsThrough().get("onBreak()V"), "constructing the break posts a harvest check");
		assertEquals(java.util.Set.of(brk, harvest), census.kernelDelivered(), "the kernel's break delivers a harvest check on its path");
		assertTrue(census.kernelConstructs().contains(brk), "the kernel posts the break itself");
		assertFalse(census.kernelConstructs().contains(harvest), "the harvest check is the break constructor's, not the kernel's own delivery");
	}

	/** A public static {@code name()V} constructing {@code type} through its no-argument constructor. */
	private static void constructing(ClassWriter cw, String name, String type) {
		MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, name, "()V", null, null);
		mv.visitCode();
		mv.visitTypeInsn(Opcodes.NEW, type);
		mv.visitInsn(Opcodes.DUP);
		mv.visitMethodInsn(Opcodes.INVOKESPECIAL, type, "<init>", "()V", false);
		mv.visitInsn(Opcodes.POP);
		mv.visitInsn(Opcodes.RETURN);
		mv.visitMaxs(2, 0);
		mv.visitEnd();
	}

	/** An event class {@code name extends superName} whose constructor calls {@code call} (owner, name) when given. */
	private static byte[] event(String name, String superName, String[] call) {
		ClassWriter cw = new ClassWriter(0);
		cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, name, null, superName, null);
		MethodVisitor init = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
		init.visitCode();
		init.visitVarInsn(Opcodes.ALOAD, 0);
		init.visitMethodInsn(Opcodes.INVOKESPECIAL, superName, "<init>", "()V", false);
		if (call != null) init.visitMethodInsn(Opcodes.INVOKESTATIC, call[0], call[1], "()V", false);
		init.visitInsn(Opcodes.RETURN);
		init.visitMaxs(1, 1);
		init.visitEnd();
		cw.visitEnd();
		return cw.toByteArray();
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
