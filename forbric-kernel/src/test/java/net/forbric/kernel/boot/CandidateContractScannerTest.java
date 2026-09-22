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
