package net.forbric.kernel.mixin;

import static org.junit.jupiter.api.Assertions.*;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;
import net.forbric.api.Ecosystem;
import net.forbric.kernel.TestFixtures;
import net.forbric.kernel.TestFixtures.Fixture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;

/** The real API's lost expression, backed by the actual hash-pinned native host and carrier defaults. */
@ResourceLock("system-properties")
class MixinDefaultPredicateTransportTest {
	private static final String HOST="net/minecraft/client/renderer/block/FluidRenderer";
	private static final String WRAP="Lcom/llamalad7/mixinextras/injector/wrapoperation/WrapOperation;";
	private static final List<ZipFile> OPEN = new ArrayList<>();
	@AfterEach void reset()throws Exception{System.clearProperty(MixinDefaultPredicateTransport.PROPERTY);for(ZipFile zip:OPEN)zip.close();OPEN.clear();}

	@Test void theActualApiPredicateIsTransportedWithItsBodyAndResidualOrIntact() throws Exception {
		Context c=context();ClassNode mixin=actualMixin();MethodNode handler=handler(mixin);String originalDesc=handler.desc;byte[] before=body(handler);
		assertEquals(1,MixinDefaultPredicateTransport.adapt(mixin,c.current(),c.nativeClasses()));
		MethodNode wrapper=mixin.methods.stream().filter(m->m.name.equals("modifyNonOverlayCheck")).findFirst().orElseThrow();
		assertEquals(WRAP,MixinFit.injectorOf(wrapper).desc);
		assertEquals("INVOKE",MixinFit.value(MixinFit.atNodes(MixinFit.injectorOf(wrapper)).getFirst(),"value"));
		assertTrue(MixinFit.value(MixinFit.atNodes(MixinFit.injectorOf(wrapper)).getFirst(),"target").toString().contains("shouldDisplayFluidOverlay"));
		assertEquals(originalDesc,handler.desc);assertArrayEquals(before,body(handler),"the original guest computation is renamed, never rewritten");
		assertEquals(0,MixinDefaultPredicateTransport.adapt(mixin,c.current(),c.nativeClasses()));
	}
	@Test void anOldBaseWithoutNativeReferencesAndTheExplicitSwitchRefuseTheMove()throws Exception{
		Context c=context();assertEquals(0,MixinDefaultPredicateTransport.adapt(actualMixin(),c.current(),name->null));
		System.setProperty(MixinDefaultPredicateTransport.PROPERTY,"off");assertEquals(0,MixinDefaultPredicateTransport.adapt(actualMixin(),c.current(),c.nativeClasses()));
	}
	@Test void changedTruthPolarityAndChangedTrueContinuationAreRefused()throws Exception{
		Context c=context();ClassNode host=c.current().apply(HOST);MethodInsnNode call=overlay(host);((JumpInsnNode)next(call)).setOpcode(Opcodes.IFNE);
		assertEquals(0,MixinDefaultPredicateTransport.adapt(actualMixin(),c.current(),c.nativeClasses()));
		c=context();host=c.current().apply(HOST);call=overlay(host);JumpInsnNode branch=(JumpInsnNode)next(call);
		for(var i=next(branch);i!=null&&i!=next(branch.label);i=next(i))if(i instanceof MethodInsnNode m){m.name="differentContinuation";break;}
		assertEquals(0,MixinDefaultPredicateTransport.adapt(actualMixin(),c.current(),c.nativeClasses()));
	}
	@Test void aDifferentDefaultAlgorithmOpaqueGetterAndMutableProjectionAreRefused()throws Exception{
		Context c=context();ClassNode defaults=c.current().apply("net/neoforged/neoforge/common/extensions/IBlockExtension");
		MethodNode method=defaults.methods.stream().filter(m->m.name.equals("shouldDisplayFluidOverlay")).findFirst().orElseThrow();
		for(var i:method.instructions)if(i instanceof TypeInsnNode t&&t.getOpcode()==Opcodes.INSTANCEOF){t.desc="arbitrary/UnrelatedType";break;}
		assertEquals(0,MixinDefaultPredicateTransport.adapt(actualMixin(),c.current(),c.nativeClasses()));
		c=context();ClassNode base=c.current().apply("net/minecraft/world/level/block/state/BlockBehaviour$BlockStateBase");base.methods.stream().filter(m->m.name.equals("getBlock")).findFirst().orElseThrow().instructions.insert(new InsnNode(Opcodes.NOP));
		assertEquals(0,MixinDefaultPredicateTransport.adapt(actualMixin(),c.current(),c.nativeClasses()));
		c=context();c.current().apply("net/minecraft/world/level/block/state/StateHolder").fields.stream().filter(f->f.name.equals("owner")).findFirst().orElseThrow().access&=~Opcodes.ACC_FINAL;
		assertEquals(0,MixinDefaultPredicateTransport.adapt(actualMixin(),c.current(),c.nativeClasses()));
	}
	@Test void swappedPhiAssignmentsAndChangedSwitchMappingCannotLookLikeTheSameStateInput()throws Exception{
		Context c=context();MethodNode method=hostBody(c.current().apply(HOST));List<VarInsnNode> writes=new ArrayList<>();
		for(var i:method.instructions)if(i instanceof VarInsnNode store&&store.getOpcode()==Opcodes.ASTORE&&store.var==49&&previous(store) instanceof VarInsnNode load&&load.getOpcode()==Opcodes.ALOAD)writes.add(load);
		assertTrue(writes.size()>=2,"actual directional state phi remains the counterexample fixture");int original=writes.get(0).var;writes.get(0).var=writes.get(1).var;writes.get(1).var=original;
		assertEquals(0,MixinDefaultPredicateTransport.adapt(actualMixin(),c.current(),c.nativeClasses()));
		c=context();c.current().apply(HOST+"$1").methods.stream().filter(m->m.name.equals("<clinit>")).findFirst().orElseThrow().instructions.insert(new InsnNode(Opcodes.NOP));
		assertEquals(0,MixinDefaultPredicateTransport.adapt(actualMixin(),c.current(),c.nativeClasses()));
	}
	@Test void groupsSlicesAndMissingOriginalCaptureRemainUnchanged()throws Exception{
		Context c=context();ClassNode mixin=actualMixin();handler(mixin).visibleAnnotations.add(new AnnotationNode("Lorg/spongepowered/asm/mixin/injection/Group;"));
		assertEquals(0,MixinDefaultPredicateTransport.adapt(mixin,c.current(),c.nativeClasses()));
		mixin=actualMixin();AnnotationNode injector=MixinFit.injectorOf(handler(mixin));injector.values.add("slice");injector.values.add(List.of(new AnnotationNode("Lorg/spongepowered/asm/mixin/injection/Slice;")));
		assertEquals(0,MixinDefaultPredicateTransport.adapt(mixin,c.current(),c.nativeClasses()));
		c=context();ClassNode original=c.nativeClasses().apply(HOST);for(LocalVariableNode l:hostBody(original).localVariables)if(l.name.equals("relativeBlock"))l.name="unavailable";
		assertEquals(0,MixinDefaultPredicateTransport.adapt(actualMixin(),c.current(),c.nativeClasses()));
	}

	private record Context(Function<String,ClassNode>current,Function<String,ClassNode>nativeClasses){}
	private static Context context()throws Exception{
		String configured=System.getProperty("forbric.predicateBase");Path base=configured==null?TestFixtures.stagedRoot().resolve("merged-base/patched-mc-merged-26.2.jar"):Path.of(configured);
		TestFixtures.require(Fixture.STAGED,Files.isRegularFile(base),"native-referenced merged base required");
		ZipFile game=new ZipFile(base.toFile());OPEN.add(game);
		Path runtime=TestFixtures.stagedRoot().resolve("neoforge-runtime/neoforge-runtime.jar");TestFixtures.require(Fixture.STAGED,Files.isRegularFile(runtime),"actual carrier required");ZipFile carrier=new ZipFile(runtime.toFile());OPEN.add(carrier);
		Path forgeRuntime=TestFixtures.stagedRoot().resolve("forge-runtime/forge-runtime.jar");TestFixtures.require(Fixture.STAGED,Files.isRegularFile(forgeRuntime),"both actual carrier hierarchies required");ZipFile forge=new ZipFile(forgeRuntime.toFile());OPEN.add(forge);
		NativeGameReferences references=new NativeGameReferences(name->read(game,name));ClassNode host=references.get(Ecosystem.FABRIC,HOST);TestFixtures.require(Fixture.STAGED,host!=null,"hash-pinned native host required (old bases deliberately cannot transport predicates)");
		Map<String,ClassNode> currentCache=new HashMap<>(),nativeCache=new HashMap<>();nativeCache.put(HOST,host);
		Function<String,ClassNode> current=name->{if(currentCache.containsKey(name))return currentCache.get(name);byte[]b=read(game,name+".class");if(b==null)b=read(carrier,name+".class");if(b==null)b=read(forge,name+".class");if(b==null)try(InputStream in=ClassLoader.getSystemResourceAsStream(name+".class")){if(in!=null)b=in.readAllBytes();}catch(Exception unavailable){return null;}if(b==null)return null;ClassNode node=parse(b);currentCache.put(name,node);return node;};
		Function<String,ClassNode> nativeClasses=name->{if(nativeCache.containsKey(name))return nativeCache.get(name);ClassNode node=references.get(Ecosystem.FABRIC,name);if(node!=null)nativeCache.put(name,node);return node;};return new Context(current,nativeClasses);
	}
	private static byte[]read(ZipFile zip,String name){try{var entry=zip.getEntry(name);return entry==null?null:zip.getInputStream(entry).readAllBytes();}catch(Exception unavailable){throw new IllegalStateException(unavailable);}}
	private static ClassNode actualMixin()throws Exception{
		String configured=System.getProperty("forbric.fabricApi");Path api=configured==null?TestFixtures.fabricApi():Path.of(configured);TestFixtures.require(Fixture.STAGED,Files.isRegularFile(api),"actual Fabric API required");
		try(ZipFile z=new ZipFile(api.toFile())){var nested=z.stream().filter(e->e.getName().startsWith("META-INF/jars/fabric-rendering-fluids-v1-")).findFirst().orElseThrow();try(ZipInputStream inner=new ZipInputStream(z.getInputStream(nested))){for(var e=inner.getNextEntry();e!=null;e=inner.getNextEntry())if(e.getName().equals("net/fabricmc/fabric/mixin/client/rendering/fluid/FluidRendererMixin.class"))return parse(inner.readAllBytes());}}throw new AssertionError("actual fluid mixin absent");
	}
	private static MethodNode handler(ClassNode mixin){return mixin.methods.stream().filter(m->m.name.equals("modifyNonOverlayCheck")).findFirst().orElseThrow();}
	private static MethodNode hostBody(ClassNode host){return host.methods.stream().filter(m->m.name.equals("tesselate")).findFirst().orElseThrow();}
	private static MethodInsnNode overlay(ClassNode host){for(var i:hostBody(host).instructions)if(i instanceof MethodInsnNode call&&call.name.equals("shouldDisplayFluidOverlay"))return call;throw new AssertionError("actual overlay call absent");}
	private static AbstractInsnNode next(AbstractInsnNode node){for(var i=node.getNext();i!=null;i=i.getNext())if(i.getOpcode()>=0)return i;return null;}
	private static AbstractInsnNode previous(AbstractInsnNode node){for(var i=node.getPrevious();i!=null;i=i.getPrevious())if(i.getOpcode()>=0)return i;return null;}
	private static ClassNode parse(byte[]bytes){ClassNode c=new ClassNode();new ClassReader(bytes).accept(c,0);return c;}
	private static byte[] body(MethodNode method){MethodNode clone=new MethodNode(method.access,"stable",method.desc,method.signature,method.exceptions.toArray(String[]::new));method.accept(clone);clone.name="stable";clone.visibleAnnotations=null;clone.invisibleAnnotations=null;clone.visibleParameterAnnotations=null;clone.invisibleParameterAnnotations=null;ClassNode c=new ClassNode();c.name="Probe";c.superName="java/lang/Object";c.version=Opcodes.V21;c.methods.add(clone);ClassWriter w=new ClassWriter(0);c.accept(w);return w.toByteArray();}
}
