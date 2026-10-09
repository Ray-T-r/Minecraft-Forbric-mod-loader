/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.transform;
import java.util.*;
import java.util.function.Function;
import net.forbric.api.Ecosystem;
import net.forbric.kernel.mixin.NativeGameReferences;
import net.forbric.kernel.mixin.MixinInstructionFingerprint;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
/** Restore independently owned components from a verified native self-only allocation at its peer's boundary. */
final class NativeOwnedComponentInitializers implements Opcodes {
    static boolean restore(ClassNode node,NativeGameReferences refs,Function<String,byte[]> resources){
        boolean changed=false;ClassNode source=refs.get(Ecosystem.FORGE,node.name),canonical=refs.get(Ecosystem.NEOFORGE,node.name);if(source==null||canonical==null)return false;
        for(MethodNode getter:node.methods){
            FieldInsnNode field=getter(getter,node.name);if(field==null||writes(node,field)>0)continue;
            MethodNode old=method(source,getter.name+getter.desc);if(old==null||!sameField(field,getter(old,source.name)))continue;
            List<MethodNode> peers=node.methods.stream().filter(m->m.name.equals(getter.name)&&!m.desc.equals(getter.desc)).toList();if(peers.size()!=1)continue;
            FieldInsnNode peer=getter(peers.getFirst(),node.name);MethodNode nativeGetter=method(canonical,peers.getFirst().name+peers.getFirst().desc);
            if(peer==null||nativeGetter==null||!sameField(peer,getter(nativeGetter,canonical.name)))continue;
            if(source.methods.stream().anyMatch(m->!m.name.equals("<init>")&&writes(m,field)>0))continue;
            Map<MethodNode,List<AbstractInsnNode>> plans=new LinkedHashMap<>();boolean valid=true;
            for(MethodNode original:source.methods)if(original.name.equals("<init>")){
                if(writes(original,field)==0)continue;List<AbstractInsnNode> init=allocation(original,field);MethodNode target=method(node,original.name+original.desc),nativeCtor=method(canonical,original.name+original.desc);
                List<AbstractInsnNode> peerInit=target==null?null:allocation(target,peer),nativeInit=nativeCtor==null?null:allocation(nativeCtor,peer);
                String currentPrefix=peerInit==null?null:prefix(target,peerInit,canonical),nativePrefix=nativeInit==null?null:prefix(nativeCtor,nativeInit);
                if(init==null||prefix(original,init)==null||currentPrefix==null||!currentPrefix.equals(nativePrefix)
                    ||!localConstructor(((TypeInsnNode)init.get(1)).desc,((MethodInsnNode)init.get(4)).desc,resources)){valid=false;break;}
                plans.put(target,init);
            }
            for(MethodNode target:node.methods)if(target.name.equals("<init>")&&writes(target,peer)>0&&!plans.containsKey(target))valid=false;
            if(!valid||plans.isEmpty())continue;
            for(var plan:plans.entrySet()){
                List<AbstractInsnNode> peerInit=allocation(plan.getKey(),peer);InsnList copy=new InsnList();for(AbstractInsnNode i:plan.getValue())copy.add(i.clone(new HashMap<>()));
                plan.getKey().instructions.insert(peerInit.getLast(),copy);plan.getKey().maxStack+=4;changed=true;
            }
        }
        return changed;
    }
    private static boolean localConstructor(String owner,String descriptor,Function<String,byte[]> resources){
        byte[] bytes=resources.apply(owner+".class");if(bytes==null)return false;ClassNode n=new ClassNode();new ClassReader(bytes).accept(n,0);
        if(!n.superName.equals("java/lang/Object")||method(n,"<clinit>()V")!=null)return false;MethodNode ctor=method(n,"<init>"+descriptor);if(ctor==null||!ctor.tryCatchBlocks.isEmpty())return false;
        for(AbstractInsnNode i:code(ctor)){
            if(i instanceof JumpInsnNode||i instanceof TableSwitchInsnNode||i instanceof LookupSwitchInsnNode||i instanceof InvokeDynamicInsnNode)return false;
            if(i instanceof FieldInsnNode f&&(f.getOpcode()!=PUTFIELD||!f.owner.equals(owner)))return false;
            if(i instanceof MethodInsnNode m&&(!m.name.equals("<init>")||!(m.owner.equals("java/lang/Object")||container(m.owner)&&m.desc.equals("()V"))))return false;
            if(i instanceof TypeInsnNode t&&t.getOpcode()==NEW&&!container(t.desc))return false;
        }return true;
    }
    private static boolean container(String name){return Set.of("java/util/HashMap","java/util/HashSet","java/util/ArrayList","java/util/LinkedHashMap","java/util/LinkedHashSet","java/util/concurrent/ConcurrentHashMap").contains(name);}
    /** The allocation runs once on an unconditional constructor prefix. No later edge may re-enter it. */
    private static String prefix(MethodNode method,List<AbstractInsnNode> allocation){return prefix(method,allocation,null);}
    /**
     * As above, comparing against {@code canonical}'s native constructor: a merged constructor also carries the
     * merge's default initializers for fields the native class does not declare ({@code this.f = new C()} with a
     * no-argument C, placed after the superclass constructor where javac puts field initializers). They write nothing
     * the native constructor reads, so they are left out of the prefix being proved equal.
     */
    private static String prefix(MethodNode method,List<AbstractInsnNode> allocation,ClassNode canonical){
        if(!method.tryCatchBlocks.isEmpty())return null;List<AbstractInsnNode> code=code(method);int end=code.indexOf(allocation.getLast());
        if(end<0)return null;Map<LabelNode,Integer> labels=new IdentityHashMap<>();int position=0;
        for(AbstractInsnNode i:method.instructions){if(i instanceof LabelNode label)labels.put(label,position);if(i.getOpcode()>=0)position++;}
        for(int index=0;index<code.size();index++){
            AbstractInsnNode i=code.get(index);int opcode=i.getOpcode();
            if(index<=end&&(i instanceof JumpInsnNode||i instanceof TableSwitchInsnNode||i instanceof LookupSwitchInsnNode||opcode==ATHROW||opcode>=IRETURN&&opcode<=RETURN))return null;
            if(i instanceof JumpInsnNode jump&&labels.getOrDefault(jump.label,Integer.MAX_VALUE)<=end)return null;
            if(i instanceof TableSwitchInsnNode table&&(labels.getOrDefault(table.dflt,Integer.MAX_VALUE)<=end||table.labels.stream().anyMatch(label->labels.getOrDefault(label,Integer.MAX_VALUE)<=end)))return null;
            if(i instanceof LookupSwitchInsnNode lookup&&(labels.getOrDefault(lookup.dflt,Integer.MAX_VALUE)<=end||lookup.labels.stream().anyMatch(label->labels.getOrDefault(label,Integer.MAX_VALUE)<=end)))return null;
        }
        MethodNode prefix=new MethodNode(method.access,method.name,method.desc,null,null);
        for(int index=0;index<=end;index++){
            if(canonical!=null&&index+4<end&&foreignDefault(code,index,canonical)){index+=4;continue;}
            prefix.instructions.add(code.get(index).clone(new HashMap<>()));
        }
        return MixinInstructionFingerprint.hash(prefix);
    }
    /** {@code ALOAD 0; NEW C; DUP; INVOKESPECIAL C.<init>()V; PUTFIELD f} where {@code canonical} declares no field f. */
    private static boolean foreignDefault(List<AbstractInsnNode> code,int at,ClassNode canonical){
        return code.get(at)instanceof VarInsnNode a&&a.getOpcode()==ALOAD&&a.var==0&&code.get(at+1)instanceof TypeInsnNode t&&t.getOpcode()==NEW
            &&code.get(at+2).getOpcode()==DUP&&code.get(at+3)instanceof MethodInsnNode c&&c.getOpcode()==INVOKESPECIAL&&c.owner.equals(t.desc)
            &&c.name.equals("<init>")&&c.desc.equals("()V")&&code.get(at+4)instanceof FieldInsnNode f&&f.getOpcode()==PUTFIELD&&f.owner.equals(canonical.name)
            &&canonical.fields.stream().noneMatch(declared->declared.name.equals(f.name)&&declared.desc.equals(f.desc));
    }
    private static List<AbstractInsnNode> allocation(MethodNode m,FieldInsnNode field){
        List<AbstractInsnNode> code=code(m),found=null;for(int i=5;i<code.size();i++)if(code.get(i)instanceof FieldInsnNode f&&f.getOpcode()==PUTFIELD&&f.owner.equals(field.owner)&&f.name.equals(field.name)&&f.desc.equals(field.desc)){
            if(found!=null)return null;List<AbstractInsnNode> p=code.subList(i-5,i+1);
            if(!(p.get(0)instanceof VarInsnNode a&&a.getOpcode()==ALOAD&&a.var==0)||!(p.get(1)instanceof TypeInsnNode t&&t.getOpcode()==NEW)||p.get(2).getOpcode()!=DUP
                ||!(p.get(3)instanceof VarInsnNode b&&b.getOpcode()==ALOAD&&b.var==0)||!(p.get(4)instanceof MethodInsnNode ctor&&ctor.getOpcode()==INVOKESPECIAL&&ctor.owner.equals(t.desc)&&ctor.name.equals("<init>")&&Type.getArgumentTypes(ctor.desc).length==1))return null;
            found=new ArrayList<>(p);
        }return found;
    }
    private static FieldInsnNode getter(MethodNode m,String owner){List<AbstractInsnNode> c=code(m);return c.size()==3&&c.get(0)instanceof VarInsnNode v&&v.getOpcode()==ALOAD&&v.var==0&&c.get(1)instanceof FieldInsnNode f&&f.getOpcode()==GETFIELD&&f.owner.equals(owner)&&c.get(2).getOpcode()==ARETURN?f:null;}
    private static boolean sameField(FieldInsnNode first,FieldInsnNode second){return first!=null&&second!=null&&first.owner.equals(second.owner)&&first.name.equals(second.name)&&first.desc.equals(second.desc);}
    private static int writes(ClassNode n,FieldInsnNode field){return n.methods.stream().mapToInt(m->writes(m,field)).sum();}
    private static int writes(MethodNode method,FieldInsnNode field){return (int)code(method).stream().filter(i->i instanceof FieldInsnNode f&&f.getOpcode()==PUTFIELD&&sameField(field,f)).count();}
    private static MethodNode method(ClassNode n,String signature){return n.methods.stream().filter(m->(m.name+m.desc).equals(signature)).findFirst().orElse(null);}
    private static List<AbstractInsnNode> code(MethodNode m){return Arrays.stream(m.instructions.toArray()).filter(i->i.getOpcode()>=0).toList();}
}
