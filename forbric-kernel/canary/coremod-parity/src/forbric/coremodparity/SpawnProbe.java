package forbric.coremodparity;

import net.minecraft.world.DifficultyInstance;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.SpawnGroupData;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.ServerLevelAccessor;

/** A real registered mob that counts how often it is finalized and with what; nothing else it does varies. */
public final class SpawnProbe extends Mob {
 public int finalizations; public SpawnGroupData finalizedWith;
 public SpawnProbe(EntityType<? extends SpawnProbe> type,Level level){super(type,level);setNoAi(true);setNoGravity(true);setInvulnerable(true);}
 @Override public SpawnGroupData finalizeSpawn(ServerLevelAccessor level,DifficultyInstance difficulty,EntitySpawnReason reason,SpawnGroupData data){
  finalizations++;finalizedWith=data;return super.finalizeSpawn(level,difficulty,reason,data);}
}
