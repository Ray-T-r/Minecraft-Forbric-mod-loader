package net.forbric.kernel.mixin;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipFile;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.*;

import net.forbric.kernel.TestFixtures;
import net.forbric.kernel.boot.LootSourceCallbacks;
import net.forbric.kernel.transform.LootTableEventBridgeInjector;

/**
 * A source that fires the loot reload's public events from the reload the kernel's bridge fires them for stays out
 * whether or not its exact bytes prove the generated-helper path: otherwise a new fabric-api build (one instruction
 * different) would fire ALL_LOADED twice. Decided by the dispatch, never by the module or its build.
 */
@ResourceLock("system-properties")
class BridgedLootDispatchPolicyTest {
	private static final String SEAM = "net/minecraft/server/ReloadableServerRegistries";
	private static final String EVENTS = "net/fabricmc/fabric/api/loot/v3/LootTableEvents";
	private static final String EVENT = "net/fabricmc/fabric/api/event/Event";
	private static final String LOADED = EVENTS + "$Loaded";
	private static final String CIR = "Lorg/spongepowered/asm/mixin/injection/callback/CallbackInfoReturnable;";

	@AfterEach void reset() {
		MergedBaseMixinCompat.reset();
		System.clearProperty(LootTableEventBridgeInjector.PROPERTY);
	}

	/** fabric-loot-api's own mixin, one instruction off its fingerprint: the plan no longer proves it, the pin still holds. */
	@Test void aRebuiltFabricLootSourceIsStillLeftOut() throws Exception {
		ClassNode source = fabricLootSource();
		MethodNode loaded = source.methods.stream().filter(m -> m.instructions.size() > 0 && java.util.Arrays.stream(m.instructions.toArray())
				.anyMatch(i -> i instanceof FieldInsnNode f && f.name.equals("ALL_LOADED"))).findFirst().orElseThrow();
		loaded.instructions.insert(new InsnNode(Opcodes.NOP));
		assertNull(LootSourceCallbacks.plan(source), "the rebuilt group is not the one the helper was proved for");
		String config = "fabric-loot-api-v3.mixins.json";
		MergedBaseMixinCompat.discover(config, json("net.fabricmc.fabric.mixin.loot", "ReloadableServerRegistriesMixin"), resources(source));
		assertEquals(List.of("ReloadableServerRegistriesMixin"), ForbricMixinService.suppressedMixinsFor(config));
		String reason = MergedBaseMixinCompat.reason(config, "ReloadableServerRegistriesMixin");
		assertTrue(reason.contains("ALL_LOADED") && reason.contains("MODIFY") && reason.contains("REPLACE"), reason);
		assertFalse(reason.contains("left out with it"), "every injector of the group serves the dispatch: " + reason);
	}

	/** Another mod's dispatcher, written differently: the event kept in a local, one handler, another class name. */
	@Test void aRenamedDispatcherKeepingTheEventInALocalIsLeftOut() throws Exception {
		ClassNode source = mixin("org/example/loot/mixin/ReloadHookMixin", SEAM);
		source.methods.add(dispatcher("announceLoaded", true));
		String config = "reloadhook.mixins.json";
		MergedBaseMixinCompat.discover(config, json("org.example.loot.mixin", "ReloadHookMixin"), resources(source));
		assertEquals(List.of("ReloadHookMixin"), ForbricMixinService.suppressedMixinsFor(config));
		assertTrue(MergedBaseMixinCompat.reason(config, "ReloadHookMixin").contains("[ALL_LOADED]"));
	}

	/** An injector that neither dispatches nor shares the dispatcher's state is named as going with it. */
	@Test void anUnrelatedInjectorIsNamedInTheReason() throws Exception {
		ClassNode source = mixin("org/example/loot/mixin/ReloadHookMixin", SEAM);
		source.methods.add(dispatcher("announceLoaded", false));
		MethodNode other = handler("countReloads", "(" + CIR + ")V", code -> {
			code.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "org/example/loot/Stats", "reloaded", "()V", false));
			code.add(new InsnNode(Opcodes.RETURN));
		});
		source.methods.add(other);
		String config = "reloadhook.mixins.json";
		MergedBaseMixinCompat.discover(config, json("org.example.loot.mixin", "ReloadHookMixin"), resources(source));
		assertTrue(MergedBaseMixinCompat.reason(config, "ReloadHookMixin").contains("outside that group: [countReloads]"));
	}

	/** RED: registering a listener reads the same event field but dispatches nothing. */
	@Test void aListenerRegistrationIsNotADispatch() throws Exception {
		ClassNode source = mixin("org/example/loot/mixin/ReloadHookMixin", SEAM);
		source.methods.add(handler("listen", "(" + CIR + ")V", code -> {
			code.add(new FieldInsnNode(Opcodes.GETSTATIC, EVENTS, "ALL_LOADED", "L" + EVENT + ";"));
			code.add(new InsnNode(Opcodes.ACONST_NULL));
			code.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, EVENT, "register", "(Ljava/lang/Object;)V", false));
			code.add(new InsnNode(Opcodes.RETURN));
		}));
		String config = "reloadhook.mixins.json";
		MergedBaseMixinCompat.discover(config, json("org.example.loot.mixin", "ReloadHookMixin"), resources(source));
		assertTrue(ForbricMixinService.suppressedMixinsFor(config).isEmpty());
	}

	/** RED: the same dispatch onto another class is not the reload the bridge fires for; nor is anything with the bridge off. */
	@Test void aDispatchElsewhereOrWithTheBridgeOffStays() throws Exception {
		ClassNode elsewhere = mixin("org/example/loot/mixin/ReloadHookMixin", "net/minecraft/server/MinecraftServer");
		elsewhere.methods.add(dispatcher("announceLoaded", false));
		String config = "reloadhook.mixins.json";
		MergedBaseMixinCompat.discover(config, json("org.example.loot.mixin", "ReloadHookMixin"), resources(elsewhere));
		assertTrue(ForbricMixinService.suppressedMixinsFor(config).isEmpty());
		ClassNode seam = mixin("org/example/loot/mixin/ReloadHookMixin", SEAM);
		seam.methods.add(dispatcher("announceLoaded", false));
		System.setProperty(LootTableEventBridgeInjector.PROPERTY, "off");
		MergedBaseMixinCompat.discover(config, json("org.example.loot.mixin", "ReloadHookMixin"), resources(seam));
		assertTrue(ForbricMixinService.suppressedMixinsFor(config).isEmpty());
	}

	private static Map<String, byte[]> classes(ClassNode source) throws Exception {
		Map<String, byte[]> out = new HashMap<>();
		out.put(source.name + ".class", StagedFabricMixinFixture.bytes(source));
		out.put(SEAM + ".class", StagedFabricMixinFixture.bytes(StagedFabricMixinFixture.game(SEAM, false)));
		return out;
	}

	private static java.util.function.Function<String, byte[]> resources(ClassNode source) throws Exception {
		Map<String, byte[]> classes = classes(source);
		return classes::get;
	}

	private static byte[] json(String pkg, String entry) {
		return ("{\"package\":\"" + pkg + "\",\"mixins\":[\"" + entry + "\"]}").getBytes(StandardCharsets.UTF_8);
	}

	private static ClassNode fabricLootSource() throws Exception {
		java.nio.file.Path api = TestFixtures.fabricApi();
		TestFixtures.requireFiles(TestFixtures.Fixture.STAGED, "fabric-api fixture", api);
		try (ZipFile outer = new ZipFile(api.toFile())) {
			var module = outer.stream().filter(e -> e.getName().startsWith("META-INF/jars/fabric-loot-api-v3-")).findFirst().orElseThrow();
			try (var inner = new java.util.zip.ZipInputStream(outer.getInputStream(module))) {
				for (var entry = inner.getNextEntry(); entry != null; entry = inner.getNextEntry())
					if (entry.getName().equals("net/fabricmc/fabric/mixin/loot/ReloadableServerRegistriesMixin.class"))
						return MixinFit.parse(inner.readAllBytes());
			}
		}
		throw new AssertionError("fabric-loot-api's reload mixin not found");
	}

	private static ClassNode mixin(String name, String target) {
		ClassNode mixin = new ClassNode();
		mixin.version = Opcodes.V21;
		mixin.access = Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT;
		mixin.name = name;
		mixin.superName = "java/lang/Object";
		AnnotationNode type = new AnnotationNode("Lorg/spongepowered/asm/mixin/Mixin;");
		type.values = new ArrayList<>(List.of("value", new ArrayList<>(List.of(Type.getObjectType(target)))));
		mixin.invisibleAnnotations = new ArrayList<>(List.of(type));
		return mixin;
	}

	/** An @Inject at RETURN that fires ALL_LOADED with nulls, the event read into a local first or straight off the field. */
	private static MethodNode dispatcher(String name, boolean viaLocal) {
		return handler(name, "(" + CIR + ")V", code -> {
			code.add(new FieldInsnNode(Opcodes.GETSTATIC, EVENTS, "ALL_LOADED", "L" + EVENT + ";"));
			if (viaLocal) {
				code.add(new VarInsnNode(Opcodes.ASTORE, 2));
				code.add(new VarInsnNode(Opcodes.ALOAD, 2));
			}
			code.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, EVENT, "invoker", "()Ljava/lang/Object;", false));
			code.add(new TypeInsnNode(Opcodes.CHECKCAST, LOADED));
			code.add(new InsnNode(Opcodes.ACONST_NULL));
			code.add(new InsnNode(Opcodes.ACONST_NULL));
			code.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE, LOADED, "onLootTablesLoaded",
					"(Lnet/minecraft/server/packs/resources/ResourceManager;Lnet/minecraft/core/Registry;)V", true));
			code.add(new InsnNode(Opcodes.RETURN));
		});
	}

	private static MethodNode handler(String name, String desc, java.util.function.Consumer<InsnList> body) {
		MethodNode m = new MethodNode(Opcodes.ACC_PRIVATE, name, desc, null, null);
		body.accept(m.instructions);
		m.maxStack = 3;
		m.maxLocals = 3;
		AnnotationNode at = new AnnotationNode("Lorg/spongepowered/asm/mixin/injection/At;");
		at.values = new ArrayList<>(List.of("value", "RETURN"));
		AnnotationNode inject = new AnnotationNode("Lorg/spongepowered/asm/mixin/injection/Inject;");
		inject.values = new ArrayList<>(List.of("method", new ArrayList<>(List.of("reload")), "at", new ArrayList<>(List.of(at))));
		m.visibleAnnotations = new ArrayList<>(List.of(inject));
		return m;
	}
}
