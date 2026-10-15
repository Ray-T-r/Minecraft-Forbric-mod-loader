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
	private static final class Resources implements Function<String, byte[]>, AutoCloseable {
		private final List<ZipFile> zips = new ArrayList<>();

		Resources() throws IOException {
			Path root = TestFixtures.stagedRoot().normalize();
			Path forge = root.resolve("merged-base/forge-runtime-interop.jar");
			if (!Files.isRegularFile(forge)) forge = root.resolve("forge-runtime/forge-runtime.jar");
			for (Path jar : List.of(root.resolve("merged-base/patched-mc-merged-26.2.jar"), forge, root.resolve("neoforge-runtime/neoforge-runtime.jar"))) {
				TestFixtures.require(Fixture.STAGED, Files.isRegularFile(jar), "staged jar absent: " + jar);
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
