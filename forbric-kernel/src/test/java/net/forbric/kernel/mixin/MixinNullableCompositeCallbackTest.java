package net.forbric.kernel.mixin;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import net.forbric.api.Ecosystem;
import net.forbric.kernel.TestFixtures;

@ResourceLock("system-properties")
class MixinNullableCompositeCallbackTest {
	private record Inputs(ClassNode mixin,Map<String,ClassNode> classes,ClassNode reference){}
	@AfterEach void reset(){System.clearProperty(MixinNullableCompositeCallback.PROPERTY);}
	@Test void bothActualSourceCallbacksKeepTheirBodiesAndCaptureTheAbsentRecord()throws Exception{
		Inputs input=inputs();Map<String,String> bodies=new HashMap<>();for(MethodNode m:input.mixin.methods)if(MixinFit.injectorOf(m)!=null)bodies.put(m.name,MixinInstructionFingerprint.hash(m));
		assertEquals(2,adapt(input));
		for(String name:List.of("hookInsert","hookExtract")){
			MethodNode inner=input.mixin.methods.stream().filter(m->m.name.contains(name+"$forbricnullablecomposite")).findFirst().orElseThrow();assertEquals(bodies.get(name),MixinInstructionFingerprint.hash(inner));
			MethodNode outer=input.mixin.methods.stream().filter(m->m.name.equals(name)).findFirst().orElseThrow();assertNotNull(MixinFit.injectorOf(outer));assertTrue(outer.desc.contains("ContainerOrHandler;"));assertEquals(true,MixinFit.value(MixinFit.injectorOf(outer),"cancellable"));
		}
		assertEquals(0,adapt(input));
	}
	@Test void anEmptySoundingMethodMustProveEveryAlternativeIsAbsent()throws Exception{
		Inputs input=inputs();ClassNode record=input.classes.get("net/neoforged/neoforge/transfer/item/ContainerOrHandler");MethodNode empty=record.methods.stream().filter(m->m.name.equals("isEmpty")).findFirst().orElseThrow();
		for(var instruction:empty.instructions)if(instruction.getOpcode()==Opcodes.ICONST_1){empty.instructions.set(instruction,new InsnNode(Opcodes.ICONST_0));break;}
		assertEquals(0,adapt(input));
	}
	@Test void aReadOfTheOldNullableValueCannotBeReplacedByANullPlaceholder()throws Exception{
		Inputs input=inputs();MethodNode insert=input.mixin.methods.stream().filter(m->m.name.equals("hookInsert")).findFirst().orElseThrow();
		InsnList read=new InsnList();read.add(new VarInsnNode(Opcodes.ALOAD,4));read.add(new InsnNode(Opcodes.POP));AbstractInsnNode end=insert.instructions.getLast();while(end.getOpcode()<0)end=end.getPrevious();insert.instructions.insertBefore(end,read);
		assertEquals(1,adapt(input),"the other independent callback can still migrate");assertNotNull(MixinFit.injectorOf(insert));assertFalse(insert.desc.contains("ContainerOrHandler"));
	}
	@Test void changedLookupInputsAndTheOffControlLeaveTheAffectedCallbackAlone()throws Exception{
		Inputs input=inputs();MethodNode eject=input.classes.get(input.reference.name).methods.stream().filter(m->m.name.equals("ejectItems")).findFirst().orElseThrow();
		AbstractInsnNode first=eject.instructions.getFirst();while(first.getOpcode()<0)first=first.getNext();eject.instructions.set(first,new FieldInsnNode(Opcodes.GETSTATIC,input.reference.name,"otherWorld","Lnet/minecraft/world/level/Level;"));
		assertEquals(1,adapt(input));input=inputs();System.setProperty(MixinNullableCompositeCallback.PROPERTY,"off");assertEquals(0,adapt(input));
	}
	private static int adapt(Inputs input){return MixinNullableCompositeCallback.adapt(input.mixin,input.classes::get,n->input.reference);}
	private static Inputs inputs()throws Exception{
		Path api=Path.of(System.getProperty("forbric.fabricApi",TestFixtures.fabricApi().toString())),base=Path.of(System.getProperty("forbric.predicateBase",TestFixtures.stagedRoot().resolve("merged-base/patched-mc-merged-26.2.jar").toString())),neo=TestFixtures.stagedRoot().resolve("neoforge-runtime/neoforge-runtime.jar");
		TestFixtures.requireFiles(TestFixtures.Fixture.STAGED,"actual API/indexed base/runtime required",api,base,neo);
		ClassNode mixin=null,reference;Map<String,ClassNode> classes=new HashMap<>();String owner="net/minecraft/world/level/block/entity/HopperBlockEntity";
		try(ZipFile jar=new ZipFile(api.toFile())){var module=jar.stream().filter(e->e.getName().startsWith("META-INF/jars/fabric-transfer-api-v1-")).findFirst().orElseThrow();try(ZipInputStream nested=new ZipInputStream(jar.getInputStream(module))){for(var e=nested.getNextEntry();e!=null;e=nested.getNextEntry())if(e.getName().equals("net/fabricmc/fabric/mixin/transfer/HopperBlockEntityMixin.class"))mixin=parse(nested.readAllBytes());}}
		try(ZipFile jar=new ZipFile(base.toFile())){classes.put(owner,parse(jar.getInputStream(jar.getEntry(owner+".class")).readAllBytes()));reference=new NativeGameReferences(p->{try{var e=jar.getEntry(p);return e==null?null:jar.getInputStream(e).readAllBytes();}catch(Exception failed){return null;}}).get(Ecosystem.FABRIC,owner);}
		try(ZipFile jar=new ZipFile(neo.toFile())){String record="net/neoforged/neoforge/transfer/item/ContainerOrHandler";classes.put(record,parse(jar.getInputStream(jar.getEntry(record+".class")).readAllBytes()));}
		assertNotNull(mixin);assertNotNull(reference);return new Inputs(mixin,classes,reference);
	}
	private static ClassNode parse(byte[] bytes){ClassNode node=new ClassNode();new ClassReader(bytes).accept(node,0);return node;}
}
