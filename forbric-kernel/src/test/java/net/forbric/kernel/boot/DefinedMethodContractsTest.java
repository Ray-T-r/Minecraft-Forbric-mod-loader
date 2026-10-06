package net.forbric.kernel.boot;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.MethodNode;

@ResourceLock("DefinedMethodContracts")
class DefinedMethodContractsTest {
    @BeforeEach @AfterEach void reset() { DefinedMethodContracts.resetForTests(); }

    @Test void anUnobservedOrConcreteOverrideCannotPassTheDefaultContract() throws Exception {
        byte[] bytes = bytes(DefaultRoute.class);
        var contract = contract(bytes, "evaluate");
        assertFalse(DefinedMethodContracts.validates(new DefaultReceiver(), contract));
        DefinedMethodContracts.observe(DefaultRoute.class.getClassLoader(), DefaultRoute.class.getName(), bytes);
        assertTrue(DefinedMethodContracts.validates(new DefaultReceiver(), contract));
        assertFalse(DefinedMethodContracts.validates(new OverrideReceiver(), contract));
    }

    @Test void observationBeforeTheContractAndAChangedFinalDefaultAreHandled() throws Exception {
        byte[] bytes = bytes(DefaultRoute.class);
        DefinedMethodContracts.observe(DefaultRoute.class.getClassLoader(), DefaultRoute.class.getName(), bytes);
        var original = contract(bytes, "evaluate");
        assertTrue(DefinedMethodContracts.validates(new DefaultReceiver(), original));
        ClassNode changed = parse(bytes);
        MethodNode method = method(changed, "evaluate");
        method.instructions.insertBefore(method.instructions.getLast(), new InsnNode(Opcodes.ICONST_1));
        method.instructions.insertBefore(method.instructions.getLast(), new InsnNode(Opcodes.IADD));
        byte[] finalBytes = write(changed);
        DefinedMethodContracts.observe(DefaultRoute.class.getClassLoader(), DefaultRoute.class.getName(), finalBytes);
        assertFalse(DefinedMethodContracts.validates(new DefaultReceiver(), original));
        assertTrue(DefinedMethodContracts.observed(DefaultRoute.class.getClassLoader(), contract(finalBytes, "evaluate")));
    }

    @Test void inheritedClassImplementationsAndPrivateHelperWitnessesAreRecordedToo() throws Exception {
        byte[] bytes = bytes(ClassRoute.class);
        DefinedMethodContracts.observe(ClassRoute.class.getClassLoader(), ClassRoute.class.getName(), bytes);
        assertTrue(DefinedMethodContracts.validates(new ClassReceiver(), contract(bytes, "evaluate")));
        assertTrue(DefinedMethodContracts.observed(ClassRoute.class.getClassLoader(), contract(bytes, "projection")));
        assertFalse(DefinedMethodContracts.validates(new ClassReceiver(), contract(bytes, "projection")));
        assertTrue(DefinedMethodContracts.observed(ClassRoute.class.getClassLoader(), contract(bytes, "staticHelper")));
        assertFalse(DefinedMethodContracts.validates(new ClassReceiver(), contract(bytes, "staticHelper")));
    }

    @Test void identicalBinaryNamesCannotBorrowAnotherDefiningLoadersHash() throws Exception {
        String name = "fixture/contracts/SameSymbol";
        byte[] first = generated(name, 1), second = generated(name, 2);
        EqualLoader a = new EqualLoader(), b = new EqualLoader();
        Class<?> ca = a.define(first), cb = b.define(second);
        var contract = contract(first, "evaluate");
        DefinedMethodContracts.observe(a, ca.getName(), first);
        assertTrue(DefinedMethodContracts.validates(ca.getConstructor().newInstance(), contract));
        assertFalse(DefinedMethodContracts.validates(cb.getConstructor().newInstance(), contract));
        DefinedMethodContracts.observe(b, cb.getName(), second);
        assertFalse(DefinedMethodContracts.validates(cb.getConstructor().newInstance(), contract));
        assertTrue(DefinedMethodContracts.validates(cb.getConstructor().newInstance(), contract(second, "evaluate")));
    }

    @Test void descriptorsAndMalformedObservationsFailClosed() throws Exception {
        byte[] bytes = bytes(DefaultRoute.class);
        var expected = contract(bytes, "evaluate");
        DefinedMethodContracts.observe(DefaultRoute.class.getClassLoader(), DefaultRoute.class.getName(), bytes);
        var wrongDescriptor = new DefinedMethodContracts.MethodContract(expected.owner(), expected.name(), "()I", expected.fingerprint());
        assertFalse(DefinedMethodContracts.validates(new DefaultReceiver(), wrongDescriptor));
        DefinedMethodContracts.observe(DefaultRoute.class.getClassLoader(), DefaultRoute.class.getName(), new byte[0]);
        assertFalse(DefinedMethodContracts.validates(new DefaultReceiver(), expected));
    }

    public interface DefaultRoute {
        default int evaluate(String input) { return input.length(); }
    }
    public static class DefaultReceiver implements DefaultRoute { }
    public static class OverrideReceiver implements DefaultRoute {
        @Override public int evaluate(String input) { return 41; }
    }
    public static class ClassRoute {
        public int evaluate(String input) { return projection(input); }
        private int projection(String input) { return input.length(); }
        public static int staticHelper(String input) { return input.length(); }
    }
    public static class ClassReceiver extends ClassRoute { }

    private static class EqualLoader extends ClassLoader {
        Class<?> define(byte[] bytes) { return defineClass(null, bytes, 0, bytes.length); }
        @Override public boolean equals(Object other) { return other instanceof ClassLoader; }
        @Override public int hashCode() { return 1; }
    }

    private static byte[] bytes(Class<?> type) throws Exception {
        try (InputStream input = type.getResourceAsStream("/" + type.getName().replace('.', '/') + ".class")) {
            return input.readAllBytes();
        }
    }
    private static ClassNode parse(byte[] bytes) {
        ClassNode node = new ClassNode(); new ClassReader(bytes).accept(node, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES); return node;
    }
    private static MethodNode method(ClassNode node, String name) {
        return node.methods.stream().filter(method -> method.name.equals(name)).findFirst().orElseThrow();
    }
    private static DefinedMethodContracts.MethodContract contract(byte[] bytes, String name) {
        ClassNode node = parse(bytes); MethodNode method = method(node, name);
        return new DefinedMethodContracts.MethodContract(node.name, method.name, method.desc, DefinedMethodContracts.fingerprint(method));
    }
    private static byte[] write(ClassNode node) {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS); node.accept(writer); return writer.toByteArray();
    }
    private static byte[] generated(String name, int result) {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, name, null, "java/lang/Object", null);
        var constructor = writer.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        constructor.visitCode(); constructor.visitVarInsn(Opcodes.ALOAD, 0);
        constructor.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        constructor.visitInsn(Opcodes.RETURN); constructor.visitMaxs(0, 0); constructor.visitEnd();
        var method = writer.visitMethod(Opcodes.ACC_PUBLIC, "evaluate", "()I", null, null);
        method.visitCode(); method.visitInsn(Opcodes.ICONST_0 + result); method.visitInsn(Opcodes.IRETURN);
        method.visitMaxs(0, 0); method.visitEnd(); writer.visitEnd(); return writer.toByteArray();
    }
}
