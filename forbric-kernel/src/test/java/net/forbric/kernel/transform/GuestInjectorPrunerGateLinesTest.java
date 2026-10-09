/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.transform;

import static org.junit.jupiter.api.Assertions.*;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Enumeration;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import net.forbric.kernel.GateLogContract;
import net.forbric.kernel.TestFixtures;
import net.forbric.kernel.TestFixtures.Fixture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.tree.ClassNode;

/**
 * gate-m9 (and m14, m51) read the pruner's line: how many injectors went, from which mixin. The generic rewrite of the
 * pruner kept the pruning — the same two of fabric-model-loading-api's ten, the same five of fabric-item-api's six —
 * but reworded the line, and both gate-m9 checks went red on a build whose models and tooltips were fine. This greps,
 * with the gate's own patterns, what the pruner prints on the real fabric-api mixins, and on a renamed copy: the line
 * reports facts about whatever mixin it pruned, and the gate's pattern accepts only the one it is about.
 */
@ResourceLock("system-properties")
class GuestInjectorPrunerGateLinesTest {
	private static final Path GATE = Path.of("run/gate-m9-client.sh");
	private static final Path MERGED_BASE = TestFixtures.stagedRoot().resolve("merged-base/patched-mc-merged-26.2.jar");
	private static final Path CLIENT_MODS = Path.of(System.getProperty("user.dir"), "run", "client-kernel", "mods").normalize();
	private static final String MODEL_MANAGER_MIXIN = "net.fabricmc.fabric.mixin.client.model.loading.ModelManagerMixin";
	private static final String ITEM_STACK_MIXIN = "net.fabricmc.fabric.mixin.item.ItemStackMixin";
	private static final String MODELS_CHECK = "ModelManagerMixin trimmed, not pinned";
	private static final String TOOLTIPS_CHECK = "fabric-item-api's tooltip injectors are pruned";

	@TempDir Path root;

	@AfterEach void reset() {
		System.clearProperty(GuestInjectorPruner.PROPERTY);
		net.forbric.api.CompatibilityFindings.reset();
	}

	@Test void theRealModelLoadingMixinPrintsTheLineTheGateReads() throws Exception {
		byte[] mixin = realMixin("fabric-model-loading-api-v1", MODEL_MANAGER_MIXIN);
		String[] out = new String[1];
		assertNotSame(mixin, GateLogContract.capture(() -> pruner().transform(MODEL_MANAGER_MIXIN, mixin, null), out));
		assertEquals(1, grep(MODELS_CHECK, out[0]), out[0]);
		assertTrue(out[0].contains("the other 8 injector(s) apply as written"), out[0]);
	}

	@Test void theRealItemMixinPrintsTheLineTheGateReads() throws Exception {
		byte[] mixin = realMixin("fabric-item-api-v1", ITEM_STACK_MIXIN);
		String[] out = new String[1];
		assertNotSame(mixin, GateLogContract.capture(() -> pruner().transform(ITEM_STACK_MIXIN, mixin, null), out));
		assertEquals(1, grep(TOOLTIPS_CHECK, out[0]), out[0]);
		assertTrue(out[0].contains("the other 1 injector(s) apply as written"), out[0]);
	}

	/** The same protocol under a name no mod has: pruned and reported by its own name, which the gate does not accept. */
	@Test void aRenamedCopyIsReportedByItsOwnNameAndNotAsTheGatesMixin() throws Exception {
		ClassNode node = read(realMixin("fabric-model-loading-api-v1", MODEL_MANAGER_MIXIN));
		node.name = "unknown/loading/ReaderHooks";
		ClassWriter writer = new ClassWriter(0);
		node.accept(writer);
		byte[] renamed = writer.toByteArray();
		String[] out = new String[1];
		assertNotSame(renamed, GateLogContract.capture(() -> pruner().transform("unknown.loading.ReaderHooks", renamed, null), out));
		assertTrue(out[0].contains("pruned 2 injector(s) from unknown.loading.ReaderHooks"), out[0]);
		assertEquals(0, grep(MODELS_CHECK, out[0]), out[0]);
	}

	@Test void switchedOffNothingIsPrunedAndTheGateLineIsAbsent() throws Exception {
		System.setProperty(GuestInjectorPruner.PROPERTY, "off");
		byte[] mixin = realMixin("fabric-model-loading-api-v1", MODEL_MANAGER_MIXIN);
		String[] out = new String[1];
		assertSame(mixin, GateLogContract.capture(() -> pruner().transform(MODEL_MANAGER_MIXIN, mixin, null), out));
		assertEquals(0, grep(MODELS_CHECK, out[0]), out[0]);
	}

	// ---------------------------------------------------------------------------------------------------------------

	private int grep(String check, String text) throws Exception {
		return GateLogContract.count(root, GateLogContract.pattern(GATE, check), text);
	}

	private static byte[] realMixin(String module, String mixin) throws Exception {
		Path fabricApi = fabricApiJar();
		TestFixtures.require(Fixture.THIRD_PARTY, fabricApi != null, "fabric-api jar absent from run/client-kernel/mods");
		String entry = mixin.replace('.', '/') + ".class";
		byte[] bytes = readFromNestedJar(fabricApi, module, entry);
		TestFixtures.require(Fixture.THIRD_PARTY, bytes != null, mixin + " absent from the nested " + module + " module");
		// The pruner proves each protocol against the merged target it lands in, so without the base it rightly prunes nothing.
		TestFixtures.require(Fixture.STAGED, Files.isRegularFile(MERGED_BASE), "staged merged base absent: " + MERGED_BASE);
		net.forbric.kernel.mixin.MixinStubRebind.noteEcosystem(mixin.replace('.', '/'), net.forbric.api.Ecosystem.FABRIC, module + ".mixins.json");
		return bytes;
	}

	/** The pruner reading the staged merged base, NeoForge's carrier appenders as the kernel splices them, and fabric-api's providers. */
	private static GuestInjectorPruner pruner() {
		return new GuestInjectorPruner(owner -> {
			try {
				if (owner.equals("net/neoforged/neoforge/common/tooltip/ItemTooltipHandler")) {
					byte[] bytes = readFromJar(TestFixtures.stagedRoot().resolve("neoforge-runtime/neoforge-runtime.jar"), owner + ".class");
					return bytes == null ? null : read(new NeoTooltipAppendersInjector().transform(owner.replace('/', '.'), bytes, null));
				}
				if (owner.equals("net/fabricmc/fabric/impl/item/ItemComponentTooltipProviderRegistryImpl")) {
					byte[] bytes = readFromNestedJar(fabricApiJar(), "fabric-item-api-v1", owner + ".class");
					return bytes == null ? null : read(bytes);
				}
				byte[] bytes = readFromJar(MERGED_BASE, owner + ".class");
				return bytes == null ? null : read(bytes);
			} catch (Exception unavailable) {
				return null;
			}
		});
	}

	private static ClassNode read(byte[] bytes) {
		ClassNode node = new ClassNode();
		new ClassReader(bytes).accept(node, 0);
		return node;
	}

	private static Path fabricApiJar() throws Exception {
		if (Files.isDirectory(CLIENT_MODS)) try (var files = Files.list(CLIENT_MODS)) {
			Path found = files.filter(p -> p.getFileName().toString().startsWith("fabric-api-")).findFirst().orElse(null);
			if (found != null) return found;
		}
		Path pinned = TestFixtures.fabricApi();
		return Files.isRegularFile(pinned) ? pinned : null;
	}

	private static byte[] readFromJar(Path jar, String entry) throws Exception {
		if (!Files.isRegularFile(jar)) return null;
		try (ZipFile zip = new ZipFile(jar.toFile())) {
			ZipEntry found = zip.getEntry(entry);
			if (found == null) return null;
			try (InputStream in = zip.getInputStream(found)) {
				return in.readAllBytes();
			}
		}
	}

	private static byte[] readFromNestedJar(Path outer, String modulePrefix, String entry) throws Exception {
		try (ZipFile zip = new ZipFile(outer.toFile())) {
			for (Enumeration<? extends ZipEntry> e = zip.entries(); e.hasMoreElements(); ) {
				ZipEntry nested = e.nextElement();
				if (!nested.getName().startsWith("META-INF/jars/" + modulePrefix)) continue;
				Path tmp = Files.createTempFile("forbric-nested", ".jar");
				try (InputStream in = zip.getInputStream(nested)) {
					Files.write(tmp, in.readAllBytes());
				}
				try {
					byte[] bytes = readFromJar(tmp, entry);
					if (bytes != null) return bytes;
				} finally {
					Files.deleteIfExists(tmp);
				}
			}
		}
		return null;
	}
}
