package net.forbric.kernel.mixin;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;
import java.util.stream.Stream;

import net.forbric.kernel.TestFixtures;
import net.forbric.kernel.TestFixtures.Fixture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;

/** A renamed synthetic pipeline exercises the generated callback, not a method/class exception. */
@ResourceLock("system-properties")
class MixinCollectionSourceAdapterTest {
	private static final String ORIGINAL = "renamed/Input", HOLDER = "renamed/Holder", HOST = "renamed/Host", MIXIN = "renamed/Guest";
	private static final String LIST = "Ljava/util/List;", STREAM = "Ljava/util/stream/Stream;";
	private static final String REDIRECT = "Lorg/spongepowered/asm/mixin/injection/Redirect;";
	private static final String WRAP = "Lcom/llamalad7/mixinextras/injector/wrapoperation/WrapOperation;";
	private static final List<URLClassLoader> LOADERS = new ArrayList<>();

	@AfterEach void reset() throws Exception { System.clearProperty(MixinCollectionSourceAdapter.PROPERTY); for (URLClassLoader loader : LOADERS) loader.close(); LOADERS.clear(); }

	@Test void theGeneratedWrapperCallsNativeAndReplacementOnceAndPreservesEverySuffix() throws Exception {
		Map<String, ClassNode> classes = fixture(); ClassNode guest = guest();
		assertEquals(1, MixinCollectionSourceAdapter.adapt(guest, classes::get));
		MethodNode wrapper = guest.methods.stream().filter(m -> m.name.equals("substitute")).findFirst().orElseThrow();
		assertEquals(WRAP, MixinFit.injectorOf(wrapper).desc);
		assertEquals("INVOKE", MixinFit.value(MixinFit.atNodes(MixinFit.injectorOf(wrapper)).getFirst(), "value"));
		assertEquals(0, MixinCollectionSourceAdapter.adapt(guest, classes::get));
		Object first = new Object(), second = new Object(), added = new Object(), carrier = new Object();
		Loaded loaded = load(classes, guest, List.of(first, second), List.of(second, first, added));
		@SuppressWarnings("unchecked") List<Object> prefix = (List<Object>) loaded.holder().getField("copied").get(null);
		prefix.add(carrier);
		AtomicInteger nativeCalls = new AtomicInteger();
		Object original = operation(loaded, nativeCalls);
		@SuppressWarnings("unchecked") Stream<Object> result = (Stream<Object>) loaded.guest().getDeclaredMethod("substitute", loaded.operation()).invoke(null, original);
		List<Object> values = result.toList();
		assertEquals(5, values.size());
		assertSame(second, values.get(0)); assertSame(first, values.get(1)); assertSame(added, values.get(2)); assertSame(carrier, values.get(3)); assertEquals("tail", values.get(4));
		assertEquals(1, nativeCalls.get()); assertEquals(1, loaded.guest().getField("calls").getInt(null));
	}

	@Test void removalDoesNotUnionTheOriginalBackInAndAMismatchedPrefixFailsExplicitly() throws Exception {
		Map<String, ClassNode> classes = fixture(); ClassNode guest = guest();
		assertEquals(1, MixinCollectionSourceAdapter.adapt(guest, classes::get));
		Object first = new Object(), second = new Object();
		Loaded loaded = load(classes, guest, List.of(first, second), List.of(second));
		Object original = operation(loaded, new AtomicInteger());
		@SuppressWarnings("unchecked") Stream<Object> changed = (Stream<Object>) loaded.guest().getDeclaredMethod("substitute", loaded.operation()).invoke(null, original);
		List<Object> values = changed.toList(); assertEquals(2, values.size()); assertSame(second, values.getFirst()); assertEquals("tail", values.getLast());
		@SuppressWarnings("unchecked") List<Object> prefix = (List<Object>) loaded.holder().getField("copied").get(null); prefix.set(0, new Object());
		InvocationTargetException failed = assertThrows(InvocationTargetException.class,
				() -> loaded.guest().getDeclaredMethod("substitute", loaded.operation()).invoke(null, original));
		assertInstanceOf(IllegalStateException.class, failed.getCause()); assertTrue(failed.getCause().getMessage().contains("prefix identity mismatch"));
		assertEquals(2, loaded.guest().getField("calls").getInt(null), "the callback precedes the native getter and its prefix validation");
	}

	@Test void ambiguousCallsAndAnExistingFieldAnchorKeepTheOriginalRedirect() {
		Map<String, ClassNode> classes = fixture(); classes.get(HOST).methods.getFirst().instructions.insert(getterCall());
		assertEquals(0, MixinCollectionSourceAdapter.adapt(guest(), classes::get));
		classes = fixture(); classes.get(HOST).methods.getFirst().instructions.insert(new FieldInsnNode(Opcodes.GETSTATIC, ORIGINAL, "seed", LIST));
		assertEquals(0, MixinCollectionSourceAdapter.adapt(guest(), classes::get));
		System.setProperty(MixinCollectionSourceAdapter.PROPERTY, "off");
		assertEquals(0, MixinCollectionSourceAdapter.adapt(guest(), fixture()::get));
	}

	@Test void locksAndMultipleInjectorContractsAreNotExpandedAroundTheNativeGetter() {
		ClassNode locked = guest(); locked.methods.getFirst().access |= Opcodes.ACC_SYNCHRONIZED;
		assertEquals(0, MixinCollectionSourceAdapter.adapt(locked, fixture()::get));
		ClassNode several = guest(); several.methods.getFirst().visibleAnnotations.add(new AnnotationNode("Lcom/llamalad7/mixinextras/injector/ModifyReturnValue;"));
		assertEquals(0, MixinCollectionSourceAdapter.adapt(several, fixture()::get));
	}

	@Test void effectfulGetterUnknownCopiesBranchesAndMixedOriginalSourcesAreRefused() {
		Map<String, ClassNode> classes = fixture(); getter(classes).instructions.insert(new MethodInsnNode(Opcodes.INVOKESTATIC, "anything/Effects", "run", "()V", false));
		assertEquals(0, MixinCollectionSourceAdapter.adapt(guest(), classes::get));
		classes = fixture(); MethodNode init = classes.get(HOLDER).methods.stream().filter(m -> m.name.equals("<clinit>")).findFirst().orElseThrow();
		for (var insn : init.instructions) if (insn instanceof MethodInsnNode call && call.name.equals("unmodifiableList")) call.name = "unknownWrapper";
		assertEquals(0, MixinCollectionSourceAdapter.adapt(guest(), classes::get));
		classes = fixture(); init = classes.get(HOLDER).methods.stream().filter(m -> m.name.equals("<clinit>")).findFirst().orElseThrow(); LabelNode end = new LabelNode(); init.instructions.insert(new JumpInsnNode(Opcodes.GOTO, end)); init.instructions.insertBefore(init.instructions.getLast(), end);
		assertEquals(0, MixinCollectionSourceAdapter.adapt(guest(), classes::get));
		classes = fixture(); for (var insn : getter(classes).instructions) if (insn instanceof FieldInsnNode f && f.name.equals("tail")) f.name = "view";
		assertEquals(0, MixinCollectionSourceAdapter.adapt(guest(), classes::get), "a second original-derived source is mixed provenance");
	}

	@Test void theRealFabricRegistryRedirectFollowsTheProvedNativeCopyAndDimensionSuffix() throws Exception {
		String target = "net/minecraft/data/registries/RegistryPatchGenerator";
		ClassNode guest = actualMixin();
		Function<String, ClassNode> classes = actualClasses();
		assertNotNull(classes.apply(target));
		assertEquals(1, MixinCollectionSourceAdapter.adapt(guest, classes));
		MethodNode wrapper = guest.methods.stream().filter(m -> m.name.equals("getDynamicRegistries")).findFirst().orElseThrow();
		assertEquals(WRAP, MixinFit.injectorOf(wrapper).desc);
		String anchor = MixinFit.value(MixinFit.atNodes(MixinFit.injectorOf(wrapper)).getFirst(), "target").toString();
		assertEquals("Lnet/neoforged/neoforge/registries/DataPackRegistriesHooks;getDataPackRegistriesWithDimensions()" + STREAM, anchor);
		assertTrue(wrapper.instructions.iterator().hasNext());
		assertEquals(0, MixinCollectionSourceAdapter.adapt(guest, classes));
	}

	private record Loaded(Class<?> holder, Class<?> guest, Class<?> operation, ClassLoader loader) { }
	private static Object operation(Loaded loaded, AtomicInteger calls) {
		return Proxy.newProxyInstance(loaded.loader(), new Class<?>[]{loaded.operation()}, (proxy, method, arguments) -> {
			if (!method.getName().equals("call")) return method.invoke(calls, arguments);
			assertEquals(0, ((Object[]) arguments[0]).length, "the native static getter has no arguments"); calls.incrementAndGet();
			return loaded.holder().getMethod("expanded").invoke(null);
		});
	}
	private static Loaded load(Map<String, ClassNode> classes, ClassNode guest, List<?> original, List<?> replacement) throws Exception {
		Map<String,byte[]> bytecode = new HashMap<>(); for (ClassNode c : classes.values()) bytecode.put(c.name.replace('/', '.'), bytes(c)); bytecode.put(guest.name.replace('/', '.'), bytes(guest));
		String extras = System.getProperty("forbric.mixinExtrasForTests", ""); assertFalse(extras.isBlank(), "the test task supplies the actual MixinExtras dependency");
		URLClassLoader loader = new URLClassLoader(new URL[]{Path.of(extras).toUri().toURL()}, MixinCollectionSourceAdapterTest.class.getClassLoader()) {
			@Override protected Class<?> findClass(String name) throws ClassNotFoundException { byte[] b = bytecode.get(name); if (b == null) return super.findClass(name); return defineClass(name,b,0,b.length); }
		};
		LOADERS.add(loader);
		Class<?> input = Class.forName(ORIGINAL.replace('/', '.'), true, loader); input.getField("seed").set(null, original);
		Class<?> holder = Class.forName(HOLDER.replace('/', '.'), true, loader), adapted = Class.forName(MIXIN.replace('/', '.'), true, loader);
		adapted.getField("replacement").set(null, replacement); return new Loaded(holder, adapted, loader.loadClass("com.llamalad7.mixinextras.injector.wrapoperation.Operation"), loader);
	}
	private static byte[] bytes(ClassNode node) { ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS); node.accept(writer); return writer.toByteArray(); }
	private static Map<String, ClassNode> fixture() {
		ClassNode input = node(ORIGINAL); input.fields.add(new FieldNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "seed", LIST,null,null));
		ClassNode holder = node(HOLDER); for (String field : List.of("copied","view","tail")) holder.fields.add(new FieldNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, field,LIST,null,null));
		MethodNode init = method("<clinit>","()V");
		init.instructions.add(new TypeInsnNode(Opcodes.NEW,"java/util/ArrayList")); init.instructions.add(new InsnNode(Opcodes.DUP)); init.instructions.add(new FieldInsnNode(Opcodes.GETSTATIC,ORIGINAL,"seed",LIST));
		init.instructions.add(new MethodInsnNode(Opcodes.INVOKESPECIAL,"java/util/ArrayList","<init>","(Ljava/util/Collection;)V",false)); init.instructions.add(new FieldInsnNode(Opcodes.PUTSTATIC,HOLDER,"copied",LIST));
		init.instructions.add(new FieldInsnNode(Opcodes.GETSTATIC,HOLDER,"copied",LIST)); init.instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC,"java/util/Collections","unmodifiableList","("+LIST+")"+LIST,false)); init.instructions.add(new FieldInsnNode(Opcodes.PUTSTATIC,HOLDER,"view",LIST));
		init.instructions.add(new LdcInsnNode("tail")); init.instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC,"java/util/List","of","(Ljava/lang/Object;)"+LIST,true)); init.instructions.add(new FieldInsnNode(Opcodes.PUTSTATIC,HOLDER,"tail",LIST)); init.instructions.add(new InsnNode(Opcodes.RETURN)); init.maxStack=3; holder.methods.add(init);
		MethodNode getter = method("expanded","()"+STREAM);
		for(String field:List.of("view","tail")){getter.instructions.add(new FieldInsnNode(Opcodes.GETSTATIC,HOLDER,field,LIST));getter.instructions.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE,"java/util/List","stream","()"+STREAM,true));}
		getter.instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC,"java/util/stream/Stream","concat","("+STREAM+STREAM+")"+STREAM,true)); getter.instructions.add(new InsnNode(Opcodes.ARETURN)); getter.maxStack=2; holder.methods.add(getter);
		ClassNode host=node(HOST); MethodNode body=method("live","()"+STREAM);body.instructions.add(getterCall());body.instructions.add(new InsnNode(Opcodes.ARETURN));body.maxStack=1;host.methods.add(body);
		Map<String,ClassNode> map=new HashMap<>();for(ClassNode n:List.of(input,holder,host))map.put(n.name,n);return map;
	}
	private static MethodInsnNode getterCall(){return new MethodInsnNode(Opcodes.INVOKESTATIC,HOLDER,"expanded","()"+STREAM,false);}
	private static MethodNode getter(Map<String,ClassNode> classes){return classes.get(HOLDER).methods.stream().filter(m->m.name.equals("expanded")).findFirst().orElseThrow();}
	private static ClassNode guest(){
		ClassNode guest=node(MIXIN);guest.visibleAnnotations=new ArrayList<>(List.of(annotation("Lorg/spongepowered/asm/mixin/Mixin;","value",List.of(Type.getObjectType(HOST)))));
		guest.fields.add(new FieldNode(Opcodes.ACC_PUBLIC|Opcodes.ACC_STATIC,"replacement",LIST,null,null));guest.fields.add(new FieldNode(Opcodes.ACC_PUBLIC|Opcodes.ACC_STATIC,"calls","I",null,null));
		MethodNode handler=method("substitute","()"+LIST);handler.instructions.add(new FieldInsnNode(Opcodes.GETSTATIC,MIXIN,"calls","I"));handler.instructions.add(new InsnNode(Opcodes.ICONST_1));handler.instructions.add(new InsnNode(Opcodes.IADD));handler.instructions.add(new FieldInsnNode(Opcodes.PUTSTATIC,MIXIN,"calls","I"));handler.instructions.add(new FieldInsnNode(Opcodes.GETSTATIC,MIXIN,"replacement",LIST));handler.instructions.add(new InsnNode(Opcodes.ARETURN));handler.maxStack=2;
		AnnotationNode at=annotation("Lorg/spongepowered/asm/mixin/injection/At;","value","FIELD");set(at,"target","L"+ORIGINAL+";seed:"+LIST);AnnotationNode inject=annotation(REDIRECT,"method",List.of("live"));set(inject,"at",at);handler.visibleAnnotations=new ArrayList<>(List.of(inject));guest.methods.add(handler);return guest;
	}
	private static ClassNode node(String name){ClassNode c=new ClassNode();c.name=name;c.superName="java/lang/Object";c.version=Opcodes.V21;c.access=Opcodes.ACC_PUBLIC;return c;}
	private static MethodNode method(String name,String desc){return new MethodNode(Opcodes.ACC_PUBLIC|Opcodes.ACC_STATIC,name,desc,null,null);}
	private static AnnotationNode annotation(String desc,String key,Object value){AnnotationNode a=new AnnotationNode(desc);a.values=new ArrayList<>(List.of(key,value));return a;}
	private static void set(AnnotationNode a,String key,Object value){a.values.add(key);a.values.add(value);}
	private static ClassNode parse(byte[] bytes){ClassNode c=new ClassNode();new ClassReader(bytes).accept(c,0);return c;}
	private static ClassNode actualMixin()throws Exception{
		String configured=System.getProperty("forbric.fabricApi");Path api=configured==null?TestFixtures.fabricApi():Path.of(configured);TestFixtures.require(Fixture.STAGED,Files.isRegularFile(api),"actual Fabric API required");
		try(ZipFile z=new ZipFile(api.toFile())){var entry=z.stream().filter(e->e.getName().startsWith("META-INF/jars/fabric-registry-sync-v0-")).findFirst().orElseThrow();try(ZipInputStream inner=new ZipInputStream(z.getInputStream(entry))){for(var e=inner.getNextEntry();e!=null;e=inner.getNextEntry())if(e.getName().equals("net/fabricmc/fabric/mixin/registry/sync/RegistryPatchGeneratorMixin.class"))return parse(inner.readAllBytes());}}
		throw new AssertionError("actual registry mixin absent");
	}
	private static Function<String,ClassNode> actualClasses()throws Exception{
		Map<String,ClassNode> map=new HashMap<>();for(String[] source:List.of(new String[]{"merged-base/patched-mc-merged-26.2.jar","net/minecraft/data/registries/RegistryPatchGenerator","net/minecraft/resources/RegistryDataLoader"},new String[]{"neoforge-runtime/neoforge-runtime.jar","net/neoforged/neoforge/registries/DataPackRegistriesHooks"})){
			Path jar=TestFixtures.stagedRoot().resolve(source[0]);TestFixtures.require(Fixture.STAGED,Files.isRegularFile(jar),"actual staged source required");try(ZipFile z=new ZipFile(jar.toFile())){for(int i=1;i<source.length;i++)map.put(source[i],parse(z.getInputStream(z.getEntry(source[i]+".class")).readAllBytes()));}
		}return map::get;
	}
}
