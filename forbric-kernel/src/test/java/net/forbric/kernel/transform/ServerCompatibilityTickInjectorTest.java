package net.forbric.kernel.transform;
import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.*;
import net.forbric.kernel.TestFixtures;
import net.forbric.kernel.TestFixtures.Fixture;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
class ServerCompatibilityTickInjectorTest {
 /** The seam's owner: the kernel's own server duties, which name nothing of MinecraftForge. */
 private static final String OWNER="net/forbric/kernel/runtime/KernelServerDuties";
 @Test void everyNormalReturnInTheActualServerHasOneCheckAndSecondPassDoesNothing() throws Exception {
  assertEveryReturnChecked(TestFixtures.stagedBase());
 }
 /** The genuine NeoForge base Stage 4 switches to: the same seam on its MinecraftServer, calling the same owner. */
 @Test void theGenuineNeoForgeServerGetsTheSameSeam() throws Exception {
  assertEveryReturnChecked(TestFixtures.genuineBase());
 }
 /** The owner the seam calls declares the method, and is not the class that names MinecraftForge's lifecycle hooks. */
 @Test void theSeamCallsTheKernelsServerDutiesNotTheMinecraftForgeLifecycleBridge() throws Exception {
  Path compiled=Path.of(System.getProperty("user.dir"),"build","classes","java","runtime").resolve(OWNER+".class");
  TestFixtures.require(Fixture.GAME_SIDE,Files.isRegularFile(compiled),"compiled game side absent: "+compiled);
  ClassNode node=new ClassNode();new ClassReader(Files.readAllBytes(compiled)).accept(node,0);
  assertTrue(node.methods.stream().anyMatch(m->m.name.equals("onCompatibilityTick")&&m.desc.equals("(Ljava/lang/Object;)V")
    &&(m.access&Opcodes.ACC_STATIC)!=0&&(m.access&Opcodes.ACC_PUBLIC)!=0),"the seam's target is a public static onCompatibilityTick(Object)");
  assertFalse(new String(Files.readAllBytes(compiled),java.nio.charset.StandardCharsets.ISO_8859_1).contains("net/minecraftforge/"),
    "the owner names nothing of MinecraftForge, so the seam links on a base without it");
 }
 private static void assertEveryReturnChecked(Path jar) throws Exception {
  TestFixtures.requireFile(Fixture.STAGED,jar,"actual game fixture required");
  byte[] bytes=TestFixtures.requireEntry(Fixture.STAGED,jar,"net/minecraft/server/MinecraftServer.class");
  var injector=new ServerCompatibilityTickInjector();byte[] transformed=injector.transform("net.minecraft.server.MinecraftServer",bytes,null);
  assertNotSame(bytes,transformed);assertSame(transformed,injector.transform("net.minecraft.server.MinecraftServer",transformed,null));
  ClassNode node=new ClassNode();new ClassReader(transformed).accept(node,0);int returns=0,checks=0;
  for(MethodNode m:node.methods)if(m.name.equals("tickServer")&&m.desc.equals("(Ljava/util/function/BooleanSupplier;)V"))for(AbstractInsnNode i:m.instructions){
   if(i.getOpcode()==Opcodes.RETURN){returns++;assertInstanceOf(MethodInsnNode.class,i.getPrevious());assertEquals("onCompatibilityTick",((MethodInsnNode)i.getPrevious()).name);
    assertEquals(OWNER,((MethodInsnNode)i.getPrevious()).owner);}
   if(i instanceof MethodInsnNode call&&call.name.equals("onCompatibilityTick"))checks++;
  }
  assertTrue(returns>0);assertEquals(returns,checks);
 }
}
