package net.forbric.tools;

import java.nio.file.Files;
import java.nio.file.Path;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.tree.ClassNode;
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
		assertTrue(output.contains("[attribution] mods name 0 ecosystem class references (candidate filter; not event/subscriber count)"));
		assertFalse(output.contains("ecosystem event class(es)"));
		assertTrue(output.contains("game/A#tick()V lost-family=forge RAW-LOST [" + forge + "#tick()V]"));
		assertTrue(output.contains("game/B#tick()V lost-family=neo RAW-LOST [" + neo + "#tick()V]"));
		assertTrue(output.contains("conflicts=3 judged=2 no-modelled-direct-hook=0 unobserved=1"));
		assertTrue(output.contains("runtime restoration=NOT_ASSESSED"));
		assertTrue(output.contains("[platform-census] denominator side=forge classes=2 methods=2"));
		assertTrue(output.contains("[platform-census] denominator side=neo classes=2 methods=2"));
		assertTrue(output.contains("OUTSIDE_EVENT_MODEL symbols are not classified as event hooks"));
		assertTrue(output.contains("raw absence is not proof of a behavior defect"));
	}

	@Test void outsideFacadesAndBothNamespacesAreInventoriedWithoutClaimingEventCoverage() {
		String forge = "net/minecraftforge/registries/RegistryManager#tick()V";
		String neo = "net/neoforged/fml/ModLoader#tick()V";
		ClassNode source = classNode("game/A", methodNode("tick", "()V", forge, neo), methodNode("plain", "()V"));
		var census = LostHookAttribution.platformCensus(Map.of(source.name, source), Map.of(source.name, source), List.of());
		assertEquals(1, census.classes());
		assertEquals(2, census.methods());
		assertEquals(1, census.callers());
		assertEquals(2, census.calls().size());
		assertEquals(Set.of(forge, neo), census.calls().stream().map(LostHookAttribution.PlatformCall::symbol)
				.collect(java.util.stream.Collectors.toSet()));
		assertTrue(census.calls().stream().noneMatch(LostHookAttribution.PlatformCall::modelledFacade));
		assertTrue(census.calls().stream().allMatch(c -> c.state() == LostHookAttribution.RawCallState.RAW_RETAINED));
	}

	@Test void callersOutsideConflictListHaveTheSameRetentionAndLossCoverage() {
		String hook = "net/minecraftforge/common/ForgeHooks#tick()V";
		ClassNode source = classNode("game/A", methodNode("listed", "()V", hook), methodNode("unlisted", "()V", hook));
		ClassNode merged = classNode("game/A", methodNode("listed", "()V", hook), methodNode("unlisted", "()V"));
		var census = LostHookAttribution.platformCensus(Map.of(source.name, source), Map.of(merged.name, merged),
				List.of(new LostHookAttribution.Conflict("game/A", "listed()V", "forge")));
		assertEquals(2, census.callers());
		assertEquals(1, census.listedConflictCallers());
		assertEquals(2, census.calls().size());
		var listed = census.calls().get(0);
		var unlisted = census.calls().get(1);
		assertTrue(listed.listedConflict());
		assertEquals(LostHookAttribution.RawCallState.RAW_RETAINED, listed.state());
		assertFalse(unlisted.listedConflict());
		assertTrue(unlisted.modelledFacade());
		assertEquals("game/A#unlisted()V", unlisted.caller());
		assertEquals(LostHookAttribution.RawCallState.RAW_LOST, unlisted.state());
		assertEquals(1, unlisted.lostOccurrences());
	}

	@Test void ownerDescriptorAndRepeatedOccurrencesCannotMaskOtherRawLosses() {
		String hook = "net/minecraftforge/common/ForgeHooks#tick()V";
		String overload = "net/minecraftforge/common/ForgeHooks#tick(I)V";
		String otherOwner = "net/neoforged/neoforge/event/EventHooks#tick()V";
		ClassNode source = classNode("game/A", methodNode("tick", "()V", hook, hook, overload, otherOwner));
		ClassNode merged = classNode("game/A", methodNode("tick", "()V", hook));
		var census = LostHookAttribution.platformCensus(Map.of(source.name, source), Map.of(merged.name, merged), List.of());
		assertEquals(3, census.calls().size());
		var rows = census.calls().stream().collect(java.util.stream.Collectors.toMap(
				LostHookAttribution.PlatformCall::symbol, c -> c));
		assertEquals(LostHookAttribution.RawCallState.RAW_PARTIAL_LOSS, rows.get(hook).state());
		assertEquals(2, rows.get(hook).originalOccurrences());
		assertEquals(1, rows.get(hook).retainedOccurrences());
		assertEquals(1, rows.get(hook).lostOccurrences());
		assertEquals(LostHookAttribution.RawCallState.RAW_LOST, rows.get(overload).state());
		assertEquals(LostHookAttribution.RawCallState.RAW_LOST, rows.get(otherOwner).state());
	}

	@Test void missingMergedClassesAndOverloadsAreUnobservedOnBothSides() {
		for (String platform : List.of("net/minecraftforge/common/ForgeHooks", "net/neoforged/neoforge/event/EventHooks")) {
			String hook = platform + "#tick()V";
			ClassNode missingClass = classNode("game/Missing", methodNode("tick", "()V", hook));
			ClassNode missingMethod = classNode("game/Present", methodNode("tick", "(I)V", hook), methodNode("plain", "()V"));
			ClassNode merged = classNode("game/Present", methodNode("tick", "()V", hook));
			var census = LostHookAttribution.platformCensus(Map.of(missingClass.name, missingClass, missingMethod.name, missingMethod),
					Map.of(merged.name, merged), List.of());
			assertEquals(3, census.methods());
			assertEquals(3, census.missingMergedMethods());
			assertEquals(2, census.missingMergedCallers());
			assertEquals(2, census.calls().size());
			for (var row : census.calls()) {
				assertEquals(LostHookAttribution.RawCallState.MERGED_CALLER_MISSING, row.state());
				assertEquals(-1, row.mergedOccurrences());
				assertEquals(0, row.retainedOccurrences());
				assertEquals(0, row.lostOccurrences());
			}
		}
	}

	@Test void opcodeAndInterfaceOwnerChangesAreNotReportedAsPreservedInvocations() {
		String owner = "net/minecraftforge/common/ForgeHooks", symbol = owner + "#tick()V";
		for (var changed : List.of(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, owner, "tick", "()V", false),
				new MethodInsnNode(Opcodes.INVOKESTATIC, owner, "tick", "()V", true))) {
			ClassNode source = classNode("game/A", methodNode("tick", "()V", symbol));
			MethodNode changedMethod = methodNode("tick", "()V");
			changedMethod.instructions.add(changed);
			ClassNode merged = classNode("game/A", changedMethod);
			var row = LostHookAttribution.platformCensus(Map.of(source.name, source), Map.of(merged.name, merged),
					List.of()).calls().get(0);
			assertEquals(LostHookAttribution.RawCallState.RAW_INVOCATION_CHANGED, row.state());
			assertEquals(1, row.mergedOccurrences());
			assertEquals(0, row.retainedOccurrences());
			assertEquals(1, row.lostOccurrences());
			assertEquals(Map.of("INVOKESTATIC/itf=false", 1), row.originalForms());
			assertNotEquals(row.originalForms(), row.mergedForms());
		}
	}

	private static ClassNode classNode(String owner, MethodNode... methods) {
		ClassNode node = new ClassNode();
		node.name = owner;
		node.methods.addAll(List.of(methods));
		return node;
	}

	private static MethodNode methodNode(String name, String descriptor, String... calls) {
		MethodNode method = new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, name, descriptor, null, null);
		for (String call : calls) {
			int hash = call.indexOf('#'), arguments = call.indexOf('(', hash);
			method.instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC, call.substring(0, hash),
					call.substring(hash + 1, arguments), call.substring(arguments), false));
		}
		return method;
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
