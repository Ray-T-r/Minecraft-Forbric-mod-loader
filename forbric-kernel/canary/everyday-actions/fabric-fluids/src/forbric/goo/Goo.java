package forbric.goo;

import net.fabricmc.api.ModInitializer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.material.FlowingFluid;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.FluidState;

/**
 * Two Fabric fluids written the way any Fabric fluid mod writes them, with no NeoForge FluidType anywhere: goo in no
 * fluid tag, brine in minecraft:water (data/minecraft/tags/fluid/water.json), which is how a Fabric mod makes a fluid
 * swimmable.
 */
public final class Goo implements ModInitializer {
 @Override public void onInitialize(){
  register("goo"); register("brine");
  // Fuels the way a Fabric mod adds them in 26.2: dirt burns for 300 ticks, and carpets are excluded.
  net.fabricmc.fabric.api.registry.FuelValueEvents.BUILD.register((builder,context)->builder.add(Items.DIRT,300));
  net.fabricmc.fabric.api.registry.FuelValueEvents.EXCLUSIONS.register((builder,context)->builder.remove(net.minecraft.tags.ItemTags.WOOL_CARPETS));
 }
 static void register(String name){
  Family f=new Family();
  f.still=Registry.register(BuiltInRegistries.FLUID,Identifier.fromNamespaceAndPath("forbricgoo",name),new Source(f));
  f.flowing=Registry.register(BuiltInRegistries.FLUID,Identifier.fromNamespaceAndPath("forbricgoo","flowing_"+name),new Flowing(f));
  ResourceKey<Block> key=ResourceKey.create(Registries.BLOCK,Identifier.fromNamespaceAndPath("forbricgoo",name));
  f.block=Registry.register(BuiltInRegistries.BLOCK,key,new LiquidBlock(f.still,BlockBehaviour.Properties.of().setId(key).noCollision().strength(100f).noLootTable().liquid()){});
 }
 static final class Family { FlowingFluid still, flowing; LiquidBlock block; }
 static abstract class Base extends FlowingFluid {
  final Family f; Base(Family f){this.f=f;}
  @Override public Fluid getFlowing(){return f.flowing;}
  @Override public Fluid getSource(){return f.still;}
  @Override protected boolean canConvertToSource(ServerLevel level){return false;}
  @Override protected void beforeDestroyingBlock(LevelAccessor level,BlockPos pos,BlockState state){}
  @Override protected int getSlopeFindDistance(LevelReader level){return 4;}
  @Override protected int getDropOff(LevelReader level){return 1;}
  @Override public Item getBucket(){return Items.AIR;}
  @Override protected boolean canBeReplacedWith(FluidState state,BlockGetter level,BlockPos pos,Fluid fluid,Direction dir){return false;}
  @Override public int getTickDelay(LevelReader level){return 5;}
  @Override protected float getExplosionResistance(){return 100f;}
  @Override protected BlockState createLegacyBlock(FluidState state){return f.block.defaultBlockState().setValue(LiquidBlock.LEVEL,getLegacyLevel(state));}
  @Override public boolean isSame(Fluid fluid){return fluid==f.still||fluid==f.flowing;}
 }
 static final class Source extends Base {
  Source(Family f){super(f);}
  @Override public boolean isSource(FluidState state){return true;}
  @Override public int getAmount(FluidState state){return 8;}
 }
 static final class Flowing extends Base {
  Flowing(Family f){super(f);}
  @Override protected void createFluidStateDefinition(StateDefinition.Builder<Fluid,FluidState> builder){super.createFluidStateDefinition(builder);builder.add(LEVEL);}
  @Override public boolean isSource(FluidState state){return false;}
  @Override public int getAmount(FluidState state){return state.getValue(LEVEL);}
 }
}
