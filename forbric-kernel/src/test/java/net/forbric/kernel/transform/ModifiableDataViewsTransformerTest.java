package net.forbric.kernel.transform;
import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.*;
import java.util.*;
import java.util.function.Function;
import java.util.zip.ZipFile;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import org.objectweb.asm.tree.analysis.*;
import net.forbric.kernel.TestFixtures;
import net.forbric.kernel.TestFixtures.Fixture;
class ModifiableDataViewsTransformerTest {
    @Test void actualNativeLostDataInitializersAreRestoredAndVerified()throws Exception{
        var resources=resources();var transform=new ModifiableDataViewsTransformer(resources);
        for(String owner:List.of("net/minecraft/world/level/biome/Biome","net/minecraft/world/level/levelgen/structure/Structure","net/minecraft/server/ReloadableServerResources")){
            byte[] raw=resources.apply(owner+".class"),result=transform.transform(owner,raw,null);assertNotSame(raw,result,owner);ClassNode parsed=parse(result);
            int calls=0;for(MethodNode method:parsed.methods){for(AbstractInsnNode i:method.instructions)if(i instanceof MethodInsnNode c&&c.owner.equals("net/forbric/kernel/runtime/KernelModifiableDataViews"))calls++;if(method.name.equals("<init>"))new Analyzer<>(new BasicVerifier()).analyze(owner,method);}
            assertTrue(calls>0,owner);assertArrayEquals(result,transform.transform(owner,result,null),"constructor state is initialized once");
        }
    }
    @Test void aChangedConstructorCannotLeaveTheRecognizedPublicSourceFieldNull()throws Exception{
        var resources=resources();String owner="net/minecraft/world/level/biome/Biome";ClassNode node=parse(resources.apply(owner+".class"));
        MethodNode ctor=node.methods.stream().filter(m->m.name.equals("<init>")).findFirst().orElseThrow();ctor.instructions.insert(new InsnNode(Opcodes.NOP));ClassWriter writer=new ClassWriter(0);node.accept(writer);
        LinkageError error=assertThrows(LinkageError.class,()->new ModifiableDataViewsTransformer(resources).transform(owner,writer.toByteArray(),null));assertTrue(error.getMessage().contains("source initializes this field"));
    }
    private static ClassNode parse(byte[] b){ClassNode n=new ClassNode();new ClassReader(b).accept(n,0);return n;}
    private static Function<String,byte[]> resources()throws Exception{
        Path jar=TestFixtures.stagedRoot().resolve("merged-base/patched-mc-merged-26.2.jar");TestFixtures.requireFiles(Fixture.STAGED,"native source data views",jar);
        Map<String,byte[]> entries=new HashMap<>();try(var zip=new ZipFile(jar.toFile())){var all=zip.entries();while(all.hasMoreElements()){var e=all.nextElement();if(!e.isDirectory())try(var in=zip.getInputStream(e)){entries.put(e.getName(),in.readAllBytes());}}}return entries::get;
    }
}
