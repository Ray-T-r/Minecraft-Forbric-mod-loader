/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.mixin;

import java.util.*;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;
import net.forbric.kernel.util.ForbricLog;

/** Keeps registry tracker callbacks while the kernel owns the single registration freeze. */
public final class FabricRegistryInitializationMixinAdapter {
    public static final String PROPERTY="forbric.fabricRegistryInitialization";
    private static final String REGISTRIES="net/minecraft/core/registries/BuiltInRegistries";
    private static final String BOOTSTRAP="net/minecraft/server/Bootstrap";
    private FabricRegistryInitializationMixinAdapter() { }
    public static boolean enabled(){return !"off".equalsIgnoreCase(System.getProperty(PROPERTY,"on"));}
    /** Recognizes the conflicting lifecycle protocol without a mixin/config/handler name. */
    static boolean conflicts(ClassNode mixin) {
        return deferredFreeze(mixin) != null || postFreeze(mixin) != null;
    }
    public static int adapt(ClassNode mixin){
        if(!enabled() || mixin == null)return 0;
        MethodNode delay=deferredFreeze(mixin);
        if(delay != null) {
            MethodNode after=MixinCallbackShape.unique(mixin,m -> MixinCallbackShape.kind(m,"Inject")
                    && MixinCallbackShape.selects(m,"bootStrap") && MixinCallbackShape.plainPoint(m,"INVOKE","L"+BOOTSTRAP+";wrapStreams()V")
                    && m.desc.equals("(Lorg/spongepowered/asm/mixin/injection/callback/CallbackInfo;)V")
                    && calls(m,"net/fabricmc/fabric/impl/registry/sync/RegistrySyncManager","bootstrapRegistries","()V")==1);
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
    private static MethodNode deferredFreeze(ClassNode mixin) {
        if(!MixinCallbackShape.targets(mixin,BOOTSTRAP))return null;
        return MixinCallbackShape.unique(mixin,m -> {
            if(!MixinCallbackShape.kind(m,"Redirect") || !MixinCallbackShape.plainPoint(m,"INVOKE","L"+REGISTRIES+";bootStrap()V")
                    || !m.desc.equals("()V") || (m.access&Opcodes.ACC_STATIC)==0 || !m.tryCatchBlocks.isEmpty())return false;
            List<AbstractInsnNode> body=Arrays.stream(m.instructions.toArray()).filter(i->i.getOpcode()>=0).toList();
            return body.size()==2 && body.get(0) instanceof MethodInsnNode call && call.getOpcode()==Opcodes.INVOKESTATIC
                    && call.owner.equals(REGISTRIES) && call.name.equals("createContents") && call.desc.equals("()V") && body.get(1).getOpcode()==Opcodes.RETURN;
        });
    }
    private static MethodNode postFreeze(ClassNode mixin) {
        if(!MixinCallbackShape.targets(mixin,"net/minecraft/server/Main")&&!MixinCallbackShape.targets(mixin,"net/minecraft/client/Minecraft"))return null;
        return MixinCallbackShape.unique(mixin,m -> MixinCallbackShape.kind(m,"Inject")
                && m.desc.equals("(Lorg/spongepowered/asm/mixin/injection/callback/CallbackInfo;)V")
                && calls(m,REGISTRIES,"bootStrap","()V")==1
                && calls(m,"net/fabricmc/fabric/impl/registry/sync/trackers/vanilla/BlockInitTracker","postFreeze","()V")==1);
    }
    private static int calls(MethodNode method,String owner,String name,String descriptor){int count=0;for(var instruction:method.instructions)if(instruction instanceof MethodInsnNode call&&call.owner.equals(owner)&&call.name.equals(name)&&call.desc.equals(descriptor))count++;return count;}
    private static AnnotationNode at(String value){AnnotationNode node=new AnnotationNode("Lorg/spongepowered/asm/mixin/injection/At;");node.values=new ArrayList<>(List.of("value",value));return node;}
    private static void set(AnnotationNode node,String key,Object value){for(int i=0;i<node.values.size();i+=2)if(node.values.get(i).equals(key)){node.values.set(i+1,value);return;}node.values.add(key);node.values.add(value);}
}
