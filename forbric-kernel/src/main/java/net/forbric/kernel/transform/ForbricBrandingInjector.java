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
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import net.forbric.api.Ecosystem;
import net.forbric.api.ForeignType;
import net.forbric.kernel.util.ForbricLog;

/**
 * Shows Forbric's release where the game shows the loader: the title screen's bottom-left and F3's version line.
 *
 * <p>NeoForge's title screen draws its {@code BrandingControl}: "NeoForge 26.2.0.88 (N mods loaded)",
 * where N is NeoForge's {@code ModList} — only the NeoForge mods. It now reads
 * {@code forbric-v0.3.1-beta (N mods loaded)} with N every mod the player installed, of both ecosystems
 * ({@code ForbricBranding.installedModCount}, what Forbric's Mods screen lists). F3's
 * "Minecraft 26.2 (26.2-forbric/neoforge)" — the launcher's profile id and the client brand — reads
 * "Minecraft 26.2 (forbric-v0.3.1-beta/forbric)".
 *
 * <p>Display only: {@code ClientBrandRetriever} and the brand NeoForge sends to servers are untouched. Each edit is
 * a one-for-one call swap with the stack unchanged, applied only where the call sequence is exactly the one below.
 * {@code -Dforbric.branding=off} leaves both as the base has it.
 */
public final class ForbricBrandingInjector implements ClassTransformer {
	public static final String PROPERTY = "forbric.branding";
	static final String BRANDING_CONTROL = "net.neoforged.neoforge.internal.BrandingControl";
	static final String DEBUG_VERSION = "net.minecraft.client.gui.components.debug.DebugEntryVersion";
	static final String BRANDING = "net/forbric/kernel/util/ForbricBranding";
	private static final String NEO_VERSION = "net/neoforged/neoforge/common/NeoForgeVersion";

	public static boolean enabled() {
		return !"off".equalsIgnoreCase(System.getProperty(PROPERTY, "on"));
	}

	@Override
	public String name() {
		return "forbric-branding";
	}

	@Override
	public AnchorSet anchors() {
		if (!enabled()) return AnchorSet.scanned("switched off by -D" + PROPERTY);
		return AnchorSet.of(
				new AnchorSet.Anchor(BRANDING_CONTROL, AnchorSet.Severity.REQUIRED,
						"the title screen names NeoForge and counts only NeoForge's mods, not Forbric and all of them"),
				new AnchorSet.Anchor(DEBUG_VERSION, AnchorSet.Severity.REQUIRED,
						"F3 shows the launcher profile id and NeoForge's brand instead of Forbric's release"));
	}

	@Override
	public byte[] transform(String className, byte[] bytes, TransformContext context) {
		if (!enabled() || bytes == null || bytes.length == 0) return bytes;
		boolean title = BRANDING_CONTROL.equals(className);
		if (!title && !DEBUG_VERSION.equals(className)) return bytes;
		ClassNode node = new ClassNode();
		new ClassReader(bytes).accept(node, 0);
		if (!(title ? brandTitleScreen(node) : brandDebugVersion(node))) return bytes;
		ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
		node.accept(writer);
		ForbricLog.info(title
				? "[Forbric/Branding] the title screen names Forbric's release and counts every installed mod, not NeoForge's"
				: "[Forbric/Branding] F3's version line names Forbric's release");
		return writer.toByteArray();
	}

	/**
	 * In {@code computeBranding}: {@code ModList.get().size()} becomes the installed-mod count, and
	 * {@code "NeoForge " + NeoForgeVersion.getVersion()} becomes Forbric's display name.
	 */
	static boolean brandTitleScreen(ClassNode node) {
		MethodNode compute = method(node, "computeBranding", "()V");
		if (compute == null || calls(compute, BRANDING)) return false;
		String modList = ForeignType.MOD_LIST.internal(Ecosystem.NEOFORGE);
		MethodInsnNode get = null, size = null, version = null;
		InvokeDynamicInsnNode concat = null;
		for (AbstractInsnNode insn : compute.instructions) {
			if (insn instanceof MethodInsnNode call) {
				if (call.owner.equals(modList) && call.name.equals("get")) get = call;
				else if (call.owner.equals(modList) && call.name.equals("size") && call.getPrevious() == get) size = call;
				else if (call.owner.equals(NEO_VERSION) && call.name.equals("getVersion")) version = call;
			} else if (insn instanceof InvokeDynamicInsnNode indy && version != null && indy.getPrevious() == version
					&& indy.desc.equals("(Ljava/lang/String;)Ljava/lang/String;")) {
				concat = indy;
			}
		}
		if (get == null || size == null || version == null || concat == null) return false;
		compute.instructions.remove(get);
		compute.instructions.set(size, new MethodInsnNode(Opcodes.INVOKESTATIC, BRANDING, "installedModCount", "()I", false));
		compute.instructions.remove(concat);
		compute.instructions.set(version, new MethodInsnNode(Opcodes.INVOKESTATIC, BRANDING, "display", "()Ljava/lang/String;", false));
		return true;
	}

	/** In {@code display}: the launcher's profile id becomes Forbric's display name, and the brand token Forbric's. */
	static boolean brandDebugVersion(ClassNode node) {
		MethodNode display = null;
		for (MethodNode m : node.methods) if (m.name.equals("display")) display = m;
		if (display == null || calls(display, BRANDING)) return false;
		MethodInsnNode instance = null, launched = null, brand = null;
		for (AbstractInsnNode insn : display.instructions) {
			if (!(insn instanceof MethodInsnNode call)) continue;
			if (call.owner.equals("net/minecraft/client/Minecraft") && call.name.equals("getInstance")) instance = call;
			else if (call.name.equals("getLaunchedVersion") && call.getPrevious() == instance) launched = call;
			else if (call.owner.equals("net/minecraft/client/ClientBrandRetriever") && call.name.equals("getClientModName")) brand = call;
		}
		if (instance == null || launched == null || brand == null) return false;
		display.instructions.remove(instance);
		display.instructions.set(launched, new MethodInsnNode(Opcodes.INVOKESTATIC, BRANDING, "display", "()Ljava/lang/String;", false));
		display.instructions.set(brand, new MethodInsnNode(Opcodes.INVOKESTATIC, BRANDING, "brand", "()Ljava/lang/String;", false));
		return true;
	}

	private static MethodNode method(ClassNode node, String name, String desc) {
		for (MethodNode m : node.methods) if (m.name.equals(name) && m.desc.equals(desc)) return m;
		return null;
	}

	private static boolean calls(MethodNode method, String owner) {
		for (AbstractInsnNode insn : method.instructions) {
			if (insn instanceof MethodInsnNode call && call.owner.equals(owner)) return true;
		}
		return false;
	}
}
