/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.transform;

import static org.junit.jupiter.api.Assertions.*;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import net.forbric.api.Ecosystem;
import net.forbric.kernel.TestFixtures;
import net.forbric.kernel.TestFixtures.Fixture;
import net.forbric.kernel.mixin.MixinFit;
import net.forbric.kernel.mixin.NativeGameReferences;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.LocalVariableNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

/**
 * The pruner's two closed protocols are recognised by where Mixin binds each callback and what each point names, never by
 * how a selector or a target is spelled: the reader pair is one pair when both callbacks bind the one same method of the
 * target, and the tooltip group is one group when all its callbacks do. The invented fixtures are a reader pair under
 * names no mod has, each callback's selector written another way, a captured Reader named rather than indexed and the
 * parser's result kept in a local; the real ones are fabric-api's two mixins, respelled.
 */
@ResourceLock("system-properties")
class GuestInjectorPrunerSpellingTest {
	private static final String MIXIN = "tidy/forms/ReaderTap", HOST = "tidy/forms/FormReader";
	private static final String CUBOID = "net/minecraft/client/resources/model/cuboid/CuboidModel";
	private static final String PAIR = "com/mojang/datafixers/util/Pair";
	private static final String UNBAKED = "Lnet/minecraft/client/resources/model/UnbakedModel;";
	private static final String DECODE = "(Ljava/io/Reader;)L" + PAIR + ";";
	private static final String FROM_STREAM = "L" + CUBOID + ";fromStream(Ljava/io/Reader;)L" + CUBOID + ";";
	private static final String PAIR_OF = "L" + PAIR + ";of(Ljava/lang/Object;Ljava/lang/Object;)L" + PAIR + ";";

	@AfterEach
	void reset() {
		System.clearProperty(GuestInjectorPruner.PROPERTY);
		net.forbric.api.CompatibilityFindings.reset();
	}

	// ---------------------------------------------------------------------------------------------------------------
	// The reader pair under invented names

	/** Each callback's selector written another way, every point spelled out: one host, so the pair goes together. */
	@Test
	void eachCallbackSpellingItsHostAnotherWayStillClosesThePair() {
		List<String[]> spellings = List.of(
				new String[] {"L" + HOST + ";decode", "decode" + DECODE},
				new String[] {"tidy.forms.FormReader.decode" + DECODE, " decode "},
				new String[] {"decode", "L" + HOST + ";decode" + DECODE},
				new String[] {"decode " + DECODE, "tidy.forms.FormReader.decode"});
		for (String[] selectors : spellings) {
			byte[] mixin = mixin(List.of(selectors[0]), List.of(selectors[1]), FROM_STREAM, PAIR_OF);
			assertPruned(mixin, host(false, false), null, String.join(" / ", selectors));
		}
		// Two spellings of the one method on the redirect, one on the argument modifier.
		byte[] array = mixin(List.of("decode", "L" + HOST + ";decode" + DECODE), List.of("decode"), FROM_STREAM, PAIR_OF);
		assertPruned(array, host(false, false), null, "an array of two spellings of one method");
	}

	/**
	 * A bare name binds the first method of that name, as Mixin binds it: the protocol method declared before an overload
	 * of the same name is the host. The old reading found two methods of the name and gave up.
	 */
	@Test
	void aBareNameBindsTheFirstDeclaredOverloadAsMixinDoes() {
		assertPruned(mixin(List.of("decode"), List.of("decode"), FROM_STREAM, PAIR_OF), host(true, false), null,
				"the protocol method declared first");
	}

	/**
	 * A point written without its owner or descriptor names the protocol call where the callback was written for — the
	 * dropped parser call in the class the mod was compiled against, the Pair.of call in the host Mixin injects into.
	 */
	@Test
	void pointsWithoutTheirOwnerOrDescriptorAreReadWhereTheyWereWritten() {
		BiFunction<String, String, ClassNode> natives = (mixin, owner) -> owner.equals(HOST) ? nativeHost(CUBOID) : null;
		assertPruned(mixin(List.of("decode"), List.of("decode"), "L" + CUBOID + ";fromStream", "of"), host(false, false), natives,
				"an owner-qualified name without a descriptor, and a bare name");
		assertPruned(mixin(List.of("decode"), List.of("decode"), "fromStream", "com.mojang.datafixers.util.Pair.of (Ljava/lang/Object;Ljava/lang/Object;)L" + PAIR + ";"),
				host(false, false), natives, "a bare name, and a dotted owner with whitespace");
		ClassNode node = read(mixin(List.of("decode"), List.of("decode"), "fromStream", "of"));
		assertTrue(GuestInjectorPruner.unsafeWithoutPruning(node, owner -> owner.equals(HOST) ? host(false, false) : null,
				owner -> owner.equals(HOST) ? nativeHost(CUBOID) : null), "the pruner-off gate reads the pair as the pruner does");
	}

	/** Callbacks binding two different methods are no pair, however alike their selectors look. */
	@Test
	void callbacksBindingDifferentMethodsAreNoPair() {
		ClassNode twin = host(false, false);
		MethodNode copy = new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "decodeLater", DECODE, null, null);
		twin.methods.getFirst().accept(copy);
		copy.name = "decodeLater";
		twin.methods.add(copy);
		assertKept(mixin(List.of("decode"), List.of("decodeLater"), FROM_STREAM, PAIR_OF), twin, null, "another method of the class");
		assertKept(mixin(List.of("decode"), List.of("/^decode/"), FROM_STREAM, PAIR_OF), twin, null, "a pattern binding both");
		// A quantifier binds every method of the name, and here that is still the one host.
		assertPruned(mixin(List.of("decode"), List.of("decode*"), FROM_STREAM, PAIR_OF), twin, null, "every decode, which is one method");
		// The bare name binds the overload declared first, which is not the protocol method.
		assertKept(mixin(List.of("decode"), List.of("decode" + DECODE), FROM_STREAM, PAIR_OF), host(true, true), null,
				"the first declared overload is another method");
		assertKept(mixin(List.of("Lother/Place;decode"), List.of("decode"), FROM_STREAM, PAIR_OF), host(false, false), null,
				"an owner other than the target: Mixin binds nothing");
	}

	/** A point that, read where it was written, names another member than the protocol's is no protocol point. */
	@Test
	void pointsNamingAnotherMemberWhereTheyWereWrittenAreNoProtocol() {
		BiFunction<String, String, ClassNode> otherParser = (mixin, owner) -> owner.equals(HOST) ? nativeHost("tidy/forms/OwnModel") : null;
		assertKept(mixin(List.of("decode"), List.of("decode"), "fromStream", PAIR_OF), host(false, false), otherParser,
				"the native call of that name is another owner's");
		assertKept(mixin(List.of("decode"), List.of("decode"), "L" + CUBOID + ";fromStream", PAIR_OF), host(false, false), null,
				"no class the mod was compiled against to read it in");
		ClassNode listOf = host(false, false);
		MethodNode decode = listOf.methods.getFirst();
		decode.instructions.insert(new InsnNode(Opcodes.POP));
		decode.instructions.insert(new MethodInsnNode(Opcodes.INVOKESTATIC, "java/util/List", "of", "(Ljava/lang/Object;)Ljava/util/List;", true));
		decode.instructions.insert(new VarInsnNode(Opcodes.ALOAD, 0));
		assertKept(mixin(List.of("decode"), List.of("decode"), FROM_STREAM, "of"), listOf, null,
				"a bare point that also selects List.of in the host");
	}

	// ---------------------------------------------------------------------------------------------------------------
	// fabric-api's two mixins, respelled

	private static final Path MERGED_BASE = TestFixtures.mergedBase();
	private static final String MODEL_MANAGER_MIXIN = "net/fabricmc/fabric/mixin/client/model/loading/ModelManagerMixin";
	private static final String ITEM_STACK_MIXIN = "net/fabricmc/fabric/mixin/item/ItemStackMixin";
	private static final String MODEL_MANAGER = "net/minecraft/client/resources/model/ModelManager";
	private static final String LAMBDA = "lambda$loadBlockModels$2";
	private static final Set<String> TOOLTIPS = Set.of("preAppendComponentTooltip", "preShouldDisplay", "preAttributeModifiers",
			"postTooltipsAdvanced", "postTooltipsNonAdvanced");

	/**
	 * fabric-model-loading-api's pair with each selector and the dropped point written another way: the redirect by an
	 * owner-qualified name, the argument modifier by a dotted owner with its descriptor, and the dropped call without its
	 * descriptor, which the vanilla {@code ModelManager} the mod was compiled against decides. The same two go.
	 */
	@Test
	void theRealModelPairRespelledIsStillPrunedWhole() throws Exception {
		ClassNode node = read(realMixin("fabric-model-loading-api-v1", MODEL_MANAGER_MIXIN));
		select(method(node, "cancelVanillaDeserialize"), List.of("L" + MODEL_MANAGER + ";" + LAMBDA));
		select(method(node, "actuallyDeserializeModel"), List.of("net.minecraft.client.resources.model.ModelManager." + LAMBDA
				+ " (Ljava/util/Map$Entry;)L" + PAIR + ";"));
		target(method(node, "cancelVanillaDeserialize"), "L" + CUBOID + ";fromStream");
		byte[] respelled = write(node);
		byte[] pruned = realPruner().transform(MODEL_MANAGER_MIXIN.replace('/', '.'), respelled, null);
		assertNotSame(respelled, pruned);
		ClassNode after = read(pruned);
		assertNull(find(after, "cancelVanillaDeserialize"));
		assertNull(find(after, "actuallyDeserializeModel"));
		assertEquals(node.methods.size() - 2, after.methods.size(), "exactly the pair goes");

		// The argument modifier on another lambda of the same body: no pair, nothing goes.
		ClassNode split = read(realMixin("fabric-model-loading-api-v1", MODEL_MANAGER_MIXIN));
		select(method(split, "actuallyDeserializeModel"), List.of("L" + MODEL_MANAGER + ";lambda$loadBlockModels$3"));
		byte[] apart = write(split);
		assertSame(apart, realPruner().transform(MODEL_MANAGER_MIXIN.replace('/', '.'), apart, null));
	}

	/** fabric-item-api's five tooltip callbacks, each selector written another way: they still bind one method, and go together. */
	@Test
	void theRealTooltipGroupRespelledStillGoesTogether() throws Exception {
		ClassNode node = read(realMixin("fabric-item-api-v1", ITEM_STACK_MIXIN));
		List<String> spellings = List.of("Lnet/minecraft/world/item/ItemStack;addDetailsToTooltip",
				"net.minecraft.world.item.ItemStack.addDetailsToTooltip", " addDetailsToTooltip ",
				"addDetailsToTooltip(Lnet/minecraft/world/item/Item$TooltipContext;Lnet/minecraft/world/item/component/TooltipDisplay;"
						+ "Lnet/minecraft/world/entity/player/Player;Lnet/minecraft/world/item/TooltipFlag;Ljava/util/function/Consumer;)V",
				"addDetailsToTooltip");
		int i = 0;
		for (String name : TOOLTIPS.stream().sorted().toList()) {
			List<String> selectors = i == 4 ? List.of("addDetailsToTooltip*", spellings.get(0)) : List.of(spellings.get(i));
			select(method(node, name), selectors);
			i++;
		}
		byte[] respelled = write(node);
		byte[] pruned = realPruner().transform(ITEM_STACK_MIXIN.replace('/', '.'), respelled, null);
		assertNotSame(respelled, pruned, "the respelled group is pruned");
		ClassNode after = read(pruned);
		for (String name : TOOLTIPS) assertNull(find(after, name), name + " goes with the group");
		assertNotNull(find(after, "hookDamage"));

		// One of them on a pattern that also binds NeoForge's two renamed bodies: no longer one host, nothing goes.
		ClassNode spread = read(realMixin("fabric-item-api-v1", ITEM_STACK_MIXIN));
		select(method(spread, "preShouldDisplay"), List.of("/addDetailsToTooltip/"));
		byte[] apart = write(spread);
		assertSame(apart, realPruner().transform(ITEM_STACK_MIXIN.replace('/', '.'), apart, null), "a pattern binding three bodies");
		// …or on the tail body by its full spelling.
		ClassNode tail = read(realMixin("fabric-item-api-v1", ITEM_STACK_MIXIN));
		select(method(tail, "postTooltipsAdvanced"), List.of("Lnet/minecraft/world/item/ItemStack;addDetailsToTooltipTail"));
		byte[] other = write(tail);
		assertSame(other, realPruner().transform(ITEM_STACK_MIXIN.replace('/', '.'), other, null), "the tail body");
	}

	// ---------------------------------------------------------------------------------------------------------------

	private static void assertPruned(byte[] mixin, ClassNode host, BiFunction<String, String, ClassNode> natives, String what) {
		GuestInjectorPruner pruner = new GuestInjectorPruner(owner -> owner.equals(HOST) ? host : null, natives);
		byte[] out = pruner.transform(MIXIN.replace('/', '.'), mixin, null);
		assertNotSame(mixin, out, what);
		assertEquals(List.of(), read(out).methods.stream().map(m -> m.name).toList(), what + ": both callbacks go together");
		assertEquals(2, pruner.prunedInjectors(), what);
	}

	private static void assertKept(byte[] mixin, ClassNode host, BiFunction<String, String, ClassNode> natives, String what) {
		GuestInjectorPruner pruner = new GuestInjectorPruner(owner -> owner.equals(HOST) ? host : null, natives);
		assertSame(mixin, pruner.transform(MIXIN.replace('/', '.'), mixin, null), what);
		assertEquals(0, pruner.prunedInjectors(), what);
		assertFalse(GuestInjectorPruner.unsafeWithoutPruning(read(mixin), owner -> owner.equals(HOST) ? host : null,
				natives == null ? null : owner -> natives.apply(MIXIN, owner)), what + ": nor does the pruner-off gate see a pair");
	}

	private static AnnotationNode at(String target) {
		AnnotationNode at = new AnnotationNode("Lorg/spongepowered/asm/mixin/injection/At;");
		at.values = new ArrayList<>(List.of("value", "INVOKE", "target", target));
		return at;
	}

	/** The pair: a redirect dropping the old parser's model, and an argument modifier handing the named Reader to the deserializer. */
	private static byte[] mixin(List<String> redirectSelectors, List<String> argumentSelectors, String dropped, String paired) {
		ClassNode node = new ClassNode();
		node.visit(Opcodes.V21, Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER, MIXIN, null, "java/lang/Object", null);
		AnnotationNode mixin = new AnnotationNode("Lorg/spongepowered/asm/mixin/Mixin;");
		mixin.values = new ArrayList<>(List.of("targets", List.of(HOST)));
		node.invisibleAnnotations = new ArrayList<>(List.of(mixin));
		MethodNode drop = new MethodNode(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC, "skipLegacy", "(Ljava/io/Reader;)L" + CUBOID + ";", null, null);
		AnnotationNode redirect = new AnnotationNode("Lorg/spongepowered/asm/mixin/injection/Redirect;");
		redirect.values = new ArrayList<>(List.of("method", new ArrayList<>(redirectSelectors), "at", at(dropped)));
		drop.visibleAnnotations = new ArrayList<>(List.of(redirect));
		drop.instructions.add(new InsnNode(Opcodes.ACONST_NULL));
		drop.instructions.add(new InsnNode(Opcodes.ARETURN));
		drop.maxStack = 1;
		drop.maxLocals = 1;
		node.methods.add(drop);
		MethodNode parse = new MethodNode(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC, "parseInstead", "(Ljava/lang/Object;Ljava/io/Reader;)Ljava/lang/Object;", null, null);
		AnnotationNode modify = new AnnotationNode("Lorg/spongepowered/asm/mixin/injection/ModifyArg;");
		modify.values = new ArrayList<>(List.of("method", new ArrayList<>(argumentSelectors), "at", at(paired), "index", 1));
		parse.visibleAnnotations = new ArrayList<>(List.of(modify));
		AnnotationNode local = new AnnotationNode("Lcom/llamalad7/mixinextras/sugar/Local;");
		local.values = new ArrayList<>(List.of("name", List.of("source")));
		@SuppressWarnings("unchecked") List<AnnotationNode>[] parameters = new List[] {null, new ArrayList<>(List.of(local))};
		parse.invisibleParameterAnnotations = parameters;
		parse.invisibleAnnotableParameterCount = 2;
		parse.instructions.add(new VarInsnNode(Opcodes.ALOAD, 1));
		parse.instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "net/fabricmc/fabric/api/client/model/loading/v1/UnbakedModelDeserializer",
				"deserialize", "(Ljava/io/Reader;)" + UNBAKED, false));
		parse.instructions.add(new InsnNode(Opcodes.ARETURN));
		parse.maxStack = 1;
		parse.maxLocals = 2;
		node.methods.add(parse);
		return write(node);
	}

	/**
	 * The merged host: the current parser consumes the Reader, its model is kept in a local, and Pair.of takes both — the
	 * Reader by the name {@code source}. {@code overload}: a {@code decode(String)} too, declared first when {@code first}.
	 */
	private static ClassNode host(boolean overload, boolean first) {
		ClassNode node = new ClassNode();
		node.visit(Opcodes.V21, Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER, HOST, null, "java/lang/Object", null);
		MethodNode decode = new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "decode", DECODE, null, null);
		LabelNode start = new LabelNode(), end = new LabelNode();
		decode.instructions.add(start);
		decode.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
		decode.instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "net/neoforged/neoforge/client/model/UnbakedModelParser", "parse", "(Ljava/io/Reader;)" + UNBAKED, false));
		decode.instructions.add(new VarInsnNode(Opcodes.ASTORE, 1));
		decode.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
		decode.instructions.add(new VarInsnNode(Opcodes.ALOAD, 1));
		decode.instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC, PAIR, "of", "(Ljava/lang/Object;Ljava/lang/Object;)L" + PAIR + ";", false));
		decode.instructions.add(new InsnNode(Opcodes.ARETURN));
		decode.instructions.add(end);
		decode.localVariables = new ArrayList<>(List.of(new LocalVariableNode("source", "Ljava/io/Reader;", null, start, end, 0),
				new LocalVariableNode("model", UNBAKED, null, start, end, 1)));
		decode.maxStack = 2;
		decode.maxLocals = 2;
		MethodNode other = new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "decode", "(Ljava/lang/String;)V", null, null);
		other.instructions.add(new InsnNode(Opcodes.RETURN));
		if (overload && first) node.methods.add(other);
		node.methods.add(decode);
		if (overload && !first) node.methods.add(other);
		return node;
	}

	/** The host as the mod was compiled against it: the old parser {@code parser.fromStream} reads the Reader. */
	private static ClassNode nativeHost(String parser) {
		ClassNode node = new ClassNode();
		node.visit(Opcodes.V21, Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER, HOST, null, "java/lang/Object", null);
		MethodNode decode = new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "decode", DECODE, null, null);
		decode.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
		decode.instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC, parser, "fromStream", "(Ljava/io/Reader;)L" + parser + ";", false));
		decode.instructions.add(new VarInsnNode(Opcodes.ASTORE, 1));
		decode.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
		decode.instructions.add(new VarInsnNode(Opcodes.ALOAD, 1));
		decode.instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC, PAIR, "of", "(Ljava/lang/Object;Ljava/lang/Object;)L" + PAIR + ";", false));
		decode.instructions.add(new InsnNode(Opcodes.ARETURN));
		node.methods.add(decode);
		return node;
	}

	private static void select(MethodNode handler, List<String> selectors) {
		AnnotationNode injector = MixinFit.injectorOf(handler);
		for (int i = 0; i + 1 < injector.values.size(); i += 2)
			if ("method".equals(injector.values.get(i))) injector.values.set(i + 1, new ArrayList<>(selectors));
	}

	private static void target(MethodNode handler, String target) {
		AnnotationNode at = MixinFit.atNodes(MixinFit.injectorOf(handler)).getFirst();
		for (int i = 0; i + 1 < at.values.size(); i += 2) if ("target".equals(at.values.get(i))) at.values.set(i + 1, target);
	}

	private static MethodNode method(ClassNode node, String name) {
		MethodNode found = find(node, name);
		assertNotNull(found, "premise: the real mixin carries " + name);
		return found;
	}

	private static MethodNode find(ClassNode node, String name) {
		for (MethodNode m : node.methods) if (m.name.equals(name)) return m;
		return null;
	}

	private static ClassNode read(byte[] bytes) {
		ClassNode node = new ClassNode();
		new ClassReader(bytes).accept(node, 0);
		return node;
	}

	private static byte[] write(ClassNode node) {
		ClassWriter writer = new ClassWriter(0);
		node.accept(writer);
		return writer.toByteArray();
	}

	private static byte[] realMixin(String module, String mixin) throws Exception {
		Path fabricApi = TestFixtures.fabricApi();
		TestFixtures.require(Fixture.THIRD_PARTY, Files.isRegularFile(fabricApi), "fabric-api jar absent: " + fabricApi);
		TestFixtures.requireFile(Fixture.STAGED, MERGED_BASE, "staged merged base absent: " + MERGED_BASE);
		byte[] bytes = readFromNestedJar(fabricApi, module, mixin + ".class");
		TestFixtures.require(Fixture.THIRD_PARTY, bytes != null, mixin + " absent from the nested " + module + " module");
		net.forbric.kernel.mixin.MixinStubRebind.noteEcosystem(mixin, Ecosystem.FABRIC, module + ".mixins.json");
		return bytes;
	}

	/** The pruner over the staged merged base, with the vanilla classes the Fabric mods were compiled against. */
	private static GuestInjectorPruner realPruner() {
		Function<String, ClassNode> classes = owner -> {
			try {
				if (owner.equals("net/neoforged/neoforge/common/tooltip/ItemTooltipHandler")) {
					byte[] bytes = readFromJar(TestFixtures.stagedRoot().resolve(TestFixtures.NEO_CARRIER_JAR), owner + ".class");
					return bytes == null ? null : read(new NeoTooltipAppendersInjector().transform(owner.replace('/', '.'), bytes, null));
				}
				if (owner.equals("net/fabricmc/fabric/impl/item/ItemComponentTooltipProviderRegistryImpl")) {
					byte[] bytes = readFromNestedJar(TestFixtures.fabricApi(), "fabric-item-api-v1", owner + ".class");
					return bytes == null ? null : read(bytes);
				}
				byte[] bytes = readFromJar(MERGED_BASE, owner + ".class");
				return bytes == null ? null : read(bytes);
			} catch (Exception unavailable) {
				return null;
			}
		};
		NativeGameReferences references = new NativeGameReferences(path -> {
			try {
				return readFromJar(MERGED_BASE, path);
			} catch (Exception unavailable) {
				return null;
			}
		});
		return new GuestInjectorPruner(classes, (mixin, owner) -> references.get(Ecosystem.FABRIC, owner));
	}

	private static byte[] readFromJar(Path jar, String entry) throws Exception {
		if (!Files.isRegularFile(jar)) return null;
		try (ZipFile zip = new ZipFile(jar.toFile())) {
			ZipEntry found = zip.getEntry(entry);
			if (found == null) return null;
			try (InputStream in = zip.getInputStream(found)) {
				return in.readAllBytes();
			}
		}
	}

	private static byte[] readFromNestedJar(Path outer, String modulePrefix, String entry) throws Exception {
		try (ZipFile zip = new ZipFile(outer.toFile())) {
			for (Enumeration<? extends ZipEntry> e = zip.entries(); e.hasMoreElements(); ) {
				ZipEntry nested = e.nextElement();
				if (!nested.getName().startsWith("META-INF/jars/" + modulePrefix)) continue;
				Path tmp = Files.createTempFile("forbric-nested", ".jar");
				try (InputStream in = zip.getInputStream(nested)) {
					Files.write(tmp, in.readAllBytes());
				}
				try {
					byte[] bytes = readFromJar(tmp, entry);
					if (bytes != null) return bytes;
				} finally {
					Files.deleteIfExists(tmp);
				}
			}
		}
		return null;
	}
}
