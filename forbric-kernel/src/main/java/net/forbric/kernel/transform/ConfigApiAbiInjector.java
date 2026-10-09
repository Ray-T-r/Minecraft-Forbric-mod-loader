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
import net.forbric.kernel.util.ByteScan;
import net.forbric.api.Ecosystem;
import net.forbric.api.ForeignType;

/**
 * Adapts the published config API's mod-ID registration and older cross-ecosystem spec contract.
 * Consumers are discovered by API descriptors; an implementation name or number of consumers is not a contract.
 * The missing validation default is supplied only for a proved delegate to Forge's IConfigSpec protocol,
 * which has no NeoForge RestartType validation semantics.
 */
public final class ConfigApiAbiInjector implements ClassTransformer {

	private static final String TRACKER = ForeignType.CONFIG_TRACKER.internal(Ecosystem.NEOFORGE);
	private static final String BRIDGE = "net/forbric/kernel/runtime/KernelConfigApiBridge";
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
	private static final byte[][] API_NAMES = {ByteScan.needle(TRACKER), ByteScan.needle(CONFIG_SCREEN), ByteScan.needle(ForeignType.CONFIG_SPEC.internal(Ecosystem.NEOFORGE))};

	private final java.util.function.Function<String, ClassNode> declarations;
	public ConfigApiAbiInjector(java.util.function.Function<String, ClassNode> declarations) { this.declarations = declarations; }

	private static final String SWITCH = "forbric.configApiAbi";

	@Override
	public String name() {
		return "forbric-config-api-abi";
	}

	@Override
	public AnchorSet anchors() { return AnchorSet.scanned("published config API descriptors and proved cross-ecosystem delegates"); }

	@Override
	public byte[] transform(String className, byte[] classBytes, TransformContext context) {
		if (classBytes == null || classBytes.length == 0) return classBytes;
		if (ForeignType.CONFIG_TRACKER.binary(Ecosystem.NEOFORGE).equals(className)) return leaveAnOpenConfigOpen(classBytes);
		if ("off".equalsIgnoreCase(net.forbric.kernel.util.ForbricSwitches.get(SWITCH, "on"))) return classBytes;
		if (!ByteScan.containsAny(classBytes, API_NAMES)) return classBytes;
		ClassNode node = new ClassNode(); new ClassReader(classBytes).accept(node, 0);
		boolean changed = routeRegistrationsThroughTheBridge(node);
		changed |= addTheValidateSpecTheCarrierCalls(node);
		changed |= routeTheConfigScreenThroughTheBridge(node);
		if (!changed) return classBytes;
		ClassWriter writer = new ClassWriter(0); node.accept(writer); return writer.toByteArray();
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
		ForbricLog.info("[Forbric/ConfigApi] ConfigTracker.loadConfigs passes over a config that is already open — the "
				+ "config port and NeoForge each load SERVER configs at server start, and the second open doubled "
				+ "every Loading event and file watcher");
		return writer.toByteArray();
	}

	private boolean routeRegistrationsThroughTheBridge(ClassNode node) {
		ClassNode tracker = declarations.apply(TRACKER);
		if (tracker == null) return false;
		int sites = 0;
		for (MethodNode method : node.methods) for (AbstractInsnNode instruction : method.instructions) {
			if (!(instruction instanceof MethodInsnNode call) || call.getOpcode() != Opcodes.INVOKEVIRTUAL
					|| !TRACKER.equals(call.owner) || !"registerConfig".equals(call.name)
					|| (!BY_ID_3.equals(call.desc) && !BY_ID_4.equals(call.desc))) continue;
			String nativeDescriptor = call.desc.replaceFirst("Ljava/lang/String;", "Lnet/neoforged/fml/ModContainer;");
			if (tracker.methods.stream().anyMatch(m -> m.name.equals(call.name) && m.desc.equals(call.desc))
					|| tracker.methods.stream().noneMatch(m -> m.name.equals(call.name) && m.desc.equals(nativeDescriptor) && (m.access & Opcodes.ACC_PUBLIC) != 0)) continue;
			call.setOpcode(Opcodes.INVOKESTATIC); call.desc = "(L" + TRACKER + ";" + call.desc.substring(1); call.owner = BRIDGE; sites++;
		}
		return sites > 0;
	}

	/**
	 * Uses the carrier's actual constructor for the mod-ID API's factory contract.
	 * Lambda handles preserve their functional type. Direct allocations are adapted only when their
	 * allocation-to-constructor region has no control-flow or frame boundaries involving an uninitialized value.
	 */
	private boolean routeTheConfigScreenThroughTheBridge(ClassNode node) {
		ClassNode screen = declarations.apply(CONFIG_SCREEN);
		if (screen == null || screen.methods.stream().anyMatch(m -> m.name.equals("<init>") && m.desc.equals(SCREEN_CTOR_BY_ID))
				|| screen.methods.stream().noneMatch(m -> m.name.equals("<init>") && m.desc.equals("(Lnet/neoforged/fml/ModContainer;" + SCREEN + ")V")
					&& (m.access & Opcodes.ACC_PUBLIC) != 0)) return false;
		int rerouted = 0;
		int direct = 0;
		for (MethodNode method : node.methods) {
			if (method.instructions == null) continue;
			for (AbstractInsnNode insn : method.instructions.toArray()) {
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
				} else if (insn instanceof org.objectweb.asm.tree.TypeInsnNode allocation && allocation.getOpcode() == Opcodes.NEW
						&& allocation.desc.equals(CONFIG_SCREEN)) {
					AbstractInsnNode duplicate = allocation.getNext();
					while (duplicate != null && duplicate.getOpcode() < 0) duplicate = duplicate.getNext();
					if (duplicate == null || duplicate.getOpcode() != Opcodes.DUP) continue;
					MethodInsnNode constructor = null;
					for (var argument = duplicate.getNext(); argument != null; argument = argument.getNext()) {
						if (argument instanceof FrameNode || argument instanceof JumpInsnNode || argument instanceof org.objectweb.asm.tree.LookupSwitchInsnNode
								|| argument instanceof org.objectweb.asm.tree.TableSwitchInsnNode || argument instanceof LabelNode
								|| argument instanceof org.objectweb.asm.tree.TypeInsnNode nested && nested.getOpcode() == Opcodes.NEW && nested.desc.equals(CONFIG_SCREEN)) break;
						if (argument instanceof MethodInsnNode call && call.getOpcode() == Opcodes.INVOKESPECIAL && call.owner.equals(CONFIG_SCREEN)) {
							if (call.name.equals("<init>") && call.desc.equals(SCREEN_CTOR_BY_ID)) constructor = call;
							break;
						}
					}
					if (constructor == null) { direct++; continue; }
					method.instructions.remove(allocation); method.instructions.remove(duplicate);
					constructor.setOpcode(Opcodes.INVOKESTATIC); constructor.owner = BRIDGE; constructor.name = SCREEN_FACTORY; constructor.desc = SCREEN_FACTORY_DESC;
					method.instructions.insert(constructor, new org.objectweb.asm.tree.TypeInsnNode(Opcodes.CHECKCAST, CONFIG_SCREEN)); rerouted++;
				}
			}
		}
		if (direct > 0) {
			ForbricLog.warn("[Forbric/ConfigApi] %s constructs ConfigurationScreen from a mod id directly in %d "
					+ "place(s) — that shape crosses a control-flow boundary involving its uninitialized value; "
					+ "no safe constructor rewrite was proved",
					node.name.replace('/', '.'), direct);
		}
		if (rerouted == 0) return false;
		ForbricLog.warn("[Forbric/ConfigApi] re-aimed %d ConfigurationScreen reference(s) in %s at the carrier's own "
				+ "constructor — it takes a ModContainer where the config API's copy takes a mod id, and a "
				+ "method reference to the wrong one fails when its lambda links, not where it is written",
				rerouted, node.name.replace('/', '.'));
		return true;
	}

	private boolean addTheValidateSpecTheCarrierCalls(ClassNode node) {
		String neo = ForeignType.CONFIG_SPEC.internal(Ecosystem.NEOFORGE), forge = ForeignType.CONFIG_SPEC.internal(Ecosystem.FORGE);
		if (!node.interfaces.contains(neo) || node.methods.stream().anyMatch(m -> m.name.equals(VALIDATE_SPEC))) return false;
		ClassNode api = declarations.apply(neo);
		if (api == null || api.methods.stream().noneMatch(m -> m.name.equals(VALIDATE_SPEC) && m.desc.equals("(" + MOD_CONFIG + ")V")
				&& (m.access & Opcodes.ACC_ABSTRACT) != 0)) return false;
		var fields = node.fields.stream().filter(f -> f.desc.equals("L" + forge + ";")
				&& (f.access & (Opcodes.ACC_PRIVATE | Opcodes.ACC_FINAL | Opcodes.ACC_STATIC)) == (Opcodes.ACC_PRIVATE | Opcodes.ACC_FINAL)).toList();
		if (fields.size() != 1) return false;
		String field = fields.getFirst().name;
		if (!delegate(node, field, forge, "isEmpty", "()Z", "()Z", false, false)
				|| !delegate(node, field, forge, "isCorrect", "(Lcom/electronwill/nightconfig/core/UnmodifiableCommentedConfig;)Z", "(Lcom/electronwill/nightconfig/core/CommentedConfig;)Z", true, false)
				|| !delegate(node, field, forge, "correct", "(Lcom/electronwill/nightconfig/core/CommentedConfig;)V", "(Lcom/electronwill/nightconfig/core/CommentedConfig;)I", false, true)) return false;
		MethodNode validate = new MethodNode(Opcodes.ACC_PUBLIC, VALIDATE_SPEC, "(" + MOD_CONFIG + ")V", null, null);
		validate.instructions.add(new InsnNode(Opcodes.RETURN)); validate.maxLocals = 2; node.methods.add(validate); return true;
	}
	private static boolean delegate(ClassNode node, String field, String api, String methodName, String descriptor, String targetDescriptor, boolean cast, boolean discard) {
		MethodNode method = node.methods.stream().filter(m -> m.name.equals(methodName) && m.desc.equals(descriptor)
				&& (m.access & (Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC)) == Opcodes.ACC_PUBLIC).findFirst().orElse(null);
		if (method == null || !method.tryCatchBlocks.isEmpty()) return false;
		var code = java.util.stream.StreamSupport.stream(method.instructions.spliterator(), false).filter(i -> i.getOpcode() >= 0).toList();
		int arguments = org.objectweb.asm.Type.getArgumentTypes(descriptor).length;
		if (code.size() != 4 + arguments + (cast ? 1 : 0) + (discard ? 1 : 0)
				|| !(code.get(0) instanceof VarInsnNode self) || self.getOpcode() != Opcodes.ALOAD || self.var != 0
				|| !(code.get(1) instanceof org.objectweb.asm.tree.FieldInsnNode wrapped) || wrapped.getOpcode() != Opcodes.GETFIELD
				|| !wrapped.owner.equals(node.name) || !wrapped.name.equals(field) || !wrapped.desc.equals("L" + api + ";")) return false;
		int index = 2;
		if (arguments == 1 && (!(code.get(index++) instanceof VarInsnNode argument) || argument.getOpcode() != Opcodes.ALOAD || argument.var != 1)) return false;
		if (cast && (!(code.get(index++) instanceof org.objectweb.asm.tree.TypeInsnNode type) || type.getOpcode() != Opcodes.CHECKCAST || !type.desc.equals("com/electronwill/nightconfig/core/CommentedConfig"))) return false;
		if (!(code.get(index++) instanceof MethodInsnNode call) || call.getOpcode() != Opcodes.INVOKEINTERFACE || !call.owner.equals(api)
				|| !call.name.equals(methodName) || !call.desc.equals(targetDescriptor)) return false;
		if (discard && code.get(index++).getOpcode() != Opcodes.POP) return false;
		return code.get(index).getOpcode() == (descriptor.endsWith("Z") ? Opcodes.IRETURN : Opcodes.RETURN);
	}
}
