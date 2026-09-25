package forbric.stubdriver;

import java.nio.file.*;
import java.util.*;
import com.google.gson.GsonBuilder;
import com.mojang.authlib.GameProfile;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/** Calls, the way the game does, methods whose Fabric injections Mixin bound to a merge-added stub. */
@Mod(StubDriver.ID)
public final class StubDriver {
 static final String ID="forbricstubdriver";
 private static final List<Map<String,Object>> CASES=new ArrayList<>();
 private static MinecraftServer server; private static int ticks;
 public StubDriver(IEventBus bus){
  NeoForge.EVENT_BUS.addListener(ServerStartedEvent.class,e->server=e.getServer());
  NeoForge.EVENT_BUS.addListener(ServerTickEvent.Post.class,e->{if(e.getServer()!=server||++ticks!=20)return;try{run();}finally{finish();}});
 }
 interface Case{String run()throws Throwable;}
 private static void test(String name,Case body){
  String failure;try{failure=body.run();}catch(Throwable t){failure="threw "+t;}
  Map<String,Object> row=new LinkedHashMap<>();row.put("name",name);row.put("pass",failure==null);row.put("detail",failure==null?"":failure);CASES.add(row);
 }
 private static String expect(boolean ok,String what){return ok?null:what;}
 private static void run(){
  ServerLevel level=server.overworld();
  FakePlayer player=new FakePlayer(level,new GameProfile(UUID.fromString("3b7e2f1a-9c4d-4e8b-a1f0-6d5c4b3a2e1f"),"StubProbe"));
  BlockPos pos=new BlockPos(0,100,0);
  // The merged game calls the two-argument getDestroySpeed(state, pos); the injection was written for the one-argument one.
  // Dirt and sponge both break at 1 by hand, so the injection's sevenfold is the ratio (the absolute depends on footing).
  float[] dirt={0f};
  test("speed.control",()->{dirt[0]=player.getDestroySpeed(Blocks.DIRT.defaultBlockState(),pos);return expect(dirt[0]>0f,"dirt speed "+dirt[0]);});
  test("speed.rebound",()->{float sponge=player.getDestroySpeed(Blocks.SPONGE.defaultBlockState(),pos);
   return expect(Math.abs(sponge-7f*dirt[0])<1e-4f,"sponge speed "+sponge+" vs dirt "+dirt[0]+" (the Fabric mod's RETURN injection makes it sevenfold)");});
  // Fabric API's elytra CUSTOM event: its injection reads a field only the carrier's canGlide(boolean) body has.
  // randomTeleport: the game calls NeoForge's overload that also takes the item; the Fabric HEAD injection was written
  // for vanilla's four-argument one, which on the merged base is the carrier's stub.
  test("teleport.control",()->{level.setBlock(new BlockPos(3,100,3),Blocks.STONE.defaultBlockState(),3);player.snapTo(0.5,120,0.5);player.removeTag("forbric_pinned");
   // randomTeleport's own answer: a fake player's position does not follow a player teleport (no real connection).
   boolean moved=teleport(player,3.5,101,3.5);return expect(moved,"an unpinned teleport failed");});
  test("teleport.rebound",()->{player.snapTo(0.5,120,0.5);player.addTag("forbric_pinned");
   boolean moved=teleport(player,3.5,101,3.5);player.removeTag("forbric_pinned");
   return expect(!moved,"a pinned player teleported");});
 }
 /** NeoForge's randomTeleport(x, y, z, particles, item), by descriptor: what chorus fruit calls on the merged base. */
 private static boolean teleport(FakePlayer player,double x,double y,double z)throws Exception{
  java.lang.reflect.Method m=net.minecraft.world.entity.LivingEntity.class.getDeclaredMethod("randomTeleport",double.class,double.class,double.class,boolean.class,net.minecraft.world.item.ItemStack.class);
  m.setAccessible(true);return (Boolean)m.invoke(player,x,y,z,false,net.minecraft.world.item.ItemStack.EMPTY);
 }
 private static void finish(){
  Map<String,Object> out=new LinkedHashMap<>();out.put("phase",System.getProperty("forbric.stubPhase"));out.put("cases",CASES);
  out.put("pass",CASES.stream().allMatch(c->Boolean.TRUE.equals(c.get("pass"))));
  try{Files.writeString(Path.of(System.getProperty("forbric.stubProbe")),new GsonBuilder().setPrettyPrinting().create().toJson(out));}catch(Exception e){throw new IllegalStateException(e);}
  System.out.println("[StubDriver] RESULT "+CASES);
  server.halt(false);
 }
}
