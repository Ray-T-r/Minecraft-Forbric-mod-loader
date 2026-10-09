/*
 * Copyright 2026 The Forbric Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package net.forbric.kernel.transform;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BooleanSupplier;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;

import net.forbric.kernel.util.ForbricLog;

/**
 * Removes a complete source callback protocol only when the actual current bridge carries it. Source/config and
 * private handler names do not decide eligibility. The null parser redirect and direct API reader callback close
 * atomically, and their captured Reader must be the current parser's consumed SSA value. Shared tooltip callbacks
 * must delegate to one closed provider helper, share the same index, and have an actual carrier appender splice
 * and matching provider declarations. Opaque bodies remain intact. Rejected-injector pruning is a separate,
 * existing per-handler decision made after all adapters have had their opportunity.
 */
public final class GuestInjectorPruner implements ClassTransformer {
	public static final String PROPERTY = "forbric.guestInjectorPruner";

	/** {@code -Dforbric.guestInjectorPruner.refused=off}: an injector Mixin rejects outright stays in its mixin. */
	public static final String REFUSED_PROPERTY = "forbric.guestInjectorPruner.refused";

	private static final String SHARED_INDEX = "Lcom/llamalad7/mixinextras/sugar/ref/LocalIntRef;";

	/** The source API each closed protocol's callback calls: the reader-deserializer pair's model deserializer, and the
	 * component-tooltip helper's provider registry. */
	private static final String MODEL_DESERIALIZER = "net/fabricmc/fabric/api/client/model/loading/v1/UnbakedModelDeserializer";
	private static final String TOOLTIP_PROVIDERS = "net/fabricmc/fabric/impl/item/ItemComponentTooltipProviderRegistryImpl";

	/**
	 * Neither protocol matches without a method call owned by its source API, and a call's owner is a CONSTANT_Utf8
	 * entry of the class's constant pool. A class naming neither provably carries no prunable group, so it is handed
	 * back before it is parsed: the pruner runs for every class the game loads.
	 */
	private static final byte[][] PROTOCOL_OWNERS = {
			net.forbric.kernel.util.ByteScan.poolEntry(MODEL_DESERIALIZER), net.forbric.kernel.util.ByteScan.poolEntry(TOOLTIP_PROVIDERS)};

	private final java.util.concurrent.atomic.LongAdder parsedClasses = new java.util.concurrent.atomic.LongAdder();

	/** How many classes this instance parsed: the ones the constant-pool prefilter could not rule out. */
	long classesParsed() {
		return parsedClasses.sum();
	}

    private record Group(String protocol,List<MethodNode> methods,String selector) { }
    private final java.util.function.Function<String,ClassNode> classes;
    public GuestInjectorPruner(){this(net.forbric.kernel.mixin.NativeGameReferences::current);}
    public GuestInjectorPruner(java.util.function.Function<String,ClassNode> classes){this.classes=classes;}

    /** Pure source inspection, also used by the config policy when this transformer is disabled. */
    public static boolean unsafeWithoutPruning(ClassNode node){return modelPair(node)!=null;}
    public static boolean unsafeWithoutPruning(ClassNode node,java.util.function.Function<String,ClassNode> classes){Group group=modelPair(node);return group!=null&&readerWasConsumed(node,group,classes);}
    private static boolean readerWasConsumed(ClassNode node,Group group,java.util.function.Function<String,ClassNode> classes) {
        List<String> targets=net.forbric.kernel.mixin.MixinFit.mixinTargets(node);if(targets.size()!=1)return false;
        ClassNode target=classes.apply(targets.getFirst());if(target==null)return false;
        List<MethodNode> hosts=target.methods.stream().filter(method->(method.name+method.desc).equals(group.selector())||method.name.equals(group.selector())).toList();
        if(hosts.size()!=1)return false;
        int old=0;org.objectweb.asm.tree.MethodInsnNode parse=null,consumer=null;
        MethodNode host=hosts.getFirst();
        for(var instruction:host.instructions)if(instruction instanceof org.objectweb.asm.tree.MethodInsnNode call) {
            if(call.owner.equals("net/minecraft/client/resources/model/cuboid/CuboidModel")&&call.name.equals("fromStream")&&call.desc.startsWith("(Ljava/io/Reader;)"))old++;
            if(call.owner.equals("net/neoforged/neoforge/client/model/UnbakedModelParser")&&call.name.equals("parse")&&call.desc.equals("(Ljava/io/Reader;)Lnet/minecraft/client/resources/model/UnbakedModel;")) {if(parse!=null)return false;parse=call;}
            if(call.owner.equals("com/mojang/datafixers/util/Pair")&&call.name.equals("of")&&call.desc.equals("(Ljava/lang/Object;Ljava/lang/Object;)Lcom/mojang/datafixers/util/Pair;")){if(consumer!=null)return false;consumer=call;}
        }
        if(old!=0||parse==null||consumer==null||host.instructions.indexOf(parse)>=host.instructions.indexOf(consumer))return false;
        MethodNode argument=group.methods().get(1);AnnotationNode local=argument.invisibleParameterAnnotations[1].getFirst();
        Object named=net.forbric.kernel.mixin.MixinFit.value(local,"name"),index=net.forbric.kernel.mixin.MixinFit.value(local,"index");
        List<String> names=net.forbric.kernel.mixin.MixinFit.stringList(named);
        int slot=-1;
        if(index instanceof Integer explicit&&explicit>=0)slot=explicit;
        else if(!names.isEmpty()&&host.localVariables!=null)for(var declaration:host.localVariables) {
            int point=host.instructions.indexOf(consumer);
            if(!declaration.desc.equals("Ljava/io/Reader;")||!names.contains(declaration.name)
                    ||host.instructions.indexOf(declaration.start)>point||host.instructions.indexOf(declaration.end)<=point)continue;
            if(slot>=0&&slot!=declaration.index)return false;slot=declaration.index;
        }
        if(slot<0)return false;
        try {
            var frames=new org.objectweb.asm.tree.analysis.Analyzer<>(new ReaderOrigins()).analyze(target.name,host);
            var consumed=frames[host.instructions.indexOf(parse)];var at=frames[host.instructions.indexOf(consumer)];
            if(consumed==null||at==null||consumed.getStackSize()==0||slot>=at.getLocals())return false;
            var reader=consumed.getStack(consumed.getStackSize()-1);var captured=at.getLocal(slot);
            return reader.insns.size()==1&&reader.insns.equals(captured.insns);
        }catch(org.objectweb.asm.tree.analysis.AnalyzerException|RuntimeException unknown){return false;}
    }
    private static final class ReaderOrigins extends org.objectweb.asm.tree.analysis.SourceInterpreter {
        ReaderOrigins(){super(org.objectweb.asm.Opcodes.ASM9);}
        @Override public org.objectweb.asm.tree.analysis.SourceValue copyOperation(org.objectweb.asm.tree.AbstractInsnNode instruction,org.objectweb.asm.tree.analysis.SourceValue value){return value;}
        @Override public org.objectweb.asm.tree.analysis.SourceValue newParameterValue(boolean instance,int slot,org.objectweb.asm.Type type){return new org.objectweb.asm.tree.analysis.SourceValue(type.getSize(),new org.objectweb.asm.tree.VarInsnNode(type.getOpcode(org.objectweb.asm.Opcodes.ILOAD),slot));}
    }

    public static boolean wouldPrune(ClassNode node){return modelPair(node)!=null || tooltipGroup(node)!=null;}
    private static Group modelPair(ClassNode node) {
        if(node==null)return null;
        MethodNode redirect=null,argument=null;
        for(MethodNode method:node.methods) {
            AnnotationNode inject=net.forbric.kernel.mixin.MixinFit.injectorOf(method);if(inject==null)continue;
            if(allAnnotations(method).stream().anyMatch(annotation->annotation.desc.equals("Lorg/spongepowered/asm/mixin/injection/Group;")))continue;
            List<org.objectweb.asm.tree.AbstractInsnNode> body=code(method);
            if(inject.desc.equals("Lorg/spongepowered/asm/mixin/injection/Redirect;") && method.desc.equals("(Ljava/io/Reader;)Lnet/minecraft/client/resources/model/cuboid/CuboidModel;")
                    && body.size()==2&&body.get(0).getOpcode()==org.objectweb.asm.Opcodes.ACONST_NULL&&body.get(1).getOpcode()==org.objectweb.asm.Opcodes.ARETURN
                    && point(inject,"Lnet/minecraft/client/resources/model/cuboid/CuboidModel;fromStream(Ljava/io/Reader;)Lnet/minecraft/client/resources/model/cuboid/CuboidModel;")) {
                if(redirect!=null)return null;redirect=method;
            }
            if(inject.desc.equals("Lorg/spongepowered/asm/mixin/injection/ModifyArg;")&&method.desc.equals("(Ljava/lang/Object;Ljava/io/Reader;)Ljava/lang/Object;")
                    &&body.size()==3&&body.get(0) instanceof org.objectweb.asm.tree.VarInsnNode reader&&reader.getOpcode()==org.objectweb.asm.Opcodes.ALOAD&&reader.var==1
                    &&body.get(1) instanceof org.objectweb.asm.tree.MethodInsnNode parse&&parse.getOpcode()==org.objectweb.asm.Opcodes.INVOKESTATIC
                    &&parse.owner.equals(MODEL_DESERIALIZER)&&parse.name.equals("deserialize")
                    &&parse.desc.equals("(Ljava/io/Reader;)Lnet/minecraft/client/resources/model/UnbakedModel;")&&body.get(2).getOpcode()==org.objectweb.asm.Opcodes.ARETURN
                    &&Integer.valueOf(1).equals(net.forbric.kernel.mixin.MixinFit.value(inject,"index"))
                    &&point(inject,"Lcom/mojang/datafixers/util/Pair;of(Ljava/lang/Object;Ljava/lang/Object;)Lcom/mojang/datafixers/util/Pair;")) {
                if(argument!=null)return null;argument=method;
            }
        }
        if(redirect==null||argument==null||!redirect.tryCatchBlocks.isEmpty()||!argument.tryCatchBlocks.isEmpty())return null;
        var selectors=net.forbric.kernel.mixin.MixinFit.stringList(net.forbric.kernel.mixin.MixinFit.value(net.forbric.kernel.mixin.MixinFit.injectorOf(redirect),"method"));
        var other=net.forbric.kernel.mixin.MixinFit.stringList(net.forbric.kernel.mixin.MixinFit.value(net.forbric.kernel.mixin.MixinFit.injectorOf(argument),"method"));
        if(selectors.size()!=1||!selectors.equals(other)||argument.invisibleParameterAnnotations==null||argument.invisibleParameterAnnotations.length!=2
                ||argument.invisibleParameterAnnotations[1]==null||argument.invisibleParameterAnnotations[1].size()!=1
                ||!argument.invisibleParameterAnnotations[1].getFirst().desc.equals("Lcom/llamalad7/mixinextras/sugar/Local;"))return null;
        return new Group("reader-deserializer",List.of(redirect,argument),selectors.getFirst());
    }
    private static boolean point(AnnotationNode injector,String target) {
        if(net.forbric.kernel.mixin.MixinFit.value(injector,"slice")!=null)return false;
        var ats=net.forbric.kernel.mixin.MixinFit.atNodes(injector);
        return ats.size()==1&&"INVOKE".equals(net.forbric.kernel.mixin.MixinFit.value(ats.getFirst(),"value"))
                &&target.equals(net.forbric.kernel.mixin.MixinFit.value(ats.getFirst(),"target"))
                &&net.forbric.kernel.mixin.MixinFit.value(ats.getFirst(),"shift")==null
                &&net.forbric.kernel.mixin.MixinFit.value(ats.getFirst(),"by")==null
                &&net.forbric.kernel.mixin.MixinFit.value(ats.getFirst(),"args")==null;
    }
    private static List<org.objectweb.asm.tree.AbstractInsnNode> code(MethodNode method){return java.util.Arrays.stream(method.instructions.toArray()).filter(i->i.getOpcode()>=0).toList();}
    private static Group tooltipGroup(ClassNode node) {
        if(node==null)return null;
        String protocol=TOOLTIP_PROVIDERS;
        MethodNode helper=null;
        for(MethodNode method:node.methods) {
            if(net.forbric.kernel.mixin.MixinFit.injectorOf(method)!=null||!method.desc.endsWith(SHARED_INDEX+")V"))continue;
            Set<String> dispatch=new java.util.HashSet<>();boolean closed=true;
            for(var instruction:method.instructions) {
                if(instruction instanceof org.objectweb.asm.tree.FieldInsnNode)closed=false;
                if(instruction instanceof org.objectweb.asm.tree.MethodInsnNode call) {
                    if(call.owner.equals(protocol)&&Set.of("hasModdedEntries","onFirst","onLast","onBefore","onAfter").contains(call.name))dispatch.add(call.name);
                    else if(!(call.owner.equals("net/fabricmc/fabric/impl/item/VanillaTooltipProviderOrder")&&call.name.equals("getVanillaOrder")
                            ||call.owner.equals("com/llamalad7/mixinextras/sugar/ref/LocalIntRef")&&Set.of("get","set").contains(call.name)
                            ||call.owner.equals("java/util/List")&&Set.of("size","get").contains(call.name)
                            ||call.owner.equals("java/util/HashSet")&&Set.of("<init>","add").contains(call.name)))closed=false;
                }
            }
            if(closed&&dispatch.equals(Set.of("hasModdedEntries","onFirst","onLast","onBefore","onAfter"))) {
                if(helper!=null)return null;helper=method;
            }
        }
        if(helper==null)return null;
        List<MethodNode> group=new ArrayList<>();String selector=null,share=null;
        for(MethodNode method:node.methods) {
            AnnotationNode injector=net.forbric.kernel.mixin.MixinFit.injectorOf(method);if(injector==null)continue;
            int calls=0;boolean closed=!method.tryCatchBlocks.isEmpty()?false:true;
            for(var instruction:method.instructions)if(instruction.getOpcode()>=0) {
                if(instruction instanceof org.objectweb.asm.tree.MethodInsnNode call) {
                    if(call.owner.equals(node.name)&&call.name.equals(helper.name)&&call.desc.equals(helper.desc))calls++;
                    else closed=false;
                }else if(instruction instanceof org.objectweb.asm.tree.FieldInsnNode field) {
                    if(field.getOpcode()!=org.objectweb.asm.Opcodes.GETSTATIC||!field.desc.equals("Lnet/minecraft/core/component/DataComponentType;"))closed=false;
                }else if(!(instruction instanceof org.objectweb.asm.tree.VarInsnNode||instruction instanceof org.objectweb.asm.tree.JumpInsnNode
                        ||instruction.getOpcode()==org.objectweb.asm.Opcodes.ACONST_NULL||instruction.getOpcode()==org.objectweb.asm.Opcodes.RETURN
                        ||instruction.getOpcode()==org.objectweb.asm.Opcodes.ARETURN||instruction.getOpcode()==org.objectweb.asm.Opcodes.IRETURN))closed=false;
            }
            if(calls==0)continue;
            if(calls!=1||!closed||!method.desc.contains(SHARED_INDEX)||allAnnotations(method).stream().anyMatch(annotation->annotation.desc.equals("Lorg/spongepowered/asm/mixin/injection/Group;")))return null;
            org.objectweb.asm.Type[] parameters=org.objectweb.asm.Type.getArgumentTypes(method.desc);
            int shared=-1;for(int p=0;p<parameters.length;p++)if(parameters[p].getDescriptor().equals(SHARED_INDEX)){if(shared>=0)return null;shared=p;}
            if(shared<0||method.invisibleParameterAnnotations==null||shared>=method.invisibleParameterAnnotations.length
                    ||method.invisibleParameterAnnotations[shared]==null||method.invisibleParameterAnnotations[shared].size()!=1)return null;
            AnnotationNode sugar=method.invisibleParameterAnnotations[shared].getFirst();
            Object sharedValue=net.forbric.kernel.mixin.MixinFit.value(sugar,"value");
            if(!sugar.desc.equals("Lcom/llamalad7/mixinextras/sugar/Share;")||!(sharedValue instanceof String id)||share!=null&&!share.equals(id))return null;
            share=id;
            var selectors=net.forbric.kernel.mixin.MixinFit.stringList(net.forbric.kernel.mixin.MixinFit.value(injector,"method"));
            if(selectors.size()!=1||selector!=null&&!selector.equals(selectors.getFirst()))return null;
            selector=selectors.getFirst();group.add(method);
        }
        return group.isEmpty()?null:new Group("component-tooltip",List.copyOf(group),selector);
    }

    private static boolean providerDeclarations(ClassNode providers) {
        String ends="(Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/world/item/Item$TooltipContext;Lnet/minecraft/world/item/component/TooltipDisplay;Ljava/util/function/Consumer;Lnet/minecraft/world/item/TooltipFlag;)V";
        String sides="(Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/core/component/DataComponentType;Lnet/minecraft/world/item/Item$TooltipContext;Lnet/minecraft/world/item/component/TooltipDisplay;Ljava/util/function/Consumer;Lnet/minecraft/world/item/TooltipFlag;Ljava/util/Set;)V";
        for(String name:List.of("hasModdedEntries","onFirst","onLast","onBefore","onAfter")) {
            String desc=name.equals("hasModdedEntries")?"()Z":name.equals("onFirst")||name.equals("onLast")?ends:sides;
            if(providers.methods.stream().noneMatch(method->method.name.equals(name)&&method.desc.equals(desc)
                    &&(method.access&(org.objectweb.asm.Opcodes.ACC_STATIC|org.objectweb.asm.Opcodes.ACC_PUBLIC))==(org.objectweb.asm.Opcodes.ACC_STATIC|org.objectweb.asm.Opcodes.ACC_PUBLIC)))return false;
        }
        return true;
    }

	private static volatile boolean fabricTooltipsPruned;

	/**
	 * fabric-item-api's tooltip injectors go only while the kernel draws Fabric's providers from NeoForge's appenders:
	 * NeoForge's appenders built, and the bridge on.
	 */
	public static boolean fabricTooltipBridgeOn() {
		return !"off".equalsIgnoreCase(System.getProperty("forbric.neoTooltipAppenders", "on"))
				&& !"off".equalsIgnoreCase(System.getProperty(FABRIC_TOOLTIP_BRIDGE, "on"));
	}

	/** {@code -Dforbric.fabricTooltipBridge=off} leaves fabric-item-api's tooltip injectors where they were. */
	public static final String FABRIC_TOOLTIP_BRIDGE = "forbric.fabricTooltipBridge";

	/** Whether fabric-item-api's five tooltip injectors were removed on this boot — the bridge draws only then. */
	public static boolean fabricTooltipInjectorsPruned() {
		return fabricTooltipsPruned;
	}

	/** Every annotation that makes a mixin method an injector: Mixin's own and MixinExtras'. */
	static final Set<String> INJECTOR_DESCS = Set.of(
			"Lorg/spongepowered/asm/mixin/injection/Inject;",
			"Lorg/spongepowered/asm/mixin/injection/Redirect;",
			"Lorg/spongepowered/asm/mixin/injection/ModifyArg;",
			"Lorg/spongepowered/asm/mixin/injection/ModifyArgs;",
			"Lorg/spongepowered/asm/mixin/injection/ModifyVariable;",
			"Lorg/spongepowered/asm/mixin/injection/ModifyConstant;",
			"Lcom/llamalad7/mixinextras/injector/ModifyReturnValue;",
			"Lcom/llamalad7/mixinextras/injector/ModifyExpressionValue;",
			"Lcom/llamalad7/mixinextras/injector/wrapoperation/WrapOperation;",
			"Lcom/llamalad7/mixinextras/injector/v2/WrapWithCondition;",
			"Lcom/llamalad7/mixinextras/injector/WrapWithCondition;",
			"Lcom/llamalad7/mixinextras/injector/wrapmethod/WrapMethod;");

	private int pruned;

	public static boolean enabled() {
		return !"off".equalsIgnoreCase(System.getProperty(PROPERTY, "on"));
	}

	@Override
	public String name() {
		return "forbric:guest-injector-pruner";
	}

	@Override
    public AnchorSet anchors(){return AnchorSet.scanned("closed Reader and shared component-tooltip callback protocols");}

    @Override public byte[] transform(String className,byte[] classBytes,TransformContext context) {
        if(classBytes==null||classBytes.length==0||!enabled())return classBytes;
        if(!net.forbric.kernel.util.ByteScan.namesAny(classBytes,PROTOCOL_OWNERS))return classBytes;
        ClassNode node=new ClassNode();new ClassReader(classBytes).accept(node,0);parsedClasses.increment();
        if(!node.name.replace('/','.').equals(className))return classBytes;
        Group group=modelPair(node);
        if(group!=null&&!readerWasConsumed(node,group,classes))return classBytes;
        if(group==null && fabricTooltipBridgeOn())group=tooltipGroup(node);
        if(group==null)return classBytes;
        if(group.protocol().equals("component-tooltip")) {
            ClassNode current=classes.apply("net/neoforged/neoforge/common/tooltip/ItemTooltipHandler");
            ClassNode providers=classes.apply(TOOLTIP_PROVIDERS);
            if(current==null||providers==null||!providerDeclarations(providers)||!NeoTooltipAppendersInjector.aroundSpliced()) {
                ForbricLog.warn("[Forbric/GuestInjectorPruner] retained %s's original tooltip callbacks: the actual carrier appender replacement has not been proved",node.name);
                return classBytes;
            }
        }
        node.methods.removeAll(group.methods());pruned+=group.methods().size();
        if(group.protocol().equals("component-tooltip"))fabricTooltipsPruned=true;
        String loss=group.protocol().equals("reader-deserializer")&&!ModelFormatFunnelInjector.enabled()
                ?"the original fabric:type custom model deserializer callback was removed while the model-format funnel is disabled":null;
        String config=net.forbric.kernel.mixin.MixinStubRebind.configOf(node.name);
        for(MethodNode victim:loss==null?List.<MethodNode>of():group.methods())
            net.forbric.kernel.mixin.MixinCompatibility.recordRemovedInjector(config,node.name.replace('/','.'),victim.name,victim.desc,loss,
                    List.of("closed source protocol="+group.protocol(),"source=GuestInjectorPruner"));
        ForbricLog.info("[Forbric/GuestInjectorPruner] removed the %d closed %s callback(s) in %s; remaining callbacks retain their original bodies",group.methods().size(),group.protocol(),node.name);
        ClassWriter writer=new ClassWriter(0);node.accept(writer);return writer.toByteArray();
    }

	/** Whether {@code m} carries an injector annotation whose {@code method} list has a selector starting with {@code prefix}. */
	static boolean isInjectorInto(MethodNode m, String prefix) {
		for (AnnotationNode a : allAnnotations(m)) {
			if (!INJECTOR_DESCS.contains(a.desc)) continue;
			List<Object> values = a.values;
			if (values == null) continue;
			for (int i = 0; i + 1 < values.size(); i += 2) {
				if (!"method".equals(values.get(i))) continue;
				Object v = values.get(i + 1);
				if (v instanceof List<?> list) {
					for (Object s : list) if (s instanceof String str && str.startsWith(prefix)) return true;
				} else if (v instanceof String str && str.startsWith(prefix)) {
					return true;
				}
			}
		}
		return false;
	}

	/**
	 * Whether {@code m} carries an injector annotation whose {@code method} list is exactly {@code selector}, and a
	 * {@code @Share} parameter — the shape of fabric-item-api's five, which only move together.
	 */
	static boolean isInjectorExactlyInto(MethodNode m, String selector) {
		boolean shared = false;
		for (List<AnnotationNode> parameter : m.invisibleParameterAnnotations == null ? new List[0] : m.invisibleParameterAnnotations) {
			if (parameter != null) for (AnnotationNode a : parameter) shared |= "Lcom/llamalad7/mixinextras/sugar/Share;".equals(a.desc);
		}
		for (List<AnnotationNode> parameter : m.visibleParameterAnnotations == null ? new List[0] : m.visibleParameterAnnotations) {
			if (parameter != null) for (AnnotationNode a : parameter) shared |= "Lcom/llamalad7/mixinextras/sugar/Share;".equals(a.desc);
		}
		if (!shared) return false;
		for (AnnotationNode a : allAnnotations(m)) {
			if (!INJECTOR_DESCS.contains(a.desc) || a.values == null) continue;
			for (int i = 0; i + 1 < a.values.size(); i += 2) {
				if (!"method".equals(a.values.get(i))) continue;
				Object v = a.values.get(i + 1);
				if (v instanceof List<?> list) return list.size() == 1 && selector.equals(list.get(0));
				return selector.equals(v);
			}
		}
		return false;
	}

	private static List<AnnotationNode> allAnnotations(MethodNode m) {
		List<AnnotationNode> out = new ArrayList<>();
		if (m.visibleAnnotations != null) out.addAll(m.visibleAnnotations);
		if (m.invisibleAnnotations != null) out.addAll(m.invisibleAnnotations);
		return out;
	}

	private static int countInjectors(ClassNode node) {
		int n = 0;
		for (MethodNode m : node.methods) {
			for (AnnotationNode a : allAnnotations(m)) {
				if (INJECTOR_DESCS.contains(a.desc)) { n++; break; }
			}
		}
		return n;
	}

	/** How many injector methods were removed, for the boot summary. */
	public int prunedInjectors() {
		return pruned;
	}

	// -----------------------------------------------------------------------------------------------------------------
	// Injectors Mixin rejects outright

	/**
	 * An {@code @Inject} the adapter found Mixin would reject: in {@code config}, the mixin {@code mixin} (internal name),
	 * the handler {@code name}{@code desc}, the refused binding, and whether its loss is required -- the author's own count
	 * for it ({@code require}, else the config's {@code defaultRequire}) is at least one, as FinalMixinApplications judges
	 * an injector that did not attach.
	 */
	public record Refused(String config, String mixin, String name, String desc, String reason, boolean required) {
	}

	/** Mixin (internal name) → the injectors to take out of it. */
	private static final Map<String, List<Refused>> REFUSED = new java.util.concurrent.ConcurrentHashMap<>();
	/** What was taken out already, so a mixin Mixin reads twice is logged once. */
	private static final Set<String> REFUSED_DONE = java.util.concurrent.ConcurrentHashMap.newKeySet();

	/** Whether injectors Mixin rejects outright are taken out: this kind's switch, and the pruner's own. */
	public static boolean refusedEnabled() {
		return enabled() && !"off".equalsIgnoreCase(System.getProperty(REFUSED_PROPERTY, "on"));
	}

	/**
	 * Whether {@code name}{@code desc} in {@code mixin} can go on its own: an injector method, in no {@code @Group} (whose
	 * count would then be the group's to fail), and nothing else in the mixin calls it or takes a handle to it.
	 */
	public static boolean prunable(ClassNode mixin, String name, String desc) {
		MethodNode handler = null;
		for (MethodNode m : mixin.methods) if (m.name.equals(name) && m.desc.equals(desc)) handler = m;
		if (handler == null || countInjectors(handler) != 1) return false;
		for (AnnotationNode a : allAnnotations(handler)) if ("Lorg/spongepowered/asm/mixin/injection/Group;".equals(a.desc)) return false;
		for (MethodNode m : mixin.methods) {
			if (m == handler || m.instructions == null) continue;
			for (org.objectweb.asm.tree.AbstractInsnNode insn : m.instructions) {
				if (insn instanceof org.objectweb.asm.tree.MethodInsnNode call && call.owner.equals(mixin.name)
						&& call.name.equals(name) && call.desc.equals(desc)) return false;
				if (insn instanceof org.objectweb.asm.tree.InvokeDynamicInsnNode indy) {
					for (Object argument : indy.bsmArgs) {
						if (argument instanceof org.objectweb.asm.Handle h && h.getOwner().equals(mixin.name)
								&& h.getName().equals(name) && h.getDesc().equals(desc)) return false;
					}
				}
			}
		}
		return true;
	}

	/**
	 * {@code mixinBytes} without {@code name}{@code desc} and the {@code @Surrogate}s of that name, which stand in only
	 * for it: what Mixin will receive once {@link #pruneRefused} has run, for the verdict to judge.
	 */
	public static byte[] without(byte[] mixinBytes, List<String> handlers) {
		ClassNode node = new ClassNode();
		new ClassReader(mixinBytes).accept(node, 0);
		for (String handler : handlers) {
			int paren = handler.indexOf('(');
			remove(node, handler.substring(0, paren), handler.substring(paren));
		}
		ClassWriter writer = new ClassWriter(0);
		node.accept(writer);
		return writer.toByteArray();
	}

	/** Remembers {@code refused} for {@link #pruneRefused}. */
	public static void rememberRefused(Refused refused) {
		REFUSED.computeIfAbsent(refused.mixin(), k -> new java.util.concurrent.CopyOnWriteArrayList<>()).add(refused);
	}

	/**
	 * Takes the remembered injectors out of {@code node}, the mixin as Mixin is about to receive it, each only while
	 * {@code stillRejected} -- the verdict's rule asked of this node -- still names a refused binding; one an adapter
	 * already moved where it fits stays. Each one taken out is a confirmed finding.
	 *
	 * @return how many were taken out
	 */
	public static int pruneRefused(ClassNode node, java.util.function.BiFunction<ClassNode, MethodNode, String> stillRejected) {
		List<Refused> entries = node == null || node.methods == null ? null : REFUSED.get(node.name);
		if (entries == null || !refusedEnabled()) return 0;
		int removed = 0;
		for (Refused entry : entries) {
			MethodNode handler = null;
			for (MethodNode m : node.methods) if (m.name.equals(entry.name()) && m.desc.equals(entry.desc())) handler = m;
			if (handler == null) continue;
			String why = stillRejected.apply(node, handler);
			String key = node.name + "." + entry.name() + entry.desc();
			if (why == null) {
				if (REFUSED_DONE.add(key + "?")) {
					ForbricLog.info("[Forbric/GuestInjectorPruner] %s.%s is no longer rejected once the mixin adapters ran; "
							+ "left in place", node.name.replace('/', '.'), entry.name());
				}
				continue;
			}
			remove(node, entry.name(), entry.desc());
			removed++;
			String mixin = node.name.replace('/', '.');
			net.forbric.kernel.mixin.MixinCompatibility.recordRemovedInjector(entry.config(), mixin, entry.name(), entry.desc(),
					"the kernel removed injector " + entry.name() + " before Mixin read it: " + why + ". Mixin would have "
							+ "rejected it (\"Invalid descriptor\") and failed the mixin with it; the rest of the mixin applies",
					entry.required(),
					List.of("kernel pruned " + entry.name() + entry.desc() + " from " + mixin, "refused binding: " + why,
							"source=GuestInjectorPruner (an injector Mixin rejects outright)",
							"-D" + REFUSED_PROPERTY + "=off keeps it, and Mixin rejects the mixin"));
			if (REFUSED_DONE.add(key)) {
				ForbricLog.info("[Forbric/GuestInjectorPruner] pruned %s.%s — %s; Mixin would have rejected it and failed the "
						+ "mixin with it, so the other %d injector(s) apply as written", mixin, entry.name(), why, countInjectors(node));
			}
		}
		return removed;
	}

	/** Test seam: forget every remembered injector. */
	public static void forgetRefused() {
		REFUSED.clear();
		REFUSED_DONE.clear();
	}

	/** Removes {@code name}{@code desc} and the {@code @Surrogate}s of that name from {@code node}. */
	private static void remove(ClassNode node, String name, String desc) {
		node.methods.removeIf(m -> m.name.equals(name) && (m.desc.equals(desc) || allAnnotations(m).stream()
				.anyMatch(a -> "Lorg/spongepowered/asm/mixin/injection/Surrogate;".equals(a.desc))));
	}

	/** How many injector annotations {@code m} carries. */
	private static int countInjectors(MethodNode m) {
		int n = 0;
		for (AnnotationNode a : allAnnotations(m)) if (INJECTOR_DESCS.contains(a.desc)) n++;
		return n;
	}
}
