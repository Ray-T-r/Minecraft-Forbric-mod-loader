package net.forbric.kernel.mixin;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import net.forbric.api.CompatibilityFinding;
import net.forbric.api.CompatibilityFindings;
import net.forbric.api.Ecosystem;
import net.forbric.kernel.boot.KernelLoadReport;
import net.forbric.kernel.ui.CompatibilityDecision;

/** The actual diagnostic sink must expose an ineffective injection without changing its policy verdict. */
@ResourceLock("ModCatalog") @ResourceLock("system-properties") @ResourceLock("system-streams")
class MixinInjectionWarningsTest {
	private static final String CONFIG = "injection-warning-test.json", MOD = "warningprobe";
	private static final String MIXIN = "example.WarningMixin", TARGET = "game.WarningTarget";
	private static final String HANDLER = "probe", DESC = "()V", RENAMED = "handler$warning000$probe";
	private static final String HUD = "net.minecraft.client.gui.Hud";
	private static final String HOTBAR_DESC = "(Lnet/minecraft/client/gui/GuiGraphicsExtractor;Lnet/minecraft/client/DeltaTracker;)V";

	@BeforeEach @AfterEach
	void reset() {
		System.clearProperty(MixinFit.LIVENESS_PROPERTY);
		MergedBaseUncalledMethods.forgetGuests();
		MixinCompatibility.reset();
		CompatibilityFindings.reset();
		PluginDeclinedMixins.reset();
		CompatibilityDecision.reset();
		MixinConfigOwners.publish(List.of(new MixinConfigOwners.Owned(CONFIG, MOD, Ecosystem.FABRIC)));
	}

	@Test void anOriginallyOptionalMissWarnsWithItsOwnerAndSymbolsWithoutBecomingAFailure() {
		prepare(MIXIN, TARGET, true, 1, 0);
		MixinCompatibility.record(CONFIG, MIXIN, "preflight uncertainty", CompatibilityFinding.Confidence.SUSPECTED,
				true, List.of("preflight"));
		String log = captureErrors(() -> observe(MIXIN, TARGET, target(MIXIN, TARGET, 0)));
		assertAttributed(log, MIXIN, TARGET, HANDLER + DESC);
		assertTrue(CompatibilityFindings.confirmedRequired().isEmpty(), CompatibilityFindings.all().toString());
		assertTrue(CompatibilityFindings.all().stream().noneMatch(f -> f.confidence() == CompatibilityFinding.Confidence.CONFIRMED),
				"the warning must not promote an optional injector to a confirmed loss");
		assertEquals(CompatibilityFinding.Confidence.RESOLVED, whole(MIXIN).confidence(),
				"the existing optional-injector discharge still applies");
	}

	@Test void aRequiredMissWarnsAndKeepsTheConfirmedRequiredFinding() {
		prepare(MIXIN, TARGET, false, 1, -1);
		String log = captureErrors(() -> observe(MIXIN, TARGET, target(MIXIN, TARGET, 0)));
		assertAttributed(log, MIXIN, TARGET, HANDLER + DESC);
		assertEquals(1, CompatibilityFindings.confirmedRequired().size());
		assertTrue(CompatibilityFindings.confirmedRequired().getFirst().id().contains("#" + HANDLER + DESC));
	}

	@SuppressWarnings("unchecked")
	@Test void anOptionalSugaredInjectorWithZeroReferencesWarnsWithoutBecomingARequiredLoss() {
		prepare(MIXIN, TARGET, true, 1, 0);
		ClassNode mixin = mixin(MIXIN, TARGET, 0);
		mixin.methods.getFirst().invisibleParameterAnnotations = new List[] {
				List.of(new AnnotationNode("Lcom/llamalad7/mixinextras/sugar/Local;")) };
		FinalMixinApplications.remember(mixin);
		String log = captureErrors(() -> observe(MIXIN, TARGET, target(MIXIN, TARGET, 0)));
		assertAttributed(log, MIXIN, TARGET, HANDLER + DESC);
		assertEquals(1, warnings(log).size(), log);
		assertTrue(CompatibilityFindings.confirmedRequired().isEmpty(), CompatibilityFindings.all().toString());
		assertTrue(CompatibilityFindings.all().stream().noneMatch(f -> f.confidence() == CompatibilityFinding.Confidence.CONFIRMED),
				"unmodelled sugar must not be promoted to a confirmed feature loss by its diagnostic");
	}

	@Test void anAttachedInjectorProducesNoWarning() {
		prepare(MIXIN, TARGET, true, 1, -1);
		String log = captureErrors(() -> observe(MIXIN, TARGET, target(MIXIN, TARGET, 1)));
		assertTrue(warnings(log).isEmpty(), log);
		assertTrue(CompatibilityFindings.confirmedRequired().isEmpty());
	}

	@Test void aPartlyAttachedInjectorBelowItsRequiredMinimumWarnsWithoutInventingAConfirmedCount() {
		prepare(MIXIN, TARGET, true, 2, -1);
		String log = captureErrors(() -> observe(MIXIN, TARGET, target(MIXIN, TARGET, 1)));
		assertAttributed(log, MIXIN, TARGET, HANDLER + DESC);
		assertTrue(CompatibilityFindings.confirmedRequired().isEmpty(), "one reference does not prove the native injection count");
		assertTrue(CompatibilityFindings.all().stream().anyMatch(f -> f.id().startsWith("mixin-injector:")
				&& f.confidence() == CompatibilityFinding.Confidence.SUSPECTED), CompatibilityFindings.all().toString());
	}

	@Test void repeatingTheSameDefinitionWarnsOncePerLaunchAndResetAllowsTheNextLaunch() {
		prepare(MIXIN, TARGET, true, 1, 0);
		ClassNode target = target(MIXIN, TARGET, 0);
		String firstLaunch = captureErrors(() -> {
			observe(MIXIN, TARGET, target);
			observe(MIXIN, TARGET, target);
		});
		assertEquals(1, warnings(firstLaunch).size(), firstLaunch);

		MixinCompatibility.reset();
		CompatibilityFindings.reset();
		prepare(MIXIN, TARGET, true, 1, 0);
		String nextLaunch = captureErrors(() -> observe(MIXIN, TARGET, target));
		assertEquals(1, warnings(nextLaunch).size(), "a new launch must report the still-missing injection again\n" + nextLaunch);
	}

	@Test void distinctMissingHandlersAreNotCollapsedIntoOneWarning() {
		prepare(MIXIN, TARGET, true, 1, 0);
		ClassNode mixin = mixin(MIXIN, TARGET, 0);
		mixin.methods.add(injector("second", 0));
		FinalMixinApplications.remember(mixin);
		ClassNode target = target(MIXIN, TARGET, 0);
		target.methods.add(mergedHandler(MIXIN, "handler$warning000$second"));
		String log = captureErrors(() -> observe(MIXIN, TARGET, target));
		assertEquals(2, warnings(log).size(), log);
		assertAttributed(log, MIXIN, TARGET, "second" + DESC);
		assertAttributed(log, MIXIN, TARGET, HANDLER + DESC);
	}

	@Test void aNamedGroupAlternativeDoesNotWarnAsAnIndependentOptionalMiss() {
		prepare(MIXIN, TARGET, true, 1, -1);
		ClassNode mixin = mixin(MIXIN, TARGET, -1);
		mixin.methods.getFirst().visibleAnnotations.add(annotation("Lorg/spongepowered/asm/mixin/injection/Group;", "name", "alternatives"));
		FinalMixinApplications.remember(mixin);
		String log = captureErrors(() -> observe(MIXIN, TARGET, target(MIXIN, TARGET, 0)));
		assertTrue(warnings(log).isEmpty(), "an unselected named-group alternative is not an independently optional miss\n" + log);
		assertTrue(CompatibilityFindings.confirmedRequired().isEmpty());
	}

	@Test void aDeadHostWarnsButKeepsItsExistingNonBlockingVerdict() {
		prepare(MIXIN, HUD, true, 1, -1);
		String log = captureErrors(() -> observe(MIXIN, HUD, deadHost()));
		assertAttributed(log, MIXIN, HUD, HANDLER + DESC);
		assertTrue(warnings(log).getFirst().contains("extractHotbarAndDecorations"), log);
		CompatibilityFinding finding = CompatibilityFindings.all().stream().filter(f -> f.id().startsWith("mixin-injector:"))
				.findFirst().orElseThrow();
		assertEquals(CompatibilityFinding.Confidence.CONFIRMED, finding.confidence());
		assertFalse(finding.required(), "the diagnostic must not make the census-inferred loss blocking");
		assertTrue(CompatibilityFindings.confirmedRequired().isEmpty());
	}

	@Test void anOriginallyOptionalInjectorInADeadHostAlsoWarnsWithoutARequiredLoss() {
		prepare(MIXIN, HUD, true, 1, 0);
		String log = captureErrors(() -> observe(MIXIN, HUD, deadHost()));
		assertAttributed(log, MIXIN, HUD, HANDLER + DESC);
		assertTrue(warnings(log).getFirst().contains("extractHotbarAndDecorations"), log);
		assertTrue(CompatibilityFindings.confirmedRequired().isEmpty());
	}

	@Test void aRemovedInjectorWarnsOnceWithItsEvidenceTargetAndPreservesSeverity() {
		String detail = "the merge removed the injection's invocation anchor";
		String log = captureErrors(() -> {
			MixinCompatibility.recordRemovedInjector(CONFIG, MIXIN, HANDLER, DESC, detail, true,
					List.of("target=" + TARGET, "actual invocation absent"));
			MixinCompatibility.recordRemovedInjector(CONFIG, MIXIN, HANDLER, DESC, detail, true,
					List.of("target=" + TARGET, "actual invocation absent"));
		});
		assertEquals(1, warnings(log).size(), log);
		assertAttributed(log, MIXIN, TARGET, HANDLER + DESC);
		assertTrue(warnings(log).getFirst().contains(detail), log);
		assertEquals(1, CompatibilityFindings.confirmedRequired().size());
	}

	@Test void theEvidenceBoundaryWarnsOnceAboutAnUnresolvedConfirmedWholeMixinSkip() {
		MixinCompatibility.record(CONFIG, MIXIN, "mixin suppressed before application", CompatibilityFinding.Confidence.CONFIRMED,
				true, List.of("target=" + TARGET, "no final handler will be defined"));
		String log = captureErrors(() -> {
			KernelLoadReport.setRunDir(null);
			KernelLoadReport.writeEvidence();
			KernelLoadReport.writeEvidence();
		});
		assertEquals(1, warnings(log).size(), log);
		assertAttributed(log, MIXIN, TARGET, "<whole mixin>");
		assertTrue(warnings(log).getFirst().contains("mixin suppressed before application"), log);
		assertEquals(CompatibilityFinding.Confidence.CONFIRMED, whole(MIXIN).confidence());
		assertEquals(1, CompatibilityFindings.confirmedRequired().stream()
				.filter(f -> f.id().equals(MixinCompatibility.id(CONFIG, MIXIN))).count());
	}

	@Test void aResolvedWholeMixinDoesNotWarnAtTheEvidenceBoundary() {
		MixinCompatibility.record(CONFIG, MIXIN, "suppressed before the plugin decision", CompatibilityFinding.Confidence.CONFIRMED,
				true, List.of("target=" + TARGET));
		MixinCompatibility.resolve(CONFIG, MIXIN, "the plugin deliberately declined this mixin");
		String log = captureErrors(() -> {
			KernelLoadReport.setRunDir(null);
			KernelLoadReport.writeEvidence();
		});
		assertTrue(warnings(log).isEmpty(), log);
		assertEquals(CompatibilityFinding.Confidence.RESOLVED, whole(MIXIN).confidence());
	}

	@Test void aMerelySuspectedWholeMixinDoesNotWarnAsAConfirmedSkipAtTheEvidenceBoundary() {
		MixinCompatibility.record(CONFIG, MIXIN, "preflight anchors not yet proved", CompatibilityFinding.Confidence.SUSPECTED,
				true, List.of("target=" + TARGET));
		String log = captureErrors(() -> {
			KernelLoadReport.setRunDir(null);
			KernelLoadReport.writeEvidence();
		});
		assertTrue(warnings(log).isEmpty(), log);
		assertEquals(CompatibilityFinding.Confidence.SUSPECTED, whole(MIXIN).confidence());
	}

	@Test void aTwoCallLootBridgeDoesNotHideUnprovedSourceHandlers() {
		String replaced = "net.fabricmc.fabric.mixin.loot.ReloadableServerRegistriesMixin";
		String registry = "net.minecraft.server.ReloadableServerRegistries";
		prepare(replaced, registry, true, 1, -1);
		ClassNode target = target(replaced, registry, 0);
		MethodNode repair = new MethodNode(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC, "repair", DESC, null, null);
		for (String name : List.of("loadLootTable", "loadTagsForRegistry")) {
			repair.instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "net/forbric/kernel/runtime/KernelLootBridge", name, DESC, false));
		}
		repair.instructions.add(new InsnNode(Opcodes.RETURN));
		target.methods.add(repair);
		SupersededMixins.observeDefinition(registry, bytes(target));
		assertNull(SupersededMixins.provedReplacement(replaced), "two bridge calls do not prove the original source helper, provider projection and completion closure");
		String log = captureErrors(() -> observe(replaced, registry, target));
		assertEquals(1, warnings(log).size(), "an unproved source handler must remain visible\n" + log);
		assertFalse(CompatibilityFindings.confirmedRequired().isEmpty());
	}

	private static void prepare(String mixin, String target, boolean required, int minimum, int require) {
		String json = "{\"required\":" + required + ",\"mixins\":[\"" + mixin
				+ "\"],\"injectors\":{\"defaultRequire\":" + minimum + "}}";
		MixinCompatibility.rememberOriginalConfig(CONFIG, json.getBytes(StandardCharsets.UTF_8));
		FinalMixinApplications.remember(mixin(mixin, target, require));
	}

	private static ClassNode mixin(String binary, String target, int require) {
		ClassNode node = new ClassNode();
		node.name = binary.replace('.', '/');
		node.visibleAnnotations = List.of(annotation("Lorg/spongepowered/asm/mixin/Mixin;", "targets", List.of(target)));
		node.methods.add(injector(HANDLER, require));
		return node;
	}

	private static MethodNode injector(String name, int require) {
		MethodNode method = new MethodNode(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC, name, DESC, null, null);
		method.visibleAnnotations = new ArrayList<>(List.of(annotation("Lorg/spongepowered/asm/mixin/injection/Inject;", "require", require)));
		return method;
	}

	private static ClassNode target(String mixin, String binary, int calls) {
		ClassNode node = new ClassNode();
		node.version = Opcodes.V21;
		node.name = binary.replace('.', '/');
		node.superName = "java/lang/Object";
		node.access = Opcodes.ACC_PUBLIC;
		node.methods.add(mergedHandler(mixin, RENAMED));
		if (calls > 0) {
			MethodNode caller = new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "caller", DESC, null, null);
			for (int i = 0; i < calls; i++) caller.instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC, node.name, RENAMED, DESC, false));
			caller.instructions.add(new InsnNode(Opcodes.RETURN));
			node.methods.add(caller);
		}
		return node;
	}

	private static MethodNode mergedHandler(String mixin, String name) {
		MethodNode handler = new MethodNode(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC, name, DESC, null, null);
		handler.visibleAnnotations = List.of(annotation("Lorg/spongepowered/asm/mixin/transformer/meta/MixinMerged;", "mixin", mixin));
		handler.instructions.add(new InsnNode(Opcodes.RETURN));
		return handler;
	}

	private static ClassNode deadHost() {
		ClassNode target = target(MIXIN, HUD, 0);
		MethodNode host = new MethodNode(Opcodes.ACC_PUBLIC, "extractHotbarAndDecorations", HOTBAR_DESC, null, null);
		host.instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC, target.name, RENAMED, DESC, false));
		host.instructions.add(new InsnNode(Opcodes.RETURN));
		target.methods.add(host);
		return target;
	}

	private static void observe(String mixin, String target, ClassNode node) {
		FinalMixinApplications.observe(target, bytes(node), (owner, name, desc) -> List.of(
				new FinalMixinApplications.Renamed("handler$warning000$" + name, desc)));
	}

	private static byte[] bytes(ClassNode node) {
		ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
		node.accept(writer);
		return writer.toByteArray();
	}

	private static CompatibilityFinding whole(String mixin) {
		return CompatibilityFindings.all().stream().filter(f -> f.id().equals(MixinCompatibility.id(CONFIG, mixin)))
				.findFirst().orElseThrow();
	}

	private static String captureErrors(Runnable action) {
		PrintStream original = System.err;
		ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		try (PrintStream sink = new PrintStream(bytes, true, StandardCharsets.UTF_8)) {
			System.setErr(sink);
			action.run();
		} finally {
			System.setErr(original);
		}
		return bytes.toString(StandardCharsets.UTF_8);
	}

	private static List<String> warnings(String log) {
		return log.lines().filter(line -> line.contains("[Forbric/WARN]") && line.contains("injection warning:")
				&& line.contains(CONFIG)).toList();
	}

	private static void assertAttributed(String log, String mixin, String target, String handler) {
		assertTrue(warnings(log).stream().anyMatch(line -> line.contains(MOD) && line.contains(mixin)
				&& line.contains(target) && line.contains(handler)), "warning must identify mod, config, mixin, handler and target\n" + log);
	}

	private static AnnotationNode annotation(String desc, String key, Object value) {
		AnnotationNode annotation = new AnnotationNode(desc);
		annotation.values = new ArrayList<>(List.of(key, value));
		return annotation;
	}
}
