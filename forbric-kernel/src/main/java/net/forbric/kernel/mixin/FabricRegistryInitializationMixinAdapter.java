/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.mixin;

import java.util.*;
import java.util.function.BiFunction;
import java.util.function.Function;
import net.forbric.api.Ecosystem;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;
import net.forbric.kernel.util.ForbricLog;

/**
 * Keeps registry tracker callbacks while the kernel owns the single registration freeze.
 *
 * <p>The protocol is recognised by what its injectors do to the freeze, never by whose they are. A Fabric game freezes
 * its built-in registries late when a mixin on {@code Bootstrap} redirects the bootstrap's {@code BuiltInRegistries.bootStrap()}
 * to {@code createContents()} alone (the deferred freeze) and a callback on {@code Main} or {@code Minecraft} later makes
 * the {@code bootStrap()} call itself (the post-freeze callback). The kernel keeps the freeze in the bootstrap, so the
 * deferring redirect is taken out, any other injector the deferring mixin hangs on the bootstrap's step after the freeze
 * ({@code wrapStreams}, which the merged bootstrap no longer calls) runs at its end, and the post-freeze callback keeps
 * everything but its second {@code bootStrap()} — a second freeze of frozen registries throws. What the callbacks call
 * besides is theirs and stays as written.
 *
 * <p>Injectors are read as Mixin reads them, never by spelling. A selector is the method it binds in the merged
 * {@code Bootstrap} ({@link MixinCallbackShape#selectsMember}); a caller with no class at hand gets the member every
 * selector, as Mixin parses it, can only name. A point is the member it names ({@link MixinCallbackShape#plainPoint}):
 * whitespace and a dotted owner are the same target, and one without its owner or descriptor names the member where the
 * method it was written for, in the class the mod was compiled against, decides it.
 */
public final class FabricRegistryInitializationMixinAdapter {
    public static final String PROPERTY="forbric.fabricRegistryInitialization";
    private static final String REGISTRIES="net/minecraft/core/registries/BuiltInRegistries";
    private static final String BOOTSTRAP="net/minecraft/server/Bootstrap";
    private FabricRegistryInitializationMixinAdapter() { }
    public static boolean enabled(){return !"off".equalsIgnoreCase(System.getProperty(PROPERTY,"on"));}
    /**
     * Recognizes the conflicting lifecycle protocol without a mixin/config/handler name, its points read as {@link #adapt}
     * reads them: one without its owner or descriptor in the method it was written for, in the class the mod was compiled
     * against ({@link NativeGameReferences}) — a gate that saw only the full spelling would let a deferred freeze written
     * another way through unrefused when it cannot be adapted.
     */
    static boolean conflicts(ClassNode mixin) {
        return conflicts(mixin,NativeGameReferences::reference);
    }
    /** {@code references} gives the class the mod was compiled against; null (or a null answer): none at hand. */
    static boolean conflicts(ClassNode mixin,BiFunction<Ecosystem,String,ClassNode> references) {
        if(mixin==null)return false;
        ClassNode source=MixinCallbackShape.targets(mixin,BOOTSTRAP)&&references!=null?references.apply(MixinStubRebind.ecosystemOf(mixin.name),BOOTSTRAP):null;
        return deferredFreeze(mixin,source) != null || postFreeze(mixin) != null;
    }
    /** Without the merged class: selectors by the member they can only name, points only when spelled with owner and descriptor. */
    public static int adapt(ClassNode mixin){
        return adapt(mixin,name->null,null);
    }
    /** {@code targets} gives the merged classes, where selectors bind; owner-less points are read in the native classes. */
    public static int adapt(ClassNode mixin,Function<String,ClassNode> targets){
        return adapt(mixin,targets,NativeGameReferences::reference);
    }
    /** {@code references} gives the class the mod was compiled against, where a point without its owner or descriptor is read. */
    static int adapt(ClassNode mixin,Function<String,ClassNode> targets,BiFunction<Ecosystem,String,ClassNode> references){
        if(!enabled() || mixin == null)return 0;
        Function<String,ClassNode> sources=owner->references==null?null:references.apply(MixinStubRebind.ecosystemOf(mixin.name),owner);
        MethodNode delay=deferredFreeze(mixin,sources.apply(BOOTSTRAP));
        if(delay != null) {
            ClassNode merged=targets==null?null:lookup(targets,BOOTSTRAP),source=sources.apply(BOOTSTRAP);
            // The deferring mixin's own callback at the bootstrap's step after the freeze: what it calls is its own.
            MethodNode after=MixinCallbackShape.unique(mixin,m -> MixinCallbackShape.kind(m,"Inject")
                    && MixinCallbackShape.selectsMember(m,merged,BOOTSTRAP,"bootStrap()V")
                    && MixinCallbackShape.plainPoint(m,"INVOKE","L"+BOOTSTRAP+";wrapStreams()V",MixinCallbackShape.written(m,source))
                    && m.desc.equals("(Lorg/spongepowered/asm/mixin/injection/callback/CallbackInfo;)V"));
            if(after==null)return 0;
            set(MixinFit.injectorOf(after),"at",List.of(at("TAIL")));
            MixinCarrierCallbackAdapters.removeInjector(delay,MixinFit.injectorOf(delay));
            ForbricLog.info("[Forbric/RegistrySync] retained %s's bootstrap tracker callback; the kernel owns its proved deferred freeze",mixin.name);
            return 1;
        }
        MethodNode after=postFreeze(mixin);if(after==null)return 0;
        MethodInsnNode bootstrap=null;
        for(var instruction:after.instructions)if(instruction instanceof MethodInsnNode call&&call.owner.equals(REGISTRIES)&&call.name.equals("bootStrap")&&call.desc.equals("()V"))bootstrap=call;
        after.instructions.remove(bootstrap);
        if(MixinCallbackShape.targets(mixin,"net/minecraft/client/Minecraft"))set(MixinFit.injectorOf(after),"at",List.of(at("RETURN")));
        ForbricLog.info("[Forbric/RegistrySync] retained %s's post-freeze callback without repeating the registry bootstrap",mixin.name);
        return 1;
    }
    /** {@code source}: the {@code Bootstrap} the mod was compiled against, where a point without its owner or descriptor is read; or null. */
    private static MethodNode deferredFreeze(ClassNode mixin,ClassNode source) {
        if(!MixinCallbackShape.targets(mixin,BOOTSTRAP))return null;
        return MixinCallbackShape.unique(mixin,m -> {
            if(!MixinCallbackShape.kind(m,"Redirect") || !MixinCallbackShape.plainPoint(m,"INVOKE","L"+REGISTRIES+";bootStrap()V",MixinCallbackShape.written(m,source))
                    || !m.desc.equals("()V") || (m.access&Opcodes.ACC_STATIC)==0 || !m.tryCatchBlocks.isEmpty())return false;
            List<AbstractInsnNode> body=Arrays.stream(m.instructions.toArray()).filter(i->i.getOpcode()>=0).toList();
            return body.size()==2 && body.get(0) instanceof MethodInsnNode call && call.getOpcode()==Opcodes.INVOKESTATIC
                    && call.owner.equals(REGISTRIES) && call.name.equals("createContents") && call.desc.equals("()V") && body.get(1).getOpcode()==Opcodes.RETURN;
        });
    }
    /**
     * A callback on the game's start that makes the {@code BuiltInRegistries.bootStrap()} call a deferred freeze took out of
     * the bootstrap: once, from a plain {@code (CallbackInfo)V} {@code @Inject} on {@code Main} or {@code Minecraft}.
     * Whatever else it calls is the mod's post-freeze work and stays.
     */
    private static MethodNode postFreeze(ClassNode mixin) {
        if(!MixinCallbackShape.targets(mixin,"net/minecraft/server/Main")&&!MixinCallbackShape.targets(mixin,"net/minecraft/client/Minecraft"))return null;
        return MixinCallbackShape.unique(mixin,m -> MixinCallbackShape.kind(m,"Inject")
                && m.desc.equals("(Lorg/spongepowered/asm/mixin/injection/callback/CallbackInfo;)V")
                && calls(m,REGISTRIES,"bootStrap","()V")==1);
    }
    /** {@code config:mixin} of every Fabric mixin seen deferring the freeze out of the bootstrap, as configs are read. */
    private static final Set<String> DEFERRED_FREEZES=java.util.concurrent.ConcurrentHashMap.newKeySet();
    /**
     * Notes whether {@code mixin}, of {@code config} and declared by a mod of {@code family}, defers the freeze out of the
     * bootstrap: a Fabric game with such a mod freezes after its entrypoints. Read before any mixin is prepared
     * ({@link MergedBaseMixinCompat#discover} visits every registered config first), whatever becomes of the mixin.
     */
    static void noteDeferredFreeze(String config,ClassNode mixin,Ecosystem family,BiFunction<Ecosystem,String,ClassNode> references){
        if(mixin==null||family!=Ecosystem.FABRIC||!MixinCallbackShape.targets(mixin,BOOTSTRAP))return;
        ClassNode source=references==null?null:references.apply(family,BOOTSTRAP);
        if(deferredFreeze(mixin,source)!=null)DEFERRED_FREEZES.add(config+":"+mixin.name.replace('/','.'));
    }
    /** The Fabric mixins seen deferring the freeze (empty: a Fabric game here freezes in the bootstrap, as the kernel does). */
    public static Set<String> deferredFreezes(){return Set.copyOf(DEFERRED_FREEZES);}
    /** Forgets what {@code config} was seen to do, before it is read again. */
    static void forgetDeferredFreezes(String config){DEFERRED_FREEZES.removeIf(entry->entry.startsWith(config+":"));}
    private static int calls(MethodNode method,String owner,String name,String descriptor){int count=0;for(var instruction:method.instructions)if(instruction instanceof MethodInsnNode call&&call.owner.equals(owner)&&call.name.equals(name)&&call.desc.equals(descriptor))count++;return count;}
    private static ClassNode lookup(Function<String,ClassNode> classes,String name){
        try{return classes.apply(name);}catch(RuntimeException unavailable){return null;}
    }
    private static AnnotationNode at(String value){AnnotationNode node=new AnnotationNode("Lorg/spongepowered/asm/mixin/injection/At;");node.values=new ArrayList<>(List.of("value",value));return node;}
    private static void set(AnnotationNode node,String key,Object value){for(int i=0;i<node.values.size();i+=2)if(node.values.get(i).equals(key)){node.values.set(i+1,value);return;}node.values.add(key);node.values.add(value);}
}
