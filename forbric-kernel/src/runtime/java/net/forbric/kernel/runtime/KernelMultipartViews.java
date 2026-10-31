/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.runtime;

import java.util.*;
import net.minecraft.world.entity.Entity;

/** Completes a native part list with the parts it is missing, shared identities visited once (ClientPartTrackingInjector). */
public final class KernelMultipartViews {
    private KernelMultipartViews() { }
    /** Completes an existing native collection while preserving its order and object identity. */
    public static void addDistinct(List<Entity> output, Entity[] additional) {
        Set<Entity> seen=Collections.newSetFromMap(new IdentityHashMap<>());seen.addAll(output);
        if(additional!=null)for(Entity part:additional)if(seen.add(part))output.add(part);
    }
}
