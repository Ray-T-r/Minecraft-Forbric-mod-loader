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
import net.forbric.kernel.mixin.MixinExpressionThunks;
import net.forbric.kernel.transform.LambdaInvocationThunkInjector;

/**
 * MixinExpressionThunks through the real weave: a guest {@code @ModifyExpressionValue} on the method reference
 * {@code this::canHit} (Lithium's typed projectile hit predicates, in miniature), on a class whose method reference
 * LambdaInvocationThunkInjector routes through a thunk. Without the thunk, and with the thunk named by the definition, the
 * predicate is wrapped. With {@code -Dforbric.expressionThunks=off} the definition names only {@code canHit}, which no
 * reference names any more: the injector finds nothing and the mod's required injector is reported.
 */
class MixinExpressionThunksWeaveTest {
	private static final String CONFIG = "expressionthunks.mixins.json";
	private static final String MOD = "hitfilter";
	private static final String TARGET = "net/minecraft/fixture/Shots";
	private static final String WRAPPED = "[ExprThunk] hits=[3, 5]";
	private static final String PLAIN = "[ExprThunk] hits=[1, 3, 5]";

	@TempDir static Path work;
	private static Path fixture;
	private static WeaveHarness.Result named, off, noThunk;

	private static final String SHOTS = """
			package net.minecraft.fixture;
			import java.util.List;
			public class Shots {
			 public boolean canHit(Integer target){return target%2==1;}
			 public List<Integer> pick(List<Integer> targets){return targets.stream().filter(this::canHit).toList();}
			 public void run(){System.out.println("[ExprThunk] hits="+pick(List.of(1,2,3,4,5)));}
			}
			""";

	private static final String MIXIN = """
			package fixture.expressionthunks.mixin;
			import java.util.function.Predicate;
			import net.minecraft.fixture.Shots;
			import org.spongepowered.asm.mixin.Mixin;
			import org.spongepowered.asm.mixin.injection.At;
			import com.llamalad7.mixinextras.expression.Definition;
			import com.llamalad7.mixinextras.expression.Expression;
			import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
			@Mixin(Shots.class)
			public class ShotsMixin {
			 @Definition(id="canHit",method="Lnet/minecraft/fixture/Shots;canHit(Ljava/lang/Integer;)Z")
			 @Expression("this::canHit")
			 @ModifyExpressionValue(method="pick",at=@At("MIXINEXTRAS:EXPRESSION"))
			 private Predicate<Integer> typed(Predicate<Integer> original){return target->original.test(target)&&target>1;}
			}
			""";

	@BeforeAll static void weaveEachWay() throws Exception {
		Path json = work.resolve(CONFIG);
		Files.writeString(json, "{\"required\":true,\"package\":\"fixture.expressionthunks.mixin\",\"mixins\":[\"ShotsMixin\"],\"injectors\":{\"defaultRequire\":1},\"mixinextras\":{\"minVersion\":\"0.5.0\"}}");
		fixture = WeaveHarness.fixture(work, "expressionthunks", List.of(source("Shots.java", SHOTS), source("ShotsMixin.java", MIXIN)), Map.of(CONFIG, json));
		named = run("named", Map.of());
		off = run("off", Map.of(MixinExpressionThunks.PROPERTY, "off"));
		noThunk = run("no-thunk", Map.of(LambdaInvocationThunkInjector.PROPERTY, "off"));
	}

	@Test void thePredicateIsWrappedAsItIsWithoutTheThunk() throws Exception {
		assertTrue(noThunk.printedLine(WRAPPED), noThunk.describe());
		assertTrue(namedHolds(named), named.describe() + "\nfindings: " + named.findings());
		WeaveHarness.assertWovenAndVerified(named, TARGET, fixture);
	}

	@Test void switchedOffTheDefinitionFindsNothingAndIsReported() {
		assertTrue(offHolds(off), off.describe() + "\nfindings: " + off.findings());
	}

	/** The switch is the runs' only difference, so each run's predicate must reject the other. */
	@Test void theControlFlipsEveryAssertion() {
		assertTrue(namedHolds(named) && !namedHolds(off), "the named predicate does not separate the runs");
		assertTrue(offHolds(off) && !offHolds(named), "the control predicate does not separate the runs");
	}

	private static boolean namedHolds(WeaveHarness.Result run) {
		return run.printedLine(WRAPPED) && losses(run).isEmpty();
	}

	private static boolean offHolds(WeaveHarness.Result run) {
		return run.printedLine(PLAIN) && !losses(run).isEmpty();
	}

	private static List<WeaveHarness.Finding> losses(WeaveHarness.Result run) {
		// The lost injector: Mixin found no expression for it (the kernel softens the required count, so it reads SUSPECTED).
		return run.findings().stream().filter(f -> f.modId().equals(MOD) && f.required()).toList();
	}

	private static Path source(String name, String text) throws Exception {
		Path file = work.resolve("src").resolve(name);
		Files.createDirectories(file.getParent());
		Files.writeString(file, text);
		return file;
	}

	private static WeaveHarness.Result run(String label, Map<String, String> properties) throws Exception {
		return WeaveHarness.run(work, label, fixture, List.of(new WeaveHarness.Config(CONFIG, MOD, Ecosystem.FABRIC)),
				List.of(LambdaInvocationThunkInjector.class), EnvType.CLIENT, "net.minecraft.fixture.Shots", "run", properties);
	}
}
