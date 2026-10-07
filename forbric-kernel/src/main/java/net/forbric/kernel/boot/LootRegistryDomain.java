/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.boot;

import java.util.*;
import java.util.function.Function;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import org.objectweb.asm.tree.analysis.*;

/** Proves that the closed values domain contains exactly one datatype for the bridge's loot registry key. */
final class LootRegistryDomain implements Opcodes {
    static final String DATA="net/minecraft/world/level/storage/loot/LootDataType",KEYS="net/minecraft/core/registries/Registries",KEY="net/minecraft/resources/ResourceKey";
    private LootRegistryDomain(){}
    static Map<String,ClassNode> prove(Function<String,byte[]> reader){
        try{
            ClassNode data=read(reader.apply(DATA+".class")),keys=read(reader.apply(KEYS+".class"));
            if((data.access&ACC_FINAL)==0)return null;
            MethodNode values=method(data,"values","()Ljava/util/stream/Stream;");if(values==null||!values.tryCatchBlocks.isEmpty())return null;
            List<AbstractInsnNode> v=code(values);if(v.size()<4)return null;int length=constant(v.getFirst());if(length<1||length>32||v.size()!=length*4+4||!(v.get(1)instanceof TypeInsnNode array&&array.getOpcode()==ANEWARRAY&&array.desc.equals(DATA)))return null;
            List<String> fields=new ArrayList<>();for(int i=0;i<length;i++){int x=2+i*4;if(v.get(x).getOpcode()!=DUP||constant(v.get(x+1))!=i||!(v.get(x+2)instanceof FieldInsnNode f&&f.getOpcode()==GETSTATIC&&f.owner.equals(DATA)&&f.desc.equals("L"+DATA+";"))||v.get(x+3).getOpcode()!=AASTORE)return null;fields.add(f.name);}
            if(new HashSet<>(fields).size()!=length||!fields.contains("TABLE")||!(v.get(v.size()-2)instanceof MethodInsnNode stream&&stream.getOpcode()==INVOKESTATIC&&stream.owner.equals("java/util/stream/Stream")&&stream.name.equals("of")&&stream.desc.equals("([Ljava/lang/Object;)Ljava/util/stream/Stream;"))||v.getLast().getOpcode()!=ARETURN)return null;
            for(String field:fields){FieldNode f=data.fields.stream().filter(x->x.name.equals(field)&&x.desc.equals("L"+DATA+";")).findFirst().orElse(null);if(f==null||(f.access&(ACC_STATIC|ACC_FINAL))!=(ACC_STATIC|ACC_FINAL))return null;}
            MethodNode getter=method(data,"registryKey","()L"+KEY+";");if(getter==null||!getter.tryCatchBlocks.isEmpty())return null;List<AbstractInsnNode> g=code(getter);if(g.size()!=3||!(g.get(0)instanceof VarInsnNode self&&self.getOpcode()==ALOAD&&self.var==0)||!(g.get(1)instanceof FieldInsnNode keyField&&keyField.getOpcode()==GETFIELD&&keyField.owner.equals(DATA)&&keyField.desc.equals("L"+KEY+";"))||g.get(2).getOpcode()!=ARETURN)return null;
            FieldNode declaration=data.fields.stream().filter(f->f.name.equals(keyField.name)).findFirst().orElse(null);if(declaration==null||(declaration.access&(ACC_PRIVATE|ACC_FINAL|ACC_STATIC))!=(ACC_PRIVATE|ACC_FINAL))return null;
            int writes=0;for(MethodNode m:data.methods)for(AbstractInsnNode i:m.instructions)if(i instanceof FieldInsnNode f&&f.owner.equals(DATA)&&f.name.equals(keyField.name)&&f.getOpcode()==PUTFIELD){if(!m.name.equals("<init>")||!aload(previous(i),1)||!aload(previous(previous(i)),0))return null;writes++;}if(writes!=1)return null;
            for(MethodNode constructor:data.methods)if(constructor.name.equals("<init>")){
                Frame<SourceValue>[] frames=new Analyzer<>(new SourceInterpreter()).analyze(DATA,constructor);
                for(AbstractInsnNode i:constructor.instructions){if(i instanceof VarInsnNode local&&local.var==1&&local.getOpcode()!=ALOAD)return null;if(i instanceof MethodInsnNode c&&c.getOpcode()==INVOKESPECIAL&&c.owner.equals(DATA)&&c.name.equals("<init>")){Type[] args=Type.getArgumentTypes(c.desc);if(args.length==0||!args[0].getDescriptor().equals("L"+KEY+";"))return null;Frame<SourceValue> frame=frames[constructor.instructions.indexOf(i)];SourceValue input=frame.getStack(frame.getStackSize()-args.length);if(input.insns.size()!=1||!aload(input.insns.iterator().next(),1))return null;}}
            }
            MethodNode init=method(data,"<clinit>","()V");if(init==null||!init.tryCatchBlocks.isEmpty())return null;List<AbstractInsnNode> c=code(init);Map<String,String> mapping=new LinkedHashMap<>();
            for(int x=0;x<c.size()-1;){if(!(c.get(x)instanceof TypeInsnNode n&&n.getOpcode()==NEW&&n.desc.equals(DATA))||c.get(x+1).getOpcode()!=DUP||!(c.get(x+2)instanceof FieldInsnNode k&&k.getOpcode()==GETSTATIC&&k.owner.equals(KEYS)&&k.desc.equals("L"+KEY+";")))return null;int at=x+3;while(at<c.size()&&!(c.get(at)instanceof MethodInsnNode call&&call.getOpcode()==INVOKESPECIAL&&call.owner.equals(DATA)&&call.name.equals("<init>"))){if(c.get(at)instanceof JumpInsnNode||c.get(at)instanceof TableSwitchInsnNode||c.get(at)instanceof LookupSwitchInsnNode||c.get(at)instanceof VarInsnNode||c.get(at).getOpcode()==PUTSTATIC||c.get(at).getOpcode()==PUTFIELD)return null;at++;}
                if(at>=c.size()-1||!(c.get(at+1)instanceof FieldInsnNode set&&set.getOpcode()==PUTSTATIC&&set.owner.equals(DATA)&&set.desc.equals("L"+DATA+";"))||mapping.put(set.name,k.name)!=null)return null;x=at+2;
            }
            if(c.getLast().getOpcode()!=RETURN||!mapping.keySet().equals(new HashSet<>(fields))||!"LOOT_TABLE".equals(mapping.get("TABLE")))return null;
            Map<String,String> identifiers=new LinkedHashMap<>();MethodNode keyInit=method(keys,"<clinit>","()V");if(keyInit==null)return null;for(AbstractInsnNode i:keyInit.instructions)if(i instanceof FieldInsnNode f&&f.getOpcode()==PUTSTATIC&&f.owner.equals(KEYS)&&f.desc.equals("L"+KEY+";")){
                AbstractInsnNode call=previous(i),literal=previous(call);if(!(call instanceof MethodInsnNode creator&&creator.getOpcode()==INVOKESTATIC&&creator.owner.equals(KEYS)&&creator.desc.equals("(Ljava/lang/String;)L"+KEY+";"))||!(literal instanceof LdcInsnNode ldc&&ldc.cst instanceof String id))continue;
                MethodNode factory=method(keys,creator.name,creator.desc);if(factory==null)return null;List<AbstractInsnNode> fcode=code(factory);if(fcode.size()!=4||!aload(fcode.getFirst(),0)||!(fcode.get(1)instanceof MethodInsnNode identifier&&identifier.getOpcode()==INVOKESTATIC&&identifier.owner.equals("net/minecraft/resources/Identifier")&&identifier.name.equals("withDefaultNamespace"))||!(fcode.get(2)instanceof MethodInsnNode resource&&resource.getOpcode()==INVOKESTATIC&&resource.owner.equals(KEY)&&resource.name.equals("createRegistryKey"))||fcode.getLast().getOpcode()!=ARETURN)return null;identifiers.put(f.name,id);
            }
            Set<String> seen=new HashSet<>();for(String key:mapping.values()){String id=identifiers.get(key);if(id==null||!seen.add(id))return null;}
            return Map.of(DATA,scoped(data,List.of(values,getter,init),true),KEYS,scoped(keys,List.of(keyInit),false));
        }catch(RuntimeException|AnalyzerException unknown){return null;}
    }
    private static ClassNode scoped(ClassNode owner,List<MethodNode> roots,boolean constructors){
        Set<MethodNode> methods=new LinkedHashSet<>(roots);if(constructors)for(MethodNode method:owner.methods)if(method.name.equals("<init>"))methods.add(method);
        Deque<MethodNode> todo=new ArrayDeque<>(methods);while(!todo.isEmpty()){MethodNode method=todo.removeFirst();for(AbstractInsnNode instruction:method.instructions){
            if(instruction instanceof MethodInsnNode call&&call.owner.equals(owner.name)){MethodNode dependency=method(owner,call.name,call.desc);if(dependency==null)throw new IllegalArgumentException("missing domain helper");if(methods.add(dependency))todo.add(dependency);}
            if(instruction instanceof InvokeDynamicInsnNode dynamic)for(Object argument:dynamic.bsmArgs)if(argument instanceof Handle handle&&handle.getOwner().equals(owner.name)){MethodNode dependency=method(owner,handle.getName(),handle.getDesc());if(dependency==null)throw new IllegalArgumentException("missing domain handle");if(methods.add(dependency))todo.add(dependency);}
        }}
        owner.methods=new ArrayList<>(methods);return owner;
    }
    private static boolean aload(AbstractInsnNode i,int slot){return i instanceof VarInsnNode v&&v.getOpcode()==ALOAD&&v.var==slot;}
    private static AbstractInsnNode previous(AbstractInsnNode n){if(n==null)return null;n=n.getPrevious();while(n!=null&&n.getOpcode()<0)n=n.getPrevious();return n;}
    private static int constant(AbstractInsnNode i){if(i.getOpcode()>=ICONST_M1&&i.getOpcode()<=ICONST_5)return i.getOpcode()-ICONST_0;if(i instanceof IntInsnNode n)return n.operand;if(i instanceof LdcInsnNode n&&n.cst instanceof Integer value)return value;return -1;}
    private static ClassNode read(byte[] b){if(b==null)throw new IllegalArgumentException();ClassNode n=new ClassNode();new ClassReader(b).accept(n,0);return n;}
    private static MethodNode method(ClassNode c,String name,String descriptor){return c.methods.stream().filter(m->m.name.equals(name)&&m.desc.equals(descriptor)).findFirst().orElse(null);}
    private static List<AbstractInsnNode> code(MethodNode m){List<AbstractInsnNode> out=new ArrayList<>();for(AbstractInsnNode i:m.instructions)if(i.getOpcode()>=0)out.add(i);return out;}
}
