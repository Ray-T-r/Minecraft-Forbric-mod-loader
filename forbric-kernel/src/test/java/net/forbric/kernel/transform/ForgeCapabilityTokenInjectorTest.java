package net.forbric.kernel.transform;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.net.URL;
import java.net.URLClassLoader;
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
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodNode;

import net.forbric.kernel.ExpectCaught;
import net.forbric.kernel.TestFixtures;
import net.forbric.kernel.TestFixtures.Fixture;

/** The carrier's own CapabilityTokenSubclass plugin, driven by the kernel over the carrier's own token classes. */
class ForgeCapabilityTokenInjectorTest {
	private static final Path FORGE = TestFixtures.stagedRoot().resolve(TestFixtures.FORGE_CARRIER_JAR);

	@Test
	void theItemHandlerTokenGetsAGetTypeReturningItsTypeArgument() throws Exception {
		ForgeCapabilityTokenInjector injector = injector();
		String name = ForgeCapabilityTokenInjector.ITEM_HANDLER_TOKEN;
		ClassNode before = parse(bytesOf(name.replace('.', '/')));
		assertTrue(before.methods.stream().noneMatch(m -> "getType".equals(m.name)), "premise: the carrier ships the token untransformed");
		ClassNode after = parse(injector.transform(name, bytesOf(name.replace('.', '/')), null));
		MethodNode getType = after.methods.stream().filter(m -> "getType".equals(m.name) && "()Ljava/lang/String;".equals(m.desc))
				.findFirst().orElse(null);
		assertNotNull(getType, "getType() was not added");
		assertTrue((getType.access & Opcodes.ACC_PUBLIC) != 0);
		List<String> body = new ArrayList<>();
		for (AbstractInsnNode insn : getType.instructions) {
			if (insn.getOpcode() < 0) continue;
			body.add(insn instanceof LdcInsnNode ldc ? "LDC " + ldc.cst : insn.getOpcode() == Opcodes.ARETURN ? "ARETURN" : "OP" + insn.getOpcode());
		}
		assertEquals(List.of("LDC net/minecraftforge/items/IItemHandler", "ARETURN"), body,
				"the type argument of CapabilityToken<IItemHandler>, as their plugin reads it from the signature");
	}

	@Test
	void theBaseTokensGetTypeIsNoLongerFinal() throws Exception {
		ForgeCapabilityTokenInjector injector = injector();
		String name = ForgeCapabilityTokenInjector.TOKEN.replace('/', '.');
		ClassNode before = parse(bytesOf(ForgeCapabilityTokenInjector.TOKEN));
		MethodNode original = before.methods.stream().filter(m -> "getType".equals(m.name)).findFirst().orElseThrow();
		assertTrue((original.access & Opcodes.ACC_FINAL) != 0, "premise: the carrier's getType is final");
		ClassNode after = parse(injector.transform(name, bytesOf(ForgeCapabilityTokenInjector.TOKEN), null));
		MethodNode unfinal = after.methods.stream().filter(m -> "getType".equals(m.name)).findFirst().orElseThrow();
		assertEquals(0, unfinal.access & Opcodes.ACC_FINAL, "a subclass must be allowed to override it");
	}

	@ExpectCaught("could not read a class header to check for a capability token")
	@Test
	void everyOtherClassPassesByHeaderOnly() throws Exception {
		ForgeCapabilityTokenInjector injector = injector();
		String provider = "net/minecraftforge/common/capabilities/CapabilityProvider";
		byte[] bytes = bytesOf(provider);
		assertSame(bytes, injector.transform(provider.replace('/', '.'), bytes, null));
		assertTrue(ForgeCapabilityTokenInjector.isTokenOrSubclass(bytesOf(ForgeCapabilityTokenInjector.ITEM_HANDLER_TOKEN.replace('.', '/'))));
		assertTrue(!ForgeCapabilityTokenInjector.isTokenOrSubclass(new byte[] { 1, 2, 3 }), "garbage is not a token");
	}

	private static ForgeCapabilityTokenInjector injector() throws Exception {
		TestFixtures.requireFiles(Fixture.STAGED, "staged Forge carrier absent: " + FORGE, FORGE);
		URLClassLoader loader = new URLClassLoader(new URL[] { FORGE.toUri().toURL() }, ForgeCapabilityTokenInjectorTest.class.getClassLoader());
		ForgeCapabilityTokenInjector injector = ForgeCapabilityTokenInjector.create(loader);
		assertNotNull(injector, "content drift: the carrier's CapabilityTokenSubclass plugin could not be created");
		return injector;
	}

	private static ClassNode parse(byte[] bytes) {
		ClassNode node = new ClassNode();
		new ClassReader(bytes).accept(node, 0);
		return node;
	}

	private static byte[] bytesOf(String internal) throws Exception {
		try (ZipFile zip = new ZipFile(FORGE.toFile())) {
			ZipEntry entry = zip.getEntry(internal + ".class");
			assertNotNull(entry, internal);
			try (InputStream in = zip.getInputStream(entry)) {
				return in.readAllBytes();
			}
		}
	}
}
