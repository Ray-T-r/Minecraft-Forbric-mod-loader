/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.mixin.weave;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import net.fabricmc.api.EnvType;
import net.forbric.api.Ecosystem;
import net.forbric.kernel.mixin.MixinReturnOrdinals;

/**
 * MixinReturnOrdinals through the real weave: a guest {@code @Inject} at {@code RETURN} ordinal 1 — the native body's
 * second way out, taken for the code 7 (apoli's action_on_block_break on {@code destroyBlock}'s successful return, in
 * miniature) — on a merged body whose carrier put an early, cancellable return in front. Native, and re-counted by its way
 * out, the hook hears the code 7. With {@code -Dforbric.returnOrdinals=off} ordinal 1 names the merged body's second
 * return — the negative-code exit — and the hook hears that instead.
 */
class MixinReturnOrdinalsWeaveTest {
	private static final String CONFIG = "returnordinals.mixins.json";
	private static final String MOD = "dooraction";
	private static final String TARGET = "net/minecraft/fixture/Doors";
	private static final String RIGHT = "[ReturnOrdinal] heard=[7]";
	private static final String WRONG = "[ReturnOrdinal] heard=[-1]";
	private static final String MOVED_LOG = "[Forbric/Mixin] fixture.returnordinals.mixin.DoorsMixin: opened's RETURN ordinal(s) 1 on net.minecraft.fixture.Doors.open";

	@TempDir static Path work;
	private static Path fixture;
	private static WeaveHarness.Result moved, off, nativeRun;

	private static final String GATE = """
			package net.minecraft.fixture;
			public class Gate { public static boolean cancel(int code){return code==99;} }
			""";

	/** The merged open: the carrier's cancellable exit first, then vanilla's three. */
	private static final String MERGED = """
			package net.minecraft.fixture;
			import java.util.ArrayList;
			import java.util.List;
			public class Doors {
			 public static final List<Integer> heard=new ArrayList<>();
			 public boolean open(int code){
			  if(Gate.cancel(code))return false;
			  if(code<0)return false;
			  if(code==7)return true;
			  return code>3;
			 }
			 public void run(){open(7);open(-1);open(2);System.out.println("[ReturnOrdinal] heard="+heard);}
			}
			""";

	private static final String MIXIN = """
			package fixture.returnordinals.mixin;
			import net.minecraft.fixture.Doors;
			import org.spongepowered.asm.mixin.Mixin;
			import org.spongepowered.asm.mixin.injection.At;
			import org.spongepowered.asm.mixin.injection.Inject;
			import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
			@Mixin(Doors.class)
			public class DoorsMixin {
			 @Inject(method="open",at=@At(value="RETURN",ordinal=1))
			 private void opened(int code,CallbackInfoReturnable<Boolean> cir){Doors.heard.add(code);}
			}
			""";

	@BeforeAll static void weaveEachWay() throws Exception {
		Path json = work.resolve(CONFIG);
		Files.writeString(json, "{\"required\":true,\"package\":\"fixture.returnordinals.mixin\",\"mixins\":[\"DoorsMixin\"],\"injectors\":{\"defaultRequire\":1}}");
		Path mixin = source("mixin/DoorsMixin.java", MIXIN);
		Path nativeTarget = source("native/Doors.java", MERGED.replace("  if(Gate.cancel(code))return false;\n", ""));
		Path nativeFixture = WeaveHarness.fixture(work, "native-returnordinals", List.of(source("native/Gate.java", GATE), nativeTarget, mixin), Map.of(CONFIG, json));
		Path current = WeaveHarness.fixture(work, "returnordinals", List.of(source("merged/Gate.java", GATE), source("merged/Doors.java", MERGED), mixin), Map.of(CONFIG, json));
		fixture = NativeWeaveReferences.with(work, current, Map.of(TARGET, NativeWeaveReferences.classes(nativeFixture).get(TARGET)));
		nativeRun = run("native", nativeFixture, Map.of());
		moved = run("moved", fixture, Map.of());
		off = run("off", fixture, Map.of(MixinReturnOrdinals.PROPERTY, "off"));
	}

	@Test void theHookHearsTheSameWayOutAsNatively() throws Exception {
		assertTrue(nativeRun.printedLine(RIGHT), nativeRun.describe());
		assertTrue(movedHolds(moved), moved.describe());
		WeaveHarness.assertWovenAndVerified(moved, TARGET, fixture);
	}

	@Test void switchedOffTheOrdinalNamesTheWrongReturn() {
		assertTrue(offHolds(off), off.describe());
	}

	/** The switch is the runs' only difference, so each run's predicate must reject the other. */
	@Test void theControlFlipsEveryAssertion() {
		assertTrue(movedHolds(moved) && !movedHolds(off), "the move predicate does not separate the runs");
		assertTrue(offHolds(off) && !offHolds(moved), "the control predicate does not separate the runs");
	}

	private static boolean movedHolds(WeaveHarness.Result run) {
		return run.printedLine(RIGHT) && run.printed(MOVED_LOG);
	}

	private static boolean offHolds(WeaveHarness.Result run) {
		return run.printedLine(WRONG) && !run.printed(MOVED_LOG);
	}

	private static Path source(String name, String text) throws Exception {
		Path file = work.resolve("src").resolve(name);
		Files.createDirectories(file.getParent());
		Files.writeString(file, text);
		return file;
	}

	private static WeaveHarness.Result run(String label, Path jar, Map<String, String> properties) throws Exception {
		return WeaveHarness.run(work, label, jar, CONFIG, MOD, Ecosystem.FABRIC, EnvType.SERVER, "net.minecraft.fixture.Doors", "run", properties);
	}
}
