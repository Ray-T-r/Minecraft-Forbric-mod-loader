package net.forbric.tools;

import java.nio.file.Files;
import java.nio.file.Path;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import static org.junit.jupiter.api.Assertions.*;

class LostHookAttributionTest {
	@TempDir Path temporary;

	@Test void sameNamedHooksFromDifferentOwnersDoNotCancelEachOther() {
		MethodNode original = new MethodNode(), merged = new MethodNode();
		original.instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC,
				"net/minecraftforge/common/ForgeHooks", "tick", "()V", false));
		merged.instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC,
				"net/neoforged/neoforge/event/EventHooks", "tick", "()V", false));
		Set<String> lost = LostHookAttribution.hookCalls(original);
		lost.removeAll(LostHookAttribution.hookCalls(merged));
		assertEquals(Set.of("net/minecraftforge/common/ForgeHooks#tick()V"), lost);
	}

	@Test void bothLosingFamiliesAreInventoried() throws Exception {
		Path report = temporary.resolve("conflicts.txt");
		Files.writeString(report, "game/A#tick()V (forge hook lost)\ngame/B#tick()V (neo hook lost)\n"
				+ "game/C#tick()V DECLINED another reason\n");
		var rows = LostHookAttribution.conflicts(report);
		assertEquals(2, rows.size());
		assertEquals("forge", rows.get(0).lostFamily());
		assertEquals("neo", rows.get(1).lostFamily());
		assertEquals("game/B", rows.get(1).owner());
	}

	@Test void realReportTraversalAttributesBothSidesAndNamesUnobservedConflicts() throws Exception {
		String forge = "net/minecraftforge/common/ForgeHooks", neo = "net/neoforged/neoforge/event/EventHooks";
		Path f = jar("forge.jar", forge, forge), n = jar("neo.jar", neo, neo);
		Path merged = jar("merged.jar", neo, forge), carrier = temporary.resolve("empty.jar");
		try (JarOutputStream ignored = new JarOutputStream(Files.newOutputStream(carrier))) { }
		Path report = temporary.resolve("report.txt"), mods = Files.createDirectory(temporary.resolve("mods"));
		Files.writeString(report, "game/A#tick()V (forge hook lost)\ngame/B#tick()V (neo hook lost)\n"
				+ "game/Missing#tick()V (forge hook lost)\n");
		ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		PrintStream previous = System.out;
		try (PrintStream out = new PrintStream(bytes)) {
			System.setOut(out);
			LostHookAttribution.main(new String[] {f.toString(), n.toString(), merged.toString(), carrier.toString(),
					carrier.toString(), report.toString(), mods.toString()});
		} finally { System.setOut(previous); }
		String output = bytes.toString(java.nio.charset.StandardCharsets.UTF_8);
		assertTrue(output.contains("game/A#tick()V lost-family=forge RAW-LOST [" + forge + "#tick()V]"));
		assertTrue(output.contains("game/B#tick()V lost-family=neo RAW-LOST [" + neo + "#tick()V]"));
		assertTrue(output.contains("conflicts=3 judged=2 no-modelled-direct-hook=0 unobserved=1"));
		assertTrue(output.contains("runtime restoration=NOT_ASSESSED"));
	}

	private Path jar(String name, String first, String second) throws Exception {
		Path file = temporary.resolve(name);
		try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(file))) {
			String[] hooks = {first, second};
			for (int i = 0; i < 2; i++) {
				String owner = "game/" + (i == 0 ? "A" : "B");
				ClassWriter writer = new ClassWriter(0);
				writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, owner, null, "java/lang/Object", null);
				var method = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "tick", "()V", null, null);
				method.visitCode();
				method.visitMethodInsn(Opcodes.INVOKESTATIC, hooks[i], "tick", "()V", false);
				method.visitInsn(Opcodes.RETURN);
				method.visitMaxs(0, 0);
				method.visitEnd();
				writer.visitEnd();
				out.putNextEntry(new JarEntry(owner + ".class"));
				out.write(writer.toByteArray());
				out.closeEntry();
			}
		}
		return file;
	}
}
