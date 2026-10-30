/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.transform;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import net.forbric.kernel.TestFixtures;
import net.forbric.kernel.mixin.NativeGameReferences;

/**
 * The scoped clear relay on the real merged {@code LivingEntity}, for the real clear-all vetoes it exists for: fabric-api's
 * {@code ServerMobEffectEvents.ALLOW_EARLY_REMOVE} and Balm's {@code MOB_EFFECT_REMOVE} both wrap vanilla's
 * {@code activeEffects.clear()} in {@code removeAllEffects}; NeoForge's body has none until the relay puts one back, after
 * which each wrap's point selects exactly that one call — and nothing is left for the kernel to re-host.
 */
class ScopedClearRelayStagedTest {
	private static final String LIVING = "net/minecraft/world/entity/LivingEntity";
	private static final Path BALM = Path.of("build/compat-inputs/sweep90/mods/balm-fabric-26.2-26.2.0.9.jar");

	private static Path merged() {
		Path jar = TestFixtures.mergedBase();
		TestFixtures.requireFile(TestFixtures.Fixture.STAGED, jar, "actual game required");
		return jar;
	}

	private static ClassNode node(byte[] bytes) {
		ClassNode node = new ClassNode();
		new ClassReader(bytes).accept(node, 0);
		return node;
	}

	private static byte[] entry(Path jar, String name) throws Exception {
		try (ZipFile zip = new ZipFile(jar.toFile())) {
			ZipEntry entry = zip.getEntry(name);
			return entry == null ? null : zip.getInputStream(entry).readAllBytes();
		}
	}

	private static ClassNode relayedLiving() throws Exception {
		Path jar = merged();
		NativeGameReferences references = new NativeGameReferences(path -> {
			try {
				return entry(jar, path);
			} catch (Exception unreadable) {
				return null;
			}
		});
		byte[] raw = entry(jar, LIVING + ".class");
		byte[] relayed = new ScopedClearRelayInjector(references::get).transform(LIVING.replace('/', '.'), raw, null);
		assertNotSame(raw, relayed, "the relay applies to the real merged body");
		return node(relayed);
	}

	private static MethodNode removeAll(ClassNode living) {
		return living.methods.stream().filter(m -> m.name.equals("removeAllEffects") && m.desc.equals("()Z")).findFirst().orElseThrow();
	}

	private static int clears(MethodNode method) {
		int n = 0;
		for (AbstractInsnNode instruction : method.instructions)
			if (instruction instanceof MethodInsnNode call && call.name.equals("clear") && call.desc.equals("()V") && call.owner.equals("java/util/Map")) n++;
		return n;
	}

	/** The clear-wrap a mixin declares on removeAllEffects: its point's target. */
	private static List<String> clearWraps(ClassNode mixin) {
		List<String> out = new ArrayList<>();
		for (MethodNode method : mixin.methods) {
			for (List<AnnotationNode> list : java.util.Arrays.asList(method.visibleAnnotations, method.invisibleAnnotations)) {
				if (list == null) continue;
				for (AnnotationNode annotation : list) {
					if (!annotation.desc.equals("Lcom/llamalad7/mixinextras/injector/wrapoperation/WrapOperation;")) continue;
					List<String> strings = flatten(annotation.values);
					if (strings.stream().anyMatch(t -> t.startsWith("removeAllEffects")) && strings.contains("Ljava/util/Map;clear()V")) out.add(method.name);
				}
			}
		}
		return out;
	}

	/** Every string in an annotation's values, nested annotations and arrays included. */
	private static List<String> flatten(Object value) {
		List<String> out = new ArrayList<>();
		if (value instanceof String text) out.add(text.replace(" ", ""));
		else if (value instanceof AnnotationNode nested) out.addAll(flatten(nested.values));
		else if (value instanceof List<?> list) for (Object element : list) out.addAll(flatten(element));
		return out;
	}

	@Test void theMergedBodyHasNoClearUntilTheRelayPutsOneBack() throws Exception {
		assertEquals(0, clears(removeAll(node(entry(merged(), LIVING + ".class")))), "NeoForge removes a computed subset instead");
		assertEquals(1, clears(removeAll(relayedLiving())), "one clear, on the view of what the carrier lets go");
	}

	@Test void fabricApisClearAllVetoHasTheOneClearToWrap() throws Exception {
		ClassNode mixin = fabricMixin("fabric-entity-events-v1", "net/fabricmc/fabric/mixin/entity/event/effect/LivingEntityMixin");
		assertEquals(List.of("allowRemoveAllEffects"), clearWraps(mixin));
		assertEquals(1, clears(removeAll(relayedLiving())));
	}

	@Test void balmsClearAllVetoHasTheOneClearToWrap() throws Exception {
		TestFixtures.require(TestFixtures.Fixture.THIRD_PARTY, Files.isRegularFile(BALM), "sweep pack absent");
		ClassNode mixin = node(entry(BALM, "net/blay09/mods/balm/fabric/internal/mixin/LivingEntityMixin.class"));
		assertEquals(List.of("clearAllEffects"), clearWraps(mixin));
		assertEquals(1, clears(removeAll(relayedLiving())));
	}

	private static ClassNode fabricMixin(String module, String name) throws Exception {
		Path api = TestFixtures.fabricApi();
		TestFixtures.requireFile(TestFixtures.Fixture.STAGED, api, "actual Fabric API fixture required");
		try (ZipFile outer = new ZipFile(api.toFile())) {
			ZipEntry jar = outer.stream().filter(e -> e.getName().startsWith("META-INF/jars/" + module + "-")).findFirst().orElseThrow();
			try (ZipInputStream inner = new ZipInputStream(new ByteArrayInputStream(outer.getInputStream(jar).readAllBytes()))) {
				for (ZipEntry e; (e = inner.getNextEntry()) != null;) if (e.getName().equals(name + ".class")) return node(inner.readAllBytes());
			}
		}
		throw new AssertionError("actual mixin not found: " + name);
	}
}
