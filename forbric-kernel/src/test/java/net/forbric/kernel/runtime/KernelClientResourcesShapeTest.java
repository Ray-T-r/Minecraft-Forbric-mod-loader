package net.forbric.kernel.runtime;

import static org.junit.jupiter.api.Assertions.assertNotNull;
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
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;

/**
 * The client resource preload reaches a PRIVATE field of a vanilla class by name, and builds a vanilla class by
 * constructor descriptor. Both are strings; nothing relates them to the merged base at compile time.
 *
 * <p>Its only failure mode is silence: a renamed field makes {@code preload} return -2, the manager stays empty,
 * and every mod reading its own asset from client setup goes back to an empty Optional — which Xaero's World Map
 * turns into "Xaero's World Map has crashed!", naming the mod. So the names are checked against the real merged
 * base here rather than discovered on a player's client.
 */
class KernelClientResourcesShapeTest {
	private static final Path RUNTIME =
			Path.of(System.getProperty("forbric.testRuntimeClasses", "build/classes/java/runtime"));
	private static final Path MERGED = Path.of(System.getenv().getOrDefault("FORBRIC_OLD", "../forbric-loader"),
			"run/merged-base/patched-mc-merged-26.2.jar");
	private static final String RELOADABLE = "net/minecraft/server/packs/resources/ReloadableResourceManager";
	private static final String MULTI = "net/minecraft/server/packs/resources/MultiPackResourceManager";

	@Test
	void everyVanillaNameThePreloadSpellsIsOneTheMergedBaseDeclares() throws Exception {
		assumeTrue(Files.isRegularFile(MERGED), "merged base absent");
		Path compiled = RUNTIME.resolve("net/forbric/kernel/runtime/KernelClientResources.class");
		assumeTrue(Files.isRegularFile(compiled), "game-side class not compiled: " + compiled);

		ClassNode ours = parse(Files.readAllBytes(compiled));
		List<String> strings = new ArrayList<>();
		MethodInsnNode multiCtor = null;
		for (var method : ours.methods) {
			for (AbstractInsnNode insn : method.instructions) {
				if (insn instanceof LdcInsnNode ldc && ldc.cst instanceof String s) strings.add(s);
				if (insn instanceof MethodInsnNode call && MULTI.equals(call.owner) && "<init>".equals(call.name)) {
					multiCtor = call;
				}
			}
		}

		ClassNode reloadable = parse(bytesOf(RELOADABLE));
		List<String> fields = reloadable.fields.stream().map(f -> f.name).toList();
		List<String> named = strings.stream().filter(fields::contains).toList();
		assertTrue(named.size() == 1,
				"the preload must name exactly one ReloadableResourceManager field; it names " + named
						+ " out of " + fields + ". A rename there makes the preload a silent no-op.");

		assertNotNull(multiCtor, "the preload must construct a MultiPackResourceManager");
		String ctorDesc = multiCtor.desc;
		ClassNode multi = parse(bytesOf(MULTI));
		assertTrue(multi.methods.stream()
						.anyMatch(m -> "<init>".equals(m.name) && m.desc.equals(ctorDesc)),
				"MultiPackResourceManager has no constructor " + ctorDesc
						+ "; the merged base declares "
						+ multi.methods.stream().filter(m -> "<init>".equals(m.name)).map(m -> m.desc).toList());
	}

	private static ClassNode parse(byte[] bytes) {
		ClassNode node = new ClassNode();
		new ClassReader(bytes).accept(node, 0);
		return node;
	}

	private static byte[] bytesOf(String internal) throws Exception {
		try (ZipFile zip = new ZipFile(MERGED.toFile())) {
			ZipEntry entry = zip.getEntry(internal + ".class");
			assertNotNull(entry, internal + " is not in the merged base");
			try (InputStream in = zip.getInputStream(entry)) {
				return in.readAllBytes();
			}
		}
	}
}
