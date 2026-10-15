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
import net.forbric.kernel.transform.DuplicateLambdaPruneInjector;

/**
 * InsertedLambdaArgumentShim's native-shape evidence through the real weave: Essential's screenshot capture, in miniature.
 * Screenshot.grab's {@code lambda$grab$3} takes a {@code ScreenshotEvent} between the file and the consumer on both
 * carriers and none on Fabric; the merge carried the name twice, DuplicateLambdaPruneInjector drops the other carrier's
 * unreachable body, and the guest's {@code @Inject} (bare name, Fabric's shape, with a {@code @Surrogate} for an
 * {@code Object} in the middle) is shimmed onto the live one: the hook hears the saved file. With
 * {@code -Dforbric.insertedLambdaArguments.nativeShapes=off} the fit check, which asks the shim where the selector lands,
 * finds the native lambda gone and leaves the mixin out (where it is kept, Mixin rejects the descriptor: the surrogate
 * does not fit either), and the hook never runs.
 */
class InsertedLambdaNativeShapeWeaveTest {
	private static final String CONFIG = "nativeshape.mixins.json";
	private static final String MOD = "shotcapture";
	private static final String TARGET = "net/minecraft/fixture/Shots";
	private static final String SWITCH = "forbric.insertedLambdaArguments.nativeShapes";
	private static final String HEARD = "[NativeShape] captured=[a.png] said=[saved a.png]";
	private static final String UNHEARD = "[NativeShape] captured=[] said=[saved a.png]";
	private static final String LEFT_OUT = "auto-suppressing guest mixin shotcapture (" + CONFIG + "):ShotsMixin — UNFIT";
	private static final String SHIM_LOG = "ShotsMixin.captured forwards the uniquely aligned original arguments to the retained lambda$grab$3";

	@TempDir static Path work;
	private static Path fixture;
	private static WeaveHarness.Result shimmed, off;

	private static final String DISK = """
			package net.minecraft.fixture;
			public class Disk { public static void write(String file){} }
			""";
	private static final String NEO_EVENT = "package net.minecraft.fixture; public class NeoShotEvent {}";
	private static final String FORGE_EVENT = "package net.minecraft.fixture; public class ForgeShotEvent {}";

	/** The merged class: both carriers' bodies of the lambda, the other carrier's reached by nothing. */
	private static final String MERGED = """
			package net.minecraft.fixture;
			import java.util.ArrayList;
			import java.util.List;
			import java.util.function.Consumer;
			public class Shots {
			 public static final List<String> captured=new ArrayList<>(), said=new ArrayList<>();
			 public static void grab(String file, Consumer<String> out){lambda$grab$3(file,new NeoShotEvent(),out);}
			 private static void lambda$grab$3(String file, NeoShotEvent event, Consumer<String> out){Disk.write(file);out.accept("saved "+file);}
			 private static void lambda$grab$3(String file, ForgeShotEvent event, Consumer<String> out){Disk.write(file);out.accept("forge "+file);}
			 public void run(){grab("a.png",said::add);System.out.println("[NativeShape] captured="+captured+" said="+said);}
			}
			""";

	/** Fabric's: the lambda without an event. */
	private static final String NATIVE = """
			package net.minecraft.fixture;
			import java.util.ArrayList;
			import java.util.List;
			import java.util.function.Consumer;
			public class Shots {
			 public static final List<String> captured=new ArrayList<>(), said=new ArrayList<>();
			 public static void grab(String file, Consumer<String> out){lambda$grab$3(file,out);}
			 private static void lambda$grab$3(String file, Consumer<String> out){Disk.write(file);out.accept("saved "+file);}
			 public void run(){grab("a.png",said::add);System.out.println("[NativeShape] captured="+captured+" said="+said);}
			}
			""";

	private static final String MIXIN = """
			package fixture.nativeshape.mixin;
			import java.util.function.Consumer;
			import net.minecraft.fixture.Shots;
			import org.spongepowered.asm.mixin.Mixin;
			import org.spongepowered.asm.mixin.injection.At;
			import org.spongepowered.asm.mixin.injection.Inject;
			import org.spongepowered.asm.mixin.injection.Surrogate;
			import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
			@Mixin(Shots.class)
			public class ShotsMixin {
			 @Inject(method="lambda$grab$3",at=@At(value="INVOKE",target="Lnet/minecraft/fixture/Disk;write(Ljava/lang/String;)V",shift=At.Shift.AFTER),remap=false)
			 private static void captured(String file,Consumer<String> out,CallbackInfo ci){Shots.captured.add(file);}
			 @Surrogate
			 private static void captured(String file,Object event,Consumer<String> out,CallbackInfo ci){Shots.captured.add("surrogate "+file);}
			}
			""";

	@BeforeAll static void weaveBothWays() throws Exception {
		Path json = work.resolve(CONFIG);
		Files.writeString(json, "{\"required\":true,\"package\":\"fixture.nativeshape.mixin\",\"mixins\":[\"ShotsMixin\"],\"injectors\":{\"defaultRequire\":1}}");
		Path mixin = source("mixin/ShotsMixin.java", MIXIN);
		List<Path> shared = List.of(source("shared/Disk.java", DISK), source("shared/NeoShotEvent.java", NEO_EVENT),
				source("shared/ForgeShotEvent.java", FORGE_EVENT));
		Path nativeFixture = WeaveHarness.fixture(work, "native-nativeshape",
				java.util.stream.Stream.concat(shared.stream(), java.util.stream.Stream.of(source("native/Shots.java", NATIVE), mixin)).toList(), Map.of(CONFIG, json));
		Path current = WeaveHarness.fixture(work, "nativeshape",
				java.util.stream.Stream.concat(shared.stream(), java.util.stream.Stream.of(source("merged/Shots.java", MERGED), mixin)).toList(), Map.of(CONFIG, json));
		fixture = NativeWeaveReferences.with(work, current, Map.of(TARGET, NativeWeaveReferences.classes(nativeFixture).get(TARGET)));
		shimmed = run("shimmed", Map.of());
		off = run("off", Map.of(SWITCH, "off"));
	}

	@Test void theHookHearsTheSavedFileOnTheLiveLambda() throws Exception {
		assertTrue(shimmedHolds(shimmed), shimmed.describe());
		WeaveHarness.assertWovenAndVerified(shimmed, TARGET, fixture);
	}

	@Test void switchedOffTheMixinIsLeftOutAndTheHookNeverRuns() {
		assertTrue(offHolds(off), off.describe());
	}

	/** The switch is the runs' only difference, so each run's predicate must reject the other. */
	@Test void theControlFlipsEveryAssertion() {
		assertTrue(shimmedHolds(shimmed) && !shimmedHolds(off), "the shim predicate does not separate the runs");
		assertTrue(offHolds(off) && !offHolds(shimmed), "the control predicate does not separate the runs");
	}

	private static boolean shimmedHolds(WeaveHarness.Result run) {
		return run.printedLine(HEARD) && run.printed(SHIM_LOG) && run.findings().stream().noneMatch(WeaveHarness.Finding::confirmedRequired);
	}

	private static boolean offHolds(WeaveHarness.Result run) {
		return run.printedLine(UNHEARD) && !run.printed(SHIM_LOG) && run.printed(LEFT_OUT);
	}

	private static Path source(String name, String text) throws Exception {
		Path file = work.resolve("src").resolve(name);
		Files.createDirectories(file.getParent());
		Files.writeString(file, text);
		return file;
	}

	private static WeaveHarness.Result run(String label, Map<String, String> properties) throws Exception {
		return WeaveHarness.run(work, label, fixture, List.of(new WeaveHarness.Config(CONFIG, MOD, Ecosystem.FABRIC)),
				List.of(DuplicateLambdaPruneInjector.class), EnvType.CLIENT, "net.minecraft.fixture.Shots", "run", properties);
	}
}
