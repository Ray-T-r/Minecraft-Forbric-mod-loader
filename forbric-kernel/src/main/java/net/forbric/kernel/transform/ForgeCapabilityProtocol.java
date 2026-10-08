/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.transform;

import java.util.*;
import java.util.function.Function;
import net.forbric.api.AncestorComposition;
import net.forbric.kernel.mixin.MixinInstructionFingerprint;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;

/** The actual removed provider, composed state, and final emitted delegates must agree. */
final class ForgeCapabilityProtocol implements Opcodes {
    private static final String BASE="net/minecraftforge/common/capabilities/CapabilityProvider";
    private static final String RUNTIME=ForgeCapabilityCompositionTransformer.RUNTIME;
    private static final String AS_FIELD=ForgeCapabilityCompositionTransformer.AS_FIELD;
    record Certificate(String owner,String retained,String removed,Map<String,String> methods,Map<String,List<List<String>>> seams) {
        boolean proves(AncestorComposition.Requirement r,byte[] bytes,Function<String,byte[]> reader){
            if(!owner.equals(r.owner())||!retained.equals(r.retainedSuperclass())||!removed.equals(r.sourceSuperclass()))return false;
            ClassNode node=parse(bytes);if(node==null||!owner.equals(node.name)||!retained.equals(node.superName)
                    ||!node.interfaces.contains(ForgeCapabilityCompositionTransformer.PROVIDER_IMPL))return false;
            List<FieldNode> fields=node.fields.stream().filter(f->f.name.equals(ForgeCapabilityCompositionTransformer.FIELD)).toList();
            if(fields.size()!=1||!fields.getFirst().desc.equals(ForgeCapabilityCompositionTransformer.AS_FIELD_DESC)
                    ||(fields.getFirst().access&(ACC_STATIC|ACC_FINAL))!=0)return false;
            for(var expected:methods.entrySet()){
                MethodNode method=method(node,expected.getKey());
                if(method==null||!expected.getValue().equals(method.access+":"+MixinInstructionFingerprint.hash(method)))return declined("final delegate changed: "+expected.getKey());
            }
            for(var expected:seams.entrySet()){
                MethodNode method=method(node,expected.getKey());if(method==null)return false;List<String> code=tokens(method);
                for(List<String> needle:expected.getValue())if(occurrences(code,needle)!=1)return declined("final lifecycle seam changed: "+expected.getKey()+" "+needle);
            }
            return protocol(removed,owner,reader);
        }
        private boolean declined(String reason){net.forbric.kernel.util.ForbricLog.debug("[Forbric/Capabilities] %s composition proof declined: %s",owner,reason);return false;}
    }
    static Certificate certificate(ClassNode before,ClassNode after,ClassNode source,Function<String,byte[]> reader){
        if(source==null||before==null||after==null||source.superName.equals(after.superName)||!protocol(source.superName,source.name,reader))return null;
        Map<String,String> methods=new LinkedHashMap<>();Map<String,List<List<String>>> seams=new LinkedHashMap<>();
        for(MethodNode emitted:after.methods){
            MethodNode previous=method(before,emitted.name+emitted.desc);
            if(previous==null){methods.put(emitted.name+emitted.desc,emitted.access+":"+MixinInstructionFingerprint.hash(emitted));continue;}
            if(MixinInstructionFingerprint.hash(previous).equals(MixinInstructionFingerprint.hash(emitted)))continue;
            List<String> old=tokens(previous),now=tokens(emitted);List<List<String>> probes=new ArrayList<>();
            for(AbstractInsnNode instruction:emitted.instructions)if(instruction instanceof MethodInsnNode call
                    &&(call.owner.equals(RUNTIME)||call.owner.equals(after.name)&&(call.name.equals("forbric$caps")||call.name.equals("gatherCapabilities")||call.name.equals("invalidateCaps")||call.name.equals("reviveCaps")))){
                int at=real(emitted).indexOf(call);List<String> needle=List.copyOf(now.subList(Math.max(0,at-1),at+1));
                if(occurrences(old,needle)==0&&occurrences(now,needle)==1)probes.add(needle);
            }
            if(!probes.isEmpty())seams.put(emitted.name+emitted.desc,List.copyOf(probes));
        }
        if(methods.isEmpty())return null;
        return new Certificate(after.name,after.superName,source.superName,Map.copyOf(methods),Map.copyOf(seams));
    }
    /** Restore only an actual unique, unconditional source constructor tail; delegating constructors stay unchanged. */
    static boolean restoreConstructorGather(ClassNode current,ClassNode source){
        if(source==null)return false;boolean changed=false;
        for(MethodNode ctor:current.methods){
            if(!ctor.name.equals("<init>"))continue;MethodNode original=method(source,ctor.name+ctor.desc);if(original==null)continue;
            List<AbstractInsnNode> sourceCode=real(original),live=real(ctor);
            if(sourceCode.size()<3||live.isEmpty()||!original.tryCatchBlocks.isEmpty())continue;
            int n=sourceCode.size();if(!(sourceCode.get(n-3)instanceof VarInsnNode receiver&&receiver.getOpcode()==ALOAD&&receiver.var==0)
                    ||!(sourceCode.get(n-2)instanceof MethodInsnNode gather&&gather.name.equals("gatherCapabilities")&&gather.desc.equals("()V")&&gather.owner.equals(source.name))
                    ||sourceCode.getLast().getOpcode()!=RETURN)continue;
            long returns=live.stream().filter(i->i.getOpcode()==RETURN).count();
            boolean delegates=live.stream().anyMatch(i->i instanceof MethodInsnNode call&&call.name.equals("<init>")&&call.owner.equals(current.name));
            if(returns!=1||delegates||live.getLast().getOpcode()!=RETURN||live.stream().anyMatch(i->i instanceof MethodInsnNode call&&call.name.equals("gatherCapabilities")&&call.desc.equals("()V")))continue;
            InsnList tail=new InsnList();tail.add(new VarInsnNode(ALOAD,0));tail.add(new MethodInsnNode(INVOKEVIRTUAL,current.name,"gatherCapabilities","()V",false));ctor.instructions.insertBefore(live.getLast(),tail);ctor.maxStack=Math.max(ctor.maxStack,1);changed=true;
        }
        return changed;
    }
    private static boolean protocol(String removed,String owner,Function<String,byte[]> reader){
        if(reader==null)return false;ClassNode nativeProvider=parse(reader.apply(removed+".class")),asField=parse(reader.apply(AS_FIELD+".class"));
        if(nativeProvider==null||asField==null||!BASE.equals(nativeProvider.superName)||!BASE.equals(asField.superName)||!nativeProvider.fields.isEmpty())return false;
        MethodNode ctor=method(nativeProvider,"<init>()V");
        if(ctor==null||!tokens(ctor).equals(List.of("v25:0","o3","m183:"+BASE+".<init>(Z)V","o177")))return false;
        MethodNode fieldCtor=method(asField,"<init>(Lnet/minecraftforge/common/capabilities/ICapabilityProviderImpl;Z)V");
        if(fieldCtor==null||!tokens(fieldCtor).equals(List.of("v25:0","v21:2","m183:"+BASE+".<init>(Z)V","v25:0","v25:1","f181:"+AS_FIELD+".ownerLnet/minecraftforge/common/capabilities/ICapabilityProviderImpl;","o177")))return false;
        String kind=removed.substring(removed.lastIndexOf('$')+1);ClassNode delegate=parse(reader.apply(RUNTIME+"$"+kind+".class")),composed=parse(reader.apply(RUNTIME+"$Composed.class"));
        if(delegate==null||composed==null||!delegate.superName.equals(composed.name)||!composed.superName.equals(AS_FIELD))return false;
        MethodNode composedCtor=method(composed,"<init>(Ljava/lang/Object;)V");
        if(composedCtor==null||!tokens(composedCtor).equals(List.of("v25:0","v25:1","t192:net/minecraftforge/common/capabilities/ICapabilityProviderImpl","o3","m183:"+AS_FIELD+".<init>(Lnet/minecraftforge/common/capabilities/ICapabilityProviderImpl;Z)V","o177")))return false;
        for(String name:List.of("fireAttachCapabilitiesEvent","shouldFireAttachCapabilitiesEvent")){
            MethodNode nativeMethod=nonBridge(nativeProvider,name),delegateMethod=nonBridge(delegate,name);
            if(nativeMethod==null||delegateMethod==null||!nativeMethod.tryCatchBlocks.isEmpty()||!delegateMethod.tryCatchBlocks.isEmpty())return false;
            Set<String> casts=new HashSet<>(Set.of(owner));for(AbstractInsnNode i:nativeMethod.instructions)if(i instanceof TypeInsnNode type)casts.add(type.desc);
            if(!withoutCasts(nativeMethod,casts).equals(withoutCasts(delegateMethod,casts)))return false;
        }
        // No additional source state, initializer or override may be silently discarded.
        for(MethodNode m:nativeProvider.methods)if(!m.name.equals("<init>")&&!m.name.equals("fireAttachCapabilitiesEvent")&&!m.name.equals("shouldFireAttachCapabilitiesEvent"))return false;
        return true;
    }
    private static MethodNode nonBridge(ClassNode node,String name){List<MethodNode> found=node.methods.stream().filter(m->m.name.equals(name)&&(m.access&ACC_BRIDGE)==0).toList();return found.size()==1?found.getFirst():null;}
    private static List<String> withoutCasts(MethodNode method,Set<String> allowed){List<String> result=new ArrayList<>();for(AbstractInsnNode i:real(method)){if(i instanceof TypeInsnNode t&&i.getOpcode()==CHECKCAST&&allowed.contains(t.desc))continue;result.add(token(i));}return result;}
    private static MethodNode method(ClassNode node,String signature){return node==null?null:node.methods.stream().filter(m->(m.name+m.desc).equals(signature)).findFirst().orElse(null);}
    private static ClassNode parse(byte[] bytes){if(bytes==null)return null;ClassNode node=new ClassNode();new ClassReader(bytes).accept(node,0);return node;}
    private static List<AbstractInsnNode> real(MethodNode method){return Arrays.stream(method.instructions.toArray()).filter(i->i.getOpcode()>=0).toList();}
    private static List<String> tokens(MethodNode method){return real(method).stream().map(ForgeCapabilityProtocol::token).toList();}
    private static String token(AbstractInsnNode i){if(i instanceof VarInsnNode v)return "v"+i.getOpcode()+":"+v.var;if(i instanceof MethodInsnNode m)return "m"+i.getOpcode()+":"+m.owner+"."+m.name+m.desc;if(i instanceof FieldInsnNode f)return "f"+i.getOpcode()+":"+f.owner+"."+f.name+f.desc;if(i instanceof TypeInsnNode t)return "t"+i.getOpcode()+":"+t.desc;return "o"+i.getOpcode();}
    private static int occurrences(List<String> code,List<String> needle){int count=0;for(int i=0;i+needle.size()<=code.size();i++)if(code.subList(i,i+needle.size()).equals(needle))count++;return count;}
}
