package net.forbric.kernel.mixin;

import static org.junit.jupiter.api.Assertions.*;

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
import org.objectweb.asm.tree.VarInsnNode;

import net.forbric.api.CompatibilityFinding;
import net.forbric.api.CompatibilityFindings;
import net.forbric.api.Ecosystem;

/**
 * The final-class ledger does not count a handler called only from a method nothing in the merged game calls as
 * attached: Better Mount HUD's XP redirect, merged into {@code Hud.extractHotbarAndDecorations}, whose vanilla caller
 * NeoForge's HUD layers replaced. Without this the whole-mixin suspicion was discharged and the load report had no
 * "partly did not run" line for a hook that never fires.
 */
@ResourceLock("ModCatalog") @ResourceLock("system-properties")
class FinalMixinApplicationsLivenessTest {
	private static final String CONFIG = "bettermounthud.mixins.json", MIXIN = "me.lortseam.bettermounthud.mixin.HudMixin";
	private static final String HUD = "net/minecraft/client/gui/Hud";
	private static final String G = "(Lnet/minecraft/client/gui/GuiGraphicsExtractor;Lnet/minecraft/client/DeltaTracker;)V";
	private static final String HANDLER = "redirect$zzz000$bettermounthud$hideXp", HANDLER_DESC = "(Ljava/lang/Object;)Z";

	@BeforeEach @AfterEach
	void reset() {
		System.clearProperty(MixinFit.LIVENESS_PROPERTY);
		MergedBaseUncalledMethods.forgetGuests();
		MixinCompatibility.reset();
		CompatibilityFindings.reset();
		MixinConfigOwners.publish(List.of(new MixinConfigOwners.Owned(CONFIG, "bettermounthud", Ecosystem.FABRIC)));
	}

	@Test
	void aRedirectCalledOnlyFromAnUncalledMethodIsAConfirmedButNonBlockingLoss() {
		prepare();
		observe(hud(false));
		CompatibilityFinding injector = injector();
		assertEquals(CompatibilityFinding.Confidence.CONFIRMED, injector.confidence(), injector.toString());
		assertFalse(injector.required(), "inferred from the census, so it marks the row and asks nothing");
		assertTrue(injector.detail().contains("attached only in Hud.extractHotbarAndDecorations (nothing in the merged game calls it"),
				injector.detail());
		assertTrue(CompatibilityFindings.confirmedRequired().isEmpty());
		assertEquals(CompatibilityFinding.Confidence.SUSPECTED, whole().confidence(), "a handler that never runs discharges nothing");
	}

	@Test
	void withTheSwitchOffTheSameCallIsAnAttachment() {
		System.setProperty(MixinFit.LIVENESS_PROPERTY, "off");
		prepare();
		observe(hud(false));
		assertTrue(CompatibilityFindings.all().stream().noneMatch(f -> f.id().startsWith("mixin-injector:")
				&& f.confidence() != CompatibilityFinding.Confidence.RESOLVED), CompatibilityFindings.all().toString());
		assertEquals(CompatibilityFinding.Confidence.RESOLVED, whole().confidence());
	}

	/** Mixin or a kernel repair calling the method again from its own class makes it live. */
	@Test
	void aCallerInTheFinalClassMakesItAnAttachmentAgain() {
		prepare();
		observe(hud(true));
		assertEquals(CompatibilityFinding.Confidence.RESOLVED, whole().confidence(), CompatibilityFindings.all().toString());
	}

	// ---------------------------------------------------------------------------------------------------------------

	private void prepare() {
		MixinCompatibility.rememberOriginalConfig(CONFIG, ("{\"required\":true,\"package\":\"me.lortseam.bettermounthud.mixin\","
				+ "\"client\":[\"HudMixin\"],\"injectors\":{\"defaultRequire\":1}}").getBytes(StandardCharsets.UTF_8));
		ClassNode mixin = new ClassNode();
		mixin.name = MIXIN.replace('.', '/');
		mixin.visibleAnnotations = List.of(annotation("Lorg/spongepowered/asm/mixin/Mixin;", "targets", List.of(HUD.replace('/', '.'))));
		MethodNode hide = new MethodNode(Opcodes.ACC_PRIVATE, "bettermounthud$hideXp", HANDLER_DESC, null, null);
		hide.visibleAnnotations = new ArrayList<>(List.of(new AnnotationNode("Lorg/spongepowered/asm/mixin/injection/Redirect;")));
		mixin.methods.add(hide);
		FinalMixinApplications.remember(mixin);
		MixinCompatibility.record(CONFIG, MIXIN, "10/11 anchors resolve", CompatibilityFinding.Confidence.SUSPECTED, true, List.of("preflight"));
	}

	/** What Mixin leaves: the redirect's merged handler called from extractHotbarAndDecorations, which extractRenderState may call. */
	private static ClassNode hud(boolean renderCallsHotbar) {
		ClassNode hud = new ClassNode();
		hud.version = Opcodes.V21;
		hud.access = Opcodes.ACC_PUBLIC;
		hud.name = HUD;
		hud.superName = "java/lang/Object";
		MethodNode handler = new MethodNode(Opcodes.ACC_PRIVATE, HANDLER, HANDLER_DESC, null, null);
		handler.visibleAnnotations = List.of(annotation("Lorg/spongepowered/asm/mixin/transformer/meta/MixinMerged;", "mixin", MIXIN));
		handler.instructions.add(new InsnNode(Opcodes.ICONST_0));
		handler.instructions.add(new InsnNode(Opcodes.IRETURN));
		hud.methods.add(handler);
		MethodNode hotbar = new MethodNode(Opcodes.ACC_PUBLIC, "extractHotbarAndDecorations", G, null, null);
		hotbar.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
		hotbar.instructions.add(new InsnNode(Opcodes.ACONST_NULL));
		hotbar.instructions.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, HUD, HANDLER, HANDLER_DESC, false));
		hotbar.instructions.add(new InsnNode(Opcodes.POP));
		hotbar.instructions.add(new InsnNode(Opcodes.RETURN));
		hud.methods.add(hotbar);
		MethodNode render = new MethodNode(Opcodes.ACC_PUBLIC, "extractRenderState", G, null, null);
		if (renderCallsHotbar) {
			render.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
			render.instructions.add(new VarInsnNode(Opcodes.ALOAD, 1));
			render.instructions.add(new VarInsnNode(Opcodes.ALOAD, 2));
			render.instructions.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, HUD, "extractHotbarAndDecorations", G, false));
		}
		render.instructions.add(new InsnNode(Opcodes.RETURN));
		hud.methods.add(render);
		return hud;
	}

	private static void observe(ClassNode hud) {
		ClassWriter w = new ClassWriter(ClassWriter.COMPUTE_MAXS);
		hud.accept(w);
		FinalMixinApplications.observe(HUD.replace('/', '.'), w.toByteArray(),
				(mixin, name, desc) -> List.of(new FinalMixinApplications.Renamed(HANDLER, desc)));
	}

	private static CompatibilityFinding injector() {
		return CompatibilityFindings.all().stream().filter(f -> f.id().startsWith("mixin-injector:")).findFirst()
				.orElseThrow(() -> new AssertionError(CompatibilityFindings.all().toString()));
	}

	private static CompatibilityFinding whole() {
		return CompatibilityFindings.all().stream().filter(f -> f.id().equals(MixinCompatibility.id(CONFIG, MIXIN))).findFirst().orElseThrow();
	}

	private static AnnotationNode annotation(String desc, String key, Object value) {
		AnnotationNode a = new AnnotationNode(desc);
		a.values = new ArrayList<>(List.of(key, value));
		return a;
	}
}
