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

import net.forbric.kernel.util.Caught;
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

	/** The public API the reader-deserializer pair's callback calls: fabric-model-loading's model deserializer. */
	private static final String MODEL_DESERIALIZER = "net/fabricmc/fabric/api/client/model/loading/v1/UnbakedModelDeserializer";

	/**
	 * Neither protocol matches without a CONSTANT_Utf8 entry its shape needs: the reader pair calls its public model
	 * deserializer, and the component-tooltip group shares its index through MixinExtras' LocalIntRef. A class naming
	 * neither provably carries no prunable group, so it is handed back before it is parsed: the pruner runs for every
	 * class the game loads.
	 */
	private static final byte[][] PROTOCOL_OWNERS = {
			net.forbric.kernel.util.ByteScan.poolEntry(MODEL_DESERIALIZER), net.forbric.kernel.util.ByteScan.poolEntry(TooltipDispatchRoles.INDEX)};

	private final java.util.concurrent.atomic.LongAdder parsedClasses = new java.util.concurrent.atomic.LongAdder();

	/** How many classes this instance parsed: the ones the constant-pool prefilter could not rule out. */
	long classesParsed() {
		return parsedClasses.sum();
	}

    private record Group(String protocol,List<MethodNode> methods) { }
    /** The two points of the reader-deserializer protocol: the old parser call it drops, and the pair that takes the Reader. */
    private static final String FROM_STREAM="Lnet/minecraft/client/resources/model/cuboid/CuboidModel;fromStream(Ljava/io/Reader;)Lnet/minecraft/client/resources/model/cuboid/CuboidModel;";
    private static final String PAIR_OF="Lcom/mojang/datafixers/util/Pair;of(Ljava/lang/Object;Ljava/lang/Object;)Lcom/mojang/datafixers/util/Pair;";
    private final java.util.function.Function<String,ClassNode> classes;
    /** (mixin, owner) → the class the mixin's mod was compiled against: what a point spelled without its owner or descriptor names. */
    private final java.util.function.BiFunction<String,String,ClassNode> natives;
    public GuestInjectorPruner(){this(net.forbric.kernel.mixin.NativeGameReferences::current);}
    public GuestInjectorPruner(java.util.function.Function<String,ClassNode> classes){this(classes,net.forbric.kernel.mixin.MixinBindings::nativeClass);}
    public GuestInjectorPruner(java.util.function.Function<String,ClassNode> classes,java.util.function.BiFunction<String,String,ClassNode> natives){this.classes=classes;this.natives=natives;}

    /** The config policy's question when this transformer is disabled: whether the mixin carries the closed pair, proved on its target. */
    public static boolean unsafeWithoutPruning(ClassNode node,java.util.function.Function<String,ClassNode> classes){return unsafeWithoutPruning(node,classes,owner->net.forbric.kernel.mixin.MixinBindings.nativeClass(node.name,owner));}
    /**
     * {@link #unsafeWithoutPruning(ClassNode, java.util.function.Function)}, a point written without its owner or descriptor
     * read in {@code natives}' class (the class the mod was compiled against, by internal name): the gate reads the pair as
     * the pruner it stands in for does.
     */
    public static boolean unsafeWithoutPruning(ClassNode node,java.util.function.Function<String,ClassNode> classes,java.util.function.Function<String,ClassNode> natives){Group group=modelPair(node);return group!=null&&readerWasConsumed(node,group,classes,natives==null?null:(mixin,owner)->natives.apply(owner));}
    /**
     * The pair's host is the one method both callbacks bind in the target, as Mixin binds them, however each selector is
     * written. Its Pair.of point is read there, where Mixin injects; its fromStream point must select nothing there, and
     * name the old parser call: spelled out, or read in the method it was written for, in the class the mod was compiled
     * against.
     */
    private static boolean readerWasConsumed(ClassNode node,Group group,java.util.function.Function<String,ClassNode> classes,
            java.util.function.BiFunction<String,String,ClassNode> natives) {
        List<String> targets=net.forbric.kernel.mixin.MixinFit.mixinTargets(node);if(targets.size()!=1)return false;
        ClassNode target=classes.apply(targets.getFirst());if(target==null)return false;
        MethodNode redirect=group.methods().get(0),argument=group.methods().get(1);
        if(!net.forbric.kernel.mixin.MixinBindings.sameHost(redirect,argument,target))return false;
        MethodNode host=net.forbric.kernel.mixin.MixinBindings.bound(redirect,target);
        AnnotationNode dropped=onlyAt(redirect),paired=onlyAt(argument);
        if(!net.forbric.kernel.mixin.MixinBindings.names(paired,PAIR_OF,host))return false;
        if(net.forbric.kernel.mixin.MixinBindings.selected(dropped,host).stream().anyMatch(org.objectweb.asm.tree.MethodInsnNode.class::isInstance))return false;
        if(!net.forbric.kernel.mixin.MixinBindings.names(dropped,FROM_STREAM,null)) {
            ClassNode nativeClass=natives==null?null:natives.apply(node.name,target.name);
            if(!net.forbric.kernel.mixin.MixinBindings.names(dropped,FROM_STREAM,net.forbric.kernel.mixin.MixinBindings.bound(redirect,nativeClass)))return false;
        }
        int old=0;org.objectweb.asm.tree.MethodInsnNode parse=null,consumer=null;
        for(var instruction:host.instructions)if(instruction instanceof org.objectweb.asm.tree.MethodInsnNode call) {
            if(call.owner.equals("net/minecraft/client/resources/model/cuboid/CuboidModel")&&call.name.equals("fromStream")&&call.desc.startsWith("(Ljava/io/Reader;)"))old++;
            if(call.owner.equals("net/neoforged/neoforge/client/model/UnbakedModelParser")&&call.name.equals("parse")&&call.desc.equals("(Ljava/io/Reader;)Lnet/minecraft/client/resources/model/UnbakedModel;")) {if(parse!=null)return false;parse=call;}
            if(call.owner.equals("com/mojang/datafixers/util/Pair")&&call.name.equals("of")&&call.desc.equals("(Ljava/lang/Object;Ljava/lang/Object;)Lcom/mojang/datafixers/util/Pair;")){if(consumer!=null)return false;consumer=call;}
        }
        if(old!=0||parse==null||consumer==null||host.instructions.indexOf(parse)>=host.instructions.indexOf(consumer))return false;
        AnnotationNode local=argument.invisibleParameterAnnotations[1].getFirst();
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
        }catch(org.objectweb.asm.tree.analysis.AnalyzerException|RuntimeException unknown){Caught.expected(unknown);return false;}
    }
    private static final class ReaderOrigins extends org.objectweb.asm.tree.analysis.SourceInterpreter {
        ReaderOrigins(){super(org.objectweb.asm.Opcodes.ASM9);}
        @Override public org.objectweb.asm.tree.analysis.SourceValue copyOperation(org.objectweb.asm.tree.AbstractInsnNode instruction,org.objectweb.asm.tree.analysis.SourceValue value){return value;}
        @Override public org.objectweb.asm.tree.analysis.SourceValue newParameterValue(boolean instance,int slot,org.objectweb.asm.Type type){return new org.objectweb.asm.tree.analysis.SourceValue(type.getSize(),new org.objectweb.asm.tree.VarInsnNode(type.getOpcode(org.objectweb.asm.Opcodes.ILOAD),slot));}
    }

    /**
     * The reader-deserializer pair by its shape alone: a redirect that drops the old parser's model and an argument
     * modifier handing the captured Reader to the model deserializer, each pointed (however the point is spelled) at its
     * protocol call. Whether they share a host, and what a point without its owner or descriptor names, takes the target:
     * {@link #readerWasConsumed}.
     */
    private static Group modelPair(ClassNode node) {
        if(node==null)return null;
        MethodNode redirect=null,argument=null;
        for(MethodNode method:node.methods) {
            AnnotationNode inject=net.forbric.kernel.mixin.MixinFit.injectorOf(method);if(inject==null)continue;
            if(allAnnotations(method).stream().anyMatch(annotation->annotation.desc.equals("Lorg/spongepowered/asm/mixin/injection/Group;")))continue;
            if(inject.desc.equals("Lorg/spongepowered/asm/mixin/injection/Redirect;") && method.desc.equals("(Ljava/io/Reader;)Lnet/minecraft/client/resources/model/cuboid/CuboidModel;")
                    && BodyFlow.returnsOnlyNull(node.name,method) && point(inject,FROM_STREAM)) {
                if(redirect!=null)return null;redirect=method;
            }
            if(inject.desc.equals("Lorg/spongepowered/asm/mixin/injection/ModifyArg;")&&method.desc.equals("(Ljava/lang/Object;Ljava/io/Reader;)Ljava/lang/Object;")
                    &&BodyFlow.returnsOnlyCallOf(node.name,method,MODEL_DESERIALIZER,"deserialize","(Ljava/io/Reader;)Lnet/minecraft/client/resources/model/UnbakedModel;",1)
                    &&Integer.valueOf(1).equals(net.forbric.kernel.mixin.MixinFit.value(inject,"index"))
                    &&point(inject,PAIR_OF)) {
                if(argument!=null)return null;argument=method;
            }
        }
        if(redirect==null||argument==null||!redirect.tryCatchBlocks.isEmpty()||!argument.tryCatchBlocks.isEmpty())return null;
        if(argument.invisibleParameterAnnotations==null||argument.invisibleParameterAnnotations.length!=2
                ||argument.invisibleParameterAnnotations[1]==null||argument.invisibleParameterAnnotations[1].size()!=1
                ||!argument.invisibleParameterAnnotations[1].getFirst().desc.equals("Lcom/llamalad7/mixinextras/sugar/Local;"))return null;
        return new Group("reader-deserializer",List.of(redirect,argument));
    }
    /**
     * What a callback body does, read by its data flow rather than by its instruction count: so the same callback written
     * another way (through a local, with a cast, split over branches) is the same callback, and one that also does anything
     * else is not.
     */
    static final class BodyFlow {
        private BodyFlow(){}
        /** Every return yields the null constant, and the body calls nothing and writes no field or array. */
        static boolean returnsOnlyNull(String owner,MethodNode method){
            var frames=frames(owner,method);if(frames==null||!sideEffectFree(method,null))return false;
            boolean returns=false;
            for(int i=0;i<method.instructions.size();i++){
                var instruction=method.instructions.get(i);
                if(instruction.getOpcode()!=org.objectweb.asm.Opcodes.ARETURN)continue;returns=true;
                var frame=frames[i];if(frame==null)continue;
                for(var producer:origins(method,frames,frame.getStack(frame.getStackSize()-1)))
                    if(producer.getOpcode()!=org.objectweb.asm.Opcodes.ACONST_NULL)return false;
            }
            return returns;
        }
        /**
         * Every return yields the result of one static call of {@code owner.name desc} whose only argument is the method's
         * parameter {@code parameter} untouched, and the body calls nothing else and writes no field or array.
         */
        static boolean returnsOnlyCallOf(String mixin,MethodNode method,String owner,String name,String desc,int parameter){
            var frames=frames(mixin,method);
            java.util.function.Predicate<org.objectweb.asm.tree.MethodInsnNode> allowed=call->call.getOpcode()==org.objectweb.asm.Opcodes.INVOKESTATIC
                    &&call.owner.equals(owner)&&call.name.equals(name)&&call.desc.equals(desc);
            if(frames==null||!sideEffectFree(method,allowed))return false;
            boolean returns=false;
            for(int i=0;i<method.instructions.size();i++){
                var instruction=method.instructions.get(i);
                if(instruction.getOpcode()!=org.objectweb.asm.Opcodes.ARETURN)continue;returns=true;
                var frame=frames[i];if(frame==null)continue;
                for(var producer:origins(method,frames,frame.getStack(frame.getStackSize()-1))){
                    if(!(producer instanceof org.objectweb.asm.tree.MethodInsnNode call)||!allowed.test(call))return false;
                    var at=frames[method.instructions.indexOf(call)];if(at==null)return false;
                    for(var argument:origins(method,frames,at.getStack(at.getStackSize()-1)))
                        if(!(argument instanceof Parameter p)||p.index!=parameter)return false;
                }
            }
            return returns;
        }
        /** No call but the {@code allowed} ones (none when null), no field, array or monitor write, no throw. */
        private static boolean sideEffectFree(MethodNode method,java.util.function.Predicate<org.objectweb.asm.tree.MethodInsnNode> allowed){
            if(!method.tryCatchBlocks.isEmpty())return false;
            for(var instruction:method.instructions){
                int opcode=instruction.getOpcode();
                if(instruction instanceof org.objectweb.asm.tree.MethodInsnNode call){if(allowed==null||!allowed.test(call))return false;}
                else if(instruction instanceof org.objectweb.asm.tree.InvokeDynamicInsnNode)return false;
                else if(opcode==org.objectweb.asm.Opcodes.PUTFIELD||opcode==org.objectweb.asm.Opcodes.PUTSTATIC||opcode>=org.objectweb.asm.Opcodes.IASTORE&&opcode<=org.objectweb.asm.Opcodes.SASTORE
                        ||opcode==org.objectweb.asm.Opcodes.MONITORENTER||opcode==org.objectweb.asm.Opcodes.MONITOREXIT||opcode==org.objectweb.asm.Opcodes.ATHROW)return false;
            }
            return true;
        }
        /** The instructions that made a value, past local stores and loads, stack copies and casts; a parameter is a {@link Parameter}. */
        private static java.util.Set<org.objectweb.asm.tree.AbstractInsnNode> origins(MethodNode method,
                org.objectweb.asm.tree.analysis.Frame<org.objectweb.asm.tree.analysis.SourceValue>[] frames,org.objectweb.asm.tree.analysis.SourceValue value){
            java.util.Set<org.objectweb.asm.tree.AbstractInsnNode> out=java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
            java.util.Set<org.objectweb.asm.tree.AbstractInsnNode> seen=java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
            java.util.ArrayDeque<org.objectweb.asm.tree.AbstractInsnNode> pending=new java.util.ArrayDeque<>(value.insns);
            while(!pending.isEmpty()){
                var producer=pending.pop();if(!seen.add(producer))continue;
                int opcode=producer.getOpcode(),index=method.instructions.indexOf(producer);
                var frame=index<0?null:frames[index];
                if(producer instanceof Parameter){out.add(producer);continue;}
                boolean copy=opcode>=org.objectweb.asm.Opcodes.ILOAD&&opcode<=org.objectweb.asm.Opcodes.ALOAD||opcode>=org.objectweb.asm.Opcodes.ISTORE&&opcode<=org.objectweb.asm.Opcodes.ASTORE
                        ||opcode==org.objectweb.asm.Opcodes.DUP||opcode==org.objectweb.asm.Opcodes.CHECKCAST;
                if(!copy||frame==null){out.add(producer);continue;}
                if(producer instanceof org.objectweb.asm.tree.VarInsnNode load&&opcode<=org.objectweb.asm.Opcodes.ALOAD)pending.addAll(frame.getLocal(load.var).insns);
                else pending.addAll(frame.getStack(frame.getStackSize()-1).insns);
            }
            return out;
        }
        /** A parameter's value, standing for itself in the data flow. */
        private static final class Parameter extends org.objectweb.asm.tree.InsnNode {
            final int index;Parameter(int index){super(org.objectweb.asm.Opcodes.NOP);this.index=index;}
        }
        private static org.objectweb.asm.tree.analysis.Frame<org.objectweb.asm.tree.analysis.SourceValue>[] frames(String owner,MethodNode method){
            try{
                return new org.objectweb.asm.tree.analysis.Analyzer<>(new org.objectweb.asm.tree.analysis.SourceInterpreter(org.objectweb.asm.Opcodes.ASM9){
                    @Override public org.objectweb.asm.tree.analysis.SourceValue newParameterValue(boolean isInstance,int local,org.objectweb.asm.Type type){
                        return new org.objectweb.asm.tree.analysis.SourceValue(type.getSize(),new Parameter(local));
                    }
                }).analyze(owner,method);
            }catch(org.objectweb.asm.tree.analysis.AnalyzerException|RuntimeException unreadable){ Caught.expected(unreadable);return null;}
        }
    }

    /** The injector's one {@code @At}. */
    private static AnnotationNode onlyAt(MethodNode method){return net.forbric.kernel.mixin.MixinFit.atNodes(net.forbric.kernel.mixin.MixinFit.injectorOf(method)).getFirst();}
    /** One unshifted INVOKE point, without a slice, whose target can name {@code member} however it is spelled. */
    private static boolean point(AnnotationNode injector,String member) {
        if(net.forbric.kernel.mixin.MixinFit.value(injector,"slice")!=null)return false;
        var ats=net.forbric.kernel.mixin.MixinFit.atNodes(injector);
        return ats.size()==1&&"INVOKE".equals(net.forbric.kernel.mixin.MixinFit.value(ats.getFirst(),"value"))
                &&net.forbric.kernel.mixin.MixinBindings.covers(ats.getFirst(),member)
                &&net.forbric.kernel.mixin.MixinFit.value(ats.getFirst(),"shift")==null
                &&net.forbric.kernel.mixin.MixinFit.value(ats.getFirst(),"by")==null
                &&net.forbric.kernel.mixin.MixinFit.value(ats.getFirst(),"args")==null;
    }
    private static List<org.objectweb.asm.tree.AbstractInsnNode> code(MethodNode method){return java.util.Arrays.stream(method.instructions.toArray()).filter(i->i.getOpcode()>=0).toList();}
    /**
     * The shared-index tooltip callbacks, which only move together: each delegates once to the one closed provider
     * helper, and shares its index under one {@code @Share} name. They are one group only on one host — the one method
     * of the mixin's target every callback binds, as Mixin binds each, however its selector is written.
     */
    private static Group tooltipGroup(ClassNode node,java.util.function.Function<String,ClassNode> classes) {
        if(node==null)return null;
        MethodNode helper=null;TooltipDispatchRoles.Dispatch dispatch=null;
        for(MethodNode method:node.methods) {
            if(net.forbric.kernel.mixin.MixinFit.injectorOf(method)!=null||!method.desc.endsWith(SHARED_INDEX+")V"))continue;
            TooltipDispatchRoles.Dispatch roles=TooltipDispatchRoles.of(node.name,method);
            if(roles!=null){if(helper!=null)return null;helper=method;dispatch=roles;}
        }
        if(helper==null)return null;
        List<MethodNode> group=new ArrayList<>();String share=null;
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
            share=id;group.add(method);
        }
        if(group.isEmpty()||!oneHost(node,group,classes))return null;
        pendingDispatch=dispatch;
        return new Group("component-tooltip",List.copyOf(group));
    }
    /** Whether every callback binds the one same method of the mixin's one target, each as Mixin binds it. */
    private static boolean oneHost(ClassNode node,List<MethodNode> callbacks,java.util.function.Function<String,ClassNode> classes) {
        List<String> targets=net.forbric.kernel.mixin.MixinFit.mixinTargets(node);if(targets.size()!=1)return false;
        ClassNode target=classes.apply(targets.getFirst());
        MethodNode host=net.forbric.kernel.mixin.MixinBindings.bound(callbacks.getFirst(),target);if(host==null)return false;
        for(MethodNode callback:callbacks)if(net.forbric.kernel.mixin.MixinBindings.bound(callback,target)!=host)return false;
        return true;
    }

    /** The registry declares each of the dispatch's five moments public and static, under the descriptor the helper calls. */
    private static boolean providerDeclarations(ClassNode providers,TooltipDispatchRoles.Dispatch dispatch) {
        for(TooltipDispatchRoles.Member member:List.of(dispatch.guard(),dispatch.first(),dispatch.last(),dispatch.before(),dispatch.after())) {
            if(providers.methods.stream().noneMatch(method->method.name.equals(member.name())&&method.desc.equals(member.desc())
                    &&(method.access&(org.objectweb.asm.Opcodes.ACC_STATIC|org.objectweb.asm.Opcodes.ACC_PUBLIC))==(org.objectweb.asm.Opcodes.ACC_STATIC|org.objectweb.asm.Opcodes.ACC_PUBLIC)))return false;
        }
        return true;
    }

	/** The dispatch of the group the last tooltipGroup call found, until transform decides on it. */
	private static volatile TooltipDispatchRoles.Dispatch pendingDispatch;
	/** The dispatch of the tooltip group pruned on this boot: what the kernel's bridge calls in its place. */
	private static volatile TooltipDispatchRoles.Dispatch prunedDispatch;

	/** What the pruned component-tooltip group dispatched to, read from its own helper; null when none was pruned. */
	public static TooltipDispatchRoles.Dispatch tooltipDispatch() {
		return prunedDispatch;
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
        if(group!=null&&!readerWasConsumed(node,group,classes,natives))return classBytes;
        if(group==null && fabricTooltipBridgeOn())group=tooltipGroup(node,classes);
        if(group==null)return classBytes;
        TooltipDispatchRoles.Dispatch dispatch=pendingDispatch;
        if(group.protocol().equals("component-tooltip")) {
            ClassNode current=classes.apply("net/neoforged/neoforge/common/tooltip/ItemTooltipHandler");
            ClassNode providers=dispatch==null?null:classes.apply(dispatch.registry());
            if(current==null||providers==null||!providerDeclarations(providers,dispatch)||!NeoTooltipAppendersInjector.aroundSpliced()) {
                ForbricLog.warn("[Forbric/GuestInjectorPruner] retained %s's original tooltip callbacks: the actual carrier appender replacement has not been proved",node.name);
                return classBytes;
            }
        }
        node.methods.removeAll(group.methods());pruned+=group.methods().size();
        if(group.protocol().equals("component-tooltip")){fabricTooltipsPruned=true;prunedDispatch=dispatch;}
        String loss=group.protocol().equals("reader-deserializer")&&!ModelFormatFunnelInjector.enabled()
                ?"the original fabric:type custom model deserializer callback was removed while the model-format funnel is disabled":null;
        String config=net.forbric.kernel.mixin.MixinStubRebind.configOf(node.name);
        for(MethodNode victim:loss==null?List.<MethodNode>of():group.methods())
            net.forbric.kernel.mixin.MixinCompatibility.recordRemovedInjector(config,node.name.replace('/','.'),victim.name,victim.desc,loss,
                    List.of("closed source protocol="+group.protocol(),"source=GuestInjectorPruner"));
        // The gates read this line (gate-m9, m14, m51): how many injectors went, from which mixin, and how many stay.
        ForbricLog.info("[Forbric/GuestInjectorPruner] pruned %d injector(s) from %s — its closed %s callback protocol; the other %d injector(s) "
                +"apply as written, with their original bodies",group.methods().size(),className,group.protocol(),countInjectors(node));
        ClassWriter writer=new ClassWriter(0);node.accept(writer);return writer.toByteArray();
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
