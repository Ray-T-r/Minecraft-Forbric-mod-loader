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
	private record Inputs(ClassNode mixin,Map<String,ClassNode> classes,ClassNode reference,java.util.function.Function<String,ClassNode> current,java.util.function.Function<String,ClassNode> source,java.util.function.Function<String,ClassNode> nativeSource,java.util.function.Function<String,ClassNode> runtimeSource){}
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
	@Test void anUnrelatedRecordProducerAndChangedNativeHelpersCannotAdmitSourceFallbacks()throws Exception{
		Inputs unrelated=inputs();for(MethodNode host:unrelated.classes.get(unrelated.reference.name).methods)for(var instruction:host.instructions)if(instruction instanceof MethodInsnNode call&&call.desc.endsWith("Lnet/neoforged/neoforge/transfer/item/ContainerOrHandler;")){call.owner="arbitrary/Unrelated";call.name="empty";}assertEquals(0,adapt(unrelated));
		Inputs changed=inputs();ClassNode helper=changed.current.apply("net/neoforged/neoforge/transfer/item/VanillaInventoryCodeHooks");MethodNode producer=helper.methods.stream().filter(m->m.name.equals("getEntityContainerOrHandler")).findFirst().orElseThrow();producer.instructions.insert(new InsnNode(Opcodes.NOP));assertEquals(0,adapt(changed));
	}
	@Test void missingNativeOriginsAndAConstructorWhichDropsTheNullableOperandAreRejected()throws Exception{
		Inputs missing=inputs();assertEquals(0,MixinNullableCompositeCallback.adapt(missing.mixin,missing.current,missing.source,n->null,missing.runtimeSource));
		Inputs wrong=inputs();ClassNode record=wrong.classes.get("net/neoforged/neoforge/transfer/item/ContainerOrHandler");MethodNode constructor=record.methods.stream().filter(m->m.name.equals("<init>")).findFirst().orElseThrow();for(var instruction:constructor.instructions)if(instruction instanceof FieldInsnNode put&&put.getOpcode()==Opcodes.PUTFIELD&&put.desc.equals("Lnet/minecraft/world/Container;")){AbstractInsnNode value=instruction.getPrevious();while(value.getOpcode()<0)value=value.getPrevious();constructor.instructions.set(value,new InsnNode(Opcodes.ACONST_NULL));break;}assertEquals(0,adapt(wrong));
	}
	@Test void aNewPlatformPredicateMustStillContainTheOriginalNullableDomain()throws Exception{
		Inputs input=inputs();String owner="net/neoforged/neoforge/transfer/item/VanillaInventoryCodeHooks";ClassNode helper=input.current.apply(owner);MethodNode predicate=helper.methods.stream().filter(m->Type.getReturnType(m.desc).equals(Type.BOOLEAN_TYPE)&&Arrays.stream(m.instructions.toArray()).anyMatch(i->i instanceof TypeInsnNode t&&t.getOpcode()==Opcodes.INSTANCEOF&&t.desc.equals("net/minecraft/world/Container"))).findFirst().orElseThrow();
		for(var instruction:predicate.instructions.toArray())if(instruction instanceof TypeInsnNode t&&t.getOpcode()==Opcodes.INSTANCEOF&&t.desc.equals("net/minecraft/world/Container")){AbstractInsnNode next=t.getNext();while(next.getOpcode()<0)next=next.getNext();JumpInsnNode branch=(JumpInsnNode)next;predicate.instructions.set(branch,new JumpInsnNode(Opcodes.IFEQ,branch.label));break;}
		assertEquals(0,MixinNullableCompositeCallback.adapt(input.mixin,input.current,input.source,input.nativeSource,n->n.equals(owner)?helper:input.runtimeSource.apply(n)),"even a hash-proved new platform callback cannot drop the source container domain");
	}
	@Test void aNewPlatformProducerCannotUseAnotherStateForTheNullableField()throws Exception{
		Inputs input=inputs();ClassNode target=input.classes.get(input.reference.name);MethodNode producer=target.methods.stream().filter(m->m.name.equals("getContainerOrHandlerAt")&&m.desc.contains("DDD")).findFirst().orElseThrow();
		MethodInsnNode block=Arrays.stream(producer.instructions.toArray()).filter(i->i instanceof MethodInsnNode c&&c.name.equals("getBlockContainer")).map(MethodInsnNode.class::cast).findFirst().orElseThrow();AbstractInsnNode state=block.getPrevious();while(state.getOpcode()<0)state=state.getPrevious();producer.instructions.set(state,new FieldInsnNode(Opcodes.GETSTATIC,target.name,"differentState","Lnet/minecraft/world/level/block/state/BlockState;"));target.fields.add(new FieldNode(Opcodes.ACC_PRIVATE|Opcodes.ACC_STATIC,"differentState","Lnet/minecraft/world/level/block/state/BlockState;",null,null));
		assertEquals(0,MixinNullableCompositeCallback.adapt(input.mixin,input.current,input.source,n->n.equals(target.name)?target:input.nativeSource.apply(n),input.runtimeSource));
	}
	@Test void theFinalCaptureMustComeFromTheProvedOperationRatherThanAnotherLocal()throws Exception{
		ClassNode target=new ClassNode();target.name="renamed/Owner";target.superName="java/lang/Object";target.access=Opcodes.ACC_PUBLIC;target.version=Opcodes.V21;
		MethodNode host=new MethodNode(Opcodes.ACC_PUBLIC|Opcodes.ACC_STATIC,"host","()V",null,null),outer=new MethodNode(Opcodes.ACC_PRIVATE|Opcodes.ACC_STATIC,"callback","(Ljava/lang/Object;)V",null,null);String lookup="Lrenamed/Producer;resolve()Ljava/lang/Object;";
		host.instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC,"renamed/Producer","resolve","()Ljava/lang/Object;",false));host.instructions.add(new VarInsnNode(Opcodes.ASTORE,0));host.instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC,"renamed/Producer","other","()Ljava/lang/Object;",false));host.instructions.add(new VarInsnNode(Opcodes.ASTORE,1));VarInsnNode capture=new VarInsnNode(Opcodes.ALOAD,0);host.instructions.add(capture);host.instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC,target.name,outer.name,outer.desc,false));host.instructions.add(new InsnNode(Opcodes.RETURN));host.maxLocals=2;host.maxStack=1;
		var proof=MixinNullableCompositeCallback.class.getDeclaredMethod("captureFromLookup",ClassNode.class,MethodNode.class,MethodNode.class,String.class);proof.setAccessible(true);assertEquals(true,proof.invoke(null,target,host,outer,lookup));capture.var=1;assertEquals(false,proof.invoke(null,target,host,outer,lookup));
	}
	private static int adapt(Inputs input){return MixinNullableCompositeCallback.adapt(input.mixin,input.current,input.source,input.nativeSource,input.runtimeSource);}
	private static Inputs inputs()throws Exception{
		Path api=Path.of(System.getProperty("forbric.fabricApi",TestFixtures.fabricApi().toString())),base=Path.of(System.getProperty("forbric.predicateBase",TestFixtures.stagedRoot().resolve("merged-base/patched-mc-merged-26.2.jar").toString())),neo=TestFixtures.stagedRoot().resolve("neoforge-runtime/neoforge-runtime.jar");
		TestFixtures.requireFiles(TestFixtures.Fixture.STAGED,"actual API/indexed base/runtime required",api,base,neo);
		ClassNode mixin=null,reference;Map<String,ClassNode> classes=new HashMap<>();String owner="net/minecraft/world/level/block/entity/HopperBlockEntity";
		try(ZipFile jar=new ZipFile(api.toFile())){var module=jar.stream().filter(e->e.getName().startsWith("META-INF/jars/fabric-transfer-api-v1-")).findFirst().orElseThrow();try(ZipInputStream nested=new ZipInputStream(jar.getInputStream(module))){for(var e=nested.getNextEntry();e!=null;e=nested.getNextEntry())if(e.getName().equals("net/fabricmc/fabric/mixin/transfer/HopperBlockEntityMixin.class"))mixin=parse(nested.readAllBytes());}}
		try(ZipFile jar=new ZipFile(base.toFile())){classes.put(owner,parse(jar.getInputStream(jar.getEntry(owner+".class")).readAllBytes()));reference=new NativeGameReferences(p->{try{var e=jar.getEntry(p);return e==null?null:jar.getInputStream(e).readAllBytes();}catch(Exception failed){return null;}}).get(Ecosystem.FABRIC,owner);}
		try(ZipFile jar=new ZipFile(neo.toFile())){String record="net/neoforged/neoforge/transfer/item/ContainerOrHandler";classes.put(record,parse(jar.getInputStream(jar.getEntry(record+".class")).readAllBytes()));}
		var raw=(java.util.function.Function<String,byte[]>)(path->{try(ZipFile jar=new ZipFile(base.toFile())){var entry=jar.getEntry(path);return entry==null?null:jar.getInputStream(entry).readAllBytes();}catch(Exception e){throw new IllegalStateException(e);}});
		NativeGameReferences originals=new NativeGameReferences(raw);
		var runtime=(java.util.function.Function<String,ClassNode>)(name->{try(ZipFile jar=new ZipFile(neo.toFile())){var entry=jar.getEntry(name+".class");return entry==null?null:parse(jar.getInputStream(entry).readAllBytes());}catch(Exception e){throw new IllegalStateException(e);}});
		var current=(java.util.function.Function<String,ClassNode>)(name->classes.computeIfAbsent(name,n->{byte[] bytes=raw.apply(n+".class");return bytes==null?runtime.apply(n):parse(bytes);}));
		assertNotNull(mixin);assertNotNull(reference);return new Inputs(mixin,classes,reference,current,n->originals.get(Ecosystem.FABRIC,n),n->originals.get(Ecosystem.NEOFORGE,n),runtime);
	}
	private static ClassNode parse(byte[] bytes){ClassNode node=new ClassNode();new ClassReader(bytes).accept(node,0);return node;}
}
