/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.mixin.weave;
import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.*;import java.util.*;import java.security.MessageDigest;
import org.junit.jupiter.api.Test;import org.junit.jupiter.api.io.TempDir;import org.objectweb.asm.*;
import net.fabricmc.api.EnvType;import net.forbric.api.Ecosystem;import net.forbric.kernel.mixin.*;

class MixinAbsorbedCallbackTransportWeaveTest implements Opcodes {
 @TempDir Path work;
 @Test void arbitraryExtractedHelperRunsTheUnchangedHostCallbackBeforeEventsWithWideArgumentsAndScopedRecursion()throws Exception {
  Map<String,String> code=new LinkedHashMap<>();
  code.put("audit.SeamProbe","""
   package audit;public class SeamProbe{public static java.util.List<String>log=new java.util.ArrayList<>();public void run(){
    for(int value:new int[]{1,-1,13,-99,2}){log.clear();try{unknown.Processor.process(new unknown.Box(),2L,value);}catch(RuntimeException failed){log.add("throw:"+failed.getMessage());}System.out.println("[Seam] "+value+" "+log);}
    log.clear();unknown.Carrier.apply(new unknown.Box(),0L,3);System.out.println("[Seam] direct "+log);}}
   """);
  code.put("unknown.Box","""
   package unknown;public class Box{public int value;public void set(long seed,int value){audit.SeamProbe.log.add("set:"+seed+":"+value);this.value=value;if(value==-99)throw new IllegalStateException("source");if(seed>0)Carrier.apply(this,0L,value+10);}public void post(){audit.SeamProbe.log.add("event:"+value);}}
   """);
  code.put("unknown.Carrier","package unknown;public class Carrier{public static void apply(Box box,long seed,int value){box.set(seed,value);box.post();}}");
  code.put("unknown.Processor","package unknown;public class Processor{public static void process(Box box,long seed,int value){if(value==-1)return;Carrier.apply(box,seed,value);}}");
  code.put("guest.Subscriber","""
   package guest;import unknown.*;@org.spongepowered.asm.mixin.Mixin(Processor.class)public class Subscriber{
    @org.spongepowered.asm.mixin.injection.Inject(method="process",at=@org.spongepowered.asm.mixin.injection.At(value="INVOKE",target="Lunknown/Box;set(JI)V",shift=org.spongepowered.asm.mixin.injection.At.Shift.AFTER))
    private static void after(Box box,long seed,int value,org.spongepowered.asm.mixin.injection.callback.CallbackInfo ci){if(!ci.getId().equals("process")||ci.isCancellable())throw new IllegalStateException("metadata");audit.SeamProbe.log.add("callback:"+seed+":"+value+":"+box.value);if(value==13)throw new IllegalStateException("callback");box.value+=100;}}
   """);
  List<Path> files=new ArrayList<>();for(var entry:code.entrySet()){Path file=work.resolve("src").resolve(entry.getKey().replace('.','/')+".java");Files.createDirectories(file.getParent());Files.writeString(file,entry.getValue());files.add(file);}
  byte[] source=source();Path binary=work.resolve("source.bin"),index=work.resolve("index.tsv"),config=work.resolve("seam.mixins.json");Files.write(binary,source);Files.writeString(index,"# forbric-native-reference-v1\nunknown/Processor\t"+HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(source))+"\n");Files.writeString(config,"{\"required\":true,\"compatibilityLevel\":\"JAVA_25\",\"package\":\"guest\",\"mixins\":[\"Subscriber\"],\"injectors\":{\"defaultRequire\":1}}");
  Path fixture=WeaveHarness.fixture(work,"unknown",files,Map.of("seam.mixins.json",config,"META-INF/forbric/native-reference/FABRIC/index.tsv",index,"META-INF/forbric/native-reference/FABRIC/unknown/Processor.class.bin",binary),List.of("-g"));
  var configs=List.of(new WeaveHarness.Config("seam.mixins.json","unknown-callback",Ecosystem.FABRIC));
  var result=WeaveHarness.run(work,"on",fixture,configs,List.of(),EnvType.SERVER,"audit.SeamProbe","run",Map.of());
  assertTrue(result.printed("[Seam] 1 [set:2:1, set:0:11, event:11, callback:2:1:11, event:111]"),result.describe());
  assertTrue(result.printed("[Seam] -1 []"),result.describe());
  assertTrue(result.printed("[Seam] 13 [set:2:13, set:0:23, event:23, callback:2:13:23, throw:callback]"),result.describe());
  assertTrue(result.printed("[Seam] -99 [set:2:-99, throw:source]"),result.describe());
  assertTrue(result.printed("[Seam] 2 [set:2:2, set:0:12, event:12, callback:2:2:12, event:112]"),result.describe());
  assertTrue(result.printed("[Seam] direct [set:0:3, event:3]"),result.describe());
  WeaveHarness.assertWovenAndVerified(result,"unknown/Processor",fixture);
  Path secondSource=work.resolve("src/guestsecond/SubscriberTwo.java");Files.createDirectories(secondSource.getParent());Files.writeString(secondSource,code.get("guest.Subscriber").replace("package guest;","package guestsecond;").replace("class Subscriber{","class SubscriberTwo{").replace("callback:","second:").replace("box.value+=100","box.value+=1000"));
  Path secondConfig=work.resolve("second.mixins.json");Files.writeString(secondConfig,"{\"required\":true,\"compatibilityLevel\":\"JAVA_25\",\"package\":\"guestsecond\",\"mixins\":[\"SubscriberTwo\"]}");
  files.add(secondSource);Path two=WeaveHarness.fixture(work,"two-callbacks",files,Map.of("seam.mixins.json",config,"second.mixins.json",secondConfig,"META-INF/forbric/native-reference/FABRIC/index.tsv",index,"META-INF/forbric/native-reference/FABRIC/unknown/Processor.class.bin",binary),List.of("-g"));
  var twoRun=WeaveHarness.run(work,"two",two,List.of(configs.getFirst(),new WeaveHarness.Config("second.mixins.json","unknown-second",Ecosystem.FABRIC)),List.of(),EnvType.SERVER,"audit.SeamProbe","run",Map.of());
  assertTrue(twoRun.printed("[Seam] 1 [set:2:1, set:0:11, event:11, callback:2:1:11, second:2:1:111, event:1111]"),twoRun.describe());
  Files.writeString(secondSource,Files.readString(secondSource).replace("@org.spongepowered.asm.mixin.Mixin(Processor.class)","@org.spongepowered.asm.mixin.Mixin(value=Processor.class,priority=2000)"));
  Path priorities=WeaveHarness.fixture(work,"priority-callbacks",files,Map.of("seam.mixins.json",config,"second.mixins.json",secondConfig,"META-INF/forbric/native-reference/FABRIC/index.tsv",index,"META-INF/forbric/native-reference/FABRIC/unknown/Processor.class.bin",binary),List.of("-g"));
  var priorityRun=WeaveHarness.run(work,"priorities",priorities,List.of(configs.getFirst(),new WeaveHarness.Config("second.mixins.json","unknown-second",Ecosystem.FABRIC)),List.of(),EnvType.SERVER,"audit.SeamProbe","run",Map.of());
  assertTrue(priorityRun.printed("[Seam] 1 [set:2:1, set:0:11, event:11, second:2:1:11, callback:2:1:1011, event:1111]"),priorityRun.describe());
  files.remove(secondSource);
  var off=WeaveHarness.run(work,"off",fixture,configs,List.of(),EnvType.SERVER,"audit.SeamProbe","run",Map.of(MergedBaseAbsorbedCalls.PROPERTY,"off"));
  assertTrue(off.printed("[Seam] 1 [set:2:1, set:0:11, event:11, event:11]"),off.describe());
  assertFalse(off.printed("callback:2:"),off.describe());
  Path observer=work.resolve("src/guest2/Observer.java");Files.createDirectories(observer.getParent());Files.writeString(observer,"""
   package guest2;@org.spongepowered.asm.mixin.Mixin(unknown.Carrier.class)public class Observer{
    @org.spongepowered.asm.mixin.injection.Inject(method="apply",at=@org.spongepowered.asm.mixin.injection.At("HEAD"))
    private static void added(unknown.Box box,long seed,int value,org.spongepowered.asm.mixin.injection.callback.CallbackInfo ci){audit.SeamProbe.log.add("observer");}}
   """);files.add(observer);Path observerConfig=work.resolve("observer.mixins.json");Files.writeString(observerConfig,"{\"required\":true,\"compatibilityLevel\":\"JAVA_25\",\"package\":\"guest2\",\"mixins\":[\"Observer\"]}");
  Path changed=WeaveHarness.fixture(work,"changed-helper",files,Map.of("seam.mixins.json",config,"observer.mixins.json",observerConfig,"META-INF/forbric/native-reference/FABRIC/index.tsv",index,"META-INF/forbric/native-reference/FABRIC/unknown/Processor.class.bin",binary),List.of("-g"));
  var mutated=WeaveHarness.run(work,"changed-helper",changed,List.of(configs.getFirst(),new WeaveHarness.Config("observer.mixins.json","unknown-observer",Ecosystem.FABRIC)),List.of(),EnvType.SERVER,"audit.SeamProbe","run",Map.of());
  assertTrue(mutated.printed("[Seam] 1 [throw:Extracted callback has no final body witness:"),mutated.describe());
  assertFalse(mutated.printed("callback:2:"),mutated.describe());
  assertTrue(mutated.printed("[Seam] direct [observer, set:0:3, event:3]"),mutated.describe());
  files.remove(observer);
  Files.writeString(work.resolve("src/guest/Subscriber.java"),code.get("guest.Subscriber").replace("method=\"process\"", "id=\"custom\",method=\"process\"").replace("value=\"INVOKE\"", "id=\"point\",value=\"INVOKE\"").replace("equals(\"process\")", "equals(\"custom:point\")"));
  Path identified=WeaveHarness.fixture(work,"identified",files,Map.of("seam.mixins.json",config,"META-INF/forbric/native-reference/FABRIC/index.tsv",index,"META-INF/forbric/native-reference/FABRIC/unknown/Processor.class.bin",binary),List.of("-g"));
  var identifiedRun=WeaveHarness.run(work,"identified",identified,configs,List.of(),EnvType.SERVER,"audit.SeamProbe","run",Map.of());
  assertTrue(identifiedRun.printed("[Seam] 1 [set:2:1, set:0:11, event:11, callback:2:1:11, event:111]"),identifiedRun.describe());
  Files.writeString(work.resolve("src/unknown/Processor.java"),code.get("unknown.Processor").replace("public static void process", "public int identity=42;public void process"));
  Files.writeString(work.resolve("src/guest/Subscriber.java"),code.get("guest.Subscriber").replace("public class Subscriber{", "public class Subscriber{@org.spongepowered.asm.mixin.Shadow private int identity;").replace("private static void after", "private void after").replace("box.value+=100", "box.value+=identity"));
  Files.writeString(work.resolve("src/audit/SeamProbe.java"),code.get("audit.SeamProbe").replace("unknown.Processor.process", "new unknown.Processor().process"));
  source=source(false);Files.write(binary,source);Files.writeString(index,"# forbric-native-reference-v1\nunknown/Processor\t"+HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(source))+"\n");
  Path instance=WeaveHarness.fixture(work,"instance",files,Map.of("seam.mixins.json",config,"META-INF/forbric/native-reference/FABRIC/index.tsv",index,"META-INF/forbric/native-reference/FABRIC/unknown/Processor.class.bin",binary),List.of("-g"));
  var instanceRun=WeaveHarness.run(work,"instance",instance,configs,List.of(),EnvType.SERVER,"audit.SeamProbe","run",Map.of());
  assertTrue(instanceRun.printed("[Seam] 1 [set:2:1, set:0:11, event:11, callback:2:1:11, event:53]"),instanceRun.describe());
  assertTrue(instanceRun.printed("[Seam] 2 [set:2:2, set:0:12, event:12, callback:2:2:12, event:54]"),instanceRun.describe());
  WeaveHarness.assertWovenAndVerified(instanceRun,"unknown/Processor",instance);
  Files.writeString(work.resolve("src/guest/Subscriber.java"),code.get("guest.Subscriber"));
  Path staticObserver=WeaveHarness.fixture(work,"static-on-instance",files,Map.of("seam.mixins.json",config,"META-INF/forbric/native-reference/FABRIC/index.tsv",index,"META-INF/forbric/native-reference/FABRIC/unknown/Processor.class.bin",binary),List.of("-g"));
  var staticRun=WeaveHarness.run(work,"static-on-instance",staticObserver,configs,List.of(),EnvType.SERVER,"audit.SeamProbe","run",Map.of());
  assertTrue(staticRun.printed("[Seam] 1 [set:2:1, set:0:11, event:11, callback:2:1:11, event:111]"),staticRun.describe());
  WeaveHarness.assertWovenAndVerified(staticRun,"unknown/Processor",staticObserver);



 }
 static byte[] source(){return source(true);}
 static byte[] source(boolean stat){ClassWriter w=new ClassWriter(ClassWriter.COMPUTE_FRAMES|ClassWriter.COMPUTE_MAXS);w.visit(V21,ACC_PUBLIC,"unknown/Processor",null,"java/lang/Object",null);MethodVisitor m=w.visitMethod(ACC_PUBLIC|(stat?ACC_STATIC:0),"process","(Lunknown/Box;JI)V",null,null);m.visitCode();LabelNodeMarker(m,stat?3:4);m.visitVarInsn(ALOAD,stat?0:1);m.visitVarInsn(LLOAD,stat?1:2);m.visitVarInsn(ILOAD,stat?3:4);m.visitMethodInsn(INVOKEVIRTUAL,"unknown/Box","set","(JI)V",false);m.visitInsn(RETURN);m.visitMaxs(0,0);m.visitEnd();w.visitEnd();return w.toByteArray();}
 private static void LabelNodeMarker(MethodVisitor m,int value){Label next=new Label();m.visitVarInsn(ILOAD,value);m.visitInsn(ICONST_M1);m.visitJumpInsn(IF_ICMPNE,next);m.visitInsn(RETURN);m.visitLabel(next);}
}
