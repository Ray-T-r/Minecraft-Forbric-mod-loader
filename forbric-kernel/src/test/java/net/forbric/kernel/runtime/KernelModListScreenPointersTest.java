package net.forbric.kernel.runtime;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodNode;

/**
 * The files the Mods screen sends a player to have to be there when it does.
 *
 * <p>Read off the compiled screen rather than drawn: its text is built inside a frame only a real client runs. The
 * pointers are string constants (plain, or a concatenation recipe), and which file each names is the contract.
 */
class KernelModListScreenPointersTest {
	private static List<String> strings() throws Exception {
		Path screen = Path.of("build/classes/java/runtime/net/forbric/kernel/runtime/KernelModListScreen.class");
		assertTrue(Files.isRegularFile(screen), "the GAME-side classes are compiled before the tests: " + screen);
		ClassNode node = new ClassNode();
		new ClassReader(Files.readAllBytes(screen)).accept(node, 0);
		List<String> out = new ArrayList<>();
		for (MethodNode method : node.methods) {
			for (AbstractInsnNode insn : method.instructions) {
				if (insn instanceof LdcInsnNode ldc && ldc.cst instanceof String s) out.add(s);
				if (insn instanceof InvokeDynamicInsnNode indy) {
					for (Object arg : indy.bsmArgs) if (arg instanceof String s) out.add(s);
				}
			}
		}
		return out;
	}

	@Test
	void theRestOfAModsSuspicionsArePointedAtTheReportThatAlwaysListsThem() throws Exception {
		// load-report.txt is written only when something failed, and a boot whose only findings are suspicions --
		// every fabric-api boot has two dozen -- writes none; the machine report always has every one of them.
		List<String> more = strings().stream().filter(s -> s.contains(" more in ")).toList();
		assertFalse(more.isEmpty(), "the pane still says how many notes it did not show");
		for (String pointer : more) {
			assertTrue(pointer.contains(".forbric-kernel/compatibility-report.json"), pointer);
			assertFalse(pointer.contains("load-report.txt"), pointer);
		}
	}

	@Test
	void confirmedLossesStillPointAtTheLoadReportTheyKeep() throws Exception {
		// The negative control: a row that did not finish, and a confirmed loss no row carries, are exactly what
		// keeps load-report.txt, so those pointers stay on it.
		List<String> all = strings();
		assertTrue(all.stream().anyMatch(s -> s.equals("see .forbric-kernel/load-report.txt")), all.toString());
		assertTrue(all.stream().anyMatch(s -> s.contains("not tied to one mod (see .forbric-kernel/load-report.txt)")),
				all.toString());
	}
}
