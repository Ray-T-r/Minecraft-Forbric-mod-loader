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
 * Lets a Fabric mod register a key mapping BEFORE {@code Minecraft} exists, instead of dying on a null instance.
 *
 * <p>fabric-api's {@code KeyMappingRegistryImpl.registerKeyMapping} opens with
 * {@code if (Minecraft.getInstance().options != null) throw new IllegalStateException("GameOptions has already
 * been initialised")} and then appends to a static list that {@code Options}' constructor drains through
 * {@code process(KeyMapping[])}. The check exists to reject registration that is TOO LATE. It reads
 * {@code getInstance().options} to decide that, and so it also throws — with a {@code NullPointerException}
 * naming neither the mod nor the timing — when registration is too EARLY, which is the one case that is
 * provably harmless: the list is static, {@code Options} has not been built yet, and {@code process} will pick
 * the mapping up when it is.
 *
 * <p>On Fabric nobody reaches the early case, because nothing calls into a mod before {@code Minecraft.<init>}.
 * Here they do, and the path is not the entrypoints — the kernel already moved those into {@code Minecraft.<init>}
 * for exactly this reason ({@code ClientEntrypointHookInjector}). It is a guest MIXIN on a merged-base seam that
 * runs earlier than its Fabric equivalent and drags the mod's {@code <clinit>} along with it:
 *
 * <ul>
 *   <li>Flashback's {@code MixinMappedRegistry} is {@code @Inject(method = "freeze", at = @At("HEAD"))} with the
 *       body {@code if (!this.frozen && Flashback.isInReplay())} — {@code !frozen} meaning "this is the real
 *       freeze". Its {@code invokestatic Flashback.isInReplay} initialises {@code Flashback}, whose
 *       {@code <clinit>} registers four key mappings.</li>
 *   <li>On Fabric the first {@code MappedRegistry.freeze()} happens inside {@code Minecraft.<init>} after the mod
 *       initializers, because {@code fabric-registry-sync-v0} moves it there ({@code BuiltInRegistriesMixin}
 *       cancels the {@code Bootstrap} freeze, {@code MainMixin} / client {@code MinecraftMixin} re-run it later).
 *       Measured on a stock Fabric 26.2 client: {@code Flashback} initialises at 11.48s, the freeze at 12.15s.</li>
 *   <li>Here those three mixins are suppressed on purpose — the kernel owns the single freeze and opens its own
 *       registration window around mod init ({@link net.forbric.kernel.mixin.MergedBaseMixinCompat}) — and that
 *       window is at {@code ClientModLoader.begin()}, i.e. still inside {@code Main.main}, BEFORE
 *       {@code new Minecraft(...)}. So the freeze the guest mixin is waiting for arrives while
 *       {@code Minecraft.getInstance()} is still null, and the client dies in {@code Bootstrap} with a stack
 *       that names {@code MappedRegistry}, {@code Flashback} and fabric-api, and nothing about timing.</li>
 * </ul>
 *
 * <p>Moving the kernel's freeze is not the fix: NeoForge's lifecycle puts mod registration at
 * {@code ClientModLoader.begin()} and that is before {@code Minecraft} by construction. What is wrong is not
 * WHEN the freeze happens but that a legal-but-early call into this API is a crash instead of a no-op. So the
 * edit is to the API's own guard: two instructions at the head of {@code registerKeyMapping} that skip the check
 * when there is no {@code Minecraft} yet, reusing the branch target the existing {@code IFNULL} already has —
 * no new label, no new frame, no change to the late-registration rejection it was written for.
 *
 * <p>{@code -Dforbric.earlyKeyMapping=off} restores the unguarded read.
 */
public final class EarlyKeyMappingRegistrationInjector implements ClassTransformer {
	private static final String IMPL = "net.fabricmc.fabric.impl.client.keymapping.KeyMappingRegistryImpl";
	private static final String REGISTER = "registerKeyMapping";
	private static final String REGISTER_DESC = "(Lnet/minecraft/client/KeyMapping;)Lnet/minecraft/client/KeyMapping;";

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
		return AnchorSet.of(new AnchorSet.Anchor(IMPL, AnchorSet.Severity.REQUIRED,
				"a Fabric mod whose class initialiser registers a key mapping, and which a guest mixin wakes up "
						+ "before Minecraft exists, takes the whole client down in Bootstrap with a "
						+ "NullPointerException that names neither the mod nor the timing"));
	}

	@Override
	public byte[] transform(String className, byte[] classBytes, TransformContext context) {
		if (classBytes == null || classBytes.length == 0) return classBytes;
		if (!IMPL.equals(className)) return classBytes;
		if (!enabled()) return classBytes;
		return guardTheInstanceRead(className, classBytes);
	}

	private static byte[] guardTheInstanceRead(String className, byte[] classBytes) {
		ClassNode node = new ClassNode();
		new ClassReader(classBytes).accept(node, 0);

		MethodNode target = null;
		for (MethodNode m : node.methods) {
			if (REGISTER.equals(m.name) && REGISTER_DESC.equals(m.desc) && (m.access & Opcodes.ACC_STATIC) != 0) {
				target = m;
				break;
			}
		}
		if (target == null) {
			ForbricLog.warn("[Forbric/KeyMapping] %s has no static %s%s — a mod registering a key mapping before "
					+ "Minecraft exists will still NPE; re-derive EarlyKeyMappingRegistrationInjector",
					className, REGISTER, REGISTER_DESC);
			return classBytes;
		}

		if (alreadyGuarded(target)) return classBytes;

		// The guard is the FIRST `Minecraft.getInstance().options` whose value is consumed by an IFNULL. Matching
		// the whole triple rather than just the getInstance call is what keeps this from re-pointing some other
		// read of the field if fabric-api ever grows one.
		AbstractInsnNode call = null;
		JumpInsnNode ifNull = null;
		for (AbstractInsnNode insn = target.instructions.getFirst(); insn != null; insn = insn.getNext()) {
			if (!(insn instanceof MethodInsnNode m) || m.getOpcode() != Opcodes.INVOKESTATIC) continue;
			if (!MINECRAFT.equals(m.owner) || !GET_INSTANCE.equals(m.name) || !GET_INSTANCE_DESC.equals(m.desc)) continue;

			AbstractInsnNode field = nextReal(m);
			if (!(field instanceof FieldInsnNode f) || f.getOpcode() != Opcodes.GETFIELD) continue;
			if (!MINECRAFT.equals(f.owner) || !OPTIONS.equals(f.name)) continue;

			AbstractInsnNode branch = nextReal(f);
			if (!(branch instanceof JumpInsnNode j) || j.getOpcode() != Opcodes.IFNULL) continue;

			call = m;
			ifNull = j;
			break;
		}

		if (call == null) {
			// Not a finding by itself: a fabric-api that no longer reads the instance here needs no guard. Said
			// once, at debug, because the anchor ledger already reports "handed the class, made no edit".
			ForbricLog.debug("[Forbric/KeyMapping] %s.%s does not read Minecraft.getInstance().options — nothing "
					+ "to guard", className, REGISTER);
			return classBytes;
		}

		// Branch to the label the existing IFNULL already jumps to: the "options is null, registration is in time"
		// arm. That label is already a branch target, so its frame is unchanged and the stack at the new jump is
		// empty exactly as it is at the old one.
		InsnList head = new InsnList();
		head.add(new MethodInsnNode(Opcodes.INVOKESTATIC, MINECRAFT, GET_INSTANCE, GET_INSTANCE_DESC, false));
		head.add(new JumpInsnNode(Opcodes.IFNULL, ifNull.label));
		target.instructions.insertBefore(call, head);

		ForbricLog.info("[Forbric/KeyMapping] %s.%s now treats a null Minecraft as \"options not built yet\" — the "
				+ "read was there to reject registration that is too LATE, and threw on the one case that is "
				+ "provably safe, which is the case a guest mixin waking a mod's <clinit> before Minecraft "
				+ "exists puts it in", className, REGISTER);

		ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
		node.accept(writer);
		return writer.toByteArray();
	}

	/**
	 * True when this method already opens with the guard, so a second pass adds nothing.
	 *
	 * <p>The shape is unmistakable: the untouched method's first real instruction is the {@code getInstance()}
	 * whose value a {@code GETFIELD options} consumes, never a bare {@code IFNULL}.
	 */
	private static boolean alreadyGuarded(MethodNode method) {
		AbstractInsnNode first = method.instructions.getFirst();
		while (first != null && first.getOpcode() < 0) first = first.getNext();
		if (!(first instanceof MethodInsnNode m) || m.getOpcode() != Opcodes.INVOKESTATIC) return false;
		if (!MINECRAFT.equals(m.owner) || !GET_INSTANCE.equals(m.name) || !GET_INSTANCE_DESC.equals(m.desc)) return false;
		AbstractInsnNode next = nextReal(m);
		return next != null && next.getOpcode() == Opcodes.IFNULL;
	}

	/** The next instruction that is not a label, line number or frame. */
	private static AbstractInsnNode nextReal(AbstractInsnNode from) {
		AbstractInsnNode insn = from.getNext();
		while (insn != null && insn.getOpcode() < 0) insn = insn.getNext();
		return insn;
	}
}
