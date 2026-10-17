/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.mixin.weave;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import net.fabricmc.api.EnvType;
import net.forbric.api.Ecosystem;
import net.forbric.kernel.mixin.NativeLocalMaterialization;

/**
 * {@code NativeLocalMaterialization} for a local the injector's own call is never handed, through the real weave, on
 * classes that share no name with any game or mod.
 *
 * <p>Natively {@code org.lantern.view.Glass.submit} keeps the blocking {@code Pane} in a local, hands it to
 * {@code Atlas.particle} and then draws the sprite. As merged, a carrier looks the pane up as the left of a {@code Pair},
 * asks its own hook with it, and reads it off the pair again for {@code particle}: no local holds it. The guest,
 * {@code com.ember.veil}, wraps the draw with a condition and a {@code @Local Pane}. Kept in the native local — the
 * operand of the merged {@code particle} call, the one of the straight stretch before the draw — the guest reads the pane
 * natively read, and skips the stone; switched off, the {@code @Local} has nothing to read and the guest does not run.
 */
class NativeLocalSiblingWeaveTest {
	private static final String CONFIG = "veil.mixins.json";
	private static final String MOD = "veil";
	private static final String TARGET = "org/lantern/view/Glass";
	private static final String NATIVE = WeaveHarnessMain.DONE + " [veil:water, draw:sprite-of-water, tail, veil:stone, tail, tail]";
	private static final String UNBOUND = WeaveHarnessMain.DONE + " [draw:sprite-of-water, tail, draw:sprite-of-stone, tail, tail]";
	private static final String KEPT_LOG = "[Forbric/Mixin] com.ember.veil.mixin.GlassMixin.veil$skip: the merged org.lantern.view.Glass.submit";

	@TempDir static Path work;
	private static Path fixture;
	private static WeaveHarness.Result natively, kept, off;

	private static final String SHARED = """
			 public static final java.util.List<String> LOG = new java.util.ArrayList<>();
			 static Pane blocking(String player) { return player.equals("diver") ? new Pane("water") : player.equals("miner") ? new Pane("stone") : null; }
			 Atlas atlas() { return new Atlas(); }
			 static void drawSprite(String sprite, int color) { LOG.add("draw:" + sprite); }
			""";

	@BeforeAll static void weave() throws Exception {
		Path nativeFixture = fixture("native", SHARED + """
				 public void submit(String player) {
				  Pane pane = blocking(player);
				  if (pane != null) {
				   Atlas atlas = atlas();
				   String sprite = atlas.particle(pane).sprite();
				   drawSprite(sprite, -15132391);
				  }
				  LOG.add("tail");
				 }
				""", true);
		Path merged = fixture("merged", SHARED + """
				 static Pair blockingAndPos(String player) { Pane pane = blocking(player); return pane == null ? null : new Pair(pane, "here"); }
				 public void submit(String player) {
				  Pair pair = blockingAndPos(player);
				  if (pair != null) {
				   if (!Hooks.overlay(player, (Pane) pair.getLeft())) {
				    Atlas atlas = atlas();
				    String sprite = atlas.particle((Pane) pair.getLeft()).sprite();
				    drawSprite(sprite, -15132391);
				   }
				  }
				  LOG.add("tail");
				 }
				""", true);
		Path original = fixture("original", SHARED + """
				 public void submit(String player) {
				  Pane pane = blocking(player);
				  if (pane != null) {
				   Atlas atlas = atlas();
				   String sprite = atlas.particle(pane).sprite();
				   drawSprite(sprite, -15132391);
				  }
				  LOG.add("tail");
				 }
				""", false);
		fixture = NativeWeaveReferences.with(work, merged, NativeWeaveReferences.classes(original));
		natively = run("native", nativeFixture, Map.of());
		kept = run("kept", fixture, Map.of());
		off = run("off", fixture, Map.of(NativeLocalMaterialization.SIBLINGS_PROPERTY, "off"));
	}

	@Test void natively() {
		assertTrue(natively.printedLine(NATIVE), natively.describe());
	}

	@Test void keptTheGuestReadsThePaneNativelyRead() throws Exception {
		assertTrue(kept.printedLine(NATIVE), kept.describe());
		assertTrue(kept.printed(KEPT_LOG), kept.describe());
		WeaveHarness.assertWovenAndVerified(kept, TARGET, fixture);
	}

	@Test void switchedOffTheGuestHasNothingToRead() {
		assertTrue(off.printedLine(UNBOUND), off.describe());
		assertFalse(off.printed(KEPT_LOG), off.describe());
	}

	@Test void theControlFlipsEveryAssertion() {
		assertTrue(kept.printedLine(NATIVE) && !off.printedLine(NATIVE));
		assertTrue(off.printedLine(UNBOUND) && !kept.printedLine(UNBOUND));
	}

	private static Path fixture(String name, String body, boolean withGuest) throws Exception {
		List<Path> sources = new ArrayList<>();
		sources.add(source(name + "/Pane.java", "package org.lantern.view; public record Pane(String name) { }"));
		sources.add(source(name + "/Pair.java", "package org.lantern.view; public record Pair(Object getLeft, Object getRight) { }"));
		sources.add(source(name + "/Material.java", "package org.lantern.view; public record Material(String sprite) { }"));
		sources.add(source(name + "/Atlas.java", "package org.lantern.view; public class Atlas { public Material particle(Pane pane) { return new Material(\"sprite-of-\" + pane.name()); } }"));
		sources.add(source(name + "/Hooks.java", "package org.lantern.view; public class Hooks { public static boolean overlay(String player, Pane pane) { return false; } }"));
		sources.add(source(name + "/Glass.java", "package org.lantern.view;\npublic class Glass {\n" + body + "}\n"));
		Map<String, Path> resources = Map.of();
		if (withGuest) {
			sources.add(source(name + "/GlassMixin.java", """
					package com.ember.veil.mixin;
					import com.llamalad7.mixinextras.injector.v2.WrapWithCondition;
					import com.llamalad7.mixinextras.sugar.Local;
					import org.lantern.view.Glass;
					import org.lantern.view.Pane;
					import org.spongepowered.asm.mixin.Mixin;
					import org.spongepowered.asm.mixin.injection.At;
					@Mixin(Glass.class)
					public class GlassMixin {
					 @WrapWithCondition(method = "submit", require = 0, at = @At(value = "INVOKE", target = "Lorg/lantern/view/Glass;drawSprite(Ljava/lang/String;I)V"))
					 private boolean veil$skip(String sprite, int color, @Local Pane pane) {
					  Glass.LOG.add("veil:" + pane.name());
					  return !pane.name().equals("stone");
					 }
					}
					"""));
			sources.add(source(name + "/Probe.java", """
					package fixture.veil;
					import org.lantern.view.Glass;
					public class Probe {
					 public String probe() {
					  for (String player : java.util.List.of("diver", "miner", "nobody")) new Glass().submit(player);
					  return Glass.LOG.toString();
					 }
					}
					"""));
			resources = Map.of(CONFIG, source(name + "/" + CONFIG,
					"{\"required\":false,\"package\":\"com.ember.veil.mixin\",\"mixins\":[\"GlassMixin\"],\"injectors\":{\"defaultRequire\":0}}"));
		}
		return WeaveHarness.fixture(work, name, sources, resources, List.of("-g"));
	}

	private static WeaveHarness.Result run(String label, Path jar, Map<String, String> properties) throws Exception {
		return WeaveHarness.run(work, label, jar, CONFIG, MOD, Ecosystem.FABRIC, EnvType.SERVER, "fixture.veil.Probe", "probe", properties);
	}

	private static Path source(String name, String text) throws Exception {
		Path file = work.resolve("src").resolve(name);
		Files.createDirectories(file.getParent());
		Files.writeString(file, text);
		return file;
	}
}
