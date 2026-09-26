/*
 * Copyright 2026 The Forbric Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package net.forbric.kernel.boot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.InputStream;
import java.lang.reflect.InvocationTargetException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import net.forbric.api.CompatibilityFinding;
import net.forbric.api.Side;

/**
 * Where a client declares its datapack registries, and what happens to the list when the two initialisers behind
 * it run in either order.
 *
 * <p>The sweep pack's client forced {@code RegistryDataLoader.<clinit>} from {@code Main.main}, before any Fabric
 * main and with the root registry frozen. WorldWeaver's TAIL injector there ran wover-biome's datapack entrypoint,
 * which created the codec registry wover-biome's own main would have created, and the freeze threw. Both the loader
 * and NeoForge's {@code DataPackRegistriesHooks} were erroneous from then on: no world, no data maps.
 */
class DatapackRegistryDeclarationTest {
	@AfterEach
	void clearSwitches() {
		System.clearProperty(DatapackRegistryDeclaration.DEFERRAL_SWITCH);
		System.clearProperty(DatapackRegistryDeclaration.RECONCILE_SWITCH);
	}

	// --- when --------------------------------------------------------------------------------------------------

	@Test
	void aClientWhoseFabricMainsRunInTheConstructorWaitsForThem() {
		assertTrue(DatapackRegistryDeclaration.waitsForFabric(Side.CLIENT, true, true));
	}

	/** The server's mains already precede step 3a, and its log is the proof the order is clean there. */
	@Test
	void theDedicatedServerKeepsItsOrder() {
		assertFalse(DatapackRegistryDeclaration.waitsForFabric(Side.DEDICATED_SERVER, true, true));
	}

	@Test
	void nothingWaitsWhenThereIsNothingToWaitFor() {
		assertFalse(DatapackRegistryDeclaration.waitsForFabric(Side.CLIENT, false, true), "no Fabric mods");
		assertFalse(DatapackRegistryDeclaration.waitsForFabric(Side.CLIENT, true, false),
				"-Dforbric.fabricMainInConstructor=off already runs the mains before step 3a");
	}

	@Test
	void theSwitchDeclaresFromMainAgain() {
		System.setProperty(DatapackRegistryDeclaration.DEFERRAL_SWITCH, "off");
		assertFalse(DatapackRegistryDeclaration.waitsForFabric(Side.CLIENT, true, true));
	}

	/**
	 * The early window asks before declaring. An unconditional call there is the state that poisoned the client:
	 * a later edit that "simplifies" the guard away gets this, not a world that will not load.
	 */
	@Test
	void theEarlyWindowAsksBeforeDeclaring() throws Exception {
		MethodNode drive = method("driveNativeRegistration");
		assumeTrue(drive != null, "KernelLifecycle not compiled yet");

		int asks = firstCall(drive, "waitsForFabric");
		int declares = firstCall(drive, "registerDataPackRegistries");
		assertTrue(asks >= 0, "driveNativeRegistration must ask whether a client waits for its Fabric mains");
		assertTrue(declares > asks, "and only then declare");
	}

	/**
	 * In the constructor hook the declaration comes after the Fabric mains AND after the window has closed again —
	 * every copy of the finally javac lays down — so it runs where native Fabric first initialises the loader.
	 */
	@Test
	void theConstructorHookDeclaresAfterTheWindowCloses() throws Exception {
		MethodNode hook = method("onClientEntrypoints");
		assumeTrue(hook != null, "KernelLifecycle not compiled yet");

		int declares = firstCall(hook, "registerDataPackRegistries");
		assertTrue(declares >= 0, "a client whose mains run in Minecraft.<init> must declare there");
		assertTrue(declares > lastCall(hook, "runClientEntrypoints"), "after the client entrypoints");
		assertTrue(declares > lastCall(hook, "closeClientEntrypointWindow"),
				"after the root is frozen again — the state the dedicated server declares in");
	}

	/** RegisterDataMapTypesEvent reads the declared list; client setup is where it is posted. */
	@Test
	void clientSetupCannotRunAheadOfTheDeclaration() throws Exception {
		MethodNode setup = method("onNeoClientSetup");
		assumeTrue(setup != null, "KernelLifecycle not compiled yet");

		int declares = firstCall(setup, "registerDataPackRegistries");
		int lifecycle = firstCall(setup, "fireClientSetupLifecycle");
		assertTrue(declares >= 0 && declares < lifecycle, "the declaration must precede client setup");
	}

	/** Three call sites on a client; a second post would hand every listener the event twice. */
	@Test
	void theDeclarationRunsOncePerProcess() throws Exception {
		MethodNode declare = method("registerDataPackRegistries");
		assumeTrue(declare != null, "KernelLifecycle not compiled yet");

		MethodInsnNode first = null;
		for (AbstractInsnNode insn : declare.instructions.toArray()) {
			if (insn instanceof MethodInsnNode call) {
				first = call;
				break;
			}
		}
		assertNotNull(first);
		assertEquals("compareAndSet", first.name, "the once-guard must be the first thing it does");
	}

	// --- the two initialisers, in either order ------------------------------------------------------------------

	/**
	 * The premise of the whole fix, on stand-ins with the merged base's shape: NeoForge patched the loader's
	 * initialiser to call into the hooks just before it returns, and the hooks' initialiser copies the loader's
	 * list. Loader first, and the copy is taken before the TAIL injector runs.
	 */
	@Test
	void initialisingTheLoaderFirstLosesWhatItsTailInjectorAdds() throws Exception {
		ClassLoader fresh = freshPair();
		Class.forName(FakeLoader.class.getName(), true, fresh);

		assertEquals(List.of("minecraft:biome", "wover:biome_data"), worldgen(fresh));
		assertEquals(List.of("minecraft:biome"), neo(fresh),
				"NeoForge's copy is vanilla's: this is why the kernel must never force the loader first");
	}

	/** The hooks first — step 3a's order and native NeoForge's — and the copy sees the injector's entry. */
	@Test
	void initialisingTheHooksFirstSeesIt() throws Exception {
		ClassLoader fresh = freshPair();
		Class.forName(FakeHooks.class.getName(), true, fresh);

		assertEquals(List.of("minecraft:biome", "wover:biome_data"), neo(fresh));
	}

	/** Nothing the kernel controls picks the order, so the reconcile restores the entry in either one. */
	@Test
	void theReconcilePutsItBackWhicheverRanFirst() throws Exception {
		ClassLoader fresh = freshPair();
		Class.forName(FakeLoader.class.getName(), true, fresh);
		List<String> loader = worldgen(fresh);
		List<String> neo = neo(fresh);

		List<Object> replaced = new ArrayList<>();
		List<Object> declared = DatapackRegistryDeclaration.reconcile(loader, neo, Function.identity(),
				e -> neo.add((String) e), replaced);

		assertEquals(List.of("wover:biome_data"), declared);
		assertEquals(loader, neo);
		assertSame(loader.get(1), neo.get(1), "the loader's own entry, not a copy");
		assertTrue(replaced.isEmpty());
	}

	@Test
	void theReconcileIsANoOpWhenTheOrderWasRight() throws Exception {
		ClassLoader fresh = freshPair();
		Class.forName(FakeHooks.class.getName(), true, fresh);
		List<String> neo = neo(fresh);

		assertTrue(DatapackRegistryDeclaration.reconcile(worldgen(fresh), neo, Function.identity(),
				e -> neo.add((String) e), new ArrayList<>()).isEmpty());
		assertEquals(2, neo.size());
	}

	@Test
	void theReconcileSwitchLeavesNeoForgesListAsCopied() throws Exception {
		System.setProperty(DatapackRegistryDeclaration.RECONCILE_SWITCH, "off");
		ClassLoader fresh = freshPair();
		Class.forName(FakeLoader.class.getName(), true, fresh);
		List<String> neo = neo(fresh);

		assertTrue(DatapackRegistryDeclaration.reconcile(worldgen(fresh), neo, Function.identity(),
				e -> neo.add((String) e), new ArrayList<>()).isEmpty());
		assertEquals(List.of("minecraft:biome"), neo);
	}

	/** Declared by key, so an entry replaced in place cannot be fixed here — only named. */
	@Test
	void anEntryReplacedInPlaceIsNamedNotDeclaredTwice() {
		record Data(String key, String codec) { }
		Data vanilla = new Data("minecraft:biome", "vanilla");
		List<Data> neo = new ArrayList<>(List.of(vanilla));
		List<Data> loader = List.of(new Data("minecraft:biome", "mixin's"));

		List<Object> replaced = new ArrayList<>();
		List<Object> declared = DatapackRegistryDeclaration.reconcile(loader, neo, e -> ((Data) e).key(),
				e -> neo.add((Data) e), replaced);

		assertTrue(declared.isEmpty());
		assertEquals(List.of("minecraft:biome"), replaced);
		assertEquals(List.of(vanilla), neo);
	}

	// --- the report ---------------------------------------------------------------------------------------------

	/** The stack the sweep pack's client printed, reduced to the frames the finding reads. */
	@Test
	void aPoisonedLoaderIsAFindingThatSaysWhatItCosts() {
		IllegalStateException frozen = new IllegalStateException(
				"Registry is already frozen (trying to add key ResourceKey[minecraft:root / wover:wover/biome_codec])");
		frozen.setStackTrace(new StackTraceElement[] {
				frame("net.minecraft.core.MappedRegistry", "validateWrite"),
				frame("org.betterx.wover.biome.impl.BiomeCodecRegistryImpl", "<clinit>"),
		});
		ExceptionInInitializerError init = new ExceptionInInitializerError(frozen);
		init.setStackTrace(new StackTraceElement[] {
				frame("org.betterx.wover.biome.impl.data.BiomeDataRegistryImpl", "initialize"),
				frame("net.minecraft.resources.RegistryDataLoader", "handler$cgo000$wover-core$wover_init"),
				frame("net.minecraft.resources.RegistryDataLoader", "<clinit>"),
				frame("net.neoforged.neoforge.registries.DataPackRegistriesHooks", "<clinit>"),
				frame("net.forbric.kernel.boot.KernelLifecycle", "registerDataPackRegistries"),
		});
		CompatibilityFinding finding = DatapackRegistryDeclaration.poisonedLoader(new InvocationTargetException(init));

		assertNotNull(finding);
		assertTrue(finding.confirmedRequired());
		assertEquals("forbric", finding.modId());
		assertTrue(finding.detail().contains("RegistryDataLoader"), finding.detail());
		assertTrue(finding.detail().contains("wover-core"), finding.detail());
		assertTrue(finding.detail().contains("no world can be created, loaded or joined"), finding.detail());
		assertTrue(finding.evidence().stream().anyMatch(e -> e.contains("Registry is already frozen")),
				finding.evidence().toString());
	}

	/** The second touch of an erroneous class says so in its message, with no initialiser frame at all. */
	@Test
	void aLaterTouchOfThePoisonedHooksIsRecognisedToo() {
		CompatibilityFinding finding = DatapackRegistryDeclaration.poisonedLoader(new NoClassDefFoundError(
				"Could not initialize class net.neoforged.neoforge.registries.DataPackRegistriesHooks"));
		assertNotNull(finding);
		assertTrue(finding.detail().contains("DataPackRegistriesHooks"), finding.detail());
	}

	@Test
	void anOrdinaryDeclarationFailureIsNotCalledAPoisonedLoader() {
		IllegalStateException other = new IllegalStateException("a mod's listener threw");
		other.setStackTrace(new StackTraceElement[] {frame("com.example.Mod", "onNewRegistry")});
		assertNull(DatapackRegistryDeclaration.poisonedLoader(other));
	}

	// --- helpers ------------------------------------------------------------------------------------------------

	/**
	 * Stand-in for {@code RegistryDataLoader}: its list, NeoForge's patched call into the hooks just before it
	 * returns, then a TAIL injector replacing the list the way wover's does.
	 */
	public static final class FakeLoader {
		public static List<String> worldgen;
		public static List<String> synced;

		static {
			worldgen = List.of("minecraft:biome");
			synced = FakeHooks.grabNetworkable(List.of());
			List<String> more = new ArrayList<>(worldgen);
			more.add("wover:biome_data");
			worldgen = List.copyOf(more);
		}

		private FakeLoader() {
		}
	}

	/** Stand-in for {@code DataPackRegistriesHooks}: its initialiser copies the loader's list. */
	public static final class FakeHooks {
		public static final List<String> NEO = new ArrayList<>(FakeLoader.worldgen);

		public static List<String> grabNetworkable(List<String> vanilla) {
			return vanilla;
		}

		private FakeHooks() {
		}
	}

	/** A loader that defines the two stand-ins itself, so each test runs their initialisers afresh. */
	private static ClassLoader freshPair() {
		return new ClassLoader(DatapackRegistryDeclarationTest.class.getClassLoader()) {
			@Override
			protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
				if (!name.equals(FakeLoader.class.getName()) && !name.equals(FakeHooks.class.getName())) {
					return super.loadClass(name, resolve);
				}
				synchronized (getClassLoadingLock(name)) {
					Class<?> c = findLoadedClass(name);
					if (c != null) return c;
					try (InputStream in = DatapackRegistryDeclarationTest.class.getClassLoader()
							.getResourceAsStream(name.replace('.', '/') + ".class")) {
						byte[] bytes = in.readAllBytes();
						return defineClass(name, bytes, 0, bytes.length);
					} catch (java.io.IOException e) {
						throw new ClassNotFoundException(name, e);
					}
				}
			}
		};
	}

	@SuppressWarnings("unchecked")
	private static List<String> worldgen(ClassLoader fresh) throws Exception {
		return (List<String>) Class.forName(FakeLoader.class.getName(), true, fresh).getField("worldgen").get(null);
	}

	@SuppressWarnings("unchecked")
	private static List<String> neo(ClassLoader fresh) throws Exception {
		return (List<String>) Class.forName(FakeHooks.class.getName(), true, fresh).getField("NEO").get(null);
	}

	private static StackTraceElement frame(String cls, String method) {
		return new StackTraceElement(cls, method, null, -1);
	}

	private static MethodNode method(String name) throws Exception {
		Path compiled = Path.of(System.getProperty("user.dir"), "build", "classes", "java", "main",
				"net", "forbric", "kernel", "boot", "KernelLifecycle.class");
		if (!Files.isRegularFile(compiled)) return null;
		ClassNode node = new ClassNode();
		new ClassReader(Files.readAllBytes(compiled)).accept(node, 0);
		for (MethodNode m : node.methods) {
			if (name.equals(m.name)) return m;
		}
		return null;
	}

	private static int firstCall(MethodNode m, String name) {
		AbstractInsnNode[] insns = m.instructions.toArray();
		for (int i = 0; i < insns.length; i++) {
			if (insns[i] instanceof MethodInsnNode call && name.equals(call.name)) return i;
		}
		return -1;
	}

	private static int lastCall(MethodNode m, String name) {
		AbstractInsnNode[] insns = m.instructions.toArray();
		for (int i = insns.length - 1; i >= 0; i--) {
			if (insns[i] instanceof MethodInsnNode call && name.equals(call.name)) return i;
		}
		return -1;
	}
}
