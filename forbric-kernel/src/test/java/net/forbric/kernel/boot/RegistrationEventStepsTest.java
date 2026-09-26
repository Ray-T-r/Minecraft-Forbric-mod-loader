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

package net.forbric.kernel.boot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipFile;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import net.forbric.api.CompatibilityFinding;
import net.forbric.api.CompatibilityFindings;

/**
 * NeoForge's {@code RegistrationEvents.init}, one step at a time.
 *
 * <p>On the sweep pack's client its data-map step threw, and the whole call went with it: no data map type at all,
 * no default-component or POI events, and — sitting after the call in the same {@code try} — no transfer bridge.
 * These run synthetic carriers with NeoForge's class names through the kernel's own runner, so what is asserted is
 * what the kernel does with a failing step, not what a description of it says.
 */
class RegistrationEventStepsTest {
	private static final String EVENTS = "net/neoforged/neoforge/internal/RegistrationEvents";
	private static final String CAULDRON = "net/neoforged/neoforge/fluids/CauldronFluidContent";
	private static final String CAPABILITIES = RegistrationEventSteps.CAPABILITIES.owner();
	private static final String REGISTRY_MANAGER = RegistrationEventSteps.DATA_MAPS.owner();
	private static final String POI = "net/neoforged/neoforge/common/world/poi/PoiTypeExtender";
	private static final Path NEOFORGE_RUNTIME = Path.of(System.getenv().getOrDefault("FORBRIC_OLD",
			System.getProperty("user.dir") + "/../forbric-loader"), "run", "neoforge-runtime",
			"neoforge-runtime.jar").normalize();

	@BeforeEach
	@AfterEach
	void reset() {
		Recorder.HITS.clear();
		CompatibilityFindings.reset();
		System.clearProperty(RegistrationEventSteps.SWITCH);
	}

	// --- the plan -----------------------------------------------------------------------------------------------

	/** The real carrier's method, when it is staged: seven calls, and the two the kernel keys on among them. */
	@Test
	void theRealMethodIsAStraightRunOfSevenCalls() throws Exception {
		assumeTrue(Files.isRegularFile(NEOFORGE_RUNTIME), "neoforge-runtime.jar not staged");
		byte[] real;
		try (ZipFile zip = new ZipFile(NEOFORGE_RUNTIME.toFile())) {
			var entry = zip.getEntry(EVENTS + ".class");
			assumeTrue(entry != null, "RegistrationEvents absent from this carrier");
			try (InputStream in = zip.getInputStream(entry)) {
				real = in.readAllBytes();
			}
		}
		List<RegistrationEventSteps.Step> steps = RegistrationEventSteps.plan(real);

		assertNotNull(steps, "NeoForge's init is expected to be isolatable");
		assertEquals(7, steps.size(), steps.toString());
		assertTrue(steps.indexOf(RegistrationEventSteps.CAPABILITIES) >= 0, steps.toString());
		assertTrue(steps.indexOf(RegistrationEventSteps.DATA_MAPS) > steps.indexOf(RegistrationEventSteps.CAPABILITIES),
				"capabilities before data maps, as NeoForge orders them");
	}

	@Test
	void aBranchIsNotOursToTakeApart() {
		assertNull(RegistrationEventSteps.plan(events(true, CAULDRON, CAPABILITIES)));
	}

	@Test
	void aCallWithAnArgumentIsNotOursToTakeApart() {
		ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
		cw.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, EVENTS, null, "java/lang/Object", null);
		MethodVisitor mv = cw.visitMethod(Opcodes.ACC_STATIC, "init", "()V", null, null);
		mv.visitCode();
		mv.visitInsn(Opcodes.ICONST_1);
		mv.visitMethodInsn(Opcodes.INVOKESTATIC, CAULDRON, "init", "(Z)V", false);
		mv.visitInsn(Opcodes.RETURN);
		mv.visitMaxs(0, 0);
		mv.visitEnd();
		cw.visitEnd();
		assertNull(RegistrationEventSteps.plan(cw.toByteArray()));
	}

	// --- the run ------------------------------------------------------------------------------------------------

	/**
	 * The sweep pack's failure, reduced: the data-map step throws. The steps around it still run, the capability
	 * step's success is what the transfer bridge sees, and the data-map loss is a finding rather than a WARN.
	 */
	@Test
	void aFailingDataMapStepNoLongerTakesTheOthersWithIt() {
		RegistrationEventSteps.Outcome outcome = RegistrationEventSteps.fire(carrier(false, false));

		assertNotNull(outcome);
		assertTrue(outcome.isolated());
		assertEquals(List.of("cauldron", "capabilities", "poi"), Recorder.HITS,
				"everything but the failing step ran, in NeoForge's order");
		assertInstanceOf(IllegalStateException.class, outcome.failures().get(RegistrationEventSteps.DATA_MAPS));
		assertTrue(outcome.capabilitiesRegistered(), "the transfer bridge waits on capabilities, not on data maps");
		assertEquals(0, outcome.dataMapTypes());

		CompatibilityFinding dataMaps = finding("neoforge-data-maps");
		assertNotNull(dataMaps, CompatibilityFindings.all().toString());
		assertTrue(dataMaps.confirmedRequired());
		assertTrue(dataMaps.detail().startsWith("NeoForge data maps unavailable: "), dataMaps.detail());
		assertTrue(dataMaps.detail().contains("a listener died"), dataMaps.detail());
	}

	/** The switch is the old single call: the first throw ends it, and the steps after it never run. */
	@Test
	void theSwitchCallsItWhole() {
		System.setProperty(RegistrationEventSteps.SWITCH, "off");
		RegistrationEventSteps.Outcome outcome = RegistrationEventSteps.fire(carrier(false, false));

		assertFalse(outcome.isolated());
		assertEquals(List.of("cauldron", "capabilities"), Recorder.HITS);
		assertInstanceOf(IllegalStateException.class, outcome.wholeFailure());
		assertFalse(outcome.capabilitiesRegistered(), "called whole, only a clean call proves anything");
		assertNotNull(finding("neoforge-data-maps"), "zero types after the call is still a loss");
	}

	@Test
	void aFailedCapabilityStepHoldsBackOnlyTheTransferBridge() {
		RegistrationEventSteps.Outcome outcome = RegistrationEventSteps.fire(carrier(true, false));

		assertFalse(outcome.capabilitiesRegistered());
		assertEquals(List.of("cauldron", "poi"), Recorder.HITS);
		CompatibilityFinding capabilities = finding("neoforge-registration:CapabilityHooks.init");
		assertNotNull(capabilities, CompatibilityFindings.all().toString());
		assertTrue(capabilities.confirmedRequired());
	}

	/**
	 * A transformer that merged a method into the loaded class (a Mixin injector into init leaves its handler)
	 * means the file's steps are not what runs: replaying them would skip the injector, so it is called whole.
	 */
	@Test
	void aTransformedClassIsCalledWhole() {
		RegistrationEventSteps.Outcome outcome = RegistrationEventSteps.fire(carrier(false, true));

		assertFalse(outcome.isolated());
		assertEquals(List.of("cauldron", "capabilities"), Recorder.HITS);
	}

	@Test
	void aShapeItCannotReadIsCalledWhole() {
		Map<String, byte[]> classes = stepClasses(false);
		classes.put(EVENTS, events(true, CAULDRON, CAPABILITIES, REGISTRY_MANAGER + "#initDataMaps", POI));
		RegistrationEventSteps.Outcome outcome = RegistrationEventSteps.fire(new Carrier(classes, classes));

		assertFalse(outcome.isolated());
	}

	/**
	 * Every step returned and no data map type exists: the finding says so, and the log line must not be the clean
	 * one, which gates m7/m9 and compat/assert.sh take as "capabilities and data maps are registered". Each of those
	 * three patterns is run through grep against both lines, so the gates and the wording cannot drift apart.
	 */
	@Test
	void aCleanRunWithNoDataMapTypeIsNotTheCleanLine(@TempDir Path dir) throws Exception {
		String empty = logOf(() -> RegistrationEventSteps.fire(cleanCarrier(0)));
		assertTrue(empty.contains("all returned, but no data map type was registered, 0 data map type(s)"), empty);
		assertFalse(empty.contains("ran NeoForge's registration events"), empty);
		assertNotNull(finding("neoforge-data-maps"), "zero types after a clean run is still a loss");

		CompatibilityFindings.reset();
		String registered = logOf(() -> RegistrationEventSteps.fire(cleanCarrier(11)));
		assertTrue(registered.contains("ran NeoForge's registration events"), registered);
		assertNull(finding("neoforge-data-maps"));

		for (String gate : List.of("run/gate-m7-neo.sh", "run/gate-m9-client.sh", "run/compat/assert.sh")) {
			String pattern = registrationPattern(gate);
			assertTrue(grep(dir, pattern, registered), gate + " must accept a run that registered data maps");
			assertFalse(grep(dir, pattern, empty), gate + " must not accept a run that registered none");
			assertFalse(grep(dir, pattern, registered.replace(", 11 data map type(s)", "")),
					gate + " must not accept a run whose count could not be read");
		}
	}

	/** Out of the shared try: the kernel installs the bridge after the call, gated on the capability step. */
	@Test
	void theTransferBridgeIsGatedOnCapabilitiesOnly() throws Exception {
		Path compiled = Path.of(System.getProperty("user.dir"), "build", "classes", "java", "main",
				"net", "forbric", "kernel", "boot", "KernelLifecycle.class");
		assumeTrue(Files.isRegularFile(compiled), "KernelLifecycle not compiled yet");
		ClassNode node = new ClassNode();
		new ClassReader(Files.readAllBytes(compiled)).accept(node, 0);
		MethodNode fire = node.methods.stream().filter(m -> "fireRegistrationEvents".equals(m.name)).findFirst()
				.orElseThrow();

		List<String> calls = new ArrayList<>();
		for (AbstractInsnNode insn : fire.instructions.toArray()) {
			if (insn instanceof MethodInsnNode call) calls.add(call.name);
		}
		int gate = calls.indexOf("capabilitiesRegistered");
		int install = calls.indexOf("install");
		assertTrue(calls.indexOf("fire") >= 0 && gate > calls.indexOf("fire") && install > gate, calls.toString());
		assertTrue(fire.tryCatchBlocks.stream().allMatch(b -> {
			AbstractInsnNode[] insns = fire.instructions.toArray();
			int start = fire.instructions.indexOf(b.start);
			int end = fire.instructions.indexOf(b.end);
			for (int i = start; i < end; i++) {
				if (insns[i] instanceof MethodInsnNode call && "install".equals(call.name)) return false;
			}
			return true;
		}), "the bridge's install must not share a try with the registration events");
	}

	// --- helpers ------------------------------------------------------------------------------------------------

	/** What the synthetic steps did, in order. Public: the synthetic classes live in another loader. */
	public static final class Recorder {
		public static final List<String> HITS = new ArrayList<>();

		public static void hit(String step) {
			HITS.add(step);
		}

		private Recorder() {
		}
	}

	/** A carrier whose four steps all return, with {@code types} data map types registered afterwards. */
	private static Carrier cleanCarrier(int types) {
		Map<String, byte[]> classes = new HashMap<>();
		classes.put(CAULDRON, step(CAULDRON, "init", "cauldron", false, false));
		classes.put(CAPABILITIES, step(CAPABILITIES, "init", "capabilities", false, false));
		classes.put(REGISTRY_MANAGER, dataMapsStep(types));
		classes.put(POI, step(POI, "init", "poi", false, false));
		classes.put(EVENTS, events(false, CAULDRON, CAPABILITIES, REGISTRY_MANAGER + "#initDataMaps", POI));
		return new Carrier(classes, classes);
	}

	/** {@code initDataMaps()} returns; {@code getDataMaps()} answers one registry holding {@code types} types. */
	private static byte[] dataMapsStep(int types) {
		ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
		cw.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, REGISTRY_MANAGER, null, "java/lang/Object", null);
		MethodVisitor init = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "initDataMaps", "()V", null, null);
		init.visitCode();
		init.visitInsn(Opcodes.RETURN);
		init.visitMaxs(0, 0);
		init.visitEnd();
		MethodVisitor get = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "getDataMaps", "()Ljava/util/Map;",
				null, null);
		get.visitCode();
		get.visitTypeInsn(Opcodes.NEW, "java/util/HashMap");
		get.visitInsn(Opcodes.DUP);
		get.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/util/HashMap", "<init>", "()V", false);
		for (int i = 0; i < types; i++) {
			get.visitInsn(Opcodes.DUP);
			get.visitLdcInsn("type" + i);
			get.visitLdcInsn("codec" + i);
			get.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/util/Map", "put",
					"(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;", true);
			get.visitInsn(Opcodes.POP);
		}
		get.visitVarInsn(Opcodes.ASTORE, 0);
		get.visitLdcInsn("minecraft:item");
		get.visitVarInsn(Opcodes.ALOAD, 0);
		get.visitMethodInsn(Opcodes.INVOKESTATIC, "java/util/Map", "of",
				"(Ljava/lang/Object;Ljava/lang/Object;)Ljava/util/Map;", true);
		get.visitInsn(Opcodes.ARETURN);
		get.visitMaxs(0, 0);
		get.visitEnd();
		cw.visitEnd();
		return cw.toByteArray();
	}

	/** The INFO lines {@code ForbricLog} wrote while {@code run} ran: stdout, log4j being absent from the test classpath. */
	private static String logOf(Runnable run) {
		java.io.ByteArrayOutputStream log = new java.io.ByteArrayOutputStream();
		java.io.PrintStream out = System.out;
		try {
			System.setOut(new java.io.PrintStream(log, true, java.nio.charset.StandardCharsets.UTF_8));
			run.run();
		} finally {
			System.setOut(out);
		}
		return log.toString(java.nio.charset.StandardCharsets.UTF_8);
	}

	/** The pattern a gate's "registration events ran" check greps for. */
	private static String registrationPattern(String gate) throws Exception {
		for (String line : Files.readAllLines(Path.of(gate))) {
			java.util.regex.Matcher m = java.util.regex.Pattern
					.compile("^(?:check|ck)\\s+\"registration events ran\"\\s+\"([^\"]+)\"").matcher(line);
			if (m.find()) return m.group(1);
		}
		throw new AssertionError(gate + " has no \"registration events ran\" check");
	}

	/** Whether {@code grep -acE pattern} counts a line of {@code text}, as the gates ask. */
	private static boolean grep(Path dir, String pattern, String text) throws Exception {
		Path log = Files.writeString(dir.resolve("boot.log"), text);
		Process grep = new ProcessBuilder("grep", "-acE", pattern, log.toString()).redirectErrorStream(true).start();
		assertTrue(grep.waitFor(15, java.util.concurrent.TimeUnit.SECONDS), "grep timed out");
		return !"0".equals(new String(grep.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8).strip());
	}

	private static CompatibilityFinding finding(String id) {
		return CompatibilityFindings.all().stream().filter(f -> f.id().equals(id)).findFirst().orElse(null);
	}

	/**
	 * A carrier with RegistrationEvents.init = cauldron, capabilities, data maps (always throws), POI. The file
	 * it serves is always the plain one; {@code transformed} defines a class that also carries a merged handler.
	 */
	private static Carrier carrier(boolean capabilitiesThrow, boolean transformed) {
		Map<String, byte[]> classes = stepClasses(capabilitiesThrow);
		String[] steps = {CAULDRON, CAPABILITIES, REGISTRY_MANAGER + "#initDataMaps", POI};
		byte[] file = events(false, steps);
		classes.put(EVENTS, transformed ? withMergedHandler(file) : file);
		Map<String, byte[]> resources = new HashMap<>(classes);
		resources.put(EVENTS, file);
		return new Carrier(classes, resources);
	}

	private static Map<String, byte[]> stepClasses(boolean capabilitiesThrow) {
		Map<String, byte[]> classes = new HashMap<>();
		classes.put(CAULDRON, step(CAULDRON, "init", "cauldron", false, false));
		classes.put(CAPABILITIES, step(CAPABILITIES, "init", "capabilities", capabilitiesThrow, false));
		classes.put(REGISTRY_MANAGER, step(REGISTRY_MANAGER, "initDataMaps", "data maps", true, true));
		classes.put(POI, step(POI, "init", "poi", false, false));
		return classes;
	}

	/** {@code init()V} calling each owner's {@code init} (or {@code owner#name}), optionally behind a branch. */
	private static byte[] events(boolean branch, String... owners) {
		ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
		cw.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, EVENTS, null, "java/lang/Object", null);
		MethodVisitor mv = cw.visitMethod(Opcodes.ACC_STATIC, "init", "()V", null, null);
		mv.visitCode();
		Label skip = new Label();
		if (branch) {
			mv.visitFieldInsn(Opcodes.GETSTATIC, "java/lang/Boolean", "TRUE", "Ljava/lang/Boolean;");
			mv.visitJumpInsn(Opcodes.IFNULL, skip);
		}
		for (String owner : owners) {
			String[] parts = owner.split("#");
			mv.visitMethodInsn(Opcodes.INVOKESTATIC, parts[0], parts.length > 1 ? parts[1] : "init", "()V", false);
		}
		if (branch) mv.visitLabel(skip);
		mv.visitInsn(Opcodes.RETURN);
		mv.visitMaxs(0, 0);
		mv.visitEnd();
		cw.visitEnd();
		return cw.toByteArray();
	}

	/** The same class with one more method, named the way Mixin names a merged injector. */
	private static byte[] withMergedHandler(byte[] file) {
		ClassReader reader = new ClassReader(file);
		ClassWriter cw = new ClassWriter(reader, 0);
		reader.accept(new org.objectweb.asm.ClassVisitor(Opcodes.ASM9, cw) {
			@Override
			public void visitEnd() {
				MethodVisitor mv = super.visitMethod(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC,
						"handler$zza000$somemod$afterInit", "()V", null, null);
				mv.visitCode();
				mv.visitInsn(Opcodes.RETURN);
				mv.visitMaxs(0, 0);
				mv.visitEnd();
				super.visitEnd();
			}
		}, 0);
		return cw.toByteArray();
	}

	/** A step class: records itself, then optionally throws; the data-map owner also answers getDataMaps. */
	private static byte[] step(String owner, String name, String label, boolean throwsAfter, boolean dataMaps) {
		ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
		cw.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, owner, null, "java/lang/Object", null);
		// Public, as NeoForge's are: the whole-call path reaches them from another package's bytecode.
		MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, name, "()V", null, null);
		mv.visitCode();
		if (throwsAfter) {
			mv.visitTypeInsn(Opcodes.NEW, "java/lang/IllegalStateException");
			mv.visitInsn(Opcodes.DUP);
			mv.visitLdcInsn("a listener died in " + label);
			mv.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/IllegalStateException", "<init>",
					"(Ljava/lang/String;)V", false);
			mv.visitInsn(Opcodes.ATHROW);
		} else {
			mv.visitLdcInsn(label);
			mv.visitMethodInsn(Opcodes.INVOKESTATIC, Recorder.class.getName().replace('.', '/'), "hit",
					"(Ljava/lang/String;)V", false);
			mv.visitInsn(Opcodes.RETURN);
		}
		mv.visitMaxs(0, 0);
		mv.visitEnd();
		if (dataMaps) {
			MethodVisitor get = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "getDataMaps",
					"()Ljava/util/Map;", null, null);
			get.visitCode();
			get.visitMethodInsn(Opcodes.INVOKESTATIC, "java/util/Map", "of", "()Ljava/util/Map;", true);
			get.visitInsn(Opcodes.ARETURN);
			get.visitMaxs(0, 0);
			get.visitEnd();
		}
		cw.visitEnd();
		return cw.toByteArray();
	}

	/** Defines the synthetic carrier classes and serves {@code resources} as their files. */
	private static final class Carrier extends ClassLoader {
		private final Map<String, byte[]> classes;
		private final Map<String, byte[]> resources;

		Carrier(Map<String, byte[]> classes, Map<String, byte[]> resources) {
			super(RegistrationEventStepsTest.class.getClassLoader());
			this.classes = classes;
			this.resources = resources;
		}

		/** Child-first for the synthetic names, so a real carrier on the test classpath can never stand in. */
		@Override
		protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
			byte[] bytes = classes.get(name.replace('.', '/'));
			if (bytes == null) return super.loadClass(name, resolve);
			synchronized (getClassLoadingLock(name)) {
				Class<?> c = findLoadedClass(name);
				return c != null ? c : defineClass(name, bytes, 0, bytes.length);
			}
		}

		@Override
		public InputStream getResourceAsStream(String name) {
			byte[] bytes = name.endsWith(".class") ? resources.get(name.substring(0, name.length() - 6)) : null;
			return bytes != null ? new ByteArrayInputStream(bytes) : super.getResourceAsStream(name);
		}
	}
}
