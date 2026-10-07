package net.forbric.kernel.mixin;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import net.forbric.api.Ecosystem;
import net.forbric.kernel.boot.*;

@ResourceLock("DefinedMethodContracts")
class MixinPredicateDelegateAdapterTest {
    @BeforeEach @AfterEach void reset(){DefinedMethodContracts.resetForTests();KernelPredicateDelegates.resetForTests();}
    @Test void actualSourceAndNativeGraphExecuteTheMissingInteriorNamespaceMatch() throws Exception {
        Runtime run=runtime(false,false);Id id=new Id("my_mod","some_metric");run.store(id,new OptionImpl(new Category(0,"category")));
        assertEquals(List.of("category",id.toString()),run.query("od"));assertEquals(0,run.originalCalls.get());assertEquals(2,id.namespaceReads);
    }
    @Test void nativePathPrefixQualifiedQueryGroupingAndNegativeControlsStayNative() throws Exception {
        for(String query:List.of("my","some","my_mod:some","","nothing")) {
            Runtime run=runtime(false,false);Id id=new Id("my_mod","some_metric");run.store(id,new OptionImpl(new Category(0,"category")));
            assertEquals(query.equals("nothing")?List.of():List.of("category",id.toString()),run.query(query),query);
            assertEquals(0,run.originalCalls.get());
        }
        Runtime run=runtime(false,false);Id first=new Id("beta","second"),second=new Id("alpha","first");
        run.store(first,new OptionImpl(new Category(2,"last")));run.store(second,new OptionImpl(new Category(1,"first")));
        assertEquals(List.of("first",second.toString(),"last",first.toString()),run.query(""));
    }
    @Test void changedFinalPredicateOrMapProviderRunsOnlyTheOriginalNativeOperation() throws Exception {
        for(boolean provider:List.of(false,true)) {
            Runtime run=runtime(!provider,provider);Id id=new Id("my_mod","some_metric");run.store(id,new OptionImpl(new Category(0,"category")));
            assertEquals(List.of(),run.query("od"));assertEquals(1,run.originalCalls.get());assertEquals(1,id.namespaceReads);
        }
    }
    @Test void changedOperationInputsOrAnOverridableMapProviderCannotBeLifted() throws Exception {
        Inputs input=inputs();MethodNode source=method(input.mixin,"searchPath");
        AbstractInsnNode argument=DefaultMethodOverloadBridge.real(source).get(14);source.instructions.set(argument,new LdcInsnNode("replacement query"));
        Inputs modified=input;
        assertEquals(0,MixinPredicateDelegateAdapter.adapt(modified.mixin,modified.nodes::get,n->modified.original));
        input=inputs();MethodNode provider=method(input.nodes.get("net/minecraft/client/gui/components/debug/DebugScreenEntries"),"allEntries");
        provider.instructions.remove(DefaultMethodOverloadBridge.real(provider).get(1));Inputs changed=input;
        assertEquals(0,MixinPredicateDelegateAdapter.adapt(changed.mixin,changed.nodes::get,n->changed.original));
    }
    public static final class Id {
        final String namespace,path;public int namespaceReads;
        public Id(String namespace,String path){this.namespace=namespace;this.path=path;}
        public String getNamespace(){namespaceReads++;return namespace;}public String getPath(){return path;}
        public String toString(){return namespace+":"+path;}
    }
    public record Category(float sortKey,String label) { }
    public interface Option {Category category();}
    public record OptionImpl(Category category)implements Option { }
    public static class Store {
        public static final Map<Id,Option> ENTRIES_BY_ID=new LinkedHashMap<>();
        public static Map<Id,Option> allEntries(){return Map.copyOf(ENTRIES_BY_ID);}
    }
    public static class Order {
        public static final Comparator<Id> CMP_BY_NAMESPACE_VANILLA_FIRST=Comparator.comparing((Id id)->!id.namespace.equals("minecraft"))
                .thenComparing(id->id.namespace).thenComparing(id->id.path);
    }
    public interface Suggestions { }
    private record Inputs(ClassNode mixin,Map<String,ClassNode> nodes,ClassNode original) { }
    private record Runtime(Object caller,Class<?> guest,Class<?> nativeType,Class<?> store,Class<?> operation,AtomicInteger originalCalls) {
        @SuppressWarnings("unchecked")void store(Id id,Option option)throws Exception{((Map<Id,Option>)store.getField("ENTRIES_BY_ID").get(null)).put(id,option);}
        List<String> query(String query)throws Exception {
            List<String> out=new ArrayList<>();Consumer<Category> categories=c->out.add(c.label());Consumer<Id> entries=id->out.add(id.toString());
            Object op=java.lang.reflect.Proxy.newProxyInstance(guest.getClassLoader(),new Class<?>[]{operation},(proxy,method,args)->{
                originalCalls.incrementAndGet();Object[] inputs=(Object[])args[0];assertSame(query,inputs[0]);assertSame(categories,inputs[1]);assertSame(entries,inputs[2]);
                return nativeType.getMethod("updateDebugScreenEntriesForSearch",String.class,Consumer.class,Consumer.class).invoke(null,inputs);
            });
            var wrapper=guest.getDeclaredMethod("searchPath",String.class,Consumer.class,Consumer.class,operation);wrapper.setAccessible(true);
            wrapper.invoke(caller,query,categories,entries,op);return out;
        }
    }
    private static Runtime runtime(boolean mutatePredicate,boolean mutateProvider)throws Exception {
        Inputs input=inputs();Map<String,String> remaps=new HashMap<>();String api=input.mixin.name;
        remaps.put(api,"fixture/delegate/Guest");remaps.put("net/neoforged/neoforge/client/ClientHooks","fixture/delegate/Native");
        remaps.put("net/minecraft/client/gui/screens/debug/DebugOptionsScreen$OptionList","fixture/delegate/Menu");
        remaps.put("net/minecraft/resources/Identifier",Type.getInternalName(Id.class));
        remaps.put("net/minecraft/client/gui/components/debug/DebugScreenEntry",Type.getInternalName(Option.class));
        remaps.put("net/minecraft/client/gui/components/debug/DebugEntryCategory",Type.getInternalName(Category.class));
        remaps.put("net/minecraft/client/gui/components/debug/DebugScreenEntries",Type.getInternalName(Store.class));
        remaps.put("net/neoforged/neoforge/common/CommonHooks",Type.getInternalName(Order.class));
        remaps.put("net/minecraft/commands/SharedSuggestionProvider",Type.getInternalName(Suggestions.class));
        Map<String,ClassNode> nodes=new HashMap<>();for(ClassNode node:input.nodes.values()){ClassNode mapped=remap(node,remaps);nodes.put(mapped.name,mapped);}
        ClassNode guest=remap(input.mixin,remaps),old=remap(input.original,remaps);
        for(AnnotationNode annotation:guest.invisibleAnnotations)if(annotation.desc.equals("Lorg/spongepowered/asm/mixin/Mixin;"))annotation.values=new ArrayList<>(List.of("value",new ArrayList<>(List.of(Type.getObjectType("fixture/delegate/Menu")))));
        ClassNode provider=parse(bytes(Store.class));provider.nestHostClass=null;nodes.put(provider.name,provider);
        assertEquals(1,MixinPredicateDelegateAdapter.adapt(guest,nodes::get,n->old));
        ClassNode nativeType=nodes.get("fixture/delegate/Native");
        nativeType.methods.removeIf(m->!m.name.startsWith("lambda$updateDebugScreenEntriesForSearch$")&&!m.name.equals("updateDebugScreenEntriesForSearch")&&!m.name.equals("isValidDebugEntryForSearch"));
        nativeType.fields.clear();nativeType.interfaces.clear();nativeType.innerClasses.clear();nativeType.nestMembers=null;nativeType.nestHostClass=null;
        if(mutatePredicate)method(nativeType,"isValidDebugEntryForSearch").instructions.insert(new InsnNode(Opcodes.NOP));
        if(mutateProvider)method(provider,"allEntries").instructions.insert(new InsnNode(Opcodes.NOP));
        ClassNode suggestions=parse(bytes(Suggestions.class));
        suggestions.fields.add(new FieldNode(Opcodes.ACC_PUBLIC|Opcodes.ACC_STATIC|Opcodes.ACC_FINAL,"MATCH_SPLITTER","Lcom/google/common/base/CharMatcher;",null,null));
        MethodNode initialization=new MethodNode(Opcodes.ACC_STATIC,"<clinit>","()V",null,null);initialization.instructions.add(new LdcInsnNode("_ "));
        initialization.instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC,"com/google/common/base/CharMatcher","anyOf","(Ljava/lang/CharSequence;)Lcom/google/common/base/CharMatcher;",false));
        initialization.instructions.add(new FieldInsnNode(Opcodes.PUTSTATIC,suggestions.name,"MATCH_SPLITTER","Lcom/google/common/base/CharMatcher;"));initialization.instructions.add(new InsnNode(Opcodes.RETURN));suggestions.methods.add(initialization);
        try(var jar=new java.util.zip.ZipFile(Path.of(System.getProperty("forbric.predicateBase")).toFile())) {
            ClassNode originalSuggestions=parse(jar.getInputStream(jar.getEntry("net/minecraft/commands/SharedSuggestionProvider.class")).readAllBytes());
            MethodNode match=method(remap(originalSuggestions,remaps),"matchesSubStr");suggestions.methods.add(match);
        }
        Map<String,byte[]> definitions=new HashMap<>();for(ClassNode node:List.of(guest,nativeType,provider,suggestions))definitions.put(node.name.replace('/','.'),write(node));
        List<java.net.URL> libraries=new ArrayList<>();libraries.add(Path.of(System.getProperty("forbric.mixinExtrasForTests")).toUri().toURL());
        Path libraryRoot=net.forbric.kernel.TestFixtures.minecraftDir().resolve("libraries");
        try(var files=Files.walk(libraryRoot)){for(Path path:files.filter(Files::isRegularFile).filter(p->p.getFileName().toString().matches("(guava|fastutil|commons-lang3)-.*\\.jar")).toList())libraries.add(path.toUri().toURL());}
        ClassLoader parent=new java.net.URLClassLoader(libraries.toArray(java.net.URL[]::new),MixinPredicateDelegateAdapterTest.class.getClassLoader());
        ClassLoader loader=new ClassLoader(parent){@Override protected Class<?> loadClass(String name,boolean resolve)throws ClassNotFoundException{
            if(!definitions.containsKey(name))return super.loadClass(name,resolve);synchronized(getClassLoadingLock(name)){Class<?> type=findLoadedClass(name);if(type==null){byte[] bytes=definitions.get(name);type=defineClass(name,bytes,0,bytes.length);DefinedMethodContracts.observe(this,name,bytes);}if(resolve)resolveClass(type);return type;}}};
        Class<?> type=loader.loadClass("fixture.delegate.Guest");return new Runtime(type.getConstructor().newInstance(),type,loader.loadClass("fixture.delegate.Native"),loader.loadClass(Store.class.getName()),loader.loadClass("com.llamalad7.mixinextras.injector.wrapoperation.Operation"),new AtomicInteger());
    }
    private static Inputs inputs()throws Exception {
        Path api=Path.of(System.getProperty("forbric.fabricApi",net.forbric.kernel.TestFixtures.fabricApi().toString()));
        Path base=Path.of(System.getProperty("forbric.predicateBase"));Path neo=net.forbric.kernel.TestFixtures.stagedRoot().resolve("neoforge-runtime/neoforge-runtime.jar");
        net.forbric.kernel.TestFixtures.require(net.forbric.kernel.TestFixtures.Fixture.STAGED,Files.isRegularFile(api)&&Files.isRegularFile(base)&&Files.isRegularFile(neo),"actual predicate inputs required");
        ClassNode guest=null;try(var jar=new java.util.zip.ZipFile(api.toFile())){var module=jar.stream().filter(e->e.getName().startsWith("META-INF/jars/fabric-rendering-v1-")).findFirst().orElseThrow();try(var nested=new java.util.zip.ZipInputStream(jar.getInputStream(module))){for(java.util.zip.ZipEntry entry;(entry=nested.getNextEntry())!=null;)if(entry.getName().equals("net/fabricmc/fabric/mixin/client/rendering/DebugOptionsScreenOptionListMixin.class")){guest=parse(nested.readAllBytes());break;}}}
        Map<String,ClassNode> nodes=new HashMap<>();ClassNode original;
        try(var jar=new java.util.zip.ZipFile(base.toFile())){for(String owner:List.of("net/minecraft/client/gui/screens/debug/DebugOptionsScreen$OptionList","net/minecraft/client/gui/components/debug/DebugScreenEntries"))nodes.put(owner,parse(jar.getInputStream(jar.getEntry(owner+".class")).readAllBytes()));original=new NativeGameReferences(p->{try{var e=jar.getEntry(p);return e==null?null:jar.getInputStream(e).readAllBytes();}catch(Exception e){return null;}}).get(Ecosystem.FABRIC,"net/minecraft/client/gui/screens/debug/DebugOptionsScreen$OptionList");}
        try(var jar=new java.util.zip.ZipFile(neo.toFile())){String owner="net/neoforged/neoforge/client/ClientHooks";nodes.put(owner,parse(jar.getInputStream(jar.getEntry(owner+".class")).readAllBytes()));}
        return new Inputs(guest,nodes,original);
    }
    private static ClassNode remap(ClassNode original,Map<String,String> names){ClassNode node=new ClassNode();original.accept(new org.objectweb.asm.commons.ClassRemapper(node,new org.objectweb.asm.commons.SimpleRemapper(names)));return node;}
    private static ClassNode parse(byte[] bytes){ClassNode node=new ClassNode();new ClassReader(bytes).accept(node,0);return node;}
    private static byte[] bytes(Class<?> type)throws Exception{return type.getResourceAsStream("/"+Type.getInternalName(type)+".class").readAllBytes();}
    private static byte[] write(ClassNode node){ClassWriter writer=new ClassWriter(ClassWriter.COMPUTE_FRAMES|ClassWriter.COMPUTE_MAXS);node.accept(writer);return writer.toByteArray();}
    private static MethodNode method(ClassNode node,String name){return node.methods.stream().filter(m->m.name.equals(name)).findFirst().orElseThrow();}
}
