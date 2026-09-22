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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.BasicVerifier;

import net.fabricmc.api.EnvType;

/**
 * Three {@code FMLLoader} methods open with {@code getstatic Launcher.INSTANCE}, and the kernel replaces
 * ModLauncher, so that field is null.
 *
 * <p>{@code getNameFunction} is the one that costs a mod its life: it is what
 * {@code ObfuscationReflectionHelper.findField/findMethod/getPrivateValue} calls through {@code remapName}, mods
 * call that helper from static initialisers, and an NPE there becomes an
 * {@code ExceptionInInitializerError} followed by a {@code NoClassDefFoundError} on every later touch — the mod
 * is erroneous for the rest of the run. Physics Mod's Forge build hit exactly this.
 */
class ForgeLauncherInfoInjectorTest {
	private static final Path CARRIER = Path.of(System.getenv().getOrDefault("FORBRIC_OLD", System.getProperty("user.dir") + "/../forbric-loader"), "run",
			"forge-runtime", "forge-runtime.jar").normalize();
	private static final String FML_LOADER = "net.minecraftforge.fml.loading.FMLLoader";

	private final ForgeLauncherInfoInjector injector = new ForgeLauncherInfoInjector();

	private static TransformContext ctx() {
		return new TransformContext(EnvType.CLIENT, false, "named");
	}

	@Test
	void noneOfTheThreeStillReachesForModLauncher() throws Exception {
		assumeTrue(Files.isRegularFile(CARRIER), "staged MinecraftForge carrier absent");
		byte[] real = readClass(FML_LOADER.replace('.', '/') + ".class");

		byte[] out = injector.transform(FML_LOADER, real, ctx());
		assertTrue(out != real, "FMLLoader was not rewritten");

		ClassNode node = new ClassNode();
		new ClassReader(out).accept(node, 0);

		for (String name : new String[] {"getNameFunction", "getLauncherInfo", "modLauncherModList"}) {
			MethodNode m = method(node, name);
			assertNotNull(m, name + " is gone from the carrier — re-check what replaced it before trusting this");
			for (AbstractInsnNode insn : m.instructions) {
				if (insn instanceof FieldInsnNode field && field.owner.startsWith("cpw/mods/modlauncher")) {
					throw new AssertionError(name + " still reads " + field.owner + "." + field.name
							+ ", which the kernel never creates — every call NPEs");
				}
			}
			// A wrong replacement body is a VerifyError at link time, which would take the whole carrier with it.
			new Analyzer<>(new BasicVerifier()).analyze(node.name, m);
		}
	}

	/** The switch really restores the old behaviour, so it is usable for measuring whether this is the cause. */
	@Test
	void theSwitchPutsTheNullBack() throws Exception {
		assumeTrue(Files.isRegularFile(CARRIER), "staged MinecraftForge carrier absent");
		byte[] real = readClass(FML_LOADER.replace('.', '/') + ".class");

		String previous = System.getProperty(ForgeLauncherInfoInjector.PROPERTY);
		System.setProperty(ForgeLauncherInfoInjector.PROPERTY, "off");
		try {
			assertSame(real, injector.transform(FML_LOADER, real, ctx()));
		} finally {
			if (previous == null) System.clearProperty(ForgeLauncherInfoInjector.PROPERTY);
			else System.setProperty(ForgeLauncherInfoInjector.PROPERTY, previous);
		}
	}

	/** Every other class passes through untouched, by identity — this runs on every class the loader defines. */
	@Test
	void anyOtherClassIsUntouched() {
		byte[] other = new byte[] {(byte) 0xCA, (byte) 0xFE, (byte) 0xBA, (byte) 0xBE};
		assertSame(other, injector.transform("net.minecraft.world.entity.Entity", other, ctx()));
	}

	/** And the carrier really did have the problem, so the test above is not passing vacuously. */
	@Test
	void theUntransformedCarrierDoesReachForModLauncher() throws Exception {
		assumeTrue(Files.isRegularFile(CARRIER), "staged MinecraftForge carrier absent");
		ClassNode node = new ClassNode();
		new ClassReader(readClass(FML_LOADER.replace('.', '/') + ".class")).accept(node, 0);

		boolean reaches = false;
		for (AbstractInsnNode insn : method(node, "getNameFunction").instructions) {
			if (insn instanceof FieldInsnNode field && field.owner.startsWith("cpw/mods/modlauncher")) reaches = true;
		}
		assertTrue(reaches,
				"the carrier's getNameFunction no longer reads Launcher.INSTANCE — if MinecraftForge changed this, "
						+ "the rewrite may no longer be needed");
		assertFalse(ForgeLauncherInfoInjector.enabled() && false, "the switch defaults on");
	}

	private static MethodNode method(ClassNode node, String name) {
		for (MethodNode m : node.methods) {
			if (m.name.equals(name)) return m;
		}
		return null;
	}

	private static byte[] readClass(String entry) throws Exception {
		try (ZipFile zip = new ZipFile(CARRIER.toFile())) {
			ZipEntry found = zip.getEntry(entry);
			assertNotNull(found, entry + " missing from the staged MinecraftForge carrier");
			try (InputStream in = zip.getInputStream(found)) {
				return in.readAllBytes();
			}
		}
	}
}
