package net.forbric.kernel.classloading;

import java.nio.file.Files;
import java.nio.file.Path;
import java.net.URL;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
import java.util.stream.Collectors;
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
   assertFalse(rows(session).containsKey("game/Final"),"preflight must not be recorded as defined");
   assertFalse(Files.exists(session.resolve("blobs")),"preflight must not be recorded as defined");
   assertNotNull(loader.loadClass("game.Final").getField("actuallyWoven"));
   assertArrayEquals(finalBytes,recorded(session,"game/Final"));
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
     assertEquals(DefinedClassEvidence.HEADER,manifest.get(0));
     assertEquals(1,manifest.stream().filter(s->!s.startsWith("#")).count());
     assertTrue(manifest.get(2).matches("game/Evidence\\t[0-9a-f]{64}"));
     assertArrayEquals(type("game/Evidence"),recorded(session,"game/Evidence"));
     assertFalse(rows(session).containsKey("game/Broken"));
     try(var blobs=Files.list(session.resolve("blobs"))){assertEquals(1,blobs.count(),"only the successful definition has bytes");}
    }
   }
  } finally { if(old==null) System.clearProperty(DefinedClassEvidence.PROPERTY); else System.setProperty(DefinedClassEvidence.PROPERTY,old); }
 }
 /**
  * {@code a/a} and {@code a/A} are two classes to the JVM and one file name on a case-insensitive filesystem. Naming
  * evidence after the class made the second write collide, and the exception escaped a definition that had already
  * succeeded. Content addressing records both, and the loader still returns both classes.
  */
 @Test void namesThatDifferOnlyInCaseAreBothDefinedAndBothRecorded() throws Exception {
  String old=System.getProperty(DefinedClassEvidence.PROPERTY);
  System.setProperty(DefinedClassEvidence.PROPERTY, temporary.toString());
  try (var loader=new ForbricClassLoader(new URL[0],getClass().getClassLoader())) {
   byte[] lower=withField("game/a"), upper=type("game/A");
   assertEquals("game.a",loader.defineRuntimeClass("game.a",lower).getName());
   assertEquals("game.A",loader.defineRuntimeClass("game.A",upper).getName());
   Path session;try(var sessions=Files.list(temporary)){session=sessions.findFirst().orElseThrow();}
   assertEquals(2,rows(session).size());
   assertArrayEquals(lower,recorded(session,"game/a"));
   assertArrayEquals(upper,recorded(session,"game/A"));
   assertFalse(Files.readString(session.resolve("definitions.tsv")).contains("#incomplete"));
  } finally { if(old==null) System.clearProperty(DefinedClassEvidence.PROPERTY); else System.setProperty(DefinedClassEvidence.PROPERTY,old); }
 }
 /** A write that fails marks the session incomplete; it never turns a successful definition into an exception. */
 @Test void anUnwritableSessionIsMarkedIncompleteAndTheClassIsStillDefined() throws Exception {
  String old=System.getProperty(DefinedClassEvidence.PROPERTY);
  System.setProperty(DefinedClassEvidence.PROPERTY, temporary.toString());
  Path blobs=null;
  try (var loader=new ForbricClassLoader(new URL[0],getClass().getClassLoader())) {
   Path session;try(var sessions=Files.list(temporary)){session=sessions.findFirst().orElseThrow();}
   blobs=Files.createDirectory(session.resolve("blobs"));
   assertTrue(blobs.toFile().setWritable(false,false));
   Class<?> defined=assertDoesNotThrow(()->loader.defineRuntimeClass("game.Unrecorded",type("game/Unrecorded")));
   assertSame(defined,loader.loadClass("game.Unrecorded"));
   var manifest=Files.readAllLines(session.resolve("definitions.tsv"));
   assertTrue(manifest.stream().anyMatch(s->s.startsWith("#incomplete\tgame/Unrecorded\t")),manifest.toString());
   assertFalse(rows(session).containsKey("game/Unrecorded"));
  } finally {
   if(blobs!=null)blobs.toFile().setWritable(true,false);
   if(old==null) System.clearProperty(DefinedClassEvidence.PROPERTY); else System.setProperty(DefinedClassEvidence.PROPERTY,old);
  }
 }
 @Test void absentOptInDoesNotCreateEvidence() throws Exception {
  String old=System.getProperty(DefinedClassEvidence.PROPERTY);System.clearProperty(DefinedClassEvidence.PROPERTY);
  try(var loader=new ForbricClassLoader(new URL[0],getClass().getClassLoader())) {
   loader.defineRuntimeClass("game.NoEvidence",type("game/NoEvidence"));
   try(var files=Files.list(temporary)){assertEquals(0,files.count());}
  } finally { if(old!=null)System.setProperty(DefinedClassEvidence.PROPERTY,old); }
 }
 private static Map<String,String> rows(Path session) throws Exception {
  return Files.readAllLines(session.resolve("definitions.tsv")).stream().filter(s->!s.startsWith("#")&&!s.isBlank())
    .map(s->s.split("\t")).collect(Collectors.toMap(p->p[0],p->p[1]));
 }
 /** The bytes recorded for a name, checked against the hash the manifest claims for them. */
 private static byte[] recorded(Path session,String internal) throws Exception {
  String hash=rows(session).get(internal);assertNotNull(hash,internal+" has no manifest row");
  byte[] bytes=Files.readAllBytes(session.resolve("blobs").resolve(hash+".class"));
  assertEquals(hash,HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)));
  return bytes;
 }
 private static byte[] type(String name) {
  ClassWriter w=new ClassWriter(0);w.visit(Opcodes.V17,Opcodes.ACC_PUBLIC,name,null,"java/lang/Object",null);w.visitEnd();return w.toByteArray();
 }
 private static byte[] withField(String name) {
  ClassWriter w=new ClassWriter(0);w.visit(Opcodes.V17,Opcodes.ACC_PUBLIC,name,null,"java/lang/Object",null);
  w.visitField(Opcodes.ACC_PUBLIC,"actuallyWoven","I",null,null).visitEnd();w.visitEnd();return w.toByteArray();
 }
}
