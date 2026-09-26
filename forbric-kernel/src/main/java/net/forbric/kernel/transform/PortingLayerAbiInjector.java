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
import org.objectweb.asm.Handle;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FrameNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

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
	private static final String ADAPTER_INTERNAL = "fuzs/forgeconfigapiport/fabric/impl/core/ForgeConfigSpecAdapter";
	private static final String TRACKER = "net/neoforged/fml/config/ConfigTracker";
	private static final String BRIDGE = "net/forbric/kernel/runtime/KernelConfigPortBridge";
	private static final String MOD_CONFIG = "Lnet/neoforged/fml/config/ModConfig;";
	private static final String SPEC = "Lnet/neoforged/fml/config/IConfigSpec;";
	private static final String TYPE = "Lnet/neoforged/fml/config/ModConfig$Type;";
	private static final String BY_ID_3 = "(" + TYPE + SPEC + "Ljava/lang/String;)" + MOD_CONFIG;
	private static final String BY_ID_4 = "(" + TYPE + SPEC + "Ljava/lang/String;Ljava/lang/String;)" + MOD_CONFIG;
	private static final String VALIDATE_SPEC = "validateSpec";
	private static final String CONFIG_SCREEN = "net/neoforged/neoforge/client/gui/ConfigurationScreen";
	private static final String SCREEN = "Lnet/minecraft/client/gui/screens/Screen;";
	/** The port's shape: a mod ID where real NeoForge takes a ModContainer. */
	private static final String SCREEN_CTOR_BY_ID = "(Ljava/lang/String;" + SCREEN + ")V";
	private static final String SCREEN_FACTORY = "configurationScreen";
	private static final String SCREEN_FACTORY_DESC = "(Ljava/lang/String;" + SCREEN + ")" + SCREEN;

	/** What the port looks like on the version this was written against. Anything else and the shim stands down. */
	private static final int EXPECTED_REGISTER_SITES = 4;

	private static final String SWITCH = "forbric.portingLayerAbi";

	@Override
	public String name() {
		return "forbric-porting-layer-abi";
	}

	@Override
	public AnchorSet anchors() {
		// The targets are a MOD's own classes (ForgeConfigAPIPort's), not the game's. Whether they are present is
		// the player's business, so declaring them as anchors would make the audit permanently red on every
		// instance that does not have that mod installed.
		return AnchorSet.scanned("targets ForgeConfigAPIPort's own classes, which are present only if the player "
				+ "installed it");
	}

	@Override
	public byte[] transform(String className, byte[] classBytes, TransformContext context) {
		if (classBytes == null || classBytes.length == 0) return classBytes;
		if ("net.neoforged.fml.config.ConfigTracker".equals(className)) return leaveAnOpenConfigOpen(classBytes);
		boolean port = "fuzs.forgeconfigapiport.fabric.impl.core.ConfigRegistryImpl".equals(className)
				|| "fuzs.forgeconfigapiport.fabric.impl.core.ForgeConfigSpecAdapter".equals(className);
		// The screen constructor is named by the port's CONSUMERS, not by the port, so it can be in any class.
		// Scanned at the byte level first: parsing every class that loads would be a real cost, and a class that
		// does not carry the name in its constant pool cannot reference it.
		boolean namesTheScreen = !port && contains(classBytes, CONFIG_SCREEN);
		if (!port && !namesTheScreen) return classBytes;
		if ("off".equalsIgnoreCase(System.getProperty(SWITCH, "on"))) {
			ForbricLog.warn("[Forbric/PortShim] ABI shim DISABLED (-D%s=off) — ForgeConfigAPIPort will call a "
					+ "ConfigTracker method real NeoForge does not have, and every mod registering a config "
					+ "through it will fail", SWITCH);
			return classBytes;
		}
		try {
			ClassNode node = new ClassNode();
			new ClassReader(classBytes).accept(node, 0);
			boolean changed;
			if (CONFIG_REGISTRY.equals(node.name)) changed = routeRegistrationsThroughTheBridge(node);
			else if (ADAPTER_INTERNAL.equals(node.name)) changed = addTheValidateSpecTheCarrierCalls(node);
			else changed = routeTheConfigScreenThroughTheBridge(node);
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

	/** {@code -Dforbric.skipLoadedConfigs=off} lets the carrier's whole-type load re-open a config again. */
	static final String SKIP_LOADED_SWITCH = "forbric.skipLoadedConfigs";

	/**
	 * Makes the carrier's whole-type {@code ConfigTracker.loadConfigs} pass over a config that is already open.
	 *
	 * <p>Two openers meet on the SERVER type, and only here: the port's own {@code ServerLifecycleHandler} loads
	 * SERVER configs in Fabric's {@code SERVER_STARTING} (its early phase, so other mods' listeners see values), and
	 * NeoForge's {@code handleServerAboutToStart} loads them again moments later from {@code initServer}. Each
	 * config then goes through {@code openConfig} twice: "Opening a config that was already loaded", a second
	 * {@code Loading} event, and a second file watcher, so every later edit to e.g. {@code neoforge-server.toml}
	 * reloads twice. Neither loader alone can do this — natively the port never meets NeoForge — and NeoForge
	 * itself only warns about it, so skipping a loaded config is its own intent. A stopped server unloads the type,
	 * so the next world still loads fresh.
	 */
	private static byte[] leaveAnOpenConfigOpen(byte[] classBytes) {
		if ("off".equalsIgnoreCase(System.getProperty(SKIP_LOADED_SWITCH, "on"))) return classBytes;
		ClassNode node = new ClassNode();
		new ClassReader(classBytes).accept(node, 0);
		String desc = "(Ljava/nio/file/Path;Ljava/nio/file/Path;" + MOD_CONFIG + ")V";
		MethodNode each = null;
		for (MethodNode method : node.methods) {
			if (method.name.startsWith("lambda$loadConfigs$") && desc.equals(method.desc)
					&& (method.access & Opcodes.ACC_STATIC) != 0) {
				if (each != null) return classBytes; // two candidates: not the shape this was written for
				each = method;
			}
		}
		if (each == null || each.instructions == null || each.instructions.size() == 0) return classBytes;
		AbstractInsnNode first = each.instructions.getFirst();
		while (first != null && first.getOpcode() < 0) first = first.getNext();
		if (first instanceof VarInsnNode load && load.getOpcode() == Opcodes.ALOAD && load.var == 2
				&& load.getNext() instanceof MethodInsnNode call && "getLoadedConfig".equals(call.name)) {
			return classBytes; // already guarded
		}
		LabelNode open = new LabelNode();
		InsnList guard = new InsnList();
		guard.add(new VarInsnNode(Opcodes.ALOAD, 2));
		guard.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "net/neoforged/fml/config/ModConfig", "getLoadedConfig",
				"()Lnet/neoforged/fml/config/IConfigSpec$ILoadedConfig;", false));
		guard.add(new JumpInsnNode(Opcodes.IFNULL, open));
		guard.add(new InsnNode(Opcodes.RETURN));
		guard.add(open);
		guard.add(new FrameNode(Opcodes.F_SAME, 0, null, 0, null));
		each.instructions.insert(guard);
		each.maxStack = Math.max(each.maxStack, 1);
		ClassWriter writer = new ClassWriter(0);
		node.accept(writer);
		ForbricLog.info("[Forbric/PortShim] ConfigTracker.loadConfigs passes over a config that is already open — the "
				+ "config port and NeoForge each load SERVER configs at server start, and the second open doubled "
				+ "every Loading event and file watcher");
		return writer.toByteArray();
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

	/**
	 * Re-aims a {@code ConfigurationScreen} built from a mod ID at the one the carrier actually has.
	 *
	 * <p>The carrier's constructor takes a {@code ModContainer}; the port's copy takes the mod ID, and mods
	 * written against the port name THAT constructor in their own code. ShoulderSurfing hands
	 * {@code ConfigurationScreen::new} to the port's screen-factory registry, so the reference is a method handle
	 * in an {@code invokedynamic} rather than a call — it fails when the lambda's call site links, which is why
	 * the {@code NoSuchMethodError} came from a line that constructs nothing and its own frame was the only one
	 * on the stack.
	 *
	 * <p>Both forms are handled. The handle is swapped for a static factory of the same instantiated type
	 * ({@code (String, Screen) -> Screen}), which is what the lambda already promised. A direct
	 * {@code NEW}/{@code DUP}/{@code INVOKESPECIAL} is left alone and reported: rewriting it means deleting the
	 * {@code NEW} and the {@code DUP}, which moves every offset in the method, and the only such site known is in
	 * the port's own ModMenu integration behind an {@code isDevelopmentEnvironment} check that is false in a
	 * player's instance.
	 */
	private static boolean routeTheConfigScreenThroughTheBridge(ClassNode node) {
		int rerouted = 0;
		int direct = 0;
		for (MethodNode method : node.methods) {
			if (method.instructions == null) continue;
			for (AbstractInsnNode insn : method.instructions) {
				if (insn instanceof InvokeDynamicInsnNode indy) {
					for (int i = 0; i < indy.bsmArgs.length; i++) {
						if (!(indy.bsmArgs[i] instanceof Handle h)) continue;
						if (h.getTag() != Opcodes.H_NEWINVOKESPECIAL || !CONFIG_SCREEN.equals(h.getOwner())
								|| !SCREEN_CTOR_BY_ID.equals(h.getDesc())) {
							continue;
						}
						indy.bsmArgs[i] = new Handle(Opcodes.H_INVOKESTATIC, BRIDGE, SCREEN_FACTORY,
								SCREEN_FACTORY_DESC, false);
						rerouted++;
					}
				} else if (insn instanceof MethodInsnNode call && call.getOpcode() == Opcodes.INVOKESPECIAL
						&& CONFIG_SCREEN.equals(call.owner) && SCREEN_CTOR_BY_ID.equals(call.desc)) {
					direct++;
				}
			}
		}
		if (direct > 0) {
			ForbricLog.warn("[Forbric/PortShim] %s constructs ConfigurationScreen from a mod id directly in %d "
					+ "place(s) — that shape belongs to ForgeConfigAPIPort's copy, not to real NeoForge, and this "
					+ "shim only re-aims the method-reference form. Those sites will still fail",
					node.name.replace('/', '.'), direct);
		}
		if (rerouted == 0) return false;
		ForbricLog.warn("[Forbric/PortShim] re-aimed %d ConfigurationScreen reference(s) in %s at the carrier's own "
				+ "constructor — it takes a ModContainer where ForgeConfigAPIPort's copy takes a mod id, and a "
				+ "method reference to the wrong one fails when its lambda links, not where it is written",
				rerouted, node.name.replace('/', '.'));
		return true;
	}

	/** Whether {@code bytes} contains {@code text} as raw ASCII — a constant-pool pre-filter, not a parse. */
	private static boolean contains(byte[] bytes, String text) {
		byte[] needle = text.getBytes(java.nio.charset.StandardCharsets.UTF_8);
		outer:
		for (int i = 0; i + needle.length <= bytes.length; i++) {
			for (int j = 0; j < needle.length; j++) {
				if (bytes[i + j] != needle[j]) continue outer;
			}
			return true;
		}
		return false;
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
