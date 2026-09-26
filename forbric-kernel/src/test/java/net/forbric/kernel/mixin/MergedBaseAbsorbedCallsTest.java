package net.forbric.kernel.mixin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;

import net.forbric.api.Ecosystem;

/**
 * Each reviewed row of {@link MergedBaseAbsorbedCalls}, pinned to the staged jars: the shape its {@code because} argues
 * about. A base or carrier rebuild that changes any of it turns the row red instead of letting it move an injector
 * on an argument that no longer holds.
 */
class MergedBaseAbsorbedCallsTest {
	private static final String OLD = System.getenv().getOrDefault("FORBRIC_OLD", "../forbric-loader");
	private static final Path MERGED = Path.of(OLD, "run/merged-base/patched-mc-merged-26.2.jar");
	private static final Path NEO_RT = Path.of(OLD, "run/neoforge-runtime/neoforge-runtime.jar");
	private static final Path VANILLA = Path.of(System.getProperty("user.home"), "Library/Application Support/minecraft/versions/26.2/26.2.jar");
	private static final Path FORGE = Path.of(OLD, "run/forge-patched/patched-mc-forge-26.2.jar");
	private static final Path NEOFORGE = Path.of(OLD, "run/neoforge-patched/patched-mc-neoforge-26.2.jar");

	@Test void everyReviewedRowStillDescribesTheStagedJars() throws Exception {
		for (Path jar : List.of(MERGED, NEO_RT, VANILLA, FORGE, NEOFORGE)) Assumptions.assumeTrue(Files.isRegularFile(jar), jar + " required");
		for (MergedBaseAbsorbedCalls.Absorbed row : MergedBaseAbsorbedCalls.KNOWN) {
			String where = row.owner() + "#" + row.method();
			MethodNode merged = method(MERGED, row.owner(), row.method());
			assertNotNull(merged, where + " is not in the merged base");
			assertEquals(0, CarrierHelpers.occurrences(merged, row.member()), where + ": the merged method makes the call itself again");
			assertTrue(CarrierHelpers.edges(merged, row.hook()).contains(CarrierHelpers.Shape.TAIL),
					where + ": the hook call is no longer the merged method's one last act");

			MixinFit.Member hook = MixinFit.parseMember(row.hook());
			MethodNode body = method(NEO_RT, hook.owner(), hook.name() + hook.desc());
			assertNotNull(body, row.hook() + " is not in the carrier");
			assertEquals(1, CarrierHelpers.occurrences(body, row.member()), row.hook() + " no longer makes " + row.member() + " once");

			for (Ecosystem ecosystem : Ecosystem.values()) {
				Path reference = switch (ecosystem) { case FABRIC -> VANILLA; case FORGE -> FORGE; case NEOFORGE -> NEOFORGE; };
				MethodNode original = method(reference, row.owner(), row.method());
				boolean inline = original != null && CarrierHelpers.edges(original, row.member()).contains(CarrierHelpers.Shape.TAIL);
				assertEquals(row.ecosystems().contains(ecosystem), inline, where + ": " + ecosystem
						+ "'s own jar " + (inline ? "makes" : "does not make") + " the call as the method's last act");
			}
		}
	}

	private static MethodNode method(Path jar, String owner, String nameAndDesc) throws Exception {
		try (ZipFile zip = new ZipFile(jar.toFile())) {
			ZipEntry entry = zip.getEntry(owner + ".class");
			if (entry == null) return null;
			ClassNode node = new ClassNode();
			try (InputStream in = zip.getInputStream(entry)) {
				new ClassReader(in.readAllBytes()).accept(node, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
			}
			int paren = nameAndDesc.indexOf('(');
			return CarrierHelpers.declared(node, nameAndDesc.substring(0, paren), nameAndDesc.substring(paren));
		}
	}
}
