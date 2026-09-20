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

import java.util.LinkedHashSet;
import java.util.Set;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import net.forbric.kernel.util.ForbricLog;

/**
 * Makes {@code ItemStack.addDetailsToTooltip} scrapeable again for fabric-item-api-v1's tooltip-order registry.
 *
 * <p>fabric-item-api-v1's {@code VanillaTooltipProviderOrder} learns vanilla's tooltip order at class
 * initialisation by reading {@code ItemStack.addDetailsToTooltip}'s bytecode through the bytecode provider:
 * every {@code GETSTATIC DataComponents.X} in order, deduplicated, with the {@code addAttributeTooltips} call
 * standing for {@code ATTRIBUTE_MODIFIERS}; an empty list is {@code IllegalStateException("Found no component
 * types")} — an {@code ExceptionInInitializerError} for any mod touching the registry. NeoForge's patch moved
 * vanilla's body to a private {@code addDetailsToTooltipComponents} and made {@code addDetailsToTooltip} a
 * dispatcher over its {@code ItemTooltipHandler}, so the scrape finds nothing.
 *
 * <p>The repair copies the scrape VIEW of the renamed body — each component type in order, the attribute call as
 * its marker — into the head of {@code addDetailsToTooltip} as straight-line {@code GETSTATIC; POP} pairs.
 * Verifier-trivial, no frames, dead to the JIT, and derived from the merge's own bytes rather than a hand-typed
 * list, so a NeoForge rename of the private body stands the repair down loudly instead of serving a stale
 * order. The ordering FEATURE itself stays NeoForge's ({@code ItemTooltipHandler} orders tooltips on this base;
 * fabric's {@code ItemStackMixin} anchors on calls that are gone), which the catalog says on fabric-item-api-v1.
 * {@code -Dforbric.tooltipOrderScrape=off}.
 */
public final class TooltipOrderScrapeInjector implements ClassTransformer {
	public static final String PROPERTY = "forbric.tooltipOrderScrape";

	static final String ITEM_STACK = "net.minecraft.world.item.ItemStack";
	static final String ITEM_STACK_INTERNAL = "net/minecraft/world/item/ItemStack";
	static final String METHOD = "addDetailsToTooltip";
	static final String RENAMED = "addDetailsToTooltipComponents";
	static final String DESC = "(Lnet/minecraft/world/item/Item$TooltipContext;Lnet/minecraft/world/item/component/TooltipDisplay;"
			+ "Lnet/minecraft/world/entity/player/Player;Lnet/minecraft/world/item/TooltipFlag;Ljava/util/function/Consumer;)V";
	static final String DATA_COMPONENTS = "net/minecraft/core/component/DataComponents";
	static final String DATA_COMPONENT_TYPE = "Lnet/minecraft/core/component/DataComponentType;";
	static final String ATTRIBUTE_TOOLTIPS = "addAttributeTooltips";
	static final String ATTRIBUTE_TOOLTIPS_DESC = "(Ljava/util/function/Consumer;Lnet/minecraft/world/item/component/TooltipDisplay;"
			+ "Lnet/minecraft/world/entity/player/Player;)V";
	static final String ATTRIBUTE_MODIFIERS = "ATTRIBUTE_MODIFIERS";
	static final int MINIMUM = 20;

	private int restored;

	public static boolean enabled() {
		return !"off".equalsIgnoreCase(System.getProperty(PROPERTY, "on"));
	}

	@Override
	public String name() {
		return "forbric:tooltip-order-scrape";
	}

	@Override
	public AnchorSet anchors() {
		if (!enabled()) return AnchorSet.scanned("switched off by -D" + PROPERTY);
		return AnchorSet.of(new AnchorSet.Anchor(ITEM_STACK, AnchorSet.Severity.REQUIRED,
				"any Fabric mod touching fabric-item-api's tooltip-order registry dies with ExceptionInInitializerError "
						+ "(\"Found no component types\")"));
	}

	@Override
	public byte[] transform(String className, byte[] classBytes, TransformContext context) {
		if (!enabled() || classBytes == null || classBytes.length == 0 || !ITEM_STACK.equals(className)) return classBytes;

		ClassNode node = new ClassNode();
		new ClassReader(classBytes).accept(node, 0);
		MethodNode dispatcher = find(node, METHOD, DESC);
		MethodNode body = find(node, RENAMED, DESC);
		if (dispatcher == null) {
			ForbricLog.warn("[Forbric/TooltipOrder] %s has no %s%s — the seam has drifted", className, METHOD, DESC);
			return classBytes;
		}
		if (readsAnyComponent(dispatcher)) return classBytes;    // a rebuilt base, or a second pass
		if (body == null) {
			ForbricLog.warn("[Forbric/TooltipOrder] %s has no %s to copy vanilla's component order from — NeoForge renamed "
					+ "the body again; fabric-item-api's tooltip-order registry will throw", className, RENAMED);
			return classBytes;
		}

		Set<String> order = new LinkedHashSet<>();
		boolean marker = false;
		for (AbstractInsnNode insn = body.instructions.getFirst(); insn != null; insn = insn.getNext()) {
			if (insn instanceof FieldInsnNode f && f.getOpcode() == Opcodes.GETSTATIC && DATA_COMPONENTS.equals(f.owner)
					&& DATA_COMPONENT_TYPE.equals(f.desc)) {
				order.add(f.name);
			} else if (insn instanceof MethodInsnNode c && c.getOpcode() == Opcodes.INVOKEVIRTUAL && ITEM_STACK_INTERNAL.equals(c.owner)
					&& ATTRIBUTE_TOOLTIPS.equals(c.name) && ATTRIBUTE_TOOLTIPS_DESC.equals(c.desc)) {
				order.add(ATTRIBUTE_MODIFIERS);
				marker = true;
			}
		}
		if (order.size() < MINIMUM || !marker) {
			ForbricLog.warn("[Forbric/TooltipOrder] %s.%s reads %d component type(s)%s — not the vanilla body this repair "
					+ "copies from; left as it is", className, RENAMED, order.size(), marker ? "" : " and never adds attribute tooltips");
			return classBytes;
		}

		// GETSTATIC; POP per entry, at the very head: fabric's scrape reads the marker CALL in vanilla, so here the
		// marker is the field it maps that call to. Straight-line, stack depth one, no frames touched.
		InsnList head = new InsnList();
		for (String name : order) {
			head.add(new FieldInsnNode(Opcodes.GETSTATIC, DATA_COMPONENTS, name, DATA_COMPONENT_TYPE));
			head.add(new InsnNode(Opcodes.POP));
		}
		dispatcher.instructions.insert(head);
		dispatcher.maxStack = Math.max(dispatcher.maxStack, 1);
		restored = order.size();
		ForbricLog.info("[Forbric/TooltipOrder] restored a scrapeable vanilla component order of %d type(s) in ItemStack.%s — "
				+ "NeoForge moved the body to %s and fabric-item-api's tooltip-order registry scrapes the original name",
				order.size(), METHOD, RENAMED);
		// No claim about the ORDERING is made here, and that is the point of the comment. This repair only makes
		// the registry loadable; whether the entries it records are APPLIED depends on whether fabric-item-api's
		// own ItemStackMixin binds, which is decided later and elsewhere (MixinRetarget's R3 puts it on the
		// renamed body). Asserting "recorded but not applied" from here outlived the truth by exactly as long as
		// it took to fix the binding — the row went on saying it after the entries had started applying.

		ClassWriter writer = new ClassWriter(0);
		node.accept(writer);
		return writer.toByteArray();
	}

	private static boolean readsAnyComponent(MethodNode method) {
		for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
			if (insn instanceof FieldInsnNode f && f.getOpcode() == Opcodes.GETSTATIC && DATA_COMPONENTS.equals(f.owner)) return true;
		}
		return false;
	}

	private static MethodNode find(ClassNode node, String name, String desc) {
		for (MethodNode m : node.methods) if (m.name.equals(name) && m.desc.equals(desc)) return m;
		return null;
	}

	/** Component types the head now lists, for the boot summary. */
	public int restoredTypes() {
		return restored;
	}
}
