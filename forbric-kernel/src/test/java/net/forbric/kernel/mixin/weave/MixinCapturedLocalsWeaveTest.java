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
import net.forbric.kernel.mixin.MixinCapturedLocals;

/**
 * MixinCapturedLocals through the real weave: a guest {@code @Inject(locals = CAPTURE_FAILHARD)} capturing a for-each's
 * array, length, index, element and the held item (Architectury's CLIENT_RIGHT_CLICK_AIR in {@code Minecraft.startUseItem},
 * in miniature), on a merged body that keeps a carrier event local between the element and the held item. Native, the
 * hook sees the held item. Converted to {@code @Local}s it still does. With {@code -Dforbric.capturedLocals=off} the
 * capture's list no longer matches, the kernel softens the failure to a skip, and the hook never runs.
 */
class MixinCapturedLocalsWeaveTest {
	private static final String CONFIG = "capturedlocals.mixins.json";
	private static final String MOD = "airclick";
	private static final String TARGET = "net/minecraft/fixture/Hands";
	private static final String SEEN = "[Captured] seen=MAIN";
	private static final String UNSEEN = "[Captured] seen=none";
	private static final String CONVERTED_LOG = "[Forbric/Mixin] fixture.capturedlocals.mixin.HandsMixin: hook's locals capture in net.minecraft.fixture.Hands.use";

	@TempDir static Path work;
	private static Path fixture;
	private static WeaveHarness.Result converted, off, nativeRun;

	/** The merged use: a carrier event local in front of the held item. */
	private static final String MERGED = """
			package net.minecraft.fixture;
			public class Hands {
			 public static String seen="none";
			 public void use(String[] hands){
			  for(String hand:hands){
			   Object event=new Object();
			   String held=hand.toUpperCase();
			   if(!held.isEmpty()&&event!=null)System.out.println("[Captured] using "+held);
			  }
			 }
			 public void run(){use(new String[]{"main"});System.out.println("[Captured] seen="+seen);}
			}
			""";

	private static final String MIXIN = """
			package fixture.capturedlocals.mixin;
			import net.minecraft.fixture.Hands;
			import org.spongepowered.asm.mixin.Mixin;
			import org.spongepowered.asm.mixin.injection.At;
			import org.spongepowered.asm.mixin.injection.Inject;
			import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
			import org.spongepowered.asm.mixin.injection.callback.LocalCapture;
			@Mixin(Hands.class)
			public class HandsMixin {
			 @Inject(method="use",at=@At(value="INVOKE",target="Ljava/lang/String;isEmpty()Z"),locals=LocalCapture.CAPTURE_FAILHARD)
			 private void hook(String[] hands,CallbackInfo ci,String[] copy,int length,int index,String hand,String held){Hands.seen=held;}
			}
			""";

	@BeforeAll static void weaveEachWay() throws Exception {
		Path json = work.resolve(CONFIG);
		Files.writeString(json, "{\"required\":true,\"package\":\"fixture.capturedlocals.mixin\",\"mixins\":[\"HandsMixin\"],\"injectors\":{\"defaultRequire\":1}}");
		Path mixin = source("mixin/HandsMixin.java", MIXIN);
		Path nativeTarget = source("native/Hands.java", MERGED.replace("   Object event=new Object();\n", "").replace("&&event!=null", ""));
		List<String> debug = List.of("-g");
		Path nativeFixture = WeaveHarness.fixture(work, "native-capturedlocals", List.of(nativeTarget, mixin), Map.of(CONFIG, json), debug);
		Path current = WeaveHarness.fixture(work, "capturedlocals", List.of(source("merged/Hands.java", MERGED), mixin), Map.of(CONFIG, json), debug);
		fixture = NativeWeaveReferences.with(work, current, Map.of(TARGET, NativeWeaveReferences.classes(nativeFixture).get(TARGET)));
		nativeRun = run("native", nativeFixture, Map.of());
		converted = run("converted", fixture, Map.of());
		off = run("off", fixture, Map.of(MixinCapturedLocals.PROPERTY, "off"));
	}

	@Test void theHookSeesTheHeldItemAsItDoesNatively() throws Exception {
		assertTrue(nativeRun.printedLine(SEEN), nativeRun.describe());
		assertTrue(convertedHolds(converted), converted.describe());
		WeaveHarness.assertWovenAndVerified(converted, TARGET, fixture);
	}

	@Test void switchedOffTheCaptureIsSkippedAndTheHookNeverRuns() {
		assertTrue(offHolds(off), off.describe());
	}

	/** The switch is the runs' only difference, so each run's predicate must reject the other. */
	@Test void theControlFlipsEveryAssertion() {
		assertTrue(convertedHolds(converted) && !convertedHolds(off), "the converted predicate does not separate the runs");
		assertTrue(offHolds(off) && !offHolds(converted), "the control predicate does not separate the runs");
	}

	private static boolean convertedHolds(WeaveHarness.Result run) {
		return run.printedLine(SEEN) && run.printed(CONVERTED_LOG) && run.findings().stream().noneMatch(WeaveHarness.Finding::confirmedRequired);
	}

	private static boolean offHolds(WeaveHarness.Result run) {
		return run.printedLine(UNSEEN) && !run.printed(CONVERTED_LOG);
	}

	private static Path source(String name, String text) throws Exception {
		Path file = work.resolve("src").resolve(name);
		Files.createDirectories(file.getParent());
		Files.writeString(file, text);
		return file;
	}

	private static WeaveHarness.Result run(String label, Path jar, Map<String, String> properties) throws Exception {
		return WeaveHarness.run(work, label, jar, CONFIG, MOD, Ecosystem.FABRIC, EnvType.CLIENT, "net.minecraft.fixture.Hands", "run", properties);
	}
}
