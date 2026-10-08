/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.mixin;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.ZipFile;
import net.forbric.kernel.TestFixtures;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;

class SourceProtocolPolicyTest {
    @AfterEach void reset(){MergedBaseMixinCompat.reset();System.clearProperty(FabricRegistryInitializationMixinAdapter.PROPERTY);}
    @Test void renamedConfigurationClassAndCallbacksStillRetainTheirOriginalTrackerBodies()throws Exception {
        ClassNode source=registry();rename(source,"independent/RelocatedCallbacks");
        MethodNode tracker=source.methods.stream().filter(m->MixinFit.injectorOf(m)!=null&&MixinFit.injectorOf(m).desc.endsWith("/Inject;")).findFirst().orElseThrow();
        tracker.name="arbitraryTracker";
        MethodNode freeze=source.methods.stream().filter(m->MixinFit.injectorOf(m)!=null&&MixinFit.injectorOf(m).desc.endsWith("/Redirect;")).findFirst().orElseThrow();
        freeze.name="anotherCallback";
        String before=MixinInstructionFingerprint.hash(tracker);
        assertEquals(1,FabricRegistryInitializationMixinAdapter.adapt(source));
        assertEquals(before,MixinInstructionFingerprint.hash(tracker));assertNull(MixinFit.injectorOf(freeze));
    }
    @Test void disabledAdapterFallsBackFromTheActualSourceAndTheSameNameAloneDoesNothing()throws Exception {
        ClassNode source=registry();rename(source,"independent/RelocatedCallbacks");
        String config="unrelated.mixins.json";
        byte[] json="{\"package\":\"independent\",\"mixins\":[\"RelocatedCallbacks\"]}".getBytes(StandardCharsets.UTF_8);
        System.setProperty(FabricRegistryInitializationMixinAdapter.PROPERTY,"off");
        MergedBaseMixinCompat.discover(config,json,path->path.equals(source.name+".class")?bytes(source):null);
        assertEquals(List.of("RelocatedCallbacks"),ForbricMixinService.suppressedMixinsFor(config));
        // The same entry after a source update with no conflicting registry operation cannot borrow the previous decision.
        source.methods.removeIf(method->MixinFit.injectorOf(method)!=null);
        MergedBaseMixinCompat.discover(config,json,path->path.equals(source.name+".class")?bytes(source):null);
        assertTrue(ForbricMixinService.suppressedMixinsFor(config).isEmpty());
        assertTrue(ForbricMixinService.suppressedMixinsFor("fabric-registry-sync-v0.mixins.json").isEmpty());
    }
    @Test void anOpaqueFreezeReplacementIsNotMistakenForTheClosedCreateContentsProtocol()throws Exception {
        ClassNode source=registry();
        MethodNode freeze=source.methods.stream().filter(m->MixinFit.injectorOf(m)!=null&&MixinFit.injectorOf(m).desc.endsWith("/Redirect;")).findFirst().orElseThrow();
        freeze.instructions.insert(new MethodInsnNode(Opcodes.INVOKESTATIC,"independent/Effects","unknown","()V",false));
        assertEquals(0,FabricRegistryInitializationMixinAdapter.adapt(source));
        assertNotNull(MixinFit.injectorOf(freeze));
    }
    private static ClassNode registry()throws Exception {
        Path jar=Path.of("build/compat-inputs/player-loading/api/fabric-registry-sync-v0-7.1.1+c7bd5b8e9e.jar");
        TestFixtures.require(TestFixtures.Fixture.THIRD_PARTY,Files.exists(jar),jar+" absent");
        try(ZipFile zip=new ZipFile(jar.toFile())){return MixinFit.parse(zip.getInputStream(zip.getEntry("net/fabricmc/fabric/mixin/registry/sync/BootstrapMixin.class")).readAllBytes());}
    }
    private static byte[] bytes(ClassNode node){ClassWriter writer=new ClassWriter(0);node.accept(writer);return writer.toByteArray();}
    private static void rename(ClassNode node,String name){
        String old=node.name;ClassNode mapped=new ClassNode();
        node.accept(new org.objectweb.asm.commons.ClassRemapper(mapped,new org.objectweb.asm.commons.Remapper(){@Override public String map(String type){return type.equals(old)?name:type;}}));
        node.name=mapped.name;node.methods=mapped.methods;node.fields=mapped.fields;node.interfaces=mapped.interfaces;
        node.visibleAnnotations=mapped.visibleAnnotations;node.invisibleAnnotations=mapped.invisibleAnnotations;
    }
}
