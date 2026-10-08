/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.transform;

import static org.junit.jupiter.api.Assertions.*;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.*;
import java.util.function.Function;
import java.util.zip.ZipFile;
import net.forbric.kernel.TestFixtures;
import net.forbric.kernel.TestFixtures.Fixture;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import org.objectweb.asm.tree.analysis.*;

/** Executes independently named owners; an allocation shape alone cannot certify its constructor path. */
class NativeOwnedComponentInitializersTest implements Opcodes {
    private static final String OWNER="future/storage/Owner",COMPONENT="future/storage/Component",PEER="future/storage/Peer";
    private enum Shape { PURE, CONDITIONAL, CONDITIONAL_PEER, CHANGED_PREFIX, UNCOVERED }
    private record World(Class<?> owner,Class<?> component,Class<?> peer) {
        Object create(boolean enabled)throws Exception{return owner.getConstructor(boolean.class).newInstance(enabled);}
        Object get(Object receiver,Class<?> result)throws Exception{
            Method getter=Arrays.stream(owner.getDeclaredMethods()).filter(m->m.getName().equals("component")&&m.getReturnType()==result).findFirst().orElseThrow();
            return getter.invoke(receiver);
        }
    }
    @Test void unknownOwnerRestoresAnUnconditionalSourceAllocationAndKeepsOwnerIdentity()throws Exception{
        byte[] original=owner(true,Shape.PURE),current=owner(false,Shape.PURE);var resources=resources(original,current,false);
        var transformer=new ModifiableDataViewsTransformer(resources);byte[] repaired=transformer.transform(OWNER,current,null);assertNotSame(current,repaired);
        World world=world(repaired,false);for(boolean enabled:List.of(false,true)){
            Object receiver=world.create(enabled),component=world.get(receiver,world.component),peer=world.get(receiver,world.peer);
            assertNotNull(component);assertSame(receiver,world.component.getField("owner").get(component));assertSame(receiver,world.peer.getField("owner").get(peer));
        }
        assertSame(repaired,transformer.transform(OWNER,repaired,null),"the independently owned state is allocated once");
    }
    @Test void conditionalSourceInitializationIsRefusedWithoutRemovingItsEnabledBranch()throws Exception{
        byte[] original=owner(true,Shape.CONDITIONAL),current=owner(false,Shape.PURE);World source=world(original,false);
        assertNull(source.get(source.create(false),source.component));Object enabled=source.create(true),owned=source.get(enabled,source.component);
        assertNotNull(owned);assertSame(enabled,source.component.getField("owner").get(owned),"the actual source branch initializes the component only when enabled");
        byte[] result=new ModifiableDataViewsTransformer(resources(original,current,false)).transform(OWNER,current,null);
        assertSame(current,result,"a guarded allocation must never be moved to the unconditional peer boundary");World declined=world(result,false);
        assertNull(declined.get(declined.create(false),declined.component),"the old recovery wrongly constructed the component for enabled=false");
        assertArrayEquals(original,owner(true,Shape.CONDITIONAL),"source proof bytes are read without rewriting the branch");
    }
    @Test void aConditionalCurrentBoundaryOrChangedNativePrefixCannotSupplyAProof()throws Exception{
        byte[] source=owner(true,Shape.PURE);
        for(Shape shape:List.of(Shape.CONDITIONAL_PEER,Shape.CHANGED_PREFIX)){
            byte[] current=owner(false,shape),nativePeer=owner(false,shape==Shape.CHANGED_PREFIX?Shape.PURE:shape);
            assertSame(current,new ModifiableDataViewsTransformer(resources(source,nativePeer,false)).transform(OWNER,current,null),shape.toString());
            World world=world(current,false);assertNull(world.get(world.create(false),world.component));
            if(shape==Shape.CONDITIONAL_PEER){assertNull(world.get(world.create(false),world.peer));assertNotNull(world.get(world.create(true),world.peer));}
        }
    }
    @Test void opaqueComponentEffectsAndAnUncoveredSourceWriterRefuseTheWholeRecovery()throws Exception{
        byte[] source=owner(true,Shape.PURE),current=owner(false,Shape.PURE);
        World sourceWorld=world(source,true);sourceWorld.create(false);assertEquals(1,sourceWorld.owner.getField("effects").getInt(null));
        assertSame(current,new ModifiableDataViewsTransformer(resources(source,current,true)).transform(OWNER,current,null),"the component constructor performs an external effect");
        byte[] otherSource=owner(true,Shape.UNCOVERED),otherCurrent=owner(false,Shape.UNCOVERED);
        assertSame(otherCurrent,new ModifiableDataViewsTransformer(resources(otherSource,otherCurrent,false)).transform(OWNER,otherCurrent,null),"an unsupported source writer cannot be silently skipped while another constructor is repaired");
        World other=world(otherSource,false);Object receiver=other.owner.getConstructor().newInstance();assertNull(other.component.getField("owner").get(other.get(receiver,other.component)));
    }
    @Test void actualClientLevelRestoresItsIndependentNativeComponentAtTheVerifiedPeerBoundary()throws Exception{
        Function<String,byte[]> resources=stagedResources();String owner="net/minecraft/client/multiplayer/ClientLevel";byte[] raw=resources.apply(owner+".class");
        var transformer=new ModifiableDataViewsTransformer(resources);byte[] repaired=transformer.transform(owner,raw,null);assertNotSame(raw,repaired);
        ClassNode before=parse(raw),after=parse(repaired);String field="modelDataManager",forge="Lnet/minecraftforge/client/model/data/ModelDataManager;",neo="Lnet/neoforged/neoforge/client/model/data/ModelDataManager;";
        assertEquals(0,writes(before,field,forge));assertEquals(1,writes(after,field,forge));assertEquals(writes(before,field,neo),writes(after,field,neo));
        for(MethodNode ctor:after.methods)if(ctor.name.equals("<init>")){
            new Analyzer<>(new BasicVerifier()).analyze(owner,ctor);List<AbstractInsnNode> code=Arrays.stream(ctor.instructions.toArray()).filter(i->i.getOpcode()>=0).toList();
            int peer=-1,lost=-1;for(int i=0;i<code.size();i++)if(code.get(i)instanceof FieldInsnNode f&&f.getOpcode()==PUTFIELD&&f.name.equals(field)){if(f.desc.equals(neo))peer=i;if(f.desc.equals(forge))lost=i;}
            if(peer>=0)assertEquals(peer+6,lost,"only the source-owned six-instruction allocation follows the unchanged canonical boundary");
        }
        assertArrayEquals(repaired,transformer.transform(owner,repaired,null));
    }
    private static byte[] owner(boolean original,Shape shape){
        ClassWriter w=new ClassWriter(ClassWriter.COMPUTE_FRAMES|ClassWriter.COMPUTE_MAXS);w.visit(V17,ACC_PUBLIC,OWNER,null,"java/lang/Object",null);
        w.visitField(ACC_PUBLIC,"owned","L"+COMPONENT+";",null,null).visitEnd();w.visitField(ACC_PUBLIC,"peer","L"+PEER+";",null,null).visitEnd();w.visitField(ACC_PUBLIC|ACC_STATIC,"effects","I",null,null).visitEnd();
        for(String[] getter:List.of(new String[]{"owned",COMPONENT},new String[]{"peer",PEER})){
            MethodVisitor m=w.visitMethod(ACC_PUBLIC,"component","()L"+getter[1]+";",null,null);m.visitCode();m.visitVarInsn(ALOAD,0);m.visitFieldInsn(GETFIELD,OWNER,getter[0],"L"+getter[1]+";");m.visitInsn(ARETURN);m.visitMaxs(0,0);m.visitEnd();
        }
        MethodVisitor m=w.visitMethod(ACC_PUBLIC,"<init>","(Z)V",null,null);m.visitCode();superCall(m);if(shape==Shape.CHANGED_PREFIX)m.visitInsn(NOP);
        Label endPeer=new Label();if(shape==Shape.CONDITIONAL_PEER){m.visitVarInsn(ILOAD,1);m.visitJumpInsn(IFEQ,endPeer);}allocation(m,PEER,"peer",false);m.visitLabel(endPeer);
        if(original){Label end=new Label();if(shape==Shape.CONDITIONAL){m.visitVarInsn(ILOAD,1);m.visitJumpInsn(IFEQ,end);}allocation(m,COMPONENT,"owned",false);m.visitLabel(end);}
        m.visitInsn(RETURN);m.visitMaxs(0,0);m.visitEnd();
        if(shape==Shape.UNCOVERED){m=w.visitMethod(ACC_PUBLIC,"<init>","()V",null,null);m.visitCode();superCall(m);allocation(m,PEER,"peer",false);if(original)allocation(m,COMPONENT,"owned",true);m.visitInsn(RETURN);m.visitMaxs(0,0);m.visitEnd();}
        w.visitEnd();return w.toByteArray();
    }
    private static void superCall(MethodVisitor m){m.visitVarInsn(ALOAD,0);m.visitMethodInsn(INVOKESPECIAL,"java/lang/Object","<init>","()V",false);}
    private static void allocation(MethodVisitor m,String component,String field,boolean nullOwner){
        m.visitVarInsn(ALOAD,0);m.visitTypeInsn(NEW,component);m.visitInsn(DUP);if(nullOwner)m.visitInsn(ACONST_NULL);else m.visitVarInsn(ALOAD,0);
        m.visitMethodInsn(INVOKESPECIAL,component,"<init>","(L"+OWNER+";)V",false);m.visitFieldInsn(PUTFIELD,OWNER,field,"L"+component+";");
    }
    private static byte[] component(String name,boolean opaque){
        ClassWriter w=new ClassWriter(ClassWriter.COMPUTE_MAXS);w.visit(V17,ACC_PUBLIC,name,null,"java/lang/Object",null);w.visitField(ACC_PUBLIC|ACC_FINAL,"owner","L"+OWNER+";",null,null).visitEnd();
        MethodVisitor m=w.visitMethod(ACC_PUBLIC,"<init>","(L"+OWNER+";)V",null,null);m.visitCode();superCall(m);m.visitVarInsn(ALOAD,0);m.visitVarInsn(ALOAD,1);m.visitFieldInsn(PUTFIELD,name,"owner","L"+OWNER+";");
        if(opaque){m.visitFieldInsn(GETSTATIC,OWNER,"effects","I");m.visitInsn(ICONST_1);m.visitInsn(IADD);m.visitFieldInsn(PUTSTATIC,OWNER,"effects","I");}
        m.visitInsn(RETURN);m.visitMaxs(0,0);m.visitEnd();w.visitEnd();return w.toByteArray();
    }
    private static Function<String,byte[]> resources(byte[] source,byte[] canonical,boolean opaque)throws Exception{
        Map<String,byte[]> entries=new HashMap<>();entries.put(OWNER+".class",canonical);entries.put(COMPONENT+".class",component(COMPONENT,opaque));entries.put(PEER+".class",component(PEER,false));
        for(var entry:Map.of("FORGE",source,"NEOFORGE",canonical).entrySet()){
            String prefix="META-INF/forbric/native-reference/"+entry.getKey()+"/";entries.put(prefix+OWNER+".class.bin",entry.getValue());
            String hash=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(entry.getValue()));entries.put(prefix+"index.tsv",("# forbric-native-reference-v1\n"+OWNER+"\t"+hash+"\n").getBytes(StandardCharsets.UTF_8));
        }return entries::get;
    }
    private static World world(byte[] owner,boolean opaque)throws Exception{
        Map<String,byte[]> definitions=Map.of(OWNER,owner,COMPONENT,component(COMPONENT,opaque),PEER,component(PEER,false));
        ClassLoader loader=new ClassLoader(NativeOwnedComponentInitializersTest.class.getClassLoader()){
            @Override protected Class<?> findClass(String name)throws ClassNotFoundException{byte[] bytes=definitions.get(name.replace('.','/'));if(bytes==null)throw new ClassNotFoundException(name);return defineClass(name,bytes,0,bytes.length);}
        };
        return new World(loader.loadClass(OWNER.replace('/','.')),loader.loadClass(COMPONENT.replace('/','.')),loader.loadClass(PEER.replace('/','.')));
    }
    private static Function<String,byte[]> stagedResources(){
        List<Path> jars=List.of(TestFixtures.stagedRoot().resolve("merged-base/patched-mc-merged-26.2.jar"),TestFixtures.stagedRoot().resolve("forge-runtime/forge-runtime.jar"),TestFixtures.stagedRoot().resolve("neoforge-runtime/neoforge-runtime.jar"));
        TestFixtures.requireFiles(Fixture.STAGED,"native owned component initializers",jars.toArray(Path[]::new));
        return path->{for(Path jar:jars)try(var zip=new ZipFile(jar.toFile())){var entry=zip.getEntry(path);if(entry!=null)try(var in=zip.getInputStream(entry)){return in.readAllBytes();}}catch(java.io.IOException e){throw new java.io.UncheckedIOException(e);}return null;};
    }
    private static ClassNode parse(byte[] bytes){ClassNode node=new ClassNode();new ClassReader(bytes).accept(node,0);return node;}
    private static int writes(ClassNode node,String name,String descriptor){int result=0;for(MethodNode method:node.methods)for(AbstractInsnNode i:method.instructions)if(i instanceof FieldInsnNode f&&f.getOpcode()==PUTFIELD&&f.name.equals(name)&&f.desc.equals(descriptor))result++;return result;}
}
