package forbric.enchantment;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Stream;
import com.google.gson.GsonBuilder;
import com.mojang.authlib.GameProfile;
import net.fabricmc.fabric.api.item.v1.*;
import net.fabricmc.fabric.api.util.TriState;
import net.minecraft.core.*;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.*;
import net.minecraft.world.item.enchantment.*;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.level.storage.loot.*;
import net.minecraft.world.level.storage.loot.functions.*;
import net.minecraft.world.level.storage.loot.parameters.LootContextParamSets;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.registries.DeferredRegister;

@Mod("forbricenchantment")
public final class EnchantmentProbe {
 private static final DeferredRegister<Item> ITEMS=DeferredRegister.create(Registries.ITEM,"forbricenchantment");
 private static final java.util.function.Supplier<NativeItem> NATIVE=ITEMS.register("native",()->new NativeItem(properties("native")));
 private static final java.util.function.Supplier<CustomItem> CUSTOM=ITEMS.register("custom",()->new CustomItem(properties("custom")));
 private static MinecraftServer server;private static FakePlayer player;private static Path root;private static String phase,nonce,mode,route;
 private static ItemStack current;private static Holder<Enchantment> enchantment;private static int events,supports,primary,custom;private static EnchantingContext observed;
 private static final List<Map<String,Object>> cases=new ArrayList<>();
 private static Item.Properties properties(String name){return new Item.Properties().setId(ResourceKey.create(Registries.ITEM,Identifier.fromNamespaceAndPath("forbricenchantment",name))).enchantable(10);}
 public static class NativeItem extends Item {
  public NativeItem(Properties properties){super(properties);}
  @Override public boolean supportsEnchantment(ItemStack stack,Holder<Enchantment> enchantment){supports++;return !"allow".equals(mode)&&!"custom".equals(mode);}
  @Override public boolean isPrimaryItemFor(ItemStack stack,Holder<Enchantment> enchantment){primary++;return !"allow".equals(mode)&&!"custom".equals(mode);}
 }
 public static final class CustomItem extends NativeItem implements FabricItem {
  public CustomItem(Item.Properties properties){super(properties);}
  @Override public boolean canBeEnchantedWith(ItemStack stack,Holder<Enchantment> enchantment,EnchantingContext context){custom++;return true;}
 }
 public EnchantmentProbe(IEventBus bus){
  ITEMS.register(bus);
  EnchantmentEvents.ALLOW_ENCHANTING.register((holder,stack,context)->{if(stack!=current)return TriState.DEFAULT;events++;observed=context;if(!holder.is(enchantment.unwrapKey().orElseThrow()))throw new IllegalStateException("wrong enchantment input");return switch(mode){case "allow"->TriState.TRUE;case "deny"->TriState.FALSE;default->TriState.DEFAULT;};});
  NeoForge.EVENT_BUS.addListener(ServerStartedEvent.class,e->{try{root=Path.of(System.getProperty("forbric.enchantRoot")).toAbsolutePath().normalize();nonce=System.getProperty("forbric.enchantNonce");phase=System.getProperty("forbric.enchantPhase");require(Files.readString(root.resolve(".m38-owned")).equals(nonce),"world owner");require(e.getServer().getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize().equals(root.resolve("world")),"wrong world");server=e.getServer();}catch(Exception failure){throw new IllegalStateException(failure);}});
  NeoForge.EVENT_BUS.addListener(ServerTickEvent.Post.class,e->{if(e.getServer()!=server)return;MinecraftServer running=server;server=null;run(running);});
 }
 private static void run(MinecraftServer running){try{
  require(net.minecraft.locale.Language.getInstance().getOrDefault("forbric.lang.probe").equals("Fabric language probe"),"Fabric-only mod translation absent");
  require(net.minecraft.locale.Language.getInstance().getOrDefault("item.minecraft.stick").equals("Stick"),"vanilla language resource absent");
  require(net.fabricmc.loader.api.FabricLoader.getInstance().getModContainer("minecraft").orElseThrow().findPath("version.json").isPresent(),"Minecraft container has no actual root");
  var level=running.overworld();player=new FakePlayer(level,new GameProfile(UUID.fromString("aa790f79-93fd-46fc-a615-e3ef8d29c146"),"EnchantProbe"));level.addNewPlayer(player);
  enchantment=level.registryAccess().lookupOrThrow(Registries.ENCHANTMENT).getOrThrow(Enchantments.SHARPNESS);
  for(String selectedRoute:List.of("command","loot","table"))for(String selectedMode:List.of("pass","deny","allow","custom")){
   route=selectedRoute;mode=selectedMode;events=supports=primary=custom=0;observed=null;current=new ItemStack(mode.equals("custom")?CUSTOM.get():NATIVE.get());
   boolean pass=false;String detail="";boolean changed=false;
   try{
    if(route.equals("command")){
     player.setItemInHand(InteractionHand.MAIN_HAND,current);
     running.getCommands().performPrefixedCommand(running.createCommandSourceStack().withEntity(player),"enchant @s minecraft:sharpness 1");
     changed=EnchantmentHelper.getItemEnchantmentLevel(enchantment,current)>0;
    }else if(route.equals("loot")){
     LootContext context=new LootContext.Builder(new LootParams.Builder(level).create(LootContextParamSets.EMPTY)).withOptionalRandomSeed(38).create(Optional.empty());
     ItemStack result=EnchantRandomlyFunction.randomEnchantment().withEnchantment(enchantment).build().apply(current,context);
     changed=EnchantmentHelper.getItemEnchantmentLevel(enchantment,result)>0;
    }else changed=!EnchantmentHelper.getAvailableEnchantmentResults(20,current,Stream.of(enchantment)).isEmpty();
    require(changed!=mode.equals("deny"),"game result ignored selected opinion");require(events==1,"Fabric event count="+events);
    require(observed==(route.equals("table")?EnchantingContext.PRIMARY:EnchantingContext.ACCEPTABLE),"wrong enchanting context");
    if(mode.equals("pass"))require((route.equals("table")?primary:supports)==1,"native default was bypassed or repeated");
    else require(primary==0&&supports==0,"decided Fabric opinion unexpectedly called native fallback");
    require(custom==(mode.equals("custom")?1:0),"Fabric item override count differs");pass=true;
   }catch(Throwable failure){detail=failure.toString();failure.printStackTrace();}
   Map<String,Object> row=new LinkedHashMap<>();row.put("name",route+"-"+mode);row.put("pass",pass);row.put("result",changed);row.put("events",events);row.put("nativeSupports",supports);row.put("nativePrimary",primary);row.put("customItem",custom);row.put("context",String.valueOf(observed));row.put("detail",detail);cases.add(row);System.out.println("[M38Enchant] "+(pass?"PASS":"FAIL")+" "+row);
  }
 }catch(Throwable failure){failure.printStackTrace();}finally{try{
  if(player!=null)running.overworld().removePlayerImmediately(player,Entity.RemovalReason.DISCARDED);
  Map<String,Object> result=new LinkedHashMap<>();result.put("nonce",nonce);result.put("phase",phase);result.put("cases",cases);result.put("pass",cases.size()==12&&cases.stream().allMatch(c->Boolean.TRUE.equals(c.get("pass"))));Files.writeString(root.resolve("probe.json"),new GsonBuilder().setPrettyPrinting().create().toJson(result));
 }catch(Exception failure){failure.printStackTrace();}running.halt(false);}}
 private static void require(boolean ok,String message){if(!ok)throw new IllegalStateException(message);}
}
