package net.forbric.kernel.transform;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.BasicVerifier;

import net.forbric.kernel.TestFixtures;
import net.forbric.kernel.TestFixtures.Fixture;

import static org.junit.jupiter.api.Assertions.*;

class TransferTransactionHooksTest {
	private static Path neo() {
		return TestFixtures.stagedRoot().resolve(TestFixtures.NEO_CARRIER_JAR);
	}
	private static Path fabric() throws Exception {
		Path modules = Path.of("build/transfer-api-compile");
		// Extracted from Fabric API by extractTransferApis, which runs only where the game side is built.
		TestFixtures.require(Fixture.GAME_SIDE, Files.isDirectory(modules), "the game side's transfer API modules are not extracted: " + modules);
		try (var paths = Files.list(modules)) { return paths.filter(p -> p.getFileName().toString().startsWith("fabric-transfer-api-")).findFirst().orElseThrow(); }
	}
	private static byte[] bytes(Path jar, String name) throws Exception {
		return TestFixtures.requireEntry(NativeCoremodParityTest.fixtureOf(jar), jar, name.replace('.', '/') + ".class");
	}
	private static ClassNode node(byte[] bytes) { ClassNode node = new ClassNode(); new ClassReader(bytes).accept(node, 0); return node; }
	/** Class bytes by internal name from the NeoForge runtime and Fabric's transfer module, null when in neither. */
	private static java.util.function.Function<String, byte[]> classBytes() throws Exception {
		List<Path> jars = List.of(neo(), fabric());
		return name -> {
			for (Path jar : jars) {
				try (var zip = new java.util.zip.ZipFile(jar.toFile())) {
					var entry = zip.getEntry(name + ".class");
					if (entry != null) try (var in = zip.getInputStream(entry)) { return in.readAllBytes(); }
				} catch (java.io.IOException unreadable) {
					return null;
				}
			}
			return null;
		};
	}
	@Test void fabricsEngineIsFoundFromItsPublicTransaction() throws Exception {
		var engine = TransferTransactionHooks.fabricEngine(classBytes());
		assertNotNull(engine);
		// The names are this build's, read here only to show the search lands where the engine is.
		assertEquals("net/fabricmc/fabric/impl/transfer/transaction/TransactionManagerImpl", engine.manager());
		assertEquals("net/fabricmc/fabric/impl/transfer/transaction/TransactionManagerImpl$TransactionImpl", engine.transaction());
		assertNull(TransferTransactionHooks.fabricEngine(name -> null));
	}
	@Test void allNativeTransactionHooksHaveValidBytecodeAndAreIdempotent() throws Exception {
		var transformer = new TransferTransactionHooks(classBytes());
		List<String> names = new java.util.ArrayList<>(List.of(TransferTransactionHooks.NEO, TransferTransactionHooks.NEO_MANAGER));
		names.addAll(transformer.engine().classes());
		assertEquals(4, names.size());
		for (String name : names) {
			assertTrue(transformer.targets(name), name);
			byte[] original = bytes(name.startsWith("net.neoforged.") ? neo() : fabric(), name);
			byte[] changed = transformer.transform(name, original, null);
			assertNotSame(original, changed); assertSame(changed, transformer.transform(name, changed, null));
			ClassNode parsed = node(changed);
			assertTrue(parsed.methods.stream().anyMatch(m -> m.name.equals(TransferTransactionHooks.MARKER)));
			for (var method : parsed.methods) if (method.name.equals("close") || method.name.startsWith("forbric$")) {
				new Analyzer<>(new BasicVerifier()).analyze(parsed.name, method);
			}
		}
		assertFalse(transformer.targets("net.minecraft.world.level.block.entity.HopperBlockEntity"));
	}
	/** The wrapper validates with the native close's own leading checks; a close that opens with none is not hooked. */
	@Test void aCloseThatDoesNotValidateFirstRefusesTheHookInsteadOfClaimingCompatibility() throws Exception {
		var transformer = new TransferTransactionHooks(classBytes());
		ClassNode changed = node(bytes(neo(), TransferTransactionHooks.NEO));
		for (var method : changed.methods) if (method.name.equals("close") && method.desc.equals("(Z)V")) {
			var first = method.instructions.getFirst();
			while (!(first instanceof org.objectweb.asm.tree.MethodInsnNode call && call.name.equals("validateOpen"))) {
				var next = first.getNext();
				if (first.getOpcode() >= 0) method.instructions.remove(first);
				first = next;
			}
			method.instructions.remove(first);
		}
		ClassWriter writer = new ClassWriter(0); changed.accept(writer); byte[] drift = writer.toByteArray();
		assertSame(drift, transformer.transform(TransferTransactionHooks.NEO, drift, null));
	}
	/** A leading call that writes state is not a check: copying it before beforeClose would run it twice. */
	@Test void aLeadingCallThatWritesStateIsNotCopiedAsAValidation() throws Exception {
		var transformer = new TransferTransactionHooks(classBytes());
		ClassNode changed = node(bytes(neo(), TransferTransactionHooks.NEO));
		for (var method : changed.methods) if (method.name.equals("validateOpen")) {
			var write = new org.objectweb.asm.tree.InsnList();
			write.add(new org.objectweb.asm.tree.VarInsnNode(org.objectweb.asm.Opcodes.ALOAD, 0));
			write.add(new org.objectweb.asm.tree.InsnNode(org.objectweb.asm.Opcodes.ICONST_1));
			write.add(new org.objectweb.asm.tree.FieldInsnNode(org.objectweb.asm.Opcodes.PUTFIELD, changed.name, "open", "Z"));
			method.instructions.insert(write);
		}
		ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS); changed.accept(writer); byte[] drift = writer.toByteArray();
		byte[] hooked = transformer.transform(TransferTransactionHooks.NEO, drift, null);
		assertNotSame(drift, hooked, "the manager's own check still leads the close");
		MethodNode wrapper = node(hooked).methods.stream().filter(m -> m.name.equals("close") && m.desc.equals("(Z)V")).findFirst().orElseThrow();
		for (var insn : wrapper.instructions) {
			assertFalse(insn instanceof org.objectweb.asm.tree.MethodInsnNode call && call.name.equals("validateOpen"), "a writing call was copied");
		}
	}

	@Test void capabilityFallbackChangesOnlyTheFinalAbsentResult() throws Exception {
		byte[] original = bytes(neo(), TransferCapabilityFallback.TARGET);
		var transformer = new TransferCapabilityFallback(); byte[] changed = transformer.transform(TransferCapabilityFallback.TARGET, original, null);
		assertNotSame(original, changed); assertSame(changed, transformer.transform(TransferCapabilityFallback.TARGET, changed, null));
		ClassNode parsed = node(changed);
		assertTrue(parsed.methods.stream().anyMatch(m -> m.name.equals("forbric$transferFallback")));
		int hooks = 0;
		for (var method : parsed.methods) if (method.name.equals("getCapability")) {
			new Analyzer<>(new BasicVerifier()).analyze(parsed.name, method);
			for (var instruction : method.instructions) if (instruction instanceof MethodInsnNode call && call.name.equals("neoFallback")) hooks++;
		}
		assertEquals(1, hooks);
	}
}
