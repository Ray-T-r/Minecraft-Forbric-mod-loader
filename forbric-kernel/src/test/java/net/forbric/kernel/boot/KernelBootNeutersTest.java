/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.boot;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import net.forbric.api.Ecosystem;
import net.forbric.api.ForeignType;
import net.forbric.kernel.transform.AnchorSet;
import net.forbric.kernel.transform.MethodBodyNeuter;
import net.forbric.kernel.TestFixtures;
import net.forbric.kernel.TestFixtures.Fixture;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;

/**
 * The neuter KernelBoot registers, as {@link KernelBoot#neuters} builds it for each side. Only NeoForge's later client
 * mod-loading calls are on it: MinecraftForge's targets (its fluid registry, its modifier pass, its mod-pack finder,
 * its own client begin) went with MinecraftForge.
 */
class KernelBootNeutersTest {
	private static final Path NEO_CARRIER = TestFixtures.stagedRoot().resolve(TestFixtures.NEO_CARRIER_JAR);

	@Test void noSideNeutersAMinecraftForgeClass() {
		for (KernelBoot.Side side : KernelBoot.Side.values()) {
			for (String owner : owners(KernelBoot.neuters(side))) {
				assertFalse(owner.startsWith("net.minecraftforge."), side + ": " + owners(KernelBoot.neuters(side)));
			}
		}
	}

	@Test void onlyTheClientNeutersAnythingAndOnlyNeoForgesClientLoader() {
		assertEquals(List.of(), owners(KernelBoot.neuters(KernelBoot.Side.SERVER)),
				"a dedicated server never runs the client loader, and nothing else is emptied there");
		assertEquals(List.of(ForeignType.CLIENT_MOD_LOADER.binary(Ecosystem.NEOFORGE)),
				owners(KernelBoot.neuters(KernelBoot.Side.CLIENT)));
	}

	/**
	 * On the client NeoForge's later mod-loading calls in Minecraft.<init> are emptied, while its no-arg begin (the
	 * redirected Main.main trigger) keeps its body.
	 */
	@Test void onTheClientNeoForgesFinishIsEmptiedAndItsBeginIsNot() throws Exception {
		String neo = ForeignType.CLIENT_MOD_LOADER.binary(Ecosystem.NEOFORGE);
		byte[] neoLoader = read(NEO_CARRIER, neo.replace('.', '/'));
		byte[] served = KernelBoot.neuters(KernelBoot.Side.CLIENT).transform(neo, neoLoader, null);
		assertEquals(List.of(Opcodes.RETURN), real(method(served, "finish", "()V")));
		assertEquals(real(method(neoLoader, "begin", "()V")), real(method(served, "begin", "()V")),
				"NeoForge's begin is the Main.main trigger the lifecycle injector redirects; its body stays whole");
		assertSame(neoLoader, KernelBoot.neuters(KernelBoot.Side.SERVER).transform(neo, neoLoader, null),
				"a dedicated server's neuter has nothing for the client loader");
	}

	private static MethodNode method(byte[] bytes, String name, String desc) {
		ClassNode node = new ClassNode();
		new ClassReader(bytes).accept(node, 0);
		return node.methods.stream().filter(m -> m.name.equals(name) && m.desc.equals(desc)).findFirst()
				.orElseThrow(() -> new AssertionError(node.name + " has no " + name + desc));
	}

	private static List<String> owners(MethodBodyNeuter neuter) {
		AnchorSet anchors = neuter.anchors();
		return anchors.anchors() == null ? List.of() : anchors.anchors().stream().map(AnchorSet.Anchor::binaryName).toList();
	}

	private static List<Integer> real(MethodNode method) {
		return java.util.Arrays.stream(method.instructions.toArray()).map(AbstractInsnNode::getOpcode).filter(op -> op >= 0).toList();
	}

	private static byte[] read(Path jar, String internalName) throws Exception {
		TestFixtures.requireFile(Fixture.STAGED, jar, jar + " absent");
		try (ZipFile zip = new ZipFile(jar.toFile())) {
			ZipEntry entry = zip.getEntry(internalName + ".class");
			assertNotNull(entry, internalName + " in " + jar);
			return zip.getInputStream(entry).readAllBytes();
		}
	}
}
