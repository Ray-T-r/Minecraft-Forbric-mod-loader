/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.mixin;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BiFunction;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.*;

import net.forbric.api.Ecosystem;
import net.forbric.kernel.TestFixtures;
import net.forbric.kernel.TestFixtures.Fixture;

/**
 * Whether a registry-loader companion was written for the dead private loader is decided where it was written: the one
 * method its selectors bind in the class the mod was compiled against ({@link MixinCallbackShape#written}), not what a
 * bare name happens to reach among the current class's overloads. Not fabric-registry-sync's mixin: another mod's names,
 * its wrap's selector and point dotted, its wrap keeping the argument array in a local, and companions of two kinds — a
 * CallbackInfoReturnable-only {@code @Inject} and a {@code @ModifyArg} — written with a bare {@code load} selector and an
 * ownerless or descriptorless point.
 *
 * <p>A bare name binds the first method of that name. NeoForge's loader no longer declares vanilla's private loader at
 * all: it widened it with a trailing flag. Compiled against a class that declares the private loader first, the
 * companion is that loader's, and follows the wrap into the widened loader (which once left the companion to bind a live
 * overload Mixin picks). Compiled against vanilla, whose first {@code load} is the resource-manager entry, the same bare
 * name is that entry's companion and stays as written — also without vanilla's class, since NeoForge's first {@code load}
 * is that entry too. Where the current class would bind it to another overload instead (the networked entry, or the
 * entry's own widening by a list of pending tags), nothing can map it and the whole mixin is left alone. A companion on
 * the loader's own lambda, bound in the current class to its twin widened by exactly the loader's appended parameters,
 * runs where the moved wrap runs and stays as written.
 *
 * <p>The merged base also declared the dead loader (and its lambda) beside the widened one, and two cases pinned the
 * refusal where a companion would have bound to those; NeoForge's class declares neither, so those cases went with it.
 */
class RegistryLoaderCompanionBindingTest {
	private static final String LOADER = "net/minecraft/resources/RegistryDataLoader";
	private static final String MIXIN = "org/example/datapacks/mixin/PackPhaseMixin";
	private static final String FUTURE = "Ljava/util/concurrent/CompletableFuture;";
	private static final String ARGS = "Ljava/util/List;Ljava/util/List;Ljava/util/concurrent/Executor;";
	private static final String FACTORY = "L" + LOADER + "$LoaderFactory;";
	private static final String NETWORKED = "load(Ljava/util/Map;Lnet/minecraft/server/packs/resources/ResourceProvider;" + ARGS + ")" + FUTURE;
	private static final String DEAD = "load(" + FACTORY + ARGS + ")" + FUTURE;
	private static final String LIVE = "load(" + FACTORY + ARGS + "Z)" + FUTURE;
	private static final String ENTRY = "load(Lnet/minecraft/server/packs/resources/ResourceManager;" + ARGS + ")" + FUTURE;
	/** The resource-manager entry's own widening, by a trailing list of pending tags: not the loader's. */
	private static final String ENTRY_PENDING = "load(Lnet/minecraft/server/packs/resources/ResourceManager;" + ARGS + "Ljava/util/List;)" + FUTURE;
	/** The private loader's own lambda, and its twin in the widened loader. */
	private static final String LAMBDA_DEAD = "lambda$load$0(Ljava/util/List;" + FACTORY + "Ljava/util/List;Ljava/util/concurrent/Executor;)" + FUTURE;
	private static final String LAMBDA_LIVE = "lambda$load$0(Ljava/util/List;" + FACTORY + "Ljava/util/List;Ljava/util/concurrent/Executor;Z)" + FUTURE;
	private static final String CONTEXT = "createContext(Ljava/util/List;Ljava/util/List;)Lnet/minecraft/resources/RegistryOps$RegistryInfoLookup;";
	private static final String OP = "com/llamalad7/mixinextras/injector/wrapoperation/Operation";
	private static final String CIR = "Lorg/spongepowered/asm/mixin/injection/callback/CallbackInfoReturnable;";
	private static final BiFunction<Ecosystem, String, ClassNode> STAGED = NativeCallTestEvidence.staged();

	/** Every case reads NeoForge's staged loader, and vanilla's as a Fabric mod is compiled against it (the vanilla-reference sidecar). */
	@BeforeEach void stagedBase() {
		TestFixtures.requireFiles(Fixture.STAGED, "the staged base", TestFixtures.stagedBase());
	}

	@AfterEach void reset() {
		MixinStubRebind.forget();
	}

	// ---- written for the dead loader ---------------------------------------------------------------------------------

	/** Written for the private loader, which NeoForge's class no longer declares: its bare name reaches only live overloads there. */
	@Test void aBareNameCompanionWrittenForTheDeadLoaderFollowsTheWrap() throws Exception {
		ClassNode mixin = mixin(inject("load", "supplyAsync(Ljava/util/function/Supplier;Ljava/util/concurrent/Executor;)" + FUTURE));
		assertEquals(2, FabricRegistryLoaderMixinAdapter.adapt(mixin, name -> current(), natives(true)));
		assertMoved(mixin, "onSupply");
	}

	/** The same, a {@code @ModifyArg} whose selector carries an owner prefix and whitespace and whose point has no descriptor. */
	@Test void aBareNameCompanionWrittenForALoaderTheMergeDroppedStillFollowsTheWrap() throws Exception {
		ClassNode mixin = mixin(modifyArg(" L" + LOADER + "; load", "Ljava/util/concurrent/CompletableFuture;supplyAsync"));
		assertEquals(2, FabricRegistryLoaderMixinAdapter.adapt(mixin, name -> current(), natives(true)));
		assertMoved(mixin, "keepSupplier");
	}

	// ---- written for a live method, or for nothing this can map --------------------------------------------------------

	/** Compiled against vanilla, a bare {@code load} is the resource-manager entry's, and NeoForge's class binds it there too. */
	@Test void aBareNameCompanionWrittenForTheResourceManagerEntryStaysAsWritten() throws Exception {
		ClassNode mixin = mixin(inject("load", "supplyAsync(Ljava/util/function/Supplier;Ljava/util/concurrent/Executor;)" + FUTURE));
		AnnotationNode companion = MixinFit.injectorOf(handler(mixin, "onSupply"));
		byte[] before = CarpetMixinAdapterTest.bytes(annotated(companion));
		assertEquals(1, FabricRegistryLoaderMixinAdapter.adapt(mixin, name -> current(), natives(false)), "the wrap alone moves");
		assertArrayEquals(before, CarpetMixinAdapterTest.bytes(annotated(MixinFit.injectorOf(handler(mixin, "onSupply")))), "the companion is left as written");
		assertEquals(List.of("L" + LOADER + ";" + LIVE), atTargets(handler(mixin, "notePhase")));
	}

	/**
	 * Written for the resource-manager entry, but bound to another live overload in a class that declares that one
	 * first: left as written, the companion would silently serve the networked entry. Nothing can map it, so the whole
	 * mixin is left alone — the wrap too.
	 */
	@Test void aCompanionTheMergedClassWouldBindToAnotherLiveOverloadLeavesTheMixinAlone() throws Exception {
		assertLeftAlone("load", ENTRY, first(current(), NETWORKED), NETWORKED);
	}

	/**
	 * The same, bound to the entry's own widening (a trailing list of pending tags): its parameters extend the entry's,
	 * but not by the ones the carrier appended to the loader the wrap follows, so it is another overload all the same.
	 */
	@Test void aCompanionTheMergedClassWouldBindToAnotherWideningOfItsEntryLeavesTheMixinAlone() throws Exception {
		assertLeftAlone("load", ENTRY, first(current(), ENTRY_PENDING), ENTRY_PENDING);
	}

	/**
	 * Written for the private loader's own lambda: where the current class binds the bare name to that lambda's twin in the
	 * widened loader — widened by exactly the loader's appended parameters — the companion runs where the moved wrap now
	 * runs, and stays as written while the wrap moves.
	 */
	@Test void aCompanionOnTheLoadersOwnLambdaBoundToItsWidenedTwinStaysAsWritten() throws Exception {
		ClassNode reordered = first(current(), LAMBDA_LIVE);
		ClassNode mixin = mixin(inject("lambda$load$0", CONTEXT));
		MethodNode companion = handler(mixin, "onSupply");
		assertEquals(LAMBDA_DEAD, name(MixinCallbackShape.written(companion, natives(false).apply(Ecosystem.FABRIC, LOADER))), "premise: vanilla's lambda");
		assertEquals(LAMBDA_LIVE, name(MixinTargetSelectors.one(companion, reordered)), "premise: the current class binds its widened twin");
		byte[] before = CarpetMixinAdapterTest.bytes(annotated(MixinFit.injectorOf(companion)));
		assertEquals(1, FabricRegistryLoaderMixinAdapter.adapt(mixin, name -> reordered, natives(false)), "the wrap alone moves");
		assertArrayEquals(before, CarpetMixinAdapterTest.bytes(annotated(MixinFit.injectorOf(handler(mixin, "onSupply")))), "the companion is left as written");
	}

	/**
	 * Without the class the mod was compiled against, a bare name is read in the current class, where NeoForge declares no
	 * dead loader for it to name: it binds NeoForge's first {@code load}, the resource-manager entry, which is vanilla's
	 * first {@code load} too (javap of both jars; neither entry calls {@code supplyAsync}, only the private loader does).
	 * The companion binds the same method it binds on vanilla, so it stays as written while the wrap moves.
	 */
	@Test void withoutTheNativeClassABareNameCompanionStaysOnTheEntryVanillaBindsItTo() throws Exception {
		ClassNode mixin = mixin(inject("load", "supplyAsync(Ljava/util/function/Supplier;Ljava/util/concurrent/Executor;)" + FUTURE));
		MethodNode companion = handler(mixin, "onSupply");
		assertEquals(ENTRY, name(MixinTargetSelectors.one(companion, StagedFabricMixinFixture.game(LOADER, true))), "premise: vanilla binds it to its entry");
		assertEquals(ENTRY, name(MixinTargetSelectors.one(companion, current())), "premise: and so does NeoForge's class");
		byte[] before = CarpetMixinAdapterTest.bytes(annotated(MixinFit.injectorOf(companion)));
		assertEquals(1, FabricRegistryLoaderMixinAdapter.adapt(mixin, name -> current(), null), "the wrap alone moves");
		assertArrayEquals(before, CarpetMixinAdapterTest.bytes(annotated(MixinFit.injectorOf(handler(mixin, "onSupply")))), "the companion is left as written");
		assertEquals(List.of("L" + LOADER + ";" + LIVE), atTargets(handler(mixin, "notePhase")));
	}

	// ---- fixtures ----------------------------------------------------------------------------------------------------------

	private static void assertMoved(ClassNode mixin, String companion) throws Exception {
		assertEquals(List.of(LIVE), MixinFit.stringList(MixinFit.value(MixinFit.injectorOf(handler(mixin, companion)), "method")), "the companion moves into the widened loader");
		MethodNode wrap = handler(mixin, "notePhase");
		assertEquals(List.of(NETWORKED), MixinFit.stringList(MixinFit.value(MixinFit.injectorOf(wrap), "method")), "the wrap stays on its own entry");
		assertEquals(List.of("L" + LOADER + ";" + LIVE), atTargets(wrap));
		CarpetMixinAdapterTest.verify(mixin);
		assertEquals(0, FabricRegistryLoaderMixinAdapter.adapt(mixin, name -> current(), natives(true)), "moved once");
	}

	/** Vanilla's loader as a Fabric mod is compiled against; {@code deadFirst}: the private loader declared before its entries. */
	private static BiFunction<Ecosystem, String, ClassNode> natives(boolean deadFirst) {
		return (family, owner) -> {
			ClassNode node = STAGED.apply(family, owner);
			if (node == null || !owner.equals(LOADER) || !deadFirst) return node;
			return first(PointRespelling.copy(node), DEAD);
		};
	}

	/** NeoForge's staged loader, with code: it declares the widened loader and not vanilla's private one. */
	private static ClassNode current() {
		try {
			ClassNode node = StagedFabricMixinFixture.game(LOADER, false);
			assertTrue(node.methods.stream().noneMatch(m -> (m.name + m.desc).equals(DEAD) || (m.name + m.desc).equals(LAMBDA_DEAD)),
					"premise: NeoForge's loader declares neither vanilla's private loader nor its lambda");
			assertTrue(node.methods.stream().anyMatch(m -> (m.name + m.desc).equals(LIVE)), "premise: it declares the widened loader");
			return node;
		} catch (RuntimeException unchecked) {
			throw unchecked; // a missing fixture's skip among them
		} catch (Exception unreadable) {
			throw new AssertionError(unreadable);
		}
	}

	/**
	 * A CallbackInfoReturnable-only companion with {@code selector}, which vanilla binds to {@code written} and
	 * {@code reordered} to {@code bound}: the adapter moves nothing and the mixin stays byte for byte as it was.
	 */
	private static void assertLeftAlone(String selector, String written, ClassNode reordered, String bound) throws Exception {
		ClassNode mixin = mixin(inject(selector, "supplyAsync(Ljava/util/function/Supplier;Ljava/util/concurrent/Executor;)" + FUTURE));
		MethodNode companion = handler(mixin, "onSupply");
		assertEquals(written, name(MixinCallbackShape.written(companion, natives(false).apply(Ecosystem.FABRIC, LOADER))), "premise: what vanilla binds");
		assertEquals(bound, name(MixinTargetSelectors.one(companion, reordered)), "premise: what the current class binds");
		byte[] before = CarpetMixinAdapterTest.bytes(mixin);
		assertEquals(0, FabricRegistryLoaderMixinAdapter.adapt(mixin, name -> reordered, natives(false)));
		assertArrayEquals(before, CarpetMixinAdapterTest.bytes(mixin));
	}

	private static String name(MethodNode method) {
		return method == null ? null : method.name + method.desc;
	}

	private static ClassNode first(ClassNode node, String member) {
		MethodNode method = node.methods.stream().filter(m -> (m.name + m.desc).equals(member)).findFirst().orElseThrow();
		node.methods.remove(method);
		node.methods.addFirst(method);
		return node;
	}

	/**
	 * The mixin: a wrap of the networked entry's call of the private loader, its selector and point dotted, which keeps
	 * the argument array in a local before calling through; and {@code companion}.
	 */
	private static ClassNode mixin(MethodNode companion) {
		ClassNode mixin = new ClassNode();
		mixin.version = Opcodes.V21;
		mixin.access = Opcodes.ACC_PUBLIC;
		mixin.name = MIXIN;
		mixin.superName = "java/lang/Object";
		AnnotationNode target = new AnnotationNode("Lorg/spongepowered/asm/mixin/Mixin;");
		target.values = new ArrayList<>(List.of("value", new ArrayList<>(List.of(Type.getObjectType(LOADER)))));
		mixin.invisibleAnnotations = new ArrayList<>(List.of(target));
		MethodNode wrap = new MethodNode(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC, "notePhase", "(" + FACTORY + ARGS + "L" + OP + ";)" + FUTURE, null, null);
		InsnList code = wrap.instructions;
		code.add(new InsnNode(Opcodes.ICONST_4));
		code.add(new TypeInsnNode(Opcodes.ANEWARRAY, "java/lang/Object"));
		code.add(new VarInsnNode(Opcodes.ASTORE, 5));
		for (int i = 0; i < 4; i++) {
			code.add(new VarInsnNode(Opcodes.ALOAD, 5));
			code.add(new LdcInsnNode(i));
			code.add(new VarInsnNode(Opcodes.ALOAD, i));
			code.add(new InsnNode(Opcodes.AASTORE));
		}
		code.add(new VarInsnNode(Opcodes.ALOAD, 4));
		code.add(new VarInsnNode(Opcodes.ALOAD, 5));
		code.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE, OP, "call", "([Ljava/lang/Object;)Ljava/lang/Object;", true));
		code.add(new TypeInsnNode(Opcodes.CHECKCAST, "java/util/concurrent/CompletableFuture"));
		code.add(new InsnNode(Opcodes.ARETURN));
		wrap.maxStack = 4;
		wrap.maxLocals = 6;
		wrap.visibleAnnotations = new ArrayList<>(List.of(injector("Lcom/llamalad7/mixinextras/injector/wrapoperation/WrapOperation;",
				"net.minecraft.resources.RegistryDataLoader." + NETWORKED, "net.minecraft.resources.RegistryDataLoader." + DEAD)));
		mixin.methods.add(wrap);
		mixin.methods.add(companion);
		MixinStubRebind.noteEcosystem(MIXIN, Ecosystem.FABRIC);
		return mixin;
	}

	/** A CallbackInfoReturnable-only callback at {@code point} in {@code selector}. */
	private static MethodNode inject(String selector, String point) {
		MethodNode callback = new MethodNode(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC, "onSupply", "(" + CIR + ")V", null, null);
		callback.instructions.add(new InsnNode(Opcodes.RETURN));
		callback.maxLocals = 1;
		callback.visibleAnnotations = new ArrayList<>(List.of(injector("Lorg/spongepowered/asm/mixin/injection/Inject;", selector, point)));
		return callback;
	}

	/** A fixed-index {@code @ModifyArg} handing the supplier through, at {@code point} in {@code selector}. */
	private static MethodNode modifyArg(String selector, String point) {
		MethodNode hand = new MethodNode(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC, "keepSupplier", "(Ljava/util/function/Supplier;)Ljava/util/function/Supplier;", null, null);
		hand.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
		hand.instructions.add(new InsnNode(Opcodes.ARETURN));
		hand.maxStack = 1;
		hand.maxLocals = 1;
		AnnotationNode modify = injector("Lorg/spongepowered/asm/mixin/injection/ModifyArg;", selector, point);
		modify.values.addAll(List.of("index", 0));
		hand.visibleAnnotations = new ArrayList<>(List.of(modify));
		return hand;
	}

	private static AnnotationNode injector(String desc, String method, String point) {
		AnnotationNode at = new AnnotationNode("Lorg/spongepowered/asm/mixin/injection/At;");
		at.values = new ArrayList<>(List.of("value", "INVOKE", "target", point));
		AnnotationNode injector = new AnnotationNode(desc);
		injector.values = new ArrayList<>(List.of("method", new ArrayList<>(List.of(method)), "at", new ArrayList<>(List.of(at))));
		return injector;
	}

	private static MethodNode handler(ClassNode mixin, String name) {
		return mixin.methods.stream().filter(m -> m.name.equals(name) && MixinFit.injectorOf(m) != null).findFirst().orElseThrow();
	}

	private static List<String> atTargets(MethodNode handler) {
		List<String> out = new ArrayList<>();
		for (AnnotationNode at : MixinFit.atNodes(MixinFit.injectorOf(handler))) out.add(MixinFit.asString(MixinFit.value(at, "target")));
		return out;
	}

	/** A one-method class carrying {@code annotation}, so two annotations can be compared byte for byte. */
	private static ClassNode annotated(AnnotationNode annotation) {
		ClassNode holder = new ClassNode();
		holder.version = Opcodes.V21;
		holder.name = "org/example/Holder";
		holder.superName = "java/lang/Object";
		MethodNode carrier = new MethodNode(Opcodes.ACC_ABSTRACT | Opcodes.ACC_PUBLIC, "carry", "()V", null, null);
		carrier.visibleAnnotations = new ArrayList<>(List.of(annotation));
		holder.methods.add(carrier);
		holder.access = Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT;
		return holder;
	}
}
