package net.forbric.kernel.mixin;
import static org.junit.jupiter.api.Assertions.*;import java.nio.file.*;import java.util.*;import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;import org.objectweb.asm.*;import org.objectweb.asm.tree.*;
import net.forbric.api.Ecosystem;

class MixinUnusedArgumentObserverAdapterTest {
    @Test void actualBodyReadsTheSharedTagExactlyOnceWithoutChangingItsInstructions()throws Exception {
        Inputs input=inputs();MethodNode handler=method(input.source,"swapFluidTag");String fingerprint=MixinInstructionFingerprint.hash(handler);
        assertEquals(1,MixinUnusedArgumentObserverAdapter.adapt(input.source,input.nodes::get,n->input.original));assertEquals(fingerprint,MixinInstructionFingerprint.hash(handler));
        ClassNode executable=new ClassNode();executable.name="fixture/observer/ActualCallback";executable.superName="java/lang/Object";executable.version=Opcodes.V21;executable.access=Opcodes.ACC_PUBLIC;
        MethodNode copy=new MethodNode(handler.access,handler.name,handler.desc,handler.signature,null);handler.accept(copy);copy.access=Opcodes.ACC_PUBLIC|Opcodes.ACC_STATIC;copy.desc="(Ljava/lang/Object;Ljava/lang/Object;Lcom/llamalad7/mixinextras/sugar/ref/LocalRef;)Ljava/lang/Object;";copy.visibleParameterAnnotations=null;copy.invisibleParameterAnnotations=null;copy.signature=null;
        // Prefixing the unused original 'this' keeps every original local slot and instruction; only game value types are renamed.
        ClassNode holder=new ClassNode();holder.name=input.source.name;holder.superName="java/lang/Object";holder.version=Opcodes.V21;holder.access=Opcodes.ACC_PUBLIC;holder.methods.add(copy);
        ClassNode mapped=new ClassNode();holder.accept(new org.objectweb.asm.commons.ClassRemapper(mapped,new org.objectweb.asm.commons.SimpleRemapper(Map.of(input.source.name,executable.name,"net/minecraft/tags/TagKey","java/lang/Object"))));
        ClassWriter writer=new ClassWriter(ClassWriter.COMPUTE_FRAMES|ClassWriter.COMPUTE_MAXS);mapped.accept(writer);byte[] bytes=writer.toByteArray();
        ClassLoader parent=new java.net.URLClassLoader(new java.net.URL[]{Path.of(System.getProperty("forbric.mixinExtrasForTests")).toUri().toURL()},getClass().getClassLoader());
        Class<?> type=new ClassLoader(parent){Class<?> define(){return defineClass(null,bytes,0,bytes.length);}}.define();Class<?> reference=Class.forName("com.llamalad7.mixinextras.sugar.ref.LocalRef",false,type.getClassLoader());
        AtomicInteger reads=new AtomicInteger();Object tag=new Object(),original=new Object();Object ref=java.lang.reflect.Proxy.newProxyInstance(type.getClassLoader(),new Class<?>[]{reference},(proxy,method,args)->{assertEquals("get",method.getName());reads.incrementAndGet();return tag;});
        assertSame(tag,type.getDeclaredMethods()[0].invoke(null,null,original,ref));assertEquals(1,reads.get());
    }
    @Test void anOldConsumerThatUsesItsTagIsNotAnUnusedArgumentObserver()throws Exception {
        Inputs input=inputs();MethodNode consumer=method(input.original,"jumpInLiquid");consumer.instructions.insert(new InsnNode(Opcodes.POP));consumer.instructions.insert(new VarInsnNode(Opcodes.ALOAD,1));
        Inputs wrongWrite=input;
        assertEquals(0,MixinUnusedArgumentObserverAdapter.adapt(wrongWrite.source,wrongWrite.nodes::get,n->wrongWrite.original));
    }
    @Test void aWrongTypedShareWriteOrChangedGuardDeclinesTheMove()throws Exception {
        Inputs input=inputs();MethodNode producer=method(input.source,"tryOtherFluidsForFluidJumping");
        MethodInsnNode set=Arrays.stream(producer.instructions.toArray()).filter(MethodInsnNode.class::isInstance).map(MethodInsnNode.class::cast).filter(c->c.name.equals("set")).findFirst().orElseThrow();
        InsnList changed=new InsnList();changed.add(new InsnNode(Opcodes.POP));changed.add(new LdcInsnNode("wrong"));producer.instructions.insertBefore(set,changed);
        Inputs badType=input;
        assertEquals(0,MixinUnusedArgumentObserverAdapter.adapt(badType.source,badType.nodes::get,n->badType.original));
        input=inputs();MethodNode host=method(input.nodes.get(input.original.name),"aiStep");
        for(AbstractInsnNode instruction:host.instructions)if(instruction instanceof MethodInsnNode call&&call.name.equals("isInShallowFluid")){call.name="differentPredicate";break;}
        Inputs guard=input;assertEquals(0,MixinUnusedArgumentObserverAdapter.adapt(guard.source,guard.nodes::get,n->guard.original));
    }
    private record Inputs(ClassNode source,Map<String,ClassNode> nodes,ClassNode original) { }
    private static Inputs inputs()throws Exception {
        Path api=Path.of(System.getProperty("forbric.fabricApi")),base=Path.of(System.getProperty("forbric.predicateBase"));
        net.forbric.kernel.TestFixtures.require(net.forbric.kernel.TestFixtures.Fixture.STAGED,Files.isRegularFile(api)&&Files.isRegularFile(base),"actual unused-argument observer inputs required");
        ClassNode source=null;try(var jar=new java.util.zip.ZipFile(api.toFile())){var module=jar.stream().filter(e->e.getName().startsWith("META-INF/jars/fabric-content-registries-v0-")).findFirst().orElseThrow();try(var nested=new java.util.zip.ZipInputStream(jar.getInputStream(module))){for(java.util.zip.ZipEntry entry;(entry=nested.getNextEntry())!=null;)if(entry.getName().equals("net/fabricmc/fabric/mixin/content/registry/fluid/LivingEntityMixin.class")){source=parse(nested.readAllBytes());break;}}}
        Map<String,ClassNode> nodes=new HashMap<>();ClassNode original;
        try(var jar=new java.util.zip.ZipFile(base.toFile())){for(String owner:List.of("net/minecraft/world/entity/LivingEntity","net/minecraft/world/entity/Entity","net/minecraft/world/entity/EntityFluidInteraction"))nodes.put(owner,parse(jar.getInputStream(jar.getEntry(owner+".class")).readAllBytes()));String owner="net/minecraft/world/entity/LivingEntity";original=new NativeGameReferences(p->{try{var e=jar.getEntry(p);return e==null?null:jar.getInputStream(e).readAllBytes();}catch(Exception e){return null;}}).get(Ecosystem.FABRIC,owner);}
        return new Inputs(source,nodes,original);
    }
    private static MethodNode method(ClassNode node,String name){return node.methods.stream().filter(m->m.name.equals(name)).findFirst().orElseThrow();}
    private static ClassNode parse(byte[] bytes){ClassNode n=new ClassNode();new ClassReader(bytes).accept(n,0);return n;}
}
