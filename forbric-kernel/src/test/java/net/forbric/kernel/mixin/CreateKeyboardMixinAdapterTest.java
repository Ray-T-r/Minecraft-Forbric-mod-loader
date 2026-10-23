/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.mixin;

import org.objectweb.asm.tree.MethodNode;
import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Path;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Create Fly's released key hooks: {@code RETURN ordinal=5} and {@code TAIL} on {@code keyPress}. In vanilla 26.2 ordinal 5
 * is the final return — TAIL — which a release never reaches (the release returns at ordinal 4), so on native Fabric both
 * hooks run on a press and on a repeat, in declaration order, and neither on a release. The merged body must do the same.
 */
class CreateKeyboardMixinAdapterTest {
	@TempDir Path root;
	@Test void releasedHooksRunWhereVanillasFinalReturnRuns() throws Exception {
		var node = CreateGuestMixinFixture.mixin("com/zurrtum/create/client/mixin/KeyboardHandlerMixin");
		assertEquals(2, MixinKeyActionAdapter.adapt(node, CarpetMixinAdapterTest::target, (family, name) -> CreateInjectionAdaptersTest.nativeTarget(name)));
		CarpetMixinAdapterTest.verify(node);
		assertEquals(0, MixinKeyActionAdapter.adapt(node, CarpetMixinAdapterTest::target, (family, name) -> CreateInjectionAdaptersTest.nativeTarget(name)));
		for (String handler : List.of("onKeyReleased", "onKey")) {
			var at = MixinFit.atNodes(MixinFit.injectorOf(node.methods.stream().filter(m -> m.name.equals(handler)).findFirst().orElseThrow())).getFirst();
			assertEquals("TAIL", MixinFit.value(at, "value"), handler);
			assertNull(MixinFit.value(at, "ordinal"), handler);
		}
		Map<String, String> sources = Map.of(
				"com/zurrtum/create/client/mixin/KeyboardHandlerMixin" + ".java", "package com.zurrtum.create.client.mixin; public class KeyboardHandlerMixin { public java.util.List<Boolean> calls=new java.util.ArrayList<>(); public Object event; private void onKey(net.minecraft.client.input.KeyEvent e,boolean p){event=e;calls.add(p);} }",
				"net/minecraft/client/input/KeyEvent.java", "package net.minecraft.client.input; public class KeyEvent {}",
				"org/spongepowered/asm/mixin/injection/callback/CallbackInfo.java", "package org.spongepowered.asm.mixin.injection.callback; public class CallbackInfo {}",
				"com/llamalad7/mixinextras/sugar/ref/LocalBooleanRef.java", "package com.llamalad7.mixinextras.sugar.ref; public interface LocalBooleanRef { boolean get(); void set(boolean v); }",
				"fixture/Flag.java", "package fixture; public class Flag implements com.llamalad7.mixinextras.sugar.ref.LocalBooleanRef { public boolean on; public Flag(boolean on) { this.on = on; } public boolean get() { return on; } public void set(boolean v) { on = v; } }",
				"fixture/Keep.java", "package fixture; public class Keep {}");
		// The global key's share: its observer is a MixinExtras value modifier on handleGlobalKeyPress, set when it answered true.
		MethodNode observer = node.methods.stream().filter(m -> m.name.startsWith(MixinKeyActionAdapter.EXIT_SHARE)).findFirst().orElseThrow();
		assertEquals(MixinKeyActionAdapter.MODIFY_EXPRESSION_VALUE, MixinFit.injectorOf(observer).desc);
		assertEquals("Lnet/minecraft/client/Minecraft;handleGlobalKeyPress(Lcom/mojang/blaze3d/platform/InputConstants$Key;Z)Z",
				MixinFit.value(MixinFit.atNodes(MixinFit.injectorOf(observer)).getFirst(), "target"));
		try (var loader = CreateGuestMixinFixture.executable(root, node, sources, m -> m.desc.startsWith(MixinKeyActionAdapter.HANDLER.substring(0, MixinKeyActionAdapter.HANDLER.length() - 2)))) {
			Class<?> type = loader.loadClass(node.name.replace('/', '.')), eventType = loader.loadClass("net.minecraft.client.input.KeyEvent"), callbackType = loader.loadClass("org.spongepowered.asm.mixin.injection.callback.CallbackInfo");
			Class<?> flagType = loader.loadClass("com.llamalad7.mixinextras.sugar.ref.LocalBooleanRef"), flagImpl = loader.loadClass("fixture.Flag");
			Object receiver = type.getConstructor().newInstance(), event = eventType.getConstructor().newInstance();
			var released = type.getMethod("onKeyReleased", long.class, int.class, eventType, callbackType, flagType);
			var pressed = type.getMethod("onKey", long.class, int.class, eventType, callbackType, flagType);
			for (boolean global : new boolean[]{false, true}) for (int action : new int[]{0, 1, 2}) {
				((List<?>) type.getField("calls").get(receiver)).clear();
				Object flag = flagImpl.getConstructor(boolean.class).newInstance(global);
				released.invoke(receiver, 0L, action, event, null, flag); pressed.invoke(receiver, 0L, action, event, null, flag);
				assertEquals(action == 0 || global ? List.of() : List.of(false, true), type.getField("calls").get(receiver),
						"action " + action + (global ? ", after a global key handleGlobalKeyPress handled (natively returned there)" : ""));
			}
		}
	}
	@Test void vanillaKeepsTheOriginalReturnSelectors() throws Exception {
		var node = CreateGuestMixinFixture.mixin("com/zurrtum/create/client/mixin/KeyboardHandlerMixin"); byte[] before = CarpetMixinAdapterTest.bytes(node);
		assertEquals(0, MixinKeyActionAdapter.adapt(node, name -> {try {return StagedFabricMixinFixture.game(name, true);} catch(Exception e){throw new AssertionError(e);}},
				(family, name) -> CreateInjectionAdaptersTest.nativeTarget(name)));
		assertArrayEquals(before, CarpetMixinAdapterTest.bytes(node));
	}
}
