package net.forbric.kernel.mixin;

import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.*;

class MixinGroupConstraintsTest {
    private static final String TARGET = "example/Target", CI = "Lorg/spongepowered/asm/mixin/injection/callback/CallbackInfo;";
    @Test void aGroupWithNoBindingAlternativeFailsBeforeOtherMixinsAreApplied() {
        ClassNode target = target(false), mixin = mixin(1, -1, "missing", "alsoMissing");
        List<String> failures = failures(mixin,target);
        assertEquals(1, failures.size()); assertTrue(failures.getFirst().contains("at most 0 can bind"));
        mixin.name = "a/different/ModMixin";
        assertEquals(failures,failures(mixin,target));
    }
    @Test void oneBindingAlternativeSatisfiesTheNativeGroupMinimum() {
        assertTrue(failures(mixin(1,-1,"call","missing"),target(false)).isEmpty());
        assertEquals(1,failures(mixin(2,-1,"call","missing"),target(false)).size());
    }
    @Test void twoCertainCallbacksExceedTheNativeMaximum() {
        List<String> failures = failures(mixin(1,1,"call","call"),target(false));
        assertEquals(1,failures.size()); assertTrue(failures.getFirst().contains("at least 2 bind"));
    }
    @Test void ordinalsAndMultipleReturnPointsCountActualSites() {
        ClassNode mixin = mixin(2,-1,"call");
        assertEquals(1,failures(mixin,target(false)).size());
        assertTrue(failures(mixin,target(true)).isEmpty());
        MixinFit.atNodes(MixinFit.injectorOf(mixin.methods.getFirst())).getFirst().values.addAll(List.of("ordinal",1));
        assertEquals(1,failures(mixin,target(true)).size());
    }
    @Test void unknownSlicesCustomPointsAndLocalCaptureCannotProduceFalseProofs() {
        ClassNode mixin=mixin(1,-1,"missing");
        MixinFit.injectorOf(mixin.methods.getFirst()).values.addAll(List.of("slice",new AnnotationNode("Lorg/spongepowered/asm/mixin/injection/Slice;")));
        assertTrue(failures(mixin,target(false)).isEmpty());
        mixin=mixin(1,-1,"missing");
        MixinFit.atNodes(MixinFit.injectorOf(mixin.methods.getFirst())).getFirst().values.set(1,"CUSTOM_POINT");
        assertTrue(failures(mixin,target(false)).isEmpty());
        mixin=mixin(1,1,"call","call");
        for(MethodNode handler:mixin.methods) MixinFit.injectorOf(handler).values.addAll(List.of("locals",new String[]{"Lorg/spongepowered/asm/mixin/injection/callback/LocalCapture;","CAPTURE_FAILHARD"}));
        assertTrue(failures(mixin,target(false)).isEmpty());
    }
    @Test void theLargestDeclaredMinimumAndSmallestMaximumWinAsNativeMixinDoes() {
        ClassNode mixin=mixin(1,-1,"call","missing");
        mixin.methods.getLast().invisibleAnnotations.getFirst().values.set(3,2);
        assertEquals(1,failures(mixin,target(false)).size());
    }
    private List<String> failures(ClassNode mixin,ClassNode target) {
        return MixinGroupConstraints.failures(mixin,path -> path.equals(TARGET+".class") ? StagedFabricMixinFixture.bytes(target) : null);
    }
    private ClassNode mixin(int min,int max,String... calls) {
        ClassNode mixin=new ClassNode(); mixin.name="vendor/AnyMixin";
        mixin.visibleAnnotations=List.of(annotation("Lorg/spongepowered/asm/mixin/Mixin;","value",List.of(Type.getObjectType(TARGET))));
        for(int i=0;i<calls.length;i++) {
            MethodNode method=new MethodNode(Opcodes.ACC_PRIVATE,"callback"+i,"("+CI+")V",null,null);
            method.visibleAnnotations=List.of(annotation("Lorg/spongepowered/asm/mixin/injection/Inject;","method",List.of("host"),"at",
                    List.of(annotation("Lorg/spongepowered/asm/mixin/injection/At;","value","INVOKE","target","Lexample/Calls;"+calls[i]+"()V"))));
            method.invisibleAnnotations=List.of(annotation("Lorg/spongepowered/asm/mixin/injection/Group;","name","alternatives","min",min,"max",max));
            mixin.methods.add(method);
        }
        return mixin;
    }
    private ClassNode target(boolean twice) {
        ClassNode target=new ClassNode(); target.name=TARGET; target.version=Opcodes.V21; target.superName="java/lang/Object";
        MethodNode host=new MethodNode(Opcodes.ACC_PUBLIC,"host","()V",null,null);
        host.instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC,"example/Calls","call","()V",false));
        if(twice)host.instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC,"example/Calls","call","()V",false));
        host.instructions.add(new InsnNode(Opcodes.RETURN)); target.methods.add(host); return target;
    }
    private AnnotationNode annotation(String desc,Object... values) { AnnotationNode a=new AnnotationNode(desc); a.values=new ArrayList<>(List.of(values)); return a; }
}
