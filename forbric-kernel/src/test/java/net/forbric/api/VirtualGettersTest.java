package net.forbric.api;
import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.*;
class VirtualGettersTest implements Opcodes {
    @Test void selectsReturnDescriptorsAndRetainsVirtualDispatch()throws Exception{
        Loader loader=new Loader();Class<?> base=loader.add("unknown.Values",type("unknown/Values","java/lang/Object",false));
        Object receiver=base.getConstructor().newInstance();assertEquals("base",VirtualGetters.get(base,"value",String.class,receiver));
        assertEquals("builder",VirtualGetters.get(base,"value",StringBuilder.class,receiver).toString());
        Class<?> child=loader.add("unknown.Override",type("unknown/Override","unknown/Values",true));Object override=child.getConstructor().newInstance();
        assertEquals("override",VirtualGetters.get(base,"value",String.class,override));assertEquals("builder",VirtualGetters.get(base,"value",StringBuilder.class,override).toString());
        assertEquals(7,VirtualGetters.get(base,"count",int.class,receiver));
    }
    @Test void missingDescriptorAndNullReceiverFailPrecisely()throws Exception{
        Class<?> base=new Loader().add("unknown.Values",type("unknown/Values","java/lang/Object",false));Object receiver=base.getConstructor().newInstance();
        assertThrows(NoSuchMethodError.class,()->VirtualGetters.get(base,"value",Integer.class,receiver));
        assertThrows(NullPointerException.class,()->VirtualGetters.get(base,"value",String.class,null));
    }
    private static class Loader extends ClassLoader {Class<?> add(String name,byte[] b){return defineClass(name,b,0,b.length);}}
    private static byte[] type(String name,String parent,boolean override){
        ClassWriter w=new ClassWriter(ClassWriter.COMPUTE_MAXS);w.visit(V21,ACC_PUBLIC,name,null,parent,null);
        MethodVisitor m=w.visitMethod(ACC_PUBLIC,"<init>","()V",null,null);m.visitCode();m.visitVarInsn(ALOAD,0);m.visitMethodInsn(INVOKESPECIAL,parent,"<init>","()V",false);m.visitInsn(RETURN);m.visitMaxs(0,0);m.visitEnd();
        m=w.visitMethod(ACC_PUBLIC,"value","()Ljava/lang/String;",null,null);m.visitCode();m.visitLdcInsn(override?"override":"base");m.visitInsn(ARETURN);m.visitMaxs(0,0);m.visitEnd();
        if(!override){m=w.visitMethod(ACC_PUBLIC,"value","()Ljava/lang/StringBuilder;",null,null);m.visitCode();m.visitTypeInsn(NEW,"java/lang/StringBuilder");m.visitInsn(DUP);m.visitLdcInsn("builder");m.visitMethodInsn(INVOKESPECIAL,"java/lang/StringBuilder","<init>","(Ljava/lang/String;)V",false);m.visitInsn(ARETURN);m.visitMaxs(0,0);m.visitEnd();
            m=w.visitMethod(ACC_PUBLIC,"count","()I",null,null);m.visitCode();m.visitIntInsn(BIPUSH,7);m.visitInsn(IRETURN);m.visitMaxs(0,0);m.visitEnd();}
        w.visitEnd();return w.toByteArray();
    }
}
