package net.forbric.kernel.mixin;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.*;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.BasicVerifier;

/**
 * A wrap of vanilla's private registry loader moves to the live pair the GUEST's selector maps to, not to a fixed overload.
 * The fixture is not fabric-registry-sync's: another mod's mixin, a thread-local instead of a ScopedValue, the wrap on the
 * networked (client) entry point with the factory typed rather than coerced, and its companion a single-argument
 * {@code @ModifyArg}. On NeoForge's base the networked entry calls the widened loader itself, so the wrap must stay on the
 * networked entry; moving it to the resource-manager path would mark the server's load instead of the client's.
 */
class RegistryLoaderSelectorMappingTest {
	private static final String LOADER = "net/minecraft/resources/RegistryDataLoader";
	private static final String MIXIN = "org/example/sync/mixin/LoaderContextMixin";
	private static final String FUTURE = "Ljava/util/concurrent/CompletableFuture;";
	private static final String ARGS = "Ljava/util/List;Ljava/util/List;Ljava/util/concurrent/Executor;";
	private static final String FACTORY = "L" + LOADER + "$LoaderFactory;";
	private static final String NETWORKED = "load(Ljava/util/Map;Lnet/minecraft/server/packs/resources/ResourceProvider;" + ARGS + ")" + FUTURE;
	private static final String RESOURCES = "load(Lnet/minecraft/server/packs/resources/ResourceManager;" + ARGS + ")" + FUTURE;
	private static final String DEAD = "load(" + FACTORY + ARGS + ")" + FUTURE;
	private static final String LIVE = "load(" + FACTORY + ARGS + "Z)" + FUTURE;
	private static final String OP = "com/llamalad7/mixinextras/injector/wrapoperation/Operation";
	private static final String SUPPLY = "Ljava/util/concurrent/CompletableFuture;supplyAsync(Ljava/util/function/Supplier;Ljava/util/concurrent/Executor;)" + FUTURE;

	@Test void aClientWrapStaysOnTheClientEntryAndFollowsTheWidenedLoader() throws Exception {
		ClassNode loader = StagedFabricMixinFixture.game(LOADER, false), mixin = mixin(NETWORKED, "markNetworked");
		assertEquals(2, FabricRegistryLoaderMixinAdapter.adapt(mixin, name -> loader));
		MethodNode wrap = mixin.methods.stream().filter(m -> m.name.equals("markNetworked")).findFirst().orElseThrow();
		AnnotationNode injector = MixinFit.injectorOf(wrap);
		assertEquals(List.of(NETWORKED), MixinFit.stringList(MixinFit.value(injector, "method")), "the guest's own entry point, not the server path");
		assertEquals("L" + LOADER + ";" + LIVE, MixinFit.value(MixinFit.atNodes(injector).getFirst(), "target"));
		assertEquals("(" + FACTORY + ARGS + "ZL" + OP + ";)" + FUTURE, wrap.desc, "the carrier's flag is taken and passed through");
		new Analyzer<>(new BasicVerifier()).analyze(mixin.name, wrap);
		assertNull(MixinFit.injectorOf(mixin.methods.stream().filter(m -> m.name.equals("markNetworked$forbricOriginal")).findFirst().orElseThrow()));
		assertEquals(List.of(LIVE), MixinFit.stringList(MixinFit.value(MixinFit.injectorOf(StagedFabricMixinFixture.method(mixin, "handOverMark")), "method")));
		assertEquals(0, FabricRegistryLoaderMixinAdapter.adapt(mixin, name -> loader), "moved once");
	}

	/** The same mixin naming the resource-manager entry follows that entry into its widened twin, which is what calls the loader. */
	@Test void aServerWrapFollowsItsEntryIntoTheDelegatingTwin() throws Exception {
		ClassNode loader = StagedFabricMixinFixture.game(LOADER, false), mixin = mixin(RESOURCES, "markResources");
		assertEquals(2, FabricRegistryLoaderMixinAdapter.adapt(mixin, name -> loader));
		AnnotationNode injector = MixinFit.injectorOf(StagedFabricMixinFixture.method(mixin, "markResources"));
		String moved = MixinFit.stringList(MixinFit.value(injector, "method")).getFirst();
		assertTrue(moved.startsWith("load(Lnet/minecraft/server/packs/resources/ResourceManager;" + ARGS + "Ljava/util/List;)"), moved);
	}

	/** RED: on vanilla the wrapped call is live, so nothing moves. */
	@Test void aLiveCallKeepsItsWrap() throws Exception {
		ClassNode vanilla = StagedFabricMixinFixture.game(LOADER, true), mixin = mixin(NETWORKED, "markNetworked");
		byte[] before = StagedFabricMixinFixture.bytes(mixin);
		assertEquals(0, FabricRegistryLoaderMixinAdapter.adapt(mixin, name -> vanilla));
		assertArrayEquals(before, StagedFabricMixinFixture.bytes(mixin));
	}

	/**
	 * A bare "load" companion is the first {@code load}'s: the resource-manager entry, on vanilla and on NeoForge's class
	 * alike (javap of both; NeoForge declares no private loader of vanilla's shape for it to name). It binds the same
	 * method it binds on vanilla, so it stays as written, and the wrap still moves.
	 */
	@Test void aBareNameCompanionStaysOnTheEntryVanillaBindsItTo() throws Exception {
		ClassNode loader = StagedFabricMixinFixture.game(LOADER, false), mixin = mixin(NETWORKED, "markNetworked");
		MethodNode hand = StagedFabricMixinFixture.method(mixin, "handOverMark");
		AnnotationNode companion = MixinFit.injectorOf(hand);
		companion.values.set(companion.values.indexOf("method") + 1, new ArrayList<>(List.of("load")));
		MethodNode vanillaBinds = MixinTargetSelectors.one(hand, StagedFabricMixinFixture.game(LOADER, true)), currentBinds = MixinTargetSelectors.one(hand, loader);
		assertEquals(RESOURCES, vanillaBinds.name + vanillaBinds.desc, "premise: vanilla binds it to its resource-manager entry");
		assertEquals(RESOURCES, currentBinds.name + currentBinds.desc, "premise: and so does NeoForge's class");
		byte[] before = StagedFabricMixinFixture.bytes(alone(hand));
		assertEquals(1, FabricRegistryLoaderMixinAdapter.adapt(mixin, name -> loader), "the wrap alone moves");
		assertArrayEquals(before, StagedFabricMixinFixture.bytes(alone(StagedFabricMixinFixture.method(mixin, "handOverMark"))),
				"the companion is left as written, its injector, its point and its body");
		assertEquals("L" + LOADER + ";" + LIVE, MixinFit.value(MixinFit.atNodes(MixinFit.injectorOf(StagedFabricMixinFixture.method(mixin, "markNetworked"))).getFirst(), "target"));
	}

	/** RED: a callback that declares the dead loader's parameters would not fit the widened one, so nothing moves. */
	@Test void aCallbackDeclaringTheHostParametersLeavesTheMixinAlone() throws Exception {
		ClassNode loader = StagedFabricMixinFixture.game(LOADER, false), mixin = mixin(NETWORKED, "markNetworked");
		MethodNode callback = new MethodNode(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC, "seeLoad",
				"(" + FACTORY + ARGS + "Lorg/spongepowered/asm/mixin/injection/callback/CallbackInfoReturnable;)V", null, null);
		callback.instructions.add(new InsnNode(Opcodes.RETURN));
		callback.visibleAnnotations = new ArrayList<>(List.of(injector("Lorg/spongepowered/asm/mixin/injection/Inject;", DEAD, SUPPLY)));
		mixin.methods.add(callback);
		byte[] before = StagedFabricMixinFixture.bytes(mixin);
		assertEquals(0, FabricRegistryLoaderMixinAdapter.adapt(mixin, name -> loader));
		assertArrayEquals(before, StagedFabricMixinFixture.bytes(mixin));
	}

	private static ClassNode mixin(String host, String wrapName) {
		ClassNode mixin = new ClassNode();
		mixin.version = Opcodes.V21;
		mixin.access = Opcodes.ACC_PUBLIC;
		mixin.name = MIXIN;
		mixin.superName = "java/lang/Object";
		AnnotationNode target = new AnnotationNode("Lorg/spongepowered/asm/mixin/Mixin;");
		target.values = new ArrayList<>(List.of("value", new ArrayList<>(List.of(Type.getObjectType(LOADER)))));
		mixin.invisibleAnnotations = new ArrayList<>(List.of(target));
		mixin.fields.add(new FieldNode(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL, "CONTEXT", "Ljava/lang/ThreadLocal;", null, null));
		// markX(factory, lookups, registries, executor, original): set the thread-local, call through, return.
		MethodNode wrap = new MethodNode(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC, wrapName, "(" + FACTORY + ARGS + "L" + OP + ";)" + FUTURE, null, null);
		InsnList code = wrap.instructions;
		code.add(new FieldInsnNode(Opcodes.GETSTATIC, MIXIN, "CONTEXT", "Ljava/lang/ThreadLocal;"));
		code.add(new InsnNode(Opcodes.ICONST_1));
		code.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "java/lang/Boolean", "valueOf", "(Z)Ljava/lang/Boolean;", false));
		code.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/ThreadLocal", "set", "(Ljava/lang/Object;)V", false));
		code.add(new VarInsnNode(Opcodes.ALOAD, 4));
		code.add(new InsnNode(Opcodes.ICONST_4));
		code.add(new TypeInsnNode(Opcodes.ANEWARRAY, "java/lang/Object"));
		for (int i = 0; i < 4; i++) {
			code.add(new InsnNode(Opcodes.DUP));
			code.add(new LdcInsnNode(i));
			code.add(new VarInsnNode(Opcodes.ALOAD, i));
			code.add(new InsnNode(Opcodes.AASTORE));
		}
		code.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE, OP, "call", "([Ljava/lang/Object;)Ljava/lang/Object;", true));
		code.add(new TypeInsnNode(Opcodes.CHECKCAST, "java/util/concurrent/CompletableFuture"));
		code.add(new InsnNode(Opcodes.ARETURN));
		wrap.maxStack = 6;
		wrap.maxLocals = 5;
		wrap.visibleAnnotations = new ArrayList<>(List.of(injector("Lcom/llamalad7/mixinextras/injector/wrapoperation/WrapOperation;", host, "L" + LOADER + ";" + DEAD)));
		mixin.methods.add(wrap);
		// handOverMark(supplier): the companion, a fixed-index @ModifyArg on the dead loader's supplyAsync.
		MethodNode hand = new MethodNode(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC, "handOverMark", "(Ljava/util/function/Supplier;)Ljava/util/function/Supplier;", null, null);
		hand.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
		hand.instructions.add(new InsnNode(Opcodes.ARETURN));
		hand.maxStack = 1;
		hand.maxLocals = 1;
		AnnotationNode modify = injector("Lorg/spongepowered/asm/mixin/injection/ModifyArg;", DEAD, SUPPLY);
		modify.values.addAll(List.of("index", 0));
		hand.visibleAnnotations = new ArrayList<>(List.of(modify));
		mixin.methods.add(hand);
		return mixin;
	}

	/** A one-method class holding a copy of {@code method} (annotations and body), so two states of it compare byte for byte. */
	private static ClassNode alone(MethodNode method) {
		ClassNode holder = new ClassNode();
		holder.version = Opcodes.V21;
		holder.access = Opcodes.ACC_PUBLIC;
		holder.name = "org/example/Holder";
		holder.superName = "java/lang/Object";
		MethodNode copy = new MethodNode(method.access, method.name, method.desc, method.signature, null);
		method.accept(copy);
		holder.methods.add(copy);
		return holder;
	}

	private static AnnotationNode injector(String desc, String method, String point) {
		AnnotationNode at = new AnnotationNode("Lorg/spongepowered/asm/mixin/injection/At;");
		at.values = new ArrayList<>(List.of("value", "INVOKE", "target", point));
		AnnotationNode injector = new AnnotationNode(desc);
		injector.values = new ArrayList<>(List.of("method", new ArrayList<>(List.of(method)), "at", new ArrayList<>(List.of(at))));
		return injector;
	}
}
