package net.forbric.kernel.transform;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.BasicVerifier;

/** MinecraftForge's Hurt, Damage and player-Attack seams, placed in the real merged damage pipeline. */
@ResourceLock("system-properties")
class ForgeDamageSeamsInjectorTest {
	private static final Path STAGED = Path.of(System.getProperty("forbric.stagedRoot", "../forbric-loader/run"));
	private static final Path MERGED = STAGED.resolve(net.forbric.kernel.TestFixtures.BASE_JAR);
	private static final Path FORGE = STAGED.resolve(net.forbric.kernel.TestFixtures.FORGE_PATCHED_JAR);
	private static final String LIVING = ForgeDamageSeamsInjector.LIVING, PLAYER = ForgeDamageSeamsInjector.PLAYER;

	@AfterEach void reset() { System.clearProperty(ForgeDamageSeamsInjector.PROPERTY); }

	/**
	 * In both bodies MinecraftForge's Hurt is asked once, after the invulnerability check and before armour, and its Damage
	 * once, after absorption and before the health is written — each by the merged body itself, where a merge that keeps
	 * both sides' edits spliced MinecraftForge's call in, or by this repair's seam where it did not. A body missing a hook
	 * is edited; one that already asks every hook is handed back as it came.
	 */
	@Test void hurtBeforeArmourAndDamageAfterAbsorptionInBothBodies() throws Exception {
		for (String owner : List.of(LIVING, PLAYER)) {
			byte[] original = NativeCoremodParityTest.read(MERGED, owner);
			byte[] out = new ForgeDamageSeamsInjector().transform(owner.replace('/', '.'), original, null);
			MethodNode before = method(node(original), "actuallyHurt", ForgeDamageSeamsInjector.HURT_DESC);
			boolean missing = hooks(before, "hurt").isEmpty() || hooks(before, "damage").isEmpty();
			if (missing) assertNotSame(original, out, owner + " lacked a hook, so the repair edits it");
			else assertSame(original, out, owner + " already asks both hooks itself");
			MethodNode hurt = method(node(out), "actuallyHurt", ForgeDamageSeamsInjector.HURT_DESC);
			List<String> order = new ArrayList<>();
			for (AbstractInsnNode insn : hurt.instructions) {
				if (insn instanceof MethodInsnNode call && (hook(call) != null
						|| call.name.equals("isInvulnerableTo") || call.name.equals("getDamageAfterArmorAbsorb")
						|| call.name.equals("onLivingDamagePre") || call.name.equals("setAbsorptionAmount")
						|| call.name.equals("setHealth"))) order.add(hook(call) != null ? hook(call) : call.name);
			}
			assertEquals(List.of("isInvulnerableTo", "hurt", "getDamageAfterArmorAbsorb", "onLivingDamagePre",
					"setAbsorptionAmount", "damage", "setHealth"), order.subList(0, 7), owner + ": " + order);
			assertEquals(1, hooks(hurt, "hurt").size(), owner + ": Hurt is asked once");
			assertEquals(1, hooks(hurt, "damage").size(), owner + ": Damage is asked once");
			MethodInsnNode damage = hooks(hurt, "damage").getFirst();
			assertTrue(damage.getNext() instanceof VarInsnNode store && store.getOpcode() == Opcodes.FSTORE && store.var == 3,
					"the Damage answer replaces the health damage the body goes on to apply");
			// The Hurt seam lets a zero-damage hit go on through NeoForge's pipeline and records it, because MinecraftForge
			// returns before Damage on it; only a Damage the kernel asks reads that record, so a seamed Hurt needs it.
			if (hooks(hurt, "hurt").getFirst().owner.equals(ForgeDamageSeamsInjector.RUNTIME)) {
				assertEquals(ForgeDamageSeamsInjector.RUNTIME, damage.owner,
						owner + ": with Hurt the seam's, Damage is asked through the kernel too, the merged body's own call included");
			}
			new Analyzer<>(new BasicVerifier()).analyze(owner, hurt);
			assertSame(out, new ForgeDamageSeamsInjector().transform(owner.replace('/', '.'), out, null), "a second pass changes nothing");
			assertTrue(ForgeDamageSeamsInjector.asksEveryHook(out), owner + ": the claim's end state holds");
		}
	}

	/**
	 * MinecraftForge's Hurt answer is the damage armour then reduces. The seam writes it into the hit's DamageContainer,
	 * which NeoForge's armour reads; MinecraftForge's own call stores it in the damage local, which only works where the
	 * armour call reads that local. Where a merge spliced MinecraftForge's call into NeoForge's container-reading body,
	 * the answer is stored and never read: a LivingHurtEvent listener's new amount is lost (only "zero ends the hit"
	 * survives). The merge refuses that run now (its result reaches nothing in the base), and the seam asks Hurt
	 * instead; either way the answer must reach the armour call.
	 */
	@Test void minecraftForgesHurtAnswerIsTheDamageArmourReduces() throws Exception {
		for (String owner : List.of(LIVING, PLAYER)) {
			MethodNode hurt = method(node(new ForgeDamageSeamsInjector().transform(owner.replace('/', '.'),
					NativeCoremodParityTest.read(MERGED, owner), null)), "actuallyHurt", ForgeDamageSeamsInjector.HURT_DESC);
			MethodInsnNode asked = hooks(hurt, "hurt").getFirst();
			MethodInsnNode armour = null;
			for (AbstractInsnNode insn : hurt.instructions) {
				if (armour == null && insn instanceof MethodInsnNode call && call.name.equals("getDamageAfterArmorAbsorb")) armour = call;
			}
			assertNotNull(armour, owner);
			AbstractInsnNode argument = armour.getPrevious();
			while (argument.getOpcode() < 0) argument = argument.getPrevious();
			if (asked.owner.equals(ForgeDamageSeamsInjector.RUNTIME)) {
				assertTrue(argument instanceof MethodInsnNode read && read.owner.equals(ForgeDamageSeamsInjector.CONTAINER)
						&& read.name.equals("getNewDamage"), owner + ": the seam's answer is written to the container armour reads");
			} else {
				AbstractInsnNode stored = asked.getNext();
				while (stored.getOpcode() < 0) stored = stored.getNext();
				assertTrue(stored instanceof VarInsnNode store && store.getOpcode() == Opcodes.FSTORE
								&& argument instanceof VarInsnNode load && load.getOpcode() == Opcodes.FLOAD && load.var == store.var,
						owner + ": MinecraftForge's native Hurt answer is stored in a local armour never reads (armour reads " + describe(argument) + ")");
			}
		}
	}

	/**
	 * The player's attack is asked at the head of hurtServer — by the merged body itself or by the seam — before
	 * difficulty scaling and the zero-damage return, and either way Player.<clinit> tells the attack forward, which
	 * otherwise asks every entity, players included, a second time.
	 */
	@Test void thePlayerIsAskedAtTheHeadOfHurtServerAndTellsTheForward() throws Exception {
		byte[] out = new ForgeDamageSeamsInjector().transform(PLAYER.replace('/', '.'), NativeCoremodParityTest.read(MERGED, PLAYER), null);
		ClassNode player = node(out);
		MethodNode server = method(player, "hurtServer", ForgeDamageSeamsInjector.SERVER_DESC);
		AbstractInsnNode first = server.instructions.getFirst();
		while (first.getOpcode() < 0) first = first.getNext();
		for (int i = 0; i < 3; i++) first = first.getNext();
		assertTrue(first instanceof MethodInsnNode call && ("playerAttack".equals(hook(call))),
				"before difficulty scaling and the zero-damage return");
		new Analyzer<>(new BasicVerifier()).analyze(PLAYER, server);
		assertEquals(1, calls(method(player, "<clinit>", "()V"), "notePlayerSeam").size());
		assertSame(out, new ForgeDamageSeamsInjector().transform(PLAYER.replace('/', '.'), out, null), "told once");
		ClassNode living = node(new ForgeDamageSeamsInjector().transform(LIVING.replace('/', '.'),
				NativeCoremodParityTest.read(MERGED, LIVING), null));
		assertTrue(calls(method(living, "<clinit>", "()V"), "notePlayerSeam").isEmpty(), "only Player carries the attack seam");
	}

	/** The family-neutral name of a MinecraftForge damage hook {@code call} asks — natively or through a seam — or null. */
	private static String hook(MethodInsnNode call) {
		if (call.owner.equals(ForgeDamageSeamsInjector.RUNTIME)) {
			return switch (call.name) { case "hurt", "damage", "playerAttack" -> call.name; default -> null; };
		}
		if (!call.owner.equals(ForgeDamageSeamsInjector.FORGE_HOOKS)) return null;
		return switch (call.name) { case "onLivingHurt" -> "hurt"; case "onLivingDamage" -> "damage"; case "onPlayerAttack" -> "playerAttack"; default -> null; };
	}

	private static List<MethodInsnNode> hooks(MethodNode method, String name) {
		List<MethodInsnNode> out = new ArrayList<>();
		for (AbstractInsnNode insn : method.instructions) if (insn instanceof MethodInsnNode call && name.equals(hook(call))) out.add(call);
		return out;
	}

	private static String describe(AbstractInsnNode insn) {
		return insn instanceof MethodInsnNode call ? call.owner + "." + call.name : insn instanceof VarInsnNode load ? "local " + load.var : "opcode " + insn.getOpcode();
	}

	@Test void minecraftForgesOwnPipelineAndTheSwitchAreLeftAlone() throws Exception {
		for (String owner : List.of(LIVING, PLAYER)) {
			byte[] forge = NativeCoremodParityTest.read(FORGE, owner);
			assertSame(forge, new ForgeDamageSeamsInjector().transform(owner.replace('/', '.'), forge, null),
					owner + " already calls MinecraftForge's hooks");
		}
		System.setProperty(ForgeDamageSeamsInjector.PROPERTY, "off");
		byte[] merged = NativeCoremodParityTest.read(MERGED, LIVING);
		assertSame(merged, new ForgeDamageSeamsInjector().transform(LIVING.replace('/', '.'), merged, null));
	}

	private static ClassNode node(byte[] bytes) {
		ClassNode node = new ClassNode();
		new ClassReader(bytes).accept(node, 0);
		return node;
	}

	private static MethodNode method(ClassNode node, String name, String desc) {
		return node.methods.stream().filter(m -> m.name.equals(name) && m.desc.equals(desc)).findFirst().orElseThrow();
	}

	private static List<MethodInsnNode> calls(MethodNode method, String name) {
		List<MethodInsnNode> out = new ArrayList<>();
		for (AbstractInsnNode insn : method.instructions) {
			if (insn instanceof MethodInsnNode call && call.owner.equals(ForgeDamageSeamsInjector.RUNTIME) && call.name.equals(name)) out.add(call);
		}
		return out;
	}
}
