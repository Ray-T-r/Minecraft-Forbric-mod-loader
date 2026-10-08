/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.tools;
import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;

/** Unknown-name counterexamples must fail proof, while actual inherited/default interface dispatch executes. */
class ContractResolutionExecutionTest {
    @Test void constructorSelfLiteralIsAnObservableNominalDifference()throws Exception{
        ClassNode common=node("future/identity/Storage","java/lang/Object");common.fields.add(new FieldNode(Opcodes.ACC_PUBLIC|Opcodes.ACC_FINAL,"type","Ljava/lang/Class;",null,null));
        MethodNode init=new MethodNode(Opcodes.ACC_PUBLIC,"<init>","(Ljava/lang/Class;)V",null,null);init.instructions.add(new VarInsnNode(Opcodes.ALOAD,0));init.instructions.add(new MethodInsnNode(Opcodes.INVOKESPECIAL,"java/lang/Object","<init>","()V",false));init.instructions.add(new VarInsnNode(Opcodes.ALOAD,0));init.instructions.add(new VarInsnNode(Opcodes.ALOAD,1));init.instructions.add(new FieldInsnNode(Opcodes.PUTFIELD,common.name,"type","Ljava/lang/Class;"));init.instructions.add(new InsnNode(Opcodes.RETURN));common.methods.add(init);
        ClassNode a=selfLiteral("future/identity/First",common.name),b=selfLiteral("future/identity/Second",common.name);
        assertFalse(EquivalentSuperclassBridge.equivalent(a,b));assertThrows(IllegalArgumentException.class,()->EquivalentSuperclassBridge.bridge(a,b));
        Map<String,byte[]> definitions=definitions(common,a,b);ClassLoader loader=loader(definitions);for(ClassNode nativeType:List.of(a,b)){
            Class<?> type=loader.loadClass(nativeType.name.replace('/','.'));Object value=type.getConstructor().newInstance();assertSame(type,type.getField("type").get(value),"each native constructor stores its own nominal class identity");
        }
    }
    @Test void privateAndStaticClassMethodsAreNotInterfaceImplementations(@TempDir Path work)throws Exception{
        for(int flags:List.of(Opcodes.ACC_PRIVATE,Opcodes.ACC_PUBLIC|Opcodes.ACC_STATIC)){
            ClassNode contract=contract("future/api/Required",false),impl=implementation("future/impl/Collision",contract.name,"java/lang/Object",flags);
            assertNull(graph(contract,impl).implementation(impl.name,"read","()I"));
            ClassNode invoke=invoke(contract.name);Map<String,byte[]> definitions=definitions(contract,impl,invoke);ClassLoader loader=loader(definitions);Object value=loader.loadClass(impl.name.replace('/','.')).getConstructor().newInstance();
            var failure=assertThrows(java.lang.reflect.InvocationTargetException.class,()->loader.loadClass(invoke.name.replace('/','.')).getMethod("call",loader.loadClass(contract.name.replace('/','.'))).invoke(null,value));
            assertInstanceOf(LinkageError.class,failure.getCause(),"the unproved raw class really fails invokeinterface");
            assertThrows(IllegalStateException.class,()->new MapContractRepair(graph(contract,impl),List.of()).repair(impl));
            assertThrows(IllegalStateException.class,()->EmptyArrayContractBridge.repair(impl,graph(contract,impl)));
            Path folder=work.resolve(Integer.toString(flags));Files.createDirectories(folder);Path own=jar(folder.resolve("own.jar"),definitions(impl)),game=jar(folder.resolve("game.jar"),definitions(contract)),peer=jar(folder.resolve("peer.jar"),Map.of()),output=folder.resolve("output.jar");
            var error=assertThrows(IllegalStateException.class,()->new RuntimeInteropPatcher().run(own,output,game,peer,null));assertTrue(error.getMessage().contains("interface slot"));assertFalse(Files.exists(output),"no artifact may claim this ABI is implemented");
        }
    }
    @Test void privateAndStaticCollisionsDoNotHideInheritedPublicImplementationOrDefault()throws Exception{
        for(int flags:List.of(Opcodes.ACC_PRIVATE,Opcodes.ACC_PUBLIC|Opcodes.ACC_STATIC))for(boolean useDefault:List.of(false,true)){
            ClassNode contract=contract("future/api/Available",useDefault),parent=node("future/impl/Parent","java/lang/Object");parent.methods.add(constructor(parent.name,parent.superName));if(!useDefault)parent.methods.add(value(Opcodes.ACC_PUBLIC,42));
            ClassNode impl=implementation("future/impl/Child",contract.name,parent.name,flags);ContractGraph graph=graph(contract,parent,impl);MethodNode method=graph.implementation(impl.name,"read","()I");assertNotNull(method);assertEquals(Opcodes.ACC_PUBLIC,method.access&(Opcodes.ACC_PUBLIC|Opcodes.ACC_STATIC|Opcodes.ACC_PRIVATE));
            assertEquals(0,new MapContractRepair(graph,List.of()).repair(impl));assertEquals(0,EmptyArrayContractBridge.repair(impl,graph));
            ClassNode invoke=invoke(contract.name);ClassLoader loader=loader(definitions(contract,parent,impl,invoke));Class<?> iface=loader.loadClass(contract.name.replace('/','.'));Object receiver=loader.loadClass(impl.name.replace('/','.')).getConstructor().newInstance();
            assertEquals(42,loader.loadClass(invoke.name.replace('/','.')).getMethod("call",iface).invoke(null,receiver),"the JVM dispatch agrees with the source graph");
        }
    }
    @Test void inaccessibleOrAbstractInstanceDeclarationsCannotBorrowADefault(){
        for(int flags:List.of(Opcodes.ACC_PROTECTED,0,Opcodes.ACC_PUBLIC|Opcodes.ACC_ABSTRACT)){
            ClassNode contract=contract("future/api/Default",true),impl=implementation("future/impl/Blocked",contract.name,"java/lang/Object",flags);
            assertNull(graph(contract,impl).implementation(impl.name,"read","()I"));
        }
    }
    private static ClassNode selfLiteral(String name,String parent){ClassNode node=node(name,parent);MethodNode init=new MethodNode(Opcodes.ACC_PUBLIC,"<init>","()V",null,null);init.instructions.add(new VarInsnNode(Opcodes.ALOAD,0));init.instructions.add(new LdcInsnNode(Type.getObjectType(name)));init.instructions.add(new MethodInsnNode(Opcodes.INVOKESPECIAL,parent,"<init>","(Ljava/lang/Class;)V",false));init.instructions.add(new InsnNode(Opcodes.RETURN));node.methods.add(init);return node;}
    private static ClassNode contract(String name,boolean defaults){ClassNode node=node(name,"java/lang/Object");node.access=Opcodes.ACC_PUBLIC|Opcodes.ACC_INTERFACE|Opcodes.ACC_ABSTRACT;node.methods.add(defaults?value(Opcodes.ACC_PUBLIC,42):new MethodNode(Opcodes.ACC_PUBLIC|Opcodes.ACC_ABSTRACT,"read","()I",null,null));return node;}
    private static ClassNode implementation(String name,String contract,String parent,int flags){ClassNode node=node(name,parent);node.interfaces.add(contract);node.methods.add(constructor(name,parent));node.methods.add(value(flags,-1));return node;}
    private static MethodNode value(int flags,int value){MethodNode method=new MethodNode(flags,"read","()I",null,null);if((flags&Opcodes.ACC_ABSTRACT)==0){method.instructions.add(new IntInsnNode(Opcodes.BIPUSH,value));method.instructions.add(new InsnNode(Opcodes.IRETURN));}return method;}
    private static ClassNode invoke(String contract){ClassNode node=node("future/Call","java/lang/Object");MethodNode call=new MethodNode(Opcodes.ACC_PUBLIC|Opcodes.ACC_STATIC,"call","(L"+contract+";)I",null,null);call.instructions.add(new VarInsnNode(Opcodes.ALOAD,0));call.instructions.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE,contract,"read","()I",true));call.instructions.add(new InsnNode(Opcodes.IRETURN));node.methods.add(call);return node;}
    private static ClassNode node(String name,String parent){ClassNode node=new ClassNode();node.version=Opcodes.V17;node.access=Opcodes.ACC_PUBLIC;node.name=name;node.superName=parent;return node;}
    private static MethodNode constructor(String name,String parent){MethodNode method=new MethodNode(Opcodes.ACC_PUBLIC,"<init>","()V",null,null);method.instructions.add(new VarInsnNode(Opcodes.ALOAD,0));method.instructions.add(new MethodInsnNode(Opcodes.INVOKESPECIAL,parent,"<init>","()V",false));method.instructions.add(new InsnNode(Opcodes.RETURN));return method;}
    private static byte[] bytes(ClassNode node){ClassWriter writer=new ClassWriter(ClassWriter.COMPUTE_MAXS);node.accept(writer);return writer.toByteArray();}
    private static Map<String,byte[]> definitions(ClassNode... nodes){Map<String,byte[]> out=new LinkedHashMap<>();for(ClassNode node:nodes)out.put(node.name,bytes(node));return out;}
    private static ContractGraph graph(ClassNode... nodes){return new ContractGraph(List.of(definitions(nodes)));}
    private static ClassLoader loader(Map<String,byte[]> definitions){return new ClassLoader(ContractResolutionExecutionTest.class.getClassLoader()){@Override protected Class<?> findClass(String name)throws ClassNotFoundException{byte[] data=definitions.get(name.replace('.','/'));if(data==null)return super.findClass(name);return defineClass(name,data,0,data.length);}};}
    private static Path jar(Path path,Map<String,byte[]> definitions)throws Exception{try(ZipOutputStream zip=new ZipOutputStream(Files.newOutputStream(path))){for(var entry:definitions.entrySet()){zip.putNextEntry(new ZipEntry(entry.getKey()+".class"));zip.write(entry.getValue());zip.closeEntry();}}return path;}
}
