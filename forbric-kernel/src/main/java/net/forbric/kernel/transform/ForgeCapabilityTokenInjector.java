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

import net.forbric.kernel.util.ForbricLog;

/**
 * Drives the carrier's own {@code CapabilityTokenSubclass} launch plugin, which MinecraftForge's loader applies
 * to every subclass of {@code CapabilityToken} and the kernel never had: it reads the class's generic signature
 * and adds {@code public String getType()} returning the type argument, and un-finals {@code getType} on the
 * base. Without it {@code CapabilityToken.getType()} throws "This will be implemented by a transformer", so
 * {@code ForgeCapabilities.<clinit>} — which builds ITEM_HANDLER and friends from anonymous tokens — dies, and
 * every Forge mod that names {@code ForgeCapabilities} dies in a class initialiser.
 *
 * <p>Offered by class HEADER only ({@code ClassReader.getSuperName()}), the shape {@link ForgeEnumExtensionInjector}
 * uses: thousands of other classes cost one header read, not a ClassNode. The plugin's {@code handlesClass} is
 * not consulted — it declines by name shape; the super name is the actual contract. A token class without a
 * generic signature (a raw {@code new CapabilityToken(){}}) makes their processor throw; the class then passes
 * through unchanged and fails on its own use, as it would on Forge.
 */
public final class ForgeCapabilityTokenInjector implements ClassTransformer {
	static final String TOKEN = "net/minecraftforge/common/capabilities/CapabilityToken";
	/** Forge-only, like the fluid-type bridge's constants: NeoForge has no capability token. */
	static final String PLUGIN = "net.minecraftforge.fml.common.asm.CapabilityTokenSubclass";
	static final String ITEM_HANDLER_TOKEN = "net.minecraftforge.common.capabilities.ForgeCapabilities$4";

	private final Object plugin;
	private final Method processClassWithFlags;
	private final Object afterPhase;

	private ForgeCapabilityTokenInjector(Object plugin, Method processClassWithFlags, Object afterPhase) {
		this.plugin = plugin;
		this.processClassWithFlags = processClassWithFlags;
		this.afterPhase = afterPhase;
	}

	public static ForgeCapabilityTokenInjector create(ClassLoader gameLoader) {
		try {
			Class<?> pluginCls = Class.forName(PLUGIN, false, gameLoader);
			Class<?> phase = Class.forName("cpw.mods.modlauncher.serviceapi.ILaunchPluginService$Phase", false, gameLoader);
			return new ForgeCapabilityTokenInjector(pluginCls.getConstructor().newInstance(),
					pluginCls.getMethod("processClassWithFlags", phase, ClassNode.class, Type.class, String.class),
					Enum.valueOf(phase.asSubclass(Enum.class), "AFTER"));
		} catch (Throwable t) {
			ForbricLog.debug("[Forbric/Capabilities] no MinecraftForge CapabilityTokenSubclass plugin — token injector not "
					+ "installed: %s", String.valueOf(t));
			return null;
		}
	}

	@Override
	public String name() {
		return "forbric:forge-capability-tokens";
	}

	@Override
	public AnchorSet anchors() {
		return AnchorSet.of(
				new AnchorSet.Anchor(TOKEN.replace('/', '.'), AnchorSet.Severity.REQUIRED,
						"CapabilityToken.getType stays final and throws, so every CapabilityManager.get dies"),
				new AnchorSet.Anchor(ITEM_HANDLER_TOKEN, AnchorSet.Severity.REQUIRED,
						"ITEM_HANDLER cannot be built: ForgeCapabilities.<clinit> throws for every Forge mod that names it"));
	}

	@Override
	public byte[] transform(String className, byte[] classBytes, TransformContext context) {
		if (classBytes == null || classBytes.length == 0 || !isTokenOrSubclass(classBytes)) return classBytes;
		Type type = Type.getObjectType(className.replace('.', '/'));
		try {
			ClassNode node = new ClassNode();
			new ClassReader(classBytes).accept(node, 0);
			int flags = (Integer) processClassWithFlags.invoke(plugin, afterPhase, node, type, "classloading");
			int writerFlags = ForgeEnumExtensionInjector.writerFlags(flags);
			if (writerFlags < 0) return classBytes;
			ClassWriter writer = new ClassWriter(writerFlags);
			node.accept(writer);
			ForbricLog.info("[Forbric/Capabilities] capability token %s given its getType() by MinecraftForge's own "
					+ "launch plugin", className);
			return writer.toByteArray();
		} catch (Throwable t) {
			ForbricLog.warn("[Forbric/Capabilities] could not process capability token " + className
					+ " — the capability built from it will throw on first use, as it would on Forge without its "
					+ "generic signature", t);
			return classBytes;
		}
	}

	static boolean isTokenOrSubclass(byte[] classBytes) {
		try {
			ClassReader reader = new ClassReader(classBytes);
			return TOKEN.equals(reader.getClassName()) || TOKEN.equals(reader.getSuperName());
		} catch (Throwable unreadable) {
			return false;
		}
	}
}
