/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.mixin;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.function.Function;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import net.forbric.api.Ecosystem;
import net.forbric.kernel.TestFixtures;
import net.forbric.kernel.TestFixtures.Fixture;
import net.forbric.kernel.boot.DeadEventAudit;
import net.forbric.kernel.transform.DeliveryBookkeeping;
import net.forbric.kernel.transform.NativeDualEventInjector;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.*;

/**
 * The guest-mixin adapters read a target as the mixin service hands it to them: the merged base after the kernel's own
 * pre-mixin transforms, among them the stand-down's delivery bookkeeping around every method that calls both families'
 * hooks. Two of Create Fly's callbacks land only where the adapters can read the game's code there: the released-key
 * hooks on {@code KeyboardHandler.keyPress} (the join in front of the final return now holds both families' key hooks,
 * each bracketed), and the section-change hook on {@code PersistentEntitySectionManager$Callback.onMove} (both
 * families' enter-section hooks follow it, each bracketed, and the caller opens its invocation at its entry). Read
 * with the bookkeeping in the way, neither lands (the defect, kept here as the control); read through
 * {@link ForbricMixinService#codeView}, each lands exactly as on the plain merged base.
 */
class BracketedTargetAdaptersStagedTest {
	@org.junit.jupiter.api.io.TempDir Path scratch;

	private static final class Resources implements Function<String, byte[]>, AutoCloseable {
		private final List<ZipFile> zips = new ArrayList<>();

		Resources() throws IOException {
			Path root = TestFixtures.stagedRoot().normalize();
			Path forge = root.resolve(TestFixtures.FORGE_INTEROP_JAR);
			if (!Files.isRegularFile(forge)) forge = root.resolve(TestFixtures.FORGE_CARRIER_JAR);
			for (Path jar : List.of(root.resolve(TestFixtures.MERGED_BASE_JAR), forge, root.resolve(TestFixtures.NEO_CARRIER_JAR))) {
				TestFixtures.requireFile(Fixture.STAGED, jar, "staged jar absent: " + jar);
				zips.add(new ZipFile(jar.toFile()));
			}
		}

		@Override public synchronized byte[] apply(String path) {
			for (ZipFile zip : zips) {
				ZipEntry entry = zip.getEntry(path);
				if (entry == null) continue;
				try (var in = zip.getInputStream(entry)) {
					return in.readAllBytes();
				} catch (IOException unreadable) {
					return null;
				}
			}
			return null;
		}

		@Override public void close() throws IOException {
			for (ZipFile zip : zips) zip.close();
		}
	}

	/** Each target as the kernel's pre-mixin pass leaves it: the stand-down written into the merged base's class. */
	private static Function<String, byte[]> instrumented(Resources resources) {
		NativeGameReferences references = new NativeGameReferences(resources);
		NativeDualEventInjector injector = new NativeDualEventInjector(resources, DeadEventAudit.forwardedForgeEvents(),
				NativeDualEventInjector.Families.DEFAULT, owner -> references.get(Ecosystem.FORGE, owner));
		return name -> {
			byte[] bytes = resources.apply(name + ".class");
			return bytes == null ? null : injector.transform(name.replace('/', '.'), bytes, null);
		};
	}

	/**
	 * Each target as the whole pre-mixin pass leaves it, in its run-time order: the stand-down's bookkeeping, then — last
	 * in the coremod phase — the early returns vanilla compiled given back, each in a block placed in front of the tail.
	 */
	private static Function<String, byte[]> instrumentedAndSplit(Resources resources) {
		Function<String, byte[]> instrumented = instrumented(resources);
		net.forbric.kernel.transform.VanillaEarlyReturns split = new net.forbric.kernel.transform.VanillaEarlyReturns();
		return name -> {
			byte[] bytes = instrumented.apply(name);
			return bytes == null ? null : split.transform(name.replace('/', '.'), bytes, null);
		};
	}

	private static ClassNode raw(byte[] bytes) {
		if (bytes == null) return null;
		ClassNode node = new ClassNode();
		new ClassReader(bytes).accept(node, 0);
		return node;
	}

	private static Function<String, ClassNode> java(Function<String, ClassNode> game) {
		return name -> name.startsWith("java/") ? CarpetMixinAdapterTest.target(name) : game.apply(name);
	}

	@Test
	void createsReleasedKeyHooksLandOnlyWhereTheAdapterReadsTheGamesCode() throws Exception {
		try (Resources resources = new Resources()) {
			Function<String, byte[]> bytes = instrumented(resources);
			assertTrue(DeliveryBookkeeping.mentions(raw(bytes.apply(MixinKeyActionAdapter.TARGET)).methods.stream()
					.filter(m -> m.name.equals("keyPress")).findFirst().orElseThrow()), "keyPress carries the stand-down");
			var control = CreateGuestMixinFixture.mixin("com/zurrtum/create/client/mixin/KeyboardHandlerMixin");
			assertEquals(0, MixinKeyActionAdapter.adapt(control, java(name -> raw(bytes.apply(name))),
					(family, name) -> CreateInjectionAdaptersTest.nativeTarget(name)), "read with the bookkeeping in the way");

			var node = CreateGuestMixinFixture.mixin("com/zurrtum/create/client/mixin/KeyboardHandlerMixin");
			assertEquals(2, MixinKeyActionAdapter.adapt(node, java(name -> ForbricMixinService.codeView(bytes.apply(name))),
					(family, name) -> CreateInjectionAdaptersTest.nativeTarget(name)));
			for (String handler : List.of("onKeyReleased", "onKey")) {
				var at = MixinFit.atNodes(MixinFit.injectorOf(node.methods.stream().filter(m -> m.name.equals(handler)).findFirst().orElseThrow())).getFirst();
				assertEquals("TAIL", MixinFit.value(at, "value"), handler);
				assertNull(MixinFit.value(at, "ordinal"), handler);
			}
			// The same answer as on the plain merged base.
			var plain = CreateGuestMixinFixture.mixin("com/zurrtum/create/client/mixin/KeyboardHandlerMixin");
			assertEquals(2, MixinKeyActionAdapter.adapt(plain, CarpetMixinAdapterTest::target, (family, name) -> CreateInjectionAdaptersTest.nativeTarget(name)));
			assertArrayEquals(CarpetMixinAdapterTest.bytes(plain), CarpetMixinAdapterTest.bytes(node));
		}
	}

	/**
	 * The released-key hooks on a {@code keyPress} whose window guard has its early return back (a block in front of the
	 * tail, which the join's run jumps over): Create Fly's two and Balm's TAIL land exactly as on the plain merged base, and
	 * neither Create's handlers nor Balm's run on a release — natively the release returns before the final return.
	 */
	@Test
	void releasedKeyHooksLandAfterTheEarlyReturnsAreGivenBack() throws Exception {
		try (Resources resources = new Resources()) {
			Function<String, byte[]> bytes = instrumentedAndSplit(resources);
			MethodNode keyPress = ForbricMixinService.codeView(bytes.apply(MixinKeyActionAdapter.TARGET)).methods.stream()
					.filter(m -> m.name.equals("keyPress")).findFirst().orElseThrow();
			long returns = java.util.stream.StreamSupport.stream(keyPress.instructions.spliterator(), false)
					.filter(i -> i.getOpcode() == org.objectweb.asm.Opcodes.RETURN).count();
			MethodNode plainKeyPress = CarpetMixinAdapterTest.target(MixinKeyActionAdapter.TARGET).methods.stream()
					.filter(m -> m.name.equals("keyPress")).findFirst().orElseThrow();
			long plainReturns = java.util.stream.StreamSupport.stream(plainKeyPress.instructions.spliterator(), false)
					.filter(i -> i.getOpcode() == org.objectweb.asm.Opcodes.RETURN).count();
			assertEquals(plainReturns + 1, returns, "the window guard's early return is given back in front of the tail");

			Function<String, ClassNode> runtime = java(name -> ForbricMixinService.codeView(bytes.apply(name)));
			var create = CreateGuestMixinFixture.mixin("com/zurrtum/create/client/mixin/KeyboardHandlerMixin");
			assertEquals(2, MixinKeyActionAdapter.adapt(create, runtime, (family, name) -> CreateInjectionAdaptersTest.nativeTarget(name)),
					"both released-key hooks are read on the split body");
			var plain = CreateGuestMixinFixture.mixin("com/zurrtum/create/client/mixin/KeyboardHandlerMixin");
			assertEquals(2, MixinKeyActionAdapter.adapt(plain, CarpetMixinAdapterTest::target, (family, name) -> CreateInjectionAdaptersTest.nativeTarget(name)));
			assertArrayEquals(CarpetMixinAdapterTest.bytes(plain), CarpetMixinAdapterTest.bytes(create));

			var balm = CarpetMixinAdapterTest.from(Fixture.THIRD_PARTY, Path.of("build/sweep80-mac/mods/balm-fabric-26.2-26.2.0.9.jar"),
					"net/blay09/mods/balm/fabric/internal/mixin/KeyboardHandlerMixin");
			assertEquals(1, MixinKeyActionAdapter.adapt(balm, runtime, (family, name) -> CreateInjectionAdaptersTest.nativeTarget(name)),
					"Balm's TAIL is read on the split body");
			var balmPlain = CarpetMixinAdapterTest.from(Fixture.THIRD_PARTY, Path.of("build/sweep80-mac/mods/balm-fabric-26.2-26.2.0.9.jar"),
					"net/blay09/mods/balm/fabric/internal/mixin/KeyboardHandlerMixin");
			assertEquals(1, MixinKeyActionAdapter.adapt(balmPlain, CarpetMixinAdapterTest::target, (family, name) -> CreateInjectionAdaptersTest.nativeTarget(name)));
			assertArrayEquals(CarpetMixinAdapterTest.bytes(balmPlain), CarpetMixinAdapterTest.bytes(balm));

			// Executed: the guard each relocated handler now carries runs it on a press and a repeat, never on a release.
			Map<String, String> sources = Map.of(
					"com/zurrtum/create/client/mixin/KeyboardHandlerMixin.java", "package com.zurrtum.create.client.mixin; public class KeyboardHandlerMixin { public java.util.List<Boolean> calls=new java.util.ArrayList<>(); public Object event; private void onKey(net.minecraft.client.input.KeyEvent e,boolean p){event=e;calls.add(p);} }",
					"net/minecraft/client/input/KeyEvent.java", "package net.minecraft.client.input; public class KeyEvent {}",
					"org/spongepowered/asm/mixin/injection/callback/CallbackInfo.java", "package org.spongepowered.asm.mixin.injection.callback; public class CallbackInfo {}",
					"com/llamalad7/mixinextras/sugar/ref/LocalBooleanRef.java", "package com.llamalad7.mixinextras.sugar.ref; public interface LocalBooleanRef { boolean get(); void set(boolean v); }",
					"fixture/Flag.java", "package fixture; public class Flag implements com.llamalad7.mixinextras.sugar.ref.LocalBooleanRef { public boolean on; public Flag(boolean on) { this.on = on; } public boolean get() { return on; } public void set(boolean v) { on = v; } }",
					"fixture/Keep.java", "package fixture; public class Keep {}");
			java.util.function.Predicate<MethodNode> guards = m -> m.desc.startsWith(MixinKeyActionAdapter.HANDLER.substring(0, MixinKeyActionAdapter.HANDLER.length() - 2));
			try (var loader = CreateGuestMixinFixture.executable(Files.createDirectories(scratch.resolve("create")), create, sources, guards)) {
				Class<?> type = loader.loadClass(create.name.replace('/', '.')), eventType = loader.loadClass("net.minecraft.client.input.KeyEvent"),
						callbackType = loader.loadClass("org.spongepowered.asm.mixin.injection.callback.CallbackInfo");
				Class<?> flagType = loader.loadClass("com.llamalad7.mixinextras.sugar.ref.LocalBooleanRef"), flagImpl = loader.loadClass("fixture.Flag");
				Object receiver = type.getConstructor().newInstance(), event = eventType.getConstructor().newInstance();
				var released = type.getMethod("onKeyReleased", long.class, int.class, eventType, callbackType, flagType);
				var pressed = type.getMethod("onKey", long.class, int.class, eventType, callbackType, flagType);
				for (boolean global : new boolean[] {false, true}) for (int action : new int[] {0, 1, 2}) {
					((List<?>) type.getField("calls").get(receiver)).clear();
					Object flag = flagImpl.getConstructor(boolean.class).newInstance(global);
					released.invoke(receiver, 0L, action, event, null, flag);
					pressed.invoke(receiver, 0L, action, event, null, flag);
					assertEquals(action == 0 || global ? List.of() : List.of(false, true), type.getField("calls").get(receiver),
							"Create, action " + action + (global ? ", after a handled global key" : ""));
				}
			}
			String inner = MixinHandlerShim.asideName(balm.name, "keyPress", MixinKeyActionAdapter.SUFFIX);
			Map<String, String> balmSources = Map.of(
					"net/blay09/mods/balm/fabric/internal/mixin/KeyboardHandlerMixin.java", "package net.blay09.mods.balm.fabric.internal.mixin; public class KeyboardHandlerMixin { public java.util.List<Integer> calls=new java.util.ArrayList<>(); private void "
							+ inner + "(long w,int a,net.minecraft.client.input.KeyEvent e,org.spongepowered.asm.mixin.injection.callback.CallbackInfo c){calls.add(a);} }",
					"net/minecraft/client/input/KeyEvent.java", "package net.minecraft.client.input; public class KeyEvent {}",
					"org/spongepowered/asm/mixin/injection/callback/CallbackInfo.java", "package org.spongepowered.asm.mixin.injection.callback; public class CallbackInfo {}",
					"com/llamalad7/mixinextras/sugar/ref/LocalBooleanRef.java", "package com.llamalad7.mixinextras.sugar.ref; public interface LocalBooleanRef { boolean get(); void set(boolean v); }",
					"fixture/Flag.java", "package fixture; public class Flag implements com.llamalad7.mixinextras.sugar.ref.LocalBooleanRef { public boolean on; public Flag(boolean on) { this.on = on; } public boolean get() { return on; } public void set(boolean v) { on = v; } }",
					"fixture/Keep.java", "package fixture; public class Keep {}");
			try (var loader = CreateGuestMixinFixture.executable(Files.createDirectories(scratch.resolve("balm")), balm, balmSources,
					m -> m.name.equals("keyPress") && guards.test(m))) {
				Class<?> type = loader.loadClass(balm.name.replace('/', '.')), eventType = loader.loadClass("net.minecraft.client.input.KeyEvent"),
						callbackType = loader.loadClass("org.spongepowered.asm.mixin.injection.callback.CallbackInfo");
				Class<?> flagType = loader.loadClass("com.llamalad7.mixinextras.sugar.ref.LocalBooleanRef"), flagImpl = loader.loadClass("fixture.Flag");
				Object receiver = type.getConstructor().newInstance(), event = eventType.getConstructor().newInstance();
				var tail = type.getMethod("keyPress", long.class, int.class, eventType, callbackType, flagType);
				for (boolean global : new boolean[] {false, true})
					for (int action : new int[] {0, 1, 2}) tail.invoke(receiver, 0L, action, event, null, flagImpl.getConstructor(boolean.class).newInstance(global));
				assertEquals(List.of(1, 2), type.getField("calls").get(receiver), "Balm's TAIL: press and repeat, never release, never after a handled global key");
			}
		}
	}

	@Test
	void createsSectionHookLocalIsPinnedOnlyWhereTheAdapterReadsTheGamesCode() throws Exception {
		try (Resources resources = new Resources()) {
			Function<String, byte[]> bytes = instrumented(resources);
			String owner = "net/minecraft/world/level/entity/PersistentEntitySectionManager$Callback";
			assertTrue(DeliveryBookkeeping.mentions(raw(bytes.apply(owner)).methods.stream()
					.filter(m -> m.name.equals("onMove")).findFirst().orElseThrow()), "onMove carries the stand-down");
			String mixin = "com/zurrtum/create/mixin/PersistentEntitySectionManagerCallbackMixin";
			var control = CreateGuestMixinFixture.mixin(mixin);
			assertEquals(0, MixinCarrierCallbackAdapters.adapt(control, java(name -> raw(bytes.apply(name))),
					(family, name) -> CreateInjectionAdaptersTest.nativeTarget(name)), "read with the bookkeeping in the way");

			var node = CreateGuestMixinFixture.mixin(mixin);
			assertEquals(1, MixinCarrierCallbackAdapters.adapt(node, java(name -> ForbricMixinService.codeView(bytes.apply(name))),
					(family, name) -> CreateInjectionAdaptersTest.nativeTarget(name)));
			var plain = CreateGuestMixinFixture.mixin(mixin);
			assertEquals(1, MixinCarrierCallbackAdapters.adapt(plain, CarpetMixinAdapterTest::target, (family, name) -> CreateInjectionAdaptersTest.nativeTarget(name)));
			assertArrayEquals(CarpetMixinAdapterTest.bytes(plain), CarpetMixinAdapterTest.bytes(node));
		}
	}
}
