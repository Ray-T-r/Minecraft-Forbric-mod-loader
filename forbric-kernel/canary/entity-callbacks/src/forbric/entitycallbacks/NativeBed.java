package forbric.entitycallbacks;
import net.minecraft.core.*;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.*;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.*;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
/** A genuine NeoForge bed contract without vanilla's OCCUPIED property. */
public final class NativeBed extends Block {
 public static final BooleanProperty ACTIVE=BooleanProperty.create("native_occupied");
 public int writes;public LivingEntity actor;
 public NativeBed(Properties properties){super(properties);registerDefaultState(stateDefinition.any().setValue(BedBlock.FACING,Direction.NORTH).setValue(ACTIVE,false));}
 @Override protected void createBlockStateDefinition(StateDefinition.Builder<Block,BlockState> builder){builder.add(BedBlock.FACING,ACTIVE);}
 @Override public boolean isBed(BlockState state,BlockGetter level,BlockPos pos,LivingEntity entity){return true;}
 @Override public void setBedOccupied(BlockState state,Level level,BlockPos pos,LivingEntity entity,boolean occupied){writes++;actor=entity;level.setBlock(pos,state.setValue(ACTIVE,occupied),3);}
 @Override public Direction getBedDirection(BlockState state,LevelReader level,BlockPos pos){return Direction.NORTH;}
}
