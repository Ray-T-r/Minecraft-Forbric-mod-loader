package net.forbric.api;
import static org.junit.jupiter.api.Assertions.*;
import java.net.URL;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.*;
import net.forbric.kernel.classloading.ForbricClassLoader;
class VirtualPropertiesTest implements Opcodes {
    @Test void arbitraryFinalGetterFieldCanBeWrittenWithoutAFieldNameInTheCaller()throws Exception{
        try(var loader=new ForbricClassLoader(new URL[0],getClass().getClassLoader())){
            Class<?> type=loader.defineRuntimeClass("unknown.State",type("unknown/State",false,false));Object state=type.getConstructor().newInstance();
            VirtualProperties.writer(type,"value",String.class,state).accept("changed");assertEquals("changed",VirtualGetters.get(type,"value",String.class,state));
        }
    }
    @Test void finalMixinChangedGetterCannotBeTreatedAsItsRawField()throws Exception{
        try(var loader=new ForbricClassLoader(new URL[0],getClass().getClassLoader())){
            Class<?> type=loader.defineRuntimeClass("unknown.Opaque",type("unknown/Opaque",true,false));Object state=type.getConstructor().newInstance();
            assertThrows(IllegalStateException.class,()->VirtualProperties.writer(type,"value",String.class,state));
        }
    }
    @Test void immutableFieldAndMissingDefinitionProofAreRejected()throws Exception{
        try(var loader=new ForbricClassLoader(new URL[0],getClass().getClassLoader())){
            Class<?> type=loader.defineRuntimeClass("unknown.Immutable",type("unknown/Immutable",false,true));Object state=type.getConstructor().newInstance();
            assertThrows(IllegalStateException.class,()->VirtualProperties.writer(type,"value",String.class,state));
        }
        assertThrows(IllegalStateException.class,()->VirtualProperties.writer(StringBuilder.class,"toString",String.class,new StringBuilder()));
    }
    @Test void equalLoadersCannotShareOrOverwriteDefinitionProofs()throws Exception{
        class EqualLoader extends ClassLoader {
            EqualLoader(){super(VirtualPropertiesTest.class.getClassLoader());}
            Class<?> add(){byte[] bytes=type("unknown/EqualState",false,false);return defineClass("unknown.EqualState",bytes,0,bytes.length);}
            @Override public boolean equals(Object other){return other instanceof EqualLoader;}
            @Override public int hashCode(){return 1;}
        }
        EqualLoader first=new EqualLoader(),second=new EqualLoader();Class<?> a=first.add(),b=second.add();
        VirtualProperties.observe(first,"unknown/EqualState",java.util.Map.of("value()Ljava/lang/String;",new VirtualProperties.Field("unknown/EqualState","randomBacking","Ljava/lang/String;")));
        try{
            Object left=a.getConstructor().newInstance(),right=b.getConstructor().newInstance();
            VirtualProperties.set(a,"value",String.class,left,"left");assertEquals("left",VirtualGetters.get(a,"value",String.class,left));
            assertThrows(IllegalStateException.class,()->VirtualProperties.set(b,"value",String.class,right,"wrong"));
            VirtualProperties.observe(second,"unknown/EqualState",java.util.Map.of());
            VirtualProperties.set(a,"value",String.class,left,"still-left");assertEquals("still-left",VirtualGetters.get(a,"value",String.class,left));
        }finally{VirtualProperties.release(first);VirtualProperties.release(second);}
    }
    private static byte[] type(String name,boolean opaque,boolean immutable){
        ClassWriter w=new ClassWriter(ClassWriter.COMPUTE_MAXS);w.visit(V21,ACC_PUBLIC,name,null,"java/lang/Object",null);w.visitField(ACC_PRIVATE|(immutable?ACC_FINAL:0),"randomBacking","Ljava/lang/String;",null,null).visitEnd();
        MethodVisitor m=w.visitMethod(ACC_PUBLIC,"<init>","()V",null,null);m.visitCode();m.visitVarInsn(ALOAD,0);m.visitMethodInsn(INVOKESPECIAL,"java/lang/Object","<init>","()V",false);m.visitVarInsn(ALOAD,0);m.visitLdcInsn("original");m.visitFieldInsn(PUTFIELD,name,"randomBacking","Ljava/lang/String;");m.visitInsn(RETURN);m.visitMaxs(0,0);m.visitEnd();
        m=w.visitMethod(ACC_PUBLIC,"value","()Ljava/lang/String;",null,null);m.visitCode();m.visitVarInsn(ALOAD,0);m.visitFieldInsn(GETFIELD,name,"randomBacking","Ljava/lang/String;");if(opaque)m.visitMethodInsn(INVOKEVIRTUAL,"java/lang/String","toUpperCase","()Ljava/lang/String;",false);m.visitInsn(ARETURN);m.visitMaxs(0,0);m.visitEnd();w.visitEnd();return w.toByteArray();
    }
}
