package net.forbric.kernel.mixin;
import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

class OperationCallGraphTest {
    @Test void actualScreenReceiverAndArgumentsIdentifyTheTopInvocationAcrossTheInheritedGateway()throws Exception {
        String owner="net/minecraft/client/gui/Gui",member="Lnet/minecraft/client/gui/screens/Screen;extractRenderStateWithTooltipAndSubtitles(Lnet/minecraft/client/gui/GuiGraphicsExtractor;IIF)V";
        var source=StagedFabricMixinFixture.game(owner,true);var target=StagedFabricMixinFixture.game(owner,false);
        var plan=OperationCallGraph.derive(source,target,"extractRenderState",member,CarpetMixinAdapterTest::target,NativeCallTestEvidence.staged());
        assertNotNull(plan,"the actual source draw must be conserved through the carrier's top-screen operand");
        assertEquals("net/minecraftforge/client/gui/overlay/ForgeLayerInstance",plan.firstOwner());assertEquals(1,plan.forwardOrdinal());assertEquals(member,plan.member());assertFalse(plan.getters().isEmpty());
    }
}
