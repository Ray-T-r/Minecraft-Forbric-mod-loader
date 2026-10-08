/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.tools;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;

/** The actual class/interface declarations supplied to a merge, plus the JDK and explicitly supplied libraries. */
final class ContractGraph {
    private final Map<String,byte[]> bytes=new LinkedHashMap<>();
    private final Map<String,ClassNode> nodes=new HashMap<>();
    private final List<Path> libraries;
    ContractGraph(List<Map<String,byte[]>> sets){for(Map<String,byte[]> set:sets)set.forEach(bytes::putIfAbsent);libraries=List.of();}
    ContractGraph(List<Map<String,byte[]>> sets,Path libraries)throws IOException{
        for(Map<String,byte[]> set:sets)set.forEach(bytes::putIfAbsent);
        if(libraries!=null&&Files.isDirectory(libraries))try(var walk=Files.walk(libraries)){this.libraries=walk.filter(p->p.toString().endsWith(".jar")).sorted().toList();}
        else this.libraries=libraries!=null&&Files.isRegularFile(libraries)?List.of(libraries):List.of();
    }

    ClassNode node(String name){
        if(name==null)return null;if(nodes.containsKey(name))return nodes.get(name);
        byte[] data=bytes.get(name);
        if(data==null)try(InputStream stream=ClassLoader.getSystemResourceAsStream(name+".class")){if(stream!=null)data=stream.readAllBytes();}catch(IOException ignored){}
        if(data==null)for(Path jar:libraries)try(ZipFile zip=new ZipFile(jar.toFile())){ZipEntry entry=zip.getEntry(name+".class");if(entry!=null){data=zip.getInputStream(entry).readAllBytes();break;}}catch(IOException ignored){}
        ClassNode node=null;if(data!=null){node=new ClassNode();new ClassReader(data).accept(node,0);}nodes.put(name,node);return node;
    }
    void replace(ClassNode node){nodes.put(node.name,node);}
    boolean assignable(String from,String to){return assignable(Type.getType(from),Type.getType(to));}
    boolean assignable(Type from,Type to){
        if(from.equals(to))return true;
        if(to.getSort()==Type.OBJECT&&to.getInternalName().equals("java/lang/Object"))return from.getSort()==Type.OBJECT||from.getSort()==Type.ARRAY;
        if(from.getSort()==Type.ARRAY&&to.getSort()==Type.ARRAY)return assignable(Type.getType(from.getDescriptor().substring(1)),Type.getType(to.getDescriptor().substring(1)));
        if(from.getSort()!=Type.OBJECT||to.getSort()!=Type.OBJECT)return false;
        Set<String> visited=new HashSet<>();Deque<String> queue=new ArrayDeque<>();queue.add(from.getInternalName());
        while(!queue.isEmpty()){String name=queue.remove();if(name.equals(to.getInternalName()))return true;if(!visited.add(name))continue;ClassNode n=node(name);if(n==null)continue;if(n.superName!=null)queue.add(n.superName);queue.addAll(n.interfaces);}
        return false;
    }
    MethodNode method(String owner,String name,String desc){return method(owner,name,desc,new HashSet<>());}
    private MethodNode method(String owner,String name,String desc,Set<String> seen){
        if(owner==null||!seen.add(owner))return null;ClassNode n=node(owner);if(n==null)return null;
        for(MethodNode m:n.methods)if(m.name.equals(name)&&m.desc.equals(desc))return m;
        MethodNode found=method(n.superName,name,desc,seen);if(found!=null)return found;
        for(String itf:n.interfaces){found=method(itf,name,desc,seen);if(found!=null)return found;}return null;
    }
    /** JVM class-method/default resolution. An unrelated interface's abstract declaration does not hide
     * a concrete inherited default; a more specific interface declaration does. */
    MethodNode implementation(String owner,String name,String desc){
        ClassNode start=node(owner),cursor=start;Set<String> ancestors=new HashSet<>();
        while(cursor!=null&&ancestors.add(cursor.name)){
            MethodNode own=ownMethod(cursor,name,desc);
            if(own!=null&&(own.access&(Opcodes.ACC_PRIVATE|Opcodes.ACC_STATIC))==0){
                // Only a callable public instance method satisfies an interface slot. An abstract or
                // inaccessible instance declaration blocks a default; private/static methods do not override it.
                return (own.access&(Opcodes.ACC_PUBLIC|Opcodes.ACC_ABSTRACT))==Opcodes.ACC_PUBLIC?own:null;
            }
            cursor=node(cursor.superName);
        }
        Map<String,MethodNode> declarations=new LinkedHashMap<>();Set<String> seen=new HashSet<>();Deque<String> pending=new ArrayDeque<>();cursor=start;
        while(cursor!=null){pending.addAll(cursor.interfaces);cursor=node(cursor.superName);}
        while(!pending.isEmpty()){String type=pending.remove();if(!seen.add(type))continue;ClassNode itf=node(type);if(itf==null)continue;MethodNode declared=ownMethod(itf,name,desc);if(declared!=null&&(declared.access&(Opcodes.ACC_STATIC|Opcodes.ACC_PRIVATE))==0)declarations.put(type,declared);pending.addAll(itf.interfaces);}
        List<String> maximal=declarations.keySet().stream().filter(type->declarations.keySet().stream().noneMatch(other->!other.equals(type)&&assignable("L"+other+";","L"+type+";"))).toList();
        List<MethodNode> defaults=maximal.stream().map(declarations::get).filter(m->(m.access&Opcodes.ACC_ABSTRACT)==0).toList();return defaults.size()==1?defaults.get(0):null;
    }
    FieldNode field(String owner,String name,String desc){
        Set<String> seen=new HashSet<>();while(owner!=null&&seen.add(owner)){ClassNode n=node(owner);if(n==null)return null;for(FieldNode f:n.fields)if(f.name.equals(name)&&f.desc.equals(desc))return f;owner=n.superName;}return null;
    }
    Map<String,MethodNode> interfaceContracts(ClassNode implementation){
        Map<String,MethodNode> methods=new LinkedHashMap<>();Set<String> seen=new HashSet<>();Deque<String> queue=new ArrayDeque<>();
        ClassNode cursor=implementation;while(cursor!=null){queue.addAll(cursor.interfaces);cursor=node(cursor.superName);}
        while(!queue.isEmpty()){String name=queue.remove();if(!seen.add(name))continue;ClassNode itf=node(name);if(itf==null)continue;
            for(MethodNode method:itf.methods)if((method.access&(Opcodes.ACC_STATIC|Opcodes.ACC_PRIVATE))==0&&!method.name.startsWith("<"))methods.putIfAbsent(method.name+method.desc,method);
            queue.addAll(itf.interfaces);
        }
        return methods;
    }
    static List<AbstractInsnNode> code(MethodNode method){List<AbstractInsnNode> result=new ArrayList<>();for(AbstractInsnNode i:method.instructions)if(i.getOpcode()>=0)result.add(i);return result;}
    static MethodNode ownMethod(ClassNode node,String name,String desc){return node==null?null:node.methods.stream().filter(m->m.name.equals(name)&&m.desc.equals(desc)).findFirst().orElse(null);}
}
