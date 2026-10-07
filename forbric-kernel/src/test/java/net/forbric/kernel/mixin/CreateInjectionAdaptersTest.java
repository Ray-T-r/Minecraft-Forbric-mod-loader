/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.mixin;
import static org.junit.jupiter.api.Assertions.*;
import java.util.*;import org.junit.jupiter.api.Test;

class CreateInjectionAdaptersTest {
	@Test void releasedCallbacksKeepTheirBodiesAndBindToTheReviewedLiveOperations()throws Exception {
		Map<String,Integer> expected=Map.of("client/mixin/ClientPacketListenerMixin",1,"mixin/LevelChunkMixin",1,"client/mixin/EntityFluidInteractionMixin",2,"client/mixin/ModelManagerMixin",1,"mixin/PersistentEntitySectionManagerCallbackMixin",1,"client/mixin/GuiRendererMixin",1,"mixin/ItemStackMixin",2);
		for(var entry:expected.entrySet()){
			var node=CreateGuestMixinFixture.mixin("com/zurrtum/create/"+entry.getKey());
			Map<String,String> bodies=new HashMap<>();for(var method:node.methods)bodies.put(method.name+method.desc,MixinInstructionFingerprint.hash(method));
			java.util.function.Function<String,org.objectweb.asm.tree.ClassNode> resolver=name->{var target=CarpetMixinAdapterTest.target(name);if(name.equals("net/minecraft/client/gui/render/GuiRenderer")){byte[] bytes=new net.forbric.kernel.transform.ForbricMergedBaseCompatTransformer().transform(name.replace('/','.'),CarpetMixinAdapterTest.bytes(target),null);target=MixinFit.parse(bytes);}return target;};
			assertEquals(entry.getValue(),MixinCarrierCallbackAdapters.adapt(node,resolver),entry.getKey());
			for(var method:node.methods)if(method.name.endsWith("$forbricOriginal"))assertEquals(bodies.get(method.name.replace("$forbricOriginal","")+method.desc),MixinInstructionFingerprint.hash(method));
			CarpetMixinAdapterTest.verify(node);assertEquals(0,MixinCarrierCallbackAdapters.adapt(node,resolver),entry.getKey()+" idempotence");
		}
	}
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
	@Test void nativeBreathingSoundAndHudScopesPreserveTheOriginalCallbacks()throws Exception{
		var living=CreateGuestMixinFixture.mixin("com/zurrtum/create/mixin/LivingEntityMixin");assertEquals(2,MixinBreathingCallbackAdapter.adapt(living,CarpetMixinAdapterTest::target));assertEquals(1,MixinEntitySoundCallbackAdapter.adapt(living,CarpetMixinAdapterTest::target));CarpetMixinAdapterTest.verify(living);
		var entity=CreateGuestMixinFixture.mixin("com/zurrtum/create/mixin/EntityMixin");assertEquals(1,MixinEntitySoundCallbackAdapter.adapt(entity,CarpetMixinAdapterTest::target));CarpetMixinAdapterTest.verify(entity);
		var hud=CreateGuestMixinFixture.mixin("com/zurrtum/create/client/mixin/HudMixin");assertEquals(1,MixinHudContextAdapter.adapt(hud,CarpetMixinAdapterTest::target));CarpetMixinAdapterTest.verify(hud);
		assertEquals(0,MixinBreathingCallbackAdapter.adapt(living,CarpetMixinAdapterTest::target));assertEquals(0,MixinEntitySoundCallbackAdapter.adapt(entity,CarpetMixinAdapterTest::target));assertEquals(0,MixinHudContextAdapter.adapt(hud,CarpetMixinAdapterTest::target));
	}
}
