/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.transform;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Path;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.*;
import org.objectweb.asm.commons.*;
import net.fabricmc.api.EnvType;

/** A coherent two-API array return is verified and executed; no family is replaced by an empty array. */
@ExecutesInjector(DragonPartsInjector.class)
class DragonPartsInjectorExecutionTest {
    private static Map<String,byte[]> coherent(Path work)throws Exception{
        Map<String,String> source=new HashMap<>();
        source.put("net.minecraft.world.entity.Entity","""
            package net.minecraft.world.entity;
            public class Entity {
                public net.minecraftforge.entity.PartEntity<?>[] getParts(){return null;}
                public net.neoforged.neoforge.entity.PartEntity<?>[] getNeoParts(){return null;}
            }
            """);
        source.put("net.neoforged.neoforge.entity.PartEntity","""
            package net.neoforged.neoforge.entity;
            public class PartEntity<T extends net.minecraft.world.entity.Entity> extends net.minecraft.world.entity.Entity {
                private final T parent; public PartEntity(T parent){this.parent=parent;} public T getParent(){return parent;}
            }
            """);
        source.put("net.minecraftforge.entity.PartEntity","""
            package net.minecraftforge.entity;
            public class PartEntity<T extends net.minecraft.world.entity.Entity> extends net.neoforged.neoforge.entity.PartEntity<T> {
                private final T parent; public PartEntity(T parent){super(parent);this.parent=parent;} public T getParent(){return parent;}
            }
            """);
        source.put(DragonPartsInjector.PART,"""
            package net.minecraft.world.entity.boss.enderdragon;
            public class EnderDragonPart extends net.minecraftforge.entity.PartEntity<EnderDragon> {
                public EnderDragonPart(EnderDragon parent){super(parent);}
            }
            """);
        source.put(DragonPartsInjector.DRAGON,"""
            package net.minecraft.world.entity.boss.enderdragon;
            public class EnderDragon extends net.minecraft.world.entity.Entity {
                public final EnderDragonPart[] subEntities={new EnderDragonPart(this),new EnderDragonPart(this)};
                public net.minecraftforge.entity.PartEntity<?>[] getParts(){return subEntities;}
                public net.neoforged.neoforge.entity.PartEntity<?>[] getNeoParts(){return subEntities;}
            }
            """);
        Map<String,byte[]> compiled=new HashMap<>(InjectorExecution.compile(work,source));SimpleRemapper rename=new SimpleRemapper(Map.of(
            DragonPartsInjector.ENTITY+".getNeoParts"+DragonPartsInjector.NEO_GET_PARTS,"getParts",
            DragonPartsInjector.DRAGON_INTERNAL+".getNeoParts"+DragonPartsInjector.NEO_GET_PARTS,"getParts"));
        for(String owner:List.of(DragonPartsInjector.ENTITY,DragonPartsInjector.DRAGON_INTERNAL)){ClassWriter writer=new ClassWriter(0);new ClassReader(compiled.get(owner)).accept(new ClassRemapper(writer,rename),0);compiled.put(owner,writer.toByteArray());}return compiled;
    }
    @Test void bothNativeArraysVerifyAndReturnEveryActualPart(@TempDir Path work)throws Throwable{
        Map<String,byte[]> classes=coherent(work);for(String name:List.of(DragonPartsInjector.PART,DragonPartsInjector.DRAGON)){byte[] original=classes.get(name.replace('.','/'));assertSame(original,InjectorExecution.transform(new DragonPartsInjector(),name,original,EnvType.CLIENT));}
        ClassLoader loader=InjectorExecution.load(classes);for(String owner:classes.keySet())assertEquals("",InjectorExecution.verify(classes.get(owner),loader),owner);
        Class<?> type=loader.loadClass(DragonPartsInjector.DRAGON);Object dragon=InjectorExecution.construct(type);Object[] actual=(Object[])type.getField("subEntities").get(dragon);
        for(var getter:type.getDeclaredMethods())if(getter.getName().equals("getParts")){assertSame(actual,getter.invoke(dragon));assertEquals(2,((Object[])getter.invoke(dragon)).length);}
        Class<?> forge=loader.loadClass(DragonPartsInjector.FORGE_PART.replace('/','.')),neo=loader.loadClass(DragonPartsInjector.NEO_PART.replace('/','.'));for(Object part:actual){assertTrue(forge.isInstance(part));assertTrue(neo.isInstance(part));assertSame(dragon,part.getClass().getMethod("getParent").invoke(part));}
        MergedBaseFrameRecomputer frames=new MergedBaseFrameRecomputer(path->classes.get(path.replace(".class","")));assertEquals(DragonPartsInjector.FORGE_PART,frames.commonSuperClass(DragonPartsInjector.PART_INTERNAL,DragonPartsInjector.FORGE_PART));assertEquals(DragonPartsInjector.NEO_PART,frames.commonSuperClass(DragonPartsInjector.PART_INTERNAL,DragonPartsInjector.NEO_PART));
    }
    @Test void retiredCompatibilityNameDoesNotMutateUnknownBytes(){byte[] original={1,2,3};assertSame(original,new DragonPartsInjector().transform(DragonPartsInjector.PART,original,null));assertNull(DragonPartsInjector.rebasedSuperclass(DragonPartsInjector.PART_INTERNAL));}
}
