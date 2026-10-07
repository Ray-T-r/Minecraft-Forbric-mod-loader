package net.forbric.kernel.mixin;
import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.*;import java.util.*;import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;import org.junit.jupiter.api.parallel.ResourceLock;import org.objectweb.asm.*;import org.objectweb.asm.tree.*;
import net.forbric.api.Ecosystem;import net.forbric.kernel.boot.*;

@ResourceLock("DefinedMethodContracts")
class MixinResourceContinuationAdapterTest {
    @BeforeEach @AfterEach void reset(){DefinedMethodContracts.resetForTests();KernelResourceContinuations.resetForTests();}
    @Test void bothActualSourceCallbacksRetainNamespacedAndDefaultResourcesWithoutDoublePrefix()throws Exception {
        for(boolean hanging:List.of(false,true))for(String name:List.of("oak","my_mod:oak","my_mod:folder/oak","minecraft:oak",":oak")) {
            Runtime run=runtime(hanging,name,false);String expected=(name.contains(":")&&!name.startsWith(":")?name.substring(0,name.indexOf(':')):"minecraft")+":"+run.prefix+(name.contains(":")?name.substring(name.indexOf(':')+1):name)+".png";
            assertEquals(expected,run.invoke(run.partial,run.prefix).toString());assertEquals(name.contains(":")?0:1,run.nativeCalls.get());
            assertTrue(Set.of(expected).contains(run.invoke(run.partial,run.prefix).toString()),"only the cross-namespace texture exists");
        }
    }
    @Test void nullSourceNameStillThrowsTheOriginalCallbackNpe()throws Exception {
        for(boolean hanging:List.of(false,true)) {
            Runtime run=runtime(hanging,null,false);var failure=assertThrows(java.lang.reflect.InvocationTargetException.class,()->run.invoke(run.partial,run.prefix));
            assertInstanceOf(NullPointerException.class,failure.getCause());assertEquals(0,run.nativeCalls.get());
        }
    }
    @Test void changedPrefixPartialOrFinalApiBodyRetainsTheActualNativeContinuationOnce()throws Exception {
        Runtime run=runtime(false,"my_mod:oak",false);assertEquals("my_mod:other/oak.png",run.invoke(run.partial,"other/").toString());assertEquals(1,run.nativeCalls.get());
        run=runtime(false,"my_mod:oak",false);Object changed=run.id.getMethod("parse",String.class).invoke(null,"my_mod:changed.png");
        assertEquals("my_mod:"+run.prefix+"changed.png",run.invoke(changed,run.prefix).toString());assertEquals(1,run.nativeCalls.get());
        run=runtime(false,"my_mod:oak",true);assertEquals("my_mod:"+run.prefix+"oak.png",run.invoke(run.partial,run.prefix).toString());assertEquals(1,run.nativeCalls.get());
    }
    @Test void changedPathRecipeOperationInputOrAmbiguousContinuationIsRejected()throws Exception {
        Inputs input=inputs(false);MethodNode helper=input.source.methods.stream().filter(m->m.name.startsWith("lambda$")).findFirst().orElseThrow();
        ((InvokeDynamicInsnNode)DefaultMethodOverloadBridge.real(helper).get(1)).bsmArgs=new Object[]{"wrong/\u0001.png"};
        Inputs recipeChanged=input;
        assertEquals(0,MixinResourceContinuationAdapter.adapt(recipeChanged.source,recipeChanged.nodes::get,n->recipeChanged.original));
        input=inputs(false);MethodNode handler=method(input.source,"init");handler.instructions.set(DefaultMethodOverloadBridge.real(handler).get(21),new LdcInsnNode("changed-input"));Inputs changed=input;
        assertEquals(0,MixinResourceContinuationAdapter.adapt(changed.source,changed.nodes::get,n->changed.original));
    }
    private record Inputs(ClassNode source,Map<String,ClassNode> nodes,ClassNode original) { }
    private record Runtime(Object receiver,Class<?> id,java.lang.reflect.Method method,Class<?> operation,Object partial,String prefix,AtomicInteger nativeCalls) {
        Object invoke(Object partial,String prefix)throws Exception {
            Object op=java.lang.reflect.Proxy.newProxyInstance(receiver.getClass().getClassLoader(),new Class<?>[]{operation},(proxy,method,args)->{
                nativeCalls.incrementAndGet();Object[] values=(Object[])args[0];assertSame(partial,values[0]);assertSame(prefix,values[1]);return id.getMethod("withPrefix",String.class).invoke(partial,prefix);
            });return method.invoke(receiver,partial,prefix,op);
        }
    }
    private static Runtime runtime(boolean hanging,String name,boolean mutateApi)throws Exception {
        Inputs input=inputs(hanging);String sourceName=input.source.name;Map<String,String> names=new HashMap<>();
        names.put(sourceName,"fixture/resource/Guest");names.put("net/minecraft/resources/Identifier","fixture/resource/Id");names.put("net/minecraft/world/level/block/state/properties/WoodType","fixture/resource/Wood");
        names.put("net/minecraft/client/gui/screens/inventory/AbstractSignEditScreen","fixture/resource/Base");names.put("net/minecraft/client/gui/screens/inventory/"+(hanging?"HangingSignEditScreen":"SignEditScreen"),"fixture/resource/Widget");names.put("net/minecraft/IdentifierException","fixture/resource/BadId");
        Map<String,ClassNode> mapped=new HashMap<>();for(ClassNode node:input.nodes.values()){ClassNode copy=remap(node,names);mapped.put(copy.name,copy);}ClassNode guest=remap(input.source,names),original=remap(input.original,names);
        AnnotationNode point=MixinFit.atNodes(MixinFit.injectorOf(method(guest,"init"))).getFirst();
        for(int i=0;i+1<point.values.size();i+=2)if(point.values.get(i).equals("target"))point.values.set(i+1,point.values.get(i+1).toString().replace("net/minecraft/resources/Identifier","fixture/resource/Id"));
        ClassNode base=node("fixture/resource/Base",false);base.fields.add(new FieldNode(Opcodes.ACC_PROTECTED|Opcodes.ACC_FINAL,"woodType","Lfixture/resource/Wood;",null,null));
        MethodNode ctor=new MethodNode(Opcodes.ACC_PUBLIC,"<init>","(Lfixture/resource/Wood;)V",null,null);ctor.instructions.add(new VarInsnNode(Opcodes.ALOAD,0));ctor.instructions.add(new MethodInsnNode(Opcodes.INVOKESPECIAL,"java/lang/Object","<init>","()V",false));ctor.instructions.add(new VarInsnNode(Opcodes.ALOAD,0));ctor.instructions.add(new VarInsnNode(Opcodes.ALOAD,1));ctor.instructions.add(new FieldInsnNode(Opcodes.PUTFIELD,base.name,"woodType","Lfixture/resource/Wood;"));ctor.instructions.add(new InsnNode(Opcodes.RETURN));base.methods.add(ctor);mapped.put(base.name,base);
        ClassNode wood=node("fixture/resource/Wood",true);wood.fields.add(new FieldNode(Opcodes.ACC_PRIVATE|Opcodes.ACC_FINAL,"name","Ljava/lang/String;",null,null));
        MethodNode getter=method(mapped.get(wood.name),"name");wood.methods.add(getter);
        MethodNode wctor=new MethodNode(Opcodes.ACC_PUBLIC,"<init>","(Ljava/lang/String;)V",null,null);wctor.instructions.add(new VarInsnNode(Opcodes.ALOAD,0));wctor.instructions.add(new MethodInsnNode(Opcodes.INVOKESPECIAL,"java/lang/Object","<init>","()V",false));wctor.instructions.add(new VarInsnNode(Opcodes.ALOAD,0));wctor.instructions.add(new VarInsnNode(Opcodes.ALOAD,1));wctor.instructions.add(new FieldInsnNode(Opcodes.PUTFIELD,wood.name,"name","Ljava/lang/String;"));wctor.instructions.add(new InsnNode(Opcodes.RETURN));wood.methods.add(wctor);mapped.put(wood.name,wood);
        MethodNode source=method(guest,"init");String fingerprint=MixinInstructionFingerprint.hash(source);assertEquals(1,MixinResourceContinuationAdapter.adapt(guest,mapped::get,n->original));assertEquals(fingerprint,MixinInstructionFingerprint.hash(source));
        guest.methods.removeIf(m->m.name.equals("<init>"));guest.access&=~Opcodes.ACC_ABSTRACT;MethodNode gctor=new MethodNode(Opcodes.ACC_PUBLIC,"<init>","(Lfixture/resource/Wood;)V",null,null);gctor.instructions.add(new VarInsnNode(Opcodes.ALOAD,0));gctor.instructions.add(new VarInsnNode(Opcodes.ALOAD,1));gctor.instructions.add(new MethodInsnNode(Opcodes.INVOKESPECIAL,base.name,"<init>",gctor.desc,false));gctor.instructions.add(new InsnNode(Opcodes.RETURN));guest.methods.add(gctor);
        ClassNode id=mapped.get("fixture/resource/Id");Set<String> roots=new HashSet<>(List.of("parse","withDefaultNamespace","getNamespace","getPath","withPrefix","withPath","toString"));Set<MethodNode> keep=new HashSet<>();ArrayDeque<MethodNode> queue=new ArrayDeque<>();id.methods.stream().filter(m->roots.contains(m.name)).forEach(queue::add);while(!queue.isEmpty()){MethodNode method=queue.remove();if(!keep.add(method))continue;for(AbstractInsnNode instruction:method.instructions)if(instruction instanceof MethodInsnNode call&&call.owner.equals(id.name))id.methods.stream().filter(m->m.name.equals(call.name)&&m.desc.equals(call.desc)).forEach(queue::add);}
        id.methods.removeIf(m->!keep.contains(m));id.fields.removeIf(f->!Set.of("namespace","path","$assertionsDisabled").contains(f.name));if(mutateApi)method(id,"withPrefix").instructions.insert(new InsnNode(Opcodes.NOP));
        Map<String,byte[]> definitions=new HashMap<>();for(ClassNode type:List.of(guest,base,wood,id,mapped.get("fixture/resource/BadId")))definitions.put(type.name.replace('/','.'),write(type));
        ClassLoader parent=new java.net.URLClassLoader(new java.net.URL[]{Path.of(System.getProperty("forbric.mixinExtrasForTests")).toUri().toURL()},MixinResourceContinuationAdapterTest.class.getClassLoader());
        ClassLoader loader=new ClassLoader(parent){@Override protected Class<?> loadClass(String binary,boolean resolve)throws ClassNotFoundException{if(!definitions.containsKey(binary))return super.loadClass(binary,resolve);synchronized(getClassLoadingLock(binary)){Class<?> type=findLoadedClass(binary);if(type==null){byte[] bytes=definitions.get(binary);type=defineClass(binary,bytes,0,bytes.length);DefinedMethodContracts.observe(this,binary,bytes);}if(resolve)resolveClass(type);return type;}}};
        Class<?> value=loader.loadClass("fixture.resource.Id"),sourceType=loader.loadClass("fixture.resource.Wood"),type=loader.loadClass("fixture.resource.Guest");Object sourceValue=sourceType.getConstructor(String.class).newInstance(name);Object receiver=type.getConstructor(sourceType).newInstance(sourceValue),partial=value.getMethod("parse",String.class).invoke(null,String.valueOf(name)+".png");Class<?> operation=loader.loadClass("com.llamalad7.mixinextras.injector.wrapoperation.Operation");var wrapper=type.getDeclaredMethod("init",value,String.class,operation);wrapper.setAccessible(true);
        return new Runtime(receiver,value,wrapper,operation,partial,hanging?"textures/gui/hanging_signs/":"textures/gui/signs/",new AtomicInteger());
    }
    private static Inputs inputs(boolean hanging)throws Exception {
        Path base=Path.of(System.getProperty("forbric.predicateBase")),api=Path.of(System.getProperty("forbric.fabricApi"));net.forbric.kernel.TestFixtures.require(net.forbric.kernel.TestFixtures.Fixture.STAGED,Files.isRegularFile(base)&&Files.isRegularFile(api),"actual resource inputs required");
        String screen=hanging?"HangingSignEditScreen":"SignEditScreen",owner="net/minecraft/client/gui/screens/inventory/"+screen;ClassNode source=null;Map<String,ClassNode> nodes=new HashMap<>();ClassNode original;
        try(var jar=new java.util.zip.ZipFile(api.toFile())){var module=jar.stream().filter(e->e.getName().startsWith("META-INF/jars/fabric-object-builder-api-v1-")).findFirst().orElseThrow();try(var nested=new java.util.zip.ZipInputStream(jar.getInputStream(module))){for(java.util.zip.ZipEntry entry;(entry=nested.getNextEntry())!=null;)if(entry.getName().equals("net/fabricmc/fabric/mixin/object/builder/client/"+screen+"Mixin.class")){source=parse(nested.readAllBytes());break;}}}
        try(var jar=new java.util.zip.ZipFile(base.toFile())){for(String name:List.of(owner,"net/minecraft/client/gui/screens/inventory/AbstractSignEditScreen","net/minecraft/world/level/block/state/properties/WoodType","net/minecraft/resources/Identifier","net/minecraft/IdentifierException"))nodes.put(name,parse(jar.getInputStream(jar.getEntry(name+".class")).readAllBytes()));original=new NativeGameReferences(p->{try{var e=jar.getEntry(p);return e==null?null:jar.getInputStream(e).readAllBytes();}catch(Exception e){return null;}}).get(Ecosystem.FABRIC,owner);}
        return new Inputs(source,nodes,original);
    }
    private static ClassNode node(String name,boolean isFinal){ClassNode n=new ClassNode();n.name=name;n.superName="java/lang/Object";n.access=Opcodes.ACC_PUBLIC|(isFinal?Opcodes.ACC_FINAL:0);n.version=Opcodes.V21;return n;}
    private static ClassNode remap(ClassNode original,Map<String,String> names){ClassNode n=new ClassNode();original.accept(new org.objectweb.asm.commons.ClassRemapper(n,new org.objectweb.asm.commons.SimpleRemapper(names)));return n;}
    private static ClassNode parse(byte[]bytes){ClassNode n=new ClassNode();new ClassReader(bytes).accept(n,0);return n;}
    private static byte[] write(ClassNode node){ClassWriter writer=new ClassWriter(ClassWriter.COMPUTE_FRAMES|ClassWriter.COMPUTE_MAXS);node.accept(writer);return writer.toByteArray();}
    private static MethodNode method(ClassNode node,String name){return node.methods.stream().filter(m->m.name.equals(name)).findFirst().orElseThrow();}
}
