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
	private static final Path MERGED = STAGED.resolve("merged-base/patched-mc-merged-26.2.jar");
	private static final Path FORGE = STAGED.resolve("forge-patched/patched-mc-forge-26.2.jar");
	private static final String LIVING = ForgeDamageSeamsInjector.LIVING, PLAYER = ForgeDamageSeamsInjector.PLAYER;

	@AfterEach void reset() { System.clearProperty(ForgeDamageSeamsInjector.PROPERTY); }

	@Test void hurtBeforeArmourAndDamageAfterAbsorptionInBothBodies() throws Exception {
		for (String owner : List.of(LIVING, PLAYER)) {
			byte[] original = NativeCoremodParityTest.read(MERGED, owner);
			byte[] out = new ForgeDamageSeamsInjector().transform(owner.replace('/', '.'), original, null);
			assertNotSame(original, out, owner);
			MethodNode hurt = method(node(out), "actuallyHurt", ForgeDamageSeamsInjector.HURT_DESC);
			List<String> order = new ArrayList<>();
			for (AbstractInsnNode insn : hurt.instructions) {
				if (insn instanceof MethodInsnNode call && (call.owner.equals(ForgeDamageSeamsInjector.RUNTIME)
						|| call.name.equals("isInvulnerableTo") || call.name.equals("getDamageAfterArmorAbsorb")
						|| call.name.equals("onLivingDamagePre") || call.name.equals("setAbsorptionAmount")
						|| call.name.equals("setHealth"))) order.add(call.name);
			}
			assertEquals(List.of("isInvulnerableTo", "hurt", "getDamageAfterArmorAbsorb", "onLivingDamagePre",
					"setAbsorptionAmount", "damage", "setHealth"), order.subList(0, 7), owner + ": " + order);
			MethodInsnNode damage = calls(hurt, "damage").getFirst();
			assertTrue(damage.getNext() instanceof VarInsnNode store && store.getOpcode() == Opcodes.FSTORE && store.var == 3,
					"the Damage answer replaces the health damage the body goes on to apply");
			new Analyzer<>(new BasicVerifier()).analyze(owner, hurt);
			assertSame(out, new ForgeDamageSeamsInjector().transform(owner.replace('/', '.'), out, null), "a second pass changes nothing");
		}
	}

	@Test void thePlayerIsAskedAtTheHeadOfHurtServerAndTellsTheForward() throws Exception {
		ClassNode player = node(new ForgeDamageSeamsInjector().transform(PLAYER.replace('/', '.'),
				NativeCoremodParityTest.read(MERGED, PLAYER), null));
		MethodNode server = method(player, "hurtServer", ForgeDamageSeamsInjector.SERVER_DESC);
		AbstractInsnNode first = server.instructions.getFirst();
		while (first.getOpcode() < 0) first = first.getNext();
		for (int i = 0; i < 3; i++) first = first.getNext();
		assertTrue(first instanceof MethodInsnNode call && call.name.equals("playerAttack"), "before difficulty scaling and the zero-damage return");
		new Analyzer<>(new BasicVerifier()).analyze(PLAYER, server);
		assertEquals(1, calls(method(player, "<clinit>", "()V"), "notePlayerSeam").size());
		ClassNode living = node(new ForgeDamageSeamsInjector().transform(LIVING.replace('/', '.'),
				NativeCoremodParityTest.read(MERGED, LIVING), null));
		assertTrue(calls(method(living, "<clinit>", "()V"), "notePlayerSeam").isEmpty(), "only Player carries the attack seam");
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
