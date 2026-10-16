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
import net.forbric.kernel.mixin.AddedCallOccurrences;

/**
 * AddedCallOccurrences through the real weave: a guest {@code @Inject} at an unnumbered call of {@code mainHand()} (Create's
 * cancellable hook at {@code ServerPlayerGameMode.useItemOn}'s {@code getMainHandItem}, in miniature), on a merged body that
 * asks {@code mainHand()} a second time on the sneaking path (NeoForge's {@code doesSneakBypassUse}). Native, the hook runs
 * once per use; numbered with the native occurrence it still does. With {@code -Dforbric.addedCallOccurrences=off} it runs
 * at the carrier's call too: twice for a sneaking use.
 */
class AddedCallOccurrencesWeaveTest {
	private static final String CONFIG = "addedcall.mixins.json";
	private static final String MOD = "handhook";
	private static final String TARGET = "net/minecraft/fixture/GameMode";
	private static final String ONCE = "[AddedCall] hooks=1 result=bypass";
	private static final String TWICE = "[AddedCall] hooks=2 result=bypass";
	private static final String NUMBERED_LOG = "[Forbric/Mixin] fixture.addedcall.mixin.UseMixin: hook's point at Lnet/minecraft/fixture/GameMode;mainHand()";

	@TempDir static Path work;
	private static Path fixture;
	private static WeaveHarness.Result numbered, off, nativeRun;

	private static final String ITEM = """
			package net.minecraft.fixture;
			public class Item {
			 public final String name; public Item(String name){this.name=name;}
			 public boolean empty(){return name.isEmpty();}
			 public boolean bypass(){return true;}
			}
			""";

	/** The merged use: vanilla's {@code mainHand().empty()}, and the carrier's {@code mainHand().bypass()} for a sneaking player. */
	private static final String MERGED = """
			package net.minecraft.fixture;
			public class GameMode {
			 public static int hooks;
			 private final Item main=new Item("stone");
			 public Item mainHand(){return main;}
			 public String use(boolean sneaking){
			  boolean holding=!mainHand().empty();
			  if(sneaking&&holding&&mainHand().bypass())return "bypass";
			  return holding?"use":"pass";
			 }
			 public void run(){String result=use(true);System.out.println("[AddedCall] hooks="+hooks+" result="+result);}
			}
			""";

	private static final String MIXIN = """
			package fixture.addedcall.mixin;
			import net.minecraft.fixture.GameMode;
			import org.spongepowered.asm.mixin.Mixin;
			import org.spongepowered.asm.mixin.injection.At;
			import org.spongepowered.asm.mixin.injection.Inject;
			import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
			@Mixin(GameMode.class)
			public class UseMixin {
			 @Inject(method="use",at=@At(value="INVOKE",target="Lnet/minecraft/fixture/GameMode;mainHand()Lnet/minecraft/fixture/Item;"))
			 private void hook(boolean sneaking,CallbackInfoReturnable<String> cir){GameMode.hooks++;}
			}
			""";

	@BeforeAll static void weaveEachWay() throws Exception {
		Path json = work.resolve(CONFIG);
		Files.writeString(json, "{\"required\":true,\"package\":\"fixture.addedcall.mixin\",\"mixins\":[\"UseMixin\"],\"injectors\":{\"defaultRequire\":1}}");
		Path item = source("native/Item.java", ITEM), mixin = source("mixin/UseMixin.java", MIXIN);
		Path nativeTarget = source("native/GameMode.java", MERGED.replace("if(sneaking&&holding&&mainHand().bypass())return \"bypass\";",
				"if(sneaking&&holding)return \"bypass\";"));
		Path nativeFixture = WeaveHarness.fixture(work, "native-addedcall", List.of(item, nativeTarget, mixin), Map.of(CONFIG, json));
		Path merged = source("merged/GameMode.java", MERGED);
		Path current = WeaveHarness.fixture(work, "addedcall", List.of(source("merged/Item.java", ITEM), merged, mixin), Map.of(CONFIG, json));
		fixture = NativeWeaveReferences.with(work, current, Map.of(TARGET, NativeWeaveReferences.classes(nativeFixture).get(TARGET)));
		nativeRun = run("native", nativeFixture, Map.of());
		numbered = run("numbered", fixture, Map.of());
		// NativeOccurrences repairs the same shape from the native body; off too, so the control isolates this pass.
		off = run("off", fixture, Map.of(AddedCallOccurrences.PROPERTY, "off", net.forbric.kernel.mixin.NativeOccurrences.PROPERTY, "off"));
	}

	@Test void theHookRunsOnceAsItDoesNatively() throws Exception {
		assertTrue(nativeRun.printedLine(ONCE), nativeRun.describe());
		assertTrue(numberedHolds(numbered), numbered.describe());
		WeaveHarness.assertWovenAndVerified(numbered, TARGET, fixture);
	}

	@Test void switchedOffTheHookAlsoRunsAtTheCarriersCall() {
		assertTrue(offHolds(off), off.describe());
	}

	/** The switch is the runs' only difference, so each run's predicate must reject the other. */
	@Test void theControlFlipsEveryAssertion() {
		assertTrue(numberedHolds(numbered) && !numberedHolds(off), "the numbered predicate does not separate the runs");
		assertTrue(offHolds(off) && !offHolds(numbered), "the control predicate does not separate the runs");
	}

	private static boolean numberedHolds(WeaveHarness.Result run) {
		return run.printedLine(ONCE) && run.printed(NUMBERED_LOG) && run.findings().stream().noneMatch(WeaveHarness.Finding::confirmedRequired);
	}

	private static boolean offHolds(WeaveHarness.Result run) {
		return run.printedLine(TWICE) && !run.printed(NUMBERED_LOG);
	}

	private static Path source(String name, String text) throws Exception {
		Path file = work.resolve("src").resolve(name);
		Files.createDirectories(file.getParent());
		Files.writeString(file, text);
		return file;
	}

	private static WeaveHarness.Result run(String label, Path jar, Map<String, String> properties) throws Exception {
		return WeaveHarness.run(work, label, jar, CONFIG, MOD, Ecosystem.FABRIC, EnvType.CLIENT, "net.minecraft.fixture.GameMode", "run", properties);
	}
}
