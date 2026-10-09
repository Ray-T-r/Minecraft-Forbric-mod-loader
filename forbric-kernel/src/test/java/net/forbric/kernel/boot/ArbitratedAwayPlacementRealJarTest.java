/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.boot;

import static org.junit.jupiter.api.Assertions.*;

import java.io.StringReader;
import java.lang.reflect.Field;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.ModInitializer;
import net.forbric.api.CompatibilityFindings;
import net.forbric.api.ModCatalog;
import net.forbric.kernel.TestFixtures;
import net.forbric.kernel.TestFixtures.Fixture;
import net.forbric.kernel.classloading.ForbricClassLoader;
import net.forbric.kernel.fabric.DispatchFixtures;
import net.forbric.kernel.fabric.EntrypointDispatchScan.Phase;
import net.forbric.kernel.fabric.FabricModMetadataParser;
import net.forbric.kernel.fabric.KernelFabricLoader;
import net.forbric.kernel.fabric.KernelModContainer;

/**
 * A real library that dispatches its custom keys from {@code main} and {@code client}, not {@code preLaunch}: MRU's
 * Fabric build calls {@code invokeEntrypoints("mru", CommonRegisterEvent.class, ...)} from its {@code onInitialize}.
 * Its NeoForge build is not among the fixtures, so the winner here is a NeoForge-shaped jar of the same id; the losing
 * build, its types and its dispatch are MRU's own bytes. The two consumers are made up and named so that one sorts
 * before "mru" and one after it.
 */
@ResourceLock("ModCatalog")
@ResourceLock("system-properties")
@ResourceLock("KernelFabricEcosystem")
public class ArbitratedAwayPlacementRealJarTest {
	private static final Path MRU = Path.of("build/sweep100-mac-network/mods/mru-1.0.41+26.2-fabric.jar");
	private static final String COMMON = "cc/cassian/mru/events/CommonRegisterEvent";
	private static final String JOURNAL = Type.getInternalName(Journal.class);

	@TempDir Path dir;

	/** What the made-up consumers write to; on the test's class path, so the game loader delegates to it. */
	public static final class Journal {
		static final List<String> LINES = Collections.synchronizedList(new ArrayList<>());

		public static void add(String line) {
			LINES.add(line);
		}
	}

	@BeforeEach @AfterEach void reset() {
		ArbitratedAwayDispatchers.reset();
		CompatibilityFindings.reset();
		ModCatalog.publish(List.of());
		Journal.LINES.clear();
		System.clearProperty(FabricLoadOrder.SWITCH);
	}

	private Path mru() {
		TestFixtures.require(Fixture.THIRD_PARTY, Files.isRegularFile(MRU), "sweep100 MRU Fabric build absent: " + MRU);
		return MRU;
	}

	private Path neoForgeShapedMru() throws Exception {
		return DispatchFixtures.write(dir.resolve("mru-neoforge.jar"), Map.of(
				"META-INF/neoforge.mods.toml", DispatchFixtures.neoForgeToml("mru"),
				"org/example/mruneo/MruMod", DispatchFixtures.plain("org/example/mruneo/MruMod")));
	}

	@Test void itsOwnCodeSaysTheKeysAreDispatchedFromMainAndClient() throws Exception {
		var orphans = ArbitratedAwayDispatchers.derive(Set.of(mru()), Map.of("mru", neoForgeShapedMru()), Set.of("main", "mru", "mru_client"));

		assertEquals(List.of("mru", "mru_client"), orphans.stream().map(ArbitratedAwayDispatchers.Orphan::key).sorted().toList());
		var common = orphans.stream().filter(o -> o.key().equals("mru")).findFirst().orElseThrow();
		assertEquals(COMMON, common.dispatch().type());
		assertEquals("onInitialize", common.dispatch().method());
		assertEquals(Set.of(Phase.MAIN), common.dispatch().phases());
		var client = orphans.stream().filter(o -> o.key().equals("mru_client")).findFirst().orElseThrow();
		assertEquals(Set.of(Phase.CLIENT), client.dispatch().phases());
		assertTrue(CompatibilityFindings.all().isEmpty(), CompatibilityFindings.all()::toString);
	}

	/**
	 * In Fabric Loader's order "glint" runs before MRU's own {@code main} and "tidal" after it: tidal's
	 * {@code onInitialize} finds its {@code mru} entrypoint already called, as it would on Fabric.
	 */
	@Test void theConsumerAfterTheLibraryFindsItsEntrypointCalledByItsOwnMain() throws Exception {
		Path mru = mru(), winner = neoForgeShapedMru();
		Path consumers = DispatchFixtures.write(dir.resolve("consumers.jar"), Map.of(
				"org/glint/GlintMain", main("org/glint/GlintMain", "glint main"),
				"org/glint/GlintMru", hook("org/glint/GlintMru", "glint mru"),
				"net/tidal/TidalMain", main("net/tidal/TidalMain", "tidal main"),
				"net/tidal/TidalMru", hook("net/tidal/TidalMru", "tidal mru")));

		try (ForbricClassLoader game = new ForbricClassLoader(new URL[] {winner.toUri().toURL(), consumers.toUri().toURL()},
				getClass().getClassLoader())) {
			game.setRescueJars(List.of(mru.toUri().toURL()));
			var constructor = KernelFabricLoader.class.getDeclaredConstructor(EnvType.class, Path.class, Path.class, String[].class, String.class);
			constructor.setAccessible(true);
			KernelFabricLoader fabric = constructor.newInstance(EnvType.CLIENT, dir, dir.resolve("config"), new String[0], "26.2");
			fabric.setGameLoader(game);
			for (String id : List.of("tidal", "glint")) {
				String owner = id.equals("tidal") ? "net.tidal.Tidal" : "org.glint.Glint";
				String json = "{\"schemaVersion\":1,\"id\":\"" + id + "\",\"version\":\"1\",\"entrypoints\":{\"main\":[\"" + owner
						+ "Main\"],\"mru\":[\"" + owner + "Mru\"]},\"depends\":{\"mru\":\"*\"}}";
				fabric.register(new KernelModContainer(FabricModMetadataParser.read(new StringReader(json)), null, null));
			}
			fabric.reorder(FabricLoadOrder.byModId(new ArrayList<>(fabric.getAllMods()), mod -> mod.getMetadata().getId()));
			fabric.freeze();

			Field active = KernelFabricEcosystem.class.getDeclaredField("loader");
			active.setAccessible(true);
			Object previous = active.get(null);
			String shim = System.getProperty(KernelForeignShimContext.SWITCH);
			try {
				active.set(null, fabric);
				System.setProperty(KernelForeignShimContext.SWITCH, "off");
				ArbitratedAwayDispatchers.record(new DuplicateModArbiter.Decision(Set.of(mru), Map.of("mru", winner), List.of(), Set.of(mru)),
						KernelFabricEcosystem.declaredEntrypointKeys(), KernelFabricEcosystem.knownModIds());
				assertEquals(2, KernelFabricEcosystem.invokePhase("main", ModInitializer.class, ModInitializer::onInitialize, Phase.MAIN));
			} finally {
				active.set(null, previous);
				if (shim == null) System.clearProperty(KernelForeignShimContext.SWITCH); else System.setProperty(KernelForeignShimContext.SWITCH, shim);
			}
			assertEquals(List.of("glint main", "glint mru", "tidal mru", "tidal main"), Journal.LINES);
			assertNull(game.getResource(COMMON + ".class"), "no loaded jar has MRU's type; the losing build lends it");
		}
	}

	/** A {@code ModInitializer} that writes {@code line} to the journal. */
	private static byte[] main(String name, String line) {
		return journaling(name, "net/fabricmc/api/ModInitializer", "onInitialize", line);
	}

	/** An implementation of MRU's own {@code CommonRegisterEvent} that writes {@code line} to the journal. */
	private static byte[] hook(String name, String line) {
		return journaling(name, COMMON, "onInitialize", line);
	}

	private static byte[] journaling(String name, String type, String method, String line) {
		ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
		writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, name, null, "java/lang/Object", new String[] {type});
		MethodVisitor init = writer.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
		init.visitCode();
		init.visitVarInsn(Opcodes.ALOAD, 0);
		init.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
		init.visitInsn(Opcodes.RETURN);
		init.visitMaxs(0, 0);
		init.visitEnd();
		MethodVisitor body = writer.visitMethod(Opcodes.ACC_PUBLIC, method, "()V", null, null);
		body.visitCode();
		body.visitLdcInsn(line);
		body.visitMethodInsn(Opcodes.INVOKESTATIC, JOURNAL, "add", "(Ljava/lang/String;)V", false);
		body.visitInsn(Opcodes.RETURN);
		body.visitMaxs(0, 0);
		body.visitEnd();
		writer.visitEnd();
		return writer.toByteArray();
	}
}
