package net.forbric.kernel.access;

import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.function.Supplier;
import net.fabricmc.api.EnvType;
import net.forbric.kernel.transform.TransformContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import static org.junit.jupiter.api.Assertions.*;

class RestoredAccessTransformerTest {
	private static final String OWNER = "sample/Restored";
	private static final String SUPPLIER = "Ljava/util/function/Supplier;";
	private static final TransformContext CONTEXT = new TransformContext(EnvType.CLIENT, false, "named");
	@BeforeEach @AfterEach void reset() { AccessCensus.reset(); }

	@Test void restoredDescriptorReceivesRealAccessAndAllowsAnExternalWrite() throws Exception {
		var aw = widener();
		aw.transform(OWNER, sample("Lnet/minecraftforge/common/util/ClearableLazy;", false), CONTEXT);
		assertEquals(1, AccessCensus.entries().size());
		byte[] repaired = sample(SUPPLIER, false); // COREMOD restored the descriptor, not the flags.
		Class<?> negative = define(repaired);
		assertThrows(IllegalAccessException.class, () -> negative.getDeclaredField("value").set(null, (Supplier<?>) List::of));
		byte[] replayed = new RestoredAccessTransformer(aw, null).transform(OWNER, repaired, CONTEXT);
		Class<?> type = define(replayed);
		Supplier<?> value = List::of;
		type.getField("value").set(null, value);
		assertSame(value, type.getField("value").get(null));
		assertTrue(AccessCensus.entries().isEmpty(), "a proven restored directive must not remain an unmatched finding");
		assertSame(replayed, new RestoredAccessTransformer(aw, null).transform(OWNER, replayed, CONTEXT));
		// Mixin's preview and the actual class definition can independently ask for these bytes. Clearing the
		// first diagnostic must not cause the later definition to miss its flags.
		Class<?> later = define(new RestoredAccessTransformer(aw, null).transform(OWNER, repaired, CONTEXT));
		later.getField("value").set(null, value);
		assertSame(value, later.getField("value").get(null));
	}

	@Test void anUnrepairedDescriptorIsStillUnmatched() {
		var aw = widener();
		byte[] original = sample("Lnet/minecraftforge/common/util/ClearableLazy;", false);
		aw.transform(OWNER, original, CONTEXT);
		assertSame(original, new RestoredAccessTransformer(aw, null).transform(OWNER, original, CONTEXT));
		assertEquals(1, AccessCensus.entries().size());
	}

	@Test void atReplayTouchesOnlyMissedExplicitRulesNotUnrelatedNewMembers() throws Exception {
		var at = new AccessTransformer(AccessTransformerParser.parse(new StringReader(
				"public-f sample.Restored added\npublic sample.Restored *\n"), "test.jar"));
		at.transform(OWNER, sample(SUPPLIER, false), CONTEXT);
		byte[] replayed = new RestoredAccessTransformer(null, at).transform(OWNER, sample(SUPPLIER, true), CONTEXT);
		Class<?> type = define(replayed);
		type.getField("added").setInt(null, 42);
		assertEquals(42, type.getField("added").getInt(null));
		assertThrows(IllegalAccessException.class, () -> type.getDeclaredField("unrelated").setInt(null, 42));
		assertTrue(AccessCensus.entries().isEmpty());
		Class<?> later = define(new RestoredAccessTransformer(null, at).transform(OWNER, sample(SUPPLIER, true), CONTEXT));
		later.getField("added").setInt(null, 43);
		assertEquals(43, later.getField("added").getInt(null));
	}

	@Test void actualMergedFeatureFieldIsReconciledAfterItsDescriptorRepair() throws Exception {
		var staged = java.nio.file.Path.of(System.getenv("FORBRIC_OLD") == null ? "../forbric-loader"
				: System.getenv("FORBRIC_OLD"), "run/merged-base/patched-mc-merged-26.2.jar");
		org.junit.jupiter.api.Assumptions.assumeTrue(java.nio.file.Files.isRegularFile(staged), "staged merged base absent");
		String owner = "net/minecraft/world/level/chunk/ChunkGenerator";
		byte[] original;
		try (var jar = new java.util.zip.ZipFile(staged.toFile()); var in = jar.getInputStream(jar.getEntry(owner + ".class"))) {
			original = in.readAllBytes();
		}
		String rules = "accessWidener v2 named\naccessible field " + owner + " featuresPerStep " + SUPPLIER
				+ "\nmutable field " + owner + " featuresPerStep " + SUPPLIER + "\n";
		var aw = ClassTweakerTransformer.createFrom(List.of(new ClassTweakerTransformer.File("fabric-biome-api.jar",
				rules.getBytes(StandardCharsets.UTF_8))), (name, bytes) -> fail("unexpected generated class"));
		byte[] early = aw.transform(owner.replace('/', '.'), original, CONTEXT);
		assertFalse(AccessCensus.entries().isEmpty(), "negative control: the raw base must reproduce the early miss");
		byte[] repaired = new net.forbric.kernel.transform.ForbricMergedBaseCompatTransformer()
				.transform(owner.replace('/', '.'), early, CONTEXT);
		byte[] result = new RestoredAccessTransformer(aw, null).transform(owner, repaired, CONTEXT);
		var node = new org.objectweb.asm.tree.ClassNode();
		new org.objectweb.asm.ClassReader(result).accept(node, 0);
		var field = node.fields.stream().filter(f -> f.name.equals("featuresPerStep")).findFirst().orElseThrow();
		assertEquals(SUPPLIER, field.desc);
		assertTrue((field.access & Opcodes.ACC_PUBLIC) != 0);
		assertEquals(0, field.access & Opcodes.ACC_FINAL);
		assertTrue(AccessCensus.entries().isEmpty(), "final evidence must reflect the actual repaired member");
	}

	private static ClassTweakerTransformer widener() {
		String text = "accessWidener v2 named\naccessible field " + OWNER + " value " + SUPPLIER
				+ "\nmutable field " + OWNER + " value " + SUPPLIER + "\n";
		return ClassTweakerTransformer.createFrom(List.of(new ClassTweakerTransformer.File("test.jar",
				text.getBytes(StandardCharsets.UTF_8))), (name, bytes) -> fail("access replay must not generate classes"));
	}

	private static byte[] sample(String descriptor, boolean restored) {
		ClassWriter writer = new ClassWriter(0);
		writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, OWNER, null, "java/lang/Object", null);
		writer.visitField(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL, "value", descriptor, null, null).visitEnd();
		if (restored) {
			writer.visitField(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL, "added", "I", null, null).visitEnd();
			writer.visitField(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC, "unrelated", "I", null, null).visitEnd();
		}
		writer.visitEnd();
		return writer.toByteArray();
	}
	private static Class<?> define(byte[] bytes) {
		return new ClassLoader(RestoredAccessTransformerTest.class.getClassLoader()) {
			Class<?> load() { return defineClass(OWNER.replace('/', '.'), bytes, 0, bytes.length); }
		}.load();
	}
}
