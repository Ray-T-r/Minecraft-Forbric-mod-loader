/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.tools;

import java.util.*;
import java.util.regex.*;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;

/** Infers collection-view interface additions from original implementations and their shared size/keyed-reader storage.
 * Field/member names select no rule: signatures, JDK Map operations and one final correlated field are the evidence. */
final class MapContractRepair {
    private final ContractGraph graph;
    private final List<ClassNode> peers=new ArrayList<>();
    MapContractRepair(ContractGraph graph,List<Map<String,byte[]>> originals){
        this.graph=graph;for(Map<String,byte[]> original:originals)for(byte[] bytes:original.values()){
            ClassNode node=new ClassNode();new ClassReader(bytes).accept(node,0);peers.add(node);
        }
    }
    int repair(ClassNode target){
        if((target.access&(Opcodes.ACC_INTERFACE|Opcodes.ACC_ABSTRACT))!=0)return 0;
        int count=0;Map<String,MethodNode> contracts=graph.interfaceContracts(target);
        for(var entry:contracts.entrySet()){
            MethodNode required=entry.getValue(),implemented=graph.implementation(target.name,required.name,required.desc);
            if((required.access&Opcodes.ACC_ABSTRACT)==0||(implemented!=null&&(implemented.access&Opcodes.ACC_ABSTRACT)==0))continue;
            if(ContractGraph.ownMethod(target,required.name,required.desc)!=null)
                throw new IllegalStateException("Incompatible existing method occupies interface slot "+target.name+"#"+required.name+required.desc+": no public instance implementation, and a duplicate-signature bridge cannot preserve the old method");
            MethodNode replacement=view(target,required,contracts);
            if(replacement==null)replacement=lookup(target,required,contracts);
            if(replacement==null)continue;
            target.methods.add(replacement);graph.replace(target);count++;
            System.out.println("[contract-repair] "+target.name+"#"+required.name+required.desc+": native Map-view contract, unique correlated final storage");
        }
        return count;
    }
    private MethodNode view(ClassNode target,MethodNode required,Map<String,MethodNode> contracts){
        if(!required.desc.equals("()Ljava/util/Map;"))return null;
        String wanted=mapPair(required.signature);if(wanted==null)return null;
        Set<String> recipes=new HashSet<>();
        for(ClassNode peer:peers){MethodNode method=ContractGraph.ownMethod(peer,required.name,required.desc);
            if(method==null||!graph.interfaceContracts(peer).containsKey(required.name+required.desc))continue;
            View nativeView=parseView(peer,method);if(nativeView!=null&&Objects.equals(mapPair(method.signature),wanted))recipes.add(nativeView.unmodifiable?"unmodifiable":"direct");
        }
        if(recipes.size()!=1)return null;
        List<FieldInsnNode> candidates=new ArrayList<>();
        for(MethodNode method:target.methods){if(!contracts.containsKey(method.name+method.desc)||!Objects.equals(lookupPair(method.signature),wanted))continue;
            Lookup read=parseLookup(target,method);if(read!=null&&hasSize(target,contracts,read.field)&&finalMap(read.field))candidates.add(read.field);
        }
        Map<String,FieldInsnNode> unique=new LinkedHashMap<>();for(FieldInsnNode f:candidates)unique.put(f.owner+"#"+f.name+f.desc,f);
        if(unique.size()!=1)return null;FieldInsnNode field=unique.values().iterator().next();
        MethodNode method=new MethodNode(Opcodes.ACC_PUBLIC,required.name,required.desc,required.signature,null);
        method.instructions.add(new VarInsnNode(Opcodes.ALOAD,0));method.instructions.add(field.clone(new HashMap<>()));
        if(recipes.contains("unmodifiable"))method.instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC,"java/util/Collections","unmodifiableMap","(Ljava/util/Map;)Ljava/util/Map;",false));
        method.instructions.add(new InsnNode(Opcodes.ARETURN));method.maxStack=1;method.maxLocals=1;return method;
    }
    private MethodNode lookup(ClassNode target,MethodNode required,Map<String,MethodNode> contracts){
        String wanted=lookupPair(required.signature);if(wanted==null||Type.getArgumentTypes(required.desc).length!=1)return null;
        List<Lookup> recipes=new ArrayList<>();
        for(ClassNode peer:peers){MethodNode method=ContractGraph.ownMethod(peer,required.name,required.desc);
            if(method!=null&&graph.interfaceContracts(peer).containsKey(required.name+required.desc)&&Objects.equals(lookupPair(method.signature),wanted)){
                Lookup recipe=parseLookup(peer,method);if(recipe!=null)recipes.add(recipe);
            }
        }
        Set<String> defaults=new HashSet<>();for(Lookup recipe:recipes)defaults.add(recipe.empty.owner+"#"+recipe.empty.name+recipe.empty.desc);
        if(defaults.size()!=1)return null;
        List<MethodNode> views=new ArrayList<>();for(MethodNode method:target.methods)
            if(contracts.containsKey(method.name+method.desc)&&Objects.equals(mapPair(method.signature),wanted)&&parseView(target,method)!=null)views.add(method);
        if(views.size()!=1)return null;MethodNode view=views.get(0);MethodInsnNode empty=recipes.get(0).empty;
        MethodNode method=new MethodNode(Opcodes.ACC_PUBLIC,required.name,required.desc,required.signature,null);
        method.instructions.add(new VarInsnNode(Opcodes.ALOAD,0));method.instructions.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL,target.name,view.name,view.desc,false));
        method.instructions.add(new VarInsnNode(Opcodes.ALOAD,1));method.instructions.add(empty.clone(new HashMap<>()));
        method.instructions.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE,"java/util/Map","getOrDefault","(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;",true));
        method.instructions.add(new TypeInsnNode(Opcodes.CHECKCAST,Type.getReturnType(required.desc).getInternalName()));
        method.instructions.add(new InsnNode(Opcodes.ARETURN));method.maxStack=3;method.maxLocals=2;return method;
    }
    private record View(FieldInsnNode field,boolean unmodifiable){}
    private record Lookup(FieldInsnNode field,MethodInsnNode empty){}
    private View parseView(ClassNode owner,MethodNode method){
        if(!method.desc.equals("()Ljava/util/Map;")||(method.access&(Opcodes.ACC_STATIC|Opcodes.ACC_ABSTRACT))!=0||!method.tryCatchBlocks.isEmpty())return null;
        List<AbstractInsnNode> code=ContractGraph.code(method);if((code.size()!=3&&code.size()!=4)||!self(code.get(0))
                ||!(code.get(1) instanceof FieldInsnNode field)||field.getOpcode()!=Opcodes.GETFIELD||!field.owner.equals(owner.name)
                ||!finalMap(field)||code.get(code.size()-1).getOpcode()!=Opcodes.ARETURN)return null;
        if(code.size()==4&&!(code.get(2) instanceof MethodInsnNode call&&call.getOpcode()==Opcodes.INVOKESTATIC&&call.owner.equals("java/util/Collections")&&call.name.equals("unmodifiableMap")&&call.desc.equals("(Ljava/util/Map;)Ljava/util/Map;")))return null;
        return new View(field,code.size()==4);
    }
    private Lookup parseLookup(ClassNode owner,MethodNode method){
        if(Type.getArgumentTypes(method.desc).length!=1||Type.getReturnType(method.desc).getSort()!=Type.OBJECT||!method.tryCatchBlocks.isEmpty())return null;
        List<AbstractInsnNode> c=ContractGraph.code(method);if(c.size()!=7||!self(c.get(0))||!(c.get(1) instanceof FieldInsnNode field)
                ||field.getOpcode()!=Opcodes.GETFIELD||!field.owner.equals(owner.name)||!finalMap(field)
                ||!(c.get(2) instanceof VarInsnNode arg)||arg.getOpcode()!=Opcodes.ALOAD||arg.var!=1
                ||!(c.get(3) instanceof MethodInsnNode empty)||!emptyCollection(empty)
                ||!(c.get(4) instanceof MethodInsnNode get)||!get.name.equals("getOrDefault")||!get.desc.equals("(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;")
                ||!graph.assignable("L"+get.owner+";","Ljava/util/Map;")||!(c.get(5) instanceof TypeInsnNode cast)
                ||cast.getOpcode()!=Opcodes.CHECKCAST||!Type.getReturnType(method.desc).getInternalName().equals(cast.desc)
                ||c.get(6).getOpcode()!=Opcodes.ARETURN)return null;
        return new Lookup(field,empty);
    }
    private boolean hasSize(ClassNode target,Map<String,MethodNode> contracts,FieldInsnNode field){
        for(MethodNode method:target.methods)if(contracts.containsKey(method.name+method.desc)&&method.desc.equals("()I")){
            List<AbstractInsnNode> c=ContractGraph.code(method);if(c.size()==4&&self(c.get(0))&&c.get(1) instanceof FieldInsnNode f
                    &&same(f,field)&&c.get(2) instanceof MethodInsnNode call&&call.name.equals("size")&&call.desc.equals("()I")
                    &&graph.assignable("L"+call.owner+";","Ljava/util/Map;")&&c.get(3).getOpcode()==Opcodes.IRETURN)return true;
        }
        return false;
    }
    private boolean finalMap(FieldInsnNode field){FieldNode declared=graph.field(field.owner,field.name,field.desc);return declared!=null&&(declared.access&Opcodes.ACC_FINAL)!=0&&graph.assignable(field.desc,"Ljava/util/Map;");}
    private static boolean same(FieldInsnNode a,FieldInsnNode b){return a.owner.equals(b.owner)&&a.name.equals(b.name)&&a.desc.equals(b.desc);}
    private static boolean self(AbstractInsnNode i){return i instanceof VarInsnNode v&&v.getOpcode()==Opcodes.ALOAD&&v.var==0;}
    private static boolean emptyCollection(MethodInsnNode method){return method.getOpcode()==Opcodes.INVOKESTATIC&&(
            (List.of("java/util/List","java/util/Set","java/util/Map").contains(method.owner)&&method.name.equals("of")&&Type.getArgumentTypes(method.desc).length==0)
                    ||method.owner.equals("java/util/Collections")&&List.of("emptyList","emptySet","emptyMap").contains(method.name)&&Type.getArgumentTypes(method.desc).length==0);}
    private static String mapPair(String signature){
        if(signature==null||!signature.startsWith("()Ljava/util/Map<"))return null;String args=signature.substring("()Ljava/util/Map<".length());
        int first=typeEnd(args,0),second=typeEnd(args,first);if(first<0||second<0||!args.substring(second).equals(">;"))return null;
        return variables(args.substring(0,first)+"|"+args.substring(first,second));
    }
    private static String lookupPair(String signature){
        if(signature==null||!signature.startsWith("("))return null;int end=typeEnd(signature,1);
        if(end<0||end>=signature.length()||signature.charAt(end)!=')')return null;return variables(signature.substring(1,end)+"|"+signature.substring(end+1));
    }
    private static int typeEnd(String value,int start){
        if(start<0||start>=value.length())return-1;int i=start;while(value.charAt(i)=='['){if(++i>=value.length())return-1;}
        if(value.charAt(i)=='T'){int end=value.indexOf(';',i);return end<0?-1:end+1;}
        if(value.charAt(i)!='L')return-1;int depth=0;for(;i<value.length();i++){char c=value.charAt(i);if(c=='<')depth++;if(c=='>')depth--;if(c==';'&&depth==0)return i+1;}return-1;
    }
    private static String variables(String value){Map<String,Integer> indices=new LinkedHashMap<>();Matcher matcher=Pattern.compile("T([A-Za-z_$][A-Za-z0-9_$]*);").matcher(value);StringBuffer result=new StringBuffer();while(matcher.find()){int index=indices.computeIfAbsent(matcher.group(1),ignored->indices.size());matcher.appendReplacement(result,"T"+index+";");}matcher.appendTail(result);return result.toString();}
}
