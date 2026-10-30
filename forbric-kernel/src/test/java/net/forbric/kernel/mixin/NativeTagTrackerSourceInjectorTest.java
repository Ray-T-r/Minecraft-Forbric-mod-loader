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
		TestFixtures.requireForgeCarrierWhereTheBaseNamesIt("the base classes this defines and verifies");
		Inputs input=inputs();byte[]original=bytes(input.classes().apply(OWNER));NativeTagTrackerSourceInjector injector=new NativeTagTrackerSourceInjector(input.original(),input.classes());byte[]output=InjectorExecution.transform(injector,OWNER.replace('/','.'),original,EnvType.SERVER);assertNotSame(original,output);
		ClassLoader loader=load(Map.of(OWNER,output));assertEquals("",InjectorExecution.verify(output,loader));
		assertSame(original,InjectorExecution.transform(new NativeTagTrackerSourceInjector(name->null,input.classes()),OWNER.replace('/','.'),original,EnvType.SERVER));System.setProperty(NativeTagTrackerSourceInjector.PROPERTY,"off");assertSame(original,InjectorExecution.transform(injector,OWNER.replace('/','.'),original,EnvType.SERVER));
	}
	@Test void theActualConstructorExtractionKeepsOrderAndReusesEveryAllocatedTracker(@TempDir Path work)throws Throwable{
		TestFixtures.requireForgeCarrierWhereTheBaseNamesIt("the base classes this defines and verifies");
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
	/**
	 * A mixin's {@code @Mutable} shadow of the vector's coordinates (one that adds a setter to {@code Vec3}) strips their
	 * {@code final}. Native code reads the sampled flow where it sampled it; the copy reads it at the cell's end, and the
	 * real merged cell's code in between only calls proved-unchanged bodies that write no vector field. So the view is
	 * kept with the flag gone -- and declining there was the defect, every launch with such a mixin -- while a field
	 * shape without that proof still declines on the same runtime class.
	 */
	@Test void aVectorWhoseCoordinatesLostFinalKeepsTheViewOnlyWhereTheWindowIsProved(@TempDir Path work)throws Throwable{
		TestFixtures.requireForgeCarrierWhereTheBaseNamesIt("the base classes this defines and verifies");
		Inputs input=inputs();byte[]output=InjectorExecution.transform(new NativeTagTrackerSourceInjector(input.original(),input.classes()),OWNER.replace('/','.'),bytes(input.classes().apply(OWNER)),EnvType.SERVER);
		ClassNode emitted=parse(output);String key=null;for(MethodNode method:emitted.methods)if(method.name.endsWith("Extract"))for(var instruction:method.instructions)if(instruction instanceof LdcInsnNode ldc&&ldc.cst instanceof String value)key=value;assertNotNull(key);
		var schema=KernelTagSourceContracts.schema(key);assertNotNull(schema);String vector="net/minecraft/world/phys/Vec3";
		List<String>proved=new ArrayList<>(),strict=new ArrayList<>();for(var shape:schema.fields())(shape.finalImmaterial()?proved:strict).add(shape.owner()+"."+shape.name());
		assertTrue(proved.containsAll(List.of(vector+".x",vector+".y",vector+".z")),"the vector's coordinates carry the window proof: "+schema.fields());
		assertTrue(proved.stream().allMatch(name->name.startsWith(vector+".")),"only the sampled vector's instance fields are relaxed: "+proved);
		assertTrue(strict.stream().anyMatch(name->name.startsWith(OWNER+".")),"the controller's own fields stay exact: "+strict);
		// The runtime class as such a mixin leaves it: the same bodies, x/y/z no longer final.
		ClassNode mutable=input.classes().apply(vector);int stripped=0;for(FieldNode field:mutable.fields)if((field.access&Opcodes.ACC_STATIC)==0&&field.desc.equals("D")){field.access&=~Opcodes.ACC_FINAL;stripped++;}assertEquals(3,stripped);
		Map<String,byte[]>classes=new HashMap<>(InjectorExecution.compile(work,Map.of("net.forbric.kernel.runtime.KernelFabricFluidBehaviors","package net.forbric.kernel.runtime;public class KernelFabricFluidBehaviors{public static boolean enabled(){return true;}public static boolean ownsNeoType(net.neoforged.neoforge.fluids.FluidType type){return false;}}"),dependencies()));
		classes.put(OWNER,output);classes.put(TRACKER,bytes(input.classes().apply(TRACKER)));classes.put(vector,bytes(mutable));classes.put("net/forbric/kernel/runtime/KernelFabricTagViews",resource("net/forbric/kernel/runtime/KernelFabricTagViews.class"));ClassLoader loader=load(classes);
		Class<?>controller=loader.loadClass(OWNER.replace('/','.')),tracker=loader.loadClass(TRACKER.replace('/','.')),vec=loader.loadClass(vector.replace('/','.'));assertSame(loader,vec.getClassLoader(),"premise: the stripped vector is the one the controller links");assertFalse(Modifier.isFinal(vec.getField("x").getModifiers()));
		DefinedMethodContracts.observe(loader,controller.getName(),output);DefinedMethodContracts.observe(loader,tracker.getName(),classes.get(TRACKER));DefinedMethodContracts.observe(loader,vec.getName(),classes.get(vector));
		Object unsafe;Class<?>unsafeClass=Class.forName("sun.misc.Unsafe");Field singleton=unsafeClass.getDeclaredField("theUnsafe");singleton.setAccessible(true);unsafe=singleton.get(null);Object interaction=unsafeClass.getMethod("allocateInstance",Class.class).invoke(unsafe,controller);
		assertTrue(KernelTagSourceContracts.validates(interaction,key),"the window proof makes the stripped flag immaterial");
		List<KernelTagSourceContracts.FieldShape>unproved=new ArrayList<>();for(var shape:schema.fields())unproved.add(new KernelTagSourceContracts.FieldShape(shape.owner(),shape.name(),shape.descriptor(),shape.flags()));
		KernelTagSourceContracts.register(key+":unproved",new KernelTagSourceContracts.Schema(schema.owner(),schema.mapField(),schema.readyField(),schema.tracker(),schema.tag(),schema.update(),schema.definitions(),schema.vectorMethods(),schema.accessFlags(),unproved,schema.fluidQuery(),schema.fluidTypeGetter()));
		assertFalse(KernelTagSourceContracts.validates(interaction,key+":unproved"),"without the proof a stripped final still declines");
		// The other direction is never immaterial: a field that BECAME final would forbid a copied write.
		List<KernelTagSourceContracts.FieldShape>hardened=new ArrayList<>();for(var shape:schema.fields())hardened.add(shape.finalImmaterial()?new KernelTagSourceContracts.FieldShape(shape.owner(),shape.name(),shape.descriptor(),shape.flags()&~Opcodes.ACC_FINAL,true):shape);
		classes.put(vector,bytes(input.classes().apply(vector)));ClassLoader finalLoader=load(classes);Class<?>finalController=finalLoader.loadClass(OWNER.replace('/','.'));DefinedMethodContracts.observe(finalLoader,finalController.getName(),output);DefinedMethodContracts.observe(finalLoader,TRACKER.replace('/','.'),classes.get(TRACKER));DefinedMethodContracts.observe(finalLoader,vector.replace('/','.'),classes.get(vector));
		KernelTagSourceContracts.register(key+":hardened",new KernelTagSourceContracts.Schema(schema.owner(),schema.mapField(),schema.readyField(),schema.tracker(),schema.tag(),schema.update(),schema.definitions(),schema.vectorMethods(),schema.accessFlags(),hardened,schema.fluidQuery(),schema.fluidTypeGetter()));
		Object finalInteraction=unsafeClass.getMethod("allocateInstance",Class.class).invoke(unsafe,finalController);
		assertTrue(KernelTagSourceContracts.validates(finalInteraction,key),"premise: the native vector passes as before");
		assertFalse(KernelTagSourceContracts.validates(finalInteraction,key+":hardened"),"a field final at run time but not natively declines");
	}
	/** The window proof itself, on a vector class of no game's: what makes a later read the same read, and what does not. */
	@Test void theWindowProofAcceptsOnlyProvedCallsThatWriteNoVectorField(){
		String arrow="fixture/geo/Arrow",other="fixture/geo/Other";ClassNode vector=new ClassNode();vector.name=arrow;vector.superName="java/lang/Object";vector.fields.add(new FieldNode(Opcodes.ACC_PUBLIC|Opcodes.ACC_FINAL,"len","D",null,null));
		MethodNode init=new MethodNode(Opcodes.ACC_PUBLIC,"<init>","(D)V",null,null);init.instructions.add(new VarInsnNode(Opcodes.ALOAD,0));init.instructions.add(new MethodInsnNode(Opcodes.INVOKESPECIAL,"java/lang/Object","<init>","()V",false));init.instructions.add(new VarInsnNode(Opcodes.ALOAD,0));init.instructions.add(new VarInsnNode(Opcodes.DLOAD,1));init.instructions.add(new FieldInsnNode(Opcodes.PUTFIELD,arrow,"len","D"));init.instructions.add(new InsnNode(Opcodes.RETURN));
		MethodNode stretch=new MethodNode(Opcodes.ACC_PUBLIC,"stretch","(D)L"+arrow+";",null,null);stretch.instructions.add(new TypeInsnNode(Opcodes.NEW,arrow));stretch.instructions.add(new InsnNode(Opcodes.DUP));stretch.instructions.add(new VarInsnNode(Opcodes.ALOAD,0));stretch.instructions.add(new FieldInsnNode(Opcodes.GETFIELD,arrow,"len","D"));stretch.instructions.add(new VarInsnNode(Opcodes.DLOAD,1));stretch.instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC,"java/lang/Math","max","(DD)D",false));stretch.instructions.add(new MethodInsnNode(Opcodes.INVOKESPECIAL,arrow,"<init>","(D)V",false));stretch.instructions.add(new InsnNode(Opcodes.ARETURN));
		MethodNode nudge=new MethodNode(Opcodes.ACC_PUBLIC,"nudge","()V",null,null);nudge.instructions.add(new VarInsnNode(Opcodes.ALOAD,0));nudge.instructions.add(new InsnNode(Opcodes.DCONST_1));nudge.instructions.add(new FieldInsnNode(Opcodes.PUTFIELD,arrow,"len","D"));nudge.instructions.add(new InsnNode(Opcodes.RETURN));
		vector.methods.addAll(List.of(init,stretch,nudge));ClassNode host=new ClassNode();host.name="fixture/geo/Host";host.superName="java/lang/Object";
		Function<String,ClassNode>classes=name->name.equals(arrow)?vector:null;
		List<DefinedMethodContracts.MethodContract>proved=new ArrayList<>();for(MethodNode m:vector.methods)proved.add(new DefinedMethodContracts.MethodContract(arrow,m.name,m.desc,DefinedMethodContracts.fingerprint(m)));
		LabelNode end=new LabelNode();InsnList after=new InsnList();after.add(end);after.add(new IincInsnNode(4,1));
		java.util.function.Function<AbstractInsnNode[],List<AbstractInsnNode>>window=code->{List<AbstractInsnNode>list=new ArrayList<>(List.of(code));return list;};
		AbstractInsnNode cellEnd=after.getLast();
		assertTrue(NativeTagTrackerSourceInjector.vectorWindowWritesNone(window.apply(new AbstractInsnNode[]{new VarInsnNode(Opcodes.ALOAD,1),new InsnNode(Opcodes.DCONST_1),new MethodInsnNode(Opcodes.INVOKEVIRTUAL,arrow,"stretch","(D)L"+arrow+";",false),new VarInsnNode(Opcodes.ASTORE,1)}),cellEnd,arrow,proved,host,classes),
				"a proved method that only builds a new vector leaves the sampled one alone");
		assertFalse(NativeTagTrackerSourceInjector.vectorWindowWritesNone(window.apply(new AbstractInsnNode[]{new VarInsnNode(Opcodes.ALOAD,1),new MethodInsnNode(Opcodes.INVOKEVIRTUAL,arrow,"nudge","()V",false)}),cellEnd,arrow,proved,host,classes),
				"a proved method that writes an existing vector's field outside its constructor");
		assertFalse(NativeTagTrackerSourceInjector.vectorWindowWritesNone(window.apply(new AbstractInsnNode[]{new VarInsnNode(Opcodes.ALOAD,1),new MethodInsnNode(Opcodes.INVOKEVIRTUAL,other,"hit","()V",false)}),cellEnd,arrow,proved,host,classes),
				"a call whose body nothing proved could do anything");
		assertFalse(NativeTagTrackerSourceInjector.vectorWindowWritesNone(window.apply(new AbstractInsnNode[]{new VarInsnNode(Opcodes.ALOAD,1),new InsnNode(Opcodes.DCONST_0),new FieldInsnNode(Opcodes.PUTFIELD,arrow,"len","D")}),cellEnd,arrow,proved,host,classes),
				"the window writing the field itself");
		LabelNode elsewhere=new LabelNode();
		assertFalse(NativeTagTrackerSourceInjector.vectorWindowWritesNone(window.apply(new AbstractInsnNode[]{new JumpInsnNode(Opcodes.GOTO,elsewhere)}),cellEnd,arrow,proved,host,classes),
				"a jump that leaves the window runs code the proof never read");
		assertTrue(NativeTagTrackerSourceInjector.vectorWindowWritesNone(window.apply(new AbstractInsnNode[]{new JumpInsnNode(Opcodes.GOTO,end)}),cellEnd,arrow,proved,host,classes),
				"a jump to the cell's end is the window's own exit");
		assertFalse(NativeTagTrackerSourceInjector.vectorWindowWritesNone(window.apply(new AbstractInsnNode[]{new InvokeDynamicInsnNode("run","()Ljava/lang/Runnable;",new Handle(Opcodes.H_INVOKESTATIC,"java/lang/invoke/LambdaMetafactory","metafactory","()V",false))}),cellEnd,arrow,proved,host,classes),
				"a dynamic call");
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
		Path base=Path.of(System.getProperty("forbric.predicateBase",TestFixtures.stagedBase().toString()));TestFixtures.requireFiles(Fixture.STAGED,"source-tag physical fixtures",base);TestFixtures.require(Fixture.JAVA_25,Runtime.version().feature()>=25,"actual physical class version requires Java 25");
		Set<Path>jars=new LinkedHashSet<>();jars.add(base);jars.addAll(dependencies());Function<String,byte[]>resources=name->{try{for(Path path:jars){if(Files.isDirectory(path)){Path file=path.resolve(name);if(Files.isRegularFile(file))return Files.readAllBytes(file);}else if(Files.isRegularFile(path))try(ZipFile jar=new ZipFile(path.toFile())){var entry=jar.getEntry(name);if(entry!=null)return jar.getInputStream(entry).readAllBytes();}}try(var in=NativeTagTrackerSourceInjectorTest.class.getResourceAsStream("/"+name)){return in==null?null:in.readAllBytes();}}catch(java.io.IOException unavailable){throw new RuntimeException(unavailable);}};
		NativeGameReferences reader=new NativeGameReferences(resources,null,TestFixtures.launchSources(base));Function<String,ClassNode>classes=name->{byte[]bytes=resources.apply(name+".class");if(bytes==null)return null;if(name.equals(OWNER))bytes=InjectorExecution.transform(new UntrackedFluidEyeQueryInjector(),name.replace('/','.'),bytes,EnvType.SERVER);if(name.equals(OWNER))bytes=InjectorExecution.transform(new ForgeEntityFluidsInjector(),name.replace('/','.'),bytes,EnvType.SERVER);if(name.equals("net/minecraft/world/level/material/Fluid"))bytes=InjectorExecution.transform(new ForeignFluidTypeInjector(),name.replace('/','.'),bytes,EnvType.SERVER);bytes=InjectorExecution.transform(new PredicateGetterResultRecorder(),name.replace('/','.'),bytes,EnvType.SERVER);return parse(bytes);};return new Inputs(resources,name->reader.get(Ecosystem.FABRIC,name),classes);
	}
	private static ClassNode parse(byte[]bytes){ClassNode node=new ClassNode();new ClassReader(bytes).accept(node,ClassReader.EXPAND_FRAMES);return node;}
	private static byte[]bytes(ClassNode node){ClassWriter writer=new ClassWriter(ClassWriter.COMPUTE_FRAMES|ClassWriter.COMPUTE_MAXS){@Override protected String getCommonSuperClass(String a,String b){return a.equals(b)?a:"java/lang/Object";}};node.accept(writer);return writer.toByteArray();}
}
