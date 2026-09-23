package net.forbric.kernel.boot;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import net.fabricmc.api.EnvType;
import net.forbric.api.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.objectweb.asm.*;

@ResourceLock("ModCatalog") @ResourceLock("system-properties")
class CandidateContractScannerTest {
	@TempDir Path dir;
	@BeforeEach @AfterEach void reset() {
		DuplicateModArbiter.reset(); CompatibilityFindings.reset();
		System.clearProperty(DuplicateModArbiter.OWNER_OVERRIDE);
		System.setProperty("forbric.dupeIdPreference", "neoforge,fabric,minecraftforge");
	}
	@AfterEach void clearPreference() { System.clearProperty("forbric.dupeIdPreference"); }

	@Test void mandatoryVersionsOverrideAnOtherwisePreferredEcosystem() throws Exception {
		var claims = versionPack(">=2", false);
		var decision = DuplicateModArbiter.arbitrateJoint(claims, List.of(), EnvType.CLIENT);
		assertTrue(decision.suppressed(claims.get(1).jar()));
		assertFalse(decision.suppressed(claims.get(2).jar()));
		assertTrue(CompatibilityFindings.confirmedRequired().isEmpty());
	}

	@Test void impossibleVersionProducesAConfirmedFindingAndKeepsTheOverride() throws Exception {
		var claims = versionPack(">=2", false);
		System.setProperty(DuplicateModArbiter.OWNER_OVERRIDE, "dep=neoforge");
		var decision = DuplicateModArbiter.arbitrateJoint(claims, List.of(), EnvType.CLIENT);
		assertFalse(decision.suppressed(claims.get(1).jar()));
		assertTrue(decision.suppressed(claims.get(2).jar()));
		assertTrue(CompatibilityFindings.confirmedRequired().stream().anyMatch(f -> f.detail().contains("requires dep >=2")));
	}

	@Test void aSoftMetadataRecommendationCannotEliminateAnInstalledCandidate() throws Exception {
		var claims = versionPack(">=2", true);
		var decision = DuplicateModArbiter.arbitrateJoint(claims, List.of(), EnvType.CLIENT);
		assertFalse(decision.suppressed(claims.get(1).jar()));
		assertTrue(CompatibilityFindings.confirmedRequired().isEmpty());
	}

	@Test void anUnconditionalRequiredMixinChoosesTheCandidateThatActuallyHasItsTarget() throws Exception {
		var claims = targetPack(true, false);
		var decision = DuplicateModArbiter.arbitrateJoint(claims, List.of(), EnvType.CLIENT);
		assertTrue(decision.suppressed(claims.get(1).jar()));
		assertFalse(decision.suppressed(claims.get(2).jar()));
	}

	@Test void optionalAndPluginControlledMixinsAreOnlySuspicions() throws Exception {
		for (boolean plugin : List.of(false, true)) {
			reset();
			var claims = targetPack(plugin, plugin);
			var decision = DuplicateModArbiter.arbitrateJoint(claims, List.of(), EnvType.CLIENT);
			assertFalse(decision.suppressed(claims.get(1).jar()));
			assertTrue(CompatibilityFindings.confirmedRequired().isEmpty());
			assertTrue(CompatibilityFindings.all().stream().anyMatch(f -> f.confidence() == CompatibilityFinding.Confidence.SUSPECTED));
		}
	}

	@Test void aClientOnlyMixinIsNotARequiredServerContract() throws Exception {
		var claims = targetPack(true, false, "CLIENT");
		var decision = DuplicateModArbiter.arbitrateJoint(claims, List.of(), EnvType.SERVER);
		assertFalse(decision.suppressed(claims.get(1).jar()));
		assertTrue(CompatibilityFindings.all().isEmpty(), "a known disabled mixin is neither broken nor suspected");
	}

	@Test void directUnconditionalEntrypointCallsCheckTheActualMemberDescriptor() throws Exception {
		var claims = apiPack(false);
		var decision = DuplicateModArbiter.arbitrateJoint(claims, List.of(), EnvType.CLIENT);
		assertTrue(decision.suppressed(claims.get(1).jar()));
		assertFalse(decision.suppressed(claims.get(2).jar()));
	}

	@Test void anUnexecutedPlatformBranchIsNotPromotedToAHardDependency() throws Exception {
		var claims = apiPack(true);
		var decision = DuplicateModArbiter.arbitrateJoint(claims, List.of(), EnvType.CLIENT);
		assertFalse(decision.suppressed(claims.get(1).jar()));
		assertTrue(CompatibilityFindings.confirmedRequired().isEmpty());
		assertTrue(CompatibilityFindings.all().stream().anyMatch(f -> f.confidence() == CompatibilityFinding.Confidence.SUSPECTED));
	}

	@Test void staticAndInstanceMethodCallsCannotShareAnIncompatibleSameNamedMember() throws Exception {
		for (int opcode : List.of(Opcodes.INVOKESTATIC, Opcodes.INVOKEVIRTUAL)) {
			reset(); boolean needsStatic = opcode == Opcodes.INVOKESTATIC;
			var claims = abiPack(opcode, false, false, !needsStatic, false, needsStatic, false, false);
			var decision = DuplicateModArbiter.arbitrateJoint(claims, List.of(), EnvType.CLIENT);
			assertTrue(decision.suppressed(claims.get(1).jar()), "same descriptor is not proof when staticness differs");
			assertFalse(decision.suppressed(claims.get(2).jar()));
		}
	}

	@Test void theConstantPoolClassOrInterfaceKindMustMatchInBothDirections() throws Exception {
		for (boolean interfaceCall : List.of(false, true)) {
			reset();
			var claims = abiPack(interfaceCall ? Opcodes.INVOKEINTERFACE : Opcodes.INVOKEVIRTUAL, interfaceCall,
					!interfaceCall, false, interfaceCall, false, false, false);
			var decision = DuplicateModArbiter.arbitrateJoint(claims, List.of(), EnvType.CLIENT);
			assertTrue(decision.suppressed(claims.get(1).jar()), "Methodref and InterfaceMethodref cannot be interchanged");
			assertFalse(decision.suppressed(claims.get(2).jar()));
		}
	}

	@Test void fieldOpcodesAlsoRequireTheMatchingStaticness() throws Exception {
		for (int opcode : List.of(Opcodes.GETSTATIC, Opcodes.GETFIELD, Opcodes.PUTSTATIC, Opcodes.PUTFIELD)) {
			reset(); boolean needsStatic = opcode == Opcodes.GETSTATIC || opcode == Opcodes.PUTSTATIC;
			var claims = abiPack(opcode, false, false, !needsStatic, false, needsStatic, true, false);
			var decision = DuplicateModArbiter.arbitrateJoint(claims, List.of(), EnvType.CLIENT);
			assertTrue(decision.suppressed(claims.get(1).jar()), "a field name and descriptor do not establish GET/PUT linkage");
		}
	}

	@Test void aPrivateMemberThatAnAccessWidenerCouldExposeIsUnknownRatherThanBrokenOrProved() throws Exception {
		var claims = abiPack(Opcodes.INVOKESTATIC, false, false, true, false, true, false, true);
		var decision = DuplicateModArbiter.arbitrateJoint(claims, List.of(), EnvType.CLIENT);
		assertTrue(decision.suppressed(claims.get(1).jar()), "the build that provably links is preferred");
		assertFalse(decision.suppressed(claims.get(2).jar()));
		assertTrue(CompatibilityFindings.all().isEmpty());
		// Unknown is not broken: chosen explicitly it loads and is reported unproved, never confirmed.
		reset(); System.setProperty(DuplicateModArbiter.OWNER_OVERRIDE, "dep=neoforge");
		decision = DuplicateModArbiter.arbitrateJoint(claims, List.of(), EnvType.CLIENT);
		assertFalse(decision.suppressed(claims.get(1).jar()));
		assertTrue(CompatibilityFindings.confirmedRequired().isEmpty());
		assertTrue(CompatibilityFindings.all().stream().anyMatch(f -> f.id().equals("arbitration:selection")
				&& f.confidence() == CompatibilityFinding.Confidence.SUSPECTED && f.detail().contains("unproved")));
	}

	@Test void aDeclaredMixinCanProvideTheMissingMemberEvenWhenItsActivationIsConditional() throws Exception {
		for (boolean plugin : List.of(false, true)) {
			reset(); var claims = apiPack(false);
			addAugmentingMixin(claims.getFirst().jar(), plugin, null);
			var decision = DuplicateModArbiter.arbitrateJoint(claims, List.of(), EnvType.CLIENT);
			assertTrue(decision.suppressed(claims.get(1).jar()), "the build that already has the member is preferred");
			assertTrue(CompatibilityFindings.confirmedRequired().isEmpty());
			reset(); System.setProperty(DuplicateModArbiter.OWNER_OVERRIDE, "dep=neoforge");
			decision = DuplicateModArbiter.arbitrateJoint(claims, List.of(), EnvType.CLIENT);
			assertFalse(decision.suppressed(claims.get(1).jar()), "pre-Mixin absence cannot disqualify a candidate whose target may change");
			assertTrue(CompatibilityFindings.confirmedRequired().isEmpty());
			assertTrue(CompatibilityFindings.all().stream().anyMatch(f -> f.confidence() == CompatibilityFinding.Confidence.SUSPECTED));
		}
	}

	@Test void aKnownDisabledMixinCannotMaskAGenuineServerMemberMismatch() throws Exception {
		var claims = apiPack(false);
		addAugmentingMixin(claims.getFirst().jar(), false, "CLIENT");
		var decision = DuplicateModArbiter.arbitrateJoint(claims, List.of(), EnvType.SERVER);
		assertTrue(decision.suppressed(claims.get(1).jar()));
		assertFalse(decision.suppressed(claims.get(2).jar()));
	}

	@Test void uniquelyDispatchedHelperChainsConstrainTheActualExternalMember() throws Exception {
		for (int access : List.of(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, Opcodes.ACC_PRIVATE, Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL)) {
			reset(); var claims = helperPack("straight", access, false, 3);
			var decision = DuplicateModArbiter.arbitrateJoint(claims, List.of(), EnvType.CLIENT);
			assertTrue(decision.suppressed(claims.get(1).jar()), "proved helper closure must reject the missing API");
			assertFalse(decision.suppressed(claims.get(2).jar()));
			assertTrue(CompatibilityFindings.confirmedRequired().isEmpty());
		}
		reset(); var claims = helperPack("straight", Opcodes.ACC_PUBLIC, true, 2);
		assertTrue(DuplicateModArbiter.arbitrateJoint(claims, List.of(), EnvType.CLIENT).suppressed(claims.get(1).jar()),
				"a final receiver class also proves the unique method body");
		reset(); claims = helperPack("other-class", Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, false, 2);
		assertTrue(DuplicateModArbiter.arbitrateJoint(claims, List.of(), EnvType.CLIENT).suppressed(claims.get(1).jar()),
				"a separate same-jar helper class is included in the closure");
	}

	@Test void conditionalAndExceptionHandledHelpersNeverBecomeHardDependencies() throws Exception {
		for (String shape : List.of("entry-branch", "helper-branch", "helper-catch")) {
			reset(); var claims = helperPack(shape, Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, false, 2);
			var decision = DuplicateModArbiter.arbitrateJoint(claims, List.of(), EnvType.CLIENT);
			assertFalse(decision.suppressed(claims.get(1).jar()), shape);
			assertTrue(CompatibilityFindings.confirmedRequired().isEmpty(), shape);
			assertTrue(CompatibilityFindings.all().stream().anyMatch(f -> f.confidence() == CompatibilityFinding.Confidence.SUSPECTED
					&& f.detail().contains("dep/Api#needed")), "retain the conditional member observation");
		}
	}

	@Test void satisfiedConditionalHelpersDoNotInventUnknownContracts() throws Exception {
		for (String shape : List.of("jdk-guard", "helper-branch-satisfied", "helper-catch-satisfied")) {
			reset(); var claims = helperPack(shape, Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, false, 1);
			var result = helperSelection(claims);
			assertEquals(JointCandidateSelector.Status.SOLVED, result.status(), shape);
			assertTrue(result.uncertain().isEmpty(), () -> shape + ": " + result.uncertain());
			assertTrue(result.unsatisfied().isEmpty());
		}
	}

	@Test void recursiveAndPolymorphicHelpersRemainExplicitlyUnproved() throws Exception {
		for (String shape : List.of("recursive", "polymorphic")) {
			reset(); var claims = helperPack(shape, shape.equals("recursive") ? Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC : Opcodes.ACC_PUBLIC, false, 1);
			var result = helperSelection(claims);
			assertEquals(JointCandidateSelector.Status.UNPROVED, result.status(), shape);
			assertTrue(result.selected().contains(claims.get(1).jar()), "an unproved body must not force the other ecosystem");
			assertTrue(result.unsatisfied().isEmpty());
			assertTrue(result.uncertain().stream().anyMatch(r -> r.detail().contains(shape.equals("recursive") ? "recursive helper" : "another body")));
			assertTrue(result.uncertain().stream().anyMatch(r -> r.detail().contains("dep/Api#needed") && !r.hard()));
		}
	}

	@Test void depthAndNodeBudgetsAreVisibleAndCannotTurnOmittedCallsIntoProof() throws Exception {
		for (String shape : List.of("straight", "fanout")) {
			reset(); int count = shape.equals("straight") ? CandidateContractScanner.HELPER_DEPTH_LIMIT + 2 : CandidateContractScanner.HELPER_NODE_LIMIT + 1;
			var claims = helperPack(shape, Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, false, count);
			var result = helperSelection(claims);
			assertEquals(JointCandidateSelector.Status.UNPROVED, result.status(), shape);
			assertTrue(result.selected().contains(claims.get(1).jar()));
			assertTrue(result.unsatisfied().isEmpty());
			assertTrue(result.uncertain().stream().anyMatch(r -> r.detail().contains("closure limit")));
		}
	}

	@Test void manualSelectionCannotSatisfyAMemberMissingBehindAProvedHelper() throws Exception {
		var claims = helperPack("straight", Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, false, 2);
		System.setProperty(DuplicateModArbiter.OWNER_OVERRIDE, "dep=neoforge");
		var decision = DuplicateModArbiter.arbitrateJoint(claims, List.of(), EnvType.CLIENT);
		assertFalse(decision.suppressed(claims.get(1).jar()), "keep the explicit choice visible");
		assertTrue(CompatibilityFindings.confirmedRequired().stream().anyMatch(f -> f.detail().contains("dep/Api#needed")));
		assertTrue(CompatibilityFindings.confirmedRequired().stream().anyMatch(f -> f.id().equals("arbitration:selection")));
	}

	@Test void instructionBudgetAlsoLeavesAnExplicitUnprovedResult() throws Exception {
		var claims = helperPack("instruction-limit", Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, false, 1);
		var result = helperSelection(claims);
		assertEquals(JointCandidateSelector.Status.UNPROVED, result.status());
		assertTrue(result.selected().contains(claims.get(1).jar()));
		assertTrue(result.uncertain().stream().anyMatch(r -> r.detail().contains("instruction limit")));
	}

	@Test void anUnreachableMemberAfterRecursiveCallOrReturnCannotBecomeHard() throws Exception {
		for (String shape : List.of("recursive", "early-return")) {
			reset(); var claims = helperPack(shape, Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, false, 1);
			var result = helperSelection(claims);
			assertTrue(result.selected().contains(claims.get(1).jar()));
			assertTrue(result.unsatisfied().isEmpty());
		}
	}

	private static JointCandidateSelector.Result helperSelection(List<DuplicateModArbiter.Claim> claims) {
		return JointCandidateSelector.solve(claims, CandidateContractScanner.scan(claims, EnvType.CLIENT),
				List.of(Ecosystem.NEOFORGE, Ecosystem.FABRIC, Ecosystem.FORGE), Map.of(), 1000);
	}

	private List<DuplicateModArbiter.Claim> helperPack(String shape, int helperAccess, boolean finalClass, int count) throws Exception {
		ClassWriter writer = new ClassWriter(0);
		writer.visit(Opcodes.V21, Opcodes.ACC_PUBLIC | (finalClass ? Opcodes.ACC_FINAL : 0), "app/Main", null,
				"java/lang/Object", new String[] {"net/fabricmc/api/ModInitializer"});
		MethodVisitor entry = writer.visitMethod(Opcodes.ACC_PUBLIC, "onInitialize", "()V", null, null); entry.visitCode();
		String helperOwner = shape.equals("other-class") ? "app/Helpers" : "app/Main";
		Label skip = new Label();
		if (shape.equals("entry-branch")) { entry.visitInsn(Opcodes.ICONST_0); entry.visitJumpInsn(Opcodes.IFEQ, skip); }
		for (int i = 0; i < (shape.equals("fanout") ? count : 1); i++) helperCall(entry, helperOwner, helperAccess, "h" + i);
		entry.visitLabel(skip); entry.visitInsn(Opcodes.RETURN); entry.visitMaxs(2, 1); entry.visitEnd();
		Map<String, byte[]> classes = new LinkedHashMap<>();
		if (shape.equals("other-class")) {
			writer.visitEnd(); classes.put("app/Main.class", writer.toByteArray()); writer = new ClassWriter(0);
			writer.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, helperOwner, null, "java/lang/Object", null);
		}
		if (shape.equals("jdk-guard")) writer.visitField(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC, "registered", "Z", null, null).visitEnd();
		for (int i = 0; i < count; i++) {
			MethodVisitor helper = writer.visitMethod(helperAccess, "h" + i, "()V", null, null); helper.visitCode();
			boolean last = i == count - 1;
			Label end = new Label(), begin = new Label(), caught = new Label();
			if (last && shape.startsWith("helper-catch")) helper.visitTryCatchBlock(begin, end, caught, "java/lang/Exception");
			helper.visitLabel(begin);
			if (last && shape.startsWith("helper-branch")) { helper.visitInsn(Opcodes.ICONST_0); helper.visitJumpInsn(Opcodes.IFEQ, end); }
			if (shape.equals("jdk-guard")) {
				// M19's NestLibRegistry shape: reject a duplicate registration; otherwise record it and log.
				Label register = new Label();
				helper.visitFieldInsn(Opcodes.GETSTATIC, helperOwner, "registered", "Z"); helper.visitJumpInsn(Opcodes.IFEQ, register);
				helper.visitTypeInsn(Opcodes.NEW, "java/lang/IllegalStateException"); helper.visitInsn(Opcodes.DUP);
				helper.visitLdcInsn("duplicate registration"); helper.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/IllegalStateException", "<init>", "(Ljava/lang/String;)V", false);
				helper.visitInsn(Opcodes.ATHROW); helper.visitLabel(register); helper.visitInsn(Opcodes.ICONST_1);
				helper.visitFieldInsn(Opcodes.PUTSTATIC, helperOwner, "registered", "Z");
				helper.visitFieldInsn(Opcodes.GETSTATIC, "java/lang/System", "out", "Ljava/io/PrintStream;"); helper.visitLdcInsn("registered");
				helper.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/io/PrintStream", "println", "(Ljava/lang/String;)V", false);
			}
			if (shape.equals("recursive")) helperCall(helper, helperOwner, helperAccess, "h0");
			if (shape.equals("early-return")) helper.visitInsn(Opcodes.RETURN);
			if (shape.equals("instruction-limit")) for (int j = 0; j < CandidateContractScanner.HELPER_INSTRUCTION_LIMIT; j++) helper.visitInsn(Opcodes.NOP);
			if (!last && !shape.equals("fanout")) helperCall(helper, helperOwner, helperAccess, "h" + (i + 1));
			if (last && !shape.equals("jdk-guard")) helper.visitMethodInsn(Opcodes.INVOKESTATIC, "dep/Api", "needed", "()V", false);
			helper.visitLabel(end); helper.visitInsn(Opcodes.RETURN);
			if (last && shape.startsWith("helper-catch")) { helper.visitLabel(caught); helper.visitInsn(Opcodes.POP); helper.visitInsn(Opcodes.RETURN); }
			helper.visitMaxs(3, (helperAccess & Opcodes.ACC_STATIC) == 0 ? 1 : 0); helper.visitEnd();
		}
		writer.visitEnd(); classes.put(helperOwner + ".class", writer.toByteArray());
		Path app = fabric("app.jar", "app", "1", ",\"depends\":{\"dep\":\"*\"},\"entrypoints\":{\"main\":[\"app.Main\"]}", classes);
		Path neo = neo("dep-neo.jar", "dep", "1", Map.of("dep/Api.class", api("dep/Api", shape.endsWith("-satisfied"))));
		Path fab = fabric("dep-fab.jar", "dep", "2", "", Map.of("dep/Api.class", api("dep/Api", true)));
		return claims(app, neo, fab);
	}

	private static void helperCall(MethodVisitor method, String owner, int access, String name) {
		boolean isStatic = (access & Opcodes.ACC_STATIC) != 0;
		if (!isStatic) method.visitVarInsn(Opcodes.ALOAD, 0);
		method.visitMethodInsn(isStatic ? Opcodes.INVOKESTATIC : (access & Opcodes.ACC_PRIVATE) != 0 ? Opcodes.INVOKESPECIAL : Opcodes.INVOKEVIRTUAL,
				owner, name, "()V", false);
	}

	private List<DuplicateModArbiter.Claim> abiPack(int opcode, boolean interfaceCall, boolean neoInterface,
			boolean neoStatic, boolean fabricInterface, boolean fabricStatic, boolean field, boolean neoPrivate) throws Exception {
		ClassWriter entry = new ClassWriter(0); entry.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, "app/Main", null, "java/lang/Object", new String[] {"net/fabricmc/api/ModInitializer"});
		MethodVisitor method = entry.visitMethod(Opcodes.ACC_PUBLIC, "onInitialize", "()V", null, null); method.visitCode();
		boolean staticCall = opcode == Opcodes.INVOKESTATIC || opcode == Opcodes.GETSTATIC || opcode == Opcodes.PUTSTATIC;
		if (!staticCall) method.visitInsn(Opcodes.ACONST_NULL);
		if (opcode == Opcodes.PUTFIELD || opcode == Opcodes.PUTSTATIC) method.visitInsn(Opcodes.ICONST_0);
		if (field) method.visitFieldInsn(opcode, "dep/Api", "needed", "I");
		else method.visitMethodInsn(opcode, "dep/Api", "needed", "()V", interfaceCall);
		if (opcode == Opcodes.GETFIELD || opcode == Opcodes.GETSTATIC) method.visitInsn(Opcodes.POP);
		method.visitInsn(Opcodes.RETURN); method.visitMaxs(2, 1); method.visitEnd(); entry.visitEnd();
		Path app = fabric("app.jar", "app", "1", ",\"depends\":{\"dep\":\"*\"},\"entrypoints\":{\"main\":[\"app.Main\"]}", Map.of("app/Main.class", entry.toByteArray()));
		Path neo = neo("dep-neo.jar", "dep", "1", Map.of("dep/Api.class", apiShape(neoInterface, neoStatic, field, neoPrivate)));
		Path fabric = fabric("dep-fab.jar", "dep", "2", "", Map.of("dep/Api.class", apiShape(fabricInterface, fabricStatic, field, false)));
		return claims(app, neo, fabric);
	}

	private static byte[] apiShape(boolean isInterface, boolean isStatic, boolean field, boolean isPrivate) {
		ClassWriter writer = new ClassWriter(0);
		writer.visit(Opcodes.V21, Opcodes.ACC_PUBLIC | (isInterface ? Opcodes.ACC_INTERFACE | Opcodes.ACC_ABSTRACT : 0), "dep/Api", null, "java/lang/Object", null);
		int access = (isPrivate ? Opcodes.ACC_PRIVATE : Opcodes.ACC_PUBLIC) | (isStatic ? Opcodes.ACC_STATIC : 0);
		if (field) writer.visitField(access, "needed", "I", null, null).visitEnd();
		else {
			boolean abstractMethod = isInterface && !isStatic;
			MethodVisitor method = writer.visitMethod(access | (abstractMethod ? Opcodes.ACC_ABSTRACT : 0), "needed", "()V", null, null);
			if (!abstractMethod) { method.visitCode(); method.visitInsn(Opcodes.RETURN); method.visitMaxs(0, isStatic ? 0 : 1); }
			method.visitEnd();
		}
		writer.visitEnd(); return writer.toByteArray();
	}

	private static void addAugmentingMixin(Path appJar, boolean plugin, String environment) throws Exception {
		Map<String, byte[]> resources = new LinkedHashMap<>();
		try (java.util.jar.JarFile zip = new java.util.jar.JarFile(appJar.toFile())) {
			for (ZipEntry entry : zip.stream().toList()) resources.put(entry.getName(), zip.getInputStream(entry).readAllBytes());
		}
		String metadata = new String(resources.get("fabric.mod.json"), StandardCharsets.UTF_8);
		resources.put("fabric.mod.json", (metadata.substring(0, metadata.length() - 1) + ",\"mixins\":[\"augment.mixins.json\"]}").getBytes(StandardCharsets.UTF_8));
		resources.put("augment.mixins.json", ("{\"required\":false,\"package\":\"app.mixin\",\"mixins\":[\"Augment\"]"
				+ (plugin ? ",\"plugin\":\"app.ConditionalPlugin\"" : "") + "}").getBytes(StandardCharsets.UTF_8));
		ClassWriter writer = new ClassWriter(0); writer.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, "app/mixin/Augment", null, "java/lang/Object", null);
		AnnotationVisitor annotation = writer.visitAnnotation("Lorg/spongepowered/asm/mixin/Mixin;", false);
		AnnotationVisitor values = annotation.visitArray("value"); values.visit(null, Type.getObjectType("dep/Api")); values.visitEnd(); annotation.visitEnd();
		if (environment != null) { AnnotationVisitor env = writer.visitAnnotation("Lnet/fabricmc/api/Environment;", false); env.visitEnum("value", "Lnet/fabricmc/api/EnvType;", environment); env.visitEnd(); }
		MethodVisitor supplied = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "needed", "()V", null, null);
		supplied.visitCode(); supplied.visitInsn(Opcodes.RETURN); supplied.visitMaxs(0, 0); supplied.visitEnd(); writer.visitEnd();
		resources.put("app/mixin/Augment.class", writer.toByteArray());
		try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(appJar))) {
			for (var entry : resources.entrySet()) { zip.putNextEntry(new ZipEntry(entry.getKey())); zip.write(entry.getValue()); zip.closeEntry(); }
		}
	}

	private List<DuplicateModArbiter.Claim> versionPack(String range, boolean optional) throws Exception {
		String section = optional ? "recommends" : "depends";
		Path app = fabric("app.jar", "app", "1", ",\"" + section + "\":{\"dep\":\"" + range + "\"}", Map.of());
		Path neo = neo("dep-neo.jar", "dep", "1", Map.of());
		Path fab = fabric("dep-fab.jar", "dep", "2", "", Map.of());
		return claims(app, neo, fab);
	}

	private List<DuplicateModArbiter.Claim> targetPack(boolean required, boolean plugin) throws Exception {
		return targetPack(required, plugin, null);
	}

	private List<DuplicateModArbiter.Claim> targetPack(boolean required, boolean plugin, String environment) throws Exception {
		String config = "{\"required\":" + required + ",\"package\":\"app.mixin\",\"mixins\":[\"Target\"]"
				+ (plugin ? ",\"plugin\":\"app.ConditionalPlugin\"" : "") + "}";
		ClassWriter mixin = new ClassWriter(0); mixin.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, "app/mixin/Target", null, "java/lang/Object", null);
		AnnotationVisitor annotation = mixin.visitAnnotation("Lorg/spongepowered/asm/mixin/Mixin;", false);
		AnnotationVisitor targets = annotation.visitArray("value"); targets.visit(null, Type.getObjectType("dep/FabricOnly")); targets.visitEnd(); annotation.visitEnd();
		if (environment != null) {
			AnnotationVisitor env = mixin.visitAnnotation("Lnet/fabricmc/api/Environment;", false);
			env.visitEnum("value", "Lnet/fabricmc/api/EnvType;", environment); env.visitEnd();
		}
		mixin.visitEnd();
		Path app = fabric("app.jar", "app", "1", ",\"depends\":{\"dep\":\"*\"},\"mixins\":[\"app.mixins.json\"]",
				Map.of("app.mixins.json", config.getBytes(StandardCharsets.UTF_8), "app/mixin/Target.class", mixin.toByteArray()));
		Path neo = neo("dep-neo.jar", "dep", "1", Map.of("dep/Shared.class", api("dep/Shared", false)));
		Path fab = fabric("dep-fab.jar", "dep", "2", "", Map.of("dep/FabricOnly.class", api("dep/FabricOnly", false)));
		return claims(app, neo, fab);
	}

	private List<DuplicateModArbiter.Claim> apiPack(boolean conditional) throws Exception {
		ClassWriter entry = new ClassWriter(0); entry.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, "app/Main", null, "java/lang/Object", new String[] {"net/fabricmc/api/ModInitializer"});
		MethodVisitor method = entry.visitMethod(Opcodes.ACC_PUBLIC, "onInitialize", "()V", null, null); method.visitCode();
		Label skip = new Label(); if (conditional) { method.visitInsn(Opcodes.ICONST_0); method.visitJumpInsn(Opcodes.IFEQ, skip); }
		method.visitMethodInsn(Opcodes.INVOKESTATIC, "dep/Api", "needed", "()V", false);
		if (conditional) method.visitLabel(skip); method.visitInsn(Opcodes.RETURN); method.visitMaxs(1, 1); method.visitEnd(); entry.visitEnd();
		Path app = fabric("app.jar", "app", "1", ",\"depends\":{\"dep\":\"*\"},\"entrypoints\":{\"main\":[\"app.Main\"]}", Map.of("app/Main.class", entry.toByteArray()));
		Path neo = neo("dep-neo.jar", "dep", "1", Map.of("dep/Api.class", api("dep/Api", false)));
		Path fab = fabric("dep-fab.jar", "dep", "2", "", Map.of("dep/Api.class", api("dep/Api", true)));
		return claims(app, neo, fab);
	}

	private static byte[] api(String name, boolean method) {
		ClassWriter writer = new ClassWriter(0); writer.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, name, null, "java/lang/Object", null);
		if (method) { MethodVisitor m = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "needed", "()V", null, null); m.visitCode(); m.visitInsn(Opcodes.RETURN); m.visitMaxs(0, 0); m.visitEnd(); }
		writer.visitEnd(); return writer.toByteArray();
	}

	private static List<DuplicateModArbiter.Claim> claims(Path app, Path neo, Path fab) {
		return List.of(new DuplicateModArbiter.Claim(app, Ecosystem.FABRIC, List.of("app"), Map.of("app", "1")),
				new DuplicateModArbiter.Claim(neo, Ecosystem.NEOFORGE, List.of("dep"), Map.of("dep", "1")),
				new DuplicateModArbiter.Claim(fab, Ecosystem.FABRIC, List.of("dep"), Map.of("dep", "2")));
	}
	private Path fabric(String file, String id, String version, String extra, Map<String, byte[]> resources) throws Exception {
		Map<String, byte[]> all = new LinkedHashMap<>(resources);
		all.put("fabric.mod.json", ("{\"schemaVersion\":1,\"id\":\"" + id + "\",\"version\":\"" + version + "\"" + extra + "}").getBytes(StandardCharsets.UTF_8));
		return jar(file, all);
	}
	private Path neo(String file, String id, String version, Map<String, byte[]> resources) throws Exception {
		Map<String, byte[]> all = new LinkedHashMap<>(resources);
		all.put("META-INF/neoforge.mods.toml", ("modLoader=\"javafml\"\nloaderVersion=\"[1,)\"\nlicense=\"MIT\"\n[[mods]]\nmodId=\"" + id + "\"\nversion=\"" + version + "\"\n").getBytes(StandardCharsets.UTF_8));
		return jar(file, all);
	}
	private Path jar(String file, Map<String, byte[]> resources) throws Exception {
		Path path = dir.resolve(file);
		try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(path))) {
			for (var entry : resources.entrySet()) { zip.putNextEntry(new ZipEntry(entry.getKey())); zip.write(entry.getValue()); zip.closeEntry(); }
		}
		return path;
	}
}
