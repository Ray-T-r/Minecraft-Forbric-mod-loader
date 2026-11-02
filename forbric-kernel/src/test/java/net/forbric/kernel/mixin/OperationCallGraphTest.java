package net.forbric.kernel.mixin;
import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

/**
 * NeoForge's Gui draws the top screen through {@code ClientHooks.extractScreen}, which draws each layered screen and then
 * the top one through {@code extractScreenInternal} (javap: its second call, ordinal 1); the source draw is that last call.
 */
class OperationCallGraphTest {
    @Test void actualScreenReceiverAndArgumentsIdentifyTheTopInvocationAcrossTheInheritedGateway()throws Exception {
        String owner="net/minecraft/client/gui/Gui",member="Lnet/minecraft/client/gui/screens/Screen;extractRenderStateWithTooltipAndSubtitles(Lnet/minecraft/client/gui/GuiGraphicsExtractor;IIF)V";
        var source=StagedFabricMixinFixture.game(owner,true);var target=StagedFabricMixinFixture.game(owner,false);
        var plan=OperationCallGraph.derive(source,target,"extractRenderState",member,CarpetMixinAdapterTest::target,NativeCallTestEvidence.staged());
        assertNotNull(plan,"the actual source draw must be conserved through the carrier's top-screen operand");
        assertEquals("net/neoforged/neoforge/client/ClientHooks",plan.firstOwner());
        assertEquals("extractScreen(Lnet/minecraft/client/gui/screens/Screen;Ljava/util/Stack;Lnet/minecraft/client/gui/GuiGraphicsExtractor;IIF)V",plan.firstMethod());
        assertEquals("extractScreenInternal(Lnet/minecraft/client/gui/screens/Screen;Lnet/minecraft/client/gui/GuiGraphicsExtractor;IIF)V",plan.helperMethod());
        assertEquals(1,plan.forwardOrdinal(),"the top screen's draw, after the layered screens' one");assertEquals(member,plan.member());assertFalse(plan.getters().isEmpty());
    }
}
