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

import java.util.List;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import net.forbric.api.Ecosystem;
import net.forbric.api.ForeignType;
import net.forbric.kernel.util.ForbricLog;

/** Redirects the four Neo-only client consumers without inserting instructions or rebuilding frames. */
public final class ForgeClientConsumersInjector implements ClassTransformer {
	static final String RUNTIME = "net/forbric/kernel/runtime/KernelForgeClientConsumers";
	static final List<Rule> RULES = List.of(
			new Rule("net.minecraft.client.model.geom.LayerDefinitions", "createRoots", "()Ljava/util/Map;",
					ForeignType.CLIENT_HOOKS, "loadLayerDefinitions", "(Lcom/google/common/collect/ImmutableMap$Builder;)V",
					"loadLayerDefinitions", false),
			new Rule("net.minecraft.client.gui.screens.inventory.tooltip.ClientTooltipComponent", "create",
					"(Lnet/minecraft/world/inventory/tooltip/TooltipComponent;)Lnet/minecraft/client/gui/screens/inventory/tooltip/ClientTooltipComponent;",
					ForeignType.CLIENT_TOOLTIP_COMPONENT_MANAGER, "createClientTooltipComponent",
					"(Lnet/minecraft/world/inventory/tooltip/TooltipComponent;)Lnet/minecraft/client/gui/screens/inventory/tooltip/ClientTooltipComponent;",
					"createClientTooltipComponent", false),
			new Rule("net.minecraft.client.gui.screens.worldselection.WorldCreationUiState", "getPresetEditor",
					"()Lnet/minecraft/client/gui/screens/worldselection/PresetEditor;", ForeignType.PRESET_EDITOR_MANAGER,
					"get", "(Lnet/minecraft/resources/ResourceKey;)Lnet/minecraft/client/gui/screens/worldselection/PresetEditor;",
					"getPresetEditor", true),
			new Rule("net.minecraft.client.multiplayer.ClientLevel", "lambda$new$0",
					"(Lit/unimi/dsi/fastutil/objects/Object2ObjectArrayMap;)V", ForeignType.COLOR_RESOLVER_MANAGER,
					"registerBlockTintCaches", "(Lnet/minecraft/client/multiplayer/ClientLevel;Ljava/util/Map;)V",
					"registerBlockTintCaches", false));

	static record Rule(String host, String method, String methodDescriptor, ForeignType family,
			String call, String descriptor, String runtimeCall, boolean handle) {
		String owner(Ecosystem ecosystem) { return family.binary(ecosystem).replace('.', '/'); }
	}

	private static boolean enabled() {
		return !"off".equalsIgnoreCase(System.getProperty("forbric.forgeClientConsumers"))
				&& !"off".equalsIgnoreCase(System.getProperty("forbric.forgeClientInit"));
	}

	@Override public String name() { return "forbric-forge-client-consumers"; }

	@Override public AnchorSet anchors() {
		if (!enabled()) return AnchorSet.scanned("Forge client consumers or their registration initialization are disabled");
		return AnchorSet.of(RULES.stream().map(rule -> new AnchorSet.Anchor(rule.host(), AnchorSet.Severity.REQUIRED,
				"traditional Forge client registrations would not reach " + rule.method())).toArray(AnchorSet.Anchor[]::new));
	}

	@Override
	public byte[] transform(String className, byte[] original, TransformContext context) {
		if (!enabled() || original == null || original.length == 0) return original;
		Rule rule = RULES.stream().filter(candidate -> candidate.host().equals(className)).findFirst().orElse(null);
		if (rule == null) return original;
		ClassNode node = new ClassNode();
		new ClassReader(original).accept(node, 0);
		MethodNode method = node.methods.stream().filter(candidate -> candidate.name.equals(rule.method())
				&& candidate.desc.equals(rule.methodDescriptor())).findFirst().orElse(null);
		if (method == null) return miss(rule, original, "the host method moved");
		int neo = references(method, rule, rule.owner(Ecosystem.NEOFORGE), rule.call(), rule.handle());
		// A native dual-family consumer or our existing funnel must not be called twice.
		if (references(method, rule, RUNTIME, rule.runtimeCall(), rule.handle()) > 0
				|| references(method, rule, rule.owner(Ecosystem.FORGE), rule.call(), false) > 0
				|| references(method, rule, rule.owner(Ecosystem.FORGE), rule.call(), true) > 0) return original;
		if (neo != 1) return miss(rule, original, "expected one NeoForge consumer, found " + neo);
		for (var instruction : method.instructions) {
			if (!rule.handle() && instruction instanceof MethodInsnNode call
					&& matches(call, rule.owner(Ecosystem.NEOFORGE), rule.call(), rule.descriptor())) {
				call.owner = RUNTIME;
				call.name = rule.runtimeCall();
			} else if (rule.handle() && instruction instanceof InvokeDynamicInsnNode dynamic) {
				for (int i = 0; i < dynamic.bsmArgs.length; i++) {
					if (dynamic.bsmArgs[i] instanceof Handle handle
							&& matches(handle, rule.owner(Ecosystem.NEOFORGE), rule.call(), rule.descriptor())) {
						dynamic.bsmArgs[i] = new Handle(handle.getTag(), RUNTIME, rule.runtimeCall(),
								handle.getDesc(), handle.isInterface());
					}
				}
			}
		}
		ClassWriter writer = new ClassWriter(0);
		node.accept(writer);
		return writer.toByteArray();
	}

	private static boolean matches(MethodInsnNode call, String owner, String name, String descriptor) {
		return call.getOpcode() == Opcodes.INVOKESTATIC && !call.itf && call.owner.equals(owner)
				&& call.name.equals(name) && call.desc.equals(descriptor);
	}

	private static boolean matches(Handle handle, String owner, String name, String descriptor) {
		return handle.getTag() == Opcodes.H_INVOKESTATIC && !handle.isInterface() && handle.getOwner().equals(owner)
				&& handle.getName().equals(name) && handle.getDesc().equals(descriptor);
	}

	private static int references(MethodNode method, Rule rule, String owner, String name, boolean handles) {
		int count = 0;
		for (var instruction : method.instructions) {
			if (!handles && instruction instanceof MethodInsnNode call && matches(call, owner, name, rule.descriptor())) count++;
			if (handles && instruction instanceof InvokeDynamicInsnNode dynamic) {
				for (Object argument : dynamic.bsmArgs) {
					if (argument instanceof Handle handle && matches(handle, owner, name, rule.descriptor())) count++;
				}
			}
		}
		return count;
	}

	private static byte[] miss(Rule rule, byte[] original, String reason) {
		ForbricLog.warn("[Forbric/ClientConsumers] did not bridge %s.%s: %s; Forge client registrations for this consumer remain unavailable",
				rule.host(), rule.method(), reason);
		return original;
	}
}
