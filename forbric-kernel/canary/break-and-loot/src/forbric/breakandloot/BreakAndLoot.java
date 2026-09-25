package forbric.breakandloot;

import java.nio.file.*;
import java.util.*;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.mojang.authlib.GameProfile;
import com.mojang.serialization.JsonOps;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.minecraft.core.*;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.RegistryOps;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.level.storage.loot.LootPool;
import net.minecraft.world.level.storage.loot.LootTable;
import net.minecraft.world.level.storage.loot.entries.LootItem;
import net.minecraft.world.level.storage.loot.parameters.LootContextParamSets;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.*;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.event.level.block.BreakBlockEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/** Drives block breaking and loot-pool conditions through the game paths a player or a mod reaches, and records verdicts. */
@Mod(BreakAndLoot.ID)
public final class BreakAndLoot {
 static final String ID="forbricbreakandloot";
 private static final List<Map<String,Object>> CASES=new ArrayList<>();
 private static final List<String> EVENTS=new ArrayList<>();
 private static String mode=""; private static MinecraftServer server; private static int ticks;
 public BreakAndLoot(IEventBus bus){
  PlayerBlockBreakEvents.BEFORE.register((level,player,pos,state,be)->{if(!(player instanceof FakePlayer))return true;EVENTS.add("before:"+id(state)+(be!=null?"+be":""));return !mode.equals("veto");});
  PlayerBlockBreakEvents.AFTER.register((level,player,pos,state,be)->{if(!(player instanceof FakePlayer fake))return;EVENTS.add("after:"+id(state)+(be!=null?"+be":""));
   if(mode.equals("nested")){mode="";fake.gameMode.destroyBlock(pos.above());}});
  NeoForge.EVENT_BUS.addListener(BreakBlockEvent.class,e->{if(mode.equals("neo-cancel"))e.setCanceled(true);});
  PlayerBlockBreakEvents.CANCELED.register((level,player,pos,state,be)->{if(player instanceof FakePlayer)EVENTS.add("canceled:"+id(state)+(be!=null?"+be":""));});
  NeoForge.EVENT_BUS.addListener(ServerStartedEvent.class,e->server=e.getServer());
  NeoForge.EVENT_BUS.addListener(ServerTickEvent.Post.class,e->{if(e.getServer()!=server||++ticks!=20)return;try{run();}finally{finish();}});
 }
 interface Case{String run()throws Throwable;}
 private static void test(String name,Case body){
  String failure;try{failure=body.run();}catch(Throwable t){failure="threw "+t;}
  Map<String,Object> row=new LinkedHashMap<>();row.put("name",name);row.put("pass",failure==null);row.put("detail",failure==null?"":failure);CASES.add(row);
 }
 private static String expect(boolean ok,String what){return ok?null:what;}
 static String id(BlockState s){return String.valueOf(BuiltInRegistries.BLOCK.getKey(s.getBlock()));}
 private static void run(){
  ServerLevel level=server.overworld();
  FakePlayer player=new FakePlayer(level,new GameProfile(UUID.fromString("5a0e9f64-4c1b-4d0f-9b2e-2f1c7a6d9e11"),"BreakProbe"));
  player.snapTo(0.5,101,0.5);
  BlockPos pos=new BlockPos(2,100,2);
  level.setBlock(pos.below(),Blocks.STONE.defaultBlockState(),3);
  java.util.function.BiFunction<BlockState,GameType,String> breakIt=(state,type)->{
   EVENTS.clear();player.setGameMode(type);level.setBlock(pos,state,3);
   boolean broken=player.gameMode.destroyBlock(pos);
   return "broken="+broken+" now="+id(level.getBlockState(pos))+" events="+EVENTS;};
  test("break.survival.plain",()->{String r=breakIt.apply(Blocks.DIRT.defaultBlockState(),GameType.SURVIVAL);return expect(r.equals("broken=true now=minecraft:air events=[before:minecraft:dirt, after:minecraft:dirt]"),r);});
  test("break.survival.blockEntity",()->{String r=breakIt.apply(Blocks.CHEST.defaultBlockState(),GameType.SURVIVAL);return expect(r.equals("broken=true now=minecraft:air events=[before:minecraft:chest+be, after:minecraft:chest+be]"),r);});
  test("break.creative",()->{String r=breakIt.apply(Blocks.DIRT.defaultBlockState(),GameType.CREATIVE);return expect(r.equals("broken=true now=minecraft:air events=[before:minecraft:dirt, after:minecraft:dirt]"),r);});
  test("break.nested",()->{EVENTS.clear();player.setGameMode(GameType.SURVIVAL);level.setBlock(pos,Blocks.CHEST.defaultBlockState(),3);level.setBlock(pos.above(),Blocks.DIRT.defaultBlockState(),3);
   mode="nested";boolean broken=player.gameMode.destroyBlock(pos);mode="";String r="broken="+broken+" events="+EVENTS+" above="+id(level.getBlockState(pos.above()));
   return expect(r.equals("broken=true events=[before:minecraft:chest+be, after:minecraft:chest+be, before:minecraft:dirt, after:minecraft:dirt] above=minecraft:air"),r);});
  test("break.neoCancel",()->{mode="neo-cancel";String r=breakIt.apply(Blocks.DIRT.defaultBlockState(),GameType.SURVIVAL);mode="";return expect(r.equals("broken=false now=minecraft:dirt events=[]"),r);});
  test("break.veto",()->{mode="veto";String r=breakIt.apply(Blocks.DIRT.defaultBlockState(),GameType.SURVIVAL);mode="";return expect(r.equals("broken=false now=minecraft:dirt events=[before:minecraft:dirt, canceled:minecraft:dirt]"),r);});

  // A loot table's pools, each with one item: which items a roll gives says which pools survived their conditions.
  LootTable table=server.reloadableRegistries().getLootTable(ResourceKey.create(Registries.LOOT_TABLE,Identifier.fromNamespaceAndPath(ID,"probe")));
  Set<String> rolled=new TreeSet<>();
  for(ItemStack stack:table.getRandomItems(new LootParams.Builder(level).create(LootContextParamSets.EMPTY)))rolled.add(String.valueOf(BuiltInRegistries.ITEM.getKey(stack.getItem())));
  test("loot.json.plain",()->expect(rolled.contains("minecraft:stone"),"a plain pool: "+rolled));
  test("loot.json.forgeFalse",()->expect(!rolled.contains("minecraft:dirt"),"a pool whose forge:condition is false: "+rolled));
  test("loot.json.forgeTrue",()->expect(rolled.contains("minecraft:diamond"),"a pool whose forge:condition is true (mod_loaded): "+rolled));
  test("loot.json.neoFalse",()->expect(!rolled.contains("minecraft:emerald"),"a pool whose neoforge:conditions are false: "+rolled));
  test("loot.json.bothTrue",()->expect(rolled.contains("minecraft:gold_ingot"),"a pool with both families' conditions true: "+rolled));

  // A pool a MinecraftForge mod builds in code with when(ICondition).
  RegistryOps<JsonElement> ops=RegistryOps.create(JsonOps.INSTANCE,level.registryAccess());
  LootPool built;
  try{LootPool.Builder builder=LootPool.lootPool().add(LootItem.lootTableItem(Items.APPLE));
   Object condition=Class.forName("net.minecraftforge.common.crafting.conditions.FalseCondition").getField("INSTANCE").get(null);
   for(var m:LootPool.Builder.class.getMethods())if(m.getName().equals("when")&&m.getParameterCount()==1&&m.getParameterTypes()[0].getName().endsWith("ICondition"))m.invoke(builder,condition);
   built=builder.build();}catch(ReflectiveOperationException failure){throw new IllegalStateException(failure);}
  test("loot.code.condition",()->{java.lang.reflect.Field f=null;for(var field:LootPool.class.getDeclaredFields())if(field.getName().equals("forge_condition")&&field.getType()==Optional.class)f=field;
   f.setAccessible(true);Object kept=f.get(built);return expect(kept instanceof Optional<?> o&&o.isPresent(),"the built pool's forge_condition is "+kept);});
  JsonElement[] encoded=new JsonElement[1];
  test("loot.code.encode",()->{encoded[0]=LootPool.CODEC.encodeStart(ops,built).getOrThrow();
   return expect(encoded[0].toString().contains("\"forge:condition\":{\"type\":\"forge:false\"}"),"the built pool encodes as "+encoded[0]);});
  test("loot.code.roundtrip",()->{if(encoded[0]==null)return "not encoded";@SuppressWarnings("unchecked") com.mojang.serialization.Codec<LootPool> forgeCodec=(com.mojang.serialization.Codec<LootPool>)LootPool.class.getField("CONDITIONAL_CODEC").get(null);
   LootPool decoded=forgeCodec.parse(ops,encoded[0]).getOrThrow();
   List<ItemStack> out=new ArrayList<>();decoded.addRandomItems(out::add,new net.minecraft.world.level.storage.loot.LootContext.Builder(new LootParams.Builder(level).create(LootContextParamSets.EMPTY)).create(Optional.empty()));
   return expect(out.isEmpty(),"MinecraftForge's own codec read the encoded pool back as one that still gives "+out);});
 }
 private static void finish(){
  Map<String,Object> out=new LinkedHashMap<>();out.put("phase",System.getProperty("forbric.breakPhase"));out.put("cases",CASES);
  out.put("pass",CASES.stream().allMatch(c->Boolean.TRUE.equals(c.get("pass"))));
  try{Files.writeString(Path.of(System.getProperty("forbric.breakProbe")),new GsonBuilder().setPrettyPrinting().create().toJson(out));}catch(Exception e){throw new IllegalStateException(e);}
  System.out.println("[BreakAndLoot] RESULT "+CASES);
  server.halt(false);
 }
}
