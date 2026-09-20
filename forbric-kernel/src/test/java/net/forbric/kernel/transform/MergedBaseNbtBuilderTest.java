package net.forbric.kernel.transform;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;

/**
 * {@code CompoundTag.builder()} — the static every {@code IForgeBlockPos.toCompoundTag()} and
 * {@code ForgeHooks.createEmptyStructure} links against, and which the merged base does not declare.
 *
 * <p>Genuine Forge's body {@code new}s {@code CompoundTag$1}, an anonymous class the byte merge could not carry
 * (the merged {@code CompoundTag$1} is a different class). The repair therefore emits {@code INBTBuilder.nbt()}'s
 * own four instructions instead. What is pinned here: the premise (the base still lacks it), the exact body, that
 * every carrier call site now resolves, that the carrier type the body {@code new}s still has its public no-arg
 * constructor, and idempotence.
 */
class MergedBaseNbtBuilderTest {
	private static final Path MERGED_BASE = Path.of(System.getProperty("user.dir"), "..", "forbric-loader", "run",
			"merged-base", "patched-mc-merged-26.2.jar").normalize();
	private static final Path FORGE_RUNTIME = forgeRuntime();
	private static final String COMPOUND_TAG = "net/minecraft/nbt/CompoundTag";
	private static final String BUILDER = "net/minecraftforge/common/util/INBTBuilder$Builder";
	private static final String DESC = "()L" + BUILDER + ";";

	@Test
	void theBaseStillLacksIt() throws Exception {
		ClassNode base = parse(bytesOf(MERGED_BASE, COMPOUND_TAG));
		assertTrue(declared(base, "builder", DESC) == null,
				"the merged CompoundTag now declares builder() — the repair is redundant, re-derive rather than keep it");
	}

	@Test
	void builderIsPublicStaticWithTheDescriptorTheCarrierCallsAndTheCarriersOwnBody() throws Exception {
		ClassNode after = parse(transform(COMPOUND_TAG));
		MethodNode builder = declared(after, "builder", DESC);
		assertNotNull(builder, "builder() was not added");
		assertEquals(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, builder.access & (Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC),
				"the carrier's call sites are invokestatic on a public method");

		List<String> body = new ArrayList<>();
		for (AbstractInsnNode insn : builder.instructions) {
			if (insn.getOpcode() < 0) continue;
			if (insn instanceof TypeInsnNode type) body.add("NEW " + type.desc);
			else if (insn instanceof MethodInsnNode call) body.add("INVOKESPECIAL " + call.owner + "." + call.name + call.desc);
			else if (insn instanceof InsnNode plain) body.add(plain.getOpcode() == Opcodes.DUP ? "DUP"
					: plain.getOpcode() == Opcodes.ARETURN ? "ARETURN" : "OP" + plain.getOpcode());
			else body.add("?" + insn.getOpcode());
		}
		assertEquals(List.of("NEW " + BUILDER, "DUP", "INVOKESPECIAL " + BUILDER + ".<init>()V", "ARETURN"), body,
				"the body must be exactly INBTBuilder.nbt()'s own instructions — nothing invented");
	}

	@Test
	void everyCarrierCallSiteNowResolves() throws Exception {
		assumeTrue(Files.isRegularFile(FORGE_RUNTIME), "staged Forge carrier absent: " + FORGE_RUNTIME);
		ClassNode after = parse(transform(COMPOUND_TAG));
		List<String> unresolved = new ArrayList<>();
		int sites = 0;
		for (String caller : List.of("net/minecraftforge/common/extensions/IForgeBlockPos", "net/minecraftforge/common/ForgeHooks")) {
			ClassNode node = parse(bytesOf(FORGE_RUNTIME, caller));
			for (MethodNode method : node.methods) {
				for (AbstractInsnNode insn : method.instructions) {
					if (!(insn instanceof MethodInsnNode call) || !COMPOUND_TAG.equals(call.owner)) continue;
					sites++;
					MethodNode target = declared(after, call.name, call.desc);
					boolean ok = target != null
							&& ((call.getOpcode() == Opcodes.INVOKESTATIC) == ((target.access & Opcodes.ACC_STATIC) != 0));
					if (!ok) unresolved.add(caller + "." + method.name + " -> " + call.name + call.desc);
				}
			}
		}
		assertTrue(sites >= 3, "the premise: the carrier calls CompoundTag.builder() from at least three sites, saw " + sites);
		assertEquals(List.of(), unresolved, "carrier call sites on CompoundTag that still do not link");
	}

	@Test
	void theCarrierTypeItNewsStillHasAPublicNoArgConstructor() throws Exception {
		assumeTrue(Files.isRegularFile(FORGE_RUNTIME), "staged Forge carrier absent: " + FORGE_RUNTIME);
		ClassNode builder = parse(bytesOf(FORGE_RUNTIME, BUILDER));
		MethodNode ctor = declared(builder, "<init>", "()V");
		assertNotNull(ctor, BUILDER + " lost its no-arg constructor — the emitted body would NoSuchMethodError");
		assertTrue((ctor.access & Opcodes.ACC_PUBLIC) != 0, "the constructor must be public for another class to new it");
	}

	@Test
	void aSecondPassAddsNothing() throws Exception {
		byte[] once = transform(COMPOUND_TAG);
		assertSame(once, new ForbricMergedBaseCompatTransformer().transform(COMPOUND_TAG.replace('/', '.'), once, null),
				"a class that already declares builder() must be returned untouched");
	}

	@Test
	void anUnrelatedClassIsUntouched() throws Exception {
		byte[] other = bytesOf(MERGED_BASE, "net/minecraft/nbt/StringTag");
		assertSame(other, new ForbricMergedBaseCompatTransformer().transform("net.minecraft.nbt.StringTag", other, null));
	}

	private static byte[] transform(String internal) throws Exception {
		byte[] before = bytesOf(MERGED_BASE, internal);
		return new ForbricMergedBaseCompatTransformer().transform(internal.replace('/', '.'), before, null);
	}

	private static MethodNode declared(ClassNode node, String name, String desc) {
		for (MethodNode method : node.methods) if (method.name.equals(name) && method.desc.equals(desc)) return method;
		return null;
	}

	private static ClassNode parse(byte[] bytes) {
		ClassNode node = new ClassNode();
		new ClassReader(bytes).accept(node, 0);
		return node;
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
		Path root = old == null || old.isBlank() ? Path.of(System.getProperty("user.dir"), "..", "forbric-loader") : Path.of(old);
		return root.resolve("run/forge-runtime/forge-runtime.jar").normalize();
	}
}
