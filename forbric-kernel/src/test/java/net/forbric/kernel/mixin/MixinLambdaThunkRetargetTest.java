package net.forbric.kernel.mixin;

import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import java.nio.file.*;
import java.util.zip.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import net.forbric.api.Ecosystem;
import net.forbric.kernel.transform.LambdaInvocationThunkInjector;

@ResourceLock("system-properties")
class MixinLambdaThunkRetargetTest {
	private static final String OWNER="example/Route", MEMBER="L"+OWNER+";consume(I)V";
	private static final String OPERATION="Lcom/llamalad7/mixinextras/injector/wrapoperation/Operation;";
	@BeforeEach @AfterEach void reset(){System.clearProperty(MixinExecutionPathRetarget.PROPERTY);MixinRetarget.reset();MixinStubRebind.forget();MergedBaseUncalledMethods.forgetGuests();}
	@Test void theNativeCallAndOneLiveThunkCarryTheOriginalOperationAndArgumentCapture(){
		ClassNode target=target(),reference=reference(),mixin=mixin();MethodNode handler=mixin.methods.getFirst();
		handler.invisibleParameterAnnotations=new List[4];handler.invisibleParameterAnnotations[3]=List.of(ann(MixinRetarget.LOCAL_SUGAR,"argsOnly",true));
		handler.desc="(L"+OWNER+";I"+OPERATION+"I)V";AbstractInsnNode body=handler.instructions.getFirst();
		for(Ecosystem family:List.of(Ecosystem.FABRIC,Ecosystem.FORGE,Ecosystem.NEOFORGE)){
			MixinStubRebind.noteEcosystem(mixin.name,family);var plan=plan(mixin,target,reference);
			assertEquals(2,plan.rewrites().size(),plan.describe());assertTrue(plan.rewrites().getFirst().why().contains("live method-reference thunk"));
			assertTrue(plan.rewrites().stream().anyMatch(r->r.element()==MixinRetarget.Element.LOCAL_INDEX&&r.from().equals("3")&&r.to().equals("1")),plan.describe());
			assertEquals("(I)V",Type.getMethodType(plan.rewrites().getFirst().to().substring(plan.rewrites().getFirst().to().indexOf('('))).getDescriptor());
		}
		var plan=plan(mixin,target,reference);assertEquals(2,MixinRetarget.apply(mixin,plan));assertSame(body,handler.instructions.getFirst());
		assertEquals("(L"+OWNER+";I"+OPERATION+"I)V",handler.desc);
	}
	@Test void aMissingNativeReferenceOrAbsentNativeAnchorCannotAuthorizeAThunk(){
		assertTrue(plan(mixin(),target(),null).isEmpty());ClassNode nativeClass=reference();nativeClass.methods.getFirst().instructions.clear();nativeClass.methods.getFirst().instructions.add(new InsnNode(Opcodes.RETURN));
		assertTrue(plan(mixin(),target(),nativeClass).isEmpty());
	}
	@Test void duplicateThunkRegistrationOrAnUnreachableThunkIsAmbiguous(){
		ClassNode target=target();MethodNode factory=target.methods.stream().filter(m->m.name.equals("factory")).findFirst().orElseThrow();
		MethodNode duplicate=new MethodNode(Opcodes.ACC_PUBLIC,"anotherFactory",factory.desc,null,null);for(AbstractInsnNode i:factory.instructions)duplicate.instructions.add(i.clone(new HashMap<>()));target.methods.add(duplicate);
		assertTrue(plan(mixin(),target,reference()).isEmpty());target=target();target.methods.removeIf(m->m.name.equals("factory"));assertTrue(plan(mixin(),target,reference()).isEmpty());
	}
	@Test void aNativeParameterChangedBeforeTheCallIsNotTheThunksCapture(){
		ClassNode reference=reference();InsnList change=new InsnList();change.add(new InsnNode(Opcodes.ICONST_4));change.add(new VarInsnNode(Opcodes.ISTORE,1));reference.methods.getFirst().instructions.insert(change);
		assertTrue(plan(mixin(),target(),parsed(reference)).isEmpty());
	}
	@Test void aCallStillPresentInTheLiveSelectedMethodAndAnUnprovedBodyAreNotMoved(){
		ClassNode target=target();target.methods.removeIf(m->m.name.equals("dispatch"));target.methods.add(reference().methods.getFirst());assertTrue(plan(mixin(),target,reference()).isEmpty());
		target=target();MethodNode thunk=target.methods.stream().filter(m->LambdaInvocationThunkInjector.isThunk(OWNER,m)).findFirst().orElseThrow();thunk.instructions.insert(new MethodInsnNode(Opcodes.INVOKESTATIC,"example/SideEffect","run","()V",false));assertTrue(plan(mixin(),target,reference()).isEmpty());
	}
	@Test void obsoleteLocalsAndCancellationOfTheOldEnclosingMethodAreNotTransported(){
		ClassNode mixin=mixin();MethodNode handler=mixin.methods.getFirst();handler.desc="(L"+OWNER+";I"+OPERATION+"Ljava/lang/String;)V";handler.invisibleParameterAnnotations=new List[4];handler.invisibleParameterAnnotations[3]=List.of(ann(MixinRetarget.LOCAL_SUGAR,"argsOnly",true));assertTrue(plan(mixin,target(),reference()).isEmpty());
		handler.visibleAnnotations=List.of(ann(MixinRetarget.INJECT,"method",new ArrayList<>(List.of("dispatch")),"at",ann("Lorg/spongepowered/asm/mixin/injection/At;","value","INVOKE","target",MEMBER),"cancellable",true));handler.desc="(Lorg/spongepowered/asm/mixin/injection/callback/CallbackInfo;)V";handler.invisibleParameterAnnotations=null;assertTrue(plan(mixin,target(),reference()).isEmpty());
	}
	@Test void theControlPropertyLeavesTheNativeSelectorIntact(){System.setProperty(MixinExecutionPathRetarget.PROPERTY,"off");assertTrue(plan(mixin(),target(),reference()).isEmpty());}
	@Test void aDirectCallOrSerializableRegistrationOfALookalikeThunkCannotAuthorizeAMove(){
		ClassNode target=target();MethodNode thunk=target.methods.stream().filter(m->LambdaInvocationThunkInjector.isThunk(OWNER,m)).findFirst().orElseThrow();target.methods.removeIf(m->m.name.equals("factory"));
		MethodNode direct=new MethodNode(Opcodes.ACC_PUBLIC,"direct","(I)V",null,null);direct.instructions.add(new VarInsnNode(Opcodes.ALOAD,0));direct.instructions.add(new VarInsnNode(Opcodes.ILOAD,1));direct.instructions.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL,OWNER,thunk.name,thunk.desc,false));direct.instructions.add(new InsnNode(Opcodes.RETURN));target.methods.add(direct);assertTrue(plan(mixin(),parsed(target),reference()).isEmpty());
		target=target();for(MethodNode m:target.methods)for(AbstractInsnNode i:m.instructions)if(i instanceof InvokeDynamicInsnNode dynamic)dynamic.bsm=new Handle(Opcodes.H_INVOKESTATIC,"java/lang/invoke/LambdaMetafactory","altMetafactory",dynamic.bsm.getDesc(),false);assertTrue(plan(mixin(),target,reference()).isEmpty());
	}
	@Test void oneThunkCanCarryAUniquePrivateHelperWithConservedInputs(){
		ClassNode target=helperTarget(false,false);var plan=plan(mixin(),target,reference());assertEquals("piece(I)V",plan.rewrites().getFirst().to());assertTrue(plan.rewrites().getFirst().why().contains("retain their SSA producers"));
		assertTrue(plan(mixin(),helperTarget(true,false),reference()).isEmpty(),"a constant argument is not the native parameter's producer");
		assertTrue(plan(mixin(),helperTarget(false,true),reference()).isEmpty(),"a same-typed field is not the native receiver");
	}
	@Test void actualUpstreamHudOperationHandlersReachTheirUniqueThunks()throws Exception{
		ClassNode mixin=actualMixin("fabric-rendering-v1","net/fabricmc/fabric/mixin/client/rendering/HudMixin");
		ClassNode current=StagedFabricMixinFixture.game("net/minecraft/client/gui/Hud",false),nativeClass=StagedFabricMixinFixture.game("net/minecraft/client/gui/Hud",true);
		ClassNode target=MixinFit.parse(new LambdaInvocationThunkInjector().transform(current.name,StagedFabricMixinFixture.bytes(current),null));
		MixinStubRebind.noteEcosystem(mixin.name,Ecosystem.FABRIC);var plan=plan(mixin,target,nativeClass);
		for(String handler:List.of("wrapMiscOverlays","wrapCrosshair","wrapMobEffectOverlay","wrapBossHealthOverlay","wrapSleepOverlay","wrapDemoTimer","wrapScoreboardSidebar","wrapOverlayMessage","wrapTitleAndSubtitle","wrapChat","wrapPlayerList"))assertTrue(plan.rewrites().stream().anyMatch(r->r.handler().startsWith(handler+"(")&&r.why().contains("live method-reference thunk")),handler+"\n"+plan.describe());
		for(String handler:List.of("wrapHeldItemTooltip","wrapExtractSpectatorGui"))assertTrue(plan.rewrites().stream().anyMatch(r->r.handler().startsWith(handler+"(")&&r.why().contains("retain their SSA producers")),handler+"\n"+plan.describe());
	}
	private static ClassNode actualMixin(String module,String owner)throws Exception{
		String configured=System.getProperty("forbric.fabricApi","");if(configured.isBlank())return StagedFabricMixinFixture.mixin(module,owner);
		Path api=Path.of(configured);net.forbric.kernel.TestFixtures.require(net.forbric.kernel.TestFixtures.Fixture.STAGED,Files.isRegularFile(api),"configured Fabric API fixture required: "+api);
		try(ZipFile zip=new ZipFile(api.toFile())){var moduleEntry=zip.stream().filter(e->e.getName().startsWith("META-INF/jars/"+module+"-")).findFirst().orElseThrow();
			try(ZipInputStream inner=new ZipInputStream(zip.getInputStream(moduleEntry))){for(ZipEntry entry;(entry=inner.getNextEntry())!=null;)if(entry.getName().equals(owner+".class"))return MixinFit.parse(inner.readAllBytes());}}
		throw new AssertionError("configured upstream mixin absent: "+owner);
	}
	private static MixinRetarget.Plan plan(ClassNode mixin,ClassNode target,ClassNode reference){MixinStubRebind.noteEcosystem(mixin.name,Optional.ofNullable(MixinStubRebind.ecosystemOf(mixin.name)).orElse(Ecosystem.FABRIC));byte[] bytes=StagedFabricMixinFixture.bytes(target);return MixinRetarget.plan(mixin,p->p.equals(target.name+".class")?bytes:null,(e,n)->reference);}
	private static ClassNode target(){
		ClassNode target=base();MethodNode dispatch=new MethodNode(Opcodes.ACC_PUBLIC,"dispatch","(I)V",null,null);dispatch.instructions.add(new InsnNode(Opcodes.RETURN));target.methods.add(dispatch);
		MethodNode consume=new MethodNode(Opcodes.ACC_PUBLIC,"consume","(I)V",null,null);consume.instructions.add(new InsnNode(Opcodes.RETURN));target.methods.add(consume);
		MethodNode factory=new MethodNode(Opcodes.ACC_PUBLIC,"factory","()Ljava/util/function/IntConsumer;",null,null);factory.instructions.add(new VarInsnNode(Opcodes.ALOAD,0));factory.instructions.add(new InvokeDynamicInsnNode("accept","(L"+OWNER+";)Ljava/util/function/IntConsumer;",new Handle(Opcodes.H_INVOKESTATIC,"java/lang/invoke/LambdaMetafactory","metafactory","(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;Ljava/lang/invoke/MethodType;Ljava/lang/invoke/MethodHandle;Ljava/lang/invoke/MethodType;)Ljava/lang/invoke/CallSite;",false),Type.getMethodType("(I)V"),new Handle(Opcodes.H_INVOKEVIRTUAL,OWNER,"consume","(I)V",false),Type.getMethodType("(I)V")));factory.instructions.add(new InsnNode(Opcodes.ARETURN));target.methods.add(factory);
		return MixinFit.parse(new LambdaInvocationThunkInjector().transform(OWNER,StagedFabricMixinFixture.bytes(parsed(target)),null));
	}
	private static ClassNode reference(){ClassNode reference=base();MethodNode dispatch=new MethodNode(Opcodes.ACC_PUBLIC,"dispatch","(I)V",null,null);dispatch.instructions.add(new VarInsnNode(Opcodes.ALOAD,0));dispatch.instructions.add(new VarInsnNode(Opcodes.ILOAD,1));dispatch.instructions.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL,OWNER,"consume","(I)V",false));dispatch.instructions.add(new InsnNode(Opcodes.RETURN));reference.methods.add(dispatch);return parsed(reference);}
	private static ClassNode helperTarget(boolean constant,boolean otherReceiver){
		ClassNode target=target();for(MethodNode m:target.methods)for(AbstractInsnNode i:m.instructions)if(i instanceof InvokeDynamicInsnNode dynamic)dynamic.bsmArgs[1]=new Handle(Opcodes.H_INVOKEVIRTUAL,OWNER,"piece","(I)V",false);
		MethodNode helper=new MethodNode(Opcodes.ACC_PRIVATE,"piece","(I)V",null,null);LabelNode exit=new LabelNode();helper.instructions.add(new VarInsnNode(Opcodes.ILOAD,1));helper.instructions.add(new JumpInsnNode(Opcodes.IFLT,exit));helper.instructions.add(new VarInsnNode(Opcodes.ALOAD,0));
		if(otherReceiver){target.fields.add(new FieldNode(Opcodes.ACC_PUBLIC,"other","L"+OWNER+";",null,null));helper.instructions.add(new FieldInsnNode(Opcodes.GETFIELD,OWNER,"other","L"+OWNER+";"));}
		helper.instructions.add(constant?new InsnNode(Opcodes.ICONST_0):new VarInsnNode(Opcodes.ILOAD,1));helper.instructions.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL,OWNER,"consume","(I)V",false));helper.instructions.add(exit);helper.instructions.add(new InsnNode(Opcodes.RETURN));target.methods.add(helper);
		return MixinFit.parse(new LambdaInvocationThunkInjector().transform(OWNER,StagedFabricMixinFixture.bytes(parsed(target)),null));
	}
	private static ClassNode mixin(){ClassNode mixin=base();mixin.name="example/Guest";mixin.visibleAnnotations=List.of(ann("Lorg/spongepowered/asm/mixin/Mixin;","value",List.of(Type.getObjectType(OWNER))));MethodNode handler=new MethodNode(Opcodes.ACC_PRIVATE,"intercept","(L"+OWNER+";I"+OPERATION+")V",null,null);handler.instructions.add(new InsnNode(Opcodes.RETURN));handler.visibleAnnotations=List.of(ann("Lcom/llamalad7/mixinextras/injector/wrapoperation/WrapOperation;","method",new ArrayList<>(List.of("dispatch")),"at",ann("Lorg/spongepowered/asm/mixin/injection/At;","value","INVOKE","target",MEMBER)));mixin.methods.add(handler);MixinStubRebind.noteEcosystem(mixin.name,Ecosystem.FABRIC);return mixin;}
	private static ClassNode base(){ClassNode node=new ClassNode();node.name=OWNER;node.version=Opcodes.V21;node.access=Opcodes.ACC_PUBLIC;node.superName="java/lang/Object";return node;}
	private static ClassNode parsed(ClassNode node){ClassWriter writer=new ClassWriter(ClassWriter.COMPUTE_MAXS);node.accept(writer);return MixinFit.parse(writer.toByteArray());}
	private static AnnotationNode ann(String descriptor,Object...values){AnnotationNode a=new AnnotationNode(descriptor);a.values=new ArrayList<>(Arrays.asList(values));return a;}
}
