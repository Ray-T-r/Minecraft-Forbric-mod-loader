package net.forbric.kernel.transform;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.zip.ZipFile;

import net.forbric.kernel.TestFixtures;
import net.forbric.kernel.TestFixtures.Fixture;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.MethodNode;

/**
 * Every class the transfer audit certifies, read from the staged jars and put through {@link VanillaEarlyReturns}
 * the way the game loads it, is still a reviewed shape.
 *
 * <p>The audit pins exact bytecode, and the early-returns transform rewrites bytecode in 3,263 merged methods. The
 * two met for the first time on a running server: from the day the transform landed, ItemStack and CompoundTag no
 * longer matched their reviewed digests, so every Forge ItemStackHandler and FluidTank was refused as not
 * rollback-safe. No unit test noticed. Only gate-m33 and gate-m39 did, and they are not run in CI. The audit also
 * stops at the first class that fails, which hid the next two (DataComponentPatch and PatchedDataComponentMap)
 * behind the first. This test reads every class, so a regenerated early-returns table that touches an audited
 * class fails here, listing all of them.
 */
@ResourceLock("native-game-references")
class ForgeTransferShapeAuditStagedTest {
	private static final VanillaEarlyReturns EARLY_RETURNS = new VanillaEarlyReturns();

	/** Every audited class, as the staged jars hold it. */
	private static Map<String, byte[]> staged() throws Exception {
		Path root = TestFixtures.stagedRoot();
		Path merged = root.resolve("merged-base/patched-mc-merged-26.2.jar").normalize();
		Path interop = root.resolve("merged-base/forge-runtime-interop.jar").normalize();
		Path forge = Files.isRegularFile(interop) ? interop : root.resolve("forge-runtime/forge-runtime.jar").normalize();
		Path neo = root.resolve("neoforge-runtime/neoforge-runtime.jar").normalize();
		TestFixtures.requireFiles(Fixture.STAGED, "the merged base and both carriers", merged, forge, neo);
		Map<String, byte[]> found = new TreeMap<>();
		List<String> missing = new ArrayList<>();
		for (String name : new TreeSet<>(ForgeTransferShapeAudit.auditedClasses())) {
			byte[] bytes = null;
			for (Path jar : List.of(merged, forge, neo)) {
				try (ZipFile zip = new ZipFile(jar.toFile())) {
					var entry = zip.getEntry(name.replace('.', '/') + ".class");
					if (entry != null) { bytes = zip.getInputStream(entry).readAllBytes(); break; }
				}
			}
			if (bytes == null) missing.add(name); else found.put(name, bytes);
		}
		assertEquals(List.of(), missing, "audited classes the staged jars do not contain: the fixture has drifted");
		return found;
	}

	/** Each class's fingerprint as the game takes it: with the merged-base repair's proof over the staged jars. */
	private static Map<String, String> unreviewed(Map<String, byte[]> classes) throws Exception {
		Map<String, String> bad = new TreeMap<>();
		try (StagedGameClasses game = StagedGameClasses.open()) {
			classes.forEach((name, bytes) -> {
				String fingerprint = ForgeTransferShapeAudit.fingerprint(bytes, game.proof());
				if (!ForgeTransferShapeAudit.reviewed(name, fingerprint)) bad.put(name, fingerprint);
			});
		}
		return bad;
	}

	private static Map<String, byte[]> restored(Map<String, byte[]> classes) {
		Map<String, byte[]> out = new TreeMap<>();
		classes.forEach((name, bytes) -> out.put(name, EARLY_RETURNS.transform(name, bytes, null)));
		return out;
	}

	@Test void everyAuditedClassIsAReviewedShapeAsMerged() throws Exception {
		// -Dforbric.vanillaEarlyReturns=off loads these bytes, less the merge delegates the merged-base repair proves
		// are the inherited default, which the fingerprint folds by that same proof.
		assertEquals(Map.of(), unreviewed(staged()), "class -> observed fingerprint");
	}

	@Test void everyAuditedClassIsStillAReviewedShapeAfterVanillaEarlyReturns() throws Exception {
		assertTrue(VanillaEarlyReturns.enabled(), "the transform is on by default, and this test is about it being on");
		assertEquals(Map.of(), unreviewed(restored(staged())),
				"the early-returns transform changed an audited class into a shape nobody reviewed, so the transfer "
						+ "bridge would refuse it on a running server. Check what changed in the transfer-critical "
						+ "methods and add the fingerprint to ForgeTransferShapeAudit.RESTORED (class -> observed)");
	}

	@Test void theRestoredShapesAreExactlyTheClassesTheTransformEdits() throws Exception {
		// A RESTORED entry for a class the transform no longer touches would be a reviewed digest nothing produces,
		// and an edited class without one is the failure above. Both directions, so the list never drifts.
		Map<String, byte[]> merged = staged();
		Map<String, byte[]> restored = restored(merged);
		TreeSet<String> edited = new TreeSet<>();
		try (StagedGameClasses game = StagedGameClasses.open()) {
			merged.forEach((name, bytes) -> {
				if (!ForgeTransferShapeAudit.fingerprint(bytes, game.proof()).equals(ForgeTransferShapeAudit.fingerprint(restored.get(name), game.proof())))
					edited.add(name);
			});
		}
		assertEquals(new TreeSet<>(ForgeTransferShapeAudit.restoredClasses()), edited);
	}

	@Test void certifyMarksTheRestoredItemStackAndStillRefusesAChangedOne() throws Exception {
		String name = "net.minecraft.world.item.ItemStack";
		byte[] restored = restored(staged()).get(name);
		try (StagedGameClasses game = StagedGameClasses.open()) {
			certifyMarksTheRestoredItemStackAndStillRefusesAChangedOne(game, name, restored);
		}
	}

	private static void certifyMarksTheRestoredItemStackAndStillRefusesAChangedOne(StagedGameClasses game, String name, byte[] restored) throws Exception {
		Path dump = Files.createTempDirectory("transfer-shape-dump");
		String previous = System.setProperty("forbric.transferShapeDump", dump.toString());
		try {
			assertTrue(marked(ForgeTransferShapeAudit.certify(name, restored, game.resources())), "the restored ItemStack must be certified");
		} finally {
			if (previous == null) System.clearProperty("forbric.transferShapeDump"); else System.setProperty("forbric.transferShapeDump", previous);
		}
		// The dump is the audit's only local evidence, and reviewed != observed in it has always meant "refused".
		String observed = ForgeTransferShapeAudit.fingerprint(restored, game.proof());
		assertEquals(List.of("reviewed=" + observed, "observed=" + observed, "certified=true"),
				Files.readAllLines(dump.resolve(name.replace('.', '/') + ".class.audit.txt")));

		// The same bytes with one transfer-critical constant changed: isSameItemSameComponents answering true where
		// it answered false. The audit still has to refuse that, or accepting a second shape made it toothless.
		ClassNode node = new ClassNode();
		new ClassReader(restored).accept(node, 0);
		MethodNode method = node.methods.stream()
				.filter(m -> m.name.equals("isSameItemSameComponents")).findFirst().orElseThrow();
		AbstractInsnNode constant = null;
		for (AbstractInsnNode insn : method.instructions) if (insn.getOpcode() == Opcodes.ICONST_0) { constant = insn; break; }
		assertTrue(constant != null, "isSameItemSameComponents has no false answer to change");
		method.instructions.set(constant, new InsnNode(Opcodes.ICONST_1));
		ClassWriter writer = new ClassWriter(0);
		node.accept(writer);
		byte[] tampered = writer.toByteArray();
		assertNotEquals(ForgeTransferShapeAudit.fingerprint(restored, game.proof()), ForgeTransferShapeAudit.fingerprint(tampered, game.proof()),
				"the change did not reach anything the fingerprint covers, so this control proves nothing");
		assertFalse(marked(ForgeTransferShapeAudit.certify(name, tampered, game.resources())), "a changed transfer-critical body was certified");
	}

	private static boolean marked(byte[] bytes) {
		ClassNode node = new ClassNode();
		new ClassReader(bytes).accept(node, ClassReader.SKIP_CODE);
		return node.methods.stream().anyMatch(m -> m.name.equals(ForgeTransferShapeAudit.MARKER));
	}
}
