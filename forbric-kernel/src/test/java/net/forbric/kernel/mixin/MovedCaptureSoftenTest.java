package net.forbric.kernel.mixin;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.tree.AnnotationNode;

/**
 * A capture MixinGuiItemCaptureAdapter moves may still fail where it gets no NOP to land on (0.9.2, a BY-shifted point
 * in the method). Mixin answers a failed CAPTURE_FAILEXCEPTION with a method that throws InjectionError each time the
 * point is reached, so the adapter softens it to CAPTURE_FAILSOFT: skipped with a WARN instead. Nothing else changes.
 */
class MovedCaptureSoftenTest {
	private static final String LOCAL_CAPTURE = "Lorg/spongepowered/asm/mixin/injection/callback/LocalCapture;";

	@Test void aMovedFailExceptionCaptureBecomesSoft() {
		AnnotationNode inject = inject("CAPTURE_FAILEXCEPTION");
		assertTrue(MixinGuiItemCaptureAdapter.softenFailException(inject));
		assertArrayEquals(new String[] {LOCAL_CAPTURE, "CAPTURE_FAILSOFT"}, (String[]) inject.values.get(inject.values.indexOf("locals") + 1));
	}

	@Test void otherCaptureModesAndTheRestOfTheAnnotationAreLeftAlone() {
		for (String mode : List.of("CAPTURE_FAILSOFT", "CAPTURE_FAILHARD", "PRINT", "NO_CAPTURE")) {
			AnnotationNode inject = inject(mode);
			assertFalse(MixinGuiItemCaptureAdapter.softenFailException(inject), mode);
			assertEquals(mode, ((String[]) inject.values.get(inject.values.indexOf("locals") + 1))[1]);
			assertEquals(List.of("method", List.of("render")), inject.values.subList(0, 2), mode);
		}
		assertFalse(MixinGuiItemCaptureAdapter.softenFailException(null));
		assertFalse(MixinGuiItemCaptureAdapter.softenFailException(new AnnotationNode("Lorg/spongepowered/asm/mixin/injection/Inject;")));
	}

	private static AnnotationNode inject(String mode) {
		AnnotationNode inject = new AnnotationNode("Lorg/spongepowered/asm/mixin/injection/Inject;");
		inject.values = new ArrayList<>(List.of("method", List.of("render"), "locals", new String[] {LOCAL_CAPTURE, mode}));
		return inject;
	}
}
