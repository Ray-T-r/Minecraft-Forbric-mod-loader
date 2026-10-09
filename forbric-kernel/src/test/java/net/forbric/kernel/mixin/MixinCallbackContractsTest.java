/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.mixin;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Path;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Stream;
import net.forbric.kernel.TestFixtures.Fixture;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.commons.ClassRemapper;
import org.objectweb.asm.commons.SimpleRemapper;
import org.objectweb.asm.tree.*;

/** Real released callbacks, renamed without altering their source contracts, and adversarial source shapes. */
class MixinCallbackContractsTest {
    @FunctionalInterface interface Source { ClassNode read() throws Exception; }
    @FunctionalInterface interface Adapter { int apply(ClassNode mixin, Function<String, ClassNode> targets); }
    record Contract(String id, String kind, Source source, Adapter adapter, int count) { }
    private static Contract create(String entry, String kind, Adapter adapter, int count) {
        return new Contract(entry, kind, () -> CreateGuestMixinFixture.mixin("com/zurrtum/create/" + entry), adapter, count);
    }
    private static Contract jar(String id, String path, String owner, String kind, Adapter adapter, int count) {
        return new Contract(id, kind, () -> CarpetMixinAdapterTest.from(Fixture.THIRD_PARTY, Path.of(path), owner), adapter, count);
    }
    private static List<Contract> contracts() {
        List<Contract> contracts = new ArrayList<>();
        Map<String, Integer> carriers = Map.of("client/mixin/ClientPacketListenerMixin",1,"mixin/LevelChunkMixin",1,
                "client/mixin/EntityFluidInteractionMixin",2,"client/mixin/ModelManagerMixin",1,"client/mixin/LoadBlockModelMixin",1,
                "mixin/PersistentEntitySectionManagerCallbackMixin",1,"client/mixin/GuiRendererMixin",1,"mixin/ItemStackMixin",2);
        carriers.forEach((entry,count) -> contracts.add(create(entry, "MixinCarrierCallbackAdapters",(mixin,targets)->MixinCarrierCallbackAdapters.adapt(mixin,targets,(family,name)->CreateInjectionAdaptersTest.nativeTarget(name)), count)));
        for (String entry : List.of("LivingEntityMixin","ItemEntityMixin","ExperienceOrbMixin","AbstractBoatMixin","LeashableMixin","ExplosionDamageCalculatorMixin"))
            contracts.add(create("mixin/"+entry, "MixinBlockQueryAdapters",MixinBlockQueryAdapters::adapt, entry.equals("LivingEntityMixin")?2:1));
        for (var entry : Map.of("mixin/SignalGetterMixin",1,"mixin/EntityMixin",1,"client/mixin/MultiPlayerGameModeMixin",2,"mixin/ServerPlayerGameModeMixin",1).entrySet())
            contracts.add(create(entry.getKey(), "MixinBlockInteractionAdapters",MixinBlockInteractionAdapters::adapt, entry.getValue()));
        contracts.add(create("mixin/LivingEntityMixin","MixinBreathingCallbackAdapter",MixinBreathingCallbackAdapter::adapt,2));
        contracts.add(create("mixin/LivingEntityMixin","MixinEntitySoundCallbackAdapter",MixinEntitySoundCallbackAdapter::adapt,1));
        contracts.add(create("mixin/EntityMixin","MixinEntitySoundCallbackAdapter",MixinEntitySoundCallbackAdapter::adapt,1));
        contracts.add(create("client/mixin/HudMixin","MixinHudContextAdapter",MixinHudContextAdapter::adapt,1));
        contracts.add(create("mixin/LiquidBlockMixin","MixinFluidInteractionAdapter",MixinFluidInteractionAdapter::adapt,2));
        contracts.add(create("client/mixin/KeyboardHandlerMixin","MixinKeyActionAdapter",(mixin,targets)->MixinKeyActionAdapter.adapt(mixin,targets,(family,name)->CreateInjectionAdaptersTest.nativeTarget(name)),2));
        contracts.add(create("mixin/StructureTemplateMixin","MixinStructurePlacementAdapter",MixinStructurePlacementAdapter::adapt,3));
        for (String entry : CarpetMixinAdapterTest.NAMES)
            contracts.add(new Contract(entry, "player/world and fluid reactions", () -> CarpetMixinAdapterTest.mixin(entry),
                    (mixin,targets) -> MixinPlayerWorldCallbackAdapter.adapt(mixin,targets)+MixinFluidReactionAdapter.adapt(mixin,targets),
                    entry.startsWith("Level_")||entry.endsWith("BlackstoneMixin")?2:1));
        contracts.add(jar("sprite loader","build/compat-inputs/player-loading/mods/continuity-3.0.1+26.2.jar",
                "me/pepperbell/continuity/client/mixin/SpriteSourceListMixin","MixinSpriteLoaderCallbackAdapter",MixinSpriteLoaderCallbackAdapter::adapt,1));
        contracts.add(jar("GUI item","build/sweep80-mac/v020-rounds/r3/mods/itemglintrelight-fabric-26.2-0.3.0+26.2.jar",
                "celia/adwadg/itemglintrelight/mixin/client/GuiGraphicsItemOutlineMixin","MixinGuiItemCaptureAdapter",MixinGuiItemCaptureAdapter::adapt,1));
        contracts.add(jar("camera roll","build/compat-inputs/c2me-barrel-20261001/do_a_barrel_roll-fabric-3.8.4+26.2.jar",
                "nl/enjarai/doabarrelroll/mixin/client/roll/CameraMixin","MixinCameraRollAdapter",(mixin,targets)->MixinCameraRollAdapter.adapt(mixin,targets,(family,name)->CreateInjectionAdaptersTest.nativeTarget(name)),4));
        contracts.add(jar("chunk status","build/compat-inputs/startup-20260930/c2me-notickvd.jar",
                "com/ishland/c2me/notickvd/mixin/MixinWorld","chunk status",(mixin,targets) -> {
                    Function<String,byte[]> resource = name -> {if (!name.endsWith(".class"))return null;
                        ClassNode target=targets.apply(name.substring(0,name.length()-6));return target==null?null:CarpetMixinAdapterTest.bytes(target);};
                    return MixinRetarget.apply(mixin,MixinRetarget.plan(mixin,resource));
                },1));
        return contracts;
    }
    private static ClassNode target(String name) {
        ClassNode target=CarpetMixinAdapterTest.target(name);
        if(name.equals("net/minecraft/client/gui/render/GuiRenderer")) {
            byte[] bytes=new net.forbric.kernel.transform.ForbricMergedBaseCompatTransformer().transform(name.replace('/','.'),CarpetMixinAdapterTest.bytes(target),null);
            return MixinFit.parse(bytes);
        }
        return target;
    }
    /** Remaps self references and private delegates too, so a renamed callback has the same executable body. */
    private static ClassNode unrelatedNames(ClassNode original) {
        Map<String,String> renames=new HashMap<>();renames.put(original.name,"example/library/UnrelatedCallbacks");
        int index=0;
        for(MethodNode method:original.methods) if(!method.name.startsWith("<") && (MixinFit.injectorOf(method)!=null
                || (method.access&Opcodes.ACC_PRIVATE)!=0 && !shadow(method)))
            renames.put(original.name+"."+method.name+method.desc,"callback"+(index++));
        ClassNode renamed=new ClassNode();original.accept(new ClassRemapper(renamed,new SimpleRemapper(Opcodes.ASM9,renames)));
        return renamed;
    }
    private static boolean shadow(MethodNode method) {
        return Stream.of(method.visibleAnnotations,method.invisibleAnnotations).filter(Objects::nonNull).flatMap(Collection::stream)
                .anyMatch(a -> a.desc.endsWith("/Shadow;"));
    }
    @TestFactory Stream<DynamicTest> unrelatedPackagesAndHandlerNamesStillPreserveEveryContract() {
        return contracts().stream().map(contract -> DynamicTest.dynamicTest(contract.id()+" / "+contract.kind(), () -> {
            ClassNode renamed=unrelatedNames(contract.source().read());
            assertEquals(contract.count(),contract.adapter().apply(renamed,MixinCallbackContractsTest::target));
            CarpetMixinAdapterTest.verify(renamed);
            assertEquals(0,contract.adapter().apply(renamed,MixinCallbackContractsTest::target),"idempotence");
        }));
    }
    @TestFactory Stream<DynamicTest> groupedOrAmbiguousCallbacksCannotBeSilentlyMoved() {
        return contracts().stream().map(contract -> DynamicTest.dynamicTest(contract.id()+" / unsafe source", () -> {
            ClassNode grouped=unrelatedNames(contract.source().read());
            for(MethodNode method:grouped.methods) if(MixinFit.injectorOf(method)!=null) {
                if(method.visibleAnnotations==null)method.visibleAnnotations=new ArrayList<>();
                method.visibleAnnotations.add(new AnnotationNode("Lorg/spongepowered/asm/mixin/injection/Group;"));
            }
            byte[] before=CarpetMixinAdapterTest.bytes(grouped);
            assertEquals(0,contract.adapter().apply(grouped,MixinCallbackContractsTest::target));
            assertArrayEquals(before,CarpetMixinAdapterTest.bytes(grouped));
            if(contract.id().equals("chunk status")) return; // Independent argument modifiers compose; this per-handler plan has no class-level ambiguity.
            // Each key hook names its own native return; a duplicate names the same one and moves the same way.
            if(contract.kind().equals("MixinKeyActionAdapter")) return;
            ClassNode ambiguous=unrelatedNames(contract.source().read());
            for(MethodNode method:new ArrayList<>(ambiguous.methods)) if(MixinFit.injectorOf(method)!=null) {
                MethodNode copy=new MethodNode(method.access,method.name+"Duplicate",method.desc,method.signature,method.exceptions.toArray(String[]::new));
                method.accept(copy);ambiguous.methods.add(copy);
            }
            before=CarpetMixinAdapterTest.bytes(ambiguous);
            assertEquals(0,contract.adapter().apply(ambiguous,MixinCallbackContractsTest::target));
            assertArrayEquals(before,CarpetMixinAdapterTest.bytes(ambiguous));
        }));
    }
}
