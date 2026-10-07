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

    @Test void identicalPureDefaultForwardersCanOptInWithoutRelaxingOrdinaryDispatch() throws Exception {
        String base="fixture/contracts/ForwardBase",sub="fixture/contracts/ForwardSub";
        byte[] original=forwarder(base,"java/lang/Object",false,false),duplicate=forwarder(sub,base,false,false);
        ClassLoader loader=net.forbric.kernel.transform.InjectorExecution.load(java.util.Map.of(base,original,sub,duplicate));
        Class<?> type=loader.loadClass(sub.replace('/','.'));Object receiver=type.getConstructor().newInstance();var expected=contract(original,"evaluate");
        assertFalse(DefinedMethodContracts.validatesTransparentDispatch(receiver,expected));
        DefinedMethodContracts.observe(loader,base,original);DefinedMethodContracts.observe(loader,sub,duplicate);
        assertFalse(DefinedMethodContracts.validates(receiver,expected));
        assertFalse(DefinedMethodContracts.validatesTransparentDispatch(receiver,expected),"the actual interface default has not been observed");
        observe(DefaultRoute.class);
        assertTrue(DefinedMethodContracts.validatesTransparentDispatch(receiver,expected));
        DefinedMethodContracts.observe(loader,sub,forwarder(sub,base,true,false));
        assertFalse(DefinedMethodContracts.validatesTransparentDispatch(receiver,expected));
        DefinedMethodContracts.observe(loader,sub,forwarder(sub,base,false,true));
        assertFalse(DefinedMethodContracts.validatesTransparentDispatch(receiver,expected));
    }

    @Test void covariantIdentityBridgesRequireEveryActualCalleeToRemainAnObservedIdentity()throws Exception{
        var expected=contract(bytes(IdentityRoot.class),"self");IdentityChild receiver=new IdentityChild();
        observe(IdentityRoot.class);assertFalse(DefinedMethodContracts.validatesTransparentDispatch(receiver,expected));
        observe(IdentityChild.class);assertTrue(DefinedMethodContracts.validatesTransparentDispatch(receiver,expected));
        assertFalse(DefinedMethodContracts.validates(receiver,expected));
        observe(EffectfulIdentity.class);assertFalse(DefinedMethodContracts.validatesTransparentDispatch(new EffectfulIdentity(),expected));
        ClassNode changed=parse(bytes(IdentityChild.class));for(MethodNode m:changed.methods)if(m.name.equals("self")&&(m.access&Opcodes.ACC_BRIDGE)==0)m.instructions.insert(new InsnNode(Opcodes.NOP));
        // A NOP is not an effect, but the accepted identity grammar is deliberately closed.
        DefinedMethodContracts.observe(IdentityChild.class.getClassLoader(),IdentityChild.class.getName(),write(changed));
        assertFalse(DefinedMethodContracts.validatesTransparentDispatch(receiver,expected));
    }

    @Test void identicalTransparentBytesStillCannotBorrowADifferentLoadersObservation()throws Exception{
        String name="fixture/contracts/IsolatedForward";byte[] code=forwarder(name,"java/lang/Object",false,false);
        EqualLoader a=new EqualLoader(),b=new EqualLoader();Class<?> first=a.define(code),second=b.define(code);var expected=contract(code,"evaluate");
        DefinedMethodContracts.observe(a,name,code);
        observe(DefaultRoute.class);
        assertTrue(DefinedMethodContracts.validatesTransparentDispatch(first.getConstructor().newInstance(),expected));
        assertFalse(DefinedMethodContracts.validatesTransparentDispatch(second.getConstructor().newInstance(),expected));
    }
    @Test void aPrivateExpectedIdentityCannotBecomeAVirtualContract()throws Exception{
        observe(PrivateIdentity.class);observe(ExposedIdentity.class);
        assertTrue(DefinedMethodContracts.observed(PrivateIdentity.class.getClassLoader(),contract(bytes(PrivateIdentity.class),"self")));
        assertFalse(DefinedMethodContracts.validatesTransparentDispatch(new ExposedIdentity(),contract(bytes(PrivateIdentity.class),"self")));
    }
    @Test void sameNamedInterfaceDefaultsInDifferentLoadersAreDifferentCallTargets()throws Exception{
        String face="fixture/contracts/SeparateCore",root="fixture/contracts/SeparateRoot",child="fixture/contracts/SeparateChild";
        byte[] before=interfaceDefault(face,1),after=interfaceDefault(face,2),base=specialForwarder(root,"java/lang/Object",face),sub=specialForwarder(child,root,face);
        Layer first=new Layer(getClass().getClassLoader(),java.util.Map.of(face,before,root,base)),second=new Layer(first,java.util.Map.of(face,after,child,sub));
        Class<?> original=first.loadClass(root.replace('/','.')),actual=second.loadClass(child.replace('/','.'));Object receiver=actual.getConstructor().newInstance();
        DefinedMethodContracts.observe(first,face,before);DefinedMethodContracts.observe(second,face,after);DefinedMethodContracts.observe(first,root,base);DefinedMethodContracts.observe(second,child,sub);
        assertTrue((Integer)original.getMethod("evaluate").invoke(original.getConstructor().newInstance())==1);assertTrue((Integer)actual.getMethod("evaluate").invoke(receiver)==2);
        assertFalse(DefinedMethodContracts.validatesTransparentDispatch(receiver,contract(base,"evaluate")));
    }
    @Test void aPrivateSymbolicCalleeCannotBeSubstitutedByASubclassPublicIdentity()throws Exception{
        String base="fixture/contracts/PrivateCalleeBase",child="fixture/contracts/PrivateCalleeChild";byte[] first=privateCallee(base,org.objectweb.asm.Type.getInternalName(ObjectIdentity.class),base,true),second=privateCallee(child,base,base,false);
        Layer loader=new Layer(getClass().getClassLoader(),java.util.Map.of(base,first,child,second));Class<?> type=loader.loadClass(child.replace('/','.'));Object receiver=type.getConstructor().newInstance();observe(ObjectIdentity.class);DefinedMethodContracts.observe(loader,base,first);DefinedMethodContracts.observe(loader,child,second);
        assertFalse(ObjectIdentity.class.getMethod("self").invoke(receiver)==receiver,"the actual private member returns another object");
        assertFalse(DefinedMethodContracts.validatesTransparentDispatch(receiver,contract(bytes(ObjectIdentity.class),"self")));
    }
    @Test void anAbstractSymbolicMemberMayDispatchToAnObservedConcreteIdentity()throws Exception{
        String face="fixture/contracts/IdentityFace",base="fixture/contracts/AbstractIdentityBase",child="fixture/contracts/ConcreteIdentityChild";
        ClassWriter contract=new ClassWriter(ClassWriter.COMPUTE_MAXS);contract.visit(Opcodes.V21,Opcodes.ACC_PUBLIC|Opcodes.ACC_INTERFACE|Opcodes.ACC_ABSTRACT,face,null,"java/lang/Object",null);contract.visitMethod(Opcodes.ACC_PUBLIC|Opcodes.ACC_ABSTRACT,"self","()L"+face+";",null,null).visitEnd();contract.visitEnd();byte[] faceBytes=contract.toByteArray();
        ClassWriter parent=new ClassWriter(ClassWriter.COMPUTE_MAXS);parent.visit(Opcodes.V21,Opcodes.ACC_PUBLIC|Opcodes.ACC_ABSTRACT,base,null,org.objectweb.asm.Type.getInternalName(ObjectIdentity.class),new String[]{face});constructor(parent,org.objectweb.asm.Type.getInternalName(ObjectIdentity.class));var bridge=parent.visitMethod(Opcodes.ACC_PUBLIC,"self","()Ljava/lang/Object;",null,null);bridge.visitCode();bridge.visitVarInsn(Opcodes.ALOAD,0);bridge.visitMethodInsn(Opcodes.INVOKEINTERFACE,face,"self","()L"+face+";",true);bridge.visitInsn(Opcodes.ARETURN);bridge.visitMaxs(0,0);bridge.visitEnd();parent.visitEnd();byte[] parentBytes=parent.toByteArray();
        ClassWriter implementation=new ClassWriter(ClassWriter.COMPUTE_MAXS);implementation.visit(Opcodes.V21,Opcodes.ACC_PUBLIC,child,null,base,null);constructor(implementation,base);var identity=implementation.visitMethod(Opcodes.ACC_PUBLIC,"self","()L"+face+";",null,null);identity.visitCode();identity.visitVarInsn(Opcodes.ALOAD,0);identity.visitInsn(Opcodes.ARETURN);identity.visitMaxs(0,0);identity.visitEnd();implementation.visitEnd();byte[] childBytes=implementation.toByteArray();
        Layer loader=new Layer(getClass().getClassLoader(),java.util.Map.of(face,faceBytes,base,parentBytes,child,childBytes));Object receiver=loader.loadClass(child.replace('/','.')).getConstructor().newInstance();observe(ObjectIdentity.class);DefinedMethodContracts.observe(loader,base,parentBytes);
        assertFalse(DefinedMethodContracts.validatesTransparentDispatch(receiver,contract(bytes(ObjectIdentity.class),"self")),"the actual concrete callee is unobserved");DefinedMethodContracts.observe(loader,child,childBytes);assertTrue(DefinedMethodContracts.validatesTransparentDispatch(receiver,contract(bytes(ObjectIdentity.class),"self")));assertTrue(ObjectIdentity.class.getMethod("self").invoke(receiver)==receiver);
    }

    public static class IdentityRoot { public IdentityRoot self(){return this;} }
    public static class PrivateIdentity {private Object self(){return this;}}
    public static class ExposedIdentity extends PrivateIdentity {public Object self(){return this;}}
    public static class ObjectIdentity {public Object self(){return this;}}
    public static class IdentityChild extends IdentityRoot { @Override public IdentityChild self(){return this;} }
    public static class EffectfulIdentity extends IdentityChild {
        static int effects;
        @Override public EffectfulIdentity self(){effects++;return this;}
    }
    private static void observe(Class<?> type)throws Exception{DefinedMethodContracts.observe(type.getClassLoader(),type.getName(),bytes(type));}

    private static byte[] forwarder(String name,String parent,boolean effectful,boolean synchronizedMethod){
        ClassWriter writer=new ClassWriter(ClassWriter.COMPUTE_FRAMES|ClassWriter.COMPUTE_MAXS);String face=org.objectweb.asm.Type.getInternalName(DefaultRoute.class);
        writer.visit(Opcodes.V21,Opcodes.ACC_PUBLIC,name,null,parent,new String[]{face});
        var ctor=writer.visitMethod(Opcodes.ACC_PUBLIC,"<init>","()V",null,null);ctor.visitCode();ctor.visitVarInsn(Opcodes.ALOAD,0);ctor.visitMethodInsn(Opcodes.INVOKESPECIAL,parent,"<init>","()V",false);ctor.visitInsn(Opcodes.RETURN);ctor.visitMaxs(0,0);ctor.visitEnd();
        var method=writer.visitMethod(Opcodes.ACC_PUBLIC|(synchronizedMethod?Opcodes.ACC_SYNCHRONIZED:0),"evaluate","(Ljava/lang/String;)I",null,null);method.visitCode();method.visitVarInsn(Opcodes.ALOAD,0);method.visitVarInsn(Opcodes.ALOAD,1);method.visitMethodInsn(Opcodes.INVOKESPECIAL,face,"evaluate","(Ljava/lang/String;)I",true);if(effectful){method.visitInsn(Opcodes.ICONST_1);method.visitInsn(Opcodes.IADD);}method.visitInsn(Opcodes.IRETURN);method.visitMaxs(0,0);method.visitEnd();writer.visitEnd();return writer.toByteArray();
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
    private static class Layer extends ClassLoader {
        private final java.util.Map<String,byte[]> definitions;
        Layer(ClassLoader parent,java.util.Map<String,byte[]> definitions){super(parent);this.definitions=definitions;}
        @Override protected Class<?> loadClass(String name,boolean resolve)throws ClassNotFoundException{synchronized(getClassLoadingLock(name)){Class<?> type=findLoadedClass(name);if(type==null){byte[] code=definitions.get(name.replace('.','/'));type=code==null?super.loadClass(name,false):defineClass(name,code,0,code.length);}if(resolve)resolveClass(type);return type;}}
    }
    private static byte[] interfaceDefault(String name,int value){ClassWriter writer=new ClassWriter(ClassWriter.COMPUTE_MAXS);writer.visit(Opcodes.V21,Opcodes.ACC_PUBLIC|Opcodes.ACC_INTERFACE|Opcodes.ACC_ABSTRACT,name,null,"java/lang/Object",null);var method=writer.visitMethod(Opcodes.ACC_PUBLIC,"evaluate","()I",null,null);method.visitCode();method.visitInsn(Opcodes.ICONST_0+value);method.visitInsn(Opcodes.IRETURN);method.visitMaxs(0,0);method.visitEnd();writer.visitEnd();return writer.toByteArray();}
    private static byte[] specialForwarder(String name,String parent,String face){ClassWriter writer=new ClassWriter(ClassWriter.COMPUTE_MAXS);writer.visit(Opcodes.V21,Opcodes.ACC_PUBLIC,name,null,parent,new String[]{face});constructor(writer,parent);var method=writer.visitMethod(Opcodes.ACC_PUBLIC,"evaluate","()I",null,null);method.visitCode();method.visitVarInsn(Opcodes.ALOAD,0);method.visitMethodInsn(Opcodes.INVOKESPECIAL,face,"evaluate","()I",true);method.visitInsn(Opcodes.IRETURN);method.visitMaxs(0,0);method.visitEnd();writer.visitEnd();return writer.toByteArray();}
    private static byte[] privateCallee(String name,String parent,String base,boolean hidden){ClassWriter writer=new ClassWriter(ClassWriter.COMPUTE_MAXS);writer.visit(Opcodes.V21,Opcodes.ACC_PUBLIC,name,null,parent,null);constructor(writer,parent);if(hidden){var outer=writer.visitMethod(Opcodes.ACC_PUBLIC,"self","()Ljava/lang/Object;",null,null);outer.visitCode();outer.visitVarInsn(Opcodes.ALOAD,0);outer.visitMethodInsn(Opcodes.INVOKEVIRTUAL,name,"self","()L"+name+";",false);outer.visitInsn(Opcodes.ARETURN);outer.visitMaxs(0,0);outer.visitEnd();var inner=writer.visitMethod(Opcodes.ACC_PRIVATE,"self","()L"+name+";",null,null);inner.visitCode();inner.visitTypeInsn(Opcodes.NEW,name);inner.visitInsn(Opcodes.DUP);inner.visitMethodInsn(Opcodes.INVOKESPECIAL,name,"<init>","()V",false);inner.visitInsn(Opcodes.ARETURN);inner.visitMaxs(0,0);inner.visitEnd();}else{var identity=writer.visitMethod(Opcodes.ACC_PUBLIC,"self","()L"+base+";",null,null);identity.visitCode();identity.visitVarInsn(Opcodes.ALOAD,0);identity.visitInsn(Opcodes.ARETURN);identity.visitMaxs(0,0);identity.visitEnd();}writer.visitEnd();return writer.toByteArray();}
    private static void constructor(ClassWriter writer,String parent){var constructor=writer.visitMethod(Opcodes.ACC_PUBLIC,"<init>","()V",null,null);constructor.visitCode();constructor.visitVarInsn(Opcodes.ALOAD,0);constructor.visitMethodInsn(Opcodes.INVOKESPECIAL,parent,"<init>","()V",false);constructor.visitInsn(Opcodes.RETURN);constructor.visitMaxs(0,0);constructor.visitEnd();}

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
