package net.forbric.kernel.mixin;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.commons.ClassRemapper;
import org.objectweb.asm.commons.Remapper;
import org.objectweb.asm.tree.*;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.BasicVerifier;

/**
 * fabric-networking's real common-listener payload mixin against the real genuine and vanilla play listeners: on NeoForge's
 * base the play listener hands what fabric-api's play receiver declined to super, where fabric-api's configuration-only
 * handler threw "Unknown addon" and disconnected the player (97-mod pack, world join).
 */
@ResourceLock(Resources.SYSTEM_PROPERTIES)
class FabricCommonPayloadMixinAdapterTest {
	private static final String MIXIN = "net/fabricmc/fabric/mixin/networking/ServerCommonPacketListenerImplMixin";
	private static final String SWITCH = "forbric.playPayloadFallThrough";

	private static ClassNode mixin() throws Exception {
		return StagedFabricMixinFixture.mixin("fabric-networking-api-v1", MIXIN);
	}

	private static ClassNode game(boolean vanilla) throws Exception {
		return StagedFabricMixinFixture.game(FabricCommonPayloadMixinAdapter.GAME_LISTENER, vanilla);
	}

	private static MethodNode refusing(ClassNode mixin) {
		List<MethodNode> found = mixin.methods.stream().filter(FabricCommonPayloadMixinAdapter::refusesOtherListeners).toList();
		assertEquals(1, found.size(), "fabric-api's one configuration-only payload handler");
		return found.getFirst();
	}

	private static List<String> head(MethodNode method, int count) {
		return java.util.stream.StreamSupport.stream(method.instructions.spliterator(), false).filter(i -> i.getOpcode() >= 0).limit(count)
				.map(i -> switch (i) {
					case VarInsnNode v -> "ALOAD " + v.var;
					case TypeInsnNode t -> (t.getOpcode() == Opcodes.INSTANCEOF ? "INSTANCEOF " : "NEW ") + t.desc;
					case JumpInsnNode j -> j.getOpcode() == Opcodes.IFEQ ? "IFEQ" : String.valueOf(j.getOpcode());
					case MethodInsnNode m -> m.owner + "." + m.name;
					default -> i.getOpcode() == Opcodes.RETURN ? "RETURN" : String.valueOf(i.getOpcode());
				}).toList();
	}

	@Test void onNeoForgesBaseThePlayListenerReturnsBeforeTheRefusal() throws Exception {
		System.clearProperty(SWITCH);
		ClassNode game = game(false);
		assertTrue(FabricCommonPayloadMixinAdapter.playListenerCallsSuper(game),
				"premise: NeoForge's play listener calls super.handleCustomPayload (javap: aload_0, aload_1, invokespecial)");
		ClassNode mixin = mixin();
		MethodNode handler = refusing(mixin);
		List<String> before = head(handler, 3);
		byte[] gameBefore = StagedFabricMixinFixture.bytes(game);

		assertEquals(1, FabricCommonPayloadMixinAdapter.adapt(mixin, n -> n.equals(FabricCommonPayloadMixinAdapter.GAME_LISTENER) ? game : null));

		assertEquals(List.of("ALOAD 0", "INSTANCEOF " + FabricCommonPayloadMixinAdapter.GAME_LISTENER, "IFEQ", "RETURN"), head(handler, 4),
				"a play listener's payload goes on to NeoForge's body; nothing else of fabric-api's handler runs for it");
		assertEquals(before, head(handler, 7).subList(4, 7), "the configuration listener runs fabric-api's handler as written");
		new Analyzer<>(new BasicVerifier()).analyze(mixin.name, handler);
		assertEquals(List.of("handleCustomPayload"), MixinFit.value(MixinFit.injectorOf(handler), "method"), "the injection itself is unchanged");
		assertArrayEquals(gameBefore, StagedFabricMixinFixture.bytes(game), "the play listener is not touched");
		assertEquals(0, mixin.methods.stream().filter(m -> m != handler).filter(FabricCommonPayloadMixinAdapter::refusesOtherListeners).count(),
				"its other injector (handlePong) is not a payload handler");
	}

	/** Vanilla's play override is empty: no play payload ever reaches fabric-api's common handler, so there is nothing to do. */
	@Test void onVanillaNothingChanges() throws Exception {
		System.clearProperty(SWITCH);
		ClassNode game = game(true);
		assertFalse(FabricCommonPayloadMixinAdapter.playListenerCallsSuper(game), "premise: vanilla's override is a bare return");
		ClassNode mixin = mixin();
		byte[] before = StagedFabricMixinFixture.bytes(mixin);
		assertEquals(0, FabricCommonPayloadMixinAdapter.adapt(mixin, n -> game));
		assertArrayEquals(before, StagedFabricMixinFixture.bytes(mixin));
	}

	@Test void theOffSwitchLeavesTheHandlerAsWritten() throws Exception {
		ClassNode game = game(false);
		ClassNode mixin = mixin();
		byte[] before = StagedFabricMixinFixture.bytes(mixin);
		System.setProperty(SWITCH, "off");
		try {
			assertEquals(0, FabricCommonPayloadMixinAdapter.adapt(mixin, n -> game));
		} finally {
			System.clearProperty(SWITCH);
		}
		assertArrayEquals(before, StagedFabricMixinFixture.bytes(mixin));
	}

	/** Read by what the handler does: the same mixin under unrelated names is adapted alike; a handler that refuses nothing is not. */
	@Test void theRuleReadsWhatTheHandlerDoes() throws Exception {
		System.clearProperty(SWITCH);
		ClassNode original = mixin();
		ClassNode renamed = new ClassNode();
		original.accept(new org.objectweb.asm.commons.ClassRemapper(renamed, new org.objectweb.asm.commons.Remapper() {
			@Override public String map(String name) {
				return name.equals(MIXIN) ? "org/qzx/net/QzxListenerHooks" : name;
			}

			@Override public String mapMethodName(String owner, String name, String desc) {
				return owner.equals(MIXIN) && !name.startsWith("<") ? "qzx" + name : name;
			}
		}));
		ClassNode game = game(false);
		assertEquals(1, FabricCommonPayloadMixinAdapter.adapt(renamed, n -> game));

		ClassNode quiet = mixin();
		MethodNode handler = refusing(quiet);
		for (AbstractInsnNode insn : handler.instructions.toArray()) {
			if (insn instanceof TypeInsnNode t && t.getOpcode() == Opcodes.NEW && t.desc.equals("java/lang/IllegalStateException")) t.desc = "java/lang/Object";
		}
		assertFalse(FabricCommonPayloadMixinAdapter.refusesOtherListeners(handler), "a handler that throws no IllegalStateException refuses no listener");
		assertEquals(0, FabricCommonPayloadMixinAdapter.adapt(quiet, n -> game));
	}

	/** The same handler serving a second kind of addon, or a play addon, is not the configuration-only one: left as written. */
	@Test void aHandlerThatServesAPlayAddonOrMoreThanOneKindIsLeftAlone() throws Exception {
		System.clearProperty(SWITCH);
		ClassNode game = game(false);
		ClassNode play = mixin();
		for (AbstractInsnNode insn : refusing(play).instructions) {
			if (insn instanceof TypeInsnNode t && t.desc.endsWith("/ServerConfigurationNetworkAddon")) {
				t.desc = t.desc.replace("ServerConfigurationNetworkAddon", "ServerPlayNetworkAddon");
			}
		}
		assertEquals(0, play.methods.stream().filter(FabricCommonPayloadMixinAdapter::refusesOtherListeners).count(),
				"a handler that serves the play addon would lose its payloads behind the guard");
		assertEquals(0, FabricCommonPayloadMixinAdapter.adapt(play, n -> game));

		ClassNode two = mixin();
		MethodNode handler = refusing(two);
		InsnList second = new InsnList();
		second.add(new VarInsnNode(Opcodes.ALOAD, 0));
		second.add(new TypeInsnNode(Opcodes.INSTANCEOF, "net/fabricmc/fabric/impl/networking/server/ServerPlayNetworkAddon"));
		second.add(new InsnNode(Opcodes.POP));
		handler.instructions.insert(second);
		assertFalse(FabricCommonPayloadMixinAdapter.refusesOtherListeners(handler), "a handler that tests two kinds of addon serves more than one");
		assertEquals(0, FabricCommonPayloadMixinAdapter.adapt(two, n -> game));
	}

	/**
	 * The handler run, on stand-ins for every class it names: as written, a play listener's payload is refused with "Unknown
	 * addon" (the disconnect measured on the 97-mod pack); adapted, the play listener returns before fabric-api's body runs,
	 * and the configuration listener still runs it as written, its refusal of an addon that is not a configuration one
	 * included.
	 */
	@Test void runTheHandlerAPlayListenerGoesOnAndTheConfigurationListenerIsServedAsWritten() throws Throwable {
		System.clearProperty(SWITCH);
		ClassNode game = game(false);
		ClassNode adapted = mixin();
		assertEquals(1, FabricCommonPayloadMixinAdapter.adapt(adapted, n -> game));

		Throwable asWritten = run(mixin(), true);
		assertTrue(asWritten instanceof IllegalStateException && "Unknown addon".equals(asWritten.getMessage()),
				"premise: as written, a play listener's payload ends in fabric-api's refusal: " + asWritten);
		assertNull(run(adapted, true), "adapted, a play listener's payload goes on to the rest of the method (NeoForge's body)");
		Throwable configuration = run(adapted, false);
		assertTrue(configuration instanceof IllegalStateException && "Unknown addon".equals(configuration.getMessage()),
				"the configuration listener runs fabric-api's handler as written: " + configuration);
	}

	/**
	 * Calls the mixin's payload handler on a listener that is (or is not) the play listener, with a payload packet and no
	 * callback info. Every class the mixin names but the JDK's is replaced by a stand-in whose methods answer null, zero or
	 * nothing, so the listener's network addon is null: neither the configuration addon nor any other. Returns what it
	 * threw, or null when it returned.
	 */
	private static Throwable run(ClassNode mixin, boolean play) throws Throwable {
		String handlerName = payloadHandler(mixin).name;
		ClassNode stubbed = new ClassNode();
		mixin.accept(new ClassRemapper(stubbed, new Remapper() {
			@Override public String map(String name) {
				return name.startsWith("java/") ? name : "stub/" + name;
			}
		}));
		Map<String, Map<String, Integer>> classes = new HashMap<>();   // owner -> name+desc -> invoke opcode
		Set<String> throwables = new HashSet<>();
		for (MethodNode m : stubbed.methods) {
			for (AbstractInsnNode insn : m.instructions) {
				if (insn instanceof MethodInsnNode c && c.owner.startsWith("stub/")) {
					classes.computeIfAbsent(c.owner, o -> new HashMap<>()).put(c.name + c.desc, c.getOpcode());
				} else if (insn instanceof FieldInsnNode f && f.owner.startsWith("stub/")) {
					classes.computeIfAbsent(f.owner, o -> new HashMap<>());
				} else if (insn instanceof TypeInsnNode t && t.getOpcode() == Opcodes.NEW && t.desc.startsWith("stub/")) {
					classes.computeIfAbsent(t.desc, o -> new HashMap<>());
				}
			}
			for (TryCatchBlockNode t : m.tryCatchBlocks) if (t.type != null) throwables.add(t.type);
		}
		// What the mixin calls on itself without declaring it (getAddon(), which the target has) is abstract here.
		for (Map.Entry<String, Integer> own : classes.getOrDefault(stubbed.name, Map.of()).entrySet()) {
			String key = own.getKey();
			if (stubbed.methods.stream().noneMatch(m -> key.equals(m.name + m.desc))) {
				int paren = key.indexOf('(');
				stubbed.methods.add(new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT, key.substring(0, paren), key.substring(paren), null, null));
			}
		}
		String gameListener = "stub/" + FabricCommonPayloadMixinAdapter.GAME_LISTENER;
		ClassNode listener = new ClassNode();
		listener.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, "stub/Listener", null, stubbed.name, play ? new String[] {gameListener} : null);
		listener.methods.add(constructor(stubbed.name));
		for (MethodNode m : stubbed.methods) {
			if ((m.access & Opcodes.ACC_ABSTRACT) != 0) listener.methods.add(answersNothing(Opcodes.ACC_PUBLIC, m.name, m.desc));
		}
		Map<String, byte[]> defined = new HashMap<>();
		defined.put(stubbed.name, write(stubbed, ClassWriter.COMPUTE_FRAMES));   // the fixture reads no frames
		defined.put(listener.name, write(listener, ClassWriter.COMPUTE_MAXS));
		ClassLoader loader = new ClassLoader(FabricCommonPayloadMixinAdapterTest.class.getClassLoader()) {
			@Override protected Class<?> findClass(String name) throws ClassNotFoundException {
				String internal = name.replace('.', '/');
				byte[] bytes = defined.get(internal);
				if (bytes == null && internal.startsWith("stub/")) bytes = standIn(internal, classes.get(internal), throwables.contains(internal));
				if (bytes == null) throw new ClassNotFoundException(name);
				return defineClass(name, bytes, 0, bytes.length);
			}
		};
		MethodNode handler = stubbed.methods.stream().filter(m -> m.name.equals(handlerName)).findFirst().orElseThrow();
		Type[] args = Type.getArgumentTypes(handler.desc);
		Class<?> packet = loader.loadClass(args[0].getClassName());
		Method method = loader.loadClass(stubbed.name.replace('/', '.')).getDeclaredMethod(handler.name, packet, loader.loadClass(args[1].getClassName()));
		method.setAccessible(true);
		Object receiver = loader.loadClass("stub.Listener").getConstructor().newInstance();
		assertEquals(play, loader.loadClass(gameListener.replace('/', '.')).isInstance(receiver), "premise: the listener is what it says");
		try {
			method.invoke(receiver, packet.getConstructor().newInstance(), null);
			return null;
		} catch (InvocationTargetException e) {
			return e.getCause();
		}
	}

	/** The handler whose injection names handleCustomPayload, adapted or not (the rule itself reads only the unadapted one). */
	private static MethodNode payloadHandler(ClassNode mixin) {
		return mixin.methods.stream().filter(m -> MixinFit.injectorOf(m) != null)
				.filter(m -> List.of("handleCustomPayload").equals(MixinFit.value(MixinFit.injectorOf(m), "method"))).findFirst().orElseThrow();
	}

	/** A stand-in: a throwable for a caught type, a class with the members called on it, or else an empty interface. */
	private static byte[] standIn(String internal, Map<String, Integer> members, boolean throwable) {
		ClassNode c = new ClassNode();
		if (throwable) {
			c.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, internal, null, "java/lang/RuntimeException", null);
			c.methods.add(constructor("java/lang/RuntimeException"));
		} else if (members != null) {
			c.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, internal, null, "java/lang/Object", null);
			c.methods.add(constructor("java/lang/Object"));
			members.forEach((key, opcode) -> {
				if (key.startsWith("<init>")) return;
				int paren = key.indexOf('(');
				c.methods.add(answersNothing(Opcodes.ACC_PUBLIC | (opcode == Opcodes.INVOKESTATIC ? Opcodes.ACC_STATIC : 0),
						key.substring(0, paren), key.substring(paren)));
			});
		} else {
			c.visit(Opcodes.V21, Opcodes.ACC_PUBLIC | Opcodes.ACC_INTERFACE | Opcodes.ACC_ABSTRACT, internal, null, "java/lang/Object", null);
		}
		return write(c, ClassWriter.COMPUTE_MAXS);
	}

	private static MethodNode constructor(String superName) {
		MethodNode init = new MethodNode(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
		init.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
		init.instructions.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, superName, "<init>", "()V", false));
		init.instructions.add(new InsnNode(Opcodes.RETURN));
		return init;
	}

	/** A method that answers null, zero or nothing. */
	private static MethodNode answersNothing(int access, String name, String desc) {
		MethodNode m = new MethodNode(access, name, desc, null, null);
		Type ret = Type.getReturnType(desc);
		switch (ret.getSort()) {
			case Type.VOID -> m.instructions.add(new InsnNode(Opcodes.RETURN));
			case Type.LONG -> { m.instructions.add(new InsnNode(Opcodes.LCONST_0)); m.instructions.add(new InsnNode(Opcodes.LRETURN)); }
			case Type.FLOAT -> { m.instructions.add(new InsnNode(Opcodes.FCONST_0)); m.instructions.add(new InsnNode(Opcodes.FRETURN)); }
			case Type.DOUBLE -> { m.instructions.add(new InsnNode(Opcodes.DCONST_0)); m.instructions.add(new InsnNode(Opcodes.DRETURN)); }
			case Type.OBJECT, Type.ARRAY -> { m.instructions.add(new InsnNode(Opcodes.ACONST_NULL)); m.instructions.add(new InsnNode(Opcodes.ARETURN)); }
			default -> { m.instructions.add(new InsnNode(Opcodes.ICONST_0)); m.instructions.add(new InsnNode(Opcodes.IRETURN)); }
		}
		return m;
	}

	private static byte[] write(ClassNode c, int flags) {
		ClassWriter w = new ClassWriter(flags) {
			@Override protected String getCommonSuperClass(String a, String b) {
				return "java/lang/Object";   // stand-ins only: no class is loaded to compute a frame
			}
		};
		c.accept(w);
		return w.toByteArray();
	}
}
