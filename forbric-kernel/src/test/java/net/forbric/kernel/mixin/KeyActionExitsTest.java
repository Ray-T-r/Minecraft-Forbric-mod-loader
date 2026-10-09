/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.mixin;

import static org.junit.jupiter.api.Assertions.*;

import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipFile;

import javax.tools.ToolProvider;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.*;

import net.forbric.api.Ecosystem;
import net.forbric.kernel.TestFixtures;
import net.forbric.kernel.TestFixtures.Fixture;

/**
 * {@link MixinKeyActionAdapter} on {@link JoinedReturnExits}: which native {@code keyPress} return a guest's {@code RETURN}
 * ordinal names is read off the native body, never assumed. The fixtures are stand-ins compiled here with the platform
 * names the adapter is about and an unrelated guest ({@code com/example/keyecho}); one native body keeps vanilla 26.2's
 * six exits (the key release is ordinal 4, the final return ordinal 5), another moves the release to ordinal 2, so
 * one guest ordinal means a different exit in each. The last tests read the real vanilla jar and merged base.
 */
@ResourceLock("system-properties")
class KeyActionExitsTest {
	private static final String MIXIN = "com/example/keyecho/mixin/EchoKeys";
	private static final String INJECT = "Lorg/spongepowered/asm/mixin/injection/Inject;";
	private static final String AT = "Lorg/spongepowered/asm/mixin/injection/At;";

	private static final String EVENT = """
			package net.minecraft.client.input;
			public record KeyEvent(int key) { }
			""";
	private static final String HOOKS = """
			package net.neoforged.neoforge.client;
			import net.minecraft.client.input.KeyEvent;
			public final class ClientHooks { public static void onKeyInput(KeyEvent event, int action) { } }
			""";
	private static final String HEAD = """
			package net.minecraft.client;
			import net.minecraft.client.input.KeyEvent;
			import net.neoforged.neoforge.client.ClientHooks;
			public class KeyboardHandler {
				long window; Object screen; boolean consumed;
				static void binding(int key, boolean down) { }
				static boolean global(KeyEvent event) { return false; }
				boolean screenPressed(KeyEvent event) { return consumed; }
				boolean screenReleased(KeyEvent event) { return consumed; }
				void debug() { }
			""";
	/** Vanilla 26.2's exits in its order: window, global key, screen press, screen release, key release, final. */
	private static final String NATIVE_SIX = HEAD + """
				private void keyPress(long handle, int action, KeyEvent event) {
					if (handle != window) return;
					if (action == 1 && global(event)) return;
					if (screen != null) {
						if (action == 1 || action == 2) {
							if (screenPressed(event)) { binding(event.key(), false); return; }
						} else if (action == 0 && screenReleased(event)) {
							debug();
							return;
						}
					}
					debug();
					if (action == 0) {
						binding(event.key(), false);
						return;
					}
					binding(event.key(), true);
				}
			}
			""";
	/** The carrier's body: every exit after the screen joins one hook call before the final return. */
	private static final String JOINED_SIX = HEAD + """
				private void keyPress(long handle, int action, KeyEvent event) {
					if (handle != window) return;
					joined: {
						if (action == 1 && global(event)) break joined;
						if (screen != null) {
							if (action == 1 || action == 2) {
								if (screenPressed(event)) { binding(event.key(), false); return; }
							} else if (action == 0 && screenReleased(event)) {
								debug();
								return;
							}
						}
						debug();
						if (action == 0) {
							binding(event.key(), false);
							break joined;
						}
						binding(event.key(), true);
					}
					ClientHooks.onKeyInput(event, action);
				}
			}
			""";
	/** Another native order: the key release is ordinal 2 and the final return ordinal 3. */
	private static final String NATIVE_FOUR = HEAD + """
				private void keyPress(long handle, int action, KeyEvent event) {
					if (handle != window) return;
					if (screen != null && action != 0 && screenPressed(event)) { binding(event.key(), false); return; }
					debug();
					if (action == 0) {
						binding(event.key(), false);
						return;
					}
					binding(event.key(), true);
				}
			}
			""";
	private static final String JOINED_FOUR = HEAD + """
				private void keyPress(long handle, int action, KeyEvent event) {
					if (handle != window) return;
					joined: {
						if (screen != null && action != 0 && screenPressed(event)) { binding(event.key(), false); return; }
						debug();
						if (action == 0) {
							binding(event.key(), false);
							break joined;
						}
						binding(event.key(), true);
					}
					ClientHooks.onKeyInput(event, action);
				}
			}
			""";

	@TempDir Path work;

	@AfterEach void reset() {
		System.clearProperty(MixinKeyActionAdapter.PROPERTY);
		MixinStubRebind.forget();
	}

	/** Ordinal 4 is the release the carrier joined; ordinal 5 is the final return; TAIL is the final return too. */
	@Test void eachOrdinalIsTheExitTheNativeBodyHasThere() throws Exception {
		ClassNode original = handler("native", NATIVE_SIX), current = handler("joined", JOINED_SIX);
		ClassNode mixin = mixin(MIXIN, "net/minecraft/client/KeyboardHandler", List.of(
				new Hook("heardRelease", "keyPress", "RETURN", 4, false, MixinKeyActionAdapter.HANDLER),
				new Hook("heardFinal", "keyPress(JILnet/minecraft/client/input/KeyEvent;)V", "RETURN", 5, false, MixinKeyActionAdapter.HANDLER),
				new Hook("heardTail", "keyPress", "TAIL", null, false, MixinKeyActionAdapter.BARE),
				new Hook("heardGlobal", "keyPress", "RETURN", 1, false, MixinKeyActionAdapter.HANDLER),
				new Hook("heardScreenRelease", "keyPress", "RETURN", 3, false, MixinKeyActionAdapter.HANDLER),
				new Hook("heardWindow", "keyPress", "RETURN", 0, false, MixinKeyActionAdapter.HANDLER),
				new Hook("cancelsRelease", "keyPress", "RETURN", 4, true, MixinKeyActionAdapter.HANDLER)));
		Map<String, byte[]> untouched = new HashMap<>();
		for (String name : List.of("heardGlobal", "heardScreenRelease", "heardWindow", "cancelsRelease")) untouched.put(name, bytes(method(mixin, name)));

		assertEquals(3, MixinKeyActionAdapter.adapt(mixin, n -> current, (family, n) -> original));
		assertPoint(mixin, "heardRelease", "INVOKE", MixinKeyActionAdapter.LIVE, 0);
		assertPoint(mixin, "heardFinal", "TAIL", null, null);
		assertPoint(mixin, "heardTail", "TAIL", null, null);
		assertEquals(MixinKeyActionAdapter.HANDLER, method(mixin, "heardTail").desc, "a bare TAIL handler is wrapped with the full arguments");
		for (var entry : untouched.entrySet()) assertArrayEquals(entry.getValue(), bytes(method(mixin, entry.getKey())), entry.getKey() + " is untouched");
		CarpetMixinAdapterTest.verify(mixin);

		Map<String, List<Integer>> heard = heard(mixin, "heardRelease", "heardFinal", "heardTail", "heardGlobal");
		assertEquals(List.of(0), heard.get("heardRelease"), "the release exit runs on a release only");
		assertEquals(List.of(1, 2, 7), heard.get("heardFinal"), "the final return runs on press, repeat and anything else");
		assertEquals(List.of(1, 2, 7), heard.get("heardTail"));
		assertEquals(List.of(0, 1, 2, 7), heard.get("heardGlobal"), "an unproved exit keeps its body unguarded");

		assertEquals(0, MixinKeyActionAdapter.adapt(mixin, n -> current, (family, n) -> original), "idempotent");
		ClassNode reread = MixinFit.parse(bytes(mixin));
		assertEquals(0, MixinKeyActionAdapter.adapt(reread, n -> current, (family, n) -> original), "idempotent after a round trip");
	}

	/** In a native body whose release is ordinal 2, ordinal 2 is joined, 3 is final and 4 names nothing. */
	@Test void theSameOrdinalMeansWhatAnotherNativeBodySays() throws Exception {
		ClassNode original = handler("native-four", NATIVE_FOUR), current = handler("joined-four", JOINED_FOUR);
		JoinedReturnExits exits = exits(original, current);
		assertEquals(4, exits.nativeReturns());
		assertEquals("0", exits.joined(2).toString());
		assertNull(exits.joined(0));
		assertNull(exits.joined(1));
		assertTrue(exits.isFinal(3));
		// This body compares the action with 0 only: the final return keeps every other value.
		assertEquals("any other", exits.finalGuard().toString());

		ClassNode mixin = mixin(MIXIN, "net/minecraft/client/KeyboardHandler", List.of(
				new Hook("heardRelease", "keyPress", "RETURN", 2, false, MixinKeyActionAdapter.HANDLER),
				new Hook("heardFinal", "keyPress", "RETURN", 3, false, MixinKeyActionAdapter.HANDLER),
				new Hook("heardNothing", "keyPress", "RETURN", 4, false, MixinKeyActionAdapter.HANDLER)));
		byte[] nothing = bytes(method(mixin, "heardNothing"));
		assertEquals(2, MixinKeyActionAdapter.adapt(mixin, n -> current, (family, n) -> original));
		assertPoint(mixin, "heardRelease", "INVOKE", MixinKeyActionAdapter.LIVE, 0);
		assertPoint(mixin, "heardFinal", "TAIL", null, null);
		assertArrayEquals(nothing, bytes(method(mixin, "heardNothing")), "an ordinal past the native returns is not guessed");
		assertEquals(List.of(0), heard(mixin, "heardRelease").get("heardRelease"));
		assertEquals(List.of(1, 2, 7), heard(mixin, "heardFinal").get("heardFinal"));
	}

	/** Look-alikes the analysis must leave alone. */
	@Test void whatItLeavesAlone() throws Exception {
		ClassNode original = handler("native", NATIVE_SIX), current = handler("joined", JOINED_SIX);
		// A guest compiled against the joined body already counts its returns.
		assertEquals(0, MixinKeyActionAdapter.adapt(guest(4, 5), n -> current, (family, n) -> current));
		// No native bytes, no proof.
		assertEquals(0, MixinKeyActionAdapter.adapt(guest(4, 5), n -> current, (family, n) -> null));
		// The same shape on another class is not keyPress.
		ClassNode elsewhere = mixin("com/example/keyecho/mixin/EchoMouse", "net/minecraft/client/MouseHandler", List.of(
				new Hook("heardRelease", "keyPress", "RETURN", 4, false, MixinKeyActionAdapter.HANDLER)));
		assertEquals(0, MixinKeyActionAdapter.adapt(elsewhere, n -> current, (family, n) -> original));
		// A carrier whose release run differs from the native one: ordinal 4 is not proved joined; TAIL still is final.
		String differentRun = JOINED_SIX.replaceFirst("binding\\(event\\.key\\(\\), false\\);(\\s+)break joined;",
				"binding(event.key() + 1, false);$1break joined;");
		assertNotEquals(JOINED_SIX, differentRun);
		ClassNode changed = handler("joined-changed", differentRun);
		assertNull(exits(original, changed).joined(4));
		ClassNode guest = guest(4, 5);
		byte[] release = bytes(method(guest, "heardRelease"));
		assertEquals(2, MixinKeyActionAdapter.adapt(guest, n -> changed, (family, n) -> original), "the final-return hooks only");
		assertArrayEquals(release, bytes(method(guest, "heardRelease")));
		// A carrier body without the joined hook is not this shape at all.
		assertEquals(0, MixinKeyActionAdapter.adapt(guest(4, 5), n -> original, (family, n) -> original));
		// The switch.
		System.setProperty(MixinKeyActionAdapter.PROPERTY, "off");
		assertEquals(0, MixinKeyActionAdapter.adapt(guest(4, 5), n -> current, (family, n) -> original));
	}

	/** Vanilla 26.2 against the staged merged base: the claim about ordinals 4 and 5, read off the real jars. */
	@Test void vanillaReleaseIsOrdinalFourAndItsFinalReturnOrdinalFive() throws Exception {
		Path merged = TestFixtures.stagedRoot().resolve("merged-base/patched-mc-merged-26.2.jar");
		TestFixtures.require(Fixture.STAGED, Files.isRegularFile(merged), "actual game required");
		ClassNode current = CarpetMixinAdapterTest.from(Fixture.STAGED, merged, MixinKeyActionAdapter.TARGET);
		ClassNode original, forge, neoforge;
		try (ZipFile jar = new ZipFile(merged.toFile())) {
			// The hash-pinned native references production reads: for a Fabric mod, vanilla's own class.
			NativeGameReferences references = new NativeGameReferences(path -> {
				try {
					var entry = jar.getEntry(path);
					return entry == null ? null : jar.getInputStream(entry).readAllBytes();
				} catch (java.io.IOException unavailable) {
					return null;
				}
			});
			original = references.get(Ecosystem.FABRIC, MixinKeyActionAdapter.TARGET);
			forge = references.get(Ecosystem.FORGE, MixinKeyActionAdapter.TARGET);
			neoforge = references.get(Ecosystem.NEOFORGE, MixinKeyActionAdapter.TARGET);
		}
		assertNotNull(original, "native reference for KeyboardHandler");
		JoinedReturnExits exits = exits(original, current);
		assertEquals(6, exits.nativeReturns());
		assertEquals("0", exits.joined(4).toString(), "vanilla's key-release exit is its fifth return");
		for (int ordinal = 0; ordinal < 4; ordinal++) assertNull(exits.joined(ordinal), "ordinal " + ordinal);
		assertTrue(exits.isFinal(5));
		assertEquals("1, 2, any other", exits.finalGuard().toString(), "the final return is never reached on a release");

		ClassNode guest = guest(4, 5);
		MixinStubRebind.noteEcosystem(guest.name, Ecosystem.FABRIC);
		assertEquals(3, MixinKeyActionAdapter.adapt(guest, n -> current, (family, n) -> family == Ecosystem.FABRIC ? original : null));
		assertPoint(guest, "heardRelease", "INVOKE", MixinKeyActionAdapter.LIVE, 0);
		assertPoint(guest, "heardFinal", "TAIL", null, null);
		assertPoint(guest, "heardTail", "TAIL", null, null);
		CarpetMixinAdapterTest.verify(guest);

		// MinecraftForge's own keyPress already joins its exits (into its own hook) and keeps three returns, like the
		// merged one: nothing a Forge mod counted there moves. A NeoForge mod was compiled against the merged shape itself.
		assertNotNull(forge, "Forge native reference");
		JoinedReturnExits forgeExits = exits(forge, current);
		assertEquals(3, forgeExits.nativeReturns());
		assertNull(forgeExits.joined(0));
		assertNull(forgeExits.joined(1));
		assertNull(forgeExits.finalGuard(), "Forge's final return is reached by every action, as the merged one is");
		ClassNode forgeGuest = guest(0, 2);
		byte[] before = bytes(forgeGuest);
		assertEquals(0, MixinKeyActionAdapter.adapt(forgeGuest, n -> current, (family, n) -> forge));
		assertArrayEquals(before, bytes(forgeGuest));
		assertNotNull(neoforge, "NeoForge native reference");
		assertNull(JoinedReturnExits.of(neoforge, MixinPlayerWorldCallbackAdapter.selector(neoforge, MixinKeyActionAdapter.HOST),
				current, MixinPlayerWorldCallbackAdapter.selector(current, MixinKeyActionAdapter.HOST), MixinKeyActionAdapter.LIVE));
	}

	// --- fixtures ---

	record Hook(String name, String method, String point, Integer ordinal, boolean cancellable, String desc) { }

	/** A guest with a release hook at {@code release}, a final-return hook at {@code last} and a TAIL hook. */
	private static ClassNode guest(int release, int last) {
		return mixin(MIXIN, "net/minecraft/client/KeyboardHandler", List.of(
				new Hook("heardRelease", "keyPress", "RETURN", release, false, MixinKeyActionAdapter.HANDLER),
				new Hook("heardFinal", "keyPress", "RETURN", last, false, MixinKeyActionAdapter.HANDLER),
				new Hook("heardTail", "keyPress", "TAIL", null, false, MixinKeyActionAdapter.HANDLER)));
	}

	/** Each handler records the action it was called with into the static list {@code HEARD} under its own name. */
	private static ClassNode mixin(String name, String target, List<Hook> hooks) {
		ClassNode node = new ClassNode();
		node.version = Opcodes.V21;
		node.access = Opcodes.ACC_PUBLIC;
		node.name = name;
		node.superName = "java/lang/Object";
		AnnotationNode annotation = new AnnotationNode("Lorg/spongepowered/asm/mixin/Mixin;");
		annotation.values = new ArrayList<>(List.of("value", new ArrayList<>(List.of(Type.getObjectType(target)))));
		node.invisibleAnnotations = new ArrayList<>(List.of(annotation));
		node.fields.add(new FieldNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "HEARD", "Ljava/util/Map;", null, null));
		MethodNode clinit = new MethodNode(Opcodes.ACC_STATIC, "<clinit>", "()V", null, null);
		clinit.instructions.add(new TypeInsnNode(Opcodes.NEW, "java/util/HashMap"));
		clinit.instructions.add(new InsnNode(Opcodes.DUP));
		clinit.instructions.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, "java/util/HashMap", "<init>", "()V", false));
		clinit.instructions.add(new FieldInsnNode(Opcodes.PUTSTATIC, name, "HEARD", "Ljava/util/Map;"));
		clinit.instructions.add(new InsnNode(Opcodes.RETURN));
		clinit.maxStack = 2;
		node.methods.add(clinit);
		MethodNode init = new MethodNode(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
		init.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
		init.instructions.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false));
		init.instructions.add(new InsnNode(Opcodes.RETURN));
		init.maxStack = 1;
		init.maxLocals = 1;
		node.methods.add(init);
		for (Hook hook : hooks) {
			AnnotationNode at = new AnnotationNode(AT);
			at.values = new ArrayList<>(List.of("value", hook.point()));
			if (hook.ordinal() != null) at.values.addAll(List.of("ordinal", hook.ordinal()));
			AnnotationNode inject = new AnnotationNode(INJECT);
			inject.values = new ArrayList<>(List.of("method", new ArrayList<>(List.of(hook.method())), "at", new ArrayList<>(List.of(at))));
			if (hook.cancellable()) inject.values.addAll(List.of("cancellable", true));
			MethodNode handler = new MethodNode(Opcodes.ACC_PRIVATE, hook.name(), hook.desc(), null, null);
			handler.visibleAnnotations = new ArrayList<>(List.of(inject));
			InsnList code = handler.instructions;
			code.add(new FieldInsnNode(Opcodes.GETSTATIC, name, "HEARD", "Ljava/util/Map;"));
			code.add(new LdcInsnNode(hook.name()));
			code.add(new InsnNode(Opcodes.ACONST_NULL));
			code.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE, "java/util/Map", "put", "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;", true));
			code.add(new InsnNode(Opcodes.POP));
			code.add(new InsnNode(Opcodes.RETURN));
			handler.maxStack = 3;
			handler.maxLocals = Type.getArgumentsAndReturnSizes(hook.desc()) >> 2;
			node.methods.add(handler);
		}
		return node;
	}

	/** For each named (outer) handler, the actions among 0, 1, 2 and 7 for which its original body ran. */
	private Map<String, List<Integer>> heard(ClassNode mixin, String... names) throws Exception {
		// Read with its frames, so the JVM verifies the wrappers' own stack map frames.
		ClassNode runnable = new ClassNode();
		new org.objectweb.asm.ClassReader(bytes(mixin)).accept(runnable, 0);
		for (MethodNode method : runnable.methods) if (!method.name.startsWith("<")) method.access = method.access & ~Opcodes.ACC_PRIVATE | Opcodes.ACC_PUBLIC;
		ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
		runnable.accept(writer);
		byte[] code = writer.toByteArray();
		Path events = compile("events", Map.of("net/minecraft/client/input/KeyEvent.java", EVENT));
		try (var loader = new java.net.URLClassLoader(new java.net.URL[] {events.toUri().toURL()}, getClass().getClassLoader()) {
			{ defineClass(mixin.name.replace('/', '.'), code, 0, code.length); }
		}) {
			Class<?> type = loader.loadClass(mixin.name.replace('/', '.'));
			Class<?> event = loader.loadClass("net.minecraft.client.input.KeyEvent");
			Class<?> callback = loader.loadClass("org.spongepowered.asm.mixin.injection.callback.CallbackInfo");
			Object receiver = type.getConstructor().newInstance(), key = event.getConstructor(int.class).newInstance(71);
			@SuppressWarnings("unchecked") Map<String, Object> log = (Map<String, Object>) type.getField("HEARD").get(null);
			Map<String, List<Integer>> heard = new HashMap<>();
			for (String name : names) {
				List<Integer> actions = new ArrayList<>();
				for (int action : new int[] {0, 1, 2, 7}) {
					log.clear();
					type.getMethod(name, long.class, int.class, event, callback).invoke(receiver, 0L, action, key, null);
					if (!log.isEmpty()) actions.add(action);
				}
				heard.put(name, actions);
			}
			return heard;
		}
	}

	private ClassNode handler(String label, String source) throws Exception {
		Path classes = compile(label, Map.of("net/minecraft/client/input/KeyEvent.java", EVENT,
				"net/neoforged/neoforge/client/ClientHooks.java", HOOKS, "net/minecraft/client/KeyboardHandler.java", source));
		return MixinFit.parse(Files.readAllBytes(classes.resolve("net/minecraft/client/KeyboardHandler.class")));
	}

	private Path compile(String label, Map<String, String> sources) throws Exception {
		Path root = Files.createDirectories(work.resolve(label));
		List<String> args = new ArrayList<>(List.of("-proc:none", "--release", "21", "-d", root.toString()));
		for (var source : sources.entrySet()) {
			Path file = work.resolve(label + "-src").resolve(source.getKey());
			Files.createDirectories(file.getParent());
			Files.writeString(file, source.getValue());
			args.add(file.toString());
		}
		StringWriter errors = new StringWriter();
		int code = ToolProvider.getSystemJavaCompiler().run(null, null, new java.io.OutputStream() {
			@Override public void write(int b) { errors.write(b); }
		}, args.toArray(String[]::new));
		assertEquals(0, code, errors.toString());
		return root;
	}

	private static JoinedReturnExits exits(ClassNode original, ClassNode current) {
		JoinedReturnExits exits = JoinedReturnExits.of(original, MixinPlayerWorldCallbackAdapter.selector(original, MixinKeyActionAdapter.HOST),
				current, MixinPlayerWorldCallbackAdapter.selector(current, MixinKeyActionAdapter.HOST), MixinKeyActionAdapter.LIVE);
		assertNotNull(exits, "the joined shape");
		return exits;
	}

	private static void assertPoint(ClassNode mixin, String handler, String value, String target, Integer ordinal) {
		AnnotationNode at = MixinFit.atNodes(MixinFit.injectorOf(method(mixin, handler))).getFirst();
		assertEquals(value, MixinFit.value(at, "value"), handler);
		assertEquals(target, MixinFit.value(at, "target"), handler);
		assertEquals(ordinal, MixinFit.value(at, "ordinal"), handler);
	}

	private static MethodNode method(ClassNode node, String name) {
		return node.methods.stream().filter(m -> m.name.equals(name)).findFirst().orElseThrow(() -> new AssertionError(name));
	}

	private static byte[] bytes(MethodNode method) {
		ClassNode holder = new ClassNode();
		holder.version = Opcodes.V21;
		holder.name = "holder";
		holder.superName = "java/lang/Object";
		holder.methods.add(method);
		return bytes(holder);
	}

	private static byte[] bytes(ClassNode node) {
		ClassWriter writer = new ClassWriter(0);
		node.accept(writer);
		return writer.toByteArray();
	}
}
