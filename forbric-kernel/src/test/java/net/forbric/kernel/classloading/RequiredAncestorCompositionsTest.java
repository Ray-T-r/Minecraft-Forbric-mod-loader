package net.forbric.kernel.classloading;
import static org.junit.jupiter.api.Assertions.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import net.forbric.api.AncestorComposition;
class RequiredAncestorCompositionsTest {
    @TempDir java.nio.file.Path directory;
    static byte[] manifest(String row){return ("# forbric-required-ancestor-composition-v1\n"+row+"\n").getBytes(StandardCharsets.UTF_8);}
    @Test void arbitraryStateProtocolNeedsAProofOfTheActualFinalDefinition(){
        RequiredAncestorCompositions contracts=new RequiredAncestorCompositions();byte[] finalBytes={1,2,3};
        var reader=(java.util.function.Function<String,byte[]>)(p->p.equals(RequiredAncestorCompositions.RESOURCE)?manifest("unknown/Child\tunknown/Kept\tunknown/Removed"):null);
        LinkageError error=assertThrows(LinkageError.class,()->contracts.verify("unknown.Child",finalBytes,reader));assertTrue(error.getMessage().contains("unknown/Removed"));
        contracts.register((r,b,res)->r.sourceSuperclass().equals("unknown/Removed")&&Arrays.equals(b,finalBytes));contracts.verify("unknown.Child",finalBytes,reader);
        assertThrows(LinkageError.class,()->contracts.verify("unknown.Child",new byte[]{4},reader));
        assertThrows(LinkageError.class,()->new RequiredAncestorCompositions().verify("unknown.Child",finalBytes,reader),"proofs must remain loader-local");
    }
    @Test void malformedConflictingOrDuplicateRequirementsCannotDisappear(){
        assertThrows(LinkageError.class,()->RequiredAncestorCompositions.parse("bad".getBytes()));
        assertThrows(LinkageError.class,()->RequiredAncestorCompositions.parse(manifest("a/B\ta/C\ta/C")));
        assertThrows(LinkageError.class,()->RequiredAncestorCompositions.parse(manifest("a/B\ta/C\ta/D\na/B\ta/C\ta/D")));
        assertTrue(RequiredAncestorCompositions.parse(null).isEmpty());
    }
    @Test void theRealLoaderChecksFinalMixinBytesBeforeSuccessfulDefinition()throws Exception{
        java.nio.file.Path game=java.nio.file.Files.createDirectories(directory.resolve("game")),metadata=java.nio.file.Files.createDirectories(directory.resolve("META-INF/forbric"));
        byte[] raw=type(false),woven=type(true);java.nio.file.Files.write(game.resolve("Composed.class"),raw);
        java.nio.file.Files.write(metadata.resolve("required-ancestor-compositions.tsv"),manifest("game/Composed\tjava/lang/Object\tunknown/State"));
        try(var rejected=new ForbricClassLoader(new java.net.URL[]{directory.toUri().toURL()},getClass().getClassLoader())){
            assertThrows(LinkageError.class,()->rejected.loadClass("game.Composed"));assertFalse(rejected.isClassLoadedByName("game.Composed"));
        }
        try(var loader=new ForbricClassLoader(new java.net.URL[]{directory.toUri().toURL()},getClass().getClassLoader())){
            loader.setMixinTransformer((name,bytes)->name.equals("game.Composed")?woven:bytes);
            var checked=new java.util.concurrent.atomic.AtomicReference<byte[]>();
            loader.registerAncestorComposition((r,bytes,resources)->{checked.set(bytes);return Arrays.equals(bytes,woven);});
            assertNotNull(loader.loadClass("game.Composed").getField("finalState"));assertArrayEquals(woven,checked.get());
        }
    }
    private static byte[] type(boolean finalState){
        var writer=new org.objectweb.asm.ClassWriter(0);writer.visit(org.objectweb.asm.Opcodes.V21,org.objectweb.asm.Opcodes.ACC_PUBLIC,"game/Composed",null,"java/lang/Object",null);
        if(finalState)writer.visitField(org.objectweb.asm.Opcodes.ACC_PUBLIC,"finalState","I",null,null).visitEnd();writer.visitEnd();return writer.toByteArray();
    }
}
