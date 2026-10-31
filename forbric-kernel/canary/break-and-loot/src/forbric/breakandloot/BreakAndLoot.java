package forbric.breakandloot;

import java.nio.file.*;
import java.util.*;
import com.google.gson.GsonBuilder;
import com.mojang.authlib.GameProfile;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.minecraft.core.*;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.level.storage.loot.LootTable;
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
import net.neoforged.neoforge.event.level.BlockDropsEvent;
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
 private static final List<String> DROPS=new ArrayList<>();
 public BreakAndLoot(IEventBus bus){
  // Experience from a player's break: NeoForge's drops event (its number at the end is what drops), and a NeoForge
  // listener changing it for one block of its own choosing.
  NeoForge.EVENT_BUS.addListener(net.neoforged.bus.api.EventPriority.NORMAL,false,BlockDropsEvent.class,e->{if(mode.equals("xp")&&id(e.getState()).equals("minecraft:lapis_ore"))e.setDroppedExperience(50);});
  NeoForge.EVENT_BUS.addListener(net.neoforged.bus.api.EventPriority.LOWEST,true,BlockDropsEvent.class,e->{if(e.getBreaker() instanceof FakePlayer)DROPS.add(id(e.getState())+"="+(e.isCanceled()?"canceled":e.getDroppedExperience()));});
  PlayerBlockBreakEvents.BEFORE.register((level,player,pos,state,be)->{if(!(player instanceof FakePlayer))return true;EVENTS.add("before:"+id(state)+(be!=null?"+be":""));return !mode.equals("veto");});
  PlayerBlockBreakEvents.AFTER.register((level,player,pos,state,be)->{if(!(player instanceof FakePlayer fake))return;EVENTS.add("after:"+id(state)+(be!=null?"+be":""));
   if(mode.equals("nested")){mode="";fake.gameMode.destroyBlock(pos.above());}});
  NeoForge.EVENT_BUS.addListener(BreakBlockEvent.class,e->{if(mode.equals("neo-cancel"))e.setCanceled(true);});
  PlayerBlockBreakEvents.CANCELED.register((level,player,pos,state,be)->{if(player instanceof FakePlayer)EVENTS.add("canceled:"+id(state)+(be!=null?"+be":""));});
  NeoForge.EVENT_BUS.addListener(ServerStartedEvent.class,e->server=e.getServer());
  NeoForge.EVENT_BUS.addListener(ServerTickEvent.Post.class,e->{if(e.getServer()!=server)return;++ticks;
   // Entities (the experience orbs) are only found in a chunk that is loaded with entities: hold the probe's chunk.
   if(ticks==5)server.overworld().setChunkForced(0,0,true);
   if(ticks!=40)return;try{run();}finally{finish();}});
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
  // Breaking air: NeoForge's removal reports nothing removed, so vanilla's Block.destroy — and AFTER — never happen.
  test("break.removalFailed",()->{EVENTS.clear();player.setGameMode(GameType.SURVIVAL);level.setBlock(pos,Blocks.AIR.defaultBlockState(),3);
   boolean broken=player.gameMode.destroyBlock(pos);String r="broken="+broken+" now="+id(level.getBlockState(pos))+" events="+EVENTS;
   return expect(!EVENTS.contains("after:minecraft:air")&&EVENTS.stream().noneMatch(e->e.startsWith("after:")),r);});
  test("break.neoCancel",()->{mode="neo-cancel";String r=breakIt.apply(Blocks.DIRT.defaultBlockState(),GameType.SURVIVAL);mode="";return expect(r.equals("broken=false now=minecraft:dirt events=[]"),r);});
  test("break.veto",()->{mode="veto";String r=breakIt.apply(Blocks.DIRT.defaultBlockState(),GameType.SURVIVAL);mode="";return expect(r.equals("broken=false now=minecraft:dirt events=[before:minecraft:dirt, canceled:minecraft:dirt]"),r);});

  // Experience from mining. NeoForge pops it from BlockDropsEvent, so a player's break must reach NeoForge's
  // dropResources.
  // Each case breaks one block in survival with a tool and sums the experience orbs that appeared around it.
  java.util.function.BiFunction<BlockState,ItemStack,String> mine=(state,tool)->{
   DROPS.clear();player.setGameMode(GameType.SURVIVAL);player.setItemInHand(InteractionHand.MAIN_HAND,tool);
   net.minecraft.world.phys.AABB box=new net.minecraft.world.phys.AABB(pos).inflate(4);
   for(var orb:level.getEntitiesOfClass(net.minecraft.world.entity.ExperienceOrb.class,box))orb.discard();
   level.setBlock(pos,state,3);boolean broken=player.gameMode.destroyBlock(pos);int xp=0;
   for(var orb:level.getEntitiesOfClass(net.minecraft.world.entity.ExperienceOrb.class,box))xp+=orb.getValue()*orbCount(orb);
   for(var orb:level.getEntitiesOfClass(net.minecraft.world.entity.ExperienceOrb.class,box))orb.discard();
   return "broken="+broken+" xp="+xp+" drops="+DROPS;};
  ItemStack pick=new ItemStack(Items.DIAMOND_PICKAXE);
  test("xp.vanillaOre",()->{StringBuilder all=new StringBuilder();int total=0;
   for(int i=0;i<6;i++){String r=mine.apply(Blocks.DIAMOND_ORE.defaultBlockState(),pick.copy());all.append(r).append("; ");
    int xp=Integer.parseInt(r.replaceAll(".* xp=(-?\\d+) .*","$1"));total+=xp;
    // Diamond ore drops 3..7; NeoForge's event fires once and its last number is exactly what dropped.
    if(xp<3||xp>7||!r.endsWith("drops=[minecraft:diamond_ore="+xp+"]"))return "a diamond ore break: "+all;}
   return null;});
  test("xp.silkTouch",()->{ItemStack silk=pick.copy();silk.enchant(level.registryAccess().lookupOrThrow(Registries.ENCHANTMENT)
    .getOrThrow(net.minecraft.world.item.enchantment.Enchantments.SILK_TOUCH),1);String r=mine.apply(Blocks.DIAMOND_ORE.defaultBlockState(),silk);
   return expect(r.equals("broken=true xp=0 drops=[minecraft:diamond_ore=0]"),r);});
  mode="xp";
  test("xp.neoDecision",()->{String r=mine.apply(Blocks.LAPIS_ORE.defaultBlockState(),pick.copy());return expect(r.equals("broken=true xp=50 drops=[minecraft:lapis_ore=50]"),r);});
  mode="";
  // A Fabric mod's hooks in vanilla's tryDropExperience (canary forbricxprelay), which the merged game never calls: natively a
  // redstone ore break drops its 1..5 plus a hundred (a @ModifyVariable at the store), and nether quartz ore none (a HEAD cancel
  // handed every argument, the provider included). The kernel relays the woven method to the drops event's value.
  test("xp.fabricReplace",()->{StringBuilder all=new StringBuilder();
   for(int i=0;i<4;i++){String r=mine.apply(Blocks.REDSTONE_ORE.defaultBlockState(),pick.copy());all.append(r).append("; ");
    int xp=Integer.parseInt(r.replaceAll(".* xp=(-?\\d+) .*","$1"));
    if(xp<101||xp>105)return "a redstone ore break: "+all;}
   return null;});
  test("xp.fabricCancel",()->{StringBuilder all=new StringBuilder();
   for(int i=0;i<4;i++){String r=mine.apply(Blocks.NETHER_QUARTZ_ORE.defaultBlockState(),pick.copy());all.append(r).append("; ");
    if(!r.startsWith("broken=true xp=0 "))return "a nether quartz ore break: "+all;}
   return null;});

  // A loot table's pools, each with one item: which items a roll gives says which pools survived their conditions.
  LootTable table=server.reloadableRegistries().getLootTable(ResourceKey.create(Registries.LOOT_TABLE,Identifier.fromNamespaceAndPath(ID,"probe")));
  Set<String> rolled=new TreeSet<>();
  for(ItemStack stack:table.getRandomItems(new LootParams.Builder(level).create(LootContextParamSets.EMPTY)))rolled.add(String.valueOf(BuiltInRegistries.ITEM.getKey(stack.getItem())));
  test("loot.json.plain",()->expect(rolled.contains("minecraft:stone"),"a plain pool: "+rolled));
  test("loot.json.forgeFalse",()->expect(!rolled.contains("minecraft:dirt"),"a pool whose forge:condition is false: "+rolled));
  test("loot.json.forgeTrue",()->expect(rolled.contains("minecraft:diamond"),"a pool whose forge:condition is true (mod_loaded): "+rolled));
  test("loot.json.neoFalse",()->expect(!rolled.contains("minecraft:emerald"),"a pool whose neoforge:conditions are false: "+rolled));
  test("loot.json.bothTrue",()->expect(rolled.contains("minecraft:gold_ingot"),"a pool with both families' conditions true: "+rolled));

 }
 static int orbCount(net.minecraft.world.entity.ExperienceOrb orb){
  try{java.lang.reflect.Field f=net.minecraft.world.entity.ExperienceOrb.class.getDeclaredField("count");f.setAccessible(true);return f.getInt(orb);}
  catch(ReflectiveOperationException e){throw new IllegalStateException(e);}
 }
 private static void finish(){
  Map<String,Object> out=new LinkedHashMap<>();out.put("phase",System.getProperty("forbric.breakPhase"));out.put("cases",CASES);
  out.put("pass",CASES.stream().allMatch(c->Boolean.TRUE.equals(c.get("pass"))));
  try{Files.writeString(Path.of(System.getProperty("forbric.breakProbe")),new GsonBuilder().setPrettyPrinting().create().toJson(out));}catch(Exception e){throw new IllegalStateException(e);}
  System.out.println("[BreakAndLoot] RESULT "+CASES);
  server.halt(false);
 }
}
