package net.forbric.kernel.transform;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.MethodNode;

/**
 * The seven "exclusive-added field … left null" entries of merge-conflicts.txt, and why six of them are left
 * alone: nothing in the base reads them. That is pinned here as reader COUNTS over every class in the merged jar,
 * so a rebuilt base that turns one of those traps live (a new reader) fails this test instead of NPE-ing in a
 * player's world — re-read the field, do not re-bless the count.
 */
class MergedBaseLeftNullFieldsTest {
	private static final Path MERGED_BASE = Path.of(System.getenv().getOrDefault("FORBRIC_OLD", System.getProperty("user.dir") + "/../forbric-loader"), "run",
			"merged-base", "patched-mc-merged-26.2.jar").normalize();
	private static final Path FORGE_RUNTIME = forgeRuntime();

	/** owner#field → expected GETFIELD readers anywhere in the merged base. */
	private static final Map<String, Integer> PINNED = Map.ofEntries(
			Map.entry("net/minecraft/client/gui/screens/TitleScreen#modUpdateNotification", 0),
			Map.entry("net/minecraft/client/multiplayer/ServerData#forgeData", 0),
			Map.entry("net/minecraft/world/entity/projectile/AbstractArrow#ignoredEntities", 0),
			Map.entry("net/minecraft/client/renderer/block/dispatch/WeightedVariants#first", 1),
			Map.entry("net/minecraft/world/inventory/Slot#background", 0),
			Map.entry("net/minecraft/client/data/models/ModelProvider$BlockStateGeneratorCollector#known", 0),
			Map.entry("net/minecraft/client/data/models/ModelProvider$ItemInfoCollector#known", 0));

	@Test
	void theReaderCountsOverTheWholeBaseAreExactlyThePinnedOnes() throws Exception {
		assumeTrue(Files.isRegularFile(MERGED_BASE), "staged merged base absent");
		Map<String, Integer> counts = new LinkedHashMap<>();
		for (String key : PINNED.keySet()) counts.put(key, 0);
		try (ZipFile zip = new ZipFile(MERGED_BASE.toFile())) {
			Enumeration<? extends ZipEntry> entries = zip.entries();
			while (entries.hasMoreElements()) {
				ZipEntry entry = entries.nextElement();
				if (!entry.getName().endsWith(".class")) continue;
				try (InputStream in = zip.getInputStream(entry)) {
					new ClassReader(in).accept(new ClassVisitor(Opcodes.ASM9) {
						@Override
						public MethodVisitor visitMethod(int access, String name, String desc, String sig, String[] ex) {
							return new MethodVisitor(Opcodes.ASM9) {
								@Override
								public void visitFieldInsn(int opcode, String owner, String field, String fdesc) {
									if (opcode != Opcodes.GETFIELD) return;
									String key = owner + "#" + field;
									if (counts.containsKey(key)) counts.merge(key, 1, Integer::sum);
								}
							};
						}
					}, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
				}
			}
		}
		assertEquals(new LinkedHashMap<>(PINNED), counts,
				"a reader count changed: a left-null field grew a reader (a live NPE trap — give it its own repair) or "
						+ "lost one (re-examine whether its repair is still needed)");
	}

	@Test
	void everyFieldWithAReaderIsWrittenByItsOwnerAfterTheTransformer() throws Exception {
		for (Map.Entry<String, Integer> pin : PINNED.entrySet()) {
			if (pin.getValue() == 0) continue;
			String owner = pin.getKey().substring(0, pin.getKey().indexOf('#'));
			String field = pin.getKey().substring(pin.getKey().indexOf('#') + 1);
			byte[] after = new ForbricMergedBaseCompatTransformer().transform(owner.replace('/', '.'), bytesOf(MERGED_BASE, owner), null);
			ClassNode node = new ClassNode();
			new ClassReader(after).accept(node, 0);
			boolean written = false;
			for (MethodNode method : node.methods) {
				for (AbstractInsnNode insn : method.instructions) {
					if (insn instanceof FieldInsnNode f && f.getOpcode() == Opcodes.PUTFIELD && owner.equals(f.owner) && field.equals(f.name)) written = true;
				}
			}
			assertTrue(written, pin.getKey() + " has a reader in the base and no writer after the transformer ran");
		}
	}

	/** forgeData is left alone for a reason that lives in the carrier, so the reason is pinned in bytecode too. */
	@Test
	void forgeDataIsWrittenByForgesOwnPingHookAndNullGuardedByItsOnlyReader() throws Exception {
		assumeTrue(Files.isRegularFile(FORGE_RUNTIME), "staged Forge carrier absent: " + FORGE_RUNTIME);
		ClassNode hooks = new ClassNode();
		new ClassReader(bytesOf(FORGE_RUNTIME, "net/minecraftforge/client/ForgeHooksClient")).accept(hooks, 0);
		int writes = 0;
		MethodNode draw = null;
		for (MethodNode method : hooks.methods) {
			if ("drawForgePingInfo".equals(method.name)) draw = method;
			for (AbstractInsnNode insn : method.instructions) {
				if (insn instanceof FieldInsnNode f && f.getOpcode() == Opcodes.PUTFIELD
						&& "net/minecraft/client/multiplayer/ServerData".equals(f.owner) && "forgeData".equals(f.name)) writes++;
			}
		}
		assertTrue(writes >= 1, "ForgeHooksClient no longer writes ServerData.forgeData — the field would stay null for good");
		assertNotNull(draw, "drawForgePingInfo is gone — find the new reader and re-check its guard");
		AbstractInsnNode first = draw.instructions.getFirst();
		while (first != null && first.getOpcode() < 0) first = first.getNext();
		AbstractInsnNode load = first, get = nextReal(load), guard = nextReal(get);
		assertTrue(get instanceof FieldInsnNode f && "forgeData".equals(f.name) && f.getOpcode() == Opcodes.GETFIELD,
				"the reader's first real instructions must be the field read");
		assertTrue(guard instanceof JumpInsnNode jump && jump.getOpcode() == Opcodes.IFNONNULL,
				"the reader must null-guard the field — that guard is why the kernel leaves it alone");
	}

	private static AbstractInsnNode nextReal(AbstractInsnNode insn) {
		AbstractInsnNode next = insn.getNext();
		while (next != null && next.getOpcode() < 0) next = next.getNext();
		return next;
	}

	private static byte[] bytesOf(Path jar, String internal) throws Exception {
		assumeTrue(Files.isRegularFile(jar), "staged artifact absent: " + jar);
		try (ZipFile zip = new ZipFile(jar.toFile())) {
			ZipEntry entry = zip.getEntry(internal + ".class");
			assertNotNull(entry, internal + " not in " + jar.getFileName());
			try (InputStream in = zip.getInputStream(entry)) {
				return in.readAllBytes();
			}
		}
	}

	private static Path forgeRuntime() {
		String old = System.getenv("FORBRIC_OLD");
		Path root = old == null || old.isBlank() ? Path.of(System.getenv().getOrDefault("FORBRIC_OLD", System.getProperty("user.dir") + "/../forbric-loader")) : Path.of(old);
		return root.resolve("run/forge-runtime/forge-runtime.jar").normalize();
	}
}
