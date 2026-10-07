/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.mixin;

import java.util.*;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;

/** A native short-circuit predicate region, copied with every call and exceptional exit intact. */
public final class NativeSourcePredicateIsland implements Opcodes {
    public record Extraction(MethodNode helper,MethodInsnNode anchor,int worldLocal,String worldType,
                      MethodInsnNode worldGetter,TypeInsnNode kind,Set<MethodInsnNode> calls){}
    private NativeSourcePredicateIsland(){}
    /** Results: 0 = consume, 1 = protected/refill with zero consumption, 3 = outer gate skipped/refill. */
    public static Extraction extract(String sourceOwner,String destinationOwner,MethodNode method,MethodInsnNode atom,String helperName){
        try{return checked(sourceOwner,destinationOwner,method,atom,helperName);}catch(RuntimeException unknown){return null;}
    }
    private static Extraction checked(String owner,String destination,MethodNode method,MethodInsnNode atom,String name){
        if((method.access&ACC_STATIC)!=0||!method.desc.equals("()V")||!method.tryCatchBlocks.isEmpty()||Type.getReturnType(atom.desc)!=Type.BOOLEAN_TYPE)return null;
        AbstractInsnNode constant=previous(atom),receiver=previous(constant),after=next(atom.getNext());
        if(!(receiver instanceof VarInsnNode self&&self.getOpcode()==ALOAD&&self.var==0)||!(constant instanceof FieldInsnNode field&&field.getOpcode()==GETSTATIC)||!(after instanceof JumpInsnNode outer&&outer.getOpcode()==IFEQ))return null;
        AbstractInsnNode primaryExit=next(outer.label);Set<AbstractInsnNode> region=Collections.newSetFromMap(new IdentityHashMap<>());ArrayDeque<AbstractInsnNode>todo=new ArrayDeque<>();todo.add(receiver);VarInsnNode output=null;
        while(!todo.isEmpty()){
            AbstractInsnNode instruction=next(todo.removeFirst());if(instruction==primaryExit)continue;if(instruction==null||!region.add(instruction))continue;
            if(instruction instanceof VarInsnNode store&&store.getOpcode()==ISTORE){if(output!=null&&output!=store)return null;output=store;continue;}
            if(instruction instanceof JumpInsnNode jump){if(method.instructions.indexOf(next(jump.label))<=method.instructions.indexOf(receiver))return null;todo.add(next(jump.label));if(jump.getOpcode()!=GOTO)todo.add(next(jump.getNext()));}
            else {if(instruction instanceof TableSwitchInsnNode||instruction instanceof LookupSwitchInsnNode||instruction.getOpcode()==RETURN||instruction.getOpcode()==ATHROW||instruction.getOpcode()==PUTFIELD||instruction.getOpcode()==PUTSTATIC||instruction instanceof InvokeDynamicInsnNode)return null;todo.add(next(instruction.getNext()));}
        }
        if(output==null)return null;int end=method.instructions.indexOf(output),start=method.instructions.indexOf(receiver);
        // A contiguous region with only its one primary-false edge may be moved; no unexamined side path.
        for(AbstractInsnNode i=receiver;i!=null&&method.instructions.indexOf(i)<=end;i=i.getNext())if(i.getOpcode()>=0&&!region.contains(i))return null;
        List<VarInsnNode>refs=new ArrayList<>(),bools=new ArrayList<>();Set<MethodInsnNode>calls=new LinkedHashSet<>();
        for(AbstractInsnNode i:region){if(i instanceof VarInsnNode v&&v.getOpcode()==ALOAD&&v.var!=0)refs.add(v);if(i instanceof VarInsnNode v&&v.getOpcode()==ILOAD)bools.add(v);if(i instanceof MethodInsnNode c)calls.add(c);}
        if(refs.isEmpty()||refs.stream().anyMatch(v->v.var!=refs.getFirst().var)||bools.isEmpty()||bools.stream().anyMatch(v->v.var!=bools.getFirst().var)||output.var==bools.getFirst().var)return null;
        int world=refs.getFirst().var,kindLocal=bools.getFirst().var;TypeInsnNode kind=null,worldCast=null;MethodInsnNode getter=null;
        VarInsnNode worldStore=null;
        for(AbstractInsnNode i=method.instructions.getFirst();i!=receiver;i=i.getNext())if(i instanceof VarInsnNode store){
            if(store.getOpcode()==ISTORE&&store.var==kindLocal){if(kind!=null||!(previous(store) instanceof TypeInsnNode test&&test.getOpcode()==INSTANCEOF)||!(previous(test) instanceof VarInsnNode root&&root.getOpcode()==ALOAD&&root.var==0))return null;kind=test;}
            if(store.getOpcode()==ASTORE&&store.var==world)worldStore=store;
        }
        if(worldStore==null||!(previous(worldStore)instanceof TypeInsnNode cast&&cast.getOpcode()==CHECKCAST)||!(previous(cast)instanceof VarInsnNode alias&&alias.getOpcode()==ALOAD))return null;worldCast=cast;
        VarInsnNode origin=null;for(AbstractInsnNode before=method.instructions.getFirst();before!=worldStore;before=before.getNext())if(before instanceof VarInsnNode saved&&saved.getOpcode()==ASTORE&&saved.var==alias.var)origin=saved;
        if(origin==null||!(previous(origin)instanceof MethodInsnNode getterCall)||Type.getReturnType(getterCall.desc).getSort()!=Type.OBJECT||!(previous(getterCall)instanceof VarInsnNode root&&root.getOpcode()==ALOAD&&root.var==0))return null;getter=getterCall;
        if(kind==null||worldCast==null||getter==null)return null;
        MethodNode helper=new MethodNode(ACC_PRIVATE,name,"(L"+worldCast.desc+";)I",null,null);helper.visibleAnnotations=new ArrayList<>(List.of(new AnnotationNode("Lorg/spongepowered/asm/mixin/Unique;")));
        helper.instructions.add(new VarInsnNode(ALOAD,0));helper.instructions.add(new TypeInsnNode(INSTANCEOF,kind.desc));helper.instructions.add(new VarInsnNode(ISTORE,2));
        Map<LabelNode,LabelNode>labels=new IdentityHashMap<>();for(AbstractInsnNode i:method.instructions)if(i instanceof LabelNode label)labels.put(label,new LabelNode());LabelNode primary=new LabelNode();for(AbstractInsnNode i:region)if(i instanceof JumpInsnNode jump&&next(jump.label)==primaryExit)labels.put(jump.label,primary);
        MethodInsnNode copiedAtom=null;
        for(AbstractInsnNode i=receiver;i!=null&&method.instructions.indexOf(i)<=end;i=i.getNext()){
            if(i instanceof FrameNode||i instanceof LineNumberNode)continue;AbstractInsnNode copy=i.clone(labels);
            if(copy instanceof VarInsnNode variable){if(variable.var==world)variable.var=1;else if(variable.var==kindLocal)variable.var=2;else if(variable.var==output.var)variable.var=3;else if(variable.var!=0)return null;}
            if(copy instanceof MethodInsnNode call&&call.owner.equals(owner))call.owner=destination;
            if(copy instanceof FieldInsnNode f&&f.owner.equals(owner))f.owner=destination;
            helper.instructions.add(copy);if(i==atom)copiedAtom=(MethodInsnNode)copy;
        }
        helper.instructions.add(new VarInsnNode(ILOAD,3));LabelNode consumes=new LabelNode();helper.instructions.add(new JumpInsnNode(IFNE,consumes));helper.instructions.add(new InsnNode(ICONST_1));helper.instructions.add(new InsnNode(IRETURN));helper.instructions.add(consumes);helper.instructions.add(new InsnNode(ICONST_0));helper.instructions.add(new InsnNode(IRETURN));helper.instructions.add(primary);helper.instructions.add(new InsnNode(ICONST_3));helper.instructions.add(new InsnNode(IRETURN));helper.maxLocals=4;helper.maxStack=12;
        return new Extraction(helper,copiedAtom,world,worldCast.desc,getter,kind,Set.copyOf(calls));
    }
    static AbstractInsnNode next(AbstractInsnNode i){while(i!=null&&i.getOpcode()<0)i=i.getNext();return i;}
    static AbstractInsnNode previous(AbstractInsnNode i){return i==null?null:back(i.getPrevious());}
    private static AbstractInsnNode back(AbstractInsnNode i){while(i!=null&&i.getOpcode()<0)i=i.getPrevious();return i;}
}
