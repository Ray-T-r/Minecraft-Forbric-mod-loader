/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.mixin;

import java.io.ByteArrayInputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;
import com.electronwill.nightconfig.core.UnmodifiableConfig;
import com.electronwill.nightconfig.json.JsonFormat;
import org.objectweb.asm.tree.*;

/** Source-discovered SDK protocol conflicts. A configuration, package or handler name never decides policy. */
public final class MergedBaseMixinCompat {
    private MergedBaseMixinCompat() { }
    public static boolean enabled(){return !"off".equalsIgnoreCase(System.getProperty("forbric.mergedBaseCompat","on"));}
    /** Discovered entries are identities used for attribution after their contents have been judged. */
    public static final List<String> SUPPRESSED_MIXINS=new CopyOnWriteArrayList<>();
    public static final List<String> SUPPRESSED_UNLESS_PRUNED=List.of();
    public static final List<String> KEPT_MIXINS=List.of();
    public static final Set<String> DISABLED_CONFIGS=Set.of();
    public record PinnedContract(String pin,String target,String contract) { }
    public static final List<PinnedContract> PINNED_CONTRACTS=new CopyOnWriteArrayList<>();
    private static final Map<String,String> SOURCES=new java.util.concurrent.ConcurrentHashMap<>();
    private static final Map<String,String> REASONS=new java.util.concurrent.ConcurrentHashMap<>();

    private static boolean indexed;
    static synchronized void discoverAll(Function<String,byte[]> resources) {
        if(indexed)return;indexed=true;
        for(String config:ForbricMixinService.registeredConfigNames())discover(config,resources.apply(config),resources);
    }
    static synchronized void discover(String configName,byte[] configBytes,Function<String,byte[]> resources) {
        if(configName==null||configBytes==null||resources==null)return;
        SUPPRESSED_MIXINS.removeIf(entry->entry.startsWith(configName+":"));
        PINNED_CONTRACTS.removeIf(row->row.pin().startsWith(configName+":"));
        REASONS.keySet().removeIf(entry->entry.startsWith(configName+":"));
        SOURCES.keySet().removeIf(entry->entry.startsWith(configName+":"));
        try(var reader=new InputStreamReader(new ByteArrayInputStream(configBytes),StandardCharsets.UTF_8)) {
            UnmodifiableConfig config=JsonFormat.fancyInstance().createParser().parse(reader);
            Object packageValue=config.get(List.of("package"));if(!(packageValue instanceof String pkg))return;
            for(String section:List.of("mixins","client","server")) {
                Object entries=config.get(List.of(section));if(!(entries instanceof List<?> list))continue;
                for(Object value:list)if(value instanceof String entry) {
                    byte[] bytes=resources.apply(pkg.replace('.','/')+"/"+entry.replace('.','/')+".class");if(bytes==null)continue;
                    ClassNode node=new ClassNode();new org.objectweb.asm.ClassReader(bytes).accept(node,org.objectweb.asm.ClassReader.EXPAND_FRAMES);
                    String identity=configName+":"+entry;SOURCES.put(identity,node.name.replace('/','.'));
                    if(!enabled()||explicitlyKept(identity))continue;
                    String reason=refusal(node,resources);
                    if(reason==null)continue;
                    SUPPRESSED_MIXINS.add(identity);REASONS.put(identity,reason);
                    for(String target:MixinFit.mixinTargets(node))for(String contract:MixinFit.contributedInterfaces(node)) {
                        PinnedContract row=new PinnedContract(identity,target,contract);if(!PINNED_CONTRACTS.contains(row))PINNED_CONTRACTS.add(row);
                    }
                }
            }
        }catch(Exception unreadable){net.forbric.kernel.util.ForbricLog.debug("[Forbric/Mixin] cannot inspect source protocols in %s: %s",configName,unreadable.toString());}
    }
    private static String refusal(ClassNode node,Function<String,byte[]> resources) {
        if(!net.forbric.kernel.transform.GuestInjectorPruner.enabled()&&net.forbric.kernel.transform.GuestInjectorPruner.unsafeWithoutPruning(node,name->parse(resources.apply(name+".class"))))
            return "source has a closed consuming-Reader callback pair which cannot remain half-applied while pruning is disabled";
        if(FabricRegistryInitializationMixinAdapter.conflicts(node)&&FabricRegistryInitializationMixinAdapter.adapt(copy(node))==0)
            return "source callback repeats or defers the kernel-owned registry freeze; its tracker protocol could not be adapted";
        if(FabricRegistryLoaderMixinAdapter.matches(node)&&FabricRegistryLoaderMixinAdapter.adapt(copy(node),name->parse(resources.apply(name+".class")))==0)
            return "registry-loader: source ScopedValue callback propagation does not fit the current registry-loader overloads";
        if(FabricCreativePagerMixinAdapter.matches(node)&&FabricCreativePagerMixinAdapter.adapt(copy(node),name->parse(resources.apply(name+".class")))==0)
            return "source implements a second creative pager, and its keyboard callback could not share the carrier pager";
        if(ForbricMixinService.registerSourceCallbacks(node))
            return "the complete original callback group is registered on its proved current caller/bridge contract; final definitions witness its retained bodies";
        String loot=bridgedLootDispatch(node,resources);
        if(loot!=null)return loot;
        return null;
    }
    /**
     * A source that fires the loot reload's public events itself, from the class whose reload the kernel's loot bridge
     * fires them for, and whose callback group was not proved for the generated helper (another build, a fork, a
     * recompile). The bridge then asks fabric-loot-api's public events itself, so they still fire once: a source left in
     * would fire them a second time, or half-apply on the reordered merged lambdas. Decided by what the source dispatches,
     * never by which build of which module it is; the injectors that neither dispatch nor share the dispatchers' state
     * are named in the reason, since they go with it.
     */
    private static String bridgedLootDispatch(ClassNode node,Function<String,byte[]> resources) {
        String seam=net.forbric.kernel.transform.LootTableEventBridgeInjector.TARGET_INTERNAL;
        if(!net.forbric.kernel.transform.LootTableEventBridgeInjector.enabled()||!MixinFit.mixinTargets(node).contains(seam))return null;
        Map<MethodNode,Set<String>> dispatchers=net.forbric.kernel.transform.LootTableEventBridgeInjector.dispatchers(node);
        if(dispatchers.isEmpty())return null;
        if(!net.forbric.kernel.transform.LootTableEventBridgeInjector.routable(parse(resources.apply(seam+".class"))))return null;
        Set<String> events=new TreeSet<>();for(Set<String> fired:dispatchers.values())events.addAll(fired);
        List<String> outside=net.forbric.kernel.transform.LootTableEventBridgeInjector.outsideDispatchGroup(node,dispatchers.keySet(),m->MixinFit.injectorOf(m)!=null)
                .stream().map(m->m.name).toList();
        return "source dispatches LootTableEvents "+events+" on the loot reload the kernel's loot bridge dispatches them from; its "
                +"callback group is not proved equivalent, so the bridge asks fabric-loot-api's public events once instead"
                +(outside.isEmpty()?"":"; left out with it, outside that group: "+outside);
    }
    private static ClassNode copy(ClassNode node){org.objectweb.asm.ClassWriter writer=new org.objectweb.asm.ClassWriter(0);node.accept(writer);return MixinFit.parse(writer.toByteArray());}
    private static ClassNode parse(byte[] bytes){return bytes==null?null:MixinFit.parse(bytes);}
    private static boolean explicitlyKept(String identity){for(String value:System.getProperty("forbric.keepMixins","").split(","))if(value.trim().equals(identity))return true;return false;}
    public static boolean sourceMayBeClaimed(String name){return enabled()&&SOURCES.entrySet().stream().anyMatch(entry->entry.getValue().equals(name.replace('/','.'))&&!explicitlyKept(entry.getKey()));}
    static String reason(String config,String entry){return REASONS.get(config+":"+entry);}
    public static boolean pinInForce(String pin){int colon=pin==null?-1:pin.indexOf(':');return colon>0&&ForbricMixinService.suppressedMixinsFor(pin.substring(0,colon)).contains(pin.substring(colon+1));}
    public static boolean contractSuppressed(String target,String contract){return PINNED_CONTRACTS.stream().anyMatch(row->row.target().equals(target)&&row.contract().equals(contract)&&pinInForce(row.pin()));}
    public static boolean targetSuppressed(String target){return SUPPRESSED_MIXINS.stream().anyMatch(pin->PINNED_CONTRACTS.stream().anyMatch(row->row.pin().equals(pin)&&row.target().equals(target))&&pinInForce(pin));}
    public static boolean protocolSuppressed(String protocol){return REASONS.entrySet().stream().anyMatch(entry->entry.getValue().startsWith(protocol+":")&&pinInForce(entry.getKey()));}
    static void reset(){indexed=false;SUPPRESSED_MIXINS.clear();PINNED_CONTRACTS.clear();REASONS.clear();SOURCES.clear();}
}
