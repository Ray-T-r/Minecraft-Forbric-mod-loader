/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.mixin.weave;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;

import net.forbric.api.Ecosystem;
import net.forbric.kernel.TestFixtures;
import net.forbric.kernel.TestFixtures.Fixture;
import net.forbric.kernel.boot.LootSourceCallbacks;
import net.forbric.kernel.boot.LootSourceContracts;
import net.forbric.kernel.mixin.NativeGameReferences;
import net.forbric.kernel.mixin.NativeReferenceSources;
import net.forbric.kernel.transform.LootTableEventBridgeInjector;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;

/**
 * The loot callback group's two pairs — the ops producer and its cleanup, the map consumer and the completion — are each
 * on one host when both callbacks bind the one same method, as Mixin binds them, in the class the source was compiled
 * against and in the current one; never because their selectors are spelled alike. fabric-loot-api's real group is
 * respelled callback by callback.
 */
class LootSourceCallbacksSpellingTest {
	private static final String SOURCE = "net/fabricmc/fabric/mixin/loot/ReloadableServerRegistriesMixin.class";
	private static final String HOST = "net/minecraft/server/ReloadableServerRegistries", LAMBDA = "lambda$scheduleRegistryLoad$0";
	private static final String BRIDGE = "net/forbric/kernel/runtime/KernelLootBridge.class";

	@AfterEach
	void reset() {
		LootSourceContracts.reset();
	}

	@Test
	void eachPairSpelledApartIsStillOneHost() throws Exception {
		try (Evidence evidence = new Evidence()) {
			MethodNode reload = evidence.nativeMethod("reload"), tags = evidence.nativeMethod(LAMBDA);
			ClassNode source = respelled(List.of("L" + HOST + ";reload"), List.of("net.minecraft.server.ReloadableServerRegistries.reload " + reload.desc),
					List.of(" " + LAMBDA + " "), List.of(LAMBDA + tags.desc, "L" + HOST + ";" + LAMBDA));
			var plan = LootSourceCallbacks.plan(source);
			assertNotNull(plan, "the group itself is spelling-blind");
			assertTrue(LootSourceContracts.offer(plan, evidence.reader(), evidence.nativeNode()), "each pair binds its one host in both classes");
		}
	}

	@Test
	void aPairWhoseCallbacksBindTwoMethodsIsNoGroup() throws Exception {
		try (Evidence evidence = new Evidence()) {
			List<ClassNode> apart = List.of(
					respelled(List.of("reload"), List.of("scheduleRegistryLoad"), List.of(LAMBDA), List.of(LAMBDA)),
					respelled(List.of("reload"), List.of("reload"), List.of(LAMBDA), List.of("lambda$scheduleRegistryLoad$1")),
					respelled(List.of("reload"), List.of("reload"), List.of("Lnet/minecraft/server/Elsewhere;" + LAMBDA), List.of(LAMBDA)),
					respelled(List.of("reload"), List.of("/^reload|scheduleRegistryLoad$/"), List.of(LAMBDA), List.of(LAMBDA)));
			for (ClassNode source : apart) {
				var plan = LootSourceCallbacks.plan(source);
				assertNotNull(plan);
				LootSourceContracts.reset();
				assertFalse(LootSourceContracts.offer(plan, evidence.reader(), evidence.nativeNode()), plan.nativeInputs().toString());
			}
		}
	}

	// ---------------------------------------------------------------------------------------------------------------

	/** The real group with each callback's {@code method} replaced: producer, cleanup, consumer, completion. */
	private static ClassNode respelled(List<String> producer, List<String> cleanup, List<String> consumer, List<String> completion) throws Exception {
		ClassNode source = source();
		for (MethodNode method : source.methods) {
			AnnotationNode injector = injector(method);
			if (injector == null) continue;
			List<String> selectors = method.desc.endsWith("Lnet/minecraft/resources/RegistryOps;") ? producer
					: method.desc.startsWith("(Ljava/util/concurrent/CompletableFuture;") ? cleanup
					: method.desc.endsWith("Ljava/util/Map;)V") ? consumer : completion;
			for (int i = 0; i + 1 < injector.values.size(); i += 2)
				if ("method".equals(injector.values.get(i))) injector.values.set(i + 1, new ArrayList<>(selectors));
		}
		return source;
	}

	private static AnnotationNode injector(MethodNode method) {
		if (method.visibleAnnotations == null) return null;
		for (AnnotationNode a : method.visibleAnnotations)
			if (a.desc.equals("Lorg/spongepowered/asm/mixin/injection/Inject;") || a.desc.endsWith("/WrapOperation;")) return a;
		return null;
	}

	private static ClassNode source() throws Exception {
		Path api = TestFixtures.fabricApi();
		TestFixtures.requireFiles(Fixture.STAGED, "raw loot callback group", api);
		try (ZipFile outer = new ZipFile(api.toFile())) {
			var module = outer.stream().filter(e -> e.getName().startsWith("META-INF/jars/fabric-loot-api-v3-")).findFirst().orElseThrow();
			try (ZipInputStream inner = new ZipInputStream(outer.getInputStream(module))) {
				for (var e = inner.getNextEntry(); e != null; e = inner.getNextEntry())
					if (e.getName().equals(SOURCE)) return node(inner.readAllBytes());
			}
		}
		throw new AssertionError("missing original loot source");
	}

	/** The staged merged base, the bridged current host, the compiled typed bridge and the native host. */
	private static final class Evidence implements AutoCloseable {
		private final ZipFile jar;
		private final byte[] host, bridge;
		private final ClassNode nativeNode;

		Evidence() throws Exception {
			Path base = TestFixtures.stagedRoot().resolve("merged-base/patched-mc-merged-26.2.jar");
			Path bridgePath = Path.of(System.getProperty("forbric.test.runtimeClasses", "build/classes/java/runtime")).resolve(BRIDGE);
			TestFixtures.requireFiles(Fixture.STAGED, "verified native loot target and compiled typed seam", base, bridgePath);
			jar = new ZipFile(base.toFile());
			host = new LootTableEventBridgeInjector().transform(HOST.replace('/', '.'), bytes(HOST + ".class"), null);
			bridge = Files.readAllBytes(bridgePath);
			// The sources a launch on this base has: its embedded index (the merged base), else the vanilla-reference
			// sidecar every launch path passes (the genuine base embeds none).
			Path sidecar = jar.getEntry("META-INF/forbric/native-reference/FABRIC/index.tsv") != null ? null
					: TestFixtures.vanillaReference(base);
			nativeNode = new NativeGameReferences(this::bytes, null, NativeReferenceSources.open(base, sidecar))
					.get(Ecosystem.FABRIC, HOST);
			assertNotNull(nativeNode);
		}

		Function<String, byte[]> reader() {
			return path -> path.equals(BRIDGE) ? bridge : path.equals(HOST + ".class") ? host : bytes(path);
		}

		ClassNode nativeNode() {
			return nativeNode;
		}

		MethodNode nativeMethod(String name) {
			return nativeNode.methods.stream().filter(m -> m.name.equals(name)).findFirst().orElseThrow();
		}

		private byte[] bytes(String path) {
			try {
				var e = jar.getEntry(path);
				return e == null ? null : jar.getInputStream(e).readAllBytes();
			} catch (Exception e) {
				throw new IllegalStateException(e);
			}
		}

		@Override
		public void close() throws Exception {
			jar.close();
		}
	}

	private static ClassNode node(byte[] bytes) {
		ClassNode n = new ClassNode();
		new ClassReader(bytes).accept(n, ClassReader.EXPAND_FRAMES);
		return n;
	}

	static byte[] write(ClassNode n) {
		ClassWriter w = new ClassWriter(0);
		n.accept(w);
		return w.toByteArray();
	}
}
