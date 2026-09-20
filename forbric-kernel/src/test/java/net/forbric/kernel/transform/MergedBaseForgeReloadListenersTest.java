package net.forbric.kernel.transform;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
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
 * MinecraftForge's {@code AddReloadListenerEvent} is never constructed on the merged base: the server reload
 * calls only NeoForge's {@code EventHooks.onResourceReload}. One owner redirect, same descriptor, sends that call
 * through {@code KernelForgeReload}, which calls NeoForge's hook and then the carrier's own Forge post.
 */
class MergedBaseForgeReloadListenersTest {
	private static final Path MERGED_BASE = Path.of(System.getProperty("user.dir"), "..", "forbric-loader", "run",
			"merged-base", "patched-mc-merged-26.2.jar").normalize();
	private static final String RSR = "net/minecraft/server/ReloadableServerResources";
	private static final String NEO_HOOKS = "net/neoforged/neoforge/event/EventHooks";
	private static final String KERNEL = "net/forbric/kernel/runtime/KernelForgeReload";
	private static final String DESC = "(L" + RSR + ";Lnet/minecraft/core/RegistryAccess;Ljava/util/Map;)Ljava/util/List;";

	@Test
	void thePremiseTheBaseCallsOnlyNeoForgesHookExactlyOnceAndNamesNoForgeReloadEvent() throws Exception {
		byte[] bytes = bytesOf(RSR);
		ClassNode base = parse(bytes);
		assertEquals(1, calls(base, NEO_HOOKS, "onResourceReload").size(),
				"the redirect assumes one NeoForge call site; a rebuilt base with another shape must be re-derived");
		String text = new String(bytes, StandardCharsets.ISO_8859_1);
		assertFalse(text.contains("net/minecraftforge/event/AddReloadListenerEvent")
				|| text.contains("net/minecraftforge/event/ForgeEventFactory"),
				"the merged base now names Forge's reload event itself — the redirect would post it twice");
	}

	@Test
	void theOneCallSiteIsRedirectedWithTheSameDescriptor() throws Exception {
		ClassNode after = parse(transform(bytesOf(RSR)));
		assertTrue(calls(after, NEO_HOOKS, "onResourceReload").isEmpty(),
				"a NeoForge-owned onResourceReload call is left — that reload would skip Forge's listeners");
		List<MethodInsnNode> kernel = calls(after, KERNEL, "onResourceReload");
		assertEquals(1, kernel.size(), "exactly one redirected call expected");
		assertEquals(Opcodes.INVOKESTATIC, kernel.getFirst().getOpcode());
		assertEquals(DESC, kernel.getFirst().desc, "same descriptor as the call it replaced, or the stack moves");
		assertFalse(kernel.getFirst().itf);
	}

	@Test
	void aSecondPassChangesNothingFurther() throws Exception {
		byte[] once = transform(bytesOf(RSR));
		assertSame(once, new ForbricMergedBaseCompatTransformer().transform(RSR.replace('/', '.'), once, null),
				"no NeoForge-owned call is left, so the second pass must find nothing");
	}

	@Test
	void anUnrelatedClassIsUntouched() throws Exception {
		byte[] other = bytesOf("net/minecraft/server/ServerFunctionLibrary");
		assertSame(other, new ForbricMergedBaseCompatTransformer().transform("net.minecraft.server.ServerFunctionLibrary", other, null));
	}

	private static byte[] transform(byte[] before) {
		return new ForbricMergedBaseCompatTransformer().transform(RSR.replace('/', '.'), before, null);
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

	private static byte[] bytesOf(String internal) throws Exception {
		assumeTrue(Files.isRegularFile(MERGED_BASE), "staged merged base absent");
		try (ZipFile zip = new ZipFile(MERGED_BASE.toFile())) {
			ZipEntry entry = zip.getEntry(internal + ".class");
			assertNotNull(entry, internal);
			try (InputStream in = zip.getInputStream(entry)) {
				return in.readAllBytes();
			}
		}
	}
}
