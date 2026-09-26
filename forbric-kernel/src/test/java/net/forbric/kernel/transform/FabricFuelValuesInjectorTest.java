package net.forbric.kernel.transform;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.BasicVerifier;

/**
 * NeoForge's DataMapHooks.populateFuelValues runs Fabric's fuel events on its builder just before building it, then
 * hands the table through vanillaBurnTimes' return hooks, whose merge-added body returns it without rebuilding vanilla's.
 */
@ResourceLock("system-properties")
class FabricFuelValuesInjectorTest {
	private static final Path RUN = Path.of(System.getProperty("forbric.stagedRoot", "../forbric-loader/run"));
	private static final Path NEO_RT = RUN.resolve("neoforge-runtime/neoforge-runtime.jar");
	private static final Path MERGED = RUN.resolve("merged-base/patched-mc-merged-26.2.jar");
	private static final String HOOKS = "net/neoforged/neoforge/common/DataMapHooks";
	private static final String FUEL_VALUES = FabricFuelValuesInjector.FUEL_VALUES;
	private static final String STUB_DESC = "(Lnet/minecraft/core/HolderLookup$Provider;Lnet/minecraft/world/flag/FeatureFlagSet;I)L"
			+ FUEL_VALUES + ";";
	/** The two-argument overload the native Fabric and MinecraftForge servers call; it forwards to STUB_DESC with 200. */
	private static final String OUTER_DESC = "(Lnet/minecraft/core/HolderLookup$Provider;Lnet/minecraft/world/flag/FeatureFlagSet;)L"
			+ FUEL_VALUES + ";";
	/** A stand-in for a guest RETURN modifier on the forwarding overload, as torrential's @ModifyReturnValue compiles. */
	private static final String RETURN_HOOK = "test/fuel/ReturnHook";

	@AfterEach void reset() {
		System.clearProperty(FabricFuelValuesInjector.PROPERTY);
		System.clearProperty(FabricFuelValuesInjector.RETURN_HOOKS_PROPERTY);
	}

	@Test void theBuilderGoesThroughFabricRightBeforeItIsBuiltAndTheTableThroughTheReturnHooksAfter() throws Exception {
		byte[] original = NativeCoremodParityTest.read(NEO_RT, HOOKS);
		byte[] out = new FabricFuelValuesInjector().transform(FabricFuelValuesInjector.HOOKS, original, null);
		assertNotSame(original, out);
		MethodNode populate = populate(out);
		List<String> calls = calls(populate);
		assertEquals(List.of("apply", "build", "throughVanillaReturnHooks"), calls.subList(calls.size() - 3, calls.size()));
		new Analyzer<>(new BasicVerifier()).analyze(HOOKS, populate);
		assertSame(out, new FabricFuelValuesInjector().transform(FabricFuelValuesInjector.HOOKS, out, null));
		System.setProperty(FabricFuelValuesInjector.PROPERTY, "off");
		assertSame(original, new FabricFuelValuesInjector().transform(FabricFuelValuesInjector.HOOKS, original, null));
	}

	@Test void withTheReturnHooksOffOnlyTheFuelEventsGoIn() throws Exception {
		System.setProperty(FabricFuelValuesInjector.RETURN_HOOKS_PROPERTY, "off");
		byte[] original = NativeCoremodParityTest.read(NEO_RT, HOOKS);
		List<String> calls = calls(populate(new FabricFuelValuesInjector().transform(FabricFuelValuesInjector.HOOKS, original, null)));
		assertEquals(List.of("apply", "build"), calls.subList(calls.size() - 2, calls.size()));
		assertFalse(calls.contains("throughVanillaReturnHooks"));
		byte[] fuel = NativeCoremodParityTest.read(MERGED, FUEL_VALUES);
		assertSame(fuel, new FabricFuelValuesInjector().transform(FabricFuelValuesInjector.FUEL_VALUES_CLASS, fuel, null));
	}

	/**
	 * The short-circuit is in the body, never in the forwarding overload — MixinStubRebind recognises the overload as
	 * the carrier's stub only while it forwards and nothing else — and the body keeps exactly one return.
	 */
	@Test void theForwardingOverloadStaysPureAndTheBodyKeepsItsOneReturn() throws Exception {
		byte[] original = NativeCoremodParityTest.read(MERGED, FUEL_VALUES);
		byte[] out = new FabricFuelValuesInjector().transform(FabricFuelValuesInjector.FUEL_VALUES_CLASS, original, null);
		assertNotSame(original, out);
		ClassNode before = node(original), after = node(out);
		assertEquals(opcodes(method(before, STUB_DESC)), opcodes(method(after, STUB_DESC)), "the forwarding overload is untouched");
		MethodNode body = method(after, FabricFuelValuesInjector.BODY_DESC);
		AbstractInsnNode first = body.instructions.getFirst();
		while (first.getOpcode() < 0) first = first.getNext();
		assertTrue(first instanceof MethodInsnNode call && call.name.equals("takePending"), "the body opens with the pending check");
		long returns = java.util.Arrays.stream(body.instructions.toArray()).filter(i -> i.getOpcode() == Opcodes.ARETURN).count();
		assertEquals(1, returns, "a RETURN or TAIL injector on the body sees one return, reached by both paths");
		assertTrue(calls(body).contains("remove"), "the body itself, and fabric-content-registries' anchor in it, are still there");
		assertSame(out, FabricFuelValuesInjector.shortCircuitTheBody(out), "idempotent");
	}

	/**
	 * The adapters read the class after this transformer, so the forwarding overload must still read as the carrier's
	 * stub and a Fabric injector anchored in the body must still move there — the verifier's reason the short-circuit
	 * may not go into the overload. Its RETURN-only counterpart moves too, and on the body it now meets one return.
	 */
	@Test void theStubRebindStillMovesAFabricInjectorIntoTheBody() throws Exception {
		byte[] out = new FabricFuelValuesInjector().transform(FabricFuelValuesInjector.FUEL_VALUES_CLASS,
				NativeCoremodParityTest.read(MERGED, FUEL_VALUES), null);
		ClassNode fuel = node(out);
		assertTrue(net.forbric.kernel.mixin.MixinStubRebind.isCarrierStub(fuel, method(fuel, STUB_DESC)));
		String builder = "L" + FabricFuelValuesInjector.BUILDER + ";";
		String add = builder + "add(Lnet/minecraft/world/level/ItemLike;I)" + builder;
		String stub = "vanillaBurnTimes" + STUB_DESC, body = "vanillaBurnTimes" + FabricFuelValuesInjector.BODY_DESC;
		ClassNode wrap = fabricMixin("com/example/FuelWrap", "wrapAdd", "(" + builder + "Lnet/minecraft/world/level/ItemLike;I"
				+ "Lcom/llamalad7/mixinextras/injector/wrapoperation/Operation;)" + builder,
				"Lcom/llamalad7/mixinextras/injector/wrapoperation/WrapOperation;", stub, "INVOKE", add);
		ClassNode modify = fabricMixin("com/example/FuelReturn", "modify", "(L" + FUEL_VALUES + ";)L" + FUEL_VALUES + ";",
				"Lcom/llamalad7/mixinextras/injector/ModifyReturnValue;", stub, "RETURN", null);
		try {
			assertEquals(1, net.forbric.kernel.mixin.MixinStubRebind.adapt(wrap, name -> fuel));
			assertEquals(1, net.forbric.kernel.mixin.MixinStubRebind.adapt(modify, name -> fuel));
		} finally {
			net.forbric.kernel.mixin.MixinStubRebindAccess.forget();
		}
		for (ClassNode mixin : List.of(wrap, modify)) {
			AnnotationNode injector = mixin.methods.getFirst().visibleAnnotations.getFirst();
			assertEquals(List.of(body), injector.values.get(injector.values.indexOf("method") + 1), mixin.name);
		}
	}

	private static ClassNode fabricMixin(String name, String handler, String desc, String injectorDesc, String selector,
			String point, String member) {
		ClassNode mixin = new ClassNode();
		mixin.version = Opcodes.V21;
		mixin.name = name;
		mixin.superName = "java/lang/Object";
		AnnotationNode type = new AnnotationNode("Lorg/spongepowered/asm/mixin/Mixin;");
		type.values = new ArrayList<>(List.of("value", new ArrayList<>(List.of(org.objectweb.asm.Type.getObjectType(FUEL_VALUES)))));
		mixin.invisibleAnnotations = new ArrayList<>(List.of(type));
		AnnotationNode at = new AnnotationNode("Lorg/spongepowered/asm/mixin/injection/At;");
		at.values = new ArrayList<>(List.of("value", point));
		if (member != null) at.values.addAll(List.of("target", member));
		AnnotationNode injector = new AnnotationNode(injectorDesc);
		injector.values = new ArrayList<>(List.of("method", new ArrayList<>(List.of(selector)), "at", List.of(at)));
		MethodNode method = new MethodNode(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC, handler, desc, null, null);
		method.visibleAnnotations = new ArrayList<>(List.of(injector));
		mixin.methods = new ArrayList<>(List.of(method));
		net.forbric.kernel.mixin.MixinStubRebindAccess.fabric(name);
		return mixin;
	}

	/**
	 * End to end on the real merged FuelValues: the table populateFuelValues built goes through the forwarding
	 * overload's return (where torrential's modifier sits), comes back as its result, and vanilla's is never rebuilt.
	 * It enters where the native Fabric and MinecraftForge servers do, at the two-argument overload, so a hook on that
	 * one runs on it too, after the inner one as it does natively.
	 */
	@Test void theBuiltTableReachesTheOverloadsReturnHookAndComesBack() throws Exception {
		try (Game game = new Game(true)) {
			Object table = game.table();
			Object result = game.through(table);
			assertSame(table, result, "no hook changed it, so the data map's table comes back");
			assertSame(table, game.seenByReturnHook(), "the overload's return hook ran on the data map's table");
			assertSame(table, game.hook.getField("seenOuter").get(null),
					"the two-argument overload's return hook, the one the native servers reach first, ran on it too");
			assertTrue((int) game.hook.getField("inner").get(null) < (int) game.hook.getField("outer").get(null),
					"the inner overload's hook runs before the outer one's, as it does natively");
			assertNull(game.takePending(), "nothing is left pending afterwards");
		}
	}

	/** Without the body's short-circuit the overload would rebuild vanilla's table; the data map's is kept instead. */
	@Test void withoutTheShortCircuitTheDataMapsTableIsKept() throws Exception {
		try (Game game = new Game(false)) {
			Object table = game.table();
			assertSame(table, game.through(table));
			assertNull(game.seenByReturnHook(), "the overload's return was never reached with that table");
		}
	}

	/**
	 * A mixin that cancels the overload at HEAD never reaches the body, so nothing took the table: the data map's
	 * table stands rather than the canceller's, which natively would replace only vanilla's.
	 */
	@Test void aHeadCancelDoesNotThrowAwayTheDataMapsTable() throws Exception {
		try (Game game = new Game(true, true)) {
			Object table = game.table(), replacement = game.table();
			game.hook.getField("replacement").set(null, replacement);
			assertSame(table, game.through(table));
			assertNull(game.takePending(), "and nothing is left pending");
		}
	}

	/** A client's own vanillaBurnTimes call has nothing pending and runs the body as shipped. */
	@Test void withNothingPendingTheBodyRunsAsShipped() throws Exception {
		try (Game game = new Game(true)) {
			Method body = game.fuel.getMethod("vanillaBurnTimes", game.builder, int.class);
			InvocationTargetException ran = assertThrows(InvocationTargetException.class, () -> body.invoke(null, null, 200));
			assertNotNull(ran.getCause(), "with no table pending it reached the body's own first instruction");
		}
	}

	/** The real merged FuelValues, transformed, beside the compiled game side and 26.2's libraries. */
	private static final class Game implements AutoCloseable {
		final URLClassLoader loader;
		final Class<?> fuel, builder, kernel, hook;

		Game(boolean shortCircuit) throws Exception {
			this(shortCircuit, false);
		}

		Game(boolean shortCircuit, boolean headCancel) throws Exception {
			Path compiled = Path.of(System.getProperty("user.dir"), "build", "classes", "java", "runtime").normalize();
			assumeTrue(Files.isDirectory(compiled) && Files.isRegularFile(MERGED), "the game side or the merged game is absent");
			byte[] original = NativeCoremodParityTest.read(MERGED, FUEL_VALUES);
			byte[] woven = shortCircuit ? FabricFuelValuesInjector.shortCircuitTheBody(original) : original;
			byte[] bytes = headCancel ? cancelledAtHead(woven) : withReturnHook(woven);
			byte[] hookBytes = returnHookClass();
			Path forge = RUN.resolve("merged-base/forge-runtime-interop.jar");
			assumeTrue(Files.isRegularFile(NEO_RT) && Files.isRegularFile(forge), "the carriers are absent");
			List<URL> urls = new ArrayList<>(List.of(compiled.toUri().toURL(), MERGED.toUri().toURL(),
					NEO_RT.toUri().toURL(), forge.toUri().toURL()));
			urls.addAll(libraries());
			loader = new URLClassLoader(urls.toArray(new URL[0]), FabricFuelValuesInjectorTest.class.getClassLoader()) {
				@Override
				protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
					synchronized (getClassLoadingLock(name)) {
						Class<?> known = findLoadedClass(name);
						if (known != null) return known;
						if (name.equals(FabricFuelValuesInjector.FUEL_VALUES_CLASS)) return defineClass(name, bytes, 0, bytes.length);
						if (name.equals(RETURN_HOOK.replace('/', '.'))) return defineClass(name, hookBytes, 0, hookBytes.length);
						return super.loadClass(name, resolve);
					}
				}
			};
			fuel = loader.loadClass(FabricFuelValuesInjector.FUEL_VALUES_CLASS);
			builder = loader.loadClass(FabricFuelValuesInjector.BUILDER.replace('/', '.'));
			kernel = loader.loadClass(FabricFuelValuesInjector.KERNEL_FUEL.replace('/', '.'));
			hook = loader.loadClass(RETURN_HOOK.replace('/', '.'));
		}

		Object table() throws Exception {
			Class<?> sorted = loader.loadClass("it.unimi.dsi.fastutil.objects.Object2IntSortedMap");
			Constructor<?> make = fuel.getDeclaredConstructor(sorted);
			make.setAccessible(true);
			return make.newInstance(loader.loadClass("it.unimi.dsi.fastutil.objects.Object2IntLinkedOpenHashMap")
					.getConstructor().newInstance());
		}

		Object through(Object table) throws Exception {
			Class<?> provider = loader.loadClass("net.minecraft.core.HolderLookup$Provider");
			Object registries = Proxy.newProxyInstance(loader, new Class<?>[] { provider }, (proxy, method, args) ->
					method.getName().equals("toString") ? "registries" : null);
			return kernel.getMethod("throughVanillaReturnHooks", fuel, provider,
					loader.loadClass("net.minecraft.world.flag.FeatureFlagSet")).invoke(null, table, registries, null);
		}

		Object takePending() throws Exception {
			return kernel.getMethod("takePending").invoke(null);
		}

		Object seenByReturnHook() throws Exception {
			return hook.getField("seen").get(null);
		}

		@Override
		public void close() throws Exception {
			loader.close();
		}
	}

	/**
	 * Calls the stand-in return hooks right before the forwarding overload's return and the two-argument overload's,
	 * as a RETURN modifier is woven.
	 */
	private static byte[] withReturnHook(byte[] fuelValues) {
		ClassNode node = node(fuelValues);
		for (String[] target : new String[][] { { STUB_DESC, "modify" }, { OUTER_DESC, "modifyOuter" } }) {
			MethodNode overload = method(node, target[0]);
			for (AbstractInsnNode insn : overload.instructions.toArray()) {
				if (insn.getOpcode() != Opcodes.ARETURN) continue;
				InsnList call = new InsnList();
				call.add(new MethodInsnNode(Opcodes.INVOKESTATIC, RETURN_HOOK, target[1], "(Ljava/lang/Object;)Ljava/lang/Object;", false));
				call.add(new TypeInsnNode(Opcodes.CHECKCAST, FUEL_VALUES));
				overload.instructions.insertBefore(insn, call);
			}
		}
		ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
		node.accept(writer);
		return writer.toByteArray();
	}

	/** The forwarding overload as a HEAD-cancelling mixin leaves it: it returns the canceller's table and nothing else. */
	private static byte[] cancelledAtHead(byte[] fuelValues) {
		ClassNode node = node(fuelValues);
		MethodNode stub = method(node, STUB_DESC);
		stub.instructions.clear();
		stub.tryCatchBlocks.clear();
		if (stub.localVariables != null) stub.localVariables.clear();
		stub.instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC, RETURN_HOOK, "cancel", "()Ljava/lang/Object;", false));
		stub.instructions.add(new TypeInsnNode(Opcodes.CHECKCAST, FUEL_VALUES));
		stub.instructions.add(new InsnNode(Opcodes.ARETURN));
		ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
		node.accept(writer);
		return writer.toByteArray();
	}

	private static byte[] returnHookClass() {
		ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
		cw.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, RETURN_HOOK, null, "java/lang/Object", null);
		cw.visitField(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "seen", "Ljava/lang/Object;", null, null).visitEnd();
		cw.visitField(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "replacement", "Ljava/lang/Object;", null, null).visitEnd();
		cw.visitField(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "seenOuter", "Ljava/lang/Object;", null, null).visitEnd();
		for (String counter : new String[] { "calls", "inner", "outer" }) {
			cw.visitField(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, counter, "I", null, null).visitEnd();
		}
		MethodVisitor cancel = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "cancel", "()Ljava/lang/Object;", null, null);
		cancel.visitCode();
		cancel.visitFieldInsn(Opcodes.GETSTATIC, RETURN_HOOK, "replacement", "Ljava/lang/Object;");
		cancel.visitInsn(Opcodes.ARETURN);
		cancel.visitMaxs(0, 0);
		cancel.visitEnd();
		// modify / modifyOuter: remember the table they saw and at which call they ran, and hand it back unchanged.
		for (String[] hook : new String[][] { { "modify", "seen", "inner" }, { "modifyOuter", "seenOuter", "outer" } }) {
			MethodVisitor modify = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, hook[0], "(Ljava/lang/Object;)Ljava/lang/Object;", null, null);
			modify.visitCode();
			modify.visitVarInsn(Opcodes.ALOAD, 0);
			modify.visitFieldInsn(Opcodes.PUTSTATIC, RETURN_HOOK, hook[1], "Ljava/lang/Object;");
			modify.visitFieldInsn(Opcodes.GETSTATIC, RETURN_HOOK, "calls", "I");
			modify.visitInsn(Opcodes.ICONST_1);
			modify.visitInsn(Opcodes.IADD);
			modify.visitInsn(Opcodes.DUP);
			modify.visitFieldInsn(Opcodes.PUTSTATIC, RETURN_HOOK, "calls", "I");
			modify.visitFieldInsn(Opcodes.PUTSTATIC, RETURN_HOOK, hook[2], "I");
			modify.visitVarInsn(Opcodes.ALOAD, 0);
			modify.visitInsn(Opcodes.ARETURN);
			modify.visitMaxs(0, 0);
			modify.visitEnd();
		}
		cw.visitEnd();
		return cw.toByteArray();
	}

	/** Minecraft 26.2's own library set, from the launcher's version JSON. */
	private static List<URL> libraries() throws Exception {
		String env = System.getenv("MC_DIR");
		Path libraries = Path.of(env != null ? env + "/libraries"
				: System.getProperty("user.home") + "/Library/Application Support/minecraft/libraries");
		Path versionJson = libraries.getParent().resolve("versions/26.2/26.2.json");
		assumeTrue(Files.isRegularFile(versionJson), "no 26.2 version JSON beside the local Minecraft libraries");
		List<URL> urls = new ArrayList<>();
		java.util.regex.Matcher path = java.util.regex.Pattern.compile("\"path\"\\s*:\\s*\"([^\"]+\\.jar)\"")
				.matcher(Files.readString(versionJson, StandardCharsets.UTF_8));
		while (path.find()) {
			Path library = libraries.resolve(path.group(1));
			if (Files.isRegularFile(library)) urls.add(library.toUri().toURL());
		}
		return urls;
	}

	private static MethodNode populate(byte[] bytes) {
		return node(bytes).methods.stream().filter(m -> m.name.equals("populateFuelValues")).findFirst().orElseThrow();
	}

	private static MethodNode method(ClassNode node, String desc) {
		return node.methods.stream().filter(m -> m.name.equals("vanillaBurnTimes") && m.desc.equals(desc)).findFirst().orElseThrow();
	}

	private static List<String> calls(MethodNode method) {
		List<String> calls = new ArrayList<>();
		for (AbstractInsnNode insn : method.instructions) if (insn instanceof MethodInsnNode call) calls.add(call.name);
		return calls;
	}

	private static List<Integer> opcodes(MethodNode method) {
		List<Integer> out = new ArrayList<>();
		for (AbstractInsnNode insn : method.instructions) if (insn.getOpcode() >= 0) out.add(insn.getOpcode());
		return out;
	}

	private static ClassNode node(byte[] bytes) {
		ClassNode node = new ClassNode();
		new ClassReader(bytes).accept(node, 0);
		return node;
	}
}
