/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.mixin;

import java.util.*;
import java.util.function.Function;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;

/** Ordered short-circuit trees. Opaque calls are recorded as ordered operand recipes, never flattened into sets. */
final class PredicateDecisionGraph implements Opcodes {
    record Expr(String operation,String symbol,List<Expr> inputs){Expr{inputs=List.copyOf(inputs);}static Expr root(int index){return new Expr("root",Integer.toString(index),List.of());}static Expr integer(int value){return new Expr("integer",Integer.toString(value),List.of());}}
    sealed interface Tree permits Test,Result,Value{}
    record Test(Expr condition,Tree yes,Tree no)implements Tree{}
    record Result(int decision)implements Tree{}
    record Value(Expr expression)implements Tree{}
    record State(List<Expr> stack,Map<Integer,Expr> locals){State copy(){return new State(new ArrayList<>(stack),new HashMap<>(locals));}}
    private PredicateDecisionGraph(){}
    static Tree helper(MethodNode method,Function<Expr,Expr> normalize){Map<Integer,Expr>locals=new HashMap<>();locals.put(0,Expr.root(0));locals.put(1,Expr.root(1));try{return walk(method.instructions.getFirst(),new State(new ArrayList<>(),locals),null,-1,-1,normalize,new HashSet<>(),0);}catch(RuntimeException unknown){return null;}}
    static Tree eventPredicate(MethodNode method,AbstractInsnNode stop,int canBreathe,int consume,Function<Expr,Expr> normalize){Map<Integer,Expr>locals=new HashMap<>();Type[]args=Type.getArgumentTypes(method.desc);int slot=0;for(int i=0;i<args.length;i++){locals.put(slot,Expr.root(i));slot+=args[i].getSize();}try{return walk(method.instructions.getFirst(),new State(new ArrayList<>(),locals),stop,canBreathe,consume,normalize,new HashSet<>(),0);}catch(RuntimeException unknown){return null;}}
    private static Tree walk(AbstractInsnNode raw,State state,AbstractInsnNode stop,int breathe,int consume,Function<Expr,Expr> normalize,Set<AbstractInsnNode>path,int depth){
        if(depth>256)throw new IllegalArgumentException("deep predicate");for(AbstractInsnNode instruction=next(raw);instruction!=null;instruction=next(instruction.getNext())){
            if(instruction==stop){if(!state.stack.isEmpty())throw new IllegalArgumentException("nonempty event operand stack");Expr b=state.locals.get(breathe),amount=state.locals.get(consume);if(integer(b,0))return new Result(0);if(!integer(b,1))throw new IllegalArgumentException("unknown boolean output");if(integer(amount,0))return new Result(1);if(amount.equals(Expr.root(consume)))return new Result(3);throw new IllegalArgumentException("changed consumption recipe");}
            if(!path.add(instruction))throw new IllegalArgumentException("predicate loop");int opcode=instruction.getOpcode();
            if(instruction instanceof VarInsnNode variable){if(opcode==ALOAD||opcode==ILOAD||opcode==DLOAD){Expr value=state.locals.get(variable.var);if(value==null)throw new IllegalArgumentException("unknown local");state.stack.add(value);}else if(opcode==ASTORE||opcode==ISTORE)state.locals.put(variable.var,pop(state));else throw new IllegalArgumentException("local write");}
            else if(instruction instanceof FieldInsnNode field){if(opcode==GETSTATIC)state.stack.add(normalize.apply(new Expr("static",field.owner+"."+field.name+field.desc,List.of())));else if(opcode==GETFIELD)state.stack.add(normalize.apply(new Expr("field",field.owner+"."+field.name+field.desc,List.of(pop(state)))));else throw new IllegalArgumentException("field write");}
            else if(instruction instanceof MethodInsnNode call){Type[]arguments=Type.getArgumentTypes(call.desc);List<Expr>inputs=new ArrayList<>();for(int i=arguments.length-1;i>=0;i--)inputs.add(0,pop(state));if(opcode!=INVOKESTATIC)inputs.add(0,pop(state));if(Type.getReturnType(call.desc)==Type.VOID_TYPE)throw new IllegalArgumentException("void effect in predicate");state.stack.add(normalize.apply(new Expr("call",opcode+":"+call.owner+"."+call.name+call.desc,inputs)));}
            else if(instruction instanceof TypeInsnNode type){if(opcode==CHECKCAST)state.stack.add(normalize.apply(new Expr("cast",type.desc,List.of(pop(state)))));else if(opcode==INSTANCEOF)state.stack.add(normalize.apply(new Expr("instanceof",type.desc,List.of(pop(state)))));else throw new IllegalArgumentException("allocation in predicate");}
            else if(instruction instanceof InvokeDynamicInsnNode dynamic){List<Expr>inputs=new ArrayList<>();Type[]args=Type.getArgumentTypes(dynamic.desc);for(int i=args.length-1;i>=0;i--)inputs.add(0,pop(state));if(!dynamic.bsm.getOwner().equals("java/lang/invoke/LambdaMetafactory")||!dynamic.bsm.getName().equals("metafactory")||dynamic.bsmArgs.length!=3||!(dynamic.bsmArgs[1]instanceof Handle handle))throw new IllegalArgumentException("opaque bootstrap");state.stack.add(normalize.apply(new Expr("lambda",handle.getOwner()+"."+handle.getName()+handle.getDesc(),inputs)));}
            else if(instruction instanceof JumpInsnNode jump){if(opcode==GOTO){instruction=jump.label;continue;}Expr condition;boolean swapped=false;
                if(opcode==IFEQ||opcode==IFNE){condition=pop(state);swapped=opcode==IFEQ;}else if(opcode==IFNULL||opcode==IFNONNULL){condition=new Expr("nonnull","",List.of(pop(state)));swapped=opcode==IFNULL;}else if(opcode==IF_ACMPEQ||opcode==IF_ACMPNE){Expr right=pop(state),left=pop(state);condition=new Expr("same","",List.of(left,right));swapped=opcode==IF_ACMPNE;}else throw new IllegalArgumentException("unsupported comparison");
                State yes=state.copy(),no=state.copy();AbstractInsnNode yesAt=swapped?jump.getNext():jump.label,noAt=swapped?jump.label:jump.getNext();if(integer(condition,0))return walk(noAt,no,stop,breathe,consume,normalize,new HashSet<>(path),depth+1);if(integer(condition,1))return walk(yesAt,yes,stop,breathe,consume,normalize,new HashSet<>(path),depth+1);
                return new Test(normalize.apply(condition),walk(yesAt,yes,stop,breathe,consume,normalize,new HashSet<>(path),depth+1),walk(noAt,no,stop,breathe,consume,normalize,new HashSet<>(path),depth+1));
            }else if(opcode>=ICONST_M1&&opcode<=ICONST_5)state.stack.add(Expr.integer(opcode-ICONST_0));
            else if(instruction instanceof IntInsnNode value&&(opcode==BIPUSH||opcode==SIPUSH))state.stack.add(Expr.integer(value.operand));
            else if(instruction instanceof LdcInsnNode value&&value.cst instanceof Integer number)state.stack.add(Expr.integer(number));
            else if(opcode==IRETURN){Expr result=pop(state);if(!state.stack.isEmpty())throw new IllegalArgumentException("unknown helper result");return result.operation.equals("integer")?new Result(Integer.parseInt(result.symbol)):new Value(normalize.apply(result));}
            else if(opcode==NOP){}
            else throw new IllegalArgumentException("unsupported predicate opcode "+opcode);
        }throw new IllegalArgumentException("unterminated predicate");
    }
    private static boolean integer(Expr value,int expected){return value!=null&&value.equals(Expr.integer(expected));}
    private static Expr pop(State state){if(state.stack.isEmpty())throw new IllegalArgumentException("empty operand stack");return state.stack.removeLast();}
    private static AbstractInsnNode next(AbstractInsnNode instruction){while(instruction!=null&&instruction.getOpcode()<0)instruction=instruction.getNext();return instruction;}
}
