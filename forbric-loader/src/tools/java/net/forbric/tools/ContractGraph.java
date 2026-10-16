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
    ContractGraph(List<Map<String,byte[]>> sets){this(sets,List.of());}
    /** {@code libraries}: exactly the jars a class outside the supplied sets and the JDK may come from, in the order
     * they answer -- the base version's own libraries, as {@link #libraries(String)} reads them; null for none. */
    ContractGraph(List<Map<String,byte[]>> sets,List<Path> libraries){
        for(Map<String,byte[]> set:sets)set.forEach(bytes::putIfAbsent);
        this.libraries=libraries==null?List.of():List.copyOf(libraries);
    }

    /**
     * The library argument every merge tool takes: the base version's libraries, each jar named, joined with the
     * platform's path separator, in the order its version JSON lists them. Exactly those, and nothing else.
     *
     * <p>It used to be a directory, walked, with the first jar in sorted path order answering for a class. A player's
     * {@code libraries} folder holds every version any launcher ever fetched there, so the proof read whatever
     * sorted first -- measured: {@code guava-25.1-jre} answering for {@code ImmutableList} in a folder that also had
     * 26.2's own {@code guava-33.6.0-jre} -- and a merge's verdict depended on what else happened to be installed.
     * A directory is therefore refused rather than walked, and a named jar that is not there is an error, not a
     * class the proof quietly cannot read.
     */
    static List<Path> libraries(String argument){
        if(argument==null||argument.isEmpty())return List.of();
        List<Path> jars=new ArrayList<>();
        for(String entry:argument.split(java.util.regex.Pattern.quote(File.pathSeparator))){
            if(entry.isEmpty())continue;
            Path jar=Path.of(entry);
            if(Files.isDirectory(jar))throw new IllegalArgumentException("a library directory is not a library list: "+jar+" (name each jar the base version's JSON lists, joined with '"+File.pathSeparator+"')");
            if(!Files.isRegularFile(jar))throw new IllegalArgumentException("library not found: "+jar);
            jars.add(jar);
        }
        return List.copyOf(jars);
    }

    /** Classes asked for and not found anywhere since the last {@link #takeMisses}: what a failed proof could not read. */
    private final Set<String> misses=new LinkedHashSet<>();
    Set<String> takeMisses(){Set<String> out=new LinkedHashSet<>(misses);misses.clear();return out;}
    ClassNode node(String name){
        if(name==null)return null;if(nodes.containsKey(name)){ClassNode cached=nodes.get(name);if(cached==null)misses.add(name);return cached;}
        byte[] data=bytes.get(name);
        if(data==null)try(InputStream stream=ClassLoader.getSystemResourceAsStream(name+".class")){if(stream!=null)data=stream.readAllBytes();}catch(IOException ignored){}
        // A named library that cannot be read is a broken input, not a class the proof may go without.
        if(data==null)for(Path jar:libraries)try(ZipFile zip=new ZipFile(jar.toFile())){ZipEntry entry=zip.getEntry(name+".class");if(entry!=null){data=zip.getInputStream(entry).readAllBytes();break;}}catch(IOException unreadable){throw new UncheckedIOException("cannot read library "+jar,unreadable);}
        ClassNode node=null;if(data!=null){node=new ClassNode();new ClassReader(data).accept(node,0);}else misses.add(name);nodes.put(name,node);return node;
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
