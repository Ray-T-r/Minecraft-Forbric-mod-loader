/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.mixin.weave;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import net.fabricmc.api.EnvType;
import net.forbric.api.Ecosystem;
import net.forbric.kernel.mixin.NativeGameReferences;
import net.forbric.kernel.mixin.NativeSourcePredicateIsland;
import net.forbric.kernel.TestFixtures;

/** The upstream modifier is woven into the copied native predicate, preserving observable short-circuit calls. */
class NativeSourcePredicateIslandWeaveTest implements Opcodes {
    @TempDir Path work;
    private static ClassNode node(byte[] b){ClassNode n=new ClassNode();new ClassReader(b).accept(n,ClassReader.EXPAND_FRAMES);return n;}
    private static byte[] bytes(ClassNode n){ClassWriter w=new ClassWriter(0);n.accept(w);return w.toByteArray();}
    private static byte[] read(ZipFile jar,String name){try{var e=jar.getEntry(name);return e==null?null:jar.getInputStream(e).readAllBytes();}catch(Exception e){throw new IllegalStateException(e);}}
    private record Inputs(ClassNode source,ClassNode nativeTarget,MethodNode handler,NativeSourcePredicateIsland.Extraction extraction){}
    private Inputs inputs()throws Exception{
        Path api=Path.of(System.getProperty("forbric.fabricApi",TestFixtures.fabricApi().toString())),base=Path.of(System.getProperty("forbric.predicateBase",TestFixtures.stagedRoot().resolve("merged-base/patched-mc-merged-26.2.jar").toString()));TestFixtures.requireFiles(TestFixtures.Fixture.STAGED,"actual drowning source/native pair",api,base);
        ClassNode source=null;try(ZipFile outer=new ZipFile(api.toFile())){var module=outer.stream().filter(e->e.getName().startsWith("META-INF/jars/fabric-content-registries-v0-")).findFirst().orElseThrow();try(ZipInputStream inner=new ZipInputStream(outer.getInputStream(module))){for(var e=inner.getNextEntry();e!=null;e=inner.getNextEntry())if(e.getName().equals("net/fabricmc/fabric/mixin/content/registry/fluid/LivingEntityMixin.class"))source=node(inner.readAllBytes());}}
        assertNotNull(source);ClassNode nativeTarget;try(ZipFile jar=new ZipFile(base.toFile())){nativeTarget=new NativeGameReferences(path->read(jar,path)).get(Ecosystem.FABRIC,"net/minecraft/world/entity/LivingEntity");}assertNotNull(nativeTarget);
        MethodNode handler=source.methods.stream().filter(m->m.name.equals("customFluidDrowning")).findFirst().orElseThrow(),nativeMethod=nativeTarget.methods.stream().filter(m->m.name.equals("baseTick")).findFirst().orElseThrow();MethodInsnNode atom=null;for(var i:nativeMethod.instructions)if(i instanceof MethodInsnNode c&&c.name.equals("isEyeInFluid"))atom=c;assertNotNull(atom);
        var extraction=NativeSourcePredicateIsland.extract(nativeTarget.name,nativeTarget.name,nativeMethod,atom,"$forbricDrowningPredicate");assertNotNull(extraction);
        return new Inputs(source,nativeTarget,handler,extraction);
    }
    @Test void theActualNativeRegionHasOnlyItsThreeProvedOutcomesAndKeepsAllOriginalCalls()throws Exception{
        var input=inputs();var extraction=input.extraction();assertEquals("net/minecraft/server/level/ServerLevel",extraction.worldType());assertEquals(10,extraction.calls().size());
        List<String> original=extraction.calls().stream().map(c->c.name+c.desc).sorted().toList(),copied=new ArrayList<>();for(var i:extraction.helper().instructions)if(i instanceof MethodInsnNode c)copied.add(c.name+c.desc);Collections.sort(copied);assertEquals(original,copied);
        MethodNode bad=input.nativeTarget().methods.stream().filter(m->m.name.equals("baseTick")).findFirst().orElseThrow();MethodInsnNode atom=null;for(var i:bad.instructions)if(i instanceof MethodInsnNode c&&c.name.equals("isEyeInFluid"))atom=c;bad.tryCatchBlocks.add(new TryCatchBlockNode(new LabelNode(),new LabelNode(),new LabelNode(),"java/lang/Throwable"));assertNull(NativeSourcePredicateIsland.extract(input.nativeTarget().name,input.nativeTarget().name,bad,atom,"different"));
    }
    @Test void theUpstreamBodyExecutesBeforeBubbleProtectionAndStopsAtTheOriginalGuards()throws Exception{
        var input=inputs();ClassNode source=input.source();MethodNode handler=input.handler();String helper=input.extraction().helper().name,helperDesc=input.extraction().helper().desc;
        // Keep the actual handler and its actual shadow; unrelated upstream injectors are outside this focused fixture.
        source.methods.removeIf(m->!m.name.equals("<init>")&&m!=handler&&!m.name.equals("isEyeInFluid"));source.methods.add(input.extraction().helper());
        for(AnnotationNode annotation:handler.visibleAnnotations)if(annotation.desc.endsWith("/ModifyExpressionValue;")){for(int i=0;i<annotation.values.size();i+=2)if(annotation.values.get(i).equals("method"))annotation.values.set(i+1,List.of(helper+helperDesc));}
        Map<String,String> code=new LinkedHashMap<>();
        code.put("net.minecraft.tags.TagKey","package net.minecraft.tags;public record TagKey(String id){}");
        code.put("net.minecraft.tags.FluidTags","package net.minecraft.tags;public class FluidTags{public static final TagKey WATER=new TagKey(\"water\");}");
        code.put("net.minecraft.world.level.material.Fluid","package net.minecraft.world.level.material;public class Fluid{}");
        code.put("net.minecraft.world.phys.Vec3","package net.minecraft.world.phys;public class Vec3{}");
        code.put("net.minecraft.core.BlockPos","package net.minecraft.core;public class BlockPos{public static BlockPos containing(double x,double y,double z){return new BlockPos();}}");
        code.put("net.minecraft.world.level.block.Block","package net.minecraft.world.level.block;public class Block{}");
        code.put("net.minecraft.world.level.block.Blocks","package net.minecraft.world.level.block;public class Blocks{public static final Block BUBBLE_COLUMN=new Block();}");
        code.put("net.minecraft.world.level.block.state.BlockState","package net.minecraft.world.level.block.state;public class BlockState{public boolean is(Object block){audit.DrownProbe.calls.add(\"bubble\");if(audit.DrownProbe.mode.equals(\"bubbleThrow\"))throw new IllegalStateException(\"bubble failed\");return audit.DrownProbe.mode.equals(\"bubble\");}}");
        code.put("net.minecraft.world.level.Level","package net.minecraft.world.level;public class Level{public net.minecraft.world.level.block.state.BlockState getBlockState(net.minecraft.core.BlockPos pos){return new net.minecraft.world.level.block.state.BlockState();}}");
        code.put("net.minecraft.server.level.ServerLevel","package net.minecraft.server.level;public class ServerLevel extends net.minecraft.world.level.Level{}");
        code.put("net.minecraft.world.effect.MobEffectUtil","package net.minecraft.world.effect;public class MobEffectUtil{public static boolean hasWaterBreathing(net.minecraft.world.entity.LivingEntity actor){audit.DrownProbe.calls.add(\"effect\");return audit.DrownProbe.mode.equals(\"effect\");}}");
        code.put("net.minecraft.world.entity.Entity","package net.minecraft.world.entity;public class Entity{}");
        code.put("net.fabricmc.fabric.impl.content.registry.fluid.InternalEntityFluidExtension","package net.fabricmc.fabric.impl.content.registry.fluid;public interface InternalEntityFluidExtension{java.util.Set<net.minecraft.tags.TagKey>fabric_api$getTouchedCustomFluids();}");
        code.put("net.fabricmc.fabric.api.registry.fluid.FluidBehavior","package net.fabricmc.fabric.api.registry.fluid;public interface FluidBehavior{boolean canDrownInFluid(net.minecraft.tags.TagKey tag,net.minecraft.world.entity.LivingEntity actor);}");
        code.put("net.fabricmc.fabric.impl.content.registry.fluid.EntityFluidInteractionRegistryImpl","package net.fabricmc.fabric.impl.content.registry.fluid;public class EntityFluidInteractionRegistryImpl{public static net.fabricmc.fabric.api.registry.fluid.FluidBehavior getFluidBehavior(net.minecraft.tags.TagKey tag){return(t,actor)->{audit.DrownProbe.calls.add(\"behavior:\"+tag.id());if(audit.DrownProbe.mode.equals(\"behaviorThrow\"))throw new IllegalStateException(\"behavior failed\");return !audit.DrownProbe.mode.equals(\"declined\");};}}");
        code.put("net.minecraft.world.entity.player.Abilities","package net.minecraft.world.entity.player;public class Abilities{public boolean invulnerable;}");
        code.put("net.minecraft.world.entity.player.Player","package net.minecraft.world.entity.player;public class Player extends net.minecraft.world.entity.LivingEntity{public Abilities getAbilities(){audit.DrownProbe.calls.add(\"abilities\");Abilities value=new Abilities();value.invulnerable=audit.DrownProbe.mode.equals(\"invulnerable\");return value;}}");
        code.put("net.minecraft.world.entity.LivingEntity","""
            package net.minecraft.world.entity;public class LivingEntity extends Entity implements net.fabricmc.fabric.impl.content.registry.fluid.InternalEntityFluidExtension{
             public net.minecraft.world.level.Level level(){return new net.minecraft.server.level.ServerLevel();}public double getX(){return 0;}public double getEyeY(){return 1;}public double getZ(){return 0;}
             public boolean isEyeInFluid(net.minecraft.tags.TagKey tag){audit.DrownProbe.calls.add("eye:"+tag.id());return tag.id().equals("water")?audit.DrownProbe.mode.equals("water"):!audit.DrownProbe.mode.equals("noEye");}
             public boolean canBreatheUnderwater(){audit.DrownProbe.calls.add("breathe");return audit.DrownProbe.mode.equals("breathe");}public java.util.Set<net.minecraft.tags.TagKey>fabric_api$getTouchedCustomFluids(){audit.DrownProbe.calls.add("touched");return new java.util.LinkedHashSet<>(java.util.List.of(new net.minecraft.tags.TagKey("custom")));}}
            """);
        code.put("audit.DrownProbe","""
            package audit;import java.util.*;public class DrownProbe{public static String mode;public static List<String>calls=new ArrayList<>();public void run()throws Throwable{for(String test:new String[]{"custom","noEye","declined","water","bubble","breathe","effect","invulnerable","behaviorThrow","bubbleThrow"}){mode=test;calls.clear();var actor=test.equals("invulnerable")?new net.minecraft.world.entity.player.Player():new net.minecraft.world.entity.LivingEntity();var helper=net.minecraft.world.entity.LivingEntity.class.getDeclaredMethod("HELPER",net.minecraft.server.level.ServerLevel.class);helper.setAccessible(true);try{Object result=helper.invoke(actor,new net.minecraft.server.level.ServerLevel());System.out.println("[DrownSource] mode="+test+" decision="+result+" calls="+calls);}catch(java.lang.reflect.InvocationTargetException failure){System.out.println("[DrownSource] mode="+test+" throw="+failure.getCause().getMessage()+" calls="+calls);}}}}
            """.replace("HELPER",helper));
        List<Path> files=new ArrayList<>();for(var entry:code.entrySet()){Path file=work.resolve("src").resolve(entry.getKey().replace('.','/')+".java");Files.createDirectories(file.getParent());Files.writeString(file,entry.getValue());files.add(file);}
        Path raw=work.resolve("upstream.class");Files.write(raw,bytes(source));String config="source-drowning.mixins.json";Path json=work.resolve(config);Files.writeString(json,"{\"required\":true,\"compatibilityLevel\":\"JAVA_25\",\"package\":\"net.fabricmc.fabric.mixin.content.registry.fluid\",\"mixins\":[\"LivingEntityMixin\"],\"injectors\":{\"defaultRequire\":1}}");
        Path fixture=WeaveHarness.fixture(work,"source-drowning",files,Map.of(config,json,source.name+".class",raw),List.of("-g"));
        var result=WeaveHarness.run(work,"on",fixture,List.of(new WeaveHarness.Config(config,"drowningoriginal",Ecosystem.FABRIC)),List.of(),EnvType.SERVER,"audit.DrownProbe","run",Map.of());
        for(String expected:List.of("mode=custom decision=0 calls=[eye:water, touched, eye:custom, behavior:custom, bubble, breathe, effect]","mode=noEye decision=3 calls=[eye:water, touched, eye:custom]","mode=declined decision=3 calls=[eye:water, touched, eye:custom, behavior:custom]","mode=water decision=0 calls=[eye:water, bubble, breathe, effect]","mode=bubble decision=3 calls=[eye:water, touched, eye:custom, behavior:custom, bubble]","mode=breathe decision=1 calls=[eye:water, touched, eye:custom, behavior:custom, bubble, breathe]","mode=effect decision=1 calls=[eye:water, touched, eye:custom, behavior:custom, bubble, breathe, effect]","mode=invulnerable decision=1 calls=[eye:water, touched, eye:custom, behavior:custom, bubble, breathe, effect, abilities]","mode=behaviorThrow throw=behavior failed calls=[eye:water, touched, eye:custom, behavior:custom]","mode=bubbleThrow throw=bubble failed calls=[eye:water, touched, eye:custom, behavior:custom, bubble]"))assertTrue(result.printed(expected),result.describe());
        WeaveHarness.assertWovenAndVerified(result,"net/minecraft/world/entity/LivingEntity",fixture);
    }
}
