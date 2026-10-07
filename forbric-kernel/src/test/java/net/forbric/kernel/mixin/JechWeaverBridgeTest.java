package net.forbric.kernel.mixin;

import static org.junit.jupiter.api.Assertions.*;
import java.lang.reflect.Proxy;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.spongepowered.asm.mixin.transformer.IMixinTransformer;

@ResourceLock("system-properties")
class JechWeaverBridgeTest {
    @AfterEach void reset() { MixinWeaverSlot.reset(); }
    private static IMixinTransformer transformer() {
        return (IMixinTransformer)Proxy.newProxyInstance(JechWeaverBridgeTest.class.getClassLoader(),new Class<?>[]{IMixinTransformer.class},(p,m,a)->null);
    }
    @Test void reflectionReplacementUsesTheCurrentDecoratorAndIsReadBackByThePipeline() throws Exception {
        var original=transformer();var earlier=transformer();var replacement=transformer();
        MixinWeaverSlot.install(original);MixinWeaverSlot.watch(()->earlier);
        var holder=ReflectiveWeaverBridge.holder();assertSame(earlier,holder.mixinTransformer);
        ReflectiveWeaverBridge.field().set(holder,replacement);
        assertSame(replacement,MixinWeaverSlot.currentOr(original));
    }
}
