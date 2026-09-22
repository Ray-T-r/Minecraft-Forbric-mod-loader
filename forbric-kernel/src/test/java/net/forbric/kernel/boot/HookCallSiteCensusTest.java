package net.forbric.kernel.boot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/**
 * Pins the census's comparison rules on synthetic jars.
 *
 * <p>The staged counterpart asserts what the real merged base looks like today; this asserts what the tool
 * MEANS, which is the half that has to survive every rebuild. Each rule here is one the hand-run {@code javap}
 * censuses had to apply by eye, which is the same as saying nothing checked that they were applied.
 */
class HookCallSiteCensusTest {
	private static final String HOOKS = "forge/Hooks";
	@TempDir Path temporary;

	@Test void aHookWithNoCallSiteIsDeadAndOneWithACallSiteIsNot() throws Exception {
		Path carrier = carrier(hooks(hook("onCalled", "()V"), hook("onNeverCalled", "()V")));
		Path base = base(callerCalling("game/Level", List.of(new Call("onCalled", "()V"))));
		var census = HookCallSiteCensus.of(carrier, HOOKS, List.of(base));
		assertEquals(List.of("onCalled()V", "onNeverCalled()V"), census.declared());
		assertEquals(java.util.Set.of("onCalled()V"), census.live());
		assertEquals(java.util.Set.of("onNeverCalled()V"), census.dead());
	}

	@Test void twoHooksWithTheSameNameAreJudgedSeparatelyByDescriptor() throws Exception {
		// The shape that makes a name-only census lie: an overload pair where the merge kept one call site.
		Path carrier = carrier(hooks(hook("onChat", "()V"), hook("onChat", "(I)V")));
		Path base = base(callerCalling("game/Level", List.of(new Call("onChat", "(I)V"))));
		var census = HookCallSiteCensus.of(carrier, HOOKS, List.of(base));
		assertEquals(java.util.Set.of("onChat(I)V"), census.live());
		assertEquals(java.util.Set.of("onChat()V"), census.dead());
	}

	@Test void aHookClassCallingItsOwnHookIsNotTheGameReachingIt() throws Exception {
		// forge/Hooks.onOuter calls forge/Hooks.onInner. If self-calls counted, a whole dead family would
		// read live as long as one unreachable entry point delegated into it.
		ClassWriter cw = new ClassWriter(0);
		cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, HOOKS, null, "java/lang/Object", null);
		emit(cw, "onInner", "()V", List.of());
		emit(cw, "onOuter", "()V", List.of(new Call("onInner", "()V")));
		cw.visitEnd();
		Path carrier = carrier(Map.of(HOOKS + ".class", cw.toByteArray()));
		// The base contains a COPY of the hook class (as a carrier jar handed in as a base would) and nothing else.
		Path base = carrier(Map.of(HOOKS + ".class", cw.toByteArray()));
		var census = HookCallSiteCensus.of(carrier, HOOKS, List.of(base));
		assertEquals(java.util.Set.of("onInner()V", "onOuter()V"), census.dead());
		assertTrue(census.live().isEmpty(), census.live().toString());
	}

	@Test void privateAndInstanceMembersAreNotHooks() throws Exception {
		ClassWriter cw = new ClassWriter(0);
		cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, HOOKS, null, "java/lang/Object", null);
		emit(cw, "onPublicStatic", "()V", List.of());
		MethodVisitor priv = cw.visitMethod(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC, "helper", "()V", null, null);
		priv.visitCode(); priv.visitInsn(Opcodes.RETURN); priv.visitMaxs(0, 0); priv.visitEnd();
		MethodVisitor inst = cw.visitMethod(Opcodes.ACC_PUBLIC, "instanceMethod", "()V", null, null);
		inst.visitCode(); inst.visitInsn(Opcodes.RETURN); inst.visitMaxs(0, 1); inst.visitEnd();
		MethodVisitor syn = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC | Opcodes.ACC_SYNTHETIC,
				"lambda$0", "()V", null, null);
		syn.visitCode(); syn.visitInsn(Opcodes.RETURN); syn.visitMaxs(0, 0); syn.visitEnd();
		cw.visitEnd();
		Path carrier = carrier(Map.of(HOOKS + ".class", cw.toByteArray()));
		var census = HookCallSiteCensus.of(carrier, HOOKS, List.of(base(callerCalling("game/Level", List.of()))));
		assertEquals(List.of("onPublicStatic()V"), census.declared());
	}

	@Test void anEventIsOnlyDeadWhenEveryHookThatPostsItIsDead() throws Exception {
		ClassWriter cw = new ClassWriter(0);
		cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, HOOKS, null, "java/lang/Object", null);
		posts(cw, "onA", "forge/SharedEvent");
		posts(cw, "onB", "forge/SharedEvent");
		posts(cw, "onC", "forge/LonelyEvent");
		cw.visitEnd();
		Path carrier = carrier(Map.of(HOOKS + ".class", cw.toByteArray()));
		// Only onA survives. SharedEvent still gets posted; LonelyEvent never does.
		Path base = base(callerCalling("game/Level", List.of(new Call("onA", "()V"))));
		var census = HookCallSiteCensus.of(carrier, HOOKS, List.of(base));
		assertEquals(java.util.Set.of("onA()V", "onB()V"), census.postersOf().get("forge/SharedEvent"));
		assertEquals(java.util.Set.of("forge/LonelyEvent"), census.deadEvents());
		assertTrue(census.liveEvents().contains("forge/SharedEvent"), census.liveEvents().toString());
		// The whole point of doing this per event rather than per hook: onB is dead and its event is not.
		assertTrue(census.dead().contains("onB()V"));
		assertFalse(census.deadEvents().contains("forge/SharedEvent"));
	}

	@Test void onlyTheHooksOwnEcosystemNamespaceCountsAsAnEventItPosts() throws Exception {
		// Without this the census calls every ArrayList a hook allocates an "event nothing posts": on the real
		// base that was 171 of them, burying the fourteen that were real.
		ClassWriter cw = new ClassWriter(0);
		cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, HOOKS, null, "java/lang/Object", null);
		posts(cw, "onThing", "forge/RealEvent");
		posts(cw, "onOther", "java/util/ArrayList");
		cw.visitEnd();
		Path carrier = carrier(Map.of(HOOKS + ".class", cw.toByteArray()));
		var census = HookCallSiteCensus.of(carrier, HOOKS, List.of(base(callerCalling("game/Level", List.of()))));
		assertEquals(java.util.Set.of("forge/RealEvent"), census.postersOf().keySet());
		assertEquals(java.util.Set.of("forge/RealEvent"), census.deadEvents());
	}

	@Test void theEcosystemRootIsTheFirstTwoPackageSegments() {
		assertEquals("net/minecraftforge/",
				HookCallSiteCensus.namespaceOf("net/minecraftforge/event/ForgeEventFactory"));
		assertEquals("net/neoforged/",
				HookCallSiteCensus.namespaceOf("net/neoforged/neoforge/event/EventHooks"));
		assertEquals("forge/", HookCallSiteCensus.namespaceOf("forge/Hooks"));
	}

	@Test void amethodCallingBothEcosystemsHookClassesIsTheDoublePostShape() throws Exception {
		// The only way one path delivers a bridged event twice: a bridge fires on the OTHER ecosystem's event,
		// so unless something fires both, the two never meet. "The base also posts it directly" is not this,
		// and reading it as this produced a finding on the real base that did not survive being checked.
		Map<String, byte[]> entries = new LinkedHashMap<>();
		entries.putAll(callsHooks("game/Single", List.of("net/minecraftforge/event/ForgeEventFactory")));
		entries.putAll(callsHooks("game/Double", List.of("net/minecraftforge/event/ForgeEventFactory",
				"net/neoforged/neoforge/event/EventHooks")));
		// An ordinary utility in the same namespace is not a hook entry point; counting it would flag methods
		// that post nothing.
		entries.putAll(callsHooks("game/Utility", List.of("net/minecraftforge/event/ForgeEventFactory",
				"net/neoforged/neoforge/common/util/Whatever")));
		Path jar = base(entries);
		assertEquals(List.of("game/Double#hook()V"), HookCallSiteCensus.methodsCallingBothFamilies(List.of(jar)));
	}

	@Test void theHookClassRuleNamesEntryPointsNotNamespaces() {
		assertTrue(HookCallSiteCensus.isHookClass("net/minecraftforge/event/ForgeEventFactory", "net/minecraftforge/"));
		assertTrue(HookCallSiteCensus.isHookClass("net/neoforged/neoforge/event/EventHooks", "net/neoforged/"));
		assertFalse(HookCallSiteCensus.isHookClass("net/minecraftforge/common/util/BlockSnapshot", "net/minecraftforge/"));
		assertFalse(HookCallSiteCensus.isHookClass("net/neoforged/neoforge/event/EventHooks", "net/minecraftforge/"));
	}

	private static Map<String, byte[]> callsHooks(String owner, List<String> hookOwners) {
		ClassWriter cw = new ClassWriter(0);
		cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, owner, null, "java/lang/Object", null);
		MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "hook", "()V", null, null);
		mv.visitCode();
		for (String h : hookOwners) mv.visitMethodInsn(Opcodes.INVOKESTATIC, h, "on", "()V", false);
		mv.visitInsn(Opcodes.RETURN);
		mv.visitMaxs(4, 4);
		mv.visitEnd();
		cw.visitEnd();
		Map<String, byte[]> out = new LinkedHashMap<>();
		out.put(owner + ".class", cw.toByteArray());
		return out;
	}

	@Test void theSummaryLeadsWithTheDenominator() throws Exception {
		Path carrier = carrier(hooks(hook("onCalled", "()V"), hook("onNeverCalled", "()V")));
		Path base = base(callerCalling("game/Level", List.of(new Call("onCalled", "()V"))));
		String summary = HookCallSiteCensus.of(carrier, HOOKS, List.of(base)).summary();
		// A run that scanned nothing must not read like a run that found nothing, so the declared count is first.
		assertTrue(summary.contains("2 declared, 1 called by the game"), summary);
		assertTrue(summary.contains("1 called by nothing"), summary);
		assertTrue(summary.startsWith("[Forbric/Hooks] " + HOOKS + ":"), summary);
	}

	// ---- fixture helpers ----

	private record Call(String name, String desc) {
	}

	/** A hook class declaring exactly these public static methods, each with an empty body. */
	private static Map<String, byte[]> hooks(Call... declared) {
		ClassWriter cw = new ClassWriter(0);
		cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, HOOKS, null, "java/lang/Object", null);
		for (Call c : declared) emit(cw, c.name(), c.desc(), List.of());
		cw.visitEnd();
		Map<String, byte[]> out = new LinkedHashMap<>();
		out.put(HOOKS + ".class", cw.toByteArray());
		return out;
	}

	private static Call hook(String name, String desc) {
		return new Call(name, desc);
	}

	private Path carrier(Map<String, byte[]> entries) throws Exception {
		return write(temporary.resolve("carrier-" + entries.hashCode() + ".jar"), entries);
	}

	private Path base(Map<String, byte[]> entries) throws Exception {
		return write(temporary.resolve("base-" + entries.hashCode() + ".jar"), entries);
	}

	private static Path write(Path jar, Map<String, byte[]> entries) throws Exception {
		try (OutputStream os = Files.newOutputStream(jar); ZipOutputStream zos = new ZipOutputStream(os)) {
			for (Map.Entry<String, byte[]> e : entries.entrySet()) {
				zos.putNextEntry(new ZipEntry(e.getKey()));
				zos.write(e.getValue());
				zos.closeEntry();
			}
		}
		return jar;
	}

	private static Map<String, byte[]> callerCalling(String owner, List<Call> calls) {
		ClassWriter cw = new ClassWriter(0);
		cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, owner, null, "java/lang/Object", null);
		MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "tick", "()V", null, null);
		mv.visitCode();
		for (Call c : calls) mv.visitMethodInsn(Opcodes.INVOKESTATIC, HOOKS, c.name(), c.desc(), false);
		mv.visitInsn(Opcodes.RETURN);
		mv.visitMaxs(4, 4);
		mv.visitEnd();
		cw.visitEnd();
		Map<String, byte[]> out = new LinkedHashMap<>();
		out.put(owner + ".class", cw.toByteArray());
		return out;
	}

	private static void emit(ClassWriter cw, String name, String desc, List<Call> selfCalls) {
		MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, name, desc, null, null);
		mv.visitCode();
		for (Call c : selfCalls) mv.visitMethodInsn(Opcodes.INVOKESTATIC, HOOKS, c.name(), c.desc(), false);
		mv.visitInsn(Opcodes.RETURN);
		mv.visitMaxs(4, 4);
		mv.visitEnd();
	}

	private static void posts(ClassWriter cw, String name, String eventType) {
		MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, name, "()V", null, null);
		mv.visitCode();
		mv.visitTypeInsn(Opcodes.NEW, eventType);
		mv.visitInsn(Opcodes.POP);
		mv.visitInsn(Opcodes.RETURN);
		mv.visitMaxs(4, 4);
		mv.visitEnd();
	}
}
