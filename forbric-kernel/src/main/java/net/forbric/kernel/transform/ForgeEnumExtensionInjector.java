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

import java.lang.reflect.Method;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.ClassNode;

import net.forbric.api.Ecosystem;
import net.forbric.api.ForeignType;
import net.forbric.kernel.util.ForbricLog;

/**
 * Makes traditional MinecraftForge's extensible enums extensible again.
 *
 * <h2>The stub</h2>
 *
 * <p>MinecraftForge lets a mod add a constant to certain vanilla enums by calling a static {@code create(...)} on
 * them. In the shipped game that method is a stub: {@code javap} on the merged base shows every one of them is
 * {@code new IllegalStateException("Enum not extended"); athrow}. MinecraftForge's own loader rewrites those
 * bodies into real factories while the class is being defined, and the kernel replaces that loader, so nothing
 * rewrote them.
 *
 * <p>A mod calling one gets that exception, and it gets it from a static initialiser — which is where a mod adds
 * an arm pose, a mob category or an item display context. A class initialiser only runs once and is erroneous
 * forever after, so the mod is not merely missing its constant: it is dead, and so is anything that touches it.
 *
 * <h2>Their bytecode, the kernel's driver</h2>
 *
 * <p>The rewrite is MinecraftForge's own {@code RuntimeEnumExtender}, driven here exactly as
 * {@link NeoEnumExtensionInjector} drives NeoForge's. Nothing about the rewrite is reimplemented: this supplies
 * the {@code ClassNode} and asks their processor whether it wants the class and then to process it.
 *
 * <h2>Which classes are offered, and why not theirs</h2>
 *
 * <p>Their own {@code handlesClass} is NOT used to choose. It answers no unless MinecraftForge's mod list holds
 * more than two entries — a heuristic that means "more than just minecraft and forge themselves" on a real
 * instance, and the kernel's MinecraftForge list does not carry those two, so a pack with one or two
 * traditional-Forge mods was declined for a reason that has nothing to do with it. Worse, asking them at all
 * during class definition walks that mod list, which pulls in a chain of loader classes that come straight back
 * through this transformer while the class that would answer is still being defined.
 *
 * <p>So the choice is made on the marker interface, which is the actual contract: a class is offered exactly when
 * it declares {@code net/minecraftforge/common/IExtensibleEnum}. That is read from the class header alone, with
 * no {@code ClassNode} built, so it costs nothing for the thousands of classes that do not. Their processor
 * re-checks the same marker and still declines anything it does not want.
 *
 * <p>If the carrier has no such processor the injector never installs ({@link #create} returns null) rather than
 * failing per class.
 */
public final class ForgeEnumExtensionInjector implements ClassTransformer {
	/** {@code ILaunchPluginService.ComputeFlags}, whose values this maps onto ASM writer flags. */
	private static final int NO_REWRITE = 0;
	private static final int COMPUTE_MAXS = 1;
	private static final int COMPUTE_FRAMES = 2;
	private static final int SIMPLE_REWRITE = 256;

	/** The interface a class declares to say "a mod may add constants to me". See {@link #transform}. */
	static final String MARKER = "net/minecraftforge/common/IExtensibleEnum";

	private final Object extender;
	private final Method processClassWithFlags;
	private final Object afterPhase;

	private ForgeEnumExtensionInjector(Object extender, Method processClassWithFlags, Object afterPhase) {
		this.extender = extender;
		this.processClassWithFlags = processClassWithFlags;
		this.afterPhase = afterPhase;
	}

	/** @return an injector, or null if this runtime has no MinecraftForge enum extender (then nothing installs). */
	public static ForgeEnumExtensionInjector create(ClassLoader gameLoader) {
		try {
			Class<?> extenderCls = Class.forName(
					ForeignType.RUNTIME_ENUM_EXTENDER.binary(Ecosystem.FORGE), false, gameLoader);
			Class<?> phase = Class.forName(
					"cpw.mods.modlauncher.serviceapi.ILaunchPluginService$Phase", false, gameLoader);

			return new ForgeEnumExtensionInjector(
					extenderCls.getConstructor().newInstance(),
					extenderCls.getMethod("processClassWithFlags", phase, ClassNode.class, Type.class, String.class),
					Enum.valueOf(phase.asSubclass(Enum.class), "AFTER"));
		} catch (Throwable t) {
			ForbricLog.debug("[Forbric/EnumExt] no MinecraftForge RuntimeEnumExtender — Forge enum extension "
					+ "injector not installed: %s", String.valueOf(t));
			return null;
		}
	}

	@Override
	public byte[] transform(String className, byte[] classBytes, TransformContext context) {
		if (!declaresTheMarker(classBytes)) return classBytes;

		Type type = Type.getObjectType(className.replace('.', '/'));
		try {
			ClassNode node = new ClassNode();
			new ClassReader(classBytes).accept(node, 0);
			// "classloading" is the reason string MinecraftForge's own loader passes; it reaches only their log.
			int flags = (Integer) processClassWithFlags.invoke(extender, afterPhase, node, type, "classloading");

			int writerFlags = writerFlags(flags);
			if (writerFlags < 0) return classBytes;

			ClassWriter writer = new ClassWriter(writerFlags);
			node.accept(writer);
			ForbricLog.info("[Forbric/EnumExt] %s can be extended again — its create(...) was a stub that threw "
					+ "\"Enum not extended\", usually from a mod's static initialiser, which kills the mod outright",
					className);
			return writer.toByteArray();
		} catch (Throwable t) {
			// Leaving the enum alone is survivable — the mod that wanted the constant fails on its own call, with
			// its own name in the message. Failing the class definition here would take down unrelated code.
			ForbricLog.warn("[Forbric/EnumExt] could not make " + className
					+ " extensible — a MinecraftForge mod adding a constant to it will fail", t);
			return classBytes;
		}
	}

	/**
	 * Whether the class declares MinecraftForge's extensible-enum marker, read from the header alone.
	 *
	 * <p>{@code ClassReader.getInterfaces()} parses no method bodies, so this is what makes the transformer free
	 * for the thousands of classes that are not extensible enums.
	 */
	static boolean declaresTheMarker(byte[] classBytes) {
		try {
			for (String iface : new ClassReader(classBytes).getInterfaces()) {
				if (MARKER.equals(iface)) return true;
			}
		} catch (Throwable unreadable) {
			// Not a class this transformer can speak about. Something else in the chain will say so.
		}
		return false;
	}

	/** Maps {@code ComputeFlags} onto the ASM writer's, or -1 for "the processor declined". */
	static int writerFlags(int computeFlags) {
		if (computeFlags == NO_REWRITE) return -1;
		if ((computeFlags & COMPUTE_FRAMES) != 0) return ClassWriter.COMPUTE_FRAMES;
		if ((computeFlags & COMPUTE_MAXS) != 0) return ClassWriter.COMPUTE_MAXS;
		if ((computeFlags & SIMPLE_REWRITE) != 0) return 0;
		// An unknown future value must not be read as "no rewrite" — the node HAS been edited by now, so the safe
		// reading is "write it back with the most conservative flags".
		return ClassWriter.COMPUTE_FRAMES;
	}

	@Override
	public String name() {
		return "forbric:forge-enum-extension";
	}
}
