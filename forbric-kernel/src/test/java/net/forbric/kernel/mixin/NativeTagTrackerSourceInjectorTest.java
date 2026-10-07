package net.forbric.kernel.mixin;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.*;
import java.lang.reflect.*;
import java.util.*;
import java.util.function.Function;
import java.util.zip.*;
import java.net.*;
import net.fabricmc.api.EnvType;
import net.forbric.api.Ecosystem;
import net.forbric.kernel.TestFixtures;
import net.forbric.kernel.TestFixtures.Fixture;
import net.forbric.kernel.boot.DefinedMethodContracts;
import net.forbric.kernel.boot.KernelTagSourceContracts;
import net.forbric.kernel.runtime.StagedGameClassLoader;
import net.forbric.kernel.transform.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;

/** Executes the copied actual native metric region and verifies the whole transformed actual controller. */
@ExecutesInjector(NativeTagTrackerSourceInjector.class)
@ResourceLock("system-properties")
class NativeTagTrackerSourceInjectorTest {
	private static final String OWNER="net/minecraft/world/entity/EntityFluidInteraction",TRACKER=OWNER+"$Tracker";
	private final List<URLClassLoader>parents=new ArrayList<>();
	@AfterEach void reset()throws Exception{System.clearProperty(NativeTagTrackerSourceInjector.PROPERTY);for(var parent:parents)parent.close();parents.clear();}
	@Test void actualNativeMetricInstructionsUseTheSameProvedPhysicalOrigins()throws Throwable{
		Inputs input=inputs();var template=NativeTagMetricTemplate.derive(input.original().apply(OWNER),input.classes().apply(OWNER),TRACKER,"metric");assertNotNull(template);
		ClassNode host=minimum(template.metric());ClassNode tracker=input.classes().apply(TRACKER);byte[]trackerBytes=bytes(tracker);ClassLoader loader=load(Map.of(OWNER,bytes(host),TRACKER,trackerBytes));
		Object actual=InjectorExecution.construct(loader.loadClass(TRACKER.replace('/','.')));Class<?>vec=loader.loadClass(template.vector().replace('/','.'));Object raw=InjectorExecution.construct(vec,1.0,0.0,0.0);
		Class<?>controller=loader.loadClass(OWNER.replace('/','.'));InjectorExecution.invokeStatic(controller,"metric",actual,2,2,3,3,0.8,0.0,1.0,0.0,0,raw);
		assertEquals(1.0,field(actual,"height"));assertEquals(true,field(actual,"eyesInside"));assertEquals(1,field(actual,"currentCount"));Object accumulated=field(actual,"accumulatedCurrent");assertEquals(1.0,accumulated.getClass().getField("x").get(accumulated));
		// Source height aggregation, flow scaling and contribution count remain the actual native instructions.
		Object shallow=InjectorExecution.construct(loader.loadClass(TRACKER.replace('/','.')));InjectorExecution.invokeStatic(controller,"metric",shallow,2,2,3,3,0.8,0.0,0.2,0.0,0,raw);
		assertEquals(0.2,field(shallow,"height"));assertEquals(false,field(shallow,"eyesInside"));assertEquals(0.2,field(shallow,"accumulatedCurrent").getClass().getField("x").get(field(shallow,"accumulatedCurrent")));
		Object stopped=InjectorExecution.construct(loader.loadClass(TRACKER.replace('/','.')));InjectorExecution.invokeStatic(controller,"metric",stopped,2,2,3,3,0.8,0.0,1.0,0.0,1,null);assertEquals(0,field(stopped,"currentCount"));
		InjectorExecution.invokeStatic(controller,"metric",null,2,2,3,3,0.8,0.0,1.0,0.0,0,null);
	}
	@Test void theActualCollectorTransformsAndVerifiesWhileOffAndMissingSourceDoNot()throws Exception{
		Inputs input=inputs();byte[]original=bytes(input.classes().apply(OWNER));NativeTagTrackerSourceInjector injector=new NativeTagTrackerSourceInjector(input.original(),input.classes());byte[]output=InjectorExecution.transform(injector,OWNER.replace('/','.'),original,EnvType.SERVER);assertNotSame(original,output);
		ClassLoader loader=load(Map.of(OWNER,output));assertEquals("",InjectorExecution.verify(output,loader));
		assertSame(original,InjectorExecution.transform(new NativeTagTrackerSourceInjector(name->null,input.classes()),OWNER.replace('/','.'),original,EnvType.SERVER));System.setProperty(NativeTagTrackerSourceInjector.PROPERTY,"off");assertSame(original,InjectorExecution.transform(injector,OWNER.replace('/','.'),original,EnvType.SERVER));
	}
	@Test void theActualConstructorExtractionKeepsOrderAndReusesEveryAllocatedTracker(@TempDir Path work)throws Throwable{
		Inputs input=inputs();byte[]output=InjectorExecution.transform(new NativeTagTrackerSourceInjector(input.original(),input.classes()),OWNER.replace('/','.'),bytes(input.classes().apply(OWNER)),EnvType.SERVER);
		Map<String,byte[]>classes=new HashMap<>(InjectorExecution.compile(work,Map.of("net.forbric.kernel.runtime.KernelFabricFluidBehaviors","package net.forbric.kernel.runtime;public class KernelFabricFluidBehaviors{public static boolean enabled(){return true;}public static boolean ownsNeoType(net.neoforged.neoforge.fluids.FluidType type){return false;}}"),dependencies()));
		classes.put(OWNER,output);classes.put(TRACKER,bytes(input.classes().apply(TRACKER)));classes.put("net/forbric/kernel/runtime/KernelFabricTagViews",resource("net/forbric/kernel/runtime/KernelFabricTagViews.class"));ClassLoader loader=load(classes);
		Class<?>controller=loader.loadClass(OWNER.replace('/','.')),tracker=loader.loadClass(TRACKER.replace('/','.'));DefinedMethodContracts.observe(loader,controller.getName(),output);DefinedMethodContracts.observe(loader,tracker.getName(),classes.get(TRACKER));
		Class<?>vec=loader.loadClass("net.minecraft.world.phys.Vec3");DefinedMethodContracts.observe(vec.getClassLoader(),vec.getName(),input.resources().apply("net/minecraft/world/phys/Vec3.class"));
		Object firstTag=tag(loader,"alpha"),secondTag=tag(loader,"beta"),first=InjectorExecution.construct(tracker),second=InjectorExecution.construct(tracker);Set<Object>tags=new LinkedHashSet<>(List.of(secondTag,firstTag));Map<Object,Object>existing=new LinkedHashMap<>();existing.put(secondTag,second);existing.put(firstTag,first);
		Object unsafe;Class<?>unsafeClass=Class.forName("sun.misc.Unsafe");Field singleton=unsafeClass.getDeclaredField("theUnsafe");singleton.setAccessible(true);unsafe=singleton.get(null);Object interaction=unsafeClass.getMethod("allocateInstance",Class.class).invoke(unsafe,controller);
		Method extract=Arrays.stream(controller.getDeclaredMethods()).filter(m->m.getName().endsWith("Extract")&&m.getParameterCount()==2).findFirst().orElseThrow();extract.setAccessible(true);@SuppressWarnings("unchecked")Map<Object,Object>view=(Map<Object,Object>)extract.invoke(interaction,tags,existing);
		assertNotNull(view);assertEquals(List.of(secondTag,firstTag),new ArrayList<>(view.keySet()));assertSame(second,view.get(secondTag));assertSame(first,view.get(firstTag));assertTrue(existing.isEmpty());
		// The actual final field declaration is independently guarded even when all method bodies still match.
		ClassNode changedTracker=input.classes().apply(TRACKER);FieldNode scalar=changedTracker.fields.stream().filter(f->f.desc.equals("D")).findFirst().orElseThrow();scalar.access|=Opcodes.ACC_VOLATILE;
		Map<String,byte[]>changedClasses=new HashMap<>(classes);changedClasses.put(TRACKER,bytes(changedTracker));ClassLoader changedLoader=load(changedClasses);Class<?>changedController=changedLoader.loadClass(OWNER.replace('/','.')),changedTrackerType=changedLoader.loadClass(TRACKER.replace('/','.'));
		DefinedMethodContracts.observe(changedLoader,changedController.getName(),output);DefinedMethodContracts.observe(changedLoader,changedTrackerType.getName(),changedClasses.get(TRACKER));Class<?>changedVec=changedLoader.loadClass("net.minecraft.world.phys.Vec3");DefinedMethodContracts.observe(changedVec.getClassLoader(),changedVec.getName(),input.resources().apply("net/minecraft/world/phys/Vec3.class"));
		Object changedInteraction=unsafeClass.getMethod("allocateInstance",Class.class).invoke(unsafe,changedController);Method changedExtract=changedController.getDeclaredMethod(extract.getName(),Set.class,Map.class);changedExtract.setAccessible(true);assertNull(changedExtract.invoke(changedInteraction,Set.of(),new LinkedHashMap<>()));
		// A final-defined vector mutation invalidates the view; recording a current default is not proof of source equivalence.
		ClassNode emitted=parse(output);MethodNode emittedExtract=emitted.methods.stream().filter(m->m.name.equals(extract.getName())).findFirst().orElseThrow();String key=null;for(var instruction:emittedExtract.instructions)if(instruction instanceof LdcInsnNode ldc&&ldc.cst instanceof String value)key=value;var vectorContract=KernelTagSourceContracts.schema(key).vectorMethods().getFirst();
		ClassNode changedVector=input.classes().apply("net/minecraft/world/phys/Vec3");MethodNode vectorMethod=changedVector.methods.stream().filter(m->m.name.equals(vectorContract.name())&&m.desc.equals(vectorContract.descriptor())).findFirst().orElseThrow();InsnList sideEffect=new InsnList();sideEffect.add(new MethodInsnNode(Opcodes.INVOKESTATIC,"java/lang/System","nanoTime","()J",false));sideEffect.add(new InsnNode(Opcodes.POP2));vectorMethod.instructions.insert(sideEffect);
		DefinedMethodContracts.observe(vec.getClassLoader(),vec.getName(),bytes(changedVector));assertNull(extract.invoke(interaction,Set.of(),new LinkedHashMap<>()));DefinedMethodContracts.observe(vec.getClassLoader(),vec.getName(),input.resources().apply("net/minecraft/world/phys/Vec3.class"));
	}
	@Test void currentTrackerAndVectorSideEffectsCannotAuthorizeCopiedSourceMetrics()throws Exception{
		Inputs input=inputs();ClassNode vector=input.classes().apply("net/minecraft/world/phys/Vec3");var template=NativeTagMetricTemplate.derive(input.original().apply(OWNER),input.classes().apply(OWNER),TRACKER,"metric");MethodInsnNode dependency=null;for(var instruction:template.metric().instructions)if(instruction instanceof MethodInsnNode call&&call.owner.equals(vector.name)){dependency=call;break;}assertNotNull(dependency);
		MethodInsnNode selected=dependency;MethodNode body=vector.methods.stream().filter(m->m.name.equals(selected.name)&&m.desc.equals(selected.desc)).findFirst().orElseThrow();InsnList sideEffect=new InsnList();sideEffect.add(new MethodInsnNode(Opcodes.INVOKESTATIC,"java/lang/System","nanoTime","()J",false));sideEffect.add(new InsnNode(Opcodes.POP2));body.instructions.insert(sideEffect);byte[]live=bytes(input.classes().apply(OWNER));
		assertSame(live,InjectorExecution.transform(new NativeTagTrackerSourceInjector(input.original(),name->name.equals(vector.name)?vector:input.classes().apply(name)),OWNER.replace('/','.'),live,EnvType.SERVER));
		ClassNode tracker=input.classes().apply(TRACKER);MethodNode reset=tracker.methods.stream().filter(m->m.desc.equals("()V")&&!m.name.startsWith("<")).findFirst().orElseThrow();sideEffect=new InsnList();sideEffect.add(new MethodInsnNode(Opcodes.INVOKESTATIC,"java/lang/System","nanoTime","()J",false));sideEffect.add(new InsnNode(Opcodes.POP2));reset.instructions.insert(sideEffect);
		assertSame(live,InjectorExecution.transform(new NativeTagTrackerSourceInjector(input.original(),name->name.equals(TRACKER)?tracker:input.classes().apply(name)),OWNER.replace('/','.'),live,EnvType.SERVER));
	}
	@Test void scalarOriginAndEyeBranchChangesAreNotTreatedAsACompatiblePhysicalSample()throws Exception{
		Inputs input=inputs();ClassNode source=input.original().apply(OWNER),current=input.classes().apply(OWNER);var template=NativeTagMetricTemplate.derive(source,current,TRACKER,"metric");assertNotNull(template);
		for(var instruction:template.current().instructions)if(instruction instanceof MethodInsnNode call&&call.name.equals("getEyeY")){call.name="getDifferentY";break;}
		assertNull(NativeTagMetricTemplate.derive(source,current,TRACKER,"metric"));
		current=input.classes().apply(OWNER);template=NativeTagMetricTemplate.derive(source,current,TRACKER,"metric");assertNotNull(template);for(var instruction:template.current().instructions)if(instruction instanceof FieldInsnNode field&&field.owner.equals(TRACKER)&&field.desc.equals("Z")&&field.getOpcode()==Opcodes.PUTFIELD){for(var before=instruction.getPrevious();before!=null;before=before.getPrevious())if(before instanceof JumpInsnNode branch&&branch.getOpcode()==Opcodes.IF_ICMPNE){branch.setOpcode(Opcodes.IF_ICMPEQ);break;}break;}
		assertNull(NativeTagMetricTemplate.derive(source,current,TRACKER,"metric"));
	}
	private static ClassNode minimum(MethodNode metric){ClassNode host=new ClassNode();host.version=Opcodes.V25;host.access=Opcodes.ACC_PUBLIC;host.name=OWNER;host.superName="java/lang/Object";host.nestMembers=new ArrayList<>(List.of(TRACKER));metric.access=Opcodes.ACC_PUBLIC|Opcodes.ACC_STATIC;host.methods.add(metric);return host;}
	private static Object field(Object receiver,String name)throws Exception{Field field=receiver.getClass().getDeclaredField(name);field.setAccessible(true);return field.get(receiver);}
	private static Object tag(ClassLoader loader,String value)throws Exception{Class<?>id=loader.loadClass("net.minecraft.resources.Identifier"),key=loader.loadClass("net.minecraft.resources.ResourceKey"),tag=loader.loadClass("net.minecraft.tags.TagKey");Object registry=loader.loadClass("net.minecraft.core.registries.Registries").getField("FLUID").get(null);Object name=id.getMethod("fromNamespaceAndPath",String.class,String.class).invoke(null,"source_test",value);return tag.getMethod("create",key,id).invoke(null,registry,name);}
	private static byte[]resource(String name)throws Exception{Path compiled=Path.of(System.getProperty("user.dir"),"build","classes","java","runtime").resolve(name);if(Files.isRegularFile(compiled))return Files.readAllBytes(compiled);try(var in=NativeTagTrackerSourceInjectorTest.class.getClassLoader().getResourceAsStream(name)){assertNotNull(in,name);return in.readAllBytes();}}
	private ClassLoader load(Map<String,byte[]>classes)throws Exception{URLClassLoader parent=new URLClassLoader(StagedGameClassLoader.urls().toArray(URL[]::new),getClass().getClassLoader());parents.add(parent);return InjectorExecution.load(classes,parent);}
	private static List<Path>dependencies()throws Exception{List<Path>paths=new ArrayList<>();for(URL url:StagedGameClassLoader.urls())paths.add(Path.of(url.toURI()));return paths;}
	private record Inputs(Function<String,byte[]>resources,Function<String,ClassNode>original,Function<String,ClassNode>classes){ }
	private static Inputs inputs()throws Exception{
		Path base=Path.of(System.getProperty("forbric.predicateBase",TestFixtures.stagedRoot().resolve("merged-base/patched-mc-merged-26.2.jar").toString()));TestFixtures.requireFiles(Fixture.STAGED,"source-tag physical fixtures",base);TestFixtures.require(Fixture.JAVA_25,Runtime.version().feature()>=25,"actual physical class version requires Java 25");
		Set<Path>jars=new LinkedHashSet<>();jars.add(base);jars.addAll(dependencies());Function<String,byte[]>resources=name->{try{for(Path path:jars){if(Files.isDirectory(path)){Path file=path.resolve(name);if(Files.isRegularFile(file))return Files.readAllBytes(file);}else if(Files.isRegularFile(path))try(ZipFile jar=new ZipFile(path.toFile())){var entry=jar.getEntry(name);if(entry!=null)return jar.getInputStream(entry).readAllBytes();}}try(var in=NativeTagTrackerSourceInjectorTest.class.getResourceAsStream("/"+name)){return in==null?null:in.readAllBytes();}}catch(java.io.IOException unavailable){throw new RuntimeException(unavailable);}};
		NativeGameReferences reader=new NativeGameReferences(resources);Function<String,ClassNode>classes=name->{byte[]bytes=resources.apply(name+".class");if(bytes==null)return null;if(name.equals(OWNER))bytes=InjectorExecution.transform(new UntrackedFluidEyeQueryInjector(),name.replace('/','.'),bytes,EnvType.SERVER);if(name.equals("net/minecraft/world/level/material/Fluid"))bytes=InjectorExecution.transform(new ForeignFluidTypeInjector(),name.replace('/','.'),bytes,EnvType.SERVER);bytes=InjectorExecution.transform(new PredicateGetterResultRecorder(),name.replace('/','.'),bytes,EnvType.SERVER);return parse(bytes);};return new Inputs(resources,name->reader.get(Ecosystem.FABRIC,name),classes);
	}
	private static ClassNode parse(byte[]bytes){ClassNode node=new ClassNode();new ClassReader(bytes).accept(node,ClassReader.EXPAND_FRAMES);return node;}
	private static byte[]bytes(ClassNode node){ClassWriter writer=new ClassWriter(ClassWriter.COMPUTE_FRAMES|ClassWriter.COMPUTE_MAXS){@Override protected String getCommonSuperClass(String a,String b){return a.equals(b)?a:"java/lang/Object";}};node.accept(writer);return writer.toByteArray();}
}
