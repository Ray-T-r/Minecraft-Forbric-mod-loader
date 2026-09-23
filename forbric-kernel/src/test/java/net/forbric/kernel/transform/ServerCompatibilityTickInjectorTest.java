package net.forbric.kernel.transform;
import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.*;
import java.util.zip.ZipFile;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
class ServerCompatibilityTickInjectorTest {
 @Test void everyNormalReturnInTheActualServerHasOneCheckAndSecondPassDoesNothing() throws Exception {
  Path jar=Path.of(System.getenv().getOrDefault("FORBRIC_OLD","../forbric-loader"),"run/merged-base/patched-mc-merged-26.2.jar");
  org.junit.jupiter.api.Assumptions.assumeTrue(Files.isRegularFile(jar),"actual game fixture required");
  byte[] bytes;try(ZipFile zip=new ZipFile(jar.toFile())){bytes=zip.getInputStream(zip.getEntry("net/minecraft/server/MinecraftServer.class")).readAllBytes();}
  var injector=new ServerCompatibilityTickInjector();byte[] transformed=injector.transform("net.minecraft.server.MinecraftServer",bytes,null);
  assertNotSame(bytes,transformed);assertSame(transformed,injector.transform("net.minecraft.server.MinecraftServer",transformed,null));
  ClassNode node=new ClassNode();new ClassReader(transformed).accept(node,0);int returns=0,checks=0;
  for(MethodNode m:node.methods)if(m.name.equals("tickServer")&&m.desc.equals("(Ljava/util/function/BooleanSupplier;)V"))for(AbstractInsnNode i:m.instructions){
   if(i.getOpcode()==Opcodes.RETURN){returns++;assertInstanceOf(MethodInsnNode.class,i.getPrevious());assertEquals("onCompatibilityTick",((MethodInsnNode)i.getPrevious()).name);}
   if(i instanceof MethodInsnNode call&&call.name.equals("onCompatibilityTick"))checks++;
  }
  assertTrue(returns>0);assertEquals(returns,checks);
 }
}
