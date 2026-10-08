/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.runtime;

import java.util.List;
import net.forbric.api.VirtualProperties;
import net.minecraft.core.Holder;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.levelgen.structure.Structure;

/** The published Forge data API views the same mutable state that the canonical modifier pass updates. */
public final class KernelModifiableDataViews {
    private KernelModifiableDataViews() { }
    public static net.minecraftforge.common.world.ModifiableBiomeInfo biome(net.neoforged.neoforge.common.world.ModifiableBiomeInfo source) { return new BiomeView(source); }
    public static net.minecraftforge.common.world.ModifiableStructureInfo structure(net.neoforged.neoforge.common.world.ModifiableStructureInfo source) { return new StructureView(source); }
    public static net.minecraftforge.common.crafting.conditions.ICondition.IContext conditions(net.neoforged.neoforge.common.conditions.ICondition.IContext source) {
        return new net.minecraftforge.common.crafting.conditions.ICondition.IContext() {
            @Override public <T> java.util.Collection<Holder<T>> getTag(net.minecraft.tags.TagKey<T> key) { return source.getTag(key); }
        };
    }
    private static net.minecraftforge.common.world.ModifiableBiomeInfo.BiomeInfo forge(net.neoforged.neoforge.common.world.ModifiableBiomeInfo.BiomeInfo v) {
        return v == null ? null : new net.minecraftforge.common.world.ModifiableBiomeInfo.BiomeInfo(v.climateSettings(),v.effects(),v.generationSettings(),v.mobSpawnSettings());
    }
    private static net.neoforged.neoforge.common.world.ModifiableBiomeInfo.BiomeInfo neo(net.minecraftforge.common.world.ModifiableBiomeInfo.BiomeInfo v) {
        return new net.neoforged.neoforge.common.world.ModifiableBiomeInfo.BiomeInfo(v.climateSettings(),v.effects(),v.generationSettings(),v.mobSpawnSettings());
    }
    private static net.minecraftforge.common.world.ModifiableStructureInfo.StructureInfo forge(net.neoforged.neoforge.common.world.ModifiableStructureInfo.StructureInfo v) {
        return v == null ? null : new net.minecraftforge.common.world.ModifiableStructureInfo.StructureInfo(v.structureSettings());
    }
    static final class BiomeView extends net.minecraftforge.common.world.ModifiableBiomeInfo {
        final net.neoforged.neoforge.common.world.ModifiableBiomeInfo source;
        net.neoforged.neoforge.common.world.ModifiableBiomeInfo.BiomeInfo last;
        BiomeInfo projected;
        BiomeView(net.neoforged.neoforge.common.world.ModifiableBiomeInfo source) { super(forge(source.getOriginalBiomeInfo())); this.source=source; }
        @Override public BiomeInfo get() { BiomeInfo modified=getModifiedBiomeInfo(); return modified==null?getOriginalBiomeInfo():modified; }
        @Override public BiomeInfo getOriginalBiomeInfo() { return super.getOriginalBiomeInfo(); }
        @Override public BiomeInfo getModifiedBiomeInfo() {
            var current=source.getModifiedBiomeInfo();if(current!=last){projected=forge(current);last=current;}return projected;
        }
        @Override public void applyBiomeModifiers(Holder<Biome> owner,List<net.minecraftforge.common.world.BiomeModifier> modifiers) {
            if(source.getModifiedBiomeInfo()!=null)throw new IllegalStateException("Biome "+owner+" already modified");
            var publish=VirtualProperties.writer(net.neoforged.neoforge.common.world.ModifiableBiomeInfo.class,"getModifiedBiomeInfo",net.neoforged.neoforge.common.world.ModifiableBiomeInfo.BiomeInfo.class,source);
            var storage=VirtualProperties.declaredWriter(net.minecraftforge.common.world.ModifiableBiomeInfo.class,"getModifiedBiomeInfo",BiomeInfo.class,this);
            storage.accept(null);
            super.applyBiomeModifiers(owner,modifiers);
            projected=super.getModifiedBiomeInfo();last=neo(projected);publish.accept(last);
        }
    }
    static final class StructureView extends net.minecraftforge.common.world.ModifiableStructureInfo {
        final net.neoforged.neoforge.common.world.ModifiableStructureInfo source;
        net.neoforged.neoforge.common.world.ModifiableStructureInfo.StructureInfo last;
        StructureInfo projected;
        StructureView(net.neoforged.neoforge.common.world.ModifiableStructureInfo source) { super(forge(source.getOriginalStructureInfo())); this.source=source; }
        @Override public StructureInfo get() { StructureInfo modified=getModifiedStructureInfo();return modified==null?getOriginalStructureInfo():modified; }
        @Override public StructureInfo getOriginalStructureInfo() { return super.getOriginalStructureInfo(); }
        @Override public StructureInfo getModifiedStructureInfo() {var current=source.getModifiedStructureInfo();if(current!=last){projected=forge(current);last=current;}return projected;}
        @Override public void applyStructureModifiers(Holder<Structure> owner,List<net.minecraftforge.common.world.StructureModifier> modifiers) {
            if(source.getModifiedStructureInfo()!=null)throw new IllegalStateException("Structure "+owner+" already modified");
            var publish=VirtualProperties.writer(net.neoforged.neoforge.common.world.ModifiableStructureInfo.class,"getModifiedStructureInfo",net.neoforged.neoforge.common.world.ModifiableStructureInfo.StructureInfo.class,source);
            var storage=VirtualProperties.declaredWriter(net.minecraftforge.common.world.ModifiableStructureInfo.class,"getModifiedStructureInfo",StructureInfo.class,this);
            storage.accept(null);
            super.applyStructureModifiers(owner,modifiers);
            projected=super.getModifiedStructureInfo();last=new net.neoforged.neoforge.common.world.ModifiableStructureInfo.StructureInfo(projected.structureSettings());publish.accept(last);
        }
    }
}
