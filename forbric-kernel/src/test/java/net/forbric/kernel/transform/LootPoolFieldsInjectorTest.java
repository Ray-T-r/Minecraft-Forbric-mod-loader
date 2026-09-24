package net.forbric.kernel.transform;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipFile;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.BasicVerifier;

/** On the real merged LootPool: each family's constructor ends up writing the other family's fields. */
class LootPoolFieldsInjectorTest {
	private static final Path MERGED = Path.of(System.getProperty("forbric.stagedRoot", "../forbric-loader/run"), "merged-base/patched-mc-merged-26.2.jar");

	@Test void bothConstructorsFillBothFamiliesFields() throws Exception {
		byte[] original = lootPool();
		assertFalse(writes(ctor(original, LootPoolFieldsInjector.NEO_CTOR), "forge_condition", LootPoolFieldsInjector.OPTIONAL),
				"premise: NeoForge's constructor leaves MinecraftForge's condition null");
		byte[] rewritten = new LootPoolFieldsInjector().transform(LootPoolFieldsInjector.TARGET, original, null);
		MethodNode neo = ctor(rewritten, LootPoolFieldsInjector.NEO_CTOR), forge = ctor(rewritten, LootPoolFieldsInjector.FORGE_CTOR);
		assertTrue(writes(neo, "forge_condition", LootPoolFieldsInjector.OPTIONAL));
		assertTrue(writes(neo, "name", LootPoolFieldsInjector.OPTIONAL));
		assertTrue(writes(forge, "name", LootPoolFieldsInjector.STRING));
		for (MethodNode ctor : new MethodNode[] {neo, forge}) new Analyzer<>(new BasicVerifier()).analyze("net/minecraft/world/level/storage/loot/LootPool", ctor);
		assertSame(rewritten, new LootPoolFieldsInjector().transform(LootPoolFieldsInjector.TARGET, rewritten, null), "idempotent");
	}

	@Test void theSwitchLeavesTheClassAlone() throws Exception {
		System.setProperty(LootPoolFieldsInjector.PROPERTY, "off");
		try {
			byte[] original = lootPool();
			assertSame(original, new LootPoolFieldsInjector().transform(LootPoolFieldsInjector.TARGET, original, null));
		} finally {
			System.clearProperty(LootPoolFieldsInjector.PROPERTY);
		}
	}

	private static byte[] lootPool() throws Exception {
		Assumptions.assumeTrue(Files.isRegularFile(MERGED), "staged merged base absent");
		try (ZipFile zip = new ZipFile(MERGED.toFile()); var in = zip.getInputStream(zip.getEntry("net/minecraft/world/level/storage/loot/LootPool.class"))) {
			return in.readAllBytes();
		}
	}

	private static MethodNode ctor(byte[] bytes, String desc) {
		ClassNode node = new ClassNode(); new ClassReader(bytes).accept(node, 0);
		return node.methods.stream().filter(m -> m.name.equals("<init>") && m.desc.equals(desc)).findFirst().orElseThrow();
	}

	private static boolean writes(MethodNode method, String name, String desc) {
		for (AbstractInsnNode insn : method.instructions)
			if (insn instanceof FieldInsnNode f && f.getOpcode() == Opcodes.PUTFIELD && f.name.equals(name) && f.desc.equals(desc)) return true;
		return false;
	}
}
