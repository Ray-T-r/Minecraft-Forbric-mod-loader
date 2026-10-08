package net.forbric.kernel.mixin;

import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import net.forbric.api.Ecosystem;

/** Unknown classes prove extraction; effects after the original call must never reorder a callback. */
class MixinRetargetAbsorbedCallTest {
    static final String OWNER="unknown/Processor", HELPER="unknown/Carrier", BOX="unknown/Box";
    static final String DESC="(L"+BOX+";I)V", MEMBER="L"+BOX+";set(I)V", HOOK="L"+HELPER+";apply"+DESC;
    @AfterEach void reset(){System.clearProperty(MergedBaseAbsorbedCalls.PROPERTY);MixinStubRebind.forget();MixinRetarget.reset();}
    @Test void arbitraryExtractionPreservesTheFinalEffectAndActualOperand(){
        Map<String,byte[]> current=current(false,false);byte[] source=target(false);
        var plan=plan(current,source);
        assertEquals(1,plan.rewrites().size(),plan.describe());assertEquals(HOOK,plan.rewrites().getFirst().to());
        assertEquals(MixinFit.Verdict.FIT,MixinFit.evaluate(MixinRetarget.rewritten(mixin(),plan),p->current.get(p)).verdict());
    }
    @Test void laterEffectsDoNotAuthorizeMovingACallbackPastThem(){assertTrue(plan(current(true,false),target(false)).isEmpty());}
    @Test void changedOperandAndMissingNativeEvidenceAreRejected(){
        assertTrue(plan(current(false,true),target(false)).isEmpty());assertTrue(plan(current(false,false),null).isEmpty());
    }
    @Test void switchDisablesOnlyTheProvedRule(){System.setProperty(MergedBaseAbsorbedCalls.PROPERTY,"off");assertTrue(plan(current(false,false),target(false)).isEmpty());}
    @Test void anInstanceObserverCannotBindAStaticHost()throws Exception{
        ClassNode observer=MixinFit.parse(mixin());MethodNode handler=observer.methods.getFirst();handler.access&=~Opcodes.ACC_STATIC;handler.maxLocals++;
        String original=MixinInstructionFingerprint.hash(handler);ClassNode source=MixinFit.parse(target(false)),live=MixinFit.parse(target(true)),carrier=MixinFit.parse(helper(false,false));
        try(var loader=new net.forbric.kernel.classloading.ForbricClassLoader(new java.net.URL[0],getClass().getClassLoader())){
            assertEquals(0,MixinAbsorbedCallbackTransport.adapt(observer,owner->owner.equals(OWNER)?live:owner.equals(HELPER)?carrier:null,owner->owner.equals(OWNER)?source:null,loader));
            assertEquals(original,MixinInstructionFingerprint.hash(handler));assertNotNull(MixinFit.injectorOf(handler),"a refused callback retains its original source annotation");
        }
    }
    private static MixinRetarget.Plan plan(Map<String,byte[]> current,byte[] source){
        MixinStubRebind.noteEcosystem("unknown/Subscriber",Ecosystem.FABRIC);
        return MixinRetarget.plan(MixinFit.parse(mixin()),current::get,(family,owner)->family==Ecosystem.FABRIC&&owner.equals(OWNER)&&source!=null?MixinFit.parse(source):null);
    }
    private static Map<String,byte[]> current(boolean extra,boolean wrong){return Map.of(OWNER+".class",target(true),HELPER+".class",helper(extra,wrong));}
    static byte[] target(boolean extracted){
        ClassWriter w=new ClassWriter(ClassWriter.COMPUTE_MAXS);w.visit(Opcodes.V21,Opcodes.ACC_PUBLIC,OWNER,null,"java/lang/Object",null);
        MethodVisitor m=w.visitMethod(Opcodes.ACC_PUBLIC|Opcodes.ACC_STATIC,"process",DESC,null,null);m.visitCode();m.visitVarInsn(Opcodes.ALOAD,0);m.visitVarInsn(Opcodes.ILOAD,1);
        m.visitMethodInsn(extracted?Opcodes.INVOKESTATIC:Opcodes.INVOKEVIRTUAL,extracted?HELPER:BOX,extracted?"apply":"set",extracted?DESC:"(I)V",false);m.visitInsn(Opcodes.RETURN);m.visitMaxs(0,0);m.visitEnd();w.visitEnd();return w.toByteArray();
    }
    static byte[] helper(boolean extra,boolean wrong){
        ClassWriter w=new ClassWriter(ClassWriter.COMPUTE_MAXS);w.visit(Opcodes.V21,Opcodes.ACC_PUBLIC,HELPER,null,"java/lang/Object",null);
        MethodVisitor m=w.visitMethod(Opcodes.ACC_PUBLIC|Opcodes.ACC_STATIC,"apply",DESC,null,null);m.visitCode();m.visitVarInsn(Opcodes.ALOAD,0);m.visitVarInsn(Opcodes.ILOAD,1);
        if(wrong){m.visitInsn(Opcodes.ICONST_1);m.visitInsn(Opcodes.IADD);}m.visitMethodInsn(Opcodes.INVOKEVIRTUAL,BOX,"set","(I)V",false);
        if(extra){m.visitVarInsn(Opcodes.ALOAD,0);m.visitMethodInsn(Opcodes.INVOKEVIRTUAL,BOX,"post","()V",false);}m.visitInsn(Opcodes.RETURN);m.visitMaxs(0,0);m.visitEnd();w.visitEnd();return w.toByteArray();
    }
    static byte[] mixin(){
        ClassWriter w=new ClassWriter(0);w.visit(Opcodes.V21,Opcodes.ACC_PUBLIC,"unknown/Subscriber",null,"java/lang/Object",null);
        AnnotationVisitor a=w.visitAnnotation("Lorg/spongepowered/asm/mixin/Mixin;",false),v=a.visitArray("value");v.visit(null,Type.getObjectType(OWNER));v.visitEnd();a.visitEnd();
        MethodVisitor m=w.visitMethod(Opcodes.ACC_PRIVATE|Opcodes.ACC_STATIC,"after",DESC.substring(0,DESC.length()-2)+MixinRetarget.CALLBACK_INFO+")V",null,null);
        a=m.visitAnnotation("Lorg/spongepowered/asm/mixin/injection/Inject;",true);v=a.visitArray("method");v.visit(null,"process"+DESC);v.visitEnd();v=a.visitArray("at");AnnotationVisitor at=v.visitAnnotation(null,"Lorg/spongepowered/asm/mixin/injection/At;");at.visit("value","INVOKE");at.visit("target",MEMBER);at.visitEnum("shift","Lorg/spongepowered/asm/mixin/injection/At$Shift;","AFTER");at.visitEnd();v.visitEnd();a.visitEnd();
        m.visitCode();m.visitInsn(Opcodes.RETURN);m.visitMaxs(0,3);m.visitEnd();w.visitEnd();return w.toByteArray();
    }
}
