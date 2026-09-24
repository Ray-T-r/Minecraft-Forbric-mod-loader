package forbric.coremodparity;

import java.nio.file.*;
import java.util.*;
import java.util.function.Predicate;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.mojang.authlib.GameProfile;
import com.mojang.serialization.JsonOps;
import com.mojang.serialization.MapCodec;
import net.fabricmc.fabric.api.biome.v1.BiomeModifications;
import net.fabricmc.fabric.api.biome.v1.BiomeSelectors;
import net.fabricmc.fabric.api.biome.v1.ModificationPhase;
import net.minecraft.core.*;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.*;
import net.minecraft.world.item.*;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.levelgen.structure.BuiltinStructures;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.TerrainAdjustment;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.*;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.common.world.BiomeModifier;
import net.neoforged.neoforge.common.world.ModifiableBiomeInfo;
import net.neoforged.neoforge.common.world.ModifiableStructureInfo;
import net.neoforged.neoforge.common.world.StructureModifier;
import net.neoforged.neoforge.event.entity.living.FinalizeSpawnEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.neoforged.neoforge.registries.NeoForgeRegistries;

/**
 * Drives the game paths the two families' coremods rewrite and records each verdict. It never posts an event and never
 * calls a kernel class: every case goes through the game method a player's action would reach.
 */
@Mod(CoremodParity.ID)
public final class CoremodParity {
 static final String ID="forbriccoremodparity";
 static final float NEO_PLAINS_TEMPERATURE=1.95f, FABRIC_DESERT_TEMPERATURE=1.7f; static final int NEO_PLAINS_WATER=0xFF00FF;
 private static final DeferredRegister<Block> BLOCKS=DeferredRegister.create(Registries.BLOCK,ID);
 private static final DeferredRegister<Item> ITEMS=DeferredRegister.create(Registries.ITEM,ID);
 private static final DeferredRegister<MapCodec<? extends BiomeModifier>> BIOME_MODIFIERS=DeferredRegister.create(NeoForgeRegistries.Keys.BIOME_MODIFIER_SERIALIZERS,ID);
 private static final DeferredRegister<MapCodec<? extends StructureModifier>> STRUCTURE_MODIFIERS=DeferredRegister.create(NeoForgeRegistries.Keys.STRUCTURE_MODIFIER_SERIALIZERS,ID);
 private static final DeferredHolder<Block,Block> MARKER=BLOCKS.register("marker",()->new Block(props("marker")));
 private static final DeferredHolder<Block,Block> SECOND=BLOCKS.register("second",()->new Block(props("second")));
 // NeoForge's constructor: the empty pot and the plant as suppliers, the way a NeoForge mod declares a pot.
 private static final DeferredHolder<Block,FlowerPotBlock> POTTED_MARKER=BLOCKS.register("potted_marker",()->new FlowerPotBlock(()->(FlowerPotBlock)Blocks.FLOWER_POT,MARKER,props("potted_marker")));
 // Declared only through addPlant (MinecraftForge's API): its own plant supplier names a different block.
 private static final DeferredHolder<Block,FlowerPotBlock> POTTED_SECOND=BLOCKS.register("potted_second",()->new FlowerPotBlock(()->(FlowerPotBlock)Blocks.FLOWER_POT,()->Blocks.BEDROCK,props("potted_second")));
 private static final DeferredHolder<Item,BlockItem> MARKER_ITEM=ITEMS.register("marker",()->new BlockItem(MARKER.get(),new Item.Properties().setId(ResourceKey.create(Registries.ITEM,Identifier.fromNamespaceAndPath(ID,"marker")))));
 private static final DeferredHolder<Item,BlockItem> SECOND_ITEM=ITEMS.register("second",()->new BlockItem(SECOND.get(),new Item.Properties().setId(ResourceKey.create(Registries.ITEM,Identifier.fromNamespaceAndPath(ID,"second")))));
 static{
  BIOME_MODIFIERS.register("warm_plains",()->MapCodec.unit(WarmPlains.INSTANCE));
  STRUCTURE_MODIFIERS.register("buried_outpost",()->MapCodec.unit(BuriedOutpost.INSTANCE));
 }
 private static BlockBehaviour.Properties props(String name){return BlockBehaviour.Properties.of().setId(ResourceKey.create(Registries.BLOCK,Identifier.fromNamespaceAndPath(ID,name)));}

 /** NeoForge's own modifier kind: plains get a temperature and a water colour no vanilla biome has. */
 enum WarmPlains implements BiomeModifier{INSTANCE;
  public void modify(Holder<Biome> biome,Phase phase,ModifiableBiomeInfo.BiomeInfo.Builder builder){
   if(phase!=Phase.MODIFY||!biome.is(Biomes.PLAINS))return;
   builder.getClimateSettings().setTemperature(NEO_PLAINS_TEMPERATURE);builder.getSpecialEffects().waterColor(NEO_PLAINS_WATER);}
  public MapCodec<? extends BiomeModifier> codec(){return MapCodec.unit(INSTANCE);}}
 /** And a structure one: the pillager outpost buries itself instead of vanilla's thin beard. */
 enum BuriedOutpost implements StructureModifier{INSTANCE;
  public void modify(Holder<Structure> structure,Phase phase,ModifiableStructureInfo.StructureInfo.Builder builder){
   if(phase==Phase.MODIFY&&structure.is(BuiltinStructures.PILLAGER_OUTPOST))builder.getStructureSettings().setTerrainAdaptation(TerrainAdjustment.BURY);}
  public MapCodec<? extends StructureModifier> codec(){return MapCodec.unit(INSTANCE);}}

 private static final Map<String,Object> OUT=new LinkedHashMap<>();
 private static final List<Map<String,Object>> CASES=new ArrayList<>();
 private static int neo,forge; private static String mode="";
 private static MinecraftServer server; private static int ticks;
 public CoremodParity(IEventBus bus){
  BLOCKS.register(bus);ITEMS.register(bus);BIOME_MODIFIERS.register(bus);STRUCTURE_MODIFIERS.register(bus);
  // A Fabric mod's weather change, through fabric-biome-api, on a biome NeoForge's modifier does not touch.
  BiomeModifications.create(Identifier.fromNamespaceAndPath(ID,"cool_desert")).add(ModificationPhase.POST_PROCESSING,
   BiomeSelectors.includeByKey(Biomes.DESERT),context->context.getWeather().setTemperature(FABRIC_DESERT_TEMPERATURE));
  NeoForge.EVENT_BUS.addListener(FinalizeSpawnEvent.class,e->{if(!(e.getEntity() instanceof Mob))return;neo++;
   if(mode.equals("neo-cancel"))e.setCanceled(true);if(mode.equals("neo-veto"))e.setSpawnCancelled(true);});
  net.minecraftforge.event.entity.living.MobSpawnEvent.FinalizeSpawn.BUS.addListener(
   (Predicate<net.minecraftforge.event.entity.living.MobSpawnEvent.FinalizeSpawn>)e->{forge++;
    if(mode.equals("neo-veto"))e.getEntity().setSpawnCancelled(false);   // a later family clearing the shared flag
    return false;});
  NeoForge.EVENT_BUS.addListener(ServerStartedEvent.class,e->server=e.getServer());
  NeoForge.EVENT_BUS.addListener(ServerTickEvent.Post.class,e->{if(e.getServer()!=server||++ticks!=20)return;try{run();}finally{finish();}});
 }
 interface Case{String run()throws Throwable;}
 /** A case passes when its body returns null; any text is the reason it did not. */
 private static void test(String name,Case body){
  String failure;
  try{failure=body.run();}catch(Throwable t){failure="threw "+t;}
  Map<String,Object> row=new LinkedHashMap<>();row.put("name",name);row.put("pass",failure==null);row.put("detail",failure==null?"":failure);CASES.add(row);
 }
 private static String expect(boolean ok,String what){return ok?null:what;}
 private static String id(Block b){return String.valueOf(BuiltInRegistries.BLOCK.getKey(b));}
 private static void run(){
  ServerLevel level=server.overworld();
  BlockPos pot=new BlockPos(0,100,0);
  level.setBlock(pot.below(),Blocks.STONE.defaultBlockState(),3);
  FakePlayer player=new FakePlayer(level,new GameProfile(UUID.fromString("7f1d3bb9-0c19-4a7c-9d3f-0b8f3e5e1c42"),"CoremodProbe"));
  player.setGameMode(GameType.SURVIVAL);player.snapTo(0.5,101,1.5);
  BlockHitResult hit=new BlockHitResult(Vec3.atCenterOf(pot),Direction.UP,pot,false);
  ((FlowerPotBlock)Blocks.FLOWER_POT).addPlant(BuiltInRegistries.BLOCK.getKey(SECOND.get()),POTTED_SECOND::get);
  record Use(Object result,String block,ItemStack held){}
  java.util.function.BiFunction<Block,ItemStack,Use> use=(start,stack)->{
   level.setBlock(pot,start.defaultBlockState(),3);player.getInventory().clearContent();player.setItemInHand(InteractionHand.MAIN_HAND,stack);
   Object result=player.gameMode.useItemOn(player,level,stack,InteractionHand.MAIN_HAND,hit);
   return new Use(result,id(level.getBlockState(pot).getBlock()),player.getMainHandItem());};
  java.util.function.Function<Block,ItemStack> pick=block->{level.setBlock(pot,block.defaultBlockState(),3);return level.getBlockState(pot).getCloneItemStack(level,pot,true);};

  test("pot.vanilla.plant",()->{Use u=use.apply(Blocks.FLOWER_POT,new ItemStack(Items.POPPY));return expect(u.block().equals("minecraft:potted_poppy")&&u.held().isEmpty(),"planting a poppy gave "+u);});
  test("pot.vanilla.take",()->{Use u=use.apply(Blocks.POTTED_POPPY,ItemStack.EMPTY);return expect(u.block().equals("minecraft:flower_pot")&&player.getInventory().countItem(Items.POPPY)==1,"taking the poppy out gave "+u+" and "+player.getInventory().countItem(Items.POPPY)+" poppy");});
  test("pot.vanilla.pickEmpty",()->{ItemStack s=pick.apply(Blocks.FLOWER_POT);return expect(s.is(Items.FLOWER_POT),"pick-block on an empty pot gave "+s);});
  test("pot.vanilla.pickFull",()->{ItemStack s=pick.apply(Blocks.POTTED_POPPY);return expect(s.is(Items.POPPY),"pick-block on a potted poppy gave "+s);});
  test("pot.vanilla.otherItem",()->{Use u=use.apply(Blocks.FLOWER_POT,new ItemStack(Items.DIRT));return expect(u.block().equals("minecraft:flower_pot")&&u.held().is(Items.DIRT),"dirt on a pot gave "+u);});
  test("pot.neoforge.plant",()->{Use u=use.apply(Blocks.FLOWER_POT,new ItemStack(MARKER_ITEM.get()));return expect(u.block().equals(ID+":potted_marker"),"planting a NeoForge-declared plant gave "+u);});
  test("pot.neoforge.take",()->{Use u=use.apply(POTTED_MARKER.get(),ItemStack.EMPTY);return expect(u.block().equals("minecraft:flower_pot")&&player.getInventory().countItem(MARKER_ITEM.get())==1,"taking a NeoForge-declared plant out gave "+u);});
  test("pot.neoforge.pick",()->{ItemStack s=pick.apply(POTTED_MARKER.get());return expect(s.is(MARKER_ITEM.get()),"pick-block on a NeoForge-declared pot gave "+s);});
  test("pot.forge.addPlant",()->{Use u=use.apply(Blocks.FLOWER_POT,new ItemStack(SECOND_ITEM.get()));return expect(u.block().equals(ID+":potted_second"),"planting an addPlant-declared plant gave "+u);});
  test("pot.neoforge.api",()->{Block full=((FlowerPotBlock)Blocks.FLOWER_POT).getFullPot(Blocks.POPPY);return expect(full==Blocks.POTTED_POPPY,"NeoForge's getFullPot(poppy) answered "+id(full));});
  test("fluid.water",()->{Object f=((LiquidBlock)Blocks.WATER).getFluid();return expect(f==Fluids.WATER,"water's getFluid answered "+f);});

  test("spawn.command",()->{mode="";neo=forge=0;Entity z=EntityTypes.ZOMBIE.spawn(level,new BlockPos(4,101,4),EntitySpawnReason.COMMAND);if(z!=null)z.discard();
   return expect(z!=null&&neo==1&&forge==1,"EntityType.spawn: entity "+z+", NeoForge events "+neo+", MinecraftForge events "+forge);});
  test("spawn.summon",()->{mode="";neo=forge=0;server.getCommands().performPrefixedCommand(server.createCommandSourceStack().withSuppressedOutput(),"summon minecraft:husk 6 101 6");
   return expect(neo==1&&forge==1,"/summon: NeoForge events "+neo+", MinecraftForge events "+forge);});
  test("spawn.neoCancel",()->{mode="neo-cancel";neo=forge=0;Entity z=EntityTypes.ZOMBIE.spawn(level,new BlockPos(4,101,6),EntitySpawnReason.COMMAND);mode="";if(z!=null)z.discard();
   return expect(z!=null&&neo==1&&forge==0,"a NeoForge cancel: entity "+z+", NeoForge "+neo+", MinecraftForge "+forge+" (a cancel skips finalization, not the spawn)");});
  test("spawn.neoVeto",()->{mode="neo-veto";neo=forge=0;Entity z=EntityTypes.ZOMBIE.spawn(level,new BlockPos(4,101,8),EntitySpawnReason.COMMAND);mode="";if(z!=null)z.discard();
   return expect(z==null&&neo==1&&forge==1,"a NeoForge veto a MinecraftForge listener cleared: entity "+z+", NeoForge "+neo+", MinecraftForge "+forge);});

  Registry<Biome> biomes=level.registryAccess().lookupOrThrow(Registries.BIOME);
  Biome plains=biomes.getValueOrThrow(Biomes.PLAINS),desert=biomes.getValueOrThrow(Biomes.DESERT);
  test("biome.neoforge.temperature",()->expect(plains.getBaseTemperature()==NEO_PLAINS_TEMPERATURE,"plains temperature "+plains.getBaseTemperature()));
  test("biome.neoforge.water",()->expect((plains.getWaterColor()&0xFFFFFF)==NEO_PLAINS_WATER,"plains water colour "+Integer.toHexString(plains.getWaterColor())));
  test("biome.neoforge.network",()->{RegistryOps<JsonElement> ops=RegistryOps.create(JsonOps.INSTANCE,level.registryAccess());
   JsonElement sent=Biome.NETWORK_CODEC.encodeStart(ops,plains).getOrThrow();Biome received=Biome.NETWORK_CODEC.parse(ops,sent).getOrThrow();
   return expect(received.getBaseTemperature()==NEO_PLAINS_TEMPERATURE&&(received.getWaterColor()&0xFFFFFF)==NEO_PLAINS_WATER,
    "what a client receives for plains: temperature "+received.getBaseTemperature()+", water "+Integer.toHexString(received.getWaterColor())+" from "+sent);});
  test("biome.fabric.keep",()->expect(desert.getBaseTemperature()==FABRIC_DESERT_TEMPERATURE,"desert temperature "+desert.getBaseTemperature()+" (a Fabric mod set "+FABRIC_DESERT_TEMPERATURE+")"));
  test("structure.neoforge.terrain",()->{Structure outpost=level.registryAccess().lookupOrThrow(Registries.STRUCTURE).getValueOrThrow(BuiltinStructures.PILLAGER_OUTPOST);
   return expect(outpost.terrainAdaptation()==TerrainAdjustment.BURY,"pillager outpost terrain adaptation "+outpost.terrainAdaptation());});
 }
 private static void finish(){
  OUT.put("phase",System.getProperty("forbric.coremodPhase"));OUT.put("nonce",System.getProperty("forbric.coremodNonce"));
  OUT.put("cases",CASES);OUT.put("pass",CASES.stream().allMatch(c->Boolean.TRUE.equals(c.get("pass"))));
  try{Files.writeString(Path.of(System.getProperty("forbric.coremodProbe")),new GsonBuilder().setPrettyPrinting().create().toJson(OUT));}catch(Exception e){throw new IllegalStateException(e);}
  System.out.println("[CoremodParity] RESULT "+CASES.stream().map(c->c.get("name")+"="+c.get("pass")).toList());
  server.halt(false);
 }
}
