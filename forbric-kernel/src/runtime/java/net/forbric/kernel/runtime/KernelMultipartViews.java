/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.runtime;

import java.util.*;
import net.forbric.api.VirtualGetters;
import net.minecraft.world.entity.Entity;

/** Both published part arrays, in the original reader's order, with shared identities visited once. */
public final class KernelMultipartViews {
    private KernelMultipartViews() { }
    public static Entity[] parts(Entity owner, boolean forgeFirst) {
        List<Entity> parts=new ArrayList<>();Set<Entity> identities=Collections.newSetFromMap(new IdentityHashMap<>());
        if(forgeFirst){add(parts,identities,forge(owner));add(parts,identities,neo(owner));}
        else{add(parts,identities,neo(owner));add(parts,identities,forge(owner));}
        return parts.toArray(Entity[]::new);
    }
    /** Completes an existing native collection while preserving its order and object identity. */
    public static void addDistinct(List<Entity> output, Entity[] additional) {
        Set<Entity> seen=Collections.newSetFromMap(new IdentityHashMap<>());seen.addAll(output);
        if(additional!=null)for(Entity part:additional)if(seen.add(part))output.add(part);
    }
    private static Entity[] forge(Entity owner){return VirtualGetters.get(Entity.class,"getParts",net.minecraftforge.entity.PartEntity[].class,owner);}
    private static Entity[] neo(Entity owner){return VirtualGetters.get(Entity.class,"getParts",net.neoforged.neoforge.entity.PartEntity[].class,owner);}
    private static void add(List<Entity> output,Set<Entity> seen,Entity[] parts){if(parts!=null)for(Entity part:parts)if(seen.add(part))output.add(part);}
}
