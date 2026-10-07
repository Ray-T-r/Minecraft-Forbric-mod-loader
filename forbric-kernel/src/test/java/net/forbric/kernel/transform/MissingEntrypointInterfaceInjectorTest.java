package net.forbric.kernel.transform;

import static org.junit.jupiter.api.Assertions.*;
import java.util.List;
import net.forbric.api.*;
import net.forbric.kernel.interop.ConfigEntrypointInitializer;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.objectweb.asm.*;

@ExecutesInjector(MissingEntrypointInterfaceInjector.class)

class MissingEntrypointInterfaceInjectorTest {
    private static final String CONTRACT="example/api/UnknownConfigInitializer";
    @Test void absentDeclaredContractIsExecutableForUnknownOwnersAndPresentApiWins() throws Exception {
        ClassWriter w=new ClassWriter(0);String name="fixture/ConfigEntry";
        w.visit(Opcodes.V17,Opcodes.ACC_PUBLIC,name,null,"java/lang/Object",new String[]{CONTRACT});
        w.visitField(Opcodes.ACC_PUBLIC,"calls","I",null,null).visitEnd();
        var init=w.visitMethod(Opcodes.ACC_PUBLIC,"<init>","()V",null,null);init.visitCode();init.visitVarInsn(Opcodes.ALOAD,0);init.visitMethodInsn(Opcodes.INVOKESPECIAL,"java/lang/Object","<init>","()V",false);init.visitInsn(Opcodes.RETURN);init.visitMaxs(1,1);init.visitEnd();
        var m=w.visitMethod(Opcodes.ACC_PUBLIC,"onInitializeConfig","()V",null,null);m.visitCode();m.visitVarInsn(Opcodes.ALOAD,0);m.visitInsn(Opcodes.DUP);m.visitFieldInsn(Opcodes.GETFIELD,name,"calls","I");m.visitInsn(Opcodes.ICONST_1);m.visitInsn(Opcodes.IADD);m.visitFieldInsn(Opcodes.PUTFIELD,name,"calls","I");m.visitInsn(Opcodes.RETURN);m.visitMaxs(3,1);m.visitEnd();w.visitEnd();
        byte[] original=w.toByteArray();var present=new MissingEntrypointInterfaceInjector(CONTRACT,ConfigEntrypointInitializer.class,()->true);assertSame(original,present.transform(name,original,null));
        var injector=new MissingEntrypointInterfaceInjector(CONTRACT,ConfigEntrypointInitializer.class,()->false);
        byte[] repaired=injector.transform(name,original,null);assertNotSame(original,repaired);assertSame(repaired,injector.transform(name,repaired,null));
        class Loader extends ClassLoader { Class<?> define(byte[] b){return defineClass("fixture.ConfigEntry",b,0,b.length);} }
        Class<?> entry=new Loader().define(repaired);Object instance=entry.getConstructor().newInstance();
        ((ConfigEntrypointInitializer)instance).onInitializeConfig();assertEquals(1,entry.getField("calls").get(instance));
    }
    @Test void aConsumerWithoutTheExecutableMethodIsRefused() {
        ClassWriter w=new ClassWriter(0);w.visit(Opcodes.V17,Opcodes.ACC_PUBLIC,"fixture/WrongEntry",null,"java/lang/Object",new String[]{CONTRACT});w.visitEnd();
        byte[] bytes=w.toByteArray();assertSame(bytes,new MissingEntrypointInterfaceInjector(CONTRACT,ConfigEntrypointInitializer.class,()->false).transform("fixture.WrongEntry",bytes,null));
    }
}
