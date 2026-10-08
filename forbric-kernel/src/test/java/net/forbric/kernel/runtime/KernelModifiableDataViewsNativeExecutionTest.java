/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.runtime;

import static org.junit.jupiter.api.Assertions.*;
import java.lang.reflect.*;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.*;
import java.util.function.*;
import net.forbric.kernel.classloading.ForbricClassLoader;
import org.junit.jupiter.api.Test;

/** Compare the complete native SDK pass with the projected pass on valid records, including phase order and writes. */
class KernelModifiableDataViewsNativeExecutionTest {
    @Test void nativeModifierPhasesAndTheirPublishedStateMatchAcrossBothViews()throws Exception{
        List<URL> urls=StagedGameClassLoader.urls();
        try(var parent=new URLClassLoader(urls.toArray(URL[]::new),getClass().getClassLoader());var loader=new ForbricClassLoader(urls.toArray(URL[]::new),parent)){
            loader.setTransformer((name,bytes)->{
                if(!Set.of("net.minecraft.world.level.biome.BiomeGenerationSettings","net.minecraft.world.level.biome.MobSpawnSettings","net.minecraft.world.level.biome.Biome","net.minecraft.world.attribute.EnvironmentAttributeMap","net.minecraft.world.level.levelgen.structure.Structure$StructureSettings","net.minecraft.world.level.levelgen.structure.Structure","net.minecraft.world.level.levelgen.structure.structures.BuriedTreasureStructure").contains(name))return bytes;
                var node=new org.objectweb.asm.tree.ClassNode();new org.objectweb.asm.ClassReader(bytes).accept(node,0);node.methods.removeIf(method->method.name.equals("<clinit>"));var writer=new org.objectweb.asm.ClassWriter(0);node.accept(writer);return writer.toByteArray();
            });
            for(String kind:List.of("Biome","Structure")){
                Object[] components=kind.equals("Biome")?biomeComponents(loader):structureComponents(loader);
                Class<?> forge=loader.loadClass("net.minecraftforge.common.world.Modifiable"+kind+"Info"),neo=loader.loadClass("net.neoforged.neoforge.common.world.Modifiable"+kind+"Info");
                Class<?> forgeRecord=loader.loadClass(forge.getName()+"$"+kind+"Info"),neoRecord=loader.loadClass(neo.getName()+"$"+kind+"Info");
                Object nativeOriginal=forgeRecord.getConstructors()[0].newInstance(components),canonicalOriginal=neoRecord.getConstructors()[0].newInstance(components);
                Object nativeInfo=forge.getConstructor(forgeRecord).newInstance(nativeOriginal),canonical=neo.getConstructor(neoRecord).newInstance(canonicalOriginal);
                Class<?> factory=loader.loadClass("net.forbric.kernel.runtime.KernelModifiableDataViews");Object projected=factory.getMethod(kind.toLowerCase(Locale.ROOT),neo).invoke(null,canonical);
                String originalGetter="getOriginal"+kind+"Info",modifiedGetter="getModified"+kind+"Info",apply="apply"+kind+"Modifiers";
                Object stable=forge.getMethod(originalGetter).invoke(projected);
                Object holder=loader.loadClass("net.minecraft.core.Holder").getMethod("direct",Object.class).invoke(null,owner(loader,kind,components));
                List<String> nativeOrder=new ArrayList<>(),viewOrder=new ArrayList<>();List<Object> nativeBuilders=new ArrayList<>(),viewBuilders=new ArrayList<>();
                Class<?> modifier=loader.loadClass("net.minecraftforge.common.world."+kind+"Modifier"),phase=loader.loadClass(modifier.getName()+"$Phase");
                List<Object> nativeModifiers=modifiers(loader,modifier,kind,holder,nativeOrder,nativeBuilders),viewModifiers=modifiers(loader,modifier,kind,holder,viewOrder,viewBuilders);
                Method run=forge.getMethod(apply,loader.loadClass("net.minecraft.core.Holder"),List.class);run.invoke(nativeInfo,holder,nativeModifiers);run.invoke(projected,holder,viewModifiers);
                List<String> expected=new ArrayList<>();for(Object value:phase.getEnumConstants())for(int i=1;i<=2;i++)expected.add(((Enum<?>)value).name()+":"+i);
                assertEquals(expected,nativeOrder);assertEquals(nativeOrder,viewOrder,"the full native phase/iteration order is preserved");
                assertEquals(expected.size(),nativeBuilders.size());assertEquals(expected.size(),viewBuilders.size());for(Object builder:nativeBuilders)assertSame(nativeBuilders.getFirst(),builder);for(Object builder:viewBuilders)assertSame(viewBuilders.getFirst(),builder);
                Object nativeChanged=forge.getMethod(modifiedGetter).invoke(nativeInfo),viewChanged=forge.getMethod(modifiedGetter).invoke(projected),canonicalChanged=neo.getMethod(modifiedGetter).invoke(canonical);
                assertNotNull(canonicalChanged);assertEquals(observables(kind,nativeChanged),observables(kind,viewChanged));assertEquals(observables(kind,nativeChanged),observables(kind,canonicalChanged),"the result is also published into the live canonical SDK object");
                assertSame(stable,forge.getMethod(originalGetter).invoke(projected));assertSame(viewChanged,forge.getMethod("get").invoke(projected));assertSame(viewChanged,forge.getMethod(modifiedGetter).invoke(projected));
                InvocationTargetException originalDuplicate=assertThrows(InvocationTargetException.class,()->run.invoke(nativeInfo,holder,List.of()));InvocationTargetException viewDuplicate=assertThrows(InvocationTargetException.class,()->run.invoke(projected,holder,List.of()));
                assertInstanceOf(IllegalStateException.class,originalDuplicate.getCause());assertInstanceOf(IllegalStateException.class,viewDuplicate.getCause());assertEquals(originalDuplicate.getCause().getMessage(),viewDuplicate.getCause().getMessage());
            }
        }
    }
    private static List<Object> modifiers(ClassLoader loader,Class<?> type,String kind,Object holder,List<String> trace,List<Object> builders){
        List<Object> out=new ArrayList<>();for(int i=1;i<=2;i++){int index=i;out.add(Proxy.newProxyInstance(loader,new Class<?>[]{type},(proxy,method,args)->{
            if(method.getName().equals("modify")){
                assertSame(holder,args[0]);trace.add(((Enum<?>)args[1]).name()+":"+index);Object builder=args[2];builders.add(builder);
                if(kind.equals("Biome")){
                    Object effects=call(builder,"getSpecialEffects");int previous=((OptionalInt)call(effects,"waterColor")).orElseThrow();effects.getClass().getMethod("waterColor",int.class).invoke(effects,previous*3+index);
                    Object climate=call(builder,"getClimateSettings");float temperature=(Float)call(climate,"getTemperature");climate.getClass().getMethod("setTemperature",float.class).invoke(climate,temperature+index*0.125f);
                }else{
                    Object settings=call(builder,"getStructureSettings"),current=call(settings,"getDecorationStep");Object[] steps=current.getClass().getEnumConstants();settings.getClass().getMethod("setDecorationStep",current.getClass()).invoke(settings,steps[(((Enum<?>)current).ordinal()*3+index)%steps.length]);
                }return null;
            }
            if(method.getName().equals("codec"))return null;
            if(method.getName().equals("toString"))return "native-sdk-modifier-"+index;
            if(method.getName().equals("hashCode"))return System.identityHashCode(proxy);
            if(method.getName().equals("equals"))return proxy==args[0];throw new AssertionError(method);
        }));}return out;
    }
    private static Object[] biomeComponents(ClassLoader loader)throws Exception{
        Class<?> climate=loader.loadClass("net.minecraft.world.level.biome.Biome$ClimateSettings"),temperature=loader.loadClass("net.minecraft.world.level.biome.Biome$TemperatureModifier");
        Object settings=climate.getConstructor(boolean.class,float.class,temperature,float.class).newInstance(true,0.5f,enumValue(temperature,"NONE"),0.25f);
        Class<?> effects=loader.loadClass("net.minecraft.world.level.biome.BiomeSpecialEffects"),grass=loader.loadClass("net.minecraft.world.level.biome.BiomeSpecialEffects$GrassColorModifier");
        Object colors=effects.getConstructor(int.class,Optional.class,Optional.class,Optional.class,grass).newInstance(13,Optional.empty(),Optional.empty(),Optional.empty(),enumValue(grass,"NONE"));
        Class<?> holderSet=loader.loadClass("net.minecraft.core.HolderSet");Object empty=holderSet.getMethod("direct",List.class).invoke(null,List.of());
        Object generation=loader.loadClass("net.minecraft.world.level.biome.BiomeGenerationSettings").getConstructor(holderSet,List.class).newInstance(empty,List.of());
        Constructor<?> spawn=loader.loadClass("net.minecraft.world.level.biome.MobSpawnSettings").getDeclaredConstructor(float.class,Map.class,Map.class);spawn.setAccessible(true);Object spawns=spawn.newInstance(0.125f,Map.of(),Map.of());return new Object[]{settings,colors,generation,spawns};
    }
    private static Object[] structureComponents(ClassLoader loader)throws Exception{
        Class<?> set=loader.loadClass("net.minecraft.core.HolderSet"),settings=loader.loadClass("net.minecraft.world.level.levelgen.structure.Structure$StructureSettings"),step=loader.loadClass("net.minecraft.world.level.levelgen.GenerationStep$Decoration"),terrain=loader.loadClass("net.minecraft.world.level.levelgen.structure.TerrainAdjustment");
        Object empty=set.getMethod("direct",List.class).invoke(null,List.of());return new Object[]{settings.getConstructor(set,Map.class,step,terrain).newInstance(empty,Map.of(),step.getEnumConstants()[0],enumValue(terrain,"NONE"))};
    }
    private static Object owner(ClassLoader loader,String kind,Object[] components)throws Exception{
        if(kind.equals("Structure"))return loader.loadClass("net.minecraft.world.level.levelgen.structure.structures.BuriedTreasureStructure").getConstructor(components[0].getClass()).newInstance(components[0]);
        Class<?> attributes=loader.loadClass("net.minecraft.world.attribute.EnvironmentAttributeMap");Constructor<?> attributeCtor=attributes.getDeclaredConstructor(Map.class);attributeCtor.setAccessible(true);Object empty=attributeCtor.newInstance(Map.of());
        Constructor<?> biome=loader.loadClass("net.minecraft.world.level.biome.Biome").getDeclaredConstructor(components[0].getClass(),attributes,components[1].getClass(),components[2].getClass(),components[3].getClass());biome.setAccessible(true);return biome.newInstance(components[0],empty,components[1],components[2],components[3]);
    }
    private static List<Object> observables(String kind,Object record)throws Exception{
        if(kind.equals("Structure")){Object settings=call(record,"structureSettings");return List.of(call(settings,"biomes"),call(settings,"spawnOverrides"),call(settings,"step"),call(settings,"terrainAdaptation"));}
        Object generation=call(record,"generationSettings"),spawns=call(record,"mobSpawnSettings");List<Object> carvers=new ArrayList<>();for(Object carver:(Iterable<?>)call(generation,"getCarvers"))carvers.add(carver);
        return List.of(call(record,"climateSettings"),call(record,"effects"),carvers,call(generation,"features"),call(spawns,"getCreatureProbability"),call(spawns,"getSpawnerTypes"),call(spawns,"getEntityTypes"));
    }
    private static Object enumValue(Class<?> type,String name){return Arrays.stream(type.getEnumConstants()).filter(value->((Enum<?>)value).name().equals(name)).findFirst().orElseThrow();}
    private static Object call(Object receiver,String name)throws Exception{return receiver.getClass().getMethod(name).invoke(receiver);}
}
