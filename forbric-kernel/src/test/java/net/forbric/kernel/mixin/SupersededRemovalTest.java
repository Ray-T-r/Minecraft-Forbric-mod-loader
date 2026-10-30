/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.mixin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;

import net.forbric.api.CompatibilityFinding;
import net.forbric.api.CompatibilityFindings;
import net.forbric.api.Ecosystem;
import net.forbric.api.ModCatalog;
import net.forbric.kernel.transform.LootTableEventBridgeInjector;

/**
 * A mixin the kernel leaves out of its config before Mixin reads it, because a repair claims its whole job, costs the
 * mod nothing until the mixin's target is defined. Its row is a note until the repair is seen (resolved) or its witness
 * is defined without it (confirmed); a fabric-api module was named "did not finish loading" on every boot and title
 * screen and resolved only when the first world's data loaded.
 */
@ResourceLock("ModCatalog")
@ResourceLock(Resources.SYSTEM_PROPERTIES)
class SupersededRemovalTest {
	private static final String CONFIG = "pour.mixins.json", MOD = "pourmod", PACKAGE = "fixture.relay.mixin",
			MIXIN = PACKAGE + ".PourMixin", CONDITIONAL_OPS = "net.neoforged.neoforge.common.conditions.ConditionalOps";
	private List<ModCatalog.Entry> previous;

	@BeforeEach
	void publish() {
		CompatibilityFindings.reset();
		MixinCompatibility.reset();
		previous = ModCatalog.everything();
		MixinConfigOwners.publish(List.of(new MixinConfigOwners.Owned(CONFIG, MOD, Ecosystem.FABRIC)));
		ModCatalog.publish(List.of(new ModCatalog.Entry(Ecosystem.FABRIC, MOD, "Pour", "1", "", List.of(), "pour.jar", "", "")));
		// What config discovery notes for a mixin whose shape the decode-guard repair's claim fits (SupersededMixinsTest
		// reads the shapes); the name is no mod's.
		SupersededMixins.claimForTests(MIXIN, "resource-conditions");
	}

	@AfterEach
	void forget() {
		CompatibilityFindings.reset();
		MixinCompatibility.reset();
		MixinConfigOwners.reset();
		ModCatalog.publish(previous);
	}

	private static void suppressByName(String config, String pkg, String entry) {
		String json = "{\"required\":true,\"package\":\"" + pkg + "\",\"mixins\":[\"" + entry + "\"]}";
		KernelGuestMixinAdapter.reportNamedSuppressions(config, json.getBytes(StandardCharsets.UTF_8), Map.of(entry, "test"),
				path -> null);
	}

	/** ConditionalOps' codec factory, with or without the kernel's wrap on its one exit (the repair's witness). */
	private static byte[] conditionalOps(boolean wrapped) {
		org.objectweb.asm.ClassWriter writer = new org.objectweb.asm.ClassWriter(0);
		writer.visit(org.objectweb.asm.Opcodes.V17, org.objectweb.asm.Opcodes.ACC_PUBLIC, CONDITIONAL_OPS.replace('.', '/'), null,
				"java/lang/Object", null);
		var factory = writer.visitMethod(org.objectweb.asm.Opcodes.ACC_PUBLIC | org.objectweb.asm.Opcodes.ACC_STATIC,
				"createConditionalCodecWithConditions",
				"(Lcom/mojang/serialization/Codec;Ljava/lang/String;)Lcom/mojang/serialization/Codec;", null, null);
		factory.visitCode();
		factory.visitVarInsn(org.objectweb.asm.Opcodes.ALOAD, 0);
		if (wrapped) factory.visitMethodInsn(org.objectweb.asm.Opcodes.INVOKESTATIC, "net/forbric/kernel/runtime/KernelFabricConditions",
				"alsoAskFabric", "(Lcom/mojang/serialization/Codec;)Lcom/mojang/serialization/Codec;", false);
		factory.visitInsn(org.objectweb.asm.Opcodes.ARETURN);
		factory.visitMaxs(1, 2);
		factory.visitEnd();
		writer.visitEnd();
		return writer.toByteArray();
	}

	private static CompatibilityFinding row(String config, String mixin) {
		String id = MixinCompatibility.id(config, mixin);
		return CompatibilityFindings.all().stream().filter(f -> f.id().equals(id)).findFirst().orElse(null);
	}

	@Test
	void aClaimedRemovalIsANoteUntilTheRepairIsSeenThenResolved() {
		suppressByName(CONFIG, PACKAGE, "PourMixin");
		assertEquals(CompatibilityFinding.Confidence.SUSPECTED, row(CONFIG, MIXIN).confidence());
		assertTrue(ModCatalog.failures().isEmpty(), "nothing is lost before the target is defined: " + ModCatalog.failures());
		SupersededMixins.observeDefinition(CONDITIONAL_OPS, conditionalOps(true));
		assertEquals(CompatibilityFinding.Confidence.RESOLVED, row(CONFIG, MIXIN).confidence());
		assertTrue(ModCatalog.failures().isEmpty());
	}

	@Test
	void aWitnessDefinedWithoutTheRepairConfirmsTheLoss() {
		suppressByName(CONFIG, PACKAGE, "PourMixin");
		SupersededMixins.observeDefinition(CONDITIONAL_OPS, conditionalOps(false));
		CompatibilityFinding row = row(CONFIG, MIXIN);
		assertEquals(CompatibilityFinding.Confidence.CONFIRMED, row.confidence());
		assertTrue(row.evidence().stream().anyMatch(e -> e.contains("was defined without it")), row.evidence().toString());
		assertEquals(1, ModCatalog.failures().size());
		assertEquals(MOD, ModCatalog.failures().get(0).modId());
		// A later proof still resolves it, as for a mixin that failed while its target was defined.
		SupersededMixins.observeDefinition(CONDITIONAL_OPS, conditionalOps(true));
		assertEquals(CompatibilityFinding.Confidence.RESOLVED, row(CONFIG, MIXIN).confidence());
	}

	@Test
	void aRepairProvedFirstResolvesTheRemovalOnArrival() {
		SupersededMixins.observeDefinition(CONDITIONAL_OPS, conditionalOps(true));
		suppressByName(CONFIG, PACKAGE, "PourMixin");
		assertEquals(CompatibilityFinding.Confidence.RESOLVED, row(CONFIG, MIXIN).confidence());
		assertTrue(ModCatalog.failures().isEmpty());
	}

	@Test
	void anUnclaimedRemovalAndASwitchedOffTableStayOrdinaryLosses() {
		suppressByName(CONFIG, PACKAGE, "OtherMixin");
		assertEquals(CompatibilityFinding.Confidence.CONFIRMED, row(CONFIG, PACKAGE + ".OtherMixin").confidence());
		CompatibilityFindings.reset();
		String before = System.setProperty(SupersededMixins.PROPERTY, "off");
		try {
			suppressByName(CONFIG, PACKAGE, "PourMixin");
			assertEquals(CompatibilityFinding.Confidence.CONFIRMED, row(CONFIG, MIXIN).confidence());
			assertEquals(1, ModCatalog.failures().size());
		} finally {
			if (before == null) System.clearProperty(SupersededMixins.PROPERTY);
			else System.setProperty(SupersededMixins.PROPERTY, before);
		}
	}

	/**
	 * The multi-class loot proof, on the real fabric-loot-api-v3 mixin (a test sample) and the staged merged host: its
	 * removal stays a note while the proof's classes arrive one by one, is resolved when the last one does, and is
	 * confirmed as soon as one is defined so that the proof can never complete.
	 */
	@Test
	void theLootRemovalWaitsForEveryWitnessAndIsConfirmedOnlyWhenOneCanNeverMatch() throws Exception {
		var source = new org.objectweb.asm.tree.ClassNode();
		java.nio.file.Path api = java.nio.file.Path.of(System.getProperty("forbric.fabricApi", net.forbric.kernel.TestFixtures.fabricApi().toString()));
		net.forbric.kernel.TestFixtures.requireFiles(net.forbric.kernel.TestFixtures.Fixture.STAGED, "current raw loot source", api);
		try (var outer = new java.util.zip.ZipFile(api.toFile())) {
			var module = outer.stream().filter(e -> e.getName().startsWith("META-INF/jars/fabric-loot-api-v3-")).findFirst().orElseThrow();
			try (var inner = new java.util.zip.ZipInputStream(outer.getInputStream(module))) {
				for (var entry = inner.getNextEntry(); entry != null; entry = inner.getNextEntry()) {
					if (entry.getName().equals("net/fabricmc/fabric/mixin/loot/ReloadableServerRegistriesMixin.class")) {
						new org.objectweb.asm.ClassReader(inner.readAllBytes()).accept(source, org.objectweb.asm.ClassReader.EXPAND_FRAMES);
						break;
					}
				}
			}
		}
		var plan = net.forbric.kernel.boot.LootSourceCallbacks.plan(source);
		assertNotNull(plan);
		java.nio.file.Path base = java.nio.file.Path.of(System.getProperty("forbric.predicateBase",
				net.forbric.kernel.TestFixtures.stagedBase().toString()));
		net.forbric.kernel.TestFixtures.requireFiles(net.forbric.kernel.TestFixtures.Fixture.STAGED, "native-indexed actual loot source proof", base);
		String config = "loot.mixins.json", pkg = plan.sourceName().substring(0, plan.sourceName().lastIndexOf('.'));
		String entry = plan.sourceName().substring(pkg.length() + 1);
		MixinConfigOwners.publish(List.of(new MixinConfigOwners.Owned(config, MOD, Ecosystem.FABRIC)));
		for (boolean refuted : new boolean[] {false, true}) {
			try (var jar = new java.util.zip.ZipFile(base.toFile());
					var loader = new net.forbric.kernel.classloading.ForbricClassLoader(new java.net.URL[0], getClass().getClassLoader())) {
				String targetPath = plan.targetName().replace('.', '/') + ".class";
				byte[] raw = jar.getInputStream(jar.getEntry(targetPath)).readAllBytes();
				byte[] host = new LootTableEventBridgeInjector().transform(plan.targetName(), raw, null);
				java.nio.file.Path bridgePath = java.nio.file.Path.of("build/classes/java/runtime/net/forbric/kernel/runtime/KernelLootBridge.class");
				if (!java.nio.file.Files.exists(bridgePath)) bridgePath = java.nio.file.Path.of("forbric-kernel").resolve(bridgePath);
				byte[] bridge = java.nio.file.Files.readAllBytes(bridgePath);
				CompatibilityFindings.reset();
				SupersededMixins.reset();
				net.forbric.kernel.boot.LootSourceCallbacks.bind(loader);
				try {
					assertTrue(net.forbric.kernel.boot.LootSourceCallbacks.offer(loader, source, path -> {
						if (path.equals(targetPath)) return host;
						if (path.equals("net/forbric/kernel/runtime/KernelLootBridge.class")) return bridge;
						try {
							var found = jar.getEntry(path);
							return found == null ? null : jar.getInputStream(found).readAllBytes();
						} catch (java.io.IOException failure) {
							throw new IllegalStateException(failure);
						}
					}));
					suppressByName(config, pkg, entry);
					assertEquals(CompatibilityFinding.Confidence.SUSPECTED, row(config, plan.sourceName()).confidence());
					assertTrue(ModCatalog.failures().isEmpty(), "boot and title screen: nothing lost yet");
					if (refuted) {
						// The host defined without the bridge: this loader can never prove the repair.
						SupersededMixins.observeDefinition(loader, plan.targetName(), raw);
						assertTrue(net.forbric.kernel.boot.LootSourceCallbacks.disproved(plan.sourceName()));
						assertEquals(CompatibilityFinding.Confidence.CONFIRMED, row(config, plan.sourceName()).confidence());
						assertEquals(1, ModCatalog.failures().size());
						continue;
					}
					SupersededMixins.observeDefinition(loader, plan.targetName(), host);
					assertFalse(net.forbric.kernel.boot.LootSourceCallbacks.disproved(plan.sourceName()), "the host alone proves nothing either way");
					assertEquals(CompatibilityFinding.Confidence.SUSPECTED, row(config, plan.sourceName()).confidence());
					SupersededMixins.observeDefinition(loader, "net.forbric.kernel.runtime.KernelLootBridge", bridge);
					SupersededMixins.observeDefinition(loader, plan.helperName().replace('/', '.'), plan.helperBytes());
					assertEquals(CompatibilityFinding.Confidence.SUSPECTED, row(config, plan.sourceName()).confidence());
					SupersededMixins.observeDefinition(loader, "net.minecraft.world.level.storage.loot.LootDataType",
							jar.getInputStream(jar.getEntry("net/minecraft/world/level/storage/loot/LootDataType.class")).readAllBytes());
					SupersededMixins.observeDefinition(loader, "net.minecraft.core.registries.Registries",
							jar.getInputStream(jar.getEntry("net/minecraft/core/registries/Registries.class")).readAllBytes());
					assertEquals(CompatibilityFinding.Confidence.RESOLVED, row(config, plan.sourceName()).confidence());
					assertTrue(ModCatalog.failures().isEmpty());
				} finally {
					SupersededMixins.reset();
				}
			}
		}
	}
}
