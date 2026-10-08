/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.loader.impl.launch;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;

/** Legacy Knot has no final-definition state-composition certifier. Refuse an explicit merged-base
 * obligation before game definitions, rather than launch with a discarded native ancestor's mutable state. */
final class LegacyAncestorContracts {
    static final String MANIFEST="META-INF/forbric/required-ancestor-compositions.tsv";
    static void verifyLaunchInputs(String side){
        LinkedHashSet<Path> inputs=new LinkedHashSet<>();
        for(String property:List.of("fabric.gameJarPath","fabric.gameJarPath."+side,"forbric.gameJar")){
            String path=System.getProperty(property);if(path!=null&&!path.isBlank())inputs.add(Path.of(path));
        }
        for(String path:System.getProperty("java.class.path","").split(java.util.regex.Pattern.quote(File.pathSeparator)))if(!path.isBlank())inputs.add(Path.of(path));
        try{verify(inputs);}catch(IOException e){throw new IllegalStateException("Cannot verify merged-base ancestor contracts before legacy launch",e);}
    }
    static void verify(Collection<Path> inputs)throws IOException{
        for(Path input:inputs){String text=null;
            if(Files.isDirectory(input)){Path manifest=input.resolve(MANIFEST);if(Files.isRegularFile(manifest))text=Files.readString(manifest);}
            else if(Files.isRegularFile(input)&&input.toString().endsWith(".jar"))try(ZipFile zip=new ZipFile(input.toFile())){ZipEntry entry=zip.getEntry(MANIFEST);if(entry!=null)text=new String(zip.getInputStream(entry).readAllBytes(),StandardCharsets.UTF_8);}
            if(text==null)continue;List<String> obligations=new ArrayList<>();
            for(String line:text.split("\\R")){if(line.isBlank()||line.startsWith("#"))continue;String[] columns=line.split("\\t",-1);if(columns.length!=3||Arrays.stream(columns).anyMatch(String::isBlank))throw new IOException("Malformed ancestor-composition manifest in "+input+": "+line);obligations.add(columns[0]+" retains "+columns[1]+"; native state of "+columns[2]+" requires final-definition proof");}
            if(!obligations.isEmpty())throw new IllegalStateException("Legacy loader cannot certify the required mutable ancestor composition in "+input+". Use the kernel loader with its registered composition proofs, or a base without this obligation:\n"+String.join("\n",obligations));
        }
    }
    private LegacyAncestorContracts(){}
}
