/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.mixin;
import static org.junit.jupiter.api.Assertions.*;
import java.util.*;import org.junit.jupiter.api.Test;
import org.objectweb.asm.*;import org.objectweb.asm.tree.*;

class CreateInjectionAdaptersTest {
	@Test void releasedCallbacksKeepTheirBodiesAndBindToTheReviewedLiveOperations()throws Exception {
		Map<String,Integer> expected=Map.of("client/mixin/ClientPacketListenerMixin",1,"mixin/LevelChunkMixin",1,"client/mixin/EntityFluidInteractionMixin",2,"client/mixin/ModelManagerMixin",1,"mixin/PersistentEntitySectionManagerCallbackMixin",1,"client/mixin/GuiRendererMixin",1,"mixin/ItemStackMixin",2);
		for(var entry:expected.entrySet()){
			var node=CreateGuestMixinFixture.mixin("com/zurrtum/create/"+entry.getKey());
			Map<String,String> bodies=new HashMap<>();for(var method:node.methods)bodies.put(method.name+method.desc,MixinInstructionFingerprint.hash(method));
			java.util.function.Function<String,org.objectweb.asm.tree.ClassNode> resolver=name->{var target=CarpetMixinAdapterTest.target(name);if(name.equals("net/minecraft/client/gui/render/GuiRenderer")){byte[] bytes=new net.forbric.kernel.transform.ForbricMergedBaseCompatTransformer().transform(name.replace('/','.'),CarpetMixinAdapterTest.bytes(target),null);target=MixinFit.parse(bytes);}return target;};
			assertEquals(entry.getValue(),MixinCarrierCallbackAdapters.adapt(node,resolver,(family,name)->nativeTarget(name)),entry.getKey());
			for(var method:node.methods)if(method.name.endsWith("$forbricOriginal"))assertEquals(bodies.get(method.name.replace("$forbricOriginal","")+method.desc),MixinInstructionFingerprint.hash(method));
			CarpetMixinAdapterTest.verify(node);assertEquals(0,MixinCarrierCallbackAdapters.adapt(node,resolver,(family,name)->nativeTarget(name)),entry.getKey()+" idempotence");
		}
	}
    static org.objectweb.asm.tree.ClassNode nativeTarget(String name){try{return CarpetMixinAdapterTest.from(net.forbric.kernel.TestFixtures.Fixture.MC_LIBRARIES,net.forbric.kernel.TestFixtures.vanillaJar(),name);}catch(Exception unavailable){return null;}}

	@Test void contextualFrictionAndResistanceKeepTheOriginalControlBlockDispatch()throws Exception {
		for(String name:List.of("LivingEntityMixin","ItemEntityMixin","ExperienceOrbMixin","AbstractBoatMixin","LeashableMixin","ExplosionDamageCalculatorMixin")){
			var node=CreateGuestMixinFixture.mixin("com/zurrtum/create/mixin/"+name);
			assertEquals(name.equals("LivingEntityMixin")?2:1,MixinBlockQueryAdapters.adapt(node,CarpetMixinAdapterTest::target),name);
			CarpetMixinAdapterTest.verify(node);assertEquals(0,MixinBlockQueryAdapters.adapt(node,CarpetMixinAdapterTest::target));
		}
	}
	@Test void interactionsKeepNativeTransactionsAndTheOriginalControlHandlers()throws Exception{
		for(var entry:Map.of("mixin/SignalGetterMixin",1,"mixin/EntityMixin",1,"client/mixin/MultiPlayerGameModeMixin",2,"mixin/ServerPlayerGameModeMixin",1).entrySet()){
			var node=CreateGuestMixinFixture.mixin("com/zurrtum/create/"+entry.getKey());assertEquals(entry.getValue(),MixinBlockInteractionAdapters.adapt(node,CarpetMixinAdapterTest::target),entry.getKey());CarpetMixinAdapterTest.verify(node);assertEquals(0,MixinBlockInteractionAdapters.adapt(node,CarpetMixinAdapterTest::target));
		}
	}
    @Test void aRenamedPredictionHostUsesTheActuallyReferencedImplementationAndKeepsItsBody()throws Exception {
        var node=CreateGuestMixinFixture.mixin("com/zurrtum/create/client/mixin/MultiPlayerGameModeMixin");
        MethodNode handler=predictionHandler(node);AnnotationNode injector=MixinFit.injectorOf(handler);
        String authored=MixinFit.stringList(MixinFit.value(injector,"method")).getFirst();String oldName=authored.substring(0,authored.indexOf('('));
        String body=MixinInstructionFingerprint.hash(handler),unknown="arbitraryPredictionBody";
        ClassNode target=CarpetMixinAdapterTest.target("net/minecraft/client/multiplayer/MultiPlayerGameMode");
        for(MethodNode method:target.methods) {
            if(method.name.equals(oldName))method.name=unknown;
            for(var instruction:method.instructions) {
                if(instruction instanceof MethodInsnNode call&&call.owner.equals(target.name)&&call.name.equals(oldName))call.name=unknown;
                if(instruction instanceof InvokeDynamicInsnNode dynamic)for(int i=0;i<dynamic.bsmArgs.length;i++)if(dynamic.bsmArgs[i] instanceof Handle handle&&handle.getOwner().equals(target.name)&&handle.getName().equals(oldName))
                    dynamic.bsmArgs[i]=new Handle(handle.getTag(),handle.getOwner(),unknown,handle.getDesc(),handle.isInterface());
            }
        }
        MixinPlayerWorldCallbackAdapter.set(injector,"method",List.of(unknown+authored.substring(authored.indexOf('('))));
        assertEquals(2,MixinBlockInteractionAdapters.adapt(node,name->name.equals(target.name)?target:CarpetMixinAdapterTest.target(name)));
        assertEquals(body,MixinInstructionFingerprint.hash(handler));
        assertTrue(MixinFit.stringList(MixinFit.value(injector,"method")).getFirst().startsWith(unknown+"("));
        assertEquals(0,MixinBlockInteractionAdapters.adapt(node,name->target));CarpetMixinAdapterTest.verify(node);
    }
    @Test void twoReferencedPredictionImplementationsRemainAmbiguous()throws Exception {
        var node=CreateGuestMixinFixture.mixin("com/zurrtum/create/client/mixin/MultiPlayerGameModeMixin");MethodNode handler=predictionHandler(node);
        String before=MixinFit.stringList(MixinFit.value(MixinFit.injectorOf(handler),"method")).getFirst(),name=before.substring(0,before.indexOf('('));
        ClassNode target=CarpetMixinAdapterTest.target("net/minecraft/client/multiplayer/MultiPlayerGameMode");
        List<MethodNode> alternatives=target.methods.stream().filter(method->method.name.equals(name)).toList();assertEquals(2,alternatives.size(),"the real merged base retains both native implementations");
        MethodNode root=new MethodNode(Opcodes.ACC_PUBLIC,"arbitraryHandleConsumer","()V",null,null);
        for(MethodNode method:alternatives){root.instructions.add(new LdcInsnNode(new Handle(Opcodes.H_INVOKEVIRTUAL,target.name,method.name,method.desc,false)));root.instructions.add(new InsnNode(Opcodes.POP));}
        root.instructions.add(new InsnNode(Opcodes.RETURN));root.maxStack=1;root.maxLocals=1;target.methods.add(root);
        assertEquals(1,MixinBlockInteractionAdapters.adapt(node,nameOf->nameOf.equals(target.name)?target:CarpetMixinAdapterTest.target(nameOf)),"the independent block-break adapter still works");
        assertEquals(before,MixinFit.stringList(MixinFit.value(MixinFit.injectorOf(handler),"method")).getFirst(),"neither ambiguous implementation is chosen");CarpetMixinAdapterTest.verify(node);
    }
    private static MethodNode predictionHandler(ClassNode node) {
        return node.methods.stream().filter(method->MixinCallbackShape.kind(method,"WrapOperation")&&MixinCallbackShape.plainPoint(method,"INVOKE","Lnet/minecraft/client/multiplayer/MultiPlayerGameMode;destroyBlock(Lnet/minecraft/core/BlockPos;)Z")
                &&MixinFit.stringList(MixinFit.value(MixinFit.injectorOf(method),"method")).stream().anyMatch(selector->selector.contains("(Lnet/minecraft/world/level/block/state/BlockState;"))).findFirst().orElseThrow();
    }
	@Test void nativeBreathingSoundAndHudScopesPreserveTheOriginalCallbacks()throws Exception{
		var living=CreateGuestMixinFixture.mixin("com/zurrtum/create/mixin/LivingEntityMixin");assertEquals(2,MixinBreathingCallbackAdapter.adapt(living,CarpetMixinAdapterTest::target));assertEquals(1,MixinEntitySoundCallbackAdapter.adapt(living,CarpetMixinAdapterTest::target));CarpetMixinAdapterTest.verify(living);
		var entity=CreateGuestMixinFixture.mixin("com/zurrtum/create/mixin/EntityMixin");assertEquals(1,MixinEntitySoundCallbackAdapter.adapt(entity,CarpetMixinAdapterTest::target));CarpetMixinAdapterTest.verify(entity);
		var hud=CreateGuestMixinFixture.mixin("com/zurrtum/create/client/mixin/HudMixin");assertEquals(1,MixinHudContextAdapter.adapt(hud,CarpetMixinAdapterTest::target));CarpetMixinAdapterTest.verify(hud);
		assertEquals(0,MixinBreathingCallbackAdapter.adapt(living,CarpetMixinAdapterTest::target));assertEquals(0,MixinEntitySoundCallbackAdapter.adapt(entity,CarpetMixinAdapterTest::target));assertEquals(0,MixinHudContextAdapter.adapt(hud,CarpetMixinAdapterTest::target));
	}
}
