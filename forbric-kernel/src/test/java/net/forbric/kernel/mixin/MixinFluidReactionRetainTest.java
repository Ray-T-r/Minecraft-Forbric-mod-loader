package net.forbric.kernel.mixin;
import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.tree.*;
import org.objectweb.asm.tree.analysis.*;

/** The handler a fluid-reaction wrapper keeps is {@code @Unique}, so a second mod's handler of the same name is renamed, not skipped. */
class MixinFluidReactionRetainTest {
	/**
	 * fabric-block-api and Create Fly both keep a {@code shouldSpreadLiquid$forbricOriginal} in LiquidBlock; only
	 * {@code @Unique} lets Mixin rename the second instead of skipping it. FluidPairMixinAdaptersWeaveTest weaves both.
	 */
	@Test void theRetainedOriginalIsUniqueSoASecondModsOriginalIsRenamedNotSkipped() {
		MethodNode original=new MethodNode(org.objectweb.asm.Opcodes.ACC_PRIVATE,"shouldSpreadLiquid","()V",null,null);
		AnnotationNode inject=new AnnotationNode("Lorg/spongepowered/asm/mixin/injection/Inject;");
		AnnotationNode kept=new AnnotationNode("Lorg/spongepowered/asm/mixin/Dynamic;");
		original.visibleAnnotations=new java.util.ArrayList<>(java.util.List.of(kept,inject));
		MixinFluidReactionAdapter.retainOriginal(original,inject);
		assertEquals("shouldSpreadLiquid$forbricOriginal",original.name);
		assertEquals(java.util.List.of("Lorg/spongepowered/asm/mixin/Dynamic;","Lorg/spongepowered/asm/mixin/Unique;"),
				original.visibleAnnotations.stream().map(a->a.desc).toList());
		assertNull(original.invisibleAnnotations);
	}
}
