package net.forbric.kernel.mixin.weave;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import net.fabricmc.api.EnvType;
import net.forbric.api.Ecosystem;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.*;

/** Real Mixin verifies semantic ordinals and enclosing-method sugar captures after a callee grows parameters. */
class MixinWidenedContractsWeaveTest {
 @TempDir Path work;
 @Test void sinkMappedOrdinalsAndNamedLocalExecuteTheirOriginalHandlersOnce() throws Exception {
  Path host=source("Host.java", """
   package net.minecraft.fixture.widen;
   public class Host {
    public static String FIRST, SECOND;
    static { SECOND=Factory.codec("second", 2); FIRST=Factory.codec("first", 1); }
    public static String local(){ Builder builder=new Builder("captured:"); return Factory.codec("value", 3); }
    public static String probe(){return FIRST+";"+SECOND+";"+local()+";calls="+Factory.calls;}
   }
   """);
  Path factory=source("Factory.java", """
   package net.minecraft.fixture.widen;
   public class Factory { public static int calls; public static String codec(String value,int added){calls++;return value+":"+added;} }
   """);
  Path builder=source("Builder.java", """
   package net.minecraft.fixture.widen;
   public class Builder { public final String text; public Builder(String text){this.text=text;} }
   """);
  Path mixin=source("ContractMixin.java", """
   package fixture.widen;
   import net.minecraft.fixture.widen.*;
   import org.spongepowered.asm.mixin.Mixin;
   import org.spongepowered.asm.mixin.injection.*;
   import com.llamalad7.mixinextras.injector.wrapoperation.*;
   import com.llamalad7.mixinextras.sugar.Local;
   @Mixin(Host.class) public class ContractMixin {
    @WrapOperation(method="<clinit>",at=@At(value="INVOKE",target="Lnet/minecraft/fixture/widen/Factory;codec(Ljava/lang/String;)Ljava/lang/String;",ordinal=0),require=0)
    private static String first(String value,Operation<String> op){return "F:"+op.call(value);}
    @WrapOperation(method="<clinit>",at=@At(value="INVOKE",target="Lnet/minecraft/fixture/widen/Factory;codec(Ljava/lang/String;)Ljava/lang/String;",ordinal=1),require=0)
    private static String second(String value,Operation<String> op){return "S:"+op.call(value);}
    @ModifyArg(method="local",at=@At(value="INVOKE",target="Lnet/minecraft/fixture/widen/Factory;codec(Ljava/lang/String;)Ljava/lang/String;"),index=0,require=0)
    private static String capture(String value,@Local(name="builder") Builder builder){return builder.text+value;}
   }
   """);
  String config="widened-contracts.mixins.json";
  Path json=source(config,"""
   {"required":false,"package":"fixture.widen","mixins":["ContractMixin"],"injectors":{"defaultRequire":0}}
   """);
  String owner="net/minecraft/fixture/widen/Host";
  ClassWriter nativeClass=new ClassWriter(ClassWriter.COMPUTE_MAXS);nativeClass.visit(Opcodes.V21,Opcodes.ACC_PUBLIC,owner,null,"java/lang/Object",null);
  for(String field:List.of("FIRST","SECOND"))nativeClass.visitField(Opcodes.ACC_PUBLIC|Opcodes.ACC_STATIC,field,"Ljava/lang/String;",null,null).visitEnd();
  MethodVisitor clinit=nativeClass.visitMethod(Opcodes.ACC_STATIC,"<clinit>","()V",null,null);clinit.visitCode();
  for(String field:List.of("FIRST","SECOND")){clinit.visitLdcInsn(field.toLowerCase());clinit.visitMethodInsn(Opcodes.INVOKESTATIC,"net/minecraft/fixture/widen/Factory","codec","(Ljava/lang/String;)Ljava/lang/String;",false);clinit.visitFieldInsn(Opcodes.PUTSTATIC,owner,field,"Ljava/lang/String;");}
  clinit.visitInsn(Opcodes.RETURN);clinit.visitMaxs(0,0);clinit.visitEnd();nativeClass.visitEnd();
  byte[] reference=nativeClass.toByteArray();Path binary=work.resolve("native.bin"),index=work.resolve("index.tsv");Files.write(binary,reference);
  Files.writeString(index,"# forbric-native-reference-v1\n"+owner+"\t"+HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(reference))+"\n");
  Path fixture=WeaveHarness.fixture(work,"widened-contracts",List.of(host,factory,builder,mixin,Path.of("src/runtime/java/net/forbric/kernel/runtime/KernelWrapOperations.java")),Map.of(config,json,"META-INF/forbric/native-reference/FABRIC/index.tsv",index,"META-INF/forbric/native-reference/FABRIC/"+owner+".class.bin",binary),List.of("-g"));
  var result=WeaveHarness.run(work,"on",fixture,config,"widenedcontracts",Ecosystem.FABRIC,EnvType.SERVER,owner.replace('/','.'),"probe",Map.of());
  assertTrue(result.printed(WeaveHarnessMain.DONE+" F:first:1;S:second:2;captured:value:3;calls=3"),result.describe());
  WeaveHarness.assertWovenAndVerified(result,owner,fixture);
  var control=WeaveHarness.run(work,"off",fixture,config,"widenedcontracts",Ecosystem.FABRIC,EnvType.SERVER,owner.replace('/','.'),"probe",Map.of("forbric.mixinAtWiden","off","forbric.wrapOperationShim","off"));
  assertTrue(control.printed(WeaveHarnessMain.DONE+" first:1;second:2;value:3;calls=3"),control.describe());
 }
 private Path source(String name,String text)throws Exception{Path path=work.resolve(name);Files.writeString(path,text);return path;}
}
