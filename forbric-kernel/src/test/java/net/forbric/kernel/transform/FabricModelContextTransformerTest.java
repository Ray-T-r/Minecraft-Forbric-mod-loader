package net.forbric.kernel.transform;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.*;
import java.util.zip.*;
import net.forbric.kernel.TestFixtures;
import net.forbric.kernel.TestFixtures.Fixture;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.BasicVerifier;

@org.junit.jupiter.api.parallel.ResourceLock(org.junit.jupiter.api.parallel.Resources.SYSTEM_PROPERTIES)
class FabricModelContextTransformerTest {
	private static byte[] api() throws Exception {
		Path jar = TestFixtures.fabricApi();
		TestFixtures.require(Fixture.THIRD_PARTY, Files.isRegularFile(jar), "actual Fabric API fixture required");
		try (ZipFile zip = new ZipFile(jar.toFile())) {
			var entry = zip.stream().filter(e -> e.getName().startsWith("META-INF/jars/fabric-renderer-api-v1-")).findFirst().orElseThrow();
			try (ZipInputStream nested = new ZipInputStream(zip.getInputStream(entry))) {
				for (ZipEntry e; (e = nested.getNextEntry()) != null;)
					if (e.getName().equals(FabricModelContextTransformer.TARGET + ".class")) return nested.readAllBytes();
			}
		}
		throw new AssertionError("Fabric's actual model interface missing");
	}

	private static byte[] game(String name) throws Exception {
		Path jar = TestFixtures.stagedRoot().resolve(name.equals(FabricModelContextTransformer.MODEL + ".class") ? TestFixtures.BASE_JAR : TestFixtures.NEO_CARRIER_JAR);
		return TestFixtures.requireEntry(Fixture.STAGED, jar, name);
	}

	private static byte[] resolve(String name) {
		try { return game(name); } catch (Exception e) { throw new AssertionError(e); }
	}

	@Test void defaultEmissionPassesTheRealLevelPositionAndStateToNativeModels() throws Exception {
		byte[] original = api();
		var transformer = new FabricModelContextTransformer(FabricModelContextTransformerTest::resolve);
		byte[] transformed = transformer.transform(FabricModelContextTransformer.TARGET, original, null);
		assertNotSame(original, transformed);
		ClassNode node = new ClassNode();
		new ClassReader(transformed).accept(node, 0);
		MethodNode emit = node.methods.stream().filter(m -> m.name.equals("emitQuads")).findFirst().orElseThrow();
		int calls = 0;
		for (var i : emit.instructions) if (i instanceof MethodInsnNode c && c.name.equals("collectParts")) {
			assertEquals("(" + FabricModelContextTransformer.CONTEXT + FabricModelContextTransformer.PARTS, c.desc);
			var cursor = previous(c);
			assertInstanceOf(VarInsnNode.class, cursor); // parts list
			for (int slot : new int[] {5, 4, 3, 2}) {
				cursor = previous(cursor);
				assertEquals(slot, ((VarInsnNode) cursor).var);
			}
			calls++;
		}
		assertEquals(1, calls);
		new Analyzer<>(new BasicVerifier()).analyze(node.name, emit);
		assertSame(transformed, transformer.transform(FabricModelContextTransformer.TARGET, transformed, null));
	}

	@Test void missingNativeContextContractDoesNotInventACall() throws Exception {
		byte[] original = api();
		ClassNode node = new ClassNode();
		new ClassReader(game(FabricModelContextTransformer.EXTENSION + ".class")).accept(node, 0);
		node.methods.removeIf(m -> m.name.equals("collectParts") && m.desc.startsWith("(" + FabricModelContextTransformer.CONTEXT));
		ClassWriter writer = new ClassWriter(0); node.accept(writer);
		assertSame(original, new FabricModelContextTransformer(n -> n.equals(FabricModelContextTransformer.EXTENSION + ".class") ? writer.toByteArray() : resolve(n)).transform(FabricModelContextTransformer.TARGET, original, null));
	}

	private static AbstractInsnNode previous(AbstractInsnNode node) {
		do { node = node.getPrevious(); } while (node.getOpcode() < 0);
		return node;
	}
}
