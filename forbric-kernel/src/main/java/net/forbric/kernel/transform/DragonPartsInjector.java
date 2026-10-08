/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.transform;

import net.forbric.api.Ecosystem;
import net.forbric.api.ForeignType;

/** Compatibility name for the retired single-family multipart rewrite. The merge now proves a shared
 * ancestor hierarchy and keeps both native array-return descriptors; retyping or emptying either loses API. */
@Deprecated
public final class DragonPartsInjector implements ClassTransformer {
    public static final String PROPERTY="forbric.dragonParts";
    static final String PART="net.minecraft.world.entity.boss.enderdragon.EnderDragonPart";
    static final String DRAGON="net.minecraft.world.entity.boss.enderdragon.EnderDragon";
    static final String HITBOXES="net.minecraft.client.renderer.debug.EntityHitboxDebugRenderer";
    static final String FORGE_PART=ForeignType.PART_ENTITY.internal(Ecosystem.FORGE);
    static final String NEO_PART=ForeignType.PART_ENTITY.internal(Ecosystem.NEOFORGE);
    static final String PART_INTERNAL=PART.replace('.','/');
    static final String DRAGON_INTERNAL=DRAGON.replace('.','/');
    static final String ENTITY="net/minecraft/world/entity/Entity";
    static final String FORGE_GET_PARTS="()[L"+FORGE_PART+";";
    static final String NEO_GET_PARTS="()[L"+NEO_PART+";";
    /** Hierarchy users must read the actual class definition. No superclass is predicted. */
    public static String rebasedSuperclass(String internalName){return null;}
    @Override public String name(){return "forbric-dragon-parts";}
    @Override public AnchorSet anchors(){return AnchorSet.scanned("multipart hierarchy and both native APIs are preserved by the proved merge");}
    @Override public byte[] transform(String name,byte[] bytes,TransformContext context){return bytes;}
}
