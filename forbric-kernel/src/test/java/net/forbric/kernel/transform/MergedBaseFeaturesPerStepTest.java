/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.transform;

import static org.junit.jupiter.api.Assertions.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.zip.ZipFile;
import net.forbric.kernel.TestFixtures;
import net.forbric.kernel.TestFixtures.Fixture;
import net.forbric.kernel.access.ClassTweakerTransformer;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;

/**
 * NeoForge's base keeps {@code ChunkGenerator.featuresPerStep} as one {@code Supplier} field, and fabric-biome-api's access
 * request can make it writeable from another class. What a refresh does with a supplier a Fabric mod wrote there is
 * {@link FeaturesPerStepRefreshStagedTest}'s subject. (Stage 4 C5: the merged base's second {@code ClearableLazy} view, its
 * provider cell, carrier class and {@code instanceof} guard were artefacts of the merged-base builder and went with it.)
 */
class MergedBaseFeaturesPerStepTest {
    private static final Path RUN=TestFixtures.stagedRoot(),MERGED=RUN.resolve(TestFixtures.BASE_JAR);
    private static final String OWNER="net/minecraft/world/level/chunk/ChunkGenerator",BINARY=OWNER.replace('/','.'),SUPPLIER="Ljava/util/function/Supplier;";
    @Test void theFieldCarriesVanillasDescriptorAndOnlyThatOne()throws Exception{
        ClassNode node=node(original());List<String> descriptors=node.fields.stream().filter(f->f.name.equals("featuresPerStep")).map(f->f.desc).sorted().toList();
        assertEquals(List.of(SUPPLIER),descriptors,"NeoForge's base keeps vanilla's one Supplier field");
    }
    @Test void theFieldIsAlsoWriteableFromAnotherClass()throws Exception{
        ClassNode node=node(widened());FieldNode field=node.fields.stream().filter(f->f.name.equals("featuresPerStep")&&f.desc.equals(SUPPLIER)).findFirst().orElseThrow();
        assertTrue((field.access&Opcodes.ACC_PUBLIC)!=0);assertEquals(0,field.access&Opcodes.ACC_FINAL,"the real access phase can satisfy a descriptor-specific mod's mutable request");
    }
    @Test void aSecondPassLeavesTheRepairedClassAlone()throws Exception{
        byte[] original=original();assertSame(original,new ForbricMergedBaseCompatTransformer().transform(BINARY,original,null),"there is no runtime descriptor patch over the base's one field");
    }
    private static byte[] widened()throws Exception{
        String file="accessWidener v2 official\naccessible field "+OWNER+" featuresPerStep "+SUPPLIER+"\nmutable field "+OWNER+" featuresPerStep "+SUPPLIER+"\n";
        return ClassTweakerTransformer.create(List.of(file.getBytes(java.nio.charset.StandardCharsets.UTF_8)),(n,b)->{}).transform(BINARY,original(),null);
    }
    private static byte[] original()throws IOException{TestFixtures.requireFile(Fixture.STAGED, MERGED,"staged merged base absent — skipping real-bytecode check");return read(MERGED,OWNER);}
    private static byte[] read(Path jar,String owner)throws IOException{try(ZipFile zip=new ZipFile(jar.toFile())){var entry=zip.getEntry(owner+".class");assertNotNull(entry,owner);try(var input=zip.getInputStream(entry)){return input.readAllBytes();}}}
    private static ClassNode node(byte[] bytes){ClassNode node=new ClassNode();new ClassReader(bytes).accept(node,0);return node;}
}
