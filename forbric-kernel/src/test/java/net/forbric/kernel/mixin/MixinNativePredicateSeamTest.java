package net.forbric.kernel.mixin;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.*;
import java.util.*;
import java.util.function.Function;
import java.util.zip.*;
import net.forbric.api.Ecosystem;
import net.forbric.kernel.TestFixtures;
import net.forbric.kernel.TestFixtures.Fixture;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;

@ResourceLock("system-properties")
class MixinNativePredicateSeamTest{
	@AfterEach void reset(){System.clearProperty(MixinNativePredicateSeam.PROPERTY);}
	@Test void actualSourceBoatAtomsMoveOnlyAfterTheirWholeControlFlowAndDefaultDispatchAreProved()throws Exception{
		Inputs inputs=inputs();ClassNode guest=inputs.guest();assertEquals(1,MixinNativePredicateSeam.adapt(guest,inputs.classes(),inputs.originals()));
		MethodNode original=guest.methods.stream().filter(m->m.name.equals("customFluidSupport")).findFirst().orElseThrow();
		List<String>selectors=MixinFit.stringList(MixinFit.value(MixinFit.injectorOf(original),"method"));assertEquals(1,selectors.size());assertTrue(selectors.getFirst().contains("forbricsourcepredicate"));
		assertEquals(4,guest.methods.size());assertEquals(0,MixinNativePredicateSeam.adapt(guest,inputs.classes(),inputs.originals()));
	}
	@Test void aChangedContinuationOrAnEffectfulCurrentQueryFailsClosed()throws Exception{
		Inputs inputs=inputs();ClassNode host=inputs.classes().apply("net/minecraft/world/entity/vehicle/boat/AbstractBoat");
		MethodNode body=host.methods.stream().filter(m->m.name.equals("checkInWater")).findFirst().orElseThrow();body.instructions.insert(new MethodInsnNode(Opcodes.INVOKESTATIC,"unknown/Effects","run","()V",false));
		Inputs first=inputs;assertEquals(0,MixinNativePredicateSeam.adapt(inputs.guest(),name->name.equals(host.name)?host:first.classes().apply(name),inputs.originals()));
		inputs=inputs();ClassNode query=inputs.classes().apply(host.name);MethodNode entry=query.methods.stream().filter(m->m.name.equals("canBoatInFluid")&&m.desc.startsWith("(Lnet/minecraft/world/level/material/FluidState;")).findFirst().orElseThrow();entry.instructions.insert(new MethodInsnNode(Opcodes.INVOKESTATIC,"unknown/Effects","run","()V",false));Inputs unchanged=inputs;
		assertEquals(0,MixinNativePredicateSeam.adapt(inputs.guest(),name->name.equals(query.name)?query:unchanged.classes().apply(name),inputs.originals()));
	}
	@Test void missingNativeRootAndDisabledControlKeepTheOriginalHandler()throws Exception{
		Inputs inputs=inputs();assertEquals(0,MixinNativePredicateSeam.adapt(inputs.guest(),inputs.classes(),name->null));
		System.setProperty(MixinNativePredicateSeam.PROPERTY,"off");assertEquals(0,MixinNativePredicateSeam.adapt(inputs.guest(),inputs.classes(),inputs.originals()));
	}
	private record Inputs(ClassNode guest,Function<String,ClassNode>classes,Function<String,ClassNode>originals){ }
	private static Inputs inputs()throws Exception{
		Path base=Path.of(System.getProperty("forbric.predicateBase",TestFixtures.stagedRoot().resolve("merged-base/patched-mc-merged-26.2.jar").toString()));
		TestFixtures.requireFiles(Fixture.STAGED,"hash-pinned native predicate base",base);
		List<Path>jars=List.of(base,TestFixtures.stagedRoot().resolve("neoforge-runtime/neoforge-runtime.jar"),TestFixtures.stagedRoot().resolve("forge-runtime/forge-runtime.jar"));
		Function<String,byte[]>resource=name->{try{for(Path path:jars)try(ZipFile zip=new ZipFile(path.toFile())){var entry=zip.getEntry(name);if(entry!=null)return zip.getInputStream(entry).readAllBytes();}return null;}catch(java.io.IOException unavailable){throw new RuntimeException(unavailable);}};
		NativeGameReferences refs=new NativeGameReferences(resource);
		Path api=Path.of(System.getProperty("forbric.fabricApi",TestFixtures.fabricApi().toString()));TestFixtures.requireFiles(Fixture.STAGED,"actual Fabric API",api);ClassNode guest=null;
		try(ZipFile outer=new ZipFile(api.toFile())){var e=outer.stream().filter(entry->entry.getName().startsWith("META-INF/jars/fabric-content-registries-v0-")).findFirst().orElseThrow();try(ZipInputStream inner=new ZipInputStream(outer.getInputStream(e))){for(var entry=inner.getNextEntry();entry!=null;entry=inner.getNextEntry())if(entry.getName().equals("net/fabricmc/fabric/mixin/content/registry/fluid/AbstractBoatMixin.class"))guest=parse(inner.readAllBytes());}}
		assertNotNull(guest);return new Inputs(guest,name->parse(resource.apply(name+".class")),name->refs.get(Ecosystem.FABRIC,name));
	}
	private static ClassNode parse(byte[]bytes){if(bytes==null)return null;ClassNode node=new ClassNode();new ClassReader(bytes).accept(node,0);return node;}
}
