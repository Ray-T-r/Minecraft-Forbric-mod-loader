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
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import net.forbric.kernel.util.ForbricLog;

/**
 * Lets a mod ask "are the client's options built yet?" BEFORE {@code Minecraft} exists, instead of dying on a null
 * instance.
 *
 * <p>fabric-api's key-mapping registry opens {@code registerKeyMapping} with
 * {@code if (Minecraft.getInstance().options != null) throw new IllegalStateException("GameOptions has already
 * been initialised")} and then appends to a static list that {@code Options}' constructor drains. The check exists to
 * reject registration that is TOO LATE. It reads {@code getInstance().options} to decide that, and so it also throws
 * — with a {@code NullPointerException} naming neither the mod nor the timing — when it runs too EARLY, which is the one
 * case that is provably harmless: no {@code Minecraft} means no options, which is exactly the answer the branch is for.
 *
 * <p>On Fabric nobody reaches the early case, because nothing calls into a mod before {@code Minecraft.<init>}. Here a
 * guest mixin on a merged-base seam can run earlier than its Fabric equivalent and drag a mod's {@code <clinit>} along:
 * Flashback's {@code MixinMappedRegistry} HEAD injection on {@code freeze} initialises {@code Flashback}, whose
 * {@code <clinit>} registers four key mappings — and the kernel's registration window is at
 * {@code ClientModLoader.begin()}, inside {@code Main.main}, before {@code new Minecraft(...)}, by NeoForge's lifecycle.
 *
 * <p>So the rule is about the read, not about whose code makes it: in a mod's class (anything outside the game's and the
 * loaders' own packages), wherever {@code Minecraft.getInstance().options} is tested against null with {@code IFNULL},
 * a null instance takes that same branch — "the options are not built yet". Two instructions in front of the read,
 * reusing the label the {@code IFNULL} already jumps to: no new label, no new frame, no change while an instance exists.
 *
 * <p>{@code -Dforbric.earlyKeyMapping=off} restores the unguarded reads.
 */
public final class EarlyKeyMappingRegistrationInjector implements ClassTransformer {
	/** The game's and the loaders' own packages: their reads are never made before {@code Minecraft} exists. */
	private static final java.util.List<String> PLATFORM = java.util.List.of("net/minecraft/", "com/mojang/",
			"net/neoforged/", "net/minecraftforge/", "net/forbric/", "java/", "jdk/", "sun/");
	private static final byte[] MINECRAFT_NEEDLE = net.forbric.kernel.util.ByteScan.needle("net/minecraft/client/Minecraft");
	private static final byte[] OPTIONS_NEEDLE = net.forbric.kernel.util.ByteScan.needle("options");

	private static final String MINECRAFT = "net/minecraft/client/Minecraft";
	private static final String GET_INSTANCE = "getInstance";
	private static final String GET_INSTANCE_DESC = "()Lnet/minecraft/client/Minecraft;";
	private static final String OPTIONS = "options";

	static final String PROPERTY = "forbric.earlyKeyMapping";

	static boolean enabled() {
		return !"off".equalsIgnoreCase(System.getProperty(PROPERTY, "on"));
	}

	@Override
	public String name() {
		return "forbric-early-key-mapping";
	}

	@Override
	public AnchorSet anchors() {
		return AnchorSet.scanned("any mod class testing Minecraft.getInstance().options against null: run before Minecraft "
				+ "exists, the test died on a NullPointerException that names neither the mod nor the timing");
	}

	@Override
	public byte[] transform(String className, byte[] classBytes, TransformContext context) {
		if (classBytes == null || classBytes.length == 0 || !enabled()) return classBytes;
		String internal = className.replace('.', '/');
		for (String platform : PLATFORM) if (internal.startsWith(platform)) return classBytes;
		if (!net.forbric.kernel.util.ByteScan.contains(classBytes, MINECRAFT_NEEDLE)
				|| !net.forbric.kernel.util.ByteScan.contains(classBytes, OPTIONS_NEEDLE)) return classBytes;
		return guardTheInstanceReads(className, classBytes);
	}

	private static byte[] guardTheInstanceReads(String className, byte[] classBytes) {
		ClassNode node = new ClassNode();
		new ClassReader(classBytes).accept(node, 0);
		int guarded = 0;
		java.util.List<String> where = new java.util.ArrayList<>();
		for (MethodNode m : node.methods) {
			int here = 0;
			for (AbstractInsnNode insn = m.instructions.getFirst(); insn != null; insn = insn.getNext()) {
				if (!(insn instanceof MethodInsnNode call) || call.getOpcode() != Opcodes.INVOKESTATIC
						|| !MINECRAFT.equals(call.owner) || !GET_INSTANCE.equals(call.name) || !GET_INSTANCE_DESC.equals(call.desc)) continue;
				AbstractInsnNode field = nextReal(call);
				if (!(field instanceof FieldInsnNode f) || f.getOpcode() != Opcodes.GETFIELD || !MINECRAFT.equals(f.owner)
						|| !OPTIONS.equals(f.name)) continue;
				AbstractInsnNode branch = nextReal(f);
				if (!(branch instanceof JumpInsnNode ifNull) || ifNull.getOpcode() != Opcodes.IFNULL) continue;
				if (alreadyGuarded(call, ifNull)) continue;
				// Branch to the label the existing IFNULL already jumps to: the "options are not built" arm. That label
				// is already a branch target with an empty stack, as at the new jump, so no frame changes.
				InsnList head = new InsnList();
				head.add(new MethodInsnNode(Opcodes.INVOKESTATIC, MINECRAFT, GET_INSTANCE, GET_INSTANCE_DESC, false));
				head.add(new JumpInsnNode(Opcodes.IFNULL, ifNull.label));
				m.instructions.insertBefore(call, head);
				here++;
			}
			if (here > 0) {
				guarded += here;
				where.add(m.name);
			}
		}
		if (guarded == 0) return classBytes;
		ForbricLog.info("[Forbric/KeyMapping] %s now treats a null Minecraft as \"options not built yet\" where it tests "
				+ "Minecraft.getInstance().options against null (%d read(s), in %s) — the test was there to tell too late "
				+ "from in time, and threw on the one case that is provably in time: running before Minecraft exists",
				className, guarded, String.join(", ", where));
		ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
		node.accept(writer);
		return writer.toByteArray();
	}

	/** Whether the read already has the guard in front of it: {@code getInstance(); IFNULL <the same label>}. */
	private static boolean alreadyGuarded(AbstractInsnNode read, JumpInsnNode ifNull) {
		AbstractInsnNode before = previousReal(read);
		AbstractInsnNode first = before == null ? null : previousReal(before);
		return before instanceof JumpInsnNode jump && jump.getOpcode() == Opcodes.IFNULL && jump.label == ifNull.label
				&& first instanceof MethodInsnNode m && m.getOpcode() == Opcodes.INVOKESTATIC && MINECRAFT.equals(m.owner)
				&& GET_INSTANCE.equals(m.name);
	}

	private static AbstractInsnNode previousReal(AbstractInsnNode from) {
		AbstractInsnNode insn = from.getPrevious();
		while (insn != null && insn.getOpcode() < 0) insn = insn.getPrevious();
		return insn;
	}

	/** The next instruction that is not a label, line number or frame. */
	private static AbstractInsnNode nextReal(AbstractInsnNode from) {
		AbstractInsnNode insn = from.getNext();
		while (insn != null && insn.getOpcode() < 0) insn = insn.getNext();
		return insn;
	}
}
