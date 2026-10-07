package net.forbric.kernel.mixin;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;
import net.forbric.kernel.TestFixtures;
import net.forbric.kernel.TestFixtures.Fixture;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import org.objectweb.asm.tree.analysis.*;

@ResourceLock("system-properties")
class BarrelRollCameraAdapterTest {
	@AfterEach void reset(){System.clearProperty(MixinCameraRollAdapter.PROPERTY);}
	@Test void actualModBindsOrdinaryMirroredAndBedCallsAndKeepsShareSlotValid() throws Exception {
		ClassNode mixin=mixin(),camera=camera();var ordinary=handler(mixin,"doABarrelRoll$addRoll1");
		assertEquals(4,MixinCameraRollAdapter.adapt(mixin,n->camera));
		assertEquals("(Lnet/minecraft/client/Camera;FFFLcom/llamalad7/mixinextras/sugar/ref/LocalFloatRef;)Z",ordinary.desc);
		assertTrue(ordinary.instructions.iterator().hasNext());
		assertTrue(java.util.stream.StreamSupport.stream(Spliterators.spliteratorUnknownSize(ordinary.instructions.iterator(),0),false).anyMatch(i->i instanceof VarInsnNode v&&v.getOpcode()==Opcodes.ALOAD&&v.var==5));
		assertNotNull(ordinary.invisibleParameterAnnotations[4]);assertNull(ordinary.invisibleParameterAnnotations[3]);
		check(mixin,"doABarrelRoll$addRoll1",MixinCameraRollAdapter.LONG,0);check(mixin,"doABarrelRoll$addRoll2",MixinCameraRollAdapter.LONG,1);check(mixin,"doABarrelRoll$addRoll3",MixinCameraRollAdapter.SHORT,1);
		assertEquals(List.of("setRotation(FFF)V"),MixinFit.stringList(MixinFit.value(MixinFit.injectorOf(handler(mixin,"doABarrelRoll$setRoll")),"method")));
		for(var m:mixin.methods)new Analyzer<>(new BasicVerifier()).analyze(mixin.name,m);
		ClassWriter w=new ClassWriter(ClassWriter.COMPUTE_MAXS);mixin.accept(w);ClassNode reread=new ClassNode();new ClassReader(w.toByteArray()).accept(reread,ClassReader.EXPAND_FRAMES);for(var m:reread.methods)new Analyzer<>(new BasicVerifier()).analyze(reread.name,m);
		assertEquals(0,MixinCameraRollAdapter.adapt(mixin,n->camera));
	}
	@Test void vanillaShapeOffSwitchAndUnexpectedCarrierShapeRemainUntouched() throws Exception {
		ClassNode mixin=mixin(),camera=camera();System.setProperty(MixinCameraRollAdapter.PROPERTY,"off");assertEquals(0,MixinCameraRollAdapter.adapt(mixin,n->camera));reset();
		MethodNode align=handler(camera,"alignWithEntity");for(var i:align.instructions)if(i instanceof MethodInsnNode c&&c.name.equals("setRotation"))c.desc="(FF)V";
		assertEquals(0,MixinCameraRollAdapter.adapt(mixin,n->camera));
		assertEquals(MixinCameraRollAdapter.SHORT,MixinFit.value(MixinFit.atNodes(MixinFit.injectorOf(handler(mixin,"doABarrelRoll$addRoll1"))).getFirst(),"target"));
	}
	private static void check(ClassNode n,String name,String target,int ordinal){AnnotationNode at=MixinFit.atNodes(MixinFit.injectorOf(handler(n,name))).getFirst();assertEquals(target,MixinFit.value(at,"target"));assertEquals(ordinal,MixinFit.value(at,"ordinal"));}
	private static MethodNode handler(ClassNode n,String name){return n.methods.stream().filter(m->m.name.equals(name)).findFirst().orElseThrow();}
	private static ClassNode mixin() throws Exception {return read(Fixture.THIRD_PARTY,Path.of("build/compat-inputs/c2me-barrel-20261001/do_a_barrel_roll-fabric-3.8.4+26.2.jar"),"nl/enjarai/doabarrelroll/mixin/client/roll/CameraMixin");}
	private static ClassNode camera() throws Exception {return read(Fixture.STAGED,TestFixtures.stagedRoot().resolve("merged-base/patched-mc-merged-26.2.jar"),MixinCameraRollAdapter.CAMERA);}
	private static ClassNode read(Fixture kind,Path p,String name) throws Exception {TestFixtures.require(kind,Files.exists(p),p+" absent");try(ZipFile z=new ZipFile(p.toFile())){ClassNode n=new ClassNode();new ClassReader(z.getInputStream(z.getEntry(name+".class")).readAllBytes()).accept(n,ClassReader.EXPAND_FRAMES);return n;}}
}
