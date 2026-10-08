/*
 * Copyright 2026 The Forbric Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package net.forbric.kernel.boot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import net.forbric.api.Ecosystem;
import net.forbric.api.ModCatalog;
import net.forbric.api.Side;
import net.forbric.kernel.transform.CreativePagerBridgeInjector;
import net.forbric.kernel.transform.GuestInjectorPruner;
import net.forbric.kernel.transform.LootTableEventBridgeInjector;
import net.forbric.kernel.transform.ModelFormatFunnelInjector;

/** The attribution rows: recorded by needle, judged by side and by kill switch, marked by catalog identity. */
@org.junit.jupiter.api.parallel.ResourceLock("ModCatalog")
class FabricApiModuleLossAuditTest {
	private static final String LOOT = "net/fabricmc/fabric/api/loot/v3/LootTableEvents";
	private static final String MODEL_PLUGIN = "net/fabricmc/fabric/api/client/model/loading/v1/ModelLoadingPlugin";
	private static final String MODEL_FORMAT = "net/fabricmc/fabric/api/client/model/loading/v1/UnbakedModelDeserializer";
	private static final String CREATIVE = "net/fabricmc/fabric/api/client/creativetab/v1/FabricCreativeModeInventoryScreen";
	private static final String DYNAMIC_REGISTRIES = "net/fabricmc/fabric/api/event/registry/DynamicRegistries";
	private static final String SETUP_CALLBACK = "net/fabricmc/fabric/api/event/registry/DynamicRegistrySetupCallback";

	private List<ModCatalog.Entry> previous;
    private static final String SOURCE_PIN="source-structure.mixins.json:PagerProtocol";
    /** Discovery uses executable source state ownership; naming the API without its provider supplies no evidence. */
    private static void discoverPagerProvider() {
        ClassWriter writer=new ClassWriter(0);String owner="fixture/PagerProtocol";
        writer.visit(Opcodes.V21,Opcodes.ACC_PUBLIC,owner,null,"java/lang/Object",new String[]{CREATIVE});
        var mixin=writer.visitAnnotation("Lorg/spongepowered/asm/mixin/Mixin;",true);var targets=mixin.visitArray("targets");targets.visit(null,"net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen");targets.visitEnd();mixin.visitEnd();
        writer.visitField(Opcodes.ACC_PRIVATE|Opcodes.ACC_STATIC,"ownPage","I",null,null).visitEnd();
        var getter=writer.visitMethod(Opcodes.ACC_PUBLIC,"getCurrentPage","()I",null,null);getter.visitCode();getter.visitFieldInsn(Opcodes.GETSTATIC,owner,"ownPage","I");getter.visitInsn(Opcodes.IRETURN);getter.visitMaxs(1,1);getter.visitEnd();
        var setter=writer.visitMethod(Opcodes.ACC_PUBLIC,"switchToPage","(I)Z",null,null);setter.visitCode();setter.visitVarInsn(Opcodes.ILOAD,1);setter.visitFieldInsn(Opcodes.PUTSTATIC,owner,"ownPage","I");setter.visitInsn(Opcodes.ICONST_1);setter.visitInsn(Opcodes.IRETURN);setter.visitMaxs(1,2);setter.visitEnd();writer.visitEnd();byte[] source=writer.toByteArray();
        byte[] config="{\"package\":\"fixture\",\"client\":[\"PagerProtocol\"]}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        net.forbric.kernel.mixin.KernelGuestMixinAdapter.unfitMixins("source-structure.mixins.json",config,path->path.equals(owner+".class")?source:null,net.fabricmc.api.EnvType.CLIENT);
    }

    private static void discoverRegistryProvider() {
        String owner="fixture/RegistryContext";
        org.objectweb.asm.tree.ClassNode source=new org.objectweb.asm.tree.ClassNode();source.version=Opcodes.V21;source.access=Opcodes.ACC_PUBLIC;source.name=owner;source.superName="java/lang/Object";
        var mixin=new org.objectweb.asm.tree.AnnotationNode("Lorg/spongepowered/asm/mixin/Mixin;");mixin.values=new java.util.ArrayList<>(List.of("targets",List.of("net.minecraft.resources.RegistryDataLoader")));source.visibleAnnotations=new java.util.ArrayList<>(List.of(mixin));
        source.fields.add(new org.objectweb.asm.tree.FieldNode(Opcodes.ACC_PRIVATE|Opcodes.ACC_STATIC|Opcodes.ACC_FINAL,"context","Ljava/lang/ScopedValue;",null,null));
        var init=new org.objectweb.asm.tree.MethodNode(Opcodes.ACC_STATIC,"<clinit>","()V",null,null);init.instructions.add(new org.objectweb.asm.tree.MethodInsnNode(Opcodes.INVOKESTATIC,"java/lang/ScopedValue","newInstance","()Ljava/lang/ScopedValue;",false));init.instructions.add(new org.objectweb.asm.tree.FieldInsnNode(Opcodes.PUTSTATIC,owner,"context","Ljava/lang/ScopedValue;"));init.instructions.add(new org.objectweb.asm.tree.InsnNode(Opcodes.RETURN));init.maxStack=1;source.methods.add(init);
        var handler=new org.objectweb.asm.tree.MethodNode(Opcodes.ACC_PRIVATE|Opcodes.ACC_STATIC,"bindContext","(Ljava/lang/Object;Ljava/util/List;Ljava/util/List;Ljava/util/concurrent/Executor;Lcom/llamalad7/mixinextras/injector/wrapoperation/Operation;)Ljava/util/concurrent/CompletableFuture;",null,null);
        var at=new org.objectweb.asm.tree.AnnotationNode("Lorg/spongepowered/asm/mixin/injection/At;");at.values=new java.util.ArrayList<>(List.of("value","INVOKE","target","Lnet/minecraft/resources/RegistryDataLoader;load(Lnet/minecraft/resources/RegistryDataLoader$LoaderFactory;Ljava/util/List;Ljava/util/List;Ljava/util/concurrent/Executor;)Ljava/util/concurrent/CompletableFuture;"));
        var inject=new org.objectweb.asm.tree.AnnotationNode("Lcom/llamalad7/mixinextras/injector/wrapoperation/WrapOperation;");inject.values=new java.util.ArrayList<>(List.of("method",List.of("load"),"at",List.of(at)));handler.visibleAnnotations=new java.util.ArrayList<>(List.of(inject));handler.instructions.add(new org.objectweb.asm.tree.FieldInsnNode(Opcodes.GETSTATIC,owner,"context","Ljava/lang/ScopedValue;"));handler.instructions.add(new org.objectweb.asm.tree.InsnNode(Opcodes.ICONST_1));handler.instructions.add(new org.objectweb.asm.tree.MethodInsnNode(Opcodes.INVOKESTATIC,"java/lang/Boolean","valueOf","(Z)Ljava/lang/Boolean;",false));handler.instructions.add(new org.objectweb.asm.tree.MethodInsnNode(Opcodes.INVOKESTATIC,"java/lang/ScopedValue","where","(Ljava/lang/ScopedValue;Ljava/lang/Object;)Ljava/lang/ScopedValue$Carrier;",false));
        for(int slot:new int[]{4,0,1,2,3})handler.instructions.add(new org.objectweb.asm.tree.VarInsnNode(Opcodes.ALOAD,slot));
        String delegate="(Lcom/llamalad7/mixinextras/injector/wrapoperation/Operation;Ljava/lang/Object;Ljava/util/List;Ljava/util/List;Ljava/util/concurrent/Executor;)Ljava/lang/Object;";
        handler.instructions.add(new org.objectweb.asm.tree.InvokeDynamicInsnNode("call",delegate.substring(0,delegate.indexOf(')')+1)+"Ljava/lang/ScopedValue$CallableOp;",new org.objectweb.asm.Handle(Opcodes.H_INVOKESTATIC,"java/lang/invoke/LambdaMetafactory","metafactory","(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;Ljava/lang/invoke/MethodType;Ljava/lang/invoke/MethodHandle;Ljava/lang/invoke/MethodType;)Ljava/lang/invoke/CallSite;",false),org.objectweb.asm.Type.getMethodType("()Ljava/lang/Object;"),new org.objectweb.asm.Handle(Opcodes.H_INVOKESTATIC,owner,"invokeOriginal",delegate,false),org.objectweb.asm.Type.getMethodType("()Ljava/lang/Object;")));
        handler.instructions.add(new org.objectweb.asm.tree.MethodInsnNode(Opcodes.INVOKEVIRTUAL,"java/lang/ScopedValue$Carrier","call","(Ljava/lang/ScopedValue$CallableOp;)Ljava/lang/Object;",false));handler.instructions.add(new org.objectweb.asm.tree.TypeInsnNode(Opcodes.CHECKCAST,"java/util/concurrent/CompletableFuture"));handler.instructions.add(new org.objectweb.asm.tree.InsnNode(Opcodes.ARETURN));handler.maxStack=6;handler.maxLocals=5;source.methods.add(handler);
        var invoke=new org.objectweb.asm.tree.MethodNode(Opcodes.ACC_PRIVATE|Opcodes.ACC_STATIC,"invokeOriginal",delegate,null,null);invoke.instructions.add(new org.objectweb.asm.tree.VarInsnNode(Opcodes.ALOAD,0));invoke.instructions.add(new org.objectweb.asm.tree.InsnNode(Opcodes.ICONST_4));invoke.instructions.add(new org.objectweb.asm.tree.TypeInsnNode(Opcodes.ANEWARRAY,"java/lang/Object"));for(int slot=1;slot<=4;slot++){invoke.instructions.add(new org.objectweb.asm.tree.InsnNode(Opcodes.DUP));invoke.instructions.add(new org.objectweb.asm.tree.InsnNode(Opcodes.ICONST_0+slot-1));invoke.instructions.add(new org.objectweb.asm.tree.VarInsnNode(Opcodes.ALOAD,slot));invoke.instructions.add(new org.objectweb.asm.tree.InsnNode(Opcodes.AASTORE));}invoke.instructions.add(new org.objectweb.asm.tree.MethodInsnNode(Opcodes.INVOKEINTERFACE,"com/llamalad7/mixinextras/injector/wrapoperation/Operation","call","([Ljava/lang/Object;)Ljava/lang/Object;",true));invoke.instructions.add(new org.objectweb.asm.tree.InsnNode(Opcodes.ARETURN));invoke.maxStack=6;invoke.maxLocals=5;source.methods.add(invoke);
        ClassWriter writer=new ClassWriter(0);source.accept(writer);byte[] bytes=writer.toByteArray();
        byte[] config="{\"package\":\"fixture\",\"mixins\":[\"RegistryContext\"]}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        net.forbric.kernel.mixin.KernelGuestMixinAdapter.unfitMixins("source-context.mixins.json",config,path->path.equals(owner+".class")?bytes:null,net.fabricmc.api.EnvType.SERVER);
    }

	@AfterEach
	void forget() {
		FabricApiModuleLossAudit.reset();
		System.clearProperty(LootTableEventBridgeInjector.PROPERTY);
		System.clearProperty(GuestInjectorPruner.PROPERTY);
		System.clearProperty(CreativePagerBridgeInjector.PROPERTY);
		System.clearProperty(ModelFormatFunnelInjector.PROPERTY);
		System.clearProperty(FabricApiModuleLossAudit.SWITCH);
		if (previous != null) ModCatalog.publish(previous);
	}

	private void publish(ModCatalog.Entry... entries) {
		previous = ModCatalog.everything();
		ModCatalog.publish(List.of(entries));
	}

	private static ModCatalog.Entry entry(String modId, String jar, String bundledBy) {
		return new ModCatalog.Entry(Ecosystem.FABRIC, modId, modId, "1", "", List.of(), jar, "", bundledBy);
	}

	private static byte[] classNaming(String... types) {
		ClassWriter cw = new ClassWriter(0);
		cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "com/example/Listener", null, "java/lang/Object", null);
		MethodVisitor m = cw.visitMethod(Opcodes.ACC_PUBLIC, "init", "()V", null, null);
		m.visitCode();
		for (String type : types) {
			m.visitTypeInsn(Opcodes.NEW, type);
			m.visitInsn(Opcodes.POP);
		}
		m.visitInsn(Opcodes.RETURN);
		m.visitMaxs(2, 1);
		m.visitEnd();
		cw.visitEnd();
		return cw.toByteArray();
	}

	private static ModCatalog.Entry degraded(String modId) {
		return ModCatalog.failures().stream().filter(e -> e.modId().equals(modId)).findFirst().orElse(null);
	}

	@Test
	void aLootListenerIsRecordedAndIsNotALossWhileTheBridgeIsOn() {
		publish(entry("balm", "balm.jar", ""));
		FabricApiModuleLossAudit.note("balm.jar", classNaming(LOOT));
		assertEquals(Set.of("balm.jar"), FabricApiModuleLossAudit.users().get(FabricApiModuleLossAudit.LOSSES.get(0)));

		FabricApiModuleLossAudit.report(Side.DEDICATED_SERVER);
		assertTrue(ModCatalog.failures().isEmpty(), "the bridge fires the events, so nothing is lost: " + ModCatalog.failures());
	}

	@Test
	void withTheLootBridgeOffTheListenerIsDegradedNamingTheModule() {
		publish(entry("balm", "balm.jar", ""));
		System.setProperty(LootTableEventBridgeInjector.PROPERTY, "off");
		FabricApiModuleLossAudit.note("balm.jar", classNaming(LOOT));
		FabricApiModuleLossAudit.report(Side.DEDICATED_SERVER);
		ModCatalog.Entry balm = degraded("balm");
		assertTrue(balm != null && balm.status() == ModCatalog.Status.DEGRADED, String.valueOf(balm));
		assertTrue(balm.statusDetail().startsWith("fabric-loot-api-v3: "), balm.statusDetail());
	}

	@Test
	void aClientOnlySurfaceIsALossOnTheClientAndNotOnTheServer() {
		publish(entry("pager", "pager.jar", ""));
		System.setProperty(CreativePagerBridgeInjector.PROPERTY, "off");
        discoverPagerProvider();
		FabricApiModuleLossAudit.note("pager.jar", classNaming(CREATIVE));
		FabricApiModuleLossAudit.report(Side.DEDICATED_SERVER);
		assertTrue(ModCatalog.failures().isEmpty(), "a dedicated server never opens the creative screen");
		FabricApiModuleLossAudit.report(Side.CLIENT);
		ModCatalog.Entry pager = degraded("pager");
		assertTrue(pager != null && pager.statusDetail().startsWith("fabric-creative-tab-api-v1: "), String.valueOf(pager));
	}

	/** A class IMPLEMENTING the creative-tab interface: owo-lib's mixin, which the fallback leaves out. */
	private static byte[] classImplementing(String itf) {
		ClassWriter cw = new ClassWriter(0);
		cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT, "com/example/PagerMixin", null, "java/lang/Object",
				new String[] {itf});
		cw.visitEnd();
		return cw.toByteArray();
	}

	@Test
	void theCreativePagerRowFollowsTheBridgeAndNamesTheAssertionErrorNotACast() {
		publish(entry("pager", "pager.jar", ""));
		FabricApiModuleLossAudit.note("pager.jar", classNaming(CREATIVE));
		FabricApiModuleLossAudit.report(Side.CLIENT);
		assertTrue(ModCatalog.failures().isEmpty(), "the bridge backs the interface from NeoForge's pager: " + ModCatalog.failures());

		System.setProperty(CreativePagerBridgeInjector.PROPERTY, "off");
        discoverPagerProvider();
		FabricApiModuleLossAudit.report(Side.CLIENT);
		ModCatalog.Entry pager = degraded("pager");
		assertTrue(pager != null && pager.statusDetail().contains("AssertionError"), String.valueOf(pager));
		assertFalse(pager.statusDetail().contains("ClassCastException"),
				"fabric-api's class tweaker injects the interface, so a cast succeeds: " + pager.statusDetail());
	}

	@Test
	void aModImplementingTheCreativeInterfaceIsToldItsMixinIsLeftOut() {
		publish(entry("owo", "owo.jar", ""), entry("caller", "caller.jar", ""));
		System.setProperty(CreativePagerBridgeInjector.PROPERTY, "off");
        discoverPagerProvider();
		FabricApiModuleLossAudit.note("owo.jar", classImplementing(CREATIVE));
		FabricApiModuleLossAudit.note("caller.jar", classNaming(CREATIVE));
		FabricApiModuleLossAudit.report(Side.CLIENT);
		ModCatalog.Entry owo = degraded("owo");
		assertTrue(owo != null && owo.statusDetail().contains("mixin implementing FabricCreativeModeInventoryScreen is left out"),
				String.valueOf(owo));
		assertTrue(owo.statusDetail().contains("unless -Dforbric.pinnedContracts=off"), "that switch keeps it: " + owo.statusDetail());
		ModCatalog.Entry caller = degraded("caller");
		assertTrue(caller != null && caller.statusDetail().contains("every call throws AssertionError"), String.valueOf(caller));
	}

	/** With Fabric's own mixin unpinned it implements the interface itself: nothing is lost, bridge or not. */
	@Test
	@org.junit.jupiter.api.parallel.ResourceLock("system-properties")
	void theCreativePagerRowIsNoLossWhileFabricsOwnMixinApplies() {
		publish(entry("owo", "owo.jar", ""), entry("caller", "caller.jar", ""));
		System.setProperty(CreativePagerBridgeInjector.PROPERTY, "off");
        discoverPagerProvider();
		System.setProperty("forbric.keepMixins", SOURCE_PIN);
		try {
			FabricApiModuleLossAudit.note("owo.jar", classImplementing(CREATIVE));
			FabricApiModuleLossAudit.note("caller.jar", classNaming(CREATIVE));
			FabricApiModuleLossAudit.report(Side.CLIENT);
			assertTrue(ModCatalog.failures().isEmpty(), String.valueOf(ModCatalog.failures()));
		} finally {
			System.clearProperty("forbric.keepMixins");
		}
	}

	@Test
	void theModelPluginRowFollowsThePrunerSwitch() {
		publish(entry("balm", "balm.jar", ""));
		FabricApiModuleLossAudit.note("balm.jar", classNaming(MODEL_PLUGIN));
		FabricApiModuleLossAudit.report(Side.CLIENT);
		assertTrue(ModCatalog.failures().isEmpty(), "the pruner lets ModelLoadingPlugins dispatch");
		System.setProperty(GuestInjectorPruner.PROPERTY, "off");
		FabricApiModuleLossAudit.report(Side.CLIENT);
		ModCatalog.Entry balm = degraded("balm");
		assertTrue(balm != null && balm.statusDetail().contains("ModelLoadingPlugin"), String.valueOf(balm));
	}

	/**
	 * Traveler's Backpack registers a {@code fabric:type} deserializer. It was marked DEGRADED on every boot; while
	 * the funnel parses its models, it is not.
	 */
	@Test
	void theModelFormatRowFollowsTheFunnelSwitch() {
		publish(entry("travelersbackpack", "travelersbackpack.jar", ""));
		FabricApiModuleLossAudit.note("travelersbackpack.jar", classNaming(MODEL_FORMAT));
		FabricApiModuleLossAudit.report(Side.CLIENT);
		assertTrue(ModCatalog.failures().isEmpty(), "the funnel parses fabric:type models: " + ModCatalog.failures());
		System.setProperty(ModelFormatFunnelInjector.PROPERTY, "off");
		FabricApiModuleLossAudit.report(Side.CLIENT);
		ModCatalog.Entry backpack = degraded("travelersbackpack");
		assertTrue(backpack != null && backpack.statusDetail().contains("fabric:type"), String.valueOf(backpack));
	}

	@Test
	void theRegistryNeedleIsTheClassNotThePackage() {
		FabricApiModuleLossAudit.note("lithostitched.jar", classNaming(DYNAMIC_REGISTRIES));
		assertTrue(FabricApiModuleLossAudit.users().isEmpty(), "DynamicRegistries works — it must not be recorded");
		FabricApiModuleLossAudit.note("other.jar", classNaming(SETUP_CALLBACK));
		assertEquals(Set.of("other.jar"), FabricApiModuleLossAudit.users().get(FabricApiModuleLossAudit.LOSSES.get(4)));
	}

	@Test
	void fabricApisOwnModuleIsNeverAUserAndAThirdPartyBesideItIs() {
		publish(entry("balm", "balm.jar", ""),
				entry("fabric-loot-api-v3", "fabric-loot-api-v3-3.0.17.jar", "fabric-api"));
		System.setProperty(LootTableEventBridgeInjector.PROPERTY, "off");
		FabricApiModuleLossAudit.note("balm.jar", classNaming(LOOT));
		FabricApiModuleLossAudit.note("fabric-loot-api-v3-3.0.17.jar", classNaming(LOOT));
		FabricApiModuleLossAudit.report(Side.DEDICATED_SERVER);
		assertTrue(degraded("balm") != null, "the third party is named");
		assertTrue(degraded("fabric-loot-api-v3") == null, "the module that DEFINES the surface is not its user");
	}

	@Test
	void threeClassesFromOneJarNameItOnce() {
		FabricApiModuleLossAudit.note("balm.jar", classNaming(LOOT));
		FabricApiModuleLossAudit.note("balm.jar", classNaming(LOOT));
		FabricApiModuleLossAudit.note("balm.jar", classNaming(LOOT, SETUP_CALLBACK));
		assertEquals(Set.of("balm.jar"), FabricApiModuleLossAudit.users().get(FabricApiModuleLossAudit.LOSSES.get(0)));
		assertEquals(2, FabricApiModuleLossAudit.users().size(), "two surfaces, one jar each");
	}

	@Test void separatelyInstalledDefiningModuleIsNotItsOwnThirdPartyConsumer() {
		String before = System.setProperty(net.forbric.kernel.mixin.FabricRegistryLoaderMixinAdapter.PROPERTY, "off");
		try {
		publish(entry("fabric-registry-sync-v0", "module.jar", ""), entry("consumer", "consumer.jar", ""));
        discoverRegistryProvider();
		FabricApiModuleLossAudit.note("module.jar", classNaming(SETUP_CALLBACK));
		FabricApiModuleLossAudit.note("consumer.jar", classNaming(SETUP_CALLBACK));
		FabricApiModuleLossAudit.report(Side.DEDICATED_SERVER);
		assertTrue(degraded("fabric-registry-sync-v0") == null);
		assertTrue(degraded("consumer") != null);
		} finally {
			if (before == null) System.clearProperty(net.forbric.kernel.mixin.FabricRegistryLoaderMixinAdapter.PROPERTY);
			else System.setProperty(net.forbric.kernel.mixin.FabricRegistryLoaderMixinAdapter.PROPERTY, before);
		}
	}

	@Test
	void switchedOffItRecordsAndMarksNothing() {
		publish(entry("balm", "balm.jar", ""));
		System.setProperty(FabricApiModuleLossAudit.SWITCH, "off");
		System.setProperty(LootTableEventBridgeInjector.PROPERTY, "off");
		FabricApiModuleLossAudit.note("balm.jar", classNaming(LOOT));
		FabricApiModuleLossAudit.report(Side.DEDICATED_SERVER);
		assertFalse(FabricApiModuleLossAudit.enabled());
		assertTrue(ModCatalog.failures().isEmpty(), "report only, and only when asked");
	}
}
