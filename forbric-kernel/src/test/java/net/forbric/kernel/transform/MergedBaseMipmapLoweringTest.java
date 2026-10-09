/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.transform;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import org.objectweb.asm.tree.analysis.*;
import net.fabricmc.api.EnvType;
import net.forbric.kernel.TestFixtures;

class MergedBaseMipmapLoweringTest {
    @TempDir Path root;
    @Test void theRealGateKeepsItsGetterAndOnlyTheSelectedLevelIsBounded() throws Exception {
        Path base=TestFixtures.stagedRoot().resolve("merged-base/patched-mc-merged-26.2.jar");
        TestFixtures.requireFiles(TestFixtures.Fixture.STAGED,"real atlas allocation",base);
        byte[] raw;try(var zip=new java.util.zip.ZipFile(base.toFile())){raw=zip.getInputStream(zip.getEntry("net/minecraft/client/renderer/texture/SpriteLoader.class")).readAllBytes();}
        byte[] result=new ForbricMergedBaseCompatTransformer().transform("net.minecraft.client.renderer.texture.SpriteLoader",raw,null);
        assertNotSame(raw,result);ClassNode after=parse(result);int getter=0,bound=0;
        for(var method:after.methods){new Analyzer<>(new BasicVerifier()).analyze(after.name,method);for(var instruction:method.instructions)if(instruction instanceof MethodInsnNode call){if(call.name.equals("allowMipmapLowering"))getter++;if(call.owner.equals("net/forbric/api/MipLevelLimits"))bound++;}}
        assertEquals(1,getter);assertEquals(1,bound);
    }
    private Map<String,byte[]> fixture(boolean valid) throws Exception {
        return InjectorExecution.compile(root,Map.of(
            "net.minecraft.util.Mth","package net.minecraft.util; public class Mth {public static int log2(int size){return 31-Integer.numberOfLeadingZeros(size);}}",
            "net.minecraft.client.renderer.texture.Stitcher","package net.minecraft.client.renderer.texture; public class Stitcher {public final int level; public Stitcher(int w,int h,int selected,int other){level=selected;}}",
            "unknown.images.Atlas","""
                package unknown.images;
                public class Atlas {
                    public static boolean configured; public static int calls;
                    public static boolean policy(){calls++; return configured;}
                    public int stitch(int requested,int imageSize){
                        int maximum=%s; int selected;
                        if(maximum<requested && policy()) selected=maximum; else selected=requested;
                        return new net.minecraft.client.renderer.texture.Stitcher(16,16,selected,0).level;
                    }
                }
                """.formatted(valid?"net.minecraft.util.Mth.log2(imageSize)":"imageSize")));
    }
    @Test void arbitraryAtlasNamesPreserveConfigurationEffectsAndStayWithinImageLimits() throws Throwable {
        Map<String,byte[]> classes=new HashMap<>(fixture(true));byte[] raw=classes.get("unknown/images/Atlas");
        byte[] out=InjectorExecution.transform(new ForbricMergedBaseCompatTransformer(),"unknown.images.Atlas",raw,EnvType.CLIENT);
        assertNotSame(raw,out);classes.put("unknown/images/Atlas",out);ClassLoader loader=InjectorExecution.load(classes);
        assertEquals("",InjectorExecution.verify(out,loader));Class<?> atlas=loader.loadClass("unknown.images.Atlas");Object receiver=InjectorExecution.construct(atlas);
        assertEquals(3,InjectorExecution.invoke(receiver,"stitch",4,8));assertEquals(1,atlas.getField("calls").get(null));
        atlas.getField("configured").set(null,true);assertEquals(3,InjectorExecution.invoke(receiver,"stitch",4,8));assertEquals(2,atlas.getField("calls").get(null));
        assertEquals(2,InjectorExecution.invoke(receiver,"stitch",2,8));assertEquals(2,atlas.getField("calls").get(null),"valid original path does not consult configuration");
        assertSame(out,new ForbricMergedBaseCompatTransformer().transform("unknown.images.Atlas",out,null));
    }
    @Test void unrelatedIntegerBoundsAndTheOffControlDoNotChangeTheMethod() throws Exception {
        byte[] invalid=fixture(false).get("unknown/images/Atlas");assertSame(invalid,new ForbricMergedBaseCompatTransformer().transform("unknown.images.Atlas",invalid,null));
        byte[] valid=fixture(true).get("unknown/images/Atlas");String old=System.setProperty(ForbricMergedBaseCompatTransformer.MIPMAP_PROPERTY,"off");
        try{assertSame(valid,new ForbricMergedBaseCompatTransformer().transform("unknown.images.Atlas",valid,null));}
        finally{if(old==null)System.clearProperty(ForbricMergedBaseCompatTransformer.MIPMAP_PROPERTY);else System.setProperty(ForbricMergedBaseCompatTransformer.MIPMAP_PROPERTY,old);}
    }
    /** The repair runs on every class the merged-base pass parses; a method that constructs no Stitcher cannot be
     * repaired, so it never reaches the per-instruction data-flow analysis. */
    @Test void onlyAMethodThatConstructsTheAllocationIsAnalyzed() throws Exception {
        Map<String,byte[]> lookalike=InjectorExecution.compile(root,Map.of(
            "net.minecraft.util.Mth","package net.minecraft.util; public class Mth {public static int log2(int size){return 31-Integer.numberOfLeadingZeros(size);}}",
            "unknown.images.Canvas","package unknown.images; public class Canvas {public final int level; public Canvas(int w,int h,int selected,int other){level=selected;}}",
            "unknown.images.Mural","""
                package unknown.images;
                public class Mural {
                    public static boolean configured;
                    public static boolean policy(){return configured;}
                    public int stitch(int requested,int imageSize){
                        int maximum=net.minecraft.util.Mth.log2(imageSize); int selected;
                        if(maximum<requested && policy()) selected=maximum; else selected=requested;
                        return new Canvas(16,16,selected,0).level;
                    }
                }
                """));
        byte[] mural=lookalike.get("unknown/images/Mural");long before=AtlasMipBoundsRepair.methodsAnalyzed();
        assertSame(mural,new ForbricMergedBaseCompatTransformer().transform("unknown.images.Mural",mural,null),"the same decision around another constructor is left alone");
        assertFalse(AtlasMipBoundsRepair.apply(parse(mural)));
        assertEquals(before,AtlasMipBoundsRepair.methodsAnalyzed(),"no method of the look-alike was analyzed");
        ClassNode atlas=parse(fixture(true).get("unknown/images/Atlas"));before=AtlasMipBoundsRepair.methodsAnalyzed();
        assertTrue(AtlasMipBoundsRepair.apply(atlas),"the allocating method is still repaired");
        assertEquals(before+1,AtlasMipBoundsRepair.methodsAnalyzed(),"only stitch(), of <init>/policy()/stitch(), was analyzed");
    }
    private static ClassNode parse(byte[] bytes){ClassNode node=new ClassNode();new ClassReader(bytes).accept(node,0);return node;}
}
