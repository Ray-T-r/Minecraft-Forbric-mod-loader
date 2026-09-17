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

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import net.forbric.kernel.util.ForbricLog;

/**
 * Answers the three {@code FMLLoader} methods that reach for ModLauncher, which the kernel never creates.
 *
 * <h2>What breaks without this</h2>
 *
 * <p>All three open with {@code getstatic cpw/mods/modlauncher/Launcher.INSTANCE} (javap), and that field is
 * written only by ModLauncher's own private constructor. The kernel replaces ModLauncher outright — there is no
 * {@code Launcher}, no {@code Environment}, no transformation service registry — so the field is null and every
 * one of them NPEs.
 *
 * <p>{@code getNameFunction} is the one that matters. It is what
 * {@code ObfuscationReflectionHelper.findField/findMethod/getPrivateValue} calls through {@code remapName}, and
 * that helper is how a large class of MinecraftForge mods reaches a private vanilla member. Mods call it from
 * static initialisers, so the NPE arrives as an {@code ExceptionInInitializerError} and every later touch of the
 * class is a {@code NoClassDefFoundError} — the mod is erroneous for the rest of the run. Physics Mod's Forge
 * build hit exactly this on the old weld, which is where the fix being ported here comes from.
 *
 * <h2>Why constants rather than a synthetic Launcher</h2>
 *
 * <p>The old weld synthesised a {@code Launcher} and an {@code Environment}. That works and is more faithful,
 * and it also means keeping a stand-in for a class the kernel has no other reason to know, whose shape is
 * ModLauncher's to change. What the three methods are actually being asked is answerable without one:
 *
 * <ul>
 *   <li>{@code getNameFunction} — the kernel runs Mojmap at runtime and performs no name mapping, so the honest
 *       answer is {@code Optional.empty()}: "no mapping service by that name". Forge's own {@code remapName}
 *       treats an empty Optional as "use the name as given", which is correct here.</li>
 *   <li>{@code getLauncherInfo} — a display string, used in crash reports and version banners.</li>
 *   <li>{@code modLauncherModList} — ModLauncher's own view of the mod list, which the kernel does not maintain;
 *       an empty list is what a caller iterating it should see, and the kernel publishes the real list through
 *       {@code ModList} and {@code LoadingModList} instead.</li>
 * </ul>
 */
public final class ForgeLauncherInfoInjector implements ClassTransformer {
	/** {@code -Dforbric.forgeLauncherInfo=off} puts the NPEs back, for measuring whether this is the cause. */
	static final String PROPERTY = "forbric.forgeLauncherInfo";

	private static final String FML_LOADER = "net.minecraftforge.fml.loading.FMLLoader";
	private static final String NAME_FUNCTION = "getNameFunction";
	private static final String LAUNCHER_INFO = "getLauncherInfo";
	private static final String MOD_LIST = "modLauncherModList";
	/** The byte pre-filter: no reference to ModLauncher, nothing to do. */
	private static final byte[] LAUNCHER = "cpw/mods/modlauncher/Launcher".getBytes(java.nio.charset.StandardCharsets.ISO_8859_1);

	@Override
	public String name() {
		return "forbric-forge-launcher-info";
	}

	static boolean enabled() {
		return !"off".equalsIgnoreCase(System.getProperty(PROPERTY, "on"));
	}

	@Override
	public byte[] transform(String className, byte[] classBytes, TransformContext context) {
		if (classBytes == null || classBytes.length == 0) return classBytes;
		if (!FML_LOADER.equals(className)) return classBytes;
		if (!enabled()) {
			ForbricLog.warn("[Forbric/Forge] -D%s=off — FMLLoader keeps reaching for ModLauncher, so "
					+ "ObfuscationReflectionHelper NPEs in whichever mod calls it first", PROPERTY);
			return classBytes;
		}
		if (!contains(classBytes, LAUNCHER)) return classBytes;

		ClassNode node = new ClassNode();
		new ClassReader(classBytes).accept(node, 0);

		int answered = 0;
		for (MethodNode m : node.methods) {
			if ((m.access & Opcodes.ACC_STATIC) == 0) continue;
			InsnList body = answerFor(m);
			if (body == null) continue;
			m.instructions = body;
			m.tryCatchBlocks.clear();
			if (m.localVariables != null) m.localVariables.clear();
			m.maxStack = 1;
			answered++;
		}
		if (answered == 0) return classBytes;

		ForbricLog.info("[Forbric/Forge] answered %d FMLLoader method(s) that reach for ModLauncher — the kernel "
				+ "replaces it, so Launcher.INSTANCE is null and ObfuscationReflectionHelper NPE'd inside whichever "
				+ "mod called it first, usually from a static initialiser", answered);

		ClassWriter writer = new ClassWriter(0);
		node.accept(writer);
		return writer.toByteArray();
	}

	/** The replacement body for one of the three, or null when this is not one of them. */
	private static InsnList answerFor(MethodNode m) {
		InsnList body = new InsnList();
		switch (m.name) {
			case NAME_FUNCTION -> {
				// Optional.empty(): no mapping service by that name. remapName reads that as "use the name as
				// given", which is right — the kernel runs Mojmap and maps nothing.
				body.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "java/util/Optional", "empty",
						"()Ljava/util/Optional;", false));
				body.add(new InsnNode(Opcodes.ARETURN));
			}
			case LAUNCHER_INFO -> {
				body.add(new LdcInsnNode("Forbric kernel (no ModLauncher)"));
				body.add(new InsnNode(Opcodes.ARETURN));
			}
			case MOD_LIST -> {
				body.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "java/util/List", "of", "()Ljava/util/List;",
						true));
				body.add(new InsnNode(Opcodes.ARETURN));
			}
			default -> {
				return null;
			}
		}
		return body;
	}

	/** Allocation-free substring search over raw class bytes. */
	private static boolean contains(byte[] haystack, byte[] needle) {
		outer:
		for (int i = 0; i + needle.length <= haystack.length; i++) {
			for (int j = 0; j < needle.length; j++) {
				if (haystack[i + j] != needle[j]) continue outer;
			}
			return true;
		}
		return false;
	}
}
