package net.forbric.kernel.classloading;

import java.nio.file.Files;
import java.nio.file.Path;
import java.net.URL;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import static org.junit.jupiter.api.Assertions.*;

class DefinedClassEvidenceTest {
 @TempDir Path temporary;
 @Test void recordsFinalMixinOutputAndNotPreviewBytes() throws Exception {
  Path classes=Files.createDirectory(temporary.resolve("input")), output=temporary.resolve("evidence");
  Files.createDirectories(classes.resolve("game"));Files.write(classes.resolve("game/Final.class"),type("game/Final"));
  String old=System.getProperty(DefinedClassEvidence.PROPERTY);System.setProperty(DefinedClassEvidence.PROPERTY,output.toString());
  try(var loader=new ForbricClassLoader(new URL[]{classes.toUri().toURL()},getClass().getClassLoader())) {
   byte[] finalBytes=withField("game/Final");
   loader.setMixinTransformer((name,bytes)->name.equals("game.Final")?finalBytes:bytes);
   assertNotNull(loader.getPreMixinClassBytes("game.Final"));
   Path session;try(var sessions=Files.list(output)){session=sessions.findFirst().orElseThrow();}
   assertFalse(Files.exists(session.resolve("game/Final.class")),"preflight must not be recorded as defined");
   assertNotNull(loader.loadClass("game.Final").getField("actuallyWoven"));
   assertArrayEquals(finalBytes,Files.readAllBytes(session.resolve("game/Final.class")));
  } finally {if(old==null)System.clearProperty(DefinedClassEvidence.PROPERTY);else System.setProperty(DefinedClassEvidence.PROPERTY,old);}
 }
 @Test void recordsOnlySuccessfulDefinitionsAndEachLoaderHasItsOwnSession() throws Exception {
  String old=System.getProperty(DefinedClassEvidence.PROPERTY);
  System.setProperty(DefinedClassEvidence.PROPERTY, temporary.toString());
  try {
   try (var loader=new ForbricClassLoader(new URL[0],getClass().getClassLoader())) {
    byte[] bytes=type("game/Evidence");
    Class<?> first=loader.defineRuntimeClass("game.Evidence",bytes);
    assertSame(first,loader.defineRuntimeClass("game.Evidence",type("game/Wrong")));
    assertThrows(ClassFormatError.class,()->loader.defineRuntimeClass("game.Broken",new byte[]{0,1}));
   }
   try (var loader=new ForbricClassLoader(new URL[0],getClass().getClassLoader())) {
    loader.defineRuntimeClass("game.Evidence",type("game/Evidence"));
   }
   try(var sessions=Files.list(temporary)) {
    var all=sessions.toList();assertEquals(2,all.size());
    for(Path session:all) {
     var manifest=Files.readAllLines(session.resolve("definitions.tsv"));
     assertEquals(1,manifest.stream().filter(s->!s.startsWith("#")).count());
     assertTrue(manifest.get(2).matches("game/Evidence\\t[0-9a-f]{64}"));
     assertArrayEquals(type("game/Evidence"),Files.readAllBytes(session.resolve("game/Evidence.class")));
     assertFalse(Files.exists(session.resolve("game/Broken.class")));
    }
   }
  } finally { if(old==null) System.clearProperty(DefinedClassEvidence.PROPERTY); else System.setProperty(DefinedClassEvidence.PROPERTY,old); }
 }
 @Test void absentOptInDoesNotCreateEvidence() throws Exception {
  String old=System.getProperty(DefinedClassEvidence.PROPERTY);System.clearProperty(DefinedClassEvidence.PROPERTY);
  try(var loader=new ForbricClassLoader(new URL[0],getClass().getClassLoader())) {
   loader.defineRuntimeClass("game.NoEvidence",type("game/NoEvidence"));
   try(var files=Files.list(temporary)){assertEquals(0,files.count());}
  } finally { if(old!=null)System.setProperty(DefinedClassEvidence.PROPERTY,old); }
 }
 private static byte[] type(String name) {
  ClassWriter w=new ClassWriter(0);w.visit(Opcodes.V17,Opcodes.ACC_PUBLIC,name,null,"java/lang/Object",null);w.visitEnd();return w.toByteArray();
 }
 private static byte[] withField(String name) {
  ClassWriter w=new ClassWriter(0);w.visit(Opcodes.V17,Opcodes.ACC_PUBLIC,name,null,"java/lang/Object",null);
  w.visitField(Opcodes.ACC_PUBLIC,"actuallyWoven","I",null,null).visitEnd();w.visitEnd();return w.toByteArray();
 }
}
