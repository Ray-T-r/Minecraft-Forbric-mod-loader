/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.tools;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;

/** Reconciles the writable runtime against the actual merged hierarchy and original peer contracts.
 * Missing accessors need a native implementation witness and unique correlated storage; ancestor bridges need
 * immutable layout, constructor and public-behavior equivalence. No implementation or field name is pinned. */
public final class RuntimeInteropPatcher {
    public static void main(String[] args)throws IOException{
        if(args.length<4){System.err.println("usage: RuntimeInteropPatcher <runtime.jar> <out.jar> <merged-game.jar> <peer-runtime.jar> [libraries-dir]");System.exit(2);}
        new RuntimeInteropPatcher().run(Path.of(args[0]),Path.of(args[1]),Path.of(args[2]),Path.of(args[3]),args.length>4?Path.of(args[4]):null);
    }
    void run(Path runtime,Path output,Path merged,Path peer,Path libraries)throws IOException{
        Map<String,byte[]> own=classes(runtime),other=classes(peer),game=classes(merged);
        ContractGraph graph=new ContractGraph(List.of(own,other,game),libraries);
        Map<String,ClassNode> edited=new LinkedHashMap<>();
        // The merged artifact carries exact native definitions as provenance. A changed ancestor establishes
        // the demand; the two runtime definitions themselves must prove the bridge before anything is emitted.
        try(ZipFile zip=new ZipFile(merged.toFile())){
            for(var entry:game.entrySet()){
                ClassNode current=parse(entry.getValue());ZipEntry reference=zip.getEntry("META-INF/forbric/native-reference/NEOFORGE/"+entry.getKey()+".class.bin");
                if(reference==null)continue;ClassNode original=parse(zip.getInputStream(reference).readAllBytes());
                if(Objects.equals(current.superName,original.superName)||!own.containsKey(current.superName)||!other.containsKey(original.superName))continue;
                ClassNode a=parse(own.get(current.superName)),b=parse(other.get(original.superName));
                if(!EquivalentSuperclassBridge.equivalent(a,b))throw new IOException("Cannot reconcile demanded runtime ancestors for "+current.name+": "+a.name+" versus "+b.name);
                ClassNode bridge=EquivalentSuperclassBridge.bridge(a,b);ClassNode previous=edited.putIfAbsent(a.name,bridge);
                if(previous!=null&&!previous.superName.equals(bridge.superName))throw new IOException("Incompatible peer ancestor demands on "+a.name);
                graph.replace(bridge);System.out.println("[interop-contract] "+a.name+" extends "+b.name+": immutable constructor/public-behavior proof");
            }
        }
        MapContractRepair accessors=new MapContractRepair(graph,List.of(own,other,game));List<String> missing=new ArrayList<>();
        for(var entry:own.entrySet()){
            ClassNode node=edited.getOrDefault(entry.getKey(),parse(entry.getValue()));graph.replace(node);
            int added=accessors.repair(node)+EmptyArrayContractBridge.repair(node,graph);if(added>0)edited.put(node.name,node);
            if((node.access&(Opcodes.ACC_ABSTRACT|Opcodes.ACC_INTERFACE))!=0)continue;
            for(MethodNode required:graph.interfaceContracts(node).values()){
                MethodNode method=graph.implementation(node.name,required.name,required.desc);
                if((required.access&Opcodes.ACC_ABSTRACT)!=0&&(method==null||(method.access&Opcodes.ACC_ABSTRACT)!=0))
                    missing.add(node.name+"#"+required.name+required.desc+" (no proved native accessor or implementation)");
            }
        }
        if(!missing.isEmpty())throw new IOException("Runtime cannot satisfy the merged interface contracts:\n"+String.join("\n",missing));
        Path absolute=output.toAbsolutePath();Files.createDirectories(absolute.getParent());Path temporary=Files.createTempFile(absolute.getParent(),"interop-contract-",".jar");
        try{
            try(ZipFile zip=new ZipFile(runtime.toFile());ZipOutputStream out=new ZipOutputStream(Files.newOutputStream(temporary))){
                for(Enumeration<? extends ZipEntry> entries=zip.entries();entries.hasMoreElements();){ZipEntry entry=entries.nextElement();byte[] bytes=zip.getInputStream(entry).readAllBytes();
                    String name=entry.getName().endsWith(".class")?entry.getName().substring(0,entry.getName().length()-6):null;
                    if(name!=null&&edited.containsKey(name)){ClassWriter writer=new ClassWriter(ClassWriter.COMPUTE_MAXS);edited.get(name).accept(writer);bytes=writer.toByteArray();}
                    out.putNextEntry(new ZipEntry(entry.getName()));out.write(bytes);out.closeEntry();
                }
            }
            Files.move(temporary,absolute,StandardCopyOption.REPLACE_EXISTING);
        }finally{Files.deleteIfExists(temporary);}
        System.out.println("[interop-contract] repaired "+edited.size()+" class(es) -> "+output);
    }
    private static ClassNode parse(byte[] bytes){ClassNode node=new ClassNode();new ClassReader(bytes).accept(node,0);return node;}
    private static Map<String,byte[]> classes(Path jar)throws IOException{
        Map<String,byte[]> result=new LinkedHashMap<>();try(ZipFile zip=new ZipFile(jar.toFile())){
            for(Enumeration<? extends ZipEntry> entries=zip.entries();entries.hasMoreElements();){ZipEntry entry=entries.nextElement();if(entry.getName().endsWith(".class")&&!entry.getName().startsWith("META-INF/"))result.put(entry.getName().substring(0,entry.getName().length()-6),zip.getInputStream(entry).readAllBytes());}
        }return result;
    }
}
