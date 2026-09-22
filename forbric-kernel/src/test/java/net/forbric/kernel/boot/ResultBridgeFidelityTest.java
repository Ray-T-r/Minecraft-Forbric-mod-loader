package net.forbric.kernel.boot;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

/**
 * A bridge whose hook returns something must do something with what it returns.
 *
 * <h2>The failure this guards</h2>
 *
 * <p>Most bridges forward an event and discard the hook's result, because those hooks return nothing. Two do
 * not: {@code onItemUseFinish} hands back the stack an item turns into and {@code onTrySpawnPortal} hands back
 * the portal to build, or nothing to refuse it. Forwarding either and dropping the answer is worse than not
 * bridging — the mod's listener runs, changes the value, and the game uses the value it had before, so the mod
 * is neither silent nor working.
 *
 * <p>gate-m12 proves these two INSTALL; nothing in any gate eats an item or lights a portal, so nothing proves
 * they deliver. This is the half that can be decided without the game: the call is there, and its answer is
 * carried back by a call that writes it. Reading the compiled bytecode rather than the source, because the
 * question is what the class does, not what it says.
 */
class ResultBridgeFidelityTest {
	private static final String BRIDGES = "net/forbric/kernel/runtime/KernelGameResultBridges";

	private static Path compiled() {
		return Path.of(System.getProperty("user.dir"), "build", "classes", "java", "runtime",
				BRIDGES + ".class");
	}

	/** Every method call in the class, as {@code owner.name}. */
	private static List<String> calls() throws Exception {
		ClassNode cn = new ClassNode();
		new ClassReader(Files.readAllBytes(compiled())).accept(cn, ClassReader.SKIP_FRAMES | ClassReader.SKIP_DEBUG);
		List<String> out = new ArrayList<>();
		for (MethodNode m : cn.methods) {
			if (m.instructions == null) continue;
			for (AbstractInsnNode insn = m.instructions.getFirst(); insn != null; insn = insn.getNext()) {
				if (insn instanceof MethodInsnNode mi) out.add(mi.owner + "." + mi.name);
			}
		}
		return out;
	}

	@Test
	void theItemUseFinishBridgeWritesTheHooksStackBack() throws Exception {
		assumeTrue(Files.isRegularFile(compiled()), "runtime classes not compiled (staged jars absent)");
		List<String> calls = calls();
		assertTrue(calls.contains("net/minecraftforge/event/ForgeEventFactory.onItemUseFinish"),
				"the bridge must actually call the MinecraftForge hook: " + calls);
		assertTrue(calls.contains(
				"net/neoforged/neoforge/event/entity/living/LivingEntityUseItemEvent$Finish.setResultStack"),
				"the hook returns the stack the item becomes; a bridge that does not write it back leaves the "
						+ "mod's listener running and its change ignored: " + calls);
	}

	@Test
	void thePortalBridgeCarriesARefusal() throws Exception {
		assumeTrue(Files.isRegularFile(compiled()), "runtime classes not compiled (staged jars absent)");
		List<String> calls = calls();
		assertTrue(calls.contains("net/minecraftforge/event/ForgeEventFactory.onTrySpawnPortal"), calls.toString());
		// An empty Optional from the hook is a refusal, and the only way to carry it is to cancel the event.
		assertTrue(calls.stream().anyMatch(c -> c.endsWith(".setCanceled")),
				"a refusal from onTrySpawnPortal has to reach the event, or a mod that blocks portals does not "
						+ "block them: " + calls);
	}
}
