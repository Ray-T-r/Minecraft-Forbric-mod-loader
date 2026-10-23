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
import net.forbric.kernel.mixin.AddedCallOccurrences;
import net.forbric.kernel.mixin.NativeOccurrences;

/**
 * NativeOccurrencePoint through the real Mixin and MixinExtras, on a target whose merged body a "carrier" patched, with names
 * that belong to no mod. Each injector is written against the native body and must run there as often as natively:
 * <ul>
 * <li>A — an unnumbered call point; the carrier reads the same getter twice more on the same path (a picked-up stack's count);</li>
 * <li>B — a {@code @ModifyVariable} at a local's store; the carrier stores the local twice more from its own hooks;</li>
 * <li>B2 — the same, where the merged body's variable table only covers the local from after the first hook (the shape of a
 * spliced body): the point cannot bind the native store there, and stands on the first store it binds after it;</li>
 * <li>C — a MixinExtras expression; the carrier evaluates the same expression again as an argument of its own hook;</li>
 * <li>D — an unnumbered call point the carrier copied into two exclusive branches: it still runs once on either branch;</li>
 * <li>E — a numbered call point; the carrier calls the same method once more in front of it.</li>
 * <li>F — an unnumbered call point the carrier hands another operand at every native call (a count it raised by one) and
 * calls once more in front of them for a slot of its own: no native call aligns as itself, each is paired by the aligned
 * consumer of its value, and the carrier's own call is left unwrapped (Mod Menu's title-screen buttons beside NeoForge's).</li>
 * </ul>
 * The run with {@code -Dforbric.nativeOccurrences=off} is the control: Mixin's own count over the merged body. The older
 * AddedCallOccurrences pass is switched off in both, so the runs differ only in this mechanism.
 */
class NativeOccurrencePointWeaveTest {
	private static final String CONFIG = "occurrence.mixins.json";
	private static final String OWNER = "fixture/occurrence/Pantry";
	private static final String NATIVE = "A=1 B=1 B2=1 C=1 D=2 E=7 F=2";
	private static final String MERGED_COUNTS = "A=3 B=3 B2=2 C=2 D=2 E=3 F=3";

	@TempDir static Path work;
	private static Path fixture;
	private static WeaveHarness.Result nativeRun, on, off, alongside;

	private static final String COMMON = """
			package fixture.occurrence;
			public class Parts {
			 public static class Jar { public int n; public Jar(int n){this.n=n;} public int count(){return n;} public Jar copy(){return new Jar(n);} public void take(int k){n-=k;} }
			 public static class Mode { private final boolean m; public Mode(boolean m){this.m=m;} public boolean mirrored(){return m;} }
			 public static class Slot { public int x; public void setX(int v){x=v;} }
			}
			""";

	private static final String NATIVE_PANTRY = """
			package fixture.occurrence;
			import fixture.occurrence.Parts.*;
			public class Pantry {
			 public static int A, B, C, D, F; public static String trace = "";
			 public Slot slotA = new Slot(), slotB = new Slot(), extra = new Slot();
			 public int position(int i, int n){ return i * 10 + n; }
			 public void shelf(){ int n = 3; slotA.setX(position(0, n)); slotB.setX(position(1, n)); }
			 public int weigh(Jar jar){ int before = jar.count(); jar.take(1); return before; }
			 public int heat(int base){ int level = base * 2; return level + 1; }
			 public int heatLate(int base){ int level = base * 2; return level + 1; }
			 public float place(Mode mode, float base){ float d = base; if (mode.mirrored()) d = -d; return d; }
			 public int pick(Jar jar, boolean flag){ int n = jar.count(); return n; }
			 public String label(Jar a, Jar b){ return a.count() + ":" + b.count(); }
			 static int stokeA(int x){ return x + 10; } static int stokeB(int x){ return x + 100; } static float distance(boolean m, float d){ return d; }
			 public static String probe(){ return Probe.run(); }
			}
			""";

	/** The same methods with a carrier's code added; heatLate's variable table is narrowed after compiling (see below). */
	private static final String MERGED_PANTRY = """
			package fixture.occurrence;
			import fixture.occurrence.Parts.*;
			public class Pantry {
			 public static int A, B, C, D, F; public static String trace = "";
			 public Slot slotA = new Slot(), slotB = new Slot(), extra = new Slot();
			 public int position(int i, int n){ return i * 10 + n; }
			 public void shelf(){ int n = 4; extra.setX(position(3, n)); slotA.setX(position(0, n)); slotB.setX(position(1, n)); }
			 public int weigh(Jar jar){ int before = jar.count(); Jar copy = jar.copy(); jar.take(1); int taken = copy.count() - jar.count(); return before + taken - 1; }
			 public int heat(int base){ int level = base * 2; level = stokeA(level); if (level < 0) return 0; level = stokeB(level); return level + 1; }
			 public int heatLate(int base){ int level = base * 2; level = stokeA(level); if (level < 0) return 0; level = stokeB(level); return level + 1; }
			 public float place(Mode mode, float base){ float d = base; if (mode.mirrored()) d = -d; return distance(mode.mirrored(), d); }
			 public int pick(Jar jar, boolean flag){ int n; if (flag) { n = jar.count() + 0; } else { n = jar.count(); } return n; }
			 public String label(Jar a, Jar b){ int early = b.count(); return a.count() + ":" + b.count() + (early < 0 ? "!" : ""); }
			 static int stokeA(int x){ return x + 10; } static int stokeB(int x){ return x + 100; } static float distance(boolean m, float d){ return d; }
			 public static String probe(){ return Probe.run(); }
			}
			""";

	private static final String PROBE = """
			package fixture.occurrence;
			import fixture.occurrence.Parts.*;
			public class Probe {
			 public static String run(){
			  Pantry p = new Pantry();
			  Pantry.A = 0; p.weigh(new Jar(5)); int a = Pantry.A;
			  Pantry.B = 0; p.heat(1); int b = Pantry.B;
			  Pantry.B = 0; p.heatLate(1); int b2 = Pantry.B;
			  Pantry.C = 0; p.place(new Mode(true), 2f); int c = Pantry.C;
			  Pantry.D = 0; p.pick(new Jar(1), true); p.pick(new Jar(1), false); int d = Pantry.D;
			  Pantry.trace = ""; p.label(new Jar(3), new Jar(7));
			  Pantry.F = 0; p.shelf(); int f = Pantry.F;
			  return "A=" + a + " B=" + b + " B2=" + b2 + " C=" + c + " D=" + d + " E=" + Pantry.trace + " F=" + f;
			 }
			}
			""";

	private static final String MIXIN = """
			package fixture.occurrence.mixin;
			import fixture.occurrence.Pantry;
			import fixture.occurrence.Parts.*;
			import org.spongepowered.asm.mixin.Mixin;
			import org.spongepowered.asm.mixin.injection.*;
			import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
			import com.llamalad7.mixinextras.expression.*;
			import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
			import com.llamalad7.mixinextras.injector.wrapoperation.*;
			@Mixin(Pantry.class)
			public class PantryMixin {
			 @Inject(method = "weigh", at = @At(value = "INVOKE", target = "Lfixture/occurrence/Parts$Jar;count()I"))
			 private void counted(Jar jar, CallbackInfoReturnable<Integer> cir) { Pantry.A++; }
			 @ModifyVariable(method = "heat", at = @At("STORE"), ordinal = 1)
			 private int stored(int level) { Pantry.B++; return level; }
			 @ModifyVariable(method = "heatLate", at = @At("STORE"), ordinal = 1)
			 private int storedLate(int level) { Pantry.B++; return level; }
			 @Definition(id = "mirrored", method = "Lfixture/occurrence/Parts$Mode;mirrored()Z")
			 @Expression("?.mirrored()")
			 @ModifyExpressionValue(method = "place", at = @At("MIXINEXTRAS:EXPRESSION"))
			 private boolean mirroredSeen(boolean mirrored) { Pantry.C++; return mirrored; }
			 @Inject(method = "pick", at = @At(value = "INVOKE", target = "Lfixture/occurrence/Parts$Jar;count()I"))
			 private void picked(Jar jar, boolean flag, CallbackInfoReturnable<Integer> cir) { Pantry.D++; }
			 @WrapOperation(method = "label", at = @At(value = "INVOKE", target = "Lfixture/occurrence/Parts$Jar;count()I", ordinal = 1))
			 private int second(Jar jar, Operation<Integer> original) { Pantry.trace += jar.n; return original.call(jar); }
			 @WrapOperation(method = "shelf", at = @At(value = "INVOKE", target = "Lfixture/occurrence/Pantry;position(II)I"))
			 private int placed(Pantry self, int i, int n, Operation<Integer> original) { Pantry.F++; return original.call(self, i, n); }
			}
			""";

	@BeforeAll static void weaveEachWay() throws Exception {
		Path json = source(CONFIG, "{\"required\":true,\"compatibilityLevel\":\"JAVA_21\",\"package\":\"fixture.occurrence.mixin\","
				+ "\"mixins\":[\"PantryMixin\"],\"injectors\":{\"defaultRequire\":1},\"mixinextras\":{\"minVersion\":\"0.5.4\"}}");
		Path common = source("src/common/Parts.java", COMMON), probe = source("src/common/Probe.java", PROBE);
		Path mixin = source("src/mixin/PantryMixin.java", MIXIN);
		Path nativeJar = WeaveHarness.fixture(work, "occurrence-native", List.of(common, probe, source("src/native/Pantry.java", NATIVE_PANTRY), mixin),
				Map.of(CONFIG, json), List.of("-g"));
		Path merged = WeaveHarness.fixture(work, "occurrence-merged", List.of(common, probe, source("src/merged/Pantry.java", MERGED_PANTRY), mixin),
				Map.of(CONFIG, json), List.of("-g"));
		narrowLateLocal(merged);
		fixture = NativeWeaveReferences.with(work, merged, Map.of(OWNER, NativeWeaveReferences.classes(nativeJar).get(OWNER)));
		nativeRun = run("native", nativeJar, Map.of(AddedCallOccurrences.PROPERTY, "off"));
		on = run("on", fixture, Map.of(AddedCallOccurrences.PROPERTY, "off"));
		off = run("off", fixture, Map.of(AddedCallOccurrences.PROPERTY, "off", NativeOccurrences.PROPERTY, "off"));
		alongside = run("alongside", fixture, Map.of());
	}

	@Test void natively_eachInjectorRunsAsWritten() {
		assertTrue(nativeRun.printed(WeaveHarnessMain.DONE + " " + NATIVE), nativeRun.describe());
	}

	@Test void eachInjectorRunsAsOftenAsNatively() throws Exception {
		assertTrue(on.printed(WeaveHarnessMain.DONE + " " + NATIVE), on.describe());
		assertTrue(on.printed("[Forbric/Mixin]") && on.printed("of the merged body's"), on.describe());
		// A, B and C drop the carrier's occurrences on the native path; B2 stands on the first store it binds; D and E log
		// nothing of the kind (D keeps both branches, E selects its counterpart).
		assertTrue(on.printed("the carrier added on the native path never run it"), on.describe());
		assertTrue(on.printed("stand(s) on the first point it binds after the native step"), on.describe());
		assertTrue(on.printed("(ordinal 1 counted natively)"), on.describe());
		assertTrue(on.printed("2 paired by the consumer of its value, which the carrier hands another operand"), on.describe());
		assertTrue(on.findings().stream().noneMatch(WeaveHarness.Finding::confirmedRequired), on.describe());
		WeaveHarness.assertWovenAndVerified(on, OWNER, fixture);
	}

	/** Switched off, Mixin counts over the merged body: the carrier's occurrences run the handlers too, and E names the wrong call. */
	@Test void switchedOffTheCarriersOccurrencesRunTheHandlers() {
		assertTrue(off.printed(WeaveHarnessMain.DONE + " " + MERGED_COUNTS), off.describe());
		assertFalse(off.printed("of the merged body's"), off.describe());
	}

	/** With the older pass left on as in production, the results are the native ones too. */
	@Test void alongsideTheOlderPassTheCountsStayNative() {
		assertTrue(alongside.printed(WeaveHarnessMain.DONE + " " + NATIVE), alongside.describe());
	}

	/**
	 * Narrows {@code heatLate}'s variable-table entry for {@code level} to begin after the first hook and its check — where a
	 * spliced merged body has it — so at the native store the local is not yet in the table.
	 */
	private static void narrowLateLocal(Path jar) throws Exception {
		Map<String, byte[]> entries = new LinkedHashMap<>();
		try (JarInputStream in = new JarInputStream(Files.newInputStream(jar))) {
			for (JarEntry entry; (entry = in.getNextJarEntry()) != null;) entries.put(entry.getName(), in.readAllBytes());
		}
		ClassNode node = new ClassNode();
		new ClassReader(entries.get(OWNER + ".class")).accept(node, 0);
		boolean narrowed = false;
		for (MethodNode method : node.methods) {
			if (!method.name.equals("heatLate")) continue;
			JumpInsnNode check = null;
			for (AbstractInsnNode instruction : method.instructions) {
				if (instruction instanceof JumpInsnNode jump && jump.getOpcode() == Opcodes.IFGE) check = jump;
			}
			assertNotNull(check, "heatLate has no sign check");
			for (LocalVariableNode local : method.localVariables) {
				if (local.name.equals("level")) { local.start = check.label; narrowed = true; }
			}
		}
		assertTrue(narrowed, "heatLate's level is not in its variable table");
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
		return WeaveHarness.run(work, label, jar, CONFIG, "pantryhooks", Ecosystem.FABRIC, EnvType.CLIENT, OWNER.replace('/', '.'), "probe", properties);
	}
}
