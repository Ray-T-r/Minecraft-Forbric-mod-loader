package net.forbric.kernel.transform;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

/**
 * W5: the carrier's {@code AddReloadListenerEvent.getConditionContext()} invokes a Forge-typed accessor on
 * {@code ReloadableServerResources} that the merged class does not declare. The repair edits the CARRIER class,
 * turning that one invokevirtual into {@code invokestatic KernelForgeConditions.contextOf(RSR)}.
 */
class MergedBaseForgeReloadContextTest {
	private static final Path MERGED_BASE = Path.of(System.getProperty("user.dir"), "..", "forbric-loader", "run",
			"merged-base", "patched-mc-merged-26.2.jar").normalize();
	private static final Path FORGE_RUNTIME = forgeRuntime();
	private static final String EVENT = "net/minecraftforge/event/AddReloadListenerEvent";
	private static final String RSR = "net/minecraft/server/ReloadableServerResources";
	private static final String FORGE_CONTEXT = "Lnet/minecraftforge/common/crafting/conditions/ICondition$IContext;";
	private static final String NEO_CONTEXT = "Lnet/neoforged/neoforge/common/conditions/ICondition$IContext;";
	private static final String KERNEL = "net/forbric/kernel/runtime/KernelForgeConditions";

	@Test
	void thePremiseTheCarrierAsksForAForgeTypedContextTheMergedClassDoesNotDeclare() throws Exception {
		ClassNode event = parse(bytesOf(FORGE_RUNTIME, EVENT));
		List<MethodInsnNode> asks = calls(event, RSR, "getConditionContext");
		assertEquals(1, asks.size(), "one Forge-typed ask expected in the carrier");
		assertEquals("()" + FORGE_CONTEXT, asks.getFirst().desc);

		ClassNode resources = parse(bytesOf(MERGED_BASE, RSR));
		List<String> declared = new ArrayList<>();
		for (MethodNode method : resources.methods) if ("getConditionContext".equals(method.name)) declared.add(method.desc);
		assertEquals(List.of("()" + NEO_CONTEXT), declared,
				"the merged ReloadableServerResources declares only NeoForge's accessor; if it ever declares Forge's, "
						+ "this repair is dead weight");
	}

	@Test
	void theOneAskIsRedirectedToTheKernelAdapterWithTheReceiverAsArgument() throws Exception {
		ClassNode after = parse(transform(bytesOf(FORGE_RUNTIME, EVENT)));
		for (MethodNode method : after.methods) {
			for (AbstractInsnNode insn : method.instructions) {
				if (insn instanceof MethodInsnNode call && RSR.equals(call.owner)) {
					assertFalse(call.desc.contains("net/minecraftforge/"),
							"a Forge-typed call on ReloadableServerResources survives: " + call.name + call.desc);
				}
			}
		}
		List<MethodInsnNode> kernel = calls(after, KERNEL, "contextOf");
		assertEquals(1, kernel.size());
		assertEquals(Opcodes.INVOKESTATIC, kernel.getFirst().getOpcode());
		assertEquals("(L" + RSR + ";)" + FORGE_CONTEXT, kernel.getFirst().desc,
				"receiver in, Forge-typed context out — the same stack as the call it replaced");
		assertFalse(kernel.getFirst().itf);
	}

	@Test
	void aSecondPassChangesNothingFurther() throws Exception {
		byte[] once = transform(bytesOf(FORGE_RUNTIME, EVENT));
		assertSame(once, new ForbricMergedBaseCompatTransformer().transform(EVENT.replace('/', '.'), once, null));
	}

	@Test
	void theAdapterExistsWithThatExactDescriptorInTheCompiledRuntime() throws Exception {
		Path compiled = Path.of(System.getProperty("forbric.testRuntimeClasses", "build/classes/java/runtime"),
				KERNEL + ".class");
		assumeTrue(Files.isRegularFile(compiled), "runtime helper not compiled");
		ClassNode node = parse(Files.readAllBytes(compiled));
		boolean found = false;
		for (MethodNode method : node.methods) {
			if ("contextOf".equals(method.name) && ("(L" + RSR + ";)" + FORGE_CONTEXT).equals(method.desc)) {
				found = true;
				assertTrue((method.access & Opcodes.ACC_STATIC) != 0 && (method.access & Opcodes.ACC_PUBLIC) != 0);
			}
		}
		assertTrue(found, "KernelForgeConditions.contextOf(ReloadableServerResources) with the redirected descriptor");
	}

	private static byte[] transform(byte[] before) {
		return new ForbricMergedBaseCompatTransformer().transform(EVENT.replace('/', '.'), before, null);
	}

	private static List<MethodInsnNode> calls(ClassNode node, String owner, String name) {
		List<MethodInsnNode> found = new ArrayList<>();
		for (MethodNode method : node.methods) {
			for (AbstractInsnNode insn : method.instructions) {
				if (insn instanceof MethodInsnNode call && owner.equals(call.owner) && name.equals(call.name)) found.add(call);
			}
		}
		return found;
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
