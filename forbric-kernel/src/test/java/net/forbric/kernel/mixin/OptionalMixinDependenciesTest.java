package net.forbric.kernel.mixin;

import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import net.forbric.api.UnifiedDependency;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.tree.*;

class OptionalMixinDependenciesTest {
    private ClassNode mixin() {
        ClassNode node = new ClassNode(); node.name = "unrelated/CompatMixin";
        MethodNode method = new MethodNode(); method.name = "owner$fix";
        AnnotationNode inject = new AnnotationNode("Lorg/spongepowered/asm/mixin/injection/Inject;");
        inject.values = new ArrayList<>(List.of("method", List.of("otherMod$shouldCache")));
        method.visibleAnnotations = new ArrayList<>(List.of(inject)); node.methods.add(method); return node;
    }
    private List<UnifiedDependency> optional() { return List.of(new UnifiedDependency("other-mod", "*", false)); }
    @Test void aDeclaredAbsentOptionalIntegrationHasNoRequiredFeatureLoss() {
        assertEquals("other-mod", OptionalMixinDependencies.absent(mixin(), optional(), id -> false));
        ClassNode renamed = mixin(); renamed.name = "another/vendor/UnrelatedName";
        assertEquals("other-mod", OptionalMixinDependencies.absent(renamed, optional(), id -> false));
    }
    @Test void anInstalledRequiredOrUndeclaredPartnerStillReportsRealFailures() {
        assertNull(OptionalMixinDependencies.absent(mixin(), optional(), id -> true));
        assertNull(OptionalMixinDependencies.absent(mixin(), List.of(new UnifiedDependency("other-mod", "*", true)), id -> false));
        assertNull(OptionalMixinDependencies.absent(mixin(), List.of(), id -> false));
        assertNull(OptionalMixinDependencies.absent("unclaimed.mixins.json", mixin(), id -> false));
    }
    @Test void mixedFeaturesFieldsInterfacesAndGroupsCannotBorrowTheExemption() {
        ClassNode node = mixin(); MethodNode helper = new MethodNode(); helper.name = "doOtherWork"; node.methods.add(helper);
        assertNull(OptionalMixinDependencies.absent(node, optional(), id -> false));
        node = mixin(); node.fields.add(new FieldNode(0,"state","Z",null,null));
        assertNull(OptionalMixinDependencies.absent(node, optional(), id -> false));
        node = mixin(); node.interfaces.add("some/Contract");
        assertNull(OptionalMixinDependencies.absent(node, optional(), id -> false));
        node = mixin(); node.methods.getFirst().visibleAnnotations.add(new AnnotationNode("Lorg/spongepowered/asm/mixin/injection/Group;"));
        assertNull(OptionalMixinDependencies.absent(node, optional(), id -> false));
        node = mixin(); MixinFit.injectorOf(node.methods.getFirst()).values.set(1,List.of("otherMod$shouldCache","destroyBlock"));
        assertNull(OptionalMixinDependencies.absent(node, optional(), id -> false));
    }
}
