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
import net.forbric.kernel.mixin.MixinStubRebind;

/**
 * The stub rebind of a sliced {@code @ModifyVariable} pinned by value — the value a flag holds joined on two paths — and of
 * its {@code @Share} partner, a {@code @ModifyArg} with a {@code @Cancellable} callback, through the real weave.
 *
 * <p>A stand-in {@code EffectsInInventory} has the shipped carrier-stubs.txt row's shape (the row is keyed on the game's
 * class, so the stand-in carries that name and its argument types; the guest, {@code com.glimmer.aura}, shares no name
 * with any mod). Natively {@code extractText} computes "show the tooltip" as the clipped-text flag, or true when there is
 * no room, and shows the name and duration when it holds and the mouse is over the effect. As merged the method is a stub
 * forwarding to the carrier's overload with an added effect argument, where the same flag sits one slot further on and
 * picks the lines (both, or none), the carrier's own hook may change them, and an empty list shows nothing.
 *
 * <p>The guest forces the flag from {@code clipText} on (the slice keeps the flag's first store, before it, out) while an
 * effect is hovered, remembers what it was in a {@code @Share}, and on the tooltip call keeps the lines only when the flag
 * was really set, adds its own, and cancels the method when nothing is left. Moved — the flag's slot pinned by what it
 * holds in both methods, the slice judged in both — every scenario shows what it shows natively; with
 * {@code -Dforbric.mixinStubRebind.slice=off} or {@code .cancellable=off} both handlers stay on the stub nothing calls,
 * since one cannot move without its {@code @Share} partner.
 */
class MixinStubRebindSliceWeaveTest {
	private static final String OWNER = "net/minecraft/client/gui/screens/inventory/EffectsInInventory";
	private static final String CONFIG = "aura.mixins.json";
	/** hovered+fits: the guest's line alone; hovered+clipped: name, duration and the guest's line; hovered+fits+nothing to add: cancelled. */
	private static final String NATIVE = "[Aura] fits+hovered=[aura:glow] clipped+hovered=[Regeneration potion, 0:30, aura:glow] fits=[] "
			+ "clipped=[Regeneration potion, 0:30] fits+hovered+silent=[] cancelled-before-tail=true";
	/** What the carrier's body shows with the guest's handlers left on the stub. */
	private static final String UNMOVED = "[Aura] fits+hovered=[] clipped+hovered=[Regeneration potion, 0:30] fits=[] "
			+ "clipped=[Regeneration potion, 0:30] fits+hovered+silent=[] cancelled-before-tail=false";
	private static final String MOVED = "now targets net.minecraft.client.gui.screens.inventory.EffectsInInventory.extractText(";
	private static final String PINNED = "@ModifyVariable on net.minecraft.client.gui.screens.inventory.EffectsInInventory.extractText(";

	@TempDir static Path work;
	private static Path mergedFixture;
	private static WeaveHarness.Result natively, moved, sliceOff, cancellableOff;

	private static final String PREFIX = """
			  int textX = x0 + 32, textY = y0 + 7, maxWidth = width - 32 - 7;
			  boolean isCompact;
			  if (maxWidth > 0) {
			   boolean shouldClip = font.width(name) > maxWidth;
			   FormattedCharSequence text = shouldClip ? ComponentRenderUtils.clipText(name, font, maxWidth) : name.getVisualOrderText();
			   graphics.text(font, text, textX, textY, -1);
			   graphics.text(font, duration, textX, textY + 9, -8355712);
			   isCompact = shouldClip;
			  } else {
			   isCompact = true;
			  }
			""";

	@BeforeAll static void weave() throws Exception {
		Path nativeFixture = fixture("native", """
				 public void show(GuiGraphicsExtractor graphics, Component name, int width, int mouseX) {
				  extractText(graphics, name, Component.literal("0:30"), new Font(), 0, 0, width, 32, mouseX, 10);
				 }
				 private void extractText(GuiGraphicsExtractor graphics, Component name, Component duration, Font font, int x0, int y0, int width, int height, int mouseX, int mouseY) {
				""" + PREFIX + """
				  if (isCompact && mouseX >= x0 && mouseX <= x0 + width && mouseY >= y0 && mouseY <= y0 + height) {
				   graphics.setTooltipForNextFrame(font, List.of(name, duration), Optional.empty(), mouseX, mouseY);
				  }
				  graphics.tail = true;
				 }
				""");
		Map<String, byte[]> original = NativeWeaveReferences.classes(nativeFixture);
		mergedFixture = NativeWeaveReferences.with(work, fixture("merged", """
				 public void show(GuiGraphicsExtractor graphics, Component name, int width, int mouseX) {
				  extractText(graphics, name, Component.literal("0:30"), new Font(), 0, 0, width, 32, mouseX, 10, new MobEffectInstance());
				 }
				 private void extractText(GuiGraphicsExtractor graphics, Component name, Component duration, Font font, int x0, int y0, int width, int height, int mouseX, int mouseY) {
				  this.extractText(graphics, name, duration, font, x0, y0, width, height, mouseX, mouseY, null);
				 }
				 private void extractText(GuiGraphicsExtractor graphics, Component name, Component duration, Font font, int x0, int y0, int width, int height, int mouseX, int mouseY, MobEffectInstance effect) {
				""" + PREFIX + """
				  if (mouseX >= x0 && mouseX <= x0 + width && mouseY >= y0 && mouseY <= y0 + height) {
				   List<Component> tooltip = isCompact ? List.of(name, duration) : List.of();
				   if (effect != null) tooltip = ClientHooks.getEffectTooltip(this, effect, tooltip);
				   if (!tooltip.isEmpty()) graphics.setTooltipForNextFrame(font, tooltip, Optional.empty(), mouseX, mouseY);
				  }
				  graphics.tail = true;
				 }
				"""), original);
		natively = run("native", nativeFixture, Map.of());
		moved = run("moved", mergedFixture, Map.of());
		sliceOff = run("slice-off", mergedFixture, Map.of(MixinStubRebind.SLICE_PROPERTY, "off"));
		cancellableOff = run("cancellable-off", mergedFixture, Map.of(MixinStubRebind.CANCELLABLE_PROPERTY, "off"));
	}

	@Test void natively() {
		assertTrue(natively.printedLine(WeaveHarnessMain.DONE + " " + NATIVE), natively.describe());
	}

	@Test void movedTheGuestShowsWhatItShowsNatively() throws Exception {
		assertTrue(movedHolds(moved), moved.describe() + "\nfindings: " + moved.findings());
		WeaveHarness.assertWovenAndVerified(moved, OWNER, mergedFixture);
	}

	@Test void withTheSliceOrTheCallbackSwitchedOffBothStayOnTheStub() {
		for (WeaveHarness.Result off : List.of(sliceOff, cancellableOff)) assertTrue(offHolds(off), off.describe());
	}

	@Test void theControlFlipsEveryAssertion() {
		assertTrue(movedHolds(moved) && !movedHolds(sliceOff) && !movedHolds(cancellableOff));
		assertTrue(offHolds(sliceOff) && offHolds(cancellableOff) && !offHolds(moved));
	}

	private static boolean movedHolds(WeaveHarness.Result run) {
		return run.printedLine(WeaveHarnessMain.DONE + " " + NATIVE) && run.printed(MOVED) && run.printed(PINNED);
	}

	private static boolean offHolds(WeaveHarness.Result run) {
		return run.printedLine(WeaveHarnessMain.DONE + " " + UNMOVED) && !run.printed(MOVED);
	}

	private static Path fixture(String name, String body) throws Exception {
		List<Path> sources = new ArrayList<>();
		sources.add(source(name + "/GuiGraphicsExtractor.java", """
				package net.minecraft.client.gui;
				import java.util.*;
				import net.minecraft.network.chat.Component;
				import net.minecraft.util.FormattedCharSequence;
				public class GuiGraphicsExtractor {
				 public List<String> tooltip = List.of();
				 public boolean tail;
				 public void text(Font font, FormattedCharSequence text, int x, int y, int color) { }
				 public void text(Font font, Component text, int x, int y, int color) { }
				 public void setTooltipForNextFrame(Font font, List<Component> lines, Optional<?> image, int x, int y) {
				  List<String> out = new ArrayList<>();
				  for (Component line : lines) out.add(line.toString());
				  tooltip = out;
				 }
				}
				"""));
		sources.add(source(name + "/Font.java", "package net.minecraft.client.gui; public class Font { public int width(net.minecraft.network.chat.Component text) { return text.toString().length() * 6; } }"));
		sources.add(source(name + "/FormattedCharSequence.java", "package net.minecraft.util; public interface FormattedCharSequence { }"));
		sources.add(source(name + "/Component.java", """
				package net.minecraft.network.chat;
				import net.minecraft.util.FormattedCharSequence;
				public interface Component {
				 static Component literal(String text) { return new Component() { public String toString() { return text; } }; }
				 default FormattedCharSequence getVisualOrderText() { return new FormattedCharSequence() { }; }
				}
				"""));
		sources.add(source(name + "/ComponentRenderUtils.java", """
				package net.minecraft.client.gui.components;
				import net.minecraft.client.gui.Font;
				import net.minecraft.network.chat.Component;
				import net.minecraft.util.FormattedCharSequence;
				public class ComponentRenderUtils { public static FormattedCharSequence clipText(Component text, Font font, int width) { return new FormattedCharSequence() { }; } }
				"""));
		sources.add(source(name + "/MobEffectInstance.java", "package net.minecraft.world.effect; public class MobEffectInstance { }"));
		sources.add(source(name + "/ClientHooks.java", """
				package net.neoforged.neoforge.client;
				import java.util.List;
				import net.minecraft.network.chat.Component;
				import net.minecraft.world.effect.MobEffectInstance;
				public class ClientHooks { public static List<Component> getEffectTooltip(Object screen, MobEffectInstance effect, List<Component> lines) { return lines; } }
				"""));
		sources.add(source(name + "/EffectsInInventory.java", """
				package net.minecraft.client.gui.screens.inventory;
				import java.util.*;
				import net.minecraft.client.gui.Font;
				import net.minecraft.client.gui.GuiGraphicsExtractor;
				import net.minecraft.client.gui.components.ComponentRenderUtils;
				import net.minecraft.network.chat.Component;
				import net.minecraft.util.FormattedCharSequence;
				import net.minecraft.world.effect.MobEffectInstance;
				import net.neoforged.neoforge.client.ClientHooks;
				public class EffectsInInventory {
				%s}
				""".formatted(body)));
		sources.add(source(name + "/Aura.java", "package com.glimmer.aura; public class Aura { public static boolean hovered, addLine; }"));
		sources.add(source(name + "/AuraMixin.java", """
				package com.glimmer.aura.mixin;
				import com.glimmer.aura.Aura;
				import com.llamalad7.mixinextras.sugar.Cancellable;
				import com.llamalad7.mixinextras.sugar.Share;
				import com.llamalad7.mixinextras.sugar.ref.LocalBooleanRef;
				import java.util.ArrayList;
				import java.util.List;
				import net.minecraft.client.gui.screens.inventory.EffectsInInventory;
				import net.minecraft.network.chat.Component;
				import org.spongepowered.asm.mixin.Mixin;
				import org.spongepowered.asm.mixin.injection.At;
				import org.spongepowered.asm.mixin.injection.ModifyArg;
				import org.spongepowered.asm.mixin.injection.ModifyVariable;
				import org.spongepowered.asm.mixin.injection.Slice;
				import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
				@Mixin(EffectsInInventory.class)
				public class AuraMixin {
				 @ModifyVariable(method = "extractText", at = @At("STORE"), ordinal = 0, require = 0, slice = @Slice(from = @At(value = "INVOKE",
				   target = "Lnet/minecraft/client/gui/components/ComponentRenderUtils;clipText(Lnet/minecraft/network/chat/Component;Lnet/minecraft/client/gui/Font;I)Lnet/minecraft/util/FormattedCharSequence;")))
				 private boolean aura$show(boolean isCompact, @Share("shown") LocalBooleanRef shown) {
				  if (!Aura.hovered) return isCompact;
				  shown.set(isCompact);
				  return true;
				 }
				 @ModifyArg(method = "extractText", require = 0, at = @At(value = "INVOKE",
				   target = "Lnet/minecraft/client/gui/GuiGraphicsExtractor;setTooltipForNextFrame(Lnet/minecraft/client/gui/Font;Ljava/util/List;Ljava/util/Optional;II)V"))
				 private List<Component> aura$lines(List<Component> lines, @Cancellable CallbackInfo callback, @Share("shown") LocalBooleanRef shown) {
				  if (!Aura.hovered) return lines;
				  List<Component> out = new ArrayList<>(shown.get() ? lines : List.of());
				  if (Aura.addLine) out.add(Component.literal("aura:glow"));
				  if (out.isEmpty()) callback.cancel();
				  return out;
				 }
				}
				"""));
		sources.add(source(name + "/Probe.java", """
				package fixture.aura;
				import com.glimmer.aura.Aura;
				import net.minecraft.client.gui.GuiGraphicsExtractor;
				import net.minecraft.client.gui.screens.inventory.EffectsInInventory;
				import net.minecraft.network.chat.Component;
				public class Probe {
				 static String show(boolean hovered, boolean addLine, int width, StringBuilder tail) {
				  Aura.hovered = hovered; Aura.addLine = addLine;
				  GuiGraphicsExtractor graphics = new GuiGraphicsExtractor();
				  new EffectsInInventory().show(graphics, Component.literal("Regeneration potion"), width, 10);
				  if (tail != null) tail.append(!graphics.tail);
				  return graphics.tooltip.toString();
				 }
				 public String probe() {
				  StringBuilder cancelled = new StringBuilder();
				  return "[Aura] fits+hovered=" + show(true, true, 300, null) + " clipped+hovered=" + show(true, true, 100, null)
				    + " fits=" + show(false, true, 300, null) + " clipped=" + show(false, true, 100, null)
				    + " fits+hovered+silent=" + show(true, false, 300, cancelled) + " cancelled-before-tail=" + cancelled;
				 }
				}
				"""));
		Path json = source(name + "/" + CONFIG, "{\"required\":false,\"package\":\"com.glimmer.aura.mixin\",\"mixins\":[\"AuraMixin\"],\"injectors\":{\"defaultRequire\":0}}");
		return WeaveHarness.fixture(work, name, sources, Map.of(CONFIG, json), List.of("-g"));
	}

	private static WeaveHarness.Result run(String label, Path fixture, Map<String, String> properties) throws Exception {
		return WeaveHarness.run(work, label, fixture, CONFIG, "aura", Ecosystem.FABRIC, EnvType.CLIENT, "fixture.aura.Probe", "probe", properties);
	}

	private static Path source(String name, String text) throws Exception {
		Path file = work.resolve("src").resolve(name);
		Files.createDirectories(file.getParent());
		Files.writeString(file, text);
		return file;
	}
}
