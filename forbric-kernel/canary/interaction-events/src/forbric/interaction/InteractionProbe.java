package forbric.interaction;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.nio.file.*;
import java.util.*;
import com.google.gson.GsonBuilder;
import com.mojang.authlib.GameProfile;
import net.fabricmc.fabric.api.event.player.ItemEvents;
import net.fabricmc.fabric.api.event.player.PlayerPickItemEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.protocol.game.ServerboundPickItemFromBlockPacket;
import net.minecraft.network.protocol.game.ServerboundPickItemFromEntityPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.event.entity.player.UseItemOnBlockEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/** Uses items on blocks and picks blocks through the game's own paths, listening through Fabric's and NeoForge's events. */
@Mod(InteractionProbe.ID)
public final class InteractionProbe {
 static final String ID="forbricinteraction";
 private static final List<Map<String,Object>> CASES=new ArrayList<>();
 private static final List<String> EVENTS=new ArrayList<>();
 private static String mode=""; private static MinecraftServer server; private static int ticks;
 public InteractionProbe(IEventBus bus){
  ItemEvents.USE_ON.register(ctx->{if(!(ctx.getPlayer() instanceof FakePlayer))return null;EVENTS.add("fabric:use_on:"+item(ctx.getItemInHand()));
   return mode.equals("fabric-fail")?InteractionResult.FAIL:null;});
  NeoForge.EVENT_BUS.addListener(UseItemOnBlockEvent.class,e->{if(!(e.getPlayer() instanceof FakePlayer))return;EVENTS.add("neo:"+e.getUsePhase());
   if(mode.equals("neo-cancel")&&e.getUsePhase()==UseItemOnBlockEvent.UsePhase.ITEM_AFTER_BLOCK)e.cancelWithResult(InteractionResult.SUCCESS);});
  PlayerPickItemEvents.BLOCK.register((player,pos,state,includeData)->{if(!(player instanceof FakePlayer))return null;EVENTS.add("fabric:pick_block:"+id(state)+":"+includeData);
   return switch(mode){case "pick-diamond"->new ItemStack(Items.DIAMOND);case "pick-empty"->ItemStack.EMPTY;default->null;};});
  PlayerPickItemEvents.ENTITY.register((player,entity,includeData)->{if(!(player instanceof FakePlayer))return null;EVENTS.add("fabric:pick_entity:"+BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()));return null;});
  // Forced, so the chunk is entity-ticking by the time the probe runs: an entity only a player-less chunk holds is not
  // visible to getEntityOrPart, and the pick-entity control would fail for a reason that is not the one it controls.
  NeoForge.EVENT_BUS.addListener(ServerStartedEvent.class,e->{server=e.getServer();server.overworld().setChunkForced(0,0,true);});
  NeoForge.EVENT_BUS.addListener(ServerTickEvent.Post.class,e->{if(e.getServer()!=server||++ticks!=20)return;try{run();}finally{finish();}});
 }
 interface Case{String run()throws Throwable;}
 private static void test(String name,Case body){
  String failure;try{failure=body.run();}catch(Throwable t){failure="threw "+t;}
  Map<String,Object> row=new LinkedHashMap<>();row.put("name",name);row.put("pass",failure==null);row.put("detail",failure==null?"":failure);CASES.add(row);
 }
 private static String expect(boolean ok,String what){return ok?null:what;}
 static String id(BlockState s){return String.valueOf(BuiltInRegistries.BLOCK.getKey(s.getBlock()));}
 static String item(ItemStack s){return String.valueOf(BuiltInRegistries.ITEM.getKey(s.getItem()));}
 private static long count(String event){return EVENTS.stream().filter(event::equals).count();}
 private static void run(){
  ServerLevel level=server.overworld();
  FakePlayer player=new FakePlayer(level,new GameProfile(UUID.fromString("7c1d2e3f-4a5b-4c6d-8e9f-0a1b2c3d4e5f"),"InteractionProbe"));
  player.snapTo(0.5,101,0.5);
  BlockPos ground=new BlockPos(2,100,2);
  BlockHitResult top=new BlockHitResult(Vec3.atCenterOf(ground).add(0,0.5,0),Direction.UP,ground,false);
  // One use of a stone block item on the top face of stone, the way a player's right click reaches the server.
  java.util.function.Function<Boolean,String> use=direct->{
   EVENTS.clear();player.setGameMode(GameType.SURVIVAL);
   level.setBlock(ground,Blocks.STONE.defaultBlockState(),3);level.setBlock(ground.above(),Blocks.AIR.defaultBlockState(),3);
   ItemStack stack=new ItemStack(Items.STONE,8);player.setItemInHand(InteractionHand.MAIN_HAND,stack);
   InteractionResult result=direct?stack.useOn(new UseOnContext(player,InteractionHand.MAIN_HAND,top))
    :player.gameMode.useItemOn(player,level,stack,InteractionHand.MAIN_HAND,top);
   return "result="+result.getClass().getSimpleName()+" above="+id(level.getBlockState(ground.above()))+" events="+EVENTS;};
  String[] plain={use.apply(false)};
  test("use.place.block",()->expect(plain[0].contains("above=minecraft:stone"),plain[0]));
  test("use.neoBefore",()->expect(count("neo:ITEM_BEFORE_BLOCK")==1,plain[0]));
  test("use.place.fabric",()->expect(count("fabric:use_on:minecraft:stone")==1,plain[0]));
  test("use.place.neoAfter",()->expect(count("neo:ITEM_AFTER_BLOCK")==1&&EVENTS.indexOf("neo:ITEM_AFTER_BLOCK")<EVENTS.indexOf("fabric:use_on:minecraft:stone")
   &&EVENTS.indexOf("neo:ITEM_BEFORE_BLOCK")<EVENTS.indexOf("neo:ITEM_AFTER_BLOCK"),"NeoForge's ITEM_AFTER_BLOCK once, after ITEM_BEFORE_BLOCK and before Fabric's USE_ON: "+plain[0]));
  test("use.fabricOverrides",()->{mode="fabric-fail";String r=use.apply(false);mode="";
   return expect(r.startsWith("result=Fail above=minecraft:air")&&count("fabric:use_on:minecraft:stone")==1,r);});
  test("use.neoCancel",()->{mode="neo-cancel";String r=use.apply(false);mode="";
   return expect(r.startsWith("result=Success above=minecraft:air")&&count("fabric:use_on:minecraft:stone")==0&&count("neo:ITEM_AFTER_BLOCK")==1,r);});
  test("use.direct",()->{String r=use.apply(true);
   return expect(r.contains("above=minecraft:stone")&&count("fabric:use_on:minecraft:stone")==1&&count("neo:ITEM_AFTER_BLOCK")==1,r);});

  // Pick block: the server's handler for the client's packet, run as the base listener's own method.
  java.util.function.BiFunction<String,Boolean,String> pick=(how,includeData)->{
   EVENTS.clear();mode=how;player.setGameMode(GameType.CREATIVE);player.getInventory().clearContent();
   level.setBlock(ground,Blocks.STONE.defaultBlockState(),3);
   try{MethodHandle handle=MethodHandles.privateLookupIn(player.connection.getClass(),MethodHandles.lookup()).findSpecial(ServerGamePacketListenerImpl.class,
     "handlePickItemFromBlock",MethodType.methodType(void.class,ServerboundPickItemFromBlockPacket.class),player.connection.getClass());
    handle.invoke(player.connection,new ServerboundPickItemFromBlockPacket(ground,includeData));}catch(Throwable t){throw new IllegalStateException(t);}finally{mode="";}
   return "hand="+item(player.getMainHandItem())+" events="+EVENTS;};
  test("pick.block.vanilla",()->{String r=pick.apply("",false);return expect(r.equals("hand=minecraft:stone events=[fabric:pick_block:minecraft:stone:false]"),r);});
  test("pick.block.fabric",()->{String r=pick.apply("pick-diamond",false);return expect(r.equals("hand=minecraft:diamond events=[fabric:pick_block:minecraft:stone:false]"),r);});
  test("pick.block.empty",()->{String r=pick.apply("pick-empty",false);return expect(r.equals("hand=minecraft:air events=[fabric:pick_block:minecraft:stone:false]"),r);});
  test("pick.block.includeData",()->{String r=pick.apply("",true);return expect(r.equals("hand=minecraft:stone events=[fabric:pick_block:minecraft:stone:true]"),r);});
  test("pick.entity",()->{EVENTS.clear();player.setGameMode(GameType.CREATIVE);
   ArmorStand stand=net.minecraft.world.entity.EntityTypes.ARMOR_STAND.create(level,EntitySpawnReason.COMMAND);stand.snapTo(1.5,101,0.5);level.addFreshEntity(stand);
   try{MethodHandle handle=MethodHandles.privateLookupIn(player.connection.getClass(),MethodHandles.lookup()).findSpecial(ServerGamePacketListenerImpl.class,
     "handlePickItemFromEntity",MethodType.methodType(void.class,ServerboundPickItemFromEntityPacket.class),player.connection.getClass());
    handle.invoke(player.connection,new ServerboundPickItemFromEntityPacket(stand.getId(),false));}finally{stand.discard();}
   return expect(EVENTS.equals(List.of("fabric:pick_entity:minecraft:armor_stand")),"events="+EVENTS);});
 }
 private static void finish(){
  Map<String,Object> out=new LinkedHashMap<>();out.put("phase",System.getProperty("forbric.interactionPhase"));out.put("cases",CASES);
  out.put("pass",CASES.stream().allMatch(c->Boolean.TRUE.equals(c.get("pass"))));
  try{Files.writeString(Path.of(System.getProperty("forbric.interactionProbe")),new GsonBuilder().setPrettyPrinting().create().toJson(out));}catch(Exception e){throw new IllegalStateException(e);}
  System.out.println("[InteractionProbe] RESULT "+CASES);
  server.halt(false);
 }
}
