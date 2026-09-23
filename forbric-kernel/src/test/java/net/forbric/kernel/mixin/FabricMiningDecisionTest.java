package net.forbric.kernel.mixin;
import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.*;
import java.net.*;
import java.util.*;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
/** Execute the adapted upstream handler in a JVM with small native-predicate boundaries. */
class FabricMiningDecisionTest {
 @TempDir Path root;
 @Test void nativeContinueNativeResetFabricOverrideAndDifferentItemsPreserveTheirDecisions()throws Exception{
  Map<String,String> sources=Map.of(
   "net/minecraft/world/entity/player/Player.java","package net.minecraft.world.entity.player; public class Player {}",
   "net/minecraft/client/player/LocalPlayer.java","package net.minecraft.client.player; public class LocalPlayer extends net.minecraft.world.entity.player.Player {}",
   "net/minecraft/client/Minecraft.java","package net.minecraft.client; public class Minecraft { public net.minecraft.client.player.LocalPlayer player=new net.minecraft.client.player.LocalPlayer(); }",
   "net/minecraft/world/item/Item.java","package net.minecraft.world.item; public class Item { public boolean reset,allow; public int nativeCalls,fabricCalls; public Object player,old,next; public boolean allowContinuingBlockBreaking(net.minecraft.world.entity.player.Player p,ItemStack a,ItemStack b){fabricCalls++;player=p;old=a;next=b;return allow;} }",
   "net/minecraft/world/item/ItemStack.java","package net.minecraft.world.item; public class ItemStack {public final Item item;public ItemStack(Item item){this.item=item;}public Item getItem(){return item;}public boolean shouldCauseBlockBreakReset(ItemStack next){item.nativeCalls++;return item.reset;} }",
   "net/fabricmc/fabric/mixin/item/client/MultiPlayerGameModeMixin.java","package net.fabricmc.fabric.mixin.item.client; public class MultiPlayerGameModeMixin {private net.minecraft.client.Minecraft minecraft; public MultiPlayerGameModeMixin(net.minecraft.client.Minecraft m){minecraft=m;} }");
  List<String> args=new ArrayList<>(List.of("--release","21","-d",root.toString()));
  for(var source:sources.entrySet()){Path file=root.resolve(source.getKey());Files.createDirectories(file.getParent());Files.writeString(file,source.getValue());args.add(file.toString());}
  assertEquals(0,ToolProvider.getSystemJavaCompiler().run(null,null,null,args.toArray(String[]::new)));
  String name="net/fabricmc/fabric/mixin/item/client/MultiPlayerGameModeMixin";
  ClassNode mixin=StagedFabricMixinFixture.mixin("fabric-item-api-v1",name),target=StagedFabricMixinFixture.game("net/minecraft/client/multiplayer/MultiPlayerGameMode",false);assertEquals(1,FabricMiningMixinAdapter.adapt(mixin,n->target));
  MethodNode handler=StagedFabricMixinFixture.method(mixin,"fabricItemContinueBlockBreakingInject");handler.access=Opcodes.ACC_PUBLIC;handler.visibleAnnotations=null;handler.invisibleAnnotations=null;
  Path shell=root.resolve(name+".class");ClassNode output=MixinFit.parse(Files.readAllBytes(shell));output.methods.add(handler);Files.write(shell,StagedFabricMixinFixture.bytes(output));
  try(URLClassLoader loader=new URLClassLoader(new URL[]{root.toUri().toURL()},ClassLoader.getPlatformClassLoader())){
   Class<?> item=loader.loadClass("net.minecraft.world.item.Item"),stack=loader.loadClass("net.minecraft.world.item.ItemStack"),minecraft=loader.loadClass("net.minecraft.client.Minecraft"),controller=loader.loadClass(name.replace('/','.'));
   Object mc=minecraft.getConstructor().newInstance(),host=controller.getConstructor(minecraft).newInstance(mc);
   for(boolean[] shape:List.of(new boolean[]{false,false,true,false},new boolean[]{true,false,true,true},new boolean[]{true,true,true,false},new boolean[]{true,true,false,true})){
    Object a=item.getConstructor().newInstance(),b=shape[2]?a:item.getConstructor().newInstance();item.getField("reset").setBoolean(a,shape[0]);item.getField("allow").setBoolean(a,shape[1]);
    Object first=stack.getConstructor(item).newInstance(a),second=stack.getConstructor(item).newInstance(b);
    assertEquals(shape[3],controller.getMethod(handler.name,stack,stack).invoke(host,first,second));assertEquals(1,item.getField("nativeCalls").getInt(a));
    int expected=shape[0]&&shape[2]?1:0;assertEquals(expected,item.getField("fabricCalls").getInt(a));
    if(expected==1){assertSame(first,item.getField("old").get(a));assertSame(second,item.getField("next").get(a));assertSame(minecraft.getField("player").get(mc),item.getField("player").get(a));}
   }
  }
 }
}
