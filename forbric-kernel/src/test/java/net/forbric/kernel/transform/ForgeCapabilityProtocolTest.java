package net.forbric.kernel.transform;
import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.*;
import java.util.*;
import java.util.function.Function;
import java.util.zip.ZipFile;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import net.forbric.api.AncestorComposition;
import net.forbric.kernel.TestFixtures;
import net.forbric.kernel.TestFixtures.Fixture;
class ForgeCapabilityProtocolTest {
    @Test void actualSourceStateAndFinalDelegatesProveEveryRequiredRoot()throws Exception{
        Function<String,byte[]> reader=reader();
        for(String owner:ForgeCapabilityCompositionTransformer.ROOTS){
            byte[] original=reader.apply(owner+".class");var transformer=new ForgeCapabilityCompositionTransformer(reader);byte[] emitted=transformer.transform(owner.replace('/','.'),original,null);
            ClassNode nativeRoot=new net.forbric.kernel.mixin.NativeGameReferences(reader).get(net.forbric.api.Ecosystem.FORGE,owner);assertNotNull(nativeRoot);
            ClassNode composed=parse(emitted);var requirement=new AncestorComposition.Requirement(owner,composed.superName,nativeRoot.superName);
            assertTrue(transformer.proves(requirement,emitted,reader),owner);
            MethodNode delegate=composed.methods.stream().filter(m->m.name.equals("gatherCapabilities")&&m.desc.equals("()V")).findFirst().orElseThrow();delegate.instructions.clear();delegate.instructions.add(new InsnNode(Opcodes.RETURN));
            ClassWriter writer=new ClassWriter(0);composed.accept(writer);assertFalse(transformer.proves(requirement,writer.toByteArray(),reader),"the final delegate lost state initialization");
            assertFalse(transformer.proves(new AncestorComposition.Requirement(owner,requirement.retainedSuperclass(),"unknown/OtherState"),emitted,reader));
            if(!owner.equals(ForgeCapabilityCompositionTransformer.LEVEL)){
                for(MethodNode ctor:parse(emitted).methods)if(ctor.name.equals("<init>"))assertTrue(Arrays.stream(ctor.instructions.toArray()).anyMatch(i->i instanceof MethodInsnNode call&&call.name.equals("gatherCapabilities")),"actual eager native constructor gather must survive");
            }
        }
    }
    @Test void anAdditionalSourceProviderStateEffectInvalidatesTheCertificate()throws Exception{
        Function<String,byte[]> reader=reader();String owner=ForgeCapabilityCompositionTransformer.ENTITY;var transformer=new ForgeCapabilityCompositionTransformer(reader);byte[] emitted=transformer.transform(owner,reader.apply(owner+".class"),null);
        String provider=new net.forbric.kernel.mixin.NativeGameReferences(reader).get(net.forbric.api.Ecosystem.FORGE,owner).superName;
        var requirement=new AncestorComposition.Requirement(owner,parse(emitted).superName,provider);
        ClassNode changed=parse(reader.apply(provider+".class"));changed.fields.add(new FieldNode(Opcodes.ACC_PRIVATE,"unknownState","I",null,null));ClassWriter writer=new ClassWriter(0);changed.accept(writer);byte[] state=writer.toByteArray();
        assertFalse(transformer.proves(requirement,emitted,p->p.equals(provider+".class")?state:reader.apply(p)),"unknown native state cannot be dismissed merely because getCapability exists");
    }
    private static ClassNode parse(byte[] b){ClassNode n=new ClassNode();new ClassReader(b).accept(n,0);return n;}
    private static Function<String,byte[]> reader()throws Exception{
        Path root=TestFixtures.stagedRoot(),merged=root.resolve("merged-base/patched-mc-merged-26.2.jar"),forge=root.resolve("forge-runtime/forge-runtime.jar"),runtime=Path.of(System.getProperty("forbric.testRuntimeClasses","build/classes/java/runtime"));
        TestFixtures.requireFiles(Fixture.STAGED,"composition source and platform",merged,forge);TestFixtures.require(Fixture.GAME_SIDE,Files.isDirectory(runtime),"compiled game runtime");
        Map<String,byte[]> bytes=new HashMap<>();for(Path jar:List.of(merged,forge))try(var zip=new ZipFile(jar.toFile())){var entries=zip.entries();while(entries.hasMoreElements()){var e=entries.nextElement();if(!e.isDirectory())try(var in=zip.getInputStream(e)){bytes.putIfAbsent(e.getName(),in.readAllBytes());}}}
        try(var paths=Files.walk(runtime)){for(Path path:paths.filter(Files::isRegularFile).toList())bytes.put(runtime.relativize(path).toString(),Files.readAllBytes(path));}return bytes::get;
    }
}
