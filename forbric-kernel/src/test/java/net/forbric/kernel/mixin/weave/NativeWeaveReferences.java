/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.mixin.weave;
import java.nio.file.*;import java.util.*;import java.util.zip.*;import java.security.MessageDigest;
/** Synthetic source classes are indexed just like real merged-base originals; name-shaped fixtures carry no policy. */
final class NativeWeaveReferences {
 private NativeWeaveReferences(){}
 static Map<String,byte[]> classes(Path jar)throws Exception{Map<String,byte[]> classes=new LinkedHashMap<>();try(ZipFile zip=new ZipFile(jar.toFile())){for(var entry:Collections.list(zip.entries()))if(entry.getName().endsWith(".class"))try(var in=zip.getInputStream(entry)){classes.put(entry.getName().substring(0,entry.getName().length()-6),in.readAllBytes());}}return classes;}
 static Path with(Path work,Path current,Map<String,byte[]> original)throws Exception{return withEcosystems(work,current,Map.of("FABRIC",original));}
 /** The fixture with native references for each named ecosystem ({@code FABRIC}, {@code NEOFORGE}, ...), each with its index. */
 static Path withEcosystems(Path work,Path current,Map<String,Map<String,byte[]>> byEcosystem)throws Exception{
  Path output=work.resolve(current.getFileName().toString()+"-indexed.jar");
  try(ZipOutputStream out=new ZipOutputStream(Files.newOutputStream(output));ZipFile zip=new ZipFile(current.toFile())){
   for(var entry:Collections.list(zip.entries())){out.putNextEntry(new ZipEntry(entry.getName()));try(var in=zip.getInputStream(entry)){in.transferTo(out);}out.closeEntry();}
   for(var ecosystem:new TreeMap<>(byEcosystem).entrySet()){String prefix="META-INF/forbric/native-reference/"+ecosystem.getKey()+"/";
    StringBuilder index=new StringBuilder("# forbric-native-reference-v1\n");for(var entry:ecosystem.getValue().entrySet()){
     index.append(entry.getKey()).append('\t').append(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(entry.getValue()))).append('\n');
     out.putNextEntry(new ZipEntry(prefix+entry.getKey()+".class.bin"));out.write(entry.getValue());out.closeEntry();
    }out.putNextEntry(new ZipEntry(prefix+"index.tsv"));out.write(index.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));out.closeEntry();}
  }return output;
 }
}
