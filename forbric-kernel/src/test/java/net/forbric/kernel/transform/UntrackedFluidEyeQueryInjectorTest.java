package net.forbric.kernel.transform;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Path;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.*;
import org.objectweb.asm.tree.analysis.*;

@ResourceLock("system-properties")
class UntrackedFluidEyeQueryInjectorTest {
    private static final Path MERGED = Path.of(System.getProperty("forbric.stagedRoot", "../forbric-loader/run"), net.forbric.kernel.TestFixtures.BASE_JAR);
    @AfterEach void reset() { System.clearProperty(UntrackedFluidEyeQueryInjector.PROPERTY); }
    @Test void eyeQueriesUseTheInteractionAndKeepNativeQueriesBehindTheUntrackedGuard() throws Exception {
        var repair = new UntrackedFluidEyeQueryInjector();
        for (String name : new String[] {UntrackedFluidEyeQueryInjector.ENTITY, UntrackedFluidEyeQueryInjector.INTERACTION}) {
            byte[] original = NativeCoremodParityTest.read(MERGED, name.replace('.', '/'));
            byte[] bytes = repair.transform(name, original, null); assertNotSame(original, bytes);
            ClassNode node = new ClassNode(); new ClassReader(bytes).accept(node, 0);
            MethodNode method = node.methods.stream().filter(m -> m.name.equals("isEyeInFluid") && m.desc.equals(UntrackedFluidEyeQueryInjector.DESC)).findFirst().orElseThrow();
            new Analyzer<>(new BasicVerifier()).analyze(node.name, method);
            assertSame(bytes, repair.transform(name, bytes, null));
            if (name.equals(UntrackedFluidEyeQueryInjector.INTERACTION)) {
                assertTrue(java.util.Arrays.stream(method.instructions.toArray()).anyMatch(i -> i instanceof MethodInsnNode c && c.name.equals("containsKey")));
                assertTrue(java.util.Arrays.stream(method.instructions.toArray()).anyMatch(i -> i instanceof MethodInsnNode c && c.name.equals("getFluidTypeByTag")), "registered trackers keep their native path");
            }
            System.setProperty(UntrackedFluidEyeQueryInjector.PROPERTY, "off");
            assertSame(original, repair.transform(name, original, null)); System.clearProperty(UntrackedFluidEyeQueryInjector.PROPERTY);
        }
    }
}
