/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.tools;

import java.util.*;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;

/** Restricted normal-flow proof for a private Supplier cache and pure invalidation. Synchronization is
 * left in the original canonical provider; this proves the common serial protocol rather than replacing it. */
final class MemoizedSupplierContract {
    private static final String SELF="self",NULL="null",VALUE="value",DELEGATE="delegate",LOCK="lock";
    record Proof(String cache,Set<String> resets){}
    static Proof prove(ClassNode provider,ContractGraph graph){
        MethodNode get=graph.implementation(provider.name,"get","()Ljava/lang/Object;");if(get==null)return null;
        Set<String> caches=new HashSet<>();for(AbstractInsnNode instruction:get.instructions)if(instruction instanceof FieldInsnNode field&&field.getOpcode()==Opcodes.PUTFIELD)caches.add(field.owner+"#"+field.name+field.desc);
        if(caches.size()!=1)return null;String cache=caches.iterator().next();FieldNode declared=provider.fields.stream().filter(field->cache.equals(provider.name+"#"+field.name+field.desc)).findFirst().orElse(null);
        if(declared==null||(declared.access&(Opcodes.ACC_PRIVATE|Opcodes.ACC_FINAL|Opcodes.ACC_STATIC))!=Opcodes.ACC_PRIVATE||!declared.desc.equals("Ljava/lang/Object;"))return null;
        Set<String> delegates=new HashSet<>();for(FieldNode field:provider.fields)if(field.desc.equals("Ljava/util/function/Supplier;")&&(field.access&(Opcodes.ACC_PRIVATE|Opcodes.ACC_FINAL|Opcodes.ACC_STATIC))==(Opcodes.ACC_PRIVATE|Opcodes.ACC_FINAL))delegates.add(provider.name+"#"+field.name+field.desc);
        if(delegates.size()!=1)return null;String delegate=delegates.iterator().next();
        MethodNode constructor=ContractGraph.ownMethod(provider,"<init>","(Ljava/util/function/Supplier;)V");if(constructor==null)return null;List<AbstractInsnNode> init=ContractGraph.code(constructor);boolean provided=false;
        for(int i=2;i<init.size();i++)if(init.get(i) instanceof FieldInsnNode field&&key(field).equals(delegate)&&field.getOpcode()==Opcodes.PUTFIELD){if(!(init.get(i-2) instanceof VarInsnNode self)||self.getOpcode()!=Opcodes.ALOAD||self.var!=0||!(init.get(i-1) instanceof VarInsnNode arg)||arg.getOpcode()!=Opcodes.ALOAD||arg.var!=1)return null;provided=true;}
        if(!provided||!run(get,provider,cache,delegate,NULL,false)||!run(get,provider,cache,delegate,VALUE,false))return null;
        Set<String> resets=new HashSet<>();for(MethodNode method:provider.methods)if(method.desc.equals("()V")&&!method.name.startsWith("<")&&(method.access&(Opcodes.ACC_PUBLIC|Opcodes.ACC_STATIC))==Opcodes.ACC_PUBLIC&&run(method,provider,cache,delegate,VALUE,true))resets.add(method.name+method.desc);
        return resets.isEmpty()?null:new Proof(cache,resets);
    }
    private static boolean run(MethodNode method,ClassNode provider,String cache,String delegate,String initial,boolean reset){
        if(!handlers(method))return false;List<AbstractInsnNode> code=ContractGraph.code(method);Map<Integer,String> locals=new HashMap<>();locals.put(0,SELF);List<String> stack=new ArrayList<>();String current=initial;int calls=0,writes=0,monitors=0,pc=0,steps=0;
        try{while(pc>=0&&pc<code.size()&&steps++<code.size()*4){AbstractInsnNode instruction=code.get(pc++);int op=instruction.getOpcode();
            if(instruction instanceof VarInsnNode var){if(op==Opcodes.ALOAD){String value=locals.get(var.var);if(value==null)return false;stack.add(value);}else if(op==Opcodes.ASTORE)locals.put(var.var,pop(stack));else return false;continue;}
            if(instruction instanceof FieldInsnNode field){String key=key(field);if(!field.owner.equals(provider.name))return false;
                if(op==Opcodes.GETFIELD){if(!pop(stack).equals(SELF))return false;if(key.equals(cache))stack.add(current);else if(key.equals(delegate))stack.add(DELEGATE);else{FieldNode lock=provider.fields.stream().filter(f->f.name.equals(field.name)&&f.desc.equals(field.desc)).findFirst().orElse(null);if(lock==null||(lock.access&Opcodes.ACC_FINAL)==0||!lock.desc.equals("Ljava/lang/Object;"))return false;stack.add(LOCK);}}
                else if(op==Opcodes.PUTFIELD){String value=pop(stack);if(!pop(stack).equals(SELF)||!key.equals(cache)||!(value.equals(NULL)||value.equals(VALUE)))return false;current=value;writes++;}else return false;continue;
            }
            if(instruction instanceof MethodInsnNode call){if(reset||op!=Opcodes.INVOKEINTERFACE||!call.owner.equals("java/util/function/Supplier")||!call.name.equals("get")||!call.desc.equals("()Ljava/lang/Object;")||!pop(stack).equals(DELEGATE))return false;calls++;stack.add(VALUE);continue;}
            if(instruction instanceof JumpInsnNode jump){if(op==Opcodes.GOTO)pc=position(code,jump.label);else if(op==Opcodes.IFNULL||op==Opcodes.IFNONNULL){String value=pop(stack);if(!value.equals(NULL)&&!value.equals(VALUE))return false;boolean condition=value.equals(NULL);if(op==Opcodes.IFNONNULL)condition=!condition;if(condition)pc=position(code,jump.label);}else return false;continue;}
            switch(op){
                case Opcodes.ACONST_NULL -> stack.add(NULL);
                case Opcodes.DUP -> stack.add(stack.get(stack.size()-1));
                case Opcodes.DUP_X1 -> {String first=pop(stack),second=pop(stack);stack.add(first);stack.add(second);stack.add(first);}
                case Opcodes.POP -> pop(stack);
                case Opcodes.MONITORENTER -> {String lock=pop(stack);if(!lock.equals(SELF)&&!lock.equals(LOCK))return false;monitors++;}
                case Opcodes.MONITOREXIT -> {String lock=pop(stack);if(!lock.equals(SELF)&&!lock.equals(LOCK)||--monitors<0)return false;}
                case Opcodes.ARETURN -> {return !reset&&pop(stack).equals(VALUE)&&stack.isEmpty()&&current.equals(VALUE)&&calls==(initial.equals(NULL)?1:0)&&writes==(initial.equals(NULL)?1:0)&&monitors==0;}
                case Opcodes.RETURN -> {return reset&&stack.isEmpty()&&current.equals(NULL)&&calls==0&&writes==1&&monitors==0;}
                case Opcodes.NOP -> {}
                default -> {return false;}
            }
        }}catch(IndexOutOfBoundsException failure){return false;}return false;
    }
    private static boolean handlers(MethodNode method){
        List<AbstractInsnNode> code=ContractGraph.code(method);for(TryCatchBlockNode handler:method.tryCatchBlocks){if(handler.type!=null)return false;int at=position(code,handler.handler);if(at<0||at+4>=code.size()||!(code.get(at) instanceof VarInsnNode exception)||exception.getOpcode()!=Opcodes.ASTORE||!(code.get(at+1) instanceof VarInsnNode lock)||lock.getOpcode()!=Opcodes.ALOAD||code.get(at+2).getOpcode()!=Opcodes.MONITOREXIT||!(code.get(at+3) instanceof VarInsnNode reload)||reload.getOpcode()!=Opcodes.ALOAD||reload.var!=exception.var||code.get(at+4).getOpcode()!=Opcodes.ATHROW)return false;}return true;
    }
    private static String pop(List<String> stack){return stack.remove(stack.size()-1);}
    private static String key(FieldInsnNode field){return field.owner+"#"+field.name+field.desc;}
    private static int position(List<AbstractInsnNode> code,AbstractInsnNode label){while(label!=null&&label.getOpcode()<0)label=label.getNext();return label==null?code.size():code.indexOf(label);}
}
