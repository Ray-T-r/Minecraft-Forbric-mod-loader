package net.forbric.kernel.boot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/** The join half: which jars are waiting on a dead event, and what counts as waiting. */
class DeadHookWorklistTest {
	private static final String EVENT = "forge/event/ChatEvent";
	private static final String OTHER = "forge/event/TickEvent";
	@TempDir Path temporary;

	@Test void aJarThatNamesTheEventInItsConstantPoolIsWaiting() throws Exception {
		Path waiting = jar("waiting.jar", Map.of("mod/Listener.class", referencing(EVENT)));
		Path idle = jar("idle.jar", Map.of("mod/Other.class", referencing("java/lang/Object")));
		var list = DeadHookWorklist.of(Map.of(EVENT, Set.of("onChat()V")), Set.of(EVENT), List.of(waiting, idle));
		assertEquals(1, list.size(), list.toString());
		assertEquals(EVENT, list.get(0).event());
		assertEquals(List.of("waiting.jar"), list.get(0).jars());
		assertEquals(Set.of("onChat()V"), list.get(0).posters());
	}

	@Test void aNameThatOnlyAppearsAsTextIsNotEvidence() throws Exception {
		// The judgement fapi-usage.py settled on, and the reason it is right: a mod's fabric.mod.json, its
		// mixin config and its log strings all contain class names it never calls.
		ClassWriter cw = new ClassWriter(0);
		cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "mod/Decoy", null, "java/lang/Object", null);
		cw.newUTF8(EVENT);
		cw.visitEnd();
		Path decoy = jar("decoy.jar", Map.of(
				"mod/Decoy.class", cw.toByteArray(),
				"fabric.mod.json", ("{\"x\":\"" + EVENT + "\"}").getBytes(java.nio.charset.StandardCharsets.UTF_8)));
		var list = DeadHookWorklist.of(Map.of(), Set.of(EVENT), List.of(decoy));
		assertTrue(list.isEmpty(), list.toString());
	}

	@Test void aNestedModJarWaitsJustAsLoudlyAsItsParent() throws Exception {
		byte[] inner = bytes(Map.of("lib/Listener.class", referencing(EVENT)));
		Path parent = jar("parent.jar", Map.of("META-INF/jars/inner.jar", inner));
		var list = DeadHookWorklist.of(Map.of(), Set.of(EVENT), List.of(parent));
		assertEquals(List.of("parent.jar"), list.get(0).jars(), "a bundled library's dead event is the pack's problem too");
	}

	@Test void theListIsOrderedByHowManyModsNotice() throws Exception {
		Path a = jar("a.jar", Map.of("mod/A.class", referencing(EVENT, OTHER)));
		Path b = jar("b.jar", Map.of("mod/B.class", referencing(EVENT)));
		Path c = jar("c.jar", Map.of("mod/C.class", referencing(EVENT)));
		var list = DeadHookWorklist.of(Map.of(), Set.of(EVENT, OTHER), List.of(a, b, c));
		// Repairing in census order is repairing in an order the byte-merge chose; this is the other order.
		assertEquals(EVENT, list.get(0).event());
		assertEquals(3, list.get(0).jars().size());
		assertEquals(OTHER, list.get(1).event());
	}

	@Test void theSimpleChainIsWhatABridgeRecordsAboutItsEvent() {
		assertEquals("LevelEvent.Load",
				DeadHookWorklist.simpleChain("net/minecraftforge/event/level/LevelEvent$Load"));
		assertEquals("ServerChatEvent",
				DeadHookWorklist.simpleChain("net/minecraftforge/event/ServerChatEvent"));
	}

	// ---- fixtures ----

	private static byte[] referencing(String... types) {
		ClassWriter cw = new ClassWriter(0);
		cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "mod/Listener", null, "java/lang/Object", null);
		MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "on", "()V", null, null);
		mv.visitCode();
		for (String t : types) {
			mv.visitTypeInsn(Opcodes.NEW, t);
			mv.visitInsn(Opcodes.POP);
		}
		mv.visitInsn(Opcodes.RETURN);
		mv.visitMaxs(4, 4);
		mv.visitEnd();
		cw.visitEnd();
		return cw.toByteArray();
	}

	private Path jar(String name, Map<String, byte[]> entries) throws Exception {
		Path p = temporary.resolve(name);
		try (OutputStream os = Files.newOutputStream(p)) {
			os.write(bytes(entries));
		}
		return p;
	}

	private static byte[] bytes(Map<String, byte[]> entries) throws Exception {
		ByteArrayOutputStream bos = new ByteArrayOutputStream();
		try (ZipOutputStream zos = new ZipOutputStream(bos)) {
			for (Map.Entry<String, byte[]> e : new LinkedHashMap<>(entries).entrySet()) {
				zos.putNextEntry(new ZipEntry(e.getKey()));
				zos.write(e.getValue());
				zos.closeEntry();
			}
		}
		return bos.toByteArray();
	}
}
