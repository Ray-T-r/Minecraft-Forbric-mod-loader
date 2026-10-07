package net.forbric.kernel.mixin.weave;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.*;
import org.objectweb.asm.commons.*;
import org.objectweb.asm.tree.*;
import net.fabricmc.api.EnvType;
import net.forbric.api.Ecosystem;
import net.forbric.kernel.TestFixtures;
import net.forbric.kernel.boot.KernelPredicateSeams;
import net.forbric.kernel.mixin.MixinNativePredicateSeam;

/** The actual API callback body, with its owners renamed, is woven into a native-proved source seam. */
class MixinNativePredicateSeamWeaveTest{
	@TempDir static Path work;
	private static Path fixture;private static WeaveHarness.Result on,off,owner,state,medium,type,mutated,nested,nativeFailure,sourceFailure,threads;
	private static final String CONFIG="native-seam.mixins.json",HOST="fixture/seams/Owner";
	@BeforeAll static void weave()throws Exception{
		Path sources=Files.createDirectories(work.resolve("sources"));List<Path>files=new ArrayList<>();
		files.add(write(sources,"Token.java","package fixture.seams;public enum Token{DEFAULT,A,B}"));
		files.add(write(sources,"Behavior.java","package fixture.seams;public interface Behavior{boolean canSupportBoat(Token token,Owner owner);}"));
		files.add(write(sources,"Registry.java","""
			package fixture.seams;import java.util.*;public class Registry{
			 public static int handlers,behaviors,nativeAsks,nativeCalls,getters;
			 public static Collection<Token>getTrackedFluids(){return List.of(Token.A,Token.B);}
			 public static Behavior getFluidBehavior(Token token){return(tag,owner)->{behaviors++;return tag==Token.B;};}
			}
			"""));
		files.add(write(sources,"FluidType.java","""
			package net.neoforged.neoforge.fluids;import fixture.seams.Owner;
			public class FluidType{private final boolean boats;public FluidType(boolean boats){this.boats=boats;}public boolean supportsBoating(Owner owner){return boats;}}
			"""));
		files.add(write(sources,"KernelFabricFluidBehaviors.java","""
			package net.forbric.kernel.runtime;import net.neoforged.neoforge.fluids.FluidType;import fixture.seams.*;
			public class KernelFabricFluidBehaviors{
			 public static boolean enabled(){return true;}public static boolean ownsNeoType(FluidType type){return type instanceof NeoType;}
			 public static final class NeoType extends FluidType{
			  public NeoType(){super(false);}@Override public boolean supportsBoating(Owner owner){Boolean answer=KernelFluidPredicateSeams.nativeDefault(this);if(answer!=null)return answer;Registry.nativeAsks++;return false;}
			 }
			}
			"""));
		files.add(write(sources,"Medium.java","""
			package fixture.seams;import net.neoforged.neoforge.fluids.FluidType;
			public class Medium{private final FluidType kind;public Medium(FluidType kind){this.kind=kind;}public FluidType type(){return kind;}public boolean query(State state,Owner owner){return type().supportsBoating(owner);}}
			"""));
		files.add(write(sources,"State.java","""
			package fixture.seams;import java.util.*;
			public class State{private final Medium medium;private final Set<Token>tags=Set.of(Token.A,Token.B);public State(Medium medium){this.medium=medium;}public Medium medium(){return medium;}public boolean supports(Owner owner){return medium().query(this,owner);}public boolean is(Token token){return tags.contains(token);}}
			"""));
		files.add(write(sources,"NativeOwner.java","package fixture.seams;public class NativeOwner extends Owner{public NativeOwner(State state){super(state);}@Override public boolean current(State state){return true;}}"));
		files.add(write(sources,"NativeState.java","package fixture.seams;public class NativeState extends State{public NativeState(Medium medium){super(medium);}@Override public boolean supports(Owner owner){return true;}}"));
		files.add(write(sources,"NativeMedium.java","package fixture.seams;import net.neoforged.neoforge.fluids.FluidType;public class NativeMedium extends Medium{public NativeMedium(FluidType type){super(type);}@Override public FluidType type(){Registry.getters++;return super.type();}}"));
		files.add(write(sources,"NativeType.java","package fixture.seams;import net.neoforged.neoforge.fluids.FluidType;public class NativeType extends FluidType{public NativeType(){super(false);}@Override public boolean supportsBoating(Owner owner){return true;}}"));
		files.add(write(sources,"Bootstrap.java","package fixture.seams;public class Bootstrap{public static String KEY;public static void install(){}}"));
		Path ownerFile=write(sources,"Owner.java",ownerSource(false));files.add(ownerFile);
		files.add(write(sources,"CounterMixin.java","""
			package fixture.seams.mixin;import fixture.seams.*;import org.spongepowered.asm.mixin.Mixin;import org.spongepowered.asm.mixin.injection.At;import com.llamalad7.mixinextras.injector.wrapoperation.*;
			@Mixin(Owner.class)public class CounterMixin{
			 @WrapOperation(method={"checkInWater","isUnderwater"},at=@At(value="INVOKE",target="Lfixture/seams/Owner;current(Lfixture/seams/State;)Z"))
			 private boolean count(Owner owner,State state,Operation<Boolean>original){Registry.nativeCalls++;return original.call(owner,state);}
			}
			"""));
		files.add(write(sources,"ChangedDefaultMixin.java","""
			package fixture.seams.mixin;import fixture.seams.*;import org.spongepowered.asm.mixin.Mixin;import org.spongepowered.asm.mixin.injection.*;import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
			@Mixin(State.class)public class ChangedDefaultMixin{@Inject(method="supports",at=@At("HEAD"),cancellable=true)private void altered(Owner owner,CallbackInfoReturnable<Boolean>ci){ci.setReturnValue(true);}}
			"""));
		// Compile the production runtime bridge against the renamed stand-ins, including its complete scope logic.
		Path repository=Path.of(System.getProperty("user.dir"));files.add(repository.resolve("src/runtime/java/net/forbric/kernel/runtime/KernelFluidPredicateSeams.java"));files.add(repository.resolve("src/main/java/net/forbric/kernel/boot/KernelPredicateSeams.java"));files.add(repository.resolve("src/runtime/java/net/forbric/kernel/runtime/KernelSharedPredicateScopes.java"));files.add(repository.resolve("src/main/java/net/forbric/kernel/boot/KernelSharedPredicateContracts.java"));
		Path config=write(sources,CONFIG,"{\"required\":false,\"minVersion\":\"0.8\",\"package\":\"fixture.seams.mixin\",\"compatibilityLevel\":\"JAVA_21\",\"mixins\":[\"SourceMixin\",\"CounterMixin\"],\"injectors\":{\"defaultRequire\":0}}");
		Path changed=write(sources,"changed.mixins.json","{\"required\":true,\"minVersion\":\"0.8\",\"package\":\"fixture.seams.mixin\",\"compatibilityLevel\":\"JAVA_21\",\"mixins\":[\"ChangedDefaultMixin\"]}");
		Path rawFixture=WeaveHarness.fixture(work,"native-seam",files,Map.of(CONFIG,config,"changed.mixins.json",changed));Map<String,byte[]>base=entries(rawFixture);
		Path nativeDir=Files.createDirectories(work.resolve("native")),nativeFile=write(nativeDir,"Owner.java",ownerSource(true));
		assertEquals(0,ToolProvider.getSystemJavaCompiler().run(null,null,null,"-proc:none","--release","21","-cp",work.resolve("native-seam-classes").toString(),"-d",nativeDir.toString(),nativeFile.toString()));byte[]nativeOwner=Files.readAllBytes(nativeDir.resolve(HOST+".class"));
		base.put("fixture/seams/mixin/SourceMixin.class",actualGuest());
		fixture=prepare(base,nativeOwner,"on",true);on=run(fixture,"on","probe",false);
		off=run(prepare(base,nativeOwner,"off",false),"off","probe",false);
		owner=run(fixture,"owner","ownerOverride",false);state=run(fixture,"state","stateOverride",false);medium=run(fixture,"medium","getterOverride",false);type=run(fixture,"type","typeOverride",false);mutated=run(fixture,"mutated","probe",true);
		nested=run(fixture,"nested","nestedScopes",false);nativeFailure=run(fixture,"native-failure","nativeFailure",false);sourceFailure=run(fixture,"source-failure","sourceFailure",false);threads=run(fixture,"threads","threadIsolation",false);
	}
	@Test void theActualCallbackSeesBothTagsWhileNativeAndGuestRunOnce()throws Exception{
		assertTrue(on.printed(WeaveHarnessMain.DONE+" true; handlers=1; behaviors=2; nativeAsks=0; nativeCalls=1; getters=0"),on.describe());WeaveHarness.assertWovenAndVerified(on,HOST,fixture);
	}
	@Test void concreteOwnerStateGetterAndTypeOverridesKeepNativeWithNoExtraGetterOrGuest(){
		for(var result:List.of(owner,state,type,mutated))assertTrue(result.printed(WeaveHarnessMain.DONE+" true; handlers=0; behaviors=0; nativeAsks=0; nativeCalls=1; getters=0"),result.describe());
		assertTrue(medium.printed(WeaveHarnessMain.DONE+" false; handlers=0; behaviors=0; nativeAsks=1; nativeCalls=1; getters=1"),medium.describe());
		assertTrue(mutated.printed("source predicate callback was not executed; native operation retained: final method witness is unavailable or changed"),mutated.describe());
		assertTrue(owner.printed("native concrete override"),owner.describe());
	}
	@Test void theDisabledControlRetainsThePreviouslySelectedAdapterDefault(){assertTrue(off.printed(WeaveHarnessMain.DONE+" false; handlers=0; behaviors=0; nativeAsks=1; nativeCalls=1; getters=0"),off.describe());}
	@Test void nestedScopesRestoreTheirParentAndExceptionsRestoreThePreviousScope(){
		assertTrue(nested.printed(WeaveHarnessMain.DONE+" true; natives=2; sources=2; outside=false; nativeAsks=1"),nested.describe());
		assertTrue(nativeFailure.printed(WeaveHarnessMain.DONE+" native-failed; natives=1; sources=0; outside=false; nativeAsks=1"),nativeFailure.describe());
		assertTrue(sourceFailure.printed(WeaveHarnessMain.DONE+" source-failed; natives=1; sources=1; outside=false; nativeAsks=1"),sourceFailure.describe());
	}
	@Test void anActiveNativeScopeNeverSuppressesAnotherThreadsAdapterCallback(){assertTrue(threads.printed(WeaveHarnessMain.DONE+" true; outside=false; nativeAsks=1"),threads.describe());}
	private static String ownerSource(boolean original){return """
		package fixture.seams;import net.neoforged.neoforge.fluids.FluidType;import net.forbric.kernel.runtime.KernelFabricFluidBehaviors;
		public class Owner{
		 private final State state;public Owner(){this(new State(new Medium(new KernelFabricFluidBehaviors.NeoType())));}public Owner(State state){this.state=state;}
		 public boolean current(State state){return state.supports(this);}
		 public boolean checkInWater(){State current=state;return BODY;}public boolean isUnderwater(){State current=state;return BODY;}
		 public String probe(){return finish(this);}public String ownerOverride(){return finish(new NativeOwner(state));}public String stateOverride(){return finish(new Owner(new NativeState(new Medium(new KernelFabricFluidBehaviors.NeoType()))));}
		 public String getterOverride(){return finish(new Owner(new State(new NativeMedium(new KernelFabricFluidBehaviors.NeoType()))));}public String typeOverride(){return finish(new Owner(new State(new Medium(new NativeType()))));}
		 public String nestedScopes(){Bootstrap.install();int[]natives={0},sources={0};boolean value=net.forbric.kernel.runtime.KernelFluidPredicateSeams.query(this,state,Bootstrap.KEY,()->{
		  boolean inner=net.forbric.kernel.runtime.KernelFluidPredicateSeams.query(this,state,Bootstrap.KEY,()->{natives[0]++;return current(state);},()->{sources[0]++;return true;});if(!inner)throw new AssertionError();natives[0]++;return current(state);},()->{sources[0]++;return true;});
		  boolean outside=current(state);return value+"; natives="+natives[0]+"; sources="+sources[0]+"; outside="+outside+"; nativeAsks="+Registry.nativeAsks;}
		 public String nativeFailure(){return failed(true);}public String sourceFailure(){return failed(false);}
		 private String failed(boolean nativeFailure){Bootstrap.install();int[]natives={0},sources={0};String message="missing failure";try{net.forbric.kernel.runtime.KernelFluidPredicateSeams.query(this,state,Bootstrap.KEY,()->{natives[0]++;if(nativeFailure)throw new IllegalStateException("native-failed");return current(state);},()->{sources[0]++;throw new IllegalStateException("source-failed");});}catch(IllegalStateException failed){message=failed.getMessage();}
		  boolean outside=current(state);return message+"; natives="+natives[0]+"; sources="+sources[0]+"; outside="+outside+"; nativeAsks="+Registry.nativeAsks;}
		 public String threadIsolation()throws Exception{Bootstrap.install();var entered=new java.util.concurrent.CountDownLatch(1);var release=new java.util.concurrent.CountDownLatch(1);var value=new java.util.concurrent.atomic.AtomicReference<Boolean>();var error=new java.util.concurrent.atomic.AtomicReference<Throwable>();
		  Thread worker=new Thread(()->{try{value.set(net.forbric.kernel.runtime.KernelFluidPredicateSeams.query(this,state,Bootstrap.KEY,()->{entered.countDown();try{release.await();}catch(InterruptedException interrupted){throw new RuntimeException(interrupted);}return current(state);},()->true));}catch(Throwable failed){error.set(failed);}});worker.start();if(!entered.await(5,java.util.concurrent.TimeUnit.SECONDS))throw new AssertionError("worker did not enter scope");boolean outside=current(state);release.countDown();worker.join(5000);if(worker.isAlive()||error.get()!=null)throw new AssertionError(error.get());return value.get()+"; outside="+outside+"; nativeAsks="+Registry.nativeAsks;}
		 private static String finish(Owner owner){Bootstrap.install();boolean value=owner.checkInWater();return value+"; handlers="+Registry.handlers+"; behaviors="+Registry.behaviors+"; nativeAsks="+Registry.nativeAsks+"; nativeCalls="+Registry.nativeCalls+"; getters="+Registry.getters;}
		}
		""".replace("BODY",original?"current.is(Token.DEFAULT)":"current(current)");}
	private static Path prepare(Map<String,byte[]>base,byte[]nativeOwner,String setting,boolean expect)throws Exception{
		Map<String,byte[]>bytes=new HashMap<>(base);ClassNode guest=parse(bytes.get("fixture/seams/mixin/SourceMixin.class"));
		if(setting.equals("off"))System.setProperty(MixinNativePredicateSeam.PROPERTY,"off");int count=MixinNativePredicateSeam.adapt(guest,name->parse(bytes.get(name+".class")),name->name.equals(HOST)?parse(nativeOwner):null);System.clearProperty(MixinNativePredicateSeam.PROPERTY);assertEquals(expect?1:0,count);
		ClassWriter writer=new ClassWriter(ClassWriter.COMPUTE_MAXS);guest.accept(writer);bytes.put("fixture/seams/mixin/SourceMixin.class",writer.toByteArray());
		if(expect){String key=null;for(var method:guest.methods)for(var instruction:method.instructions)if(instruction instanceof LdcInsnNode ldc&&ldc.cst instanceof String s&&KernelPredicateSeams.contract(s)!=null)key=s;assertNotNull(key);
			var contract=KernelPredicateSeams.contract(key);String code="package fixture.seams;public class Bootstrap{public static String KEY;public static void install(){KEY=net.forbric.kernel.boot.KernelPredicateSeams.register("+contract(contract)+");}}";Path boot=write(work.resolve("bootstrap"),"Bootstrap.java",code),out=Files.createDirectories(work.resolve("bootstrap-classes"));assertEquals(0,ToolProvider.getSystemJavaCompiler().run(null,null,null,"-proc:none","--release","21","-cp",System.getProperty("java.class.path"),"-d",out.toString(),boot.toString()));bytes.put("fixture/seams/Bootstrap.class",Files.readAllBytes(out.resolve("fixture/seams/Bootstrap.class")));}
		Path jar=work.resolve("prepared-"+setting+".jar");try(ZipOutputStream zip=new ZipOutputStream(Files.newOutputStream(jar))){for(var entry:bytes.entrySet()){zip.putNextEntry(new ZipEntry(entry.getKey()));zip.write(entry.getValue());zip.closeEntry();}}return jar;
	}
	private static String contract(KernelPredicateSeams.Contract contract){String prefix="net.forbric.kernel.boot.KernelPredicateSeams.";List<String>guards=new ArrayList<>();for(var guard:contract.guards()){var m=guard.method();guards.add("new "+prefix+"Guard("+value(guard.receiver())+",new net.forbric.kernel.boot.DefinedMethodContracts.MethodContract(\""+m.owner()+"\",\""+m.name()+"\",\""+m.descriptor()+"\",\""+m.fingerprint()+"\"),"+guard.direct()+")");}return"new "+prefix+"Contract(java.util.List.of("+String.join(",",guards)+"),\""+contract.question()+"\",\""+contract.descriptor()+"\")";}
	private static String value(KernelPredicateSeams.Value value){String p="net.forbric.kernel.boot.KernelPredicateSeams.";if(value instanceof KernelPredicateSeams.Root r)return"new "+p+"Root("+r.index()+")";if(value instanceof KernelPredicateSeams.Opaque o)return"new "+p+"Opaque("+value(o.receiver())+")";var f=(KernelPredicateSeams.Projection)value;return"new "+p+"Projection("+value(f.receiver())+",\""+f.owner()+"\",\""+f.name()+"\",\""+f.descriptor()+"\")";}
	private static byte[]actualGuest()throws Exception{
		TestFixtures.requireFiles(TestFixtures.Fixture.STAGED, "actual Fabric API callback required",
				Path.of(System.getProperty("forbric.fabricApi",TestFixtures.fabricApi().toString())));
		Path api=Path.of(System.getProperty("forbric.fabricApi",TestFixtures.fabricApi().toString()));byte[]original=null;try(ZipFile outer=new ZipFile(api.toFile())){var entry=outer.stream().filter(e->e.getName().startsWith("META-INF/jars/fabric-content-registries-v0-")).findFirst().orElseThrow();try(ZipInputStream inner=new ZipInputStream(outer.getInputStream(entry))){for(var e=inner.getNextEntry();e!=null;e=inner.getNextEntry())if(e.getName().equals("net/fabricmc/fabric/mixin/content/registry/fluid/AbstractBoatMixin.class"))original=inner.readAllBytes();}}assertNotNull(original);
		Map<String,String>map=Map.of("net/fabricmc/fabric/mixin/content/registry/fluid/AbstractBoatMixin","fixture/seams/mixin/SourceMixin","net/minecraft/world/entity/vehicle/boat/AbstractBoat",HOST,"net/minecraft/world/entity/Entity",HOST,"net/minecraft/world/level/material/FluidState","fixture/seams/State","net/minecraft/tags/TagKey","fixture/seams/Token","net/fabricmc/fabric/impl/content/registry/fluid/EntityFluidInteractionRegistryImpl","fixture/seams/Registry","net/fabricmc/fabric/api/registry/fluid/FluidBehavior","fixture/seams/Behavior");
		ClassNode guest=new ClassNode();new ClassReader(original).accept(new ClassRemapper(guest,new Remapper(){@Override public String map(String owner){return map.getOrDefault(owner,owner);}}),0);guest.version=Opcodes.V21;
		MethodNode handler=guest.methods.stream().filter(m->m.name.equals("customFluidSupport")).findFirst().orElseThrow();AnnotationNode injector=handler.visibleAnnotations.stream().filter(a->a.desc.endsWith("/WrapOperation;")).findFirst().orElseThrow();AnnotationNode at=null;
		for(int i=0;i<injector.values.size();i+=2)if(injector.values.get(i).equals("at"))at=((List<AnnotationNode>)injector.values.get(i+1)).getFirst();assertNotNull(at);for(int i=0;i<at.values.size();i+=2)if(at.values.get(i).equals("target"))at.values.set(i+1,"Lfixture/seams/State;is(Lfixture/seams/Token;)Z");
		InsnList count=new InsnList();count.add(new FieldInsnNode(Opcodes.GETSTATIC,"fixture/seams/Registry","handlers","I"));count.add(new InsnNode(Opcodes.ICONST_1));count.add(new InsnNode(Opcodes.IADD));count.add(new FieldInsnNode(Opcodes.PUTSTATIC,"fixture/seams/Registry","handlers","I"));handler.instructions.insert(count);
		ClassWriter writer=new ClassWriter(ClassWriter.COMPUTE_MAXS);guest.accept(writer);return writer.toByteArray();
	}
	private static WeaveHarness.Result run(Path jar,String label,String method,boolean changed)throws Exception{
		List<WeaveHarness.Config>configs=new ArrayList<>(List.of(new WeaveHarness.Config(CONFIG,"renamed-native-seam",Ecosystem.FABRIC)));if(changed)configs.add(new WeaveHarness.Config("changed.mixins.json","native-default-mutation",Ecosystem.FABRIC));
		return WeaveHarness.run(work,label,jar,configs,List.of(),EnvType.SERVER,"fixture.seams.Owner",method,Map.of());
	}
	private static Map<String,byte[]>entries(Path jar)throws Exception{Map<String,byte[]>map=new HashMap<>();try(ZipFile zip=new ZipFile(jar.toFile())){for(var e:Collections.list(zip.entries()))map.put(e.getName(),zip.getInputStream(e).readAllBytes());}return map;}
	private static ClassNode parse(byte[]bytes){if(bytes==null)return null;ClassNode node=new ClassNode();new ClassReader(bytes).accept(node,0);return node;}
	private static Path write(Path dir,String name,String source)throws Exception{Files.createDirectories(dir);Path file=dir.resolve(name);Files.writeString(file,source);return file;}
}
