/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.mixin.weave;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarInputStream;
import java.util.jar.JarOutputStream;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LocalVariableNode;
import org.objectweb.asm.tree.MethodNode;

import net.fabricmc.api.EnvType;
import net.forbric.api.Ecosystem;
import net.forbric.kernel.transform.LocalRanges;

/**
 * LocalRanges through KernelBoot's own pre-Mixin chain and the real Mixin, with names of no mod: a carrier's early return
 * spliced between a local's store and the start of its variable-table range (a merged {@code explode}'s cancelled event).
 * A {@code RETURN} handler that asks for the local by name runs at every exit natively; on the merged body it runs at the
 * carrier's early return too once the range starts at the store again, and not without it.
 */
class LocalRangesWeaveTest {
	private static final String CONFIG = "ranges.mixins.json";
	private static final String OWNER = "fixture/ranges/Forge";

	@TempDir static Path work;
	private static Path fixture;
	private static WeaveHarness.Result nativeRun, on, off;

	private static final String NATIVE_FORGE = """
			package fixture.ranges;
			public class Forge {
			 public static int exits; public static String seen = "";
			 public void blast(boolean veto){ StringBuilder made = new StringBuilder("x"); made.append('y'); }
			 static boolean cancel(StringBuilder made){ return made.length() > 0; }
			 public static String probe(){ Forge f = new Forge(); f.blast(true); f.blast(false); return "exits=" + exits + " seen=" + seen; }
			}
			""";

	private static final String MERGED_FORGE = """
			package fixture.ranges;
			public class Forge {
			 public static int exits; public static String seen = "";
			 public void blast(boolean veto){ StringBuilder made = new StringBuilder("x"); if (veto && cancel(made)) return; made.append('y'); }
			 static boolean cancel(StringBuilder made){ return made.length() > 0; }
			 public static String probe(){ Forge f = new Forge(); f.blast(true); f.blast(false); return "exits=" + exits + " seen=" + seen; }
			}
			""";

	private static final String MIXIN = """
			package fixture.ranges.mixin;
			import fixture.ranges.Forge;
			import org.spongepowered.asm.mixin.Mixin;
			import org.spongepowered.asm.mixin.injection.*;
			import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
			import com.llamalad7.mixinextras.sugar.Local;
			@Mixin(Forge.class)
			public class ForgeMixin {
			 @Inject(method = "blast", at = @At("RETURN"), require = 0)
			 private void leaving(boolean veto, CallbackInfo ci, @Local(name = "made") StringBuilder made) { Forge.exits++; Forge.seen += made; }
			}
			""";

	@BeforeAll static void weaveEachWay() throws Exception {
		Path json = source(CONFIG, "{\"required\":true,\"compatibilityLevel\":\"JAVA_21\",\"package\":\"fixture.ranges.mixin\","
				+ "\"mixins\":[\"ForgeMixin\"],\"injectors\":{\"defaultRequire\":0},\"mixinextras\":{\"minVersion\":\"0.5.4\"}}");
		Path mixin = source("src/mixin/ForgeMixin.java", MIXIN);
		Path nativeJar = WeaveHarness.fixture(work, "ranges-native", List.of(source("src/native/Forge.java", NATIVE_FORGE), mixin),
				Map.of(CONFIG, json), List.of("-g"));
		Path merged = WeaveHarness.fixture(work, "ranges-merged", List.of(source("src/merged/Forge.java", MERGED_FORGE), mixin),
				Map.of(CONFIG, json), List.of("-g"));
		spliceRangeAfterEarlyReturn(merged);
		fixture = NativeWeaveReferences.with(work, merged, Map.of(OWNER, NativeWeaveReferences.classes(nativeJar).get(OWNER)));
		nativeRun = run("native", nativeJar, Map.of());
		on = run("on", fixture, Map.of());
		off = run("off", fixture, Map.of(LocalRanges.PROPERTY, "off"));
	}

	@Test void nativelyTheHandlerRunsAtEveryExit() {
		assertTrue(nativeRun.printed(WeaveHarnessMain.DONE + " exits=2 seen=xyxy"), nativeRun.describe());
	}

	/** The early return sees the local before the append, as a handler at that exit would on a body compiled whole. */
	@Test void withTheRangeRepairedTheCarriersEarlyReturnRunsItToo() throws Exception {
		assertTrue(on.printed(WeaveHarnessMain.DONE + " exits=2 seen=xxy"), on.describe());
		WeaveHarness.assertWovenAndVerified(on, OWNER, fixture);
	}

	@Test void switchedOffTheEarlyReturnIsMissed() {
		// MixinExtras finds no "made" in the table at the early return and leaves that exit without the handler, silently.
		assertTrue(off.printed(WeaveHarnessMain.DONE + " exits=1 seen=xy"), off.describe());
	}

	/** Moves {@code made}'s range start in {@code blast} to the label the carrier's check jumps to — after its early return. */
	private static void spliceRangeAfterEarlyReturn(Path jar) throws Exception {
		Map<String, byte[]> entries = new LinkedHashMap<>();
		try (JarInputStream in = new JarInputStream(Files.newInputStream(jar))) {
			for (JarEntry entry; (entry = in.getNextJarEntry()) != null;) entries.put(entry.getName(), in.readAllBytes());
		}
		ClassNode node = new ClassNode();
		new ClassReader(entries.get(OWNER + ".class")).accept(node, 0);
		boolean moved = false;
		for (MethodNode method : node.methods) {
			if (!method.name.equals("blast")) continue;
			JumpInsnNode check = null;
			for (AbstractInsnNode instruction : method.instructions) {
				if (instruction instanceof JumpInsnNode jump && jump.getOpcode() == Opcodes.IFEQ) check = jump;
			}
			assertNotNull(check, "blast has no veto check");
			for (LocalVariableNode local : method.localVariables) {
				if (local.name.equals("made")) { local.start = check.label; moved = true; }
			}
		}
		assertTrue(moved);
		ClassWriter writer = new ClassWriter(0);
		node.accept(writer);
		entries.put(OWNER + ".class", writer.toByteArray());
		try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(jar))) {
			for (var entry : entries.entrySet()) {
				out.putNextEntry(new JarEntry(entry.getKey()));
				out.write(entry.getValue());
				out.closeEntry();
			}
		}
	}

	private static Path source(String name, String text) throws Exception {
		Path file = work.resolve(name);
		Files.createDirectories(file.getParent());
		Files.writeString(file, text);
		return file;
	}

	private static WeaveHarness.Result run(String label, Path jar, Map<String, String> properties) throws Exception {
		return WeaveHarness.run(work, label, jar, List.of(new WeaveHarness.Config(CONFIG, "forgehooks", Ecosystem.FABRIC)),
				List.of(LocalRanges.class), EnvType.CLIENT, OWNER.replace('/', '.'), "probe", properties);
	}
}
