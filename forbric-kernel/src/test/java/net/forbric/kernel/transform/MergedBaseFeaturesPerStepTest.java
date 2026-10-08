/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.transform;

import static org.junit.jupiter.api.Assertions.*;
import java.io.*;
import java.lang.reflect.Field;
import java.net.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import java.util.zip.ZipFile;
import net.forbric.kernel.TestFixtures;
import net.forbric.kernel.TestFixtures.Fixture;
import net.forbric.kernel.access.ClassTweakerTransformer;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;

/** The rebuilt base keeps both public field descriptors while the stronger view reads the live Supplier cell. */
class MergedBaseFeaturesPerStepTest {
    private static final Path RUN=TestFixtures.stagedRoot(),MERGED=RUN.resolve("merged-base/patched-mc-merged-26.2.jar"),INTEROP=RUN.resolve("merged-base/forge-runtime-interop.jar"),NEO=RUN.resolve("neoforge-runtime/neoforge-runtime.jar");
    private static final String OWNER="net/minecraft/world/level/chunk/ChunkGenerator",BINARY=OWNER.replace('/','.'),SUPPLIER="Ljava/util/function/Supplier;",STRONG="Lnet/minecraftforge/common/util/ClearableLazy;";
    @Test void theFieldCarriesVanillasDescriptorAndOnlyThatOne()throws Exception{
        ClassNode node=node(original());List<String> descriptors=node.fields.stream().filter(f->f.name.equals("featuresPerStep")).map(f->f.desc).sorted().toList();
        assertEquals(java.util.stream.Stream.of(SUPPLIER,STRONG).sorted().toList(),descriptors,"both native ABI views are retained exactly once");
    }
    @Test void theFieldIsAlsoWriteableFromAnotherClass()throws Exception{
        ClassNode node=node(widened());FieldNode field=node.fields.stream().filter(f->f.name.equals("featuresPerStep")&&f.desc.equals(SUPPLIER)).findFirst().orElseThrow();
        assertTrue((field.access&Opcodes.ACC_PUBLIC)!=0);assertEquals(0,field.access&Opcodes.ACC_FINAL,"the real access phase can satisfy a descriptor-specific mod's mutable request");
    }
    @Test void everyReaderReadsTheOneFieldTheConstructorWrites()throws Exception{
        ClassNode host=node(original());MethodNode cell=host.methods.stream().filter(m->m.name.startsWith("forbric$providerCell$")&&m.desc.equals("()"+SUPPLIER)).findFirst().orElseThrow();
        assertTrue(Arrays.stream(cell.instructions.toArray()).anyMatch(i->i instanceof FieldInsnNode field&&field.getOpcode()==Opcodes.GETFIELD&&field.name.equals("featuresPerStep")&&field.desc.equals(SUPPLIER)));
        String carrier=host.methods.stream().filter(m->m.name.equals("<init>")).flatMap(m->Arrays.stream(m.instructions.toArray())).filter(i->i instanceof MethodInsnNode call&&call.name.equals("wrap")&&call.owner.contains("$forbricProvider$")).map(i->((MethodInsnNode)i).owner).findFirst().orElseThrow();
        ClassNode view=node(read(MERGED,carrier));assertTrue(view.fields.stream().anyMatch(f->f.desc.equals("L"+OWNER+";")),"the alias binds the owner, rather than a stale initial provider");
        MethodNode get=view.methods.stream().filter(m->m.name.equals("get")).findFirst().orElseThrow();assertTrue(Arrays.stream(get.instructions.toArray()).anyMatch(i->i instanceof MethodInsnNode call&&call.owner.equals(OWNER)&&call.name.equals(cell.name)));
    }
    @Test void invalidationGoesThroughTheGuardAndNotAThroughCast()throws Exception{
        ClassNode node=node(original());MethodNode refresh=node.methods.stream().filter(m->m.name.equals("refreshFeaturesPerStep")).findFirst().orElseThrow();
        assertTrue(Arrays.stream(refresh.instructions.toArray()).noneMatch(i->i.getOpcode()==Opcodes.CHECKCAST));
        MethodInsnNode guard=Arrays.stream(refresh.instructions.toArray()).filter(i->i instanceof MethodInsnNode call&&call.getOpcode()==Opcodes.INVOKESTATIC&&call.owner.equals(OWNER)).map(i->(MethodInsnNode)i).findFirst().orElseThrow();
        MethodNode body=node.methods.stream().filter(m->m.name.equals(guard.name)&&m.desc.equals(guard.desc)).findFirst().orElseThrow();
        assertTrue(Arrays.stream(body.instructions.toArray()).anyMatch(i->i.getOpcode()==Opcodes.INSTANCEOF),"invalidate only when the current provider supplies the optional reset capability");
    }
    @Test void readsAreRetargetedAtSupplierGet()throws Exception{
        try(World world=world()){
            Object first=world.type.getMethod("forbric$testRead").invoke(world.owner);assertEquals(List.of("initial"),first);assertEquals(1,world.computations.get());
            world.type.getMethod("validate").invoke(world.owner);assertEquals(1,world.computations.get(),"both views share the original native cache");
            world.type.getMethod("refreshFeaturesPerStep").invoke(world.owner);assertEquals(List.of("initial"),world.type.getMethod("forbric$testRead").invoke(world.owner));assertEquals(2,world.computations.get());
            Object alias=world.strong.get(world.owner);AtomicInteger reads=new AtomicInteger();List<String> changed=List.of("modded");Supplier<Object> replacement=()->{reads.incrementAndGet();return changed;};
            world.loader.loadClass("fixture.ExternalProviderWriter").getMethod("install",world.type,Supplier.class).invoke(null,world.owner,replacement);
            assertSame(alias,world.strong.get(world.owner));assertSame(changed,world.type.getMethod("forbric$testRead").invoke(world.owner));world.type.getMethod("validate").invoke(world.owner);assertEquals(2,reads.get(),"validate reads the replacement through the live stronger API");
            world.type.getMethod("refreshFeaturesPerStep").invoke(world.owner);((Supplier<?>)alias).getClass().getMethod("invalidate").invoke(alias);assertSame(changed,((Supplier<?>)alias).get());assertEquals(2,world.computations.get(),"the stale initial provider was not consulted");
        }
    }
    @Test void aSecondPassLeavesTheRepairedClassAlone()throws Exception{
        byte[] original=original();assertSame(original,new ForbricMergedBaseCompatTransformer().transform(BINARY,original,null),"there is no runtime descriptor patch over the proved two-view merge");
    }
    private record World(URLClassLoader loader,Class<?> type,Object owner,Field strong,AtomicInteger computations)implements AutoCloseable{public void close()throws IOException{loader.close();}}
    private static World world()throws Exception{
        TestFixtures.require(Fixture.JAVA_25,Runtime.version().feature()>=25,"actual classfile 69 needs Java 25");for(Path jar:List.of(MERGED,INTEROP,NEO))TestFixtures.require(Fixture.STAGED,Files.isRegularFile(jar),jar+" required");
        Map<String,byte[]> definitions=new HashMap<>();ClassNode host=node(widened());host.methods.removeIf(m->m.name.equals("<clinit>"));host.access&=~Opcodes.ACC_ABSTRACT;
        // Exercise the actual game readers/reset bodies while avoiding unrelated bootstrap/biome construction.
        for(MethodNode method:host.methods)if((method.access&Opcodes.ACC_ABSTRACT)!=0){
            method.access&=~Opcodes.ACC_ABSTRACT;Type result=Type.getReturnType(method.desc);
            if(result.getSort()!=Type.VOID)method.instructions.add(new InsnNode(switch(result.getSort()){case Type.OBJECT,Type.ARRAY->Opcodes.ACONST_NULL;case Type.LONG->Opcodes.LCONST_0;case Type.DOUBLE->Opcodes.DCONST_0;case Type.FLOAT->Opcodes.FCONST_0;default->Opcodes.ICONST_0;}));
            method.instructions.add(new InsnNode(result.getOpcode(Opcodes.IRETURN)));method.maxStack=2;method.maxLocals=1+Arrays.stream(Type.getArgumentTypes(method.desc)).mapToInt(Type::getSize).sum();
        }
        MethodNode read=new MethodNode(Opcodes.ACC_PUBLIC,"forbric$testRead","()Ljava/lang/Object;",null,null);read.instructions.add(new VarInsnNode(Opcodes.ALOAD,0));read.instructions.add(new FieldInsnNode(Opcodes.GETFIELD,OWNER,"featuresPerStep",STRONG));read.instructions.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE,Type.getType(STRONG).getInternalName(),"get","()Ljava/lang/Object;",true));read.instructions.add(new InsnNode(Opcodes.ARETURN));host.methods.add(read);ClassWriter writer=new ClassWriter(ClassWriter.COMPUTE_MAXS);host.accept(writer);definitions.put(BINARY,writer.toByteArray());
        ClassNode external=new ClassNode();external.version=Opcodes.V17;external.access=Opcodes.ACC_PUBLIC;external.name="fixture/ExternalProviderWriter";external.superName="java/lang/Object";MethodNode install=new MethodNode(Opcodes.ACC_PUBLIC|Opcodes.ACC_STATIC,"install","(L"+OWNER+";Ljava/util/function/Supplier;)V",null,null);install.instructions.add(new VarInsnNode(Opcodes.ALOAD,0));install.instructions.add(new VarInsnNode(Opcodes.ALOAD,1));install.instructions.add(new FieldInsnNode(Opcodes.PUTFIELD,OWNER,"featuresPerStep",SUPPLIER));install.instructions.add(new InsnNode(Opcodes.RETURN));external.methods.add(install);writer=new ClassWriter(ClassWriter.COMPUTE_MAXS);external.accept(writer);definitions.put("fixture.ExternalProviderWriter",writer.toByteArray());
        List<URL> urls=new ArrayList<>();for(Path jar:List.of(MERGED,INTEROP,NEO))urls.add(jar.toUri().toURL());Path libraries=TestFixtures.minecraftDir().resolve("libraries");TestFixtures.require(Fixture.MC_LIBRARIES,Files.isDirectory(libraries),"Minecraft libraries required for actual game signatures");try(var files=Files.walk(libraries)){for(Path jar:files.filter(p->p.toString().endsWith(".jar")).sorted().toList())urls.add(jar.toUri().toURL());}
        URLClassLoader loader=new URLClassLoader(urls.toArray(URL[]::new),ClassLoader.getPlatformClassLoader()){@Override protected Class<?> findClass(String name)throws ClassNotFoundException{byte[] bytes=definitions.get(name);return bytes==null?super.findClass(name):defineClass(name,bytes,0,bytes.length);}};
        Class<?> type=loader.loadClass(BINARY);Object owner=unsafe().allocateInstance(type);AtomicInteger computations=new AtomicInteger();Object nativeProvider=loader.loadClass("net.neoforged.neoforge.common.util.Lazy").getMethod("of",Supplier.class).invoke(null,(Supplier<Object>)()->{computations.incrementAndGet();return List.of("initial");});Field strong=null;
        for(Field field:type.getDeclaredFields())if(field.getName().equals("featuresPerStep")){field.setAccessible(true);if(field.getType()==Supplier.class)field.set(owner,nativeProvider);else strong=field;}
        assertNotNull(strong);String carrier=node(original()).methods.stream().filter(m->m.name.equals("<init>")).flatMap(m->Arrays.stream(m.instructions.toArray())).filter(i->i instanceof MethodInsnNode call&&call.name.equals("wrap")&&call.owner.contains("$forbricProvider$")).map(i->((MethodInsnNode)i).owner).findFirst().orElseThrow();strong.set(owner,loader.loadClass(carrier.replace('/','.')).getMethod("wrap",type).invoke(null,owner));
        return new World(loader,type,owner,strong,computations);
    }
    private static sun.misc.Unsafe unsafe()throws Exception{Field field=sun.misc.Unsafe.class.getDeclaredField("theUnsafe");field.setAccessible(true);return (sun.misc.Unsafe)field.get(null);}
    private static byte[] widened()throws Exception{
        String file="accessWidener v2 official\naccessible field "+OWNER+" featuresPerStep "+SUPPLIER+"\nmutable field "+OWNER+" featuresPerStep "+SUPPLIER+"\n";
        return ClassTweakerTransformer.create(List.of(file.getBytes(java.nio.charset.StandardCharsets.UTF_8)),(n,b)->{}).transform(BINARY,original(),null);
    }
    private static byte[] original()throws IOException{TestFixtures.require(Fixture.STAGED,Files.isRegularFile(MERGED),"staged merged base absent — skipping real-bytecode check");return read(MERGED,OWNER);}
    private static byte[] read(Path jar,String owner)throws IOException{try(ZipFile zip=new ZipFile(jar.toFile())){var entry=zip.getEntry(owner+".class");assertNotNull(entry,owner);try(var input=zip.getInputStream(entry)){return input.readAllBytes();}}}
    private static ClassNode node(byte[] bytes){ClassNode node=new ClassNode();new ClassReader(bytes).accept(node,0);return node;}
}
