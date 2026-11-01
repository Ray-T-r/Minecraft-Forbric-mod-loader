/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.boot;

import java.nio.file.Path;
import java.util.List;
import net.forbric.api.Ecosystem;
import net.forbric.kernel.classloading.ForbricClassLoader;
import net.forbric.kernel.classloading.LoaderProbePolicy;
import net.forbric.kernel.discovery.LoaderKind;

/** Resource origins of explicitly supplied platform carriers, independent of guest loader-probe ownership. */
final class RuntimeJarProvenance {
 private RuntimeJarProvenance() {}
 static void register(ForbricClassLoader loader,List<Path> jars){
  for(Path jar:jars){LoaderProbePolicy.Family family=family(jar);if(family!=null)loader.addRuntimeJarFamily(jar,family);}
 }
 static LoaderProbePolicy.Family family(Path jar){
  List<LoaderKind> declared=MultiLoaderArbiter.loadersOf(jar);
  // A carrier can also contain Fabric metadata that exposes its platform API to the kernel launcher.
  // Its native manifest still identifies the origin of those API classes. Two native claims are ambiguous, and a
  // MinecraftForge one names no family (nothing loads it).
  List<LoaderKind> nativeFamilies=declared.stream().filter(e->e!=LoaderKind.FABRIC).toList();
  if(nativeFamilies.size()==1){Ecosystem ecosystem=nativeFamilies.getFirst().ecosystem();return ecosystem==null?null:LoaderProbePolicy.familyOf(ecosystem);}
  return nativeFamilies.isEmpty()&&declared.equals(List.of(LoaderKind.FABRIC))?LoaderProbePolicy.Family.FABRIC:null;
 }
}
