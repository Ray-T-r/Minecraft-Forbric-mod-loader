/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.interop.protocol;
import net.forbric.api.ProtocolExtension;
import net.forbric.api.Side;
import net.fabricmc.api.EnvType;
import net.forbric.kernel.transform.*;

/** Connects the public transformation registration contract to the kernel's ordered pipeline. */
public final class ProtocolTransformAdapters {
    private ProtocolTransformAdapters() { }
    public static ProtocolExtension.Transforms registry(TransformChain chain) {
        return (phase, name, transform) -> chain.register(TransformPhase.valueOf(phase), new ClassTransformer() {
            @Override public String name() { return name; }
            @Override public AnchorSet anchors() { return AnchorSet.scanned("discoverable protocol transformation " + name); }
            @Override public byte[] transform(String className, byte[] bytes, TransformContext context) {
                ProtocolExtension.TransformData data = context == null ? null : new ProtocolExtension.TransformData(
                    Side.parse(context.getEnvType().name()), context.isDevelopment(), context.getRuntimeNamespace(), context.getEcosystem(), context.getSourceModId());
                return transform.apply(className, bytes, data);
            }
        });
    }
    public static ProtocolExtension.BytecodeTransform transform(ClassTransformer transformer) {
        return (name, bytes, data) -> transformer.transform(name, bytes, data == null ? null : new TransformContext(
            data.side() == Side.CLIENT ? EnvType.CLIENT : EnvType.SERVER, data.development(), data.namespace(), data.ecosystem(), data.modId()));
    }
}
