package forbric.entitycallbacks;

import java.nio.file.*;
import java.util.*;
import com.google.gson.GsonBuilder;
import com.mojang.authlib.GameProfile;
import net.fabricmc.fabric.api.entity.event.v1.*;
import net.fabricmc.fabric.api.entity.event.v1.effect.ServerMobEffectEvents;
import net.fabricmc.fabric.api.util.EventResult;
import net.minecraft.core.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Unit;
import net.minecraft.world.effect.*;
import net.minecraft.world.entity.*;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.monster.zombie.Zombie;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.gameevent.GameEvent;
import net.minecraft.world.level.*;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.phys.AABB;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.*;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.event.VanillaGameEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/** Executes actual world/entity methods. It subscribes to public Fabric events; it never posts them. */
@Mod("forbricentitycallbacks")
public final class EntityCallbacks {
 private static final net.neoforged.neoforge.registries.DeferredRegister<Block> BLOCKS=net.neoforged.neoforge.registries.DeferredRegister.create(net.minecraft.core.registries.Registries.BLOCK,"forbricentitycallbacks");
 private static final java.util.function.Supplier<NativeBed> NATIVE_BED=BLOCKS.register("native_bed",()->new NativeBed(net.minecraft.world.level.block.state.BlockBehaviour.Properties.of().setId(net.minecraft.resources.ResourceKey.create(net.minecraft.core.registries.Registries.BLOCK,net.minecraft.resources.Identifier.fromNamespaceAndPath("forbricentitycallbacks","native_bed")))));
 private static FakePlayer player; private static Zombie monster;
 private static MinecraftServer server; private static ServerLevel level;
 private static final BlockPos BED=new BlockPos(8,80,8);
 private static String phase,nonce; private static Path root;
 private static int ticks,adds,removes,early,allow,custom,customTick,glideEvents,occupation,nearby;
 private static boolean beforeRemoval,vanillaNearby,originalStone;
 private static MobEffectInstance effect;
 private static String mode="";
 private static final List<Map<String,Object>> cases=new ArrayList<>();
 public EntityCallbacks(net.neoforged.bus.api.IEventBus bus){
  BLOCKS.register(bus);
  ServerMobEffectEvents.BEFORE_ADD.register((value,entity,context)->{if(entity==player&&mode.equals("effect-add")){adds++;require(value==effect,"before-add input identity");value.update(new MobEffectInstance(MobEffects.SPEED,400));}});
  ServerMobEffectEvents.ALLOW_EARLY_REMOVE.register((value,entity,context)->{if(entity!=player||!mode.equals("effect-clear-veto"))return true;early++;return !value.is(MobEffects.SPEED);});
  ServerMobEffectEvents.BEFORE_REMOVE.register((value,entity,context)->{if(entity==player&&mode.equals("effect-remove")){removes++;beforeRemoval=entity.hasEffect(value.getEffect());}});
  EntityElytraEvents.ALLOW.register(entity->{if(entity!=player)return true;allow++;return !mode.equals("glide-deny");});
  EntityElytraEvents.CUSTOM.register((entity,tick)->{if(entity!=player)return false;custom++;if(tick)customTick++;return mode.startsWith("glide-custom")||mode.equals("glide-boolean")||mode.equals("glide-tick");});
  NeoForge.EVENT_BUS.addListener(VanillaGameEvent.class,e->{if(e.getCause()==player&&e.getVanillaEvent().is(GameEvent.ELYTRA_GLIDE.key()))glideEvents++;});
  EntitySleepEvents.SET_BED_OCCUPATION_STATE.register((entity,pos,state,occupied)->{if(entity!=player)return false;occupation++;if(mode.equals("bed-nonbed"))originalStone=state.is(Blocks.STONE);return mode.equals("bed-handled")||mode.equals("bed-custom-handled");});
  EntitySleepEvents.ALLOW_BED.register((entity,pos,state,vanilla)->entity==player&&mode.equals("bed-nonbed")?EventResult.ALLOW:EventResult.PASS);
  EntitySleepEvents.ALLOW_NEARBY_MONSTERS.register((p,pos,vanilla)->{if(p!=player||!mode.equals("nearby-monsters"))return EventResult.PASS;nearby++;vanillaNearby=vanilla;return EventResult.ALLOW;});
  NeoForge.EVENT_BUS.addListener(ServerStartedEvent.class,e->{try{
   root=Path.of(System.getProperty("forbric.entityRoot")).toAbsolutePath().normalize();nonce=System.getProperty("forbric.entityNonce");phase=System.getProperty("forbric.entityPhase");
   require(Files.readString(root.resolve(".m37-owned")).trim().equals(nonce),"world ownership");
   require(e.getServer().getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize().equals(root.resolve("world")),"wrong world");
   server=e.getServer();level=server.overworld();
  }catch(Exception failure){throw new IllegalStateException(failure);}});
  NeoForge.EVENT_BUS.addListener(ServerTickEvent.Post.class,e->{if(e.getServer()!=server)return;try{
   if(++ticks==1)prepare();
   if(ticks<20)return;
   if(level.getEntitiesOfClass(Monster.class,new AABB(BED).inflate(8,5,8)).isEmpty()&&ticks<100)return;
   run();finish();
  }catch(Throwable failure){cases.add(row("driver",false,failure.toString()));finish();}});
 }
 private static void prepare(){
  level.getChunk(BED);level.setChunkForced(0,0,true);server.getCommands().performPrefixedCommand(server.createCommandSourceStack(),"time set night");
  for(int x=0;x<16;x++)for(int z=0;z<16;z++)level.setBlock(new BlockPos(x,79,z),Blocks.STONE.defaultBlockState(),2);
  player=new FakePlayer(level,new GameProfile(UUID.fromString("0bc5b5a2-409f-4ad3-b21c-0114de997039"),"EntityProbe"));
  player.setGameMode(GameType.SURVIVAL);player.snapTo(9,80,8);player.setNoGravity(true);player.setInvulnerable(true);level.addNewPlayer(player);
  monster=EntityTypes.ZOMBIE.create(level,EntitySpawnReason.COMMAND);require(monster!=null,"zombie creation");monster.snapTo(10,80,8);monster.setNoAi(true);monster.setInvulnerable(true);require(level.addFreshEntity(monster),"zombie insertion");
 }
 private static void run()throws Exception{
  test("effect-add",()->{effect=new MobEffectInstance(MobEffects.SPEED,200);player.forceAddEffect(effect,null);require(adds==1&&player.getEffect(MobEffects.SPEED)==effect&&effect.getDuration()==400,"effect callback/count/updated duration");});
  test("effect-remove",()->{player.forceAddEffect(new MobEffectInstance(MobEffects.SPEED,200),null);require(player.removeAllEffects(),"native removal result");require(removes==1&&beforeRemoval&&!player.hasEffect(MobEffects.SPEED),"before-remove did not precede real removal");});
  test("effect-clear-veto",()->{player.forceAddEffect(new MobEffectInstance(MobEffects.SPEED,200),null);player.forceAddEffect(new MobEffectInstance(MobEffects.HASTE,200),null);
   require(player.removeAllEffects(),"native removal result");require(early==2&&player.hasEffect(MobEffects.SPEED)&&!player.hasEffect(MobEffects.HASTE),"Fabric clear-all veto did not keep the vetoed effect and only it");});
  test("glide-deny",()->{flight(1);require(!player.tryToStartFallFlying()&&!player.isFallFlying()&&allow==1&&custom==0,"Fabric veto did not prevent native allowed flight");});
  test("glide-custom",()->{flight(0);require(player.tryToStartFallFlying()&&player.isFallFlying()&&allow==1&&custom==1,"Fabric custom flight was not consumed");});
  test("glide-boolean",()->{flight(0);var method=LivingEntity.class.getDeclaredMethod("canGlide",boolean.class);method.setAccessible(true);require(Boolean.TRUE.equals(method.invoke(player,false))&&allow==1&&custom==1,"equipment branch did not consult custom flight");});
  // Glider-less custom flight at a damage tick: Fabric's flight tick is asked, claims it, and the tick ends there —
  // no slot is chosen and no glide game event, as natively.
  test("glide-tick",()->{flight(0);require(player.getItemBySlot(EquipmentSlot.CHEST).isEmpty(),"no glider item");
   require(player.tryToStartFallFlying()&&player.isFallFlying(),"custom flight did not start");glideTick();
   require(player.isFallFlying()&&customTick==1&&custom==3&&allow==3&&glideEvents==0,"Fabric's flight tick was not asked for custom flight without a glider item");});
  // A real elytra at a damage tick: the tick is asked (and declines), the elytra wears and the glide event is heard.
  test("glide-tick-native",()->{flight(1);player.setItemSlot(EquipmentSlot.CHEST,new ItemStack(Items.ELYTRA));
   require(player.tryToStartFallFlying()&&player.isFallFlying(),"native flight did not start");glideTick();
   require(player.isFallFlying()&&customTick==1&&player.getItemBySlot(EquipmentSlot.CHEST).getDamageValue()==1&&glideEvents==1,
    "native glide tick lost its damage, its game event or Fabric's tick question");});
  test("bed-native",()->{bed();player.startSleeping(BED);require(occupation==1&&occupied(),"native bed occupation/callback");player.stopSleeping();require(occupation==2&&!occupied(),"native wake occupation/callback");});
  test("bed-handled",()->{bed();player.startSleeping(BED);require(occupation==1&&!occupied(),"handled callback did not prevent native bed write");player.stopSleeping();require(occupation==2&&!occupied(),"handled wake callback");});
  test("bed-nonbed",()->{level.setBlock(BED,Blocks.STONE.defaultBlockState(),2);player.startSleeping(BED);require(occupation==1&&originalStone&&level.getBlockState(BED).is(Blocks.STONE),"Fabric non-bed was overwritten or context differed");player.stopSleeping();require(occupation==2&&level.getBlockState(BED).is(Blocks.STONE),"non-bed wake was overwritten");});
  test("bed-custom-native",()->{NativeBed block=NATIVE_BED.get();block.writes=0;level.setBlock(BED,block.defaultBlockState(),2);player.startSleeping(BED);require(occupation==1&&block.writes==1&&block.actor==player&&level.getBlockState(BED).getValue(NativeBed.ACTIVE),"native custom bed setter or actor lost");player.stopSleeping();require(occupation==2&&block.writes==2&&!level.getBlockState(BED).getValue(NativeBed.ACTIVE),"native custom wake setter lost");});
  test("bed-custom-handled",()->{NativeBed block=NATIVE_BED.get();block.writes=0;level.setBlock(BED,block.defaultBlockState(),2);player.startSleeping(BED);require(occupation==1&&block.writes==0&&!level.getBlockState(BED).getValue(NativeBed.ACTIVE),"Fabric handled custom bed still wrote native state");player.stopSleeping();require(occupation==2&&block.writes==0&&!level.getBlockState(BED).getValue(NativeBed.ACTIVE),"Fabric handled custom wake still wrote native state");});
  test("nearby-monsters",()->{bed();player.snapTo(9,80,8);require(!level.getEntitiesOfClass(Monster.class,new AABB(BED).inflate(8,5,8)).isEmpty(),"actual monster was not visible");var result=player.startSleepInBed(BED);require(nearby==1&&!vanillaNearby&&result.right().isPresent()&&player.isSleeping(),"Fabric nearby-monster result not consumed: "+result);player.stopSleepInBed(true,true);});
 }
 /** One flight tick on a damage tick: the FakePlayer does not tick itself, so its flight step is driven directly. */
 private static void glideTick()throws Exception{
  var ticksField=LivingEntity.class.getDeclaredField("fallFlyTicks");ticksField.setAccessible(true);ticksField.setInt(player,19);glideEvents=0;
  var step=LivingEntity.class.getDeclaredMethod("updateFallFlying");step.setAccessible(true);step.invoke(player);
 }
 private static void flight(double nativeAttribute){player.stopFallFlying();player.setOnGround(false);player.setDeltaMovement(0,0,0);player.getAttribute(NeoForgeMod.GLIDING_FLIGHT).setBaseValue(nativeAttribute);}
 private static void bed(){
  player.stopSleeping();occupation=0;player.stopFallFlying();player.snapTo(9,80,8);
  var state=Blocks.BED.red().defaultBlockState().setValue(BedBlock.FACING,Direction.NORTH).setValue(BedBlock.OCCUPIED,false);
  level.setBlock(BED,state.setValue(BedBlock.PART,BedPart.FOOT),2);level.setBlock(BED.north(),state.setValue(BedBlock.PART,BedPart.HEAD),2);
 }
 private static boolean occupied(){return level.getBlockState(BED).getValue(BedBlock.OCCUPIED);}
 @FunctionalInterface private interface Probe{void run()throws Exception;}
 private static void test(String name,Probe probe){mode=name;adds=removes=early=allow=custom=customTick=glideEvents=occupation=nearby=0;beforeRemoval=vanillaNearby=originalStone=false;
  boolean pass=false;String detail="";try{probe.run();pass=true;}catch(Throwable failure){detail=failure.toString();failure.printStackTrace();}
  Map<String,Object> result=row(name,pass,detail);result.put("adds",adds);result.put("removes",removes);result.put("early",early);result.put("allow",allow);result.put("custom",custom);result.put("customTick",customTick);result.put("glideEvents",glideEvents);result.put("occupation",occupation);result.put("nearby",nearby);cases.add(result);
  System.out.println("[M37Entity] "+(pass?"PASS":"FAIL")+" "+result);mode="";player.stopFallFlying();player.stopSleeping();player.removeAllEffects();player.setItemSlot(EquipmentSlot.CHEST,ItemStack.EMPTY);
 }
 private static Map<String,Object> row(String name,boolean pass,String detail){Map<String,Object> r=new LinkedHashMap<>();r.put("name",name);r.put("pass",pass);r.put("detail",detail);return r;}
 private static void require(boolean condition,String detail){if(!condition)throw new IllegalStateException(detail);}
 private static void finish(){try{
  if(monster!=null)monster.discard();if(player!=null)level.removePlayerImmediately(player,Entity.RemovalReason.DISCARDED);if(level!=null)level.setChunkForced(0,0,false);
  Map<String,Object> r=new LinkedHashMap<>();r.put("phase",phase);r.put("nonce",nonce);r.put("pass",cases.size()==14&&cases.stream().allMatch(c->Boolean.TRUE.equals(c.get("pass"))));r.put("cases",cases);r.put("ticks",ticks);
  Files.writeString(root.resolve("probe.json"),new GsonBuilder().setPrettyPrinting().create().toJson(r));
 }catch(Exception failure){failure.printStackTrace();}finally{MinecraftServer stop=server;server=null;if(stop!=null)stop.halt(false);}}
}
