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

package net.forbric.kernel.transform;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

/**
 * Covers the MinecraftForge face put back on the merged {@code KeyMapping}, against the real artifact.
 *
 * <p>onekeyminer died in its class initializer on
 * {@code AbstractMethodError: KeyMapping.setKeyConflictContext(…IKeyConflictContext) is abstract}, which cost it
 * every keybinding. The merge had kept MinecraftForge's constructors and fields but not its accessors.
 *
 * <p>The assertions that matter are not "the methods exist" — they are that the methods reach the fields the
 * game READS. Re-adding them over the MinecraftForge fields would have silenced the crash and left a Forge mod's
 * conflict context ignored, which is worse.
 */
class MergedBaseKeyMappingFaceTest {
	private static final Path MERGED = Path.of(System.getProperty("user.dir"), "..", "forbric-loader", "run",
			"merged-base", "patched-mc-merged-26.2.jar").normalize();
	private static final String KEY_MAPPING = "net/minecraft/client/KeyMapping";
	private static final String MF_CONTEXT = "Lnet/minecraftforge/client/settings/IKeyConflictContext;";
	private static final String NEO_CONTEXT = "Lnet/neoforged/neoforge/client/settings/IKeyConflictContext;";
	private static final String MF_MODIFIER = "Lnet/minecraftforge/client/settings/KeyModifier;";
	private static final String NEO_MODIFIER = "Lnet/neoforged/neoforge/client/settings/KeyModifier;";
	private static final String BRIDGE = "net/forbric/kernel/runtime/KernelForgeKeyBindings";

	@Test
	void theAccessorsAForgeModCallsAreThere() throws IOException {
		ClassNode node = repaired();
		for (String[] shape : new String[][] {
				{"setKeyConflictContext", "(" + MF_CONTEXT + ")V"},
				{"getKeyConflictContext", "()" + MF_CONTEXT},
				{"getKeyModifier", "()" + MF_MODIFIER},
				{"getDefaultKeyModifier", "()" + MF_MODIFIER},
				{"setKeyModifierAndCode", "(" + MF_MODIFIER + "Lcom/mojang/blaze3d/platform/InputConstants$Key;)V"},
		}) {
			assertTrue(find(node, shape[0], shape[1]) != null,
					shape[0] + shape[1] + " is missing — a Forge mod calling it dies in its class initializer");
		}
	}

	/** The NeoForge originals must survive untouched: a getter pair differing only in return type is legal. */
	@Test
	void theNeoForgeAccessorsAreStillThere() throws IOException {
		ClassNode node = repaired();
		assertTrue(find(node, "getKeyConflictContext", "()" + NEO_CONTEXT) != null);
		assertTrue(find(node, "setKeyConflictContext", "(" + NEO_CONTEXT + ")V") != null);
	}

	/**
	 * The assertion with teeth. A MinecraftForge setter that wrote the MinecraftForge field would pass every
	 * "does it exist" check and leave the binding behaving as though nothing had been set, because every live
	 * consumer reads NeoForge's.
	 */
	@Test
	void theForgeSetterReachesTheFieldTheGameReads() throws IOException {
		MethodNode setter = find(repaired(), "setKeyConflictContext", "(" + MF_CONTEXT + ")V");
		assertTrue(setter != null);

		boolean converts = false;
		boolean reachesNeo = false;
		for (AbstractInsnNode insn : setter.instructions) {
			if (insn instanceof MethodInsnNode call && BRIDGE.equals(call.owner)
					&& "toNeoContext".equals(call.name)) {
				converts = true;
			}
			if (insn instanceof MethodInsnNode call && KEY_MAPPING.equals(call.owner)
					&& "setKeyConflictContext".equals(call.name) && ("(" + NEO_CONTEXT + ")V").equals(call.desc)) {
				reachesNeo = true;
			}
			if (insn instanceof FieldInsnNode f && f.getOpcode() == Opcodes.PUTFIELD
					&& MF_CONTEXT.equals(f.desc)) {
				throw new AssertionError("the setter writes the MinecraftForge field, which nothing reads — the "
						+ "crash would be gone and the conflict context would never take effect");
			}
		}
		assertTrue(converts, "the MinecraftForge context must be adapted, not cast");
		assertTrue(reachesNeo, "and stored through the NeoForge setter, which is what same() consults");
	}

	/**
	 * The MinecraftForge-typed constructors write only the dead fields, leaving the NeoForge ones null — so a
	 * mod using one gets a mapping whose first conflict check is a NullPointerException.
	 */
	@Test
	void theForgeConstructorsFillTheLiveFieldsToo() throws IOException {
		ClassNode node = repaired();
		int checked = 0;
		for (MethodNode m : node.methods) {
			if (!"<init>".equals(m.name) || !m.desc.contains(MF_CONTEXT)) continue;
			checked++;
			List<String> written = new ArrayList<>();
			for (AbstractInsnNode insn : m.instructions) {
				if (insn instanceof FieldInsnNode f && f.getOpcode() == Opcodes.PUTFIELD) written.add(f.desc);
			}
			assertTrue(written.contains(NEO_CONTEXT),
					m.desc + " never writes the NeoForge conflict context, so same() will NPE on it");
			assertTrue(written.contains(NEO_MODIFIER),
					m.desc + " never writes the NeoForge key modifier");
		}
		assumeTrue(checked > 0, "this base declares no MinecraftForge-typed KeyMapping constructor");
	}

	/**
	 * WHERE the mirror lands, which is the whole of it.
	 *
	 * <p>The constructor's own tail registers the binding: {@code GETSTATIC KeyMapping.MAP},
	 * {@code KeyMappingLookup.put(key, this)}, {@code RETURN}. That put reads the mapping back through
	 * {@code getKeyModifier()} — the MinecraftForge-faced accessor this transformer adds, which reads the
	 * NEOFORGE field. Mirroring before the RETURN put the write after the read, so the field was still null at
	 * the put, and MinecraftForge's lookup did {@code computeIfAbsent} on the result. Every Forge-typed key
	 * binding died in its own {@code <clinit>} with an NPE raised inside MinecraftForge's own code.
	 *
	 * <p>Assert the ORDER, not merely the presence: the previous test passes either way.
	 */
	@Test
	void theLiveFieldsAreWrittenBeforeTheConstructorRegistersTheBinding() throws IOException {
		ClassNode node = repaired();
		int checked = 0;
		for (MethodNode m : node.methods) {
			if (!"<init>".equals(m.name) || !m.desc.contains(MF_CONTEXT)) continue;

			int firstLookup = -1;
			int lastLiveWrite = -1;
			int i = 0;
			for (AbstractInsnNode insn : m.instructions) {
				if (firstLookup < 0 && insn instanceof FieldInsnNode f && f.getOpcode() == Opcodes.GETSTATIC
						&& "MAP".equals(f.name)) {
					firstLookup = i;
				}
				if (insn instanceof FieldInsnNode f && f.getOpcode() == Opcodes.PUTFIELD
						&& (NEO_CONTEXT.equals(f.desc) || NEO_MODIFIER.equals(f.desc))) {
					lastLiveWrite = i;
				}
				i++;
			}
			if (firstLookup < 0) continue; // a delegating constructor; it has no registration of its own
			checked++;
			assertTrue(lastLiveWrite >= 0 && lastLiveWrite < firstLookup,
					m.desc + " writes the NeoForge fields at " + lastLiveWrite + " but reaches the key lookup at "
							+ firstLookup + " — the lookup reads those fields back through the adapted "
							+ "getKeyModifier(), so a write that comes after it is an NPE inside MinecraftForge");
		}
		assumeTrue(checked > 0, "this base has no MinecraftForge-typed constructor that registers a binding");
	}

	/**
	 * And WHICH lookup it registers into. Both {@code getAll} overloads read NeoForge's {@code MAP}, so a binding
	 * put into MinecraftForge's would sit in a map nothing ever reads: the key exists, binds, shows in the
	 * Controls screen and never fires. The two {@code put} methods are descriptor-identical, so this is a field
	 * descriptor and an owner and nothing else.
	 */
	@Test
	void theConstructorRegistersIntoTheLookupTheGameReads() throws IOException {
		ClassNode node = repaired();
		int checked = 0;
		for (MethodNode m : node.methods) {
			if (!"<init>".equals(m.name) || !m.desc.contains(MF_CONTEXT)) continue;
			for (AbstractInsnNode insn : m.instructions) {
				if (insn instanceof FieldInsnNode f && f.getOpcode() == Opcodes.GETSTATIC && "MAP".equals(f.name)) {
					checked++;
					assertTrue(f.desc.startsWith("Lnet/neoforged/"),
							m.desc + " registers into " + f.desc + ", which nothing reads — the binding would "
									+ "exist, bind and never fire");
				}
				if (insn instanceof MethodInsnNode call && "put".equals(call.name)
						&& call.owner.endsWith("KeyMappingLookup")) {
					assertTrue(call.owner.startsWith("net/neoforged/"),
							m.desc + " calls " + call.owner + ".put, so the binding lands in the dead lookup");
				}
			}
		}
		assumeTrue(checked > 0, "this base has no MinecraftForge-typed constructor that registers a binding");
	}

	@Test
	void aSecondPassLeavesTheClassAlone() throws IOException {
		byte[] once = new ForbricMergedBaseCompatTransformer()
				.transform("net.minecraft.client.KeyMapping", original(), null);
		byte[] twice = new ForbricMergedBaseCompatTransformer()
				.transform("net.minecraft.client.KeyMapping", once, null);
		assertSame(once, twice, "re-running must not stack another set of accessors on top");
	}

	/**
	 * The adapters themselves, loaded and driven for real.
	 *
	 * <p>{@code KernelForgeKeyBindings} is in the game-side source set, which is not on the test classpath by
	 * design, so it is loaded from its compiled output over the staged carriers — the same shape
	 * {@code PassiveSeederLoadingModListTest} uses. Without this, the conversion tables are asserted by nothing:
	 * the bytecode tests above only check that the CALL is made.
	 */
	@Test
	void theModifierMappingIsByNameWithOneDeliberateException() throws Exception {
		try (java.net.URLClassLoader cl = gameSideLoader()) {
			Class<?> bridge = Class.forName("net.forbric.kernel.runtime.KernelForgeKeyBindings", true, cl);
			java.lang.reflect.Method toForge = bridge.getMethod("toForgeModifier", Object.class);
			java.lang.reflect.Method toNeo = bridge.getMethod("toNeoModifier", Object.class);
			Class<?> neoMod = Class.forName("net.neoforged.neoforge.client.settings.KeyModifier", true, cl);
			Class<?> mfMod = Class.forName("net.minecraftforge.client.settings.KeyModifier", true, cl);

			for (String shared : List.of("CONTROL", "SHIFT", "ALT", "NONE")) {
				Object neo = enumConstant(neoMod, shared);
				assertEquals(shared, name(toForge.invoke(null, neo)), shared + " must survive the round trip");
				assertEquals(shared, name(toNeo.invoke(null, enumConstant(mfMod, shared))), shared);
			}
			assertEquals("CONTROL", name(toForge.invoke(null, enumConstant(neoMod, "CONTROL_OR_COMMAND"))),
					"NeoForge's extra constant means \"control, or command on macOS\", which is what "
							+ "MinecraftForge's CONTROL does. NONE would silently drop a modifier the player bound");
			// NONE, not null, on the way OUT. MinecraftForge's KeyMappingLookup.put reads this accessor and uses
			// the result as an EnumMap key straight away — computeIfAbsent on a null bucket, which is an NPE
			// raised inside Forge's code and blamed on whichever mod was constructing a key binding. There is no
			// such thing as a null modifier in either family: unmodified IS NONE, which is what the field starts
			// as. Null still passes through unchanged on the way IN, where nothing dereferences it.
			assertEquals("NONE", name(toForge.invoke(null, new Object[] {null})),
					"a null modifier must adapt to NONE, or MinecraftForge's own lookup NPEs on it");
			assertEquals(null, toNeo.invoke(null, new Object[] {null}), "null stays null going the other way");
		}
	}

	/** A conflict context adapted out and back must come back as the SAME object, not a second wrapper. */
	@Test
	void adaptingAContextBackAgainUnwrapsIt() throws Exception {
		try (java.net.URLClassLoader cl = gameSideLoader()) {
			Class<?> bridge = Class.forName("net.forbric.kernel.runtime.KernelForgeKeyBindings", true, cl);
			java.lang.reflect.Method toNeo = bridge.getMethod("toNeoContext", Object.class);
			java.lang.reflect.Method toForge = bridge.getMethod("toForgeContext", Object.class);

			Class<?> forgeContexts = Class.forName("net.minecraftforge.client.settings.KeyConflictContext", true, cl);
			Object inGame = enumOrField(forgeContexts, "IN_GAME");
			assumeTrue(inGame != null, "the carrier's KeyConflictContext has no IN_GAME");

			Object asNeo = toNeo.invoke(null, inGame);
			assertTrue(asNeo != null && !asNeo.equals(inGame), "it must be adapted, not returned as-is");
			assertSame(inGame, toForge.invoke(null, asNeo),
					"round-tripping must UNWRAP — stacking adapters would make conflicts() recurse a layer "
							+ "deeper on every pass through a mod that reads and re-sets its own context");
		}
	}

	/**
	 * The compiled game-side set over the staged carriers and merged base.
	 *
	 * <p>{@code net.forbric.kernel.runtime} is not on the test classpath by design, and the carriers' own
	 * {@code KeyModifier} reaches into the game for its display name, so the merged base has to be here too.
	 */
	private static java.net.URLClassLoader gameSideLoader() throws IOException {
		Path compiled = Path.of(System.getProperty("user.dir"), "build", "classes", "java", "runtime").normalize();
		Path run = Path.of(System.getProperty("user.dir"), "..", "forbric-loader", "run").normalize();
		Path forgeRt = run.resolve("forge-runtime/forge-runtime.jar");
		Path neoRt = run.resolve("neoforge-runtime/neoforge-runtime.jar");
		assumeTrue(Files.isDirectory(compiled) && Files.isRegularFile(forgeRt) && Files.isRegularFile(neoRt)
						&& Files.isRegularFile(MERGED),
				"the game-side set is not compiled, or the staged artifacts are absent");
		List<java.net.URL> urls = new ArrayList<>(List.of(compiled.toUri().toURL(), forgeRt.toUri().toURL(),
				neoRt.toUri().toURL(), MERGED.toUri().toURL()));
		// Touching the carrier's KeyModifier initialises it, and its display name reaches into the game — which
		// reaches into brigadier, DataFixerUpper, fastutil, guava and on. The whole library tree is what the game
		// itself puts on the classpath, so that is what goes here rather than chasing one NoClassDefFoundError
		// at a time.
		Path libraries = mcLibraries();
		assumeTrue(libraries != null, "the Minecraft library tree is not where this machine keeps it — skipping");
		try (var jars = Files.walk(libraries)) {
			for (Path jar : jars.filter(f -> f.toString().endsWith(".jar")).toList()) {
				urls.add(jar.toUri().toURL());
			}
		}
		return new java.net.URLClassLoader(urls.toArray(new java.net.URL[0]),
				ClassLoader.getPlatformClassLoader());
	}

	/** The same library tree {@code build.gradle} resolves brigadier from. */
	private static Path mcLibraries() {
		String configured = System.getProperty("forbric.mcLibraries");
		String env = System.getenv("MC_DIR");
		Path root = Path.of(configured != null ? configured
				: env != null ? env + "/libraries"
				: System.getProperty("user.home") + "/Library/Application Support/minecraft/libraries");
		return Files.isDirectory(root) ? root : null;
	}

	private static Object enumConstant(Class<?> type, String name) throws Exception {
		return type.getField(name).get(null);
	}

	private static Object enumOrField(Class<?> type, String name) {
		try {
			return type.getField(name).get(null);
		} catch (ReflectiveOperationException absent) {
			return null;
		}
	}

	private static String name(Object enumConstant) throws Exception {
		return (String) enumConstant.getClass().getMethod("name").invoke(enumConstant);
	}

	private static MethodNode find(ClassNode node, String name, String desc) {
		for (MethodNode m : node.methods) {
			if (m.name.equals(name) && m.desc.equals(desc)) return m;
		}
		return null;
	}

	private static ClassNode repaired() throws IOException {
		byte[] out = new ForbricMergedBaseCompatTransformer()
				.transform("net.minecraft.client.KeyMapping", original(), null);
		ClassNode node = new ClassNode();
		new ClassReader(out).accept(node, 0);
		return node;
	}

	private static byte[] original() throws IOException {
		assumeTrue(Files.isRegularFile(MERGED), "staged merged base absent — skipping the real-bytecode check");
		try (ZipFile jar = new ZipFile(MERGED.toFile())) {
			ZipEntry entry = jar.getEntry(KEY_MAPPING + ".class");
			assumeTrue(entry != null, "KeyMapping absent — this is a server-only base");
			try (InputStream in = jar.getInputStream(entry)) {
				byte[] bytes = in.readAllBytes();
				ClassNode node = new ClassNode();
				new ClassReader(bytes).accept(node, ClassReader.SKIP_CODE);
				boolean split = node.fields.stream().anyMatch(f -> "keyConflictContext".equals(f.name)
								&& MF_CONTEXT.equals(f.desc))
						&& node.fields.stream().anyMatch(f -> "keyConflictContext".equals(f.name)
								&& NEO_CONTEXT.equals(f.desc));
				assumeTrue(split, "this base no longer splits KeyMapping's conflict context");
				return bytes;
			}
		}
	}
}
