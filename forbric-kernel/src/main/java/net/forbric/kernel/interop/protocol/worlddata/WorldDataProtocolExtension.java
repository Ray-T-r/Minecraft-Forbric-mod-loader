/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.interop.protocol.worlddata;
import net.forbric.api.ProtocolExtension;
import net.forbric.kernel.interop.protocol.ProtocolTransformAdapters;
import net.forbric.kernel.transform.ModifiableDataViewsTransformer;
import net.forbric.kernel.classloading.ForbricClassLoader;
/** The public Forge/NeoForge mutable world-data protocol, independently discoverable. */
public final class WorldDataProtocolExtension implements ProtocolExtension {
    @Override public String id(){return "forbric:world-data-views";}
    @Override public void registerTransformers(Context context,Transforms transforms){
        if(!(context.gameLoader()instanceof ForbricClassLoader loader))return;
        var transformer=new ModifiableDataViewsTransformer(path->{try(var in=loader.getGameResourceAsStream(path)){return in==null?null:in.readAllBytes();}catch(java.io.IOException unavailable){return null;}});
        transforms.register("COREMOD",transformer.name(),ProtocolTransformAdapters.transform(transformer));
    }
}
