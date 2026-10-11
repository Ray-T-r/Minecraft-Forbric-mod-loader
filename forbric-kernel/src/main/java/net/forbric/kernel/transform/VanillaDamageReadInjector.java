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

import java.util.ArrayList;
import java.util.List;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

import net.forbric.kernel.util.ForbricLog;

/**
 * Gives {@code actuallyHurt} back vanilla's first read of its {@code damage} parameter, before armour, so a mod that
 * changes the damage there changes the damage.
 *
 * <p>Vanilla's {@code actuallyHurt(level, source, damage)} starts {@code damage = getDamageAfterArmorAbsorb(source,
 * damage)}: the first {@code fload 3} is the armour call's argument, and a Fabric mod's
 * {@code @ModifyVariable(at = @At(value = "LOAD", ordinal = 0), index = 3)} rewrites the damage about to be armoured.
 * The merged bodies are NeoForge's, which keep the amount in the entity's {@code DamageContainer} and do not read the
 * parameter until long after armour, magic, absorption and NeoForge's {@code LivingDamageEvent.Pre}. So that injector
 * lands at the end of the pipeline, too late to change anything — and TaCZ's pair of them breaks outright: its
 * {@code LOAD} handler creates the {@code LivingHurtEvent} it {@code @Share}s, its {@code @Inject} at the armour call
 * reads it, and the read now runs first: every hit on a living entity was a NullPointerException on the server thread.
 *
 * <p>So right after the invulnerability check — before MinecraftForge's Hurt seam, which reads the container —
 * {@code KernelLivingDamage.vanillaRead(this, container, damage, damage)} is called. Its first {@code fload 3} is the
 * one such an injector binds to; the second is the parameter as it came. Only when the two differ does the helper move
 * the container by the difference, so with no such mod NeoForge's damage is untouched, bit for bit — including the
 * invulnerability-frame call, whose parameter and container already differ by a reduction other mods may modify.
 *
 * <p>Applied to {@code LivingEntity} and {@code Player} (which overrides it the same way), only where the method opens
 * with the invulnerability check and armours the container's amount rather than the parameter. A read of the
 * parameter before armour does not make the body vanilla's: the edit-splicing merge keeps MinecraftForge's
 * {@code damage = ForgeHooks.onLivingHurt(this, source, damage)} right after the check, whose result NeoForge's body
 * never reads again, so a mod rewriting that first read would still change nothing. Registered after
 * {@code ForgeDamageSeamsInjector}, and placed right after the invulnerability check, so the read lands ahead of
 * MinecraftForge's Hurt — the seam's or the merged body's own. {@code -Dforbric.vanillaDamageRead=off}.
 */
public final class VanillaDamageReadInjector implements ClassTransformer {
	public static final String PROPERTY = "forbric.vanillaDamageRead";
	static final String LIVING = "net/minecraft/world/entity/LivingEntity";
	static final List<String> TARGETS = List.of("net.minecraft.world.entity.LivingEntity", "net.minecraft.world.entity.player.Player");
	static final String HURT_DESC = ForgeDamageSeamsInjector.HURT_DESC;
	static final String HELPER = ForgeDamageSeamsInjector.RUNTIME;
	static final String HELPER_DESC = "(L" + LIVING + ";L" + ForgeDamageSeamsInjector.CONTAINER + ";FF)V";

	public static boolean enabled() {
		return !"off".equalsIgnoreCase(System.getProperty(PROPERTY, "on"));
	}

	@Override
	public String name() {
		return "forbric-vanilla-damage-read";
	}

	@Override
	public AnchorSet anchors() {
		if (!enabled()) return AnchorSet.scanned("switched off by -D" + PROPERTY);
		List<AnchorSet.Anchor> anchors = new ArrayList<>();
		for (String target : TARGETS) {
			anchors.add(new AnchorSet.Anchor(target, AnchorSet.Severity.REQUIRED,
					"a mod changing the damage at actuallyHurt's first read changes nothing, and a pair that @Share state "
							+ "across it (TaCZ) throws on every hit"));
		}
		return AnchorSet.of(anchors.toArray(new AnchorSet.Anchor[0]));
	}

	@Override
	public byte[] transform(String className, byte[] bytes, TransformContext context) {
		if (!enabled() || bytes == null || bytes.length == 0 || !TARGETS.contains(className)) return bytes;
		ClassNode node = new ClassNode();
		new ClassReader(bytes).accept(node, 0);
		MethodNode hurt = null;
		for (MethodNode m : node.methods) if (m.name.equals("actuallyHurt") && m.desc.equals(HURT_DESC)) hurt = m;
		if (hurt == null || !repair(hurt)) return bytes;
		ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
		node.accept(writer);
		ForbricLog.info("[Forbric/Damage] %s.actuallyHurt reads its damage before armour again, as vanilla does — a mod "
				+ "changing it there now changes the damage NeoForge applies", className.substring(className.lastIndexOf('.') + 1));
		return writer.toByteArray();
	}

	static boolean repair(MethodNode method) {
		if ((method.access & (Opcodes.ACC_STATIC | Opcodes.ACC_ABSTRACT)) != 0) return false;
		List<AbstractInsnNode> code = new ArrayList<>();
		for (AbstractInsnNode insn : method.instructions) if (insn.getOpcode() >= 0) code.add(insn);
		// aload0 aload1 aload2 invokevirtual isInvulnerableTo; ifne <end>
		if (code.size() < 5 || !load(code.get(0), Opcodes.ALOAD, 0) || !load(code.get(1), Opcodes.ALOAD, 1)
				|| !load(code.get(2), Opcodes.ALOAD, 2)
				|| !(code.get(3) instanceof MethodInsnNode invulnerable) || !invulnerable.name.equals("isInvulnerableTo")
				|| code.get(4).getOpcode() != Opcodes.IFNE) return false;
		int armour = -1;
		FieldInsnNode containers = null;   // the body's own reference to the stack, owner as the body spells it
		for (int i = 0; i < code.size(); i++) {
			AbstractInsnNode insn = code.get(i);
			if (containers == null && insn instanceof FieldInsnNode field && field.getOpcode() == Opcodes.GETFIELD
					&& field.name.equals("damageContainers") && field.desc.equals("Ljava/util/Stack;")) containers = field;
			if (insn instanceof MethodInsnNode call && call.owner.equals(HELPER) && call.name.equals("vanillaRead")) return false;
			if (armour < 0 && insn instanceof MethodInsnNode call && call.name.equals("getDamageAfterArmorAbsorb")) armour = i;
		}
		// No armour call at all, or no container, is not this method's shape. Vanilla's flow armours the parameter
		// itself (its float argument is `fload 3`), so a mod changing the parameter changes the damage: nothing to do.
		// NeoForge's body armours the container's amount; there a read of the parameter before armour — such as
		// MinecraftForge's own Hurt hook, which a merge keeping both sides' edits carries — feeds nothing it applies.
		if (armour < 1 || containers == null || load(code.get(armour - 1), Opcodes.FLOAD, 3)) return false;

		InsnList read = new InsnList();
		read.add(new VarInsnNode(Opcodes.ALOAD, 0));
		read.add(new VarInsnNode(Opcodes.ALOAD, 0));
		read.add(new FieldInsnNode(Opcodes.GETFIELD, containers.owner, "damageContainers", "Ljava/util/Stack;"));
		read.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/util/Stack", "peek", "()Ljava/lang/Object;", false));
		read.add(new TypeInsnNode(Opcodes.CHECKCAST, ForgeDamageSeamsInjector.CONTAINER));
		read.add(new VarInsnNode(Opcodes.FLOAD, 3));
		read.add(new VarInsnNode(Opcodes.FLOAD, 3));
		read.add(new MethodInsnNode(Opcodes.INVOKESTATIC, HELPER, "vanillaRead", HELPER_DESC, false));
		method.instructions.insert(code.get(4), read);
		return true;
	}

	private static boolean load(AbstractInsnNode insn, int opcode, int slot) {
		return insn instanceof VarInsnNode v && v.getOpcode() == opcode && v.var == slot;
	}
}
