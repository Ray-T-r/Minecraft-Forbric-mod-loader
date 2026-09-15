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
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import net.forbric.kernel.util.ForbricLog;

/**
 * Adapts ForgeConfigAPIPort's compiled call sites to the NeoForge API it is actually running against.
 *
 * <p>{@link net.forbric.kernel.boot.PortingLayerAudit} is the general half of this problem: it reports, for any
 * Fabric mod that ships its own {@code net.neoforged.*} / {@code net.minecraftforge.*}, where its copy and the
 * carrier's disagree. This is the special half, and it is honestly special: a two-method ABI shim for one named
 * mod. There is no general repair, because "the port's copy differs from the real one" has as many right answers
 * as there are differences, and guessing is how a config ends up written to two files.
 *
 * <p>The two differences that matter, out of 54 shadowed classes and 8 disagreements:
 *
 * <ul>
 *   <li>{@code ConfigTracker.registerConfig} takes a {@code ModContainer} on real NeoForge 26.2.0.88 and a mod-ID
 *       {@code String} in the port's copy. Four call sites, two of each descriptor, all
 *       {@code getstatic ConfigTracker.INSTANCE} then {@code invokevirtual}. Each becomes an
 *       {@code invokestatic} into {@link net.forbric.kernel.runtime.KernelConfigPortBridge} with the tracker as
 *       argument zero — a one-instruction owner/opcode/descriptor swap, so no stack surgery, no frames, no
 *       {@code maxStack} change.</li>
 *   <li>Real {@code IConfigSpec} declares {@code validateSpec(ModConfig)}; the port's copy does not, and its
 *       {@code ForgeConfigSpecAdapter} implements the interface without it. The carrier's {@code registerConfig}
 *       calls it unconditionally, so the adapter gets an {@code AbstractMethodError}. Added here as a no-op,
 *       which is also what it means: the carrier's own implementation validates NeoForge {@code RestartType}
 *       against the config type, and a wrapped MinecraftForge {@code ForgeConfigSpec} has no such concept.</li>
 * </ul>
 *
 * <p><b>Drift is a refusal, not a warning.</b> If the port moves — a fifth call site, a renamed class, a
 * {@code validateSpec} it now declares itself — the shim stands down whole rather than half-rewriting a jar it no
 * longer understands, and the audit's report stands on its own. {@code -Dforbric.portingLayerAbi=off} disables it.
 *
 * <p>Known limitation, stated rather than half-fixed: {@code /config showfile} stays broken. Its argument type is
 * built by {@code EnumArgument} over {@code ModConfig$Type}, and the carrier's enum implements
 * {@code StringRepresentable} nowhere, so parsing throws inside Minecraft's own lookup. Making the two
 * {@code getSerializedName} call sites resolve would leave that untouched and merely change the exception.
 */
public final class PortingLayerAbiInjector implements ClassTransformer {

	private static final String CONFIG_REGISTRY = "fuzs/forgeconfigapiport/fabric/impl/core/ConfigRegistryImpl";
	private static final String SPEC_ADAPTER = "fuzs/forgeconfigapiport/fabric/impl/core/ForgeConfigSpecAdapter";
	private static final String TRACKER = "net/neoforged/fml/config/ConfigTracker";
	private static final String BRIDGE = "net/forbric/kernel/runtime/KernelConfigPortBridge";
	private static final String MOD_CONFIG = "Lnet/neoforged/fml/config/ModConfig;";
	private static final String SPEC = "Lnet/neoforged/fml/config/IConfigSpec;";
	private static final String TYPE = "Lnet/neoforged/fml/config/ModConfig$Type;";
	private static final String BY_ID_3 = "(" + TYPE + SPEC + "Ljava/lang/String;)" + MOD_CONFIG;
	private static final String BY_ID_4 = "(" + TYPE + SPEC + "Ljava/lang/String;Ljava/lang/String;)" + MOD_CONFIG;
	private static final String VALIDATE_SPEC = "validateSpec";

	/** What the port looks like on the version this was written against. Anything else and the shim stands down. */
	private static final int EXPECTED_REGISTER_SITES = 4;

	private static final String SWITCH = "forbric.portingLayerAbi";

	@Override
	public String name() {
		return "forbric-porting-layer-abi";
	}

	@Override
	public byte[] transform(String className, byte[] classBytes, TransformContext context) {
		if (classBytes == null || classBytes.length == 0) return classBytes;
		if (!"fuzs.forgeconfigapiport.fabric.impl.core.ConfigRegistryImpl".equals(className)
				&& !"fuzs.forgeconfigapiport.fabric.impl.core.ForgeConfigSpecAdapter".equals(className)) {
			return classBytes;
		}
		if ("off".equalsIgnoreCase(System.getProperty(SWITCH, "on"))) {
			ForbricLog.warn("[Forbric/PortShim] ABI shim DISABLED (-D%s=off) — ForgeConfigAPIPort will call a "
					+ "ConfigTracker method real NeoForge does not have, and every mod registering a config "
					+ "through it will fail", SWITCH);
			return classBytes;
		}
		try {
			ClassNode node = new ClassNode();
			new ClassReader(classBytes).accept(node, 0);
			boolean changed = CONFIG_REGISTRY.equals(node.name)
					? routeRegistrationsThroughTheBridge(node)
					: addTheValidateSpecTheCarrierCalls(node);
			if (!changed) return classBytes;
			ClassWriter writer = new ClassWriter(0);
			node.accept(writer);
			return writer.toByteArray();
		} catch (IllegalStateException refused) {
			ForbricLog.warn("[Forbric/PortShim] standing down on %s: %s — the audit's report stands, and configs "
					+ "registered through this port will fail", className, refused.getMessage());
			return classBytes;
		} catch (RuntimeException e) {
			ForbricLog.warn("[Forbric/PortShim] could not adapt " + className, e);
			return classBytes;
		}
	}

	private static boolean routeRegistrationsThroughTheBridge(ClassNode node) {
		int sites = 0;
		for (MethodNode method : node.methods) {
			if (method.instructions == null) continue;
			for (AbstractInsnNode insn : method.instructions) {
				if (!(insn instanceof MethodInsnNode call) || call.getOpcode() != Opcodes.INVOKEVIRTUAL
						|| !TRACKER.equals(call.owner) || !"registerConfig".equals(call.name)) {
					continue;
				}
				if (!BY_ID_3.equals(call.desc) && !BY_ID_4.equals(call.desc)) {
					throw new IllegalStateException("ConfigTracker.registerConfig" + call.desc
							+ " is a shape this shim was not written for");
				}
				sites++;
			}
		}
		if (sites != EXPECTED_REGISTER_SITES) {
			throw new IllegalStateException("expected " + EXPECTED_REGISTER_SITES
					+ " mod-id-keyed ConfigTracker.registerConfig call sites, found " + sites);
		}
		for (MethodNode method : node.methods) {
			if (method.instructions == null) continue;
			for (AbstractInsnNode insn : method.instructions) {
				if (insn instanceof MethodInsnNode call && call.getOpcode() == Opcodes.INVOKEVIRTUAL
						&& TRACKER.equals(call.owner) && "registerConfig".equals(call.name)) {
					// The receiver stays on the stack and becomes argument zero, so the instruction count and the
					// stack depth are both unchanged — only the owner, the opcode and the descriptor move.
					call.setOpcode(Opcodes.INVOKESTATIC);
					call.desc = "(L" + TRACKER + ";" + call.desc.substring(1);
					call.owner = BRIDGE;
				}
			}
		}
		ForbricLog.warn("[Forbric/PortShim] routed ForgeConfigAPIPort's %d config registrations through the kernel "
				+ "— it asks for a mod-id-keyed ConfigTracker.registerConfig, and real NeoForge %s takes a "
				+ "ModContainer", sites, "26.2.x");
		return true;
	}

	private static boolean addTheValidateSpecTheCarrierCalls(ClassNode node) {
		for (MethodNode method : node.methods) {
			if (VALIDATE_SPEC.equals(method.name)) {
				throw new IllegalStateException("ForgeConfigSpecAdapter already declares " + VALIDATE_SPEC
						+ method.desc + " — the port has caught up and this shim is stale");
			}
		}
		MethodNode validate = new MethodNode(Opcodes.ACC_PUBLIC, VALIDATE_SPEC, "(" + MOD_CONFIG + ")V", null, null);
		validate.instructions.add(new InsnNode(Opcodes.RETURN));
		validate.maxStack = 0;
		validate.maxLocals = 2;
		node.methods.add(validate);
		ForbricLog.warn("[Forbric/PortShim] gave ForgeConfigAPIPort's spec adapter the validateSpec real NeoForge "
				+ "calls on every registration — it implements IConfigSpec against an older shape of it");
		return true;
	}
}
