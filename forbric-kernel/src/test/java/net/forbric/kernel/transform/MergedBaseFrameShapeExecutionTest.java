/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.transform;

import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;

/** An unknown hierarchy and its covariant arrays fail real linkage before repair and execute afterward. */
class MergedBaseFrameShapeExecutionTest {
    private static final String PREFIX="future/protocol/";
    private static Map<String,byte[]> types(){
        Map<String,byte[]> types=new HashMap<>();
        types.put(PREFIX+"Old",plain(PREFIX+"Old","java/lang/Object"));
        types.put(PREFIX+"Current",plain(PREFIX+"Current","java/lang/Object"));
        types.put(PREFIX+"First",plain(PREFIX+"First",PREFIX+"Current"));
        types.put(PREFIX+"Second",plain(PREFIX+"Second",PREFIX+"Current"));return types;
    }
    private static byte[] plain(String owner,String parent){
        ClassWriter writer=new ClassWriter(0);writer.visit(Opcodes.V17,Opcodes.ACC_PUBLIC,owner,null,parent,null);
        MethodVisitor init=writer.visitMethod(Opcodes.ACC_PUBLIC,"<init>","()V",null,null);init.visitCode();init.visitVarInsn(Opcodes.ALOAD,0);
        init.visitMethodInsn(Opcodes.INVOKESPECIAL,parent,"<init>","()V",false);init.visitInsn(Opcodes.RETURN);init.visitMaxs(1,1);init.visitEnd();writer.visitEnd();return writer.toByteArray();
    }
    private static byte[] choose(boolean array,String declared){
        ClassWriter writer=new ClassWriter(0);writer.visit(Opcodes.V17,Opcodes.ACC_PUBLIC,PREFIX+"Reader",null,"java/lang/Object",null);
        MethodVisitor method=writer.visitMethod(Opcodes.ACC_PUBLIC|Opcodes.ACC_STATIC,"choose","(ZJ)Ljava/lang/Object;",null,null);method.visitCode();
        Label second=new Label(),join=new Label();method.visitVarInsn(Opcodes.ILOAD,0);method.visitJumpInsn(Opcodes.IFEQ,second);value(method,PREFIX+"First",array);method.visitJumpInsn(Opcodes.GOTO,join);
        method.visitLabel(second);method.visitFrame(Opcodes.F_SAME,0,null,0,null);value(method,PREFIX+"Second",array);
        method.visitLabel(join);method.visitFrame(Opcodes.F_SAME1,0,null,1,new Object[]{array?"[L"+declared+";":declared});method.visitInsn(Opcodes.ARETURN);method.visitMaxs(2,3);method.visitEnd();writer.visitEnd();return writer.toByteArray();
    }
    private static void value(MethodVisitor method,String type,boolean array){
        if(array){method.visitInsn(Opcodes.ICONST_1);method.visitTypeInsn(Opcodes.ANEWARRAY,type);}
        else{method.visitTypeInsn(Opcodes.NEW,type);method.visitInsn(Opcodes.DUP);method.visitMethodInsn(Opcodes.INVOKESPECIAL,type,"<init>","()V",false);}
    }
    private static MergedBaseFrameRecomputer recomputer(Map<String,byte[]> types){return new MergedBaseFrameRecomputer(path->{
        byte[] known=types.get(path.substring(0,path.length()-6));if(known!=null)return known;
        try(var input=ClassLoader.getPlatformClassLoader().getResourceAsStream(path)){return input==null?null:input.readAllBytes();}catch(Exception absent){return null;}
    });}
    private static Class<?> reader(Map<String,byte[]> types,byte[] bytes)throws Exception{
        Map<String,byte[]> definitions=new HashMap<>(types);definitions.put(PREFIX+"Reader",bytes);
        ClassLoader loader=new ClassLoader(ClassLoader.getPlatformClassLoader()){
            @Override protected Class<?> findClass(String name)throws ClassNotFoundException{byte[] definition=definitions.get(name.replace('.','/'));if(definition==null)throw new ClassNotFoundException(name);return defineClass(name,definition,0,definition.length);}
        };return loader.loadClass((PREFIX+"Reader").replace('/','.'));
    }
    @Test void unknownScalarAndArrayHierarchyRepairsExecuteAndAreIdempotent()throws Exception{
        for(boolean array:List.of(false,true)){
            Map<String,byte[]> types=types();byte[] original=choose(array,PREFIX+"Old");
            assertThrows(VerifyError.class,()->reader(types,original).getDeclaredMethods());
            var repair=recomputer(types);byte[] repaired=repair.transform("future.protocol.Reader",original,null);assertNotSame(original,repaired);
            Class<?> reader=reader(types,repaired);assertTrue(reader.getDeclaredMethods().length>0);
            for(boolean branch:List.of(false,true)){
                Object result=reader.getMethod("choose",boolean.class,long.class).invoke(null,branch,47L);
                Class<?> type=array?result.getClass().getComponentType():result.getClass();assertEquals((PREFIX+(branch?"First":"Second")).replace('/','.'),type.getName());
                if(array)assertEquals(1,java.lang.reflect.Array.getLength(result));
            }
            assertEquals(opcodes(original),opcodes(repaired),"the repair preserves every executable opcode");
            assertSame(repaired,repair.transform("future.protocol.Reader",repaired,null));
        }
    }
    @Test void validUnknownFramesAndUnresolvedHierarchiesRetainTheOriginalBytes(){
        Map<String,byte[]> types=types();for(boolean array:List.of(false,true)){
            byte[] valid=choose(array,PREFIX+"Current");assertSame(valid,recomputer(types).transform("future.protocol.Reader",valid,null));
            byte[] invalid=choose(array,PREFIX+"Old");assertSame(invalid,new MergedBaseFrameRecomputer(path->null).transform("future.protocol.Reader",invalid,null));
        }
        var repair=recomputer(types);assertEquals("[L"+PREFIX+"Current;",repair.commonSuperClass("[L"+PREFIX+"First;","[L"+PREFIX+"Second;"));
        assertEquals("[Ljava/lang/Object;",repair.commonSuperClass("[[I","[[J"));assertFalse(repair.assignable("[L"+PREFIX+"Old;","[L"+PREFIX+"Current;"));
    }
    private static List<Integer> opcodes(byte[] bytes){ClassNode node=new ClassNode();new ClassReader(bytes).accept(node,0);List<Integer> out=new ArrayList<>();for(MethodNode method:node.methods)for(AbstractInsnNode instruction:method.instructions)if(instruction.getOpcode()>=0)out.add(instruction.getOpcode());return out;}
}
