package net.forbric.kernel.boot;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import java.nio.file.*;
import java.util.zip.ZipFile;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;

class KernelForgeLoadingStateTest {
    @AfterEach void reset() {System.clearProperty("forbric.forgeClientInit");}

    @Test void readinessChangesTheActualCarrierFieldAndLeavesFailureWritesEffective() throws Exception {
        ClassLoader loader = fixture();
        Class<?> carrier = loader.loadClass("net.minecraftforge.fml.ModLoader");
        assertEquals(false, carrier.getMethod("isLoadingStateValid").invoke(null));
        KernelLifecycle.setForgeLoadingState(loader, true);
        assertEquals(true, carrier.getMethod("isLoadingStateValid").invoke(null));
        carrier.getMethod("nativeFailure").invoke(null);
        assertEquals(false, carrier.getMethod("isLoadingStateValid").invoke(null), "a later carrier failure must remain effective");
        KernelLifecycle.setForgeLoadingState(loader, true);
        KernelLifecycle.setForgeLoadingState(loader, false);
        assertEquals(false, carrier.getMethod("isLoadingStateValid").invoke(null));
    }

    @Test void disabledSeedingDoesNotChangeTheCarrierState() throws Exception {
        ClassLoader loader = fixture();
        Class<?> carrier = loader.loadClass("net.minecraftforge.fml.ModLoader");
        System.setProperty("forbric.forgeClientInit", "off");
        KernelLifecycle.setForgeLoadingState(loader, true);
        assertEquals(false, carrier.getMethod("isLoadingStateValid").invoke(null));
    }

    @Test void theActualForgeCarrierStillOwnsItsGuardAndFailurePaths() throws Exception {
        Path jar = Path.of(System.getenv().getOrDefault("FORBRIC_OLD", "../forbric-loader"), "run/forge-runtime/forge-runtime.jar");
        assumeTrue(Files.isRegularFile(jar));
        ClassNode node = new ClassNode();
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            new ClassReader(zip.getInputStream(zip.getEntry("net/minecraftforge/fml/ModLoader.class")).readAllBytes()).accept(node, 0);
        }
        FieldNode field = node.fields.stream().filter(f -> f.name.equals("loadingStateValid")).findFirst().orElseThrow();
        assertEquals("Z", field.desc);
        assertEquals(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC, field.access & (Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC));
        assertNull(field.value);
        MethodNode getter = node.methods.stream().filter(m -> m.name.equals("isLoadingStateValid")).findFirst().orElseThrow();
        assertTrue(java.util.Arrays.stream(getter.instructions.toArray()).anyMatch(i -> i instanceof FieldInsnNode f && f.name.equals("loadingStateValid")));
        MethodNode failure = node.methods.stream().filter(m -> m.name.equals("waitForTransition")).findFirst().orElseThrow();
        assertTrue(java.util.Arrays.stream(failure.instructions.toArray()).anyMatch(i -> i instanceof FieldInsnNode f && f.getOpcode() == Opcodes.PUTSTATIC && f.name.equals("loadingStateValid")));
    }

    @Test void readyIsPublishedAfterConstructionAndAnOuterFailureClosesItAgain() throws Exception {
        ClassNode node = new ClassNode();
        try (var input = KernelLifecycle.class.getResourceAsStream("KernelLifecycle.class")) {new ClassReader(input).accept(node, 0);}
        MethodNode method = node.methods.stream().filter(m -> m.name.equals("registerNeoForgeContent")).findFirst().orElseThrow();
        int construction = -1, ready = -1, falseCalls = 0;
        for (int i=0; i<method.instructions.size(); i++) {
            var instruction = method.instructions.get(i);
            if (!(instruction instanceof MethodInsnNode call)) continue;
            if (call.name.equals("constructMods")) construction = i;
            if (!call.name.equals("setForgeLoadingState")) continue;
            var previous = instruction.getPrevious();while (previous.getOpcode() < 0) previous = previous.getPrevious();
            if (previous.getOpcode() == Opcodes.ICONST_1) {assertEquals(-1, ready);ready=i;}
            if (previous.getOpcode() == Opcodes.ICONST_0) falseCalls++;
        }
        assertTrue(construction >= 0 && ready > construction, "do not open guarded event delivery before containers are published");
        assertEquals(2, falseCalls, "reset when entering the kernel stage and when its outer setup fails");
    }

    private static ClassLoader fixture() {
        String name = "net/minecraftforge/fml/ModLoader";
        ClassWriter writer = new ClassWriter(0);writer.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, name, null, "java/lang/Object", null);
        writer.visitField(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC, "loadingStateValid", "Z", null, null).visitEnd();
        var getter = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "isLoadingStateValid", "()Z", null, null);
        getter.visitCode();getter.visitFieldInsn(Opcodes.GETSTATIC, name, "loadingStateValid", "Z");getter.visitInsn(Opcodes.IRETURN);getter.visitMaxs(1,0);getter.visitEnd();
        var fail = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "nativeFailure", "()V", null, null);
        fail.visitCode();fail.visitInsn(Opcodes.ICONST_0);fail.visitFieldInsn(Opcodes.PUTSTATIC,name,"loadingStateValid","Z");fail.visitInsn(Opcodes.RETURN);fail.visitMaxs(1,0);fail.visitEnd();writer.visitEnd();
        byte[] bytes=writer.toByteArray();
        return new ClassLoader(KernelForgeLoadingStateTest.class.getClassLoader()) {
            @Override protected Class<?> findClass(String binary) throws ClassNotFoundException {
                if (binary.equals(name.replace('/','.'))) return defineClass(binary,bytes,0,bytes.length);
                throw new ClassNotFoundException(binary);
            }
        };
    }
}
