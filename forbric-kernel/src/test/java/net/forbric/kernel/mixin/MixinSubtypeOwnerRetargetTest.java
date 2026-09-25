package net.forbric.kernel.mixin;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.ZipFile;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.objectweb.asm.tree.*;

/** lithostitched's Fabric predicate injector, on the real merged loadFromResource. */
@ResourceLock("system-properties")
class MixinSubtypeOwnerRetargetTest {
	private static final Path LITHO = Path.of("run/client-merged-pack/mods/lithostitched-1.7.13-fabric-26.2.jar");
	private static final String MIXIN = "dev/worldgen/lithostitched/mixin/common/predicate/RegistryLoadTaskMixin";
	private static final String TARGET = "net/minecraft/resources/RegistryLoadTask$PendingRegistration";

	@AfterEach void reset() { System.clearProperty(MixinSubtypeOwnerRetarget.PROPERTY); }

	private static ClassNode litho() throws Exception {
		assumeTrue(Files.isRegularFile(LITHO), "lithostitched's Fabric jar required");
		try (ZipFile zip = new ZipFile(LITHO.toFile())) {
			return MixinFit.parse(zip.getInputStream(zip.getEntry(MIXIN + ".class")).readAllBytes());
		}
	}

	/** The target as ForbricMixinService reads it: with its local variable table (the fixture skips debug info). */
	private static ClassNode withLocals(boolean vanilla) throws Exception {
		Path jar = vanilla ? Path.of(System.getProperty("user.home"), "Library/Application Support/minecraft/versions/26.2/26.2.jar")
				: Path.of(System.getenv().getOrDefault("FORBRIC_OLD", "../forbric-loader"), "run/merged-base/patched-mc-merged-26.2.jar");
		assumeTrue(Files.isRegularFile(jar), "actual game required");
		try (ZipFile zip = new ZipFile(jar.toFile())) {
			ClassNode node = new ClassNode();
			new org.objectweb.asm.ClassReader(zip.getInputStream(zip.getEntry(TARGET + ".class")).readAllBytes()).accept(node, 0);
			return node;
		}
	}

	@Test void thePredicateCheckMovesToTheSameCallThroughCodec() throws Exception {
		ClassNode mixin = litho(), target = withLocals(false);
		assertEquals(1, MixinSubtypeOwnerRetarget.adapt(mixin, n -> target));
		MethodNode handler = mixin.methods.stream().filter(m -> m.name.equals("loadFromResource")).findFirst().orElseThrow();
		assertEquals("Lcom/mojang/serialization/Codec;parse(Lcom/mojang/serialization/DynamicOps;Ljava/lang/Object;)Lcom/mojang/serialization/DataResult;",
				MixinFit.value(MixinFit.atNodes(MixinFit.injectorOf(handler)).getFirst(), "target"));
		assertEquals(0, MixinSubtypeOwnerRetarget.adapt(mixin, n -> target), "a second pass changes nothing");
	}

	@Test void vanillasOwnCallTheSwitchAndAMissingLocalAreLeftAlone() throws Exception {
		ClassNode vanilla = withLocals(true);
		assertEquals(0, MixinSubtypeOwnerRetarget.adapt(litho(), n -> vanilla), "vanilla still calls Decoder.parse");
		ClassNode merged = withLocals(false);
		System.setProperty(MixinSubtypeOwnerRetarget.PROPERTY, "off");
		assertEquals(0, MixinSubtypeOwnerRetarget.adapt(litho(), n -> merged));
		System.clearProperty(MixinSubtypeOwnerRetarget.PROPERTY);
		ClassNode renamed = withLocals(false);
		for (MethodNode m : renamed.methods) if (m.localVariables != null) for (LocalVariableNode l : m.localVariables) if (l.name.equals("json")) l.name = "element";
		assertEquals(0, MixinSubtypeOwnerRetarget.adapt(litho(), n -> renamed), "the handler's @Local(name=\"json\") must exist at the call");
	}

	@Test void codecDoesNotRedeclareParse() throws Exception {
		Path dfu = Path.of(System.getProperty("user.home"), "Library/Application Support/minecraft/libraries/com/mojang/datafixerupper");
		assumeTrue(Files.isDirectory(dfu), "Minecraft's DataFixerUpper required");
		Path jar;
		try (var files = Files.walk(dfu)) { jar = files.filter(p -> p.toString().endsWith(".jar")).sorted().reduce((a, b) -> b).orElseThrow(); }
		try (ZipFile zip = new ZipFile(jar.toFile())) {
			ClassNode codec = MixinFit.parse(zip.getInputStream(zip.getEntry("com/mojang/serialization/Codec.class")).readAllBytes());
			assertTrue(codec.interfaces.contains("com/mojang/serialization/Decoder"));
			assertEquals(List.of(), codec.methods.stream().filter(m -> m.name.equals("parse")).map(m -> m.name + m.desc).toList(),
					"Codec.parse is Decoder.parse: the retarget names the same method");
		}
	}
}
