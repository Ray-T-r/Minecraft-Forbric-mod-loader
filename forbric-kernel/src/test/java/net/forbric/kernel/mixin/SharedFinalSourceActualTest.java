package net.forbric.kernel.mixin;

import static org.junit.jupiter.api.Assertions.*;
import java.lang.reflect.Method;
import java.nio.file.*;
import java.util.*;
import java.util.function.Function;
import net.forbric.kernel.TestFixtures;
import net.forbric.kernel.TestFixtures.Fixture;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;

/** Replays actual source bodies against a captured final definition, including real MixinExtras relay bytecode.
 * Supply -Dforbric.sharedFinalDefined=<defined-classes directory or final Entity.class> along with staged inputs. */
@ResourceLock("system-properties")
@ResourceLock("DefinedMethodContracts")
class SharedFinalSourceActualTest {
    private static final String HOST="net/minecraft/world/entity/Entity";
    @AfterEach void reset(){SharedFinalSourceCertifier.resetForTests();}
    @Test void actualSourceAndRealFinalMixinShapeCloseAllNineMethods()throws Exception{
        Replay fixture=replay();String before=fingerprint(fixture.target);var result=SharedFinalSourceCertifier.certify(fixture.target);
        assertEquals(Set.of(fixture.key),result.keySet());assertEquals(9,result.get(fixture.key).methods().size());assertEquals(before,fingerprint(fixture.target),"proof must not rewrite executable lambda/bootstrap arguments");
    }
    @Test void mutatingEitherRealSourceBodyRevokesTheWholeActualContinuation()throws Exception{
        for(String source:List.of("checkIfUnderSwimmableFluid","checkIfStandingInSwimmableFluid")){
            Replay fixture=replay();assertEquals(1,SharedFinalSourceCertifier.certify(fixture.target).size());MethodNode method=fixture.target.methods.stream().filter(m->m.name.endsWith("$"+source)&&!m.name.contains("forbricshared")).findFirst().orElseThrow();
            AbstractInsnNode returned=Arrays.stream(method.instructions.toArray()).filter(i->i.getOpcode()==Opcodes.IRETURN).reduce((a,b)->b).orElseThrow();InsnList invert=new InsnList();invert.add(new InsnNode(Opcodes.ICONST_1));invert.add(new InsnNode(Opcodes.IXOR));method.instructions.insertBefore(returned,invert);assertTrue(SharedFinalSourceCertifier.certify(fixture.target).isEmpty(),source);
        }
    }
    private record Replay(ClassNode target,String key) { }
    @SuppressWarnings("unchecked") private static Replay replay()throws Exception{
        Path definition=Path.of(System.getProperty("forbric.sharedFinalDefined",TestFixtures.stagedRoot().resolve("shared-final-definitions").toString()));TestFixtures.require(Fixture.OPT_IN,Files.exists(definition),"actual shared final definition: "+definition);
        // Reuse the existing actual native alignment fixture, including its tracked source metrics and carrier view.
        Method factory=MixinSharedPredicateSeamTest.class.getDeclaredMethod("inputs");factory.setAccessible(true);Object inputs=factory.invoke(null);
        ClassNode mixin=(ClassNode)value(inputs,"guest");Function<String,ClassNode> classes=(Function<String,ClassNode>)value(inputs,"classes"),source=(Function<String,ClassNode>)value(inputs,"source"),carrier=(Function<String,ClassNode>)value(inputs,"carrier");
        MethodNode under=copy(mixin.methods.stream().filter(m->m.name.equals("checkIfUnderSwimmableFluid")).findFirst().orElseThrow()),ground=copy(mixin.methods.stream().filter(m->m.name.equals("checkIfStandingInSwimmableFluid")).findFirst().orElseThrow());assertEquals(2,MixinSharedPredicateSeam.adapt(mixin,classes,source,carrier));
        ClassNode actual=readDefinition(definition);assertEquals(HOST,actual.name);
        List<MethodNode> generated=mixin.methods.stream().filter(m->m.name.contains("forbricshared")).map(SharedFinalSourceActualTest::copy).toList();MethodNode u=generated.stream().filter(m->m.name.contains("Under")&&!m.name.endsWith("query")).findFirst().orElseThrow(),g=generated.stream().filter(m->m.name.contains("Standing")&&!m.name.endsWith("typed")).findFirst().orElseThrow(),q=generated.stream().filter(m->m.name.endsWith("$query")).findFirst().orElseThrow();
        MethodNode actualQuery=actual.methods.stream().filter(m->m.name.equals(q.name)||m.name.endsWith("$"+q.name)).findFirst().orElseThrow();String newKey=literal(q),oldKey=literal(actualQuery);
        // Registration identities include the current native-reader epoch. Replay the captured registration's
        // opaque identity, while preserving every original/source instruction and all other generated literals.
        for(MethodNode method:generated)for(AbstractInsnNode instruction:method.instructions)if(instruction instanceof LdcInsnNode literal&&newKey.equals(literal.cst))literal.cst=oldKey;
        MethodInsnNode ua=firstCall(u),ga=firstCall(g);SharedFinalSourceCertifier.resetForTests();SharedFinalSourceCertifier.remember(oldKey,HOST,mixin.name,under,ground,u,g,generated.stream().filter(m->m!=u&&m!=g).toList(),ua,ga);return new Replay(actual,oldKey);
    }
    private static Object value(Object object,String name)throws Exception{Method method=object.getClass().getDeclaredMethod(name);method.setAccessible(true);return method.invoke(object);}
    private static MethodInsnNode firstCall(MethodNode method){return Arrays.stream(method.instructions.toArray()).filter(MethodInsnNode.class::isInstance).map(MethodInsnNode.class::cast).findFirst().orElseThrow();}
    private static String literal(MethodNode method){return (String)Arrays.stream(method.instructions.toArray()).filter(LdcInsnNode.class::isInstance).map(LdcInsnNode.class::cast).findFirst().orElseThrow().cst;}
    private static MethodNode copy(MethodNode method){MethodNode copy=new MethodNode(method.access,method.name,method.desc,null,null);method.accept(copy);for(AbstractInsnNode instruction:copy.instructions)if(instruction instanceof InvokeDynamicInsnNode dynamic)dynamic.bsmArgs=dynamic.bsmArgs.clone();return copy;}
    private static ClassNode readDefinition(Path path)throws Exception{
        if(Files.isDirectory(path)){Path directory=Files.isRegularFile(path.resolve("definitions.tsv"))?path:Files.list(path).filter(p->Files.isRegularFile(p.resolve("definitions.tsv"))).findFirst().orElseThrow();String hash=Files.readAllLines(directory.resolve("definitions.tsv")).stream().filter(line->line.startsWith(HOST+"\t")).findFirst().orElseThrow().split("\t")[1];path=directory.resolve("blobs/"+hash+".class");}
        ClassNode node=new ClassNode();new ClassReader(Files.readAllBytes(path)).accept(node,ClassReader.SKIP_DEBUG|ClassReader.SKIP_FRAMES);return node;
    }
    private static String fingerprint(ClassNode node){return node.methods.stream().map(m->m.name+m.desc+MixinInstructionFingerprint.hash(m)).reduce("",(a,b)->a+"\n"+b);}
}
