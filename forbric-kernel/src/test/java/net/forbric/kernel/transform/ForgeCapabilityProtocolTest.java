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
    /**
     * -Dforbric.forgeCapabilities=off must still prove every row the staged base's own manifest requires (the loader
     * refuses to define a root without that proof), and the proof must stay a proof: lost state is still rejected.
     */
    @Test void withDispatchOffEveryRowOfTheRealManifestIsStillProvedAndLostStateIsStillRejected()throws Exception{
        Function<String,byte[]> reader=reader();byte[] manifest=reader.apply("META-INF/forbric/required-ancestor-compositions.tsv");
        TestFixtures.require(Fixture.STAGED,manifest!=null,"the staged merged base predates required-ancestor-compositions.tsv");
        List<AncestorComposition.Requirement> rows=new ArrayList<>();
        for(String line:new String(manifest,java.nio.charset.StandardCharsets.UTF_8).split("\\R")){
            if(line.isBlank()||line.startsWith("#"))continue;String[] p=line.split("\t",-1);rows.add(new AncestorComposition.Requirement(p[0],p[1],p[2]));
        }
        assertEquals(ForgeCapabilityCompositionTransformer.ROOTS,rows.stream().map(AncestorComposition.Requirement::owner).collect(java.util.stream.Collectors.toSet()),
                "premise: the base requires exactly the capability roots composed");
        System.setProperty(ForgeCapabilityCompositionTransformer.PROPERTY,"off");
        try{
        var off=new ForgeCapabilityCompositionTransformer(reader,true);assertFalse(off.dispatches());assertFalse(off.transferFallback());
        for(var requirement:rows){
            String owner=requirement.owner();byte[] emitted=off.transform(owner.replace('/','.'),reader.apply(owner+".class"),null);
            assertTrue(off.proves(requirement,emitted,reader),owner+" must define with dispatch off");
            ClassNode lost=parse(emitted);lost.fields.removeIf(f->f.name.equals(ForgeCapabilityCompositionTransformer.FIELD));
            ClassWriter writer=new ClassWriter(0);lost.accept(writer);
            assertFalse(off.proves(requirement,writer.toByteArray(),reader),owner+": a definition without the composed state is still refused");
            assertFalse(new ForgeCapabilityCompositionTransformer(reader,true,false).proves(requirement,emitted,reader),
                    "a proof certifies what its own instance emitted, not what the switch says");
        }
        }finally{System.clearProperty(ForgeCapabilityCompositionTransformer.PROPERTY);}
    }
    private static ClassNode parse(byte[] b){ClassNode n=new ClassNode();new ClassReader(b).accept(n,0);return n;}
    private static Function<String,byte[]> reader()throws Exception{
        Path root=TestFixtures.stagedRoot(),merged=root.resolve("merged-base/patched-mc-merged-26.2.jar"),forge=root.resolve("forge-runtime/forge-runtime.jar"),runtime=Path.of(System.getProperty("forbric.testRuntimeClasses","build/classes/java/runtime"));
        TestFixtures.requireFiles(Fixture.STAGED,"composition source and platform",merged,forge);TestFixtures.require(Fixture.GAME_SIDE,Files.isDirectory(runtime),"compiled game runtime");
        // On demand, with the same precedence (compiled game side, then merged base, then carrier): the merged base
        // alone is ~200 MB unpacked, and holding it whole per test ran the suite's test JVM out of heap.
        Map<String,byte[]> cache=new java.util.concurrent.ConcurrentHashMap<>();
        return path->{byte[] hit=cache.get(path);if(hit!=null)return hit;byte[] found=read(path,runtime,List.of(merged,forge));if(found!=null)cache.put(path,found);return found;};
    }
    private static byte[] read(String path,Path runtime,List<Path> jars){
        try{
            Path file=runtime.resolve(path);if(Files.isRegularFile(file))return Files.readAllBytes(file);
            for(Path jar:jars)try(var zip=new ZipFile(jar.toFile())){var entry=zip.getEntry(path);if(entry!=null&&!entry.isDirectory())try(var in=zip.getInputStream(entry)){return in.readAllBytes();}}
            return null;
        }catch(java.io.IOException unreadable){throw new java.io.UncheckedIOException(unreadable);}
    }
}
