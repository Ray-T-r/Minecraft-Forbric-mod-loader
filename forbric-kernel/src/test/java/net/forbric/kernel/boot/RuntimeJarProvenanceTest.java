package net.forbric.kernel.boot;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;
import net.forbric.kernel.classloading.*;
import net.forbric.kernel.transform.InjectorExecution;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RuntimeJarProvenanceTest {
 @Test void nativeCarrierMetadataSurvivesAFabricShimAndFilenameChanges(@TempDir Path work)throws Exception{
  assertEquals(LoaderProbePolicy.Family.NEOFORGE,RuntimeJarProvenance.family(jar(work,"unlisted-a",List.of("META-INF/neoforge.mods.toml","fabric.mod.json"),Map.of())));
  assertEquals(LoaderProbePolicy.Family.FORGE,RuntimeJarProvenance.family(jar(work,"unlisted-b",List.of("META-INF/mods.toml","fabric.mod.json"),Map.of())));
 }
 @Test void plainAndAmbiguousCarriersDoNotGuess(@TempDir Path work)throws Exception{
  assertNull(RuntimeJarProvenance.family(jar(work,"library",List.of(),Map.of())));
  assertNull(RuntimeJarProvenance.family(jar(work,"ambiguous",List.of("META-INF/neoforge.mods.toml","META-INF/mods.toml","fabric.mod.json"),Map.of())));
  assertNull(RuntimeJarProvenance.family(work.resolve("absent.jar")));
  assertEquals(LoaderProbePolicy.Family.FABRIC,RuntimeJarProvenance.family(jar(work,"fabric",List.of("fabric.mod.json"),Map.of())));
 }
 @Test void registrationIdentifiesResourcesWithoutChangingLoaderProbeOwnership(@TempDir Path work)throws Exception{
  Map<String,byte[]> classes=InjectorExecution.compile(work,Map.of("fixture.RuntimeOwned","package fixture; public class RuntimeOwned {}"));
  Path jar=jar(work,"renamed-platform",List.of("META-INF/neoforge.mods.toml","fabric.mod.json"),classes);
  try(var loader=new ForbricClassLoader(new java.net.URL[]{jar.toUri().toURL()},getClass().getClassLoader())){
   RuntimeJarProvenance.register(loader,List.of(jar));
   assertEquals(LoaderProbePolicy.Family.NEOFORGE,loader.familyOfResource("fixture.RuntimeOwned"));
   Class<?> type=loader.loadClass("fixture.RuntimeOwned");assertSame(loader,type.getClassLoader());
   assertNull(loader.familyOfClass("fixture.RuntimeOwned"));
  }
 }
 private static Path jar(Path work,String name,List<String> manifests,Map<String,byte[]> classes)throws Exception{
  Path jar=work.resolve(name+".jar");try(var out=new ZipOutputStream(Files.newOutputStream(jar))){
   for(String entry:manifests){out.putNextEntry(new ZipEntry(entry));out.write("{}".getBytes(java.nio.charset.StandardCharsets.UTF_8));out.closeEntry();}
   for(var entry:classes.entrySet()){out.putNextEntry(new ZipEntry(entry.getKey()+".class"));out.write(entry.getValue());out.closeEntry();}
  }return jar;
 }
}
