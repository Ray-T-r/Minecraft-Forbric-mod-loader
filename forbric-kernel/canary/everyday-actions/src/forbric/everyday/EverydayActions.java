package forbric.everyday;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.*;
import java.util.*;
import com.google.gson.GsonBuilder;
import com.mojang.authlib.GameProfile;
import net.minecraft.core.BlockPos;
import net.minecraft.core.NonNullList;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.boss.enderdragon.EnderDragon;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.alchemy.PotionContents;
import net.minecraft.world.item.alchemy.Potions;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity;
import net.minecraft.world.level.block.entity.BrewingStandBlockEntity;
import net.minecraft.world.level.block.entity.FurnaceBlockEntity;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/** The things every player does in a session — craft, smelt, brew, meet the dragon — with fabric-item-api installed. */
@Mod(EverydayActions.ID)
public final class EverydayActions {
 static final String ID="forbriceveryday";
 private static final List<Map<String,Object>> CASES=new ArrayList<>();
 private static MinecraftServer server; private static int ticks; private static EnderDragon dragon;
 public EverydayActions(IEventBus bus){
  NeoForge.EVENT_BUS.addListener(ServerStartedEvent.class,e->{server=e.getServer();server.overworld().setChunkForced(0,0,true);});
  NeoForge.EVENT_BUS.addListener(ServerTickEvent.Post.class,e->{if(e.getServer()!=server)return;ticks++;
   if(ticks==20)first();
   if(ticks==40){try{second();}finally{finish();}}});
 }
 interface Case{String run()throws Throwable;}
 private static void test(String name,Case body){
  String failure;try{failure=body.run();}catch(InvocationTargetException t){failure="threw "+t.getCause();}catch(Throwable t){failure="threw "+t;}
  Map<String,Object> row=new LinkedHashMap<>();row.put("name",name);row.put("pass",failure==null);row.put("detail",failure==null?"":failure);CASES.add(row);
 }
 private static String expect(boolean ok,String what){return ok?null:what;}
 static String id(ItemStack s){return s.isEmpty()?"empty":s.getCount()+" "+BuiltInRegistries.ITEM.getKey(s.getItem());}
 private static Object remainder(Item item,Class<?> param,Object arg)throws Throwable{
  for(Method m:Item.class.getMethods())if(m.getName().equals("getCraftingRemainder")&&m.getParameterCount()==1&&m.getParameterTypes()[0]==param)return m.invoke(item,arg);
  throw new NoSuchMethodException("Item.getCraftingRemainder("+param.getSimpleName()+")");}
 private static void first(){
  ServerLevel level=server.overworld();
  // Crafting remainders: what taking a crafting result, brewing and burning a fuel bucket all ask.
  ItemStack bucket=new ItemStack(Items.WATER_BUCKET);
  test("remainder.stack",()->{Object r=ItemStack.class.getMethod("getCraftingRemainder").invoke(bucket);return expect(String.valueOf(r).contains("minecraft:bucket"),"water bucket leaves "+r);});
  test("remainder.item",()->{Object r=remainder(Items.WATER_BUCKET,ItemStack.class,bucket);return expect(String.valueOf(r).contains("minecraft:bucket"),"Item.getCraftingRemainder(ItemStack) gave "+r);});
  test("remainder.neoOverload",()->{Object r=remainder(Items.WATER_BUCKET,Class.forName("net.minecraft.world.item.ItemInstance"),bucket);
   return expect(String.valueOf(r).contains("minecraft:bucket"),"NeoForge's getCraftingRemainder(ItemInstance) gave "+r);});
  test("craft.cake",()->{List<ItemStack> grid=new ArrayList<>(List.of(new ItemStack(Items.MILK_BUCKET),new ItemStack(Items.MILK_BUCKET),new ItemStack(Items.MILK_BUCKET),
    new ItemStack(Items.SUGAR),new ItemStack(Items.EGG),new ItemStack(Items.SUGAR),new ItemStack(Items.WHEAT),new ItemStack(Items.WHEAT),new ItemStack(Items.WHEAT)));
   CraftingInput input=CraftingInput.of(3,3,grid);
   var recipe=server.getRecipeManager().getRecipeFor(RecipeType.CRAFTING,input,level).orElseThrow();
   String result=id(recipe.value().assemble(input));
   List<String> left=new ArrayList<>();for(ItemStack s:recipe.value().getRemainingItems(input))if(!s.isEmpty())left.add(id(s));
   return expect(result.equals("1 minecraft:cake")&&left.equals(List.of("1 minecraft:bucket","1 minecraft:bucket","1 minecraft:bucket")),"cake="+result+" left="+left);});
  // A furnace with raw iron and coal, ticked the way its block entity ticker ticks it.
  test("furnace.idle",()->{BlockPos pos=new BlockPos(6,100,6);level.setBlock(pos,Blocks.FURNACE.defaultBlockState(),3);FurnaceBlockEntity f=(FurnaceBlockEntity)level.getBlockEntity(pos);
   for(int i=0;i<20;i++)AbstractFurnaceBlockEntity.serverTick(level,pos,level.getBlockState(pos),f);return null;});
  // Each is removed after its case: left in the world, the level's own ticker would throw the same error on the next
  // tick and take the server down before the other cases are reported.
  test("furnace.smelt",()->{BlockPos pos=new BlockPos(4,100,4);level.setBlock(pos,Blocks.FURNACE.defaultBlockState(),3);FurnaceBlockEntity f=(FurnaceBlockEntity)level.getBlockEntity(pos);
   try{f.setItem(0,new ItemStack(Items.RAW_IRON,2));f.setItem(1,new ItemStack(Items.COAL,1));
    for(int i=0;i<250;i++)AbstractFurnaceBlockEntity.serverTick(level,pos,level.getBlockState(pos),f);
    return expect(id(f.getItem(2)).equals("1 minecraft:iron_ingot"),"output="+id(f.getItem(2))+" fuel="+id(f.getItem(1))+" input="+id(f.getItem(0)));}
   finally{f.clearContent();level.setBlock(pos,Blocks.AIR.defaultBlockState(),3);}});
  // A brewing stand turning water bottles into awkward potions with nether wart.
  test("brewing.awkward",()->{BlockPos pos=new BlockPos(8,100,4);level.setBlock(pos,Blocks.BREWING_STAND.defaultBlockState(),3);BrewingStandBlockEntity b=(BrewingStandBlockEntity)level.getBlockEntity(pos);
   try{for(int i=0;i<3;i++)b.setItem(i,PotionContents.createItemStack(Items.POTION,Potions.WATER));b.setItem(3,new ItemStack(Items.NETHER_WART));b.setItem(4,new ItemStack(Items.BLAZE_POWDER));
    for(int i=0;i<420;i++)BrewingStandBlockEntity.serverTick(level,pos,level.getBlockState(pos),b);
    String potion=String.valueOf(b.getItem(0).get(net.minecraft.core.component.DataComponents.POTION_CONTENTS));
    return expect(potion.contains("awkward")&&b.getItem(3).isEmpty(),"bottle="+potion+" ingredient="+id(b.getItem(3)));}
   finally{b.clearContent();level.setBlock(pos,Blocks.AIR.defaultBlockState(),3);}});
  // The Ender Dragon: added to a world, found by its parts, hurt through one, removed.
  test("dragon.add",()->{dragon=EntityTypes.ENDER_DRAGON.create(level,EntitySpawnReason.COMMAND);dragon.snapTo(8,120,8);
   boolean added=level.addFreshEntity(dragon);var part=dragon.getSubEntities()[0];
   return expect(added&&level.getEntityOrPart(part.getId())==part,"added="+added+" part lookup="+level.getEntityOrPart(part.getId()));});
 }
 private static void second(){
  ServerLevel level=server.overworld();
  test("dragon.hurt",()->{if(dragon==null||!dragon.isAddedToLevel())return "no dragon in the world";FakePlayer attacker=new FakePlayer(level,new GameProfile(UUID.fromString("0f3a6c2e-8b1d-4e5f-9a7b-3c2d1e0f4a5b"),"DragonProbe"));
   float before=dragon.getHealth();boolean hurt=dragon.getSubEntities()[0].hurtServer(level,level.damageSources().playerAttack(attacker),10f);
   return expect(hurt&&dragon.getHealth()<before,"hurt="+hurt+" health "+before+" -> "+dragon.getHealth());});
  test("dragon.remove",()->{if(dragon==null)return "no dragon";dragon.discard();return expect(level.getEntityOrPart(dragon.getSubEntities()[0].getId())==null,"a part is still found after removal");});
  // A Fabric mod's fluids (canary/everyday-actions/fabric-fluids): goo in no fluid tag, brine in minecraft:water. A pig
  // stands in each; NeoForge's entity code asks the fluid's NeoForge type, which a Fabric fluid does not declare. Last,
  // in the tick that reports: without the repair the fluid's own scheduled ticks throw on the next world tick.
  for(String name:new String[]{"goo","brine"})test(name.equals("goo")?"fluid.untagged":"fluid.water",()->{
   var fluid=BuiltInRegistries.FLUID.getValue(net.minecraft.resources.Identifier.fromNamespaceAndPath("forbricgoo",name));
   if(fluid==null||fluid==net.minecraft.world.level.material.Fluids.EMPTY)return "the Fabric fluid mod is not loaded";
   BlockPos pos=new BlockPos(name.equals("goo")?10:14,100,10);var pig=EntityTypes.PIG.create(level,EntitySpawnReason.COMMAND);
   try{level.setBlock(pos.below(),Blocks.STONE.defaultBlockState(),3);level.setBlock(pos,fluid.defaultFluidState().createLegacyBlock(),3);level.setBlock(pos.above(),fluid.defaultFluidState().createLegacyBlock(),3);
    pig.snapTo(pos.getX()+0.5,pos.getY(),pos.getZ()+0.5);level.addFreshEntity(pig);
    for(int i=0;i<5;i++)pig.tick();
    boolean water=name.equals("brine");
    return expect(pig.isInWater()==water&&pig.isUnderWater()==water,"a pig in "+name+": inWater="+pig.isInWater()+" underWater="+pig.isUnderWater());}
   finally{pig.discard();level.setBlock(pos,Blocks.AIR.defaultBlockState(),3);level.setBlock(pos.above(),Blocks.AIR.defaultBlockState(),3);}});
 }
 private static void finish(){
  Map<String,Object> out=new LinkedHashMap<>();out.put("phase",System.getProperty("forbric.everydayPhase"));out.put("cases",CASES);
  out.put("pass",CASES.stream().allMatch(c->Boolean.TRUE.equals(c.get("pass"))));
  try{Files.writeString(Path.of(System.getProperty("forbric.everydayProbe")),new GsonBuilder().setPrettyPrinting().create().toJson(out));}catch(Exception e){throw new IllegalStateException(e);}
  System.out.println("[EverydayActions] RESULT "+CASES);
  server.halt(false);
 }
}
