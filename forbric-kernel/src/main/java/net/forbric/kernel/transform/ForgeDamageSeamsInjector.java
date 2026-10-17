/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.transform;

import java.util.ArrayList;
import java.util.List;

import net.forbric.kernel.util.ForbricLog;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FrameNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;
import net.forbric.kernel.util.Caught;

/**
 * MinecraftForge's Hurt, Damage and player-Attack events get their positions back in NeoForge's damage pipeline.
 *
 * <p>The merged {@code actuallyHurt} and {@code Player.hurtServer} are NeoForge's: nothing calls
 * {@code ForgeHooks.onLivingHurt}, {@code onLivingDamage} or {@code onPlayerAttack}, and no NeoForge event sits where
 * the first two were, so a bridge has nothing to listen to. Tombstone's ghost immunity, its Voodoo Poppet and its
 * damage perks are exactly these listeners, and all of them did nothing. Three seams, each an {@code invokestatic}
 * into {@code KernelLivingDamage} with nothing else that another mod's injector could match:
 *
 * <ul>
 *   <li><b>Hurt</b>, in {@code actuallyHurt} right after the {@code isInvulnerableTo} check and before armour —
 *       MinecraftForge's position. True from the helper returns, as MinecraftForge's {@code amount <= 0} does; a
 *       listener that killed the entity returns too, so NeoForge's "killed during LivingDamageEvent.Pre" check
 *       cannot throw.</li>
 *   <li><b>Damage</b>, right after the health damage is read back from the container (after armour, magic and
 *       absorption; after NeoForge's Pre and its dead check), rewriting that local.</li>
 *   <li><b>player Attack</b>, at the head of {@code Player.hurtServer}: before difficulty scaling and before the
 *       zero-damage return, so a snowball is still an attack. {@code Player.<clinit>} tells the attack forward the
 *       seam is in, so players are asked here and only here.</li>
 * </ul>
 *
 * <p>Each seam is placed only when its proof holds and no MinecraftForge call is already there — each on its own: a
 * merge that keeps both sides' edits may already call some of MinecraftForge's hooks natively (the edit-spliced base
 * calls {@code onLivingDamage} in {@code LivingEntity}'s {@code actuallyHurt} and {@code onPlayerAttack} at the head of
 * {@code hurtServer}, and refuses {@code onLivingHurt}, whose answer NeoForge's body would never read) and lose another,
 * and only the lost ones get a seam. Where Hurt gets a seam and Damage is the body's own call, that call is routed
 * through the kernel so the zero-damage hits MinecraftForge returns on before Damage still skip it. A native player
 * attack is still told to the attack forward, which otherwise asks players a second time. The outcome is judged by {@link #CLAIM}
 * on the class this hands back — every hook asked, natively or through a seam — not by whether it edited.
 * {@code -Dforbric.forgeDamageSeams=off} leaves both classes as merged (the attack forward then asks every entity).
 */
public final class ForgeDamageSeamsInjector implements ClassTransformer {
	public static final String PROPERTY = "forbric.forgeDamageSeams";
	static final String LIVING = "net/minecraft/world/entity/LivingEntity";
	static final String PLAYER = "net/minecraft/world/entity/player/Player";
	static final String CONTAINER = "net/neoforged/neoforge/common/damagesource/DamageContainer";
	static final String RUNTIME = "net/forbric/kernel/runtime/KernelLivingDamage";
	static final String SOURCE = "Lnet/minecraft/world/damagesource/DamageSource;";
	static final String HURT_DESC = "(Lnet/minecraft/server/level/ServerLevel;" + SOURCE + "F)V";
	static final String SERVER_DESC = "(Lnet/minecraft/server/level/ServerLevel;" + SOURCE + "F)Z";
	static final String FORGE_HOOKS = "net/minecraftforge/common/ForgeHooks";
	static final String NEO_COMMON_HOOKS = "net/neoforged/neoforge/common/CommonHooks";
	static final String CLAIM = "forbric-forge-damage-seams#minecraftForgesDamageHooksAsked";

	static boolean enabled() {
		return !"off".equalsIgnoreCase(System.getProperty(PROPERTY, "on"));
	}

	@Override public String name() { return "forbric-forge-damage-seams"; }

	@Override public AnchorSet anchors() {
		if (!enabled()) return AnchorSet.scanned("MinecraftForge's Hurt, Damage and player-Attack events left undelivered "
				+ "with -D" + PROPERTY + "=off");
		return AnchorSet.scanned("LivingEntity and Player, judged by the " + CLAIM + " claim: each MinecraftForge damage hook "
				+ "asked, natively or through a seam");
	}

	@Override public List<Claim> claims() {
		if (!enabled()) return List.of();
		String cost = "MinecraftForge's LivingHurtEvent and LivingDamageEvent never fire — damage perks, immunity and "
				+ "death-prevention in MinecraftForge mods do nothing";
		return List.of(new Claim(CLAIM, AnchorSet.of(new AnchorSet.Anchor(LIVING.replace('/', '.'), AnchorSet.Severity.REQUIRED, cost),
				new AnchorSet.Anchor(PLAYER.replace('/', '.'), AnchorSet.Severity.REQUIRED, cost))));
	}

	@Override public byte[] transform(String className, byte[] bytes, TransformContext context, ClaimReporter reporter) {
		byte[] result = transform(className, bytes, context);
		if (enabled() && result != null && result.length > 0
				&& (className.equals(LIVING.replace('/', '.')) || className.equals(PLAYER.replace('/', '.')))
				&& asksEveryHook(result)) reporter.hit(CLAIM);
		return result;
	}

	@Override public byte[] transform(String className, byte[] bytes, TransformContext context) {
		if (!enabled() || bytes == null || bytes.length == 0) return bytes;
		boolean player = className.equals(PLAYER.replace('/', '.'));
		if (!player && !className.equals(LIVING.replace('/', '.'))) return bytes;
		ClassNode node = new ClassNode();
		new ClassReader(bytes).accept(node, 0);
		List<String> asked = nativelyAsked(node);
		if (!asked.isEmpty()) {
			ForbricLog.info("[Forbric/Damage] %s: MinecraftForge's %s already asked by the merged body itself — no seam for it",
					className, String.join(", ", asked));
		}
		List<String> placed = repair(node);
		if (placed.isEmpty()) return bytes;
		ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
		node.accept(writer);
		boolean told = placed.remove(FORWARD_TOLD);
		if (!placed.isEmpty()) {
			ForbricLog.info("[Forbric/Damage] %s: MinecraftForge's %s back in NeoForge's damage pipeline, where MinecraftForge "
					+ "asks them", className, String.join(", ", placed));
		}
		if (told) {
			ForbricLog.info("[Forbric/Damage] %s: the player Attack forward told the merged hurtServer asks players itself",
					className);
		}
		return writer.toByteArray();
	}

	/** What {@link #repair} reports for telling the attack forward about a native player attack. */
	static final String FORWARD_TOLD = "the attack forward told";

	/**
	 * Whether {@code bytes} asks every MinecraftForge damage hook its class owes, natively or through a seam: Hurt and
	 * Damage in {@code actuallyHurt}, and for {@code Player} the attack at {@code hurtServer} with the forward told.
	 */
	static boolean asksEveryHook(byte[] bytes) {
		ClassNode node = new ClassNode();
		try { new ClassReader(bytes).accept(node, 0); } catch (RuntimeException unreadable) { Caught.warn(unreadable, "[Forbric/Damage] could not read a class to check its damage seams; taken as not asking every hook"); return false; }
		MethodNode hurt = own(node, "actuallyHurt", HURT_DESC);
		if (hurt == null || calls(hurt, FORGE_HOOKS, "onLivingHurt") + calls(hurt, RUNTIME, "hurt") == 0
				|| calls(hurt, FORGE_HOOKS, "onLivingDamage") + calls(hurt, RUNTIME, "damage") == 0) return false;
		if (!node.name.equals(PLAYER)) return true;
		MethodNode server = own(node, "hurtServer", SERVER_DESC);
		MethodNode clinit = own(node, "<clinit>", "()V");
		return server != null && clinit != null && calls(server, FORGE_HOOKS, "onPlayerAttack") + calls(server, RUNTIME, "playerAttack") > 0
				&& calls(clinit, RUNTIME, "notePlayerSeam") > 0;
	}

	/** The MinecraftForge hooks the merged body already calls itself, for the log. */
	private static List<String> nativelyAsked(ClassNode node) {
		List<String> asked = new ArrayList<>();
		MethodNode hurt = own(node, "actuallyHurt", HURT_DESC);
		if (hurt != null && calls(hurt, FORGE_HOOKS, "onLivingHurt") != 0) asked.add("Hurt");
		if (hurt != null && calls(hurt, FORGE_HOOKS, "onLivingDamage") != 0) asked.add("Damage");
		MethodNode server = node.name.equals(PLAYER) ? own(node, "hurtServer", SERVER_DESC) : null;
		if (server != null && calls(server, FORGE_HOOKS, "onPlayerAttack") != 0) asked.add("player Attack");
		return asked;
	}

	/** Places every seam whose proof holds; the names placed. */
	static List<String> repair(ClassNode node) {
		List<String> placed = new ArrayList<>();
		MethodNode hurt = own(node, "actuallyHurt", HURT_DESC);
		if (hurt != null) {
			String seams = hurtSeams(hurt);
			if (seams != null) placed.add(seams);
		}
		if (node.name.equals(PLAYER)) {
			MethodNode server = own(node, "hurtServer", SERVER_DESC);
			if (server != null && calls(server, FORGE_HOOKS, "onPlayerAttack") != 0) {
				// Asked natively, at MinecraftForge's own position; the forward off NeoForge's incoming-damage event must
				// still leave players to it, or a player is attacked twice. Only a NeoForge pipeline has that forward.
				if (hurt != null && calls(hurt, NEO_COMMON_HOOKS, "onLivingDamagePre") == 1 && noteSeam(node)) {
					placed.add(FORWARD_TOLD);
				}
			} else if (server != null && attackSeam(server) && noteSeam(node)) placed.add("player Attack in hurtServer");
		}
		return placed;
	}

	/**
	 * Hurt after the invulnerability check, Damage after the health damage is read back — each unless the body
	 * already asks it, natively or through this repair. The Hurt seam records the zero-damage hits Damage must then
	 * skip (MinecraftForge returns before Damage on them), so it goes in only where Damage is this repair's too: a
	 * seam of its own, or the merged body's own Damage call routed through the kernel, which reads that record. A
	 * merge that kept MinecraftForge's Damage and refused its Hurt (the Hurt answer was stored where NeoForge's body
	 * never reads it) leaves exactly that body.
	 *
	 * @return what was placed ("Hurt and Damage in actuallyHurt", or one of them), or null when nothing was
	 */
	static String hurtSeams(MethodNode method) {
		if ((method.access & (Opcodes.ACC_STATIC | Opcodes.ACC_ABSTRACT)) != 0) return null;
		boolean nativeHurt = calls(method, FORGE_HOOKS, "onLivingHurt") != 0, nativeDamage = calls(method, FORGE_HOOKS, "onLivingDamage") != 0;
		boolean seamedHurt = calls(method, RUNTIME, "hurt") != 0, seamedDamage = calls(method, RUNTIME, "damage") != 0;
		boolean wantHurt = !nativeHurt && !seamedHurt, wantDamage = !nativeDamage && !seamedDamage;
		if (!wantHurt && !wantDamage) return null;
		List<AbstractInsnNode> code = code(method);
		// aload0 aload1 aload2 invokevirtual isInvulnerableTo; ifne <end>
		if (code.size() < 5 || !load(code.get(0), Opcodes.ALOAD, 0) || !load(code.get(1), Opcodes.ALOAD, 1)
				|| !load(code.get(2), Opcodes.ALOAD, 2)
				|| !(code.get(3) instanceof MethodInsnNode invulnerable) || !invulnerable.name.equals("isInvulnerableTo")
				|| code.get(4).getOpcode() != Opcodes.IFNE) return null;
		if (calls(method, NEO_COMMON_HOOKS, "onLivingDamagePre") != 1
				|| calls(method, "com/google/common/base/Preconditions", "checkArgument") != 1) return null;
		// The one `getNewDamage(); fstore 3` after NeoForge's Pre: the health damage the method goes on to apply.
		AbstractInsnNode pre = null, store = null;
		int stores = 0;
		for (AbstractInsnNode insn : code) {
			if (insn instanceof MethodInsnNode call && call.name.equals("onLivingDamagePre")) pre = insn;
			if (pre != null && insn instanceof VarInsnNode v && v.getOpcode() == Opcodes.FSTORE && v.var == 3
					&& v.getPrevious() instanceof MethodInsnNode read && read.owner.equals(CONTAINER)
					&& read.name.equals("getNewDamage")) {
				store = insn;
				stores++;
			}
		}
		if (stores != 1) return null;
		MethodInsnNode own = null;
		if (wantHurt && nativeDamage) {
			own = nativeDamageAfter(method, store);
			if (own == null) return null;
		}
		if (wantHurt) hurtSeam(method, code.get(4));
		if (wantDamage) damageSeam(method, store);
		if (own != null) {
			routeDamage(method, own);
			return "Hurt and Damage in actuallyHurt (Damage the merged body's own call, asked through the kernel)";
		}
		return wantHurt && wantDamage ? "Hurt and Damage in actuallyHurt" : wantHurt ? "Hurt in actuallyHurt" : "Damage in actuallyHurt";
	}

	/**
	 * The body's own {@code damage = ForgeHooks.onLivingDamage(this, source, damage)}, when it is the only Damage call,
	 * reads and writes the health-damage local and comes after the one place that local is read back from the container:
	 * MinecraftForge's own Damage at MinecraftForge's position. Anything else is null.
	 */
	private static MethodInsnNode nativeDamageAfter(MethodNode method, AbstractInsnNode store) {
		if (calls(method, FORGE_HOOKS, "onLivingDamage") != 1) return null;
		boolean after = false;
		for (AbstractInsnNode insn : method.instructions) {
			if (insn == store) after = true;
			if (!(insn instanceof MethodInsnNode call) || !call.owner.equals(FORGE_HOOKS) || !call.name.equals("onLivingDamage")) continue;
			if (!after || call.getOpcode() != Opcodes.INVOKESTATIC || !call.desc.equals("(L" + LIVING + ";" + SOURCE + "F)F")) return null;
			AbstractInsnNode amount = real(call.getPrevious(), false), source = amount == null ? null : real(amount.getPrevious(), false);
			AbstractInsnNode self = source == null ? null : real(source.getPrevious(), false), result = real(call.getNext(), true);
			return load(self, Opcodes.ALOAD, 0) && load(source, Opcodes.ALOAD, 2) && load(amount, Opcodes.FLOAD, 3)
					&& load(result, Opcodes.FSTORE, 3) ? call : null;
		}
		return null;
	}

	/** The nearest real instruction from {@code insn} on, forwards or backwards. */
	private static AbstractInsnNode real(AbstractInsnNode insn, boolean forwards) {
		while (insn != null && insn.getOpcode() < 0) insn = forwards ? insn.getNext() : insn.getPrevious();
		return insn;
	}

	/**
	 * The body's own Damage call asks through the kernel, with the hit's container: a zero-damage hit the Hurt seam
	 * recorded is skipped as MinecraftForge skips it, and the answer reaches the container NeoForge's Post reads.
	 */
	private static void routeDamage(MethodNode method, MethodInsnNode own) {
		InsnList read = new InsnList();
		container(read);
		method.instructions.insertBefore(own, read);
		method.instructions.set(own, new MethodInsnNode(Opcodes.INVOKESTATIC, RUNTIME, "damage",
				"(L" + LIVING + ";" + SOURCE + "FL" + CONTAINER + ";)F", false));
	}

	/** Right after the invulnerability check's {@code ifne}: ask Hurt, and end the hit on its answer. */
	private static void hurtSeam(MethodNode method, AbstractInsnNode check) {
		LabelNode end = new LabelNode(), go = new LabelNode();
		InsnList hurt = new InsnList();
		hurt.add(new VarInsnNode(Opcodes.ALOAD, 0));
		container(hurt);
		hurt.add(new VarInsnNode(Opcodes.ALOAD, 2));
		hurt.add(new MethodInsnNode(Opcodes.INVOKESTATIC, RUNTIME, "hurt",
				"(L" + LIVING + ";L" + CONTAINER + ";" + SOURCE + ")Z", false));
		hurt.add(new JumpInsnNode(Opcodes.IFNE, end));
		hurt.add(new VarInsnNode(Opcodes.ALOAD, 0));
		hurt.add(new FieldInsnNode(Opcodes.GETFIELD, LIVING, "dead", "Z"));
		hurt.add(new JumpInsnNode(Opcodes.IFEQ, go));
		hurt.add(end);
		hurt.add(new FrameNode(Opcodes.F_SAME, 0, null, 0, null));
		hurt.add(new InsnNode(Opcodes.RETURN));
		hurt.add(go);
		hurt.add(new FrameNode(Opcodes.F_SAME, 0, null, 0, null));
		method.instructions.insert(check, hurt);
	}

	/** Right after the health damage is read back: Damage's answer replaces it. */
	private static void damageSeam(MethodNode method, AbstractInsnNode store) {
		InsnList damage = new InsnList();
		damage.add(new VarInsnNode(Opcodes.ALOAD, 0));
		container(damage);
		damage.add(new VarInsnNode(Opcodes.ALOAD, 2));
		damage.add(new VarInsnNode(Opcodes.FLOAD, 3));
		damage.add(new MethodInsnNode(Opcodes.INVOKESTATIC, RUNTIME, "damage",
				"(L" + LIVING + ";L" + CONTAINER + ";" + SOURCE + "F)F", false));
		damage.add(new VarInsnNode(Opcodes.FSTORE, 3));
		method.instructions.insert(store, damage);
	}

	/** {@code if (!KernelLivingDamage.playerAttack(this, source, amount)) return false;} at the head. */
	static boolean attackSeam(MethodNode method) {
		if ((method.access & (Opcodes.ACC_STATIC | Opcodes.ACC_ABSTRACT)) != 0) return false;
		if (calls(method, FORGE_HOOKS, "onPlayerAttack") != 0 || calls(method, RUNTIME, "playerAttack") != 0) return false;
		// Nothing jumps back to the first instruction: the seam's F_SAME frames are then relative to the entry frame.
		for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null && insn.getOpcode() < 0; insn = insn.getNext()) {
			if (insn instanceof FrameNode) return false;
		}
		if (code(method).isEmpty()) return false;
		LabelNode go = new LabelNode();
		InsnList attack = new InsnList();
		attack.add(new VarInsnNode(Opcodes.ALOAD, 0));
		attack.add(new VarInsnNode(Opcodes.ALOAD, 2));
		attack.add(new VarInsnNode(Opcodes.FLOAD, 3));
		attack.add(new MethodInsnNode(Opcodes.INVOKESTATIC, RUNTIME, "playerAttack", "(L" + LIVING + ";" + SOURCE + "F)Z", false));
		attack.add(new JumpInsnNode(Opcodes.IFNE, go));
		attack.add(new InsnNode(Opcodes.ICONST_0));
		attack.add(new InsnNode(Opcodes.IRETURN));
		attack.add(go);
		attack.add(new FrameNode(Opcodes.F_SAME, 0, null, 0, null));
		method.instructions.insert(attack);
		return true;
	}

	/** {@code KernelLivingDamage.notePlayerSeam()} first thing in {@code Player.<clinit>}, once. */
	static boolean noteSeam(ClassNode node) {
		MethodNode clinit = own(node, "<clinit>", "()V");
		if (clinit == null || calls(clinit, RUNTIME, "notePlayerSeam") != 0) return false;
		clinit.instructions.insert(new MethodInsnNode(Opcodes.INVOKESTATIC, RUNTIME, "notePlayerSeam", "()V", false));
		return true;
	}

	/** {@code (DamageContainer) this.damageContainers.peek()} — NeoForge's own way of reading the hit's container. */
	private static void container(InsnList list) {
		list.add(new VarInsnNode(Opcodes.ALOAD, 0));
		list.add(new FieldInsnNode(Opcodes.GETFIELD, LIVING, "damageContainers", "Ljava/util/Stack;"));
		list.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/util/Stack", "peek", "()Ljava/lang/Object;", false));
		list.add(new TypeInsnNode(Opcodes.CHECKCAST, CONTAINER));
	}

	private static boolean load(AbstractInsnNode insn, int opcode, int slot) {
		return insn instanceof VarInsnNode v && v.getOpcode() == opcode && v.var == slot;
	}

	private static List<AbstractInsnNode> code(MethodNode method) {
		List<AbstractInsnNode> out = new ArrayList<>();
		for (AbstractInsnNode insn : method.instructions) if (insn.getOpcode() >= 0) out.add(insn);
		return out;
	}

	private static int calls(MethodNode method, String owner, String name) {
		int n = 0;
		for (AbstractInsnNode insn : method.instructions) {
			if (insn instanceof MethodInsnNode call && call.owner.equals(owner) && call.name.equals(name)) n++;
		}
		return n;
	}

	private static MethodNode own(ClassNode node, String name, String desc) {
		for (MethodNode method : node.methods) if (method.name.equals(name) && method.desc.equals(desc)) return method;
		return null;
	}
}
